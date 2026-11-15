// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Verifies three undotted, single-rail groups in a three-beat system fragment. */
final class WrappedEighthTriplets {
    private WrappedEighthTriplets() {}

    private static final class ShaftQuotaExceeded extends RuntimeException {}

    private record Ribbon(float coverage, int leftY, int rightY) {}

    private record RuleBand(int first, int[] rows, int radius) {
        boolean contains(int x, int y) {
            return Math.abs(y - rows[x - first]) <= radius;
        }
    }

    static WrappedTripletMeasure.Half half(byte[] gray, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> candidates) {
        return half(gray, width, height, bar, notes, candidates, null);
    }

    static WrappedTripletMeasure.Half half(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            List<ScoreNoteEvent> notes,
            List<Integer> candidates,
            byte[] labels) {
        if (candidates.size() != 9) return null;
        return singleRailGroups(gray, width, height, bar, notes, candidates, false, false, labels);
    }

    /** A closed six-triplet bar can precede a proved wrap even when a natural enters a dot window. */
    static WrappedTripletMeasure.Half closedNaturalPredecessor(
            byte[] gray, int width, int height, List<MeasureRegion> regions, int measure,
            List<ScoreNoteEvent> notes, List<Integer> candidates, byte[] labels) {
        if (labels == null || gray == null || labels.length != gray.length || measure < 1
                || measure + 1 >= regions.size() || candidates.size() != 18) return null;
        var bar = regions.get(measure);
        var before = regions.get(measure - 1);
        var after = regions.get(measure + 1);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float leftPocket = (bar.left() - before.right()) * width;
        float rightPocket = (after.left() - bar.right()) * width;
        if (gap < 10 || gap > 30
                || Math.abs(bar.top() - before.top()) * height > gap * .35f
                || Math.abs(bar.top() - after.top()) * height > gap * .35f
                || leftPocket < gap * .4f || leftPocket > gap * 1.6f
                || rightPocket < gap * .4f || rightPocket > gap * 1.6f) return null;
        var proof = singleRailGroups(gray, width, height, bar, notes, candidates, false, false, labels);
        if (proof == null) return null;
        var boundaries = new MeasureRegion((bar.left() + before.right()) * .5f,
                (bar.right() + after.left()) * .5f, bar.top(), bar.bottom());
        return WrappedMeasureBoundaries.confirmsClosedTripletBar(
                gray, width, height, boundaries, proof.x()[0], proof.x()[17]) ? proof : null;
    }

    /** Verify a reverse-facing mask on a genuine triplet's single rail in the following bar. */
    static boolean followingRailAlias(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            List<ScoreNoteEvent> notes,
            List<Integer> candidates) {
        if (candidates.size() != 19) return false;
        var ordered =
                candidates.stream()
                        .map(notes::get)
                        .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                        .toList();
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (!Float.isFinite(gap) || gap < 10 || gap > 30) return false;
        float left = width * bar.left(), span = width * (bar.right() - bar.left());
        int aliases = 0;
        var budget = new PrintedBeamShafts.Budget(250_000);
        for (int i = 2; i + 1 < ordered.size(); i++) {
            // The two flanks belong to the same group after dropping this mask from the count.
            if (i % 3 != 2) continue;
            var a = ordered.get(i - 2);
            var b = ordered.get(i - 1);
            var mask = ordered.get(i);
            var c = ordered.get(i + 1);
            if (a.stemDirection() != 1
                    || b.stemDirection() != 1
                    || c.stemDirection() != 1
                    || mask.stemDirection() != -1
                    || a.kind() != ScoreNoteEvent.Kind.PITCHED
                    || b.kind() != ScoreNoteEvent.Kind.PITCHED
                    || mask.kind() != ScoreNoteEvent.Kind.PITCHED
                    || c.kind() != ScoreNoteEvent.Kind.PITCHED
                    || a.beamCount() != 1
                    || b.beamCount() != 1
                    || c.beamCount() != 1
                    || mask.beamCount() != 1
                    || mask.augmentationDots() != 0
                    || mask.tiedFromPrevious()
                    || mask.boundaryTies() != 0
                    || mask.staffCount() != 1) continue;

            float ax = left + span * a.positionInMeasure(),
                    bx = left + span * b.positionInMeasure();
            float mx = left + span * mask.positionInMeasure(),
                    cx = left + span * c.positionInMeasure();
            float ay = a.pageY() * height, by = b.pageY() * height, my = mask.pageY() * height;
            float cy = c.pageY() * height;
            if (bx - ax < gap * 1.55f
                    || bx - ax > gap * 3.25f
                    || cx - bx < gap * 1.55f
                    || cx - bx > gap * 3.25f
                    || mx - bx < gap * .68f
                    || mx - bx > gap * 1.15f
                    || cx - mx < gap * 1.25f
                    || cx - mx > gap * 2.15f
                    || !(my + gap * 1.6f < cy && cy + gap * .60f < by && by + gap * .60f < ay))
                continue;
            var first = PrintedBeamShafts.at(gray, width, height, ax, ay, gap, budget);
            var last = PrintedBeamShafts.at(gray, width, height, cx, cy, gap, budget);
            if (first.size() != 1
                    || last.size() != 1
                    || first.get(0).direction() != -1
                    || last.get(0).direction() != -1
                    || !matchesRailBridge(
                            gray, width, height, first.get(0), last.get(0), gap, true)) continue;
            var ribbon = bestRibbon(gray, width, height, first.get(0), last.get(0), gap);
            float start = (first.get(0).left() + first.get(0).right()) * .5f;
            float end = (last.get(0).left() + last.get(0).right()) * .5f;
            if (start >= mx || mx >= end) continue;
            float railY =
                    ribbon.leftY()
                            + (ribbon.rightY() - ribbon.leftY()) * (mx - start) / (end - start);
            if (Math.abs(railY - my) <= gap * .40f && ++aliases > 1) return false;
        }
        return aliases == 1;
    }

    /** Printed groups of three eighths fill a physically closed bar with a quarter-note beat. */
    static List<ScoreNoteEvent> closeQuarterBeatBars(
            List<ScoreNoteEvent> notes,
            List<ScoreRestEvent> rests,
            List<MeasureRegion> regions,
            List<ScoreMeterChange> meters,
            byte[] gray,
            int width,
            int height) {
        return closeQuarterBeatBars(notes, rests, regions, meters, gray, width, height, null);
    }

    static List<ScoreNoteEvent> closeQuarterBeatBars(
            List<ScoreNoteEvent> notes,
            List<ScoreRestEvent> rests,
            List<MeasureRegion> regions,
            List<ScoreMeterChange> meters,
            byte[] gray,
            int width,
            int height,
            byte[] labels) {
        if (meters.isEmpty() || notes.isEmpty()) return notes;
        var bars = new ArrayList<List<Integer>>(regions.size());
        for (int i = 0; i < regions.size(); i++) bars.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) bars.get(notes.get(i).measureIndex()).add(i);
        var result = notes;
        int next = 0;
        ScoreMeterChange meter = null;
        List<ScoreMeterChange> literalMeters = null;
        var withRests = new java.util.BitSet(regions.size());
        for (var rest : rests) withRests.set(rest.measureIndex());
        for (int m = 0; m < regions.size(); m++) {
            while (next < meters.size() && meters.get(next).measureIndex() <= m)
                meter = meters.get(next++);
            var owners = bars.get(m);
            if (meter == null
                    || meter.denominator() != 4
                    || meter.numerator() != 4 && meter.numerator() != 6
                    || withRests.get(m)
                    || owners.size() != 3 * meter.numerator()
                    || owners.stream().map(notes::get).allMatch(n -> n.tupletDivisor() == 3))
                continue;
            var bar = regions.get(m);
            boolean canUseHeader =
                    meter.numerator() == 6 && labels != null && labels.length == gray.length;
            boolean canUseOpening = m == 0 && canUseHeader;
            List<ScoreNoteEvent> proofNotes = notes;
            boolean recoverEndpoint = owners.stream().anyMatch(i -> notes.get(i).beamCount() != 1);
            if (recoverEndpoint) {
                if (!canUseOpening) continue;
                int lastOwner = owners.stream()
                        .max(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure()))
                        .orElseThrow();
                var last = notes.get(lastOwner);
                if (last.beamCount() != 0
                        || last.unbeamedDurationBeats() != ScoreNoteEvent.DURATION_QUARTER
                        || last.tupletDivisor() != 1
                        || last.stemDirection() != -1
                        || owners.stream().anyMatch(i -> i != lastOwner && notes.get(i).beamCount() != 1))
                    continue;
                proofNotes = new ArrayList<>(notes);
                proofNotes.set(lastOwner, ClosingTripletRhythm.singleBeam(last));
            }
            var proof = singleRailGroups(gray, width, height, bar, proofNotes, owners, true);
            if (proof == null) continue;
            float firstHead = proof.x()[0], lastHead = proof.x()[owners.size() - 1];
            boolean closed = WrappedMeasureBoundaries.confirmsClosedTripletBar(
                    gray, width, height, bar, firstHead, lastHead);
            if (recoverEndpoint || !closed) {
                if (canUseOpening) {
                    if (!WrappedMeasureBoundaries.confirmsOpeningTripletBar(
                                    gray,
                                    width,
                                    height,
                                    regions,
                                    firstHead,
                                    lastHead,
                                    recoverEndpoint)
                            || !MeterFontMatcher.standaloneReadings(
                                            labels,
                                            gray,
                                            width,
                                            height,
                                            List.of(bar),
                                            owners.stream().map(notes::get).toList())
                                    .equals(List.of(meter))) continue;
                } else {
                    // A physically new staff can introduce its own 6/4 without a left divider.
                    if (!canUseHeader
                            || m == 0
                            || meter.measureIndex() != m
                            || bar.top() <= regions.get(m - 1).bottom()
                            || !WrappedMeasureBoundaries.confirmsOpeningTripletBar(
                                    gray,
                                    width,
                                    height,
                                    regions.subList(m, regions.size()),
                                    firstHead,
                                    lastHead,
                                    false)) continue;
                    if (literalMeters == null)
                        literalMeters =
                                MeterFontMatcher.standaloneReadings(
                                        labels, gray, width, height, regions, notes);
                    if (!literalMeters.contains(meter)) continue;
                }
            }
            if (result == notes) result = new ArrayList<>(notes);
            for (int i : owners) {
                var n = proofNotes.get(i);
                if (n.tupletDivisor() != 3) result.set(i, n.withTupletRatio(3, 2));
            }
        }
        return result == notes ? notes : List.copyOf(result);
    }

    /** A printed flat can occupy the dot window and the first head can overreport a second beam. */
    static ScorePageInterpretation closeFlatMarkedTripletBars(
            ScorePageInterpretation score, byte[] labels, byte[] gray, int width, int height) {
        if (score.beamSource().groups().isEmpty()
                || score.meterChanges().isEmpty()
                || labels == null
                || gray == null
                || labels.length != gray.length
                || gray.length != (long) width * height) return score;
        var singleGroups = new HashSet<List<Integer>>();
        for (var group : score.beamSource().resolve().groups()) {
            var columns = group.columns();
            if (columns.size() != 3
                    || group.bridgeMask(0) != 1
                    || group.bridgeMask(1) != 1
                    || !group.source().hooks().isEmpty()
                    || columns.stream()
                            .anyMatch(
                                    c ->
                                            !c.usable()
                                                    || c.owners().size() != 1
                                                    || c.source().members().size() != 1)) continue;
            singleGroups.add(columns.stream().map(c -> c.owners().get(0)).toList());
        }
        if (singleGroups.size() < 6) return score;
        var notes = score.notes();
        var regions = score.measures();
        var edited = notes;
        List<ScoreMeterChange> literalMeters = null;
        for (var meter : score.meterChanges()) {
            int measure = meter.measureIndex();
            if (measure <= 0
                    || measure >= regions.size()
                    || meter.numerator() != 6
                    || meter.denominator() != 4
                    || regions.get(measure).top() <= regions.get(measure - 1).bottom()
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == measure)) continue;
            var owners =
                    java.util.stream.IntStream.range(0, notes.size())
                            .boxed()
                            .filter(i -> notes.get(i).measureIndex() == measure)
                            .sorted(
                                    Comparator.comparingDouble(
                                            i -> notes.get(i).positionInMeasure()))
                            .toList();
            if (owners.size() != 18
                    || notes.get(owners.get(0)).beamCount() != 2
                    || owners.subList(1, 18).stream().anyMatch(i -> notes.get(i).beamCount() != 1))
                continue;
            boolean complete = true;
            for (int group = 0; group < owners.size(); group += 3)
                complete &= singleGroups.contains(owners.subList(group, group + 3));
            if (!complete) continue;
            var region = regions.get(measure);
            var proof = singleRailGroups(gray, width, height, region, notes, owners, true, true);
            if (proof == null
                    || !WrappedMeasureBoundaries.confirmsClosedTripletBar(
                            gray,
                            width,
                            height,
                            region,
                            proof.x()[0],
                            proof.x()[owners.size() - 1])) continue;
            if (literalMeters == null)
                literalMeters =
                        MeterFontMatcher.standaloneReadings(
                                labels, gray, width, height, regions, notes);
            if (!literalMeters.contains(meter)) continue;
            if (edited == notes) edited = new ArrayList<>(notes);
            int first = owners.get(0);
            for (int owner : owners) {
                var note =
                        owner == first
                                ? ClosingTripletRhythm.singleBeam(notes.get(owner))
                                : notes.get(owner);
                edited.set(owner, note.withTupletRatio(3, 2));
            }
        }
        return edited == notes
                ? score
                : score.withNotes(
                        edited, java.util.stream.IntStream.range(0, notes.size()).boxed().toList());
    }

    static WrappedTripletMeasure.Half closedFourGroups(
            byte[] gray, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> candidates) {
        if (candidates.size() != 12) return null;
        return singleRailGroups(gray, width, height, bar, notes, candidates, true);
    }

    private static WrappedTripletMeasure.Half singleRailGroups(
            byte[] gray, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> candidates, boolean closedBar) {
        return singleRailGroups(gray, width, height, bar, notes, candidates, closedBar, false);
    }

    private static WrappedTripletMeasure.Half singleRailGroups(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            List<ScoreNoteEvent> notes,
            List<Integer> candidates,
            boolean closedBar,
            boolean allowSingleFlat) {
        return singleRailGroups(
                gray, width, height, bar, notes, candidates, closedBar, allowSingleFlat, null);
    }

    private static WrappedTripletMeasure.Half singleRailGroups(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            List<ScoreNoteEvent> notes,
            List<Integer> candidates,
            boolean closedBar,
            boolean allowSingleFlat,
            byte[] labels) {
        boolean labeled = !closedBar && labels != null && labels.length == gray.length;
        boolean closedNaturalPredecessor = labeled && candidates.size() == 18;
        boolean wrapped = labeled && !closedNaturalPredecessor;
        var owners = candidates.stream()
                .sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure())).toList();
        float[] x = new float[owners.size()], y = new float[owners.size()];
        float left = bar.left() * width, right = bar.right() * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (!Float.isFinite(gap) || gap < 10 || gap > 30) return null;
        int clef = notes.get(owners.get(0)).clefBottomDiatonic();
        int reportedExtraRail = 0;
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>();
        var budget = new PrintedBeamShafts.Budget(800_000);
        PrintedBeamShaftPixels.Frame contrast = null;
        for (int i = 0; i < owners.size(); i++) {
            var n = notes.get(owners.get(i));
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED
                    || n.staffIndex() != 0
                    || n.staffCount() != 1
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || n.beamCount() < 1
                    || n.beamCount() > 2
                    || n.unbeamedDurationBeats() != 0
                    || n.crossStaffBeam()
                    || n.leadingRestBeats() != 0
                    || n.followingRestBeats() != 0
                    || n.tiedFromPrevious()
                    || n.boundaryTies() != 0
                    || (n.articulations() & NoteOrnament.GRACE) != 0
                    || clef == ScoreNoteEvent.CLEF_UNKNOWN
                    || n.clefBottomDiatonic() != clef
                    || !(n.tupletDivisor() == 1
                            || n.tupletDivisor() == 3 && n.tupletNormalNotes() == 2)
                    || Math.abs(n.stemDirection()) != 1) return null;
            if (n.beamCount() == 2 && ++reportedExtraRail > 1) return null;
            x[i] = left + (right - left) * n.positionInMeasure();
            y[i] = n.pageY() * height;
            if (n.augmentationDots() != 0
                    && !(wrapped
                            && n.augmentationDots() == 1
                            && i + 1 < owners.size()
                            && fingeringThreeOwnsDot(
                                    gray,
                                    width,
                                    height,
                                    left,
                                    right,
                                    x[i],
                                    y[i],
                                    notes.get(owners.get(i + 1)),
                                    gap))) return null;
            if (i > 0) {
                float space = x[i] - x[i - 1];
                if (space < gap * 1.65f) return null;
                if (space > gap * (closedBar ? 3.85f : 3.4f)
                        && !(closedBar
                                && x.length == 12
                                && space <= gap * 4.2f
                                && pairedFlatAfter(
                                        gray, width, height, x[i - 1], y[i - 1], x[i], gap, n)))
                    return null;
            }
            var source = PrintedBeamShafts.at(gray, width, height, x[i], y[i], gap, budget);
            if (closedBar && source.isEmpty() && !budget.refused()) {
                if (contrast == null)
                    contrast =
                            new PrintedBeamShaftPixels.Frame(
                                    gray,
                                    gray,
                                    width,
                                    height,
                                    0,
                                    units -> {
                                        if (!budget.take(units)) throw new ShaftQuotaExceeded();
                                    },
                                    true);
                try {
                    source =
                            PrintedBeamShafts.at(
                                    gray, width, height, x[i], y[i], gap, budget, contrast);
                } catch (ShaftQuotaExceeded exhausted) {
                    return null;
                }
            }
            if (closedBar && i % 3 == 1 && source.isEmpty() && !budget.refused()) {
                shafts.add(null);
                continue;
            }
            if (wrapped && source.size() == 2 && i + 1 < owners.size()) {
                var next = notes.get(owners.get(i + 1));
                var unintended =
                        source.stream()
                                .filter(shaft -> shaft.direction() == n.stemDirection())
                                .findFirst();
                if (unintended.isPresent()
                        && next.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_SHARP) {
                    var rival = unintended.get();
                    float nextX = left + (right - left) * next.positionInMeasure();
                    float nextY = next.pageY() * height;
                    if (rival.left() > x[i] + gap * .55f
                            && rival.right() < nextX - gap * .85f
                            && OmrScoreInterpreter.printedSharpBeforeDyad(
                                    gray, width, height, rival.left(), nextX, nextY, gap)) {
                        source = source.stream().filter(shaft -> shaft != rival).toList();
                    }
                }
            }
            if (source.size() != 1 || source.get(0).direction() != -n.stemDirection()) return null;
            shafts.add(source.get(0));
        }
        if (wrapped) {
            for (int middle = 1; middle < x.length; middle += 3) {
                var earlier = shafts.get(middle - 1);
                var axis = shafts.get(middle);
                var later = shafts.get(middle + 1);
                if (earlier.direction() != axis.direction()
                        || later.direction() != axis.direction()
                        || singleRail(gray, width, height, earlier, axis, gap)
                                && singleRail(gray, width, height, axis, later, gap)) continue;
                var adjusted =
                        trimExtendedMiddleShaft(gray, width, height, earlier, axis, later, gap);
                if (adjusted != null) shafts.set(middle, adjusted);
            }
        }
        if (x.length == 18) {
            for (int middle = 1; middle < x.length; middle += 3) {
                var earlier = shafts.get(middle - 1);
                var axis = shafts.get(middle);
                var later = shafts.get(middle + 1);
                if (earlier == null
                        || axis == null
                        || later == null
                        || axis.pixels() == null
                        || earlier.direction() != axis.direction()
                        || later.direction() != axis.direction()) continue;
                if (singleRail(gray, width, height, earlier, axis, gap)
                        && singleRail(gray, width, height, axis, later, gap)) continue;
                var continued = continueMiddleShaft(gray, width, height, earlier, axis, later, gap);
                if (continued != null) shafts.set(middle, continued);
            }
        }
        boolean flatOwnsDot = false;
        boolean naturalOwnsDot = false;
        for (int i = 0; i + 1 < x.length; i++) {
            var dot =
                    WrappedTripletDotInk.uniqueCore(gray, width, height, x[i], y[i], x[i + 1], gap);
            boolean ownedNatural = labeled && dot != null
                    && notes.get(owners.get(i + 1)).writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL
                    && naturalOwnsDot(gray, labels, width, height, dot, x[i + 1], y[i + 1], gap);
            naturalOwnsDot |= ownedNatural;
            if (dot != null
                    && !(wrapped
                            && highLedgerOwnsDot(
                                    gray, labels, width, height, bar, x[i], y[i], dot, gap))
                    && !(wrapped
                            && notes.get(owners.get(i + 1)).writtenAccidental()
                                    == ScoreNoteEvent.ACCIDENTAL_SHARP
                            && sharpOwnsDot(
                                    gray, labels, width, height, x[i], y[i], dot, x[i + 1],
                                    y[i + 1], gap))
                    && !ownedNatural
                    && (x.length != 18
                            || !headConnectedLedger(gray, width, height, x[i], y[i], dot, gap))
                    && !(x.length == 12
                            && closedBar
                            && notes.get(owners.get(i + 1)).writtenAccidental()
                                    == ScoreNoteEvent.ACCIDENTAL_FLAT
                            && WrappedTripletDotInk.pairedFlatOwns(
                                    gray, width, height, dot, gap))) {
                if (!allowSingleFlat
                        || notes.get(owners.get(i + 1)).writtenAccidental()
                                != ScoreNoteEvent.ACCIDENTAL_FLAT
                        || !WrappedTripletDotInk.singleFlatOwns(
                                gray, width, height, dot, x[i + 1], y[i + 1], gap)) return null;
                flatOwnsDot = true;
            }
            var a = shafts.get(i);
            var b = shafts.get(i+1);
            if (a == null || b == null) {
                int middle = a == null ? i : i + 1;
                int stem = notes.get(owners.get(middle)).stemDirection();
                if (stem != notes.get(owners.get(i)).stemDirection()
                        || stem != notes.get(owners.get(i+1)).stemDirection()
                        || !PrintedTupletBeamOwner.connectedHeads(
                                gray, width, height, x[i], y[i], x[i+1], y[i+1], gap, stem)
                        || !singleRail(gray, width, height, shafts.get(middle-1), shafts.get(middle+1), gap))
                    return null;
                continue;
            }
            boolean same = a.direction() == b.direction();
            if (i % 3 == 2) {
                if (same) {
                    var ribbon = bestRibbon(gray, width, height, a, b, gap);
                    if (ribbon.coverage() > .46f
                            && (!closedBar
                                    || x.length != 12
                                    || !staffRuleOnlyCut(
                                            gray, width, height, bar, a, b, gap, ribbon, false)))
                        return null;
                }
                continue;
            }
            if (!same || !singleRail(gray, width, height, a, b, gap)) return null;
        }
        return (!allowSingleFlat || flatOwnsDot) && (!closedNaturalPredecessor || naturalOwnsDot)
                ? new WrappedTripletMeasure.Half(owners, x) : null;
    }

    /** Keep a proved beam endpoint when the scanner continues into adjacent vertical ink. */
    static PrintedBeamShafts.Shaft trimExtendedMiddleShaft(
            byte[] gray,
            int width,
            int height,
            PrintedBeamShafts.Shaft a,
            PrintedBeamShafts.Shaft middle,
            PrintedBeamShafts.Shaft c,
            float gap) {
        int x0 = (a.left() + a.right()) / 2;
        int x = (middle.left() + middle.right()) / 2;
        int x1 = (c.left() + c.right()) / 2;
        if (x0 >= x || x >= x1 || !singleRail(gray, width, height, a, c, gap)) return null;
        int end = Math.round(a.endY() + (c.endY() - a.endY()) * (x - x0) / (float) (x1 - x0));
        int direction = middle.direction();
        float overrun = (middle.endY() - end) * direction;
        if (overrun < gap * .72f || overrun > gap * 1.55f) return null;
        int inner = end - direction * Math.round(gap * .45f);
        if (inner < 0 || inner >= height || end < 0 || end >= height) return null;
        for (int y = inner; (end - y) * direction >= 0; y += direction) {
            boolean occupied = false;
            for (int col = middle.left(); col <= middle.right(); col++)
                occupied |= (gray[y * width + col] & 255) < 165;
            if (!occupied) return null;
        }
        var shortened =
                new PrintedBeamShafts.Shaft(
                        middle.left(),
                        middle.right(),
                        middle.startY(),
                        end,
                        direction,
                        middle.columns());
        return singleRail(gray, width, height, a, shortened, gap)
                        && singleRail(gray, width, height, shortened, c, gap)
                ? shortened
                : null;
    }

    /** The core lies on the next pitch's printed sharp or on the current head's ledger tip. */
    private static boolean sharpOwnsDot(
            byte[] gray,
            byte[] labels,
            int width,
            int height,
            float headX,
            float headY,
            WrappedTripletDotInk.Center core,
            float nextX,
            float nextY,
            float gap) {
        if (nextX - core.x() < gap * 1.35f
                || nextX - core.x() > gap * 2.15f
                || core.y() - nextY < gap * .80f
                || core.y() - nextY > gap * 1.55f
                || !OmrScoreInterpreter.printedSharpBeforeDyad(
                        gray, width, height, core.x(), nextX, nextY, gap)) return false;
        int label = labels[core.y() * width + core.x()] & 255;
        if (label == OmrMeasurePostProcessor.CLEF_OR_KEY) return true;
        if (label != OmrMeasurePostProcessor.SYMBOL || Math.abs(headY - core.y()) > gap * .24f)
            return false;
        int left = Math.round(headX + gap * .46f);
        for (int y = core.y() - 1; y <= core.y() + 1; y++) {
            boolean linked = true;
            for (int x = left; x <= core.x(); x++) linked &= (gray[y * width + x] & 255) < 155;
            if (linked) return true;
        }
        return false;
    }

    /** The dot-window stroke belongs to the next attack's literal natural sign. */
    static boolean naturalOwnsDot(
            byte[] gray,
            byte[] labels,
            int width,
            int height,
            WrappedTripletDotInk.Center core,
            float nextX,
            float nextY,
            float gap) {
        if (labels == null || labels.length != gray.length || gap < 6 || gap > 32
                || nextX - core.x() < gap * .94f || nextX - core.x() > gap * 2.30f
                || Math.abs(nextY - core.y()) > gap * 1.48f
                || labels[core.y() * width + core.x()] != OmrMeasurePostProcessor.CLEF_OR_KEY)
            return false;
        int left = Math.max(0, Math.round(nextX - gap * 3.10f));
        int top = Math.max(0, Math.round(nextY - gap * 2.22f));
        int right = Math.min(width - 1, Math.round(nextX - gap * .64f));
        int bottom = Math.min(height - 1, Math.round(nextY + gap * 1.92f));
        if (core.x() < left || core.x() > right || core.y() < top || core.y() > bottom)
            return false;
        int w = right - left + 1, h = bottom - top + 1;
        byte[] semantic = new byte[w * h];
        java.util.Arrays.fill(semantic, (byte) 255);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int source = (top + y) * width + left + x;
                if (labels[source] == OmrMeasurePostProcessor.CLEF_OR_KEY
                        && (gray[source] & 255) < 170) semantic[y * w + x] = 0;
            }
        }
        var components = RawNumeralComponents.capture(
                semantic, w, h, 0, 0, w - 1, h - 1, () -> {}, () -> {});
        int corePixel = (core.y() - top) * w + core.x() - left;
        for (var glyph : components.components()) {
            boolean containsCore = false;
            for (int i = 0; i < glyph.size(); i++) containsCore |= glyph.pixel(i) == corePixel;
            if (!containsCore) continue;
            java.util.Arrays.fill(semantic, (byte) 255);
            for (int i = 0; i < glyph.size(); i++) semantic[glyph.pixel(i)] = 0;
            return OmrScoreInterpreter.provesNaturalMeterInk(
                    semantic, w, h, glyph.left, glyph.right, nextY - top, gap, h + 1);
        }
        return false;
    }

    /** A clipped lower lobe can be part of a completely printed fingering above the next note. */
    static boolean fingeringThreeOwnsDot(
            byte[] gray,
            int width,
            int height,
            float left,
            float right,
            float headX,
            float headY,
            ScoreNoteEvent next,
            float gap) {
        if (next.stemDirection() != -1 || next.visualSize() != ScoreNoteEvent.VisualSize.FULL)
            return false;
        float nextX = left + (right - left) * next.positionInMeasure();
        float nextY = next.pageY() * height;
        if (nextX - headX < gap * 1.65f
                || nextX - headX > gap * 2.45f
                || nextY - headY < gap * 1.05f
                || nextY - headY > gap * 1.75f
                || WrappedTripletDotInk.uniqueCore(gray, width, height, headX, headY, nextX, gap)
                        != null) return false;
        int l = Math.round(nextX - gap * .84f), r = Math.round(nextX + gap * .84f);
        int t = Math.round(nextY - gap * 2.65f), b = Math.round(nextY - gap * .66f);
        if (l < 0 || r >= width || t < 0 || b >= height) return false;
        var shape =
                RawNumeralComponents.capture(
                        gray, width, height, l, t, r, b, 139, () -> {}, () -> {});
        int owners = 0;
        for (var ink : shape.components()) {
            float x = (ink.left + ink.right) * .5f;
            float y = (ink.top + ink.bottom) * .5f;
            if (ink.left <= l
                    || ink.right >= r
                    || ink.top <= t
                    || ink.bottom >= b
                    || Math.abs(x - nextX) > gap * .35f
                    || Math.abs(y - headY) > gap * .39f
                    || ink.bottom - ink.top + 1 < gap * .7f
                    || ink.bottom - ink.top + 1 > gap * 1.5f
                    || !TripletRhythmDetector.isIsolatedThree(ink, width)) continue;
            owners++;
        }
        return owners == 1;
    }

    /** A short, head-connected ledger tip is part of the printed pitch. */
    static boolean highLedgerOwnsDot(
            byte[] gray,
            byte[] labels,
            int width,
            int height,
            MeasureRegion bar,
            float headX,
            float headY,
            WrappedTripletDotInk.Center core,
            float gap) {
        float staffTop = bar.bottom() * height - gap * 6.1f;
        return headY < staffTop - gap * .8f
                && labels[core.y() * width + core.x()] == OmrMeasurePostProcessor.SYMBOL
                && Math.abs(headY - core.y()) <= gap * .25f
                && headConnectedLedger(gray, width, height, headX, headY, core, gap, .48f);
    }

    private static boolean pairedFlatAfter(
            byte[] gray,
            int width,
            int height,
            float x,
            float y,
            float next,
            float gap,
            ScoreNoteEvent following) {
        if (following.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT) return false;
        var dot = WrappedTripletDotInk.uniqueCore(gray, width, height, x, y, next, gap);
        return dot != null && WrappedTripletDotInk.pairedFlatOwns(gray, width, height, dot, gap);
    }

    /** A compact ledger cap can fill the same dark window as an augmentation dot. */
    private static boolean headConnectedLedger(
            byte[] gray,
            int width,
            int height,
            float headX,
            float headY,
            WrappedTripletDotInk.Center core,
            float gap) {
        return headConnectedLedger(gray, width, height, headX, headY, core, gap, .35f);
    }

    static boolean headConnectedLedger(
            byte[] gray,
            int width,
            int height,
            float headX,
            float headY,
            WrappedTripletDotInk.Center core,
            float gap,
            float maxTip) {
        int left = Math.round(headX - gap), right = Math.round(core.x() + gap * .4f);
        int top = Math.round(Math.min(core.y(), headY) - gap * .65f);
        int bottom = Math.round(Math.max(core.y(), headY) + gap * .65f);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return false;
        var graph =
                RawNumeralComponents.capture(
                        gray, width, height, left, top, right, bottom, 100, () -> {}, () -> {});
        int radius = Math.max(1, Math.round(gap * .13f));
        int cx = Math.round(headX), cy = Math.round(headY);
        int leftLedger = Math.round(headX - gap * .60f);
        int dark = 0;
        for (int x = leftLedger; x < cx; x++) {
            for (int y = core.y() - 2; y <= core.y() + 2; y++) {
                if ((gray[y * width + x] & 255) > 120) continue;
                dark++;
                break;
            }
        }
        if (dark < (cx - leftLedger) * .83f) return false;
        if (!taperingLedgerTip(gray, width, headX, core, gap, maxTip)) return false;
        for (var glyph : graph.components()) {
            if (core.x() < glyph.left
                    || core.x() > glyph.right
                    || core.y() < glyph.top
                    || core.y() > glyph.bottom
                    || glyph.left > leftLedger + 1
                    || glyph.size() < gap * gap * .55f) continue;
            int center = 0;
            boolean containsCore = false;
            for (int i = 0; i < glyph.size(); i++) {
                int pixel = glyph.pixel(i), x = pixel % width, y = pixel / width;
                containsCore |= x == core.x() && y == core.y();
                if (Math.abs(x - cx) <= radius && Math.abs(y - cy) <= radius) center++;
            }
            if (containsCore && center >= radius * radius * 4) return true;
        }
        return false;
    }

    private static boolean taperingLedgerTip(
            byte[] gray,
            int width,
            float headX,
            WrappedTripletDotInk.Center core,
            float gap,
            float maxTip) {
        int prior = Integer.MAX_VALUE;
        int thinFrom = Math.round(headX + gap * .74f);
        int right = core.x() + Math.round(gap * .28f);
        int radius = Math.round(gap * .37f);
        int thicknessAtCore = 0, occupied = 0;
        for (int x = thinFrom; x <= right; x++) {
            int thickness = 0;
            for (int y = core.y() - radius; y <= core.y() + radius; y++)
                if ((gray[y * width + x] & 255) < 155) thickness++;
            if (prior != Integer.MAX_VALUE && thickness > prior + 1) return false;
            if (x <= core.x() && thickness == 0) return false;
            if (x == core.x()) thicknessAtCore = thickness;
            if (x > core.x() && thickness > 0) occupied++;
            prior = thickness;
        }
        return thicknessAtCore <= gap * maxTip && occupied >= 2;
    }

    /** Keep a head-connected middle shaft only if its ink reappears at both flanks' real rail. */
    private static PrintedBeamShafts.Shaft continueMiddleShaft(
            byte[] gray,
            int width,
            int height,
            PrintedBeamShafts.Shaft earlier,
            PrintedBeamShafts.Shaft axis,
            PrintedBeamShafts.Shaft later,
            float gap) {
        int x0 = (earlier.left() + earlier.right()) / 2;
        int x = (axis.left() + axis.right()) / 2;
        int x1 = (later.left() + later.right()) / 2;
        int direction = axis.direction();
        if (!(x0 < x && x < x1) || !singleRail(gray, width, height, earlier, later, gap))
            return null;
        int cap =
                Math.round(
                        earlier.endY()
                                + (later.endY() - earlier.endY()) * (x - x0) / (float) (x1 - x0));
        int remaining = (cap - axis.endY()) * direction;
        if (remaining < gap * .75f || remaining > gap * 2.25f) return null;
        int endpoint = cap - direction * Math.round(gap * .15f);
        int shoulder = Math.round(gap * .25f);
        int first = axis.endY() + direction;
        if (first < 0
                || first >= height
                || endpoint < 0
                || endpoint >= height
                || axis.left() < 0
                || axis.right() >= width
                || x < shoulder
                || x + shoulder >= width) return null;
        int missing = 0;
        for (int y = first; direction * (endpoint - y) >= 0; y += direction) {
            boolean ink = false;
            for (int column = axis.left(); column <= axis.right(); column++)
                ink |= (gray[y * width + column] & 255) < 160;
            if (!ink && ++missing > 2) return null;
        }
        for (int column = x - shoulder; column <= x + shoulder; column++)
            if ((gray[endpoint * width + column] & 255) >= 155) return null;
        var revised =
                new PrintedBeamShafts.Shaft(
                        axis.left(), axis.right(), axis.startY(), cap, direction, axis.columns());
        return singleRail(gray, width, height, earlier, revised, gap)
                        && singleRail(gray, width, height, revised, later, gap)
                ? revised
                : null;
    }

    static boolean matchesRailBridge(
            byte[] gray,
            int width,
            int height,
            PrintedBeamShafts.Shaft first,
            PrintedBeamShafts.Shaft last,
            float gap,
            boolean connected) {
        return connected
                ? singleRail(gray, width, height, first, last, gap)
                : bestRibbon(gray, width, height, first, last, gap).coverage() <= .46f;
    }

    static boolean matchesBeamRails(byte[] gray, int width, int height,
            PrintedBeamShafts.Shaft left, PrintedBeamShafts.Shaft right, float gap, int rails) {
        return rails == 0 ? matchesRailBridge(gray, width, height, left, right, gap, false)
                : matchesRailCount(gray, width, height, left, right, gap, rails == 2);
    }

    private static boolean singleRail(byte[] gray, int width, int height,
            PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, float gap) {
        return matchesRailCount(gray, width, height, a, b, gap, false);
    }

    private static boolean matchesRailCount(
            byte[] gray,
            int width,
            int height,
            PrintedBeamShafts.Shaft a,
            PrintedBeamShafts.Shaft b,
            float gap,
            boolean doubleRail) {
        var ribbon = bestRibbon(gray, width, height, a, b, gap);
        if (ribbon.coverage() < .9f) return false;
        int side = -a.direction();
        for (float separation : new float[] {.69f, .84f, .99f, 1.14f}) {
            int dy = side * Math.round(gap * separation);
            if (coverage(
                            gray,
                            width,
                            height,
                            a,
                            b,
                            gap,
                            ribbon.leftY() + dy,
                            ribbon.rightY() + dy,
                            .19f)
                    >= .88f) return doubleRail;
        }
        return !doubleRail;
    }

    static boolean mixedCut(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            ScoreNoteEvent left,
            ScoreNoteEvent right) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (!Float.isFinite(gap)
                || gap < 10
                || gap > 30
                || width < 1
                || height < 1
                || gray == null
                || gray.length != (long) width * height) return false;
        float ax = (bar.left() + (bar.right() - bar.left()) * left.positionInMeasure()) * width;
        float bx = (bar.left() + (bar.right() - bar.left()) * right.positionInMeasure()) * width;
        if (bx - ax < gap * 1.65f || bx - ax > gap * 4) return false;
        var budget = new PrintedBeamShafts.Budget(150_000);
        var a = PrintedBeamShafts.at(gray, width, height, ax, left.pageY() * height, gap, budget);
        var b = PrintedBeamShafts.at(gray, width, height, bx, right.pageY() * height, gap, budget);
        return a.size() == 1
                && b.size() == 1
                && a.get(0).direction() == -left.stemDirection()
                && b.get(0).direction() == -right.stemDirection()
                && (a.get(0).direction() != b.get(0).direction()
                        || bestRibbon(gray, width, height, a.get(0), b.get(0), gap).coverage()
                                <= .46f);
    }

    static WrappedTripletMeasure.Half doubleRailGroups(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            List<ScoreNoteEvent> notes,
            List<Integer> candidates) {
        if (candidates.size() != 8) return null;
        return mixedRailGroups(gray, width, height, bar, notes, candidates, 4, true);
    }

    static WrappedTripletMeasure.Half mixedOpeningGroups(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            List<ScoreNoteEvent> notes,
            List<Integer> candidates) {
        if (candidates.isEmpty() || candidates.size() % 3 != 0) return null;
        return mixedRailGroups(gray, width, height, bar, notes, candidates, 3, false);
    }

    private static WrappedTripletMeasure.Half mixedRailGroups(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            List<ScoreNoteEvent> notes,
            List<Integer> candidates,
            int groupSize,
            boolean doubleRail) {
        if (width < 1 || height < 1 || gray == null || gray.length != (long) width * height)
            return null;
        var owners =
                candidates.stream()
                        .sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure()))
                        .toList();
        float[] x = new float[owners.size()], y = new float[owners.size()];
        float left = bar.left() * width, right = bar.right() * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (!Float.isFinite(gap) || gap < 10 || gap > 30) return null;
        int clef = notes.get(owners.get(0)).clefBottomDiatonic();
        int reportedExtraRail = 0;
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>();
        var budget = new PrintedBeamShafts.Budget(800_000);
        for (int i = 0; i < owners.size(); i++) {
            var n = notes.get(owners.get(i));
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED
                    || n.staffIndex() != 0
                    || n.staffCount() != 1
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || n.beamCount() < 1
                    || n.beamCount() > 2
                    || doubleRail && (n.beamCount() != 2 || n.tupletDivisor() != 1)
                    || n.augmentationDots() != 0
                    || n.unbeamedDurationBeats() != 0
                    || n.crossStaffBeam()
                    || n.leadingRestBeats() != 0
                    || n.followingRestBeats() != 0
                    || n.tiedFromPrevious()
                    || n.boundaryTies() != 0
                    || (n.articulations() & NoteOrnament.GRACE) != 0
                    || clef == ScoreNoteEvent.CLEF_UNKNOWN
                    || n.clefBottomDiatonic() != clef
                    || !(n.tupletDivisor() == 1
                            || n.tupletDivisor() == 3 && n.tupletNormalNotes() == 2)
                    || Math.abs(n.stemDirection()) != 1) return null;
            if (!doubleRail && n.beamCount() == 2 && ++reportedExtraRail > 1) return null;
            x[i] = left + (right - left) * n.positionInMeasure();
            y[i] = n.pageY() * height;
            if (i > 0
                    && (x[i] - x[i - 1] < gap * 1.65f
                            || x[i] - x[i - 1] > gap * (owners.size() == 6 ? 4f : 3.4f)))
                return null;
            var source = PrintedBeamShafts.at(gray, width, height, x[i], y[i], gap, budget);
            if (source.size() != 1 || source.get(0).direction() != -n.stemDirection()) return null;
            shafts.add(source.get(0));
        }
        for (int i = 0; i + 1 < x.length; i++) {
            if (WrappedTripletDotInk.uniqueCore(gray, width, height, x[i], y[i], x[i + 1], gap)
                    != null) return null;
            var a = shafts.get(i);
            var b = shafts.get(i + 1);
            boolean same = a.direction() == b.direction();
            if (i % groupSize == groupSize - 1) {
                if (same && bestRibbon(gray, width, height, a, b, gap).coverage() > .46f)
                    return null;
                continue;
            }
            if (!same) return null;
            if (!matchesRailCount(gray, width, height, a, b, gap, doubleRail)) return null;
        }
        return new WrappedTripletMeasure.Half(owners, x);
    }

    static boolean cutBetweenRecoveredGroups(
            byte[] gray, int width, int height, MeasureRegion bar,
            PrintedBeamShafts.Shaft left, PrintedBeamShafts.Shaft right, float gap) {
        var ribbon = bestRibbon(gray, width, height, left, right, gap);
        return ribbon.coverage() <= .46f
                || staffRuleOnlyCut(gray, width, height, bar, left, right, gap, ribbon, true);
    }

    /** A staff crossing can imitate part of a beam without joining the two shafts. */
    private static boolean staffRuleOnlyCut(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            PrintedBeamShafts.Shaft left,
            PrintedBeamShafts.Shaft right,
            float gap,
            Ribbon ribbon,
            boolean recoveredShaft) {
        if (ribbon.coverage() >= .75f) return false;
        int a = (left.left() + left.right()) / 2, b = (right.left() + right.right()) / 2;
        int inset = Math.round(gap * .45f), first = a + inset, last = b - inset;
        if (first < 0 || last >= width || last <= first) return false;
        var staff = WrappedMeasureBoundaries.closedTripletStaff(gray, width, height, bar);
        if (staff == null || !staff.verifiedAt(a) || !staff.verifiedAt(b)) return false;
        var centre = staff.at((a + b) * .5f);
        int rule = Math.round((centre[0] - (ribbon.leftY() + ribbon.rightY()) * .5f) / centre[1]);
        if (rule < 0 || rule >= 5) return false;

        // A real beam running on top of a rule has no independent separation witness.
        var before = staff.at(a);
        var after = staff.at(b);
        float start = ribbon.leftY() - (before[0] - rule * before[1]);
        float end = ribbon.rightY() - (after[0] - rule * after[1]);
        float near = Math.min(Math.abs(start), Math.abs(end));
        float far = Math.max(Math.abs(start), Math.abs(end));
        if (start * end >= 0
                || (recoveredShaft ? near < gap * .12f || far < gap * .36f : near < gap * .31f))
            return false;

        int[] rows = new int[last - first + 1];
        int isolated = 0, flank = Math.max(3, Math.round(gap * .22f));
        for (int x = first; x <= last; x++) {
            var local = staff.at(x);
            int y = Math.round(local[0] - rule * local[1]);
            if (y < flank || y + flank >= height) return false;
            rows[x - first] = y;
            if ((gray[y * width + x] & 255) < 155
                    && (gray[(y - flank) * width + x] & 255) >= 155
                    && (gray[(y + flank) * width + x] & 255) >= 155) isolated++;
        }
        if (isolated < rows.length * .84f) return false;
        var ruleInk = new RuleBand(first, rows, Math.round(gap * .16f));
        return bestRibbon(gray, width, height, left, right, gap, ruleInk).coverage() <= .46f;
    }

    private static Ribbon bestRibbon(byte[] gray, int width, int height,
            PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, float gap) {
        return bestRibbon(gray, width, height, a, b, gap, null);
    }

    private static Ribbon bestRibbon(
            byte[] gray,
            int width,
            int height,
            PrintedBeamShafts.Shaft a,
            PrintedBeamShafts.Shaft b,
            float gap,
            RuleBand excluded) {
        var best = new Ribbon(0, a.endY(), b.endY());
        int direction = a.direction();
        for (int dy = -Math.round(gap * .28f); dy <= Math.round(gap * .28f); dy++) {
            for (int slope = -1; slope <= 1; slope++) {
                int centre = -direction * Math.round(gap * .14f);
                int leftY = a.endY() + centre + dy;
                int rightY = b.endY() + centre + dy + Math.round(slope * gap * .24f);
                float cover =
                        coverage(gray, width, height, a, b, gap, leftY, rightY, .31f, excluded);
                int distance = Math.abs(leftY-a.endY()-centre) + Math.abs(rightY-b.endY()-centre);
                int prior = Math.abs(best.leftY()-a.endY()-centre)
                        + Math.abs(best.rightY()-b.endY()-centre);
                if (cover > best.coverage() || cover == best.coverage() && distance < prior)
                    best = new Ribbon(cover, leftY, rightY);
            }
        }
        return best;
    }

    private static float coverage(byte[] gray, int width, int height,
            PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, float gap,
            int leftY, int rightY, float band) {
        return coverage(gray, width, height, a, b, gap, leftY, rightY, band, null);
    }

    private static float coverage(
            byte[] gray,
            int width,
            int height,
            PrintedBeamShafts.Shaft a,
            PrintedBeamShafts.Shaft b,
            float gap,
            int leftY,
            int rightY,
            float band,
            RuleBand excluded) {
        int x0 = (a.left() + a.right()) / 2, x1 = (b.left() + b.right()) / 2;
        int padding = Math.round(gap * .45f);
        if (x0 < 0 || x1 >= width || x1 - x0 <= padding * 2) return 0;
        int hits = 0, columns = 0, radius = Math.round(gap * band);
        int thickness = Math.round(gap * .27f);
        for (int x = x0 + padding; x <= x1 - padding; x++) {
            float progress = (x - x0) / (float) (x1 - x0);
            int middle = Math.round(leftY * (1 - progress) + rightY * progress);
            if (middle - radius < 0 || middle + radius >= height) return 0;
            int run = 0;
            for (int y = middle - radius; y <= middle + radius; y++) {
                if ((gray[y * width + x] & 255) < 155
                        && (excluded == null || !excluded.contains(x, y))) {
                    if (++run >= thickness) {
                        hits++;
                        break;
                    }
                } else run = 0;
            }
            columns++;
        }
        return hits / (float) columns;
    }
}
