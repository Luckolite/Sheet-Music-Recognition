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
        return new ScoreNoteEvent(0, (x - left) / (right - left), step, 0, 1, y / (float) height,
                false, 0, beams, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, whole, 1, 0, 0, 30, false, 0,
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
}
