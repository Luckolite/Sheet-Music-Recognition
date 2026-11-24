// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class OpeningFifthLedgerStepTest {
    private static final int W = 310, H = 345, BOTTOM = 300, GAP = 16;
    private static final int SPACE_X = 122, LINE_X = 188, PEER_X = 155;
    private final byte[] labels = new byte[W * H];
    private final byte[] gray = new byte[W * H];
    private final Object space, line, peer, staff;
    private final Method step;
    private final List<MeasureRegion> bars = List.of(new MeasureRegion(.24f, .9f, .60f, .96f));

    public OpeningFifthLedgerStepTest() throws Exception {
        var ownerClass = OmrScoreInterpreter.class;
        var component = Class.forName(ownerClass.getName() + "$Component");
        var staffClass = Class.forName(ownerClass.getName() + "$Staff");
        var hc = component.getDeclaredConstructors()[0];
        hc.setAccessible(true);
        space = hc.newInstance(318, SPACE_X - 10, SPACE_X + 10, 138, 156, (float) SPACE_X, 147f);
        peer = hc.newInstance(318, PEER_X - 10, PEER_X + 10, 155, 173, (float) PEER_X, 164f);
        line = hc.newInstance(318, LINE_X - 10, LINE_X + 10, 144, 162, (float) LINE_X, 153f);
        var sc = staffClass.getDeclaredConstructors()[0];
        sc.setAccessible(true);
        staff = sc.newInstance((float) BOTTOM - 4 * GAP, (float) BOTTOM, (float) GAP);
        var count = staffClass.getDeclaredField("count");
        count.setAccessible(true);
        count.set(staff, 1);
        step = ownerClass.getDeclaredMethod("openingFifthLedgerStep", byte[].class, byte[].class, byte[].class,
                int.class, int.class, component, staffClass, List.class, List.class);
        step.setAccessible(true);
        Arrays.fill(gray, (byte) 255);
        for (int y = BOTTOM - 4 * GAP; y <= BOTTOM; y += GAP) {
            box(30, 279, y, y + 1, OmrMeasurePostProcessor.STAFF);
        }
        for (int x : new int[] {SPACE_X, PEER_X, LINE_X}) {
            int y = x == SPACE_X ? 147 : x == PEER_X ? 164 : 153;
            for (int dy = -8; dy <= 8; dy++)
                for (int dx = -10; dx <= 10; dx++)
                    if (dx * dx / 100f + dy * dy / 64f <= 1)
                        box(x + dx, x + dx, y + dy, y + dy, OmrMeasurePostProcessor.NOTEHEAD);
            box(x - 10, x - 8, y, 271, (byte) 1);
            for (int rule = x == PEER_X ? 172 : 156; rule <= 220; rule += GAP)
                box(x - 13, x + 13, rule - 1, rule + 1, OmrMeasurePostProcessor.SYMBOL);
        }
        box(SPACE_X - 10, LINE_X - 8, 249, 256, OmrMeasurePostProcessor.SYMBOL);
        box(SPACE_X - 10, LINE_X - 8, 262, 271, OmrMeasurePostProcessor.SYMBOL);
    }

    @Test
    public void fiveShortRulesAndDoubleBeamDistinguishTheOpeningSpaceFromTheLine() throws Exception {
        assertEquals(19, pitch(space, gray, bars));
        assertEquals(18, pitch(line, gray, bars));
        assertEquals(0, pitch(peer, gray, bars));
    }

    @Test
    public void missingLedgerOrBeamInOriginalRefusesTheAdmission() throws Exception {
        byte[] cut = gray.clone();
        for (int y = 170; y <= 174; y++) Arrays.fill(cut, y * W + SPACE_X - 14, y * W + SPACE_X + 15, (byte) 255);
        assertEquals(0, pitch(space, cut, bars));
        cut = gray.clone();
        for (int y = 248; y <= 272; y++) Arrays.fill(cut, y * W + SPACE_X + 2, y * W + PEER_X - 12, (byte) 255);
        assertEquals(0, pitch(space, cut, bars));
        assertEquals(0, pitch(space, null, bars));
    }

    private int pitch(Object head, byte[] original, List<MeasureRegion> measures) throws Exception {
        return (int) step.invoke(null, labels, gray, original, W, H, head, staff, List.of(space, peer, line), measures);
    }

    private void box(int left, int right, int top, int bottom, byte label) {
        for (int y = top; y <= bottom; y++)
            for (int x = left; x <= right; x++) {
                labels[y * W + x] = label;
                gray[y * W + x] = 0;
            }
    }
}
