// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class OwnedTertiaryBeamletsTest {
    private static final int WIDTH = 690;
    private static final int HEIGHT = 205;
    private static final int ESTIMATED_GAP = 16;
    private static final int STAFF_TOP = 54;
    private static final int LEFT = 36;
    private static final int RIGHT = 650;
    private static final int STAFF_BOTTOM = 124;
    private static final int UPPER_HEAD = 89;
    private static final int LOWER_HEAD = 124;
    private static final int BEAM_TIP = 166;

    @Test
    public void restoresOnlyUndercountedHooksOnTheirOwnDyads() {
        assertEquals(List.of(2, 3, 6, 7), recover(true, true));
    }

    @Test
    public void requiresBothAnAttachedHookAndATwoStrokeClose() {
        assertEquals("detached third beamlet", List.of(), recover(false, true));
        assertEquals("single closing stroke", List.of(), recover(true, false));
    }

    @Test
    public void closingDyadNeedsBothSharedShaftsAndAnExclusivelyOwnedThirdHook() {
        assertEquals(true, closingDyadHook(true, true, false));
        assertEquals(false, closingDyadHook(false, true, false));
        assertEquals(false, closingDyadHook(true, false, false));
        assertEquals(false, closingDyadHook(true, true, true));
    }


    @Test
    public void longDownstrokeOwnsTheThirdHookUnderTheStaffRule() {
        assertEquals(true, highLedgerHook(true, true, false));
        assertEquals(false, highLedgerHook(false, true, false));
        assertEquals(false, highLedgerHook(true, false, false));
        assertEquals(false, highLedgerHook(true, true, true));
    }

    private static boolean highLedgerHook(boolean hook, boolean pairedRails, boolean filledHead) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        int owner = 423, prior = 339;
        int top = 124, upper = 50, lower = 69, tip = 159;
        int shaft = shaftX(owner), reverse = shaft - 8;
        for (int x : new int[] {prior, owner}) {
            oval(gray, x, upper, false);
            oval(gray, x, lower, false);
            ink(gray, shaftX(x), upper, shaftX(x) + 2, tip);
        }
        ink(gray, 300, top - 1, 490, top + 1);
        ink(gray, shaftX(prior), tip - 8, shaft + 2, tip);
        if (pairedRails) ink(gray, shaftX(prior), tip - 21, shaft + 2, tip - 14);
        if (hook) ink(gray, shaft - 14, top + 2, shaft + 2, top + 10);
        if (filledHead) ink(gray, reverse - 4, top - 8, reverse + 4, top + 8);
        return OwnedTertiaryBeamlets.ownsHighLedgerHookOnStaffLine(
                gray, WIDTH, HEIGHT, reverse, top + 6, owner, lower, prior, lower, ESTIMATED_GAP, top);
    }

    private static boolean closingDyadHook(
            boolean terminalHook, boolean sharedPeerShaft, boolean peerAlsoHooks) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        int peer = 490, last = 537, upper = 81, lower = 98;
        for (int x : new int[] {peer, last}) {
            oval(gray, x, upper, false);
            oval(gray, x == peer && !sharedPeerShaft ? x + 13 : x, lower, false);
            ink(gray, shaftX(x), upper, shaftX(x) + 2, BEAM_TIP);
        }
        ink(gray, shaftX(peer), BEAM_TIP - 7, shaftX(last) + 2, BEAM_TIP);
        ink(gray, shaftX(peer), BEAM_TIP - 21, shaftX(last) + 2, BEAM_TIP - 14);
        if (terminalHook) {
            ink(gray, shaftX(last) - 14, BEAM_TIP - 35, shaftX(last), BEAM_TIP - 26);
        }
        if (peerAlsoHooks) {
            ink(gray, shaftX(peer), BEAM_TIP - 35, shaftX(peer) + 14, BEAM_TIP - 26);
        }
        return OwnedTertiaryBeamlets.provesClosingDyadHook(
                gray, WIDTH, HEIGHT, last, lower, last, upper, peer, upper,
                sharedPeerShaft ? peer : peer + 13, lower, ESTIMATED_GAP,
                new PrintedBeamShafts.Budget(500_000));
    }

    private static List<Integer> recover(boolean attachFirstHook, boolean doubleClose) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        // Five printed lines establish a 17.5-pixel gap independently of the note estimate.
        for (int y : new int[] {STAFF_TOP, 72, 89, 107, STAFF_BOTTOM}) {
            ink(gray, LEFT, y, RIGHT + 4, y);
        }
        ink(gray, RIGHT - 4, STAFF_TOP, RIGHT - 3, STAFF_BOTTOM);
        if (doubleClose) {
            ink(gray, RIGHT + 2, STAFF_TOP, RIGHT + 4, STAFF_BOTTOM);
        }

        var heads = new ArrayList<OwnedTertiaryBeamlets.Head>();
        for (int pair = 0; pair < 4; pair++) {
            int dottedX = 100 + pair * 125;
            int hookedX = dottedX + 55;
            drawBeamedDyad(gray, dottedX);
            drawBeamedDyad(gray, hookedX);
            ink(gray, shaftX(dottedX), BEAM_TIP - 7, shaftX(hookedX) + 2, BEAM_TIP);
            ink(gray, shaftX(dottedX), BEAM_TIP - 21, shaftX(hookedX) + 2, BEAM_TIP - 14);
            int hookEnd = shaftX(hookedX) - (pair == 0 && !attachFirstHook ? 18 : 0);
            ink(gray, hookEnd - 14, BEAM_TIP - 35, hookEnd, BEAM_TIP - 26);
            ink(gray, dottedX + 15, UPPER_HEAD - 2, dottedX + 18, UPPER_HEAD + 1);
            ink(gray, dottedX + 15, LOWER_HEAD - 2, dottedX + 18, LOWER_HEAD + 1);
            addDyad(heads, dottedX, 1, 2, 0);
            addDyad(heads, hookedX, 0, pair < 2 ? 2 : 3, 0);
        }
        int closingX = 590;
        ink(gray, shaftX(closingX), UPPER_HEAD, shaftX(closingX) + 2, LOWER_HEAD + 39);
        oval(gray, closingX, UPPER_HEAD, true);
        oval(gray, closingX, LOWER_HEAD, true);
        addDyad(heads, closingX, 0, 0, 2);
        var measure = new MeasureRegion(LEFT / (float) WIDTH, RIGHT / (float) WIDTH,
                34f / HEIGHT, 182f / HEIGHT);
        return OwnedTertiaryBeamlets.recover(gray, WIDTH, HEIGHT, heads, List.of(measure));
    }

    private static void addDyad(
            List<OwnedTertiaryBeamlets.Head> heads, int x, int dots, int beams, int duration) {
        for (int y : new int[] {UPPER_HEAD, LOWER_HEAD}) {
            int step = y == UPPER_HEAD ? 4 : 0;
            var note = new ScoreNoteEvent(0, (x - LEFT) / (float) (RIGHT - LEFT), step, 0, 1,
                    y / (float) HEIGHT, false, dots, beams, ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                    duration).withStemDirection(-1);
            heads.add(new OwnedTertiaryBeamlets.Head(heads.size(), note, x, y, ESTIMATED_GAP));
        }
    }

    private static int shaftX(int headX) {
        return headX - 10;
    }

    private static void drawBeamedDyad(byte[] gray, int x) {
        ink(gray, shaftX(x), UPPER_HEAD, shaftX(x) + 2, BEAM_TIP);
        oval(gray, x, UPPER_HEAD, false);
        oval(gray, x, LOWER_HEAD, false);
    }

    private static void oval(byte[] gray, int centerX, int centerY, boolean hollow) {
        for (int y = -5; y <= 5; y++) {
            for (int x = -10; x <= 10; x++) {
                if (x * x + 4 * y * y <= 100) {
                    boolean counter = hollow && x * x + 4 * y * y < 43;
                    gray[(centerY + y) * WIDTH + centerX + x] = counter ? (byte) 255 : 0;
                }
            }
        }
    }

    private static void ink(byte[] gray, int left, int top, int right, int bottom) {
        for (int y = top; y <= bottom; y++) {
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) 0);
        }
    }
}
