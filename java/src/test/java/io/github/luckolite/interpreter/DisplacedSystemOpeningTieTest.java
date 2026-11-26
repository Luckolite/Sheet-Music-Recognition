// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class DisplacedSystemOpeningTieTest {
    private static final int W=800, H=500, GAP=16;

    @Test
    public void twoSpansAndNeighboringPitchRestoreTheDisplacedHalfNote() throws Exception {
        var notes=run(5,true,ScoreNoteEvent.ACCIDENTAL_FROM_KEY);
        assertEquals(-2,notes.get(1).staffStep());
        assertTrue(notes.get(1).tiedFromPrevious());
        assertTrue(notes.get(2).tiedFromPrevious());
    }

    @Test
    public void requiresBothPrintedSpansAndPreservesARealAdjacentPitch() throws Exception {
        assertUnchanged(run(5,false,ScoreNoteEvent.ACCIDENTAL_FROM_KEY));
        assertUnchanged(run(8,true,ScoreNoteEvent.ACCIDENTAL_FROM_KEY));
        assertUnchanged(run(5,true,ScoreNoteEvent.ACCIDENTAL_NATURAL));
    }

    private static void assertUnchanged(List<ScoreNoteEvent> notes) {
        assertEquals(-3,notes.get(1).staffStep());
        assertFalse(notes.get(1).tiedFromPrevious());
        assertFalse(notes.get(2).tiedFromPrevious());
    }

    private static List<ScoreNoteEvent> run(int displacement,boolean nextArc,int accidental)
            throws Exception {
        byte[] gray=new byte[W*H],labels=new byte[W*H];
        Arrays.fill(gray,(byte)244);
        for(int top:new int[]{88,300})
            for(int line=0;line<5;line++)
                for(int x=32;x<780;x++) ink(gray,labels,x,top+line*GAP,4);
        int y=380+displacement;
        hollow(gray,labels,650,168);
        hollow(gray,labels,180,y);
        hollow(gray,labels,262,380);
        arc(gray,labels,662,753,168);
        arc(gray,labels,135,168,y);
        if(nextArc) arc(gray,labels,189,254,Math.round((y+380)*.5f));

        var a=note(0,.84f,-2,168,4,0,ScoreNoteEvent.ACCIDENTAL_FROM_KEY);
        var opening=note(1,.18f,-3,y,2,0,accidental);
        var b=note(1,.54f,-2,380,1,1,ScoreNoteEvent.ACCIDENTAL_FROM_KEY);
        var original=List.of(detected(a,650,168),detected(opening,180,y),detected(b,262,380));
        var staffs=List.of(PrintedSystemTieFrameTest.make("Staff",88f,152f,16f),
                PrintedSystemTieFrameTest.make("Staff",300f,364f,16f));
        var method=OmrScoreInterpreter.class.getDeclaredMethod("markTieContinuations",
                byte[].class,byte[].class,int.class,int.class,List.class,List.class);
        method.setAccessible(true);
        var results=new ArrayList<ScoreNoteEvent>();
        for(var pair:(List<?>)method.invoke(null,labels,gray,W,H,original,staffs))
            results.add((ScoreNoteEvent)PrintedSystemTieFrameTest.field(pair,"event"));
        return results;
    }

    private static Object detected(ScoreNoteEvent note,int x,int y) throws Exception {
        var head=PrintedSystemTieFrameTest.make("Component",80,x-7,x+7,y-5,y+5,(float)x,(float)y);
        return PrintedSystemTieFrameTest.make("DetectedNote",note,head,16f);
    }

    private static ScoreNoteEvent note(int m,float x,int step,int y,double beats,int dots,int accidental) {
        return new ScoreNoteEvent(m,x,step,0,1,y/(float)H,false,dots,0,accidental,(float)beats,1,0,0,30);
    }

    private static void hollow(byte[] gray,byte[] labels,int cx,int cy) {
        for(int dy=-5;dy<=5;dy++) for(int dx=-7;dx<=7;dx++) {
            double r=dx*dx/49d+dy*dy/25d;
            if(r>.99) continue;
            int p=(cy+dy)*W+cx+dx;
            labels[p]=2;
            if(r>.39) gray[p]=15;
        }
    }

    private static void arc(byte[] gray,byte[] labels,int left,int right,int y) {
        for(int x=left;x<=right;x++) {
            float t=(x-left)/(float)(right-left);
            int row=Math.round(y+8+24*t*(1-t));
            ink(gray,labels,x,row,5);
            ink(gray,labels,x,row+1,5);
        }
    }

    private static void ink(byte[] gray,byte[] labels,int x,int y,int mark) {
        gray[y*W+x]=15;
        labels[y*W+x]=(byte)mark;
    }
}
