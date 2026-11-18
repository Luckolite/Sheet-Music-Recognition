// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class ClosedTripletPredecessorTest {
    @Test
    public void closedSixGroupsRecoverOneClippedInnerStem() {
        var page = new Plate();
        var proof = page.prove();
        assertNotNull(proof);
        assertEquals(page.owners, proof.owners());
    }

    @Test
    public void genuineClosingRuleAndAllThreeNotesInEachGroupAreRequired() {
        var withoutRule = new Plate();
        withoutRule.fill(797, 801, 121, 188, (byte) 255);
        assertNull(withoutRule.prove());

        var separated = new Plate();
        separated.fill(178, 190, 136, 172, (byte) 255);
        assertNull(separated.prove());

        var incomplete = new Plate();
        incomplete.owners.remove(1);
        assertNull(incomplete.prove());
    }

    private static final class Plate {
        private static final int WIDTH = 930, HEIGHT = 380, LEFT = 130, RIGHT = 790;
        final byte[] gray = new byte[WIDTH * HEIGHT];
        final List<ScoreNoteEvent> notes = new ArrayList<>();
        final List<Integer> owners = new ArrayList<>();
        final List<MeasureRegion> geometry =
                List.of(region(39, 112), region(LEFT, RIGHT), region(808, 897));

        Plate() {
            Arrays.fill(gray, (byte) 255);
            for (int by = 122; by <= 186; by += 16) fill(30, 900, by, by + 1, (byte) 0);
            fill(120, 122, 122, 187, (byte) 0);
            fill(798, 800, 122, 187, (byte) 0);
            for (int group = 0; group < 6; group++) {
                boolean up = group % 2 == 0;
                int firstStem = 155 + group * 105 + (up ? 10 : -10);
                for (int column = firstStem; column <= firstStem + 70; column++) {
                    int axis = tip(up, column - firstStem);
                    fill(column, column, up ? axis : axis - 7, up ? axis + 7 : axis, (byte) 0);
                }
                for (int i = 0; i < 3; i++) {
                    int slot = group * 3 + i;
                    int headX = 155 + slot * 35;
                    int headY = up ? 226 - i * 9 : 115 + i * 7;
                    int stemX = headX + (up ? 10 : -10);
                    int cap = tip(up, i * 35);
                    fill(
                            stemX - 1,
                            stemX + 1,
                            Math.min(cap, headY),
                            Math.max(cap, headY),
                            (byte) 0);
                    head(headX, headY);
                    if (slot == 1) fill(stemX - 2, stemX + 2, headY - 17, headY - 15, (byte) 255);
                    var n =
                            new ScoreNoteEvent(
                                            1,
                                            (headX - LEFT) / (float) (RIGHT - LEFT),
                                            0,
                                            0,
                                            1,
                                            headY / (float) HEIGHT,
                                            false,
                                            0,
                                            slot == 2 ? 2 : 1,
                                            ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                                            0)
                                    .withStemDirection(slot == 1 ? 0 : up ? 1 : -1);
                    owners.add(slot);
                    notes.add(n);
                }
            }
        }

        WrappedTripletMeasure.Half prove() {
            return WrappedShaftContinuation.closedPredecessor(
                    gray, WIDTH, HEIGHT, geometry, 1, notes, owners);
        }

        private static int tip(boolean up, int x) {
            return up ? 161 - Math.round(x * .34f) : 182 + Math.round(x * .34f);
        }

        private static MeasureRegion region(int left, int right) {
            return new MeasureRegion(
                    left / (float) WIDTH, right / (float) WIDTH, 88f / HEIGHT, 219f / HEIGHT);
        }

        private void head(int x, int y) {
            for (int dy = -7; dy <= 7; dy++) {
                int across = Math.round(10 * (float) Math.sqrt(1 - dy * dy / 64f));
                fill(x - across, x + across, y + dy, y + dy, (byte) 0);
            }
        }

        void fill(int x0, int x1, int y0, int y1, byte tone) {
            for (int y = y0; y <= y1; y++)
                Arrays.fill(gray, y * WIDTH + x0, y * WIDTH + x1 + 1, tone);
        }
    }
}
