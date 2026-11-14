// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** A printed double-bar cadence can extend a complete dotted figure with a tied quarter. */
final class TiedQuarterCadence {
    private TiedQuarterCadence() {}

    record Timing(double onset, double duration) {}
    record Proof(ScorePageInterpretation score, Timing[] written) {}

    static Proof capture(ScorePageInterpretation score, ScoreMeterChange inherited,
            byte[] gray, int width, int height, float[] beats) {
        var written = new Timing[score.notes().size()];
        if (gray == null || gray.length != (long) width * height)
            return new Proof(score, written);
        var notes = new ArrayList<>(score.notes());
        var byMeasure = new ArrayList<List<Integer>>();
        for (int i=0;i<beats.length;i++) byMeasure.add(new ArrayList<>());
        for (int i=0;i<notes.size();i++) byMeasure.get(notes.get(i).measureIndex()).add(i);
        var meter = inherited;
        int nextMeter = 0;
        boolean changed = false;
        for (int i = 1; i + 1 < beats.length; i++) {
            while (nextMeter < score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex() <= i)
                meter = score.meterChanges().get(nextMeter++);
            if (meter.numerator() != 4 || meter.denominator() != 4
                    || beats[i] != 4 || byMeasure.get(i).size() != 16) continue;
            var bar = score.measures().get(i);
            var next = score.measures().get(i+1);
            final int index = i;
            boolean meterAfter = score.meterChanges().stream()
                    .anyMatch(m -> m.measureIndex() == index+1 && m.numerator() == 3 && m.denominator() == 4);
            if (!meterAfter || bar.bottom() >= next.top()
                    || score.rests().stream().anyMatch(rest -> rest.measureIndex()==index)) continue;
            var owners = byMeasure.get(i).stream()
                    .sorted(Comparator.comparingDouble(n -> notes.get(n).positionInMeasure())).toList();
            if (!proves(gray,width,height,bar,owners.stream().map(notes::get).toList())) continue;
            double onset = 0;
            for (int k = 0; k < owners.size(); k++) {
                int owner=owners.get(k);
                var note=notes.get(owner);
                if (k < 14 && k % 2 == 0 && note.augmentationDots() == 0)
                    notes.set(owner, withDot(note));
                double duration = k == 15 ? 1 : k == 14 ? .5 : k % 2 == 0 ? .375 : .125;
                written[owner] = new Timing(onset, duration);
                onset += duration;
            }
            beats[i] = 5;
            changed = true;
        }
        return new Proof(changed ? score.withNotes(notes,IntStream.range(0,notes.size()).boxed().toList())
                .withClockContextSource(score.clockContextSource()) : score, written);
    }

    static boolean proves(byte[] gray, int width, int height, MeasureRegion bar, List<ScoreNoteEvent> notes) {
        if (notes.size() != 16 || gray == null || gray.length != (long) width * height) return false;
        float[] x=new float[16], y=new float[16];
        float l=bar.left()*width, r=bar.right()*width;
        float gap=(bar.bottom()-bar.top())*height/8.2f;
        float bottom=bar.bottom()*height-2.1f*gap;
        if (!Float.isFinite(gap) || gap < 10 || gap > 30 || bottom >= height-2*gap
                || bottom < 6*gap || l <= gap || r >= width-gap) return false;
        int clef=notes.get(0).clefBottomDiatonic();
        if (clef == ScoreNoteEvent.CLEF_UNKNOWN) return false;
        for (int i=0; i<notes.size(); i++) {
            var n=notes.get(i);
            if (n.kind()!=ScoreNoteEvent.Kind.PITCHED || n.staffCount()!=1 || n.staffIndex()!=0
                    || n.clefBottomDiatonic()!=clef || n.tupletDivisor()!=1 || n.crossStaffBeam()
                    || n.boundaryTies()!=0 || n.followingRestBeats()!=0 || n.leadingRestBeats()!=0
                    || (n.articulations() & NoteOrnament.GRACE)!=0
                    || n.beamCount() != (i==15 ? 0 : i==14 ? 1 : i%2==0 ? 2 : 3)
                    || Math.abs(n.stemDirection())!=1 || n.tiedFromPrevious()!=(i==15)
                    || n.unbeamedDurationBeats()!=(i==15?1:0)
                    || (i<14 && i%2==0 ? n.augmentationDots()>1 : n.augmentationDots()!=0))
                return false;
            x[i]=l+n.positionInMeasure()*(r-l);
            y[i]=n.pageY()*height;
            if (i>0 && (x[i]-x[i-1]<gap*1.30f || x[i]-x[i-1]>gap*4)) return false;
        }
        var eighth=notes.get(14);
        var quarter=notes.get(15);
        if (x[0]-l > gap*1.6f || r-x[15]<gap*1.4f
                || eighth.staffStep()!=quarter.staffStep() || eighth.octaveShift()!=quarter.octaveShift()
                || eighth.stemDirection()!=quarter.stemDirection() || Math.abs(y[15]-y[14])>gap*.2f
                || (quarter.writtenAccidental()!=eighth.writtenAccidental()
                    && quarter.writtenAccidental()!=ScoreNoteEvent.ACCIDENTAL_FROM_KEY))
            return false;
        var staff=new PlayingTechniqueDetector.Staff(bottom-4*gap,bottom,gap,0,1);
        if (PrintedCadenceDurations.boundaryColumn(gray,width,staff,r,false,true)<0
                || !tieBow(gray,width,height,x[14],x[15],(y[14]+y[15])/2,gap,eighth.stemDirection()))
            return false;
        for (int i=0; i<14; i++) {
            boolean dotted=i%2==0;
            if ((WrappedTripletDotInk.uniqueCore(gray,width,height,x[i],y[i],x[i+1],gap)!=null)!=dotted)
                return false;
            if (dotted && (notes.get(i).stemDirection()!=notes.get(i+1).stemDirection()
                    || !PrintedTupletBeamOwner.connectedHeads(
                            gray,width,height,x[i],y[i],x[i+1],y[i+1],gap,notes.get(i).stemDirection())))
                return false;
        }
        return WrappedTripletDotInk.uniqueCore(gray,width,height,x[14],y[14],x[15],gap)==null
                && notes.get(13).stemDirection()==eighth.stemDirection()
                && PrintedTupletBeamOwner.connectedHeads(gray,width,height,
                        x[13],y[13],x[14],y[14],gap,eighth.stemDirection());
    }

    // Prove a detached bow with its deepest part between the heads and white paper beyond it.
    static boolean tieBow(byte[] gray,int width,int height,float a,float b,float y,float gap,int side) {
        int previous=-1, covered=0, interior=0;
        int left=Math.round(a + .25f*(b-a)), right=Math.round(b - .25f*(b-a));
        int near=Math.round(gap*1.08f), far=Math.round(gap*1.69f);
        if (left<0 || left>=right || right>=width || y-far-5<0 || y+far+5>=height) return false;
        for (int x=left;x<=right;x++) {
            int closest=-1, deepest=-1;
            for(int n=near;n<=far;n++) {
                int at=Math.round(y)+side*n;
                if ((gray[at*width+x]&255)<135) {
                    if (closest<0) closest=n;
                    deepest=n;
                }
            }
            if (closest<0 || deepest-closest>gap*.33f) continue;
            int center=(deepest+closest)/2;
            if (previous>=0 && Math.abs(center-previous)>2) return false;
            previous=center;
            covered++;
            if (Math.abs(x-.5f*(a+b))<(b-a)*.12f && deepest>=gap*1.35f) interior++;
            int beyond=Math.round(y)+side*(deepest+Math.round(gap*.27f));
            if ((gray[beyond*width+x]&255)<175) return false;
        }
        return covered >= .92f*(right-left+1) && interior >= 2;
    }

    private static ScoreNoteEvent withDot(ScoreNoteEvent n) {
        return new ScoreNoteEvent(n.measureIndex(),n.positionInMeasure(),n.staffStep(),n.staffIndex(),
                n.staffCount(),n.pageY(),n.tiedFromPrevious(),1,n.beamCount(),n.writtenAccidental(),
                n.unbeamedDurationBeats(),n.tupletDivisor(),n.followingRestBeats(),n.articulations(),
                n.clefBottomDiatonic(),n.crossStaffBeam(),n.leadingRestBeats(),n.compactOpening(),
                n.octaveShift(),n.boundaryTies(),n.tupletNormalNotes(),n.stemDirection(),n.kind(),
                n.visualSize(),n.harmonicSemitones(),n.openStringTouch());
    }
}
