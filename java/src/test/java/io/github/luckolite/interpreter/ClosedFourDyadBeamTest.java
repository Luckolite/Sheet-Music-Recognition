package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class ClosedFourDyadBeamTest {
    private static final int WIDTH = 310;
    private static final int HEIGHT = 216;
    private static final float GAP = 16;
    private static final int[] CENTERS = {62, 94, 126, 158};
    private static final int LOWER = 151;
    private static final int UPPER = 111;
    private static final int OUTER = 68;
    private static final int INNER = 82;

    @Test public void twoClosedBeamsOnFourDyadShaftsOwnTheRun() {
        for (int direction : new int[]{-1, 1})
            assertTrue(proves(image(direction), direction));
    }

    @Test public void separateSecondaryOnThreeAttacksPreservesItsNumeral() {
        for (int direction : new int[]{-1, 1}) {
            var gray = image(direction);
            paint(gray, direction, 73, 100, INNER-2, INNER+2, (byte) 255);
            assertFalse(proves(gray, direction));
        }
    }

    @Test public void aRailThatContinuesBeyondFourAttacksDoesNotQualify() {
        for (int direction : new int[]{-1, 1}) {
            var gray = image(direction);
            paint(gray, direction, 167, 194, OUTER-2, OUTER+2, (byte) 0);
            assertFalse(proves(gray, direction));
        }
    }

    @Test public void aStaffLineIsNotAnOwnedSecondaryRail() {
        var gray = image(-1);
        paint(gray, -1, 75, 164, INNER-2, INNER+2, (byte) 255);
        paint(gray, -1, 15, 275, INNER, INNER, (byte) 0);
        assertFalse(proves(gray, -1));
    }

    @Test public void aBrokenSharedShaftDoesNotQualify() {
        var gray = image(-1);
        paint(gray, -1, 133, 140, 116, 130, (byte) 255);
        assertFalse(proves(gray, -1));
    }

    private static boolean proves(byte[] gray, int direction) {
        var heads = new ClosedFourDyadBeam.Head[4];
        for(int i = 0; i < heads.length; i++) {
            int a = y(direction, UPPER), b = y(direction, LOWER);
            int center = direction < 0 ? CENTERS[i] : CENTERS[heads.length - i - 1];
            heads[i] = new ClosedFourDyadBeam.Head(x(direction, center), Math.min(a,b), Math.max(a,b));
        }
        return ClosedFourDyadBeam.proves(gray, WIDTH, HEIGHT, heads, GAP, direction);
    }

    private static byte[] image(int direction) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        paint(gray, direction, 69, 169, OUTER-2, OUTER+2, (byte) 0);
        paint(gray, direction, 69, 169, INNER-2, INNER+2, (byte) 0);
        for (int center : CENTERS) {
            paint(gray, direction, center+8, center+10, OUTER, LOWER, (byte) 0);
            paint(gray, direction, center-5, center+10, UPPER-4, UPPER+4, (byte) 0);
            paint(gray, direction, center-5, center+10, LOWER-4, LOWER+4, (byte) 0);
        }
        return gray;
    }

    private static int y(int direction, int y) { return direction < 0 ? y : HEIGHT-1-y; }
    private static int x(int direction, int x) { return direction < 0 ? x : WIDTH-1-x; }

    private static void paint(byte[] gray, int direction, int l, int r, int top, int bottom, byte v) {
        for (int yy = top; yy <= bottom; yy++)
            for (int xx = l; xx <= r; xx++)
                gray[y(direction, yy)*WIDTH+x(direction, xx)] = v;
    }
}
