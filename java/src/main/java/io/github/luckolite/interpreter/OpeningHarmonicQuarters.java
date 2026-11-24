// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0

package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** An opening pair of open-string touches followed by a stopped fourth and an open touch. */
final class OpeningHarmonicQuarters {
    private OpeningHarmonicQuarters() {}

    private record Column(PrintedOpenHarmonicPair.Evidence first, float secondUpperY,
                          int touch, HarmonicFingeringPair.Fingers firstLower,
                          HarmonicFingeringPair.Fingers lastLower) {}

    static ScorePageInterpretation recover(ScorePageInterpretation score,
            List<Map<String, Object>> events, byte[] labels, byte[] raw, byte[] normalized,
            int width, int height, float[] beats, int[] keys, double[] starts) {
        if (labels == null || raw == null || normalized == null || labels.length != (long) width * height
                || labels.length != raw.length || labels.length != normalized.length
                || score.notes().size() != events.size()) return score;
        for (var change : score.meterChanges()) {
            int index = change.measureIndex();
            if (change.numerator() != 2 || change.denominator() != 4
                    || index < 1 || index + 1 >= beats.length || beats[index] != 2
                    || score.rests().stream().anyMatch(rest -> rest.measureIndex() == index)) continue;
            var notes = score.notes();
            var indices = IntStream.range(0, notes.size()).filter(i -> notes.get(i).measureIndex() == index).toArray();
            if (indices.length != 1) continue;
            int owner = indices[0];
            var stopped = notes.get(owner);
            var event = events.get(owner);
            if (!eligible(stopped, event, owner, starts[index])) continue;
            var bars = score.measures();
            var bar = bars.get(index);
            var subsequent = bars.get(index + 1);
            var first = prove(labels, raw, width, height, bar, subsequent, stopped, keys[index]);
            if (first == null || !first.equals(prove(labels, normalized, width, height, bar, subsequent,
                    stopped, keys[index]))) continue;
            if (!MeterFontMatcher.standaloneReadings(labels, raw, width, height, bars, notes).contains(change)
                    || !MeterFontMatcher.standaloneReadings(labels, normalized, width, height, bars, notes).contains(change))
                continue;

            var nextNotes = new ArrayList<>(notes);
            var owners = new ArrayList<>(IntStream.range(0, notes.size()).boxed().toList());
            var position = first.first();
            var naturalLower = ClosedOpenAndStoppedHarmonicQuarters.natural(position.lower(),
                    position.lowerY(), pitch(position.lower(), keys[index]), first.firstLower().touch());
            var naturalUpper = ClosedOpenAndStoppedHarmonicQuarters.natural(position.upper(),
                    position.upperY(), pitch(position.upper(), keys[index]), 0);
            var parallelUpper = ClosedOpenAndStoppedHarmonicQuarters.natural(position.upper(),
                    first.secondUpperY(), pitch(position.upper(), keys[index]), 0);
            if (naturalLower == null || naturalUpper == null || parallelUpper == null
                    || naturalLower.open() == naturalUpper.open()) continue;
            double start = starts[index];
            boolean written = event.containsKey("writtenPlacement");
            append(nextNotes, events, owners, bar, index, position.headX(), naturalLower,
                    width, height, start, "below", true, written, 0);
            append(nextNotes, events, owners, bar, index, position.headX(), naturalUpper,
                    width, height, start, "above", true, written, 0);
            float x = x(stopped, bar, width);
            append(nextNotes, events, owners, bar, index, x, parallelUpper,
                    width, height, start + 1, "above", false, written, 1);
            nextNotes.set(nextNotes.size() - 1, new ScoreNoteEvent(index, stopped.positionInMeasure(),
                    position.upper(), 0, 1, first.secondUpperY() / height, false, 0, 0,
                    ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 1, 1, 0, 0, ScoreNoteEvent.CLEF_TREBLE)
                    .withStemDirection(1));
            var newStop = stopped.withOctaveShift(0).withHarmonicSemitones(24)
                    .withOpenStringTouch(new ScoreNoteEvent.OpenStringTouch(position.upper(),
                            first.secondUpperY() / height, ScoreNoteEvent.ACCIDENTAL_FROM_KEY));
            nextNotes.set(owner, newStop);
            event.remove("octaveShift");
            event.put("writtenMidi", first.touch() - 5);
            event.put("harmonicTouchMidi", first.touch());
            event.put("artificialHarmonicFourth", true);
            event.put("compoundHarmonicFourth", true);
            event.put("openingHarmonicStoppedFingering", Map.of("base", first.lastLower().base(),
                    "touch", first.lastLower().touch(), "placement", "below"));
            event.put("startBeat", start + 1);
            if (written) event.put("writtenPlacement", placement(1));
            return score.withNotes(nextNotes, owners).withClockContextSource(
                    score.clockContextSource().withNoteCount(nextNotes.size()));
        }
        return score;
    }

    private static boolean eligible(ScoreNoteEvent n, Map<String, Object> e, int owner, double start) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.clefBottomDiatonic() == ScoreNoteEvent.CLEF_TREBLE
                && n.staffIndex() == 0 && n.staffCount() == 1 && n.stemDirection() == 1
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL && n.beamCount() == 0
                && n.unbeamedDurationBeats() == 1 && n.octaveShift() == 2 && n.openStringTouch() == null
                && n.harmonicSemitones() == 0 && n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && n.tupletDivisor() == 1 && n.tupletNormalNotes() == 1 && n.augmentationDots() == 0
                && n.articulations() == 0 && !n.crossStaffBeam() && !n.tiedFromPrevious()
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0 && n.boundaryTies() == 0
                && e.get("sourceNoteIndex") instanceof Number i && i.intValue() == owner
                && e.get("startBeat") instanceof Number t && t.doubleValue() > start + 1
                && t.doubleValue() <= start + 2
                && e.get("durationBeats") instanceof Number d && d.doubleValue() == 1
                && !Boolean.TRUE.equals(e.get("durationFallback"));
    }

    private static Column prove(byte[] labels, byte[] ink, int width, int height, MeasureRegion bar,
            MeasureRegion next, ScoreNoteEvent stopped, int key) {
        float nominal = (bar.bottom() - bar.top()) * height / 8.2f;
        if (nominal < 10 || nominal > 25 || Math.abs(bar.bottom() - next.bottom()) * height > nominal * .24f)
            return null;
        float x = x(stopped, bar, width);
        var frame = PrintedDirectionStaff.printedRules(ink, width, height, x,
                bar.bottom() * height - 2.1f * nominal, nominal);
        if (frame == null) return null;
        float gap = frame[1];
        var first = PrintedOpenHarmonicPair.find(labels, ink, width, height, x - 4.45f * gap,
                x - 3.55f * gap, frame[0], gap, key);
        if (first == null || first.lower() != stopped.staffStep() + 3
                || first.upper() - stopped.staffStep() < 5 || first.upper() - stopped.staffStep() > 6)
            return null;
        float upper = frame[0] - first.upper() * gap * .5f;
        var proposed = stopped.withOpenStringTouch(new ScoreNoteEvent.OpenStringTouch(
                first.upper(), upper / height, ScoreNoteEvent.ACCIDENTAL_FROM_KEY));
        if (OpenStringHarmonics.fourthPartnerShaft(ink, width, height, bar, proposed, x) < 0) return null;
        var staff = new PlayingTechniqueDetector.Staff(frame[0] - 4 * gap, frame[0], gap, 0, 1);
        int boundary = PrintedCadenceDurations.boundaryColumn(ink, width, staff,
                (bar.right() + next.left()) * width * .5f, true, false);
        if (boundary < 0 || !PrintedCadenceDurations.emptyOutsideChord(ink, width, staff,
                first.headX() - 1.55f * gap, boundary, first.headX(), x, false)) return null;
        int touch = pitch(first.lower(), key);
        if (touch - pitch(stopped.staffStep(), key) != 5) return null;
        var low = HarmonicFingeringPair.read(ink, width, height, first.headX(),
                first.lowerZeroY() - 2.38f * gap, first.lowerZeroY() + 1.22f * gap, gap, false);
        float stopY = stopped.pageY() * height;
        var last = HarmonicFingeringPair.read(ink, width, height, x,
                stopY + gap * .5f, stopY + 3.9f * gap, gap, false);
        return low != null && low.base() == 0 && last != null && last.base() > 0
                ? new Column(first, upper, touch, low, last) : null;
    }

    private static void append(List<ScoreNoteEvent> notes, List<Map<String, Object>> events,
            List<Integer> origins, MeasureRegion bar, int measure, float x,
            ClosedOpenAndStoppedHarmonicQuarters.Head head, int w, int h, double time,
            String side, boolean first, boolean written, int beat) {
        ClosedOpenAndStoppedHarmonicQuarters.append(notes, events, origins, bar, measure,
                x, head, w, h, time, side, 1);
        var event = events.get(events.size() - 1);
        event.remove("mixedHarmonicQuarter");
        var mark = event.remove("mixedHarmonicFingering");
        if (first) event.put("openingOpenHarmonicPair", true);
        if (head.finger() > 0) event.put("openingPairFingering", mark);
        if (written) event.put("writtenPlacement", placement(beat));
    }

    private static ScoreNoteTiming.WrittenPlacement placement(int beat) {
        return new ScoreNoteTiming.WrittenPlacement(beat, 1, -1, -1, false, 0);
    }

    private static float x(ScoreNoteEvent note, MeasureRegion region, int width) {
        return (region.left() + (region.right() - region.left()) * note.positionInMeasure()) * width;
    }

    private static int pitch(int step, int key) {
        return ScoreTiePitchGuard.writtenMidi(ScoreNoteEvent.CLEF_TREBLE + step,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
    }
}
