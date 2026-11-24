package io.github.luckolite.interpreter;

import java.util.ArrayList;

/** Optical evidence for two open-string harmonic touches sharing an unflagged upstem. */
final class PrintedOpenHarmonicPair {
    private PrintedOpenHarmonicPair() {}

    record Evidence(float headX, int shaft, int lower, int upper, float lowerY, float upperY,
                    int lowerZeroY, int upperZeroY) {}

    static Evidence agree(byte[] labels, byte[] raw, byte[] normalized, int width, int height,
                           float left, float right, float[] sourceStaff, float[] normalizedStaff, int key) {
        if (sourceStaff == null || normalizedStaff == null
                || sourceStaff.length < 2 || normalizedStaff.length < 2) return null;
        var source = find(labels, raw, width, height, left, right, sourceStaff[0], sourceStaff[1], key);
        if (source == null) return null;
        var other = find(labels, normalized, width, height, left, right, normalizedStaff[0], normalizedStaff[1], key);
        return other != null && Math.abs(source.shaft() - other.shaft()) <= 1
                && source.lower() == other.lower() && source.upper() == other.upper()
                && Math.abs(source.lowerY() - other.lowerY()) <= 1.2f
                && Math.abs(source.upperY() - other.upperY()) <= 1.2f
                && Math.abs(source.lowerZeroY() - other.lowerZeroY()) <= 2
                && Math.abs(source.upperZeroY() - other.upperZeroY()) <= 2 ? source : null;
    }
    static Evidence find(byte[] labels, byte[] ink, int width, int height,
                         float left, float right, float floor, float gap, int key) {
        if (width < 1 || height < 1 || labels == null || ink == null
                || ink.length != (long) width * height || labels.length != ink.length
                || !Float.isFinite(left + right + floor + gap) || left > right
                || gap < 10 || gap > 25 || left < 3 * gap || right > width - 3 * gap
                || right - left > 3 * gap || floor < 12 * gap || floor > height - 7 * gap) return null;
        var staff = new PlayingTechniqueDetector.Staff(floor - 4 * gap, floor, gap, 0, 1);
        var matches = new ArrayList<Evidence>();
        for (int shaft = Math.round(left + .56f * gap); shaft <= Math.round(right + .76f * gap); shaft++) {
            float x = shaft - .51f * gap;
            var steps = new ArrayList<Integer>();
            for (int step = -2; step <= 8; step++) {
                float y = stepY(floor, gap, step);
                if (OpenStringHarmonics.uniqueSoundingMidi(written(step, key)) < 0
                        || !(ArtificialHarmonics.enclosedCounter(ink, width, height, x, y, gap * .82f, gap * .18f)
                          || step >= 0 && step <= 8 && step % 2 == 0
                              && ArtificialHarmonics.symbolDiamond(labels, ink, width, height, x, y, gap))
                        || !ArtificialHarmonics.shaftCrossedDiamond(
                                ink, width, height, x, y, gap, shaft, 1, true)) continue;
                steps.add(step);
            }
            if (steps.size() != 2) continue;
            int low = steps.get(0), high = steps.get(1);
            if (high - low < 2 || high - low > 6) continue;
            float lowY = stepY(floor, gap, low), highY = stepY(floor, gap, high);
            if (!quarterShaft(ink, width, height, shaft, lowY, highY, staff)) continue;
            int lowZero = zero(ink, width, height, x, lowY, gap, 1);
            int highZero = zero(ink, width, height, x, highY, gap, -1);
            if (lowZero < 0 || highZero < 0) continue;
            matches.add(new Evidence(x, shaft, low, high, lowY, highY, lowZero, highZero));
        }
        if (matches.isEmpty() || matches.size() > Math.round(gap * .3f)) return null;
        var first = matches.get(0);
        var last = matches.get(matches.size() - 1);
        return last.shaft() - first.shaft() == matches.size() - 1 && matches.stream().allMatch(p ->
                p.lower() == first.lower() && p.upper() == first.upper()
                && Math.abs(p.lowerZeroY() - first.lowerZeroY()) <= 2
                && Math.abs(p.upperZeroY() - first.upperZeroY()) <= 2)
                ? matches.get(matches.size() / 2) : null;
    }

    private static float stepY(float floor, float gap, int step) {
        return floor - step * gap * .5f;
    }

    private static int written(int step, int key) {
        return ScoreTiePitchGuard.writtenMidi(ScoreNoteEvent.CLEF_TREBLE + step,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
    }

    private static boolean quarterShaft(byte[] ink, int width, int height, int shaft, float lowY,
                                        float highY, PlayingTechniqueDetector.Staff staff) {
        float gap = staff.gap();
        int start = Math.round(lowY - gap * .27f);
        int limit = Math.round(highY - gap * 3.5f);
        if (!dark(ink, width, shaft, start)) return false;
        int end = start;
        while (end > limit && dark(ink, width, shaft, end - 1)) end--;
        return end > limit && highY - end >= 1.0f * gap
                && ArtificialHarmonics.unflaggedQuarterTip(ink, width, height, shaft,
                        highY, lowY, end, 1, staff);
    }

    private static int zero(byte[] ink, int width, int height, float x, float y, float gap, int side) {
        int left = Math.round(x - .72f * gap), right = Math.round(x + .72f * gap);
        int near = Math.round(y + side * .9f * gap), far = Math.round(y + side * 5.0f * gap);
        int top = Math.min(near, far), bottom = Math.max(near, far);
        var components = RawNumeralComponents.captureLongWindow(ink, width, height, left, top, right, bottom);
        int found = -1;
        for (var mark : components.components()) {
            int w = mark.right - mark.left + 1, h = mark.bottom - mark.top + 1;
            float cx = (mark.left + mark.right) * .5f, cy = (mark.top + mark.bottom) * .5f;
            boolean stackedAbove = side < 0 && h > 1.28f * gap && h <= 2.55f * gap;
            if (stackedAbove) cy = mark.bottom - .48f * gap;
            if (w < .38f * gap || w > .99f * gap || h < .67f * gap || !stackedAbove && h > 1.28f * gap
                    || w > h * .94f || Math.abs(cx - x) > .28f * gap || (cy - y) * side < 1.25f * gap
                    || !ArtificialHarmonics.enclosedCounter(ink, width, height,
                            cx, cy, gap * .68f, gap * .17f)) continue;
            if (found >= 0) return -1;
            found = Math.round(cy);
        }
        return found;
    }
    private static boolean dark(byte[] ink, int width, int x, int y) {
        return (ink[y * width + x] & 255) < 165;
    }
}
