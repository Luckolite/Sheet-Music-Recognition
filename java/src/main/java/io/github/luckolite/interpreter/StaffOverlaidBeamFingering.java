// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Stem-side fingerings can overlap the two lowest rules below an elevated beam. */
final class StaffOverlaidBeamFingering {
    private StaffOverlaidBeamFingering() {}

    static List<PrintedFingeringDigits.Mark> include(List<PrintedFingeringDigits.Mark> existing,
            byte[] raw, byte[] normalized, int width, int height, List<SheetInterpreter.Word> words,
            ScorePageInterpretation score) {
        if (raw == null || normalized == null || raw.length != (long) width * height
                || normalized.length != raw.length) return existing;
        var source = score.beamSource().resolve();
        var notes = score.notes();
        var measures = score.measures();
        var result = new ArrayList<>(existing);
        for (int m = 1; m + 1 < measures.size(); m++) {
            var bar = measures.get(m);
            if (bar.top() <= measures.get(m - 1).bottom()) continue;
            final int index = m;
            var owners = java.util.stream.IntStream.range(0, notes.size())
                    .filter(i -> notes.get(i).measureIndex() == index).boxed()
                    .sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure())).toList();
            if (owners.size() != 14 || !fullyOwned(source, owners)) continue;
            var track = WrappedMeasureBoundaries.verifiedTripletStaff(raw, width, height, bar);
            var otherTrack = WrappedMeasureBoundaries.verifiedTripletStaff(normalized, width, height, bar);
            if (track == null || otherTrack == null) continue;
            float span = (bar.right() - bar.left()) * width;
            for (int owner : owners) {
                var note = notes.get(owner);
                if (note.stemDirection() != -1 || note.staffCount() != 1
                        || note.kind() != ScoreNoteEvent.Kind.PITCHED
                        || note.openStringTouch() != null || note.harmonicSemitones() != 0
                        || existing.stream().anyMatch(r -> r.noteIndex() == owner)) continue;
                float x = bar.left() * width + span * note.positionInMeasure();
                float y = note.pageY() * height;
                float[] a = track.at(x), b = otherTrack.at(x);
                if (Math.abs(a[0] - b[0]) > 1.5f || Math.abs(a[1] - b[1]) > .65f
                        || a[1] < 12 || a[1] > 24 || y >= a[0] - a[1] * 4.75f
                        || !(track.verifiedAt(x) || track.verifiedAt(x - a[1] * 2))
                        || !(otherTrack.verifiedAt(x) || otherTrack.verifiedAt(x - b[1] * 2))) continue;
                int hint = digitHint(words, width, height, x, a[0], a[1]);
                if (hint < -1) continue;
                int first = digit(raw, width, height, x, a[0], a[1], hint);
                int second = digit(normalized, width, height, x, b[0], b[1], hint);
                if (first > 0 && first == second)
                    result.add(new PrintedFingeringDigits.Mark(owner, first, PrintedFingeringDigits.Placement.BELOW));
            }
        }
        if (result.size() == existing.size()) return existing;
        result.sort(Comparator.comparingInt(PrintedFingeringDigits.Mark::noteIndex));
        return List.copyOf(result);
    }

    private static boolean fullyOwned(ScoreBeamSource.Resolution source, List<Integer> owners) {
        int hits = 0;
        for (var g : source.groups()) {
            var actual = g.columns().stream().map(c -> c.owners()).toList();
            for (int start : new int[] {0, 3, 6, 10}) {
                int length = start < 6 ? 3 : 4;
                if (!actual.equals(owners.subList(start, start + length).stream().map(List::of).toList()))
                    continue;
                if (start < 6 && g.source().tupletMark() == ScoreBeamSource.TupletMark.UNNUMBERED
                                && g.source().bridges().equals(List.of(1, 1))
                        || start >= 6 && g.source().bridges().equals(List.of(3, 3, 3))) hits++;
            }
        }
        return hits == 4;
    }

    private static int digitHint(List<SheetInterpreter.Word> words, int width, int height,
            float x, float bottom, float gap) {
        int hint = -1;
        for (var word : words) {
            float cx = (word.left() + word.right()) * width * .5f;
            float cy = (word.top() + word.bottom()) * height * .5f;
            if (Math.abs(cx - x) > gap * .44f || cy < bottom - gap * 1.25f
                    || cy > bottom + gap * .12f) continue;
            if (word.text().length() != 1 || "1234".indexOf(word.text().charAt(0)) < 0
                    || hint != -1) return -2;
            hint = word.text().charAt(0) - '0';
        }
        return hint;
    }

    static int digit(byte[] ink, int width, int height, float x, float bottom, float gap, int hint) {
        int left = Math.round(x - gap * .90f), right = Math.round(x + gap * 1.05f);
        int first = Math.round(bottom - gap * 1.48f), last = Math.round(bottom + gap * .27f);
        if (left < 0 || right >= width || first < 0 || last >= height) return -1;
        float upperRule = bottom - gap;
        int minX = width, maxX = -1, minY = height, maxY = -1, pixels = 0;
        int topHits = 0, lowerHits = 0, lastGap = -1;
        int column = -1, coreRows = 0, coreWidth = 0, footRows = 0;
        for (int y = first; y <= last; y++) {
            if (Math.abs(y - bottom) <= gap * .13f || Math.abs(y - upperRule) <= gap * .13f) continue;
            int low = width, high = -1, count = 0;
            for (int c = left; c <= right; c++) {
                if ((ink[y * width + c] & 255) >= 165) continue;
                low = Math.min(c, low);
                high = Math.max(c, high);
                count++;
            }
            int run = high - low + 1;
            if (count < 3 || run > gap * 1.10f || count < run * .58f) continue;
            if (low == left || high == right || lastGap >= 0 && y - lastGap > gap * .44f) return -1;
            lastGap = y;
            minX = Math.min(low, minX);
            maxX = Math.max(high, maxX);
            minY = Math.min(y, minY);
            maxY = Math.max(y, maxY);
            pixels += count;
            if (y < upperRule) topHits++;
            else lowerHits++;
            if (hint < 0 && y >= upperRule + gap * .19f && y <= bottom - gap * .37f) {
                int center = Math.round((low + high) * .5f);
                if (run < gap * .21f || run > gap * .45f
                        || column != -1 && Math.abs(center - column) > 1) return -1;
                column = center;
                coreRows++;
                coreWidth = Math.max(coreWidth, run);
            } else if (hint < 0 && y > bottom - gap * .37f && column >= 0
                    && low < column - coreWidth * .5f + 1 && high > column + coreWidth * .5f
                    && run >= coreWidth + 2) footRows++;
        }
        if (topHits < 1 || lowerHits < gap * .42f || pixels < gap * gap * .30f
                || maxY - minY < gap * .86f || maxY - minY > gap * 1.43f
                || Math.abs((minX + maxX) * .5f - x) > gap * .46f) return -1;
        if (hint > 0) return hint;
        return topHits >= 2 && coreRows >= gap * .34f && footRows >= 2
                && maxX - minX + 1 <= gap * .77f ? 1 : -1;
    }
}
