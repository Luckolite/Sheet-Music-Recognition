// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Procedural short opening examples and a separately metered staff. */
public class PrintedOpeningSixteenthExamplesTest {
    private static final int W = 1600, H = 690, GAP = 16, TOP = 170, BOTTOM = TOP + 4 * GAP;
    private static final int LEFT = 90, SPAN = 410, PITCH_Y = TOP + 8;
    private static final float[] ORIGINAL = {4,4,4,4}, PROVED = {2,2,2,4};

    @Test
    public void countsEachFullyWrittenExampleAndReusesOrdinarySixteenthTiming() {
        var plate = new Plate();
        var score = plate.score(List.of());
        assertArrayEquals(PROVED, plate.beats(score, plate.paper.clone()), 0);
        try (var timing = ScoreNoteTiming.beginTimingSession(score)) {
            for (int i = 0; i < 24; i++)
                assertEquals((i % 8) * .25, ScoreNoteTiming.beatInMeasure(
                        score.notes().get(i), score.notes(), PROVED[i / 8]), .0001);
        }
    }

    @Test
    public void aDividerBeamOrShaftMustBePresentInBothPlanes() {
        var plate = new Plate();
        var score = plate.score(List.of());
        for (int[] erase : List.of(
                new int[]{510, 513, TOP + 1, BOTTOM - 1},
                new int[]{172, 205, 140, 146},
                new int[]{178, 184, 162, 171},
                new int[]{150, 184, 521, 566})) {
            byte[] incomplete = plate.paper.clone();
            draw(incomplete,erase[0],erase[1],erase[2],erase[3],(byte)255);
            assertArrayEquals(ORIGINAL, plate.beats(score, incomplete), 0);
        }
    }

    @Test
    public void anExplicitOpeningMeterOrAClosedStaffKeepsExistingCapacity() {
        var plate = new Plate();
        assertArrayEquals(ORIGINAL, plate.beats(plate.score(List.of(new ScoreMeterChange(0,4,4))),
                plate.paper.clone()), 0);
        byte[] closed = plate.paper.clone();
        draw(closed, 1453, 1455, TOP, BOTTOM,(byte)0);
        assertArrayEquals(ORIGINAL, plate.beats(plate.score(List.of()), closed), 0);
    }

    private static final class Plate {
        final byte[] paper = new byte[W * H], labels = new byte[W * H];
        final List<MeasureRegion> regions = new ArrayList<>();
        final List<ScoreNoteEvent> notes = new ArrayList<>();

        Plate() {
            Arrays.fill(paper, (byte)255);
            for (int y = TOP; y <= BOTTOM; y += GAP) draw(paper, 30, 1452, y,y+1,(byte)0);
            for (int bar = 0; bar < 3; bar++) {
                int left = LEFT + bar * 428, right = left + SPAN + (bar == 2 ? 96 : 0);
                regions.add(region(left, right, TOP));
                if (bar < 2) {
                    draw(paper,right+2,right+4,TOP,BOTTOM,(byte)0);
                    draw(paper,right+10,right+12,TOP,BOTTOM,(byte)0);
                }
                for (int offset : new int[]{38,82,126,170,232,276,320,364}) {
                    int x = left + offset;
                    draw(paper, x + 7,x + 10,140,PITCH_Y,(byte)0);
                    draw(paper, x - 8,x + 8,PITCH_Y-5,PITCH_Y+5,(byte)0);
                    notes.add(new ScoreNoteEvent(bar, offset / (float) (right - left), 7, 0, 1,
                            PITCH_Y / (float) H, false, 0, 2,
                            ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0, 1, 0, 0, 30, false,
                            0, false, 0, 0, 1, 1, ScoreNoteEvent.Kind.PITCHED));
                }
                for (int group : new int[]{38,232}) {
                    int x = left + group + 7;
                    draw(paper,x,x+135,140,146,(byte)0);
                    draw(paper,x,x+135,155,160,(byte)0);
                }
            }
            var header = new HeaderSymbolNormalizationTest.Page(false,true);
            for (int y=0;y<header.h;y++) {
                System.arraycopy(header.gray, y*header.w, paper, (y+430)*W+40, header.w);
                System.arraycopy(header.labels, y*header.w, labels, (y+430)*W+40, header.w);
            }
            regions.add(region(119,485,510));
        }

        ScorePageInterpretation score(List<ScoreMeterChange> meters) {
            return new ScorePageInterpretation(regions, notes, 0, List.of(), List.of(), meters);
        }

        float[] beats(ScorePageInterpretation score, byte[] original) {
            var result=ORIGINAL.clone();
            PrintedOpeningSixteenthExamples.apply(score, labels, paper, original, W, H, result);
            return result;
        }
    }

    private static MeasureRegion region(int left,int right,int top) {
        return new MeasureRegion(left/(float)W, right/(float)W,
                (top-2.1f*GAP)/H,(top+6.1f*GAP)/H);
    }

    private static void draw(byte[] gray,int left,int right,int top,int bottom,byte value) {
        for (int row=top;row<=bottom;row++)
            Arrays.fill(gray,row*W+left,row*W+right+1,value);
    }
}
