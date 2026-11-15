// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** A terminal hollow dyad can be assigned to a spurious second staff below its one short ledger. */
final class ClosedLedgerHalfDyad {
    private ClosedLedgerHalfDyad() {}

    static ScorePageInterpretation capture(ScorePageInterpretation score, byte[] gray, int width, int height) {
        if (gray == null || width < 3 || height < 3 || gray.length != (long) width * height)
            return score;
        var notes = new ArrayList<List<Integer>>(score.measures().size());
        for (int i = 0; i < score.measures().size(); i++) notes.add(new ArrayList<>());
        for (int i = 0; i < score.notes().size(); i++)
            notes.get(score.notes().get(i).measureIndex()).add(i);
        var rests = new boolean[notes.size()];
        for (var rest : score.rests()) rests[rest.measureIndex()] = true;
        var changedNotes = new ArrayList<>(score.notes());
        var measures = new ArrayList<>(score.measures());
        boolean changed = false;
        int nextMeter = 0;
        ScoreMeterChange meter = null;
        for (int bar = 0; bar < notes.size(); bar++) {
            while (nextMeter < score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex() <= bar)
                meter = score.meterChanges().get(nextMeter++);
            if (meter == null || meter.numerator() != 4 || meter.denominator() != 4
                    || rests[bar] || notes.get(bar).size() != 2) continue;
            var a = notes.get(bar).get(0);
            var b = notes.get(bar).get(1);
            var upper = score.notes().get(a);
            var lower = score.notes().get(b);
            if (upper.pageY() > lower.pageY()) {
                var other = upper;
                upper = lower;
                lower = other;
                int index = a;
                a = b;
                b = index;
            }
            var here = measures.get(bar);
            var next = bar + 1 < measures.size() ? measures.get(bar + 1) : null;
            if (!proves(gray, width, height, here, next, upper, lower)) continue;
            changedNotes.set(a, singleStaff(upper, upper.staffStep(), upper.clefBottomDiatonic()));
            // One ledger below the bottom rule, then the hollow head in the next space.
            changedNotes.set(b, singleStaff(lower, -3, upper.clefBottomDiatonic()));
            measures.set(bar, new MeasureRegion(here.left(), here.right(), here.top(), here.bottom(), 2));
            changed = true;
        }
        if (!changed) return score;
        return new ScorePageInterpretation(measures, changedNotes, score.firstMeasureNumber(),
                score.keyChanges(), score.tempoChanges(), score.meterChanges(), score.rests(),
                score.techniqueChanges(), score.dynamicChanges(), score.playbackDirections(),
                score.expressiveEvents(), score.slurSource(), score.openingPickup(),
                score.tremoloSource(), score.multiRestGroups(), score.beamSource(), score.clockContextSource());
    }

    private static ScoreNoteEvent singleStaff(ScoreNoteEvent n, int step, int clef) {
        return new ScoreNoteEvent(n.measureIndex(), n.positionInMeasure(), step, 0, 1, n.pageY(),
                n.tiedFromPrevious(), n.augmentationDots(), n.beamCount(), n.writtenAccidental(),
                n.unbeamedDurationBeats(), n.tupletDivisor(), n.followingRestBeats(), n.articulations(),
                clef, n.crossStaffBeam(), n.leadingRestBeats(), n.compactOpening(), n.octaveShift(),
                n.boundaryTies(), n.tupletNormalNotes(), 1, n.kind(), n.visualSize(),
                n.harmonicSemitones(), n.openStringTouch());
    }

    private static boolean plainHalf(ScoreNoteEvent note, int staff) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && note.staffIndex() == staff && note.staffCount() == 2 && !note.crossStaffBeam()
                && note.unbeamedDurationBeats() == 2 && note.beamCount() == 0
                && note.augmentationDots() == 0 && note.tupletDivisor() == 1 && !note.tiedFromPrevious()
                && note.boundaryTies() == 0 && note.leadingRestBeats() == 0 && note.followingRestBeats() == 0
                && note.articulations() == 0 && note.octaveShift() == 0 && note.harmonicSemitones() == 0
                && note.openStringTouch() == null
                && note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && !note.compactOpening();
    }

    private static boolean proves(byte[] gray, int width, int height, MeasureRegion bar, MeasureRegion next,
            ScoreNoteEvent upper, ScoreNoteEvent lower) {
        if (bar.cadenceQuarterBeats() != 0 || !plainHalf(upper, 0) || !plainHalf(lower, 1)
                || upper.stemDirection() != 1 || lower.stemDirection() != 0
                || lower.staffStep() != 0 || lower.clefBottomDiatonic() >= 0) return false;
        float barGap = (bar.bottom() - bar.top()) * height / 8.2f;
        float left = bar.left() * width, right = bar.right() * width, span = right - left;
        float upperX = left + upper.positionInMeasure()*span, lowerX = left + lower.positionInMeasure()*span;
        float upperY = upper.pageY()*height, lowerY = lower.pageY()*height;
        if (barGap < 12 || barGap > 23 || left - 2*barGap < 0 || right + 2*barGap >= width
                || Math.min(upperY, lowerY) - 4*barGap < 0 || lowerY + 2*barGap >= height
                || span < 7.7f*barGap || span > 10*barGap
                || next != null && next.top()*height < bar.bottom()*height + 2*barGap
                || upper.positionInMeasure() < .28f || upper.positionInMeasure() > .48f
                || Math.abs(upperX-lowerX) > .23f*barGap
                || lowerY - upperY < barGap*4.1f || lowerY - upperY > barGap*5.6f) return false;
        var rails = PrintedDirectionStaff.printedRules(gray, width, height,
                left + 1.5f*barGap, bar.bottom()*height - 2.1f*barGap, barGap);
        if (rails == null) return false;
        float bottom=rails[0], gap=rails[1], top=bottom-4*gap;
        if (Math.abs(gap-barGap) > .12f*barGap || top < gap || bottom + gap >= height
                || Math.abs((bottom-upperY)*2/gap-upper.staffStep()) > .31f
                || lowerY-bottom < 1.48f*gap || lowerY-bottom > 2.07f*gap) return false;
        var staff = new PlayingTechniqueDetector.Staff(top,bottom,gap,0,1);
        if (!fiveRails(gray,width,top,gap,Math.round(left+gap*.3f),Math.round(upperX-gap*1.15f))
                || !fiveRails(gray,width,top,gap,Math.round(lowerX+gap*1.35f),Math.round(right-gap*1.45f)))
            return false;

        int open=PrintedCadenceDurations.boundaryColumn(gray,width,staff,left-gap*.53f,true,false);
        int close=pairedClosingRule(gray,width,height,staff,right);
        if (open < 0 || close < 0 || attached(gray,width,height,open,staff)
                || !PrintedCadenceDurations.emptyOutsideChord(gray,width,staff,left,close,
                        Math.min(upperX,lowerX),Math.max(upperX,lowerX),false))
            return false;
        int shaft = sharedShaft(gray,width,upperX,upperY,lowerX,lowerY,gap);
        return shaft >= 0
                && hollow(gray,width,upperX,upperY,gap)
                && hollow(gray,width,lowerX,lowerY,gap)
                && oneLedger(gray,width,left,close,bottom,lowerX,lowerY,shaft,gap);
    }

    private static boolean fiveRails(byte[] ink, int width, float top, float gap, int left, int right) {
        if (right-left < gap*1.3f) return false;
        int radius=Math.round(gap*.28f);
        for (int rule=0; rule<5; rule++) {
            int center=Math.round(top+rule*gap), covered=0;
            for (int x=left; x<=right; x++) {
                for (int y=center-radius; y<=center+radius; y++)
                    if (dark(ink,width,x,y)) {
                        covered++;
                        break;
                    }
            }
            if (covered < .84f*(right-left+1)) return false;
        }
        return true;
    }

    private static int pairedClosingRule(byte[] gray,int w,int h,PlayingTechniqueDetector.Staff staff,float right) {
        float gap=staff.gap();
        int first=Math.round(right-gap*.62f), last=Math.round(right+gap*.65f);
        for (int a=first; a<=last; a++) {
            float[] inner=spaces(gray,w,a,staff);
            if (inner[0]<.85f || inner[1]<.85f || inner[2]<.46f || inner[3]<.46f
                    || !reachesRule(gray,w,a,staff.top(),gap)
                    || !reachesRule(gray,w,a,staff.bottom(),gap)) continue;
            for (int b=a+Math.round(gap*.31f); b<=last && b-a<=gap*.78f; b++) {
                float[] outer=spaces(gray,w,b,staff);
                if (outer[0]<.90f || outer[1]<.90f || outer[2]<.90f || outer[3]<.86f
                        || Math.abs((a+b)*.5f-right)>gap*.47f
                        || !reachesRule(gray,w,b,staff.top(),gap)
                        || !reachesRule(gray,w,b,staff.bottom(),gap)) continue;
                boolean paper=false;
                for (int x=a+1; x<b; x++) {
                    float[] valley=spaces(gray,w,x,staff);
                    if (valley[0]+valley[1]+valley[2]+valley[3]<1.05f) paper=true;
                }
                if (paper && !attached(gray,w,h,a,staff) && !attached(gray,w,h,b,staff)) return a;
            }
        }
        return -1;
    }

    private static boolean reachesRule(byte[] gray,int w,int x,float row,float gap) {
        int radius=Math.round(gap*.22f), center=Math.round(row);
        for (int y=center-radius; y<center+radius; y++)
            if (dark(gray,w,x,y) && dark(gray,w,x,y+1)) return true;
        return false;
    }

    private static float[] spaces(byte[] gray,int w,int x,PlayingTechniqueDetector.Staff staff) {
        float[] result = new float[4];
        for (int space=0; space<4; space++) {
            int l=Math.round(staff.top()+(space+.2f)*staff.gap()),
                    r=Math.round(staff.top()+(space+.8f)*staff.gap()), dark=0;
            for (int y=l; y<=r; y++)
                if (dark(gray,w,x,y)) dark++;
            result[space]=dark/(float)(r-l+1);
        }
        return result;
    }

    private static boolean attached(byte[] gray, int w, int h, int x, PlayingTechniqueDetector.Staff staff) {
        int top=Math.round(staff.top()), bottom=Math.round(staff.bottom());
        return ClosedHeadBarlineGuard.attached(gray,w,h,x,top,bottom,staff.gap())
                || DottedDyadMeasureSeparator.continuesBeyondStaff(gray,w,h,x,top,bottom,staff.gap());
    }

    private static int sharedShaft(byte[] gray,int w,float ax,float ay,float bx,float by,float gap) {
        int top=Math.round(ay-gap*1.65f), bottom=Math.round(by-gap*.37f);
        int first=Math.round(Math.max(ax,bx)+gap*.42f), last=Math.round(Math.min(ax,bx)+gap*.86f);
        int lastPassing=-2, shaft=-1, count=0;
        for (int x=first; x<=last; x++) {
            int density=0, run=0, maxGap=0;
            for (int y=top; y<=bottom; y++) {
                if (dark(gray,w,x,y)) {
                    density++;
                    run=0;
                } else {
                    maxGap=Math.max(maxGap,++run);
                }
            }
            if (density < .90f*(bottom-top+1) || maxGap>gap*.26f
                    || !joinsHead(gray,w,x,ax,ay,gap) || !joinsHead(gray,w,x,bx,by,gap)) continue;
            if (count>0 && x!=lastPassing+1) return -1;
            shaft=x;
            lastPassing=x;
            count++;
        }
        return count>0 && count<=Math.round(gap*.28f) ? shaft : -1;
    }

    private static boolean joinsHead(byte[] gray,int w,int shaft,float x,float y,float gap) {
        for (int row=Math.round(y-gap*.43f); row<=Math.round(y+gap*.43f); row++) {
            if (Math.abs(row-y)<gap*.19f) continue;
            int from=Math.round(x+gap*.11f), reach=0;
            for (int xx=shaft; xx>=from && dark(gray,w,xx,row); xx--) reach++;
            if (reach>=shaft-from+1) return true;
        }
        return false;
    }

    private static boolean hollow(byte[] ink,int w,float x,float y,float gap) {
        int rx=Math.round(gap*.89f), ry=Math.round(gap*.73f);
        int width=2*rx+1, height=2*ry+1, size=width*height;
        int left=Math.round(x)-rx, top=Math.round(y)-ry;
        boolean[] visited=new boolean[size];
        int[] queue=new int[size];
        for (int seed=0; seed<size; seed++) {
            if (visited[seed] || (ink[(top+seed/width)*w+left+seed%width]&255)<185) continue;
            visited[seed]=true;
            queue[0]=seed;
            int count=1, sumX=0, sumY=0;
            boolean exterior=false;
            for (int i=0; i<count; i++) {
                int p=queue[i], xx=p%width, yy=p/width;
                sumX+=xx;
                sumY+=yy;
                exterior |= xx==0 || xx==width-1 || yy==0 || yy==height-1;
                for (int dy=-1; dy<=1; dy++) {
                    for (int dx=-1; dx<=1; dx++) {
                        if (Math.abs(dx)+Math.abs(dy)!=1 || xx+dx<0 || xx+dx>=width
                                || yy+dy<0 || yy+dy>=height) continue;
                        int q=(yy+dy)*width+xx+dx;
                        if (visited[q] || (ink[(top+yy+dy)*w+left+xx+dx]&255)<185) continue;
                        visited[q]=true;
                        queue[count++]=q;
                    }
                }
            }
            if (!exterior && count>=gap*gap*.08f && count<=gap*gap*.55f
                    && Math.abs(sumX/(float)count-rx)<=gap*.38f
                    && Math.abs(sumY/(float)count-ry)<=gap*.39f) return true;
        }
        return false;
    }

    private static boolean oneLedger(byte[] gray,int w,float left,int close,float bottom,
            float head,float y,int shaft,float gap) {
        int first=-1,last=-1;
        for (int row=Math.round(bottom+gap*.87f); row<=Math.round(bottom+gap*1.35f); row++) {
            int l=shaft,r=shaft;
            if (!dark(gray,w,shaft,row)) continue;
            while (l > Math.round(head-gap*1.4f) && dark(gray,w,l-1,row)) l--;
            while (r < Math.round(head+gap*1.3f) && dark(gray,w,r+1,row)) r++;
            if (head-l < gap*.87f || r-head < gap*.77f || r-l > gap*2.45f) continue;
            if (first < 0) first=row;
            if (last >=0 && row!=last+1) return false;
            last=row;
        }
        if (first<0 || last-first+1>gap*.38f) return false;
        float distance=y-(first+last)*.5f;
        if (distance<gap*.42f || distance>gap*.80f) return false;

        // A real lower staff continues outside the head. A second ledger cuts through the hollow.
        int outerLeft=Math.round(left+gap*.35f), outerRight=Math.round(close-gap*.65f);
        for (int row=Math.round(bottom+gap*.52f); row<=Math.round(y+gap*.80f); row++) {
            if (longHorizontal(gray,w,row,outerLeft,Math.round(head-gap*1.35f),gap)
                    || longHorizontal(gray,w,row,Math.round(head+gap*1.30f),outerRight,gap))
                return false;
            if (row<Math.round(y-gap*.42f) || row>Math.round(y+gap*.43f)) continue;
            if (longHorizontal(gray,w,row,Math.round(head-gap*1.35f),Math.round(head-gap*.68f),gap)
                    || longHorizontal(gray,w,row,Math.round(head+gap*.74f),Math.round(head+gap*1.25f),gap))
                return false;
        }
        return true;
    }

    private static boolean longHorizontal(byte[] gray,int w,int y,int l,int r,float gap) {
        int run=0;
        for (int x=l; x<=r; x++) {
            run=dark(gray,w,x,y) ? run+1 : 0;
            if (run>=Math.round(gap*.48f)) return true;
        }
        return false;
    }

    private static boolean dark(byte[] gray,int w,int x,int y) {
        return (gray[y*w+x]&255)<155;
    }
}
