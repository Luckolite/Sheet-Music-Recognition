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

    /** Preserve the pitch of a hollow sixth sitting in the space below two short ledgers. */
    static ScorePageInterpretation captureTerminalSpaceSixth(ScorePageInterpretation score,
            byte[] raw, byte[] normalized, int width, int height) {
        if (raw == null || normalized == null || raw.length != (long) width * height
                || normalized.length != raw.length) return score;
        var notes = score.notes();
        ArrayList<ScoreNoteEvent> edited = null;
        for (int i = 1; i + 3 < notes.size(); i++) {
            var a = notes.get(i);
            var b = notes.get(i + 1);
            int m = a.measureIndex();
            if (m < 0 || m + 1 >= score.measures().size() || b.measureIndex() != m
                    || !plainHalf(a, 0, 1) || !plainHalf(b, 0, 1)
                    || a.stemDirection() != 1 || b.stemDirection() != 1) continue;
            int lowOwner = a.pageY() > b.pageY() ? i : i + 1;
            var low = notes.get(lowOwner);
            var high = lowOwner == i ? b : a;
            if (low.staffStep() != -6 || high.staffStep() != 0
                    || low.clefBottomDiatonic() != high.clefBottomDiatonic()) continue;
            var c = notes.get(i + 2);
            var d = notes.get(i + 3);
            var nextLow = c.pageY() > d.pageY() ? c : d;
            var nextHigh = nextLow == c ? d : c;
            if (c.measureIndex() != m + 1 || d.measureIndex() != m + 1
                    || !repeatsWrittenSixth(high, nextHigh, 0) || !repeatsWrittenSixth(low, nextLow, -5)) continue;
            var bar = score.measures().get(m);
            var next = score.measures().get(m + 1);
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            float x = headX(bar, low, width), y = low.pageY() * height;
            float ax = headX(bar, high, width), ay = high.pageY() * height;
            float nx = headX(next, nextHigh, width);
            if (gap < 12 || gap > 23 || x < 4 * gap || x + 4 * gap >= width
                    || ay < 5 * gap || y + 2 * gap >= height
                    || Math.abs(x - ax) > .20f * gap
                    || Math.abs(nx - headX(next, nextLow, width)) > .23f * gap
                    || nx - x < 3 * gap || nx - x > 10 * gap
                    || Math.abs(ay - nextHigh.pageY() * height) > .19f * gap
                    || Math.abs(y - nextLow.pageY() * height) > .25f * gap
                    || y - ay < 2.42f * gap || y - ay > 2.76f * gap) continue;
            if (!provesSpaceSixth(raw, width, height, bar, x, y, ax, ay, gap)
                    || !provesSpaceSixth(normalized, width, height, bar, x, y, ax, ay, gap)) continue;
            if (edited == null) edited = new ArrayList<>(notes);
            edited.set(lowOwner, singleStaff(low, -5, low.clefBottomDiatonic()));
        }
        return edited == null ? score : score.withNotes(edited,
                java.util.stream.IntStream.range(0, notes.size()).boxed().toList())
                .withClockContextSource(score.clockContextSource());
    }

    /** Corrects a hollow closing head whose centroid fell in the adjacent staff space. */
    static ScorePageInterpretation captureTerminalStaffLine(ScorePageInterpretation score,
            byte[] raw, byte[] normalized, int width, int height) {
        if (raw == null || normalized == null || raw.length != (long) width * height
                || normalized.length != raw.length) return score;
        var notes = score.notes();
        ArrayList<ScoreNoteEvent> edited = null;
        for (int i = 0; i + 1 < notes.size(); i++) {
            var a = notes.get(i);
            var b = notes.get(i + 1);
            int barIndex = a.measureIndex();
            if (b.measureIndex() != barIndex || i + 2 < notes.size()
                    && notes.get(i + 2).measureIndex() == barIndex
                    || !plainHalf(a, 0, 1) || !plainHalf(b, 0, 1)
                    || a.stemDirection() != 1 || b.stemDirection() != 1) continue;
            int lowerIndex = a.pageY() > b.pageY() ? i : i + 1;
            var lower = notes.get(lowerIndex);
            var upper = lowerIndex == i ? b : a;
            int line = lower.staffStep() + 1;
            if (line < 2 || line > 6 || line % 2 != 0 || upper.staffStep() != line + 2
                    || upper.clefBottomDiatonic() != lower.clefBottomDiatonic()) continue;
            var bar = score.measures().get(barIndex);
            if (barIndex + 1 < score.measures().size()
                    && score.measures().get(barIndex + 1).top() <= bar.bottom()) continue;
            float nominal = (bar.bottom() - bar.top()) * height / 8.2f;
            float x = headX(bar, lower, width), y = lower.pageY() * height;
            float upperX = headX(bar, upper, width), upperY = upper.pageY() * height;
            float right = bar.right() * width;
            if (nominal < 12 || nominal > 23 || x < 4 * nominal || x + 4 * nominal >= width
                    || upperY < 5 * nominal || y + 2 * nominal >= height
                    || right - x < 2.4f * nominal || right - x > 4.8f * nominal
                    || Math.abs(x - upperX) > .17f * nominal
                    || y - upperY < 1.13f * nominal || y - upperY > 1.33f * nominal) continue;
            if (!provesTerminalStaffLine(raw, width, height, bar, x, y, upperX, upperY, line, nominal)
                    || !provesTerminalStaffLine(normalized, width, height, bar, x, y, upperX, upperY, line, nominal))
                continue;
            if (edited == null) edited = new ArrayList<>(notes);
            edited.set(lowerIndex, lower.withStaffStep(line));
        }
        return edited == null ? score : score.withNotes(edited,
                java.util.stream.IntStream.range(0, notes.size()).boxed().toList())
                .withClockContextSource(score.clockContextSource());
    }

    private static boolean provesTerminalStaffLine(byte[] ink, int width, int height, MeasureRegion bar,
            float x, float y, float upperX, float upperY, int step, float nominal) {
        var rules = PrintedDirectionStaff.printedRules(ink, width, height, x,
                bar.bottom() * height - 2.1f * nominal, nominal);
        if (rules == null || Math.abs(rules[1] - nominal) > .12f * nominal) return false;
        float gap = rules[1], line = rules[0] - .5f * step * gap;
        if (Math.abs(line - gap - upperY) > .14f * gap || y - line < .19f * gap
                || y - line > .35f * gap || !hollow(ink, width, upperX, line - gap, gap)
                || !hollow(ink, width, x, line, gap)) return false;
        // Below the line-centered oval, there must be paper before the next staff space.
        int left = Math.round(x - .43f * gap), right = Math.round(x + .43f * gap);
        int rim = Math.round(line + .46f * gap);
        int rimInk = 0;
        for (int col = left; col <= right; col++) if (dark(ink, width, col, rim)) rimInk++;
        if (rimInk < .5f * (right - left + 1)) return false;
        for (int row = Math.round(line + .68f * gap); row <= Math.round(line + .80f * gap); row++)
            for (int col = left; col <= right; col++)
                if (dark(ink, width, col, row)) return false;
        return true;
    }

    private static boolean repeatsWrittenSixth(ScoreNoteEvent prior, ScoreNoteEvent next, int step) {
        return next.staffStep() == step && next.clefBottomDiatonic() == prior.clefBottomDiatonic()
                && next.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && next.stemDirection() == 1 && ClosedMonophonicSixteenths.regularQuartetHead(next);
    }

    private static float headX(MeasureRegion bar, ScoreNoteEvent note, int width) {
        return (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
    }

    private static boolean provesSpaceSixth(byte[] ink, int width, int height, MeasureRegion bar,
            float x, float y, float ax, float ay, float nominal) {
        var rails = PrintedDirectionStaff.printedRules(ink, width, height, x,
                bar.bottom() * height - 2.1f * nominal, nominal);
        if (rails == null || Math.abs(rails[0] - ay) > .22f * rails[1]
                || Math.abs(rails[1] - nominal) > .12f * nominal) return false;
        float gap = rails[1];
        if (sharedShaft(ink, width, ax, ay, x, y, gap) < 0
                || !hollow(ink, width, ax, ay, gap) || !hollow(ink, width, x, y, gap)) return false;
        int first = ledgerAt(ink, width, x, rails[0] + gap, gap);
        int second = ledgerAt(ink, width, x, rails[0] + 2 * gap, gap);
        return first >= 0 && second >= 0 && Math.abs(second - first - gap) <= .18f * gap
                && y - second >= .48f * gap && y - second <= .86f * gap
                && ledgerAt(ink, width, x, rails[0] + 3 * gap, gap) < 0;
    }

    private static int ledgerAt(byte[] ink, int width, float x, float y, float gap) {
        int left = Math.round(x - gap), right = Math.round(x + gap);
        int first = -1, last = -1;
        for (int row = Math.round(y - .27f * gap); row <= Math.round(y + .27f * gap); row++) {
            int covered = 0;
            for (int col = left; col <= right; col++) if (dark(ink, width, col, row)) covered++;
            if (covered < (right - left + 1) * .85f) continue;
            if (first < 0) first = row;
            if (last >= 0 && row != last + 1) return -1;
            last = row;
        }
        return first >= 0 && last - first + 1 >= 2 && last - first + 1 <= .38f * gap
                ? Math.round((first + last) * .5f) : -1;
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
        return plainHalf(note, staff, 2);
    }

    private static boolean plainHalf(ScoreNoteEvent note, int staff, int staffCount) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && note.staffIndex() == staff && note.staffCount() == staffCount && !note.crossStaffBeam()
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

    static boolean hollow(byte[] ink,int w,float x,float y,float gap) {
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
