// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class StemlessOpeningHalfTest {
    private static final int WIDTH = 350, HEIGHT = 210;
    private static final float GAP = 16;

    @Test
    public void slantedStemlessHeadsUseThePrintedHalfAttack() throws Exception {
        assertEquals(List.of(2f, 2f, 2f, 2f, 2f), recognize(true, true, false));
    }

    @Test
    public void ordinaryWholeShapesDoNotBorrowAnAdjacentHalfShaft() throws Exception {
        assertEquals(List.of(2f, 4f, 4f, 2f, 2f), recognize(false, true, false));
    }

    @Test
    public void needsTheIndependentFollowingHalfAttack() throws Exception {
        assertEquals(List.of(2f, 4f, 4f, 2f, 2f), recognize(true, false, false));
        assertEquals(List.of(2f, 4f, 4f, 2f, 2f, 2f), recognize(true, true, true));
    }

    private static List<Float> recognize(boolean slanted, boolean followingShaft,
            boolean thirdAttack) throws Exception {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        drawOval(gray, 95, 45, true);
        drawOval(gray, 95, 130, slanted);
        drawOval(gray, 95, 147, slanted);
        drawOval(gray, 200, 147, true);
        drawOval(gray, 200, 99, true);
        shaft(gray, 86, 49, 95);
        if (followingShaft) shaft(gray, 209, 90, 143);

        Class<?> owner = OmrScoreInterpreter.class;
        Class<?> component = Class.forName(owner.getName() + "$Component");
        Class<?> printed = Class.forName(owner.getName() + "$DetectedNote");
        var makeHead = component.getDeclaredConstructors()[0];
        var makePrinted = printed.getDeclaredConstructors()[0];
        makeHead.setAccessible(true);
        makePrinted.setAccessible(true);
        List<Object> heads = new ArrayList<>();
        List<ScoreNoteEvent> notes = new ArrayList<>();
        int[][] music = {{95,45,17,2,-1}, {95,130,1,4,0}, {95,147,-1,4,0},
                {200,147,-1,2,1}, {200,99,6,2,0}, {270,99,6,2,0}};
        for (int i = 0; i < (thirdAttack ? 6 : 5); i++) {
            int[] data = music[i];
            int x = data[0], y = data[1];
            var n = new ScoreNoteEvent(0, x/(float) WIDTH, data[2], 0, 1,
                    y/(float) HEIGHT, false, 0, 0, 2, data[3], 1).withStemDirection(data[4]);
            notes.add(n);
            var h = makeHead.newInstance(270, x-10, x+10, y-8, y+8, (float) x, (float) y);
            heads.add(makePrinted.newInstance(n, h, GAP));
        }
        var restore = owner.getDeclaredMethod("retainStemlessOpeningHalfHeads", List.class,
                List.class, List.class, byte[].class, int.class, int.class);
        restore.setAccessible(true);
        restore.invoke(null, notes, heads, List.of(), gray, WIDTH, HEIGHT);
        return notes.stream().map(ScoreNoteEvent::unbeamedDurationBeats).toList();
    }

    private static void drawOval(byte[] gray, int cx, int cy, boolean slanted) {
        double angle = slanted ? -.53 : .63;
        for (int y = -8; y <= 8; y++)
            for (int x = -10; x <= 10; x++) {
                if (x*x/95.0 + y*y/55.0 > 1) continue;
                double a = x*Math.cos(angle) + y*Math.sin(angle);
                double b = -x*Math.sin(angle) + y*Math.cos(angle);
                if (a*a/45.0 + b*b/9.5 > 1)
                    gray[(cy+y)*WIDTH+cx+x] = 0;
            }
    }

    private static void shaft(byte[] gray, int x, int top, int bottom) {
        for (int y = top; y <= bottom; y++) {
            gray[y*WIDTH+x] = 0;
            gray[y*WIDTH+x+1] = 0;
        }
    }
}
