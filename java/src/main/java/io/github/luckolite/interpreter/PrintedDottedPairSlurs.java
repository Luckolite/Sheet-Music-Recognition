// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/** Reads complete short slurs between consecutive dotted-pair heads with different pitches. */
final class PrintedDottedPairSlurs {
    private PrintedDottedPairSlurs() {}

    static ScorePageInterpretation apply(ScorePageInterpretation score, byte[] raw, byte[] normalized,
            int width, int height, IntPredicate printedAccidental) {
        if (raw == null || normalized == null || raw.length != (long) width * height
                || raw.length != normalized.length) return score;
        var source = score.slurSource();
        var spans = new ArrayList<>(source.spans());
        var candidates = new ArrayList<>(source.candidates());
        var notes = score.notes();
        for (int i = 1; i < notes.size(); i++) {
            var first = notes.get(i - 1);
            var last = notes.get(i);
            boolean sameStepFlat = first.staffStep() == last.staffStep()
                    && first.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT
                    && last.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FLAT;
            boolean descendingNaturals = first.staffStep() == last.staffStep() + 1
                    && first.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL
                    && last.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL
                    && printedAccidental.test(i - 1);
            if (first.measureIndex() != last.measureIndex() || !(sameStepFlat || descendingNaturals)
                    || first.staffStep() < 6 || first.clefBottomDiatonic() != last.clefBottomDiatonic()
                    || first.stemDirection() != -1 || last.stemDirection() != -1
                    || !ClosedMonophonicSixteenths.regularBeamHead(first, 2)
                    || !ClosedMonophonicSixteenths.regularBeamHead(last, 3)
                    || first.tupletDivisor() != 1 || last.tupletDivisor() != 1
                    || first.augmentationDots() != 1 || last.augmentationDots() != 0
                    || first.tiedFromPrevious() || last.tiedFromPrevious() || !printedAccidental.test(i))
                continue;
            int firstPitch = ScoreTiePitchGuard.midi(first, score.keyChanges());
            int lastPitch = ScoreTiePitchGuard.midi(last, score.keyChanges());
            if (firstPitch == Integer.MIN_VALUE || lastPitch == Integer.MIN_VALUE || firstPitch == lastPitch)
                continue;
            var start = source.noteOrigins().get(i - 1).orElse(null);
            var end = source.noteOrigins().get(i).orElse(null);
            if (start == null || end == null || start.pageIndex() != end.pageIndex()
                    || start.noteIndex() >= end.noteIndex()
                    || spans.stream().anyMatch(s -> s.start().equals(start) && s.end().equals(end)))
                continue;
            var pending = candidates.stream().filter(s -> s.start().equals(start) && s.end().equals(end)).toList();
            if (pending.size() > 1 || !pending.isEmpty() && (pending.get(0).placement() != ScoreSlurSpan.Placement.ABOVE
                    || pending.get(0).lineType() != ScoreSlurSpan.LineType.SOLID))
                continue;
            var bar = score.measures().get(first.measureIndex());
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            float x1 = headX(bar, first, width), x2 = headX(bar, last, width);
            float y1 = first.pageY() * height, y2 = last.pageY() * height;
            if (gap < 12 || gap > 24 || x2 - x1 < 2.5f * gap || x2 - x1 > 3.65f * gap
                    || Math.abs(y2 - y1) > (descendingNaturals ? .70f : .30f) * gap) continue;
            var a = prove(raw, width, height, bar, gap, x1, y1, x2, y2, first.staffStep(), descendingNaturals);
            var b = raw == normalized ? a
                    : prove(normalized, width, height, bar, gap, x1, y1, x2, y2, first.staffStep(), descendingNaturals);
            if (a == null || b == null || Math.abs(a.start().x() - b.start().x()) > 2
                    || Math.abs(a.end().x() - b.end().x()) > 2 || Math.abs(a.start().y() - b.start().y()) > 2
                    || Math.abs(a.end().y() - b.end().y()) > 2) continue;
            int page = start.pageIndex();
            var evidence = new ScoreSlurSpan.ArcEvidence(page, a.start().x() / (float) width,
                    a.start().y() / height, a.end().x() / (float) width, a.end().y() / height);
            String id = pending.isEmpty() ? "printed-dotted-pair-slur:" + page + ":" + start.noteIndex()
                    : pending.get(0).id();
            spans.add(new ScoreSlurPages.Span(id, start, end, ScoreSlurSpan.Placement.ABOVE,
                    ScoreSlurSpan.LineType.SOLID, List.of(evidence)));
            candidates.removeAll(pending);
        }
        return spans.size() == source.spans().size() ? score
                : score.withSlurSource(new ScoreSlurSource(spans, source.noteOrigins(), candidates))
                        .withClockContextSource(score.clockContextSource());
    }

    private static PrintedSlurContours.Bow prove(byte[] ink, int width, int height, MeasureRegion bar,
            float gap, float x1, float y1, float x2, float y2, int step, boolean descending) {
        var rule = ClosedDottedSixteenths.localDottedPairStaff(ink, width, height,
                bar.right() * width - .72f * gap, y1 + step * gap * .5f, gap);
        if (rule == null) return null;
        var track = StaffPitchTrack.fromVerifiedSamples(List.of(
                new float[] {x1, rule[0], rule[1]}, new float[] {x2, rule[0], rule[1]}));
        int left = Math.round(x1 - gap), right = Math.round(x2 + gap);
        int top = Math.round(Math.min(y1, y2) - 2.85f * gap);
        int bottom = Math.round(Math.min(y1, y2) - .50f * gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return null;
        var graph = RawNumeralComponents.capture(
                ink, width, height, left, top, right, bottom, 155, () -> {}, () -> {});
        PrintedSlurContours.Bow result = null;
        for (var part : graph.components()) {
            if (part.left <= left + 1 || part.right >= right - 1 || part.top <= top + 1
                    || !descending && part.bottom >= bottom - 1 || Math.abs(part.left - x1) > .6f * gap
                    || Math.abs(part.right - x2) > .65f * gap || part.right - part.left < 2.35f * gap
                    || part.bottom - part.top < .65f * gap || part.bottom - part.top > 1.58f * gap)
                continue;
            var curve = fit(part, ink, width, track, rule[1], descending);
            if (curve == null) continue;
            if (result != null) return null;
            result = curve;
        }
        return result;
    }

    /** The connected tip can contain a staccato dot. Fit the thin upper contour between both tips. */
    private static PrintedSlurContours.Bow fit(RawNumeralComponents.Component part, byte[] ink,
            int width, StaffPitchTrack track, float gap, boolean descending) {
        int[] upper = new int[part.right - part.left + 1];
        java.util.Arrays.fill(upper, Integer.MAX_VALUE);
        for (int i = 0; i < part.size(); i++) {
            int pixel = part.pixel(i), x = pixel % width - part.left;
            upper[x] = Math.min(upper[x], pixel / width);
        }
        // Keep the inner contour when the unequal-height tips touch articulation ink.
        int trim = Math.round((descending ? .78f : .50f) * gap);
        var columns = new ArrayList<PrintedSlurContours.ForeignRun>();
        for (int i = trim; i < upper.length - trim; i++) {
            int y = upper[i], x = part.left + i;
            if (y == Integer.MAX_VALUE) return null;
            int bottom = y;
            while (bottom < part.bottom && (ink[(bottom + 1) * width + x] & 255) < 155) bottom++;
            if (bottom - y + 1 > .48f * gap) return null;
            columns.add(new PrintedSlurContours.ForeignRun(
                    x, List.of(new PrintedSlurContours.RawBand(y, bottom))));
        }
        var bow = PrintedSlurContours.fitPixelCells(columns, track);
        return bow != null && bow.above()
                && bow.bendInGaps() >= (descending ? .30f : .40f) && bow.bendInGaps() <= 1.20f
                && bow.rmsInGaps() <= (descending ? .035f : .050f) ? bow : null;
    }

    private static float headX(MeasureRegion bar, ScoreNoteEvent note, int width) {
        return (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
    }
}
