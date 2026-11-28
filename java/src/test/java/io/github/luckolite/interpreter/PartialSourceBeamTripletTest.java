// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class PartialSourceBeamTripletTest {
    private static final int W = InStaffPrimaryBeamContextTest.W;
    private static final int H = InStaffPrimaryBeamContextTest.H;
    private static final List<MeasureRegion> BARS = List.of(new MeasureRegion(0, 1, 96f / H, 316f / H));

    private static byte[] page(boolean connected, boolean digit) throws Exception {
        var raster = new byte[W * H];
        java.util.Arrays.fill(raster, (byte) 255);
        for (int start : new int[]{135, 245})
            for (int y = start; y <= start + 64; y += 16)
                InStaffPrimaryBeamContextTest.line(raster, 20, y, 580, y, 1);
        for (int cx : new int[]{180, 222, 264}) {
            for (int y = 129; y <= 141; y++)
                for (int x = cx - 10; x <= cx + 10; x++)
                    if ((x - cx) * (x - cx) / 100.0 + (y - 135) * (y - 135) / 36.0 <= 1)
                        raster[y * W + x] = 0;
            InStaffPrimaryBeamContextTest.line(raster, cx - 10, 135, cx - 10, 185, 3);
        }
        if (connected) InStaffPrimaryBeamContextTest.line(raster, 170, 185, 254, 185, 5);
        if (digit) {
            var numeral = CurledTupletThreeTest.glyph(3, 22, .5f, .15f, .8f);
            for (int y = 0; y < 100; y++)
                for (int x = 0; x < 100; x++) {
                    int xx = 210 + x - numeral.left(), yy = 206 + y - numeral.top();
                    if (xx < 0 || xx >= W || yy < 0 || yy >= H) continue;
                    int v = numeral.gray()[y * 100 + x] & 255;
                    raster[yy * W + xx] = (byte) Math.min(raster[yy * W + xx] & 255, v);
                }
        }
        return raster;
    }

    private static ScorePageInterpretation score(int restStaff) {
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int x : new int[]{180, 222, 264}) {
            var note = new ScoreNoteEvent(0, x / (float) W, 12, 0, 2,
                    135f / H, false, 0, 1, 2, 0).withStemDirection(-1);
            notes.add(x == 264 ? note.withTupletRatio(3, 2) : note);
        }
        var source = new ScorePageInterpretation(BARS, notes);
        if (restStaff < 0) return source;
        return source.withRests(List.of(new ScoreRestEvent(0, 230f / W, 256f / H, .05f,
                restStaff, 2, .5)));
    }

    private static List<Integer> ratios(ScorePageInterpretation score, byte[] raster) {
        return TripletRhythmDetector.recoverSourceTriplets(score, raster, W, H)
                .notes().stream().map(ScoreNoteEvent::tupletDivisor).toList();
    }

    @Test
    public void completePrintedBeamUsesAllThreeHeadsDespiteOtherStaffRest() throws Exception {
        assertEquals(List.of(3, 3, 3), ratios(score(1), page(true, true)));
    }

    @Test
    public void missingNumeralOrBrokenBeamKeepsPartialEvidenceUnchanged() throws Exception {
        assertEquals(List.of(1, 1, 3), ratios(score(-1), page(true, false)));
        assertEquals(List.of(1, 1, 3), ratios(score(-1), page(false, true)));
    }

    @Test
    public void sameStaffRestPreventsAThreeAttackBeam() throws Exception {
        assertEquals(List.of(1, 1, 3), ratios(score(0), page(true, true)));
    }
    @Test
    public void accidentalSpacingAllowanceRequiresTheWidenedIntervalBeforeThatHead() {
        assertEquals(true, TripletRhythmDetector.matchingRepeatedThreeSpacing(11, 0.2f, 11, true, false));
        assertEquals(true, TripletRhythmDetector.matchingRepeatedThreeSpacing(0.2f, 11, 11, false, true));
        assertEquals(false, TripletRhythmDetector.matchingRepeatedThreeSpacing(11, 0.2f, 11, false, false));
        assertEquals(false, TripletRhythmDetector.matchingRepeatedThreeSpacing(11, 0.2f, 11, false, true));
        assertEquals(false, TripletRhythmDetector.matchingRepeatedThreeSpacing(6, 6, 11, true, true));
        assertEquals(false, TripletRhythmDetector.matchingRepeatedThreeSpacing(14, 0.2f, 11, true, false));
        assertEquals(true, TripletRhythmDetector.matchingRepeatedThreeSpacing(5, 4, 11, false, false));
    }
}
