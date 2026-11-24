// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class FingeringAnnotationFilterTest {
    static final int W = 400, H = 300;
    final byte[] gray = new byte[W * H];
    final List<MeasureRegion> measures = List.of(new MeasureRegion(0, 1, 0, 1));
    final List<PlayingTechniqueDetector.Staff> staffs =
            List.of(new PlayingTechniqueDetector.Staff(100, 164, 16, 0, 1));

    public FingeringAnnotationFilterTest() {
        Arrays.fill(gray, (byte) 255);
    }

    ScoreNoteEvent note(int step, int y) {
        return new ScoreNoteEvent(0, .5f, step, 0, 1, y / (float) H, false, 0, 0, 1, 1);
    }

    PlayingTechniqueDetector.Word word(String text, int top, int bottom) {
        return new PlayingTechniqueDetector.Word(
                text, 180f / W, top / (float) H, 220f / W, bottom / (float) H);
    }

    List<ScoreNoteEvent> apply(String text, int top, int bottom, ScoreNoteEvent n) {
        return FingeringAnnotationFilter.apply(
                List.of(word(text, top, bottom)), staffs, measures, List.of(n), gray, W, H);
    }

    @Test
    public void aboveStaffFingeringGlyphsAreRemoved() {
        for (String text : List.of("L2", "2", "LR", "3", "H4"))
            assertTrue(text, apply(text, 55, 88, note(10, 80)).isEmpty());
    }

    @Test
    public void topLineInkIsNotLedgerEvidence() {
        for (int x = 168; x < 234; x++) gray[100 * W + x] = 0;
        assertTrue(apply("3", 66, 104, note(8, 100)).isEmpty());
    }

    @Test
    public void graceNoteIsPreserved() {
        assertEquals(
                1, apply("2", 55, 88, note(10, 80).withArticulations(NoteOrnament.GRACE)).size());
    }

    @Test
    public void ordinaryStaffNoteIsPreserved() {
        assertEquals(1, apply("2", 70, 103, note(6, 95)).size());
    }

    @Test
    public void belowStaffWordIsIgnored() {
        assertEquals(1, apply("2", 110, 143, note(10, 130)).size());
    }

    @Test
    public void shortNumberIsInsufficient() {
        assertEquals(1, apply("2", 75, 85, note(10, 80)).size());
    }

    @Test
    public void nonFingeringWordIsIgnored() {
        for (String text : List.of("12", "8va", "Allegro", "R2", "0"))
            assertEquals(text, 1, apply(text, 55, 88, note(10, 80)).size());
    }

    @Test
    public void realLedgerAcrossTextProtectsNote() {
        for (int x = 165; x < 236; x++) gray[80 * W + x] = 0;
        assertEquals(1, apply("2", 55, 88, note(10, 80)).size());
    }

    @Test
    public void ledgerOnlyOnOneSideIsInsufficient() {
        for (int x = 165; x < 180; x++) gray[80 * W + x] = 0;
        assertTrue(apply("2", 55, 88, note(10, 80)).isEmpty());
    }

    @Test
    public void absentWordsPreserveListIdentity() {
        var notes = List.of(note(10, 80));
        assertSame(
                notes,
                FingeringAnnotationFilter.apply(List.of(), staffs, measures, notes, gray, W, H));
    }

    @Test
    public void sourcePixelsArePreserved() {
        var before = gray.clone();
        apply("2", 55, 88, note(10, 80));
        assertArrayEquals(before, gray);
    }

    @Test
    public void shortLedgerMistakenForDigitKeepsItsDownStem() {
        for (int y = 80; y <= 126; y++) gray[y * W + 190] = 0;
        assertEquals(1, apply("2", 70, 92, note(11, 80)).size());
    }

    @Test
    public void upStemBeyondOcrBoxProtectsTopLineNote() {
        for (int y = 42; y <= 100; y++) gray[y * W + 210] = 0;
        assertEquals(1, apply("3", 80, 105, note(8, 100)).size());
    }

    @Test
    public void NumeralSpineInsideTextBoxIsNotAnIndependentStem() {
        for (int y = 60; y <= 87; y++) gray[y * W + 190] = 0;
        assertTrue(apply("2", 55, 88, note(10, 80)).isEmpty());
    }

    @Test
    public void disconnectedStrokesDoNotProtectAnAnnotation() {
        for (int y = 80; y <= 126; y++) if (y < 90 || y > 98) gray[y * W + 190] = 0;
        assertTrue(apply("2", 70, 92, note(11, 80)).isEmpty());
    }

    @Test
    public void explicitHandLabelIsNotASingleMisreadDigit() {
        for (int y = 42; y <= 100; y++) gray[y * W + 210] = 0;
        assertTrue(apply("L2", 80, 105, note(8, 100)).isEmpty());
    }

    @Test
    public void fullTokensRetainAsciiWhitespaceAndUnicodeBoundaries() {
        for (String text : List.of("1", " 5\n", "L\t2", "h4", "L\r\nR", "H L"))
            assertTrue(text, FingeringAnnotationFilter.isFingering(text));
        for (String text :
                List.of(
                        "",
                        "R2",
                        "L22",
                        "L2x",
                        "\uFF2C2",
                        "\uFF12",
                        "L\u00A02",
                        "L\u20032",
                        "L2\nx")) assertFalse(text, FingeringAnnotationFilter.isFingering(text));
        assertFalse(FingeringAnnotationFilter.isFingering(null));
        assertTrue(FingeringAnnotationFilter.isFingering("L2"));
    }

    @Test
    public void digitStemAndHandLabelKeepWholeInputAndCallerPixels() {
        for (int y = 42; y <= 100; y++) gray[y * W + 210] = 0;
        var originalPixels = gray.clone();
        var n = note(8, 100);
        var notes = List.of(n);
        var digits = List.of(word(" 3\n", 80, 105));
        assertSame(
                notes,
                FingeringAnnotationFilter.apply(digits, staffs, measures, notes, gray, W, H));
        var hand = List.of(word("H\n3", 80, 105));
        assertEquals(
                List.of(),
                FingeringAnnotationFilter.apply(hand, staffs, measures, notes, gray, W, H));
        assertArrayEquals(originalPixels, gray);
        assertSame(n, notes.get(0));
        assertEquals(" 3\n", digits.get(0).text());
        assertEquals("H\n3", hand.get(0).text());
    }

    @Test
    public void separatedFingeringAndSegueRequireThreeCompletePrintedBeamGroups() {
        int width = 640;
        int height = 330;
        float left = 30;
        float right = 580;
        var image = new byte[width * height];
        Arrays.fill(image, (byte) 255);
        var staff = List.of(new PlayingTechniqueDetector.Staff(215, 279, 16, 0, 1));
        var region = List.of(new MeasureRegion(left / width, right / width, .35f, .98f));
        for (int y = 215; y <= 279; y += 16)
            drawInk(image, width, 1, width - 1, y, y + 1, 0);
        drawInk(image, width, 584, 586, 215, 280, 0);

        var notes = new ArrayList<ScoreNoteEvent>();
        for (int slot = 0; slot < 12; slot++) {
            int x = 73 + slot * 43;
            int y = 205 - slot % 4 * 8;
            notes.add(captionNote(x, y, 9 + (279 - y) / 8, -1, 2, 0, height, left, right));
            drawInk(image, width, x - 10, x + 8, y - 5, y + 6, 0);
            drawInk(image, width, x - 10, x - 7, y, 250, 0);
            if (slot % 4 == 3) {
                int xMin = x - 3 * 43 - 10;
                drawInk(image, width, xMin, x - 7, 229, 235, 0);
                drawInk(image, width, xMin, x - 7, 237, 250, 0);
                for (int shaftX = xMin; shaftX < x; shaftX += 43)
                    drawInk(image, width, shaftX, shaftX + 3, 236, 237, 0);
            }
        }
        var printed = List.copyOf(notes);
        var digits = new PlayingTechniqueDetector.Word("4 3 2 1 4", 58f / width,
                258f / height, 248f / width, 293f / height);
        var segue = new PlayingTechniqueDetector.Word("segue", 275f / width,
                263f / height, 410f / width, 302f / height);
        var words = List.of(digits, segue);
        for (int x : new int[] {75, 316, 342, 370}) {
            notes.add(captionNote(x, 283, 0, 0, 0, 4, height, left, right));
            drawInk(image, width, x - 5, x + 5, 280, 288, 0);
        }

        assertEquals(printed, FingeringAnnotationFilter.apply(words, staff, region, notes, image, width, height));
        var thinSeam = image.clone();
        drawInk(thinSeam, width, 150, 192, 235, 238, 0);
        drawInk(thinSeam, width, 150, 192, 236, 237, 200);
        drawInk(thinSeam, width, 164, 170, 235, 236, 200);
        assertEquals(printed, FingeringAnnotationFilter.apply(words, staff, region, notes, thinSeam, width, height));
        assertSame(notes, FingeringAnnotationFilter.apply(
                List.of(segue), staff, region, notes, image, width, height));
        var brokenGroup = image.clone();
        drawInk(brokenGroup, width, 127, 150, 228, 251, 255);
        assertSame(notes, FingeringAnnotationFilter.apply(words, staff, region, notes, brokenGroup, width, height));
        var separateShaft = image.clone();
        drawInk(separateShaft, width, 305, 308, 282, 321, 0);
        assertSame(notes, FingeringAnnotationFilter.apply(words, staff, region, notes, separateShaft, width, height));
    }

    private static ScoreNoteEvent captionNote(int x, int y, int step, int stem, int beams,
            float whole, int height, float left, float right) {
        return captionNote(x, y, step, stem, beams, whole, height, left, right, false);
    }

    private static ScoreNoteEvent captionNote(int x, int y, int step, int stem, int beams,
            float whole, int height, float left, float right, boolean tied) {
        return new ScoreNoteEvent(0, (x - left) / (right - left), step, 0, 1, y / (float) height,
                tied, 0, beams, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, whole, 1, 0, 0, 30, false, 0,
                false, 0, 0, 1, stem, ScoreNoteEvent.Kind.PITCHED, ScoreNoteEvent.VisualSize.FULL, 0, null);
    }

    private static void drawInk(byte[] image, int width, int left, int right, int top, int bottom, int shade) {
        for (int y = top; y < bottom; y++)
            Arrays.fill(image, y * width + left, y * width + right, (byte) shade);
    }

    @Test
    public void mergedShortCaptionRequiresItsInitialLobeAndContinuousFineBeamSeam() {
        int width = 640;
        int height = 330;
        float left = 30, right = 580;
        var image = new byte[width * height];
        Arrays.fill(image, (byte) 255);
        var staff = List.of(new PlayingTechniqueDetector.Staff(215, 279, 16, 0, 1));
        var region = List.of(new MeasureRegion(left / width, right / width, .35f, .90f));
        for (int y = 215; y <= 279; y += 16)
            drawInk(image, width, 1, width - 1, y, y + 1, 0);
        drawInk(image, width, 584, 586, 215, 280, 0);
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int slot = 0; slot < 12; slot++) {
            int x = 73 + slot * 43, y = 205 - slot % 4 * 8;
            notes.add(captionNote(x, y, 9 + (279 - y) / 8, -1, 2, 0, height, left, right));
            drawInk(image, width, x - 10, x + 8, y - 5, y + 3, 0);
            drawInk(image, width, x - 10, x - 7, y, 250, 0);
            if (slot % 4 == 3) {
                int xMin = x - 3 * 43 - 10;
                drawInk(image, width, xMin, x - 7, 228, 236, 0);
                drawInk(image, width, xMin, x - 7, 236, 237, 205);
                drawInk(image, width, xMin, x - 7, 237, 250, 0);
                for (int shaftX = xMin; shaftX < x; shaftX += 43)
                    drawInk(image, width, shaftX, shaftX + 3, 236, 237, 0);
            }
        }
        var sixteenths = List.copyOf(notes);
        for (int i = 8; i < 11; i++) notes.set(i, notes.get(i).withTupletRatio(3, 2));
        var caption = new PlayingTechniqueDetector.Word("5 4 3 segue", 61f / width,
                269f / height, 296f / width, 313f / height);
        for (int x : new int[] {76, 111, 149, 177, 191, 221, 248, 270})
            drawInk(image, width, x, x + 11, 285, 294, 0);
        notes.add(captionNote(182, 288, -1, 0, 0, 4, height, left, right));
        var words = List.of(caption);
        assertEquals(sixteenths, FingeringAnnotationFilter.apply(words, staff, region, notes, image, width, height));
        var fusedBeams = image.clone();
        drawInk(fusedBeams, width, 76, 94, 236, 237, 0);
        assertSame(notes, FingeringAnnotationFilter.apply(words, staff, region, notes, fusedBeams, width, height));
        var wrongGlyph = new ArrayList<>(notes);
        wrongGlyph.set(12, captionNote(195, 288, -1, 0, 0, 4, height, left, right));
        assertSame(wrongGlyph, FingeringAnnotationFilter.apply(
                words, staff, region, wrongGlyph, image, width, height));
    }

    @Test
    public void compactCaptionOwnsOnlyItsInitialWithPrintedSixteenthGroups() {
        int width = 640;
        int height = 330;
        float left = 30, right = 580;
        var image = new byte[width * height];
        Arrays.fill(image, (byte) 255);
        var staff = List.of(new PlayingTechniqueDetector.Staff(215, 279, 16, 0, 1));
        var region = List.of(new MeasureRegion(left / width, right / width, .35f, .90f));
        for (int y = 215; y <= 279; y += 16)
            drawInk(image, width, 1, width - 1, y, y + 1, 0);
        drawInk(image, width, 584, 586, 215, 280, 0);
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int slot = 0; slot < 12; slot++) {
            int x = 73 + slot * 43, y = 205 - slot % 4 * 8;
            notes.add(captionNote(x, y, 9 + (279 - y) / 8, -1, 2, 0, height, left, right));
            drawInk(image, width, x - 10, x + 8, y - 5, y + 3, 0);
            drawInk(image, width, x - 10, x - 7, y, 250, 0);
            if (slot % 4 == 3) {
                int xMin = x - 3 * 43 - 10;
                drawInk(image, width, xMin, x - 7, 228, 236, 0);
                drawInk(image, width, xMin, x - 7, 236, 237, 205);
                drawInk(image, width, xMin, x - 7, 237, 250, 0);
                for (int shaftX = xMin; shaftX < x; shaftX += 43)
                    drawInk(image, width, shaftX, shaftX + 3, 236, 237, 0);
            }
        }
        var expected = List.copyOf(notes);
        for (int slot = 8; slot < 11; slot++) notes.set(slot, notes.get(slot).withTupletRatio(3, 2));
        for (int x : new int[] {75, 107, 124, 143, 164, 182})
            drawInk(image, width, x, x + 11, 285, 298, 0);
        var words = List.of(new PlayingTechniqueDetector.Word("1 segue", 61f / width,
                269f / height, 222f / width, 313f / height));
        notes.add(captionNote(113, 290, -1, 0, 0, 4, height, left, right));
        assertEquals(expected, FingeringAnnotationFilter.apply(words, staff, region, notes, image, width, height));

        var erased = image.clone();
        drawInk(erased, width, 107, 118, 285, 298, 255);
        assertSame(notes, FingeringAnnotationFilter.apply(words, staff, region, notes, erased, width, height));
        var attachedStem = image.clone();
        drawInk(attachedStem, width, 105, 107, 249, 291, 0);
        assertSame(notes, FingeringAnnotationFilter.apply(words, staff, region, notes, attachedStem, width, height));
        var beamBreak = image.clone();
        drawInk(beamBreak, width, 178, 188, 225, 254, 255);
        assertSame(notes, FingeringAnnotationFilter.apply(words, staff, region, notes, beamBreak, width, height));
        var incomplete = new ArrayList<>(notes);
        incomplete.remove(0);
        assertSame(incomplete, FingeringAnnotationFilter.apply(words, staff, region, incomplete, image, width, height));
    }


    @Test
    public void mergedSegueRequiresAllSeparateFingeringAndCaptionOwnersInBothPlanes() {
        int width = 640, height = 330;
        float left = 30, right = 580;
        var image = new byte[width * height];
        Arrays.fill(image, (byte) 255);
        var staffs = List.of(new PlayingTechniqueDetector.Staff(215, 279, 16, 0, 1));
        var measures = List.of(new MeasureRegion(left / width, right / width, .35f, .98f));
        var notes = closedSixteenthCaptionNotes(image, width, height, left, right);
        var expected = List.copyOf(notes);
        var digits = new PlayingTechniqueDetector.Word("3 2", 40f / width,
                273f / height, 128f / width, 312f / height);
        var caption = new PlayingTechniqueDetector.Word("2 1 segue", 197f / width,
                270f / height, 359f / width, 312f / height);
        var words = List.of(digits, caption);
        for (int x : new int[] {49, 101, 209, 226, 257, 274, 291, 308, 325}) {
            drawInk(image, width, x, x + 11, 285, 301, 0);
        }
        notes.add(captionNote(54, 292, -2, 0, 3, 0, height, left, right));
        notes.add(captionNote(262, 292, -2, 0, 0, 4, height, left, right));
        notes.add(captionNote(296, 292, -2, 0, 0, 4, height, left, right));
        assertEquals(expected, FingeringAnnotationFilter.removeMergedSegueAliases(
                words, staffs, measures, notes, image, image, width, height));
        assertSame(notes, FingeringAnnotationFilter.removeMergedSegueAliases(
                List.of(caption), staffs, measures, notes, image, image, width, height));

        var missingDescender = image.clone();
        drawInk(missingDescender, width, 291, 302, 285, 301, 255);
        assertSame(notes, FingeringAnnotationFilter.removeMergedSegueAliases(
                words, staffs, measures, notes, image, missingDescender, width, height));
        var brokenGroup = image.clone();
        drawInk(brokenGroup, width, 178, 188, 225, 254, 255);
        assertSame(notes, FingeringAnnotationFilter.removeMergedSegueAliases(
                words, staffs, measures, notes, brokenGroup, image, width, height));
    }

    @Test
    public void compactSegueRetainsLeadingDigitImmediatelyBelowBottomRule() {
        int width = 640, height = 330;
        float left = 30, right = 580;
        var image = new byte[width * height];
        Arrays.fill(image, (byte) 255);
        var staffs = List.of(new PlayingTechniqueDetector.Staff(215, 279, 16, 0, 1));
        var measures = List.of(new MeasureRegion(left / width, right / width, .35f, .98f));
        var notes = closedSixteenthCaptionNotes(image, width, height, left, right);
        var expected = List.copyOf(notes);
        for (int i = 4; i < 7; i++) notes.set(i, notes.get(i).withTupletRatio(3, 2));
        var words = List.of(new PlayingTechniqueDetector.Word("3 segue", 61f / width,
                270f / height, 222f / width, 313f / height));
        drawInk(image, width, 75, 86, 281, 298, 0);
        for (int x : new int[] {107, 124, 143, 164, 182}) {
            drawInk(image, width, x, x + 11, 285, 298, 0);
        }
        notes.add(captionNote(113, 291, -2, 0, 3, 0, height, left, right));
        assertEquals(expected, FingeringAnnotationFilter.apply(
                words, staffs, measures, notes, image, image, width, height));

        var noDigit = image.clone();
        drawInk(noDigit, width, 75, 86, 281, 298, 255);
        assertSame(notes, FingeringAnnotationFilter.apply(
                words, staffs, measures, notes, image, noDigit, width, height));
        assertSame(notes, FingeringAnnotationFilter.apply(
                words, staffs, measures, notes, image, width, height));
    }

    @Test
    public void pairedSegueLettersRequireTheirSeparateTextAndRealDoubleRails() {
        int width = 640, height = 330;
        float left = 30, right = 580;
        var image = new byte[width * height];
        Arrays.fill(image, (byte) 255);
        var staffs = List.of(new PlayingTechniqueDetector.Staff(215, 279, 16, 0, 1));
        var measures = List.of(new MeasureRegion(left / width, right / width, .35f, .98f));
        var notes = closedSixteenthCaptionNotes(image, width, height, left, right);
        var expected = List.copyOf(notes);
        var words = List.of(new PlayingTechniqueDetector.Word("3 segue", 61f / width,
                275f / height, 222f / width, 316f / height));
        drawInk(image, width, 75, 86, 285, 301, 0);
        for (int x : new int[] {107, 124, 143, 164, 182}) {
            drawInk(image, width, x, x + 11, 291, x == 143 ? 312 : 307, 0);
        }
        notes.add(captionNote(113, 299, -3, 0, 2, 0, height, left, right));
        notes.add(captionNote(148, 302, -3, 0, 3, 0, height, left, right, true));
        assertEquals(expected, FingeringAnnotationFilter.apply(
                words, staffs, measures, notes, image, image, width, height));

        var missingLetter = image.clone();
        drawInk(missingLetter, width, 143, 154, 291, 312, 255);
        assertSame(notes, FingeringAnnotationFilter.apply(
                words, staffs, measures, notes, image, missingLetter, width, height));
        var cutRail = image.clone();
        drawInk(cutRail, width, 80, 94, 225, 252, 255);
        assertSame(notes, FingeringAnnotationFilter.apply(
                words, staffs, measures, notes, cutRail, image, width, height));
    }

    @Test
    public void singleSegueDescenderCanOwnAStemlessBeamAlias() {
        int width = 640, height = 330;
        float left = 30, right = 580;
        var image = new byte[width * height];
        Arrays.fill(image, (byte) 255);
        var staffs = List.of(new PlayingTechniqueDetector.Staff(215, 279, 16, 0, 1));
        var measures = List.of(new MeasureRegion(left / width, right / width, .35f, .98f));
        var notes = closedSixteenthCaptionNotes(image, width, height, left, right);
        var expected = List.copyOf(notes);
        var words = List.of(new PlayingTechniqueDetector.Word("segue", 190f / width,
                274f / height, 300f / width, 315f / height));
        for (int x : new int[] {200, 215, 254, 274}) {
            drawInk(image, width, x, x + 12, 284, 298, 0);
        }
        drawInk(image, width, 230, 250, 284, 310, 0);
        notes.add(captionNote(242, 294, -2, 0, 3, 0, height, left, right));
        assertEquals(expected, FingeringAnnotationFilter.apply(
                words, staffs, measures, notes, image, image, width, height));

        var missingTail = image.clone();
        drawInk(missingTail, width, 230, 250, 300, 310, 255);
        assertSame(notes, FingeringAnnotationFilter.apply(
                words, staffs, measures, notes, image, missingTail, width, height));
        assertSame(notes, FingeringAnnotationFilter.apply(
                words, staffs, measures, notes, image, width, height));
    }

    @Test
    public void mergedFingeringSegueInitialNeedsDetachedInkAndACompleteBar() {
        int width = 640, height = 330;
        float left = 30, right = 580;
        var image = new byte[width * height];
        Arrays.fill(image, (byte) 255);
        var staffs = List.of(new PlayingTechniqueDetector.Staff(215, 279, 16, 0, 1));
        var measures = List.of(new MeasureRegion(left / width, right / width, .35f, .98f));
        var notes = closedSixteenthCaptionNotes(image, width, height, left, right);
        var expected = List.copyOf(notes);
        var words = List.of(new PlayingTechniqueDetector.Word("3 2 1 3 2 1 segue", 61f / width,
                280f / height, 407f / width, 317f / height));
        for (int x : new int[] {74, 109, 144, 179, 214, 249}) {
            drawInk(image, width, x, x + 11, 288, 306, 0);
        }
        for (int x : new int[] {278, 296, 313, 334, 359}) {
            drawInk(image, width, x, x + 11, 288, x == 313 ? 311 : 306, 0);
        }
        notes.add(captionNote(284, 297, -2, 0, 2, 0, height, left, right));
        assertEquals(expected, FingeringAnnotationFilter.apply(
                words, staffs, measures, notes, image, image, width, height));

        var missing = image.clone();
        drawInk(missing, width, 278, 289, 288, 306, 255);
        assertSame(notes, FingeringAnnotationFilter.apply(
                words, staffs, measures, notes, image, missing, width, height));
        var attached = image.clone();
        drawInk(attached, width, 283, 285, 247, 297, 0);
        assertSame(notes, FingeringAnnotationFilter.apply(
                words, staffs, measures, notes, image, attached, width, height));
        var broken = image.clone();
        drawInk(broken, width, 80, 94, 225, 252, 255);
        assertSame(notes, FingeringAnnotationFilter.apply(
                words, staffs, measures, notes, image, broken, width, height));
    }

    private static ArrayList<ScoreNoteEvent> closedSixteenthCaptionNotes(
            byte[] image, int width, int height, float left, float right) {
        for (int y = 215; y <= 279; y += 16) {
            drawInk(image, width, 1, width - 1, y, y + 1, 0);
        }
        drawInk(image, width, 584, 586, 215, 280, 0);
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int slot = 0; slot < 12; slot++) {
            int x = 73 + slot * 43, y = 205 - slot % 4 * 8;
            notes.add(captionNote(x, y, 9 + (279 - y) / 8, -1, 2, 0, height, left, right));
            drawInk(image, width, x - 10, x + 8, y - 5, y + 6, 0);
            drawInk(image, width, x - 10, x - 7, y, 250, 0);
            if (slot % 4 == 3) {
                int xMin = x - 3 * 43 - 10;
                drawInk(image, width, xMin, x - 7, 229, 235, 0);
                drawInk(image, width, xMin, x - 7, 237, 250, 0);
                for (int shaftX = xMin; shaftX < x; shaftX += 43) {
                    drawInk(image, width, shaftX, shaftX + 3, 236, 237, 0);
                }
            }
        }
        return notes;
    }

    @Test
    public void looseNumeralNeedsItsPairedCurvesAndSamePrintedDigit() throws Exception {
        int w = 180, h = 170;
        byte[] image = new byte[w * h];
        java.util.Arrays.fill(image, (byte) 255);
        for (int top : new int[] {111, 119, 128})
            for (int y = top; y < top + 2; y++)
                for (int i = 0; i < 12; i++) {
                    image[y * w + 44 + i] = 0;
                    image[y * w + 99 + i] = 0;
                }
        for (int y = 112; y < 130; y++)
            for (int i = 0; i < 2; i++) {
                image[y * w + 55 + i] = 0;
                image[y * w + 110 + i] = 0;
            }
        for (int y = 107; y <= 133; y++) {
            int curve = Math.round(7 * (y - 120) * (y - 120) / 169f);
            int opening = 34 + curve, closing = 124 - curve;
            image[y * w + opening] = image[y * w + opening - 1] = 0;
            image[y * w + closing] = image[y * w + closing + 1] = 0;
        }

        var start = new PlayingTechniqueDetector.Word("(3", 27f/w, 100f/h, 68f/w, 139f/h);
        var end = new PlayingTechniqueDetector.Word("3)", 93f/w, 100f/h, 132f/w, 139f/h);
        var extract = FingeringAnnotationFilter.class.getDeclaredMethod("parenthesizedDigit",
                byte[].class, int.class, int.class, PlayingTechniqueDetector.Word.class,
                float.class, float.class, boolean.class);
        var same = FingeringAnnotationFilter.class.getDeclaredMethod("sameDetachedDigit",
                byte[].class, int.class, RawNumeralComponents.Component.class,
                RawNumeralComponents.Component.class);
        extract.setAccessible(true);
        same.setAccessible(true);
        var left = extract.invoke(null, image, w, h, start, 100f, 16f, true);
        var right = extract.invoke(null, image, w, h, end, 100f, 16f, false);
        org.junit.Assert.assertNotNull(left);
        org.junit.Assert.assertNotNull(right);
        assertEquals(true, same.invoke(null, image, w, left, right));

        byte[] compact = image.clone();
        for (int y = 107; y <= 133; y++) {
            java.util.Arrays.fill(compact, y * w + 114, y * w + 130, (byte) 255);
            int curve = Math.round(7 * (y - 120) * (y - 120) / 169f);
            int closure = 118 - curve;
            compact[y * w + closure] = compact[y * w + closure + 1] = 0;
        }
        var compactEnd = new PlayingTechniqueDetector.Word("(8", 93f/w, 100f/h, 132f/w, 139f/h);
        var extractClose = FingeringAnnotationFilter.class.getDeclaredMethod("parenthesizedDigit",
                byte[].class, int.class, int.class, PlayingTechniqueDetector.Word.class,
                float.class, float.class, boolean.class, boolean.class);
        extractClose.setAccessible(true);
        org.junit.Assert.assertNull(extract.invoke(null, compact, w, h, compactEnd, 100f, 16f, false));
        var tightlySpacedRight = extractClose.invoke(null, compact, w, h, compactEnd, 100f, 16f, false, true);
        org.junit.Assert.assertNotNull(tightlySpacedRight);
        assertEquals(true, same.invoke(null, compact, w, left, tightlySpacedRight));

        byte[] altered = image.clone();
        for (int y = 108; y <= 133; y++)
            java.util.Arrays.fill(altered, y*w+99, y*w+112, (byte) 0);
        assertEquals(false, same.invoke(null, altered, w, left, right));

        for (int y = 106; y <= 134; y++)
            java.util.Arrays.fill(image, y*w+115, y*w+128, (byte) 255);
        org.junit.Assert.assertNull(extract.invoke(null, image, w, h, end, 100f, 16f, false));
    }
    @Test
    public void pairedStaffFingeringsNeedTheirInkAndAClosedFourQuartetLine() {
        int width = 700, height = 340, floor = 278;
        float left = 36, right = 638;
        byte[] image = new byte[width * height];
        Arrays.fill(image, (byte) 255);
        var regions = List.of(new MeasureRegion(left / width, right / width, .35f, .99f));
        var staff = List.of(new PlayingTechniqueDetector.Staff(214, floor, 16, 0, 1));
        for (int y = 214; y <= floor; y += 16) drawInk(image, width, 30, 660, y, y + 1, 0);
        var music = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < 16; i++) {
            int x = 88 + i * 34;
            int y = 184 - i % 4 * 8;
            music.add(captionNote(x, y, (floor - y) / 8, -1, 2, 0, height, left, right));
            drawInk(image, width, x - 9, x + 8, y - 5, y + 6, 0);
            drawInk(image, width, x - 10, x - 7, y, 242, 0);
            if (i % 4 == 3) {
                drawInk(image, width, x - 112, x - 7, 224, 230, 0);
                drawInk(image, width, x - 112, x - 7, 236, 242, 0);
            }
        }
        int a = 88 + 5 * 34, b = 88 + 11 * 34;
        var words = List.of(
                new PlayingTechniqueDetector.Word("(3", (a - 21f) / width, 261f / height,
                        (a + 14f) / width, 299f / height),
                new PlayingTechniqueDetector.Word("3)", (b - 14f) / width, 261f / height,
                        (b + 21f) / width, 299f / height));
        for (int x : new int[] {a, b}) {
            drawInk(image, width, x + 4, x + 8, floor + 2, floor + 15, 0);
            drawInk(image, width, x - 4, x - 1, floor + 6, floor + 14, 0);
            drawInk(image, width, x - 4, x + 8, floor + 10, floor + 15, 0);
        }
        drawInk(image, width, a - 18, a - 16, floor - 12, floor + 18, 0);
        drawInk(image, width, b + 16, b + 18, floor - 12, floor + 18, 0);
        var noisy = new ArrayList<>(music);
        noisy.add(captionNote(a, floor + 8, -1, 0, 1, 0, height, left, right));
        noisy.add(captionNote(b, floor + 8, -1, 0, 0, 4, height, left, right));
        assertEquals(music, FingeringAnnotationFilter.removePairedStaffFingeringAliases(
                words, staff, regions, noisy, image, image, width, height));

        var erased = image.clone();
        drawInk(erased, width, a - 5, a + 9, floor + 2, floor + 15, 255);
        assertSame(noisy, FingeringAnnotationFilter.removePairedStaffFingeringAliases(
                words, staff, regions, noisy, image, erased, width, height));
        var missingRail = image.clone();
        drawInk(missingRail, width, 98, 106, 224, 230, 255);
        assertSame(noisy, FingeringAnnotationFilter.removePairedStaffFingeringAliases(
                words, staff, regions, noisy, image, missingRail, width, height));
        assertSame(noisy, FingeringAnnotationFilter.removePairedStaffFingeringAliases(
                words.subList(0, 1), staff, regions, noisy, image, image, width, height));
    }


}
