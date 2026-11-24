// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.List;

/** Confirms the isolated numeral below a source-proved three-note group. */
final class OpeningTripletNumeral {
    private OpeningTripletNumeral() {}

    static boolean present(
            List<PlayingTechniqueDetector.Word> words,
            byte[] source, byte[] normalized, byte[] labels, int width, int height,
            float middleX, float middleY, float gap) {
        if (source == null || normalized == null || labels == null
                || source.length != (long) width * height || normalized.length != source.length
                || labels.length != source.length || !(gap >= 10 && gap <= 27)) return false;
        int left = Math.round(middleX - .9f * gap), right = Math.round(middleX + .9f * gap);
        int top = Math.round(middleY + .5f * gap), bottom = Math.round(middleY + 2.45f * gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return false;
        var cue = words.stream()
                .filter(w -> "3".equals(w.text().strip()))
                .filter(w -> (w.right() - w.left()) * width <= 3.8f * gap
                        && (w.bottom() - w.top()) * height <= 4.2f * gap)
                .filter(w -> {
                    float x = (w.left() + w.right()) * width * .5f;
                    float y = (w.top() + w.bottom()) * height * .5f;
                    return x > left + 1 && x < right - 1 && y > top + 1 && y < bottom - 1;
                }).toList();
        if (cue.size() != 1) return false;
        var word = cue.get(0);
        float centreX = (word.left() + word.right()) * width * .5f;
        float centreY = (word.top() + word.bottom()) * height * .5f;
        if (matchingGlyphs(source, normalized, labels, width, height, left, top, right, bottom,
                gap, centreX, centreY, false)) return true;

        // The original window can crop a tall numeral beside a curved triplet mark.
        int tallLeft = Math.round(middleX - 1.18f * gap);
        int tallRight = Math.round(middleX + 1.18f * gap);
        int tallBottom = Math.round(middleY + 3.10f * gap);
        return tallLeft >= 0 && tallRight < width && tallBottom < height
                && matchingGlyphs(source, normalized, labels, width, height, tallLeft, top, tallRight, tallBottom,
                        gap, centreX, centreY, true);
    }

    /** A lower staccato can share a narrow print bridge with the numeral beneath it. */
    static boolean joinedLowerStaccato(byte[] ink, byte[] labels, int width, int height,
            float x, float y, float gap) {
        return joinedLowerThree(ink, labels, width, height, x, y, gap) != null;
    }

    private static RawNumeralComponents.Component joinedLowerThree(byte[] ink, byte[] labels,
            int width, int height, float x, float y, float gap) {
        int left = Math.round(x - 1.15f * gap), right = Math.round(x + 1.15f * gap);
        int top = Math.round(y + .66f * gap), bottom = Math.round(y + 2.74f * gap);
        if (gap < 10 || gap > 25 || left < 0 || right >= width || top < 0 || bottom >= height)
            return null;
        var components = RawNumeralComponents.captureLongWindow(ink, width, height, left, top, right, bottom);
        RawNumeralComponents.Component matched = null;
        for (var c : components.components()) {
            if (c.left <= left || c.right >= right || c.bottom >= bottom || c.top <= top
                    || c.top - y < .76f * gap || c.top - y > 1.13f * gap
                    || c.bottom - c.top + 1 < 1.45f * gap || c.bottom - c.top + 1 > 1.9f * gap
                    || c.right - c.left + 1 < .8f * gap || c.right - c.left + 1 > 1.4f * gap) continue;
            int[] rowWidths = new int[c.bottom - c.top + 1];
            for (int i = 0; i < c.size(); i++) rowWidths[c.pixel(i) / width - c.top]++;
            int split = -1;
            for (int row = Math.round(.24f * gap); row <= Math.round(.40f * gap); row++) {
                if (rowWidths[row] > 0 && rowWidths[row] <= .27f * gap
                        && rowWidths[row + 3] >= .53f * gap) { split = c.top + row; break; }
            }
            if (split < 0) continue;
            var dot = new java.util.ArrayList<Integer>();
            var numeral = new java.util.ArrayList<Integer>();
            for (int i = 0; i < c.size(); i++) {
                int pixel = c.pixel(i);
                (pixel / width < split ? dot : numeral).add(pixel);
            }
            int dotLeft = dot.stream().mapToInt(v -> v % width).min().orElse(left);
            int dotRight = dot.stream().mapToInt(v -> v % width).max().orElse(right);
            int dotWidth = dotRight - dotLeft + 1, dotHeight = split - c.top;
            if (dotWidth < .23f * gap || dotWidth > .49f * gap
                    || Math.abs(x - (dotLeft + dotRight) * .5f) > .18f * gap
                    || dot.size() < .65f * dotWidth * dotHeight) continue;
            if (numeral.stream().filter(i -> labels[i] == OmrMeasurePostProcessor.SYMBOL).count()
                    < .45f * numeral.size()) continue;
            int[] pixels = numeral.stream().mapToInt(i -> i).toArray();
            if (TupletNumeralBeamBand.detachedThree(pixels, width, c.left, split, c.right, c.bottom, gap))
                if (matched != null) return null;
                else matched = c;
        }
        return matched;
    }

    private record Bounds(int left, int top, int right, int bottom) {
        boolean agrees(Bounds other) {
            return other != null && Math.abs(left - other.left) <= 1 && Math.abs(top - other.top) <= 1
                    && Math.abs(right - other.right) <= 1 && Math.abs(bottom - other.bottom) <= 1;
        }
    }

    /** Upgrade confirmed numbers only after the detached under-curve is proved in both planes. */
    static ScorePageInterpretation projectCurvedLowerMarks(ScorePageInterpretation score, byte[] labels,
            byte[] raw, byte[] gray, int width, int height) {
        long area = (long) width * height;
        if (raw == null || gray == null || labels == null || raw.length != area || gray.length != area
                || labels.length != area) return score;
        var source = score.beamSource();
        if (source.groups().stream().noneMatch(g -> g.tupletMark() == ScoreBeamSource.TupletMark.NUMBERED))
            return score;
        var numbered = new java.util.HashSet<String>();
        for (var resolved : source.resolve().groups()) {
            var mark = resolved.source();
            if (mark.tupletMark() != ScoreBeamSource.TupletMark.NUMBERED || !mark.hooks().isEmpty()
                    || resolved.columns().stream().anyMatch(c ->
                            !c.usable() || c.owners().size() != 1 || c.source().members().size() != 1)) continue;
            var owners = resolved.columns().stream().map(c -> score.notes().get(c.owners().get(0))).toList();
            var mid = owners.get(1);
            if ((mid.articulations() & NoteArticulation.STACCATO) == 0 || owners.stream().anyMatch(n ->
                    n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffCount() != 1
                            || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                            || n.measureIndex() != mid.measureIndex() || n.stemDirection() != 1
                            || n.beamCount() != 1 || n.augmentationDots() != 0 || n.tupletDivisor() != 3
                            || n.tupletNormalNotes() != 2 || n.crossStaffBeam())) continue;
            var bar = score.measures().get(mid.measureIndex());
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            float x = (bar.left() + mid.positionInMeasure() * (bar.right() - bar.left())) * width;
            float y = mid.pageY() * height;
            var a = joinedLowerThree(raw, labels, width, height, x, y, gap);
            var b = joinedLowerThree(gray, labels, width, height, x, y, gap);
            if (a == null || b == null || !bounds(a).agrees(bounds(b))) continue;
            var below = underCurve(raw, width, height, a, gap);
            if (below != null && below.agrees(underCurve(gray, width, height, b, gap))) numbered.add(mark.id());
        }
        if (numbered.isEmpty()) return score;
        var groups = source.groups().stream().map(g -> numbered.contains(g.id())
                ? new ScoreBeamSource.Group(g.id(), g.columns(), g.bridges(), g.hooks(),
                      ScoreBeamSource.TupletMark.NUMBERED_CURVED_BELOW)
                : g).toList();
        return score.withBeamSource(new ScoreBeamSource(groups, source.detachedColumns(), source.noteOrigins()));
    }

    private static Bounds bounds(RawNumeralComponents.Component c) {
        return new Bounds(c.left, c.top, c.right, c.bottom);
    }

    private static Bounds underCurve(byte[] ink, int width, int height, RawNumeralComponents.Component number,
            float gap) {
        int left = number.left - Math.round(.75f * gap), right = number.right + Math.round(.65f * gap);
        int top = number.bottom + 1, bottom = number.bottom + Math.round(1.04f * gap);
        if (left < 0 || right >= width || bottom >= height) return null;
        var components = RawNumeralComponents.captureLongWindow(ink, width, height, left, top, right, bottom, 165);
        Bounds found = null;
        for (var c : components.components()) {
            int span = c.right - c.left + 1, rise = c.bottom - c.top + 1;
            if (c.left <= left || c.right >= right || c.top <= top || c.bottom >= bottom
                    || c.top - number.bottom > .35f * gap || span < 1.22f * gap || span > 1.80f * gap
                    || rise < .28f * gap || rise > .61f * gap || c.left > number.left
                    || c.right < (number.left + number.right) * .5f
                    || Math.abs((c.left + c.right - number.left - number.right) * .5f) > .48f * gap
                    || !hasCurvedStroke(c, width, gap)) continue;
            if (found != null) return null;
            found = bounds(c);
        }
        return found;
    }

    private static boolean hasCurvedStroke(RawNumeralComponents.Component arc, int stride, float gap) {
        int width = arc.right - arc.left + 1;
        int[] low = new int[width], high = new int[width], count = new int[width];
        java.util.Arrays.fill(low, Integer.MAX_VALUE);
        for (int i = 0; i < arc.size(); i++) {
            int pixel = arc.pixel(i);
            int x = pixel % stride - arc.left, y = pixel / stride;
            low[x] = Math.min(low[x], y);
            high[x] = Math.max(high[x], y);
            count[x]++;
        }
        for (int x = 0; x < width; x++)
            if (count[x] < 1 || count[x] != high[x] - low[x] + 1
                    || count[x] > Math.round(gap * .26f)) return false;
        float first = (low[0] + high[0]) * .5f, last = (low[width - 1] + high[width - 1]) * .5f;
        if (Math.abs(last - first) > .22f * gap) return false;
        int middle = width / 2;
        float bend = (low[middle] + high[middle] - first - last) * .5f;
        if (bend < .20f * gap || bend > .49f * gap) return false;
        float squared = 0;
        for (int i = 0; i < width; i++) {
            float p = i / (float) (width - 1);
            float expected = first + p * (last - first) + 4 * bend * p * (1 - p);
            float residual = (low[i] + high[i]) * .5f - expected;
            squared += residual * residual;
        }
        return squared / width <= gap * gap * .007f;
    }

    private static boolean matchingGlyphs(
            byte[] source, byte[] normalized, byte[] labels, int width, int height,
            int left, int top, int right, int bottom, float gap, float x, float y, boolean tall) {
        var original = glyph(source, labels, width, height, left, top, right, bottom, gap, x, y, tall);
        var corrected = glyph(normalized, labels, width, height, left, top, right, bottom, gap, x, y, tall);
        return original != null && corrected != null
                && Math.abs(original.left - corrected.left) <= 1 && Math.abs(original.right - corrected.right) <= 1
                && Math.abs(original.top - corrected.top) <= 1 && Math.abs(original.bottom - corrected.bottom) <= 1;
    }

    private static RawNumeralComponents.Component glyph(
            byte[] gray, byte[] labels, int width, int height, int left, int top, int right, int bottom,
            float gap, float x, float y, boolean tall) {
        var components = RawNumeralComponents.capture(
                gray, width, height, left, top, right, bottom, () -> {}, () -> {});
        RawNumeralComponents.Component found = null;
        for (var c : components.components()) {
            float w = c.right - c.left + 1, h = c.bottom - c.top + 1;
            if (w < .55f * gap || w > 1.5f * gap || h < .8f * gap || h > (tall ? 1.85f : 1.6f) * gap
                    || c.left <= left || c.right >= right || c.top <= top || c.bottom >= bottom
                    || x < c.left || x > c.right || y < c.top || y > c.bottom
                    || c.size() < w * h * .28f || c.size() > w * h * .70f) continue;
            int symbols = 0;
            for (int i = 0; i < c.size(); i++)
                if (labels[c.pixel(i)] == OmrMeasurePostProcessor.SYMBOL) symbols++;
            if (symbols < c.size() * .55f) continue;
            if (tall) {
                int[] pixels = java.util.stream.IntStream.range(0, c.size()).map(c::pixel).toArray();
                if (!TupletNumeralBeamBand.detachedThree(
                        pixels, width, c.left, c.top, c.right, c.bottom, gap)) continue;
            }
            if (found != null) return null;
            found = c;
        }
        return found;
    }
}
