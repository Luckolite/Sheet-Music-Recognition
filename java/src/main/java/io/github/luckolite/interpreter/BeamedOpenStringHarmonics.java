// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** Retains a hollow touching pitch whose upstem belongs to the surrounding printed beams. */
final class BeamedOpenStringHarmonics {
    private BeamedOpenStringHarmonics() {}

    static ScorePageInterpretation capture(
            ScorePageInterpretation score, List<Map<String, Object>> events, byte[] labels,
            byte[] original, byte[] normalized, int width, int height, float[] beats,
            double[] starts, int[] keys) {
        if (labels == null || original == null || normalized == null
                || original.length != (long) width * height || normalized.length != original.length
                || labels.length != original.length || events.size() != score.notes().size())
            return score;
        var revised = new ArrayList<>(score.notes());
        boolean changed = false;
        for (int owner = 0; owner < revised.size(); owner++) {
            var note = revised.get(owner);
            if (!halfCandidate(note)) continue;
            int m = note.measureIndex();
            if (m < 0 || m >= starts.length || m >= keys.length || m >= beats.length
                    || m >= score.measures().size()) continue;
            var event = events.get(owner);
            if (!(event.get("sourceNoteIndex") instanceof Number index) || index.intValue() != owner
                    || !(event.get("startBeat") instanceof Number onset)
                    || !(event.get("durationBeats") instanceof Number duration)
                    || duration.doubleValue() != 2 || Boolean.TRUE.equals(event.get("durationFallback"))
                    || event.containsKey("writtenMidi") || event.containsKey("octaveShift")
                    || event.containsKey("tupletActualNotes")
                    || !(event.get("writtenPlacement")
                            instanceof ScoreNoteTiming.WrittenPlacement placement)
                    || placement.durationBeats() != 2 || placement.principalIndex() != -1)
                continue;
            double at = onset.doubleValue() - starts[m];
            if (!(at > 0 && at + .25 <= beats[m] && at + 2 > beats[m])
                    || Math.abs(at * 4 - Math.rint(at * 4)) > 1e-6
                    || Math.abs(placement.onsetBeats() - at) > 1e-6) continue;

            int written = ScoreTiePitchGuard.writtenMidi(
                    note.diatonicPitchIdentity(), note.writtenAccidental(), keys[m]);
            var tuning = OpenStringHarmonics.sounding(written);
            if (tuning == null || !Integer.valueOf(written).equals(event.get("midi"))) continue;
            var region = score.measures().get(m);
            float gap = (region.bottom() - region.top()) * height / 8.2f;
            float x = (region.left() + note.positionInMeasure() * (region.right() - region.left()))
                    * width;
            float y = note.pageY() * height;
            if (gap < 10 || gap > 24 || !proved(labels, original, width, height, region, x, y, gap)
                    || !proved(labels, normalized, width, height, region, x, y, gap)) continue;
            revised.set(owner, new ScoreNoteEvent(
                    m, note.positionInMeasure(), note.staffStep(), 0, 1, note.pageY(),
                    false, 0, 2, note.writtenAccidental(), 0, 1, 0, note.articulations(),
                    note.clefBottomDiatonic(), false, 0, note.compactOpening(), 0, 0, 1, 1));
            event.put("writtenPlacement", new ScoreNoteTiming.WrittenPlacement(
                    placement.onsetBeats(), .25, -1, -1, false, 0));
            event.put("durationBeats", .25);
            event.put("midi", tuning.sounding());
            event.put("writtenMidi", written);
            event.put("harmonicTouchMidi", written);
            event.put("openStringMidi", tuning.open());
            event.put("beamedOpenStringHarmonic", true);
            changed = true;
        }
        if (!changed) return score;
        return score.withNotes(revised, IntStream.range(0, revised.size()).boxed().toList())
                .withClockContextSource(score.clockContextSource());
    }

    private static boolean halfCandidate(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffIndex() == 0 && n.staffCount() == 1
                && n.clefBottomDiatonic() == ScoreNoteEvent.CLEF_TREBLE
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.unbeamedDurationBeats() == ScoreNoteEvent.DURATION_HALF && n.beamCount() == 0
                && n.stemDirection() == 1 && !n.crossStaffBeam() && !n.tiedFromPrevious()
                && n.tupletDivisor() == 1 && n.tupletNormalNotes() == 1
                && n.augmentationDots() == 0 && n.boundaryTies() == 0 && n.octaveShift() == 0
                && n.harmonicSemitones() == 0 && n.openStringTouch() == null
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0
                && n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    static boolean proved(byte[] labels, byte[] gray, int w, int h,
            MeasureRegion bar, float x, float y, float hintGap) {
        float floor = bar.bottom() * h - 2.1f * hintGap;
        var track = StaffPitchTrack.forPrintedSlurs(
                gray, w, h, floor - 4 * hintGap, floor, hintGap);
        if (track == null) return false;
        var local = track.at(x);
        float gap = local[1], bottom = local[0];
        var budget = new PrintedBeamShafts.Budget(760_000);
        var owner = upShaft(gray, w, h, x, y, gap, budget);
        if (owner == null || !diamond(labels, gray, w, h, owner, x, y, gap)
                || zero(gray, w, h, x, bottom, gap, .40f) == null) return false;
        var before = ringPeer(labels, gray, w, h, track, bar, owner, x, -1, gap, budget);
        var after = ringPeer(labels, gray, w, h, track, bar, owner, x, 1, gap, budget);
        if (before == null || after == null || budget.refused()) return false;
        return WrappedEighthTriplets.matchesBeamRails(gray, w, h, before, owner, gap, 2)
                && (WrappedEighthTriplets.matchesBeamRails(gray, w, h, owner, after, gap, 2)
                        || WrappedEighthTriplets.matchesBeamRails(gray, w, h, owner, after, gap, 1));
    }

    private static PrintedBeamShafts.Shaft upShaft(byte[] gray, int w, int h,
            float x, float y, float g, PrintedBeamShafts.Budget budget) {
        var stems = PrintedBeamShafts.at(gray, w, h, x, y, g, budget);
        return stems.size() == 1 && stems.get(0).direction() == -1 ? stems.get(0) : null;
    }

    private static boolean diamond(byte[] labels, byte[] gray, int w, int h,
            PrintedBeamShafts.Shaft shaft, float x, float y, float gap) {
        int axis = (shaft.left() + shaft.right()) / 2;
        return ArtificialHarmonics.shaftCrossedDiamond(gray, w, h, x, y, gap, axis, 1, true)
                || LedgerCrossedHarmonicDiamond.proved(labels, gray, w, h, x, y, gap, axis, 1);
    }

    private static Float zero(byte[] gray, int w, int h, float x, float floor, float gap,
            float maxOffset) {
        int radius = Math.round(gap * (maxOffset + .69f));
        int center = Math.round(x);
        int top = Math.round(floor + gap * 1.2f), bottom = Math.round(floor + gap * 5.8f);
        if (center - radius < 0 || center + radius >= w || top < 0 || bottom >= h)
            return null;
        var box = new NaturalHarmonicRingProof.Box(center-radius, top, center+radius, bottom);
        NaturalHarmonicRingProof.Ring selected = null;
        for (int cutoff : new int[] {150, 175, 195, 215}) {
            var matches = NaturalHarmonicRingProof.rings(gray, w, h, box, cutoff,
                            Math.round(gap * .57f), Math.round(gap * 1.53f),
                            new NaturalHarmonicRingProof.Budget(220_000, 220_000))
                    .stream().filter(r -> Math.abs(r.centerX() - x) <= maxOffset * gap
                            && r.counterPixels() >= gap * gap * .16f).toList();
            if (matches.size() > 1) return null;
            if (matches.isEmpty()) continue;
            if (selected != null && (Math.abs(selected.centerX() - matches.get(0).centerX()) > 3
                    || Math.abs(selected.centerY() - matches.get(0).centerY()) > 3)) return null;
            selected = matches.get(0);
        }
        return selected == null ? null : selected.centerX();
    }

    private static PrintedBeamShafts.Shaft ringPeer(byte[] labels, byte[] gray, int w, int h,
            StaffPitchTrack track, MeasureRegion bar, PrintedBeamShafts.Shaft owner, float x,
            int side, float gap, PrintedBeamShafts.Budget budget) {
        float hint = x + side * gap * 2.20f;
        if (hint < bar.left() * w || hint > bar.right() * w) return null;
        var ring = zero(gray, w, h, hint, track.at(hint)[0], gap, .61f);
        if (ring == null || ring < bar.left() * w || ring > bar.right() * w) return null;
        var local = track.at(ring);
        PrintedBeamShafts.Shaft result = null;
        for (int step = -3; step <= 11; step++) {
            float y = local[0] - step * local[1] * .5f;
            var shaft = upShaft(gray, w, h, ring, y, local[1], budget);
            if (shaft == null || Math.abs(shaft.endY() - owner.endY()) > gap * .48f
                    || !diamond(labels, gray, w, h, shaft, ring, y, local[1])) continue;
            if (result != null) return null;
            result = shaft;
        }
        return result;
    }
}
