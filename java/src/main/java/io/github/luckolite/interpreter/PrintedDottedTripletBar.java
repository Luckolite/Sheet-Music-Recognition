// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Repairs a complete six-beat dotted-triplet bar with two proved borrowed beam heads. */
final class PrintedDottedTripletBar {
    private PrintedDottedTripletBar() {}

    private record Head(int owner, ScoreNoteEvent note, float x, float y) {}
    private record Shaft(int x, int y, int travel, float density) {}
    private record Bridge(Shaft a, Shaft b, float inset, float gap) {
        float center(float x) { return a.y + (b.y-a.y)*(x-a.x)/(b.x-a.x) - a.travel*inset; }
    }
    private record Raster(byte[] ink, int width, int height) {
        boolean dark(int x, int y) {
            return x >= 0 && x < width && y >= 0 && y < height && (ink[y*width+x]&255) < 155;
        }
    }

    static ScorePageInterpretation apply(
            ScorePageInterpretation score, ScoreMeterChange inherited,
            byte[] gray, int width, int height) {
        if (gray == null || gray.length != (long) width*height || width < 256 || height < 128)
            return score;
        var raster = new Raster(gray, width, height);
        var notes = score.notes();
        var perBar = new ArrayList<List<Head>>(score.measures().size());
        for (int i = 0; i < score.measures().size(); i++) perBar.add(new ArrayList<>());
        for (int owner = 0; owner < notes.size(); owner++) {
            var n = notes.get(owner);
            var bar = score.measures().get(n.measureIndex());
            perBar.get(n.measureIndex()).add(new Head(owner,n,
                    (bar.left()+n.positionInMeasure()*(bar.right()-bar.left()))*width,n.pageY()*height));
        }
        var remove = new boolean[notes.size()];
        var rewrite = new ScoreNoteEvent[notes.size()];
        boolean changed = false;
        int nextMeter = 0;
        var meter = inherited;
        for (int barIndex=0; barIndex<perBar.size(); barIndex++) {
            while (nextMeter<score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex()<=barIndex)
                meter=score.meterChanges().get(nextMeter++);
            final int index=barIndex;
            var heads=perBar.get(barIndex);
            if (meter==null || meter.numerator()!=6 || meter.denominator()!=4 || heads.size()!=20
                    || score.rests().stream().anyMatch(r -> r.measureIndex()==index)
                    || heads.stream().anyMatch(h -> !ordinary(h.note))) continue;
            heads.sort(Comparator.comparingDouble(Head::x));
            var retained=qualified(raster,score.measures().get(barIndex),heads);
            if (retained==null) continue;
            for (var head:heads) remove[head.owner]=true;
            for (int slot=0;slot<retained.size();slot++) {
                var head=retained.get(slot);
                remove[head.owner]=false;
                rewrite[head.owner]=withTriplet(head.note,slot%3==0 ? 1 : 0);
            }
            changed=true;
        }
        if (!changed) return score;
        var edited = new ArrayList<ScoreNoteEvent>();
        var owners = new ArrayList<Integer>();
        for (int i=0;i<notes.size();i++) {
            if (remove[i]) continue;
            owners.add(i);
            edited.add(rewrite[i]==null ? notes.get(i) : rewrite[i]);
        }
        return score.withNotes(edited,owners);
    }

    private static boolean ordinary(ScoreNoteEvent n) {
        return n.kind()==ScoreNoteEvent.Kind.PITCHED && n.staffIndex()==0 && n.staffCount()==1
                && n.visualSize()==ScoreNoteEvent.VisualSize.FULL && n.harmonicSemitones()==0
                && n.openStringTouch()==null && !n.crossStaffBeam() && n.tupletDivisor()==1
                && n.leadingRestBeats()==0 && n.followingRestBeats()==0 && !n.tiedFromPrevious()
                && n.boundaryTies()==0 && (n.articulations()&NoteOrnament.GRACE)==0
                && Math.abs(n.stemDirection())==1 && n.clefBottomDiatonic()!=ScoreNoteEvent.CLEF_UNKNOWN;
    }

    private static List<Head> qualified(Raster p, MeasureRegion bar, List<Head> candidates) {
        if (candidates.get(0).note.positionInMeasure()>.12f
                || candidates.get(19).note.positionInMeasure()<.88f) return null;
        float left=bar.left()*p.width, right=bar.right()*p.width;
        float coarseGap=(bar.bottom()-bar.top())*p.height/8.2f;
        if (coarseGap<10 || coarseGap>28 || right-left<26*coarseGap || right-left>50*coarseGap)
            return null;
        var samples=new ArrayList<float[]>();
        for (int j=0;j<=12;j++) {
            float x=left+(right-left)*j/12;
            var sample=PrintedDirectionStaff.printedRules(p.ink,p.width,p.height,
                    x,bar.bottom()*p.height-2.1f*coarseGap,coarseGap);
            if (sample!=null) samples.add(new float[]{x,sample[0],sample[1]});
        }
        if (samples.size()<7) return null;
        var track=StaffPitchTrack.fromVerifiedSamples(samples);
        if (!barline(p,left,track.at(left)) || !barline(p,right,track.at(right))) return null;
        List<Head> result=null;
        int clef=candidates.get(0).note.clefBottomDiatonic();
        for (int first=1;first<19;first++) {
            var firstExtra=candidates.get(first);
            if (!couldBeBorrowed(p,track,firstExtra)) continue;
            for (int second=first+1;second<19;second++) {
                var secondExtra=candidates.get(second);
                if (!couldBeBorrowed(p,track,secondExtra)) continue;
                var kept=new ArrayList<Head>();
                for (int i=0;i<candidates.size();i++)
                    if (i!=first && i!=second) kept.add(candidates.get(i));
                if (!pattern(kept,clef) || !printedGroups(p,track,kept,List.of(firstExtra,secondExtra)))
                    continue;
                if (result!=null) return null;
                result=kept;
            }
        }
        return result;
    }

    private static boolean barline(Raster p, float edge, float[] rails) {
        float gap=rails[1];
        if (gap<10 || gap>27 || rails[0]-4*gap<1 || rails[0]>p.height-2) return false;
        var staff=new PlayingTechniqueDetector.Staff(rails[0]-4*gap,rails[0],gap,0,1);
        return PrintedCadenceDurations.boundaryColumn(p.ink,p.width,staff,edge,true,false)>=0;
    }

    private static boolean couldBeBorrowed(Raster p, StaffPitchTrack track, Head h) {
        return h.note.stemDirection()==1 && h.note.beamCount()<=1 && h.note.augmentationDots()==0
                && PrintedStemDirection.detect(p.ink,p.width,p.height,h.x,h.y,track.at(h.x)[1])==0;
    }

    private static boolean pattern(List<Head> notes, int clef) {
        int missingDots=0;
        for (int i=0;i<notes.size();i++) {
            var n=notes.get(i).note;
            int slot=i%3;
            if (n.beamCount()!=(slot==1 ? 2 : 1) || n.clefBottomDiatonic()!=clef
                    || n.stemDirection()!=notes.get(i-slot).note.stemDirection()
                    || n.augmentationDots()>(slot==0 ? 1 : 0)) return false;
            if (slot==0 && n.augmentationDots()==0) missingDots++;
        }
        return missingDots==1;
    }

    private static boolean printedGroups(Raster p, StaffPitchTrack track,
            List<Head> heads, List<Head> extras) {
        int provedExtras=0;
        for (int offset=0;offset<heads.size();offset+=3) {
            var a=heads.get(offset); var b=heads.get(offset+1); var c=heads.get(offset+2);
            float gap=track.at(b.x)[1];
            int direction=b.note.stemDirection();
            if (gap<10 || gap>27 || b.x-a.x<gap*1.7f || b.x-a.x>gap*3.5f
                    || c.x-b.x<gap*1.45f || c.x-b.x>gap*2.25f
                    || !dot(p,a,b,gap) || dot(p,b,c,gap)) return false;
            if (offset+3<heads.size()) {
                var d=heads.get(offset+3);
                if (d.x-c.x<gap*1.7f || d.x-c.x>gap*3.5f || dot(p,c,d,gap)
                        || direction==d.note.stemDirection()
                        && !SeparateBeamGroups.between(p.ink,p.width,p.height,c.x,c.y,d.x,d.y,gap))
                    return false;
            }
            boolean normalFirst=PrintedTupletBeamOwner.connectedAwayFromRules(
                    p.ink,p.width,p.height,a.x,a.y,b.x,b.y,gap,direction,track);
            boolean normalLast=PrintedTupletBeamOwner.connectedAwayFromRules(
                    p.ink,p.width,p.height,b.x,b.y,c.x,c.y,gap,direction,track);
            var extra=extras.stream().filter(h -> h.x>a.x && h.x<b.x).toList();
            if (extra.isEmpty()) {
                if (!normalFirst || !normalLast) return false;
                continue;
            }
            if (extra.size()!=1 || direction!=-1) return false;
            var first=bridge(p,track,a,b,gap);
            var next=bridge(p,track,b,c,gap);
            if (first==null || next==null || Math.abs(first.b.x-next.a.x)>gap*.17f
                    || Math.abs(first.center(b.x)-next.center(b.x))>gap*.38f
                    || !ownedBranch(p,track,extra.get(0),b,first)) return false;
            provedExtras++;
        }
        return provedExtras==extras.size();
    }

    private static boolean dot(Raster p, Head h, Head next, float gap) {
        return WrappedTripletDotInk.uniqueCore(p.ink,p.width,p.height,h.x,h.y,next.x,gap)!=null
                || LegacyTripletDotInk.singleDotCore(p.ink,p.width,p.height,h.x,h.y,next.x,gap);
    }

    private static Bridge bridge(Raster p, StaffPitchTrack track, Head a, Head b, float gap) {
        var left=shaftEnds(p,a,gap);
        var right=shaftEnds(p,b,gap);
        Bridge best=null;
        float bestScore=Float.NEGATIVE_INFINITY;
        int margin=Math.round(gap*.24f);
        int band=Math.max(1,Math.round(gap*.12f));
        for (var x:left) for (var y:right) {
            if (y.x-x.x<gap*1.15f || Math.abs(y.y-x.y)>.63f*(y.x-x.x)) continue;
            for (int inset=Math.round(gap*.23f);inset<=Math.round(gap*.45f);inset++) {
                var candidate=new Bridge(x,y,inset,gap);
                int full=0;
                int isolated=0;
                int cols=y.x-x.x-2*margin+1;
                for (int px=x.x+margin;px<=y.x-margin;px++) {
                    int cy=Math.round(candidate.center(px));
                    int hits=0;
                    for (int dy=-band;dy<=band;dy++) if (p.dark(px,cy+dy)) hits++;
                    if (hits>=2*band) full++;
                    if (!nearRule(track.at(px),cy,.22f)) isolated++;
                }
                if (cols<8 || full<cols*.94f || isolated<cols*.35f) continue;
                float score=full/(float)cols+x.density+y.density-.01f*inset;
                if (score>bestScore) {
                    best=candidate;
                    bestScore=score;
                }
            }
        }
        return best;
    }

    /** Allow small paper holes only between an ink-attached head shoulder and a dark far tip. */
    private static List<Shaft> shaftEnds(Raster p, Head head, float gap) {
        int travel=-head.note.stemDirection();
        int center=Math.round(head.x-travel*gap*.52f), shoulder=Math.round(gap*.24f);
        int first=Math.round(head.y+travel*gap*.23f);
        int minimum=Math.round(gap*1.8f), limit=Math.round(gap*5.4f);
        List<Shaft> shafts=new ArrayList<>();
        for (int x=center-shoulder;x<=center+shoulder;x++) {
            if (!p.dark(x,first) && !p.dark(x,first+travel) && !p.dark(x,first+2*travel))
                continue;
            int dark=0, missed=0;
            for (int step=0;step<=limit;step++) {
                int y=first+step*travel;
                boolean hit=p.dark(x,y);
                if (hit) { dark++; missed=0; }
                else if (++missed>gap*.57f) break;
                float density=dark/(float)(step+1);
                if (step<minimum || !hit || density<.78f) continue;
                int clear=0;
                for (int d=1;d<=4;d++) if (!p.dark(x,y+travel*d)) clear++;
                if (clear>=3) shafts.add(new Shaft(x,y,travel,density));
            }
        }
        return shafts;
    }

    private static boolean ownedBranch(Raster p, StaffPitchTrack track, Head guest, Head owner,
            Bridge primary) {
        float gap=primary.gap;
        int shaft=primary.b.x;
        if (shaft-guest.x<gap*.22f || shaft-guest.x>gap*.63f
                || owner.x-guest.x<gap*.7f || owner.x-guest.x>gap*1.25f
                || guest.y-owner.y<gap*2.3f || guest.y-owner.y>gap*4.1f
                || primary.center(guest.x)-guest.y<gap*.45f
                || primary.center(guest.x)-guest.y>gap*1.05f) return false;
        int left=Math.round(guest.x-gap*.96f), right=Math.round(guest.x+gap*.91f);
        int top=Math.round(guest.y-gap*1.02f), bottom=Math.round(guest.y+gap*.65f);
        int minX=right+1, maxX=left-1, foreign=0, area=0;
        for (int x=left;x<=right;x++) {
            float[] rails=track.at(x);
            if (x<shaft-gap*.12f || x>shaft+gap*.14f) {
                int mark=0;
                for (int y=top;y<=bottom;y++) {
                    if (!p.dark(x,y) || nearRule(rails,y,.16f)
                            || y>=primary.center(x)-gap*(x>shaft ? .63f : .43f)) continue;
                    mark++;
                }
                if (mark>=3) {
                    minX=Math.min(minX,x);
                    maxX=Math.max(maxX,x);
                    area+=mark;
                    if (x>shaft+gap*.14f) foreign+=mark;
                }
            }
        }
        // Separate a true right shoulder from a staff rule crossing the branch.
        int rightRival=0;
        int referenceStart=shaft+Math.round(gap*.68f);
        for (int x=shaft+Math.round(gap*.18f); x<=shaft+Math.round(gap*.5f); x++) {
            for (int y=Math.round(guest.y-gap*.48f);
                    y<=Math.round(guest.y+gap*.11f); y++) {
                if (y>=primary.center(x)-gap*.37f || !p.dark(x,y)) continue;
                int light=0;
                for(int sample=referenceStart;sample<referenceStart+4;sample++)
                    if(!p.dark(sample,y)) light++;
                if(light>=3) rightRival++;
            }
        }
        if (rightRival >= Math.round(gap * gap * .03f)) return false;
        if (foreign>gap*.4f || shaft-minX<gap*.58f || shaft-minX>gap*.99f
                || maxX<shaft-gap*.32f || area<gap*gap*.25f || area>gap*gap*.65f) return false;
        // The branch must join the owned shaft on three rows. A detached spot cannot vote.
        for (int shift=-Math.round(gap*.75f);shift<=Math.round(gap*.14f);shift++) {
            int y=Math.round(guest.y)+shift;
            if (nearRule(track.at(shaft),y,.21f)
                    || y>primary.center(shaft)-gap*.42f) continue;
            boolean full=true;
            for (int x=Math.round(guest.x);x<=shaft;x++)
                for (int d=-1;d<=1;d++)
                    if (!p.dark(x,y+d)) full=false;
            if (full) return true;
        }
        return false;
    }

    private static boolean nearRule(float[] rails, float y, float tolerance) {
        float line=(rails[0]-y)/rails[1];
        return line>=-.18f && line<=4.18f && Math.abs(line-Math.round(line))<=tolerance;
    }

    private static ScoreNoteEvent withTriplet(ScoreNoteEvent n,int dots) {
        return new ScoreNoteEvent(n.measureIndex(),n.positionInMeasure(),n.staffStep(),n.staffIndex(),
                n.staffCount(),n.pageY(),n.tiedFromPrevious(),dots,n.beamCount(),n.writtenAccidental(),
                n.unbeamedDurationBeats(),3,n.followingRestBeats(),n.articulations(),
                n.clefBottomDiatonic(),n.crossStaffBeam(),n.leadingRestBeats(),n.compactOpening(),
                n.octaveShift(),n.boundaryTies(),2,n.stemDirection(),n.kind(),n.visualSize(),
                n.harmonicSemitones(),n.openStringTouch());
    }
}
