// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Optical boundaries for a candidate measure extending onto the next printed system. */
final class WrappedMeasureBoundaries {
    private WrappedMeasureBoundaries() {}

    /**
     * Tests only the physical boundary evidence. The caller owns inherited meter, rhythm,
     * single-staff note ownership and absence of a new header meter or key. Head x is in pixels.
     */
    static boolean confirms(
            byte[] gray,
            int width,
            int height,
            List<MeasureRegion> measures,
            int first,
            float firstLastHeadX,
            float secondFirstHeadX) {
        return confirms(gray, width, height, measures, first, firstLastHeadX, secondFirstHeadX, false);
    }

    static boolean confirms(
            byte[] gray, int width, int height, List<MeasureRegion> measures, int first,
            float firstLastHeadX, float secondFirstHeadX, boolean singleBeamGroups) {
        if (gray == null || width <= 0 || height <= 0 || gray.length != (long) width * height
                || measures == null || first < 1 || first >= measures.size() - 2) return false;
        var before = box(measures.get(first - 1), width, height);
        var end = box(measures.get(first), width, height);
        var start = box(measures.get(first + 1), width, height);
        var after = box(measures.get(first + 2), width, height);
        if (before == null || end == null || start == null || after == null) return false;

        float initialGap = (end.height() + start.height()) / 16.4f;
        if (initialGap < 6 || initialGap > 64
                || !sameRow(before, end, initialGap) || !sameRow(start, after, initialGap)
                || !boundaryPocket(before.right, end.left, initialGap)
                || !boundaryPocket(start.right, after.left, initialGap)
                || start.top - end.bottom < initialGap * 3
                || end.right - start.left < initialGap * 16
                || !Float.isFinite(firstLastHeadX) || firstLastHeadX <= end.left
                || firstLastHeadX > end.right - initialGap * (singleBeamGroups ? .50f : .75f)
                || !Float.isFinite(secondFirstHeadX)
                || secondFirstHeadX < start.left + initialGap * 1.3f
                || secondFirstHeadX >= start.right) return false;

        var endTrack = track(gray, width, height, end);
        var startTrack = track(gray, width, height, start);
        if (endTrack == null || startTrack == null) return false;
        float gap = (endTrack.at(end.right)[1] + startTrack.at(start.left)[1]) * .5f;

        // Opening and closing barlines lie in the spaces between the neighboring cropped measures.
        return hasBoundary(gray, width, height, endTrack, before.right, end.left, gap)
                && hasBoundary(gray, width, height, startTrack, start.right, after.left, gap)
                && (openRightMargin(gray, width, height, endTrack, end.right, firstLastHeadX, gap)
                        || singleBeamGroups && openRightEdge(gray, width, height, endTrack,
                                end.right, firstLastHeadX, gap))
                && openLeftMargin(gray, width, height, startTrack, start.left, secondFirstHeadX, gap);
    }

    static boolean confirmsClosed(
            byte[] gray, int width, int height, MeasureRegion region, float firstHead, float lastHead) {
        var bar = box(region, width, height);
        if (bar == null) return false;
        var staff = track(gray, width, height, bar);
        if (staff == null) return false;
        float gap = staff.at(firstHead)[1];
        if (firstHead - bar.left < gap * .88f || bar.right - lastHead < gap * 1.2f) return false;
        return unownedClosedEdge(gray, width, height, staff, bar.left, firstHead, true, gap)
                && unownedClosedEdge(gray, width, height, staff, bar.right, lastHead, false, gap);
    }

    static boolean confirmsInitialStaffBar(
            byte[] gray,int width,int height,List<MeasureRegion> regions,int index,
            float firstHead,float lastHead) {
        if(index<1 || index+1>=regions.size()) return false;
        var bar=box(regions.get(index),width,height);
        var before=box(regions.get(index-1),width,height);
        var after=box(regions.get(index+1),width,height);
        if(bar==null || before==null || after==null) return false;
        float estimatedGap=bar.height()/8.2f;
        if(estimatedGap<8 || estimatedGap>30 || bar.top-before.bottom<estimatedGap*3
                || !sameRow(bar,after,estimatedGap) || firstHead-bar.left<estimatedGap*2
                || !boundaryPocket(bar.right,after.left,estimatedGap)) return false;
        var staff=track(gray,width,height,bar);
        if(staff==null) return false;
        float gap=staff.at(firstHead)[1];
        float afterSignature=bar.left+gap*1.15f;
        float beforeAttack=firstHead-gap*.75f;
        return beforeAttack-afterSignature>=gap*.8f
                && staffRules(gray,width,height,staff,Math.round(afterSignature))==5
                && staffRules(gray,width,height,staff,Math.round(beforeAttack))==5
                && clearOfVerticalInk(gray,width,height,staff,afterSignature,beforeAttack)
                && unownedClosedEdge(gray,width,height,staff,bar.right,lastHead,false,gap);
    }

    private static boolean unownedClosedEdge(byte[] gray,int width,int height,StaffPitchTrack staff,
            float edge,float nearestHead,boolean opening,float gap) {
        int left=Math.max(0,Math.round(edge-gap*(opening ? .85f : .25f)));
        int right=Math.min(width-1,Math.round(edge+gap*(opening ? .25f : .85f)));
        int consecutive=0;
        for (int x=left;x<=right;x++) {
            var f=staff.at(x);
            int top=Math.round(f[0]-4*f[1]), bottom=Math.round(f[0]);
            if (top<0 || bottom>=height || (nearestHead-x)*(opening?1:-1)<gap*1.30f
                    || verticalCoverage(gray,width,height,staff,x)<.92f
                    || continuedColumn(gray,width,height,x,top,-1,gap)
                    || continuedColumn(gray,width,height,x,bottom,1,gap)
                    || DottedDyadMeasureSeparator.continuesBeyondStaff(gray,width,height,x,top,bottom,gap)
                    || ClosedHeadBarlineGuard.attached(gray,width,height,x,top,bottom,gap)) {
                consecutive=0;
                continue;
            }
            if (++consecutive>=Math.max(2,Math.round(gap*.15f))) return true;
        }
        return false;
    }

    private static boolean continuedColumn(byte[] gray,int width,int height,
            int x,int staffEdge,int direction,float gap) {
        int inner=Math.max(1,Math.round(gap*.28f)), outer=Math.round(gap*1.15f);
        if (staffEdge+direction*outer<0 || staffEdge+direction*outer>=height) return true;
        int dark=0;
        for (int d=inner;d<=outer;d++)
            if ((gray[(staffEdge+direction*d)*width+x]&255)<165) dark++;
        return dark>=(outer-inner+1)*.78f;
    }

    private record Box(float left, float right, float top, float bottom) {
        float height() {
            return bottom - top;
        }
    }

    private static Box box(MeasureRegion region, int width, int height) {
        if (region == null
                || !(region.left() >= 0 && region.right() <= 1 && region.left() < region.right()
                        && region.top() >= 0 && region.bottom() <= 1 && region.top() < region.bottom()))
            return null;
        return new Box(region.left() * width, region.right() * width,
                region.top() * height, region.bottom() * height);
    }

    private static boolean sameRow(Box a, Box b, float gap) {
        return Math.abs(a.top - b.top) <= gap && Math.abs(a.bottom - b.bottom) <= gap;
    }

    private static boolean boundaryPocket(float left, float right, float gap) {
        return right >= left && right - left <= gap * 1.8f;
    }

    private static StaffPitchTrack track(byte[] gray, int width, int height, Box box) {
        float gap = box.height() / 8.2f, hint = box.bottom - 2.1f * gap;
        var samples = new ArrayList<float[]>();
        for (int i = 0; i <= 8; i++) {
            float x = box.left + (box.right - box.left) * i / 8;
            var rules = PrintedDirectionStaff.printedRules(gray, width, height, x, hint, gap);
            if (rules != null && Math.abs(rules[1] - gap) <= .15f * gap
                    && Math.abs(rules[0] - hint) <= .65f * gap)
                samples.add(new float[] {x, rules[0], rules[1]});
        }
        if (samples.size() < 3) return null;
        var first = samples.get(0);
        var last = samples.get(samples.size() - 1);
        float widthOfBar = box.right - box.left;
        if (first[0] - box.left > widthOfBar * .38f
                || box.right - last[0] > widthOfBar * .38f) return null;
        return StaffPitchTrack.fromVerifiedSamples(samples);
    }

    private static boolean hasBoundary(byte[] gray, int width, int height, StaffPitchTrack track,
            float pocketLeft, float pocketRight, float gap) {
        int consecutive = 0;
        for (int x = Math.round(pocketLeft - gap * .25f); x <= Math.round(pocketRight + gap * .25f); x++) {
            if (verticalCoverage(gray, width, height, track, x) >= .9f) {
                if (++consecutive >= 2) return true;
            } else consecutive = 0;
        }
        return false;
    }

    private static boolean openRightMargin(byte[] gray, int width, int height, StaffPitchTrack track,
            float end, float lastHead, float gap) {
        float start = Math.max(lastHead + gap * .7f, end - gap * 1.1f);
        // Five rules must approach the decoder edge and then visibly end in white margin.
        if (staffRules(gray, width, height, track, Math.round(end - gap * .55f)) != 5
                || staffRules(gray, width, height, track, Math.round(end + gap * .75f)) > 1
                || staffRules(gray, width, height, track, Math.round(end + gap * 1.3f)) > 1) return false;
        return clearOfVerticalInk(gray, width, height, track, start, end + gap * .5f);
    }

    private static boolean openRightEdge(byte[] gray, int width, int height, StaffPitchTrack track,
            float end, float lastHead, float gap) {
        // Sample five distinct, continuous rails approaching the end. One weak
        // column can be crossed without treating a full-height rule as an edge.
        return staffRules(gray, width, height, track, Math.round(end - gap * .8f)) == 5
                && staffRules(gray, width, height, track, Math.round(end - gap * .25f)) == 5
                && staffRules(gray, width, height, track, Math.round(end + gap * .75f)) <= 1
                && staffRules(gray, width, height, track, Math.round(end + gap * 1.3f)) <= 1
                && clearOfVerticalInk(gray, width, height, track,
                        Math.max(lastHead + gap * .4f, end - gap * 1.1f), end + gap * .5f);
    }

    private static boolean openLeftMargin(byte[] gray, int width, int height, StaffPitchTrack track,
            float start, float firstHead, float gap) {
        // Sample the ruled area after the second system's retained header and before its first head.
        if (staffRules(gray, width, height, track, Math.round(start + gap * .3f)) != 5
                || staffRules(gray, width, height, track, Math.round(firstHead - gap * .7f)) != 5)
            return false;
        return clearOfVerticalInk(gray, width, height, track, start - gap * .7f, firstHead - gap * .65f);
    }

    private static boolean clearOfVerticalInk(byte[] gray, int width, int height, StaffPitchTrack track,
            float left, float right) {
        for (int x = Math.round(left); x <= Math.round(right); x++)
            if (verticalCoverage(gray, width, height, track, x) >= .72f) return false;
        return true;
    }

    private static float verticalCoverage(byte[] gray, int width, int height, StaffPitchTrack track, int x) {
        var frame = track.at(x);
        int top = Math.round(frame[0] - 4 * frame[1]), bottom = Math.round(frame[0]);
        if (x < 0 || x >= width || top < 0 || bottom >= height || top >= bottom) return 1;
        int ink = 0;
        for (int y = top; y <= bottom; y++) if ((gray[y * width + x] & 255) < 175) ink++;
        return (float) ink / (bottom - top + 1);
    }

    private static int staffRules(byte[] gray, int width, int height, StaffPitchTrack track, int x) {
        var frame = track.at(x);
        int band = Math.max(1, Math.round(frame[1] * .15f)), hits = 0;
        if (x < 0 || x >= width || frame[0] + band >= height || frame[0] - 4 * frame[1] - band < 0)
            return -1;
        for (int line = 0; line < 5; line++) {
            int mid = Math.round(frame[0] - line * frame[1]);
            for (int y = mid - band; y <= mid + band; y++) {
                if ((gray[y * width + x] & 255) < 175) {
                    hits++;
                    break;
                }
            }
        }
        return hits;
    }
}
