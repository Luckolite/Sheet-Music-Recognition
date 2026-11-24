// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;

/** Captures raw printed bows into explicit physical-page source identities. */
final class PrintedSlurPageCapture {
    private static final int MAX_FRAMES = 512;
    private static final long MAX_SCAN_PIXELS = 128L * 1024 * 1024;

    record Frame<T extends PrintedSlurOwners.Location>(
            int left, int top, int right, int bottom, StaffPitchTrack track, List<T> heads) {
        Frame {
            Objects.requireNonNull(track);
            heads = List.copyOf(heads);
            if (left < 0 || top < 0 || right <= left || bottom <= top || !track.verified())
                throw new IllegalArgumentException(
                        "Printed arc needs a verified bounded staff frame");
        }
    }

    record Declined(
            String id,
            Frame<? extends PrintedSlurOwners.Location> frame,
            PrintedSlurContours.Bow bow,
            PrintedSlurOwners.Status status) {}

    record Result(
            ScoreSlurSource source,
            Map<PrintedSlurOwners.Status, Integer> statuses,
            boolean limitReached,
            List<Declined> declined,
            List<ScoreExpressiveEvent> pageEdges) {
        Result(
                ScoreSlurSource source,
                Map<PrintedSlurOwners.Status, Integer> statuses,
                boolean limitReached) {
            this(source, statuses, limitReached, List.of());
        }

        Result(ScoreSlurSource source, Map<PrintedSlurOwners.Status, Integer> statuses,
                boolean limitReached, List<Declined> declined) {
            this(source, statuses, limitReached, declined, List.of());
        }
        Result {
            Objects.requireNonNull(source);
            statuses = Map.copyOf(statuses);
            declined = List.copyOf(declined);
            pageEdges = List.copyOf(pageEdges);
        }
    }

    private PrintedSlurPageCapture() {}

    static Result capture(
            int physicalPage,
            int noteCount,
            byte[] gray,
            byte[] labels,
            int width,
            int height,
            List<? extends Frame<? extends PrintedSlurOwners.Location>> frames,
            Set<PrintedSlurOwners.Tie> retainedTies) {
        return captureInternal(
                physicalPage,
                noteCount,
                gray,
                labels,
                width,
                height,
                frames,
                retainedTies,
                false,
                null, List.of());
    }

    static Result captureGeometry(
            int physicalPage,
            int noteCount,
            byte[] gray,
            byte[] labels,
            int width,
            int height,
            List<? extends Frame<? extends PrintedSlurOwners.Location>> frames) {
        return captureInternal(
                physicalPage, noteCount, gray, labels, width, height, frames, Set.of(), true, null, List.of());
    }

    static Result capture(
            int physicalPage,
            int noteCount,
            byte[] gray,
            byte[] labels,
            int width,
            int height,
            List<? extends Frame<? extends PrintedSlurOwners.Location>> frames,
            Set<PrintedSlurOwners.Tie> retainedTies,
            List<ScoreNoteEvent> sourceNotes) {
        return captureInternal(
                physicalPage,
                noteCount,
                gray,
                labels,
                width,
                height,
                frames,
                retainedTies,
                false,
                sourceNotes, List.of());
    }

    static Result captureGeometry(
            int physicalPage,
            int noteCount,
            byte[] gray,
            byte[] labels,
            int width,
            int height,
            List<? extends Frame<? extends PrintedSlurOwners.Location>> frames,
            List<ScoreNoteEvent> sourceNotes) {
        return captureInternal(
                physicalPage,
                noteCount,
                gray,
                labels,
                width,
                height,
                frames,
                Set.of(),
                true,
                sourceNotes, List.of());
    }

    static Result capture(
            int page, int count, byte[] gray, byte[] labels, int width, int height,
            List<? extends Frame<? extends PrintedSlurOwners.Location>> frames,
            Set<PrintedSlurOwners.Tie> ties, List<ScoreNoteEvent> notes,
            List<MeasureRegion> measures) {
        return captureInternal(page, count, gray, labels, width, height, frames,
                ties, false, notes, measures);
    }

    static Result captureGeometry(
            int page, int count, byte[] gray, byte[] labels, int width, int height,
            List<? extends Frame<? extends PrintedSlurOwners.Location>> frames,
            List<ScoreNoteEvent> notes, List<MeasureRegion> measures) {
        return captureInternal(page, count, gray, labels, width, height, frames,
                Set.of(), true, notes, measures);
    }
    private static Result captureInternal(
            int physicalPage,
            int noteCount,
            byte[] gray,
            byte[] labels,
            int width,
            int height,
            List<? extends Frame<? extends PrintedSlurOwners.Location>> frames,
            Set<PrintedSlurOwners.Tie> retainedTies,
            boolean geometryOnly,
            List<ScoreNoteEvent> sourceNotes, List<MeasureRegion> measures) {
        if (physicalPage < 0
                || noteCount < 0
                || noteCount > ScoreSlurSource.MAX_NOTES
                || width <= 0
                || height <= 0
                || gray == null
                || gray.length != (long) width * height
                || labels != null && labels.length != gray.length)
            throw new IllegalArgumentException("Invalid printed page source");
        Objects.requireNonNull(frames);
        Objects.requireNonNull(retainedTies);
        var seen = new HashSet<Integer>();
        long pixels = 0;
        // Validate all ownership before doing any scanning or publishing a partial source.
        for (var frame : frames) {
            if (frame.right() >= width || frame.bottom() >= height)
                throw new IllegalArgumentException("Staff frame lies outside source image");
            pixels =
                    Math.addExact(
                            pixels,
                            (long) (frame.right() - frame.left() + 1)
                                    * (frame.bottom() - frame.top() + 1));
            for (var head : frame.heads()) {
                if (!geometryOnly && !(head instanceof PrintedSlurOwners.Head))
                    throw new IllegalArgumentException(
                            "Musical classification requires explicit timed heads");
                if (head.owner() >= noteCount
                        || head.x() >= width
                        || head.y() >= height
                        || !seen.add(head.owner()))
                    throw new IllegalArgumentException(
                            "Printed head lacks unique page-local ownership");
            }
        }
        for (var tie : retainedTies)
            if (tie.startOwner() >= noteCount || tie.endOwner() >= noteCount)
                throw new IllegalArgumentException("Retained tie lies outside source page");
        if (frames.size() > MAX_FRAMES || pixels > MAX_SCAN_PIXELS)
            return new Result(ScoreSlurSource.empty(noteCount), Map.of(), true);

        if (sourceNotes != null && sourceNotes.size() != noteCount)
            throw new IllegalArgumentException("Beam ownership source count disagrees");
        var beam =
                sourceNotes == null ? null : new ObservedStemBeam(sourceNotes, gray, width, height);
        var marks =
                sourceNotes == null
                        ? null
                        : new PrintedCurveMarkRoles(sourceNotes, gray, width, height);
        var joints =
                sourceNotes == null
                        ? null
                        : new PrintedCurveJointRoles(sourceNotes, gray, width, height);
        var spans = new ArrayList<ScoreSlurPages.Span>();
        var candidates = new ArrayList<ScoreSlurPages.Span>();
        var statuses =
                new EnumMap<PrintedSlurOwners.Status, Integer>(PrintedSlurOwners.Status.class);
        int ordinal = 0;
        int literalOrdinal = 0;
        int markOrdinal = 0;
        int shortRailOrdinal = 0;
        int jointOrdinal = 0;
        int asymmetricOrdinal = 0;
        var pieces = new ArrayList<PrintedSystemSlurJoins.Observed>();
        var declined = new ArrayList<Declined>();
        boolean retainDeclined = sourceNotes != null;
        boolean admitJoins = sourceNotes != null;
        long[] localWindowPixels = {2_000_000};
        long[] railAndTipPixels = {600_000};
        long[] accidentalSlurPixels = {800_000};
        long[] junctionSlurPixels = {250_000};
        for (var frame : frames) {
            var hints =
                    PrintedSlurOwners.headEvidence(
                            frame.heads(), gray, width, height, frame.track());
            if (marks != null) hints = marks.wrap(hints, frame.heads(), frame.track());
            if (joints != null) hints = joints.wrap(hints, frame.heads(), frame.track());
            var raw =
                    PrintedSlurContours.find(
                            gray,
                            labels,
                            width,
                            height,
                            frame.left(),
                            frame.top(),
                            frame.right(),
                            frame.bottom(),
                            frame.track(),
                            null,
                            hints);
            if (raw.requiredLimitReached())
                return new Result(ScoreSlurSource.empty(noteCount), statuses, true);
            var frameBows = new ArrayList<>(raw.bows());
            if (sourceNotes != null)
                frameBows.addAll(octaveSlursInLocalWindows(
                        gray, labels, width, height, frame, sourceNotes, hints, raw.bows(),
                        raw.optionalLimitReached() ? null : localWindowPixels));
            if (labels != null && sourceNotes != null && !raw.optionalLimitReached())
                frameBows.addAll(terminalLedgerSlurs(
                        gray, labels, width, height, frame, sourceNotes, hints, frameBows,
                        localWindowPixels));
            if (sourceNotes != null && !raw.optionalLimitReached())
                frameBows.addAll(DetachedLedgerSlurComponents.capture(
                        gray, width, height, frame, sourceNotes, frameBows, localWindowPixels));
            var below = sourceNotes != null && !raw.optionalLimitReached()
                    ? DetachedLedgerSlurComponents.belowStaff(
                            gray, width, height, frame, sourceNotes, measures, frameBows, localWindowPixels)
                    : Map.<PrintedSlurContours.Bow, DetachedLedgerSlurComponents.Owners>of();
            frameBows.addAll(below.keySet());
            var above = sourceNotes != null && !raw.optionalLimitReached()
                    ? DetachedLedgerSlurComponents.aboveStaff(
                            gray, width, height, frame, sourceNotes, frameBows, localWindowPixels,
                            railAndTipPixels, accidentalSlurPixels, junctionSlurPixels)
                    : Map.<PrintedSlurContours.Bow, DetachedLedgerSlurComponents.Owners>of();
            frameBows.addAll(above.keySet());
            var complete = sourceNotes != null
                    ? PrintedLiteralOctaveSlurs.capture(
                            gray, labels, width, height, frame, sourceNotes, hints, frameBows)
                    : List.<PrintedSlurContours.Bow>of();
            frameBows.addAll(complete);
            for (var bow : frameBows) {
                boolean literal = complete.contains(bow);
                String id =
                        literal
                                ? "printed-page-" + physicalPage + "-literal-octave-arc-" + literalOrdinal++
                                : raw.jointBows().contains(bow)
                                ? "printed-page-" + physicalPage + "-joint-arc-" + jointOrdinal++
                                : raw.shortRailBows().contains(bow)
                                        ? "printed-page-"
                                                + physicalPage
                                                + "-short-rail-arc-"
                                                + shortRailOrdinal++
                                        : marks != null && marks.ownsRivalCurve(bow)
                                                ? "printed-page-"
                                                        + physicalPage
                                                        + "-mark-arc-"
                                                        + markOrdinal++
                                                : bow.asymmetric()
                                                        ? "printed-page-"
                                                                + physicalPage
                                                                + "-asymmetric-arc-"
                                                                + asymmetricOrdinal++
                                                        : "printed-page-"
                                                                + physicalPage
                                                                + "-arc-"
                                                                + ordinal++;
                var bound = literal
                        ? PrintedSlurOwners.literalOctaveBinding(
                                id, physicalPage, bow, frame.heads(), gray, width, height, frame.track())
                        : PrintedSlurOwners.bind(
                                id,
                                physicalPage,
                                bow,
                                frame.heads(),
                                gray,
                                width,
                                height,
                                frame.track());
                var provedOwners = bow.above() ? above.get(bow) : below.get(bow);
                if (provedOwners != null && bound.span().isEmpty())
                    bound = PrintedSlurOwners.geometryBinding(
                            id, physicalPage, bow, provedOwners.first(), provedOwners.second(), width, height);
                if (sourceNotes != null && bound.status() == PrintedSlurOwners.Status.NO_END_OWNER) {
                    var cell = wideBeamedDyadPixelCells(id, physicalPage, bow, frame,
                            gray, width, height, sourceNotes);
                    if (cell.span().isPresent()) bound = cell;
                }
                if (beam != null
                        && bound.span().isPresent()
                        && beam.ownsLedger(
                                bow, bound.span().orElseThrow(), frame.heads(), frame.track()))
                    continue;
                if (admitJoins && !literal) {
                    if (pieces.size() == 4096) {
                        pieces.clear();
                        admitJoins = false;
                    } else pieces.add(new PrintedSystemSlurJoins.Observed(frame, bow));
                }
                if (geometryOnly) {
                    if (beam != null
                            && bound.span().isPresent()
                            && beam.owns(
                                    bow, bound.span().orElseThrow(), frame.heads(), frame.track()))
                        continue;
                    statuses.merge(bound.status(), 1, Integer::sum);
                    if (retainDeclined
                            && (bound.status() == PrintedSlurOwners.Status.AMBIGUOUS_START
                                    || bound.status() == PrintedSlurOwners.Status.NO_START_OWNER
                                    || bound.status() == PrintedSlurOwners.Status.NO_END_OWNER)) {
                        if (declined.size() == 4096) {
                            declined.clear();
                            retainDeclined = false;
                        } else declined.add(new Declined(id, frame, bow, bound.status()));
                    }
                    bound.span().ifPresent(candidates::add);
                    continue;
                }
                var owned =
                        PrintedSlurOwners.resolveBinding(
                                bound,
                                frame.heads().stream()
                                        .map(h -> (PrintedSlurOwners.Head) h)
                                        .toList(),
                                retainedTies);
                if (beam != null
                        && owned.span().isPresent()
                        && beam.owns(bow, owned.span().orElseThrow(), frame.heads(), frame.track()))
                    continue;
                statuses.merge(owned.status(), 1, Integer::sum);
                if (retainDeclined
                        && (owned.status() == PrintedSlurOwners.Status.AMBIGUOUS_START
                                || owned.status() == PrintedSlurOwners.Status.NO_START_OWNER
                                || owned.status() == PrintedSlurOwners.Status.NO_END_OWNER)) {
                    if (declined.size() == 4096) {
                        declined.clear();
                        retainDeclined = false;
                    } else declined.add(new Declined(id, frame, bow, owned.status()));
                }
                owned.span().ifPresent(spans::add);
                if (owned.status() == PrintedSlurOwners.Status.UNPITCHED_UNRESOLVED
                        || owned.status() == PrintedSlurOwners.Status.SAME_PITCH_UNRESOLVED
                        || owned.status() == PrintedSlurOwners.Status.TIE_PITCH_CONFLICT
                        || owned.status() == PrintedSlurOwners.Status.REVERSED_CLOCK) {
                    bound.span()
                            .filter(
                                    span ->
                                            beam == null
                                                    || !beam.owns(
                                                            bow,
                                                            span,
                                                            frame.heads(),
                                                            frame.track()))
                            .ifPresent(candidates::add);
                }
            }
        }
        var pageEdges = new ArrayList<ScoreExpressiveEvent>();
        if (admitJoins) {
            var joined =
                    PrintedSystemSlurJoins.join(
                            physicalPage, pieces, frames, sourceNotes, gray, width, height,
                            8_000_000L, pageEdges, measures);
            candidates.addAll(joined);
            if (!joined.isEmpty())
                statuses.merge(
                        PrintedSlurOwners.Status.GEOMETRY_OWNED, joined.size(), Integer::sum);
        }
        if (sourceNotes != null) {
            var graceRoles = new CalibratedGraceSlurs(sourceNotes, gray, width, height);
            for (var observed : graceRoles.discover(frames)) {
                var frame = observed.frame();
                var bow = observed.bow();
                String id =
                        "printed-page-"
                                + physicalPage
                                + "-grace-cell-"
                                + observed.graceOwner()
                                + ":"
                                + observed.principalOwner();
                boolean duplicate = false;
                for (var old : spans)
                    if (old.start().noteIndex() == observed.graceOwner()
                            && old.end().noteIndex() == observed.principalOwner()
                            && old.placement()
                                    == (bow.above()
                                            ? ScoreSlurSpan.Placement.ABOVE
                                            : ScoreSlurSpan.Placement.BELOW)) duplicate = true;
                for (var old : candidates)
                    if (old.start().noteIndex() == observed.graceOwner()
                            && old.end().noteIndex() == observed.principalOwner()
                            && old.placement()
                                    == (bow.above()
                                            ? ScoreSlurSpan.Placement.ABOVE
                                            : ScoreSlurSpan.Placement.BELOW)) duplicate = true;
                if (duplicate) continue;
                if (geometryOnly) {
                    var bound =
                            PrintedSlurOwners.bindPixelCells(
                                    id,
                                    physicalPage,
                                    bow,
                                    frame.heads(),
                                    gray,
                                    width,
                                    height,
                                    frame.track());
                    if (beam != null
                            && bound.span().isPresent()
                            && beam.owns(
                                    bow, bound.span().orElseThrow(), frame.heads(), frame.track()))
                        continue;
                    statuses.merge(bound.status(), 1, Integer::sum);
                    bound.span().ifPresent(candidates::add);
                } else {
                    var owned =
                            PrintedSlurOwners.resolvePixelCells(
                                    id,
                                    physicalPage,
                                    bow,
                                    frame.heads().stream()
                                            .map(h -> (PrintedSlurOwners.Head) h)
                                            .toList(),
                                    retainedTies,
                                    gray,
                                    width,
                                    height,
                                    frame.track());
                    if (beam != null
                            && owned.span().isPresent()
                            && beam.owns(
                                    bow, owned.span().orElseThrow(), frame.heads(), frame.track()))
                        continue;
                    statuses.merge(owned.status(), 1, Integer::sum);
                    owned.span().ifPresent(spans::add);
                    if (owned.status() == PrintedSlurOwners.Status.UNPITCHED_UNRESOLVED
                            || owned.status() == PrintedSlurOwners.Status.SAME_PITCH_UNRESOLVED
                            || owned.status() == PrintedSlurOwners.Status.TIE_PITCH_CONFLICT
                            || owned.status() == PrintedSlurOwners.Status.REVERSED_CLOCK)
                        PrintedSlurOwners.bindPixelCells(
                                        id,
                                        physicalPage,
                                        bow,
                                        frame.heads(),
                                        gray,
                                        width,
                                        height,
                                        frame.track())
                                .span()
                                .ifPresent(candidates::add);
                }
            }
        }
        if (sourceNotes != null)
            addCompactStemBows(physicalPage, frames, sourceNotes, gray, width, height,
                    retainedTies, geometryOnly, spans, candidates);
        return new Result(
                ScoreSlurSource.capture(
                        spans,
                        candidates,
                        List.of(new ScoreSlurPages.Page(physicalPage, noteCount))),
                statuses,
                false,
                declined, pageEdges);
    }

    /** Recover isolated octave slurs hidden by connected ink in the whole-frame scan. */
    /**
     * Retest short, detached arcs above the noteheads proved locally beyond the sampled
     * staff domain. Excluding the lower ledger band prevents it from branching the curve.
     */
    private static List<PrintedSlurContours.Bow> terminalLedgerSlurs(
            byte[] gray, byte[] labels, int width, int height,
            Frame<? extends PrintedSlurOwners.Location> frame, List<ScoreNoteEvent> notes,
            PrintedSlurContours.HeadEvidence hints, List<PrintedSlurContours.Bow> retained,
            long[] pagePixels) {
        if (frame.heads().size() > 512) return List.of();
        var heads = frame.heads().stream()
                .sorted(Comparator.comparingDouble(PrintedSlurOwners.Location::x)).toList();
        var rescued = new ArrayList<PrintedSlurContours.Bow>();
        for (int i = 1; i < heads.size(); i++) {
            var a = heads.get(i - 1);
            var b = heads.get(i);
            if (frame.track().verifiedAt(a.x()) || frame.track().verifiedAt(b.x())) continue;
            var leftNote = notes.get(a.owner());
            var rightNote = notes.get(b.owner());
            if (a.grace() || b.grace() || leftNote.measureIndex() != rightNote.measureIndex()
                    || leftNote.staffStep() < 13 || rightNote.staffStep() < 13
                    || leftNote.stemDirection() != -1 || rightNote.stemDirection() != -1) continue;
            float gap = frame.track().at((a.x() + b.x()) * .5f)[1];
            if (b.x() - a.x() < gap * 1.7f || b.x() - a.x() > gap * 3.35f
                    || Math.abs(a.y() - b.y()) > gap * 1.1f
                    || retained.stream().anyMatch(bow -> bow.above()
                            && Math.abs(bow.start().x() - a.x()) <= gap * .65f
                            && Math.abs(bow.end().x() - b.x()) <= gap * .65f)) continue;
            int left = Math.max(frame.left(), Math.round(a.x() - gap * .45f));
            int right = Math.min(frame.right(), Math.round(b.x() + gap * .38f));
            int top = Math.max(frame.top(), Math.round(Math.min(a.y(), b.y()) - gap * 1.85f));
            int bottom = Math.min(frame.bottom(), Math.round(Math.max(a.y(), b.y()) - gap * .75f));
            if (left >= right || top >= bottom
                    || !inkAboveMidpoint(gray, width, a, b, gap, top, bottom)) continue;
            pagePixels[0] -= (long) (right - left + 1) * (bottom - top + 1);
            if (pagePixels[0] < 0) return List.copyOf(rescued);
            var local = PrintedSlurContours.find(
                    gray, labels, width, height, left, top, right, bottom, frame.track(), null, hints);
            if (local.limitReached() || local.bows().size() != 1) continue;
            var bow = local.bows().get(0);
            if (!bow.above() || bow.asymmetric() || bow.rmsInGaps() > .065f
                    || bow.bendInGaps() < .3f || local.shortRailBows().contains(bow)
                    || local.jointBows().contains(bow) || bow.start().x() <= left + 1
                    || bow.end().x() >= right - 1
                    || Math.abs(bow.start().x() - a.x()) > gap * .45f
                    || Math.abs(bow.end().x() - b.x()) > gap * .45f
                    || bow.points().size() != bow.end().x() - bow.start().x() + 1
                    || bow.points().stream().anyMatch(point -> point.y() <= top + 1
                            || point.y() >= bottom - 1
                            || (gray[Math.round(point.y()) * width + point.x()] & 255) > 205))
                continue;
            var binding = PrintedSlurOwners.bind(
                    "terminal-ledger-slur", 0, bow, frame.heads(), gray, width, height, frame.track());
            if (binding.span().isPresent()
                    && binding.span().orElseThrow().start().noteIndex() == a.owner()
                    && binding.span().orElseThrow().end().noteIndex() == b.owner())
                rescued.add(bow);
        }
        return rescued;
    }

    private static List<PrintedSlurContours.Bow> octaveSlursInLocalWindows(
            byte[] gray, byte[] labels, int width, int height,
            Frame<? extends PrintedSlurOwners.Location> frame, List<ScoreNoteEvent> notes,
            PrintedSlurContours.HeadEvidence hints, List<PrintedSlurContours.Bow> retained,
            long[] pagePixels) {
        if (frame.heads().size() > 512 || pagePixels != null && pagePixels[0] <= 0) return List.of();
        var upperHeads = upperOctaveHeads(frame, notes);
        var rescued = new ArrayList<PrintedSlurContours.Bow>();
        long remainingPixels = 640_000;
        for (int i = 1; i < upperHeads.size(); i++) {
            var a = upperHeads.get(i - 1);
            var b = upperHeads.get(i);
            if (notes.get(a.owner()).measureIndex() != notes.get(b.owner()).measureIndex()) continue;
            float gap = frame.track().at((a.x() + b.x()) * .5f)[1];
            if (b.x() - a.x() < gap * 1.55f || b.x() - a.x() > gap * 3.35f
                    || Math.abs(b.y() - a.y()) > gap * 1.65f
                    || retained.stream().anyMatch(bow -> bow.above()
                            && bow.start().x() <= a.x() + gap * .75f
                            && bow.end().x() >= b.x() - gap * .75f
                            && Math.abs(bow.start().y() - a.y()) <= gap * 2f)) continue;
            int left = Math.max(frame.left(), Math.round(a.x() - gap * 1.6f));
            int right = Math.min(frame.right(), Math.round(b.x() + gap * 1.6f));
            int top = Math.max(frame.top(), Math.round(Math.min(a.y(), b.y()) - gap * 4.15f));
            int bottom = Math.min(frame.bottom(), Math.round(Math.max(a.y(), b.y()) + gap * 1.45f));
            if (left >= right || top >= bottom
                    || !inkAboveMidpoint(gray, width, a, b, gap, top, bottom)) continue;
            long pixels = (long) (right - left + 1) * (bottom - top + 1);
            remainingPixels -= pixels;
            if (remainingPixels < 0) return List.of();
            if (pagePixels != null && (pagePixels[0] -= pixels) < 0) return List.copyOf(rescued);
            var local = PrintedSlurContours.find(
                    gray, labels, width, height, left, top, right, bottom, frame.track(), null, hints);
            if (local.limitReached()) return List.of();
            var candidates = local.bows().stream()
                    .filter(bow -> bow.above() && !bow.asymmetric()
                            && Math.abs(bow.start().x() - a.x()) <= gap * .77f
                            && Math.abs(bow.end().x() - b.x()) <= gap * .77f
                            && a.y() - bow.start().y() >= gap * .32f
                            && a.y() - bow.start().y() <= gap * 1.4f
                            && b.y() - bow.end().y() >= gap * .32f
                            && b.y() - bow.end().y() <= gap * 1.4f)
                    .toList();
            if (candidates.size() != 1) continue;
            var bow = candidates.get(0);
            if (local.shortRailBows().contains(bow) || local.jointBows().contains(bow)) continue;
            var binding = PrintedSlurOwners.bind(
                    "local-octave-slur", 0, bow, frame.heads(), gray, width, height, frame.track());
            if (binding.span().isPresent()
                    && binding.span().orElseThrow().start().noteIndex() == a.owner()
                    && binding.span().orElseThrow().end().noteIndex() == b.owner())
                rescued.add(bow);
        }
        return rescued;
    }

    /** A detached bow cap occupies the full ink pixel beyond an adjacent written dyad. */
    private static PrintedSlurOwners.Binding wideBeamedDyadPixelCells(
            String id, int page, PrintedSlurContours.Bow bow,
            Frame<? extends PrintedSlurOwners.Location> frame,
            byte[] gray, int width, int height, List<ScoreNoteEvent> notes) {
        var declined = new PrintedSlurOwners.Binding(
                PrintedSlurOwners.Status.NO_END_OWNER, Optional.empty());
        float gap = frame.track().at(bow.start().x())[1];
        float length = bow.end().x() - bow.start().x();
        if (gap < 8 || gap > 28 || !bow.above() || bow.asymmetric()
                || bow.bendInGaps() < .6f || bow.bendInGaps() > 1.1f || bow.rmsInGaps() > .055f
                || length < gap * 3.3f || length > gap * 4.25f
                || bow.points().size() < length * .90f) return declined;
        var cell = PrintedSlurOwners.bindPixelCells(id, page, bow, frame.heads(),
                gray, width, height, frame.track());
        if (cell.span().isEmpty()) return declined;
        var span = cell.span().orElseThrow();
        var old = notes.get(span.start().noteIndex());
        var next = notes.get(span.end().noteIndex());
        if (old.measureIndex() != next.measureIndex() || old.staffStep() < 12
                || Math.abs(old.staffStep() - next.staffStep()) != 1
                || !wideBeamedDyadEnd(old) || !wideBeamedDyadEnd(next)) return declined;
        PrintedSlurOwners.Location a = null, b = null;
        for (var head : frame.heads()) {
            if (head.owner() == span.start().noteIndex()) a = head;
            if (head.owner() == span.end().noteIndex()) b = head;
        }
        if (a == null || b == null || b.x() - a.x() < gap * 2.5f
                || b.x() - a.x() > gap * 4f
                || !hasWideBeamedDyadBelow(a, frame.heads(), notes, gap)
                || !hasWideBeamedDyadBelow(b, frame.heads(), notes, gap)) return declined;
        var shafts = new PrintedBeamShafts.Budget(60_000);
        var s = PrintedBeamShafts.at(gray, width, height, a.x(), a.y(), gap, shafts);
        var t = PrintedBeamShafts.at(gray, width, height, b.x(), b.y(), gap, shafts);
        return s.size() == 1 && t.size() == 1 && s.get(0).direction() == 1
                        && t.get(0).direction() == 1
                        && SystemQuarterCadence.directHarmonicRail(
                                gray, width, height, s.get(0), t.get(0), gap)
                ? cell : declined;
    }

    private static boolean wideBeamedDyadEnd(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED && note.beamCount() == 2
                && note.stemDirection() == -1 && !note.crossStaffBeam()
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL && note.harmonicSemitones() == 0
                && note.tupletDivisor() == 1 && note.augmentationDots() == 0 && !note.tiedFromPrevious();
    }

    private static boolean hasWideBeamedDyadBelow(
            PrintedSlurOwners.Location upper, List<? extends PrintedSlurOwners.Location> heads,
            List<ScoreNoteEvent> notes, float gap) {
        int members = 0;
        var top = notes.get(upper.owner());
        for (var head : heads) {
            if (head.owner() == upper.owner() || Math.abs(head.x() - upper.x()) > gap * .23f)
                continue;
            var note = notes.get(head.owner());
            if (note.measureIndex() != top.measureIndex() || note.staffIndex() != top.staffIndex()
                    || note.staffCount() != top.staffCount()) continue;
            int steps = top.staffStep() - note.staffStep();
            if (!wideBeamedDyadEnd(note) || steps < 7 || steps > 10 || ++members != 1)
                return false;
        }
        return members == 1;
    }

    static List<PrintedSlurOwners.Location> upperOctaveHeads(
            Frame<? extends PrintedSlurOwners.Location> frame, List<ScoreNoteEvent> notes) {
        if (frame.heads().size() > 512) return List.of();
        var upperHeads = new ArrayList<PrintedSlurOwners.Location>();
        for (var high : frame.heads()) {
            var upper = notes.get(high.owner());
            if (!ordinaryOctaveSlurHead(upper) || high.grace()) continue;
            float gap = frame.track().at(high.x())[1];
            int peers = 0;
            for (var low : frame.heads()) {
                var lower = notes.get(low.owner());
                if (!low.grace() && ordinaryOctaveSlurHead(lower)
                        && lower.measureIndex() == upper.measureIndex()
                        && lower.staffStep() == upper.staffStep() - 7
                        && Math.abs(high.x() - low.x()) <= gap * .18f
                        && low.y() - high.y() >= gap * 3.15f
                        && low.y() - high.y() <= gap * 3.85f) peers++;
            }
            if (peers == 1) upperHeads.add(high);
        }
        upperHeads.sort(Comparator.comparingDouble(PrintedSlurOwners.Location::x));
        return upperHeads;
    }

    private static boolean ordinaryOctaveSlurHead(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL && note.staffCount() == 1
                && note.stemDirection() == -1 && note.beamCount() == 2 && !note.crossStaffBeam();
    }

    private static boolean inkAboveMidpoint(
            byte[] gray, int width, PrintedSlurOwners.Location a, PrintedSlurOwners.Location b,
            float gap, int top, int bottom) {
        int x = Math.round((a.x() + b.x()) * .5f);
        float middle = (a.y() + b.y()) * .5f;
        int low = Math.max(top, Math.round(middle - gap * 2.8f));
        int high = Math.min(bottom, Math.round(middle - gap * .48f));
        for (int y = low; y <= high; y++)
            if ((gray[y * width + x] & 255) < 154) return true;
        return false;
    }

    private static void addCompactStemBows(
            int page, List<? extends Frame<? extends PrintedSlurOwners.Location>> frames,
            List<ScoreNoteEvent> notes, byte[] gray, int width, int height,
            Set<PrintedSlurOwners.Tie> retainedTies, boolean geometryOnly,
            List<ScoreSlurPages.Span> spans, List<ScoreSlurPages.Span> candidates) {
        var detector = new ObservedStemBeam(notes, gray, width, height);
        var recovered = new ArrayList<ScoreSlurPages.Span>();
        for (var frame : frames) {
            var heads = frame.heads().stream()
                    .sorted(Comparator.comparingDouble(PrintedSlurOwners.Location::x)).toList();
            for (int i = 0; i + 1 < heads.size(); i++) {
                var a = heads.get(i);
                var b = heads.get(i + 1);
                float gap = frame.track().at(a.x())[1];
                if (i > 0 && a.x() - heads.get(i - 1).x() < gap * .5f
                        || i + 2 < heads.size() && heads.get(i + 2).x() - b.x() < gap * .5f)
                    continue;
                boolean exists = java.util.stream.Stream.concat(spans.stream(), candidates.stream())
                        .anyMatch(s -> s.start().noteIndex() == a.owner()
                                && s.end().noteIndex() == b.owner());
                if (exists) continue;
                var found = detector.compactBowAbove(a, b, frame.track());
                if (detector.refused()) return;
                if (found.isEmpty()) continue;
                var bow = found.get();
                var span = new ScoreSlurPages.Span(
                        "printed-page-" + page + "-stem-bow-" + a.owner() + ":" + b.owner(),
                        new ScoreSlurPages.Anchor(page, a.owner()),
                        new ScoreSlurPages.Anchor(page, b.owner()),
                        ScoreSlurSpan.Placement.ABOVE, ScoreSlurSpan.LineType.SOLID,
                        List.of(new ScoreSlurSpan.ArcEvidence(page, bow.start().x() / (float) width,
                                bow.start().y() / height, bow.end().x() / (float) width,
                                bow.end().y() / height)));
                if (geometryOnly) recovered.add(span);
                else {
                    var binding = new PrintedSlurOwners.Binding(
                            PrintedSlurOwners.Status.GEOMETRY_OWNED, Optional.of(span));
                    PrintedSlurOwners.resolveBinding(binding,
                            List.of((PrintedSlurOwners.Head) a, (PrintedSlurOwners.Head) b),
                            retainedTies).span().ifPresent(recovered::add);
                }
            }
        }
        (geometryOnly ? candidates : spans).addAll(recovered);
    }
}
