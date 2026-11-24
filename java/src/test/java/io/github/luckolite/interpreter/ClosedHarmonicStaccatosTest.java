// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ClosedHarmonicStaccatosTest {
    private static final int W=660,H=424,G=16,LEFT=43,FIRST=73,SPACING=31,FLOOR=265;
    private static final int RIGHT=FIRST+15*SPACING+15;
    private static final ScoreMeterChange METER=new ScoreMeterChange(0,4,4);

    @Test
    public void detachedStaccatosLeaveSixteenAttacksAndFourQuarters() {
        var source=score();
        var ink=scene();
        var after=ClosedSixteenthDyads.apply(source,METER,ink,ink,W,H);
        for(int i=0;i<16;i++) {
            var n=after.notes().get(i);
            assertEquals(0,n.augmentationDots());
            assertEquals(i%4==3?NoteArticulation.STACCATO:0,n.articulations());
            var placement=ScoreNoteTiming.writtenPlacement(n,after.notes(),4).orElseThrow();
            assertEquals(.25*i,placement.onsetBeats(),0);
            assertEquals(.25,placement.durationBeats(),0);
        }
        byte[] absent=ink.clone();
        box(absent,x(7)-3,x(7)+3,y(7)+14,y(7)+22,(byte)255);
        assertSame(source,ClosedSixteenthDyads.apply(source,METER,ink,absent,W,H));
        byte[] extra=ink.clone();
        box(extra,x(6)+14,x(6)+18,y(6)-4,y(6),(byte)0);
        assertSame(source,ClosedSixteenthDyads.apply(source,METER,extra,ink,W,H));
        byte[] open=ink.clone();
        box(open,RIGHT-1,RIGHT+7,FLOOR-4*G-1,FLOOR+1,(byte)255);
        assertSame(source,ClosedSixteenthDyads.apply(source,METER,ink,open,W,H));
    }

    private static ScorePageInterpretation score() {
        var measure=new MeasureRegion((float)LEFT/W,(float)RIGHT/W,
                (FLOOR-98f)/H,(FLOOR+33f)/H);
        var notes=new ArrayList<ScoreNoteEvent>(16);
        for(int i=0;i<16;i++) {
            notes.add(new ScoreNoteEvent(0,(float)(x(i)-LEFT)/(RIGHT-LEFT),
                    Math.floorDiv(FLOOR-y(i),8),0,1,(float)y(i)/H,false,
                    i==6 || i==10 ? 1:0,2,ScoreNoteEvent.ACCIDENTAL_FROM_KEY,0)
                    .withClef(ScoreNoteEvent.CLEF_TREBLE).withStemDirection(i<12?1:-1)
                    .withOctaveShift(2).withArticulations(i%4==3?NoteArticulation.STACCATO:0));
        }
        return new ScorePageInterpretation(List.of(measure),notes);
    }

    private static byte[] scene() {
        var ink=new byte[W*H];
        Arrays.fill(ink,(byte)255);
        for(int row=0;row<5;row++) box(ink,20,W-21,FLOOR-row*G,FLOOR-row*G+1,(byte)0);
        for(int x:new int[]{LEFT,RIGHT+3}) box(ink,x,x+2,FLOOR-4*G,FLOOR+1,(byte)0);
        for(int i=0;i<16;i++) {
            int x=x(i),y=y(i);
            for(int row=y-8;row<=y+8;row++) for(int col=x-10;col<=x+10;col++) {
                float u=(col-x)/10f,v=(row-y)/7.5f;
                if(u*u+v*v+u*v*.35f<1) ink[row*W+col]=0;
            }
            int stem=i<12?x+8:x-9;
            int end=i<12?174:325;
            box(ink,stem,stem+2,Math.min(y,end),Math.max(y,end),(byte)0);
            if(i%4!=3) {
                box(ink,stem,stem+SPACING+2,end-2,end+1,(byte)0);
                int inside=i<12?end+10:end-12;
                box(ink,stem,stem+SPACING+2,inside,inside+4,(byte)0);
            }
            if(i%4==3) box(ink,x-2,x+2,y+16,y+20,(byte)0);
        }
        return ink;
    }

    private static int x(int i) { return FIRST+i*SPACING; }
    private static int y(int i) { return i<12 ? 278 : 241; }
    private static void box(byte[] ink,int l,int r,int t,int b,byte color) {
        for(int y=t;y<=b;y++) Arrays.fill(ink,y*W+l,y*W+r+1,color);
    }
}
