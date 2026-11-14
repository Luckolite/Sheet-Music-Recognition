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
                || firstLastHeadX > end.right - initialGap * .75f
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
                && openRightMargin(gray, width, height, endTrack, end.right, firstLastHeadX, gap)
                && openLeftMargin(gray, width, height, startTrack, start.left, secondFirstHeadX, gap);
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
