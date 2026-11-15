// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class TerminalMixedHarmonicCadenceTest {
    private static final int W=300,H=240,X=199,LOW=172,HIGH=156;
    private final byte[] gray=new byte[W*H],labels=new byte[W*H];

    @Test
    public void singleShaftOwnsTheTerminalMixedQuarter() {
        draw();
        var before=page();
        var after=TerminalMixedHarmonicCadence.capture(before,labels,gray,gray,W,H);
        assertEquals(1,after.measures().get(1).cadenceQuarterBeats());
        var touch = after.notes().get(0);
        assertEquals(2, touch.octaveShift());
        assertEquals(0, touch.harmonicSemitones());
        assertEquals(
                ScoreTiePitchGuard.midi(before.notes().get(0), before.keyChanges()) + 24,
                ScoreTiePitchGuard.midi(touch, after.keyChanges()));
        assertEquals(1f, touch.unbeamedDurationBeats(), 0f);
        assertEquals(before.notes().get(1),after.notes().get(1));
    }

    @Test
    public void clippedStemAndHollowStoppedHeadKeepTheOriginalValue() {
        draw();
        var before=page();
        var erased=gray.clone();
        for(int i=164;i<=172;i++)Arrays.fill(erased,i*W+205,i*W+215,(byte)255);
        assertSame(before,TerminalMixedHarmonicCadence.capture(before,labels,gray,erased,W,H));
        erased=gray.clone();
        for(int i=HIGH-2;i<=HIGH+2;i++)Arrays.fill(erased,i*W+X-4,i*W+X+5,(byte)255);
        assertSame(before,TerminalMixedHarmonicCadence.capture(before,labels,gray,erased,W,H));
    }

    private ScorePageInterpretation page() {
        return new ScorePageInterpretation(
                List.of(region(42,145),region(153,256)),List.of(note(-3,LOW,2,0),note(-1,HIGH,1,2)),0,
                List.of(new ScoreKeyChange(0,5)),List.of(),List.of(),List.of(),
                List.of(),List.of(),List.of());
    }

    private MeasureRegion region(int left,int right) {
        return new MeasureRegion(left/(float)W,right/(float)W,50f/H,185.9f/H);
    }

    private ScoreNoteEvent note(int step,int y,int beats,int octave) {
        return new ScoreNoteEvent(1,(X-153f)/103f,step,0,1,y/(float)H,
                false,0,0,ScoreNoteEvent.ACCIDENTAL_FROM_KEY,beats,1,0,0,ScoreNoteEvent.CLEF_TREBLE,
                false,0,false,octave,0,1,1);
    }

    private void draw() {
        Arrays.fill(gray,(byte)255);
        for(int y=84;y<=148;y+=16) rectangle(43,y-1,251,y+1,OmrMeasurePostProcessor.STAFF);
        rectangle(142,83,144,149,OmrMeasurePostProcessor.STEM_OR_REST);
        rectangle(239,83,241,149,OmrMeasurePostProcessor.STEM_OR_REST);
        rectangle(249,82,254,149,OmrMeasurePostProcessor.STEM_OR_REST);
        diamond(132,11);
        diamond(LOW-1,13);
        rectangle(179,162,220,162,OmrMeasurePostProcessor.STAFF);
        rectangle(209,77,211,LOW+2,OmrMeasurePostProcessor.STEM_OR_REST);
        for(int y=HIGH-6;y<=HIGH+6;y++)
            for(int x=X-10;x<=X+10;x++)
                if(Math.pow((x-X)/10d,2)+Math.pow((y-HIGH)/6d,2)<=1) {
                    gray[y*W+x]=0;
                    labels[y*W+x]=OmrMeasurePostProcessor.NOTEHEAD;
                }
    }

    private void diamond(int center,int radius) {
        for(int y=center-radius;y<=center+radius;y++)
            for(int x=X-radius;x<=X+radius;x++) {
                int d=Math.abs(X-x)+Math.abs(y-center);
                if(d>=radius-5&&d<=radius) {
                    gray[y*W+x]=0;
                    labels[y*W+x]=OmrMeasurePostProcessor.SYMBOL;
                }
            }
    }

    private void rectangle(int left,int top,int right,int bottom,byte label) {
        for(int y=top;y<=bottom;y++) {
            Arrays.fill(gray,y*W+left,y*W+right+1,(byte)0);
            Arrays.fill(labels,y*W+left,y*W+right+1,label);
        }
    }
}
