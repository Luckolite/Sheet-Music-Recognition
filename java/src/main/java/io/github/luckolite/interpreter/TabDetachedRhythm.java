// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Independent detached TAB rhythm evidence below the fret grid. */
final class TabDetachedRhythm {
    private TabDetachedRhythm() {}

    static int beamLevels(TablatureDecoder.Staff tab, byte[] gray, int width, int height, float fretX) {
        float gap = tab.gap(), bottom = tab.bottom();
        int center = Math.round(fretX);
        int top = Math.max(0, Math.round(bottom + gap * 1.86f));
        int end = Math.min(height - 1, Math.round(bottom + gap * 2.95f));
        int near = Math.round(gap * .14f);
        if (center - gap * 1.2f < 0 || center + gap * 1.2f >= width) return 0;
        int levels = 0, run = 0, longReach = 0;
        for (int y = top; y <= end + 1; y++) {
            int distance = 0;
            if (y <= end) {
                for (int direction : new int[]{-1, 1}) {
                    int widthRun = 0, blanks = 0;
                    for (int offset = near; offset <= gap * 1.16f; offset++) {
                        if (dark(gray, width, center + direction * offset, y, 200)) {
                            blanks = 0;
                            widthRun++;
                        } else if (++blanks > 2) break;
                    }
                    distance = Math.max(distance, widthRun);
                }
            }
            if (distance >= gap * .31f) {
                run++;
                longReach = Math.max(longReach, distance);
            } else if (run > 0) {
                if (run >= gap * .09f) levels++;
                run = 0;
            }
        }
        return longReach >= gap * .72f && levels <= 3 ? levels : 0;
    }

    static TablatureDecoder.Staff triplets(
            TablatureDecoder.Staff tab, byte[] gray, int width, int height) {
        if (tab.standardTop() >= 0 || tab.bars().size() < 2) return tab;
        List<TablatureDecoder.Fret> result = null;
        var frets = tab.frets();
        for (int b = 0; b + 1 < tab.bars().size(); b++) {
            float left = tab.bars().get(b), right = tab.bars().get(b+1);
            var indices = new ArrayList<Integer>();
            for (int i = 0; i < frets.size(); i++)
                if (frets.get(i).x() > left && frets.get(i).x() < right) indices.add(i);
            if (indices.size() != 4) continue;
            var first = frets.get(indices.get(0));
            var middle = frets.get(indices.get(1));
            var last = frets.get(indices.get(2));
            var ending = frets.get(indices.get(3));
            if (!quarter(first) || !quarter(middle) || !quarter(last)
                    || ending.duration() != 2 || ending.beams() != 0 || ending.dots() != 0
                    || first.string() != middle.string() || first.string() != last.string()
                    || (last.x()-first.x()) < tab.gap()*4
                    || Math.abs(middle.x()-(first.x()+last.x())*.5f) > tab.gap()*.55f
                    || !bracket(gray,width,height,tab,first.x(),middle.x(),last.x())) continue;
            if (result == null) result = new ArrayList<>(frets);
            for (int k = 0; k < 3; k++) {
                int i = indices.get(k);
                var n = frets.get(i);
                result.set(i, new TablatureDecoder.Fret(n.x(),n.y(),n.string(),n.fret(),
                        n.duration(),n.beams(),n.dots(),n.marks(),n.tied(),3,n.wholeRestGlyph()));
            }
        }
        return result == null ? tab : tab.withFrets(result);
    }

    private static boolean quarter(TablatureDecoder.Fret f) {
        return f.fret() >= 0 && f.duration() == 1 && f.beams() == 0 && f.dots() == 0
                && f.tuplet() == 1 && !f.tied() && f.marks() == 0;
    }

    private static boolean bracket(byte[] gray, int w, int h, TablatureDecoder.Staff tab,
            float left, float mid, float right) {
        float gap = tab.gap();
        int a = Math.round(left), b = Math.round(mid - gap*.96f);
        int c = Math.round(mid + gap*.96f), d = Math.round(right);
        int margin = Math.round(gap*.6f);
        if (a - margin < 0 || d + margin >= w || b-a < gap || d-c < gap) return false;
        for (int y = Math.max(margin, Math.round(tab.bottom()+gap*2.9f));
                y <= Math.min(h-margin-1, Math.round(tab.bottom()+gap*4.25f)); y++) {
            if (!arm(gray,w,y,a+Math.round(gap*.22f),b)
                    || !arm(gray,w,y,c,d-Math.round(gap*.22f))) continue;
            if (hook(gray,w,y,a,gap) && hook(gray,w,y,d,gap) && three(gray,w,y,mid,gap)) return true;
        }
        return false;
    }

    private static boolean arm(byte[] gray,int w,int y,int a,int b) {
        int ink = 0;
        for (int x = a; x <= b; x++) if (dark(gray,w,x,y,190)) ink++;
        return ink >= (b-a+1)*.90f;
    }

    private static boolean hook(byte[] gray,int w,int y,int x,float gap) {
        int reach = Math.round(gap*.42f), radius = Math.round(gap*.20f);
        for (int xx = x-radius; xx <= x+radius; xx++) {
            int ink = 0;
            for (int dy = 0; dy <= reach; dy++) if (dark(gray,w,xx,y-dy,190)) ink++;
            if (ink >= reach*.77f) return true;
        }
        return false;
    }

    private static boolean three(byte[] gray,int w,int y,float mid,float gap) {
        int left = Math.round(mid-gap*.47f), right = Math.round(mid+gap*.47f);
        int minX = right+1, maxX = -1, minY = -1, maxY = -1;
        for (int yy = y-Math.round(gap*.56f); yy <= y+Math.round(gap*.54f); yy++)
            for (int x = left; x <= right; x++) if (dark(gray,w,x,yy,175)) {
                minX = Math.min(minX,x);
                maxX = Math.max(maxX,x);
                if (minY < 0) minY = yy;
                maxY = yy;
            }
        if (maxY-minY < gap*.58f || maxY-minY > gap*.96f
                || maxX-minX < gap*.35f || maxX-minX > gap*.64f
                || minX == left || maxX == right) return false;
        int upper = minY+Math.round((maxY-minY)*.40f), lower = maxY-Math.round((maxY-minY)*.40f);
        for (int yy : new int[]{upper,lower}) {
            int leftInk = 0, rightInk = 0, split = (minX+maxX)/2;
            for (int x = minX; x <= maxX; x++) if (dark(gray,w,x,yy,175)) {
                if (x < split) leftInk++;
                else rightInk++;
            }
            if (rightInk < 2 || leftInk >= rightInk) return false;
        }
        return true;
    }

    private static boolean dark(byte[] gray,int w,int x,int y,int threshold) {
        return (gray[y*w+x]&255) < threshold;
    }
}
