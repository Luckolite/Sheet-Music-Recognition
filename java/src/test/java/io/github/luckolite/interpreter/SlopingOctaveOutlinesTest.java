// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class SlopingOctaveOutlinesTest {
    private static final int WIDTH = 800, HEIGHT = 355, GAP = 16;
    private static final int STAFF_TOP = 148, GLYPH_LEFT = 165, GLYPH_RIGHT = 192;
    private static final int FIRST_ATTACK = 70, ADVANCE = 36;
    private static final int FIRST_SHIFTED = 3, LAST_SHIFTED = 13;
    private static final int DOT_START = 197, DOT_END = DOT_START + 43 * 8 + 2;
    private static final List<PlayingTechniqueDetector.Staff> STAFFS =
            List.of(new PlayingTechniqueDetector.Staff(STAFF_TOP, STAFF_TOP + 4 * GAP, GAP, 0, 1));

    @Test
    public void slopingLineOwnsExactlyTheLoweredWrittenOctavesBetweenIndependentBeams() {
        var ink = printed();
        var shifts = shifts(ink);
        for (int i = 0; i < shifts.size(); i++)
            assertEquals(
                    i / 2 >= FIRST_SHIFTED && i / 2 <= LAST_SHIFTED ? 1 : 0, (int) shifts.get(i));
    }

    @Test
    public void lostTailOrInterruptedBeamsCannotEstablishAnOpenEnd() {
        var ink = printed();
        paint(ink, DOT_END - 16, DOT_END + 3, 65, 96, 255);
        assertEquals(0L, shifts(ink).stream().filter(s -> s != 0).count());
        ink = printed();
        paint(ink, 505, 514, 273, 301, 255);
        assertEquals(0L, shifts(ink).stream().filter(s -> s != 0).count());
    }

    @Test
    public void largeStaffFitsTheWholeSlopingDottedChain() {
        int wide = WIDTH * 5 / 4, tall = HEIGHT * 5 / 4;
        byte[] source = printed(), enlarged = new byte[wide * tall];
        for (int y = 0; y < tall; y++) {
            for (int x = 0; x < wide; x++)
                enlarged[y * wide + x] = source[(y * 4 / 5) * WIDTH + x * 4 / 5];
        }
        var staffs = List.of(new PlayingTechniqueDetector.Staff(
                STAFF_TOP * 5 / 4, (STAFF_TOP + 4 * GAP) * 5 / 4, GAP * 5 / 4, 0, 1));
        var shifts = shifts(enlarged, wide, tall, staffs);
        for (int i = 0; i < shifts.size(); i++)
            assertEquals(
                    i / 2 >= FIRST_SHIFTED && i / 2 <= LAST_SHIFTED ? 1 : 0, (int) shifts.get(i));
    }

    private static List<Integer> shifts(byte[] ink) {
        return shifts(ink, WIDTH, HEIGHT, STAFFS);
    }

    private static List<Integer> shifts(
            byte[] ink, int width, int height, List<PlayingTechniqueDetector.Staff> staffs) {
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 17; i++) {
            int x = FIRST_ATTACK + ADVANCE * i;
            int upper = inside(i) ? 179 : 131;
            for (int y : new int[] {upper, upper + 56}) {
                notes.add(
                        new ScoreNoteEvent(
                                        0,
                                        x / (float) WIDTH,
                                        (212 - y) / 8,
                                        0,
                                        1,
                                        y / (float) HEIGHT,
                                        false,
                                        0,
                                        2,
                                        2,
                                        0,
                                        1)
                                .withStemDirection(-1));
            }
        }
        return OctaveMarkDetector.apply(
                        List.of(),
                        staffs,
                        List.of(new MeasureRegion(0, 1, 0, 1)),
                        notes,
                        ink,
                        width,
                        height)
                .stream()
                .map(ScoreNoteEvent::octaveShift)
                .toList();
    }

    private static boolean inside(int slot) {
        return slot >= FIRST_SHIFTED && slot <= LAST_SHIFTED;
    }

    private static byte[] printed() {
        var ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        paint(ink, GLYPH_LEFT, GLYPH_RIGHT, 70, 99, 0);
        paint(ink, GLYPH_LEFT + 5, GLYPH_RIGHT - 5, 74, 79, 255);
        paint(ink, GLYPH_LEFT + 4, GLYPH_RIGHT - 4, 85, 94, 255);
        for (int dot = 0; dot < 44; dot++) {
            int x = DOT_START + 8 * dot;
            int y = 77 - Math.min(dot, 12);
            paint(ink, x - 2, x + 2, y, y + 3, 0);
        }
        for (int y = STAFF_TOP; y <= STAFF_TOP + 4 * GAP; y += GAP)
            paint(ink, 25, WIDTH - 25, y, y, 0);
        for (int slot = 0; slot < 17; slot++) {
            int x = FIRST_ATTACK + ADVANCE * slot;
            int upper = inside(slot) ? 179 : 131;
            paint(ink, x - 11, x - 9, upper, upper + 113, 0);
            for (int y : new int[] {upper, upper + 56}) paint(ink, x - 8, x + 8, y - 4, y + 4, 0);
        }
        for (int[] group : new int[][] {{0, 2}, {3, 6}, {7, 9}, {10, 13}, {14, 16}}) {
            int left = FIRST_ATTACK + ADVANCE * group[0] - 10;
            int right = FIRST_ATTACK + ADVANCE * group[1] - 10;
            int tip = inside(group[0]) ? 292 : 244;
            paint(ink, left, right, tip - 6, tip, 0);
            paint(ink, left, right, tip - 19, tip - 13, 0);
        }
        return ink;
    }

    private static void paint(byte[] paper, int left, int right, int top, int bottom, int tone) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(paper, y * WIDTH + left, y * WIDTH + right + 1, (byte) tone);
    }
}
