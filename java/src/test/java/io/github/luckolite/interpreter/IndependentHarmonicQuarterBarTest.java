// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class IndependentHarmonicQuarterBarTest {
    private static final int W = 520, H = 275;
    private static final List<MeasureRegion> BARS =
            List.of(bar(31, 143), bar(161, 282), bar(300, 447), bar(465, 509));
    private static final float[] BEATS = {2, 2, 2, 1};

    @Test
    public void aHollowTouchOnTwoQuarterOwnersCannotKeepItsSpuriousHalf() {
        var score = new ScorePageInterpretation(
                BARS, List.of(note(187, 92, 11, false), note(187, 116, 8, false),
                              note(188, 156, 3, false), note(244, 100, 10, false),
                              touchHalf(), note(245, 132, 6, false).withOctaveShift(0)),
                0, List.of(), List.of(), List.of(new ScoreMeterChange(0, 2, 4)));
        var source = paper();
        var after = ClosedDoubleHarmonicQuarters.removeOwnedTouchHalves(score, source, source, W, H);
        assertEquals(5, after.notes().size());
        assertEquals(2, after.notes().stream().filter(n -> n.harmonicSemitones() == 24).count());

        var filledDiamond = source.clone();
        fill(filledDiamond, 234, 99, 256, 118, 0);
        assertSame(score, ClosedDoubleHarmonicQuarters.removeOwnedTouchHalves(
                score, filledDiamond, source, W, H));
        assertSame(score, ClosedDoubleHarmonicQuarters.removeOwnedTouchHalves(
                score, source, filledDiamond, W, H));
    }

    private static ScoreNoteEvent touchHalf() {
        return new ScoreNoteEvent(1, (245 - 161f) / 121, 9, 0, 1, 108f / H, false,
                0, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 2, 1,
                0, 0, 30, false, 0, false, 0, 0, 1, -1, ScoreNoteEvent.Kind.PITCHED,
                ScoreNoteEvent.VisualSize.FULL);
    }

    @Test
    public void twoClosedHarmonicQuartersOwnTheBar() {
        var score = score(2);
        var gray = paper();
        var corrected = ClosedDoubleHarmonicQuarters.apply(score, gray, W, H);
        assertEquals(List.of(0, 0, 0, 0),
                corrected.notes().stream().map(ScoreNoteEvent::beamCount).toList());
        assertEquals(List.of(1f, 1f, 1f, 1f),
                corrected.notes().stream().map(ScoreNoteEvent::unbeamedDurationBeats).toList());
        var onset = Map.of(0, 0, 1, 0, 2, 1, 3, 1);
        assertEquals(onset, ClosedDoubleHarmonicQuarters.writtenOnsets(score, gray, W, H, BEATS));
        assertEquals(onset, ClosedDoubleHarmonicQuarters.writtenOnsets(corrected, gray, W, H, BEATS));
    }

    @Test
    public void trueDividerAndBothDiamondsAreRequired() {
        var score = score(2);
        var noDivider = paper();
        fill(noDivider, 289, 112, 294, 184, 255);
        var noDiamond = paper();
        fill(noDiamond, 244, 72, 251, 84, 255);
        for (var variant : List.of(noDivider, noDiamond)) {
            assertSame(score, ClosedDoubleHarmonicQuarters.apply(score, variant, W, H));
            assertEquals(Map.of(), ClosedDoubleHarmonicQuarters.writtenOnsets(score, variant, W, H, BEATS));
        }

        var tripleMeter = score(3);
        assertSame(tripleMeter, ClosedDoubleHarmonicQuarters.apply(tripleMeter, paper(), W, H));
    }

    @Test
    public void denseDownstemsOwnTwoQuarterChordsAcrossLedgerInk() {
        var score = denseScore(2);
        var gray = densePaper();
        var after = ClosedDoubleHarmonicQuarters.apply(score, gray, gray.clone(), W, H);
        var onset = Map.of(0, 0, 1, 0, 2, 1, 3, 1);
        assertEquals(onset, ClosedDoubleHarmonicQuarters.writtenOnsets(score, gray, gray, W, H, BEATS));
        assertEquals(onset, ClosedDoubleHarmonicQuarters.writtenOnsets(after, gray, gray, W, H, BEATS));
        assertEquals(List.of(1f, 1f, 1f, 1f),
                after.notes().stream().map(ScoreNoteEvent::unbeamedDurationBeats).toList());
        assertEquals(List.of(2, 2, 2, 2), after.notes().stream().map(ScoreNoteEvent::octaveShift).toList());
    }

    @Test
    public void denseDownstemsRequireTwoFilledOwnersSeparateShaftsAndBothRules() {
        var score = denseScore(2);
        var source = densePaper();
        var brokenStem = source.clone();
        fill(brokenStem, 187, 170, 190, 179, 255);
        var openBar = source.clone();
        fill(openBar, 342, 117, 351, 180, 255);
        var hollowOwner = source.clone();
        fill(hollowOwner, 196, 106, 201, 110, 255);
        var realBeam = source.clone();
        fill(realBeam, 188, 171, 276, 175, 0);
        for (var variant : List.of(brokenStem, openBar, hollowOwner, realBeam)) {
            assertSame(score, ClosedDoubleHarmonicQuarters.apply(score, source, variant, W, H));
            assertSame(score, ClosedDoubleHarmonicQuarters.apply(score, variant, source, W, H));
            assertEquals(Map.of(),
                    ClosedDoubleHarmonicQuarters.writtenOnsets(score, source, variant, W, H, BEATS));
        }
    }

    private static ScorePageInterpretation denseScore(int beats) {
        return new ScorePageInterpretation(
                List.of(bar(31, 143), bar(161, 339), bar(357, 447), bar(465, 509)),
                List.of(denseNote(198, 108, 9, true), denseNote(198, 140, 5, true),
                        denseNote(286, 92, 11, false), denseNote(286, 124, 7, false)),
                0, List.of(), List.of(), List.of(new ScoreMeterChange(0, beats, 4)));
    }

    private static ScoreNoteEvent denseNote(int x, int y, int step, boolean alias) {
        return new ScoreNoteEvent(1, (x - 161f) / 178, step, 0, 1, y / (float) H, false,
                0, alias ? 2 : 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, alias ? 0 : 1, 1,
                0, 0, 30, false, 0, false, 2, 0, 1, -1, ScoreNoteEvent.Kind.PITCHED,
                ScoreNoteEvent.VisualSize.FULL);
    }

    private static byte[] densePaper() {
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        for (int y : new int[] {116, 132, 148, 164, 180}) fill(gray, 30, y, 508, y, 0);
        fill(gray, 151, 116, 153, 180, 0);
        fill(gray, 347, 116, 349, 180, 0);
        fill(gray, 455, 116, 457, 180, 0);
        fill(gray, 179, 84, 217, 86, 0);
        fill(gray, 267, 68, 305, 70, 0);
        fill(gray, 267, 100, 305, 102, 0);
        draw(gray, 198, 108, 198, 140, 191);
        draw(gray, 286, 92, 286, 124, 182);
        return gray;
    }

    @Test
    public void firstTwoFourBarKeepsOpposedQuarterShafts() throws Exception {
        var score = openingScore();
        var gray = openingPaper("2");
        var edited = ClosedDoubleHarmonicQuarters.apply(score, gray, gray.clone(), W, H);
        assertEquals(List.of(1f, 1f, 1f),
                edited.notes().stream().map(ScoreNoteEvent::unbeamedDurationBeats).toList());
        var offsets = Map.of(0, 0, 1, 0, 2, 1);
        assertEquals(offsets, ClosedDoubleHarmonicQuarters.writtenOnsets(score, gray, gray, W, H, BEATS));
        assertEquals(offsets, ClosedDoubleHarmonicQuarters.writtenOnsets(edited, gray, gray, W, H, BEATS));
    }

    @Test
    public void firstTwoFourBarRequiresItsMeterAndShapeInBothPlanes() throws Exception {
        var score = openingScore();
        var source = openingPaper("2");
        var damaged = source.clone();
        fill(damaged, 252, 183, 272, 201, 255);
        var flagged = source.clone();
        fill(flagged, 246, 214, 278, 220, 0);
        for (var variant : List.of(openingPaper("3"), damaged, flagged)) {
            assertSame(score, ClosedDoubleHarmonicQuarters.apply(score, source, variant, W, H));
            assertSame(score, ClosedDoubleHarmonicQuarters.apply(score, variant, source, W, H));
            assertEquals(Map.of(),
                    ClosedDoubleHarmonicQuarters.writtenOnsets(score, source, variant, W, H, BEATS));
        }
    }

    private static ScorePageInterpretation openingScore() {
        var measures = List.of(new MeasureRegion(.55f, .8f, 3f / H, 68f / H),
                new MeasureRegion(115f / W, 307f / W, 109f / H, 240f / H),
                new MeasureRegion(325f / W, 460f / W, 109f / H, 240f / H));
        return new ScorePageInterpretation(measures,
                List.of(openingNote(187, 221, -2, 1, 0), openingNote(188, 189, 2, 1, 0),
                        openingNote(257, 173, 4, -1, 1)),
                0, List.of(), List.of(), List.of(new ScoreMeterChange(1, 2, 4)));
    }

    private static ScoreNoteEvent openingNote(int x, int y, int step, int direction, int beams) {
        return new ScoreNoteEvent(1, (x - 115f) / 192, step, 0, 1, y / (float) H, false,
                0, beams, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, beams == 0 ? 1 : 0, 1,
                0, 0, ScoreNoteEvent.CLEF_TREBLE, false, 0, false, 0, 0, 1, direction,
                ScoreNoteEvent.Kind.PITCHED, ScoreNoteEvent.VisualSize.FULL);
    }

    private static byte[] openingPaper(String topDigit) throws Exception {
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        var image = new BufferedImage(54, 73, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 54, 73);
        graphics.setColor(Color.BLACK);
        try (var font = MeterFontMatcher.class.getResourceAsStream("Bravura.otf")) {
            graphics.setFont(Font.createFont(Font.TRUETYPE_FONT, font).deriveFont(62f));
        }
        graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        drawMeterDigit(graphics, topDigit.charAt(0), 4, 36);
        drawMeterDigit(graphics, '4', 36, 70);
        graphics.dispose();
        for (int y = 0; y < 73; y++)
            for (int x = 0; x < 54; x++)
                gray[(135 + y) * W + 115 + x] = (byte) (image.getRGB(x, y) & 255);

        for (int y : new int[] {140, 156, 172, 188, 204}) fill(gray, 115, y, 460, y + 1, 0);
        fill(gray, 314, 140, 316, 205, 0);
        fill(gray, 174, 220, 201, 221, 0);
        fill(gray, 196, 118, 198, 222, 0);
        fill(gray, 245, 146, 247, 232, 0);
        diamond(gray, 188, 165);
        diamond(gray, 187, 205);
        diamond(gray, 257, 149);
        fill(gray, 180, 185, 198, 193, 0);
        fill(gray, 179, 216, 197, 225, 0);
        fill(gray, 246, 169, 266, 177, 0);
        for (int dy = -11; dy <= 11; dy++) {
            int radius = 12 - Math.abs(dy);
            fill(gray, 257 - radius, 188 + dy, 257 + radius, 188 + dy, 0);
        }
        fill(gray, 251, 199, 262, 201, 255);
        return gray;
    }

    private static void drawMeterDigit(Graphics2D graphics, char digit, int top, int bottom) {
        String glyph = Character.toString(0xe080 + digit - '0');
        var bounds = graphics.getFont().createGlyphVector(graphics.getFontRenderContext(), glyph)
                .getPixelBounds(null, 0, 0);
        int x = (54 - bounds.width) / 2 - bounds.x;
        int y = top + (bottom - top - bounds.height) / 2 - bounds.y;
        graphics.drawString(glyph, x, y);
    }

    private static ScorePageInterpretation score(int beats) {
        return new ScorePageInterpretation(
                BARS, List.of(note(187, 116, 8, false), note(188, 156, 3, false),
                              note(244, 100, 10, false), note(245, 132, 6, true)),
                0, List.of(), List.of(), List.of(new ScoreMeterChange(0, beats, 4)));
    }

    private static byte[] paper() {
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte) 255);
        for (int y : new int[] {116, 132, 148, 164, 180}) fill(gray, 30, y, 508, y, 0);
        fill(gray, 151, 116, 153, 180, 0);
        fill(gray, 290, 116, 292, 180, 0);
        fill(gray, 455, 116, 457, 180, 0);
        fill(gray, 171, 92, 204, 92, 0);
        fill(gray, 228, 76, 261, 76, 0);
        fill(gray, 228, 100, 261, 100, 0);
        fill(gray, 228, 108, 261, 108, 0);
        draw(gray, 187, 116, 188, 156, 196);
        draw(gray, 244, 100, 245, 132, 173);
        return gray;
    }

    private static void draw(byte[] gray, int x0, int y0, int x1, int y1, int end) {
        fill(gray, x0 - 11, y0 - 24, x0 - 9, end, 0);
        diamond(gray, x0, y0 - 24);
        diamond(gray, x1, y1 - 24);
        fill(gray, x0 - 10, y0 - 4, x0 + 8, y0 + 4, 0);
        fill(gray, x1 - 10, y1 - 4, x1 + 8, y1 + 4, 0);
    }

    private static void diamond(byte[] gray, int x, int y) {
        for (int dy = -11; dy <= 11; dy++)
            for (int dx = -11; dx <= 11; dx++) {
                int radius = Math.abs(dx) + Math.abs(dy);
                if (radius >= 7 && radius <= 12) gray[(y + dy) * W + x + dx] = 0;
            }
    }

    private static ScoreNoteEvent note(int x, int y, int step, boolean beamAlias) {
        return new ScoreNoteEvent(1, (x - 161f) / 121, step, 0, 1, y / (float) H, false,
                0, beamAlias ? 1 : 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, beamAlias ? 0 : 1, 1,
                0, 0, 30, false, 0, false, 2, 0, 1, -1, ScoreNoteEvent.Kind.PITCHED,
                ScoreNoteEvent.VisualSize.FULL);
    }

    private static MeasureRegion bar(int left, int right) {
        return new MeasureRegion(left / (float) W, right / (float) W, 84f / H, 216f / H);
    }

    private static void fill(byte[] image, int left, int top, int right, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(image, y * W + left, y * W + right + 1, (byte) value);
    }
}
