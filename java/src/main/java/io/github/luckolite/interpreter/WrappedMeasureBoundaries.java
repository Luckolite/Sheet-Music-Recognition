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
        return confirms(
                gray,
                width,
                height,
                measures,
                first,
                firstLastHeadX,
                secondFirstHeadX,
                singleBeamGroups,
                null,
                0);
    }

    static boolean confirms(
            byte[] gray,
            int width,
            int height,
            List<MeasureRegion> measures,
            int first,
            float firstLastHeadX,
            float secondFirstHeadX,
            boolean singleBeamGroups,
            byte[] labels,
            int inheritedKey) {
        return confirms(gray, width, height, measures, first, firstLastHeadX, secondFirstHeadX,
                singleBeamGroups, labels, inheritedKey, null, null);
    }

    static boolean confirmsSixteenthDyads(
            byte[] gray, int width, int height, List<MeasureRegion> measures, int first,
            float lastHead, float firstHead, byte[] labels, int key,
            PlayingTechniqueDetector.Staff outgoing, PlayingTechniqueDetector.Staff incoming) {
        return confirms(gray, width, height, measures, first, lastHead, firstHead, true, labels, key,
                outgoing, incoming);
    }

    private static boolean confirms(
            byte[] gray, int width, int height, List<MeasureRegion> measures, int first,
            float firstLastHeadX, float secondFirstHeadX, boolean useEdgeAndHeader,
            byte[] labels, int inheritedKey, PlayingTechniqueDetector.Staff outgoing,
            PlayingTechniqueDetector.Staff incoming) {
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
                || firstLastHeadX > end.right - initialGap * (useEdgeAndHeader ? .50f : .75f)
                || !Float.isFinite(secondFirstHeadX)
                || secondFirstHeadX < start.left + initialGap * 1.3f
                || secondFirstHeadX >= start.right) return false;

        // Dense ledger chords can obscure bilateral staff sampling. The fitted
        // frame still requires raw confirmation at both boundaries and margins.
        var endTrack = outgoing == null ? track(gray, width, height, end)
                : StaffPitchTrack.linear(width, outgoing.bottom(), outgoing.gap(), 0);
        var startTrack = incoming == null ? track(gray, width, height, start)
                : StaffPitchTrack.linear(width, incoming.bottom(), incoming.gap(), 0);
        if (endTrack == null || startTrack == null) return false;
        float gap = (endTrack.at(end.right)[1] + startTrack.at(start.left)[1]) * .5f;

        // Opening and closing barlines lie in the spaces between the neighboring cropped measures.
        return hasBoundary(gray, width, height, endTrack, before.right, end.left, gap)
                && hasBoundary(gray, width, height, startTrack, start.right, after.left, gap)
                && (openRightMargin(gray, width, height, endTrack, end.right, firstLastHeadX, gap)
                        || useEdgeAndHeader
                                && openRightEdge(
                                        gray,
                                        width,
                                        height,
                                        endTrack,
                                        end.right,
                                        firstLastHeadX,
                                        gap))
                && (openLeftMargin(
                                gray, width, height, startTrack, start.left, secondFirstHeadX, gap)
                        || useEdgeAndHeader
                                && inheritedFlatHeader(
                                        gray,
                                        labels,
                                        width,
                                        height,
                                        startTrack,
                                        start.left,
                                        secondFirstHeadX,
                                        gap,
                                        inheritedKey,
                                        incoming == null ? 1.5f : 1.3f)
                        || useEdgeAndHeader && incoming == null
                                && inheritedSharpHeader(
                                        gray, labels, width, height, startTrack,
                                        start.left, secondFirstHeadX, gap, inheritedKey));
    }

    static boolean confirmsClosed(
            byte[] gray, int width, int height, MeasureRegion region, float firstHead, float lastHead) {
        var bar = box(region, width, height);
        if (bar == null) return false;
        var staff = track(gray, width, height, bar);
        if (staff == null) return false;
        return confirmsClosed(gray, width, height, bar, firstHead, lastHead, staff);
    }

    static boolean confirmsClosedWithVerifiedStaff(
            byte[] gray,
            int width,
            int height,
            MeasureRegion region,
            float firstHead,
            float lastHead,
            StaffPitchTrack staff) {
        var bar = box(region, width, height);
        return bar != null && confirmsClosed(gray, width, height, bar, firstHead, lastHead, staff);
    }

    private static boolean confirmsClosed(
            byte[] gray,
            int width,
            int height,
            Box bar,
            float firstHead,
            float lastHead,
            StaffPitchTrack staff) {
        float gap = staff.at(firstHead)[1];
        if (firstHead - bar.left < gap * .88f || bar.right - lastHead < gap * 1.2f) return false;
        return unownedClosedEdge(gray, width, height, staff, bar.left, firstHead, true, gap)
                && unownedClosedEdge(gray, width, height, staff, bar.right, lastHead, false, gap);
    }

    static boolean confirmsPackedRightEdgeFromStem(
            byte[] gray, int width, int height, MeasureRegion region,
            float firstHead, float lastHead, float lastStem) {
        var bounds = box(region, width, height);
        if (bounds == null) return false;
        var staff = track(gray, width, height, bounds, true);
        return staff != null && confirmsPackedRightEdgeFromStem(gray, width, height,
                region, firstHead, lastHead, lastStem, staff);
    }

    /** A downstem can leave the final head near the crop while its rail still closes before the rule. */
    static boolean confirmsPackedRightEdgeFromStem(
            byte[] gray,
            int width,
            int height,
            MeasureRegion region,
            float firstHead,
            float lastHead,
            float lastStem,
            StaffPitchTrack staff) {
        return confirmsPackedRightEdgeFromStem(gray, width, height, region, firstHead, lastHead,
                lastStem, staff, 1.20f);
    }

    static boolean confirmsPackedTripletPredecessor(
            byte[] gray, int width, int height, MeasureRegion region,
            float firstHead, float lastHead, float lastStem) {
        var bounds = box(region, width, height);
        if (bounds == null) return false;
        var staff = track(gray, width, height, bounds, true);
        return staff != null && confirmsPackedRightEdgeFromStem(
                gray, width, height, region, firstHead, lastHead, lastStem, staff, 1.30f);
    }

    private static boolean confirmsPackedRightEdgeFromStem(
            byte[] gray, int width, int height, MeasureRegion region,
            float firstHead, float lastHead, float lastStem, StaffPitchTrack staff, float maxInset) {
        var bar = box(region, width, height);
        if (bar == null) return false;
        float gap = staff.at(lastHead)[1], inset = bar.right - lastHead;
        if (inset < gap * .45f
                || inset >= gap * maxInset
                || firstHead - bar.left < gap * .88f
                || lastHead - lastStem < gap * .28f
                || lastHead - lastStem > gap * .80f
                || !unownedClosedEdge(gray, width, height, staff, bar.left, firstHead, true, gap))
            return false;
        int begin = Math.max(0, Math.round(bar.right - gap * .25f));
        int end = Math.min(width - 1, Math.round(bar.right + gap * .92f));
        int run = 0;
        for (int x = begin; x <= end; x++) {
            if (x - lastHead < gap * .95f
                    || x - lastStem < gap * 1.48f
                    || !independentRuleColumn(gray, width, height, staff, x, gap, .94f)) {
                run = 0;
                continue;
            }
            if (++run >= Math.max(2, Math.round(gap * .13f))) return true;
        }
        return false;
    }

    /** Check a packed staff ending whose left divider lies between consecutive decoder regions. */
    static boolean confirmsPocketAndPackedClosingEdge(
            byte[] plane, int width, int height, MeasureRegion preceding, MeasureRegion region,
            float firstHead, float lastHead, float lastStem) {
        var before = box(preceding, width, height);
        var bar = box(region, width, height);
        if (before == null || bar == null) return false;
        float gap = bar.height() / 8.2f;
        if (gap < 10 || gap > 25 || !sameRow(before, bar, gap)
                || !boundaryPocket(before.right, bar.left, gap)
                || firstHead - bar.left < gap * .60f || firstHead - bar.left >= gap * .88f)
            return false;
        var pocket = new MeasureRegion((preceding.right() + region.left()) * .5f,
                region.right(), region.top(), region.bottom());
        return confirmsPackedRightEdgeFromStem(plane, width, height, pocket,
                firstHead, lastHead, lastStem);
    }

    /** A shortened decoder box is acceptable only when both independent barlines are visible. */
    static boolean confirmsClosedTripletBar(
            byte[] gray,
            int width,
            int height,
            MeasureRegion region,
            float firstHead,
            float lastHead) {
        if (confirmsClosed(gray, width, height, region, firstHead, lastHead)) return true;
        var bar = box(region, width, height);
        if (bar == null || lastHead >= bar.right) return false;
        var staff = track(gray, width, height, bar);
        if (staff == null) return false;
        float gap = staff.at(firstHead)[1];
        if (firstHead - bar.left < gap * .88f
                || bar.right - lastHead < gap * .8f
                || bar.right - lastHead >= gap * 1.2f
                || !unownedClosedEdge(gray, width, height, staff, bar.left, firstHead, true, gap)
                || !unownedClosedEdge(gray, width, height, staff, bar.right, lastHead, false, gap))
            return false;
        int first = Math.max(0, Math.round(bar.right - gap * .25f));
        int last = Math.min(width - 1, Math.round(bar.right + gap * 1.35f));
        int run = 0;
        int strokes = 0;
        for (int x = first; x <= last + 1; x++) {
            if (x <= last && verticalCoverage(gray, width, height, staff, x) >= .92f) run++;
            else {
                if (run >= Math.max(2, Math.round(gap * .12f))) strokes++;
                run = 0;
            }
        }
        return strokes == 1;
    }

    /** Use the measured separator between neighboring boxes when the left crop hides its inset. */
    static boolean confirmsPocketClosedTripletBar(
            byte[] gray, int width, int height, MeasureRegion preceding, MeasureRegion region,
            float firstHead, float lastHead) {
        var prior = box(preceding, width, height);
        var bar = box(region, width, height);
        if (prior == null || bar == null) return false;
        var staff = track(gray, width, height, bar);
        if (staff == null) return false;
        float gap = staff.at(firstHead)[1];
        float inset = firstHead - bar.left;
        if (!sameRow(prior, bar, gap)
                || !boundaryPocket(prior.right, bar.left, gap)
                || inset < gap * .6f || inset >= gap * .88f
                || bar.right - lastHead < gap * 1.2f
                || !unownedClosedEdge(gray, width, height, staff, bar.right, lastHead, false, gap))
            return false;
        int begin = Math.max(0, Math.round(prior.right - gap * .16f));
        int end = Math.min(width - 1, Math.round(bar.left + gap * .10f));
        int consecutive = 0;
        for (int x = begin; x <= end; x++) {
            if (firstHead - x < gap * 1.08f
                    || !independentRuleColumn(gray, width, height, staff, x, gap, .92f)) {
                consecutive = 0;
                continue;
            }
            if (++consecutive >= Math.max(2, Math.round(gap * .15f))) return true;
        }
        return false;
    }

    /** Decoder crops can cut the narrow inset before the following printed barline. */
    static boolean confirmsNeighborBoundedTripletBar(byte[] source, int width, int height,
            List<MeasureRegion> regions, int index, float firstHead, float lastHead) {
        if (index <= 0 || index + 1 >= regions.size()) return false;
        var bar = box(regions.get(index), width, height);
        var preceding = box(regions.get(index - 1), width, height);
        var following = box(regions.get(index + 1), width, height);
        if (bar == null || preceding == null || following == null) return false;
        float gap = bar.height() / 8.2f;
        float inset = bar.right - lastHead;
        if (gap < 10 || gap > 25 || inset < gap * .60f || inset >= gap * 1.2f
                || firstHead - bar.left < gap * .85f
                || !sameRow(preceding, bar, gap) || !sameRow(bar, following, gap)
                || !boundaryPocket(preceding.right, bar.left, gap)
                || !boundaryPocket(bar.right, following.left, gap)) return false;

        float left = (preceding.right + bar.left) * .5f;
        float right = (bar.right + following.left) * .5f;
        float bottom = bar.bottom - gap * 2.1f;
        var staff = StaffPitchTrack.forPrintedSlurs(
                source, width, height, bottom - gap * 4, bottom, gap);
        if (staff == null || !staff.verifiedAt(left) || !staff.verifiedAt(right)) return false;
        float[] start = staff.at(left), end = staff.at(right);
        if (Math.abs(start[1] - gap) > gap * .12f
                || Math.abs(end[1] - gap) > gap * .12f
                || Math.abs(start[0] - bottom) > gap * .55f
                || Math.abs(end[0] - bottom) > gap * .55f) return false;
        return unownedClosedEdge(source, width, height, staff, left, firstHead, true, start[1])
                && unownedClosedEdge(source, width, height, staff, right, lastHead, false, end[1]);
    }

    static boolean confirmsTightNeighborBoundedChromaticBar(byte[] gray, int width, int height,
            List<MeasureRegion> regions, int index, float first, float last, StaffPitchTrack track) {
        if (index <= 0 || index + 1 >= regions.size() || track == null) return false;
        var before = box(regions.get(index - 1), width, height);
        var bar = box(regions.get(index), width, height);
        var after = box(regions.get(index + 1), width, height);
        if (before == null || bar == null || after == null) return false;
        float gap = track.at(first)[1];
        float beforePocket = bar.left - before.right, afterPocket = after.left - bar.right;
        if (!sameRow(before, bar, gap) || !sameRow(bar, after, gap)
                || beforePocket < .8f * gap || beforePocket > 1.35f * gap
                || afterPocket < .8f * gap || afterPocket > 1.35f * gap) return false;
        float left = (before.right + bar.left) * .5f, right = (bar.right + after.left) * .5f;
        return first - left >= 1.15f * gap && right - last >= 1.30f * gap
                && unownedClosedEdge(gray, width, height, track, left, first, true, gap, 1.12f)
                && unownedClosedEdge(gray, width, height, track, right, last, false, gap);
    }

    static StaffPitchTrack verifiedTripletStaff(byte[] source, int width, int height, MeasureRegion region) {
        return verifiedTripletStaff(source, width, height, region, false);
    }

    /** Recover both separators in neighboring crop pockets around a nearly packed six-beat bar. */
    static boolean confirmsNarrowTripletBarPockets(byte[] source, int width, int height,
            List<MeasureRegion> regions, int index, float firstHead, float lastHead) {
        if (index <= 0 || index + 1 >= regions.size()) return false;
        var region = regions.get(index);
        var bar = box(region, width, height);
        var previous = box(regions.get(index - 1), width, height);
        var next = box(regions.get(index + 1), width, height);
        if (bar == null || previous == null || next == null) return false;
        float gap = bar.height() / 8.2f;
        float firstInset = firstHead - bar.left, lastInset = bar.right - lastHead;
        if (gap < 10 || gap > 25 || !sameRow(previous, bar, gap) || !sameRow(bar, next, gap)
                || !boundaryPocket(previous.right, bar.left, gap) || !boundaryPocket(bar.right, next.left, gap)
                || firstInset < gap * .60f || firstInset >= gap * .88f
                || lastInset < gap * .60f || lastInset >= gap * 1.2f) return false;
        float left = (previous.right + bar.left) * .5f, right = (bar.right + next.left) * .5f;
        var staff = track(source, width, height, new Box(left, right, bar.top, bar.bottom));
        return staff != null && staff.verifiedAt(left) && staff.verifiedAt(right)
                && unownedClosedEdge(source, width, height, staff, left, firstHead, true,
                        staff.at(left)[1], 1.18f)
                && unownedClosedEdge(source, width, height, staff, right, lastHead, false, staff.at(right)[1]);
    }

    static StaffPitchTrack verifiedTripletStaff(
            byte[] source, int width, int height, MeasureRegion region, boolean nearby) {
        var bar = box(region, width, height);
        return bar == null ? null : track(source, width, height, bar, nearby);
    }

    static boolean confirmsInitialStaffBar(
            byte[] gray,int width,int height,List<MeasureRegion> regions,int index,
            float firstHead,float lastHead) {
        return confirmsInitialStaffBar(gray,width,height,regions,index,firstHead,lastHead,Float.POSITIVE_INFINITY);
    }

    /** Keep the clear header pocket before a separately proved first shaft. */
    static boolean confirmsInitialStaffBar(
            byte[] gray,int width,int height,List<MeasureRegion> regions,int index,
            float firstHead,float lastHead,float firstShaftLeft) {
        return confirmsInitialStaffBar(gray, width, height, regions, index, firstHead, lastHead,
                firstShaftLeft, false);
    }

    /** Try nearby clear rail windows when closely spaced shafts defeat the regular samples. */
    static boolean confirmsSampledInitialTripletBar(
            byte[] gray, int width, int height, List<MeasureRegion> regions, int index,
            float firstHead, float lastHead, int firstShaftLeft) {
        return confirmsInitialStaffBar(gray, width, height, regions, index, firstHead, lastHead,
                firstShaftLeft, true);
    }

    private static boolean confirmsInitialStaffBar(
            byte[] gray, int width, int height, List<MeasureRegion> regions, int index,
            float firstHead, float lastHead, float firstShaftLeft, boolean nearbySamples) {
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
        if (nearbySamples) {
            if (staff != null) return false;
            staff = track(gray, width, height, bar, true);
        }
        if(staff==null) return false;
        float gap=staff.at(firstHead)[1];
        float afterSignature=bar.left+gap*1.15f;
        float beforeAttack=Math.min(firstHead-gap*.75f,firstShaftLeft-gap*.2f);
        return beforeAttack-afterSignature>=gap*.8f
                && staffRules(gray,width,height,staff,Math.round(afterSignature))==5
                && staffRules(gray,width,height,staff,Math.round(beforeAttack))==5
                && clearOfVerticalInk(gray,width,height,staff,afterSignature,beforeAttack)
                && unownedClosedEdge(gray,width,height,staff,bar.right,lastHead,false,gap);
    }

    static boolean confirmsShaftBoundedInitialStaffBar(
            byte[] gray,
            int width,
            int height,
            List<MeasureRegion> regions,
            int index,
            float firstHead,
            int firstShaftLeft) {
        return confirmsShaftBoundedInitialStaffBar(
                gray, width, height, regions, index, firstHead, firstShaftLeft, null, .5f);
    }

    static boolean confirmsWideShaftBoundedOpening(byte[] gray, int width, int height,
            List<MeasureRegion> regions, int index, float firstHead, int firstShaftLeft) {
        return confirmsShaftBoundedInitialStaffBar(
                gray, width, height, regions, index, firstHead, firstShaftLeft, null, .70f);
    }

    static boolean confirmsCompleteShaftBoundedInitialStaffBar(
            byte[] gray,
            int width,
            int height,
            List<MeasureRegion> regions,
            int index,
            float firstHead,
            float lastHead,
            int firstShaftLeft,
            StaffPitchTrack staff) {
        return staff.verified()
                && confirmsShaftBoundedInitialStaffBar(
                        gray, width, height, regions, index, firstHead, firstShaftLeft, staff, .5f)
                && confirmsInitialStaffBarClosingEdge(
                        gray, width, height, box(regions.get(index), width, height), lastHead, staff);
    }

    private static boolean confirmsShaftBoundedInitialStaffBar(
            byte[] gray,
            int width,
            int height,
            List<MeasureRegion> regions,
            int index,
            float firstHead,
            int firstShaftLeft,
            StaffPitchTrack verifiedStaff,
            float leadSample) {
        if (index < 1 || index + 1 >= regions.size()) return false;
        var bar = box(regions.get(index), width, height);
        var before = box(regions.get(index - 1), width, height);
        var after = box(regions.get(index + 1), width, height);
        if (bar == null || before == null || after == null) return false;
        float guess = bar.height() / 8.2f;
        if (guess < 10
                || guess > 25
                || bar.top - before.bottom < guess * 3
                || !sameRow(bar, after, guess)
                || !boundaryPocket(bar.right, after.left, guess)
                || firstHead - bar.left < guess * 2) return false;
        var staff = verifiedStaff == null ? track(gray, width, height, bar) : verifiedStaff;
        if (staff == null) return false;
        float gap = staff.at(firstHead)[1];
        float firstInk = firstShaftLeft;
        float left = bar.left - gap * leadSample, right = firstInk - gap * .15f;
        if (firstHead - firstInk < gap * .35f
                || firstHead - firstInk > gap * .85f
                || right - left < gap * 1.8f
                || !clearOfVerticalInk(gray, width, height, staff, left, right)) return false;
        for (float x : new float[] {left, bar.left, right})
            if (staffRules(gray, width, height, staff, Math.round(x)) != 5) return false;
        // The incoming five rules must visibly start before the cropped key slot.
        int end = Math.round(bar.left - gap * 1.25f);
        int nearLimit = Math.max(0, Math.round(bar.left - 7 * gap));
        int wideLimit = Math.max(0, Math.round(bar.left - 9 * gap));
        for (int x = end; x >= wideLimit; x--) {
            if (staffRules(gray, width, height, staff, x) != 5
                    || staffRules(gray, width, height, staff, Math.round(x - gap * .65f)) != 0)
                continue;
            // A wide key header can put the opening rule farther from the note crop.
            // Check the incoming five rules again inside the header before accepting it.
            if (x >= nearLimit || staffRules(gray, width, height, staff, Math.round(x + gap)) == 5
                    && staffRules(gray, width, height, staff, Math.round(x + 3 * gap)) == 5)
                return true;
        }
        return false;
    }

    /** A printed header and an incoming staff edge can close the first bar's left side. */
    static boolean confirmsOpeningTripletBar(
            byte[] gray, int width, int height, List<MeasureRegion> regions,
            float firstHead, float lastHead, boolean provedTerminalGroup) {
        return confirmsOpeningTripletBar(gray, width, height, regions, firstHead, lastHead,
                provedTerminalGroup, false);
    }

    static boolean confirmsOpeningTripletBar(
            byte[] gray, int width, int height, List<MeasureRegion> regions,
            float firstHead, float lastHead, boolean provedTerminalGroup, boolean wideHeader) {
        if (regions.size() < 2) return false;
        var opening = box(regions.get(0), width, height);
        var following = box(regions.get(1), width, height);
        if (opening == null || following == null) return false;
        var staff = track(gray, width, height, opening);
        if (staff == null) return false;
        float gap = staff.at(firstHead)[1];
        float inset = firstHead - opening.left;
        if (inset < gap * 2.5f || inset > gap * 7
                || !sameRow(opening, following, gap)
                || !boundaryPocket(opening.right, following.left, gap)) return false;

        // The notes must follow a clear ruled gap after the header.
        float afterHeader = firstHead - gap * 1.65f;
        float beforeHead = firstHead - gap * .7f;
        if (staffRules(gray, width, height, staff, Math.round(afterHeader)) != 5
                || staffRules(gray, width, height, staff, Math.round(beforeHead)) != 5
                || !clearOfVerticalInk(gray, width, height, staff, afterHeader, beforeHead)
                || !(confirmsInitialStaffBarClosingEdge(gray, width, height, regions.get(0), lastHead)
                        || provedTerminalGroup && closeRuleInShortOpeningMargin(
                                gray, width, height, staff, opening, following, lastHead, gap)))
            return false;

        int margin = Math.max(2, Math.round(gap * .65f));
        int limit = Math.max(margin, Math.round(opening.left - gap * (wideHeader ? 16 : 10)));
        for (int x = Math.round(opening.left - gap); x >= limit; x--) {
            if (staffRules(gray, width, height, staff, x) == 5
                    && staffRules(gray, width, height, staff, x - margin) == 0) return true;
        }
        return false;
    }

    /** A literal 4/4 and an independent right rule bound the first bar of a system. */
    static boolean confirmsOpeningFourFourWithVerifiedStaff(
            byte[] gray,
            int width,
            int height,
            List<MeasureRegion> regions,
            int index,
            float firstHead,
            float lastHead,
            StaffPitchTrack staff) {
        return confirmsOpeningMeterWithVerifiedStaff(gray, width, height, regions, index,
                firstHead, lastHead, staff, "4/4");
    }

    static boolean confirmsOpeningMeterWithVerifiedStaff(
            byte[] gray, int width, int height, List<MeasureRegion> regions, int index,
            float firstHead, float lastHead, StaffPitchTrack staff, String meter) {
        if (index < 1 || index + 1 >= regions.size()) return false;
        var previous = box(regions.get(index - 1), width, height);
        var start = box(regions.get(index), width, height);
        var following = box(regions.get(index + 1), width, height);
        if (previous == null || start == null || following == null) return false;
        float gap = staff.at(firstHead)[1];
        float inset = firstHead - start.left;
        if (gap < 10 || gap > 26
                || start.top - previous.bottom < gap * 3
                || inset < gap * 2f || inset > gap * 9f
                || !sameRow(start, following, gap)
                || !boundaryPocket(start.right, following.left, gap)
                || !confirmsInitialStaffBarClosingEdge(gray, width, height, start, lastHead, staff))
            return false;

        // A key signature can precede the meter on the same system.
        int left = Math.round(inset <= gap * 3.8f ? start.left - gap * 1.55f : firstHead - gap * 4.3f);
        int right = Math.round(firstHead - gap * .85f);
        var frame = staff.at((left + right) * .5f);
        int top = Math.round(frame[0] - frame[1] * 4);
        int below = Math.round(frame[0] + gap * .2f);
        if (left < 0 || right >= width || top < 4 || below >= height) return false;
        var crop = new MeterChangeDetector.Crop(left, top - 3, right, below, top, frame[1]);
        var glyph = MeterFontMatcher.readCrop(gray, width, height, crop);
        return glyph.text().equals(meter) && glyph.topScore() >= .84f && glyph.bottomScore() >= .84f;
    }

    private static boolean closeRuleInShortOpeningMargin(
            byte[] gray, int width, int height, StaffPitchTrack staff,
            Box opening, Box following, float lastHead, float gap) {
        int left = Math.round(Math.max(lastHead + gap * .95f, opening.right - gap * .25f));
        int right = Math.round(Math.min(following.left - gap * .25f, lastHead + gap * 1.3f));
        int first = -1, last = -1;
        for (int x = left; x <= right; x++) {
            if (verticalCoverage(gray, width, height, staff, x) < .92f) continue;
            if ((first >= 0 && x != last + 1)
                    || !independentRuleColumn(gray, width, height, staff, x, gap, .98f)) return false;
            if (first < 0) first = x;
            last = x;
        }
        int stroke = last - first + 1;
        return first >= 0 && stroke >= 3 && stroke <= Math.round(gap * .35f);
    }

    static boolean confirmsInitialStaffBarClosingEdge(
            byte[] gray, int width, int height, MeasureRegion region, float lastHead) {
        var bar = box(region, width, height);
        if (bar == null) return false;
        var staff = track(gray, width, height, bar);
        return staff != null && confirmsInitialStaffBarClosingEdge(
                gray, width, height, bar, lastHead, staff);
    }

    static boolean confirmsInitialStaffBarClosingEdge(
            byte[] gray, int width, int height, MeasureRegion region, float lastHead, StaffPitchTrack staff) {
        var bar = box(region, width, height);
        return bar != null && staff != null
                && confirmsInitialStaffBarClosingEdge(gray, width, height, bar, lastHead, staff);
    }

    /** A crowded rail run can leave five independent samples only around its closing divider. */
    static boolean confirmsSampledClosingEdge(
            byte[] gray, int width, int height, MeasureRegion region, float lastHead) {
        var bar = box(region, width, height);
        if (bar == null) return false;
        float gap = bar.height() / 8.2f;
        if (gap < 10 || gap > 25) return false;
        float left = bar.right - gap * .40f, right = bar.right + gap * .92f;
        float bottom = bar.bottom - gap * 2.1f;
        var a = closingPocketRules(gray, width, height, left, bottom, gap);
        var b = closingPocketRules(gray, width, height, right, bottom, gap);
        if (a == null || b == null || Math.abs(a[0] - b[0]) > gap * .24f
                || Math.abs(a[1] - b[1]) > gap * .12f) return false;
        var staff = StaffPitchTrack.fromVerifiedSamples(
                List.of(new float[] {left, a[0], a[1]}, new float[] {right, b[0], b[1]}));
        return confirmsInitialStaffBarClosingEdge(gray, width, height, bar, lastHead, staff);
    }

    /** Sample both sides of an interior physical separator when a packed measure defeats the full track. */
    static boolean confirmsSampledTripletBar(byte[] ink, int width, int height,
            MeasureRegion preceding, MeasureRegion region, float previous, float first, float last) {
        var prior = box(preceding, width, height);
        var bar = box(region, width, height);
        if (prior == null || bar == null) return false;
        float gap = bar.height() / 8.2f, separation = bar.left - prior.right;
        if (gap < 10 || gap > 25 || !sameRow(prior, bar, gap)
                || separation < .30f * gap || separation > .85f * gap
                || !confirmsSampledClosingEdge(ink, width, height, region, last)) return false;
        float middle = (prior.right + bar.left) * .5f;
        float left = middle - .85f * gap, right = middle + .85f * gap;
        if (middle - previous < 1.3f * gap || first - middle < 1.3f * gap) return false;
        float bottom = bar.bottom - 2.1f * gap;
        var a = closingPocketRules(ink, width, height, left, bottom, gap);
        var b = closingPocketRules(ink, width, height, right, bottom, gap);
        if (a == null || b == null || Math.abs(a[0] - b[0]) > .22f * gap
                || Math.abs(a[1] - b[1]) > .12f * gap) return false;
        var staff = StaffPitchTrack.fromVerifiedSamples(
                List.of(new float[] {left, a[0], a[1]}, new float[] {right, b[0], b[1]}));
        int from = Math.round(prior.right - .18f * gap), to = Math.round(bar.left + .18f * gap);
        int firstRule = -1, lastRule = -1;
        for (int x = from; x <= to; x++) {
            if (!independentRuleColumn(ink, width, height, staff, x, gap, .82f)) continue;
            if (lastRule >= 0 && x != lastRule + 1) return false;
            if (firstRule < 0) firstRule = x;
            lastRule = x;
        }
        return firstRule >= 0 && lastRule - firstRule + 1 >= Math.max(2, Math.round(.15f * gap))
                && lastRule - firstRule + 1 <= Math.round(.51f * gap);
    }


    private static float[] closingPocketRules(
            byte[] gray, int width, int height, float x, float bottom, float gap) {
        int left = Math.round(x - gap * .20f), right = Math.round(x + gap * .20f);
        if (left < 0 || right >= width) return null;
        float[] centers = new float[5];
        int margin = Math.round(gap * .28f);
        for (int line = 0; line < 5; line++) {
            int center = Math.round(bottom - line * gap);
            if (center - margin < 0 || center + margin >= height) return null;
            int first = -1, last = -1;
            for (int y = center - margin; y <= center + margin; y++) {
                int observed = 0;
                for (int col = left; col <= right; col++)
                    if ((gray[y * width + col] & 255) < 155) observed++;
                if (observed < (right - left + 1) * .86f) continue;
                if (last >= 0 && y != last + 1) return null;
                if (first < 0) first = y;
                last = y;
            }
            if (first < 0 || last - first + 1 > gap * .27f) return null;
            centers[line] = (first + last) * .5f;
        }
        float actual = (centers[0] - centers[4]) * .25f;
        if (Math.abs(actual - gap) > gap * .12f) return null;
        for (int i = 1; i < 4; i++)
            if (Math.abs(centers[i] - (centers[0] - i * actual)) > gap * .12f) return null;
        return new float[] {centers[0], actual};
    }

    private static boolean confirmsInitialStaffBarClosingEdge(
            byte[] gray, int width, int height, Box bar, float lastHead, StaffPitchTrack staff) {
        float gap = staff.at(lastHead)[1];
        if (!unownedClosedEdge(gray, width, height, staff, bar.right, lastHead, false, gap))
            return false;
        int first = Math.max(0, Math.round(bar.right - gap * .25f));
        int last = Math.min(width - 1, Math.round(bar.right + gap * 1.35f));
        int run = 0, independent = 0;
        for (int x = first; x <= last + 1; x++) {
            if (x <= last && verticalCoverage(gray, width, height, staff, x) >= .92f) run++;
            else {
                if (run >= Math.max(2, Math.round(gap * .12f))) independent++;
                run = 0;
            }
        }
        return independent == 1;
    }

    private static boolean unownedClosedEdge(byte[] gray,int width,int height,StaffPitchTrack staff,
            float edge,float nearestHead,boolean opening,float gap) {
        return unownedClosedEdge(gray, width, height, staff, edge, nearestHead, opening, gap, 1.30f);
    }

    private static boolean unownedClosedEdge(byte[] gray,int width,int height,StaffPitchTrack staff,
            float edge,float nearestHead,boolean opening,float gap,float minimumClearance) {
        int left=Math.max(0,Math.round(edge-gap*(opening ? .85f : .25f)));
        int right=Math.min(width-1,Math.round(edge+gap*(opening ? .25f : .85f)));
        int consecutive=0;
        for (int x=left;x<=right;x++) {
            if ((nearestHead-x)*(opening?1:-1)<gap*minimumClearance
                    || !independentRuleColumn(gray,width,height,staff,x,gap,.92f)) {
                consecutive=0;
                continue;
            }
            if (++consecutive>=Math.max(2,Math.round(gap*.15f))) return true;
        }
        return false;
    }

    private static boolean independentRuleColumn(
            byte[] gray, int width, int height, StaffPitchTrack staff,
            int x, float gap, float minimumCoverage) {
        var frame = staff.at(x);
        int top = Math.round(frame[0] - 4 * frame[1]), bottom = Math.round(frame[0]);
        return x >= 0 && x < width && top >= 0 && bottom < height
                && verticalCoverage(gray, width, height, staff, x) >= minimumCoverage
                && !continuedColumn(gray, width, height, x, top, -1, gap)
                && !continuedColumn(gray, width, height, x, bottom, 1, gap)
                && !DottedDyadMeasureSeparator.continuesBeyondStaff(gray, width, height, x, top, bottom, gap)
                && !ClosedHeadBarlineGuard.attached(gray, width, height, x, top, bottom, gap);
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

    static StaffPitchTrack closedTripletStaff(
            byte[] gray, int width, int height, MeasureRegion region) {
        var bounds = box(region, width, height);
        return bounds == null ? null : track(gray, width, height, bounds);
    }

    private static StaffPitchTrack track(byte[] gray, int width, int height, Box box) {
        return track(gray, width, height, box, false);
    }

    private static StaffPitchTrack track(byte[] gray, int width, int height, Box box, boolean nearby) {
        float gap = box.height() / 8.2f, hint = box.bottom - 2.1f * gap;
        var samples = new ArrayList<float[]>();
        for (int i = 0; i <= 8; i++) {
            float x = box.left + (box.right - box.left) * i / 8;
            var rules = PrintedDirectionStaff.printedRules(gray, width, height, x, hint, gap);
            if (nearby && !frameNearHint(rules, hint, gap)) {
                for (float offset : new float[] {-.45f, .45f, -.78f, .78f}) {
                    float next = x + gap * offset;
                    if (next < box.left || next > box.right) continue;
                    rules = PrintedDirectionStaff.printedRules(gray, width, height, next, hint, gap);
                    if (frameNearHint(rules, hint, gap)) {
                        x = next;
                        break;
                    }
                }
            }
            if (frameNearHint(rules, hint, gap))
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


    /** Read the five rules in the clear pockets beside a crowded beam passage. */
    static StaffPitchTrack verifiedStaffPockets(byte[] ink, int width, int height,
            MeasureRegion bar, boolean insetRight) {
        float left = bar.left() * width, right = bar.right() * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25) return null;
        float bottom = bar.bottom() * height - 2.1f * gap;
        var start = staffPocket(ink, width, height, left, bottom, gap);
        var end = staffPocket(ink, width, height, right, bottom, gap);
        if (end == null && insetRight)
            end = staffPocket(ink, width, height, right - gap * .65f, bottom, gap);
        if (start == null || end == null || Math.abs(start[1] - end[1]) > gap * .40f
                || Math.abs(start[2] - end[2]) > gap * .10f) return null;
        return StaffPitchTrack.fromVerifiedSamples(List.of(start, end));
    }

    private static float[] staffPocket(byte[] ink, int width, int height, float x,
            float hint, float gap) {
        int radius = Math.round(gap * .52f), band = Math.round(gap * .24f);
        int left = Math.round(x) - radius, right = Math.round(x) + radius;
        if (left < 0 || right >= width) return null;
        float[] centres = new float[5];
        for (int rule = 0; rule < 5; rule++) {
            int mid = Math.round(hint - gap * rule), first = -1, last = -1;
            if (mid - band < 0 || mid + band >= height) return null;
            for (int y = mid - band; y <= mid + band; y++) {
                int dark = 0;
                for (int col = left; col <= right; col++)
                    if ((ink[y * width + col] & 255) < 155) dark++;
                if (dark < (right - left + 1) * .86f) continue;
                if (first < 0) first = y;
                if (last >= 0 && y != last + 1) return null;
                last = y;
            }
            if (first < 0 || last - first + 1 > gap * .36f) return null;
            centres[rule] = (first + last) * .5f;
        }
        float fitted = (centres[0] - centres[4]) / 4;
        if (Math.abs(fitted - gap) > gap * .10f) return null;
        for (int i = 1; i < 4; i++)
            if (Math.abs(centres[i] - (centres[0] - i * fitted)) > gap * .12f) return null;
        return new float[] {x, centres[0], fitted};
    }

    private static boolean frameNearHint(float[] rules, float hint, float gap) {
        return rules != null && Math.abs(rules[1] - gap) <= .15f * gap
                && Math.abs(rules[0] - hint) <= .65f * gap;
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

    private static boolean inheritedFlatHeader(
            byte[] gray,
            byte[] labels,
            int width,
            int height,
            StaffPitchTrack staff,
            float cropLeft,
            float firstHead,
            float gap,
            int inheritedKey,
            float blankGaps) {
        if (labels == null
                || labels.length != gray.length
                || inheritedKey >= 0
                || inheritedKey < -7) return false;
        int paperRight = Math.round(firstHead - gap * .95f);
        int paperLeft = paperRight;
        while (paperLeft > cropLeft
                && staffRules(gray, width, height, staff, paperLeft) == 5
                && verticalCoverage(gray, width, height, staff, paperLeft) <= .35f) paperLeft--;
        if (paperRight - paperLeft < gap * blankGaps || paperLeft <= cropLeft) {
            // A faint rail may drop out in an otherwise clear interval after the key.
            // The independently authenticated clef and full flat signature below remain required.
            paperLeft = paperRight;
            int floor = Math.round(cropLeft - gap * .45f);
            while (paperLeft > floor
                    && staffRules(gray, width, height, staff, paperLeft) >= 4
                    && verticalCoverage(gray, width, height, staff, paperLeft) <= .23f) paperLeft--;
            if (paperRight - paperLeft < gap * blankGaps) return false;
        }

        // Locate the incoming staff edge before the partial signature in the decoder box.
        int limit = Math.max(0, Math.round(cropLeft - gap * 10));
        int margin = Math.max(2, Math.round(gap * .65f));
        for (int x = Math.round(cropLeft - gap * 1.25f); x >= limit + margin; x--) {
            if (staffRules(gray, width, height, staff, x) != 5
                    || staffRules(gray, width, height, staff, x - margin) != 0) continue;
            var frame = staff.at(x);
            int flats =
                    OmrScoreInterpreter.inheritedFlatHeaderStart(
                            gray,
                            labels,
                            width,
                            height,
                            x,
                            paperLeft,
                            frame[0],
                            frame[1],
                            -inheritedKey);
            return flats >= 0
                    && !hasBoundary(
                            gray,
                            width,
                            height,
                            staff,
                            flats + gap * .25f,
                            firstHead - gap * .85f,
                            gap);
        }
        return false;
    }

    private static boolean inheritedSharpHeader(
            byte[] gray,
            byte[] labels,
            int width,
            int height,
            StaffPitchTrack staff,
            float cropLeft,
            float firstHead,
            float gap,
            int inheritedKey) {
        if (labels == null
                || labels.length != gray.length
                || inheritedKey < 2
                || inheritedKey > 7) return false;
        int paperRight = Math.round(firstHead - gap * .95f);
        int paperLeft = paperRight;
        while (paperLeft > cropLeft - gap
                && staffRules(gray, width, height, staff, paperLeft) == 5
                && verticalCoverage(gray, width, height, staff, paperLeft) <= .35f) paperLeft--;
        if (paperRight - paperLeft < gap * 1.5f || paperLeft <= cropLeft - gap) return false;

        // Locate the incoming staff edge before the partial signature in the decoder box.
        int limit = Math.max(0, Math.round(cropLeft - gap * 12));
        int margin = Math.max(2, Math.round(gap * .65f));
        for (int x = Math.round(cropLeft - gap * 1.25f); x >= limit + margin; x--) {
            if (staffRules(gray, width, height, staff, x) != 5
                    || staffRules(gray, width, height, staff, x - margin) != 0) continue;
            var frame = staff.at(x);
            int signature =
                    OmrScoreInterpreter.inheritedSharpHeaderStart(
                            gray,
                            labels,
                            width,
                            height,
                            x,
                            paperLeft,
                            frame[0],
                            frame[1],
                            inheritedKey);
            return signature >= 0
                    && !hasBoundary(
                            gray,
                            width,
                            height,
                            staff,
                            signature + gap * .25f,
                            firstHead - gap * .85f,
                            gap);
        }
        return false;
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
