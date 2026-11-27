// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class RestAndArcoEvidenceTest {
    private static final int WIDTH = 520, HEIGHT = 400;

    @Test public void missedArcoLetterNeedsFourGlyphsAndAnOpenC() {
        byte[] gray = paper();
        rectangle(gray,160,125,172,143);
        rectangle(gray,180,125,194,143);
        rectangle(gray,202,125,205,143);
        rectangle(gray,202,125,218,127);
        rectangle(gray,202,141,218,143);
        rectangle(gray,226,125,243,127);
        rectangle(gray,226,141,243,143);
        rectangle(gray,226,125,229,143);
        rectangle(gray,240,125,243,143);
        var words = List.of(new PlayingTechniqueDetector.Word(
                "arO",150f/WIDTH,118f/HEIGHT,257f/WIDTH,150f/HEIGHT));
        assertEquals("arco", PrintedArcoWord.recover(words,gray,WIDTH,HEIGHT).get(0).text());
        rectangle(gray,215,125,218,143);
        assertSame(words, PrintedArcoWord.recover(words,gray,WIDTH,HEIGHT));
    }

    @Test public void explicitWholeRestNeedsInkOnBothPlanesAndAnUnownedBar() {
        var measure = new MeasureRegion(120f/WIDTH,390f/WIDTH,100f/HEIGHT,230f/HEIGHT);
        var page = new ScorePageInterpretation(List.of(measure),List.of());
        byte[] paper = paper();
        for (int y=134; y<=198; y+=16) rectangle(paper,120,y,389,y);
        byte[] mark = paper.clone();
        rectangle(mark,246,150,264,157);
        assertSame(page, CenteredWholeMeasureRest.recover(page,paper,mark,WIDTH,HEIGHT));
        var repaired = CenteredWholeMeasureRest.recover(page,mark,mark.clone(),WIDTH,HEIGHT);
        assertEquals(1,repaired.rests().size());
        assertTrue(repaired.rests().get(0).isFullMeasure());
        assertEquals(1,repaired.clockContextSource().restCount());
        assertSame(repaired, CenteredWholeMeasureRest.recover(repaired,mark,mark,WIDTH,HEIGHT));
    }

    private static byte[] paper() {
        byte[] gray = new byte[WIDTH*HEIGHT];
        Arrays.fill(gray,(byte)255);
        return gray;
    }

    private static void rectangle(byte[] gray,int left,int top,int right,int bottom) {
        for (int y=top; y<=bottom; y++)
            Arrays.fill(gray,y*WIDTH+left,y*WIDTH+right+1,(byte)0);
    }
}
