// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original procedural numerals and variable-height horizontal rule bands. */
public class TupletRuleEdgesTest {
    private static final int W = 400, H = 240;
    private static final String[] THREE = {
        "..#######...",
        ".##########.",
        "###......###",
        "####.....###",
        "####.....###",
        "####.....###",
        ".##.....####",
        ".......####.",
        "......####..",
        "....#####...",
        "....#####...",
        "....#####...",
        "......####..",
        ".......####.",
        "##.....####.",
        "###....####.",
        "###....####.",
        "###....####.",
        ".###....###.",
        "..########..",
        "..########..",
        "....####...."
    };

    private static byte[] image(boolean closed, int ink) {
        byte[] g = new byte[W * H];
        Arrays.fill(g, (byte) 245);
        for (int y = 0; y < THREE.length; y++)
            for (int x = 0; x < 12; x++)
                if (THREE[y].charAt(x) == '#' || closed && x < 2 && y > 1 && y < THREE.length - 2)
                    g[(145 + y) * W + 119 + x] = (byte) ink;
        return g;
    }

    private static void box(byte[] g, int l, int t, int r, int b, int ink) {
        for (int y = t; y <= b; y++) for (int x = l; x <= r; x++) g[y * W + x] = (byte) ink;
    }

    private static void rule(byte[] g, int y, int ink) {
        box(g, 60, y, 195, y, ink);
        box(g, 60, y - 1, 126, y - 1, ink);
        box(g, 126, y + 1, 195, y + 1, ink);
    }

    private static List<ScoreNoteEvent> detect(byte[] g) {
        var notes =
                List.of(
                        new ScoreNoteEvent(0, .25f, 2, 0, 1, .4f, false, 0, 1),
                        new ScoreNoteEvent(0, .3125f, 2, 0, 1, .4f, false, 0, 1),
                        new ScoreNoteEvent(0, .375f, 2, 0, 1, .4f, false, 0, 1));
        return TripletRhythmDetector.apply(
                notes, List.of(new MeasureRegion(0, 1, .2f, .6f)), g, W, H);
    }

    private static void assertDivisor(int divisor, byte[] g) {
        for (var n : detect(g)) assertEquals(divisor, n.tupletDivisor());
    }

    private static TupletNumeralInk.Window window(byte[] g) {
        return TupletNumeralInk.ruleEdgesWindow(g, W, H, 100, 150, 96, 96, 12, 165);
    }

    private static int at(TupletNumeralInk.Window w, int x, int y) {
        return w.pixels()[(y - w.top()) * w.width() + x - w.left()] & 255;
    }

    private static boolean staffRuleBracket(byte[] gray) throws Exception {
        Class<?> glyph = Class.forName(TripletRhythmDetector.class.getName() + "$Glyph");
        var glyphConstructor =
                glyph.getDeclaredConstructor(int.class, int.class, int.class, int.class);
        glyphConstructor.setAccessible(true);
        Class<?> staffs = Class.forName(TripletRhythmDetector.class.getName() + "$PrintedStaffs");
        var staffConstructor = staffs.getDeclaredConstructor();
        staffConstructor.setAccessible(true);
        var proof =
                TripletRhythmDetector.class.getDeclaredMethod(
                        "staffRuleBracket",
                        byte[].class,
                        int.class,
                        int.class,
                        MeasureRegion.class,
                        glyph,
                        float.class,
                        float.class,
                        float.class,
                        float.class,
                        staffs);
        proof.setAccessible(true);
        return (boolean)
                proof.invoke(
                        null,
                        gray,
                        W,
                        H,
                        new MeasureRegion(0, 1, .3f, (166 + 2.1f * 16) / H),
                        glyphConstructor.newInstance(119, 145, 130, 166),
                        98f,
                        124.5f,
                        161f,
                        16f,
                        staffConstructor.newInstance());
    }

    @Test
    public void fivePhysicalStaffRulesCanExplainBothApparentBracketArms() throws Exception {
        var staff = image(false, 100);
        for (int row = 102; row <= 166; row += 16) box(staff, 5, row, W - 6, row, 100);
        assertTrue(staffRuleBracket(staff));

        var realBracket = staff.clone();
        box(realBracket, 91, 157, 113, 158, 100);
        box(realBracket, 138, 157, 165, 158, 100);
        box(realBracket, 91, 153, 92, 157, 100);
        box(realBracket, 164, 153, 165, 157, 100);
        assertFalse(staffRuleBracket(realBracket));

        var twoUnownedRules = image(false, 100);
        box(twoUnownedRules, 5, 150, W - 6, 150, 100);
        box(twoUnownedRules, 5, 166, W - 6, 166, 100);
        assertFalse(staffRuleBracket(twoUnownedRules));
    }

    @Test
    public void adjacentRuleEdgesCannotRetainTheFullCrossbar() {
        var g = image(false, 100);
        rule(g, 150, 100);
        assertDivisor(3, g);
    }

    @Test
    public void paleVariableBandRetainsTheOriginalNumeral() {
        var g = image(false, 185);
        rule(g, 150, 185);
        assertDivisor(3, g);
    }

    @Test
    public void thinRuleUnderTheFootRetainsTheTerminalInk() {
        var g = image(false, 100);
        rule(g, 165, 100);
        assertEquals(0, at(window(g), 123, 165));
        assertEquals(0, at(window(g), 123, 166));
    }

    @Test
    public void fullyRemovedStaffRuleNeedsOnlyOneNumeralSearch() {
        var gray = image(false, 100);
        box(gray, 60, 115, 195, 115, 100);
        var normal = TupletNumeralInk.window(gray, W, H, 100, 150, 96, 96, 12, 165);
        assertFalse(normal.hasRuleEdges());
        assertArrayEquals(normal.pixels(), window(gray).pixels());
    }

    @Test
    public void legacyMaskRemainsSeparateFromTheAdditiveRetry() {
        var g = image(false, 100);
        rule(g, 165, 100);
        var old = TupletNumeralInk.window(g, W, H, 100, 150, 96, 96, 12, 165);
        assertTrue(old.hasRuleEdges());
        assertEquals(255, at(old, 123, 165));
        assertEquals(0, at(window(g), 123, 165));
    }

    @Test
    public void closedEightCannotBecomeAnOpenThree() {
        var g = image(true, 100);
        rule(g, 150, 100);
        assertDivisor(1, g);
    }

    @Test
    public void thickBeamIsNotErasedAsAStaffRule() {
        var g = image(false, 100);
        box(g, 60, 147, 195, 153, 100);
        assertEquals(0, at(window(g), 90, 150));
    }

    @Test
    public void shortBracketDoesNotSupplyFullRuleCoverage() {
        var g = image(false, 100);
        box(g, 88, 150, 107, 151, 100);
        assertEquals(0, at(window(g), 95, 150));
    }

    @Test
    public void verticalGlyphContinuationSurvivesMasking() {
        var g = image(false, 100);
        rule(g, 150, 100);
        box(g, 99, 141, 101, 160, 100);
        assertEquals(0, at(window(g), 100, 150));
    }

    @Test
    public void blankPaperDoesNotChange() {
        byte[] g = new byte[W * H];
        Arrays.fill(g, (byte) 245);
        var v = window(g);
        for (byte p : v.pixels()) assertEquals(255, p & 255);
    }

    @Test
    public void sourceRasterRemainsUntouched() {
        var g = image(false, 100);
        rule(g, 150, 100);
        byte[] before = g.clone();
        detect(g);
        assertArrayEquals(before, g);
    }
}
