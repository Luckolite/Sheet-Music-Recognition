// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original component/rule fixtures for semantic upper-loop fragmentation. */
public class SplitHeaderClefRecognitionTest {
    private static final int W = 600, H = 320;

    @Test
    public void openingTrebleNeedsItsPrintedLoopAndConnectedWaist() throws Exception {
        assertNotNull(splitWaist(true, true));
        assertNull(splitWaist(false, true));
        assertNull(splitWaist(true, false));
    }

    private static Object splitWaist(boolean closedLoop, boolean connected) throws Exception {
        var raw = new byte[W * H];
        Arrays.fill(raw, (byte) 255);
        for (int yy = 71; yy <= 109; yy++)
            for (int xx = 45; xx <= 65; xx++) {
                double r = Math.pow((xx - 55) / 10., 2) + Math.pow((yy - 90) / 18., 2);
                if (r >= .45 && r <= 1) raw[yy * W + xx] = 0;
            }
        if (!closedLoop)
            for (int yy = 71; yy <= 109; yy++)
                Arrays.fill(raw, yy * W + 44, yy * W + 54, (byte) 255);
        for (int yy = 116; yy <= 162; yy++)
            for (int xx = 24; xx <= 75; xx++)
                if (Math.pow((xx - 50) / 26., 2) + Math.pow((yy - 139) / 23., 2) <= 1)
                    raw[yy * W + xx] = 0;
        for (int yy = 141; yy <= 194; yy++)
            Arrays.fill(raw, yy * W + 50, yy * W + 59, (byte) 0);
        for (int yy = 190; yy <= 194; yy++)
            Arrays.fill(raw, yy * W + 37, yy * W + 62, (byte) 0);
        if (connected)
            for (int yy = 104; yy <= 123; yy++)
                Arrays.fill(raw, yy * W + 51, yy * W + 60, (byte) 0);
        for (int y = 100; y <= 164; y += 16)
            Arrays.fill(raw, y * W + 13, y * W + 221, (byte) 0);
        var o = OmrScoreInterpreter.class;
        var component = Class.forName(o.getName() + "$Component");
        var candidate = Class.forName(o.getName() + "$AccidentalCandidate");
        var staff = Class.forName(o.getName() + "$Staff");
        var make = component.getDeclaredConstructors()[0];
        var model = candidate.getDeclaredConstructors()[0];
        var frame = staff.getDeclaredConstructor(float.class, float.class, float.class);
        var read = o.getDeclaredMethod("openingTrebleWithSplitWaist", List.class, byte[].class,
                int.class, int.class, staff, float.class, component);
        for (var m : List.of(make, model, frame, read)) m.setAccessible(true);
        var high = make.newInstance(310, 45, 65, 71, 109, 55f, 90f);
        var waist = make.newInstance(770, 24, 75, 116, 162, 50f, 139f);
        var low = make.newInstance(590, 37, 62, 141, 194, 53f, 168f);
        var rival = make.newInstance(675, 145, 179, 86, 161, 163f, 127f);
        var pieces = List.of(model.newInstance(high, OmrMeasurePostProcessor.SYMBOL),
                model.newInstance(waist, OmrMeasurePostProcessor.SYMBOL),
                model.newInstance(low, OmrMeasurePostProcessor.CLEF_OR_KEY));
        return read.invoke(null, pieces, raw, W, H, frame.newInstance(100f, 164f, 16f), 202f, rival);
    }

    private int read(
            boolean track,
            int lowerLeft,
            int lowerRight,
            int lowerTop,
            int lowerBottom,
            int upperLeft,
            int upperRight,
            int upperTop,
            int upperBottom,
            int rules,
            int noteX,
            boolean symbol,
            boolean wideComplete)
            throws Exception {
        return read(
                track,
                lowerLeft,
                lowerRight,
                lowerTop,
                lowerBottom,
                upperLeft,
                upperRight,
                upperTop,
                upperBottom,
                rules,
                noteX,
                symbol,
                wideComplete,
                false,
                false);
    }

    private int read(
            boolean track,
            int lowerLeft,
            int lowerRight,
            int lowerTop,
            int lowerBottom,
            int upperLeft,
            int upperRight,
            int upperTop,
            int upperBottom,
            int rules,
            int noteX,
            boolean symbol,
            boolean wideComplete,
            boolean eight,
            boolean neighbor)
            throws Exception {
        Class<?> root = OmrScoreInterpreter.class;
        Class<?> hc = Class.forName(root.getName() + "$Component"),
                sc = Class.forName(root.getName() + "$Staff"),
                dc = Class.forName(root.getName() + "$DetectedNote");
        var ch = hc.getDeclaredConstructors()[0];
        ch.setAccessible(true);
        var cs = sc.getDeclaredConstructors()[0];
        cs.setAccessible(true);
        var cd = dc.getDeclaredConstructors()[0];
        cd.setAccessible(true);
        Object staff = cs.newInstance(120f, 168f, 12f);
        if (track) {
            var f = sc.getDeclaredField("pitchTrack");
            f.setAccessible(true);
            f.set(staff, StaffPitchTrack.linear(W, 168, 12, 0));
        }
        Object lower =
                ch.newInstance(
                        900,
                        lowerLeft,
                        lowerRight,
                        lowerTop,
                        lowerBottom,
                        (lowerLeft + lowerRight) * .5f,
                        (lowerTop + lowerBottom) * .5f);
        List<Object> glyphs = new ArrayList<>();
        glyphs.add(lower);
        byte[] gray = new byte[W * H], labels = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        if (!wideComplete) {
            if (symbol)
                for (int y = upperTop; y <= upperBottom; y++)
                    for (int x = upperLeft; x <= upperRight; x++)
                        labels[y * W + x] = OmrMeasurePostProcessor.SYMBOL;
            else
                glyphs.add(
                        ch.newInstance(
                                250,
                                upperLeft,
                                upperRight,
                                upperTop,
                                upperBottom,
                                (upperLeft + upperRight) * .5f,
                                (upperTop + upperBottom) * .5f));
        }
        for (int line = 0; line < rules; line++)
            for (int x = 0; x < W; x++) gray[(120 + line * 12) * W + x] = (byte) 90;
        Object head = ch.newInstance(100, noteX - 6, noteX + 6, 145, 154, (float) noteX, 150f);
        var event = new ScoreNoteEvent(0, .5f, 3, 0, 1, 150f / H, false, 0, 0);
        Object note = cd.newInstance(event, head, 12f);
        if (eight)
            for (int y = 80; y <= 96; y++)
                for (int x = 108; x <= 116; x++)
                    if (x == 108 || x == 116 || y == 80 || y == 88 || y == 96) gray[y * W + x] = 0;
        if (neighbor)
            for (int y = 80; y <= 96; y++) for (int x = 97; x <= 98; x++) gray[y * W + x] = 0;
        byte[] g0 = gray.clone(), l0 = labels.clone();
        var m =
                root.getDeclaredMethod(
                        "applyPrintedClefs",
                        List.class,
                        List.class,
                        List.class,
                        byte[].class,
                        byte[].class,
                        int.class,
                        int.class);
        m.setAccessible(true);
        var result =
                (List<?>) m.invoke(null, List.of(note), List.of(staff), glyphs, labels, gray, W, H);
        assertArrayEquals(l0, labels);
        assertArrayEquals(g0, gray);
        var f = dc.getDeclaredField("event");
        f.setAccessible(true);
        var actual = (ScoreNoteEvent) f.get(result.get(0));
        assertEquals(event.withClef(actual.clefBottomDiatonic()), actual);
        return actual.clefBottomDiatonic();
    }

    private int split(boolean track, int rules, boolean symbol, int noteX) throws Exception {
        return read(track, 90, 130, 122, 188, 108, 122, 99, 131, rules, noteX, symbol, false);
    }

    @Test
    public void splitHeaderRetainsItsPrintedOctaveDigit() throws Exception {
        assertEquals(
                37,
                read(true, 90, 130, 122, 188, 108, 122, 99, 131, 5, 250, true, false, true, false));
    }

    @Test
    public void multiDigitSystemNumberDoesNotRaiseTheSplitClef() throws Exception {
        assertEquals(
                30,
                read(true, 90, 130, 122, 188, 108, 122, 99, 131, 5, 250, true, false, true, true));
    }

    @Test
    public void symbolUpperLoopAndSemanticLowerCurlUseTheExistingHeaderProof() throws Exception {
        assertEquals(30, split(true, 5, true, 250));
    }

    @Test
    public void threeProvedRulesAreEnoughForTheCompleteSplitHeader() throws Exception {
        assertEquals(30, split(true, 3, true, 250));
    }

    @Test
    public void twoRulesCannotProveASymbolHeader() throws Exception {
        assertEquals(-1, split(true, 2, true, 250));
    }

    @Test
    public void missingRawRulesCannotCreateASymbolClef() throws Exception {
        assertEquals(-1, split(true, 0, true, 250));
    }

    @Test
    public void missingTrackCannotInventTheHeaderFrame() throws Exception {
        assertEquals(-1, split(false, 5, true, 250));
    }

    @Test
    public void symbolToTheRightCannotJoinTheLowerBody() throws Exception {
        assertEquals(-1, read(true, 90, 130, 122, 188, 140, 153, 99, 131, 5, 250, true, false));
    }

    @Test
    public void aNumberAboveTheClefCannotBecomeItsUpperLoop() throws Exception {
        assertEquals(-1, read(true, 90, 130, 122, 188, 108, 122, 65, 90, 5, 250, true, false));
    }

    @Test
    public void aDisconnectedTipCannotBecomeACompleteHeader() throws Exception {
        assertEquals(-1, read(true, 90, 130, 122, 188, 108, 122, 99, 112, 5, 250, true, false));
    }

    @Test
    public void aTallLetterCannotBuildACompleteHeader() throws Exception {
        assertEquals(-1, read(true, 90, 130, 122, 188, 108, 122, 45, 131, 5, 250, true, false));
    }

    @Test
    public void aShortAccidentalCannotStandInForTheLargeLowerCurl() throws Exception {
        assertEquals(-1, read(true, 90, 130, 145, 166, 108, 122, 99, 131, 5, 250, true, false));
    }

    @Test
    public void anOversizedBodyCannotBeAHeaderClef() throws Exception {
        assertEquals(-1, read(true, 60, 130, 122, 188, 108, 122, 99, 131, 5, 250, true, false));
    }

    @Test
    public void aSymbolClefAfterTheNoteDoesNotApplyRetroactively() throws Exception {
        assertEquals(-1, split(true, 5, true, 100));
    }

    @Test
    public void boundedCompleteWideTrebleMatchesTheHeaderContract() throws Exception {
        assertEquals(30, read(false, 90, 133, 100, 188, 0, 0, 0, 0, 0, 250, false, true));
    }

    @Test
    public void completeTrebleBeyondTheHeaderWidthLimitIsRejected() throws Exception {
        assertEquals(-1, read(false, 90, 140, 100, 188, 0, 0, 0, 0, 0, 250, false, true));
    }

    @Test
    public void completeWideClefAfterTheNoteRemainsLocal() throws Exception {
        assertEquals(-1, read(false, 90, 133, 100, 188, 0, 0, 0, 0, 0, 100, false, true));
    }
}
