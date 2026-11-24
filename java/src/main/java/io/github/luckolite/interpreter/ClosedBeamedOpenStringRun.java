// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

/** Closes an independently traced run of open-string diamonds and stopped fourths. */
final class ClosedBeamedOpenStringRun {
    private ClosedBeamedOpenStringRun() {}

    record Zero(float x, float y, boolean openFoot) {}
    record Head(float x, float y, int step, PrintedBeamShafts.Shaft shaft, boolean ruledDiamond) {}
    record Staff(StaffPitchTrack track, float gap, float floor) {}
    record Existing(int owner, ScoreNoteEvent note, float x, float y, int slot) {}
    record Attack(Head head, Existing existing, boolean stopped) {}

    static ScorePageInterpretation capture(
            ScorePageInterpretation score, List<Map<String,Object>> events, int page,
            byte[] labels, byte[] original, byte[] normalized, int w, int h,
            float[] beats, double[] starts, int[] keys) {
        if (page<0 || labels==null || original==null || normalized==null
                || original.length!=(long)w*h || labels.length!=original.length
                || normalized.length!=original.length || events.size()!=score.notes().size())
            return score;
        var measures=score.measures();
        var byBar=new ArrayList<List<Integer>>(measures.size());
        for (int i=0;i<measures.size();i++) byBar.add(new ArrayList<>());
        for (int i=0;i<events.size();i++) {
            int bar=score.notes().get(i).measureIndex();
            if (bar>=0 && bar<byBar.size()) byBar.get(bar).add(i);
        }
        for (int bar=0;bar<byBar.size();bar++) {
            int measure=bar;
            var row=byBar.get(bar);
            if (bar>=beats.length || bar>=starts.length || bar>=keys.length || beats[bar]!=4
                    || row.size()!=7 || row.stream().filter(i ->
                            Boolean.TRUE.equals(events.get(i).get("beamedOpenStringHarmonic"))).count()!=2
                    || score.rests().stream().anyMatch(r->r.measureIndex()==measure)) continue;
            var geometry=measures.get(bar);
            var existing=existing(score,events,geometry,row,starts[bar],w,h);
            if (existing.isEmpty()) continue;
            var left=prove(labels,original,w,h,geometry,existing);
            var right=prove(labels,normalized,w,h,geometry,existing);
            if (left.size()!=15 || right.size()!=15 || !samePrint(left,right)) continue;
            var repaired=retain(score,events,page,bar,geometry,w,h,starts[bar],keys[bar],left,right);
            if (repaired!=score) return repaired;
        }
        return score;
    }

    private static List<Existing> existing(ScorePageInterpretation score, List<Map<String,Object>> events,
            MeasureRegion bar, List<Integer> row, double start, int w, int h) {
        var result=new ArrayList<Existing>();
        var slots=new HashSet<Integer>();
        for(int owner:row) {
            var n=score.notes().get(owner);
            var e=events.get(owner);
            if (!plain(n) || !(e.get("sourceNoteIndex") instanceof Number id)
                    || id.intValue()!=owner || !(e.get("startBeat") instanceof Number onset)
                    || !(e.get("durationBeats") instanceof Number duration) || duration.doubleValue()!=.25
                    || !(e.get("writtenPlacement") instanceof ScoreNoteTiming.WrittenPlacement p)
                    || p.grace() || p.durationBeats()!=.25
                    || Math.abs(p.onsetBeats()-(onset.doubleValue()-start))>1e-6
                    || score.beamSource().noteOrigins().get(owner).isPresent()) return List.of();
            int slot=(int)Math.rint(p.onsetBeats()*4);
            if (slot<0 || slot>13 || Math.abs(p.onsetBeats()-slot*.25)>1e-6 || !slots.add(slot))
                return List.of();
            float x=(bar.left()+n.positionInMeasure()*(bar.right()-bar.left()))*w;
            result.add(new Existing(owner,n,x,n.pageY()*h,slot));
        }
        if (result.stream().filter(a->a.note.octaveShift()==2).count()!=3) return List.of();
        result.sort(Comparator.comparingDouble(Existing::x));
        return result;
    }

    private static boolean plain(ScoreNoteEvent n) {
        return n.kind()==ScoreNoteEvent.Kind.PITCHED && n.staffIndex()==0 && n.staffCount()==1
                && n.clefBottomDiatonic()==ScoreNoteEvent.CLEF_TREBLE
                && n.visualSize()==ScoreNoteEvent.VisualSize.FULL && n.beamCount()==2
                && n.unbeamedDurationBeats()==0 && n.augmentationDots()==0
                && n.stemDirection()!=0 && n.tupletDivisor()==1 && !n.crossStaffBeam()
                && n.tupletNormalNotes()==1 && n.writtenAccidental()==ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && n.harmonicSemitones()==0 && n.openStringTouch()==null
                && !n.tiedFromPrevious() && n.boundaryTies()==0 && n.articulations()==0
                && n.leadingRestBeats()==0 && n.followingRestBeats()==0;
    }

    static List<Attack> prove(byte[] labels, byte[] ink, int w, int h, MeasureRegion bar,
            List<Existing> existing) {
        var staff=staff(ink,w,h,bar);
        if (staff==null) return List.of();
        var stops=existing.stream().filter(a->a.note.octaveShift()==2).toList();
        if (stops.size()!=3 || stops.get(0).note.stemDirection()!=1
                || stops.get(1).note.stemDirection()!=-1 || stops.get(2).note.stemDirection()!=1)
            return List.of();
        var zeros=zeroes(ink,w,h,bar,staff);
        if (zeros.size()!=12 || zeros.stream().filter(Zero::openFoot).count()>1) return List.of();
        var result=new ArrayList<Attack>(15);
        var used=new HashSet<Integer>();
        float gap=staff.gap;
        for (int group=0;group<4;group++) {
            var stop=group<3?stops.get(group):null;
            int direction=stop==null?1:stop.note.stemDirection();
            for (var z:zeros.subList(group*3,group*3+3)) {
                var nearby=existing.stream().filter(e->e.note.octaveShift()==0
                        && Math.abs(e.x-z.x)<gap*.40f).toList();
                if (nearby.size()>1 || stop!=null && z.x>=stop.x-gap*.9f) return List.of();
                var owner=nearby.isEmpty()?null:nearby.get(0);
                var choices=readDiamond(labels,ink,w,h,staff,z,direction,
                        new PrintedBeamShafts.Budget(1_950_000));
                if (owner!=null) {
                    if (!used.add(owner.owner)) return List.of();
                    choices=choices.stream().filter(a->a.step==owner.note.staffStep()
                            && Math.abs(a.y-owner.y)<gap*.32f).toList();
                } else if (choices.size()>1) {
                    choices=choices.stream().filter(Head::ruledDiamond).toList();
                }
                if (choices.size()!=1) return List.of();
                result.add(new Attack(choices.get(0),owner,false));
            }
            if (stop!=null) {
                var shaft=stopped(labels,ink,w,h,staff,stop);
                if (shaft==null || !used.add(stop.owner)) return List.of();
                result.add(new Attack(shaft,stop,true));
            }
        }
        if (used.size()!=existing.size()) return List.of();
        return closedRails(ink,w,h,bar,staff,result)?result:List.of();
    }

    private static Head stopped(byte[] labels, byte[] ink, int w, int h, Staff staff,
            Existing base) {
        var local=staff.track.at(base.x);
        float gap=local[1], touch=local[0]-(base.note.staffStep()+3)*gap*.5f;
        int direction=base.note.stemDirection();
        var budget=new PrintedBeamShafts.Budget(160_000);
        var found=PrintedBeamShafts.at(ink,w,h,base.x,base.y,gap,budget);
        if (found.size()!=1 || found.get(0).direction()!=-direction) return null;
        var shaft=found.get(0);
        if (!diamond(labels,ink,w,h,base.x,touch,gap,shaft,direction)
                && !ArtificialHarmonics.symbolDiamond(labels,ink,w,h,base.x,touch,gap)) return null;
        int axis=(shaft.left()+shaft.right())/2;
        for (int y=Math.round(touch);y<=Math.round(base.y);y++)
            if (y<0 || y>=h || (ink[y*w+axis]&255)>=185) return null;
        int rx=Math.round(gap*.27f), ry=Math.round(gap*.18f), x=Math.round(base.x), y=Math.round(base.y);
        if (x-rx<0 || x+rx>=w || y-ry<0 || y+ry>=h) return null;
        int dark=0;
        for (int j=y-ry;j<=y+ry;j++)
            for (int i=x-rx;i<=x+rx;i++)
                if ((ink[j*w+i]&255)<155) dark++;
        return dark>=(2*rx+1)*(2*ry+1)*.75f
                ?new Head(base.x,base.y,base.note.staffStep(),shaft,false):null;
    }

    private static boolean closedRails(byte[] gray,int w,int h,MeasureRegion bar,Staff staff,
            List<Attack> row) {
        float g=staff.gap;
        float right=bar.right()*w;
        var local=staff.track.at(right-1.25f*g);
        var rules=new PlayingTechniqueDetector.Staff(local[0]-4*local[1],local[0],local[1],0,1);
        if (PrintedCadenceDurations.boundaryColumn(gray,w,rules,right,true,false)<0
                || row.get(0).head.x-bar.left()*w<g*.92f
                || right-row.get(row.size() - 1).head.x<g*1.82f
                || right-row.get(row.size() - 1).head.x>g*3.8f) return false;
        for (int i=0;i<row.size();i++) {
            var next=row.get(i);
            if (next.existing!=null && next.existing.slot!=i) return false;
            if (i==0) continue;
            var a=row.get(i-1).head;
            var b=next.head;
            float space=b.x-a.x;
            if (space<g*1.65f || space>g*3.65f) return false;
            if (i==4 || i==8) {
                if (a.shaft.direction()==b.shaft.direction()
                        || Math.abs(a.shaft.endY()-b.shaft.endY())<g*4) return false;
            } else if (!WrappedEighthTriplets.matchesBeamRails(
                    gray,w,h,a.shaft,b.shaft,g,i==12?0:i==14?1:2)) return false;
        }
        return true;
    }

    private static boolean samePrint(List<Attack> a,List<Attack> b) {
        for (int i=0;i<a.size();i++) {
            var x=a.get(i);var y=b.get(i);
            if (x.existing!=y.existing || x.stopped!=y.stopped || x.head.step!=y.head.step
                    || Math.abs(x.head.x-y.head.x)>2 || Math.abs(x.head.y-y.head.y)>2
                    || x.head.shaft.direction()!=y.head.shaft.direction()
                    || Math.abs(x.head.shaft.endY()-y.head.shaft.endY())>2) return false;
        }
        return true;
    }

    private static ScorePageInterpretation retain(ScorePageInterpretation score,
            List<Map<String,Object>> events,int page,int bar,MeasureRegion region,int w,int h,
            double start,int key,List<Attack> raw,List<Attack> normalized) {
        var notes=new ArrayList<>(score.notes());
        var changed=new LinkedHashMap<Integer,Map<String,Object>>();
        var indices=new ArrayList<>(IntStream.range(0,notes.size()).boxed().toList());
        var owners=new ArrayList<Integer>(15);
        for(int slot=0;slot<15;slot++) {
            var a=raw.get(slot);
            int step=a.head.step, diatonic=ScoreNoteEvent.CLEF_TREBLE+step;
            int written=ScoreTiePitchGuard.writtenMidi(diatonic,ScoreNoteEvent.ACCIDENTAL_FROM_KEY,key);
            int owner=a.existing==null?notes.size():a.existing.owner;
            double duration=slot==14?.5:.25, onset=start+slot*.25;
            Map<String,Object> event=a.existing==null?new LinkedHashMap<>()
                    :new LinkedHashMap<>(events.get(owner));
            if (a.stopped) {
                int touch=written+5;
                // The score gives the stopped pitch and the shared, two-octave harmonic role.
                // Its touching pitch is the perfect fourth on that same string.
                if(Math.abs(ScoreTiePitchGuard.writtenMidi(
                            diatonic+3,ScoreNoteEvent.ACCIDENTAL_FROM_KEY,key)-touch)>1
                        || !Integer.valueOf(written+24).equals(event.get("midi")))
                    return score;
                notes.set(owner,a.existing.note.withOctaveShift(0).withHarmonicSemitones(24));
                event.remove("octaveShift");
                event.put("writtenMidi",written);
                event.put("harmonicTouchMidi",touch);
                event.put("artificialHarmonicFourth",true);
                event.put("beamedArtificialHarmonicFourth",true);
            } else {
                var pitch=OpenStringHarmonics.sounding(written);
                if(pitch==null) return score;
                if(a.existing==null) {
                    float x=(a.head.x+normalized.get(slot).head.x)*.5f;
                    float y=(a.head.y+normalized.get(slot).head.y)*.5f;
                    var note=new ScoreNoteEvent(bar,(x/w-region.left())/(region.right()-region.left()),
                            step,0,1,y/h,false,0,slot==14?1:2,ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                            0,1,0,0,ScoreNoteEvent.CLEF_TREBLE,false,0,false,0,0,1,-a.head.shaft.direction());
                    notes.add(note);
                    indices.add(-1);
                    event.put("measureIndex",bar);
                    event.put("staffIndex",0);
                    event.put("staffCount",1);
                    event.put("sourceNoteIndex",owner);
                    event.put("x",x);
                    event.put("y",y);
                    event.put("clefInferred",false);
                    event.put("durationFallback",false);
                    event.put("tiedFromPrevious",false);
                    event.put("startBeat",onset);
                    event.put("durationBeats",duration);
                    event.put("writtenPlacement",new ScoreNoteTiming.WrittenPlacement(
                            slot*.25,duration,-1,-1,false,0));
                }
                event.put("midi",pitch.sounding());
                event.put("writtenMidi",written);
                event.put("harmonicTouchMidi",written);
                event.put("openStringMidi",pitch.open());
                event.put("beamedOpenStringHarmonic",true);
            }
            owners.add(owner);
            changed.put(owner,event);
        }
        var edited=score.withNotes(notes,indices)
                .withClockContextSource(score.clockContextSource().withNoteCount(notes.size()));
        var beams=beams(edited.beamSource(),owners,page);
        if (beams==null) return score;
        for(var update:changed.entrySet()) {
            int owner=update.getKey();
            if(owner==events.size()) events.add(update.getValue());
            else events.set(owner,update.getValue());
        }
        return edited.withBeamSource(beams);
    }

    private static ScoreBeamSource beams(ScoreBeamSource old,List<Integer> owners,int page) {
        var origins=new ArrayList<>(old.noteOrigins());
        var unavailable=new HashSet<ScoreBeamSource.Anchor>();
        for(var o:origins)o.ifPresent(unavailable::add);
        for(var g:old.groups())for(var c:g.columns())unavailable.addAll(c.members());
        for(var c:old.detachedColumns())unavailable.addAll(c.members());
        var groups=new ArrayList<>(old.groups());
        for(int first:new int[]{0,4,8,12}) {
            var columns=new ArrayList<ScoreBeamSource.Column>();
            for(int i=first;i<Math.min(first+4,owners.size());i++) {
                int owner=owners.get(i);
                var anchor=new ScoreBeamSource.Anchor(page,owner);
                if(origins.get(owner).isPresent() || !unavailable.add(anchor))return null;
                origins.set(owner,Optional.of(anchor));
                columns.add(new ScoreBeamSource.Column(List.of(anchor)));
            }
            String id="closed-open-beams:"+page+":"+owners.get(first);
            if(groups.stream().anyMatch(g->g.id().equals(id)))return null;
            groups.add(new ScoreBeamSource.Group(id,columns,first==12?List.of(3,1):List.of(3,3,3),List.of()));
        }
        return new ScoreBeamSource(groups,old.detachedColumns(),origins);
    }

    static Staff staff(byte[] gray, int w, int h, MeasureRegion bar) {
        float gap = (bar.bottom() - bar.top()) * h / 8.2f;
        if (gap < 10 || gap > 24) return null;
        float floor = bar.bottom() * h - 2.1f * gap;
        var track = StaffPitchTrack.forPrintedSlurs(gray, w, h, floor - gap * 4, floor, gap);
        if (track == null) return null;
        var center = track.at((bar.left() + bar.right()) * w * .5f);
        return new Staff(track, center[1], center[0]);
    }

    static List<Zero> zeroes(byte[] gray, int w, int h, MeasureRegion bar, Staff staff) {
        float gap = staff.gap;
        int left = Math.round(bar.left() * w), right = Math.round(bar.right() * w);
        int top = Math.round(staff.floor + gap), bottom = Math.round(staff.floor + 6.1f * gap);
        if (right-left < 30*gap || right-left > 42*gap || top < 0 || bottom >= h) return List.of();
        int reach = Math.round(gap * 1.1f), strip = Math.round(gap * 3.7f);
        var zeros = new ArrayList<Zero>();
        var budget = new NaturalHarmonicRingProof.Budget(5_000_000, 13_000_000);
        for (int x = left; x < right; x += strip) {
            int next = Math.min(right, x + strip);
            var box = new NaturalHarmonicRingProof.Box(
                    Math.max(0, x-reach), top, Math.min(w-1, next+reach), bottom);
            for (int cutoff : new int[] {150, 175, 195, 215}) {
                for (var ring : NaturalHarmonicRingProof.rings(gray,w,h,box,cutoff,
                        Math.round(.57f*gap),Math.round(1.56f*gap),1.70f,budget)) {
                    if (ring.centerX() < x || ring.centerX() >= next
                            || ring.counterPixels() < gap*gap*.16f
                            || ring.bounds().height() < ring.bounds().width() + 1) continue;
                    var old = zeros.stream().anyMatch(z -> Math.abs(z.x-ring.centerX()) < gap*.28f
                            && Math.abs(z.y-ring.centerY()) < gap*.30f);
                    if (!old) zeros.add(new Zero(ring.centerX(),ring.centerY(),false));
                }
            }
            var components = RawNumeralComponents.capture(
                    gray,w,h,box.left(),box.top(),box.right(),box.bottom(),165,() -> {},() -> {});
            for (var c : components.components()) {
                if (c.left==box.left() || c.right==box.right() || c.top==box.top()
                        || c.bottom==box.bottom() || !taperedOpenFoot(gray,w,c,gap)) continue;
                float cx=(c.left+c.right)*.5f, cy=(c.top+c.bottom)*.5f;
                if (cx<x || cx>=next || zeros.stream().anyMatch(z -> Math.abs(z.x-cx)<gap*.28f
                        && Math.abs(z.y-cy)<gap*.30f)) continue;
                zeros.add(new Zero(cx,cy,true));
            }
        }
        return zeros.stream().sorted(Comparator.comparingDouble(Zero::x)).toList();
    }

    /** The two sides of a damaged zero still join at its crown and taper toward its open foot. */
    private static boolean taperedOpenFoot(byte[] gray, int w, RawNumeralComponents.Component box,
            float gap) {
        int width=box.right-box.left+1, height=box.bottom-box.top+1;
        if (width<.82f*gap || width>1.20f*gap || height<1.06f*gap || height>1.57f*gap
                || box.size()<width*height*.39f || box.size()>width*height*.69f) return false;
        int crown=0, paired=0, tapered=0, widest=0, footWidth=width;
        for (int j=0; j<height; j++) {
            int starts=0, first=box.right+1,last=-1, leftEnd=-1, rightStart=-1;
            boolean dark=false;
            for (int x=box.left; x<=box.right; x++) {
                boolean ink=(gray[(box.top+j)*w+x]&255)<=165;
                if (ink && !dark) {
                    starts++;
                    if (starts==2) { rightStart=x; leftEnd=last; }
                }
                if (ink) { first=Math.min(first,x); last=x; }
                dark=ink;
            }
            if (starts>2) return false;
            int span=last-first+1;
            if (j<height*.25f && starts==1 && span>=width*.51f) crown++;
            if (j>=height*.27f && j<height*.79f && starts==2) {
                int pocket=rightStart-leftEnd-1;
                if (pocket<gap*.19f || pocket>gap*.58f || leftEnd-first+1<2
                        || last-rightStart+1<2 || span<width*.73f) return false;
                paired++;
                widest=Math.max(widest,span);
            }
            if (j>=height*.79f) {
                if (starts!=2) return false;
                if (span<=width*.82f) tapered++;
                footWidth=Math.min(footWidth,span);
            }
        }
        return crown>=2 && paired>=Math.round(height*.43f) && tapered>=2
                && widest-footWidth>=Math.round(gap*.22f);
    }

    static List<Head> readDiamond(byte[] labels, byte[] gray, int w, int h, Staff staff,
            Zero zero, int direction, PrintedBeamShafts.Budget budget) {
        var local = staff.track.at(zero.x);
        var result = new ArrayList<Head>();
        for (int step = -3; step <= 11; step++) {
            float y = local[0] - step*local[1]*.5f;
            for (int dx : new int[] {0,-1,1,-2,2,-3,3}) {
                float x = zero.x+dx;
                var axes = PrintedBeamShafts.at(gray,w,h,x,y,local[1],budget);
                if (budget.refused()) return List.of();
                if (axes.size()!=1 || axes.get(0).direction()!=-direction
                        || !diamond(labels,gray,w,h,x,y,local[1],axes.get(0),direction))
                    continue;
                var shaft=axes.get(0);
                boolean onRule=LedgerCrossedHarmonicDiamond.proved(labels,gray,w,h,x,y,local[1],
                        (shaft.left()+shaft.right())/2,direction);
                result.add(new Head(x,y,step,shaft,onRule));
                break;
            }
        }
        return result;
    }

    static boolean diamond(byte[] labels, byte[] gray, int w, int h, float x, float y, float gap,
            PrintedBeamShafts.Shaft shaft, int direction) {
        int axis = (shaft.left()+shaft.right())/2;
        return ArtificialHarmonics.shaftCrossedDiamond(gray,w,h,x,y,gap,axis,direction,true)
                || LedgerCrossedHarmonicDiamond.proved(labels,gray,w,h,x,y,gap,axis,direction);
    }
}
