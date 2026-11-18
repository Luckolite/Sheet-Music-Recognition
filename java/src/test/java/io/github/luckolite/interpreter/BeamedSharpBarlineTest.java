// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class BeamedSharpBarlineTest {
    @Test
    public void printedSharpBelongsToFollowingChordUnderContinuousRails() {
        var plate = new Plate();
        assertEquals(List.of(plate.joined), plate.resolve());
    }

    @Test
    public void sharpRequiresSourceInkAndTwoAttachedRails() {
        var missingCrossbars = new Plate();
        for (int y : new int[] {165, 181})
            missingCrossbars.fill(120, 142, y - 1, y + 3, (byte) 255);
        assertEquals(missingCrossbars.regions, missingCrossbars.resolve());

        var brokenBeam = new Plate();
        brokenBeam.fill(121, 127, 101, 136, (byte) 255);
        assertEquals(brokenBeam.regions, brokenBeam.resolve());

        var divider = new Plate();
        divider.fill(124, 125, 129, 195, (byte) 0);
        assertEquals(divider.regions, divider.resolve());
    }

    private static final class Plate {
        private static final int WIDTH = 310, HEIGHT = 295;

        final List<MeasureRegion> regions = List.of(region(12, 115), region(133, 301));
        final MeasureRegion joined = region(12, 301);
        final byte[] gray = new byte[WIDTH * HEIGHT];
        final byte[] labels = new byte[WIDTH * HEIGHT];

        Plate() {
            Arrays.fill(gray, (byte) 255);
            for (int y = 130; y <= 194; y += 16)
                ink(12, 301, y, y + 1, OmrMeasurePostProcessor.STAFF);
            for (int x = 98; x <= 165; x++) {
                int beam = tip(x);
                ink(x, x, beam, beam + 8, OmrMeasurePostProcessor.SYMBOL);
                ink(x, x, beam + 13, beam + 21, OmrMeasurePostProcessor.SYMBOL);
            }
            for (int x : new int[] {90, 153}) {
                int lower = x < 100 ? 193 : 185;
                int stemX = x + 9;
                ink(stemX, stemX + 2, tip(stemX), lower, OmrMeasurePostProcessor.STEM_OR_REST);
                head(x, lower - 16);
                head(x, lower);
            }
            byte sharp = OmrMeasurePostProcessor.CLEF_OR_KEY;
            ink(124, 126, 151, 195, sharp);
            ink(135, 137, 145, 190, sharp);
            ink(123, 140, 164, 168, sharp);
            ink(123, 140, 180, 184, sharp);
        }

        private static MeasureRegion region(int left, int right) {
            return new MeasureRegion(
                    (float) left / WIDTH, (float) right / WIDTH, 96f / HEIGHT, 228f / HEIGHT);
        }

        private static int tip(int x) {
            return 109 - Math.round((x - 98) * .12f);
        }

        List<MeasureRegion> resolve() {
            return BeamedFlatBarline.join(regions, labels, gray, WIDTH, HEIGHT);
        }

        private void head(int x, int y) {
            for (int dy = -8; dy <= 8; dy++) {
                int radius = Math.round(10f * (float) Math.sqrt(1 - dy * dy / 81f));
                ink(x - radius, x + radius, y + dy, y + dy, OmrMeasurePostProcessor.NOTEHEAD);
            }
        }

        private void ink(int left, int right, int top, int bottom, byte label) {
            fill(left, right, top, bottom, (byte) 0);
            for (int y = top; y <= bottom; y++)
                Arrays.fill(labels, y * WIDTH + left, y * WIDTH + right + 1, label);
        }

        void fill(int left, int right, int top, int bottom, byte tone) {
            for (int y = top; y <= bottom; y++)
                Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, tone);
        }
    }
}
