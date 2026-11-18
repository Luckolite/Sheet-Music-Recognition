// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Source ownership for one narrow, separately closed harmonic quarter at a system end. */
final class ClosedSingleHarmonicQuarter {
    private ClosedSingleHarmonicQuarter() {}

    private record Head(int index, ScoreNoteEvent note, float x, float y) {}

    static ScorePageInterpretation capture(
            ScorePageInterpretation score, byte[] labels, byte[] normalized,
            byte[] original, int width, int height) {
        return capture(score, labels, normalized, original, width, height, null);
    }

    static ScorePageInterpretation capture(
            ScorePageInterpretation score, byte[] labels, byte[] normalized,
            byte[] original, int width, int height, Integer initialKeyFifths) {
        if (original == null || normalized == null || labels == null
                || original.length != (long) width * height
                || normalized.length != original.length || labels.length != original.length) return score;
        var measures = score.measures();
        var notes = score.notes();
        var owners = new ArrayList<List<Head>>(measures.size());
        for (int i = 0; i < measures.size(); i++) owners.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            var bar = measures.get(note.measureIndex());
            float x = (bar.left() + note.positionInMeasure() * (bar.right() - bar.left())) * width;
            owners.get(note.measureIndex()).add(new Head(i, note, x, note.pageY() * height));
        }
        var regions = new ArrayList<>(measures);
        var changed = new ArrayList<>(notes);
        var removed = new java.util.HashSet<Integer>();
        var removedRests = new java.util.HashSet<ScoreRestEvent>();
        for (int i = 1; i + 1 < measures.size(); i++) {
            var bar = measures.get(i);
            var previous = measures.get(i - 1);
            var next = measures.get(i + 1);
            float nominal = (bar.bottom() - bar.top()) * height / 8.2f;
            float span = (bar.right() - bar.left()) * width;
            var heads = owners.get(i);
            boolean rowEnd = next.top() > bar.bottom();
            float closingPocket = (next.left() - bar.right()) * width;
            boolean inlineDoubleRule = !rowEnd
                    && Math.abs(next.top() - bar.top()) * height <= nominal * .35f
                    && closingPocket >= nominal * .65f && closingPocket <= nominal * 1.55f;
            if (bar.cadenceQuarterBeats() != 0 || nominal < 10 || nominal > 26
                    || span < nominal * 5.5f || span > nominal * (inlineDoubleRule ? 10f : 8.5f)
                    || Math.abs(previous.top() - bar.top()) * height > nominal * .35f
                    || !rowEnd && !inlineDoubleRule
                    || heads.isEmpty() || heads.size() > 2) continue;
            final int index = i;
            var rests = score.rests().stream().filter(r -> r.measureIndex() == index).toList();
            heads.sort(Comparator.comparingDouble(Head::y).reversed());
            var stopped = heads.get(0);
            var touch = heads.size() == 2 ? heads.get(1) : null;
            boolean lowerSpace = inlineDoubleRule && touch != null && touch.note.staffStep() == -1;
            boolean fadedStem = inlineDoubleRule && touch != null
                    && stopped.note.stemDirection() == 0
                    && touch.note.staffStep() >= 2 && touch.note.staffStep() <= 7;
            if (inlineDoubleRule && (touch == null
                    || !lowerSpace && !fadedStem && touch.note.staffStep() != 1
                    || !lowerSpace && span > nominal * 8.5f)) continue;
            if (lowerSpace && ((bar.left() - previous.right()) * width < nominal * .55f
                    || (bar.left() - previous.right()) * width > nominal * 1.6f
                    || stopped.x < touch.x)) continue;
            if (!ordinary(stopped.note, 1, fadedStem ? 0 : 1)
                    || stopped.note.positionInMeasure() < .24f || stopped.note.positionInMeasure() > .62f
                    || stopped.note.openStringTouch() != null || stopped.note.harmonicSemitones() != 0)
                continue;
            if (touch == null) {
                if (stopped.note.octaveShift() != 2) continue;
            } else if (!ordinary(touch.note, 2, 1) || stopped.note.octaveShift() != 0
                    || touch.note.octaveShift() != 0 || touch.note.harmonicSemitones() != 0
                    || touch.note.openStringTouch() != null
                    || Math.abs(stopped.x - touch.x) > nominal * (lowerSpace ? .34f : .16f)
                    || !printedFourth(score.keyChanges(), stopped.note, touch.note,
                            lowerSpace ? initialKeyFifths : null)) continue;
            ScoreRestEvent restAlias = null;
            if (!rests.isEmpty()) {
                if (touch != null || rests.size() != 1
                        || !overlapsDiamond(rests.get(0), bar, stopped, width, height, nominal)) continue;
                restAlias = rests.get(0);
            }
            int a = prove(normalized, width, height, previous, bar, stopped, touch, nominal,
                    inlineDoubleRule, lowerSpace, fadedStem);
            int b = prove(original, width, height, previous, bar, stopped, touch, nominal,
                    inlineDoubleRule, lowerSpace, fadedStem);
            if (a < 0 || b < 0 || Math.abs(a - b) > nominal * .12f) continue;
            regions.set(i, new MeasureRegion(bar.left(), bar.right(), bar.top(), bar.bottom(), 1));
            if (restAlias != null) removedRests.add(restAlias);
            if (touch != null) {
                removed.add(touch.index());
                changed.set(stopped.index(),
                        stopped.note.withStemDirection(1).withHarmonicSemitones(24));
            }
        }
        if (regions.equals(measures)) return score;
        if (!removed.isEmpty()) {
            var retained = new ArrayList<ScoreNoteEvent>();
            var sourceOwners = new ArrayList<Integer>();
            for (int i = 0; i < changed.size(); i++) {
                if (removed.contains(i)) continue;
                retained.add(changed.get(i));
                sourceOwners.add(i);
            }
            score = score.withNotes(retained, sourceOwners);
        }
        return new ScorePageInterpretation(regions, score.notes(), score.firstMeasureNumber(),
                score.keyChanges(), score.tempoChanges(), score.meterChanges(),
                score.rests().stream().filter(r -> !removedRests.contains(r)).toList(),
                score.techniqueChanges(), score.dynamicChanges(), score.playbackDirections(),
                score.expressiveEvents(), score.slurSource(), score.openingPickup(),
                score.tremoloSource(), score.multiRestGroups(), score.beamSource());
    }

    private static boolean overlapsDiamond(
            ScoreRestEvent rest, MeasureRegion bar, Head stopped, int width, int height, float gap) {
        if (rest.kind() != ScoreRestEvent.Kind.LITERAL || rest.durationBeats() != .5
                || rest.staffIndex() != 0 || rest.staffCount() != 1) return false;
        float restX = (bar.left() + rest.positionInMeasure() * (bar.right() - bar.left())) * width;
        float touchY = stopped.y - 1.5f * gap;
        return Math.abs(restX - stopped.x) <= gap * .4f
                && Math.abs(rest.pageY() * height - touchY) <= gap * .3f
                && rest.pageHeight() * height >= gap * .65f
                && rest.pageHeight() * height <= gap * 2f;
    }


    private static boolean ordinary(ScoreNoteEvent note, int duration, int stem) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED && note.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && note.staffIndex() == 0 && note.staffCount() == 1
                && note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && note.clefBottomDiatonic() != ScoreNoteEvent.CLEF_UNKNOWN && note.stemDirection() == stem
                && note.beamCount() == 0 && note.unbeamedDurationBeats() == duration
                && note.augmentationDots() == 0 && note.tupletDivisor() == 1
                && !note.crossStaffBeam() && !note.tiedFromPrevious() && note.boundaryTies() == 0
                && !note.compactOpening() && note.leadingRestBeats() == 0 && note.followingRestBeats() == 0
                && note.articulations() == 0;
    }

    private static boolean printedFourth(List<ScoreKeyChange> keys, ScoreNoteEvent stop,
            ScoreNoteEvent touch, Integer inferredKeyFifths) {
        if (touch.clefBottomDiatonic() != stop.clefBottomDiatonic()
                || touch.staffStep() - stop.staffStep() != 3) return false;
        ScoreKeyChange active = null;
        for (var key : keys) {
            if (key.measureIndex() > stop.measureIndex()) break;
            active = key;
        }
        if (active == null && inferredKeyFifths == null) return false;
        int fifths = active == null ? inferredKeyFifths : active.fifths();
        int base = ScoreTiePitchGuard.writtenMidi(stop.diatonicPitchIdentity(), stop.writtenAccidental(), fifths);
        int upper = ScoreTiePitchGuard.writtenMidi(touch.diatonicPitchIdentity(), touch.writtenAccidental(), fifths);
        return upper - base == 5 && base + 24 <= 127;
    }

    private static int prove(
            byte[] ink, int width, int height, MeasureRegion previous, MeasureRegion bar,
            Head stopped, Head touch, float nominal, boolean inlineDoubleRule, boolean lowerSpace,
            boolean fadedStem) {
        float left = bar.left() * width, right = bar.right() * width;
        var frame = PrintedDirectionStaff.printedRules(ink, width, height, right - nominal * 2.55f,
                bar.bottom() * height - nominal * 2.1f, nominal);
        if (frame == null || Math.abs(frame[1] - nominal) > nominal * .12f) return -1;
        float gap = frame[1];
        var staff = new PlayingTechniqueDetector.Staff(frame[0] - 4 * gap, frame[0], gap, 0, 1);
        int boundary = PrintedCadenceDurations.boundaryColumn(ink, width, staff, right + .35f * gap, false, true);
        if (boundary < 0 || PrintedCadenceDurations.boundaryColumn(
                        ink, width, staff, (left + previous.right() * width) * .5f, true, false) < 0
                || !PrintedCadenceDurations.emptyOutsideChord(ink, width, staff, left, boundary,
                        stopped.x, stopped.x, false)
                || Math.abs(stopped.y - (frame[0] - stopped.note.staffStep() * gap * .5f)) > gap * .24f
                || !filledCore(ink, width, stopped.x, stopped.y, gap)) return -1;
        float touchX = touch == null ? stopped.x : touch.x;
        float touchY = touch == null ? stopped.y - 1.5f * gap : touch.y;
        boolean bottomLine = Math.abs(touchY - frame[0]) <= gap * .32f;
        boolean firstSpaceBelow = touch != null && touch.note.staffStep() == -1
                && Math.abs(touchY - (frame[0] + gap * .5f)) <= gap * .19f;
        boolean ownedTouch = inlineDoubleRule
                ? lowerSpace ? firstSpaceBelow
                        : Math.abs(touchY - (frame[0] - touch.note.staffStep() * gap * .5f)) <= gap * .24f
                : bottomLine || firstSpaceBelow;
        if (Math.abs(stopped.y - touchY - 1.5f * gap) > gap * .24f || !ownedTouch) return -1;
        var budget = new PrintedBeamShafts.Budget(100_000);
        if (fadedStem) return fadedSharedStem(ink, width, height, stopped, touch, budget, staff);
        if (Math.abs(stopped.y - touchY - 1.5f * gap) > gap * .20f
                || !ArtificialHarmonics.joinedQuarterStem(ink, width, height, stopped.x, stopped.y, gap, 1))
            return -1;
        var lower = PrintedBeamShafts.at(ink, width, height, stopped.x, stopped.y, gap, budget);
        if (lower.size() != 1 || lower.get(0).direction() != -1) return -1;
        var shaft = lower.get(0);
        int first = shaft.left(), last = shaft.right();
        // The offset diamond still owns the lower note's continuous shaft and must
        // independently pass the diamond contour and flag-free tip checks below.
        if (touch != null && (!lowerSpace || stopped.x - touch.x <= gap * .16f)) {
            var upper = PrintedBeamShafts.at(ink, width, height, touchX, touchY, gap, budget);
            if (upper.size() != 1 || upper.get(0).direction() != -1
                    || Math.abs(upper.get(0).endY() - shaft.endY()) > gap * .38f) return -1;
            first = Math.max(first, upper.get(0).left());
            last = Math.min(last, upper.get(0).right());
        }
        for (int stem = first; stem <= last; stem++) {
            if (ArtificialHarmonics.unflaggedQuarterTip(ink, width, height, stem, touchY, stopped.y,
                            touchY - gap * 2.65f, 1, staff)
                    && ArtificialHarmonics.shaftCrossedDiamond(ink, width, height,
                            touchX, touchY, gap, stem, 1, true)) return stem;
        }
        return -1;
    }

    /** A short faded join can leave only the diamond's long upper shaft recognized. */
    private static int fadedSharedStem(
            byte[] ink, int width, int height, Head stopped, Head touch,
            PrintedBeamShafts.Budget budget, PlayingTechniqueDetector.Staff staff) {
        float gap = staff.gap();
        var upper = PrintedBeamShafts.at(ink, width, height, touch.x, touch.y, gap, budget);
        if (upper.size() != 1 || upper.get(0).direction() != -1) return -1;
        var shaft = upper.get(0);
        int top = Math.round(touch.y + .16f * gap);
        int bottom = Math.round(stopped.y - .07f * gap);
        if (top < 0 || bottom >= height || bottom - top < gap * 1.2f) return -1;
        for (int x = shaft.left(); x <= shaft.right(); x++) {
            if (x < 0 || x >= width || x - stopped.x < gap * .45f || x - stopped.x > gap * .82f)
                continue;
            int firstMissing = -1, lastMissing = -1;
            for (int y = top; y <= bottom; y++) {
                if ((ink[y * width + x] & 255) < 155) continue;
                if (firstMissing == -1) firstMissing = y;
                lastMissing = y;
            }
            if (firstMissing <= top || lastMissing > touch.y + gap * .56f
                    || lastMissing - firstMissing + 1 > gap * .32f) continue;
            if (ArtificialHarmonics.unflaggedQuarterTip(
                            ink, width, height, x, touch.y, stopped.y, touch.y - 2.65f * gap, 1, staff)
                    && ArtificialHarmonics.shaftCrossedDiamond(
                            ink, width, height, touch.x, touch.y, gap, x, 1, true)) return x;
        }
        return -1;
    }

    private static boolean filledCore(byte[] ink, int width, float x, float y, float gap) {
        int center = Math.round(x), middle = Math.round(y), dark = 0;
        int across = Math.round(gap * .23f), down = Math.round(gap * .14f);
        for (int row = middle - down; row <= middle + down; row++)
            for (int col = center - across; col <= center + across; col++)
                if ((ink[row * width + col] & 255) < 155) dark++;
        return dark >= (2 * across + 1) * (2 * down + 1) * .76f;
    }
}
