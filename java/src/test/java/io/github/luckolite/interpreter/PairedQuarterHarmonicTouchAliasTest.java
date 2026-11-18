// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class PairedQuarterHarmonicTouchAliasTest {
    private static final int W = 460, H = 258;
    private static final int RIGHT = 238, TOUCH = 162;
    private static final List<MeasureRegion> BARS =
            List.of(region(24, 102, 0), region(120, 288, 0), region(306, 442, 1));

    @Test
    public void sameShaftHollowFourthDoesNotAddAHalfNote() {
        var score = score();
        var gray = image();
        var result = recognize(score, gray, gray);
        assertEquals(score.measures(), result.measures());
        assertEquals(List.of(score.notes().get(0), score.notes().get(1), score.notes().get(2),
                score.notes().get(3), score.notes().get(5)), result.notes());
    }

    @Test
    public void bothSourcesNeedTheClosingRuleAndHollowTouch() {
        var score = score();
        byte[] source = image();
        byte[] noRule = source.clone();
        fill(noRule, 290, 105, 301, 171, 255);
        assertSame(score, recognize(score, source, noRule));

        byte[] filled = source.clone();
        fill(filled, RIGHT - 5, TOUCH - 4, RIGHT + 5, TOUCH + 4, 0);
        assertSame(score, recognize(score, source, filled));
    }

    private static ScorePageInterpretation recognize(ScorePageInterpretation s, byte[] a, byte[] b) {
        return PenultimateHarmonicQuarterAlias.capture(s,new byte[W * H],a,b,W,H);
    }

    private static ScorePageInterpretation score() {
        return new ScorePageInterpretation(BARS,
                List.of(note(156, 129, 5, 1, -1), note(157, 146, 3, 2, -1),
                        note(156, 162, 1, 1, -1), note(RIGHT, 146, 3, 1, 1),
                        note(RIGHT, TOUCH, 1, 2, 1), note(RIGHT+1, 186, -2, 1, 1)),
                0,List.of(),List.of(),List.of(new ScoreMeterChange(0,2,4)));
    }

    private static ScoreNoteEvent note(int x, int y, int step, int beats, int stem) {
        return new ScoreNoteEvent(1, (x - 120f) / 168f, step, 0, 1, y / (float)H, false,
                0, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, beats, 1, 0, 0, 30, false, 0, false,
                0, 0, 1, stem, ScoreNoteEvent.Kind.PITCHED, ScoreNoteEvent.VisualSize.FULL);
    }

    private static MeasureRegion region(int left, int right, int cadence) {
        return new MeasureRegion(left / (float)W, right / (float)W, 70f / H, 203f / H, cadence);
    }

    private static byte[] image() {
        var gray = new byte[W * H];
        Arrays.fill(gray, (byte)255);
        for (int y = 106; y <= 170; y += 16) fill(gray, 24, y, 441, y, 0);
        fill(gray, 110, 106, 112, 170, 0);
        fill(gray, 295, 106, 297, 170, 0);
        fill(gray, RIGHT - 16, 186, RIGHT + 16, 186, 0);
        diamond(gray, RIGHT, 122);
        diamond(gray, RIGHT, TOUCH);
        ellipse(gray, RIGHT, 146);
        ellipse(gray, RIGHT+1, 186);
        fill(gray, RIGHT + 9, 95, RIGHT + 11, 187, 0);
        return gray;
    }

    private static void ellipse(byte[] gray, int x, int y) {
        for(int j=-6;j<=6;j++) for(int i=-10;i<=10;i++)
            if(i*i/100d+j*j/36d<=1) gray[(y+j)*W+x+i]=0;
    }

    private static void diamond(byte[] gray, int x, int y) {
        for(int j=-11;j<=11;j++) for(int i=-11;i<=11;i++) {
            int d=Math.abs(i)+Math.abs(j);
            if(d>=7&&d<=11) gray[(y+j)*W+x+i]=0;
        }
    }

    private static void fill(byte[] gray, int left, int top, int right, int bottom, int value) {
        for(int y=top;y<=bottom;y++)
            Arrays.fill(gray,y*W+left,y*W+right+1,(byte)value);
    }
}
