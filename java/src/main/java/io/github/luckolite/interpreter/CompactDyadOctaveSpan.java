// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/** Bind an interior dotted octave span to complete beamed pairs and preserve both written flats. */
final class CompactDyadOctaveSpan {
    private CompactDyadOctaveSpan() {}

    private record Pair(int upper, int lower) {}
    private record Spine(int x, int top, int bottom) {}

    static ScorePageInterpretation repair(ScorePageInterpretation score, List<PlayingTechniqueDetector.Staff> staffs,
            byte[] raw, byte[] gray, int w, int h, List<OctaveMarkDetector.StaffSpan> spans,
            Consumer<SourceLocalAccidental.ProvedMark> printed) {
        if (raw == null || gray == null || raw.length != (long) w * h || raw.length != gray.length) return score;
        var notes = score.notes();
        var rows = new ArrayList<List<Integer>>();
        for (var ignored : score.measures()) rows.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) rows.get(notes.get(i).measureIndex()).add(i);
        List<PlayingTechniqueDetector.Word> words = null;

        List<ScoreNoteEvent> edited = null;
        for (int i = 0; i < rows.size(); i++) {
            var owners = rows.get(i);
            if (owners.size() != 24 || score.measures().get(i).cadenceQuarterBeats() != 0) continue;
            var bar = score.measures().get(i);
            float gap = (bar.bottom() - bar.top()) * h / 8.2f;
            if (gap < 12 || gap > 23) continue;
            owners.sort(Comparator.comparingDouble(j -> notes.get(j).positionInMeasure()));
            var pairs = octavePairs(owners, notes, bar, w, h, gap);
            if (pairs.isEmpty()) continue;
            if (words == null) words = OctaveMarkDetector.printedWords(raw, w, h, staffs);
            if (words.isEmpty()) return score;
            var prevUpper = notes.get(pairs.get(2).upper());
            var nextUpper = notes.get(pairs.get(3).upper());
            var lastUpper = notes.get(pairs.get(8).upper());
            var followingUpper = notes.get(pairs.get(9).upper());
            float prev = x(bar, prevUpper, w), next = x(bar, nextUpper, w);
            float last = x(bar, lastUpper, w), following = x(bar, followingUpper, w);
            float terminal = -1;
            for (var word : words) {
                float left = word.left() * w, right = word.right() * w;
                if (left - prev < .14f * gap || left - prev > .49f * gap
                        || next - right < .28f * gap || next - right > .86f * gap
                        || prevUpper.pageY() * h - word.bottom() * h < 1.06f * gap
                        || prevUpper.pageY() * h - word.bottom() * h > 2.1f * gap) continue;
                float a = OctaveMarkDetector.compactDottedDyadEnd(raw, w, h, word, gap);
                float b = OctaveMarkDetector.compactDottedDyadEnd(gray, w, h, word, gap);
                if (a - last < .38f * gap || following - a < .42f * gap || b < 0 || Math.abs(a - b) > .16f * gap)
                    continue;
                if (terminal >= 0) { terminal = -1; break; }
                terminal = (a + b) * .5f;
            }
            if (terminal < 0 || !witnessedBar(raw, w, h, bar, pairs, notes, gap)
                    || !witnessedBar(gray, w, h, bar, pairs, notes, gap)) continue;
            int measure = i;
            if (spans.stream().anyMatch(span -> span.firstMeasureIndex() <= measure
                    && span.lastMeasureIndex() >= measure && span.staffIndex() == 0)) continue;
            if (edited == null) edited = new ArrayList<>(notes);
            for (int pos : new int[]{2, 5, 7, 10}) {
                var pair = pairs.get(pos);
                for (int index : new int[]{pair.upper(), pair.lower()}) {
                    var note = notes.get(index).withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_DOUBLE_FLAT);
                    if (pos == 2) {
                        note = note.withOctaveShift(0);

                    }
                    if (pos == 2 || pos == 5) printed.accept(new SourceLocalAccidental.ProvedMark(note, false));
                    edited.set(index, note);
                }
            }
            spans.add(new OctaveMarkDetector.StaffSpan(i, i, 0, 1, 1,
                    new OctaveMarkDetector.Fragment((prev + next) * .5f, terminal)));
        }
        return edited == null ? score : score.withNotes(edited,
                IntStream.range(0, notes.size()).boxed().toList());
    }

    private static List<Pair> octavePairs(List<Integer> owners, List<ScoreNoteEvent> notes,
            MeasureRegion bar, int w, int h, float gap) {
        var result = new ArrayList<Pair>();
        for (int slot = 0; slot < 12; slot++) {
            int a = owners.get(2 * slot), b = owners.get(2 * slot + 1);
            if (notes.get(a).pageY() > notes.get(b).pageY()) { int t = a; a = b; b = t; }
            var first = notes.get(a);
            var last = notes.get(b);
            if (!ordinary(first) || !ordinary(last) || first.staffStep() - last.staffStep() != 7
                    || Math.abs(x(bar, first, w) - x(bar, last, w)) > .21f * gap
                    || Math.abs((last.pageY() - first.pageY()) * h - 3.5f * gap) > .24f * gap
                    || first.octaveShift() != (slot >= 2 && slot <= 8 ? 1 : 0)
                    || last.octaveShift() != first.octaveShift()) return List.of();
            result.add(new Pair(a, b));
        }
        var stated = result.get(2);
        var lower = notes.get(stated.lower());
        var upper = notes.get(stated.upper());
        if (lower.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT
                || upper.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_DOUBLE_FLAT) return List.of();
        for (int i : new int[]{5, 7, 10}) {
            var repeated = result.get(i);
            if (notes.get(repeated.lower()).diatonicPitchIdentity() != lower.diatonicPitchIdentity()
                    || notes.get(repeated.upper()).diatonicPitchIdentity() != upper.diatonicPitchIdentity())
                return List.of();
        }
        return result;
    }

    private static boolean ordinary(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffCount() == 1 && n.staffIndex() == 0
                && n.stemDirection() == -1 && n.beamCount() == 1 && n.unbeamedDurationBeats() == 0
                && n.augmentationDots() == 0 && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL && n.harmonicSemitones() == 0
                && n.openStringTouch() == null && !n.tiedFromPrevious() && n.boundaryTies() == 0
                && !n.crossStaffBeam() && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static boolean witnessedBar(byte[] ink, int w, int h, MeasureRegion bar,
            List<Pair> pairs, List<ScoreNoteEvent> notes, float gap) {
        var budget = new PrintedBeamShafts.Budget(320_000);
        PrintedBeamShafts.Shaft previous = null;
        for (int i = 0; i < pairs.size(); i++) {
            var n = notes.get(pairs.get(i).lower());
            var shafts = PrintedBeamShafts.at(ink, w, h, x(bar, n, w), n.pageY() * h, gap, budget);
            if (shafts.size() != 1 || shafts.get(0).direction() != 1 || budget.refused()) return false;
            var shaft = shafts.get(0);
            if (previous != null && !(i % 3 == 0
                    ? WrappedEighthTriplets.cutBetweenRecoveredGroups(ink, w, h, bar, previous, shaft, gap)
                    : WrappedEighthTriplets.matchesBeamRails(ink, w, h, previous, shaft, gap, 1))) return false;
            previous = shaft;
        }
        for (int index : new int[]{pairs.get(2).upper(), pairs.get(2).lower(),
                pairs.get(5).upper(), pairs.get(5).lower()}) {
            var n = notes.get(index);
            if (!doubleFlat(ink, w, h, x(bar, n, w), n.pageY() * h, gap)) return false;
        }
        return true;
    }

    private static boolean doubleFlat(byte[] ink, int w, int h, float x, float y, float gap) {
        int l = Math.round(x - 3.32f * gap), r = Math.round(x - 1.28f * gap);
        int t = Math.round(y - 1.92f * gap), b = Math.round(y + .88f * gap);
        if (l < 0 || r >= w || t < 0 || b >= h) return false;
        var spines = new ArrayList<Spine>();
        for (int col = l; col <= r; col++) {
            int start = -1, stop = -1, count = 0;
            for (int row = t; row <= b; row++) {
                if ((ink[row * w + col] & 255) >= 195) continue;
                if (start < 0) start = row;
                stop = row; count++;
            }
            if (start < 0 || start > y - 1.48f * gap || stop < y + .40f * gap
                    || count < (stop - start + 1) * .87f) continue;
            if (spines.isEmpty() || col > spines.get(spines.size() - 1).x() + 1)
                spines.add(new Spine(col, start, stop));
            else spines.set(spines.size() - 1, new Spine(col, start, stop));
        }
        if (spines.size() != 2) return false;
        var a = spines.get(0);
        var c = spines.get(1);
        if (c.x() - a.x() < .87f * gap || c.x() - a.x() > 1.36f * gap
                || Math.abs(a.top() - c.top()) > .19f * gap || Math.abs(a.bottom() - c.bottom()) > .25f * gap)
            return false;
        return bowl(ink, w, a.x(), y, gap) && bowl(ink, w, c.x(), y, gap);
    }

    private static boolean bowl(byte[] ink, int w, int spine, float y, float gap) {
        int l = Math.round(spine + .41f * gap), r = Math.round(spine + .69f * gap);
        int filled = 0, empty = 0;
        for (int row = Math.round(y - 1.22f * gap); row <= Math.round(y + .40f * gap); row++) {
            int count = 0;
            for (int col = l; col <= r; col++) if ((ink[row * w + col] & 255) < 155) count++;
            if (row < y - .79f * gap && count == 0) empty++;
            if (row >= y - .74f * gap && count >= 2) filled++;
        }
        return empty >= .23f * gap && filled >= .38f * gap;
    }

    private static float x(MeasureRegion bar, ScoreNoteEvent note, int w) {
        return (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * w;
    }
}
