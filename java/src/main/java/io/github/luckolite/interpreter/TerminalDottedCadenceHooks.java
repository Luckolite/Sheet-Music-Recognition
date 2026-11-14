// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Restores owned third-level hooks on a complete sloping beam before a short dotted-half measure. */
final class TerminalDottedCadenceHooks {
    private TerminalDottedCadenceHooks() {}

    private record Head(int index, ScoreNoteEvent note, float x, float y) {}
    private record Axis(int x, int tip) {}
    private record Ink(byte[] gray, int width, int height) {
        boolean dark(int x, int y) {
            return x>=0 && x<width && y>=0 && y<height && (gray[y*width+x]&255)<155;
        }

        int count(int x, int y, int radius) {
            int n=0;
            for (int dy=-radius;dy<=radius;dy++) if (dark(x,y+dy)) n++;
            return n;
        }
    }

    static ScorePageInterpretation apply(ScorePageInterpretation score, ScoreMeterChange inherited,
            byte[] gray, int width, int height) {
        if (gray==null || gray.length!=(long)width*height || width<256 || height<128)
            return score;
        var raster=new Ink(gray,width,height);
        var notes=score.notes();
        var measures=score.measures();
        var byBar=new ArrayList<List<Head>>(measures.size());
        for (int i=0;i<measures.size();i++) byBar.add(new ArrayList<>());
        for (int i=0;i<notes.size();i++) {
            var n=notes.get(i);
            var region=measures.get(n.measureIndex());
            byBar.get(n.measureIndex()).add(new Head(i,n,
                    (region.left()+n.positionInMeasure()*(region.right()-region.left()))*width,
                    n.pageY()*height));
        }
        var output=new ArrayList<>(notes);
        var meter=inherited;
        int nextMeter=0;
        for (int i=0;i+2<measures.size();i++) {
            while (nextMeter<score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex()<=i)
                meter=score.meterChanges().get(nextMeter++);
            final int bar=i;
            if (meter==null || meter.numerator()!=6 || meter.denominator()!=4
                    || byBar.get(i).size()<17 || byBar.get(i).size()>28
                    || byBar.get(i+1).size()!=1
                    || score.meterChanges().stream().anyMatch(m->m.measureIndex()==bar+1)
                    || score.rests().stream().anyMatch(r->r.measureIndex()==bar || r.measureIndex()==bar+1))
                continue;
            var tail=byBar.get(i);
            tail.sort(Comparator.comparingDouble(Head::x));
            var group=tail.subList(tail.size()-4,tail.size());
            var previous=tail.get(tail.size()-5);
            var cadence=byBar.get(i+1).get(0);
            float gap=cadenceGap(raster,measures.get(i),measures.get(i+1),measures.get(i+2),cadence);
            if (gap<=0 || !terminal(raster,measures.get(i),previous,group,gap)) continue;
            int last=group.get(3).index;
            // Both hooks identify the written short slots. Preserve an early slot whose running
            // clock is already 1/8 beat; this rule repairs the boundary overrun only.
            var lastPlacement=ScoreNoteTiming.writtenPlacement(notes.get(last),notes,6f).orElse(null);
            var earlyPlacement=ScoreNoteTiming.writtenPlacement(group.get(1).note,notes,6f).orElse(null);
            if (lastPlacement==null || earlyPlacement==null
                    || !near(lastPlacement.onsetBeats(),5.875)
                    || !near(lastPlacement.durationBeats(),.25)
                    || !near(earlyPlacement.durationBeats(),.125)) continue;
            output.set(last,withTertiary(notes.get(last)));
        }
        if (output.equals(notes)) return score;
        return score.withNotes(output, java.util.stream.IntStream.range(0,notes.size()).boxed().toList());
    }

    private static boolean near(double value,double expected) {
        return Math.abs(value-expected)<1e-6;
    }

    private static float cadenceGap(Ink ink, MeasureRegion before, MeasureRegion bar,
            MeasureRegion after, Head cadence) {
        float left=bar.left()*ink.width, right=bar.right()*ink.width;
        float estimate=(bar.bottom()-bar.top())*ink.height/8.2f;
        var note=cadence.note;
        if (!plain(note) || note.stemDirection()!=1 || note.unbeamedDurationBeats()!=2
                || note.augmentationDots()!=1 || note.beamCount()!=0
                || estimate<10 || estimate>26 || right-left<3.4f*estimate || right-left>4.7f*estimate
                || Math.abs(bar.top()-before.top())*ink.height>.22f*estimate
                || after.top()*ink.height<bar.bottom()*ink.height+1.6f*estimate
                || cadence.x-left<estimate || cadence.x-left>1.65f*estimate) return 0;
        var rails=PrintedDirectionStaff.printedRules(ink.gray,ink.width,ink.height,
                left+estimate*1.0f,bar.bottom()*ink.height-2.1f*estimate,estimate);
        if (rails==null) return 0;
        float gap=rails[1];
        if (gap<10 || gap>27 || rails[0]-4*gap<1 || rails[0]>ink.height-2) return 0;
        var staff=new PlayingTechniqueDetector.Staff(rails[0]-4*gap,rails[0],gap,0,1);
        float seam=(left+before.right()*ink.width)*.5f;
        if (PrintedCadenceDurations.boundaryColumn(ink.gray,ink.width,staff,seam,true,false)<0
                || PrintedCadenceDurations.boundaryColumn(ink.gray,ink.width,staff,right,true,false)<0
                || !LegacyTripletDotInk.singleDotCore(ink.gray,ink.width,ink.height,
                        cadence.x,cadence.y,right,gap)) return 0;
        return gap;
    }

    private static boolean terminal(Ink p, MeasureRegion bar, Head before, List<Head> group,float gap) {
        var first=group.get(0); var last=group.get(3);
        if (last.note.beamCount()!=2 || bar.right()*p.width-last.x<gap*.55f
                || bar.right()*p.width-last.x>gap*1.25f
                || !SeparateBeamGroups.between(p.gray,p.width,p.height,before.x,before.y,first.x,first.y,gap))
            return false;
        var lines=new ArrayList<List<Axis>>(4);
        for (int i=0;i<4;i++) {
            var head=group.get(i);
            var note=head.note;
            if (!plain(note) || note.stemDirection()!=-1 || note.unbeamedDurationBeats()!=0
                    || note.beamCount()<2 || note.beamCount()>3
                    || i%2==0 && note.beamCount()!=2 || note.augmentationDots()!=(i%2==0?1:0))
                return false;
            if (i>0) {
                float dx=head.x-group.get(i-1).x;
                if (dx<gap*1.45f || dx>gap*2.5f) return false;
            }
            var axes=attachedShafts(p,head,gap);
            if (axes.size()<2 || axes.size()>Math.round(gap*.3f)) return false;
            lines.add(axes);
        }
        int proofs=0;
        for (var a:lines.get(0)) for (var b:lines.get(1)) {
            if (hook(p,a,gap) || !hook(p,b,gap) || !rails(p,a,b,gap)) continue;
            for (var c:lines.get(2)) {
                if (hook(p,c,gap) || !rails(p,b,c,gap)) continue;
                for (var d:lines.get(3))
                    if (hook(p,d,gap) && rails(p,c,d,gap)) proofs++;
            }
        }
        return proofs>=2;
    }

    private static boolean plain(ScoreNoteEvent n) {
        return n.kind()==ScoreNoteEvent.Kind.PITCHED && n.staffIndex()==0 && n.staffCount()==1
                && n.visualSize()==ScoreNoteEvent.VisualSize.FULL && !n.crossStaffBeam()
                && !n.tiedFromPrevious() && n.boundaryTies()==0 && n.tupletDivisor()==1
                && n.leadingRestBeats()==0 && n.followingRestBeats()==0
                && n.harmonicSemitones()==0 && n.openStringTouch()==null
                && (n.articulations()&NoteOrnament.GRACE)==0;
    }

    private static List<Axis> attachedShafts(Ink p, Head head,float gap) {
        var result=new ArrayList<Axis>();
        int left=Math.round(head.x-gap*.8f),right=Math.round(head.x-gap*.4f);
        int start=Math.round(head.y+gap*.2f),limit=start+Math.round(gap*6);
        for (int x=left;x<=right;x++) {
            int end=start-1;
            for (int y=start;y<limit;y++) {
                if (p.dark(x,y)) end=y;
                else if (y-end>1) break;
            }
            if (end-start<gap*1.7f || end>=limit-2) continue;
            if (!result.isEmpty()) {
                var previous=result.get(result.size()-1);
                if (x!=previous.x+1 || Math.abs(end-previous.tip)>gap*.15f) return List.of();
            }
            result.add(new Axis(x,end));
        }
        return result;
    }

    private static boolean rails(Ink p,Axis a,Axis b,float gap) {
        int span=b.x-a.x;
        return span>=gap*1.35f && span<=gap*2.8f
                && Math.abs(b.tip-a.tip)<span*.4f
                && band(p,a,b,gap,.22f,.42f)
                && band(p,a,b,gap,.96f,1.30f)
                && !band(p,a,b,gap,1.55f,2.1f);
    }

    private static boolean band(Ink p,Axis a,Axis b,float gap,float near,float far) {
        int inset=Math.round(gap*.3f), radius=Math.round(gap*.125f);
        for (int depth=Math.round(gap*near);depth<=Math.round(gap*far);depth++) {
            boolean full=true;
            for (int x=a.x+inset;x<=b.x-inset;x++) {
                int y=Math.round(a.tip+(b.tip-a.tip)*(x-a.x)/(float)(b.x-a.x))-depth;
                if (p.count(x,y,radius)<2*radius) {
                    full=false;
                    break;
                }
            }
            if (full) return true;
        }
        return false;
    }

    private static boolean hook(Ink p,Axis a,float gap) {
        int length=Math.round(gap*.61f), radius=Math.round(gap*.125f);
        int far=Math.round(gap*1.30f), near=Math.round(gap*1.06f);
        for (int depth=Math.round(gap*1.4f);depth<=Math.round(gap*2.52f);depth++) {
            for (int slope=-3;slope<=4;slope++) {
                boolean attached=true;
                for (int dx=-length;dx<=0;dx++)
                    if (p.count(a.x+dx, Math.round(a.tip-depth+dx*slope*.1f),radius)<2*radius) {
                        attached=false;
                        break;
                    }
                if (!attached) continue;
                int exterior=0;
                for (int d=near;d<=far;d++)
                    exterior+=p.count(a.x-d, Math.round(a.tip-depth-d*slope*.1f),radius);
                if (exterior>radius+1) continue;
                boolean rightClear=true;
                for (int dx=Math.round(gap*.25f);dx<=Math.round(gap*.56f);dx++)
                    if (p.count(a.x+dx,Math.round(a.tip-depth+dx*slope*.1f),radius)>radius)
                        rightClear=false;
                if (rightClear) return true;
            }
        }
        return false;
    }

    private static ScoreNoteEvent withTertiary(ScoreNoteEvent n) {
        return new ScoreNoteEvent(n.measureIndex(),n.positionInMeasure(),n.staffStep(),n.staffIndex(),
                n.staffCount(),n.pageY(),n.tiedFromPrevious(),n.augmentationDots(),3,
                n.writtenAccidental(),n.unbeamedDurationBeats(),n.tupletDivisor(),n.followingRestBeats(),
                n.articulations(),n.clefBottomDiatonic(),n.crossStaffBeam(),n.leadingRestBeats(),
                n.compactOpening(),n.octaveShift(),n.boundaryTies(),n.tupletNormalNotes(),
                n.stemDirection(),n.kind(),n.visualSize(),n.harmonicSemitones(),n.openStringTouch());
    }
}
