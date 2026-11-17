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
        if (edited == null) return score;
        return score.withNotes(edited, IntStream.range(0, notes.size()).boxed().toList())
                .withSlurSource(new ScoreSlurSource(spans, origins, source.candidates()))
                .withClockContextSource(score.clockContextSource());
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

    private static Marks marks(
            byte[] gray, int width, int height, StaffPitchTrack staff,
            PrintedSlurOwners.GeometryHead a, PrintedSlurOwners.GeometryHead b, float gap) {
        var start = dot(gray, width, height, a.x(), a.y(), gap);
        var end = dot(gray, width, height, b.x(), b.y(), gap);
        if (start == null || end == null) return null;
        int first = Math.round(start.center() - gap * .18f), last = Math.round(end.center());
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
                || end.top() - bow.end().y() > gap * .35f) return null;
        return new Marks(start, end, bow);
    }

    private static Dot dot(byte[] gray, int width, int height, float x, float y, float gap) {
        int left = Math.round(x - gap * .52f), right = Math.round(x + gap * .52f);
        int top = Math.round(y - gap * 1.4f), bottom = (int) Math.floor(y - gap * .76f);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return null;

        // The last ink belongs to the dot body if it is compact and centered over the head.
        int foot = top - 1;
        for (int row = bottom; row >= top && foot < top; row--)
            for (int col = left; col <= right; col++)
                if (ink(gray, width, col, row)) {
                    foot = row;
                    break;
                }
        int first = Math.max(top, foot - Math.round(gap * .42f) + 1);
        int minX = right + 1, maxX = left - 1, minY = foot + 1, area = 0, sum = 0;
        for (int row = first; row <= foot; row++)
            for (int col = left; col <= right; col++)
                if (ink(gray, width, col, row)) {
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
        return new Dot(minX, minY, maxX, foot, center);
    }

    private static boolean ink(byte[] gray, int width, int x, int y) {
        return (gray[y * width + x] & 255) < 155;
    }
}
