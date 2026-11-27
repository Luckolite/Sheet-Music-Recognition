// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class BeamJoinedEighthRestTest {
    private static final int WIDTH = 384, HEIGHT = 220, GAP = 14, BEAM = 124;
    private static final MeasureRegion BAR = new MeasureRegion(0, 1, 0, 1);

    @Test
    public void findsTheSingleBulbAndDiagonalOnAnOwnedBeam() {
        Scene page = new Scene();
        var results = page.detect(List.of()).rests();
        assertEquals(results.toString(), 1, results.size());
        assertEquals(.5, results.get(0).durationBeats(), 0);
        assertEquals(ScoreRestEvent.Kind.LITERAL, results.get(0).kind());
    }

    @Test
    public void breakInTheBeamOrInTheRestTailRejectsTheFallback() {
        Scene gap = new Scene();
        gap.erase(220, BEAM - 1, 232, BEAM + 6);
        assertTrue(gap.detect(List.of()).rests().isEmpty());
        Scene detached = new Scene();
        detached.erase(191, BEAM - 3, 219, BEAM - 1);
        assertTrue(detached.detect(List.of()).rests().isEmpty());
    }

    @Test
    public void doesNotDuplicateAnAlreadyRecognizedRest() {
        Scene page = new Scene();
        var rest = new ScoreRestEvent(0, 206f / WIDTH, 111f / HEIGHT, 24f / HEIGHT, 0, 1, .5);
        var results = page.detect(List.of(rest)).rests();
        assertEquals(1, results.size());
        assertSame(rest, results.get(0));
    }

    private static final class Scene {
        final byte[] gray = new byte[WIDTH * HEIGHT];

        Scene() {
            Arrays.fill(gray, (byte) 255);
            for (int y = 96; y <= 152; y += GAP) ink(15, y, 366, y);
            for (int x : new int[]{166, 250}) {
                ellipse(x, 82, 9, 7);
                ink(x - 8, 82, x - 7, BEAM);
            }
            ink(158, BEAM, 243, BEAM + 5);
            ellipse(202, 104, 5, 5);
            for (int y = 101; y < BEAM; y++) {
                int x = Math.round(212 - (y - 101) * .30f);
                ink(x - 1, y, x + 1, y);
            }
            ink(205, 105, 210, 107);
        }

        private void ellipse(int cx, int cy, int rx, int ry) {
            for (int y = cy - ry; y <= cy + ry; y++)
                for (int x = cx - rx; x <= cx + rx; x++)
                    if (Math.pow((x - cx) / (double) rx, 2) + Math.pow((y - cy) / (double) ry, 2) <= 1)
                        gray[y * WIDTH + x] = 0;
        }

        private void ink(int x1, int y1, int x2, int y2) {
            for (int y = y1; y <= y2; y++) Arrays.fill(gray, y * WIDTH + x1, y * WIDTH + x2 + 1, (byte) 0);
        }

        void erase(int x1, int y1, int x2, int y2) {
            for (int y = y1; y <= y2; y++) Arrays.fill(gray, y * WIDTH + x1, y * WIDTH + x2 + 1, (byte) 255);
        }

        SixteenthRestDetector.Detection detect(List<ScoreRestEvent> previous) {
            byte[] copy = gray.clone();
            var result = RawBeamedTwoBulbRests.add(new SixteenthRestDetector.Detection(previous, List.of()),
                    gray, WIDTH, HEIGHT, List.of(BAR), List.of(new SixteenthRestDetector.Staff(96,152,GAP,0,1)),
                    List.of(note(166),note(250)));
            assertArrayEquals(copy, gray);
            return result;
        }

        private ScoreNoteEvent note(int x) {
            return new ScoreNoteEvent(0, x / (float) WIDTH, 1, 0, 1, 82f / HEIGHT, false)
                    .withBeamAndStem(1, 0);
        }
    }
}
