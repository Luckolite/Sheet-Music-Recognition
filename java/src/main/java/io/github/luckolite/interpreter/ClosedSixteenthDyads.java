// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** Reject an excess dotted sixteenth when independent beam evidence fills an entire printed bar. */
final class ClosedSixteenthDyads {
    private ClosedSixteenthDyads() {}

    record Ink(byte[] raw,int width,int height) {
        boolean dark(int x,int y) {
            return x>=0 && x<width && y>=0 && y<height && (raw[y*width+x]&255)<162;
        }
    }
    record Head(int owner,ScoreNoteEvent note,float x,float y) {}
    record Dyad(Head top,Head bottom) {}
    record Shaft(int x,int tip) {}
    record Rails(int x0,int y0,int x1,int y1,int separation) {
        int at(int x) {return Math.round(y0+(y1-y0)*(x-x0)/(float)(x1-x0));}
    }

    static ScorePageInterpretation apply(ScorePageInterpretation score,byte[] gray,int w,int h) {
        if (gray==null || gray.length!=(long)w*h || w<256 || h<128) return score;
        var ink=new Ink(gray,w,h);
        var notes=score.notes();
        var regions=score.measures();
        var bars=new ArrayList<List<Head>>();
        for (int i=0;i<regions.size();i++) bars.add(new ArrayList<>());
        ScoreMeterChange active=null;
        int meter=0;
        for (int i=0;i<notes.size();i++) {
            var n=notes.get(i);
            if (n.measureIndex() < 0 || n.measureIndex() >= regions.size()) continue;
            var b=regions.get(n.measureIndex());
            bars.get(n.measureIndex()).add(new Head(i,n,
                    (b.left()+n.positionInMeasure()*(b.right()-b.left()))*w,n.pageY()*h));
        }
        var result=new ArrayList<>(notes);
        for (int m=0;m<regions.size();m++) {
            while (meter<score.meterChanges().size() && score.meterChanges().get(meter).measureIndex()<=m)
                active=score.meterChanges().get(meter++);
            final int bar=m;
            int count = bars.get(m).size();
            if (active == null
                    || active.numerator() != 4
                    || active.denominator() != 4
                    || count != 30 && count != 32
                    || regions.get(m).cadenceQuarterBeats() != 0
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == bar)) continue;
            if (count == 30) {
                closeToEighth(ink, regions.get(m), bars.get(m), result);
                continue;
            }
            if (active.measureIndex()==m && closedHeaderSixteenths(ink,regions.get(m),bars.get(m))) {
                for (var head:bars.get(m)) {
                    var note=head.note.withTupletRatio(1,1);
                    result.set(head.owner,note.augmentationDots()==0 ? note : withoutDot(note));
                }
                if (!closes(bars.get(m),result))
                    for (var head:bars.get(m)) result.set(head.owner,head.note);
                continue;
            }
            var pair=prove(ink,regions.get(m),bars.get(m));
            if (pair==null) continue;
            result.set(pair.top.owner,withoutDot(pair.top.note));
            result.set(pair.bottom.owner,withoutDot(pair.bottom.note));
            if (!closes(bars.get(m),result)) {
                result.set(pair.top.owner,pair.top.note);
                result.set(pair.bottom.owner,pair.bottom.note);
            }
        }
        if (result.equals(notes)) return score;
        return score.withNotes(result,IntStream.range(0,notes.size()).boxed().toList());
    }

    private static void closeToEighth(
            Ink ink, MeasureRegion region, List<Head> heads, List<ScoreNoteEvent> result) {
        heads.sort(Comparator.comparingDouble(Head::x));
        float gap = (region.bottom() - region.top()) * ink.height / 8.2f;
        if (gap < 12
                || gap > 23
                || (region.right() - region.left()) * ink.width < gap * 42
                || (region.right() - region.left()) * ink.width > gap * 62
                || !WrappedMeasureBoundaries.confirmsClosed(
                        ink.raw, ink.width, ink.height, region, heads.get(0).x, heads.get(29).x))
            return;

        float sum = 0;
        int sampleCount = 0;
        float floor = region.bottom() * ink.height - gap * 2.1f;
        for (int slot = 0; slot < 15; slot += 2) {
            var sample =
                    PrintedDirectionStaff.printedRules(
                            ink.raw, ink.width, ink.height, heads.get(slot * 2).x, floor, gap);
            if (sample != null && Math.abs(sample[1] - gap) <= gap * .10f) {
                sum += sample[1];
                sampleCount++;
            }
        }
        if (sampleCount < 3) return;
        gap = sum / sampleCount;
        int clef = heads.get(0).note.clefBottomDiatonic();
        var geometry = new ClosedFourDyadBeam.Head[15];
        int tripletClaims = 0;
        for (int slot = 0; slot < 15; slot++) {
            var a = heads.get(slot * 2);
            var b = heads.get(slot * 2 + 1);
            if (b.x - a.x > gap * .15f
                    || Math.abs(b.y - a.y) < gap * .65f
                    || Math.abs(b.y - a.y) > gap * 1.4f) return;
            int actual = a.note.tupletDivisor();
            if (actual != 1 && actual != 3) return;
            if (actual == 3) tripletClaims |= 1 << slot;
            for (var head : List.of(a, b)) {
                var n = head.note;
                if (!ordinaryGeometry(n, clef)
                        || n.augmentationDots() != 0
                        || n.tupletDivisor() != actual
                        || n.tupletNormalNotes() != (actual == 3 ? 2 : 1)) return;
                if (slot < 14
                        && (n.beamCount() != 2
                                || n.stemDirection() != -1
                                || n.unbeamedDurationBeats() != 0)) return;
            }
            var upper = a.y < b.y ? a : b;
            var lower = a.y < b.y ? b : a;
            if (slot == 14
                    && (upper.note.beamCount() != 0
                            || upper.note.stemDirection() != -1
                            || upper.note.unbeamedDurationBeats() != 1
                            || lower.note.beamCount() != 1
                            || lower.note.stemDirection() != 0
                            || lower.note.unbeamedDurationBeats() != 0)) return;
            geometry[slot] = new ClosedFourDyadBeam.Head((a.x + b.x) * .5f, upper.y, lower.y);
        }
        if (!hasContainedTripletClaims(tripletClaims)) return;
        for (int i = 0; i < 12; i += 4) {
            var group = java.util.Arrays.copyOfRange(geometry, i, i + 4);
            boolean roomForPrintedAccidentals = true;
            for (int j = 1; j < 4; j++) {
                if (group[j].x() - group[j - 1].x() <= gap * 4.7f) continue;
                roomForPrintedAccidentals &=
                        heads.get((i + j) * 2).note.writtenAccidental()
                                        != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                                || heads.get((i + j) * 2 + 1).note.writtenAccidental()
                                        != ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
            }
            if (!roomForPrintedAccidentals
                    || !ClosedFourDyadBeam.provesWithAccidentalSpacing(
                            ink.raw, ink.width, ink.height, group, gap, 1)) return;
        }
        if (!ClosedFourDyadBeam.provesTwoSixteenthsAndEighth(
                ink.raw,
                ink.width,
                ink.height,
                java.util.Arrays.copyOfRange(geometry, 12, 15),
                gap)) return;
        var revised = new ArrayList<ScoreNoteEvent>(30);
        for (int i = 0; i < 30; i++) {
            var note = heads.get(i).note;
            revised.add(i < 28 ? note.withTupletRatio(1, 1) : asSharedEighth(note));
        }
        for (int i = 0; i < 30; i++) {
            var placed = ScoreNoteTiming.writtenPlacement(revised.get(i), revised, 4f).orElse(null);
            if (placed == null
                    || Math.abs(placed.onsetBeats() - (i / 2) * .25) > 1e-6
                    || Math.abs(placed.durationBeats() - (i < 28 ? .25 : .5)) > 1e-6) return;
        }
        for (int i = 0; i < 30; i++) result.set(heads.get(i).owner, revised.get(i));
    }

    /** Each candidate claim must contain three adjacent dyads under one closed double rail. */
    static boolean hasContainedTripletClaims(int claims) {
        if (claims == 0 || (claims & ~0xfff) != 0) return false;
        for (int bit = 0; bit < 12; bit += 4) {
            int underOneRail = claims >> bit & 0xf;
            if (underOneRail != 0 && underOneRail != 0b0111 && underOneRail != 0b1110) return false;
        }
        return true;
    }

    private static ScoreNoteEvent asSharedEighth(ScoreNoteEvent n) {
        return new ScoreNoteEvent(
                n.measureIndex(),
                n.positionInMeasure(),
                n.staffStep(),
                n.staffIndex(),
                n.staffCount(),
                n.pageY(),
                n.tiedFromPrevious(),
                n.augmentationDots(),
                1,
                n.writtenAccidental(),
                0,
                n.tupletDivisor(),
                n.followingRestBeats(),
                n.articulations(),
                n.clefBottomDiatonic(),
                n.crossStaffBeam(),
                n.leadingRestBeats(),
                n.compactOpening(),
                n.octaveShift(),
                n.boundaryTies(),
                n.tupletNormalNotes(),
                -1,
                n.kind(),
                n.visualSize(),
                n.harmonicSemitones(),
                n.openStringTouch());
    }

    private static boolean closedHeaderSixteenths(Ink ink,MeasureRegion bar,List<Head> heads) {
        long triplets=heads.stream().filter(h -> h.note.tupletDivisor()==3).count();
        long dots=heads.stream().filter(h -> h.note.augmentationDots()!=0).count();
        if (triplets!=12 || dots!=2) return false;
        float left=bar.left()*ink.width,right=bar.right()*ink.width;
        float gap=(bar.bottom()-bar.top())*ink.height/8.2f;
        if (gap<10 || gap>26 || right-left<32*gap || right-left>42*gap) return false;
        heads.sort(Comparator.comparingDouble(Head::x));
        var samples=new ArrayList<float[]>();
        for (int slot=0;slot<16;slot++) {
            if (slot%5==2) continue;
            var head=heads.get(slot*2);
            float hint=head.y+head.note.staffStep()*gap*.5f;
            var sample=localRules(ink,head.x,hint,gap);
            if (sample!=null) samples.add(new float[]{head.x,sample[0],sample[1]});
        }
        if (samples.size()<8) return false;
        var track=StaffPitchTrack.fromVerifiedSamples(samples);
        var entry=track.at(heads.get(0).x);
        gap=entry[1];
        float first=heads.get(0).x, last=heads.get(31).x;
        if (first-left<gap*4.7f || first-left>gap*6.6f || right-last<gap*1.15f
                || right-last>gap*2.0f || !closedSingleRule(ink,right,track.at(right))) return false;
        int top=Math.round(entry[0]-4*gap);
        int from=Math.round(first-gap*4.35f), to=Math.round(first-gap*1.45f);
        var crop=new MeterChangeDetector.Crop(from,Math.round(top-gap*.45f),to,
                Math.round(entry[0]+gap*.65f),top,gap);
        var meter=MeterFontMatcher.readCrop(ink.raw,ink.width,ink.height,crop);
        if (!meter.text().equals("4/4") || meter.topScore()<.80f || meter.bottomScore()<.80f)
            return false;

        List<Dyad> pairs=new ArrayList<>();
        int dotted=-1;
        int clef=heads.get(0).note.clefBottomDiatonic();
        for (int slot=0;slot<16;slot++) {
            var a=heads.get(slot*2);var b=heads.get(slot*2+1);
            var pair=a.y<b.y?new Dyad(a,b):new Dyad(b,a);
            int direction=slot<4 || slot>=12 ? 1 : -1;
            int ratio=slot<3 || slot>=13 ? 3 : 1;
            for (var head:List.of(a,b)) {
                var n=head.note;
                if (!ordinaryGeometry(n,clef) || n.stemDirection()!=direction || n.beamCount()!=2
                        || n.unbeamedDurationBeats()!=0 || n.tupletDivisor()!=ratio
                        || n.tupletNormalNotes()!=(ratio==3?2:1)) return false;
            }
            if (b.x-a.x>gap*.16f || pair.bottom.y-pair.top.y<gap*2.25f
                    || pair.bottom.y-pair.top.y>gap*2.85f
                    || a.note.augmentationDots()!=b.note.augmentationDots()) return false;
            if (a.note.augmentationDots()!=0) {
                if (dotted>=0 || slot<4 || slot>=8 || a.note.augmentationDots()!=2) return false;
                dotted=slot;
            }
            if (slot>0) {
                float spacing=pair.top.x-pairs.get(slot-1).top.x;
                if (spacing<gap*1.52f || spacing>gap*3.35f) return false;
            }
            pairs.add(pair);
        }
        if (dotted<0 || dotsByHead(ink,pairs.get(dotted),pairs.get(dotted+1),gap,track)) return false;
        List<List<Shaft>> axes=new ArrayList<>();
        for (int i=0;i<16;i++) {
            var stems=shafts(ink,pairs.get(i),gap,pairs.get(i).top.note.stemDirection());
            if (stems.isEmpty()) return false;
            axes.add(stems);
        }
        for (int i=0;i<15;i++) {
            var a=pairs.get(i);var b=pairs.get(i+1);
            int direction=a.top.note.stemDirection();
            int nextDirection=b.top.note.stemDirection();
            if (direction!=nextDirection) {
                if (i%4!=3) return false;
                continue;
            }
            var binding=beams(ink,axes.get(i),axes.get(i+1),a,b,gap,direction);
            if (i%4==3 ? !binding.isEmpty() : binding.isEmpty()) return false;
        }
        return true;
    }

    private static boolean closedSingleRule(Ink ink,float boundary,float[] rules) {
        float gap=rules[1],top=rules[0]-4*gap;
        int column=PrintedCadenceDurations.boundaryColumn(ink.raw,ink.width,
                new PlayingTechniqueDetector.Staff(top,rules[0],gap,0,1),
                boundary+gap*.5f,true,false);
        return column>=0 && !DottedDyadMeasureSeparator.continuesBeyondStaff(
                ink.raw,ink.width,ink.height,column,Math.round(top),Math.round(rules[0]),gap)
                && !ClosedHeadBarlineGuard.attached(
                        ink.raw,ink.width,ink.height,column,Math.round(top),Math.round(rules[0]),gap);
    }

    private static boolean dotsByHead(Ink ink,Dyad pair,Dyad next,float gap,StaffPitchTrack track) {
        for (var head:List.of(pair.top,pair.bottom)) {
            int from=Math.round(head.x+gap*.7f);
            int to=Math.round(Math.min(head.x+gap*2.15f,next.top.x-gap*.88f));
            int minY=Math.round(head.y-gap*.50f),maxY=Math.round(head.y+gap*.50f);
            if (to-from<gap*.6f) return true;
            int accidental=0;
            for (int x=from+Math.round(gap*.47f);x<=to;x++)
                for (int y=minY;y<=maxY;y++) {
                    int span=Math.round(gap*.52f);
                    if (DoubleSharpGlyph.matchesRaw(ink.raw,ink.width,ink.height,
                            x-span,y-span,x+span,y+span,gap)) {
                        accidental=x;
                        break;
                    }
                }
            for (int x=from;x<=to;x++) {
                if (accidental!=0 && Math.abs(x-accidental)<gap*.55f) continue;
                var frame=track.at(x);
                for (int y=minY;y<=maxY;y++) {
                    float rule=(frame[0]-y)/frame[1];
                    if (rule>=-.2f && rule<=4.2f && Math.abs(rule-Math.round(rule))<.23f) continue;
                    if (ink.dark(x,y) && ink.dark(x,y+1) && ink.dark(x+1,y) && ink.dark(x+1,y+1)
                            && !ink.dark(x-3,y) && !ink.dark(x+4,y)) return true;
                }
            }
        }
        return false;
    }

    private static Dyad prove(Ink ink,MeasureRegion bar,List<Head> heads) {
        float left=bar.left()*ink.width,right=bar.right()*ink.width;
        float gap=(bar.bottom()-bar.top())*ink.height/8.2f;
        if (gap<10 || gap>26 || right-left<32*gap || right-left>47*gap) return null;
        heads.sort(Comparator.comparingDouble(Head::x));
        var samples=new ArrayList<float[]>();
        for (int i=0;i<=12;i++) {
            float x=left+(right-left)*i/12;
            var nearest=heads.stream().min(Comparator.comparingDouble(s->Math.abs(x-s.x))).orElseThrow();
            float hint=nearest.y+nearest.note.staffStep()*gap*.5f;
            var sample=localRules(ink,x,hint,gap);
            if (sample!=null) samples.add(new float[]{x,sample[0],sample[1]});
        }
        if (samples.size()<7) return null;
        var track=StaffPitchTrack.fromVerifiedSamples(samples);
        gap=track.at((left+right)*.5f)[1];
        if (!barline(ink,left,track.at(left)) || !barline(ink,right,track.at(right))
                || heads.get(0).x-left<gap*.95f || heads.get(0).x-left>gap*2.8f
                || right-heads.get(31).x<gap*.75f || right-heads.get(31).x>gap*2.4f) return null;
        List<Dyad> pairs=new ArrayList<>();
        int dots=-1,unbeamed=-1;
        int clef=heads.get(0).note.clefBottomDiatonic();
        for (int slot=0;slot<16;slot++) {
            var first=heads.get(slot*2);var second=heads.get(slot*2+1);
            var pair=first.y<second.y?new Dyad(first,second):new Dyad(second,first);
            var a=pair.top.note;var b=pair.bottom.note;
            if (!ordinary(a,clef) || !ordinary(b,clef) || a.beamCount()!=b.beamCount()
                    || a.augmentationDots()!=b.augmentationDots()
                    || second.x-first.x>gap*.18f || pair.bottom.y-pair.top.y<gap*2.35f
                    || pair.bottom.y-pair.top.y>gap*2.8f) return null;
            if (a.beamCount()==0) {
                if (unbeamed>=0 || slot%4==0 || slot%4==3 || a.unbeamedDurationBeats()!=1
                        || b.unbeamedDurationBeats()!=1 || a.augmentationDots()!=0) return null;
                unbeamed=slot;
            } else if (a.beamCount()!=2 || a.unbeamedDurationBeats()!=0
                    || b.unbeamedDurationBeats()!=0) return null;
            if (a.augmentationDots()!=0) {
                if (dots>=0 || a.augmentationDots()!=1 || slot==0 || slot==15) return null;
                dots=slot;
            }
            if (slot>0) {
                float dx=pair.top.x-pairs.get(slot-1).top.x;
                if (dx<gap*1.75f || dx>gap*2.95f) return null;
            }
            pairs.add(pair);
        }
        if (dots<0) return null;
        List<List<Shaft>> axes=new ArrayList<>();
        for (Dyad pair:pairs) {
            var shafts=shafts(ink,pair,gap);
            if (shafts.isEmpty()) return null;
            axes.add(shafts);
        }
        var bindings=new ArrayList<List<Rails>>();
        for (int slot=0;slot<15;slot++) {
            var rails=beams(ink,axes.get(slot),axes.get(slot+1),pairs.get(slot),pairs.get(slot+1),gap);
            bindings.add(rails);
            if (slot%4==3) {
                if (!rails.isEmpty() || !clearBreak(ink,axes.get(slot),axes.get(slot+1),pairs.get(slot),pairs.get(slot+1),gap,track))
                    return null;
            } else if (rails.isEmpty()) return null;
        }
        if (compensatingInk(ink,bindings,pairs,dots,gap,track)) return null;
        return pairs.get(dots);
    }

    private static boolean ordinary(ScoreNoteEvent n,int clef) {
        return ordinaryGeometry(n,clef) && n.stemDirection()==-1 && n.tupletDivisor()==1;
    }

    private static boolean ordinaryGeometry(ScoreNoteEvent n,int clef) {
        return n.kind()==ScoreNoteEvent.Kind.PITCHED && n.staffCount()==1 && n.staffIndex()==0
                && n.visualSize()==ScoreNoteEvent.VisualSize.FULL
                && !n.crossStaffBeam() && !n.tiedFromPrevious()
                && n.boundaryTies()==0 && n.leadingRestBeats()==0 && n.followingRestBeats()==0
                && n.openStringTouch()==null && n.harmonicSemitones()==0
                && (n.articulations()&NoteOrnament.GRACE)==0
                && clef!=ScoreNoteEvent.CLEF_UNKNOWN && n.clefBottomDiatonic()==clef;
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

    private static boolean barline(Ink ink,float x,float[] rules) {
        float g=rules[1],top=rules[0]-4*g;
        if (top<1 || rules[0]>=ink.height-1 || g<10 || g>26) return false;
        var staff=new PlayingTechniqueDetector.Staff(top,rules[0],g,0,1);
        return PrintedCadenceDurations.boundaryColumn(ink.raw,ink.width,staff,x,true,false)>=0;
    }

    private static boolean clearBreak(Ink ink,List<Shaft> a,List<Shaft> b,Dyad left,Dyad right,
            float g,StaffPitchTrack track) {
        int x=Math.round((left.top.x+right.top.x)*.5f-g*.60f);
        int last=Math.max(a.stream().mapToInt(Shaft::tip).max().orElseThrow(),
                b.stream().mapToInt(Shaft::tip).max().orElseThrow())+Math.round(g*.3f);
        int first=Math.round(Math.min(left.bottom.y,right.bottom.y)+g);
        int light=0,possible=0;
        for(int xx=x-2;xx<=x+2;xx++) {
            var rules=track.at(xx);
            for (int y=first;y<=last;y++) {
                float level=(rules[0]-y)/rules[1];
                if (level>=-.15f && level<=4.15f && Math.abs(level-Math.round(level))<.2f) continue;
                possible++;
                if (!ink.dark(xx,y)) light++;
            }
        }
        return possible>20 && light>possible*.91f;
    }

    private static List<Shaft> shafts(Ink ink,Dyad p,float g) {
        return shafts(ink,p,g,-1);
    }

    private static List<Shaft> shafts(Ink ink,Dyad p,float g,int direction) {
        var out=new ArrayList<Shaft>();
        int outward=-direction;
        Head inner=outward>0?p.top:p.bottom,outer=outward>0?p.bottom:p.top;
        int begin=Math.round(inner.y+outward*g*.24f),end=Math.round(outer.y+outward*g*4.95f);
        int left=Math.round(inner.x+g*(direction>0?.39f:-.83f));
        int right=Math.round(inner.x+g*(direction>0?.83f:-.39f));
        for (int x=left;x<=right;x++) {
            int last=begin-outward,hits=0;
            for (int y=begin;(end-y)*outward>0;y+=outward) {
                if (ink.dark(x,y)) {
                    last=y;
                    hits++;
                } else if ((y-last)*outward>g*.38f) break;
            }
            if ((last-outer.y)*outward>=g*1.7f && hits>=((last-begin)*outward+1)*.83f)
                out.add(new Shaft(x,last));
        }
        return out;
    }

    private static List<Rails> beams(Ink ink,List<Shaft> left,List<Shaft> right,Dyad a,Dyad b,
            float gap) {
        return beams(ink,left,right,a,b,gap,-1);
    }

    private static List<Rails> beams(Ink ink,List<Shaft> left,List<Shaft> right,Dyad a,Dyad b,
            float gap,int direction) {
        int margin=Math.round(gap*.3f),radius=Math.max(2,Math.round(gap*.12f));
        int minOffset=Math.round(gap*(direction>0 ? -1.6f : -.81f)),maxOffset=Math.round(gap*.55f);
        int minSeparation=Math.round(gap*.66f),maxSeparation=Math.round(gap*1.15f);
        List<Rails> accepted=new ArrayList<>();
        for (var s:left) for (var t:right) {
            int span=t.x-s.x;
            if (span<gap*1.5f || span>gap*3.2f) continue;
            int first=s.x+margin,last=t.x-margin;
            for (int dy=minOffset;dy<=maxOffset;dy++) for (int dz=minOffset;dz<=maxOffset;dz++) {
                int y=s.tip-direction*dy,z=t.tip-direction*dz;
                float ay=direction>0?a.top.y:a.bottom.y,by=direction>0?b.top.y:b.bottom.y;
                if (Math.abs(y-z)>span*.35f || (ay-y)*direction<gap*1.9f
                        || (by-z)*direction<gap*1.9f)
                    continue;
                var axis=new Rails(s.x,y,t.x,z,0);
                if (band(ink,axis,first,last,0,radius)<.97f) continue;
                for (int d=minSeparation;d<=maxSeparation;d++) {
                    if (band(ink,axis,first,last,-direction*d,radius)<.97f
                            || band(ink,axis,first,last,-direction*(d/2),1)>=.65f
                            || band(ink,axis,first,last,-direction*2*d,radius)>=.60f) continue;
                    accepted.add(new Rails(s.x,y,t.x,z,d));
                }
            }
        }
        return accepted;
    }

    private static float band(Ink ink,Rails axis,int first,int last,int shift,int radius) {
        int full=0;
        for (int x=first;x<=last;x++) {
            int y=axis.at(x)-shift;
            if (stroke(ink,x,y,radius)>=2*radius) full++;
        }
        return full/(float)(last-first+1);
    }

    private static int stroke(Ink ink,int x,int y,int radius) {
        int hits=0;
        for (int dy=-radius;dy<=radius;dy++) if (ink.dark(x,y+dy)) hits++;
        return hits;
    }

    private static boolean compensatingInk(Ink ink,List<List<Rails>> edges,List<Dyad> pairs,
            int dot,float gap,StaffPitchTrack track) {
        for(int i=0;i<pairs.size();i++) {
            if (dot==i) continue;
            for (int outward:new int[]{-1,1}) {
                int edge=outward<0?i-1:i;
                if (edge<0 || edge>=edges.size() || edges.get(edge).isEmpty()) continue;
                var fits=edges.get(edge);
                int positives=0;
                for(var f:fits) if(peerHook(ink,f,outward,gap,track)) positives++;
                if (positives>fits.size()*.20f) return true;
            }
        }
        return false;
    }

    private static boolean peerHook(Ink ink,Rails axis,int direction,float gap,StaffPitchTrack track) {
        int x=direction<0?axis.x1:axis.x0;
        int reach=Math.round(gap*.70f),radius=Math.round(gap*.13f);
        for(int depth=Math.round(gap*.65f);depth<=Math.round(gap*1.05f);depth++) {
            int candidates=0,solid=0;
            for(int k=2;k<=reach;k++) {
                int col=x+direction*k;
                int y=axis.at(col)-axis.separation-depth;
                var rail=track.at(col);
                float level=(rail[0]-y)/rail[1];
                if (level>=-.22f && level<=4.22f && Math.abs(level-Math.round(level))<.27f) continue;
                candidates++;
                if(stroke(ink,col,y,radius)>=2*radius) solid++;
            }
            if (candidates>=reach-3 && solid>=candidates-1) return true;
        }
        return false;
    }

    private static boolean closes(List<Head> heads,List<ScoreNoteEvent> result) {
        var local=heads.stream().map(h->result.get(h.owner)).toList();
        for (int i=0;i<local.size();i++) {
            var placement=ScoreNoteTiming.writtenPlacement(local.get(i),local,4f).orElse(null);
            if (placement==null || Math.abs(placement.onsetBeats()-(i/2)*.25)>1e-6
                    || Math.abs(placement.durationBeats()-.25)>1e-6) return false;
        }
        return true;
    }

    private static ScoreNoteEvent withoutDot(ScoreNoteEvent n) {
        return new ScoreNoteEvent(n.measureIndex(),n.positionInMeasure(),n.staffStep(),n.staffIndex(),
                n.staffCount(),n.pageY(),n.tiedFromPrevious(),0,n.beamCount(),n.writtenAccidental(),
                n.unbeamedDurationBeats(),n.tupletDivisor(),n.followingRestBeats(),n.articulations(),
                n.clefBottomDiatonic(),n.crossStaffBeam(),n.leadingRestBeats(),n.compactOpening(),
                n.octaveShift(),n.boundaryTies(),n.tupletNormalNotes(),n.stemDirection(),n.kind(),
                n.visualSize(),n.harmonicSemitones(),n.openStringTouch());
    }
}
