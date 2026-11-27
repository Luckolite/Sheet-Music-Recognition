// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class FramedEndingBracketTest {
    private static final int WIDTH = 600, HEIGHT = 360;

    @Test
    public void narrowPageFrameDoesNotConsumeTheSecondEnding() {
        var mark = read(true, true, true).get(0);
        assertEquals(1, mark.start());
        assertEquals(2, mark.end());
        assertEquals(List.of(2), mark.passes());
    }

    @Test
    public void ordinaryBracketAndRealLeftHookStillMatter() {
        assertEquals(1, read(false, true, true).size());
        assertTrue(read(true, false, true).isEmpty());
    }

    @Test
    public void anAttachedShortVerticalStrokeIsNotACropFrame() {
        assertTrue(read(true, true, false).isEmpty());
    }

    private static List<PrintedEndingBrackets.Mark> read(
            boolean frame, boolean leftHook, boolean fullHeight) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        if (frame)
            black(gray, 596, fullHeight ? 28 : 70, WIDTH, 334);
        black(gray, 308, 148, frame ? WIDTH : 596, 150);
        if (leftHook) black(gray, 308, 148, 310, 192);
        black(gray, 594, 148, 596, 191);
        return PrintedEndingBrackets.detect(
                gray, WIDTH, HEIGHT,
                List.of(new PlayingTechniqueDetector.Staff(214, 262, 12, 0, 1)),
                List.of(
                        new MeasureRegion(.1f, .5f, .55f, .78f),
                        new MeasureRegion(308f / WIDTH, 595f / WIDTH, .55f, .78f)),
                List.of(new PlayingTechniqueDetector.Word(
                        "2.", 316f / WIDTH, 155f / HEIGHT, 332f / WIDTH, 174f / HEIGHT)));
    }

    private static void black(byte[] gray, int left, int top, int right, int bottom) {
        for (int y = top; y < bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right, (byte) 0);
    }
}
