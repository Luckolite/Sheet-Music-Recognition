package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original synthetic tempo glyphs; no score images. */
public class FlaggedTempoTest {
    private ScoreTempoChange mark(boolean flag, boolean dot, int scale) {
        return mark(flag, dot, scale, false, false);
    }

    private ScoreTempoChange mark(
            boolean flag, boolean dot, int scale, boolean precedingInk, boolean equalsFringe) {
        int w = 300 * scale, h = 160 * scale;
        byte[] gray = new byte[w * h];
        Arrays.fill(gray, (byte) 255);
        rect(gray, w, scale, 80, 40, 2, 28);
        rect(gray, w, scale, 73, 64, 9, 6);
        if (flag) {
            rect(gray, w, scale, 82, 41, 4, 4);
            rect(gray, w, scale, 85, 44, 4, 7);
            rect(gray, w, scale, 87, 50, 2, 6);
        }
        if (precedingInk) {
            rect(gray, w, scale, 59, 40, 2, 28);
            rect(gray, w, scale, 56, 64, 7, 6);
            rect(gray, w, scale, 61, 41, 4, 4);
            rect(gray, w, scale, 64, 44, 4, 7);
        }
        if (equalsFringe) rect(gray, w, scale, 82, 56, 4, 4);
        if (dot) rect(gray, w, scale, flag ? 93 : 87, 65, 3, 3);
        rect(gray, w, scale, 104, 58, 11, 2);
        rect(gray, w, scale, 104, 63, 11, 2);
        rect(gray, w, scale, 120, 54, 3, 16);
        return TempoChangeDetector.detect(
                        List.of(
                                new MeasureNumberReconciler.NumberToken(
                                        163, 120f / 300, 54f / 160, 145f / 300, 70f / 160)),
                        gray,
                        w,
                        h,
                        List.of(new MeasureRegion(.2f, .9f, .55f, .9f)))
                .get(0);
    }

    private void rect(byte[] gray, int w, int s, int x, int y, int a, int b) {
        for (int yy = y * s; yy < (y + b) * s; yy++)
            for (int xx = x * s; xx < (x + a) * s; xx++) gray[yy * w + xx] = 0;
    }

    @Test
    public void eighthNotePulseRetainsHalfBpmWithoutRounding() {
        for (int s = 1; s <= 3; s++)
            assertEquals(new ScoreTempoChange(0, 0, 81.5, .5), mark(true, false, s));
    }

    @Test
    public void plainQuarterRetainsItsOriginalTempo() {
        assertEquals(new ScoreTempoChange(0, 0, 163), mark(false, false, 1));
    }

    @Test
    public void dottedEighthAndQuarterUseTheirOwnPulseLengths() {
        assertEquals(new ScoreTempoChange(0, 0, 122.25, .75), mark(true, true, 2));
        assertEquals(new ScoreTempoChange(0, 0, 244.5, 1.5), mark(false, true, 2));
    }

    @Test
    public void nearerBeatNoteOwnsTheEquationAfterSimilarText() {
        assertEquals(new ScoreTempoChange(0, 0, 163), mark(false, false, 1, true, false));
    }

    @Test
    public void equalsFringeRetainsTheQuarterPulse() {
        assertEquals(new ScoreTempoChange(0, 0, 163), mark(false, false, 1, false, true));
    }

    @Test
    public void darkCoreSeparatesANearerBeatNoteFromTheTempoWord() {
        int width = 300, height = 160;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 255);
        rect(gray, width, 1, 56, 40, 2, 28);
        rect(gray, width, 1, 51, 64, 7, 6);
        rect(gray, width, 1, 58, 41, 4, 4);
        rect(gray, width, 1, 61, 44, 4, 7);
        rect(gray, width, 1, 61, 60, 15, 9);
        rect(gray, width, 1, 86, 40, 2, 28);
        rect(gray, width, 1, 77, 64, 11, 6);
        gray[65 * width + 76] = (byte) 150;
        rect(gray, width, 1, 94, 58, 11, 2);
        rect(gray, width, 1, 94, 63, 11, 2);
        rect(gray, width, 1, 110, 54, 3, 16);
        var number = new MeasureNumberReconciler.NumberToken(
                80, 110f / 300, 54f / 160, 135f / 300, 70f / 160);
        var marks = TempoChangeDetector.detect(List.of(number), gray, width, height,
                List.of(new MeasureRegion(.2f, .9f, .55f, .9f)));
        assertEquals(List.of(new ScoreTempoChange(0, 0, 80)), marks);
    }
}
