// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** Two upstem quarters can alternate a stopped/open chord and two stopped harmonics. */
final class ClosedUpstemHarmonicChange {
    private ClosedUpstemHarmonicChange() {}

    private record Proof(int firstShaft, int secondShaft, int openStep, float openY) {}

    static ScorePageInterpretation recover(
            ScorePageInterpretation score, byte[] gray, byte[] raw,
            int width, int height, int initialKey) {
        if (gray == null || raw == null || gray.length != (long) width * height
                || gray.length != raw.length) return score;
        var notes = score.notes();
        var bars = score.measures();
        var removals = new java.util.BitSet();
        var replacements = new HashMap<Integer, ScoreNoteEvent>();
        for (var meter : score.meterChanges()) {
            int bar = meter.measureIndex();
            if (meter.numerator() != 2 || meter.denominator() != 4 || bar < 1 || bar + 1 >= bars.size()
                    || bars.get(bar).cadenceQuarterBeats() != 0
                    || score.rests().stream().anyMatch(rest -> rest.measureIndex() == bar)) continue;
            var region = bars.get(bar);
            var ids = IntStream.range(0, notes.size()).filter(i -> notes.get(i).measureIndex() == bar)
                    .boxed().sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure())).toList();
            if (ids.size() != 4) continue;
            var first = notes.get(ids.get(0));
            var tail = ids.subList(1, 4).stream()
                    .sorted(Comparator.comparingDouble(i -> notes.get(i).pageY())).toList();
            int upperOwner = tail.get(0), touchOwner = tail.get(1), lowerOwner = tail.get(2);
            var upper = notes.get(upperOwner);
            var touch = notes.get(touchOwner);
            var lower = notes.get(lowerOwner);
            if (!ordinary(first, 1, 2) || !ordinary(upper, 1, 2) || !ordinary(lower, 1, 0)
                    || !ordinary(touch, 2, 0) || first.staffStep() != lower.staffStep()
                    || touch.staffStep() - lower.staffStep() != 2
                    || upper.staffStep() - lower.staffStep() != 4
                    || score.beamSource().noteOrigins().get(touchOwner).isPresent()) continue;
            int key = score.keyChanges().stream().filter(change -> change.measureIndex() <= bar)
                    .max(Comparator.comparingInt(ScoreKeyChange::measureIndex)).map(ScoreKeyChange::fifths)
                    .orElse(initialKey);
            int stopped = pitch(first.diatonicPitchIdentity(), key);
            int lowTouch = pitch(touch.diatonicPitchIdentity(), key);
            int highStop = pitch(upper.diatonicPitchIdentity(), key);
            int openTouch = pitch(first.diatonicPitchIdentity() + 5, key);
            int natural = OpenStringHarmonics.uniqueSoundingMidi(openTouch);
            if (lowTouch - stopped != 4 || pitch(first.diatonicPitchIdentity() + 3, key) - stopped != 5
                    || pitch(upper.diatonicPitchIdentity() + 3, key) - highStop != 5
                    || natural < 0 || natural == stopped + 24) continue;
            var a = proof(gray, width, height, region, bars.get(bar + 1), first, lower, touch, upper);
            var b = proof(raw, width, height, region, bars.get(bar + 1), first, lower, touch, upper);
            if (a == null || b == null || Math.abs(a.firstShaft() - b.firstShaft()) > 1
                    || Math.abs(a.secondShaft() - b.secondShaft()) > 1
                    || a.openStep() != b.openStep() || Math.abs(a.openY() - b.openY()) > 1) continue;
            var open = new ScoreNoteEvent.OpenStringTouch(
                    b.openStep(), b.openY() / height, ScoreNoteEvent.ACCIDENTAL_FROM_KEY);
            removals.set(touchOwner);
            replacements.put(ids.get(0), first.withOctaveShift(0).withHarmonicSemitones(24).withOpenStringTouch(open));
            replacements.put(lowerOwner, lower.withHarmonicSemitones(28));
            replacements.put(upperOwner, upper.withOctaveShift(0).withHarmonicSemitones(24));
        }
        if (removals.isEmpty()) return score;
        var retained = new ArrayList<ScoreNoteEvent>();
        var owners = new ArrayList<Integer>();
        for (int i = 0; i < notes.size(); i++) {
            if (removals.get(i)) continue;
            retained.add(replacements.getOrDefault(i, notes.get(i)));
            owners.add(i);
        }
        return score.withNotes(retained, owners);
    }

    /** The printed pitches belong to both complete attacks of the recovered bar. */
    static void retainRoles(ScorePageInterpretation score, List<Map<String, Object>> events,
            double[] starts, int[] keys) {
        var notes = score.notes();
        for (var meter : score.meterChanges()) {
            int bar = meter.measureIndex();
            if (meter.numerator() != 2 || meter.denominator() != 4 || starts[bar + 1] - starts[bar] != 2) continue;
            var owners = IntStream.range(0, notes.size()).filter(i -> notes.get(i).measureIndex() == bar)
                    .boxed().sorted(Comparator.<Integer>comparingDouble(i -> ((Number) events.get(i).get("startBeat")).doubleValue())
                            .thenComparingDouble(i -> -notes.get(i).pageY())).toList();
            if (owners.size() != 3) continue;
            int a = owners.get(0), b = owners.get(1), c = owners.get(2);
            var first = notes.get(a);
            var lower = notes.get(b);
            var upper = notes.get(c);
            if (first.harmonicSemitones() != 24 || first.openStringTouch() == null
                    || first.openStringTouch().staffStep() - first.staffStep() != 5
                    || lower.harmonicSemitones() != 28 || upper.harmonicSemitones() != 24
                    || lower.openStringTouch() != null || upper.openStringTouch() != null
                    || first.stemDirection() != 1 || lower.stemDirection() != 1 || upper.stemDirection() != 1
                    || first.staffStep() != lower.staffStep() || upper.staffStep() - lower.staffStep() != 4
                    || !Main.completeCompoundQuarter(events.get(a), starts[bar], starts[bar] + 1)
                    || !Main.completeCompoundQuarter(events.get(b), starts[bar] + 1, starts[bar] + 2)
                    || !Main.completeCompoundQuarter(events.get(c), starts[bar] + 1, starts[bar] + 2)
                    || !Boolean.TRUE.equals(events.get(a).get("artificialHarmonicFourth"))
                    || !Boolean.TRUE.equals(events.get(c).get("artificialHarmonicFourth"))) continue;
            int written = pitch(lower.diatonicPitchIdentity(), keys[bar]);
            int touching = pitch(lower.diatonicPitchIdentity() + 2, keys[bar]);
            if (touching - written != 4) continue;
            var second = events.get(b);
            second.put("writtenMidi", written);
            second.put("harmonicTouchMidi", touching);
            second.put("midi", written + 28);
            second.put("artificialHarmonicThird", true);
            second.put("sharedUpstemHarmonicQuarter", true);
            events.get(c).put("sharedUpstemHarmonicQuarter", true);
            events.get(a).put("compoundHarmonicFourth", true);
        }
    }

    private static boolean ordinary(ScoreNoteEvent note, float duration, int octave) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED && note.staffIndex() == 0 && note.staffCount() == 1
                && note.clefBottomDiatonic() == ScoreNoteEvent.CLEF_TREBLE
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL && note.stemDirection() == 1
                && note.unbeamedDurationBeats() == duration && note.beamCount() == 0
                && note.octaveShift() == octave && note.harmonicSemitones() == 0
                && note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && note.openStringTouch() == null && !note.crossStaffBeam() && note.augmentationDots() == 0
                && note.tupletDivisor() == 1 && note.articulations() == 0
                && !note.tiedFromPrevious() && note.boundaryTies() == 0
                && note.leadingRestBeats() == 0 && note.followingRestBeats() == 0;
    }

    private static int pitch(int diatonic, int key) {
        return ScoreTiePitchGuard.writtenMidi(diatonic, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
    }

    private static float x(MeasureRegion region, ScoreNoteEvent n, int width) {
        return (region.left() + n.positionInMeasure() * (region.right() - region.left())) * width;
    }

    private static Proof proof(byte[] ink, int width, int height,
            MeasureRegion bar, MeasureRegion next, ScoreNoteEvent first,
            ScoreNoteEvent lower, ScoreNoteEvent lowerTouch, ScoreNoteEvent upper) {
        float nominal = (bar.bottom() - bar.top()) * height / 8.2f;
        if (nominal < 12 || nominal > 24 || Math.abs(bar.top() - next.top()) * height > nominal * .4f)
            return null;
        var frame = PrintedDirectionStaff.printedRules(ink, width, height, bar.right() * width - 1.2f * nominal,
                bar.bottom() * height - 2.1f * nominal, nominal);
        if (frame == null) return null;
        float floor = frame[0], gap = frame[1];
        float firstX = x(bar, first, width), lowerX = x(bar, lower, width);
        float firstY = first.pageY() * height, lowerY = lower.pageY() * height;
        float upperX = x(bar, upper, width), upperY = upper.pageY() * height;
        float lowerTouchX = x(bar, lowerTouch, width), lowerTouchY = lowerTouch.pageY() * height;
        if (lowerX - firstX < 3.9f * gap || lowerX - firstX > 5.65f * gap
                || Math.abs(upperX - lowerX) > .06f * gap || Math.abs(lowerTouchX - lowerX) > .24f * gap
                || Math.abs(firstY - lowerY) > .14f * gap
                || Math.abs(floor - upper.staffStep() * gap * .5f - upperY) > .18f * gap
                || Math.abs(floor - lowerTouch.staffStep() * gap * .5f - lowerTouchY) > .22f * gap
                || Math.abs(floor - lower.staffStep() * gap * .5f - lowerY) > .55f * gap) return null;
        var staff = new PlayingTechniqueDetector.Staff(floor - 4 * gap, floor, gap, 0, 1);
        int boundary = PrintedCadenceDurations.boundaryColumn(ink, width, staff, bar.right() * width, true, false);
        if (boundary < lowerX + 2.1f * gap || boundary > lowerX + 4.3f * gap
                || !PrintedCadenceDurations.emptyOutsideChord(ink, width, staff,
                    firstX, boundary, firstX, lowerX, false)) return null;
        int openStep = first.staffStep() + 5;
        float openY = floor - openStep * gap * .5f;
        float firstTouchY = floor - (first.staffStep() + 3) * gap * .5f;
        float upperTouchY = floor - (upper.staffStep() + 3) * gap * .5f;
        if (DetachedHarmonicZero.above(ink, width, height, firstX, openY - gap * 2.08f, gap, false) == null
                || !ArtificialHarmonics.filledQuarterCore(ink, width, height, upperX, upperY, gap))
            return null;
        int a = proveSharedShaft(ink, width, height, staff, firstX, firstY,
                firstX, firstTouchY, firstX, openY);
        int b = proveSharedShaft(ink, width, height, staff, lowerX, lowerY,
                lowerTouchX, lowerTouchY, upperX, upperTouchY);
        if (a < 0 || b < 0) return null;
        return new Proof(a, b, openStep, openY);
    }

    private static int proveSharedShaft(byte[] ink, int width, int height,
            PlayingTechniqueDetector.Staff staff, float x, float y, float x1, float y1, float x2, float y2) {
        float gap = staff.gap();
        if (!ArtificialHarmonics.filledQuarterCore(ink, width, height, x, y, gap)) return -1;
        var found = PrintedBeamShafts.at(ink, width, height, x, y, gap,
                new PrintedBeamShafts.Budget(80_000));
        if (found.size() != 1 || found.get(0).direction() != -1) return -1;
        var shaft = found.get(0);
        for (int col = shaft.left(); col <= shaft.right(); col++) {
            if (!ArtificialHarmonics.shaftCrossedDiamond(ink, width, height, x1, y1, gap, col, 1, true)
                    || !ArtificialHarmonics.shaftCrossedDiamond(ink, width, height, x2, y2, gap, col, 1, true)
                    || !ArtificialHarmonics.unflaggedQuarterTip(ink, width, height, col, y2, y, shaft.endY(), 1, staff))
                continue;
            int light = 0;
            for (int row = Math.round(y2 + gap * .15f); row <= Math.round(y - gap * .30f); row++)
                if ((ink[row * width + col] & 255) >= 168) light++;
            if (light <= 2) return col;
        }
        return -1;
    }
}
