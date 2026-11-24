// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/** Distinguishes two level flat spines from the staggered natural that cancels them. */
final class BeamedDoubleFlatCarry {
    private BeamedDoubleFlatCarry() {}

    private record Spine(int left, int right, int top, int bottom) {
        float center() { return (left + right) * .5f; }
    }

    static ScorePageInterpretation reconcile(ScorePageInterpretation score, byte[] normalized, byte[] raw,
            int w, int h, Consumer<SourceLocalAccidental.ProvedMark> printed) {
        if (raw == null || normalized == null || raw.length != (long) w * h
                || normalized.length != raw.length) return score;
        var notes = score.notes();
        var rows = new ArrayList<List<Integer>>();
        for (var ignored : score.measures()) rows.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) rows.get(notes.get(i).measureIndex()).add(i);
        List<ScoreNoteEvent> repaired = null;
        for (int i = 0; i < rows.size(); i++) {
            var owners = rows.get(i);
            if (owners.size() != 14) continue;
            owners.sort(Comparator.comparingDouble(j -> notes.get(j).positionInMeasure()));
            var row = owners.stream().map(notes::get).toList();
            if (!matchesClock(row)) continue;
            var bar = score.measures().get(i);
            float gap = (bar.bottom() - bar.top()) * h / 8.2f;
            if (gap < 12 || gap > 23) continue;
            var doubleFlat = signs(normalized, w, h, bar, row, 2, gap);
            var cancellation = signs(normalized, w, h, bar, row, 9, gap);
            if (doubleFlat.size() != 2 || cancellation.size() != 3
                    || !doubleFlat.equals(signs(raw, w, h, bar, row, 2, gap))
                    || !cancellation.equals(signs(raw, w, h, bar, row, 9, gap))) continue;
            if (repaired == null) repaired = new ArrayList<>(notes);
            for (int index : new int[]{2, 4, 9, 11}) {
                int accidental = index < 6 ? ScoreNoteEvent.ACCIDENTAL_DOUBLE_FLAT : ScoreNoteEvent.ACCIDENTAL_FLAT;
                var note = row.get(index).withWrittenAccidental(accidental);
                repaired.set(owners.get(index), note);
                if (index == 2 || index == 9)
                    printed.accept(new SourceLocalAccidental.ProvedMark(note, accidental, false));
            }
            if (misreadLedgerNatural(row, bar, normalized, raw, w, h, gap)) {
                var stated = row.get(7).withStaffStep(row.get(13).staffStep());
                var repeated = row.get(13).withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_NATURAL);
                repaired.set(owners.get(7), stated);
                repaired.set(owners.get(13), repeated);
                printed.accept(new SourceLocalAccidental.ProvedMark(stated, false));
            }
        }
        return repaired == null ? score : score.withNotes(repaired,
                IntStream.range(0, notes.size()).boxed().toList());
    }

    private static boolean matchesClock(List<ScoreNoteEvent> row) {
        var first = row.get(0);
        for (int i = 0; i < row.size(); i++) {
            var n = row.get(i);
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffCount() != 1 || n.staffIndex() != 0
                    || n.beamCount() != (i < 6 ? 1 : 2) || Math.abs(n.stemDirection()) != 1
                    || n.unbeamedDurationBeats() != 0 || n.augmentationDots() != 0
                    || n.tupletDivisor() != (i < 6 ? 3 : 1) || n.tupletNormalNotes() != (i < 6 ? 2 : 1)
                    || n.tiedFromPrevious() || n.boundaryTies() != 0 || n.crossStaffBeam()
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL || n.octaveShift() != first.octaveShift()
                    || n.harmonicSemitones() != 0 || n.openStringTouch() != null) return false;
        }
        int pitch = row.get(2).diatonicPitchIdentity();
        return row.get(4).diatonicPitchIdentity() == pitch && row.get(9).diatonicPitchIdentity() == pitch
                && row.get(11).diatonicPitchIdentity() == pitch
                && row.get(2).writtenAccidental() == row.get(4).writtenAccidental()
                && row.get(9).writtenAccidental() == row.get(11).writtenAccidental();
    }

    private static boolean misreadLedgerNatural(List<ScoreNoteEvent> row, MeasureRegion bar,
            byte[] normalized, byte[] raw, int w, int h, float gap) {
        var stated = row.get(7);
        var repeated = row.get(13);
        int step = repeated.staffStep();
        if (step < 14 || step % 2 != 0 || stated.staffStep() != step + 1
                || row.get(8).staffStep() != step + 2 || row.get(12).staffStep() != step + 2
                || stated.clefBottomDiatonic() != repeated.clefBottomDiatonic()
                || stated.stemDirection() != -1 || repeated.stemDirection() != -1
                || stated.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_NATURAL
                || repeated.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                || Math.abs(stated.pageY() - repeated.pageY()) * h > .15f * gap) return false;
        float x = headX(bar, stated, w), y = stated.pageY() * h;
        float prev = headX(bar, row.get(6), w);
        var natural = AccidentalSlurSpines.prove(normalized, w, prev, x, y, gap, ScoreNoteEvent.ACCIDENTAL_NATURAL);
        if (natural.size() != 2 || !natural.equals(
                AccidentalSlurSpines.prove(raw, w, prev, x, y, gap, ScoreNoteEvent.ACCIDENTAL_NATURAL))) return false;
        float repeatX = headX(bar, repeated, w), repeatY = repeated.pageY() * h;
        float repeatPrev = headX(bar, row.get(12), w);
        for (var ink : List.of(normalized, raw)) {
            if (!ledgerCrossesHead(ink, w, h, x, y, gap)
                    || !ledgerCrossesHead(ink, w, h, repeatX, repeatY, gap)
                    || !clearAccidentalSpace(ink, w, h, repeatPrev, repeatX, repeatY, gap)) return false;
        }
        return true;
    }

    private static boolean ledgerCrossesHead(byte[] ink, int w, int h, float x, float y, float gap) {
        int from = Math.round(x + .26f * gap), to = Math.round(x + .82f * gap);
        int top = Math.round(y - .48f * gap), bottom = Math.round(y + 2.47f * gap);
        if (from < 0 || to >= w || top < 0 || bottom >= h) return false;
        int headRows = 0, upperRows = 0, lowerRows = 0, between = 0;
        for (int row = top; row <= bottom; row++) {
            float distance = (row - y) / gap;
            int end = distance <= .48f ? to : Math.round(x + .55f * gap);
            int dark = 0;
            for (int col = from; col <= end; col++)
                if ((ink[row * w + col] & 255) < 145) dark++;
            if (dark == end - from + 1) {
                if (Math.abs(distance) <= .27f) headRows++;
                if (distance >= .91f && distance <= 1.44f) upperRows++;
                if (distance >= 1.86f && distance <= 2.44f) lowerRows++;
            } else if (distance >= .45f && distance <= .83f && dark < (end - from + 1) / 2) {
                between++;
            }
        }
        return headRows >= 2 && upperRows >= 2 && lowerRows >= 2 && between >= 2;
    }

    static boolean clearAccidentalSpace(byte[] ink, int w, int h, float prev, float x, float y, float gap) {
        return clearAccidentalSpace(ink, w, h, prev, x, y, gap, .72f, .78f);
    }

    static boolean clearAccidentalSpace(byte[] ink, int w, int h, float prev, float x, float y, float gap,
            float previousClearance, float ownerClearance) {
        int left = Math.round(prev + previousClearance * gap), right = Math.round(x - ownerClearance * gap);
        int top = Math.round(y - 2.63f * gap), bottom = Math.round(y + 1.73f * gap);
        if (left < 0 || right >= w || top < 0 || bottom >= h || right - left < .31f * gap) return false;
        for (int col = left; col <= right; col++)
            if (longestSpine(ink, w, col, top, bottom, y, gap) != null) return false;
        return true;
    }

    private static List<Spine> signs(byte[] ink, int w, int h, MeasureRegion bar,
            List<ScoreNoteEvent> row, int slot, float gap) {
        float x = headX(bar, row.get(slot), w), y = row.get(slot).pageY() * h;
        float previous = headX(bar, row.get(slot - 1), w);
        return signs(ink, w, h, previous, x, y, gap, slot != 2);
    }

    static boolean hasCancellingFlat(byte[] ink, int w, int h, float previous, float x, float y, float gap) {
        return !signs(ink, w, h, previous, x, y, gap, true).isEmpty();
    }

    private static List<Spine> signs(byte[] ink, int w, int h, float previous, float x, float y,
            float gap, boolean cancellation) {
        int left = Math.round(Math.max(previous + .66f * gap, x - 3.30f * gap));
        int right = Math.round(x - .77f * gap);
        int top = Math.round(y - 2.62f * gap), bottom = Math.round(y + 1.78f * gap);
        if (left < 0 || right >= w || top < 0 || bottom >= h || right - left < 1.7f * gap)
            return List.of();
        var spines = new ArrayList<Spine>();
        for (int col = left; col <= right;) {
            var line = longestSpine(ink, w, col, top, bottom, y, gap);
            if (line == null) { col++; continue; }
            int start = col++;
            var best = line;
            for (; col <= right; col++) {
                line = longestSpine(ink, w, col, top, bottom, y, gap);
                if (line == null) break;
                if (line.bottom() - line.top() > best.bottom() - best.top()) best = line;
            }
            if (col - start >= 2 && col - start <= .42f * gap)
                spines.add(new Spine(start, col - 1, best.top(), best.bottom()));
        }
        if (spines.size() != (cancellation ? 3 : 2)) return List.of();
        var a = spines.get(0);
        var last = spines.get(spines.size() - 1);
        float separation = last.center() - a.center();
        if (separation < .80f * gap || separation > 1.36f * gap
                || x - last.center() < 1.35f * gap || x - last.center() > 2.03f * gap
                || !flatBowl(ink, w, left, right, last, y, gap)) return List.of();
        if (!cancellation) {
            if (Math.abs(a.top() - last.top()) > .21f * gap
                    || Math.abs(a.bottom() - last.bottom()) > .21f * gap
                    || !flatBowl(ink, w, left, spines.get(1).left() - 1, a, y, gap)) return List.of();
        } else {
            var middle = spines.get(1);
            float span = middle.center() - a.center();
            if (span < .43f * gap || span > .76f * gap || middle.top() - a.top() < .37f * gap
                    || middle.top() - a.top() > 1.19f * gap || middle.bottom() - a.bottom() < .27f * gap
                    || !naturalBars(ink, w, left, right, a, middle, y, gap)) return List.of();
        }
        return List.copyOf(spines);
    }

    private static Spine longestSpine(byte[] ink, int w, int x, int top, int bottom, float y, float gap) {
        int start = top;
        Spine best = null;
        for (int row = top; row <= bottom + 1; row++) {
            if (row <= bottom && (ink[row * w + x] & 255) < 145) continue;
            if (row - start >= 1.63f * gap && start < y - .75f * gap && row > y + .27f * gap
                    && (best == null || row - start > best.bottom() - best.top() + 1))
                best = new Spine(x, x, start, row - 1);
            start = row + 1;
        }
        return best;
    }

    private static boolean flatBowl(byte[] ink, int w, int left, int right, Spine spine, float y, float gap) {
        if (spine.top() > y - 1.43f * gap || spine.bottom() < y + .30f * gap
                || spine.bottom() > y + .78f * gap) return false;
        int from = Math.round(spine.center() + .47f * gap);
        int to = Math.min(right, Math.round(spine.center() + .81f * gap));
        if (to - from < .13f * gap) return false;
        int occupied = 0, upper = 0;
        for (int row = Math.round(y - 1.48f * gap); row <= Math.round(y + .48f * gap); row++) {
            if (staffRow(ink, w, left, right, row)) continue;
            int dark = 0;
            for (int x = from; x <= to; x++)
                if ((ink[row * w + x] & 255) < 155) dark++;
            if (dark < 2) continue;
            if (row < y - .83f * gap) upper++;
            else occupied++;
        }
        return upper <= 2 && occupied >= .42f * gap;
    }

    private static boolean naturalBars(byte[] ink, int w, int left, int right,
            Spine a, Spine b, float y, float gap) {
        int upper = 0, lower = 0, blank = 0;
        for (int row = Math.round(y - 1.03f * gap); row <= Math.round(y + .76f * gap); row++) {
            if (staffRow(ink, w, left, right, row)) continue;
            int dark = 0;
            for (int x = a.right() + 1; x < b.left(); x++)
                if ((ink[row * w + x] & 255) < 165) dark++;
            int columns = b.left() - a.right() - 1;
            if (dark >= columns * .68f) {
                if (row < y - .13f * gap) upper++;
                else lower++;
            }
            if (dark <= 1) blank++;
        }
        return upper >= 2 && lower >= 2 && blank >= 2;
    }

    private static boolean staffRow(byte[] ink, int w, int left, int right, int row) {
        int dark = 0;
        for (int x = left; x <= right; x++)
            if ((ink[row * w + x] & 255) < 165) dark++;
        return dark >= .84f * (right - left + 1);
    }

    private static float headX(MeasureRegion bar, ScoreNoteEvent n, int width) {
        return (bar.left() + (bar.right() - bar.left()) * n.positionInMeasure()) * width;
    }
}
