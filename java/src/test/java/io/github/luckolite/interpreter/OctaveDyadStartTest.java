// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class OctaveDyadStartTest {
    private static final int W=600, H=290, GAP=16;
    private static final float LEFT=176.6f, RIGHT=501f;
    private static final int[] X={129,179,214,252}, Y={122,106,147,131};
    private static final int[] END={239,229,254,246};

    @Test
    public void selectsNextDyadOnlyAcrossSeparatePrintedDoubleBeams() throws Exception {
        byte[] raw=new byte[W*H];
        Arrays.fill(raw,(byte)255);
        for(int row=148;row<=212;row+=GAP) fill(raw,75,row,522,row+1,0);
        var notes=new ArrayList<ScoreNoteEvent>();
        for(int i=0;i<X.length;i++) {
            int step=(i==0?10:i==1?12:i==2?7:9);
            for(int k=0;k<2;k++) {
                int y=Y[i]+k*57;
                notes.add(new ScoreNoteEvent(0,X[i]/(float)W,step-k*7,0,1,y/(float)H,
                        false,0,2,2,0f).withStemDirection(-1));
                oval(raw,X[i],y);
            }
            fill(raw,X[i]-11,Y[i]+3,X[i]-8,END[i],0);
        }
        beam(raw,0,1,true);
        beam(raw,2,3,true);
        fill(raw,190,59,208,69,0);
        fill(raw,191,68,208,82,0);
        fill(raw,193,61,205,66,255);
        fill(raw,194,72,205,79,255);
        for(int x=216;x<=492;x+=8) fill(raw,x,64,Math.min(x+3,492),65,0);
        fill(raw,491,64,492,65,0);
        fill(raw,491,69,492,71,0);
        fill(raw,491,76,492,78,0);

        var word=new PlayingTechniqueDetector.Word("8va",187f/W,57f/H,212f/W,85f/H);
        var staff=new PlayingTechniqueDetector.Staff(148,212,GAP,0,1);
        var find=OctaveMarkDetector.class.getDeclaredMethod("closedDyadOctaveStart",byte[].class,
                int.class,int.class,PlayingTechniqueDetector.Word.class,PlayingTechniqueDetector.Staff.class,
                List.class,List.class,List.class,float.class,float.class);
        find.setAccessible(true);
        assertEquals(196.5f,boundary(find,raw,word,staff,notes),.04f);
        byte[] joined=raw.clone();
        beam(joined,1,2,false);
        assertEquals(LEFT,boundary(find,joined,word,staff,notes),.04f);
    }

    private static float boundary(java.lang.reflect.Method method,byte[] raw,
            PlayingTechniqueDetector.Word word,PlayingTechniqueDetector.Staff staff,List<ScoreNoteEvent> notes)
            throws Exception {
        return (float)method.invoke(null,raw,W,H,word,staff,List.of(staff),
                List.of(new MeasureRegion(0,1,0,1)),notes,LEFT,RIGHT);
    }

    private static void beam(byte[] raw,int a,int b,boolean doubleRail) {
        int start=X[a]-10,last=X[b]-10;
        for(int x=start;x<=last;x++) {
            int end=Math.round(END[a]+(END[b]-END[a])*(x-start)/(float)(last-start));
            fill(raw,x,end-4,x,end,0);
            if(doubleRail)fill(raw,x,end-15,x,end-11,0);
        }
    }

    private static void oval(byte[] raw,int centerX,int centerY) {
        for(int dy=-7;dy<=7;dy++)
            for(int dx=-11;dx<=11;dx++)
                if(dx*dx/121f+dy*dy/49f<=1)raw[(centerY+dy)*W+centerX+dx]=0;
    }

    private static void fill(byte[] raw,int left,int top,int right,int bottom,int shade) {
        for(int y=top;y<=bottom;y++) Arrays.fill(raw,y*W+left,y*W+right+1,(byte)shade);
    }
}
