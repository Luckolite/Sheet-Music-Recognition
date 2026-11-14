// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Reads a closed 3/4 lane of dotted-sixteenth pairs despite an extra beam fragment. */
final class ClosedDottedSixteenths {
    private ClosedDottedSixteenths() {}

    private record Head(int index,ScoreNoteEvent note,float x,float y) {}
    private record Shaft(int x,int tip) {}
    private record Beam(Shaft a,Shaft b,int depth,int separation) {
        float outer(float x) {return a.tip+(b.tip-a.tip)*(x-a.x)/(b.x-a.x)-depth;}
    }
    private record Ink(byte[] pixels,int w,int h) {
        boolean dark(int x,int y) {
            return x>=0 && x<w && y>=0 && y<h && (pixels[y*w+x]&255)<130;
        }

        int weight(int x,int y,int radius) {
            int n=0;
            for(int k=-radius;k<=radius;k++) if(dark(x,y+k)) n++;
            return n;
        }
    }

    static ScorePageInterpretation apply(ScorePageInterpretation score,byte[] gray,int w,int h) {
        if (gray==null || gray.length!=(long)w*h || w<256 || h<128) return score;
        var ink=new Ink(gray,w,h);
        var notes=score.notes();
        var bars=new ArrayList<List<Head>>();
        for(var ignored:score.measures()) bars.add(new ArrayList<>());
        for (int i=0;i<notes.size();i++) {
            var n=notes.get(i);
            var bar=score.measures().get(n.measureIndex());
            bars.get(n.measureIndex()).add(new Head(i,n,
                    (bar.left()+n.positionInMeasure()*(bar.right()-bar.left()))*w,n.pageY()*h));
        }
        var corrected=new ScoreNoteEvent[notes.size()];
        var suppressed=new boolean[notes.size()];
        int nextMeter=0;
        ScoreMeterChange meter=null;
        boolean changed=false;
        for(int m=0;m<bars.size();m++) {
            while (nextMeter<score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex()<=m)
                meter=score.meterChanges().get(nextMeter++);
            final int bar=m;
            var row=bars.get(m);
            if (meter==null || meter.numerator()!=3 || meter.denominator()!=4 || row.size()!=13
                    || score.rests().stream().anyMatch(r->r.measureIndex()==bar)) continue;
            var proof=prove(ink,score.measures().get(m),row);
            if (proof==null) continue;
            suppressed[proof.fragment.index]=true;
            for(int i=0;i<proof.kept.size();i++)
                corrected[proof.kept.get(i).index]=rhythm(proof.kept.get(i).note,i%2==0);
            changed=true;
        }
        if (!changed) return score;
        var retained=new ArrayList<ScoreNoteEvent>();
        var owners=new ArrayList<Integer>();
        for(int i=0;i<notes.size();i++) {
            if (suppressed[i]) continue;
            owners.add(i);
            retained.add(corrected[i]==null?notes.get(i):corrected[i]);
        }
        return score.withNotes(retained,owners);
    }

    private record Proof(Head fragment,List<Head> kept) {}

    private static Proof prove(Ink ink,MeasureRegion bar,List<Head> row) {
        if(bar.cadenceQuarterBeats()!=0 || row.stream().anyMatch(h->!regular(h.note))) return null;
        float left=bar.left()*ink.w,right=bar.right()*ink.w;
        float gap=(bar.bottom()-bar.top())*ink.h/8.2f;
        if (gap<10 || gap>27 || right-left<28*gap || right-left>39*gap) return null;
        var candidates=row.stream().filter(h->h.note.stemDirection()==0).toList();
        if(candidates.size()!=1) return null;
        var fragment=candidates.get(0);
        if(fragment.note.beamCount()!=3 || fragment.note.augmentationDots()!=0
                || fragment.note.articulations()!=0) return null;
        var owners=row.stream().filter(h->h!=fragment).sorted(Comparator.comparingDouble(Head::x)).toList();
        if(owners.stream().anyMatch(h->h.note.stemDirection()!=-1
                || h.note.clefBottomDiatonic()!=owners.get(0).note.clefBottomDiatonic())) return null;
        if(owners.get(0).x-left<gap*1.4f || owners.get(0).x-left>gap*3.25f
                || right-owners.get(11).x<gap*.85f || right-owners.get(11).x>gap*2.1f) return null;
        var samples=new ArrayList<float[]>();
        for(int i=0;i<=12;i++) {
            float x=left+(right-left)*i/12;
            var near=owners.stream().min(Comparator.comparingDouble(a->Math.abs(a.x-x))).orElseThrow();
            float hint=near.y+near.note.staffStep()*gap*.5f;
            var rules=localRules(ink,x,hint,gap);
            if(rules!=null) samples.add(new float[]{x,rules[0],rules[1]});
        }
        var track=samples.size()>=4?StaffPitchTrack.fromVerifiedSamples(samples):null;
        // The narrow right rule closes the run. The first gap can be a system header.
        if(track==null || !barline(ink,right,track.at(right))
                || PrintedStemDirection.detect(ink.pixels,ink.w,ink.h,fragment.x,fragment.y,gap)!=0)
            return null;
        gap=track.at((left+right)*.5f)[1];
        int fragmentGroup=-1;
        List<List<Shaft>> axes=new ArrayList<>();
        for(int i=0;i<owners.size();i++) {
            var a=owners.get(i);
            float next=i+1<owners.size()?owners.get(i+1).x:right;
            if(i<11 && (next-a.x<gap*1.50f || next-a.x>gap*4.15f)) return null;
            var aShafts=shafts(ink,a,gap);
            if(aShafts.isEmpty()) return null;
            axes.add(aShafts);
            if(dot(ink,a,next,gap)!=(i%2==0)) return null;
            if(i%4==0 && Math.abs(fragment.x-a.x)<=gap*.31f && fragment.y-a.y>gap*3)
                fragmentGroup=i;
        }
        if(fragmentGroup<0 || !shafts(ink,fragment,gap).isEmpty()) return null;
        for(int i=0;i<11;i++) {
            var a=owners.get(i);
            var b=owners.get(i+1);
            if(i%4==3) {
                if(!gapBetween(ink,a,b,axes.get(i),axes.get(i+1),gap,track)) return null;
            } else if (!ownedLink(ink,axes.get(i),axes.get(i+1),i,gap,track,
                    i==fragmentGroup?fragment:null)) return null;
        }
        var trial=new ArrayList<ScoreNoteEvent>();
        for(int i=0;i<owners.size();i++) trial.add(rhythm(owners.get(i).note,i%2==0));
        for(int i=0;i<trial.size();i++) {
            var p=ScoreNoteTiming.writtenPlacement(trial.get(i),trial,3f).orElse(null);
            if(p==null || p.onsetBeats()!=i/2*.5+(i%2==1?.375:0)
                    || p.durationBeats()!=(i%2==0?.375:.125)) return null;
        }
        return new Proof(fragment,owners);
    }

    private static boolean regular(ScoreNoteEvent n) {
        return n.kind()==ScoreNoteEvent.Kind.PITCHED && n.staffIndex()==0 && n.staffCount()==1
                && n.visualSize()==ScoreNoteEvent.VisualSize.FULL && !n.crossStaffBeam()
                && n.tupletDivisor()==1 && n.unbeamedDurationBeats()==0 && n.beamCount()>0
                && n.beamCount()<=3 && n.augmentationDots()<=1
                && !n.tiedFromPrevious() && n.boundaryTies()==0
                && n.leadingRestBeats()==0 && n.followingRestBeats()==0
                && n.harmonicSemitones()==0 && n.openStringTouch()==null
                && n.clefBottomDiatonic()!=ScoreNoteEvent.CLEF_UNKNOWN
                && (n.articulations()&NoteOrnament.GRACE)==0;
    }

    private static float[] localRules(Ink ink,float x,float floor,float gap) {
        int center=Math.round(x), measure=Math.round(gap*2.7f);
        int flank=Math.round(gap*.27f), range=Math.round(gap*.32f);
        int[] rows=new int[5];
        for (int level=0;level<5;level++) {
            int expected=Math.round(floor-level*gap);
            int best=0;
            for(int y=expected-range;y<=expected+range;y++) {
                int hit=0;
                for (int px=center-measure;px<=center+measure;px++)
                    if (ink.dark(px,y) && !ink.dark(px,y-flank) && !ink.dark(px,y+flank)) hit++;
                if (hit>best) {best=hit; rows[level]=y;}
            }
            if (best<measure*.53f) return null;
            if (level>0 && Math.abs(rows[level-1]-rows[level]-gap)>gap*.3f) return null;
        }
        float exactGap=(rows[0]-rows[4])/4f;
        if(exactGap<gap*.85f || exactGap>gap*1.15f) return null;
        return new float[]{rows[0],exactGap};
    }

    private static boolean barline(Ink p,float x,float[] r) {
        if(r[1]<10 || r[1]>27 || r[0]-4*r[1]<1 || r[0]>=p.h) return false;
        var staff=new PlayingTechniqueDetector.Staff(r[0]-4*r[1],r[0],r[1],0,1);
        return PrintedCadenceDurations.boundaryColumn(p.pixels,p.w,staff,x,true,false)>=0;
    }

    private static List<Shaft> shafts(Ink p,Head h,float gap) {
        int first=Math.round(h.y+gap*.2f),limit=first+Math.round(gap*8.05f);
        var out=new ArrayList<Shaft>();
        for(int x=Math.round(h.x-gap*.81f);x<=Math.round(h.x-gap*.41f);x++) {
            int hit=0,last=first-1;
            for(int y=first;y<limit;y++) {
                if(p.dark(x,y)) {last=y;hit++;}
                if(y-last>Math.round(gap*.33f)) break;
            }
            int span=last-first+1;
            if(span>=gap*2 && hit>=span*.88f && last<limit-2)
                out.add(new Shaft(x,last));
        }
        return out;
    }

    private static boolean dot(Ink p,Head a,float next,float gap) {
        return WrappedTripletDotInk.uniqueCore(p.pixels,p.w,p.h,a.x,a.y,next,gap)!=null
                || LegacyTripletDotInk.singleDotCore(p.pixels,p.w,p.h,a.x,a.y,next,gap);
    }

    private static boolean gapBetween(Ink p,Head a,Head b,List<Shaft> left,List<Shaft> right,
            float g,StaffPitchTrack track) {
        int x=Math.round((a.x+b.x)*.5f-g*.57f);
        int tail=Math.max(left.stream().mapToInt(Shaft::tip).max().orElseThrow(),
                right.stream().mapToInt(Shaft::tip).max().orElseThrow());
        int dark=0,pts=0;
        for(int col=x-2;col<=x+2;col++) {
            var r=track.at(col);
            for(int y=tail-Math.round(g*2.25f);y<=tail+2;y++) {
                if(onRule(r,y,.19f)) continue;
                pts++;
                if(p.dark(col,y)) dark++;
            }
        }
        return pts>30 && dark<=pts*.13f;
    }

    private static boolean ownedLink(Ink p,List<Shaft> left,List<Shaft> right,int slot,
            float g,StaffPitchTrack track,Head fragment) {
        int radius=Math.max(2,Math.round(g*.12f)),margin=Math.round(g*.29f);
        for(var a:left) for(var b:right) {
            if(Math.abs(a.tip-b.tip)>g*.6f || b.x-a.x<g*1.35f) continue;
            int first=a.x+margin,last=b.x-margin;
            for(int inset=Math.round(g*.26f);inset<=Math.round(g*.58f);inset++)
                for(int separation=Math.round(g*.65f);separation<=Math.round(g*.96f);separation++) {
                    var f=new Beam(a,b,inset,separation);
                    if (coverage(p,f,first,last,0,radius)<.94
                            || coverage(p,f,first,last,separation,radius)<.94
                            || coverage(p,f,first,last,separation/2,1)>.93) continue;
                    if (slot%2==0 && !hook(p,f,g,radius,track)) continue;
                    if (fragment!=null && !beamFragment(p,fragment,f,g,track)) continue;
                    return true;
                }
        }
        return false;
    }

    private static float coverage(Ink p,Beam f,int first,int last,int shift,int radius) {
        int count=0;
        for(int x=first;x<=last;x++)
            if(p.weight(x,Math.round(f.outer(x))-shift,radius)>=2*radius) count++;
        return count/(float)(last-first+1);
    }

    private static boolean hook(Ink p,Beam f,float g,int radius,StaffPitchTrack track) {
        int shaft=f.b.x,len=Math.round(g*.66f),outside=Math.round(g*1.1f);
        for(int step=Math.round(g*.53f);step<=Math.round(g*1.12f);step++) {
            int base=Math.round(f.outer(shaft))-f.separation-step;
            int hit=0;
            for(int x=shaft-len;x<=shaft;x++) {
                int y=Math.round(base+(f.outer(x)-f.outer(shaft)));
                if(p.weight(x,y,radius)>=2*radius) hit++;
            }
            if(hit<len || p.weight(shaft,base,1)<2) continue;
            int flank=0;
            for(int dx=outside;dx<=outside+Math.round(g*.36f);dx++) {
                int x=shaft-dx;
                int y=Math.round(base+(f.outer(x)-f.outer(shaft)));
                if(p.weight(x,y,radius)>=2*radius && !onRule(track.at(x),y,.14f)) flank++;
            }
            if(flank<=1 && p.weight(shaft+Math.round(g*.35f),base,radius)<2*radius) return true;
        }
        return false;
    }

    private static boolean beamFragment(Ink p,Head h,Beam f,float g,StaffPitchTrack track) {
        if(h.x-f.a.x<g*.47f || h.x-f.a.x>g*1.12f
                || h.y<f.outer(h.x)-f.separation-g*.45f
                || h.y>f.outer(h.x)+g*.35f) return false;
        int exterior=0,core=0;
        int left=Math.round(h.x-g*.68f),right=Math.round(h.x+g*.64f);
        for(int x=left;x<=right;x++) {
            var lines=track.at(x);
            for(int y=Math.round(h.y-g*.91f);y<=Math.round(h.y+g*.97f);y++) {
                if(!p.dark(x,y) || onRule(lines,y,.14f)) continue;
                float distance=Math.min(Math.abs(y-f.outer(x)),Math.abs(y-f.outer(x)+f.separation));
                if(distance<=g*.36f) core++;
                else exterior++;
            }
        }
        return core>=g*g*.55f && exterior<=g*.52f;
    }

    private static boolean onRule(float[] rules,float y,float tolerance) {
        float level=(rules[0]-y)/rules[1];
        return level>=-.15f && level<=4.15f && Math.abs(level-Math.round(level))<tolerance;
    }

    private static ScoreNoteEvent rhythm(ScoreNoteEvent n,boolean dotted) {
        if(n.augmentationDots()==(dotted?1:0) && n.beamCount()==(dotted?2:3)) return n;
        return new ScoreNoteEvent(n.measureIndex(),n.positionInMeasure(),n.staffStep(),n.staffIndex(),
                n.staffCount(),n.pageY(),n.tiedFromPrevious(),dotted?1:0,dotted?2:3,
                n.writtenAccidental(),n.unbeamedDurationBeats(),n.tupletDivisor(),n.followingRestBeats(),
                n.articulations(),n.clefBottomDiatonic(),n.crossStaffBeam(),n.leadingRestBeats(),
                n.compactOpening(),n.octaveShift(),n.boundaryTies(),n.tupletNormalNotes(),
                n.stemDirection(),n.kind(),n.visualSize(),n.harmonicSemitones(),n.openStringTouch());
    }
}
