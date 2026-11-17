// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class CompactWholeSegueCaptionTest {
    private static final int W=800,H=280,GAP=16;
    private static final MeasureRegion BAR=new MeasureRegion(120f/W,650f/W,76f/H,220f/H);
    private static final PlayingTechniqueDetector.Staff STAFF=
            new PlayingTechniqueDetector.Staff(112,176,GAP,0,1);
    private static final PlayingTechniqueDetector.Word CAPTION=
            new PlayingTechniqueDetector.Word("1 segue",450f/W,179f/H,607f/W,217f/H);

    @Test
    public void releasesOnlyTheCaptionHeadAndStraightensItsCompleteGroups() {
        var notes=recognition();
        var original=ink();
        var corrected=apply(original,notes,List.of(CAPTION));
        assertEquals(12,corrected.size());
        for(int i=0;i<corrected.size();i++) {
            var note=corrected.get(i);
            var placement=ScoreNoteTiming.writtenPlacement(note,corrected,3).orElseThrow();
            assertEquals(i*.25,placement.onsetBeats(),.00001);
            assertEquals(.25,placement.durationBeats(),.00001);
        }
        assertEquals(notes,apply(original,notes,List.of()));
        var erased=original.clone();
        paint(erased,485,503,183,204,255);
        assertEquals(notes,apply(erased,notes,List.of(CAPTION)));
    }

    @Test
    public void anIndependentTripletWithItsOwnClosingBeamRetainsItsExactRatios() {
        var notes=recognition();
        var source=ink();
        // Separate the first three shafts from the fourth so their beamed triplet retains its own end.
        paint(source,239,263,146,161,255);
        var kept=apply(source,notes,List.of(CAPTION));
        assertEquals(notes,kept);
        assertEquals(List.of(3,3,3),kept.subList(0,3).stream().map(ScoreNoteEvent::tupletDivisor).toList());
    }

    private static List<ScoreNoteEvent> apply(byte[] gray,List<ScoreNoteEvent> notes,
            List<PlayingTechniqueDetector.Word> words) {
        return FingeringAnnotationFilter.apply(words,List.of(STAFF),List.of(BAR),notes,gray,W,H);
    }

    private static List<ScoreNoteEvent> recognition() {
        var notes=new ArrayList<ScoreNoteEvent>();
        for(int i=0;i<12;i++) {
            boolean triplet=i<3 || i>=8 && i<11;
            notes.add(head(145+44*i,104,9,2,triplet?3:1,-1));
        }
        notes.add(head(495,193,-1,0,1,0));
        return notes;
    }

    private static ScoreNoteEvent head(int x,int y,int step,int beams,int actual,int stem) {
        return new ScoreNoteEvent(0,(x-120f)/530f,step,0,1,y/(float)H,false,0,beams,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,beams==0?4:0,actual,0,0,ScoreNoteEvent.CLEF_TREBLE,
                false,0,false,0,0,actual==3?2:1,stem);
    }

    private static byte[] ink() {
        var gray=new byte[W*H];
        Arrays.fill(gray,(byte)255);
        for(int y=112;y<=176;y+=GAP)paint(gray,109,671,y,y,0);
        paint(gray,650,652,112,176,0);
        for(int group=0;group<12;group+=4) {
            int first=145+44*group-10,last=first+44*3+1;
            paint(gray,first,last,146,150,0);
            paint(gray,first,last,157,161,0);
            for(int i=group;i<group+4;i++) {
                int x=145+44*i;
                paint(gray,x-10,x-9,104,161,0);
                paint(gray,x-10,x-2,103,106,0);
                for(int y=-6;y<=6;y++)
                    for(int dx=-8;dx<=8;dx++)
                        if(dx*dx/64d+y*y/36d<=1) gray[(104+y)*W+x+dx]=0;
            }
        }
        paint(gray,462,475,186,202,0);
        paint(gray,488,501,186,202,0);
        paint(gray,506,520,186,202,0);
        paint(gray,525,539,186,210,0);
        paint(gray,544,558,186,202,0);
        paint(gray,563,579,186,202,0);
        return gray;
    }

    private static void paint(byte[] gray,int x1,int x2,int y1,int y2,int value) {
        for(int y=y1;y<=y2;y++)Arrays.fill(gray,y*W+x1,y*W+x2+1,(byte)value);
    }
}
