// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Original shaft, beams and slash ink with an optional genuine beam hook. */
public class TremoloBeamSeparationTest {
    private static final int W = 190, H = 180;
    private final byte[] gray = new byte[W * H];

    private void rect(int x0, int y0, int x1, int y1) {
        for (int y = y0; y <= y1; y++) Arrays.fill(gray, y * W + x0, y * W + x1 + 1, (byte) 10);
    }

    private void figure() {
        Arrays.fill(gray, (byte) 255);
        rect(100, 50, 101, 125);
        rect(100, 50, 146, 57);
        rect(100, 64, 146, 71);
        for (int x = 88; x <= 112; x++)
            rect(x, 94 - (x - 88) / 4, x, 99 - (x - 88) / 4);
    }

    private boolean twoBeams(int slashY) throws ReflectiveOperationException {
        var staff = Class.forName(OmrScoreInterpreter.class.getName() + "$Staff");
        var ctor = staff.getDeclaredConstructor(float.class, float.class, float.class);
        ctor.setAccessible(true);
        var method = OmrScoreInterpreter.class.getDeclaredMethod("admitsTwoBeamsBeforeSlash",
                byte[].class, byte[].class, int.class, int.class, staff, int[].class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, gray, new byte[gray.length], W, H,
                ctor.newInstance(92f, 172f, 20f), new int[] {1, 0, slashY, 100, 50, -1});
    }

    @Test public void distantSlashDoesNotSupplyAThirdBeam() throws Exception {
        figure();
        assertTrue(twoBeams(95));
    }

    @Test public void aGenuineThirdBeamIsRetained() throws Exception {
        figure();
        rect(100, 78, 146, 86);
        assertFalse(twoBeams(95));
    }

    @Test public void aGenuineShortThirdHookIsRetained() throws Exception {
        figure();
        rect(100, 78, 109, 86);
        assertFalse(twoBeams(95));
    }

    @Test public void aShortThirdHookCanFaceAwayFromTheMainBeams() throws Exception {
        figure();
        rect(91, 78, 100, 86);
        assertFalse(twoBeams(95));
    }

    @Test public void aSlashTooCloseToTheTipIsAmbiguous() throws Exception {
        figure();
        assertFalse(twoBeams(82));
    }
}
