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
            if (meter == null || meter.denominator() != 4) continue;
            boolean mixed = meter.numerator() == 4 && heads.size() == 16;
            if (mixed || meter.numerator() == 6 && heads.size() == 20) {
                if (score.rests().stream().anyMatch(r -> r.measureIndex() == index)) continue;
            } else continue;
            if (mixed) {
                var kept = qualifiedMixedRun(raster, score.measures().get(barIndex), heads);
                if (kept == null) continue;
                for (var head : heads) remove[head.owner] = true;
                for (int slot = 0; slot < kept.size(); slot++) {
                    var head = kept.get(slot);
                    remove[head.owner] = false;
                    if (slot < 6)
                        rewrite[head.owner] = withTriplet(head.note, slot % 3 == 0 ? 1 : 0);
                }
                changed = true;
                continue;
            }
            if (heads.stream().anyMatch(h -> !ordinary(h.note))) continue;
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
        return ordinaryRhythm(n) && Math.abs(n.stemDirection()) == 1;
    }

    private static boolean ordinaryRhythm(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED
                && n.staffIndex() == 0
                && n.staffCount() == 1
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.harmonicSemitones() == 0
                && n.openStringTouch() == null
                && !n.crossStaffBeam()
                && n.tupletDivisor() == 1
                && n.leadingRestBeats() == 0
                && n.followingRestBeats() == 0
                && !n.tiedFromPrevious()
                && n.boundaryTies() == 0
                && (n.articulations() & NoteOrnament.GRACE) == 0
                && n.clefBottomDiatonic() != ScoreNoteEvent.CLEF_UNKNOWN;
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

    /** Two separated dotted triplets followed by four dotted-sixteenth and hook pairs. */
    private static List<Head> qualifiedMixedRun(
            Raster raster, MeasureRegion bar, List<Head> heads) {
        if (bar.cadenceQuarterBeats() != 0
                || heads.stream()
                        .anyMatch(
                                h ->
                                        !ordinaryRhythm(h.note)
                                                || h.note.unbeamedDurationBeats() != 0))
            return null;
        var opposite = heads.stream().filter(h -> h.note.stemDirection() == 1).toList();
        var stemless = heads.stream().filter(h -> h.note.stemDirection() == 0).toList();
        if (opposite.size() != 1 || stemless.size() != 1) return null;
        var safe =
                heads.stream()
                        .filter(h -> h.note.stemDirection() == -1)
                        .sorted(Comparator.comparingDouble(Head::x))
                        .toList();
        if (safe.size() != 14) return null;
        float left = bar.left() * raster.width, right = bar.right() * raster.width;
        float gap = (bar.bottom() - bar.top()) * raster.height / 8.2f;
        if (gap < 10
                || gap > 27
                || right - left < gap * 32
                || right - left > gap * 45
                || safe.get(0).x - left < gap * 1.2f
                || safe.get(0).x - left > gap * 3.2f
                || right - safe.get(13).x < gap * .85f
                || right - safe.get(13).x > gap * 2.4f) return null;
        var samples = new ArrayList<float[]>();
        for (int i = 0; i <= 12; i++) {
            float x = left + (right - left) * i / 12;
            var staff =
                    PrintedDirectionStaff.printedRules(
                            raster.ink,
                            raster.width,
                            raster.height,
                            x,
                            bar.bottom() * raster.height - 2.1f * gap,
                            gap);
            if (staff != null) samples.add(new float[] {x, staff[0], staff[1]});
        }
        if (samples.size() < 7) return null;
        var track = StaffPitchTrack.fromVerifiedSamples(samples);
        if (!barline(raster, right, track.at(right))) return null;
        int clef = safe.get(0).note.clefBottomDiatonic();
        for (int i = 0; i < safe.size(); i++) {
            var h = safe.get(i);
            int index = i < 6 ? i % 3 : (i - 6) % 2;
            int beams = i < 6 ? (index == 1 ? 2 : 1) : (index == 0 ? 2 : 3);
            if (h.note.beamCount() != beams
                    || h.note.clefBottomDiatonic() != clef
                    || h.note.augmentationDots() != (index == 0 ? 1 : 0)) return null;
            float end = i + 1 < safe.size() ? safe.get(i + 1).x : right;
            if (i + 1 < safe.size() && (end - h.x < gap * 1.55f || end - h.x > gap * 4.45f))
                return null;
            if (i < 6 && detachedDot(raster, h, end, gap) != (index == 0)) return null;
            if (i >= 6 && index == 0 && !dot(raster, h, safe.get(i + 1), gap)) return null;
        }
        for (int i = 0; i < 6; i++) {
            var a = safe.get(i);
            var b = safe.get(i + 1);
            if (i % 3 == 2) {
                if (!SeparateBeamGroups.between(
                        raster.ink, raster.width, raster.height, a.x, a.y, b.x, b.y, gap))
                    return null;
            } else if (!PrintedTupletBeamOwner.connectedAwayFromRules(
                    raster.ink, raster.width, raster.height, a.x, a.y, b.x, b.y, gap, -1, track))
                return null;
        }
        boolean oppositeAlias = false;
        for (int i : new int[] {1, 4}) {
            var a = safe.get(i);
            var b = safe.get(i + 1);
            if (opposite.get(0).x <= a.x || opposite.get(0).x >= b.x) continue;
            var beam = bridge(raster, track, a, b, gap);
            oppositeAlias = beam != null && reversedBeamTip(raster, opposite.get(0), b, beam);
        }
        if (!oppositeAlias) return null;

        boolean ribbonAlias = false;
        var lower = stemless.get(0);
        if (lower.note.beamCount() != 3
                || lower.note.augmentationDots() != 0
                || lower.note.articulations() != 0
                || PrintedStemDirection.detect(
                                raster.ink, raster.width, raster.height, lower.x, lower.y, gap)
                        != 0) return null;
        for (int i = 6; i < safe.size(); i += 2) {
            var a = safe.get(i);
            var b = safe.get(i + 1);
            if (!OwnedTertiaryBeamlets.provesBackwardHook(
                    raster.ink, raster.width, raster.height, b.x, b.y, a.x, a.y, gap)) return null;
            if (Math.abs(lower.x - a.x) < gap * .3f
                    && lower.y - a.y > gap * 3.5f
                    && ClosedDottedSixteenths.ownsDottedPairAlias(
                            raster.ink,
                            raster.width,
                            raster.height,
                            track,
                            gap,
                            a.x,
                            a.y,
                            b.x,
                            b.y,
                            lower.x,
                            lower.y)) ribbonAlias = true;
        }
        if (!ribbonAlias) return null;

        var trial = new ArrayList<ScoreNoteEvent>();
        for (int i = 0; i < safe.size(); i++) {
            var n = safe.get(i).note;
            trial.add(i < 6 ? withTriplet(n, i % 3 == 0 ? 1 : 0) : n);
        }
        for (int i = 0; i < trial.size(); i++) {
            var place = ScoreNoteTiming.writtenPlacement(trial.get(i), trial, 4f).orElse(null);
            float start, duration;
            if (i < 6) {
                int slot = i % 3;
                start = i / 3 + (slot == 0 ? 0 : slot == 1 ? .5f : 2f / 3f);
                duration = slot == 0 ? .5f : slot == 1 ? 1f / 6f : 1f / 3f;
            } else {
                int slot = i - 6;
                start = 2 + slot / 2 * .5f + (slot % 2 == 0 ? 0 : .375f);
                duration = slot % 2 == 0 ? .375f : .125f;
            }
            if (place == null
                    || Math.abs(place.onsetBeats() - start) > .0001f
                    || Math.abs(place.durationBeats() - duration) > .0001f) return null;
        }
        return safe;
    }

    private static boolean detachedDot(Raster raster, Head head, float next, float gap) {
        var dot =
                WrappedTripletDotInk.uniqueCore(
                        raster.ink, raster.width, raster.height, head.x, head.y, next, gap);
        if (dot == null) return false;
        int half = Math.round(gap * .21f);
        for (int x = Math.round(head.x + gap * .65f); x <= dot.x() - half; x++) {
            boolean clear = true;
            for (int y = dot.y() - half; y <= dot.y() + half; y++) {
                if (raster.dark(x, y)) {
                    clear = false;
                    break;
                }
            }
            if (clear) return true;
        }
        return false;
    }

    /** The reversed apparent shaft retraces the actual last note's lower beam end. */
    private static boolean reversedBeamTip(Raster raster, Head guest, Head owner, Bridge beam) {
        float gap = beam.gap;
        if (guest.note.beamCount() != 2
                || guest.note.augmentationDots() != 0
                || guest.note.articulations() != 0
                || beam.b.x - guest.x < gap * .35f
                || beam.b.x - guest.x > gap * .85f
                || guest.y - owner.y < gap * 3.5f
                || guest.y - owner.y > gap * 6
                || Math.abs(guest.y - beam.center(guest.x)) > gap * .26f) return false;
        var budget = new PrintedBeamShafts.Budget(100_000);
        var falseShaft =
                PrintedBeamShafts.at(
                        raster.ink, raster.width, raster.height, guest.x, guest.y, gap, budget);
        var realShaft =
                PrintedBeamShafts.at(
                        raster.ink, raster.width, raster.height, owner.x, owner.y, gap, budget);
        if (budget.refused() || falseShaft.size() != 1 || realShaft.size() != 1) return false;
        var a = falseShaft.get(0);
        var b = realShaft.get(0);
        return a.direction() == -1
                && b.direction() == 1
                && a.left() <= beam.b.x
                && a.right() >= beam.b.x
                && b.left() <= beam.b.x
                && b.right() >= beam.b.x
                && Math.abs(a.startY() - b.endY()) < gap * .6f
                && Math.abs(a.endY() - b.startY()) < gap * .6f;
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
