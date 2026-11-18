// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original procedural sharp/staff drawings; exercises the production key API directly. */
public class OrderedSharpHeaderConflictTest {
    static final int W = 640, H = 260;

    static class Page {
        final byte[] labels = new byte[W * H], gray = new byte[W * H];

        Page(int secondPitch, boolean second, boolean clef, boolean narrow) {
            Arrays.fill(gray, (byte) 255);
            for (int y = 100; y <= 164; y += 16) for (int x = 10; x < 630; x++) ink(x, y, 4);
            if (clef) for (int y = 70; y <= 180; y++) for (int x = 30; x <= 55; x++) ink(x, y, 3);
            sharp(80, 100, false);
            if (second) sharp(101, secondPitch, narrow);
        }

        void ink(int x, int y, int label) {
            labels[y * W + x] = (byte) label;
            gray[y * W + x] = 0;
        }

        void sharp(int x, int y, boolean narrow) {
            for (int xx :
                    narrow
                            ? new int[] {x - 5, x - 4, x, x + 1}
                            : new int[] {x - 5, x - 4, x + 3, x + 4})
                for (int yy = y - 22; yy <= y + 22; yy++) ink(xx, yy, 3);
            for (int cy : new int[] {y - 5, y + 5})
                for (int yy = narrow ? cy - 2 : cy - 1; yy <= (narrow ? cy + 3 : cy + 1); yy++)
                    for (int xx = x - 8; xx <= x + 7; xx++) ink(xx, yy, 3);
        }

        void natural() {
            for (int y = 110; y <= 141; y++) for (int x = 75; x <= 76; x++) ink(x, y, 3);
            for (int y = 123; y <= 154; y++) for (int x = 84; x <= 85; x++) ink(x, y, 3);
            for (int cy : new int[] {124, 140})
                for (int y = cy - 1; y <= cy + 1; y++) for (int x = 76; x <= 84; x++) ink(x, y, 3);
        }

        void clearSymbols() {
            for (int y = 50; y < 190; y++)
                for (int x = 60; x < 140; x++) {
                    labels[y * W + x] =
                            (byte) (y >= 100 && y <= 164 && (y - 100) % 16 == 0 ? 4 : 0);
                    gray[y * W + x] = (byte) (labels[y * W + x] == 4 ? 0 : 255);
                }
        }

        void flat(int x, int y) {
            for (int yy = y - 28; yy <= y + 7; yy++)
                for (int xx = x; xx <= x + 2; xx++) ink(xx, yy, 3);
            for (int yy = y - 7; yy <= y + 7; yy++)
                for (int xx = x + 2; xx <= x + 12; xx++) {
                    double radius = Math.pow((xx - x - 3) / 9d, 2) + Math.pow((yy - y) / 7d, 2);
                    if (radius <= 1 && radius >= .35) ink(xx, yy, 3);
                }
        }

        @SuppressWarnings("unchecked")
        List<ScoreKeyChange> keys(boolean clipAtFirstSharp) throws Exception {
            Class<?> o = OmrScoreInterpreter.class;
            Constructor<?> sc = Class.forName(o.getName() + "$Staff").getDeclaredConstructors()[0];
            sc.setAccessible(true);
            Object staff = sc.newInstance(100f, 164f, 16f);
            Constructor<?> ac =
                    Class.forName(o.getName() + "$AccidentalCandidate")
                            .getDeclaredConstructors()[0];
            ac.setAccessible(true);
            List<Object> candidates = new ArrayList<>();
            Method components =
                    o.getDeclaredMethod(
                            "findComponents", byte[].class, int.class, int.class, byte.class);
            components.setAccessible(true);
            for (Object c : (List<?>) components.invoke(null, labels, W, H, (byte) 3))
                candidates.add(ac.newInstance(c, (byte) 3));
            List<Object> heads = new ArrayList<>();
            if (clipAtFirstSharp) {
                Constructor<?> c =
                        Class.forName(o.getName() + "$Component").getDeclaredConstructors()[0];
                c.setAccessible(true);
                heads.add(c.newInstance(100, 70, 86, 117, 129, 78f, 123f));
            }
            return (List<ScoreKeyChange>)
                    method("detectKeyChangesWithHeaders")
                            .invoke(
                                    null,
                                    labels,
                                    gray,
                                    W,
                                    H,
                                    List.of(
                                            new MeasureRegion(
                                                    180f / W, 620f / W, 65f / H, 190f / H)),
                                    List.of(staff),
                                    candidates,
                                    heads,
                                    new ArrayList<>());
        }
    }

    @Test
    public void darkSharpCrossbarsMustExtendAcrossTheFadedStaff() throws Exception {
        Page page = new Page(0, false, false, false);
        page.clearSymbols();
        for (int rule = 100; rule <= 164; rule += 16)
            for (int y = rule - 1; y <= rule + 1; y++)
                Arrays.fill(page.gray, y * W + 10, y * W + 630, (byte) 120);
        for (int y = 100; y <= 148; y++)
            for (int x : new int[] {75, 76, 83, 84}) page.ink(x, y, 3);
        for (int rule : new int[] {116, 132})
            for (int y = rule - 1; y <= rule + 1; y++)
                for (int x = 72; x <= 87; x++) page.ink(x, y, 3);
        Class<?> omr = OmrScoreInterpreter.class;
        Class<?> component = Class.forName(omr.getName() + "$Component");
        Class<?> staffClass = Class.forName(omr.getName() + "$Staff");
        Constructor<?> glyph = component.getDeclaredConstructors()[0];
        Constructor<?> staffMaker = staffClass.getDeclaredConstructors()[0];
        glyph.setAccessible(true);
        staffMaker.setAccessible(true);
        Object owner = glyph.newInstance(268, 72, 87, 100, 148, 79.5f, 124f);
        Object staff = staffMaker.newInstance(100f, 164f, 16f);
        Method pitch = omr.getDeclaredMethod("staffOverlaidSharpPitch", byte[].class,
                int.class, int.class, component, staffClass, staffClass, int.class);
        pitch.setAccessible(true);
        assertEquals(124f, (float) pitch.invoke(null, page.gray, W, H, owner, staff, staff, 192), .01f);

        // Leave the full staff stripe and both shafts in place beneath the erased bar.
        for (int y = 115; y <= 117; y++) {
            Arrays.fill(page.gray, y * W + 72, y * W + 88, (byte) 120);
            for (int x : new int[] {75, 76, 83, 84}) page.ink(x, y, 3);
        }
        assertTrue(Float.isNaN((float) pitch.invoke(null, page.gray, W, H, owner, staff, staff, 192)));
    }

    static Method method(String name) {
        Method m =
                Arrays.stream(OmrScoreInterpreter.class.getDeclaredMethods())
                        .filter(
                                x ->
                                        x.getName().equals(name)
                                                && (!name.equals("detectKeyChangesWithHeaders")
                                                        || x.getParameterCount() == 9))
                        .findFirst()
                        .orElseThrow();
        m.setAccessible(true);
        return m;
    }

    @Test
    public void orderedCompleteSharpCohortResolvesFlatBowlConflict() throws Exception {
        assertEquals(
                List.of(new ScoreKeyChange(0, 2)), new Page(121, true, true, true).keys(false));
    }

    @Test
    public void intactTwoSharpHeaderIsPreserved() throws Exception {
        assertEquals(
                List.of(new ScoreKeyChange(0, 2)), new Page(124, true, true, false).keys(false));
    }

    @Test
    public void neighboringPrintedPhasesArePreserved() throws Exception {
        for (int phase : new int[] {120, 122, 123, 124, 125, 126, 127, 128})
            assertEquals(
                    "phase " + phase,
                    List.of(new ScoreKeyChange(0, 2)),
                    new Page(phase, true, true, true).keys(false));
    }

    @Test
    public void plainSingleSharpRemainsSingle() throws Exception {
        assertEquals(
                List.of(new ScoreKeyChange(0, 1)), new Page(121, false, true, false).keys(false));
    }

    @Test
    public void noCompleteClefDoesNotAdmitLeftHeader() throws Exception {
        assertEquals(List.of(), new Page(121, true, false, true).keys(false));
    }

    @Test
    public void headBeforeHeaderPreventsBorrowedSignature() throws Exception {
        assertEquals(List.of(), new Page(121, true, true, true).keys(true));
    }

    @Test
    public void headerInputsRemainUnchanged() throws Exception {
        Page p = new Page(121, true, true, true);
        byte[] l = p.labels.clone(), g = p.gray.clone();
        p.keys(false);
        assertArrayEquals(l, p.labels);
        assertArrayEquals(g, p.gray);
    }

    @Test
    public void orderedGenuineFlatHeaderRemainsFlat() throws Exception {
        Page p = new Page(121, false, true, false);
        p.clearSymbols();
        p.flat(80, 132);
        p.flat(101, 108);
        assertEquals(List.of(new ScoreKeyChange(0, -2)), p.keys(false));
    }

    @Test
    public void naturalHeaderWithoutPriorRemainsUnqualified() throws Exception {
        Page p = new Page(121, false, true, false);
        p.clearSymbols();
        p.natural();
        assertEquals(List.of(), p.keys(false));
    }

    @Test
    public void loneConflictingGlyphDoesNotProveSharpHeader() throws Exception {
        Page p = new Page(121, false, true, false);
        p.clearSymbols();
        p.sharp(101, 121, true);
        assertEquals(List.of(), p.keys(false));
    }

    @Test
    public void wrongPitchOrderDoesNotResolveConflict() throws Exception {
        assertEquals(List.of(), new Page(140, true, true, true).keys(false));
    }

    @Test
    public void completeDenseSharpBodiesCanResolveOneFlatLabel() throws Exception {
        assertTrue(denseBodyOrder(0, false));
        assertFalse(denseBodyOrder(8, false));
        assertFalse(denseBodyOrder(0, true));
    }

    private static boolean denseBodyOrder(int movedSecond, boolean removeFifth) throws Exception {
        var o = OmrScoreInterpreter.class;
        var component = Class.forName(o.getName() + "$Component");
        var glyph = Class.forName(o.getName() + "$SignatureGlyph");
        var staffType = Class.forName(o.getName() + "$Staff");
        var body = component.getDeclaredConstructors()[0];
        var signature = glyph.getDeclaredConstructor(float.class, int.class, float.class, component);
        var staff = staffType.getDeclaredConstructor(float.class, float.class, float.class);
        var corroborate = o.getDeclaredMethod("corroboratePrintedSharpHeader",
                List.class, List.class, component, staffType, boolean.class, float.class, float.class);
        for (var member : List.of(body, signature, staff, corroborate)) member.setAccessible(true);
        var semantic = new ArrayList<>();
        var paper = new ArrayList<>();
        int[] centers = {100,124,92,116,140};
        for (int i = 0; i < centers.length; i++) {
            float x = 78f + i * 18;
            int middle = centers[i] + (i == 1 ? movedSecond : 0);
            Object printed = body.newInstance(440, (int) x - 8, (int) x + 8, middle - 21,
                    middle + 21, x, (float) middle);
            semantic.add(signature.newInstance(x, i == 1 ? -1 : 1, Float.NaN, null));
            if (i != 4 || !removeFifth) {
                float selectedCrossbars = centers[i] + (i == 1 ? 5 : i == 4 ? -6 : 0);
                paper.add(signature.newInstance(x, 1, selectedCrossbars, printed));
            }
        }
        var clef = body.newInstance(1500, 32, 55, 69, 187, 44f, 134f);
        return (boolean) corroborate.invoke(null, semantic, paper, clef,
                staff.newInstance(100f, 164f, 16f), true, 57f, 176f);
    }

    @Test
    public void naturalPrefixPreventsSharpCohortOverride() throws Exception {
        Page p = new Page(121, false, true, false);
        p.clearSymbols();
        p.natural();
        p.sharp(108, 100, false);
        p.sharp(129, 121, true);
        assertEquals(List.of(), p.keys(false));
    }

    @Test
    public void distantStaffCannotCompleteHeaderCohort() throws Exception {
        assertEquals(
                List.of(new ScoreKeyChange(0, 1)), new Page(210, true, true, true).keys(false));
    }

    @Test
    public void repeatedSharpHeaderRequiresTheCompleteOrderedSourceInk() {
        var complete = new Page(124, true, true, false);
        assertEquals(56, OmrScoreInterpreter.inheritedSharpHeaderStart(
                complete.gray, complete.labels, W, H, 10, 120, 164, 16, 2));
        assertEquals(-1, OmrScoreInterpreter.inheritedSharpHeaderStart(
                complete.gray, complete.labels, W, H, 10, 120, 164, 16, 3));
        var displaced = new Page(112, true, true, false);
        assertEquals(-1, OmrScoreInterpreter.inheritedSharpHeaderStart(
                displaced.gray, displaced.labels, W, H, 10, 120, 164, 16, 2));
        for (int y = 101; y <= 147; y++)
            if (Math.floorMod(y - 100, 16) != 0)
                Arrays.fill(complete.gray, y * W + 93, y * W + 110, (byte) 255);
        assertEquals(-1, OmrScoreInterpreter.inheritedSharpHeaderStart(
                complete.gray, complete.labels, W, H, 10, 120, 164, 16, 2));
    }

    @Test
    public void clippedHeaderSharpSpineNeedsConnectedSourceInk() {
        var page = new Page(124, true, true, false);
        for (int y = 102; y < 118; y++)
            for (int x = 104; x <= 105; x++) page.labels[y * W + x] = 0;

        assertEquals(56, OmrScoreInterpreter.inheritedSharpHeaderStart(
                page.gray, page.labels, W, H, 10, 120, 164, 16, 2));

        for (int y = 102; y < 118; y++)
            for (int x = 104; x <= 105; x++) page.gray[y * W + x] = (byte) (y == 116 ? 0 : 255);
        assertEquals(-1, OmrScoreInterpreter.inheritedSharpHeaderStart(
                page.gray, page.labels, W, H, 10, 120, 164, 16, 2));
    }
}
