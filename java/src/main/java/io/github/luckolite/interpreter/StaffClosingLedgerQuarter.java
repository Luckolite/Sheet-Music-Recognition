// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/** Reassign a remote ledger head to the closed octave it shares on the following staff. */
final class StaffClosingLedgerQuarter {
    private StaffClosingLedgerQuarter() {}

    private record Head(int owner, ScoreNoteEvent note, float x, float y) {}
    private record Move(int originalOwner, ScoreNoteEvent note) {}

    static ScorePageInterpretation reassign(ScorePageInterpretation score,
            byte[] normalized, byte[] raw, int width, int height) {
        if (normalized == null || raw == null || normalized.length != (long) width * height
                || raw.length != normalized.length) return score;
        var bars = score.measures();
        var groups = new ArrayList<List<Head>>(bars.size());
        for (int i = 0; i < bars.size(); i++) groups.add(new ArrayList<>());
        for (int owner = 0; owner < score.notes().size(); owner++) {
            var n = score.notes().get(owner);
            var m = bars.get(n.measureIndex());
            groups.get(n.measureIndex()).add(new Head(owner, n,
                    (m.left() + n.positionInMeasure() * (m.right() - m.left())) * width,
                    n.pageY() * height));
        }

        var moves = new HashMap<Integer, Move>();
        var relocated = new java.util.BitSet();
        for (int from = 0; from + 1 < bars.size(); from++) {
            var previous = bars.get(from);
            if (bars.get(from + 1).top() <= previous.bottom()) continue;
            var firstNext = bars.get(from + 1);
            float nextTop = firstNext.top();
            float rowSlack = (firstNext.bottom() - nextTop) * .07f;
            int target = from + 1;
            while (target + 1 < bars.size()
                    && Math.abs(bars.get(target + 1).top() - nextTop) < rowSlack) target++;
            var bar = bars.get(target);
            float nominal = (bar.bottom() - bar.top()) * height / 8.2f;
            if (nominal < 10 || nominal > 27 || bar.cadenceQuarterBeats() != 0
                    || previous.cadenceQuarterBeats() != 0
                    || Math.abs(bar.right() - previous.right()) * width > nominal * 2) continue;
            var targetHeads = groups.get(target);
            if (targetHeads.isEmpty()) continue;
            var last = targetHeads.stream().max(Comparator.comparingDouble(Head::x)).orElseThrow();
            if (!ordinaryQuarter(last.note) || last.note.staffStep() < 8 || last.note.staffStep() > 12
                    || last.note.positionInMeasure() < .70f
                    || targetHeads.stream().anyMatch(head -> head != last
                            && last.x - head.x < nominal * 1.5f)) continue;
            var remote = groups.get(from).stream().filter(head -> ordinaryQuarter(head.note)
                    && head.note.staffStep() <= -12 && head.note.positionInMeasure() > .70f
                    && head.y > previous.bottom() * height + nominal
                    && Math.abs(head.x - last.x) <= nominal * .18f
                    && Math.abs(last.y - head.y - nominal * 3.5f) <= nominal * .3f).toList();
            if (remote.size() != 1) continue;
            var upper = remote.get(0);
            var proof = sharedQuarterShaft(normalized, width, height, bar, upper, last, nominal);
            var printed = sharedQuarterShaft(raw, width, height, bar, upper, last, nominal);
            if (proof < 0 || printed < 0 || Math.abs(proof - printed) > 2) continue;
            float pos = (upper.x / width - bar.left()) / (bar.right() - bar.left());
            if (pos <= 0 || pos >= 1) continue;
            var note = new ScoreNoteEvent(target, pos, last.note.staffStep() + 7,
                    0, 1, upper.note.pageY(), false, 0, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                    ScoreNoteEvent.DURATION_QUARTER, 1, 0, 0, ScoreNoteEvent.CLEF_TREBLE).withStemDirection(-1);
            moves.put(last.owner, new Move(upper.owner, note));
            relocated.set(upper.owner);
        }
        if (moves.isEmpty()) return score;
        var notes = new ArrayList<ScoreNoteEvent>();
        var owners = new ArrayList<Integer>();
        for (int owner = 0; owner < score.notes().size(); owner++) {
            if (relocated.get(owner)) continue;
            var note = score.notes().get(owner);
            var move = moves.get(owner);
            if (move != null && move.note.positionInMeasure() <= note.positionInMeasure()) {
                notes.add(move.note);
                owners.add(move.originalOwner);
            }
            notes.add(note);
            owners.add(owner);
            if (move != null && move.note.positionInMeasure() > note.positionInMeasure()) {
                notes.add(move.note);
                owners.add(move.originalOwner);
            }
        }
        return score.withNotes(notes, owners);
    }

    private static boolean ordinaryQuarter(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.staffIndex() == 0 && n.staffCount() == 1 && n.clefBottomDiatonic() == ScoreNoteEvent.CLEF_TREBLE
                && n.stemDirection() == -1 && n.beamCount() == 0 && n.unbeamedDurationBeats() == 1
                && n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY && n.tupletDivisor() == 1
                && n.augmentationDots() == 0 && n.articulations() == 0 && !n.tiedFromPrevious()
                && n.boundaryTies() == 0 && n.followingRestBeats() == 0 && n.leadingRestBeats() == 0
                && n.harmonicSemitones() == 0 && n.octaveShift() == 0 && n.openStringTouch() == null
                && !n.crossStaffBeam() && !n.compactOpening();
    }

    private static int sharedQuarterShaft(byte[] ink, int w, int h, MeasureRegion bar,
            Head upper, Head lower, float nominal) {
        var rules = PrintedDirectionStaff.printedRules(ink, w, h, lower.x,
                bar.bottom() * h - 2.1f * nominal, nominal);
        if (rules == null) return -1;
        float gap = rules[1], floor = rules[0];
        int step = lower.note.staffStep();
        if (Math.abs(floor - step * gap * .5f - lower.y) > gap * .18f
                || Math.abs(floor - (step + 7) * gap * .5f - upper.y) > gap * .18f
                || !ArtificialHarmonics.filledQuarterCore(ink, w, h, lower.x, lower.y, gap)
                || !ArtificialHarmonics.filledQuarterCore(ink, w, h, upper.x, upper.y, gap)) return -1;
        var staff = new PlayingTechniqueDetector.Staff(floor - 4 * gap, floor, gap, 0, 1);
        var budget = new PrintedBeamShafts.Budget(120_000);
        var a = PrintedBeamShafts.at(ink, w, h, upper.x, upper.y, gap, budget);
        var b = PrintedBeamShafts.at(ink, w, h, lower.x, lower.y, gap, budget);
        if (budget.refused() || a.size() != 1 || b.size() != 1) return -1;
        var top = a.get(0);
        var base = b.get(0);
        if (top.direction() != 1 || base.direction() != 1
                || Math.abs(top.endY() - base.endY()) > gap * .15f
                || base.endY() - lower.y < gap * 2.6f || base.endY() - lower.y > gap * 4.25f) return -1;
        for (var column : top.columns()) {
            if (column.x() < base.left() || column.x() > base.right()
                    || Math.abs(column.endY() - top.endY()) > gap * .15f) continue;
            if (ArtificialHarmonics.unflaggedQuarterTip(ink, w, h, column.x(),
                    upper.y, lower.y, base.endY(), -1, staff)) return column.x();
        }
        return -1;
    }
}