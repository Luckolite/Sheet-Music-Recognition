// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class RuleOverlaidFingeringsTest {
    private static final int WIDTH = 240;
    private static final int HEIGHT = 230;
    private static final int GAP = 16;
    private static final int FLOOR = 184;
    private static final PrintedSlurOwners.GeometryHead HEAD =
            new PrintedSlurOwners.GeometryHead(0, 100, 190, 0, 1);

    @Test
    public void upperFingeringNeedsItsGlyphRuleAndStemInBothRasters() {
        byte[] full = scene(true);
        assertEquals(1, read(full, full));

        byte[] withoutFingering = scene(false);
        byte[] withoutRuleFlank = full.clone();
        fill(withoutRuleFlank, 82, 92, 119, 121, 255);
        byte[] severedStem = full.clone();
        fill(severedStem, 105, 111, 164, 175, 255);
        for (byte[] missing : List.of(withoutFingering, withoutRuleFlank, severedStem)) {
            assertEquals(-1, read(full, missing));
            assertEquals(-1, read(missing, full));
        }
    }

    @Test
    public void lowerTwoNeedsTwoIndependentShapesAndAnOwnedStem() {
        byte[] raw = two(0);
        List<float[]> references = List.of(
                MatchedDetachedThreeShape.vector(raw, WIDTH, 87, 174, 97, 192),
                MatchedDetachedThreeShape.vector(two(1), WIDTH, 88, 174, 98, 192));
        for (int y = FLOOR - 4 * GAP; y <= FLOOR; y += GAP)
            fill(raw, 56, 185, y - 1, y + 1, 0);
        fill(raw, 91, 93, 115, 157, 0);
        fill(raw, 71, 153, 153, 157, 0);
        fill(raw, 92, 107, 111, 119, 0);
        var head = new PrintedSlurOwners.GeometryHead(0, 100, 115, 0, 1);
        var track = StaffPitchTrack.fromVerifiedSamples(
                List.of(new float[] {60, FLOOR, GAP}, new float[] {180, FLOOR, GAP}));
        assertTrue(RuleOverlaidFingerings.belowTwo(raw, raw, WIDTH, HEIGHT, head, track, FLOOR, GAP,
                new long[] {100_000}, references, references));
        assertFalse(RuleOverlaidFingerings.belowTwo(raw, raw, WIDTH, HEIGHT, head, track, FLOOR, GAP,
                new long[] {100_000}, references.subList(0, 1), references));
        byte[] broken = raw.clone();
        fill(broken, 90, 94, 139, 149, 255);
        assertFalse(RuleOverlaidFingerings.belowTwo(raw, broken, WIDTH, HEIGHT, head, track, FLOOR, GAP,
                new long[] {100_000}, references, references));
    }

    private static byte[] two(int shift) {
        String[] rows = {
                "   ######   ", "  ########  ", " ########## ", " ###    ####", " ##      ###",
                "         ###", "         ###", "        ####", "        ### ", "       #### ",
                "      ####  ", "     ####   ", "    ####    ", "   ####     ", "  ####     #",
                " ####     ##", " ###########", " ###########", " ########## "
        };
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = 0; y < rows.length; y++)
            for (int x = 0; x < rows[y].length(); x++)
                if (rows[y].charAt(x) == '#') gray[(174 + y) * WIDTH + 86 + shift + x] = 0;
        return gray;
    }

    private static int read(byte[] raw, byte[] normalized) {
        var track = StaffPitchTrack.fromVerifiedSamples(
                List.of(new float[] {60, FLOOR, GAP}, new float[] {180, FLOOR, GAP}));
        return RuleOverlaidFingerings.above(raw, normalized, WIDTH, HEIGHT, HEAD, track, FLOOR, GAP,
                new long[] {20_000}, List.of(), List.of());
    }

    private static byte[] scene(boolean numeral) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = FLOOR - 4 * GAP; y <= FLOOR; y += GAP)
            fill(gray, 56, 185, y - 1, y + 1, 0);
        fill(gray, 107, 109, 144, 190, 0);
        fill(gray, 74, 169, 144, 148, 0);
        fill(gray, 91, 107, 187, 193, 0);
        if (numeral) {
            // Draw a serif, stem, and foot across the top staff rule.
            for (int y = 111; y <= 115; y++) fill(gray, 107 - (y - 111), 109, y, y, 0);
            fill(gray, 104, 109, 116, 116, 0);
            fill(gray, 106, 109, 117, 126, 0);
            fill(gray, 105, 110, 127, 127, 0);
            fill(gray, 104, 111, 128, 128, 0);
            fill(gray, 103, 112, 129, 130, 0);
        }
        return gray;
    }

    private static void fill(byte[] gray, int left, int right, int top, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
    }
}
