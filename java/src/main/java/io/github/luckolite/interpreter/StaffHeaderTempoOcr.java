// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Finds small direction text at printed staff scale. Results still require optical tempo proof. */
final class StaffHeaderTempoOcr {
    @FunctionalInterface
    interface Reader {
        OcrText read(byte[] gray, int width, int height) throws Exception;
    }

    private StaffHeaderTempoOcr() {}

    static List<PlayingTechniqueDetector.Word> words(
            byte[] gray, int width, int height, Reader reader) throws Exception {
        if (gray == null
                || width <= 0
                || height <= 0
                || (long) width * height != gray.length
                || reader == null) return List.of();
        var result = new ArrayList<PlayingTechniqueDetector.Word>();
        for (var staff : RawStaffLineDetector.detect(gray, width, height)) {
            float gap = staff.gap();
            int top = Math.max(0, Math.round(staff.top() - 8 * gap));
            int bottom = Math.min(height, Math.round(staff.top() - .3f * gap));
            if (bottom <= top) continue;
            int window = Math.min(width, Math.max(1, Math.round(32 * gap)));
            int step = Math.max(1, window / 2);
            int ch = bottom - top;
            int previousRight = 0;
            int inkColumn = -1;
            for (int left = 0; left < width; left += step) {
                int cw = Math.min(window, width - left);
                if (cw < 8 * gap) break;
                int right = left + cw;
                if (inkColumn < left) {
                    // A blank window proves its overlap blank. Otherwise reuse
                    // the previous witness as long as it is still inside the crop.
                    int scanLeft = inkColumn < 0 ? Math.max(left, previousRight) : left;
                    inkColumn = firstInkColumn(gray, width, top, bottom, scanLeft, right);
                }
                previousRight = right;
                if (inkColumn < 0) continue;
                byte[] crop = new byte[cw * ch];
                for (int source = top * width + left, dest = 0; dest < crop.length;
                        source += width, dest += cw) {
                    System.arraycopy(gray, source, crop, dest, cw);
                }
                OcrText text = reader.read(crop, cw, ch);
                if (text == null) continue;
                for (var block : text.blocks())
                    for (var line : block.lines())
                        for (var element : line.elements()) {
                            var box = element.box();
                            if (box == null
                                    || box.left < 0
                                    || box.top < 0
                                    || box.right > cw
                                    || box.bottom > ch
                                    || box.right <= box.left
                                    || box.bottom <= box.top) continue;
                            result.add(
                                    new PlayingTechniqueDetector.Word(
                                            element.text(),
                                            (left + box.left) / (float) width,
                                            (top + box.top) / (float) height,
                                            (left + box.right) / (float) width,
                                            (top + box.bottom) / (float) height));
                        }
            }
        }
        return List.copyOf(result);
    }

    private static int firstInkColumn(
            byte[] gray, int width, int top, int bottom, int left, int right) {
        for (int row = top * width, end = bottom * width; row < end; row += width) {
            int limit = row + right;
            for (int at = row + left; at < limit; at++) {
                if ((gray[at] & 255) < 240) return at - row;
            }
        }
        return -1;
    }
}
