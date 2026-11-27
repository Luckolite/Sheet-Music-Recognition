// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;

/** One immutable raw-core graph per source window. No frame, glyph or musical authority is created. */
final class RawNumeralComponents {
    static final class Component {
        private final int[] pixels;
        final int left, top, right, bottom;

        Component(int[] queue, int size, int left, int top, int right, int bottom) {
            pixels = Arrays.copyOf(queue, size);
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        int size() {
            return pixels.length;
        }

        int pixel(int i) {
            return pixels[i];
        }
    }

    private final List<Component> components;
    final int left, top, right, bottom;

    private RawNumeralComponents(
            List<Component> components, int left, int top, int right, int bottom) {
        this.components = List.copyOf(components);
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    List<Component> components() {
        return components;
    }

    static RawNumeralComponents capture(
            byte[] gray,
            int width,
            int height,
            int left,
            int top,
            int right,
            int bottom,
            ObservedFringeContinuation.Charge charge,
            Runnable newComponent) {
        return capture(gray, width, height, left, top, right, bottom, 165, charge, newComponent);
    }

    static RawNumeralComponents capture(
            byte[] gray,
            int width,
            int height,
            int left,
            int top,
            int right,
            int bottom,
            int threshold,
            ObservedFringeContinuation.Charge charge,
            Runnable newComponent) {
        return capture(gray, width, height, left, top, right, bottom,
                threshold, charge, newComponent, 16_384);
    }

    static RawNumeralComponents captureLongWindow(
            byte[] gray, int width, int height, int left, int top, int right, int bottom) {
        return captureLongWindow(gray, width, height, left, top, right, bottom, 154);
    }

    static RawNumeralComponents captureLongWindow(
            byte[] gray, int width, int height, int left, int top, int right, int bottom,
            int threshold) {
        return capture(gray, width, height, left, top, right, bottom, threshold,
                () -> {}, () -> {}, 650_000);
    }

    private static RawNumeralComponents capture(
            byte[] gray, int width, int height, int left, int top, int right, int bottom,
            int threshold, ObservedFringeContinuation.Charge charge, Runnable newComponent,
            int maxCells) {
        if (left > right || top > bottom)
            throw new IllegalArgumentException("raw core graph bounds");
        int w = right - left + 1, h = bottom - top + 1;
        if (gray == null
                || width < 1
                || height < 1
                || gray.length != (long) width * height
                || left < 0
                || top < 0
                || right >= width
                || bottom >= height
                || w < 1
                || h < 1
                || (long) w * h > maxCells)
            throw new IllegalArgumentException("raw core graph bounds");
        int cells = w * h;
        BitSet seen = null;
        int[] queue = null;
        var components = new ArrayList<Component>();
        for (int y = top, seed = 0; y <= bottom; y++) {
            int position = y * width + left;
            for (int x = left; x <= right; x++, position++, seed++) {
                charge.tick();
                if ((gray[position] & 255) > threshold || seen != null && seen.get(seed)) continue;
                newComponent.run();
                if (seen == null) {
                    seen = new BitSet(cells);
                    queue = new int[Math.min(64, cells)];
                }
                int take = 0, size = 1, l = width, r = -1, t = height, b = -1;
                queue[0] = position;
                seen.set(seed);
                while (take < size) {
                    charge.tick();
                    int pixel = queue[take++], yy = pixel / width, xx = pixel - yy * width;
                    l = Math.min(l, xx);
                    r = Math.max(r, xx);
                    t = Math.min(t, yy);
                    b = Math.max(b, yy);
                    int minX = Math.max(left, xx - 1), maxX = Math.min(right, xx + 1);
                    int lastY = Math.min(bottom, yy + 1);
                    for (int ny = Math.max(top, yy - 1); ny <= lastY; ny++) {
                        int next = (ny - top) * w + minX - left;
                        int neighbor = ny * width + minX;
                        for (int nx = minX; nx <= maxX; nx++, next++, neighbor++) {
                            charge.tick();
                            if (!seen.get(next) && (gray[neighbor] & 255) <= threshold) {
                                seen.set(next);
                                if (size == queue.length) {
                                    int capacity = size >= Math.min(8192, cells / 4) ? cells : size * 2;
                                    queue = Arrays.copyOf(queue, capacity);
                                }
                                queue[size++] = neighbor;
                            }
                        }
                    }
                }
                // Keep the coordinate-freezing budget even though the queue already holds raw coordinates.
                for (int i = 0; i < size; i++) charge.tick();
                components.add(new Component(queue, size, l, t, r, b));
            }
        }
        return new RawNumeralComponents(components, left, top, right, bottom);
    }
}
