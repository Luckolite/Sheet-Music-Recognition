// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Place an admitted downstem quarter at the second of two printed open harmonic attacks. */
final class PenultimateHarmonicQuarterClock {
    private PenultimateHarmonicQuarterClock() {}

    private record Shaft(int x, int upper, int tip) {
        int length() {
            return tip - upper + 1;
        }
    }

    static void place(
            ScorePageInterpretation score,
            List<Map<String, Object>> events,
            byte[] normalized,
            byte[] raw,
            int width,
            int height,
            float[] beats,
            double[] starts) {
        int bar = beats.length - 2;
        var regions = score.measures();
        if (bar < 1
                || regions.size() != beats.length
                || starts.length != beats.length + 1
                || beats[bar] != 2
                || beats[bar + 1] != 1
                || regions.get(bar + 1).cadenceQuarterBeats() != 1
                || width < 1
                || height < 1
                || normalized == null
                || raw == null
                || normalized.length != (long) width * height
                || raw.length != normalized.length
                || score.rests().stream().anyMatch(rest -> rest.measureIndex() == bar)) return;
        ScoreMeterChange meter = null;
        for (var change : score.meterChanges()) if (change.measureIndex() <= bar) meter = change;
        if (meter == null || meter.numerator() != 2 || meter.denominator() != 4) return;
        var notes = score.notes();
        if (events.size() != notes.size()) return;
        int owner = -1;
        for (int i = 0; i < notes.size(); i++) {
            if (notes.get(i).measureIndex() != bar) continue;
            if (owner >= 0) return;
            owner = i;
        }
        if (owner < 0 || owner >= events.size()) return;
        var note = notes.get(owner);
        var event = events.get(owner);
        if (note.kind() != ScoreNoteEvent.Kind.PITCHED
                || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                || note.staffCount() != 1
                || note.staffIndex() != 0
                || note.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE
                || note.stemDirection() != -1
                || note.beamCount() != 0
                || note.unbeamedDurationBeats() != 1
                || note.tupletDivisor() != 1
                || note.tupletNormalNotes() != 1
                || note.augmentationDots() != 0
                || note.crossStaffBeam()
                || note.tiedFromPrevious()
                || note.boundaryTies() != 0
                || note.leadingRestBeats() != 0
                || note.followingRestBeats() != 0
                || note.harmonicSemitones() != 0
                || note.octaveShift() != 0
                || note.openStringTouch() != null
                || (note.articulations() & NoteOrnament.GRACE) != 0
                || !Integer.valueOf(owner).equals(event.getOrDefault("sourceNoteIndex", owner))
                || !Integer.valueOf(bar).equals(event.get("measureIndex"))
                || !Boolean.FALSE.equals(event.get("durationFallback"))
                || event.containsKey("writtenPlacement")
                || !(event.get("startBeat") instanceof Number onset)
                || !(event.get("durationBeats") instanceof Number length)
                || length.doubleValue() != 1
                || onset.doubleValue() <= starts[bar] + 1
                || onset.doubleValue() >= starts[bar + 1]) return;
        var previous = regions.get(bar - 1);
        var region = regions.get(bar);
        var next = regions.get(bar + 1);
        if (proves(normalized, width, height, previous, region, next, note)
                && (raw == normalized
                        || proves(raw, width, height, previous, region, next, note))) {
            event.put("startBeat", starts[bar] + 1);
        }
    }

    private static boolean proves(
            byte[] ink,
            int width,
            int height,
            MeasureRegion previous,
            MeasureRegion region,
            MeasureRegion next,
            ScoreNoteEvent note) {
        float nominal = (region.bottom() - region.top()) * height / 8.2f;
        float left = region.left() * width, right = region.right() * width;
        if (!Float.isFinite(nominal)
                || nominal < 12
                || nominal > 24
                || (right - left) < nominal * 5.1f
                || (right - left) > nominal * 8.3f
                || Math.abs(previous.bottom() - region.bottom()) * height > nominal * .25f
                || Math.abs(next.bottom() - region.bottom()) * height > nominal * .25f)
            return false;
        var frame =
                PrintedDirectionStaff.printedRules(
                        ink,
                        width,
                        height,
                        (left + right) * .5f,
                        region.bottom() * height - 2.1f * nominal,
                        nominal);
        if (frame == null || Math.abs(frame[1] - nominal) > nominal * .12f) return false;
        float gap = frame[1], bottom = frame[0];
        var staff = new PlayingTechniqueDetector.Staff(bottom - 4 * gap, bottom, gap, 0, 1);
        int opening =
                PrintedCadenceDurations.boundaryColumn(
                        ink, width, staff, (previous.right() * width + left) * .5f, true, false);
        int closure =
                PrintedCadenceDurations.boundaryColumn(
                        ink, width, staff, (right + next.left() * width) * .5f, true, false);
        if (opening < 0 || closure - opening < gap * 6.2f || closure - opening > gap * 8.4f)
            return false;
        var shafts = downshafts(ink, width, height, opening, closure, staff);
        if (shafts.size() != 2) return false;
        var a = shafts.get(0);
        var b = shafts.get(1);
        float x1 = a.x + .63f * gap, x2 = b.x + .63f * gap;
        float x = left + note.positionInMeasure() * (right - left), y = note.pageY() * height;
        if (a.x - opening < gap * 1.08f
                || a.x - opening > gap * 2.15f
                || b.x - a.x < gap * 2.32f
                || b.x - a.x > gap * 3.37f
                || Math.abs(x - x2) > gap * .29f
                || Math.abs(y - b.upper) > gap * .44f
                || Math.abs(y - (bottom - note.staffStep() * .5f * gap)) > gap * .29f) return false;
        return clearShaftGap(ink, width, height, a, b, staff)
                && twoFingeringRings(ink, width, height, a, x1, staff)
                && twoFingeringRings(ink, width, height, b, x2, staff)
                && PrintedCadenceDurations.emptyOutsideChord(
                        ink, width, staff, opening, x2 - gap * 1.23f, x1, x1, false)
                && PrintedCadenceDurations.emptyOutsideChord(
                        ink, width, staff, x1 + gap * .90f, closure, x2, x2, false);
    }

    private static boolean clearShaftGap(
            byte[] ink,
            int width,
            int height,
            Shaft a,
            Shaft b,
            PlayingTechniqueDetector.Staff staff) {
        float gap = staff.gap();
        int left = a.x + Math.round(gap * 1.10f), right = b.x - Math.round(gap * .4f);
        int top = Math.min(a.tip, b.tip) - Math.round(gap * .9f);
        int bottom = Math.max(a.tip, b.tip) + Math.round(gap * .25f);
        if (left < 0 || right >= width || right - left < gap * .65f || top < 0 || bottom >= height)
            return false;
        for (int x = left; x <= right; x++) {
            int dark = 0;
            for (int y = top; y <= bottom; y++) {
                float level = (staff.bottom() - y) / gap;
                if (level >= -.22f && level <= 4.22f && Math.abs(level - Math.round(level)) <= .22f)
                    continue;
                if ((ink[y * width + x] & 255) < 155 && ++dark > 1) return false;
            }
        }
        return true;
    }

    private static List<Shaft> downshafts(
            byte[] ink,
            int width,
            int height,
            int opening,
            int closure,
            PlayingTechniqueDetector.Staff staff) {
        float gap = staff.gap(), bottom = staff.bottom();
        int from = opening + Math.round(gap * .65f), to = closure - Math.round(gap * .65f);
        int top = Math.round(staff.top() - gap * 1.65f), limit = Math.round(bottom + gap * 1.18f);
        if (from < 0 || to >= width || top < 0 || limit >= height) return List.of();
        var found = new ArrayList<Shaft>(2);
        for (int x = from; x <= to; ) {
            var shaft = shaftAt(ink, width, top, limit, x, gap);
            if (shaft == null) {
                x++;
                continue;
            }
            int start = x++;
            while (x <= to && shaftAt(ink, width, top, limit, x, gap) != null) x++;
            var axis = shaftAt(ink, width, top, limit, (start + x - 1) / 2, gap);
            if (x - start > gap * .31f
                    || !ArtificialHarmonics.unflaggedQuarterTip(
                            ink,
                            width,
                            height,
                            axis.x,
                            axis.upper,
                            axis.tip - 1.5f * gap,
                            axis.tip,
                            -1,
                            staff)) return List.of();
            found.add(axis);
            if (found.size() > 2) return List.of();
        }
        return found;
    }

    private static Shaft shaftAt(byte[] ink, int width, int top, int limit, int x, float gap) {
        Shaft best = null;
        for (int y = top; y <= limit; ) {
            int start = y;
            while (y <= limit && (ink[y * width + x] & 255) < 155) y++;
            if (y - start >= gap * 3.55f
                    && y - start <= gap * 5.7f
                    && (best == null || y - start > best.length()))
                best = new Shaft(x, start, y - 1);
            y++;
        }
        return best;
    }

    private static boolean twoFingeringRings(
            byte[] ink,
            int width,
            int height,
            Shaft shaft,
            float x,
            PlayingTechniqueDetector.Staff staff) {
        float gap = staff.gap(), bottom = staff.bottom();
        var budget = new NaturalHarmonicRingProof.Budget(1_000_000, 1_000_000);
        int left = Math.round(x - gap * .91f), right = Math.round(x + gap * .91f);
        int high = Math.round(shaft.upper - gap * 2.4f), low = Math.round(bottom + gap * 4.0f);
        if (left < 0 || right >= width || high < 0 || low >= height) return false;
        return oneRing(
                        ink,
                        width,
                        height,
                        left,
                        right,
                        high,
                        Math.round(shaft.upper - gap * .30f),
                        x,
                        gap,
                        budget)
                && oneRing(
                        ink,
                        width,
                        height,
                        left,
                        right,
                        Math.round(bottom + gap * .65f),
                        low,
                        x,
                        gap,
                        budget);
    }

    private static boolean oneRing(
            byte[] ink,
            int width,
            int height,
            int left,
            int right,
            int top,
            int bottom,
            float x,
            float gap,
            NaturalHarmonicRingProof.Budget budget) {
        return NaturalHarmonicRingProof.rings(
                                ink,
                                width,
                                height,
                                new NaturalHarmonicRingProof.Box(left, top, right, bottom),
                                180,
                                Math.round(gap * .45f),
                                Math.round(gap * 1.55f),
                                budget)
                        .stream()
                        .filter(ring -> Math.abs(x - ring.centerX()) <= gap * .44f)
                        .filter(
                                ring ->
                                        ring.counterPixels()
                                                >= ring.bounds().width()
                                                        * ring.bounds().height()
                                                        * .17f)
                        .count()
                == 1;
    }
}
