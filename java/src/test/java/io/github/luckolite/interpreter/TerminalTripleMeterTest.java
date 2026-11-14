// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class TerminalTripleMeterTest {
    private static final int W = 920;
    private static final int H = 260;
    private static final int TOP = 105;
    private static final int GAP = 16;
    private static final int BOTTOM = TOP + 4 * GAP;
    private static final List<MeasureRegion> BARS =
            List.of(region(20, 176), region(194, 473), region(491, 758), region(776, 862));
    private static final MeterChangeDetector.Crop CANDIDATE =
            new MeterChangeDetector.Crop(181, 102, 238, 173, 105, GAP);

    @Test
    public void removesAdjacentRuleAndStopsBeforeFirstNoteInk() {
        var crop = read(staff(true, true), notes(true), BARS);
        assertNotNull(crop);
        assertEquals(190, crop.left());
        assertTrue(crop.right() >= 224 && crop.right() < 230);
        assertEquals(TOP, crop.firstLine());
    }

    @Test
    public void requiresPrintedOpeningAndBothClosingRules() {
        assertNull(read(staff(false, true), notes(true), BARS));
        assertNull(read(staff(true, false), notes(true), BARS));
    }

    @Test
    public void requiresTwoCompleteRunsAndAnIsolatedDottedHalf() {
        var incomplete = notes(true);
        incomplete.remove(incomplete.size() - 2);
        var gray = staff(true, true);
        assertNull(read(gray, incomplete, BARS));
        assertNull(read(gray, notes(false), BARS));
        var more = new ArrayList<>(BARS);
        more.add(region(870, 902));
        assertNull(read(gray, notes(true), more));
    }

    @Test
    public void outOfBoundsDividerIsRejected() {
        var clipped = List.of(region(-24, -18), region(0, 273), BARS.get(2), BARS.get(3));
        assertNull(read(staff(true, true), notes(true), clipped));
    }

    private static MeterChangeDetector.Crop read(
            byte[] gray, List<ScoreNoteEvent> notes, List<MeasureRegion> measures) {
        return TerminalTripleMeter.crop(
                CANDIDATE, new byte[gray.length], gray, W, H, 1, measures, notes);
    }

    private static byte[] staff(boolean opening, boolean doubleRule) {
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        for (int y = TOP; y <= BOTTOM; y += GAP) {
            Arrays.fill(gray, y * W + 12, y * W + 875, (byte) 0);
        }
        if (opening) stroke(gray, 184);
        stroke(gray, 860);
        if (doubleRule) stroke(gray, 868);
        for (int y = 109; y <= 114; y++) {
            for (int x = 194; x < 222; x++) if (x % 5 != 2) gray[y * W + x] = 0;
        }
        for (int y = 145; y <= 148; y++) {
            for (int x = 194; x < 222; x++) if (x % 5 != 2) gray[y * W + x] = 0;
        }
        return gray;
    }

    private static void stroke(byte[] gray, int x) {
        for (int y = TOP; y <= BOTTOM; y++) {
            Arrays.fill(gray, y * W + x, y * W + x + 5, (byte) 0);
        }
    }

    private static List<ScoreNoteEvent> notes(boolean dotted) {
        var result = new ArrayList<ScoreNoteEvent>();
        for (int bar = 1; bar <= 2; bar++) {
            for (int i = 0; i < 12; i++) {
                result.add(note(bar, .18f + .065f * i, 2, 0, 0));
            }
        }
        result.add(note(3, .45f, 0, 2, dotted ? 1 : 0));
        return result;
    }

    private static ScoreNoteEvent note(int bar, float x, int beams, float length, int dots) {
        return new ScoreNoteEvent(
                bar,
                x,
                7,
                0,
                1,
                111f / H,
                false,
                dots,
                beams,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                length,
                1,
                0,
                0,
                30,
                false,
                0,
                false,
                0,
                0,
                1,
                -1,
                ScoreNoteEvent.Kind.PITCHED);
    }

    private static MeasureRegion region(int left, int right) {
        return new MeasureRegion(left / (float) W, right / (float) W, 71f / H, 203f / H);
    }
}
