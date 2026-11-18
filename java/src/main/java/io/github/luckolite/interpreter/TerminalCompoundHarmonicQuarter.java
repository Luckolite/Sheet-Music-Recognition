// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** One stopped quarter and two touching diamonds on a terminal downshaft. */
final class TerminalCompoundHarmonicQuarter {
    private TerminalCompoundHarmonicQuarter() {}

    record Proof(float x, float y, float lowerY, int stoppedStep, int shaft, float gap) {}

    static Proof prove(byte[] ink, int width, int height, MeasureRegion before, MeasureRegion bar,
            float roughX) {
        if (ink == null || ink.length != (long) width * height) return null;
        float left = bar.left() * width, right = bar.right() * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float fraction = (roughX - left) / (right - left);
        if (!Float.isFinite(gap) || gap < 10 || gap > 26
                || right - left < 6.4f * gap || right - left > 9.2f * gap
                || !Float.isFinite(fraction) || fraction < .28f || fraction > .50f
                || Math.abs(before.top() - bar.top()) * height > .35f * gap
                || left - before.right() * width < .55f * gap
                || left - before.right() * width > 1.65f * gap) return null;
        var frame = PrintedDirectionStaff.printedRules(
                ink, width, height, right - 2.55f * gap, bar.bottom() * height - 2.1f * gap, gap);
        if (frame == null) return null;
        gap = frame[1];
        var staff = new PlayingTechniqueDetector.Staff(frame[0] - 4 * gap, frame[0], gap, 0, 1);
        int edge = PrintedCadenceDurations.boundaryColumn(
                ink, width, staff, right + .35f * gap, false, true);
        if (edge < 0 || PrintedCadenceDurations.boundaryColumn(
                        ink, width, staff, (left + before.right() * width) / 2, true, false) < 0
                || !PrintedCadenceDurations.emptyOutsideChord(
                        ink, width, staff, left, edge, roughX, roughX, false)) return null;
        Proof candidate = null;
        for (int step = 3; step <= 5; step++) {
            float y = frame[0] - step * gap * .5f;
            int col = Math.round(roughX), row = Math.round(y);
            if (!filled(ink, width, height, col, row, gap)) continue;
            float upper = y - 1.5f * gap, lower = y + gap;
            var tail = PrintedBeamShafts.at(ink, width, height, roughX, lower + .5f * gap, gap,
                    new PrintedBeamShafts.Budget(48_000));
            if (tail.size() != 1 || tail.get(0).direction() != 1) continue;
            var stem = tail.get(0);
            for (int shaft = stem.left(); shaft <= stem.right(); shaft++) {
                if (!shortSeam(ink, width, height, shaft, upper, lower, gap)
                        || !ArtificialHarmonics.shaftCrossedDiamond(
                                ink, width, height, roughX, upper, gap, shaft, -1, true)
                        || !ArtificialHarmonics.shaftCrossedDiamond(
                                ink, width, height, roughX, lower, gap, shaft, -1, true)
                        || !ArtificialHarmonics.unflaggedQuarterTip(
                                ink, width, height, shaft, lower, lower + .5f * gap,
                                stem.endY(), -1, staff)) continue;
                if (candidate != null && candidate.stoppedStep() != step) return null;
                candidate = new Proof(roughX, y, lower, step, shaft, gap);
                break;
            }
        }
        return candidate;
    }

    static ScorePageInterpretation close(
            ScorePageInterpretation score, byte[] normalized, byte[] original, int width, int height,
            List<PlayingTechniqueDetector.Word> words, Integer initialKey, boolean standardViolin) {
        var bars = score.measures();
        int last = bars.size() - 1;
        if (last < 1 || score.rests().stream().anyMatch(r -> r.measureIndex() == last)) return score;
        var meter = score.meterChanges().stream().filter(m -> m.measureIndex() <= last)
                .reduce((prior, next) -> next).orElse(null);
        if (meter == null || meter.numerator() != 2 || meter.denominator() != 4) return score;
        var bar = bars.get(last);
        var owners = new ArrayList<Integer>(2);
        for (int i = 0; i < score.notes().size(); i++) {
            if (score.notes().get(i).measureIndex() != last) continue;
            owners.add(i);
            if (owners.size() > 2) return score;
        }
        if (owners.isEmpty() || bar.cadenceQuarterBeats() != 0) return score;
        owners.sort(java.util.Comparator.comparingInt(i -> score.notes().get(i).staffStep()));
        int keep = owners.get(0);
        int drop = owners.size() == 2 ? owners.get(1) : -1;
        var stop = score.notes().get(keep);
        if (!plainStop(stop)) return score;
        float x = (bar.left() + stop.positionInMeasure() * (bar.right() - bar.left())) * width;
        var a = prove(normalized, width, height, bars.get(last - 1), bar, x);
        var b = prove(original, width, height, bars.get(last - 1), bar, x);
        if (a == null || b == null || a.stoppedStep != stop.staffStep() || b.stoppedStep != a.stoppedStep
                || Math.abs(a.shaft - b.shaft) > 1 || Math.abs(a.lowerY - b.lowerY) > .12f * a.gap
                || Math.abs(stop.pageY() * height - a.y) > .22f * a.gap) return score;
        if (drop >= 0) {
            var touch = score.notes().get(drop);
            float touchX = (bar.left() + touch.positionInMeasure() * (bar.right() - bar.left())) * width;
            if (!plainStop(touch) || touch.staffStep() != stop.staffStep() + 3
                    || Math.abs(touchX - x) > .16f * a.gap
                    || Math.abs(touch.pageY() * height - (a.y - 1.5f * a.gap)) > .22f * a.gap)
                return score;
        }
        if (!OpenStringHarmonics.verifiedZeroBelow(
                words, original, normalized, width, height, x, a.lowerY, a.gap)) return score;
        Integer key = initialKey;
        for (var change : score.keyChanges()) if (change.measureIndex() <= last) key = change.fifths();
        if (key == null) return score;
        int pitch = ScoreTiePitchGuard.writtenMidi(stop.diatonicPitchIdentity(), stop.writtenAccidental(), key);
        int upper = ScoreTiePitchGuard.writtenMidi(
                stop.diatonicPitchIdentity() + 3, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
        int lower = ScoreTiePitchGuard.writtenMidi(
                stop.diatonicPitchIdentity() - 2, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
        if (upper - pitch != 5 || pitch + 24 > 127 || lower >= pitch) return score;
        ScoreNoteEvent.OpenStringTouch open = null;
        if (standardViolin) {
            int sounding = OpenStringHarmonics.uniqueSoundingMidi(lower);
            if (sounding < 0 || sounding == pitch + 24) return score;
            open = new ScoreNoteEvent.OpenStringTouch(stop.staffStep() - 2, a.lowerY / height,
                    ScoreNoteEvent.ACCIDENTAL_FROM_KEY);
        }
        var chord = new ScoreNoteEvent(
                last, stop.positionInMeasure(), stop.staffStep(), 0, 1, stop.pageY(),
                false, 0, 0, stop.writtenAccidental(), 1, 1, 0, 0, stop.clefBottomDiatonic(),
                false, 0, false, standardViolin ? 2 : 0, 0, 1, -1, stop.kind(), stop.visualSize(),
                standardViolin ? 0 : 24, open);
        var notes = new ArrayList<ScoreNoteEvent>();
        var oldOwners = new ArrayList<Integer>();
        for (int i = 0; i < score.notes().size(); i++) {
            if (i == drop) continue;
            notes.add(i == keep ? chord : score.notes().get(i));
            oldOwners.add(i);
        }
        var revised = score.withNotes(notes, oldOwners);
        var regions = new ArrayList<>(bars);
        regions.set(last, new MeasureRegion(bar.left(), bar.right(), bar.top(), bar.bottom(), 1));
        return new ScorePageInterpretation(regions, revised.notes(), revised.firstMeasureNumber(),
                revised.keyChanges(), revised.tempoChanges(), revised.meterChanges(), revised.rests(),
                revised.techniqueChanges(), revised.dynamicChanges(), revised.playbackDirections(),
                revised.expressiveEvents(), revised.slurSource(), revised.openingPickup(),
                revised.tremoloSource(), revised.multiRestGroups(), revised.beamSource());
    }

    private static boolean plainStop(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.staffIndex() == 0 && n.staffCount() == 1 && n.clefBottomDiatonic() == ScoreNoteEvent.CLEF_TREBLE
                && n.stemDirection() == -1 && n.beamCount() <= 1 && n.unbeamedDurationBeats() <= 1
                && n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && n.augmentationDots() == 0 && n.tupletDivisor() == 1 && !n.crossStaffBeam()
                && n.articulations() == 0 && !n.tiedFromPrevious() && n.boundaryTies() == 0
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0
                && n.harmonicSemitones() == 0 && n.openStringTouch() == null;
    }

    private static boolean filled(byte[] ink, int width, int height, int x, int y, float gap) {
        int cross = Math.round(gap * .23f), down = Math.round(gap * .13f);
        if (x - cross < 0 || x + cross >= width || y - down < 0 || y + down >= height)
            return false;
        int dark = 0;
        for (int yy = y - down; yy <= y + down; yy++)
            for (int xx = x - cross; xx <= x + cross; xx++)
                if ((ink[yy * width + xx] & 255) < 155) dark++;
        return dark >= (2 * cross + 1) * (2 * down + 1) * .86f;
    }

    private static boolean shortSeam(byte[] ink, int width, int height, int shaft,
            float upper, float lower, float gap) {
        int top = Math.round(upper + .18f * gap), bottom = Math.round(lower + .83f * gap);
        if (shaft < 0 || shaft >= width || top < 0 || bottom >= height) return false;
        int missing = 0;
        for (int y = top; y <= bottom; y++) {
            if ((ink[y * width + shaft] & 255) < 160) continue;
            if (y < lower + .40f * gap || y > lower + .77f * gap) return false;
            missing++;
        }
        return missing <= .20f * gap;
    }
}
