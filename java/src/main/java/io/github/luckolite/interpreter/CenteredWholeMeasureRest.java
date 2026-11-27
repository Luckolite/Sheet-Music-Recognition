// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.BitSet;

/** A centered hanging plate qualifies explicit silence in a previously unowned bar. */
final class CenteredWholeMeasureRest {
    private CenteredWholeMeasureRest() {}
    private record Plate(float x, float y, float height) {}

    static ScorePageInterpretation recover(ScorePageInterpretation page, byte[] gray,
            byte[] source, int width, int height) {
        if (gray == null || source == null || gray.length != (long)width*height
                || source.length != gray.length) return page;
        var occupied = new BitSet();
        for (var note : page.notes()) occupied.set(note.measureIndex());
        for (var rest : page.rests()) occupied.set(rest.measureIndex());
        for (var group : page.multiRestGroups())
            occupied.set(group.firstMeasureIndex(),group.firstMeasureIndex()+group.measureCount());
        ArrayList<ScoreRestEvent> result = null;
        for (int i = 0; i < page.measures().size(); i++) {
            if (occupied.get(i)) continue;
            var measure = page.measures().get(i);
            float gap = (measure.bottom()-measure.top())*height/8.15f;
            float left = measure.left()*width, right = measure.right()*width;
            if (gap < 8 || gap > 28 || right-left < gap*7 || measure.cadenceQuarterBeats() != 0) continue;
            if (overlapsAnotherLane(page,i,measure,gap/height)) continue;
            var mark = plate(source,width,height,left,right,measure.bottom()*height-gap*2.05f,gap);
            if (mark == null) continue;
            var processed = gray == source ? mark
                    : plate(gray,width,height,left,right,measure.bottom()*height-gap*2.05f,gap);
            if (processed == null || Math.abs(mark.x()-processed.x()) > .26f*gap
                    || Math.abs(mark.y()-processed.y()) > .25f*gap) continue;
            if (result == null) result = new ArrayList<>(page.rests());
            result.add(ScoreRestEvent.fullMeasure(i,(mark.x()-left)/(right-left),mark.y()/height,
                    mark.height()/height,0,1));
        }
        if (result == null) return page;
        var before = page.clockContextSource();
        var after = new ScoreClockContextSource(before.origin(),before.noteCount(),before.measureCount(),result.size(),
                before.partWords(),before.numbers(),before.meters(),before.printedValues(),before.glyphLedgers(),
                before.restValues(),before.voiceGroups(),before.syncAnchors(),before.missing());
        return page.withRests(result).withClockContextSource(after);
    }

    private static boolean overlapsAnotherLane(
            ScorePageInterpretation page,int index,MeasureRegion region,float gap) {
        for (int i = 0; i < page.measures().size(); i++) {
            if (i == index) continue;
            var other = page.measures().get(i);
            if (other.left() < region.right() && other.right() > region.left()
                    && other.top() < region.bottom()+gap && other.bottom() > region.top()-gap) return true;
        }
        return false;
    }

    private static Plate plate(byte[] gray,int w,int h,float left,float right,float hint,float gap) {
        float center = (left+right)*.5f;
        var rails = PrintedDirectionStaff.printedRules(gray,w,h,center,hint,gap);
        if (rails == null || Math.abs(rails[0]-hint) > gap*.52f) return null;
        gap = rails[1];
        int rule = Math.round(rails[0]-3*gap);
        int searchLeft = Math.round(center-(right-left)*.18f), searchRight = Math.round(center+(right-left)*.18f);
        if (searchLeft < 0 || searchRight >= w || rule-gap*.5f < 0 || rule+gap >= h) return null;
        Plate best = null;
        for (int y = rule+Math.round(gap*.22f); y <= rule+Math.round(gap*.44f); y++)
            for (int x = searchLeft; x < searchRight; x++) {
                if (!ink(gray,w,x,y)) continue;
                int start = x;
                while (x <= searchRight && ink(gray,w,x,y)) x++;
                int edge = x-1, length = x-start;
                if (start == searchLeft || edge == searchRight || length < gap*.80f || length > gap*1.75f)
                    continue;
                int middle = (start+edge)/2;
                int top = y, bottom = y;
                while (top > rule-Math.round(gap*.18f) && ink(gray,w,middle,top-1)) top--;
                while (bottom < rule+Math.round(gap*.70f) && ink(gray,w,middle,bottom+1)) bottom++;
                if (top > rule+1 || top < rule-gap*.18f || bottom < rule+gap*.26f || bottom > rule+gap*.62f)
                    continue;
                int uniform = 0;
                for (int xx = start; xx <= edge; xx++)
                    if (ink(gray,w,xx,Math.round(rule+gap*.23f))
                            && ink(gray,w,xx,bottom-1)
                            && !ink(gray,w,xx,Math.round(rule-gap*.28f))
                            && !ink(gray,w,xx,Math.round(rule+gap*.71f))) uniform++;
                if (uniform < length*.75f) continue;
                if (best != null && Math.abs(best.x()-middle) > gap*.32f) return null;
                best = new Plate(middle,(top+bottom)*.5f,bottom-top+1f);
            }
        return best;
    }

    private static boolean ink(byte[] gray,int width,int x,int y) {
        return (gray[y*width+x]&255)<155;
    }
}
