// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

import io.github.luckolite.interpreter.ClosedSixteenthDyads.Head;

/** Assign a printed cross within an otherwise complete bar of sixteenth-note quartets. */
final class ClosedDoubleSharpBeams {
    private ClosedDoubleSharpBeams() {}

    record Capture(ScorePageInterpretation score, List<ScoreNoteEvent> doubleSharps) {}

    static Capture restore(ScorePageInterpretation score, ScoreMeterChange initial,
            byte[] gray, byte[] raw, int width, int height, int page) {
        if (gray == null || raw == null || gray.length != (long)width*height
                || raw.length != gray.length) return new Capture(score,List.of());
        var regions=score.measures();
        var notes=score.notes();
        var rows=new ArrayList<List<Head>>(regions.size());
        for (int i=0;i<regions.size();i++) rows.add(new ArrayList<>());
        for (int i=0;i<notes.size();i++) {
            var n=notes.get(i);
            if (n.measureIndex()<0 || n.measureIndex()>=regions.size()) continue;
            var box=regions.get(n.measureIndex());
            float x=(box.left()+n.positionInMeasure()*(box.right()-box.left()))*width;
            rows.get(n.measureIndex()).add(new Head(i,n,x,n.pageY()*height));
        }
        var hasRest=new java.util.BitSet(regions.size());
        for (var r:score.rests()) hasRest.set(r.measureIndex());
        var edited=new ArrayList<>(notes);
        var proved=new ArrayList<ScoreNoteEvent>();
        var removed=new java.util.BitSet(notes.size());
        var beams=score.beamSource();
        int change=0;
        var meter=initial;
        for (int i=1;i+1<regions.size();i++) {
            while (change<score.meterChanges().size() && score.meterChanges().get(change).measureIndex()<=i)
                meter=score.meterChanges().get(change++);
            if (meter==null || meter.denominator()!=4 || hasRest.get(i)
                    || regions.get(i).cadenceQuarterBeats()!=0) continue;
            var row=rows.get(i);
            if (meter.numerator()==3 && row.size()==12) {
                row.sort(Comparator.comparingDouble(Head::x));
                if (!terminalCross(gray, raw, width, height, regions, i, row)) {
                    var cross = localQuartetCross(gray, raw, width, height, regions.get(i), row);
                    if (cross == null) continue;
                    var head = row.get(cross.slot());
                    if (head.note().writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_DOUBLE_SHARP) {
                        var corrected = withExplicitCross(head.note());
                        edited.set(head.owner(), corrected);
                        proved.add(corrected);
                    }
                    if (cross.borrowedDot()) {
                        var prior = row.get(cross.slot() - 1);
                        edited.set(prior.owner(), ClosedMonophonicSixteenths.withoutAugmentationDot(prior.note()));
                    }
                    if (cross.borrowedTriplet()) for (int j = cross.slot(); j < cross.slot() + 3; j++) {
                        var member = row.get(j);
                        edited.set(member.owner(), edited.get(member.owner()).withTupletRatio(1, 1));
                    }
                    if (page >= 0) beams = ClosedSixteenthDyads.replaceMonophonicDoubleRails(beams, page, row);
                    continue;
                }

                var prior=row.get(10);
                var last=row.get(11);
                edited.set(prior.owner(),ClosedMonophonicSixteenths.withoutAugmentationDot(prior.note()));
                var corrected=withExplicitCross(last.note());
                edited.set(last.owner(),corrected);
                proved.add(corrected);
                if (page>=0) beams=ClosedSixteenthDyads.replaceMonophonicDoubleRails(beams,page,row);
            } else if (meter.numerator()==4 && row.size()==33) {
                row.sort(Comparator.comparingDouble(Head::x));
                var removal=ownedCrossAlias(gray,raw,width,height,regions,i,row);
                if (removal==null) continue;
                removed.set(removal.owner());
                if (page>=0) beams=replaceDyadBeams(beams,page,
                        row.stream().filter(h -> h!=removal).toList());
            }
        }
        if (removed.isEmpty() && edited.equals(notes)) return new Capture(score,List.of());
        var retained=IntStream.range(0,notes.size()).filter(i->!removed.get(i)).boxed().toList();
        var updated=score.withBeamSource(beams).withNotes(retained.stream().map(edited::get).toList(),retained);
        return new Capture(updated,List.copyOf(proved));
    }

    private record CrossAlias(int slot, boolean borrowedDot, boolean borrowedTriplet) {}

    private static CrossAlias localQuartetCross(byte[] gray, byte[] raw, int width, int height,
            MeasureRegion bar, List<Head> row) {
        var notes = row.stream().map(Head::note).toList();
        if (!notes.stream().allMatch(ClosedMonophonicSixteenths::regularQuartetHead)) return null;
        var first = notes.get(0);
        float gap = gap(bar, height);
        for (int slot : new int[] {1, 11}) {
            var head = row.get(slot);
            var before = row.get(slot - 1);
            var next = head.note();
            boolean terminal = slot == 11 && next.tiedFromPrevious() && before.note().staffStep() == next.staffStep()
                    && before.note().writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_SHARP
                    && next.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_SHARP;
            boolean chromatic = slot == 1 && !next.tiedFromPrevious()
                    && first.staffStep() == next.staffStep() + 1
                    && notes.get(2).staffStep() == next.staffStep()
                    && notes.get(3).staffStep() == next.staffStep()
                    && notes.get(2).writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_SHARP
                    && notes.get(3).writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL;
            boolean dot = before.note().augmentationDots() == 1;
            boolean triplet = chromatic && next.tupletDivisor() == 3
                    && next.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_DOUBLE_SHARP;
            if (!(terminal && dot || chromatic && (triplet && dot
                    || !dot && next.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY))) continue;
            boolean compatible = true;
            for (int i = 0; i < notes.size(); i++) {
                var note = notes.get(i);
                boolean expectedRatio = triplet && i >= slot && i < slot + 3;
                if (note.clefBottomDiatonic() != next.clefBottomDiatonic()
                        || note.augmentationDots() != (dot && i == slot - 1 ? 1 : 0)
                        || note.tiedFromPrevious() != (terminal && i == slot)
                        || note.tupletDivisor() != (expectedRatio ? 3 : 1)
                        || note.tupletNormalNotes() != (expectedRatio ? 2 : 1)) {
                    compatible = false;
                    break;
                }
            }
            if (!compatible || uniqueCross(gray, raw, width, height, before.x(), head, gap) == null) continue;
            if (dot && (!ClosedDyadsBeforeHalf.sharpOwnsDotGap(
                            gray, width, height, gap, before.x(), before.y(), head.x(), head.y())
                    || !ClosedDyadsBeforeHalf.sharpOwnsDotGap(
                            raw, width, height, gap, before.x(), before.y(), head.x(), head.y()))) continue;
            if (ClosedMonophonicSixteenths.provesRegularQuartets(gray, width, height, bar, notes)
                    && ClosedMonophonicSixteenths.provesRegularQuartets(raw, width, height, bar, notes))
                return new CrossAlias(slot, dot, triplet);
        }
        return null;
    }

    static boolean terminalCross(byte[] gray, byte[] raw, int w, int h,
            List<MeasureRegion> regions, int index, List<Head> row) {
        var previous=row.get(10);
        var closing=row.get(11);
        if (!row.stream().allMatch(a -> straight(a.note()))
                || previous.note().augmentationDots()!=1 || previous.note().tiedFromPrevious()
                || !closing.note().tiedFromPrevious() || closing.note().augmentationDots()!=0
                || previous.note().staffStep()!=closing.note().staffStep()
                || previous.note().writtenAccidental()!=ScoreNoteEvent.ACCIDENTAL_SHARP
                || closing.note().writtenAccidental()!=ScoreNoteEvent.ACCIDENTAL_SHARP
                || row.subList(0,10).stream().anyMatch(a -> a.note().augmentationDots()!=0
                        || a.note().tiedFromPrevious())) return false;
        var bar=regions.get(index);
        float gap=gap(bar,h);
        if (Math.abs(previous.y()-closing.y())>.20f*gap
                || uniqueCross(gray,raw,w,h,previous.x(),closing,gap)==null) return false;
        return provesQuartets(gray,w,h,regions,index,row,false)
                && provesQuartets(raw,w,h,regions,index,row,false)
                && WrappedTripletDotInk.uniqueCore(gray,w,h,previous.x(),previous.y(),closing.x(),gap)==null
                && WrappedTripletDotInk.uniqueCore(raw,w,h,previous.x(),previous.y(),closing.x(),gap)==null;
    }

    static Head ownedCrossAlias(byte[] gray, byte[] raw, int w, int h,
            List<MeasureRegion> regions, int index, List<Head> row) {
        var possible=row.stream().filter(a->alias(a.note())).toList();
        if (possible.size()!=1) return null;
        var ghost=possible.get(0);
        var real=row.stream().filter(a->a!=ghost).toList();
        if (real.stream().anyMatch(a->!straight(a.note()) || a.note().augmentationDots()!=0
                || a.note().tiedFromPrevious())) return null;
        var bar=regions.get(index);
        float gap=gap(bar,h);
        int next=row.indexOf(ghost)+1;
        if (next<2 || next+1>=row.size() || (next-1)%2!=0) return null;
        var a=row.get(next);
        var b=row.get(next+1);
        var owner=a.y()>b.y()?a:b;
        if (owner.note().writtenAccidental()!=ScoreNoteEvent.ACCIDENTAL_DOUBLE_SHARP
                || Math.abs(ghost.y()-owner.y())>.46f*gap
                || owner.x()-ghost.x()<1.25f*gap || owner.x()-ghost.x()>2.35f*gap) return null;
        var cross=uniqueCross(gray,raw,w,h,row.get(next-2).x(),owner,gap);
        if (cross==null || ghost.x()<cross.left() || ghost.x()>cross.right()
                || ghost.y()<cross.top() || ghost.y()>cross.bottom()) return null;
        for (var plane:List.of(gray,raw)) {
            var budget=new PrintedBeamShafts.Budget(70_000);
            if (!PrintedBeamShafts.at(plane,w,h,ghost.x(),ghost.y(),gap,budget).isEmpty()
                    || budget.refused() || !provesQuartets(plane,w,h,regions,index,real,true))
                return null;
        }
        return ghost;
    }

    private static boolean straight(ScoreNoteEvent n) {
        return ordinary(n) && n.beamCount()==2 && n.stemDirection()==-1
                && n.unbeamedDurationBeats()==0 && n.augmentationDots()<=1;
    }

    private static boolean alias(ScoreNoteEvent n) {
        return ordinary(n) && n.beamCount()==0 && n.stemDirection()==0
                && n.unbeamedDurationBeats()==1 && n.augmentationDots()==0
                && n.writtenAccidental()==ScoreNoteEvent.ACCIDENTAL_FROM_KEY && !n.tiedFromPrevious();
    }

    private static boolean ordinary(ScoreNoteEvent n) {
        return n.kind()==ScoreNoteEvent.Kind.PITCHED && n.staffIndex()==0 && n.staffCount()==1
                && n.visualSize()==ScoreNoteEvent.VisualSize.FULL && n.tupletDivisor()==1
                && n.tupletNormalNotes()==1 && n.boundaryTies()==0 && n.articulations()==0
                && n.openStringTouch()==null && n.harmonicSemitones()==0 && !n.crossStaffBeam()
                && n.leadingRestBeats()==0 && n.followingRestBeats()==0;
    }

    private static float gap(MeasureRegion bar,int height) {
        return (bar.bottom()-bar.top())*height/8.2f;
    }

    private static boolean provesQuartets(byte[] ink,int w,int h,List<MeasureRegion> regions,
            int index,List<Head> row,boolean dyads) {
        var bar=regions.get(index);
        float gap=gap(bar,h);
        if (gap<10 || gap>25) return false;
        var before=regions.get(index-1);
        var after=regions.get(index+1);
        if (Math.abs(bar.top()-before.top())*h>.35f*gap) return false;
        boolean endOfStaff=after.top()>bar.bottom();
        if (dyads && endOfStaff || !endOfStaff && Math.abs(after.top()-bar.top())*h>.35f*gap)
            return false;
        float left=(before.right()+bar.left())*w*.5f;
        float right=endOfStaff ? bar.right()*w : (bar.right()+after.left())*w*.5f;
        if (bar.left()*w-left<.12f*gap || bar.left()*w-left>.8f*gap
                || right-row.get(row.size() - 1).x()<.93f*gap || right-row.get(row.size() - 1).x()>3.3f*gap
                || row.get(0).x()-left<1.2f*gap || row.get(0).x()-left>3.3f*gap)
            return false;
        float floor=bar.bottom()*h-2.1f*gap;
        var a=dividerSample(ink,w,h,left,floor,gap,1);
        var b=dividerSample(ink,w,h,right,floor,gap,-1);
        if (a==null || b==null || Math.abs(a[1]-b[1])>.40f*gap) return false;
        var staff=StaffPitchTrack.fromVerifiedSamples(List.of(a,b));
        var budget=new PrintedBeamShafts.Budget(340_000);
        PrintedBeamShafts.Shaft previous=null;
        float previousX=0;
        int stride=dyads?2:1;
        for (int i=0;i<row.size();i+=stride) {
            var one=row.get(i);
            var two=dyads?row.get(i+1):one;
            var lower=one.y()>two.y()?one:two;
            var upper=lower==one?two:one;
            if (dyads && (Math.abs(upper.x()-lower.x())>.25f*gap
                    || lower.note().staffStep()+2!=upper.note().staffStep()
                    || lower.y()-upper.y()<.76f*gap || lower.y()-upper.y()>1.24f*gap)) return false;
            var axes=PrintedBeamShafts.at(ink,w,h,lower.x(),lower.y(),gap,budget);
            if (axes.size()!=1 || axes.get(0).direction()!=1 || budget.refused()) return false;
            var shaft=axes.get(0);
            if (dyads && !sharedStem(ink,w,upper,lower,shaft,gap)) return false;
            if (previous!=null && (lower.x()-previousX<1.58f*gap || lower.x()-previousX>3.62f*gap
                    || (i/stride%4==0
                            ? !WrappedEighthTriplets.matchesBeamRails(ink,w,h,previous,shaft,gap,0)
                            : !WrappedEighthTriplets.matchesExactlyTwoBeamRails(
                                    ink,w,h,previous,shaft,gap,staff)))) return false;
            previous=shaft;
            previousX=lower.x();
        }
        return true;
    }

    static float[] dividerSample(byte[] ink,int w,int h,float edge,float floor,float gap,int inward) {
        for (float margin:new float[]{0,.6f,1.2f,1.8f}) {
            var p=PrintedDirectionStaff.printedRules(ink,w,h,edge+inward*margin*gap,floor,gap);
            if (p!=null && Math.abs(p[0]-floor)<=.48f*gap && Math.abs(p[1]-gap)<=.10f*gap
                    && fullHeightDivider(ink,w,edge,gap,p[0],p[1])) return new float[]{edge,p[0],p[1]};
        }
        // Staff-end sample strips can have paper on the far side of the divider.
        // Read the rules in the adjacent music, then verify the vertical separately.
        int near=Math.round(edge+inward*.44f*gap), far=Math.round(edge+inward*1.80f*gap);
        int left=Math.min(near,far), right=Math.max(near,far);
        int flank=Math.round(gap*.26f), expected=Math.round(floor);
        if (left<0 || right>=w || expected-5*gap<0 || expected+gap>=h) return null;
        float[] best=null;
        double confidence=0;
        for (int y=expected-Math.round(.40f*gap);y<=expected+Math.round(.40f*gap);y++)
            for (int offset=-6;offset<=5;offset++) {
                float spacing=gap+offset*.22f;
                double score=0;
                for (int rule=0;rule<5;rule++) {
                    int yy=Math.round(y-rule*spacing);
                    int dark=rowInk(ink,w,left,right,yy);
                    int light=Math.min(rowInk(ink,w,left,right,yy-flank),
                            rowInk(ink,w,left,right,yy+flank));
                    if (dark<.78f*(right-left+1) || dark-light<.46f*(right-left+1)) {
                        score=0;
                        break;
                    }
                    score+=dark-light*.5;
                }
                score-=Math.abs(offset)*.08+Math.abs(y-floor)*.03;
                if (score>confidence && fullHeightDivider(ink,w,edge,gap,y,spacing)) {
                    confidence=score;
                    best=new float[]{edge,y,spacing};
                }
            }
        return best;
    }

    private static boolean fullHeightDivider(byte[] ink,int width,float edge,float gap,float floor,float spacing) {
        var frame=new PlayingTechniqueDetector.Staff(floor-4*spacing,floor,spacing,0,1);
        int column=PrintedCadenceDurations.boundaryColumn(ink,width,frame,edge,true,false);
        return column>=0 && Math.abs(column-edge)<=.34f*gap;
    }

    private static int rowInk(byte[] ink,int width,int left,int right,int y) {
        int result=0;
        for (int x=left;x<=right;x++) if ((ink[y*width+x]&255)<155) result++;
        return result;
    }

    private static boolean sharedStem(byte[] ink,int w,Head upper,Head lower,
            PrintedBeamShafts.Shaft shaft,float gap) {
        int x=(shaft.left()+shaft.right())/2;
        int begin=Math.round(upper.y()+.10f*gap), end=Math.round(lower.y()-.12f*gap);
        if (x<=0 || x>=w-1 || begin<0 || end>=ink.length/w || end-begin<gap*.45f) return false;
        int dark=0;
        for (int y=begin;y<=end;y++) {
            int p=y*w+x;
            if ((ink[p]&255)<155 || (ink[p-1]&255)<155 || (ink[p+1]&255)<155) dark++;
        }
        return dark>=(end-begin+1)*.90f;
    }

    static DoubleSharpGlyph.Crop uniqueCross(byte[] gray,byte[] raw,int w,int h,
            float priorX,Head head,float gap) {
        if (gap<10 || gap>25) return null;
        int left=Math.round(Math.max(priorX+.62f*gap,head.x()-2.6f*gap));
        int right=Math.round(head.x()-.65f*gap);
        int min=(int)Math.ceil(.56f*gap), max=(int)Math.floor(1.27f*gap);
        int top=Math.round(head.y()-.70f*gap), bottom=Math.round(head.y()+.80f*gap);
        if (left<0 || right>=w || top<0 || bottom>=h || right-left>2*gap) return null;
        DoubleSharpGlyph.Crop best=null;
        int largest=0, commonLeft=left, commonRight=right, commonTop=top, commonBottom=bottom;
        for (int l=left;l<=right-min+1;l++)
            for (int r=l+min-1;r<=right && r-l<max;r++)
                for (int t=top;t<=Math.round(head.y()-.30f*gap);t++)
                    for (int b=Math.max(t+min-1,Math.round(head.y()+.28f*gap));b<=bottom && b-t<max;b++) {
                        int area=(r-l+1)*(b-t+1);
                        if (!DoubleSharpGlyph.matchesRaw(gray,w,h,l,t,r,b,gap)
                                || !DoubleSharpGlyph.matchesRaw(raw,w,h,l,t,r,b,gap)) continue;
                        if (area>largest) {
                            best=new DoubleSharpGlyph.Crop(l,t,r,b);
                            largest=area;
                        }
                        commonLeft=Math.max(commonLeft,l);
                        commonRight=Math.min(commonRight,r);
                        commonTop=Math.max(commonTop,t);
                        commonBottom=Math.min(commonBottom,b);
                    }
        return commonRight-commonLeft>=.16f*gap && commonBottom-commonTop>=.16f*gap ? best : null;
    }

    private static ScoreNoteEvent withExplicitCross(ScoreNoteEvent n) {
        return new ScoreNoteEvent(n.measureIndex(),n.positionInMeasure(),n.staffStep(),n.staffIndex(),
                n.staffCount(),n.pageY(),false,n.augmentationDots(),n.beamCount(),
                ScoreNoteEvent.ACCIDENTAL_DOUBLE_SHARP,n.unbeamedDurationBeats(),n.tupletDivisor(),
                n.followingRestBeats(),n.articulations(),n.clefBottomDiatonic(),n.crossStaffBeam(),
                n.leadingRestBeats(),n.compactOpening(),n.octaveShift(),n.boundaryTies(),n.tupletNormalNotes(),
                n.stemDirection(),n.kind(),n.visualSize(),n.harmonicSemitones(),n.openStringTouch());
    }

    private static ScoreBeamSource replaceDyadBeams(ScoreBeamSource source,int page,List<Head> row) {
        var owned=new HashSet<ScoreBeamSource.Anchor>();
        for(var head:row) source.noteOrigins().get(head.owner()).ifPresent(owned::add);
        var groups=new ArrayList<ScoreBeamSource.Group>();
        for(var group:source.groups()) {
            var origins=group.columns().stream().flatMap(c->c.members().stream()).toList();
            if(origins.stream().noneMatch(owned::contains)) groups.add(group);
            else if(!owned.containsAll(origins) || group.tupletMark()!=null) return source;
        }
        var detached=new ArrayList<ScoreBeamSource.Column>();
        for(var column:source.detachedColumns()) {
            if(column.members().stream().noneMatch(owned::contains)) detached.add(column);
            else if(!owned.containsAll(column.members())) return source;
        }
        var remainder=new ScoreBeamSource(groups,detached,source.noteOrigins());
        var rebuilt=ClosedSixteenthDyads.projectDoubleRails(remainder,page,row);
        return rebuilt==remainder ? source : rebuilt;
    }
}
