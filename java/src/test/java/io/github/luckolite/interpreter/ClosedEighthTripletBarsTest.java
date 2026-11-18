// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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
    public void aClosingShaftCanFollowOnlyItsOwnFaintBridge() throws Exception {
        int width = 260, height = 180;
        byte[] paper = new byte[width * height];
        Arrays.fill(paper, (byte) 255);
        var read = WrappedEighthTriplets.class.getDeclaredMethod("faintDownstem",
                byte[].class, int.class, int.class, float.class, float.class, float.class);
        read.setAccessible(true);
        ink(paper, width, 143, 144, 84, 123, 0);
        assertNull(read.invoke(null, paper, width, height, 153f, 81f, 16f));
        ink(paper, width, 143, 144, 104, 107, 230);
        var shaft = (PrintedBeamShafts.Shaft) read.invoke(null, paper, width, height, 153f, 81f, 16f);
        assertEquals(143, shaft.left());
        assertEquals(144, shaft.right());
        assertEquals(123, shaft.endY());
        var disconnected = paper.clone();
        ink(disconnected, width, 143, 144, 105, 106, 255);
        assertNull(read.invoke(null, disconnected, width, height, 153f, 81f, 16f));
        var wide = paper.clone();
        ink(wide, width, 145, 145, 84, 123, 0);
        ink(wide, width, 145, 145, 104, 107, 230);
        assertNull(read.invoke(null, wide, width, height, 153f, 81f, 16f));
    }

    @Test
    public void continuousStaffRuleDoesNotJoinTwoThickerPrintedBeamEnds() throws Exception {
        int width = 640, height = 240;
        var patch = new byte[width * height];
        Arrays.fill(patch, (byte) 255);
        for (int y = 80; y <= 144; y += 16) ink(patch, width, 20, 620, y, y + 3, 0);
        var bar = new MeasureRegion(65f / width, 575f / width, 47f / height, 178f / height);
        var left = new PrintedBeamShafts.Shaft(301, 303, 59, 117, 1, List.of(), null);
        var right = new PrintedBeamShafts.Shaft(337, 339, 59, 120, 1, List.of(), null);
        var ribbon = Class.forName(WrappedEighthTriplets.class.getName() + "$Ribbon");
        var generator = ribbon.getDeclaredConstructor(float.class, int.class, int.class);
        generator.setAccessible(true);
        var candidate = generator.newInstance(.86f, 114, 115);
        var prove = WrappedEighthTriplets.class.getDeclaredMethod("continuingStaffOnlyCut",
                byte[].class, int.class, int.class, MeasureRegion.class,
                PrintedBeamShafts.Shaft.class, PrintedBeamShafts.Shaft.class, float.class, ribbon);
        prove.setAccessible(true);
        assertEquals(false, prove.invoke(null, patch, width, height, bar, left, right, 16f, candidate));
        ink(patch, width, 278, 303, 105, 118, 0);
        ink(patch, width, 337, 360, 107, 122, 0);
        assertEquals(true, prove.invoke(null, patch, width, height, bar, left, right, 16f, candidate));
        ink(patch, width, 303, 337, 108, 121, 0);
        assertEquals(false, prove.invoke(null, patch, width, height, bar, left, right, 16f, candidate));
    }

    @Test
    public void closeCropNeedsTheRealRulesOnBothSidesOfTheNeighborPocket() {
        int width = 640, height = 230, leftRule = 120, rightRule = 520;
        var regions = List.of(
                new MeasureRegion(30f / width, 111f / width, 46f / height, 177f / height),
                new MeasureRegion(129f / width, 511f / width, 46f / height, 177f / height),
                new MeasureRegion(529f / width, 615f / width, 46f / height, 177f / height));
        var paper = new byte[width * height];
        Arrays.fill(paper, (byte) 255);
        for (int y = 80; y <= 144; y += 16) ink(paper, width, 12, 630, y, y + 1, 0);
        ink(paper, width, leftRule - 1, leftRule + 2, 80, 145, 0);
        ink(paper, width, rightRule - 1, rightRule + 2, 80, 145, 0);
        org.junit.Assert.assertTrue(WrappedMeasureBoundaries.confirmsNeighborBoundedTripletBar(
                paper, width, height, regions, 1, 150, 498));
        org.junit.Assert.assertFalse(WrappedMeasureBoundaries.confirmsNeighborBoundedTripletBar(
                paper, width, height, regions, 1, 150, 518));

        for (boolean opening : List.of(false, true)) {
            var missing = paper.clone();
            int rule = opening ? leftRule : rightRule;
            for (int y = 80; y <= 145; y++)
                if ((y - 80) % 16 > 1) ink(missing, width, rule - 1, rule + 2, y, y, 255);
            org.junit.Assert.assertFalse(WrappedMeasureBoundaries.confirmsNeighborBoundedTripletBar(
                    missing, width, height, regions, 1, 150, 498));
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
    public void aClosedFourGroupRhythmDoesNotNeedAResolvedClef() {
        notes.replaceAll(note -> note.withClef(ScoreNoteEvent.CLEF_UNKNOWN));
        notes.set(5, ClosingTripletRhythm.beamedValue(notes.get(5), 2));
        var meters = List.of(new ScoreMeterChange(0, 4, 4));
        var recognized = WrappedEighthTriplets.closeQuarterBeatBars(
                notes, List.of(), bars, meters, gray, WIDTH, HEIGHT, null, gray.clone());
        assertEquals(12, recognized.stream().filter(note ->
                note.clefBottomDiatonic() == ScoreNoteEvent.CLEF_UNKNOWN
                        && note.beamCount() == 1 && note.tupletDivisor() == 3
                        && note.tupletNormalNotes() == 2).count());

        var open = gray.clone();
        ink(open, WIDTH, RIGHT - 2, RIGHT + 1, 82, 142, 255);
        assertSame(notes, WrappedEighthTriplets.closeQuarterBeatBars(
                notes, List.of(), bars, meters, gray, WIDTH, HEIGHT, null, open));
        notes.set(5, notes.get(5).withClef(30));
        assertSame(notes, WrappedEighthTriplets.closeQuarterBeatBars(
                notes, List.of(), bars, meters, gray, WIDTH, HEIGHT, null, gray.clone()));
    }

    @Test
    public void fourPrintedGroupsRejectOneSpuriousSecondaryRail() {
        notes.set(5, ClosingTripletRhythm.beamedValue(notes.get(5), 2));
        var meters = List.of(new ScoreMeterChange(0, 4, 4));
        var recognized = WrappedEighthTriplets.closeQuarterBeatBars(
                notes, List.of(), bars, meters, gray, WIDTH, HEIGHT, null, gray.clone());
        assertEquals(12, recognized.stream().filter(
                note -> note.beamCount() == 1 && note.tupletDivisor() == 3
                        && note.tupletNormalNotes() == 2).count());
        var missingBeam = gray.clone();
        ink(missingBeam, WIDTH, 109, 127, 55, 65, 255);
        assertSame(notes, WrappedEighthTriplets.closeQuarterBeatBars(
                notes, List.of(), bars, meters, gray, WIDTH, HEIGHT, null, missingBeam));
        assertSame(notes, WrappedEighthTriplets.closeQuarterBeatBars(
                notes, List.of(), bars, meters, missingBeam, WIDTH, HEIGHT, null, gray));
    }

    @Test
    public void provedRestoredTripletExtendsOnlyItsPartiallyOwnedBeam() {
        var typed = notes.stream().map(n -> n.withTupletRatio(3, 2)).toList();
        var meter = List.of(new ScoreMeterChange(0, 4, 4));
        var partial = new ScoreBeamSource.Group("retained-two-columns",
                List.of(new ScoreBeamSource.Column(List.of(new ScoreBeamSource.Anchor(0, 1))),
                        new ScoreBeamSource.Column(List.of(new ScoreBeamSource.Anchor(0, 2)))),
                List.of(1), List.of());
        var source = ScoreBeamSource.capture(0, typed.size(), List.of(partial), List.of());
        var score = new ScorePageInterpretation(
                bars, typed, 0, List.of(), List.of(), meter, List.of()).withBeamSource(source);
        var corrected = WrappedEighthTriplets.completeRestoredTripletBeams(
                score, List.of(0), gray, gray, WIDTH, HEIGHT);
        var expected = new ScoreBeamSource.Column(List.of(new ScoreBeamSource.Anchor(0, 0)));
        var extended = corrected.beamSource().groups().get(0);
        assertEquals(List.of(expected, partial.columns().get(0), partial.columns().get(1)), extended.columns());
        assertEquals(List.of(1, 1), extended.bridges());
        assertEquals(score.notes(), corrected.notes());
        var broken = gray.clone();
        ink(broken, WIDTH, 109, 127, 55, 65, 255);
        assertSame(score, WrappedEighthTriplets.completeRestoredTripletBeams(
                score, List.of(0), gray, broken, WIDTH, HEIGHT));
        assertSame(score, WrappedEighthTriplets.completeRestoredTripletBeams(
                score, List.of(), gray, gray, WIDTH, HEIGHT));
    }

    @Test
    public void terminalRecoveredBeamRequiresBothPlanesToHideOptionalTupletMark() {
        var typed = notes.stream().map(n -> n.withTupletRatio(3, 2)).toList();
        var partial = new ScoreBeamSource.Group("right-edge-terminal",
                List.of(new ScoreBeamSource.Column(List.of(new ScoreBeamSource.Anchor(0, 10))),
                        new ScoreBeamSource.Column(List.of(new ScoreBeamSource.Anchor(0, 11)))),
                List.of(1), List.of());
        var source = ScoreBeamSource.capture(0, typed.size(), List.of(partial), List.of());
        var score = new ScorePageInterpretation(bars, typed, 0, List.of(), List.of(),
                List.of(new ScoreMeterChange(0, 4, 4)), List.of()).withBeamSource(source);
        var clean = WrappedEighthTriplets.completeRestoredTripletBeams(
                score, List.of(0), gray, gray, WIDTH, HEIGHT).beamSource().groups().get(0);
        assertEquals(ScoreBeamSource.TupletMark.UNNUMBERED, clean.tupletMark());

        var numbered = gray.clone();
        ink(numbered, WIDTH, 382, 392, 182, 184, 0);
        ink(numbered, WIDTH, 390, 392, 185, 194, 0);
        ink(numbered, WIDTH, 383, 392, 190, 192, 0);
        ink(numbered, WIDTH, 382, 392, 197, 199, 0);
        ink(numbered, WIDTH, 390, 392, 195, 196, 0);

        var bracketed = gray.clone();
        ink(bracketed, WIDTH, 349, 374, 189, 190, 0);
        ink(bracketed, WIDTH, 400, 425, 189, 190, 0);
        ink(bracketed, WIDTH, 349, 350, 181, 190, 0);
        ink(bracketed, WIDTH, 424, 425, 181, 190, 0);

        for (byte[] printed : List.of(numbered, bracketed)) {
            for (boolean inRaw : List.of(false, true)) {
                var recovered = WrappedEighthTriplets.completeRestoredTripletBeams(
                        score, List.of(0), inRaw ? gray : printed, inRaw ? printed : gray, WIDTH, HEIGHT);
                var group = recovered.beamSource().groups().get(0);
                assertEquals(clean.columns(), group.columns());
                assertNull(group.tupletMark());
            }
        }
    }

    private static void verifyInteriorSixFourAlias(
            int width, int height, byte[] paper, MeasureRegion bar, List<ScoreNoteEvent> notes) {
        var regions = List.of(new MeasureRegion(5f / width, 20f / width, bar.top(), bar.bottom()), bar);
        var interior = new ArrayList<ScoreNoteEvent>();
        for (var note : notes)
            interior.add(new ScoreNoteEvent(1, note.positionInMeasure(), note.staffStep(),
                    note.staffIndex(), note.staffCount(), note.pageY(), false, 0, 1,
                    note.writtenAccidental(), 0, 1, 0, 0, note.clefBottomDiatonic())
                    .withStemDirection(note.stemDirection()));
        interior.set(1, ClosingTripletRhythm.beamedValue(interior.get(1), 2));
        var meter = List.of(new ScoreMeterChange(0, 6, 4));
        var proved = WrappedEighthTriplets.closeQuarterBeatBars(
                interior, List.of(), regions, meter, paper, width, height, null, paper.clone());
        assertEquals(18L, proved.stream().filter(n -> n.beamCount() == 1
                && n.tupletDivisor() == 3 && n.tupletNormalNotes() == 2).count());
        var broken = paper.clone();
        ink(broken, width, 109, 127, 54, 64, 255);
        var doubleRail = paper.clone();
        ink(doubleRail, width, 76, 141, 71, 75, 0);
        for (var variant : List.of(broken, doubleRail)) {
            assertSame(interior, WrappedEighthTriplets.closeQuarterBeatBars(
                    interior, List.of(), regions, meter, paper, width, height, null, variant));
            assertSame(interior, WrappedEighthTriplets.closeQuarterBeatBars(
                    interior, List.of(), regions, meter, variant, width, height, null, paper));
        }
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
        verifyInteriorSixFourAlias(width, height, pixels, region.get(0), eighteen);
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

    @Test
    public void openingSixFourRejectsAnUnprintedSecondaryRail() throws Exception {
        checkOpeningTriplets(false, false, true);
    }

    private static void checkOpeningTriplets(boolean missingBeamLabel, boolean laterStaff)
            throws Exception {
        checkOpeningTriplets(missingBeamLabel, laterStaff, false);
    }

    private static void checkOpeningTriplets(
            boolean missingBeamLabel, boolean laterStaff, boolean falseSecondaryRail)
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
                            quarterAlias ? 0 : falseSecondaryRail && slot == 1 ? 2 : 1,
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
                        .map(note -> (note.beamCount() != 1
                                ? ClosingTripletRhythm.singleBeam(note) : note).withTupletRatio(3, 2))
                        .toList(),
                WrappedEighthTriplets.closeQuarterBeatBars(
                        score, List.of(), bars, meters, paper, width, height, labels,
                        falseSecondaryRail ? paper.clone() : null));
        if (falseSecondaryRail) {
            byte[] detached = paper.clone();
            ink(detached, width, 203, 213, 72, 81, 255);
            assertSame(score, WrappedEighthTriplets.closeQuarterBeatBars(
                    score, List.of(), bars, meters, paper, width, height, labels, detached));
        }
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

    @Test
    public void tracksBeamWidthsWithoutCountingStaffRulesAsRails() {
        int width = 240, height = 220;
        byte[] ink = new byte[width * height];
        java.util.Arrays.fill(ink, (byte) 255);
        for (int y = 100; y <= 164; y += 16)
            for (int row = y - 1; row <= y + 1; row++)
                java.util.Arrays.fill(ink, row * width + 10, row * width + 230, (byte) 0);
        var tracked = StaffPitchTrack.linear(width, 164, 16, 0);
        var left = new PrintedBeamShafts.Shaft(48, 49, 60, 135, 1, List.of());
        var right = new PrintedBeamShafts.Shaft(105, 106, 60, 135, 1, List.of());
        assertTrue(WrappedEighthTriplets.trackedRailCount(ink, width, height, tracked, left, right, 16, 0));
        assertFalse(WrappedEighthTriplets.trackedRailCount(ink, width, height, tracked, left, right, 16, 1));

        for (int y = 127; y <= 135; y++)
            java.util.Arrays.fill(ink, y * width + 48, y * width + 107, (byte) 0);
        assertTrue(WrappedEighthTriplets.trackedRailCount(ink, width, height, tracked, left, right, 16, 1));
        assertFalse(WrappedEighthTriplets.trackedRailCount(ink, width, height, tracked, left, right, 16, 2));

        for (int y = 114; y <= 121; y++)
            java.util.Arrays.fill(ink, y * width + 48, y * width + 107, (byte) 0);
        assertTrue(WrappedEighthTriplets.trackedRailCount(ink, width, height, tracked, left, right, 16, 2));

        for (int y = 121; y <= 127; y++)
            java.util.Arrays.fill(ink, y * width + 48, y * width + 107, (byte) 0);
        assertFalse(WrappedEighthTriplets.trackedRailCount(ink, width, height, tracked, left, right, 16, 2));
    }
}
