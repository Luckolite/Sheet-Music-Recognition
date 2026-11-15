// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ClosedEighthTripletBarsTest {
    private static final int WIDTH = 500, HEIGHT = 230;
    private static final int LEFT = 40, RIGHT = 449, TOP = 46, BOTTOM = 177;
    private final byte[] gray = new byte[WIDTH * HEIGHT];
    private final List<MeasureRegion> bars = List.of(new MeasureRegion(
            LEFT / (float) WIDTH, RIGHT / (float) WIDTH, TOP / (float) HEIGHT, BOTTOM / (float) HEIGHT));
    private final List<ScoreNoteEvent> notes = new ArrayList<>();

    public ClosedEighthTripletBarsTest() {
        Arrays.fill(gray, (byte) 255);
        for (int y = 80; y <= 144; y += 16) fill(LEFT, RIGHT, y, y+1, 0);
        fill(LEFT, LEFT+2, 80, 144, 0);
        fill(RIGHT-1, RIGHT+1, 80, 144, 0);
        for (int i = 0; i < 12; i++) {
            int x = 67 + i * 32;
            int stem = i / 3 % 2 == 0 ? 1 : -1;
            int y = stem > 0 ? 112 + 8 * (i % 3) : 112 - 8 * (i % 3);
            int tip = stem > 0 ? 60 : 162;
            int shaft = x + stem * 9;
            fill(x-5, x+5, y-4, y+4, 0);
            fill(shaft-1, shaft+1, Math.min(tip, y), Math.max(tip, y), 0);
            if (i % 3 == 0) fill(shaft, shaft+65, tip-3, tip+3, 0);
            notes.add(new ScoreNoteEvent(0, (x-LEFT) / (float) (RIGHT-LEFT),
                    (144-y)/8, 0, 1, y / (float) HEIGHT, false, 0, 1, 2, 0, 1,
                    0, 0, 30, false, 0, false, 0, 0, 1, stem,
                    ScoreNoteEvent.Kind.PITCHED, ScoreNoteEvent.VisualSize.FULL, 0, null));
        }
    }

    @Test
    public void fourGroupsFillClosedCommonTimeBar() {
        var interpreted = infer(4);
        assertEquals(12, interpreted.stream().filter(n -> n.tupletDivisor() == 3
                && n.tupletNormalNotes() == 2).count());
    }

    @Test
    public void differentMeterAndOpenBarKeepOrdinaryRhythm() {
        assertSame(notes, infer(6));
        fill(RIGHT-2, RIGHT+1, 82, 142, 255);
        assertSame(notes, infer(4));
    }

    @Test
    public void missingBeamKeepsOrdinaryRhythm() {
        fill(109, 127, 55, 65, 255);
        assertSame(notes, infer(4));
    }

    @Test
    public void sixGroupsFillSixQuarterBarAndKeepRealDots() {
        int width = 740, height = 230;
        var pixels = new byte[width * height];
        Arrays.fill(pixels, (byte) 255);
        var region =
                List.of(new MeasureRegion(40f / width, 640f / width, 46f / height, 177f / height));
        for (int y = 80; y <= 144; y += 16) ink(pixels, width, 40, 640, y, y + 1, 0);
        ink(pixels, width, 40, 42, 80, 144, 0);
        ink(pixels, width, 639, 641, 80, 144, 0);
        var eighteen = new ArrayList<ScoreNoteEvent>();
        for (int slot = 0; slot < 18; slot++) {
            int x = 67 + slot * 32, stem = slot / 3 % 2 == 0 ? 1 : -1;
            int y = stem > 0 ? 112 + 8 * (slot % 3) : 112 - 8 * (slot % 3);

            int tip = stem > 0 ? 60 : 162;
            int shaft = x + stem * 9;
            ink(pixels, width, x - 5, x + 5, y - 4, y + 4, 0);
            ink(pixels, width, shaft - 1, shaft + 1, Math.min(tip, y), Math.max(tip, y), 0);
            if (slot % 3 == 0) ink(pixels, width, shaft, shaft + 65, tip - 3, tip + 3, 0);
            eighteen.add(
                    new ScoreNoteEvent(
                            0,
                            (x - 40) / 600f,
                            (144 - y) / 8,
                            0,
                            1,
                            y / (float) height,
                            false,
                            0,
                            1,
                            2,
                            0,
                            1,
                            0,
                            0,
                            30,
                            false,
                            0,
                            false,
                            0,
                            0,
                            1,
                            stem,
                            ScoreNoteEvent.Kind.PITCHED,
                            ScoreNoteEvent.VisualSize.FULL,
                            0,
                            null));
        }
        // A short ruled tip tapers from the actual first head.
        ink(pixels, width, 55, 80, 109, 113, 0);
        ink(pixels, width, 81, 84, 109, 112, 0);
        ink(pixels, width, 85, 86, 110, 111, 0);
        var sixFour = List.of(new ScoreMeterChange(0, 6, 4));
        var result =
                WrappedEighthTriplets.closeQuarterBeatBars(
                        eighteen, List.of(), region, sixFour, pixels, width, height);
        assertEquals(18, result.stream().filter(n -> n.tupletDivisor() == 3).count());

        var separated = pixels.clone();
        ink(separated, width, 116, 120, 117, 121, 0);
        assertSame(
                eighteen,
                WrappedEighthTriplets.closeQuarterBeatBars(
                        eighteen, List.of(), region, sixFour, separated, width, height));
        var attached = pixels.clone();
        ink(attached, width, 79, 85, 106, 116, 0);
        assertSame(
                eighteen,
                WrappedEighthTriplets.closeQuarterBeatBars(
                        eighteen, List.of(), region, sixFour, attached, width, height));
    }

    private static void ink(byte[] image, int width, int x0, int x1, int y0, int y1, int value) {
        for (int y = y0; y <= y1; y++)
            Arrays.fill(image, y * width + x0, y * width + x1 + 1, (byte) value);
    }

    @Test
    public void sixTripletsFillOpeningBarWithOwnedMeterAndNoLeftDivider() throws Exception {
        checkOpeningTriplets(false, false);
    }

    @Test
    public void terminalTripletHeadNeedsItsBeamAndIndependentClosingRule() throws Exception {
        checkOpeningTriplets(true, false);
    }

    @Test
    public void sixTripletsFillTheFirstBarOfALaterStaffWithItsOwnMeter() throws Exception {
        checkOpeningTriplets(false, true);
    }

    private static void checkOpeningTriplets(boolean missingBeamLabel, boolean laterStaff)
            throws Exception {
        int width = 840, height = 260;
        int left = 100, right = missingBeamLabel ? 734 : 754;
        int firstLine = 96, lastLine = 160;
        int closingRule = missingBeamLabel ? 741 : right;
        byte[] paper = new byte[width * height], labels = new byte[paper.length];
        Arrays.fill(paper, (byte) 255);
        var meter = new RasterMeterReadingsTest.Page(16);
        meter.digit(6, 0);
        meter.digit(4, 1);
        for (int y = firstLine; y <= lastLine; y++) {
            System.arraycopy(meter.gray, y * meter.w + 120, paper, y * width + 120, 45);
            System.arraycopy(meter.labels, y * meter.w + 120, labels, y * width + 120, 45);
        }
        for (int y = firstLine; y <= lastLine; y += 16) {
            ink(paper, width, 20, 815, y, y + 1, 0);
            Arrays.fill(labels, y * width + 20, y * width + 816, OmrMeasurePostProcessor.STAFF);
        }
        ink(paper, width, closingRule - 1, closingRule + 1, firstLine, lastLine, 0);
        var opening = new MeasureRegion(left / (float) width, right / (float) width,
                62f / height, 193f / height);
        int nextLeft = missingBeamLabel ? 750 : 770;
        var following = new MeasureRegion(nextLeft / (float) width, 815f / width,
                62f / height, 193f / height);
        var score = new ArrayList<ScoreNoteEvent>();
        for (int slot = 0; slot < 18; slot++) {
            int x = 180 + slot * 32, stem = slot / 3 % 2 == 0 ? 1 : -1;
            int y = stem > 0 ? 128 + 8 * (slot % 3) : 128 - 8 * (slot % 3);
            int tip = stem > 0 ? 76 : 178;
            int shaft = x + stem * 9;
            ink(paper, width, x - 5, x + 5, y - 4, y + 4, 0);
            ink(paper, width, shaft - 1, shaft + 1, Math.min(tip, y), Math.max(tip, y), 0);
            if (slot % 3 == 0) ink(paper, width, shaft, shaft + 65, tip - 3, tip + 3, 0);
            boolean quarterAlias = missingBeamLabel && slot == 17;
            score.add(
                    new ScoreNoteEvent(
                            laterStaff ? 1 : 0,
                            (x - left) / (float) (right - left),
                            (160 - y) / 8,
                            0,
                            1,
                            y / (float) height,
                            false,
                            0,
                            quarterAlias ? 0 : 1,
                            2,
                            quarterAlias ? ScoreNoteEvent.DURATION_QUARTER : 0,
                            1,
                            0,
                            0,
                            30,
                            false,
                            0,
                            false,
                            0,
                            0,
                            1,
                            stem,
                            ScoreNoteEvent.Kind.PITCHED,
                            ScoreNoteEvent.VisualSize.FULL,
                            0,
                            null));
        }
        var precedingStaff =
                new MeasureRegion(left / (float) width, right / (float) width, .03f, .15f);
        var bars =
                laterStaff
                        ? List.of(precedingStaff, opening, following)
                        : List.of(opening, following);
        var meters = List.of(new ScoreMeterChange(laterStaff ? 1 : 0, 6, 4));
        assertEquals(score.stream()
                        .map(note -> (note.beamCount() == 0
                                ? ClosingTripletRhythm.singleBeam(note) : note).withTupletRatio(3, 2))
                        .toList(),
                WrappedEighthTriplets.closeQuarterBeatBars(
                        score, List.of(), bars, meters, paper, width, height, labels));
        if (missingBeamLabel) {
            byte[] detached = paper.clone();
            ink(detached, width, 693, 705, 173, 184, 255);
            assertSame(score, WrappedEighthTriplets.closeQuarterBeatBars(
                    score, List.of(), bars, meters, detached, width, height, labels));
            byte[] open = paper.clone();
            ink(open, width, closingRule - 1, closingRule + 1, firstLine, lastLine, 255);
            assertSame(score, WrappedEighthTriplets.closeQuarterBeatBars(
                    score, List.of(), bars, meters, open, width, height, labels));
        }
        for (int y = firstLine; y <= lastLine; y++) {
            if ((y - firstLine) % 16 < 2) continue;
            ink(paper, width, 120, 164, y, y, 255);
            Arrays.fill(labels, y * width + 120, y * width + 165, (byte) 0);
        }
        assertSame(score, WrappedEighthTriplets.closeQuarterBeatBars(
                score, List.of(), bars, meters, paper, width, height, labels));
    }

    private List<ScoreNoteEvent> infer(int numerator) {
        return WrappedEighthTriplets.closeQuarterBeatBars(
                notes,
                List.of(),
                bars,
                List.of(new ScoreMeterChange(0, numerator, 4)),
                gray,
                WIDTH,
                HEIGHT);
    }

    private void fill(int x0, int x1, int y0, int y1, int value) {
        for (int y = y0; y <= y1; y++)
            Arrays.fill(gray, y*WIDTH+x0, y*WIDTH+x1+1, (byte) value);
    }
}
