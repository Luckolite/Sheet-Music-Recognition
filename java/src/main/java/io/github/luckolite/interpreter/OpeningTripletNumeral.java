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
        var original = glyph(source, labels, width, height, left, top, right, bottom, gap, centreX, centreY);
        var corrected = glyph(normalized, labels, width, height, left, top, right, bottom, gap, centreX, centreY);
        return original != null && corrected != null
                && Math.abs(original.left - corrected.left) <= 1 && Math.abs(original.right - corrected.right) <= 1
                && Math.abs(original.top - corrected.top) <= 1 && Math.abs(original.bottom - corrected.bottom) <= 1;
    }

    private static RawNumeralComponents.Component glyph(
            byte[] gray, byte[] labels, int width, int height, int left, int top, int right, int bottom,
            float gap, float x, float y) {
        var components = RawNumeralComponents.capture(
                gray, width, height, left, top, right, bottom, () -> {}, () -> {});
        RawNumeralComponents.Component found = null;
        for (var c : components.components()) {
            float w = c.right - c.left + 1, h = c.bottom - c.top + 1;
            if (w < .55f * gap || w > 1.5f * gap || h < .8f * gap || h > 1.6f * gap
                    || c.left <= left || c.right >= right || c.top <= top || c.bottom >= bottom
                    || x < c.left || x > c.right || y < c.top || y > c.bottom
                    || c.size() < w * h * .28f || c.size() > w * h * .70f) continue;
            int symbols = 0;
            for (int i = 0; i < c.size(); i++)
                if (labels[c.pixel(i)] == OmrMeasurePostProcessor.SYMBOL) symbols++;
            if (symbols < c.size() * .55f) continue;
            if (found != null) return null;
            found = c;
        }
        return found;
    }
}
