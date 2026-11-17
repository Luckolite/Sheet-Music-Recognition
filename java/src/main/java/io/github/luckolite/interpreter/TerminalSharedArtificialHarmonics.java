// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;

/** Closed quarter with two stopped violin harmonics on one unflagged upstem. */
final class TerminalSharedArtificialHarmonics {
    private TerminalSharedArtificialHarmonics() {}

    record Ink(float x, float upperY, float lowerY, int shaft, int tip, float gap) {}

    static ScorePageInterpretation close(ScorePageInterpretation score, byte[] gray, byte[] raw,
            int width, int height, Integer initialKey) {
        var bars = score.measures();
        int bar = bars.size() - 1;
        if (bar < 1 || bars.get(bar).cadenceQuarterBeats() != 0
                || score.rests().stream().anyMatch(r -> r.measureIndex() == bar)) return score;
        var meter = score.meterChanges().stream().filter(m -> m.measureIndex() <= bar)
                .reduce((first, second) -> second).orElse(null);
        if (meter == null || meter.numerator() != 2 || meter.denominator() != 4) return score;
        var notes = score.notes();
        if (notes.isEmpty()) return score;
        int aliasIndex = notes.size() - 1;
        var alias = notes.get(aliasIndex);
        if (alias.measureIndex() != bar
                || aliasIndex > 0 && notes.get(aliasIndex - 1).measureIndex() == bar
                || !plain(alias, bar) || alias.staffStep() < 2 || alias.staffStep() > 6
                || alias.beamCount() != 3 || alias.stemDirection() != 0
                || alias.unbeamedDurationBeats() != 0 || alias.harmonicSemitones() != 0
                || alias.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) return score;
        Integer key = initialKey;
        for (var change : score.keyChanges()) if (change.measureIndex() <= bar) key = change.fifths();
        if (key == null) return score;
        int diatonic = alias.diatonicPitchIdentity() - 3;
        int lower = ScoreTiePitchGuard.writtenMidi(diatonic - 4, ScoreNoteEvent.ACCIDENTAL_FLAT, key);
        int upper = ScoreTiePitchGuard.writtenMidi(diatonic, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
        int lowerTouch = ScoreTiePitchGuard.writtenMidi(diatonic - 2, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
        int upperTouch = ScoreTiePitchGuard.writtenMidi(diatonic + 3, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
        if (lowerTouch - lower != 4 || upperTouch - upper != 5
                || lower + 28 > 127 || upper + 24 > 127 || lower + 28 == upper + 24) return score;
        var region = bars.get(bar);
        float x = (region.left() + alias.positionInMeasure() * (region.right() - region.left())) * width;
        var a = prove(raw, width, height, bars.get(bar - 1), region, x, alias.pageY() * height, alias.staffStep());
        var b = prove(gray, width, height, bars.get(bar - 1), region, x, alias.pageY() * height, alias.staffStep());
        if (a == null || b == null || Math.abs(a.shaft - b.shaft) > 1 || Math.abs(a.tip - b.tip) > 1
                || Math.abs(a.x - b.x) > .15f * a.gap
                || Math.abs(a.upperY - b.upperY) > .15f * a.gap
                || Math.abs(a.lowerY - b.lowerY) > .15f * a.gap) return score;

        float position = (a.x / width - region.left()) / (region.right() - region.left());
        var edited = new ArrayList<>(notes.subList(0, aliasIndex));
        var owners = new ArrayList<Integer>(edited.size() + 2);
        for (int i = 0; i < edited.size(); i++) owners.add(i);
        int upperStep = alias.staffStep() - 3;
        edited.add(quarter(bar, position, upperStep - 4, a.lowerY / height,
                ScoreNoteEvent.ACCIDENTAL_FLAT, 28));
        edited.add(quarter(bar, position, upperStep, a.upperY / height,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 24));
        owners.add(-1);
        owners.add(-1);

        var rewritten = score.withNotes(edited, owners);
        var measures = new ArrayList<>(bars);
        measures.set(bar, new MeasureRegion(region.left(), region.right(), region.top(), region.bottom(), 1));
        return new ScorePageInterpretation(measures, rewritten.notes(), rewritten.firstMeasureNumber(),
                rewritten.keyChanges(), rewritten.tempoChanges(), rewritten.meterChanges(), rewritten.rests(),
                rewritten.techniqueChanges(), rewritten.dynamicChanges(), rewritten.playbackDirections(),
                rewritten.expressiveEvents(), rewritten.slurSource(), rewritten.openingPickup(),
                rewritten.tremoloSource(), rewritten.multiRestGroups(), rewritten.beamSource());
    }

    /** Locate the stopped third of a complete same-staff final pair for event serialization. */
    static int stoppedThird(ScorePageInterpretation score) {
        var bars = score.measures();
        var notes = score.notes();
        int bar = bars.size() - 1, i = notes.size() - 2;
        if (bar < 1 || i < 0 || bars.get(bar).cadenceQuarterBeats() != 1
                || i > 0 && notes.get(i - 1).measureIndex() == bar) return -1;
        var a = notes.get(i);
        var b = notes.get(i + 1);
        var lower = a.staffStep() < b.staffStep() ? a : b;
        var upper = lower == a ? b : a;
        if (!plain(lower, bar) || !plain(upper, bar)
                || lower.beamCount() != 0 || upper.beamCount() != 0
                || lower.unbeamedDurationBeats() != 1 || upper.unbeamedDurationBeats() != 1
                || lower.stemDirection() != 1 || upper.stemDirection() != 1
                || lower.harmonicSemitones() != 28 || upper.harmonicSemitones() != 24
                || lower.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT
                || upper.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                || upper.staffStep() - lower.staffStep() != 4
                || lower.positionInMeasure() != upper.positionInMeasure()) return -1;
        return lower == a ? i : i + 1;
    }

    static Ink prove(byte[] ink, int width, int height, MeasureRegion before,
            MeasureRegion bar, float roughX, float roughY, int touchStep) {
        if (ink == null || ink.length != (long) width * height) return null;
        float left = bar.left() * width, right = bar.right() * width;
        float nominal = (bar.bottom() - bar.top()) * height / 8.2f;
        float fraction = (roughX - left) / (right - left);
        if (nominal < 10 || nominal > 26 || right - left < 6.5f * nominal || right - left > 10.5f * nominal
                || !Float.isFinite(fraction) || fraction < .29f || fraction > .52f
                || Math.abs(before.top() - bar.top()) * height > .4f * nominal
                || left - before.right() * width < .52f * nominal
                || left - before.right() * width > 1.55f * nominal) return null;
        var rules = PrintedDirectionStaff.printedRules(ink, width, height,
                right - 2.55f * nominal, bar.bottom() * height - 2.1f * nominal, nominal);
        if (rules == null) return null;
        float bottom = rules[0], gap = rules[1];
        var staff = new PlayingTechniqueDetector.Staff(bottom - 4 * gap, bottom, gap, 0, 1);
        int opening = PrintedCadenceDurations.boundaryColumn(ink, width, staff,
                (before.right() * width + left) * .5f, true, false);
        int closing = PrintedCadenceDurations.boundaryColumn(ink, width, staff, right, false, true);
        if (opening < 0 || closing < 0 || !PrintedCadenceDurations.emptyOutsideChord(
                ink, width, staff, left, closing, roughX, roughX, true)) return null;
        float center = roughX + .15f * gap;
        float upperTouch = bottom - touchStep * gap * .5f;
        float upperBase = upperTouch + 1.5f * gap;
        float lowerTouch = upperBase + gap;
        float lowerBase = upperBase + 2.1f * gap;
        if (Math.abs(roughY - upperTouch) > .2f * gap || center < gap || center >= width - gap
                || upperTouch <= gap || lowerBase >= height - gap) return null;
        var shafts = PrintedBeamShafts.at(ink, width, height, center, roughY - .45f * gap, gap,
                new PrintedBeamShafts.Budget(48_000));
        if (shafts.size() != 1 || shafts.get(0).direction() != -1
                || !filled(ink, width, center, upperBase, gap)
                || !filled(ink, width, center, lowerBase, gap)) return null;
        var stem = shafts.get(0);
        for (int shaft = stem.right(); shaft >= stem.left(); shaft--) {
            if (continuous(ink, width, shaft, upperTouch, lowerBase, gap)
                    && ArtificialHarmonics.shaftCrossedDiamond(
                            ink, width, height, center, upperTouch, gap, shaft, 1, true)
                    && ArtificialHarmonics.shaftCrossedDiamond(
                            ink, width, height, center, lowerTouch, gap, shaft, 1, true)
                    && ArtificialHarmonics.unflaggedQuarterTip(
                            ink, width, height, shaft, upperTouch, upperBase, stem.endY(), 1, staff)
                    && PrintedQuarterDyadAccidentals.isolatedFlat(ink, width, height, roughX, lowerBase, gap))
                return new Ink(center, upperBase, lowerBase, shaft, stem.endY(), gap);
        }
        return null;
    }

    private static boolean plain(ScoreNoteEvent note, int bar) {
        return note.measureIndex() == bar && note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.staffCount() == 1 && note.staffIndex() == 0
                && note.clefBottomDiatonic() == ScoreNoteEvent.CLEF_TREBLE
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && note.tupletDivisor() == 1 && note.augmentationDots() == 0 && !note.crossStaffBeam()
                && note.articulations() == 0 && !note.tiedFromPrevious() && note.boundaryTies() == 0
                && note.leadingRestBeats() == 0 && note.followingRestBeats() == 0
                && note.octaveShift() == 0 && note.openStringTouch() == null;
    }

    private static ScoreNoteEvent quarter(int bar, float position, int step, float y,
            int accidental, int harmonic) {
        return new ScoreNoteEvent(bar, position, step, 0, 1, y,
                false, 0, 0, accidental, 1, 1, 0, 0, ScoreNoteEvent.CLEF_TREBLE, false,
                0, false, 0, 0, 1, 1, ScoreNoteEvent.Kind.PITCHED, ScoreNoteEvent.VisualSize.FULL,
                harmonic, null);
    }

    private static boolean filled(byte[] ink, int width, float x, float y, float gap) {
        int halfW = Math.round(.24f * gap), halfH = Math.round(.14f * gap);
        int rows = 0;
        for (int yy = Math.round(y) - halfH; yy <= Math.round(y) + halfH; yy++) {
            int dark = 0;
            for (int xx = Math.round(x) - halfW; xx <= Math.round(x) + halfW; xx++)
                if ((ink[yy * width + xx] & 255) < 155) dark++;
            if (dark * 5 >= 4 * (2 * halfW + 1)) rows++;
        }
        return rows >= 2 * halfH;
    }

    private static boolean continuous(byte[] ink, int width, int x, float top, float bottom, float gap) {
        int missing = 0;
        for (int y = Math.round(top); y <= Math.round(bottom); y++)
            if ((ink[y * width + x] & 255) >= 155) missing++;
        return missing <= .27f * gap;
    }
}
