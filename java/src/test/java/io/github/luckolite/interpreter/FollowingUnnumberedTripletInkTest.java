// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class FollowingUnnumberedTripletInkTest {
    private static final int WIDTH = 256;
    private static final int HEIGHT = 340;
    private static final float GAP = 16;
    private static final float[] HEAD_X = {92, 120, 148};
    private static final float[] HEAD_Y = {180, 180, 180};
    private static final List<PrintedBeamShafts.Shaft> SHAFTS =
            List.of(shaft(92), shaft(120), shaft(148));
    private static final StaffPitchTrack TRACK = StaffPitchTrack.fromVerifiedSamples(
            List.of(new float[] {20, 209, GAP}, new float[] {235, 209, GAP}));
    private static final UnnumberedTripletInk.Rows ROWS = new UnnumberedTripletInk.Rows(0, HEIGHT - 1);
    private static final UnnumberedTripletInk.Head LOWER_HEAD = new UnnumberedTripletInk.Head(108, 255);

    @Test
    public void nextSystemOwnsItsVisibleHeadAndCompactStaccato() {
        var gray = beam();
        fill(gray, 105, 111, 228, 234);
        fill(gray, 100, 117, 249, 260);

        assertFalse(clear(gray, List.of()));
        assertFalse(clear(gray, List.of(new UnnumberedTripletInk.Head(114, 255))));
        assertTrue(clear(gray, List.of(LOWER_HEAD)));
    }

    @Test
    public void nextSystemInkCannotHideCurrentNumeralOrBracket() {
        var gray = beam();
        fill(gray, 105, 111, 228, 234);
        fill(gray, 100, 117, 249, 260);
        var numbered = gray.clone();
        paint(numbered, 116, 115, ".#####.", "##...##", ".....##", "....##.",
                "...###.", "....##.", ".....##", ".....##", "##...##", ".#####.");
        assertFalse(clear(numbered, List.of(LOWER_HEAD)));

        var bracketed = gray.clone();
        fill(bracketed, 89, 109, 112, 112);
        fill(bracketed, 131, 151, 112, 112);
        fill(bracketed, 89, 89, 112, 116);
        fill(bracketed, 151, 151, 112, 116);
        assertFalse(clear(bracketed, List.of(LOWER_HEAD)));
    }

    private static PrintedBeamShafts.Shaft shaft(int x) {
        return new PrintedBeamShafts.Shaft(x - 1, x + 1, 180, 143, -1, List.of());
    }

    private static boolean clear(byte[] gray, List<UnnumberedTripletInk.Head> lower) {
        return UnnumberedTripletInk.clearFollowing(gray, WIDTH, HEIGHT, GAP, HEAD_X, HEAD_Y,
                SHAFTS, ROWS, TRACK, List.of(), lower);
    }

    private static byte[] beam() {
        var gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (var shaft : SHAFTS) fill(gray, shaft.left(), shaft.right(), 143, 180);
        fill(gray, 91, 149, 142, 144);
        return gray;
    }

    private static void fill(byte[] gray, int l, int r, int t, int b) {
        for (int y = t; y <= b; y++) {
            Arrays.fill(gray, y * WIDTH + l, y * WIDTH + r + 1, (byte) 0);
        }
    }

    private static void paint(byte[] gray, int left, int top, String... pattern) {
        for (int y = 0; y < pattern.length; y++) {
            for (int x = 0; x < pattern[y].length(); x++) {
                if (pattern[y].charAt(x) == '#') gray[(top + y) * WIDTH + left + x] = 0;
            }
        }
    }
}
