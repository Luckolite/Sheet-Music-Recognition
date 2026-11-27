// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class CurvedFlagStaffRuleTest {
    private final int w = 300, h = 180;
    private final byte[] gray = new byte[w * h], labels = new byte[w * h];

    private boolean flag(boolean up) throws Exception {
        var type = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        var ctor = type.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        var head = ctor.newInstance(100, 99, 119, 49, 61, 110f, 55f);
        var method =
                OmrScoreInterpreter.class.getDeclaredMethod(
                        "hasCurvedFlag",
                        byte[].class,
                        byte[].class,
                        int.class,
                        int.class,
                        type,
                        float.class,
                        int.class,
                        int.class,
                        boolean.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, labels, gray, w, h, head, 16f, 100, up ? 80 : 120, up);
    }

    private void hook(boolean up) {
        Arrays.fill(gray, (byte) 255);
        for (int d = 0; d <= 34; d++) {
            int x = 100 + Math.round(20 * (float) Math.sin(Math.PI * d / 34));
            int y = up ? 80 + d : 120 - d;
            for (int dx = 0; dx < 3; dx++) gray[y * w + x + dx] = 0;
        }
    }

    @Test
    public void theReturnOfADownStemHookIsVisible() throws Exception {
        hook(false);
        assertTrue(flag(false));
    }

    @Test
    public void theReturnOfAnUpStemHookIsVisible() throws Exception {
        hook(true);
        assertTrue(flag(true));
    }

    @Test
    public void aLongBeamDoesNotReturnWithinTheFlagWindow() throws Exception {
        Arrays.fill(gray, (byte) 255);
        for (int y = 90; y <= 115; y++) for (int x = 100; x < 180; x++) gray[y * w + x] = 0;
        assertFalse(flag(false));
    }

    @Test
    public void mislabeledStaffRulesDoNotHideTheReturningHook() throws Exception {
        hook(true);
        for (int y : new int[] {84, 98, 112})
            for (int yy = y; yy < y + 3; yy++) for (int x = 20; x < 260; x++) gray[yy * w + x] = 0;
        assertTrue(flag(true));
    }

    @Test
    public void aSlurContinuingBeyondTheHookWindowIsNotAFlag() throws Exception {
        Arrays.fill(gray, (byte) 255);
        for (int d = 0; d <= 21; d++)
            for (int dx = 0; dx < 3; dx++) gray[(80 + d) * w + 106 + d + dx] = 0;
        for (int x = 127; x < 220; x++) gray[102 * w + x] = 0;
        assertFalse(flag(true));
    }

    @Test
    public void aDetachedRestDotBesideTheStemTipDoesNotHideItsFlag() throws Exception {
        Arrays.fill(gray, (byte) 255);
        for (int d = 0; d <= 25; d++) {
            int x = 100 + Math.round(18 * (float) Math.sin(Math.PI * d / 25));
            for (int dx = 0; dx < 3; dx++) gray[(88 + d) * w + x + dx] = 0;
        }
        for (int y = 80; y <= 85; y++) for (int x = 121; x <= 128; x++) gray[y * w + x] = 0;
        assertTrue(flag(true));
    }

    private void slurEnteringFromOutside(boolean up) {
        Arrays.fill(gray, (byte) 255);
        for (int d = 4; d <= 22; d++) {
            int x = 100 + Math.max(9, 28 - d);
            int y = up ? 80 + d : 120 - d;
            for (int dx = 0; dx < 3; dx++) gray[y * w + x + dx] = 0;
        }
    }

    @Test
    public void anIncomingSlurHasNoUpStemFlagRoot() throws Exception {
        slurEnteringFromOutside(true);
        assertFalse(flag(true));
    }

    @Test
    public void anIncomingSlurHasNoDownStemFlagRoot() throws Exception {
        slurEnteringFromOutside(false);
        assertFalse(flag(false));
    }

    @Test
    public void aLateReturningCurveStillNeedsItsStemRoot() throws Exception {
        Arrays.fill(gray, (byte) 255);
        for (int d = 10; d <= 34; d++) {
            int x = 100 + Math.round(20 * (float) Math.sin(Math.PI * d / 34));
            for (int dx = 0; dx < 3; dx++) gray[(80 + d) * w + x + dx] = 0;
        }
        assertFalse(flag(true));
    }

    @Test
    public void aCurveContinuingPastTheFreeEndIsASlur() throws Exception {
        hook(false);
        for (int dx = 0; dx <= 21; dx++)
            for (int dy = 0; dy < 3; dy++) gray[(123 + dx / 2 + dy) * w + 100 - dx] = 0;
        assertFalse(flag(false));
    }

    @Test public void aLongLedgerStemKeepsItsPrintedEighthFlag() throws Exception {
        drawExtendedLedgerStem(gray, true);
        assertTrue(extendedLedgerFlag(gray.clone()));
    }

    @Test public void aLongLedgerQuarterCannotBorrowAStaffRule() throws Exception {
        drawExtendedLedgerStem(gray, false);
        assertFalse(extendedLedgerFlag(gray.clone()));
    }

    @Test public void aFlagMustAlsoBePresentInTheOriginalRaster() throws Exception {
        byte[] original = new byte[gray.length];
        drawExtendedLedgerStem(original, false);
        drawExtendedLedgerStem(gray, true);
        assertFalse(extendedLedgerFlag(original));
    }

    private boolean extendedLedgerFlag(byte[] original) throws Exception {
        var type = Class.forName(OmrScoreInterpreter.class.getName() + "$Component");
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var head = constructor.newInstance(210, 99, 121, 26, 42, 110f, 34f);
        var method = OmrScoreInterpreter.class.getDeclaredMethod(
                "extendedLedgerFlag", byte[].class, byte[].class, byte[].class,
                int.class, int.class, type, float.class, float.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, labels, gray, original, w, h, head, 100f, 16f);
    }

    private void drawExtendedLedgerStem(byte[] ink, boolean flagged) {
        Arrays.fill(ink, (byte) 255);
        for (int y = 100; y <= 164; y += 16)
            for (int x = 20; x < 260; x++) {
                ink[y * w + x] = 0;
                labels[y * w + x] = OmrMeasurePostProcessor.STAFF;
            }
        for (int y = 26; y <= 42; y++)
            for (int x = 99; x <= 121; x++)
                if ((x - 110) * (x - 110) / 121f + (y - 34) * (y - 34) / 64f <= 1) {
                    ink[y * w + x] = 0;
                    labels[y * w + x] = OmrMeasurePostProcessor.NOTEHEAD;
                }
        for (int y = 34; y <= 132; y++) {
            ink[y * w + 99] = 0;
            ink[y * w + 100] = 0;
        }
        if (flagged)
            for (int d = 0; d <= 34; d++) {
                int x = 100 + Math.round(20 * (float) Math.sin(Math.PI * d / 34));
                for (int dx = 0; dx < 3; dx++) ink[(132 - d) * w + x + dx] = 0;
            }
    }
}
