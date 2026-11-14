// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** In-place ARGB equivalent of meter-crop cleanup, without per-pixel native bitmap calls. */
public final class MeterCropRaster {
    private MeterCropRaster() {}

    static int[] copyGray(byte[] gray, int width, int left, int top, int cropWidth, int cropHeight) {
        int[] pixels = new int[cropWidth * cropHeight];
        for (int row = top * width + left, dest = 0; dest < pixels.length; row += width) {
            for (int x = 0; x < cropWidth; x++)
                pixels[dest++] = 0xff000000 | (gray[row + x] & 255) * 0x10101;
        }
        return pixels;
    }

    public static void prepare(
            int[] pixels,
            int width,
            int height,
            int sourceTop,
            float firstLine,
            float gap,
            boolean maskInk) {
        if (width < 1 || height < 1 || (long) width * height != pixels.length)
            throw new IllegalArgumentException("raster size");
        if (maskInk)
            for (int i = 0; i < pixels.length; i++)
                pixels[i] = isDark(pixels[i]) ? 0xff000000 : 0xffffffff;
        int radius = Math.max(1, Math.round(gap * .06f));
        for (int line = 0; line < 5; line++) {
            int y = Math.round(firstLine + line * gap) - sourceTop;
            if (y + radius < 0 || y - radius >= height) continue;
            int aboveTop = Math.max(0, y - radius - 2);
            int aboveBottom = Math.max(0, y - radius - 1);
            int belowTop = Math.min(height - 1, y + radius + 1);
            int belowBottom = Math.min(height - 1, y + radius + 2);
            int eraseStart = Math.max(0, y - radius) * width;
            int eraseEnd = Math.min(height - 1, y + radius) * width;
            // Clipped probes may read a row being rewritten. Keep their column order.
            boolean independent = aboveBottom * width < eraseStart && belowTop * width > eraseEnd;
            int above = 0, below = 0;
            int aboveStart = aboveTop * width, aboveEnd = aboveBottom * width;
            int belowStart = belowTop * width, belowEnd = belowBottom * width;
            if (independent) {
                above = darkAtColumn(pixels, width, 0, aboveStart, aboveEnd) ? 1 : 0;
                below = darkAtColumn(pixels, width, 0, belowStart, belowEnd) ? 1 : 0;
            }
            for (int x = 0; x < width; x++) {
                boolean bridge;
                if (independent) {
                    int incoming = x + 1;
                    if (incoming < width) {
                        above = above << 1 | (darkAtColumn(pixels, width, incoming, aboveStart, aboveEnd) ? 1 : 0);
                        below = below << 1 | (darkAtColumn(pixels, width, incoming, belowStart, belowEnd) ? 1 : 0);
                    } else {
                        above <<= 1;
                        below <<= 1;
                    }
                    above &= 7;
                    below &= 7;
                    bridge = above != 0 && below != 0;
                } else {
                    bridge = darkColumn(pixels, width, x, aboveTop, aboveBottom)
                            && darkColumn(pixels, width, x, belowTop, belowBottom);
                }
                int color = bridge ? 0xff000000 : 0xffffffff;
                for (int at = eraseStart + x; at <= eraseEnd + x; at += width)
                    pixels[at] = color;
            }
        }
        float leftEdge = width * .15f, rightEdge = width * .90f;
        int requiredDark = (int) (height * .85f) + 1;
        int allowedLight = height - requiredDark;
        for (int x = 0; x < width; x++) {
            if (x >= leftEdge && x < rightEdge) continue;
            int dark = 0, light = 0;
            for (int at = x; at < pixels.length; at += width) {
                if (!isDark(pixels[at])) {
                    if (++light > allowedLight) break;
                    continue;
                }
                if (++dark == requiredDark) {
                    for (int erase = x; erase < pixels.length; erase += width)
                        pixels[erase] = 0xffffffff;
                    break;
                }
            }
        }
    }

    private static boolean darkColumn(int[] pixels, int width, int centerX, int top, int bottom) {
        int firstX = Math.max(0, centerX - 1), lastX = Math.min(width - 1, centerX + 1);
        int firstRow = top * width, lastRow = bottom * width;
        for (int x = firstX; x <= lastX; x++)
            for (int at = firstRow + x; at <= lastRow + x; at += width)
                if (isDark(pixels[at])) return true;
        return false;
    }

    private static boolean darkAtColumn(int[] pixels, int width, int x, int firstRow, int lastRow) {
        for (int at = firstRow + x; at <= lastRow + x; at += width)
            if (isDark(pixels[at])) return true;
        return false;
    }

    private static boolean isDark(int color) {
        return weightedLuminance(color) < 13500;
    }

    private static int weightedLuminance(int color) {
        return ((color >>> 16) & 255) * 30 + ((color >>> 8) & 255) * 59 + (color & 255) * 11;
    }

}
