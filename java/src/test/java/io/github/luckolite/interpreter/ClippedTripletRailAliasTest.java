// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class ClippedTripletRailAliasTest {
    private static final int W = 140, H = 140, GAP = 16;

    @Test
    public void completeMaskInsideAngledRailHasNoIndependentBody() throws Exception {
        var page = new Fixture();
        assertTrue(page.belongs(page.gray.clone(), page.labels));
    }

    @Test
    public void everyMaskMemberMustBelongToBothRetainedPlanes() throws Exception {
        var page = new Fixture();
        var raw = page.gray.clone();
        raw[page.oneMember] = (byte) 255;
        assertFalse(page.belongs(raw, page.labels));
        page.gray[page.oneMember] = (byte) 255;
        assertFalse(page.belongs(page.original, page.labels));
    }

    @Test
    public void omittedMemberCannotProveCompleteMask() throws Exception {
        var page = new Fixture();
        var labels = page.labels.clone();
        labels[page.oneMember] = 0;
        assertFalse(page.belongs(page.original, labels));
    }

    private static final class Fixture {
        final byte[] gray = new byte[W * H];
        final byte[] labels = new byte[W * H];
        final byte[] original;
        final PrintedBeamShafts.Shaft[] shafts = {
            shaft(30, 100), shaft(65, 88), shaft(100, 75)
        };
        final Object mask;
        final Class<?> component;
        final int oneMember;

        Fixture() throws Exception {
            Arrays.fill(gray, (byte) 255);
            int sumX = 0, sumY = 0, minY = H, maxY = 0, area = 0;
            int first = -1;
            for (int x = 40; x < 49; x++) {
                int center = Math.round(100 - (x - 30) * 25 / 70f);
                for (int y = center + 2; y <= center + 4; y++) {
                    int pixel = y * W + x;
                    if (first < 0) first = pixel;
                    labels[pixel] = 2;
                    gray[pixel] = 0;
                    sumX += x;
                    sumY += y;
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                    area++;
                }
            }
            oneMember = first;
            original = gray.clone();
            component = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
            var ctor = component.getDeclaredConstructor(
                    int.class, int.class, int.class, int.class, int.class, float.class, float.class);
            ctor.setAccessible(true);
            mask = ctor.newInstance(area, 40, 48, minY, maxY, sumX / (float) area, sumY / (float) area);
        }

        private static PrintedBeamShafts.Shaft shaft(int x, int y) {
            return new PrintedBeamShafts.Shaft(x, x, y + 30, y, -1, List.of());
        }

        boolean belongs(byte[] raw, byte[] semantic) throws Exception {
            var proof = OmrScoreInterpreter.class.getDeclaredMethod(
                    "maskInsideOpeningRail", byte[].class, byte[].class, byte[].class,
                    int.class, int.class, component, PrintedBeamShafts.Shaft[].class, float.class);
            proof.setAccessible(true);
            return (boolean) proof.invoke(null, semantic, gray, raw, W, H, mask, shafts, (float) GAP);
        }
    }
}
