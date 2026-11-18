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

    @Test
    public void terminalOctaveNeedsBothRailsFromItsPrecedingOctave() {
        var group = Arrays.stream(heads(1))
                .map(h -> new BeamedFlatBarline.Dyad(h.x(), h.top(), h.bottom()))
                .toArray(BeamedFlatBarline.Dyad[]::new);
        var gray = image(1);
        assertTrue(BeamedFlatBarline.terminalShaftWithRails(gray, WIDTH, HEIGHT, group, GAP) != null);
        paint(gray, 1, 79, 92, INNER - 2, INNER + 2, (byte) 255);
        assertTrue(BeamedFlatBarline.terminalShaftWithRails(gray, WIDTH, HEIGHT, group, GAP) == null);
    }


    @Test public void aBrokenSharedShaftDoesNotQualify() {
        var gray = image(-1);
        paint(gray, -1, 133, 140, 116, 130, (byte) 255);
        assertFalse(proves(gray, -1));
    }

    @Test
    public void independentlyMeasuredSingleStaffCrossingCanOccludeOneSeam() {
        var gray = image(1);
        int middle = (OUTER + INNER) / 2;
        paint(gray, 1, 110, 120, middle - 3, middle + 3, (byte) 0);
        assertTrue(
                ClosedFourDyadBeam.provesOctaves(gray, WIDTH, HEIGHT, heads(1), GAP, y(1, middle)));
        assertFalse(
                ClosedFourDyadBeam.provesOctaves(
                        gray, WIDTH, HEIGHT, heads(1), GAP, y(1, middle + 5)));
    }

    @Test
    public void twoOccludedBridgesDoNotQualifyAsOneStaffCrossing() {
        var gray = image(1);
        int middle = (OUTER + INNER) / 2;
        paint(gray, 1, 78, 88, middle - 3, middle + 3, (byte) 0);
        paint(gray, 1, 110, 120, middle - 3, middle + 3, (byte) 0);
        assertFalse(
                ClosedFourDyadBeam.provesOctaves(gray, WIDTH, HEIGHT, heads(1), GAP, y(1, middle)));
    }

    @Test
    public void innerRailCanCrossVerifiedStaffForOnlyAShortRun() {
        var gray = image(1);
        paint(gray, 1, 173, 175, INNER - 2, INNER + 2, (byte) 0);
        assertFalse(
                ClosedFourDyadBeam.provesOctaves(gray, WIDTH, HEIGHT, heads(1), GAP, y(1, INNER)));
        assertTrue(
                ClosedFourDyadBeam.provesOctaves(
                        gray, WIDTH, HEIGHT, heads(1), GAP, y(1, INNER), 1, true));
        paint(gray, 1, 176, 194, INNER - 2, INNER + 2, (byte) 0);
        assertFalse(
                ClosedFourDyadBeam.provesOctaves(
                        gray, WIDTH, HEIGHT, heads(1), GAP, y(1, INNER), 1, true));
    }

    @Test
    public void adjacentProvedBarlineMayOccupyOnlyTheLeftClosureSample() {
        var gray = image(-1);
        paint(gray, -1, 56, 59, OUTER - 3, INNER + 3, (byte) 0);
        assertFalse(proves(gray, -1));
        assertTrue(ClosedFourDyadBeam.provesOctavesAfterRule(
                gray, WIDTH, HEIGHT, heads(-1), GAP, LOWER, -1, 57));
        assertFalse(ClosedFourDyadBeam.provesOctavesAfterRule(
                gray, WIDTH, HEIGHT, heads(-1), GAP, LOWER, -1, 49));
        paint(gray, -1, 73, 100, INNER - 2, INNER + 2, (byte) 255);
        assertFalse(ClosedFourDyadBeam.provesOctavesAfterRule(
                gray, WIDTH, HEIGHT, heads(-1), GAP, LOWER, -1, 57));
    }

    private static boolean proves(byte[] gray, int direction) {
        return ClosedFourDyadBeam.proves(gray, WIDTH, HEIGHT, heads(direction), GAP, direction);
    }

    private static ClosedFourDyadBeam.Head[] heads(int direction) {
        var heads = new ClosedFourDyadBeam.Head[4];
        for(int i = 0; i < heads.length; i++) {
            int a = y(direction, UPPER), b = y(direction, LOWER);
            int center = direction < 0 ? CENTERS[i] : CENTERS[heads.length - i - 1];
            heads[i] = new ClosedFourDyadBeam.Head(x(direction, center), Math.min(a,b), Math.max(a,b));
        }
        return heads;
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
