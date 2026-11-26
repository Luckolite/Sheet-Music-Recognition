// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class BracketedDupletsTest {
    private static final int W = 500, H = 210;
    private static final List<MeasureRegion> BARS = List.of(
            new MeasureRegion(.06f,.94f,.11f,.85f));

    @Test public void bothArmsAndHooksOwnTheMixedValuePair() {
        var notes = source();
        var recognized = BracketedDuplets.capture(notes,BARS,page(true),W,H);
        assertEquals(List.of(1,2,2),recognized.stream().map(ScoreNoteEvent::tupletDivisor).toList());
        assertEquals(List.of(1,2,2),recognized.stream().map(ScoreNoteEvent::tupletNormalNotes).toList());
        assertEquals(notes,BracketedDuplets.capture(notes,BARS,page(false),W,H));
    }

    @Test public void meterAndCompleteLaneAuthorizeTheCompoundRatio() {
        var notes = source();
        notes = List.of(notes.get(0),notes.get(1).withTupletRatio(2,2),notes.get(2).withTupletRatio(2,2));
        var compound = List.of(new ScoreMeterChange(0,12,8));
        var resolved = BracketedDuplets.resolve(notes,compound,false);
        assertEquals(2.25,ScoreNoteTiming.writtenDurationBeats(resolved.get(1)),0);
        assertEquals(.75,ScoreNoteTiming.writtenDurationBeats(resolved.get(2)),0);
        assertEquals(notes,BracketedDuplets.resolve(notes,compound,true));
        assertEquals(notes,BracketedDuplets.resolve(notes,List.of(new ScoreMeterChange(0,4,4)),false));
        assertEquals(notes,BracketedDuplets.resolve(notes,List.of(new ScoreMeterChange(0,9,8)),false));
        assertEquals(notes,BracketedDuplets.resolve(notes,List.of(compound.get(0),new ScoreMeterChange(0,9,8)),false));
    }

    private static List<ScoreNoteEvent> source() {
        return List.of(note(79,10,0,2,1),note(205,12,0,1,1),note(410,14,1,0,0));
    }

    private static ScoreNoteEvent note(int x,int step,int beam,float duration,int dots) {
        return new ScoreNoteEvent(0,(x-30f)/440,step,0,1,.27f,false,dots,beam,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,duration).withStemDirection(-1);
    }

    private static byte[] page(boolean rightHook) {
        byte[] gray = new byte[W * H];
        Arrays.fill(gray, (byte)255);
        for (int y=60;y<=124;y+=16) paint(gray,24,473,y,y);
        paint(gray,188,291,146,147);
        paint(gray,323,422,146,147);
        paint(gray,188,189,132,147);
        if (rightHook) paint(gray,421,422,132,147);
        paint(gray,300,314,135,138);
        paint(gray,311,314,138,144);
        for (int y=144;y<=154;y++) paint(gray,311-(y-144),314-(y-144),y,y);
        paint(gray,299,315,153,156);
        return gray;
    }

    private static void paint(byte[] gray,int first,int last,int top,int bottom) {
        for (int y=top;y<=bottom;y++) Arrays.fill(gray,y*W+first,y*W+last+1,(byte)0);
    }
}
