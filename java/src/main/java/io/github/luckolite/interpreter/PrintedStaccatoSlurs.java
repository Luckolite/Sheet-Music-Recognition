// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/** Reads the bowed contour and compact dots of short slurs over beamed sixths. */
final class PrintedStaccatoSlurs {
    private PrintedStaccatoSlurs() {}

    private record Dyad(PrintedSlurOwners.GeometryHead upper, PrintedSlurOwners.GeometryHead lower) {}

    private record Dot(int left, int top, int right, int bottom, float center) {}

    private record Marks(Dot start, Dot end, PrintedSlurContours.Bow bow) {}

    static ScorePageInterpretation apply(
            ScorePageInterpretation score, int page, byte[] gray, byte[] original, int width, int height) {
        return apply(score, page, null, gray, original, width, height);
    }

    static ScorePageInterpretation apply(ScorePageInterpretation score, int page, byte[] labels,
            byte[] gray, byte[] original, int width, int height) {
        if (page < 0 || width <= 0 || height <= 0 || gray == null || original == null
                || gray.length != (long) width * height || original.length != gray.length) return score;
        var notes = score.notes();
        if (notes.size() < 4) return score;
        List<PrintedSlurOwners.GeometryHead> heads;
        try {
            heads = PrintedSlurContext.geometryHeads(notes, score.measures(), width, height);
        } catch (IllegalArgumentException invalidGeometry) {
            return score;
        }
        var bars = new ArrayList<List<PrintedSlurOwners.GeometryHead>>();
        for (int i = 0; i < score.measures().size(); i++) bars.add(new ArrayList<>());
        for (var head : heads) {
            var note = notes.get(head.owner());
            if (note.staffCount() == 1) bars.get(note.measureIndex()).add(head);
        }

        List<ScoreNoteEvent> edited = null;
        var source = score.slurSource();
        var spans = new ArrayList<>(source.spans());
        var origins = new ArrayList<>(source.noteOrigins());
        for (int bar = 0; bar < bars.size(); bar++) {
            var row = bars.get(bar);
            if (row.size() < 4) continue;
            var region = score.measures().get(bar);
            float estimate = (region.bottom() - region.top()) * height / 8.2f;
            if (estimate < 7 || estimate > 34) continue;
            row.sort(Comparator.comparingDouble(PrintedSlurOwners.GeometryHead::x));
            if (row.size() == 9 && ownedLowerDotsBeforeHalf(
                    score, notes, bar, row, region, gray, original, width, height, estimate)) {
                if (edited == null) edited = new ArrayList<>(notes);
                int alias = row.get(1).owner(), owner = row.get(2).owner();
                edited.set(alias, WrappedEighthTriplets.withoutDots(notes.get(alias)));
                var following = notes.get(owner);
                edited.set(owner, following.withArticulations(
                        following.articulations() | NoteArticulation.STACCATO));
            }
            for (int i = 1; i < row.size(); i++) {
                var a = row.get(i - 1);
                var b = row.get(i);
                var first = notes.get(a.owner());
                var last = notes.get(b.owner());
                if (first.augmentationDots() != 1 || last.augmentationDots() != 0
                        || (last.articulations() & NoteArticulation.STACCATO) == 0
                        || !plainSixteenth(WrappedEighthTriplets.withoutDots(first))
                        || !plainSixteenth(last)
                        || first.visualSize() != ScoreNoteEvent.VisualSize.FULL
                        || last.visualSize() != ScoreNoteEvent.VisualSize.FULL
                        || first.staffStep() - last.staffStep() != 1
                        || first.tiedFromPrevious() || last.tiedFromPrevious()
                        || first.boundaryTies() != 0 || last.boundaryTies() != 0) continue;
                if (b.x() - a.x() < estimate * 1.78f || b.x() - a.x() > estimate * 2.25f
                        || i > 1 && a.x() - row.get(i - 2).x() < estimate * .32f
                        || i + 1 < row.size() && row.get(i + 1).x() - b.x() < estimate * .32f) continue;
                var fitted = WrappedMeasureBoundaries.verifiedTripletStaff(original, width, height, region, true);
                if (fitted == null || !fitted.verifiedAt(a.x()) || !fitted.verifiedAt(b.x())) continue;
                float gap = fitted.at(a.x())[1];
                if (b.y() - a.y() < gap * .44f || b.y() - a.y() > gap * .65f) continue;
                var printed = borrowedMonophonicDots(original, width, height, fitted, a, b, gap);
                if (printed == null || !printed.equals(
                        borrowedMonophonicDots(gray, width, height, fitted, a, b, gap))) continue;
                var start = origins.get(a.owner()).orElse(null);
                var end = origins.get(b.owner()).orElse(null);
                if (!source.hasOriginalOwners()) {
                    start = new ScoreSlurPages.Anchor(page, a.owner());
                    end = new ScoreSlurPages.Anchor(page, b.owner());
                }
                if (start == null || end == null) continue;
                if (edited == null) edited = new ArrayList<>(notes);
                edited.set(a.owner(), WrappedEighthTriplets.withoutDots(first)
                        .withArticulations(first.articulations() | NoteArticulation.STACCATO));
                origins.set(a.owner(), Optional.of(start));
                origins.set(b.owner(), Optional.of(end));
                if (!alreadyOwned(spans, start, end)) {
                    var bow = printed.bow();
                    spans.add(new ScoreSlurPages.Span(
                            "printed-page-" + page + "-staccato-arc-" + start.noteIndex() + "-" + end.noteIndex(),
                            start, end, ScoreSlurSpan.Placement.ABOVE, ScoreSlurSpan.LineType.SOLID,
                            List.of(new ScoreSlurSpan.ArcEvidence(page,
                                    bow.start().x() / (float) width, bow.start().y() / height,
                                    bow.end().x() / (float) width, bow.end().y() / height))));
                }
            }
            var opening = openingUpperThirdSlur(score, row, region, gray, original, width, height, estimate, page);
            if (opening != null) {
                int startOwner = opening.start().noteIndex(), endOwner = opening.end().noteIndex();
                var start = origins.get(startOwner).orElse(null);
                var end = origins.get(endOwner).orElse(null);
                if (!source.hasOriginalOwners()) {
                    start = opening.start();
                    end = opening.end();
                }
                if (start != null && end != null && !alreadyOwned(spans, start, end)
                        && !alreadyOwned(source.candidates(), start, end)) {
                    origins.set(startOwner, Optional.of(start));
                    origins.set(endOwner, Optional.of(end));
                    spans.add(new ScoreSlurPages.Span(
                            opening.id(), start, end, opening.placement(), opening.lineType(), opening.evidence()));
                }
            }
            var below = openingLowerPortato(score, row, notes, bar, region,
                    gray, original, width, height, estimate);
            if (below != null) {
                int low = below.start(), high = below.partner(), end = below.end();
                var start = origins.get(low).orElse(null);
                var stop = origins.get(end).orElse(null);
                if (!source.hasOriginalOwners()) {
                    start = new ScoreSlurPages.Anchor(page, low);
                    stop = new ScoreSlurPages.Anchor(page, end);
                }
                if (start != null && stop != null) {
                    if (edited == null) edited = new ArrayList<>(notes);
                    edited.set(low, WrappedEighthTriplets.withoutDots(notes.get(low)));
                    edited.set(high, WrappedEighthTriplets.withoutDots(notes.get(high)));
                    origins.set(low, Optional.of(start));
                    origins.set(end, Optional.of(stop));
                    var bow = below.bow();
                    spans.add(new ScoreSlurPages.Span(
                            "printed-page-" + page + "-lower-portato-" + start.noteIndex() + "-" + stop.noteIndex(),
                            start, stop, ScoreSlurSpan.Placement.BELOW, ScoreSlurSpan.LineType.SOLID,
                            List.of(new ScoreSlurSpan.ArcEvidence(page,
                                    bow.start().x() / (float) width, bow.start().y() / height,
                                    bow.end().x() / (float) width, bow.end().y() / height))));
                }
            }
            var slots = new ArrayList<Dyad>();
            for (int i = 0; i < row.size(); ) {
                int j = i + 1;
                while (j < row.size() && row.get(j).x() - row.get(i).x() < estimate * .22f) j++;
                slots.add(j == i + 2 ? sixth(row.get(i), row.get(i + 1), notes) : null);
                i = j;
            }
            StaffPitchTrack staff = null;
            for (int i = 1; i < slots.size(); i++) {
                var a = slots.get(i - 1);
                var b = slots.get(i);
                if (a == null || b == null || !staccatoPair(a, b, notes)) continue;
                if (staff == null) {
                    staff = WrappedMeasureBoundaries.verifiedTripletStaff(original, width, height, region, true);
                    if (staff == null) break;
                }
                float g = staff.at(a.upper().x())[1];
                float dx = b.upper().x() - a.upper().x(), rise = a.upper().y() - b.upper().y();
                if (dx < 1.65f * g || dx > 2.65f * g || rise < .25f * g || rise > .9f * g
                        || !staff.verifiedAt(a.upper().x()) || !staff.verifiedAt(b.upper().x())) continue;
                int start = a.upper().owner(), end = b.upper().owner();
                var first = origins.get(start).orElse(null);
                var last = origins.get(end).orElse(null);
                if (!source.hasOriginalOwners()) {
                    first = new ScoreSlurPages.Anchor(page, start);
                    last = new ScoreSlurPages.Anchor(page, end);
                }
                if (first == null || last == null || alreadyOwned(spans, first, last)
                        || alreadyOwned(source.candidates(), first, last)) continue;
                var dark = marks(original, width, height, staff, a.upper(), b.upper(), g);
                if (dark == null) continue;
                if (gray != original && !dark.equals(marks(gray, width, height, staff, a.upper(), b.upper(), g)))
                    continue;

                if (edited == null) edited = new ArrayList<>(notes);
                for (int owner : new int[] {start, a.lower().owner()}) {
                    var note = notes.get(owner);
                    edited.set(owner, note.withArticulations(note.articulations() | NoteArticulation.STACCATO));
                }
                origins.set(start, Optional.of(first));
                origins.set(end, Optional.of(last));
                var bow = dark.bow();
                spans.add(new ScoreSlurPages.Span(
                        "printed-page-" + page + "-staccato-arc-" + first.noteIndex() + "-" + last.noteIndex(),
                        first, last, ScoreSlurSpan.Placement.ABOVE, ScoreSlurSpan.LineType.SOLID,
                        List.of(new ScoreSlurSpan.ArcEvidence(page,
                                bow.start().x() / (float) width, bow.start().y() / height,
                                bow.end().x() / (float) width, bow.end().y() / height))));
            }
        }
        var candidates = promoteTripletPortato(score, page, original, gray, width, height, heads, spans);
        for (int i = 0; i < bars.size(); i++) {
            var region = score.measures().get(i);
            float gap = (region.bottom() - region.top()) * height / 8.2f;
            edited = retainAlternatingOctavePortato(notes, bars.get(i), region, gap, page,
                    original, gray, width, height, spans, origins, candidates, edited);
        }
        edited = retainHarmonicPortato(score, page, labels, original, gray, width, height, heads, spans, edited);
        edited = retainDottedBeamPortato(score, page, original, gray, width, height,
                heads, spans, origins, candidates, edited);
        if (edited == null) {
            return spans.size() == source.spans().size() ? score
                    : score.withSlurSource(new ScoreSlurSource(spans, origins, candidates))
                            .withClockContextSource(score.clockContextSource());
        }
        return score.withNotes(edited, IntStream.range(0, notes.size()).boxed().toList())
                .withSlurSource(new ScoreSlurSource(spans, origins, candidates))
                .withClockContextSource(score.clockContextSource());
    }


    /** A full bar of dotted octave pairs can lose the short arches before beam recovery. */
    private static List<ScoreNoteEvent> retainAlternatingOctavePortato(
            List<ScoreNoteEvent> notes, List<PrintedSlurOwners.GeometryHead> row, MeasureRegion bar,
            float gap, int page, byte[] raw, byte[] gray, int w, int h,
            List<ScoreSlurPages.Span> spans, List<Optional<ScoreSlurPages.Anchor>> origins,
            List<ScoreSlurPages.Span> candidates, List<ScoreNoteEvent> edited) {
        if (row.size() != 24 || gap < 12 || gap > 25 || bar.cadenceQuarterBeats() != 0)
            return edited;
        var octaves = new ArrayList<Dyad>();
        for (int i = 0; i < row.size(); i += 2) {
            var first = row.get(i);
            var second = row.get(i + 1);
            var top = first.y() < second.y() ? first : second;
            var low = top == first ? second : first;
            var upper = notes.get(top.owner());
            var lower = notes.get(low.owner());
            boolean dotted = i % 4 == 0;
            if (second.x() - first.x() > gap * .22f || upper.staffStep() - lower.staffStep() != 7
                    || upper.clefBottomDiatonic() != lower.clefBottomDiatonic()
                    || Math.abs(low.y() - top.y() - 3.5f * gap) > gap * .32f
                    || !alternatingOctaveHead(upper, dotted) || !alternatingOctaveHead(lower, dotted))
                return edited;
            octaves.add(new Dyad(top, low));
        }
        for (int i = 0; i < octaves.size(); i += 2) {
            var first = octaves.get(i);
            var last = octaves.get(i + 1);
            var a = first.upper();
            var b = last.upper();
            if (b.x() - a.x() < 2.1f * gap || b.x() - a.x() > 3.4f * gap
                    || b.y() - a.y() < .34f * gap || b.y() - a.y() > .74f * gap
                    || notes.get(a.owner()).staffStep() - notes.get(b.owner()).staffStep() != 1)
                continue;
            var start = origins.get(a.owner()).orElse(null);
            var end = origins.get(b.owner()).orElse(null);
            if (start == null || end == null || start.pageIndex() != page || end.pageIndex() != page)
                continue;
            var evidence = compactOctaveArc(raw, w, h, page, a, b, gap);
            if (evidence == null || !evidence.equals(compactOctaveArc(gray, w, h, page, a, b, gap)))
                continue;
            if (!alreadyOwned(spans, start, end) && !alreadyOwned(candidates, start, end)) {
                spans.add(new ScoreSlurPages.Span("printed-page-" + page + "-compact-octave-portato-"
                        + start.noteIndex() + "-" + end.noteIndex(), start, end,
                        ScoreSlurSpan.Placement.ABOVE, ScoreSlurSpan.LineType.SOLID, List.of(evidence)));
            }
            if (edited == null) edited = new ArrayList<>(notes);
            for (var head : List.of(a, first.lower(), b, last.lower())) {
                var note = edited.get(head.owner());
                edited.set(head.owner(), note.withArticulations(note.articulations() | NoteArticulation.STACCATO));
            }
        }
        return edited;
    }

    private static boolean alternatingOctaveHead(ScoreNoteEvent n, boolean dotted) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.stemDirection() == -1 && n.staffCount() == 1 && n.tupletDivisor() == 1
                && n.unbeamedDurationBeats() == 0 && !n.tiedFromPrevious() && n.boundaryTies() == 0
                && n.beamCount() >= 2 && n.beamCount() <= (dotted ? 2 : 3)
                && (dotted ? n.augmentationDots() >= 1 && n.augmentationDots() <= 2 : n.augmentationDots() == 0);
    }

    private static ScoreSlurSpan.ArcEvidence compactOctaveArc(byte[] ink, int w, int h, int page,
            PrintedSlurOwners.GeometryHead a, PrintedSlurOwners.GeometryHead b, float gap) {
        int left = Math.round(a.x() - .49f * gap), right = Math.round(b.x() + .39f * gap);
        int top = Math.round(a.y() - 2.24f * gap), bottom = Math.round(b.y() - .53f * gap);
        if (left < 0 || right >= w || top < 0 || bottom >= h || bottom <= top
                || !compactDotCore(ink, w, a, gap) || !compactDotCore(ink, w, b, gap)) return null;
        var region = RawNumeralComponents.capture(ink, w, h, left, top, right, bottom, 145, () -> {}, () -> {});
        ScoreSlurSpan.ArcEvidence found = null;
        for (var c : region.components()) {
            int span = c.right - c.left + 1;
            if (Math.abs(c.left - a.x()) > .30f * gap || Math.abs(c.right - b.x()) > .30f * gap
                    || c.top <= top || c.bottom >= bottom || span < 2.05f * gap
                    || c.size() < span * .14f * gap || c.size() > span * .34f * gap) continue;
            int[] profile = new int[span];
            java.util.Arrays.fill(profile, bottom);
            for (int i = 0; i < c.size(); i++) {
                int pixel = c.pixel(i);
                int column = pixel % w - c.left;
                profile[column] = Math.min(profile[column], pixel / w);
            }
            int startY = profile[0], endY = profile[span - 1];
            if (a.y() - startY < .98f * gap || a.y() - startY > 1.92f * gap
                    || b.y() - endY < 1.39f * gap || b.y() - endY > 2.08f * gap) continue;
            float rise = 0;
            boolean continuous = true;
            for (int i = 1; i < span; i++) {
                if (Math.abs(profile[i] - profile[i - 1]) > .22f * gap) continuous = false;
                if (i >= span / 4 && i <= span * 3 / 4)
                    rise = Math.max(rise, startY + i * (endY - startY) / (float) (span - 1) - profile[i]);
            }
            if (!continuous || rise < .35f * gap || rise > .81f * gap) continue;
            if (found != null) return null;
            found = new ScoreSlurSpan.ArcEvidence(page, c.left / (float) w, startY / (float) h,
                    c.right / (float) w, endY / (float) h);
        }
        return found;
    }

    private static boolean compactDotCore(byte[] ink, int width, PrintedSlurOwners.GeometryHead head, float gap) {
        int radius = Math.max(1, Math.round(gap * .105f));
        for (int y = Math.round(head.y() - gap * 1.27f); y <= Math.round(head.y() - gap * .96f); y++) {
            for (int x = Math.round(head.x() - gap * .29f); x <= Math.round(head.x() + gap * .21f); x++) {
                int dense = 0;
                for (int dy = -radius; dy <= radius; dy++)
                    for (int dx = -radius; dx <= radius; dx++)
                        if ((ink[(y + dy) * width + x + dx] & 255) < 145) dense++;
                if (dense >= (2 * radius + 1) * (2 * radius + 1) * .77f) return true;
            }
        }
        return false;
    }

    private static List<ScoreNoteEvent> retainDottedBeamPortato(
            ScorePageInterpretation score, int page, byte[] original, byte[] gray, int width, int height,
            List<PrintedSlurOwners.GeometryHead> heads, List<ScoreSlurPages.Span> spans,
            List<Optional<ScoreSlurPages.Anchor>> origins, List<ScoreSlurPages.Span> candidates,
            List<ScoreNoteEvent> edited) {
        var geometry = new PrintedSlurOwners.GeometryHead[score.notes().size()];
        for (var head : heads) geometry[head.owner()] = head;
        for (var group : score.beamSource().resolve().groups()) {
            var beam = group.source();
            if (beam.tupletMark() != null || !beam.bridges().equals(List.of(3, 3, 3))
                    || !beam.hooks().equals(List.of(
                            new ScoreBeamSource.Hook(1, 3, ScoreBeamSource.Direction.BACKWARD),
                            new ScoreBeamSource.Hook(3, 3, ScoreBeamSource.Direction.BACKWARD)))
                    || group.columns().stream().anyMatch(c -> c.ambiguous() || c.owners().size() != 2))
                continue;
            for (int i = 0; i < 4; i += 2) {
                var first = dottedPortatoColumn(score.notes(), geometry, group.columns().get(i).owners(), true);
                var last = dottedPortatoColumn(score.notes(), geometry, group.columns().get(i + 1).owners(), false);
                if (first == null || last == null) continue;
                var notes = score.notes();
                int openingInterval = notes.get(first.upper().owner()).staffStep()
                        - notes.get(first.lower().owner()).staffStep();
                int closingInterval = notes.get(last.upper().owner()).staffStep()
                        - notes.get(last.lower().owner()).staffStep();
                if (openingInterval != closingInterval) continue;
                if (openingInterval == 5) {
                    captureDescendingSixthPortato(score, page, original, gray, width, height,
                            first, last, spans, origins, candidates);
                    continue;
                }
                if (openingInterval != 7) continue;
                var a = first.upper();
                var b = last.upper();
                var aNote = score.notes().get(a.owner());
                var bNote = score.notes().get(b.owner());
                if (aNote.measureIndex() != bNote.measureIndex()
                        || aNote.octaveShift() != bNote.octaveShift()
                        || (aNote.articulations() & NoteArticulation.STACCATO) == 0) continue;
                var region = score.measures().get(aNote.measureIndex());
                float gap = (region.bottom() - region.top()) * height / 8.2f;
                if (gap < 12 || gap > 25 || b.x() - a.x() < 1.9f * gap || b.x() - a.x() > 3.3f * gap
                        || a.y() - b.y() < .68f * gap || a.y() - b.y() > 1.26f * gap) continue;
                var open = origins.get(a.owner()).orElse(null);
                var close = origins.get(b.owner()).orElse(null);
                if (!score.slurSource().hasOriginalOwners()) {
                    open = new ScoreSlurPages.Anchor(page, a.owner());
                    close = new ScoreSlurPages.Anchor(page, b.owner());
                }
                if (open == null || close == null || open.pageIndex() != page || close.pageIndex() != page
                        || alreadyOwned(spans, open, close) || alreadyOwned(candidates, open, close)) continue;
                var raw = upperOctavePortatoInk(original, width, height, region, a, b, gap);
                if (raw == null) continue;
                var normalized = original == gray ? raw : upperOctavePortatoInk(gray, width, height, region, a, b, gap);
                if (!raw.equals(normalized)) continue;
                origins.set(a.owner(), Optional.of(open));
                origins.set(b.owner(), Optional.of(close));
                var bow = raw.bow();
                spans.add(new ScoreSlurPages.Span("printed-page-" + page + "-octave-portato-"
                        + open.noteIndex() + "-" + close.noteIndex(), open, close,
                        ScoreSlurSpan.Placement.ABOVE, ScoreSlurSpan.LineType.SOLID, List.of(
                                new ScoreSlurSpan.ArcEvidence(page, bow.start().x() / (float) width,
                                        bow.start().y() / height, bow.end().x() / (float) width, bow.end().y() / height))));
                if (edited == null) edited = new ArrayList<>(score.notes());
                for (var head : List.of(first.upper(), first.lower(), last.upper(), last.lower())) {
                    var note = edited.get(head.owner());
                    edited.set(head.owner(), note.withArticulations(note.articulations() | NoteArticulation.STACCATO));
                }
            }
        }
        return edited;
    }

    private static void captureDescendingSixthPortato(ScorePageInterpretation score, int page,
            byte[] raw, byte[] gray, int w, int h, Dyad first, Dyad last,
            List<ScoreSlurPages.Span> spans, List<Optional<ScoreSlurPages.Anchor>> origins,
            List<ScoreSlurPages.Span> candidates) {
        var a = first.upper();
        var b = last.upper();
        var firstNote = score.notes().get(a.owner());
        var lastNote = score.notes().get(b.owner());
        var lead = score.notes().get(first.lower().owner());
        var stop = score.notes().get(last.lower().owner());
        if (firstNote.measureIndex() != lastNote.measureIndex()
                || firstNote.octaveShift() != lastNote.octaveShift()
                || firstNote.staffStep() - lastNote.staffStep() != 2
                || (lead.articulations() & stop.articulations() & NoteArticulation.STACCATO) == 0) return;
        var bar = score.measures().get(firstNote.measureIndex());
        float gap = (bar.bottom() - bar.top()) * h / 8.2f;
        if (gap < 12 || gap > 25 || b.x() - a.x() < 2.1f * gap || b.x() - a.x() > 2.75f * gap
                || b.y() - a.y() < .75f * gap || b.y() - a.y() > 1.18f * gap) return;
        // MusicXML attaches the slur to the lead head of the chord. The upper head locates the ink.
        var open = origins.get(first.lower().owner()).orElse(null);
        var close = origins.get(last.lower().owner()).orElse(null);
        if (!score.slurSource().hasOriginalOwners()) {
            open = new ScoreSlurPages.Anchor(page, first.lower().owner());
            close = new ScoreSlurPages.Anchor(page, last.lower().owner());
        }
        if (open == null || close == null || open.pageIndex() != page || close.pageIndex() != page) return;
        for (var source : List.of(spans, candidates)) {
            for (var startHead : List.of(first.lower(), first.upper())) {
                for (var endHead : List.of(last.lower(), last.upper())) {
                    var from = origins.get(startHead.owner()).orElse(null);
                    var to = origins.get(endHead.owner()).orElse(null);
                    if (from != null && to != null && alreadyOwned(source, from, to)) return;
                }
            }
        }
        var printed = descendingSixthArc(raw, w, h, page, a, b, gap);
        if (printed == null || !printed.equals(descendingSixthArc(gray, w, h, page, a, b, gap))) return;
        origins.set(first.lower().owner(), Optional.of(open));
        origins.set(last.lower().owner(), Optional.of(close));
        spans.add(new ScoreSlurPages.Span("printed-page-" + page + "-descending-portato-"
                + open.noteIndex() + "-" + close.noteIndex(), open, close,
                ScoreSlurSpan.Placement.ABOVE, ScoreSlurSpan.LineType.SOLID, List.of(printed)));
    }

    private static ScoreSlurSpan.ArcEvidence descendingSixthArc(byte[] ink, int w, int h, int page,
            PrintedSlurOwners.GeometryHead a, PrintedSlurOwners.GeometryHead b, float gap) {
        var first = dot(ink, w, h, a.x(), a.y(), gap, false, 1.56f);
        var last = dot(ink, w, h, b.x(), b.y(), gap);
        if (first == null || last == null) return null;
        int left = Math.round(first.center() - .40f * gap);
        int right = Math.round(last.center() + .48f * gap);
        int top = Math.round(first.top() - 1.15f * gap);
        int bottom = Math.round(last.top() - .30f * gap);
        if (left < 0 || right >= w || top < 0 || bottom >= h || bottom <= top) return null;
        var region = RawNumeralComponents.capture(ink, w, h, left, top, right, bottom,
                145, () -> {}, () -> {});
        ScoreSlurSpan.ArcEvidence result = null;
        for (var component : region.components()) {
            if (component.left < first.center() + .14f * gap || component.left > first.center() + .43f * gap
                    || Math.abs(component.right - last.center()) > .28f * gap
                    || component.top <= top || component.bottom >= bottom) continue;
            float[] profile = descendingArcProfile(component, w, gap);
            if (profile == null) continue;
            if (first.top() - profile[0] < .08f * gap || first.top() - profile[0] > .62f * gap
                    || last.top() - profile[profile.length - 1] < .40f * gap
                    || last.top() - profile[profile.length - 1] > .83f * gap) continue;
            if (result != null) return null;
            result = new ScoreSlurSpan.ArcEvidence(page, component.left / (float) w, profile[0] / h,
                    component.right / (float) w, profile[profile.length - 1] / h);
        }
        return result;
    }

    private static float[] descendingArcProfile(RawNumeralComponents.Component component, int width, float gap) {
        int span = component.right - component.left + 1;
        if (span < 2.12f * gap || span > 2.63f * gap || component.size() < span * .13f * gap
                || component.size() > span * .30f * gap) return null;
        int[] count = new int[span];
        int[] sum = new int[span];
        for (int i = 0; i < component.size(); i++) {
            int pixel = component.pixel(i);
            int slot = pixel % width - component.left;
            count[slot]++;
            sum[slot] += pixel / width;
        }
        float[] y = new float[span];
        for (int i = 0; i < span; i++) {
            if (count[i] == 0 || count[i] > .42f * gap) return null;
            y[i] = sum[i] / (float) count[i];
            if (i > 0 && Math.abs(y[i] - y[i - 1]) > .16f * gap) return null;
        }
        float slope = y[span - 1] - y[0];
        if (slope < .46f * gap || slope > .94f * gap) return null;
        float min = Float.POSITIVE_INFINITY;
        float max = 0;
        for (int i = span / 4; i <= span * 3 / 4; i++) {
            float lift = y[0] + slope * i / (span - 1) - y[i];
            min = Math.min(min, lift);
            max = Math.max(max, lift);
        }
        return min >= .22f * gap && max >= .33f * gap && max <= .60f * gap ? y : null;
    }

    private static Dyad dottedPortatoColumn(List<ScoreNoteEvent> notes,
            PrintedSlurOwners.GeometryHead[] geometry, List<Integer> owners, boolean dotted) {
        var a = geometry[owners.get(0)];
        var b = geometry[owners.get(1)];
        if (a == null || b == null) return null;
        var top = a.y() < b.y() ? a : b;
        var lower = top == a ? b : a;
        for (var head : List.of(top, lower)) {
            var n = notes.get(head.owner());
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.stemDirection() != -1 || n.staffCount() != 1
                    || n.augmentationDots() != (dotted ? 1 : 0) || n.beamCount() != (dotted ? 2 : 3)
                    || n.tupletDivisor() != 1 || n.unbeamedDurationBeats() != 0 || n.tiedFromPrevious()
                    || n.boundaryTies() != 0 || n.visualSize() != ScoreNoteEvent.VisualSize.FULL) return null;
        }
        return new Dyad(top, lower);
    }

    private static Marks upperOctavePortatoInk(byte[] gray, int width, int height, MeasureRegion bar,
            PrintedSlurOwners.GeometryHead a, PrintedSlurOwners.GeometryHead b, float gap) {
        float bottom = bar.bottom() * height - 2.1f * gap;
        var staff = StaffPitchTrack.forPrintedSlurs(gray, width, height, bottom - 4 * gap, bottom, gap);
        if (staff == null) return null;
        var first = dot(gray, width, height, a.x(), a.y(), gap);
        if (first == null) return null;
        var last = dot(gray, width, height, b.x(), b.y(), gap);
        if (last != null) {
            var existing = marks(gray, width, height, staff, a, b, gap);
            if (existing != null) return existing;
        } else {
            last = touchingPortatoFoot(gray, width, height, b, gap);
        }
        if (last == null) return null;
        var bow = portatoComponentBow(gray, width, height, staff, first, last, gap);
        return bow == null ? null : new Marks(first, last, bow);
    }

    private static Dot touchingPortatoFoot(byte[] gray, int width, int height,
            PrintedSlurOwners.GeometryHead head, float gap) {
        int left = Math.round(head.x() - gap * .32f), right = Math.round(head.x() + gap * .32f);
        int bottom = Math.round(head.y() - gap * .70f);
        int top = bottom - Math.round(gap * .37f);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return null;
        for (int x = left; x <= right; x++)
            if (ink(gray, width, x, bottom)) return null;
        int foot = bottom - 1;
        int min = right + 1, max = left - 1, area = 0;
        for (int y = top; y <= foot; y++) {
            int rowInk = 0;
            for (int x = left; x <= right; x++) {
                if (!ink(gray, width, x, y)) continue;
                rowInk++;
                min = Math.min(min, x);
                max = Math.max(max, x);
            }
            if (rowInk < gap * .20f || rowInk > gap * .46f) return null;
            area += rowInk;
        }
        float center = (min + max) * .5f;
        if (max - min + 1 > .53f * gap || Math.abs(center - head.x()) > .23f * gap
                || area < (max - min + 1) * (foot - top + 1) * .71f) return null;
        return new Dot(min, top, max, foot, center);
    }

    private static PrintedSlurContours.Bow portatoComponentBow(byte[] gray, int width, int height,
            StaffPitchTrack staff, Dot first, Dot last, float gap) {
        int left = Math.round(first.center() - .65f * gap);
        int right = Math.round(last.center() + .65f * gap);
        int top = Math.round(last.top() - 1.45f * gap);
        int bottom = first.top() - 1;
        if (left < 0 || right >= width || top < 0 || bottom >= height || bottom <= top) return null;
        var components = RawNumeralComponents.capture(gray, width, height, left, top, right, bottom,
                155, () -> {}, () -> {});
        PrintedSlurContours.Bow answer = null;
        for (var component : components.components()) {
            if (component.left < first.center() - .45f * gap || component.left > first.center() + .33f * gap
                    || component.right < last.center() - .20f * gap || component.right > last.center() + .47f * gap
                    || component.top <= top) continue;
            int stop = Math.min(component.right, Math.round(last.center() - .14f * gap));
            int[] edge = new int[stop - component.left + 1];
            java.util.Arrays.fill(edge, -1);
            for (int i = 0; i < component.size(); i++) {
                int pixel = component.pixel(i);
                int x = pixel % width, y = pixel / width;
                if (x <= stop && (x < last.left() || y < last.top()))
                    edge[x - component.left] = Math.max(edge[x - component.left], y);
            }
            var runs = new ArrayList<PrintedSlurContours.ForeignRun>(edge.length);
            for (int i = 0; i < edge.length; i++) {
                if (edge[i] < 0 || i > 0 && Math.abs(edge[i] - edge[i - 1]) > gap * .25f) break;
                runs.add(new PrintedSlurContours.ForeignRun(component.left + i,
                            List.of(new PrintedSlurContours.RawBand(edge[i], edge[i]))));
            }
            if (runs.size() != edge.length
                    || first.top() - edge[0] < 0 || first.top() - edge[0] > .60f * gap
                    || last.top() - edge[edge.length - 1] < 0 || last.top() - edge[edge.length - 1] > .58f * gap)
                continue;
            var bow = PrintedSlurContours.fitPixelCells(runs, staff);
            if (bow == null || !bow.above() || bow.asymmetric() || bow.bendInGaps() < .28f
                    || bow.bendInGaps() > 1.05f || bow.rmsInGaps() > .084f) continue;
            if (answer != null) return null;
            answer = bow;
        }
        return answer;
    }

    private record BeamPortatoInk(Dot first, Dot second, PrintedSlurContours.Bow bow) {}

    private static List<ScoreNoteEvent> retainHarmonicPortato(
            ScorePageInterpretation score, int page, byte[] labels, byte[] raw, byte[] gray, int width, int height,
            List<PrintedSlurOwners.GeometryHead> heads, List<ScoreSlurPages.Span> spans,
            List<ScoreNoteEvent> edited) {
        var source = score.slurSource();
        var geometry = new PrintedSlurOwners.GeometryHead[score.notes().size()];
        for (var head : heads) geometry[head.owner()] = head;
        for (var beam : score.beamSource().resolve().groups()) {
            if (beam.source().tupletMark() != ScoreBeamSource.TupletMark.UNNUMBERED
                    || !beam.source().bridges().equals(List.of(1, 1)) || !beam.source().hooks().isEmpty()
                    || beam.columns().stream().anyMatch(c -> c.owners().size() != 1)) continue;
            int left = beam.columns().get(1).owners().get(0);
            int right = beam.columns().get(2).owners().get(0);
            var a = score.notes().get(left);
            var b = score.notes().get(right);
            var start = source.noteOrigins().get(left).orElse(null);
            var stop = source.noteOrigins().get(right).orElse(null);
            if (a.measureIndex() != b.measureIndex() || !downstemHarmonicTriplet(a)
                    || !downstemHarmonicTriplet(b) || start == null || stop == null
                    || start.pageIndex() != page || stop.pageIndex() != page
                    || spans.stream().anyMatch(s -> s.start().equals(start) && s.end().equals(stop))
                    || source.candidates().stream().anyMatch(s -> s.start().equals(start) && s.end().equals(stop)))
                continue;
            var bar = score.measures().get(a.measureIndex());
            var first = geometry[left];
            var second = geometry[right];
            var ink = (b.articulations() & NoteArticulation.STACCATO) == 0 ? null
                    : harmonicBeamPortato(raw,width,height,bar,first,second);
            var upper = harmonicTouchPortato(labels,raw,width,height,bar,first,second);
            if ((ink == null) == (upper == null)) continue;
            boolean above = upper != null;
            if (above) ink = upper;
            var normalized = raw == gray ? ink : above
                    ? harmonicTouchPortato(labels,gray,width,height,bar,first,second)
                    : harmonicBeamPortato(gray,width,height,bar,first,second);
            if (!ink.equals(normalized)) continue;
            var placement = above ? ScoreSlurSpan.Placement.ABOVE : ScoreSlurSpan.Placement.BELOW;
            String location = above ? "-touch-tip-portato-" : "-beam-tip-portato-";
            if (edited == null) edited = new ArrayList<>(score.notes());
            for (int owner : new int[] {left,right}) {
                var note = edited.get(owner);
                edited.set(owner,note.withArticulations(note.articulations() | NoteArticulation.STACCATO));
            }
            var bow = ink.bow();
            spans.add(new ScoreSlurPages.Span("printed-page-" + page + location
                    + start.noteIndex() + "-" + stop.noteIndex(),start,stop,placement,
                    ScoreSlurSpan.LineType.SOLID,List.of(new ScoreSlurSpan.ArcEvidence(page,
                            bow.start().x()/(float)width,bow.start().y()/height,
                            bow.end().x()/(float)width,bow.end().y()/height))));
        }
        return edited;
    }

    private static BeamPortatoInk harmonicTouchPortato(byte[] labels, byte[] plane, int w, int h,
            MeasureRegion bar, PrintedSlurOwners.GeometryHead first, PrintedSlurOwners.GeometryHead second) {
        float gap = (bar.bottom() - bar.top()) * h / 8.2f;
        if (labels == null || first == null || second == null || gap < 10 || gap > 26
                || second.x()-first.x() < 1.6f*gap || second.x()-first.x() > 2.55f*gap
                || second.y()-first.y() < 1.1f*gap || second.y()-first.y() > 1.85f*gap) return null;
        var a = touchHead(first, gap);
        var b = touchHead(second, gap);
        if (!ArtificialHarmonics.ownsRaisedPortatoDiamond(labels,plane,w,h,first.x(),first.y(),a.y(),gap)
                || !ArtificialHarmonics.ownsRaisedPortatoDiamond(labels,plane,w,h,second.x(),second.y(),b.y(),gap))
            return null;
        var staff = WrappedMeasureBoundaries.verifiedTripletStaff(plane,w,h,bar,true);
        if (staff == null || !staff.verifiedAt(a.x()) || !staff.verifiedAt(b.x())) return null;
        var dot = upperTripletPortatoDot(plane,w,h,a,gap);
        var lobe = contactingUpperPortatoDot(plane,w,h,b,gap);
        if (dot == null || lobe == null) return null;
        int left=Math.round(a.x()-.5f*gap), right=Math.round(b.x()+.63f*gap);
        int top=Math.round(a.y()-2.6f*gap), bottom=lobe.top()-1;
        if (left<0 || right>=w || top<0 || bottom>=h || bottom<=top) return null;
        var bow=tripletPortatoComponent(plane,w,h,left,top,right,bottom,staff,a,b,gap,false,true);
        if (bow==null || dot.top()-bow.start().y()<.22f*gap || dot.top()-bow.start().y()>.7f*gap
                || lobe.top()-bow.end().y()>.28f*gap || lobe.top()-bow.end().y()<0
                || Math.abs(lobe.center()-bow.end().x())>.24f*gap
                || (plane[bottom*w+Math.round(bow.end().x())]&255)>165) return null;
        return new BeamPortatoInk(dot,lobe,bow);
    }

    private static PrintedSlurOwners.GeometryHead touchHead(PrintedSlurOwners.GeometryHead stopped, float gap) {
        return new PrintedSlurOwners.GeometryHead(stopped.owner(),stopped.x(),stopped.y()-1.5f*gap,
                stopped.staffIndex(),stopped.staffCount(),false);
    }

    private record InkRow(int left,int right) {
        int width() { return right-left+1; }
    }

    private static Dot contactingUpperPortatoDot(byte[] plane,int w,int h,
            PrintedSlurOwners.GeometryHead head,float gap) {
        int left=Math.round(head.x()-.64f*gap),right=Math.round(head.x()+.64f*gap);
        int top=Math.round(head.y()-2.0f*gap),bottom=Math.round(head.y()-.73f*gap);
        if (left<0 || right>=w || top<0 || bottom>=h) return null;
        var rows=new InkRow[bottom-top+1];
        for (int row=0;row<rows.length;row++) {
            int first=-1,last=-1,count=0;
            for(int x=left;x<=right;x++) if ((plane[(top+row)*w+x]&255)<=165) {
                if(first<0) first=x;
                last=x;
                count++;
            }
            if(count>0 && count==last-first+1) rows[row]=new InkRow(first,last);
            else if(count>0) rows[row]=new InkRow(left,right);
        }
        int neck=Math.round(.27f*gap),core=Math.round(.32f*gap);
        Dot owned=null;
        for(int row=3;row<rows.length-3;row++) {
            if(rows[row]==null || rows[row].width()<core
                    || !thinPortatoNeck(rows[row-1],neck) || !thinPortatoNeck(rows[row-2],neck)
                    || !thinPortatoNeck(rows[row-3],neck)) continue;
            int min=rows[row].left(),max=rows[row].right(),tail=row+1,area=rows[row].width();
            while(tail<rows.length && rows[tail]!=null) {
                var last=rows[tail-1];
                var next=rows[tail];
                if(Math.max(last.left(),next.left())>Math.min(last.right(),next.right())) break;
                min=Math.min(min,next.left());
                max=Math.max(max,next.right());
                area+=next.width();
                tail++;
            }
            int length=tail-row,span=max-min+1;
            float center=(min+max)*.5f;
            if(tail>=rows.length || rows[tail]!=null || min<=left || max>=right
                    || length<.32f*gap || length>.65f*gap || span<.35f*gap || span>.65f*gap
                    || area<span*length*.55f || Math.abs(center-head.x())>.23f*gap) continue;
            if(owned!=null) return null;
            owned=new Dot(min,top+row,max,top+tail-1,center);
        }
        return owned;
    }

    private static boolean thinPortatoNeck(InkRow row,int limit) {
        return row!=null && row.width()<=limit;
    }

    private static boolean downstemHarmonicTriplet(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.staffCount() == 1 && n.stemDirection() == -1 && n.octaveShift() == 2
                && n.tupletDivisor() == 3 && n.tupletNormalNotes() == 2 && n.beamCount() == 1
                && !n.crossStaffBeam() && !n.tiedFromPrevious() && n.boundaryTies() == 0
                && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static BeamPortatoInk harmonicBeamPortato(byte[] plane,int width,int height,MeasureRegion bar,
            PrintedSlurOwners.GeometryHead first,PrintedSlurOwners.GeometryHead second) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (first == null || second == null || gap < 10 || gap > 26
                || second.x()-first.x() < 1.60f*gap || second.x()-first.x() > 2.55f*gap
                || first.y()-second.y() < .45f*gap || first.y()-second.y() > 1.2f*gap) return null;
        var budget = new PrintedBeamShafts.Budget(100_000);
        var a = PrintedBeamShafts.at(plane,width,height,first.x(),first.y(),gap,budget);
        var b = PrintedBeamShafts.at(plane,width,height,second.x(),second.y(),gap,budget);
        if (a.size()!=1 || b.size()!=1 || a.get(0).direction()!=1 || b.get(0).direction()!=1
                || !SystemQuarterCadence.directHarmonicRail(plane,width,height,a.get(0),b.get(0),gap)) return null;
        var u = beamTipHead(first,a.get(0));
        var v = beamTipHead(second,b.get(0));
        var startDot = beamTipDot(plane,width,height,u,gap);
        var endDot = beamTipDot(plane,width,height,v,gap);
        if (startDot==null || endDot==null || startDot.top() <= endDot.bottom()) return null;
        var staff = WrappedMeasureBoundaries.verifiedTripletStaff(plane,width,height,bar,true);
        if (staff==null || !staff.verifiedAt(u.x()) || !staff.verifiedAt(v.x())) return null;
        int left=Math.round(u.x()-.45f*gap), right=Math.round(v.x()+.45f*gap);
        int top=Math.round(Math.min(u.y(),v.y())+.5f*gap), bottom=Math.round(Math.max(u.y(),v.y())+2.8f*gap);
        if(left<0 || right>=width || top<0 || bottom>=height) return null;
        byte[] separated = withoutContinuingPortatoRule(plane,width,height,left,right,top,bottom,staff,gap);
        if (separated==null) return null;
        var bow=tripletPortatoComponent(separated,width,height,left,top,right,bottom,staff,u,v,gap,true,false);
        if (bow==null || bow.start().y()-startDot.bottom() < .14f*gap
                || bow.start().y()-startDot.bottom() > .75f*gap
                || bow.end().y()-endDot.bottom() < .14f*gap || bow.end().y()-endDot.bottom() > .75f*gap)
            return null;
        return new BeamPortatoInk(startDot,endDot,bow);
    }

    private static PrintedSlurOwners.GeometryHead beamTipHead(PrintedSlurOwners.GeometryHead head,
            PrintedBeamShafts.Shaft shaft) {
        return new PrintedSlurOwners.GeometryHead(head.owner(),(shaft.left()+shaft.right())*.5f,
                shaft.endY(),head.staffIndex(),head.staffCount(),false);
    }

    private static Dot beamTipDot(byte[] plane,int width,int height,PrintedSlurOwners.GeometryHead head,float gap) {
        int left=Math.round(head.x()-.55f*gap), right=Math.round(head.x()+.55f*gap);
        int top=Math.round(head.y()+.32f*gap), bottom=Math.round(head.y()+1.85f*gap);
        if (left<0 || right>=width || top<0 || bottom>=height) return null;
        Dot dot=null;
        var ink=RawNumeralComponents.capture(plane,width,height,left,top,right,bottom,165,()->{},()->{});
        for (var c:ink.components()) {
            int w=c.right-c.left+1,h=c.bottom-c.top+1;
            float center=(c.left+c.right)*.5f;
            if(c.left<=left||c.right>=right||c.top<=top||c.bottom>=bottom
                    || Math.min(w,h)<.25f*gap||Math.max(w,h)>.62f*gap||Math.max(w,h)>1.4f*Math.min(w,h)
                    || c.size()<w*h*.55f || Math.abs(center-head.x())>.25f*gap) continue;
            if(dot!=null) return null;
            dot=new Dot(c.left,c.top,c.right,c.bottom,center);
        }
        return dot;
    }

    private static List<ScoreSlurPages.Span> promoteTripletPortato(
            ScorePageInterpretation score, int page, byte[] raw, byte[] gray, int width, int height,
            List<PrintedSlurOwners.GeometryHead> heads, List<ScoreSlurPages.Span> confirmed) {
        var source = score.slurSource();
        if (source.candidates().isEmpty()) return source.candidates();
        var geometry = new PrintedSlurOwners.GeometryHead[score.notes().size()];
        for (var head : heads) geometry[head.owner()] = head;
        var pairs = new java.util.HashSet<List<ScoreSlurPages.Anchor>>();
        for (var beam : score.beamSource().resolve().groups()) {
            if (!beam.source().bridges().equals(List.of(1, 1)) || !beam.source().hooks().isEmpty()
                    || beam.columns().stream().anyMatch(c -> c.owners().size() != 1)) continue;
            for (int i = 0; i < 2; i++) {
                int left = beam.columns().get(i).owners().get(0);
                int right = beam.columns().get(i + 1).owners().get(0);
                var a = source.noteOrigins().get(left).orElse(null);
                var b = source.noteOrigins().get(right).orElse(null);
                if (a != null && b != null && a.pageIndex() == page && b.pageIndex() == page
                        && tripletPortatoPair(score.notes().get(left), score.notes().get(right)))
                    pairs.add(List.of(a,b));
            }
        }
        if (pairs.isEmpty()) return source.candidates();
        var owners = new java.util.HashMap<ScoreSlurPages.Anchor,Integer>();
        for (int i = 0; i < source.noteOrigins().size(); i++) {
            var anchor = source.noteOrigins().get(i);
            if (anchor.isPresent()) owners.put(anchor.get(), i);
        }
        var remaining = new ArrayList<ScoreSlurPages.Span>();
        for (var candidate : source.candidates()) {
            if (!pairs.contains(List.of(candidate.start(),candidate.end()))
                    || candidate.lineType() != ScoreSlurSpan.LineType.SOLID
                    || candidate.evidence().size() != 1 || candidate.evidence().get(0).pageIndex() != page
                    || confirmed.stream().anyMatch(s -> s.start().equals(candidate.start())
                            && s.end().equals(candidate.end()))) {
                remaining.add(candidate);
                continue;
            }
            int left = owners.get(candidate.start()), right = owners.get(candidate.end());
            var a = geometry[left];
            var b = geometry[right];
            var bar = score.measures().get(score.notes().get(left).measureIndex());
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            boolean below = candidate.placement() == ScoreSlurSpan.Placement.BELOW;
            var bow = isolatedTripletPortato(raw,width,height,bar,a,b,gap,below);
            var corrected = raw == gray ? bow : isolatedTripletPortato(gray,width,height,bar,a,b,gap,below);
            if (bow == null || corrected == null || !bow.start().equals(corrected.start()) || !bow.end().equals(corrected.end())) {
                remaining.add(candidate);
                continue;
            }
            var trace = candidate.evidence().get(0);
            if (Math.abs(trace.startX()*width-bow.start().x()) > .62f*gap
                    || Math.abs(trace.startY()*height-bow.start().y()) > .62f*gap
                    || Math.abs(trace.endX()*width-bow.end().x()) > .62f*gap
                    || Math.abs(trace.endY()*height-bow.end().y()) > .62f*gap) {
                remaining.add(candidate);
                continue;
            }
            confirmed.add(new ScoreSlurPages.Span(candidate.id(),candidate.start(),candidate.end(),
                    candidate.placement(),candidate.lineType(),List.of(new ScoreSlurSpan.ArcEvidence(page,
                            bow.start().x()/(float)width,bow.start().y()/height,
                            bow.end().x()/(float)width,bow.end().y()/height))));
        }
        return remaining;
    }

    private static boolean tripletPortatoPair(ScoreNoteEvent a, ScoreNoteEvent b) {
        return a.measureIndex() == b.measureIndex() && a.staffCount() == 1 && b.staffCount() == 1
                && a.kind() == ScoreNoteEvent.Kind.PITCHED && b.kind() == ScoreNoteEvent.Kind.PITCHED
                && a.visualSize() == ScoreNoteEvent.VisualSize.FULL && b.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && a.tupletDivisor() == 3 && b.tupletDivisor() == 3
                && a.tupletNormalNotes() == 2 && b.tupletNormalNotes() == 2
                && a.beamCount() == 1 && b.beamCount() == 1 && !a.crossStaffBeam() && !b.crossStaffBeam()
                && a.stemDirection() == b.stemDirection() && !a.tiedFromPrevious() && !b.tiedFromPrevious()
                && a.boundaryTies() == 0 && b.boundaryTies() == 0
                && (a.articulations() & NoteArticulation.STACCATO) != 0
                && (b.articulations() & NoteArticulation.STACCATO) != 0;
    }

    private static PrintedSlurContours.Bow isolatedTripletPortato(byte[] plane, int w, int h, MeasureRegion bar,
            PrintedSlurOwners.GeometryHead a, PrintedSlurOwners.GeometryHead b,float gap,boolean below) {
        if(a==null || b==null || gap<10 || gap>26
                || b.x()-a.x()<1.65f*gap || b.x()-a.x()>2.65f*gap || Math.abs(a.y()-b.y())>1.3f*gap
                || !hasTripletPortatoDot(plane,w,h,a,gap,below) || !hasTripletPortatoDot(plane,w,h,b,gap,below)) return null;
        var staff=WrappedMeasureBoundaries.verifiedTripletStaff(plane,w,h,bar,true);
        if(staff==null || !staff.verifiedAt(a.x()) || !staff.verifiedAt(b.x())) return null;
        int left=Math.round(a.x()-.45f*gap), right=Math.round(b.x()+.45f*gap);
        float upper=Math.min(a.y(),b.y()), lower=Math.max(a.y(),b.y());
        int top=Math.round(below?upper+.8f*gap:upper-2.55f*gap);
        int bottom=Math.round(below?lower+2.5f*gap:lower-.75f*gap);
        if(left<0 || right>=w || top<0 || bottom>=h || top>=bottom) return null;
        return tripletPortatoComponent(plane,w,h,left,top,right,bottom,staff,a,b,gap,below,false);
    }

    private static PrintedSlurContours.Bow tripletPortatoComponent(byte[] plane, int w, int h,
            int left,int top,int right,int bottom,StaffPitchTrack staff,
            PrintedSlurOwners.GeometryHead a,PrintedSlurOwners.GeometryHead b,float gap,boolean below,boolean contactingTip) {
        var ink=RawNumeralComponents.capture(plane,w,h,left,top,right,bottom,165,()->{},()->{});
        PrintedSlurContours.Bow owned=null;
        for(var c:ink.components()) {
            int span=c.right-c.left+1;
            if(span<gap*1.3f || span>gap*2.5f || c.left<=left || c.right>=right || c.top<=top || (contactingTip ? c.bottom!=bottom : c.bottom>=bottom)
                    || Math.abs(c.left-a.x())>.46f*gap || Math.abs(c.right-b.x())>.46f*gap) continue;
            int[] tops=new int[span], bottoms=new int[span], counts=new int[span];
            java.util.Arrays.fill(tops,h);
            for(int j=0;j<c.size();j++) {
                int pixel=c.pixel(j);
                int x=pixel%w-c.left,y=pixel/w;
                tops[x]=Math.min(tops[x],y);
                bottoms[x]=Math.max(bottoms[x],y);
                counts[x]++;
            }
            var runs=new ArrayList<PrintedSlurContours.ForeignRun>(span);
            for(int x=0;x<span;x++) {
                int thick=bottoms[x]-tops[x]+1;
                float limit=contactingTip && x>=span-.48f*gap ? .74f : .52f;
                if (thick<1 || thick>gap*limit || thick!=counts[x]) break;
                runs.add(new PrintedSlurContours.ForeignRun(c.left+x,List.of(new PrintedSlurContours.RawBand(tops[x],bottoms[x]))));
            }
            if(runs.size()!=span) continue;
            var fit=PrintedSlurContours.fitPixelCells(runs,staff);
            if(fit==null || fit.above()==below || fit.asymmetric()
                    || fit.bendInGaps()<.28f || fit.bendInGaps()>.74f || fit.rmsInGaps()>(contactingTip?.065f:.05f)) continue;
            if(owned!=null) return null;
            owned=fit;
        }
        return owned;
    }

    private static byte[] withoutContinuingPortatoRule(byte[] plane,int w,int h,int left,int right,
            int top,int bottom,StaffPitchTrack staff,float gap) {
        int shoulder=Math.round(.65f*gap);
        if (left<shoulder || right+shoulder>=w
                || !staff.verifiedAt(left-shoulder) || !staff.verifiedAt(right+shoulder)) return null;
        var rows = new int[right-left+2*shoulder+1];
        int first=left-shoulder;
        for (int i=0;i<rows.length;i++) {
            int row=Math.round(staff.at(first+i)[0]);
            if (row<3 || row+3>=h) return null;
            rows[i]=row;
        }
        if (rows[shoulder]<top || rows[rows.length-shoulder-1]>bottom
                || ruleCoverage(plane,w,first,rows,0,0,rows.length)<.93f
                || ruleCoverage(plane,w,first,rows,0,0,shoulder)<.92f
                || ruleCoverage(plane,w,first,rows,0,rows.length-shoulder,rows.length)<.92f) return null;
        int above=0,below=0;
        for (int i=1;i<=Math.min(3,Math.round(gap*.20f));i++) {
            if (above==i-1 && ruleCoverage(plane,w,first,rows,-i,0,rows.length)>=.72f) above=i;
            if (below==i-1 && ruleCoverage(plane,w,first,rows,i,0,rows.length)>=.72f) below=i;
        }
        byte[] separated=plane.clone();
        for(int x=left;x<=right;x++) {
            int row=rows[x-first];
            for (int y=Math.max(top,row-above);y<=Math.min(bottom,row+below);y++)
                separated[y*w+x]=(byte)255;
        }
        return separated;
    }

    private static float ruleCoverage(byte[] plane,int w,int first,int[] rows,int offset,int start,int stop) {
        int ink=0;
        for(int i=start;i<stop;i++) if ((plane[(rows[i]+offset)*w+first+i]&255)<=165) ink++;
        return (float)ink/(stop-start);
    }

    private static boolean hasTripletPortatoDot(byte[] plane,int w,int h,
            PrintedSlurOwners.GeometryHead head,float gap,boolean below) {
        return below ? looseLowerDot(plane,w,h,head,gap) : upperTripletPortatoDot(plane,w,h,head,gap)!=null;
    }

    private static Dot upperTripletPortatoDot(byte[] plane,int w,int h,
            PrintedSlurOwners.GeometryHead head,float gap) {
        int left=Math.round(head.x()-.7f*gap), right=Math.round(head.x()+.7f*gap);
        int top=Math.round(head.y()-1.96f*gap), bottom=Math.round(head.y()-.7f*gap);
        if(left<0 || right>=w || top<0 || bottom>=h) return null;
        var graph=RawNumeralComponents.capture(plane,w,h,left,top,right,bottom,()->{},()->{});
        Dot owned=null;
        for(var c:graph.components()) {
            float width=c.right-c.left+1,height=c.bottom-c.top+1;
            float center=(c.left+c.right)*.5f;
            if(c.left<=left || c.right>=right || c.top<=top || c.bottom>=bottom
                    || Math.abs(center-head.x())>=gap*.22f
                    || Math.min(width,height)<gap*.24f || Math.max(width,height)>gap*.59f
                    || Math.max(width,height)>1.4f*Math.min(width,height) || c.size()<width*height*.54f) continue;
            if(owned!=null) return null;
            owned=new Dot(c.left,c.top,c.right,c.bottom,center);
        }
        return owned;
    }

    private static ScoreSlurPages.Span openingUpperThirdSlur(
            ScorePageInterpretation score, List<PrintedSlurOwners.GeometryHead> row, MeasureRegion region,
            byte[] gray, byte[] original, int width, int height, float gap, int page) {
        if (row.size() != 24 || gap < 12 || gap > 22 || region.cadenceQuarterBeats() != 0) return null;
        var a = openingThird(row, 0, score.notes(), gap);
        var b = openingThird(row, 2, score.notes(), gap);
        if (a == null || b == null) return null;
        float margin = a.x() - region.left() * width;
        float advance = b.x() - a.x();
        if (margin < .8f * gap || margin > 2.4f * gap || advance < 2.85f * gap || advance > 3.6f * gap
                || b.y() - a.y() < .15f * gap || b.y() - a.y() > .65f * gap
                || score.notes().get(a.owner()).staffStep() - score.notes().get(b.owner()).staffStep() != 1)
            return null;
        var track = WrappedMeasureBoundaries.verifiedTripletStaff(gray, width, height, region, true);
        if (track == null || !track.verifiedAt(a.x()) || !track.verifiedAt(b.x())) return null;
        var first = detachedUpperFringe(original, width, height, a, b, track, gap);
        if (first == null) return null;
        var second = detachedUpperFringe(gray, width, height, a, b, track, gap);
        if (second == null || !first.start().equals(second.start()) || !first.end().equals(second.end()))
            return null;
        String id = "printed-page-" + page + "-upper-third-" + a.owner() + "-" + b.owner();
        var source = PrintedSlurOwners.bindPixelCells(id, page, first, row, original, width, height, track)
                .span().orElse(null);
        var normalized = PrintedSlurOwners.bindPixelCells(id, page, second, row, gray, width, height, track)
                .span().orElse(null);
        return source != null && source.equals(normalized) && source.start().noteIndex() == a.owner()
                && source.end().noteIndex() == b.owner() ? source : null;
    }

    private static PrintedSlurOwners.GeometryHead openingThird(
            List<PrintedSlurOwners.GeometryHead> row, int index, List<ScoreNoteEvent> notes, float gap) {
        var a = row.get(index);
        var b = row.get(index + 1);
        var upper = a.y() < b.y() ? a : b;
        var lower = upper == a ? b : a;
        if (Math.abs(upper.x() - lower.x()) > .20f * gap || lower.y() - upper.y() < .8f * gap
                || lower.y() - upper.y() > 1.23f * gap
                || notes.get(upper.owner()).staffStep() - notes.get(lower.owner()).staffStep() != 2) return null;
        for (var head : List.of(upper, lower)) {
            var note = notes.get(head.owner());
            if (!plainSixteenth(note) || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || note.stemDirection() != -1 || note.boundaryTies() != 0 || note.tiedFromPrevious()
                    || note.articulations() != 0) return null;
        }
        return upper;
    }

    private static PrintedSlurContours.Bow detachedUpperFringe(
            byte[] ink, int width, int height, PrintedSlurOwners.GeometryHead a,
            PrintedSlurOwners.GeometryHead b, StaffPitchTrack track, float gap) {
        int left = Math.round(a.x() + .04f * gap), right = Math.round(b.x() - .13f * gap);
        int top = Math.round(a.y() - 1.95f * gap), bottom = Math.round(a.y() - .94f * gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return null;
        var columns = new ArrayList<PrintedSlurContours.ForeignRun>();
        // The upper fringe stays clear of the taller accidental under the arch.
        for (int x = left; x <= right; x++) {
            int y = top;
            while (y <= bottom && (ink[y * width + x] & 255) > 176) y++;
            int first = y;
            while (y <= bottom && (ink[y * width + x] & 255) <= 176) y++;
            if (y == first || y - first > Math.round(.31f * gap)) continue;
            columns.add(new PrintedSlurContours.ForeignRun(
                    x, List.of(new PrintedSlurContours.RawBand(first, y - 1))));
        }
        var bow = PrintedSlurContours.fitPixelCells(columns, track);
        return bow != null && bow.above() && !bow.asymmetric() ? bow : null;
    }

    private static Dyad sixth(
            PrintedSlurOwners.GeometryHead a, PrintedSlurOwners.GeometryHead b, List<ScoreNoteEvent> notes) {
        if (b.y() < a.y()) return sixth(b, a, notes);
        var upper = notes.get(a.owner());
        var lower = notes.get(b.owner());
        if (upper.staffStep() - lower.staffStep() != 5 || !plainSixteenth(upper) || !plainSixteenth(lower))
            return null;
        return new Dyad(a, b);
    }

    private static boolean plainSixteenth(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED && (note.articulations() & NoteOrnament.GRACE) == 0
                && note.beamCount() == 2 && note.stemDirection() == -1 && note.augmentationDots() == 0
                && note.tupletDivisor() == 1 && note.tupletNormalNotes() == 1
                && note.openStringTouch() == null && note.harmonicSemitones() == 0;
    }

    private static boolean staccatoPair(Dyad a, Dyad b, List<ScoreNoteEvent> notes) {
        int staccato = NoteArticulation.STACCATO;
        return (notes.get(a.upper().owner()).articulations() & staccato) == 0
                && (notes.get(a.lower().owner()).articulations() & staccato) == 0
                && (notes.get(b.upper().owner()).articulations() & staccato) != 0
                && (notes.get(b.lower().owner()).articulations() & staccato) != 0
                && notes.get(a.upper().owner()).staffStep() != notes.get(b.upper().owner()).staffStep()
                && !notes.get(b.upper().owner()).tiedFromPrevious()
                && !notes.get(b.lower().owner()).tiedFromPrevious();
    }

    private static boolean alreadyOwned(
            List<ScoreSlurPages.Span> spans, ScoreSlurPages.Anchor start, ScoreSlurPages.Anchor end) {
        return spans.stream().anyMatch(span -> span.placement() == ScoreSlurSpan.Placement.ABOVE
                && span.start().equals(start) && span.end().equals(end));
    }

    private record LowerPortato(int start, int partner, int end, PrintedSlurContours.Bow bow) {}

    private static LowerPortato openingLowerPortato(ScorePageInterpretation score,
            List<PrintedSlurOwners.GeometryHead> row, List<ScoreNoteEvent> notes, int measure,
            MeasureRegion region, byte[] gray, byte[] raw, int width, int height, float gap) {
        if (row.size() != 24 || gap < 12 || gap > 22 || region.cadenceQuarterBeats() != 0
                || score.rests().stream().anyMatch(r -> r.measureIndex() == measure)) return null;
        var meter = score.meterChanges().stream().filter(m -> m.measureIndex() <= measure)
                .reduce((a, b) -> b).orElse(null);
        if (meter == null || meter.numerator() != 3 || meter.denominator() != 4) return null;
        var lows = new ArrayList<PrintedSlurOwners.GeometryHead>(12);
        var highs = new ArrayList<PrintedSlurOwners.GeometryHead>(12);
        for (int i = 0; i < row.size(); i += 2) {
            var a = row.get(i);
            var b = row.get(i + 1);
            var low = a.y() > b.y() ? a : b;
            var high = a.y() > b.y() ? b : a;
            if (Math.abs(low.x() - high.x()) > gap * .21f
                    || notes.get(high.owner()).staffStep() - notes.get(low.owner()).staffStep() != 5
                    || i > 0 && low.x() - lows.get(lows.size() - 1).x() < gap * 1.65f) return null;
            for (var head : List.of(low, high)) {
                var n = notes.get(head.owner());
                if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.beamCount() != 2 || n.stemDirection() != 1
                        || n.tupletDivisor() != 1 || n.unbeamedDurationBeats() != 0
                        || n.visualSize() != ScoreNoteEvent.VisualSize.FULL || n.crossStaffBeam()
                        || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0
                        || ((i == 4 || i == 6) && n.tiedFromPrevious()) || n.boundaryTies() != 0
                        || n.augmentationDots() != (i == 4 ? 1 : 0)
                        || n.articulations() != (i == 4 || i == 6 ? NoteArticulation.STACCATO : 0)) return null;
            }
            lows.add(low);
            highs.add(high);
        }
        var a = lows.get(2);
        var b = lows.get(3);
        if (a.y() - b.y() < gap * .35f || a.y() - b.y() > gap * .7f
                || b.x() - a.x() < gap * 2 || b.x() - a.x() > gap * 2.65f) return null;
        var arc = lowerPortatoInk(raw, width, height, region, a, b, gap);
        var corrected = lowerPortatoInk(gray, width, height, region, a, b, gap);
        if (arc == null || corrected == null
                || !arc.start().equals(corrected.start()) || !arc.end().equals(corrected.end())) return null;
        return new LowerPortato(a.owner(), highs.get(2).owner(), b.owner(), arc);
    }

    private static PrintedSlurContours.Bow lowerPortatoInk(byte[] gray, int width, int height,
            MeasureRegion region, PrintedSlurOwners.GeometryHead a,
            PrintedSlurOwners.GeometryHead b, float gap) {
        if (!looseLowerDot(gray, width, height, a, gap) || !looseLowerDot(gray, width, height, b, gap)) return null;
        float bottom = region.bottom() * height - 2.1f * gap;
        var frame = new PlayingTechniqueDetector.Staff(bottom - 4 * gap, bottom, gap, 0, 1);
        var proof = new WholeStaffSlurRules.Page(gray, width, height);
        var track = proof.prove(gray, width, height, frame);
        if (track == null || !proof.finish()) return null;
        int left = Math.round(a.x() - gap * .6f), right = Math.round(b.x() + gap * .6f);
        int top = Math.round(b.y() + 1.3f * gap), low = Math.round(a.y() + 2.4f * gap);
        if (left < 0 || right >= width || top < 0 || low >= height) return null;
        var found = PrintedSlurContours.find(gray, null, width, height, left, top, right, low, track);
        if (found.limitReached()) return null;
        var matching = found.bows().stream().filter(arc -> !arc.above() && !arc.asymmetric()
                && arc.bendInGaps() >= .40f && arc.bendInGaps() <= .85f && arc.rmsInGaps() <= .06f
                && Math.abs(arc.start().x() - a.x()) <= gap * .45f
                && Math.abs(arc.end().x() - b.x()) <= gap * .45f).toList();
        return matching.size() == 1 ? matching.get(0) : null;
    }

    static boolean looseLowerDot(byte[] gray, int width, int height,
            PrintedSlurOwners.GeometryHead note, float gap) {
        int left = Math.round(note.x() - gap * .7f), right = Math.round(note.x() + gap * .7f);
        int top = Math.round(note.y() + gap * .7f), bottom = Math.round(note.y() + gap * 1.75f);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return false;
        var graph = RawNumeralComponents.capture(gray, width, height, left, top, right, bottom, () -> {}, () -> {});
        return graph.components().stream().filter(c -> {
            float w = c.right - c.left + 1, h = c.bottom - c.top + 1;
            return c.left > left && c.right < right && c.top > top && c.bottom < bottom
                    && Math.abs((c.left + c.right) * .5f - note.x()) < gap * .22f
                    && Math.min(w, h) >= gap * .26f && Math.max(w, h) <= gap * .58f
                    && Math.max(w, h) <= 1.34f * Math.min(w, h) && c.size() >= w * h * .55f;
        }).count() == 1;
    }

    /** A lower staccato at the next slur can enter a preceding sixteenth's augmentation window. */
    private static boolean ownedLowerDotsBeforeHalf(ScorePageInterpretation score, List<ScoreNoteEvent> notes,
            int measure, List<PrintedSlurOwners.GeometryHead> row, MeasureRegion bar,
            byte[] gray, byte[] raw, int width, int height, float gap) {
        if (gap < 10 || gap > 25 || bar.cadenceQuarterBeats() != 0) return false;
        var boxed = row.stream().map(h -> notes.get(h.owner())).toList();
        var dotted = boxed.get(1);
        var next = boxed.get(2);
        var closing = boxed.get(8);
        if (dotted.augmentationDots() != 1 || (next.articulations() & NoteArticulation.STACCATO) != 0
                || (boxed.get(3).articulations() & NoteArticulation.STACCATO) == 0
                || closing.kind() != ScoreNoteEvent.Kind.PITCHED || closing.unbeamedDurationBeats() != 2
                || closing.stemDirection() != 1 || closing.beamCount() != 0
                || closing.augmentationDots() != 0) return false;
        float advance = row.get(2).x() - row.get(1).x(), ascent = row.get(1).y() - row.get(2).y();
        if (advance < gap * 2.05f || advance > gap * 2.55f
                || ascent < gap * .88f || ascent > gap * 1.17f) return false;
        for (int i = 0; i < 8; i++) {
            var note = boxed.get(i);
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.beamCount() != 2
                    || note.stemDirection() != (i < 4 ? 1 : -1) || note.unbeamedDurationBeats() != 0
                    || note.augmentationDots() != (i == 1 ? 1 : 0)) return false;
        }
        for (int i = 0; i < boxed.size(); i++) {
            var note = boxed.get(i);
            if (note.visualSize() != ScoreNoteEvent.VisualSize.FULL || note.tupletDivisor() != 1
                    || note.crossStaffBeam() || note.tiedFromPrevious() || note.boundaryTies() != 0
                    || note.followingRestBeats() != 0 || note.leadingRestBeats() != 0
                    || (note.articulations() & NoteOrnament.GRACE) != 0
                    || i > 0 && row.get(i).x() - row.get(i - 1).x() < gap * 1.5f) return false;
        }
        var meter = score.meterChanges().stream().filter(m -> m.measureIndex() <= measure)
                .reduce((a, b) -> b).orElse(null);
        if (meter == null || meter.numerator() != 4 || meter.denominator() != 4
                || score.rests().stream().anyMatch(rest -> rest.measureIndex() == measure)) return false;
        var origin = score.slurSource().noteOrigins();
        var a = origin.get(row.get(2).owner()).orElse(null);
        var b = origin.get(row.get(3).owner()).orElse(null);
        if (a == null || b == null || score.slurSource().spans().stream().noneMatch(m ->
                m.placement() == ScoreSlurSpan.Placement.BELOW && m.start().equals(a) && m.end().equals(b)))
            return false;
        return lowerDotClosure(gray, width, height, row, bar, gap)
                && lowerDotClosure(raw, width, height, row, bar, gap);
    }

    private static boolean lowerDotClosure(byte[] plane, int width, int height,
            List<PrintedSlurOwners.GeometryHead> row, MeasureRegion bar, float gap) {
        var previous = row.get(1);
        var next = row.get(2);
        var other = row.get(3);
        var end = row.get(row.size() - 1);
        var a = dot(plane, width, height, next.x(), next.y(), gap, true);
        var b = dot(plane, width, height, other.x(), other.y(), gap, true);
        if (a == null || b == null || a.top() - previous.y() > gap * .65f
                || Math.abs((a.top() - next.y()) - (b.top() - other.y())) > gap * .28f
                || WrappedTripletDotInk.uniqueCore(
                        plane, width, height, previous.x(), previous.y(), next.x(), gap) != null
                || !WrappedMeasureBoundaries.confirmsClosedTripletBar(
                        plane, width, height, bar, row.get(0).x(), end.x())
                || end.x() < gap || end.x() >= width - gap || end.y() < gap || end.y() >= height - gap
                || !ClosedLedgerHalfDyad.hollow(plane, width, end.x(), end.y(), gap)) return false;
        var budget = new PrintedBeamShafts.Budget(100_000);
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(row.size());
        for (int i = 0; i < row.size(); i++) {
            var member = row.get(i);
            var source = PrintedBeamShafts.at(plane, width, height, member.x(), member.y(), gap, budget);
            if (source.size() != 1 || source.get(0).direction() != (i < 4 || i == 8 ? -1 : 1))
                return false;
            var shaft = source.get(0);
            if (i > 0 && i != 4 && i != 8 && !WrappedEighthTriplets.matchesBeamRails(
                    plane, width, height, shafts.get(shafts.size() - 1), shaft, gap, 2)) return false;
            shafts.add(shaft);
        }
        return !budget.refused();
    }

    /** Prove the owner of an upper staccato read as a dot on the preceding descending note. */
    private static Marks borrowedMonophonicDots(byte[] plane, int width, int height, StaffPitchTrack staff,
            PrintedSlurOwners.GeometryHead left, PrintedSlurOwners.GeometryHead right, float gap) {
        var fit = marks(plane, width, height, staff, left, right, gap, true);
        if (fit == null || WrappedTripletDotInk.uniqueCore(
                plane, width, height, left.x(), left.y(), right.x(), gap) != null) return null;
        float heightAbove = left.y() - (fit.end().top() + fit.end().bottom()) * .5f;
        if (heightAbove < gap * .43f || heightAbove > gap * .65f) return null;
        var budget = new PrintedBeamShafts.Budget(100_000);
        var a = PrintedBeamShafts.at(plane, width, height, left.x(), left.y(), gap, budget);
        var b = PrintedBeamShafts.at(plane, width, height, right.x(), right.y(), gap, budget);
        return a.size() == 1 && b.size() == 1 && a.get(0).direction() == 1
                && b.get(0).direction() == 1
                && WrappedEighthTriplets.matchesBeamRails(plane, width, height, a.get(0), b.get(0), gap, 2)
                ? fit : null;
    }

    private static Marks marks(
            byte[] gray, int width, int height, StaffPitchTrack staff,
            PrintedSlurOwners.GeometryHead a, PrintedSlurOwners.GeometryHead b, float gap) {
        return marks(gray, width, height, staff, a, b, gap, false);
    }

    private static Marks marks(byte[] gray, int width, int height, StaffPitchTrack staff,
            PrintedSlurOwners.GeometryHead a, PrintedSlurOwners.GeometryHead b, float gap, boolean touchingStart) {
        var start = dot(gray, width, height, a.x(), a.y(), gap);
        var end = dot(gray, width, height, b.x(), b.y(), gap);
        if (start == null || end == null) return null;
        int first = Math.round(start.center() - (touchingStart ? 0 : gap * .18f));
        int last = Math.round(end.center());
        var runs = new ArrayList<PrintedSlurContours.ForeignRun>();
        int previous = -1;
        for (int x = first; x <= last; x++) {
            float u = Math.max(0f, Math.min(1f, (x - start.center()) / (end.center() - start.center())));
            float ceiling = start.top() + u * (end.top() - start.top());
            if (x <= start.right()) ceiling = Math.min(ceiling, start.top());
            if (x >= end.left()) ceiling = Math.min(ceiling, end.top());
            int bottom = (int) Math.floor(ceiling) - 1;
            int top = Math.max(0, bottom - Math.round(gap * 1.45f));
            int y = bottom;
            while (y >= top && !ink(gray, width, x, y)) y--;
            if (y < top || previous >= 0 && Math.abs(y - previous) > gap * .24f) return null;
            int up = y;
            while (up > top && ink(gray, width, x, up - 1)) up--;
            // The lower contour stays visible when a numeral touches the far shoulder.
            float maxThickness = x >= last - gap * .16f ? .94f : .46f;
            if (y - up + 1 > Math.round(gap * maxThickness)) return null;
            runs.add(new PrintedSlurContours.ForeignRun(x, List.of(new PrintedSlurContours.RawBand(y, y))));
            previous = y;
        }
        var bow = PrintedSlurContours.fitPixelCells(runs, staff);
        if (bow == null || !bow.above() || bow.asymmetric()
                || bow.bendInGaps() < .30f || bow.bendInGaps() > .85f || bow.rmsInGaps() > .075f
                || start.top() - bow.start().y() > gap * .35f
                || end.top() - bow.end().y() > (touchingStart ? Math.round(gap * .38f) : gap * .35f)
                || touchingStart && start.top() - bow.start().y() > Math.round(gap * .13f)) return null;
        return new Marks(start, end, bow);
    }

    private static Dot dot(byte[] gray, int width, int height, float x, float y, float gap) {
        return dot(gray, width, height, x, y, gap, false);
    }

    private static Dot dot(byte[] gray, int width, int height, float x, float y, float gap, boolean below) {
        return dot(gray, width, height, x, y, gap, below, 1.4f);
    }

    private static Dot dot(
            byte[] gray, int width, int height, float x, float y, float gap, boolean below, float topDistance) {
        if (below) y = height - 1 - y - gap * .37f;
        int left = Math.round(x - gap * .52f), right = Math.round(x + gap * .52f);
        int top = Math.round(y - gap * topDistance), bottom = (int) Math.floor(y - gap * .76f);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return null;

        // The last ink belongs to the dot body if it is compact and centered over the head.
        int foot = top - 1;
        for (int row = bottom; row >= top && foot < top; row--)
            for (int col = left; col <= right; col++)
                if (ink(gray, width, col, below ? height - 1 - row : row)) {
                    foot = row;
                    break;
                }
        int first = Math.max(top, foot - Math.round(gap * .42f) + 1);
        int minX = right + 1, maxX = left - 1, minY = foot + 1, area = 0, sum = 0;
        for (int row = first; row <= foot; row++)
            for (int col = left; col <= right; col++)
                if (ink(gray, width, col, below ? height - 1 - row : row)) {
                    minX = Math.min(minX, col);
                    maxX = Math.max(maxX, col);
                    minY = Math.min(minY, row);
                    area++;
                    sum += col;
                }
        if (area == 0) return null;
        float w = maxX - minX + 1, h = foot - minY + 1, center = sum / (float) area;
        if (minX == left || maxX == right || foot == bottom
                || w < gap * .25f || w > gap * .54f || h < gap * .25f || h > gap * .54f
                || w < h * .65f || w > h * 1.52f || area < w * h * .67f
                || Math.abs(x - center) > gap * .22f) return null;
        return below ? new Dot(minX, height - 1 - foot, maxX, height - 1 - minY, center)
                : new Dot(minX, minY, maxX, foot, center);
    }

    private static boolean ink(byte[] gray, int width, int x, int y) {
        return (gray[y * width + x] & 255) < 155;
    }
}
