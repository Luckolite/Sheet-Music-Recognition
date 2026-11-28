// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Original synthetic anti-aliased shafts and neighboring printed rules. */
public class AntialiasedStemBarlineTest {
    @Test
    public void connectedDarkCoreOwnsPaleFringe() throws Exception {
        Page page = new Page();
        page.stroke(300, 80, 136, 165, OmrMeasurePostProcessor.STEM_OR_REST);
        page.stroke(301, 80, 136, 135, OmrMeasurePostProcessor.STEM_OR_REST);
        page.noteShaft(302, 72);
        assertEquals(List.of(50, 165, 450), page.boundaries());
    }

    @Test
    public void separatedCoreCannotClaimPrintedRule() throws Exception {
        Page page = new Page();
        page.stroke(300, 80, 136, 0, OmrMeasurePostProcessor.STEM_OR_REST);
        page.noteShaft(302, 72);
        assertEquals(List.of(50, 165, 299, 450), page.boundaries());
    }

    private static final class Page {
        final int w = 500, h = 200;
        final int[] rows = {80, 94, 108, 122, 136};
        final byte[] gray = new byte[w * h], labels = new byte[w * h];

        Page() {
            Arrays.fill(gray, (byte) 255);
            for (int y : rows) {
                for (int x = 50; x <= 450; x++) pixel(x, y, 40, OmrMeasurePostProcessor.STAFF);
            }
            stroke(165, 80, 136, 0, OmrMeasurePostProcessor.STEM_OR_REST);
        }

        void pixel(int x, int y, int grayTone, byte label) {
            gray[y * w + x] = (byte) grayTone;
            labels[y * w + x] = label;
        }

        void stroke(int x, int top, int bottom, int grayTone, byte label) {
            for (int y = top; y <= bottom; y++) pixel(x, y, grayTone, label);
        }

        void noteShaft(int x, int headY) {
            stroke(x, headY + 1, 134, 0, OmrMeasurePostProcessor.STEM_OR_REST);
            int cx = x + 10;
            for (int y = headY - 6; y <= headY + 8; y++) {
                for (int xx = x; xx <= x + 20; xx++) {
                    double u = (xx - cx) / 11.0, v = (y - headY + .3 * (xx - cx)) / 6.0;
                    if (u * u + v * v <= 1) pixel(xx, y, 37, OmrMeasurePostProcessor.NOTEHEAD);
                }
            }
            for (int xx = x - 34; xx <= x; xx++) {
                for (int y : new int[] {121, 132}) stroke(xx, y, y + 3, 10, (byte) 5);
            }
        }

        @SuppressWarnings("unchecked")
        List<Integer> boundaries() throws Exception {
            Method method =
                    OmrMeasurePostProcessor.class.getDeclaredMethod(
                            "findBoundaries",
                            byte[].class,
                            byte[].class,
                            int.class,
                            int.class,
                            int[].class,
                            float.class,
                            int.class,
                            int.class,
                            float.class);
            method.setAccessible(true);
            return (List<Integer>) method.invoke(null, labels, gray, w, h, rows, 14f, 50, 450, 0f);
        }
    }
}
