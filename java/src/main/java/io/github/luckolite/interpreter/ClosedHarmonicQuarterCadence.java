// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Proof for an isolated harmonic quarter or two sixteenth-note groups closed by one. */
final class ClosedHarmonicQuarterCadence {
    private ClosedHarmonicQuarterCadence() {}

    private record Owner(int index, ScoreNoteEvent note) {}

    static int duration(
            MeasureRegion previous, MeasureRegion bar, MeasureRegion next, List<ScoreNoteEvent> heads,
            byte[] labels, byte[] gray, byte[] raw, int width, int height) {
        if (raw == null || labels == null || raw.length != gray.length || labels.length != gray.length
                || bar.cadenceQuarterBeats() != 0 || next.top() <= bar.bottom()
                || (heads.size() != 1 && heads.size() != 9)) return 0;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float span = (bar.right() - bar.left()) * width;
        boolean pairedBeams = heads.size() == 9;
        if (gap < 10 || gap > 25 || Math.abs(bar.top() - previous.top()) * height > .35f * gap
                || span < (pairedBeams ? 18 : 5.5f) * gap || span > (pairedBeams ? 25 : 8.5f) * gap)
            return 0;
        var ordered = heads.stream().sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
        if (!pattern(ordered)) return 0;
        return proves(previous, bar, ordered, labels, gray, width, height, gap)
                && proves(previous, bar, ordered, labels, raw, width, height, gap)
                ? (pairedBeams ? 3 : 1) : 0;
    }

    private static boolean pattern(List<ScoreNoteEvent> notes) {
        for (int i = 0; i < notes.size(); i++) {
            var n = notes.get(i);
            boolean terminal = i + 1 == notes.size();
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffIndex() != 0 || n.staffCount() != 1
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL || n.stemDirection() != 1
                    || n.octaveShift() != 2 || n.harmonicSemitones() != 0 || n.openStringTouch() != null
                    || n.augmentationDots() != 0 || n.tupletDivisor() != 1
                    || n.boundaryTies() != 0 || n.tiedFromPrevious() || n.crossStaffBeam()
                    || n.followingRestBeats() != 0 || n.leadingRestBeats() != 0
                    || (n.articulations() & NoteOrnament.GRACE) != 0
                    || n.beamCount() != (terminal ? 0 : 2)
                    || n.unbeamedDurationBeats() != (terminal ? 1 : 0)) return false;
        }
        return true;
    }

    private static boolean proves(
            MeasureRegion previous, MeasureRegion bar, List<ScoreNoteEvent> heads, byte[] labels,
            byte[] ink, int width, int height, float nominal) {
        float left = bar.left() * width, right = bar.right() * width, span = right - left;
        var rules = PrintedDirectionStaff.printedRules(
                ink, width, height, right - nominal * 2.55f, bar.bottom() * height - nominal * 2.1f, nominal);
        if (rules == null || Math.abs(rules[1] - nominal) > .12f * nominal) return false;
        float bottom = rules[0], gap = rules[1];
        var staff = new PlayingTechniqueDetector.Staff(bottom - 4 * gap, bottom, gap, 0, 1);
        int open = PrintedCadenceDurations.boundaryColumn(ink, width, staff,
                (left + previous.right() * width) * .5f, true, false);
        int end = PrintedCadenceDurations.boundaryColumn(ink, width, staff, right + .35f * gap, false, true);
        if (open < 0 || end <= open) return false;

        float firstX = left + span * heads.get(0).positionInMeasure(), lastX = firstX;
        var budget = new PrintedBeamShafts.Budget(220_000);
        PrintedBeamShafts.Shaft prior = null;
        for (int i = 0; i < heads.size(); i++) {
            var n = heads.get(i);
            float x = left + span * n.positionInMeasure(), y = n.pageY() * height;
            boolean last = i + 1 == heads.size();
            if (i > 0 && (x - lastX < 1.65f * gap || x - lastX > 3.3f * gap)) return false;
            var axes = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
            if (axes.size() != 1 || axes.get(0).direction() != -1) return false;
            var axis = axes.get(0);
            if (prior != null && !WrappedEighthTriplets.matchesBeamRails(
                    ink, width, height, prior, axis, gap, last || i == 4 ? 0 : 2)) return false;
            if (last && !quarter(labels, ink, width, height, x, y, staff, axis)) return false;
            prior = axis;
            lastX = x;
        }
        // A sharp of the lower-ledger touch can extend just beyond the standard accidental pocket.
        boolean ledgerSharp = heads.size() == 1 && OmrScoreInterpreter.printedTerminalDyadSharp(
                ink, width, height, lastX, staff.bottom() + gap, gap, false);
        float firstInk = ledgerSharp ? firstX - gap * .15f : firstX;
        return !budget.refused() && firstX - open >= gap * 1.60f && end - lastX >= gap * 2.0f
                && PrintedCadenceDurations.emptyOutsideChord(ink, width, staff,
                        left, end + .30f * gap, firstInk, lastX, true);
    }

    private static boolean quarter(
            byte[] labels, byte[] ink, int width, int height, float x, float y,
            PlayingTechniqueDetector.Staff staff, PrintedBeamShafts.Shaft shaft) {
        float gap = staff.gap(), touch = y - 1.5f * gap;
        if (!ArtificialHarmonics.joinedQuarterStem(ink, width, height, x, y, gap, 1)) return false;
        boolean onLedger = Math.abs(touch - (staff.bottom() + gap)) <= .23f * gap;
        for (int column = shaft.left(); column <= shaft.right(); column++) {
            if (!ArtificialHarmonics.unflaggedQuarterTip(ink, width, height, column, touch, y,
                    touch - gap * 2.65f, 1, staff)) continue;
            if (ArtificialHarmonics.shaftCrossedDiamond(ink, width, height, x, touch, gap, column, 1, true)
                    || onLedger && LedgerCrossedHarmonicDiamond.proved(labels, ink, width, height,
                            x, staff.bottom() + gap, gap, column, 1)) return true;
        }
        return false;
    }

    /** Keep the optically closed rhythm independent of horizontal spacing changes at accidentals. */
    static Map<Integer, Double> onsets(ScorePageInterpretation score) {
        var byBar = new HashMap<Integer, List<Owner>>();
        for (int i = 0; i < score.notes().size(); i++) {
            var n = score.notes().get(i);
            if (score.measures().get(n.measureIndex()).cadenceQuarterBeats() == 3)
                byBar.computeIfAbsent(n.measureIndex(), ignored -> new ArrayList<>()).add(new Owner(i, n));
        }
        var written = new HashMap<Integer, Double>();
        for (var owners : byBar.values()) {
            if (owners.size() != 9) continue;
            owners.sort(Comparator.comparingDouble(o -> o.note().positionInMeasure()));
            if (!pattern(owners.stream().map(Owner::note).toList())) continue;
            for (int i = 0; i < owners.size(); i++) written.put(owners.get(i).index(), i * .25);
        }
        return written;
    }
}
