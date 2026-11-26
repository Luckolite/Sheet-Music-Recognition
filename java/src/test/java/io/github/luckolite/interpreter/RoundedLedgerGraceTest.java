// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original rounded grace ellipses, short stems, beams and ledger strokes. */
public class RoundedLedgerGraceTest {
    static final int W = 480, H = 360, G = 14;
    static final List<MeasureRegion> M = List.of(new MeasureRegion(.025f, .975f, .05f, .92f));

    static class Page {
        final byte[] labels = new byte[W * H], gray = new byte[W * H];

        Page(
                boolean pair,
                boolean largePrincipal,
                boolean shortStem,
                boolean beams,
                boolean own,
                boolean bilateral) {
            Arrays.fill(gray, (byte) 255);
            for (int y = 160; y <= 216; y += G)
                for (int x = 12; x <= 467; x++) {
                    pixel(x, y, 4);
                    pixel(x, y + 1, 4);
                }
            if (own) rule(189, 211, 118);
            rule(bilateral ? 191 : 200, bilateral ? 209 : 218, 132);
            if (pair) {
                rule(211, 233, 118);
                rule(211, 233, 132);
            }
            rule(233, 267, 132);
            rule(233, 267, 146);
            head(200, 118, 7, 6);
            if (pair) head(222, 125, 7, 6);
            head(250, 132, largePrincipal ? 11 : 7, largePrincipal ? 8 : 6);
            int length = shortStem ? 30 : 56;
            stem(207, 118 - length, 118);
            if (pair) stem(229, 125 - length, 125);
            if (beams)
                for (int x = 207; x <= 229; x++)
                    for (int band : new int[] {0, 6})
                        for (int dy = 0; dy < 3; dy++)
                            pixel(x, 118 - length + Math.round((x - 207) * 7f / 22) + band + dy, 1);
            stem(239, 132, 180);
            head(350, 188, 11, 8);
            stem(361, 140, 188);
        }

        void pixel(int x, int y, int label) {
            gray[y * W + x] = 0;
            labels[y * W + x] = (byte) label;
        }

        void rule(int left, int right, int y) {
            for (int x = left; x <= right; x++) pixel(x, y, 4);
        }

        void stem(int x, int top, int bottom) {
            for (int y = top; y <= bottom; y++) {
                pixel(x, y, 1);
                pixel(x + 1, y, 1);
            }
        }

        void head(int cx, int cy, int rx, int ry) {
            for (int y = cy - ry; y <= cy + ry; y++)
                for (int x = cx - rx; x <= cx + rx; x++)
                    if (Math.pow((x - cx) / (double) rx, 2) + Math.pow((y - cy) / (double) ry, 2)
                            <= 1) pixel(x, y, 2);
        }

        List<ScoreNoteEvent> notes() {
            return OmrScoreInterpreter.analyze(labels, gray, W, H, M).notes();
        }

        ScoreNoteEvent at(int x) {
            return notes().stream()
                    .filter(
                            n ->
                                    Math.abs(
                                                    (M.get(0).left() + n.positionInMeasure() * .95f)
                                                                    * W
                                                            - x)
                                            < 3)
                    .findFirst()
                    .orElse(null);
        }
    }

    static Page good() {
        return new Page(true, true, true, true, true, true);
    }

    @Test
    public void roundedFirstGraceKeepsItsShortInnerLedger() {
        var n = good().at(200);
        assertNotNull(n);
        assertEquals(14, n.staffStep());
    }

    @Test
    public void bothGracePitchesAreKept() {
        assertEquals(13, good().at(222).staffStep());
    }

    @Test
    public void principalPitchIsUnchanged() {
        assertEquals(12, good().at(250).staffStep());
    }

    @Test
    public void recoveredHeadJoinsTheGracePrefix() {
        assertTrue((good().at(200).articulations() & NoteOrnament.GRACE) != 0);
    }

    @Test
    public void loneRoundedHeadDoesNotGainThePrefixAllowance() {
        assertNull(new Page(false, true, true, true, true, true).at(200));
    }

    @Test
    public void sameSizeFollowingNotesDoNotEstablishAGracePrefix() {
        assertNull(new Page(true, false, true, true, true, true).at(200));
    }

    @Test
    public void longOrdinaryStemsDoNotEstablishAGracePrefix() {
        assertNull(new Page(true, true, false, true, true, true).at(200));
    }

    @Test
    public void unconnectedShortStemsDoNotEstablishAGracePrefix() {
        assertNull(new Page(true, true, true, false, true, true).at(200));
    }

    @Test
    public void headStillNeedsItsOwnLedger() {
        assertNull(new Page(true, true, true, true, false, true).at(200));
    }

    @Test
    public void shortInnerLedgerMustExtendBothSides() {
        assertNull(new Page(true, true, true, true, true, false).at(200));
    }

    @Test
    public void staffRuleCannotReplaceTheSharedBeam() {
        var p = new Page(true, true, true, false, true, true);
        p.stem(229, 88, 95);
        p.rule(180, 260, 88);
        assertNull(p.at(200));
    }

    private static final Class<?> DECODER = OmrScoreInterpreter.class;

    private static Object component(int area, int x, int y, int rx, int ry) throws Exception {
        var type = Class.forName(DECODER.getName() + "$Component");
        var ctor = type.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(area, x - rx, x + rx, y - ry, y + ry, (float) x, (float) y);
    }

    private static byte[] blank() {
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        return gray;
    }

    private static void ink(byte[] gray, int left, int right, int top, int bottom, int tone) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * W + left, y * W + right + 1, (byte) tone);
    }

    @Test
    public void bothPlanesMustRetainEveryShortLedgerOfSlashedGrace() throws Exception {
        var head = component(75, 200, 125, 6, 4);
        var principal = component(210, 235, 142, 11, 7);
        var staffType = Class.forName(DECODER.getName() + "$Staff");
        var staffCtor = staffType.getDeclaredConstructor(float.class, float.class, float.class);
        staffCtor.setAccessible(true);
        var staff = staffCtor.newInstance(160f, 224f, 16f);
        var proof = DECODER.getDeclaredMethod("slashedGraceLedgerChain",
                byte[].class, byte[].class, int.class, int.class, head.getClass(),
                staffType, staffType, List.class, List.class, List.class);
        proof.setAccessible(true);

        byte[] gray = blank();
        ink(gray, 195, 205, 122, 126, 25);
        ink(gray, 206, 207, 90, 125, 25);
        for (int y = 90; y <= 106; y++) {
            int x = 206 + Math.round((y - 90) * 9f / 16);
            ink(gray, x - 1, x + 1, y, y, 25);
        }
        for (int y = 94; y <= 111; y++) ink(gray, 316 - y, 318 - y, y, y, 25);
        ink(gray, 224, 225, 142, 181, 25);
        ink(gray, 192, 208, 130, 130, 155);
        ink(gray, 192, 208, 144, 144, 155);
        byte[] original = gray.clone();
        assertTrue((boolean) proof.invoke(null, gray, original, W, H, head, staff, staff,
                List.of(staff), List.of(head, principal), M));

        ink(original, 192, 208, 130, 130, 255);
        assertFalse((boolean) proof.invoke(null, gray, original, W, H, head, staff, staff,
                List.of(staff), List.of(head, principal), M));

        ink(original, 180, 220, 130, 132, 25);
        assertFalse((boolean) proof.invoke(null, gray, original, W, H, head, staff, staff,
                List.of(staff), List.of(head, principal), M));
    }

    @Test
    public void paleBeamPairNeedsBothAttachedShafts() throws Exception {
        var first = component(75, 200, 125, 6, 4);
        var last = component(75, 225, 117, 6, 4);
        var proof = DECODER.getDeclaredMethod("paleGraceShafts", byte[].class, int.class,
                int.class, first.getClass(), last.getClass(), float.class);
        proof.setAccessible(true);
        byte[] gray = blank();
        ink(gray, 206, 207, 92, 125, 190);
        ink(gray, 231, 232, 92, 117, 190);
        ink(gray, 206, 232, 92, 94, 25);
        ink(gray, 206, 232, 100, 102, 25);
        assertNotNull(proof.invoke(null, gray, W, H, first, last, 16f));
        ink(gray, 231, 232, 105, 110, 255);
        assertNull(proof.invoke(null, gray, W, H, first, last, 16f));
    }
}
