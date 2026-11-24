// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/** Preserve octave-specific accidental carry across an interior printed octave shift. */
final class PairedOctaveAccidentalCarry {
    private PairedOctaveAccidentalCarry() {}

    static ScorePageInterpretation repair(ScorePageInterpretation score, List<PlayingTechniqueDetector.Staff> staffs,
            byte[] raw, byte[] gray, int width, int height, List<OctaveMarkDetector.StaffSpan> spans,
            Consumer<SourceLocalAccidental.ProvedMark> printed) {
        if (raw == null || gray == null || raw.length != (long) width * height || raw.length != gray.length)
            return score;
        var notes = score.notes();
        var rows = new ArrayList<List<Integer>>();
        for (var ignored : score.measures()) rows.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) rows.get(notes.get(i).measureIndex()).add(i);
        List<ScoreNoteEvent> repaired = null;
        List<PlayingTechniqueDetector.Word> words = null;
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var bar = score.measures().get(i);
            if (row.size() != 32 || bar.cadenceQuarterBeats() != 0) continue;
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (gap < 12 || gap > 23) continue;
            row.sort(Comparator.comparingDouble(j -> notes.get(j).positionInMeasure()));
            var lower = new ArrayList<Integer>();
            var upper = new ArrayList<Integer>();
            for (int slot = 0; slot < 16; slot++) {
                int a = row.get(2 * slot), b = row.get(2 * slot + 1);
                if (notes.get(a).pageY() < notes.get(b).pageY()) {
                    upper.add(a);
                    lower.add(b);
                } else {
                    upper.add(b);
                    lower.add(a);
                }
            }
            var lows = lower.stream().map(notes::get).toList();
            var highs = upper.stream().map(notes::get).toList();
            if (!pairedScale(bar, lows, highs, width, height, gap)) continue;
            if (!witnesses(raw, width, height, bar, lows, highs, gap)
                    || !witnesses(gray, width, height, bar, lows, highs, gap)) continue;
            if (words == null) words = OctaveMarkDetector.printedWords(raw, width, height, staffs);
            float end = bracketEnd(words, raw, gray, width, height, bar, highs, gap);
            if (end < 0) continue;
            if (repaired == null) repaired = new ArrayList<>(notes);
            for (var owners : List.of(lower, upper)) {
                for (int slot : new int[]{1, 2, 3, 5, 6, 7, 9, 10, 11, 13, 14, 15}) {
                    var note = notes.get(owners.get(slot));
                    int accidental = switch (slot) {
                        case 3, 7, 9, 13 -> ScoreNoteEvent.ACCIDENTAL_FLAT;
                        default -> ScoreNoteEvent.ACCIDENTAL_NATURAL;
                    };
                    var changed = note.withWrittenAccidental(accidental);
                    repaired.set(owners.get(slot), changed);
                    if (slot < 8 && (slot != 5 || owners == upper
                            || provedNatural(raw, width, bar, lows, slot, height, gap)
                            && provedNatural(gray, width, bar, lows, slot, height, gap)))
                        printed.accept(new SourceLocalAccidental.ProvedMark(changed, false));
                }
            }
            int measure = i;
            if (spans.stream().noneMatch(span -> span.firstMeasureIndex() <= measure
                    && span.lastMeasureIndex() >= measure && span.staffIndex() == 0)) {
                float left = (x(bar, highs.get(3), width) + x(bar, highs.get(4), width)) * .5f;
                spans.add(new OctaveMarkDetector.StaffSpan(i, i, 0, 1, 1,
                        new OctaveMarkDetector.Fragment(left, end)));
            }
        }
        return repaired == null ? score : score.withNotes(repaired,
                IntStream.range(0, notes.size()).boxed().toList());
    }

    private static boolean pairedScale(MeasureRegion bar, List<ScoreNoteEvent> lower, List<ScoreNoteEvent> upper,
            int width, int height, float gap) {
        int[] steps = {0, 1, 3, 5, 0, 1, 3, 5, 7, 5, 3, 1, 7, 5, 3, 1};
        var first = lower.get(0);
        for (int slot = 0; slot < steps.length; slot++) {
            var lo = lower.get(slot);
            var hi = upper.get(slot);
            for (var n : List.of(lo, hi)) {
                if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffCount() != 1 || n.staffIndex() != 0
                        || n.beamCount() != 2 || n.tupletDivisor() != 1 || n.unbeamedDurationBeats() != 0
                        || n.augmentationDots() != 0 || n.tiedFromPrevious() || n.boundaryTies() != 0
                        || n.crossStaffBeam() || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                        || n.harmonicSemitones() != 0 || n.openStringTouch() != null
                        || n.clefBottomDiatonic() != first.clefBottomDiatonic()
                        || n.octaveShift() != (slot >= 4 && slot < 12 ? 1 : 0)) return false;
            }
            if (hi.stemDirection() != -1 || lo.staffStep() != first.staffStep() + steps[slot]
                    || hi.staffStep() != lo.staffStep() + 7
                    || Math.abs(x(bar, lo, width) - x(bar, hi, width)) > .25f * gap
                    || Math.abs((lo.pageY() - hi.pageY()) * height - 3.5f * gap) > .25f * gap)
                return false;
        }
        return true;
    }

    private static boolean witnesses(byte[] ink, int w, int h, MeasureRegion bar,
            List<ScoreNoteEvent> lows, List<ScoreNoteEvent> highs, float gap) {
        for (var voice : List.of(lows, highs)) {
            for (int slot : new int[]{1, 2, 5, 6}) {
                if (slot == 5 && voice == lows
                        && voice.get(slot).writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL) continue;
                if (!provedNatural(ink, w, bar, voice, slot, h, gap)) return false;
            }
            for (int slot : new int[]{3, 7}) {
                var n = voice.get(slot);
                if (!BeamedDoubleFlatCarry.hasCancellingFlat(ink, w, h, x(bar, voice.get(slot - 1), w),
                        x(bar, n, w), n.pageY() * h, gap)) return false;
            }
            for (int slot : new int[]{9, 10, 11, 13, 14, 15}) {
                var n = voice.get(slot);
                if (!clearCarry(ink, w, h, bar, voice.get(slot - 1), n, gap, voice == lows)) return false;
            }
        }
        return true;
    }

    private static boolean clearCarry(byte[] ink, int w, int h, MeasureRegion bar,
            ScoreNoteEvent previous, ScoreNoteEvent current, float gap, boolean lower) {
        // Lower heads share a long downstem. Exclude that shaft from the accidental window.
        return BeamedDoubleFlatCarry.clearAccidentalSpace(ink, w, h, x(bar, previous, w),
                x(bar, current, w), current.pageY() * h, gap, lower ? .53f : .72f, lower ? .96f : .78f);
    }
    private static boolean provedNatural(byte[] ink, int w, MeasureRegion bar,
            List<ScoreNoteEvent> voice, int slot, int h, float gap) {
        var n = voice.get(slot);
        return !AccidentalSlurSpines.prove(ink, w, x(bar, voice.get(slot - 1), w),
                x(bar, n, w), n.pageY() * h, gap, ScoreNoteEvent.ACCIDENTAL_NATURAL).isEmpty();
    }

    private static float bracketEnd(List<PlayingTechniqueDetector.Word> words, byte[] raw, byte[] gray,
            int w, int h, MeasureRegion bar, List<ScoreNoteEvent> highs, float gap) {
        float previous = x(bar, highs.get(3), w), first = x(bar, highs.get(4), w);
        float last = x(bar, highs.get(11), w), following = x(bar, highs.get(12), w);
        float found = -1;
        for (var word : words) {
            float left = word.left() * w;
            if (left <= previous || left > first || word.right() * w < first
                    || highs.get(3).pageY() * h - word.bottom() * h < .8f * gap
                    || highs.get(3).pageY() * h - word.bottom() * h > 2f * gap) continue;
            float a = OctaveMarkDetector.closedAltaEnd(raw, w, h, word, gap);
            float b = OctaveMarkDetector.closedAltaEnd(gray, w, h, word, gap);
            if (a <= last || a >= following || b <= last || b >= following || Math.abs(a - b) > .16f * gap)
                continue;
            if (found >= 0) return -1;
            found = (a + b) * .5f;
        }
        return found;
    }

    private static float x(MeasureRegion bar, ScoreNoteEvent n, int width) {
        return (bar.left() + n.positionInMeasure() * (bar.right() - bar.left())) * width;
    }
}
