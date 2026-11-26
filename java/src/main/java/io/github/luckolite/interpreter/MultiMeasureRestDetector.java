// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
// Adapted from Music Sheets: standalone package and platform-independent diagnostics.
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Recognizes printed rest counts such as the 4 above a multi-measure rest. */
final class MultiMeasureRestDetector {
    private MultiMeasureRestDetector() {}

    record RestBarCandidate(int measureIndex, MeasureRegion region, boolean compoundOpening) {
        RestBarCandidate(int measureIndex, MeasureRegion region) {
            this(measureIndex, region, false);
        }
    }

    static List<MeasureNumberReconciler.NumberToken> detect(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            List<MeasureRegion> measures,
            List<MeasureNumberReconciler.NumberToken> tokens) {
        return detect(labels, gray, width, height, measures, tokens, null);
    }

    static List<MeasureNumberReconciler.NumberToken> detect(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            List<MeasureRegion> measures,
            List<MeasureNumberReconciler.NumberToken> tokens,
            RecognizedMultiRestOrigins origins) {
        if (origins != null) origins.begin(labels, gray, width, height, measures);
        if (labels == null
                || gray == null
                || width <= 0
                || height <= 0
                || labels.length != width * height
                || gray.length != labels.length
                || measures == null
                || measures.isEmpty()
                || tokens == null) return List.of();
        List<RestBarCandidate> restBars =
                candidateRestBars(labels, gray, width, height, measures, tokens);
        if (restBars.isEmpty()) return List.of();

        List<MeasureNumberReconciler.NumberToken> readings = new ArrayList<>(tokens);
        for (RestBarCandidate candidate : restBars) {
            var count = standaloneCount(gray, width, height, candidate, true);
            if (count != null
                    && aboveStaff(count, labels, gray, width, height, candidate.region())) {
                // OCR can invert a nine into six. Only the independently proved upper-bowl
                // glyph at the same location may replace that single-digit reading.
                if (count.value() == 9)
                    readings.removeIf(token -> token.value() == 6 && sameCountGlyph(token, count));
                // The centered printed count can disqualify a separate off-center OCR token.
                if (centeredCount(count, candidate.region()))
                    readings.removeIf(token -> token.value() >= 2 && token.value() <= 32
                            && token.value() != count.value()
                            && !centeredCount(token, candidate.region())
                            && (token.left() > count.right() || token.right() < count.left())
                            && containingRestBar(restBars, measures,
                                    (token.left() + token.right()) * .5f,
                                    (token.top() + token.bottom()) * .5f, null) == candidate.measureIndex()
                            && hasCountTextInk(token, gray, width, height));
                readings.add(count);
            }
        }
        List<MeasureNumberReconciler.NumberToken> result = new ArrayList<>();
        boolean[] claimedMeasures = new boolean[measures.size()];
        for (MeasureNumberReconciler.NumberToken token : readings) {
            // Counts above multi-measure rests are normally 2-32. Larger values are overwhelmingly
            // likely to be a tempo, copyright year, or printed system number.
            if (token.value() < 2 || token.value() > 32) continue;
            float centerX = (token.left() + token.right()) * .5f;
            float centerY = (token.top() + token.bottom()) * .5f;
            RestBarCandidate[] matched = new RestBarCandidate[1];
            int measureIndex = containingRestBar(restBars, measures, centerX, centerY, matched);
            if (measureIndex < 0 || claimedMeasures[measureIndex]
                    || matched[0].compoundOpening() && token.value() != 3) continue;
            // The short-bar fallback can share an opening region with a meter. Its count must
            // be above that staff; preserve the existing caller-OCR contract for long bars.
            if (cappedRestWindow(gray, width, height, measures.get(measureIndex)) != null
                    && !aboveStaff(token, labels, gray, width, height, measures.get(measureIndex)))
                continue;
            claimedMeasures[measureIndex] = true;
            result.add(token);
            if (origins != null) origins.admitted(token, matched[0], measureIndex);
        }
        return List.copyOf(result);
    }

    private static boolean hasCountTextInk(
            MeasureNumberReconciler.NumberToken token, byte[] gray, int width, int height) {
        int left = clamp(Math.round(token.left() * width), 0, width);
        int right = clamp(Math.round(token.right() * width), left, width);
        int top = clamp(Math.round(token.top() * height), 0, height);
        int bottom = clamp(Math.round(token.bottom() * height), top, height);
        int inkRows = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                if ((gray[y * width + x] & 255) > 165) continue;
                if (++inkRows == 3) return true;
                break;
            }
        }
        return false;
    }

    private static boolean sameCountGlyph(
            MeasureNumberReconciler.NumberToken ocr, MeasureNumberReconciler.NumberToken shape) {
        float w = shape.right() - shape.left(), h = shape.bottom() - shape.top();
        float overlapW = Math.min(ocr.right(), shape.right()) - Math.max(ocr.left(), shape.left());
        float overlapH = Math.min(ocr.bottom(), shape.bottom()) - Math.max(ocr.top(), shape.top());
        return w > 0
                && h > 0
                && overlapW >= w * .9f
                && overlapH >= h * .9f
                && ocr.right() - ocr.left() <= w * 1.8f
                && ocr.bottom() - ocr.top() <= h * 1.8f;
    }

    /** Returns note-free windows containing the heavy bar used for a multi-rest. */
    static List<RestBarCandidate> candidateRestBars(
            byte[] labels, byte[] gray, int width, int height, List<MeasureRegion> measures) {
        return candidateRestBars(labels, gray, width, height, measures, List.of());
    }

    private static List<RestBarCandidate> candidateRestBars(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            List<MeasureRegion> measures,
            List<MeasureNumberReconciler.NumberToken> tokens) {
        if (labels == null
                || gray == null
                || width <= 0
                || height <= 0
                || labels.length != width * height
                || gray.length != labels.length
                || measures == null
                || measures.isEmpty()) return List.of();
        labels = PrintedRestBarHeads.withoutBars(labels, gray, width, height);
        int[] noteheadPixels = new int[measures.size()];
        List<MeasureRegion> noteRegions = new ArrayList<>();
        List<Integer> writtenCounts = new ArrayList<>();
        for (int index = 0; index < measures.size(); index++) {
            MeasureRegion parent = measures.get(index);
            float extent = parent.bottom() - parent.top();
            float top = Math.max(0, parent.top() - extent * .85f);
            float bottom = Math.min(1, parent.bottom() + extent * .85f);
            // Ledger heads belong to the written measure even when its region
            // contains only the staff. Stop at the gap to an adjacent staff.
            for (MeasureRegion other : measures) {
                if (other.right() <= parent.left() || other.left() >= parent.right()) continue;
                if (other.bottom() < parent.top())
                    top = Math.max(top, (other.bottom() + parent.top()) * .5f);
                if (other.top() > parent.bottom())
                    bottom = Math.min(bottom, (other.top() + parent.bottom()) * .5f);
            }
            MeasureRegion noteRegion =
                    new MeasureRegion(parent.left(), parent.right(), top, bottom);
            noteRegions.add(noteRegion);
            noteheadPixels[index] =
                    countLabel(labels, width, height, noteRegion, OmrMeasurePostProcessor.NOTEHEAD);
            if (noteheadPixels[index] > 0) writtenCounts.add(noteheadPixels[index]);
        }
        writtenCounts.sort(Integer::compare);
        int ordinaryWrittenInk =
                writtenCounts.isEmpty() ? 0 : writtenCounts.get(writtenCounts.size() / 2);
        int noteFreeLimit = Math.max(6, ordinaryWrittenInk / 14);
        List<RestBarCandidate> result = new ArrayList<>();
        OmrScoreInterpreter.RestLedgerEvidence ledgerEvidence = null;
        List<RawStaffLineDetector.StaffLines> rawStaffs = null;
        measuresLoop:
        for (int index = 0; index < measures.size(); index++) {
            MeasureRegion parent = measures.get(index);
            boolean wideRestRegion = (parent.right() - parent.left()) * width
                    >= (parent.bottom() - parent.top()) * height * (12f / 8.2f);
            for (var count : tokens) {
                if (index == 0 && count.value() == 3
                        && compoundOpening(gray, width, height, parent, count)) {
                    result.add(new RestBarCandidate(index, parent, true));
                    continue measuresLoop;
                }
                boolean blockCount = count.value() == 2 || count.value() == 4;
                if (count.value() < 2 || count.value() > 32 || !centeredCount(count, parent)
                        || (!blockCount && !wideRestRegion)) continue;
                if (rawStaffs == null) rawStaffs = RawStaffLineDetector.detect(gray, width, height);
                boolean blockRest = blockCount && printedBlockRest(labels, gray, width, height, parent,
                        noteRegions.get(index), noteheadPixels[index], noteFreeLimit, count, rawStaffs);
                boolean wideRest = !blockRest && wideRestRegion
                        && noteheadPixels[index] - countGlyphHeads(count, labels, width, height,
                                noteRegions.get(index)) <= noteFreeLimit
                        && wideCappedRest(labels, gray, width, height, parent, count, rawStaffs);
                if (blockRest || wideRest) {
                    result.add(new RestBarCandidate(index, parent));
                    continue measuresLoop;
                }
            }
            // Segmentation can classify a printed rest count as a notehead. Exclude
            // only that verified glyph above the staff, never other heads in the measure.
            MeasureNumberReconciler.NumberToken countGlyph = null;
            if (noteheadPixels[index] > noteFreeLimit
                    && hasThickHorizontalBar(gray, width, height, parent)) {
                MeasureNumberReconciler.NumberToken count =
                        standaloneCount(
                                gray, width, height, new RestBarCandidate(index, parent), true);
                if (count != null && aboveStaff(count, labels, gray, width, height, parent))
                    countGlyph = count;
                // The shape-only fallback reads single digits. A complete OCR
                // count can also explain mislabeled ink above a multi-rest,
                // but never an actual note elsewhere in the bar.
                for (var token : tokens)
                    if (token.value() >= 2
                            && token.value() <= 32
                            && token.left() >= parent.left()
                            && token.right() <= parent.right()
                            && aboveStaff(token, labels, gray, width, height, parent)
                            && (countGlyph == null
                                    || countGlyphHeads(
                                                    token,
                                                    labels,
                                                    width,
                                                    height,
                                                    noteRegions.get(index))
                                            > countGlyphHeads(
                                                    countGlyph,
                                                    labels,
                                                    width,
                                                    height,
                                                    noteRegions.get(index)))) countGlyph = token;
            }
            // A real multi-measure rest owns an otherwise note-free visual measure. Looking only
            // at the sliding window is not enough: an ordinary written measure can contain a
            // long beam or staff-line segment in one locally empty window, and a nearby time
            // signature/OCR digit then expands that measure several times. Require the complete
            // parent measure to be note-free before treating any local heavy bar as a rest.
            int unsupportedLedgerHeads = 0;
            if (countGlyph != null) {
                if (ledgerEvidence == null)
                    ledgerEvidence =
                            new OmrScoreInterpreter.RestLedgerEvidence(
                                    labels, gray, width, height, measures);
                unsupportedLedgerHeads =
                        ledgerEvidence.unsupportedHeads(parent, noteRegions.get(index), countGlyph);
            }
            if (noteheadPixels[index]
                            - unsupportedLedgerHeads
                            - countGlyphHeads(
                                    countGlyph, labels, width, height, noteRegions.get(index))
                    > noteFreeLimit) {
                var possibleCounts = nearbyRestCounts(parent, tokens);
                if (possibleCounts.size() < 2
                        || !hasThickHorizontalBar(gray, width, height, parent)) continue;
                if (rawStaffs == null) rawStaffs = RawStaffLineDetector.detect(gray, width, height);
                var shared = sharedStaffRest(labels, gray, width, height, parent, noteRegions.get(index),
                        noteheadPixels[index], noteFreeLimit, possibleCounts, rawStaffs);
                if (shared != null) result.add(new RestBarCandidate(index, shared));
                continue;
            }
            int parentLeft = clamp(Math.round(parent.left() * width), 0, width - 1);
            int parentRight = clamp(Math.round(parent.right() * width), parentLeft, width - 1);
            int parentTop = clamp(Math.round(parent.top() * height), 0, height - 1);
            int parentBottom = clamp(Math.round(parent.bottom() * height), parentTop, height - 1);
            int parentWidth = parentRight - parentLeft + 1;
            int parentHeight = parentBottom - parentTop + 1;
            int windowWidth = Math.min(parentWidth, Math.max(48, Math.round(parentHeight * 2.4f)));
            int step = Math.max(1, windowWidth / 2);
            for (int windowLeft = parentLeft; windowLeft <= parentRight; windowLeft += step) {
                int windowRight = Math.min(parentRight, windowLeft + windowWidth - 1);
                if (windowRight - windowLeft + 1 < Math.min(60, windowWidth)) break;
                MeasureRegion window =
                        new MeasureRegion(
                                windowLeft / (float) width,
                                windowRight / (float) width,
                                parent.top(),
                                parent.bottom());
                int localHeads =
                        countLabel(labels, width, height, window, OmrMeasurePostProcessor.NOTEHEAD)
                                - countGlyphHeads(countGlyph, labels, width, height, window);
                if (localHeads <= noteFreeLimit
                        && hasThickHorizontalBar(gray, width, height, window)) {
                    MeasureRegion capped = cappedRestWindow(gray, width, height, window);
                    if (capped != null && index > 0 && index == measures.size() - 1
                            && TerminalCompoundHarmonicQuarter.prove(
                                    gray, width, height, measures.get(index - 1), parent,
                                    (capped.left() + capped.right()) * width * .5f) != null)
                        continue;
                    addOrMerge(
                            result, new RestBarCandidate(index, capped != null ? capped : window));
                }
                if (windowRight == parentRight) break;
                if (windowLeft + windowWidth > parentRight)
                    windowLeft = Math.max(windowLeft, parentRight - windowWidth - step);
            }
        }
        return List.copyOf(result);
    }

    private record InkBand(int top, int bottom) {
        int thickness() { return bottom - top + 1; }
        int center() { return (top + bottom) / 2; }
    }

    /** The bar follows the middle rule and has a thin, bilateral cap at each end. */
    private static boolean wideCappedRest(
            byte[] labels, byte[] gray, int width, int height, MeasureRegion parent,
            MeasureNumberReconciler.NumberToken count, List<RawStaffLineDetector.StaffLines> staffs) {
        float gap = (parent.bottom() - parent.top()) * height / 8.2f;
        int left = Math.round(parent.left() * width);
        int right = Math.min(width - 1, Math.round(parent.right() * width));
        if (gap < 7 || gap > 36 || right - left < 12 * gap) return false;
        int x = Math.round((count.left() + count.right()) * .5f * width);
        float hint = parent.bottom() * height - 2.1f * gap;
        var rails = PrintedDirectionStaff.printedRules(gray, width, height, x, hint, gap);
        float bottom;
        if (rails != null) {
            bottom = rails[0];
            gap = rails[1];
        } else {
            RawStaffLineDetector.StaffLines match = null;
            for (var staff : staffs) {
                if (staff.top() < parent.top() * height || staff.bottom() > parent.bottom() * height
                        || Math.abs(staff.bottom() - hint) > .65f * gap
                        || Math.abs(staff.gap() - gap) > .2f * gap) continue;
                if (match != null) return false;
                match = staff;
            }
            if (match == null) return false;
            bottom = match.bottom();
            gap = match.gap();
        }
        int top = Math.round(bottom - 4 * gap);
        if (count.bottom() * height > top + gap * .12f || top - count.bottom() * height > gap
                || !countAboveRule(count, labels, gray, width, height, top)) return false;
        int middle = Math.round(bottom - 2 * gap);
        var ink = inkBand(gray, width, height, x, middle);
        if (ink == null || ink.thickness() < gap * .35f || ink.thickness() > gap * .9f) return false;
        int a = walkRestBar(gray, width, height, x, ink.center(), left, -1, gap);
        int b = walkRestBar(gray, width, height, x, ink.center(), right, 1, gap);
        return a >= 0 && b >= 0 && b - a >= 9 * gap
                && Math.abs((a + b) * .5f - x) <= gap;
    }

    /** A complete staff rule immediately after a long printed H-rest ends the rest's bar. */
    static boolean afterWideRestBar(
            byte[] gray, int width, int height, int x, int[] rows, float gap, float shift) {
        if (gray == null || gap < 7 || gap > 36) return false;
        int start = x - Math.round(gap * 2.1f);
        int middle = Math.round(rows[2] + shift);
        if (start < 0 || x >= width) return false;
        var ink = inkBand(gray, width, height, start, middle);
        if (ink == null || ink.thickness() < gap * .35f || ink.thickness() > gap * .9f) return false;
        int top = Math.max(0, Math.round(rows[0] + shift));
        int bottom = Math.min(height - 1, Math.round(rows[4] + shift));
        if (bottom - top < 3.6f * gap) return false;
        int hits = 0;
        for (int y = top; y <= bottom; y++)
            if ((gray[y * width + x] & 255) <= 150) hits++;
        if (hits < (bottom - top + 1) * .95f) return false;
        int near = walkRestBar(gray, width, height, start, ink.center(), x - 1, 1, gap);
        if (near < 0 || x - near < gap * .35f || x - near > gap * 1.8f) return false;
        int far = walkRestBar(gray, width, height, start, ink.center(),
                Math.max(0, x - Math.round(36 * gap)), -1, gap);
        return far >= 0 && near - far >= 9 * gap;
    }

    private static int walkRestBar(
            byte[] gray, int width, int height, int start, int middle, int limit, int step, float gap) {
        int capColumns = 0, fringeColumns = 0;
        for (int x = start + step; step * (limit - x) >= 0; x += step) {
            var ink = inkBand(gray, width, height, x, middle);
            if (ink == null || ink.thickness() < gap * .35f)
                return capColumns > 0 ? x - step : -1;
            if (ink.thickness() >= gap * 1.5f) {
                if (ink.thickness() > gap * 2.7f || ink.top() > middle - gap * .6f
                        || ink.bottom() < middle + gap * .6f
                        || ++capColumns > Math.max(2, Math.round(gap * .42f))) return -1;
                continue;
            }
            if (capColumns > 0) {
                if (ink.thickness() > gap * .88f && Math.abs(ink.center() - middle) > gap * .22f
                        && ++fringeColumns <= 2) continue;
                return -1;
            }
            if (ink.thickness() > gap * 1.22f
                    || Math.abs(ink.center() - middle) > 2) return -1;
            middle = ink.center();
        }
        return -1;
    }

    private static InkBand inkBand(byte[] gray, int width, int height, int x, int y) {
        if (y < 0 || y >= height || (gray[y * width + x] & 255) > 150) return null;
        int top = y, bottom = y;
        while (top > 0 && (gray[(top - 1) * width + x] & 255) <= 150) top--;
        while (bottom < height - 1 && (gray[(bottom + 1) * width + x] & 255) <= 150) bottom++;
        return new InkBand(top, bottom);
    }

    /** Engraved three-bar silence can use a breve rest and a hanging whole rest. */
    private static boolean compoundOpening(
            byte[] gray, int width, int height, MeasureRegion region,
            MeasureNumberReconciler.NumberToken count) {
        float gap = (region.bottom() - region.top()) * height / 8.2f;
        int left = Math.round(region.left() * width), right = Math.round(region.right() * width);
        int center = Math.round((count.left() + count.right()) * .5f * width);
        if (gap < 8 || gap > 30 || center < left + gap * 2 || center > right - gap * .6f
                || right - left > gap * 9 || (count.right() - count.left()) * width > gap * 3.3f
                || (count.bottom() - count.top()) * height < gap * 1.3f) return false;
        float bottom = region.bottom() * height - 2.1f * gap;
        var rules = PrintedDirectionStaff.printedRules(gray, width, height, center - gap, bottom, gap);
        if (rules == null || Math.abs(rules[1] - gap) > gap * .12f) return false;
        gap = rules[1];
        int firstLine = Math.round(rules[0] - gap * 4);
        int countBottom = Math.round(count.bottom() * height);
        if (countBottom > firstLine + gap * .12f || countBottom < firstLine - gap) return false;

        int from = Math.max(left, Math.round(center - gap * 1.8f));
        int to = Math.min(right, Math.round(center + gap * 1.65f));
        int firstPlate = -1, lastPlate = -1, firstHanging = -1, lastHanging = -1;
        int upper = Math.round(firstLine + gap * 1.16f), lip = Math.round(firstLine + gap * 1.36f);
        int lower = Math.round(firstLine + gap * 1.82f);
        if (from < 0 || to >= width || upper < 0 || lower >= height) return false;
        for (int x = from; x <= to; x++) {
            if (!solidRestColumn(gray, width, x, upper, lip)) continue;
            if (x < center && !solidRestColumn(gray, width, x, lip, lower)) continue;
            if (solidRestColumn(gray, width, x, lip, lower)) {
                if (firstPlate < 0) firstPlate = x;
                if (lastPlate >= 0 && x > lastPlate + 1) return false;
                lastPlate = x;
            } else if (solidRestColumn(gray, width, x, lower - 2, lower)) {
                return false;
            } else {
                if (firstHanging < 0) firstHanging = x;
                if (lastHanging >= 0 && x > lastHanging + 1) return false;
                lastHanging = x;
            }
        }
        if (firstPlate < 0 || firstHanging < 0 || firstHanging - lastPlate < gap * .4f
                || firstHanging - lastPlate > gap * 1.5f
                || Math.abs(center - (firstPlate + lastHanging) * .5f) > gap * .65f
                || !restPlateWidth(firstPlate, lastPlate, gap)
                || !restPlateWidth(firstHanging, lastHanging, gap)) return false;
        int meterLeft = Math.max(left, Math.round(firstPlate - gap * 2.85f));
        int meterRight = Math.round(firstPlate - gap * .5f);
        if (CommonTimeMeter.read(gray, width, height, meterLeft, meterRight, firstLine, gap, 0) != 4)
            return false;

        // Between the meter and the measure edge only the two plates may interrupt the rails.
        for (int space = 0; space < 4; space++) {
            for (int y = Math.round(firstLine + (space + .23f) * gap);
                    y <= Math.round(firstLine + (space + .77f) * gap); y++) {
                for (int x = meterRight + 1; x <= right; x++) {
                    if ((gray[y * width + x] & 255) >= 130) continue;
                    boolean full = space == 1 && x >= firstPlate - 2 && x <= lastPlate + 2;
                    boolean hanging = space == 1 && x >= firstHanging - 2 && x <= lastHanging + 2
                            && y <= firstLine + gap * 1.56f;
                    if (!full && !hanging) return false;
                }
            }
        }
        return true;
    }

    private static boolean solidRestColumn(byte[] gray, int width, int x, int y0, int y1) {
        int ink = 0;
        for (int y = y0; y <= y1; y++) if ((gray[y * width + x] & 255) < 150) ink++;
        return ink >= (y1 - y0 + 1) * .86f;
    }

    private static boolean restPlateWidth(int first, int last, float gap) {
        return last - first + 1 >= gap * .42f && last - first + 1 <= gap * 1.1f;
    }

    private static boolean centeredCount(MeasureNumberReconciler.NumberToken count, MeasureRegion parent) {
        float centerX = (count.left() + count.right()) * .5f;
        float centerY = (count.top() + count.bottom()) * .5f;
        return Math.abs(centerX - (parent.left() + parent.right()) * .5f)
                        <= (parent.right() - parent.left()) * .2f
                && centerY >= parent.top() - (parent.bottom() - parent.top()) * .20f
                && centerY <= parent.bottom();
    }

    /** Old-style two- and four-bar rests fill one or two staff spaces with a solid rectangle. */
    private static boolean printedBlockRest(
            byte[] labels, byte[] gray, int width, int height, MeasureRegion parent,
            MeasureRegion noteRegion, int noteheadPixels, int noteFreeLimit,
            MeasureNumberReconciler.NumberToken count, List<RawStaffLineDetector.StaffLines> staffs) {
        for (var staff : staffs) {
            int[] rows = staff.rows();
            float gap = staff.gap();
            if (staff.top() < parent.top() * height || staff.bottom() > parent.bottom() * height
                    || count.bottom() * height > staff.top() + gap * .12f
                    || staff.top() - count.bottom() * height > gap
                    || !countAboveRule(count, labels, gray, width, height, staff.top())) continue;
            int top = rows[1] + Math.max(2, Math.round(gap * .15f));
            int lastRule = count.value() == 4 ? 3 : 2;
            int bottom = rows[lastRule] - Math.max(2, Math.round(gap * .12f));
            if (bottom - top < 4) continue;
            int center = Math.round((count.left() + count.right()) * .5f * width);
            int left = Math.max(Math.round(parent.left() * width), center - Math.round(gap));
            int right = Math.min(Math.round(parent.right() * width), center + Math.round(gap));
            int coreLeft = right, coreRight = left;
            int minInk = Math.max(2, Math.round(gap * .35f));
            int maxInk = Math.round(gap * .85f);
            boolean rectangle = true;
            for (int y = top; y <= bottom; y++) {
                if (lastRule == 3 && Math.abs(y - rows[2]) <= Math.max(1, Math.round(gap * .12f))) continue;
                int first = right, last = left, ink = 0;
                for (int x = left; x <= right; x++) {
                    if ((gray[y * width + x] & 255) > 150) continue;
                    first = Math.min(first, x);
                    last = x;
                    ink++;
                }
                if (ink < minInk || ink > maxInk || last - first + 1 != ink
                        || first == left || last == right) {
                    rectangle = false;
                    break;
                }
                coreLeft = Math.min(coreLeft, first);
                coreRight = Math.max(coreRight, last);
            }
            if (!rectangle || coreRight - coreLeft + 1 > maxInk
                    || Math.abs((coreLeft + coreRight) * .5f - center) > gap * .25f) continue;
            var glyph = new MeasureRegion(
                    (coreLeft - 1f) / width, (coreRight + 1f) / width,
                    rows[1] / (float) height, rows[lastRule] / (float) height);
            int glyphHeads = countLabel(labels, width, height, glyph, OmrMeasurePostProcessor.NOTEHEAD)
                    + countGlyphHeads(count, labels, width, height, noteRegion);
            if (noteheadPixels - glyphHeads <= noteFreeLimit) return true;
        }
        return false;
    }

    private static List<MeasureNumberReconciler.NumberToken> nearbyRestCounts(
            MeasureRegion parent, List<MeasureNumberReconciler.NumberToken> tokens) {
        float span = parent.right() - parent.left();
        float middle = (parent.left() + parent.right()) * .5f;
        float rangeTop = parent.top() - (parent.bottom() - parent.top()) * .35f;
        return tokens.stream().filter(token -> token.value() >= 2 && token.value() <= 32
                && Math.abs((token.left() + token.right()) * .5f - middle) <= span * .23f
                && (token.top() + token.bottom()) * .5f >= rangeTop
                && (token.top() + token.bottom()) * .5f <= parent.bottom()).toList();
    }

    /** Staff numbering copies can be false noteheads. Each linked staff must prove the same rest. */
    private static MeasureRegion sharedStaffRest(
            byte[] labels, byte[] gray, int width, int height, MeasureRegion parent, MeasureRegion noteRegion,
            int noteheadPixels, int noteFreeLimit, List<MeasureNumberReconciler.NumberToken> counts,
            List<RawStaffLineDetector.StaffLines> staffs) {
        int read = 0, removed = 0, staffCount = 0;
        int firstRow = height;
        MeasureRegion firstRest = null;
        for (var staff : staffs) {
            int[] rows = staff.rows();
            float gap = staff.gap();
            if (rows[0] < parent.top() * height || rows[4] > parent.bottom() * height) continue;
            staffCount++;
            var local = new MeasureRegion(parent.left(), parent.right(),
                    Math.max(parent.top(), (rows[0] - gap) / height),
                    Math.min(parent.bottom(), (rows[4] + gap) / height));
            if (!hasThickHorizontalBar(gray, width, height, local)) return null;
            MeasureNumberReconciler.NumberToken tokenForStaff = null;
            for (var token : counts) {
                float above = rows[0] - (token.top() + token.bottom()) * .5f * height;
                if (above <= 0 || above > 4.5f * gap
                        || !aboveStaff(token, labels, gray, width, height, local)) continue;
                if (tokenForStaff != null && (tokenForStaff.value() != token.value()
                        || !sameCountGlyph(tokenForStaff, token))) return null;
                tokenForStaff = token;
            }
            if (tokenForStaff == null || (read != 0 && tokenForStaff.value() != read)) return null;
            read = tokenForStaff.value();
            removed += countGlyphHeads(tokenForStaff, labels, width, height, noteRegion);
            if (rows[0] < firstRow) {
                firstRow = rows[0];
                firstRest = local;
            }
        }
        return staffCount >= 2 && noteheadPixels - removed <= noteFreeLimit ? firstRest : null;
    }

    static List<Integer> candidateMeasureIndexes(
            byte[] labels, byte[] gray, int width, int height, List<MeasureRegion> measures) {
        List<Integer> result = new ArrayList<>();
        for (RestBarCandidate candidate : candidateRestBars(labels, gray, width, height, measures))
            if (!result.contains(candidate.measureIndex)) result.add(candidate.measureIndex);
        return List.copyOf(result);
    }

    /**
     * ML Kit commonly ignores an isolated one-character OCR crop. Recognize only the distinctive
     * printed 2/3/4/6/7/8/9 shapes above a heavy bar, including mislabeled count ink.
     */
    static MeasureNumberReconciler.NumberToken standaloneCount(
            byte[] gray, int width, int height, RestBarCandidate candidate) {
        return standaloneCount(gray, width, height, candidate, false);
    }

    private static MeasureNumberReconciler.NumberToken standaloneCount(
            byte[] gray, int width, int height, RestBarCandidate candidate, boolean contrastRetry) {
        if (gray == null || gray.length != width * height || candidate == null) return null;
        MeasureRegion region = candidate.region;
        MeasureRegion capped = cappedRestWindow(gray, width, height, region);
        if (capped != null) region = capped;
        int regionTop = clamp(Math.round(region.top() * height), 0, height - 1);
        int regionBottom = clamp(Math.round(region.bottom() * height), regionTop, height - 1);
        int regionHeight = regionBottom - regionTop + 1;
        int left = clamp(Math.round(region.left() * width), 0, width - 1);
        int right = clamp(Math.round(region.right() * width), left, width - 1);
        int top = clamp(Math.round(regionTop - regionHeight * .95f), 0, height - 1);
        int bottom = clamp(Math.round(regionTop + regionHeight * .30f), top, height - 1);
        int localWidth = right - left + 1, localHeight = bottom - top + 1;
        var result = scanCount(gray, width, height, regionHeight, left, right, top, bottom);
        if (result != null || !contrastRetry || !hasThickHorizontalBar(gray, width, height, region))
            return result;
        // A fixed cutoff can flood the bowls on gray paper. Retry only the
        // already-proven heavy rest bar with unchanged shape and isolation tests.
        int[] histogram = new int[256];
        int count = 0;
        for (int y = top; y <= bottom; y++)
            for (int x = left; x <= right; x++) {
                histogram[gray[y * width + x] & 255]++;
                count++;
            }
        int paper = 0, seen = histogram[0];
        while (paper < 255 && seen < count * .85f) seen += histogram[++paper];
        if (paper < 100 || paper >= 200) return null;
        int shift = 200 - paper;
        byte[] contrasted = new byte[localWidth * localHeight];
        for (int y = 0; y < localHeight; y++)
            for (int x = 0; x < localWidth; x++)
                contrasted[y * localWidth + x] =
                        (byte) Math.min(255, (gray[(top + y) * width + left + x] & 255) + shift);
        result =
                scanCount(
                        contrasted,
                        localWidth,
                        localHeight,
                        regionHeight,
                        0,
                        localWidth - 1,
                        0,
                        localHeight - 1);
        return result == null
                ? null
                : new MeasureNumberReconciler.NumberToken(
                        result.value(),
                        (left + result.left() * localWidth) / width,
                        (top + result.top() * localHeight) / height,
                        (left + result.right() * localWidth) / width,
                        (top + result.bottom() * localHeight) / height);
    }

    private static MeasureNumberReconciler.NumberToken scanCount(
            byte[] gray,
            int width,
            int height,
            int regionHeight,
            int left,
            int right,
            int top,
            int bottom) {
        int localWidth = right - left + 1, localHeight = bottom - top + 1;
        boolean[] visited = new boolean[localWidth * localHeight];
        int[] stack = new int[visited.length];
        MeasureNumberReconciler.NumberToken best = null;
        float restCenterX = (left + right) * .5f;
        float bestDistance = Float.MAX_VALUE;
        for (int origin = 0; origin < visited.length; origin++) {
            int originX = origin % localWidth, originY = origin / localWidth;
            if (visited[origin] || (gray[(top + originY) * width + left + originX] & 0xff) > 165)
                continue;
            int stackSize = 0;
            stack[stackSize++] = origin;
            visited[origin] = true;
            int area = 0, minX = right, maxX = left, minY = bottom, maxY = top;
            while (stackSize > 0) {
                int current = stack[--stackSize];
                int localX = current % localWidth, localY = current / localWidth;
                int x = left + localX, y = top + localY;
                area++;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int nextX = localX + dx, nextY = localY + dy;
                        if ((dx == 0 && dy == 0)
                                || nextX < 0
                                || nextX >= localWidth
                                || nextY < 0
                                || nextY >= localHeight) continue;
                        int next = nextY * localWidth + nextX;
                        if (!visited[next]
                                && (gray[(top + nextY) * width + left + nextX] & 0xff) <= 165) {
                            visited[next] = true;
                            stack[stackSize++] = next;
                        }
                    }
            }
            int value =
                    looksLikeSix(gray, width, regionHeight, minX, maxX, minY, maxY)
                            ? 6
                            : looksLikeSeven(
                                            gray, width, regionHeight, area, minX, maxX, minY, maxY)
                                    ? 7
                                    : looksLikeEight(
                                                    gray,
                                                    width,
                                                    regionHeight,
                                                    minX,
                                                    maxX,
                                                    minY,
                                                    maxY)
                                            ? 8
                                            : looksLikeFour(
                                                            gray,
                                                            width,
                                                            regionHeight,
                                                            area,
                                                            minX,
                                                            maxX,
                                                            minY,
                                                            maxY)
                                                    ? (looksLikeNine(
                                                                    gray,
                                                                    width,
                                                                    regionHeight,
                                                                    minX,
                                                                    maxX,
                                                                    minY,
                                                                    maxY)
                                                            ? 9
                                                            : 4)
                                                    : looksLikeNine(
                                                                    gray,
                                                                    width,
                                                                    regionHeight,
                                                                    minX,
                                                                    maxX,
                                                                    minY,
                                                                    maxY)
                                                            ? 9
                                                            : looksLikeThree(
                                                                            gray,
                                                                            width,
                                                                            regionHeight,
                                                                            area,
                                                                            minX,
                                                                            maxX,
                                                                            minY,
                                                                            maxY)
                                                                    ? 3
                                                                    : looksLikeTwo(
                                                                                    gray,
                                                                                    width,
                                                                                    regionHeight,
                                                                                    area,
                                                                                    minX,
                                                                                    maxX,
                                                                                    minY,
                                                                                    maxY)
                                                                            ? 2
                                                                            : 0;
            if (value == 0 || !isolatedCountGlyph(gray, width, height, minX, maxX, minY, maxY))
                continue;
            float distance = Math.abs((minX + maxX) * .5f - restCenterX);
            // A time-signature 4 can sit at the left edge of the opening measure. The count is
            // centered above the proven heavy rest bar, so evaluate every glyph and retain the
            // closest one rather than returning the first scan-order match.
            if (distance > localWidth * .36f || distance >= bestDistance) continue;
            bestDistance = distance;
            best =
                    new MeasureNumberReconciler.NumberToken(
                            value,
                            minX / (float) width,
                            minY / (float) height,
                            maxX / (float) width,
                            maxY / (float) height);
        }
        return best;
    }

    private static int countGlyphHeads(
            MeasureNumberReconciler.NumberToken token,
            byte[] labels,
            int width,
            int height,
            MeasureRegion region) {
        if (token == null) return 0;
        float left = Math.max(region.left(), token.left()),
                right = Math.min(region.right(), token.right());
        float top = Math.max(region.top(), token.top()),
                bottom = Math.min(region.bottom(), token.bottom());
        return left >= right || top >= bottom
                ? 0
                : countLabel(
                        labels,
                        width,
                        height,
                        new MeasureRegion(left, right, top, bottom),
                        OmrMeasurePostProcessor.NOTEHEAD);
    }

    private static boolean aboveStaff(
            MeasureNumberReconciler.NumberToken token,
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            MeasureRegion region) {
        int left = Math.round(region.left() * width),
                right = Math.min(width - 1, Math.round(region.right() * width));
        for (int y = Math.round(region.top() * height);
                y < Math.min(height, Math.round(region.bottom() * height));
                y++) {
            int ink = 0;
            for (int x = left; x <= right; x++)
                if (labels[y * width + x] == OmrMeasurePostProcessor.STAFF) ink++;
            if (ink > (right - left) * .55f)
                return countAboveRule(token, labels, gray, width, height, y);
        }
        // The model can omit every staff rule in a silent measure. Require a
        // complete printed five-line group rather than accepting an isolated bar.
        int top = clamp(Math.round(region.top() * height), 0, height - 1);
        int bottom = clamp(Math.round(region.bottom() * height), top, height - 1);
        left = clamp(left, 0, width - 1);
        right = clamp(right, left, width - 1);
        int w = right - left + 1, h = bottom - top + 1;
        byte[] crop = new byte[w * h];
        for (int y = 0; y < h; y++)
            System.arraycopy(gray, (top + y) * width + left, crop, y * w, w);
        int first =
                RawStaffLineDetector.detect(crop, w, h).stream()
                        .mapToInt(RawStaffLineDetector.StaffLines::top)
                        .min()
                        .orElse(-1);
        return first >= 0 && countAboveRule(token, labels, gray, width, height, top + first);
    }

    private static boolean countAboveRule(
            MeasureNumberReconciler.NumberToken token,
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            int ruleY) {
        if (token.bottom() * height < ruleY) return true;
        float tokenHeight = (token.bottom() - token.top()) * height;
        // OCR padding may include the top rule, but the glyph must remain detached above it.
        if (tokenHeight <= 0 || token.bottom() * height - ruleY > Math.max(1, tokenHeight * .12f))
            return false;
        int left = clamp(Math.round(token.left() * width), 0, width - 1);
        int right = clamp(Math.round(token.right() * width), left, width - 1);
        int searchTop =
                clamp((int) Math.ceil(token.top() * height + tokenHeight * .65f), 0, height - 1);
        int gap = 0;
        for (int y = searchTop; y < Math.min(height, ruleY); y++) {
            boolean empty = true;
            for (int x = left; x <= right; x++)
                if ((gray[y * width + x] & 255) <= 165) {
                    empty = false;
                    break;
                }
            gap = empty ? gap + 1 : 0;
            if (gap < 2) continue;
            // Never subtract a real notehead lying on the rule as OCR-count contamination.
            for (int yy = y + 1;
                    yy <= Math.min(height - 1, Math.round(token.bottom() * height));
                    yy++)
                for (int x = left; x <= right; x++)
                    if (labels[yy * width + x] == OmrMeasurePostProcessor.NOTEHEAD) return false;
            return true;
        }
        return false;
    }

    /** An eight has two enclosed white bowls stacked vertically; a notehead has only one. */
    private static boolean looksLikeEight(
            byte[] gray, int width, int regionHeight, int left, int right, int top, int bottom) {
        int w = right - left + 1, h = bottom - top + 1;
        if (h < regionHeight * .13f || h > regionHeight * .48f || w < h * .4f || w > h * .95f)
            return false;
        boolean[] visited = new boolean[w * h];
        int[] stack = new int[w * h];
        List<Float> centers = new ArrayList<>();
        for (int origin = 0; origin < visited.length; origin++) {
            if (visited[origin]
                    || (gray[(top + origin / w) * width + left + origin % w] & 255) <= 165)
                continue;
            int size = 0, area = 0, sumY = 0;
            boolean edge = false;
            stack[size++] = origin;
            visited[origin] = true;
            while (size > 0) {
                int current = stack[--size], x = current % w, y = current / w;
                area++;
                sumY += y;
                edge |= x == 0 || x == w - 1 || y == 0 || y == h - 1;
                int next = current - 1;
                if (!(next < 0
                        || next >= visited.length
                        || Math.abs(next % w - x) + Math.abs(next / w - y) != 1
                        || visited[next]
                        || (gray[(top + next / w) * width + left + next % w] & 255) <= 165)) {
                    visited[next] = true;
                    stack[size++] = next;
                }
                next = current + 1;
                if (!(next < 0
                        || next >= visited.length
                        || Math.abs(next % w - x) + Math.abs(next / w - y) != 1
                        || visited[next]
                        || (gray[(top + next / w) * width + left + next % w] & 255) <= 165)) {
                    visited[next] = true;
                    stack[size++] = next;
                }
                next = current - w;
                if (!(next < 0
                        || next >= visited.length
                        || Math.abs(next % w - x) + Math.abs(next / w - y) != 1
                        || visited[next]
                        || (gray[(top + next / w) * width + left + next % w] & 255) <= 165)) {
                    visited[next] = true;
                    stack[size++] = next;
                }
                next = current + w;
                if (!(next < 0
                        || next >= visited.length
                        || Math.abs(next % w - x) + Math.abs(next / w - y) != 1
                        || visited[next]
                        || (gray[(top + next / w) * width + left + next % w] & 255) <= 165)) {
                    visited[next] = true;
                    stack[size++] = next;
                }
            }
            if (!edge && area >= w * h * .035f) centers.add(sumY / (float) area / h);
        }
        return centers.size() == 2 && centers.get(0) < .45f && centers.get(1) > .55f;
    }

    /** Standalone single-digit recognition must not read a letter or one digit from adjacent text. */
    private static boolean isolatedCountGlyph(
            byte[] gray, int width, int height, int left, int right, int top, int bottom) {
        int h = bottom - top + 1, margin = Math.max(2, Math.round(h * .38f));
        for (int direction : new int[] {-1, 1}) {
            int ink = 0;
            for (int y = Math.max(0, top); y <= Math.min(height - 1, bottom); y++)
                for (int dx = 1; dx <= margin; dx++) {
                    int x = direction < 0 ? left - dx : right + dx;
                    if (x >= 0 && x < width && (gray[y * width + x] & 255) <= 165) ink++;
                }
            if (ink > Math.max(2, Math.round(h * h * .035f))) return false;
        }
        return true;
    }

    /** A nine has one upper bowl and a right-hand tail that curves back left below it. */
    private static boolean looksLikeNine(
            byte[] gray, int width, int regionHeight, int left, int right, int top, int bottom) {
        int w = right - left + 1, h = bottom - top + 1;
        if (h < regionHeight * .13f || h > regionHeight * .48f || w < h * .4f || w > h * .95f)
            return false;
        boolean[] seen = new boolean[w * h];
        int[] queue = new int[w * h];
        int holes = 0;
        float holeY = 0;
        for (int i = 0; i < seen.length; i++) {
            if (seen[i] || (gray[(top + i / w) * width + left + i % w] & 255) <= 165) continue;
            int read = 0, size = 1, area = 0, sumY = 0;
            boolean edge = false;
            queue[0] = i;
            seen[i] = true;
            while (read < size) {
                int at = queue[read++], x = at % w, y = at / w;
                area++;
                sumY += y;
                edge |= x == 0 || x == w - 1 || y == 0 || y == h - 1;
                int next = at - 1;
                if (!(next < 0
                        || next >= seen.length
                        || seen[next]
                        || Math.abs(next % w - x) + Math.abs(next / w - y) != 1
                        || (gray[(top + next / w) * width + left + next % w] & 255) <= 165)) {
                    seen[next] = true;
                    queue[size++] = next;
                }
                next = at + 1;
                if (!(next < 0
                        || next >= seen.length
                        || seen[next]
                        || Math.abs(next % w - x) + Math.abs(next / w - y) != 1
                        || (gray[(top + next / w) * width + left + next % w] & 255) <= 165)) {
                    seen[next] = true;
                    queue[size++] = next;
                }
                next = at - w;
                if (!(next < 0
                        || next >= seen.length
                        || seen[next]
                        || Math.abs(next % w - x) + Math.abs(next / w - y) != 1
                        || (gray[(top + next / w) * width + left + next % w] & 255) <= 165)) {
                    seen[next] = true;
                    queue[size++] = next;
                }
                next = at + w;
                if (!(next < 0
                        || next >= seen.length
                        || seen[next]
                        || Math.abs(next % w - x) + Math.abs(next / w - y) != 1
                        || (gray[(top + next / w) * width + left + next % w] & 255) <= 165)) {
                    seen[next] = true;
                    queue[size++] = next;
                }
            }
            if (!edge && area >= w * h * .035f) {
                holes++;
                holeY = sumY / (float) area / h;
            }
        }
        if (holes != 1 || holeY > .45f) return false;
        int spine = 0, bottomWidth = 0;
        for (int x = Math.round(w * .55f); x < w; x++) {
            int count = 0;
            for (int y = Math.round(h * .3f); y <= Math.round(h * .8f); y++)
                if ((gray[(top + y) * width + left + x] & 255) <= 165) count++;
            spine = Math.max(spine, count);
        }
        for (int y = Math.round(h * .78f); y < h; y++) {
            int first = w, last = -1;
            for (int x = 0; x < w; x++)
                if ((gray[(top + y) * width + left + x] & 255) <= 165) {
                    first = Math.min(first, x);
                    last = x;
                }
            if (first < w * .4f) bottomWidth = Math.max(bottomWidth, last - first + 1);
        }
        return spine >= h * .45f && bottomWidth >= w * .6f;
    }

    /** A six has one lower bowl and a left spine curving right at its upper terminal. */
    private static boolean looksLikeSix(
            byte[] gray, int width, int regionHeight, int left, int right, int top, int bottom) {
        int w = right - left + 1, h = bottom - top + 1;
        if (h < regionHeight * .13f || h > regionHeight * .48f || w < h * .4f || w > h * .95f)
            return false;
        if (singleBowlCenter(gray, width, left, right, top, bottom) < .55f
                && !closedTerminalSix(gray, width, left, right, top, bottom)) return false;
        int leftSpine = 0, terminalX = 0, terminalInk = 0;
        for (int x = 0; x <= Math.round(w * .45f); x++) {
            int ink = 0;
            for (int y = Math.round(h * .20f); y <= Math.round(h * .80f); y++)
                if ((gray[(top + y) * width + left + x] & 255) <= 165) ink++;
            leftSpine = Math.max(leftSpine, ink);
        }
        for (int y = 0; y < Math.max(1, Math.round(h * .20f)); y++)
            for (int x = 0; x < w; x++)
                if ((gray[(top + y) * width + left + x] & 255) <= 165) {
                    terminalX += x;
                    terminalInk++;
                }
        return leftSpine >= h * .45f
                && terminalInk > 0
                && terminalX / (float) terminalInk >= w * .50f;
    }

    /** A closed terminal may add a second pocket to a six, but never a second outer waist. */
    private static boolean closedTerminalSix(
            byte[] gray, int width, int left, int right, int top, int bottom) {
        int w = right - left + 1, h = bottom - top + 1;
        int[] starts = new int[h], ends = new int[h];
        int minimum = w;
        for (int y = 0; y < h; y++) {
            starts[y] = w;
            ends[y] = -1;
            for (int x = 0; x < w; x++)
                if ((gray[(top + y) * width + left + x] & 255) <= 165) {
                    starts[y] = Math.min(starts[y], x);
                    ends[y] = x;
                }
            if (ends[y] < 0) return false;
            minimum = Math.min(minimum, starts[y]);
        }
        // A letter B has a straight left edge reaching its top and bottom, not this curved body.
        if (starts[0] <= minimum || starts[h - 1] <= minimum) return false;
        int[] hull = new int[h];
        int length = 0;
        for (int y = 0; y < h; y++) {
            while (length >= 2) {
                int a = hull[length - 2], b = hull[length - 1];
                long cross =
                        (long) (b - a) * (starts[y] - starts[a])
                                - (long) (starts[b] - starts[a]) * (y - a);
                if (cross > 0) break;
                length--;
            }
            hull[length++] = y;
        }
        // Exact digital convexity on the entire unilateral contour rejects a waist or spur.
        for (int j = 1; j < length; j++) {
            int a = hull[j - 1], b = hull[j], denominator = b - a;
            for (int y = a; y <= b; y++) {
                long numerator = (long) starts[a] * (b - y) + (long) starts[b] * (y - a);
                if (starts[y] != (numerator + denominator - 1) / denominator) return false;
            }
        }
        boolean[] seen = new boolean[w * h];
        int[] queue = new int[w * h];
        List<int[]> pockets = new ArrayList<>();
        for (int origin = 0; origin < seen.length; origin++) {
            if (seen[origin] || (gray[(top + origin / w) * width + left + origin % w] & 255) <= 165)
                continue;
            int read = 0, size = 1, area = 0, sumY = 0, minY = h, maxY = -1;
            boolean edge = false;
            queue[0] = origin;
            seen[origin] = true;
            while (read < size) {
                int at = queue[read++], x = at % w, y = at / w;
                area++;
                sumY += y;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                edge |= x == 0 || x == w - 1 || y == 0 || y == h - 1;
                for (int next : new int[] {at - 1, at + 1, at - w, at + w})
                    if (next >= 0
                            && next < seen.length
                            && !seen[next]
                            && Math.abs(next % w - x) + Math.abs(next / w - y) == 1
                            && (gray[(top + next / w) * width + left + next % w] & 255) > 165) {
                        seen[next] = true;
                        queue[size++] = next;
                    }
            }
            if (!edge) {
                if (area < w * h * .035f || pockets.size() == 2) return false;
                pockets.add(new int[] {area, sumY, minY, maxY});
            }
        }
        if (pockets.size() != 2) return false;
        int[] upper = pockets.get(0), lower = pockets.get(1);
        if (upper[1] / (float) upper[0] >= h * .45f
                || lower[1] / (float) lower[0] <= h * .55f
                || upper[3] >= lower[2]) return false;
        int upperRow = upper[1] / upper[0], lowerRow = lower[1] / lower[0];
        int rightLimit = Math.min(ends[upperRow], ends[lowerRow]);
        boolean rightWaist = false, reachesSpine = false;
        for (int y = upper[3] + 1; y < lower[2]; y++) {
            rightWaist |= ends[y] < rightLimit;
            reachesSpine |= starts[y] == minimum;
        }
        return rightWaist && reachesSpine;
    }

    private static float singleBowlCenter(
            byte[] gray, int width, int left, int right, int top, int bottom) {
        int w = right - left + 1, h = bottom - top + 1;
        boolean[] seen = new boolean[w * h];
        int[] queue = new int[w * h];
        int holes = 0;
        float center = -1;
        for (int origin = 0; origin < seen.length; origin++) {
            if (seen[origin] || (gray[(top + origin / w) * width + left + origin % w] & 255) <= 165)
                continue;
            int read = 0, size = 1, area = 0, sumY = 0;
            boolean edge = false;
            queue[0] = origin;
            seen[origin] = true;
            while (read < size) {
                int at = queue[read++], x = at % w, y = at / w;
                area++;
                sumY += y;
                edge |= x == 0 || x == w - 1 || y == 0 || y == h - 1;
                for (int next : new int[] {at - 1, at + 1, at - w, at + w})
                    if (next >= 0
                            && next < seen.length
                            && !seen[next]
                            && Math.abs(next % w - x) + Math.abs(next / w - y) == 1
                            && (gray[(top + next / w) * width + left + next % w] & 255) > 165) {
                        seen[next] = true;
                        queue[size++] = next;
                    }
            }
            if (!edge && area >= w * h * .035f) {
                holes++;
                center = sumY / (float) area / h;
            }
        }
        return holes == 1 ? center : -1;
    }

    /** A seven has a broad top stroke and a narrow descending diagonal, not a lower bowl. */
    private static boolean looksLikeSeven(
            byte[] gray,
            int width,
            int regionHeight,
            int area,
            int left,
            int right,
            int top,
            int bottom) {
        int w = right - left + 1, h = bottom - top + 1;
        if (h < regionHeight * .13f
                || h > regionHeight * .48f
                || w < h * .35f
                || w > h * 1.10f
                || area < w * h * .12f
                || area > w * h * .70f) return false;
        int topSpan = 0, lowerSpan = 0, upperX = -1, lowerX = -1, rightSpine = 0;
        int[] columns = new int[w];
        for (int y = 0; y < h; y++) {
            int first = w, last = -1;
            for (int x = 0; x < w; x++)
                if ((gray[(top + y) * width + left + x] & 255) <= 165) {
                    first = Math.min(first, x);
                    last = Math.max(last, x);
                    columns[x]++;
                }
            if (last < first) continue;
            if (y < h * .20f) topSpan = Math.max(topSpan, last - first + 1);
            if (y > h * .65f) lowerSpan = Math.max(lowerSpan, last - first + 1);
            if (y >= h * .30f && y <= h * .45f) upperX = Math.max(upperX, first + last);
            if (y >= h * .75f) lowerX = Math.max(lowerX, first + last);
        }
        for (int x = Math.round(w * .55f); x < w; x++)
            rightSpine = Math.max(rightSpine, columns[x]);
        return topSpan >= w * .70f
                && lowerSpan <= w * .65f
                && upperX >= w
                && lowerX >= 0
                && lowerX < upperX - w * .25f
                && (rightSpine < h * .67f
                        || (lowerSpan <= w * .44f && upperX - lowerX >= w * .43f));
    }

    /** Kept as a narrow compatibility seam for the original four-shape regression tests. */
    static MeasureNumberReconciler.NumberToken standaloneFour(
            byte[] gray, int width, int height, RestBarCandidate candidate) {
        MeasureNumberReconciler.NumberToken token = standaloneCount(gray, width, height, candidate);
        return token != null && token.value() == 4 ? token : null;
    }

    private static boolean looksLikeFour(
            byte[] gray,
            int width,
            int regionHeight,
            int area,
            int minX,
            int maxX,
            int minY,
            int maxY) {
        int glyphWidth = maxX - minX + 1, glyphHeight = maxY - minY + 1;
        if (glyphHeight < regionHeight * .13f
                || glyphHeight > regionHeight * .48f
                || glyphWidth < regionHeight * .045f
                || glyphWidth > regionHeight * .28f
                || glyphWidth < glyphHeight * .30f
                || glyphWidth > glyphHeight * 1.10f) return false;
        float fill = area / (float) (glyphWidth * glyphHeight);
        if (fill < .14f || fill > .66f) return false;
        int[] rows = new int[glyphHeight];
        int[] columns = new int[glyphWidth];
        int upperLeft = 0, lowerLeft = 0;
        for (int y = minY; y <= maxY; y++)
            for (int x = minX; x <= maxX; x++) {
                if ((gray[y * width + x] & 0xff) > 165) continue;
                int localX = x - minX, localY = y - minY;
                rows[localY]++;
                columns[localX]++;
                if (localX < glyphWidth * .55f && localY < glyphHeight * .58f) upperLeft++;
                if (localX < glyphWidth * .45f && localY > glyphHeight * .72f) lowerLeft++;
            }
        int rightSpine = 0;
        for (int x = Math.max(0, Math.round(glyphWidth * .52f)); x < glyphWidth; x++)
            rightSpine = Math.max(rightSpine, columns[x]);
        int middleCrossbar = 0;
        for (int y = Math.max(0, Math.round(glyphHeight * .36f));
                y <= Math.min(glyphHeight - 1, Math.round(glyphHeight * .74f));
                y++) middleCrossbar = Math.max(middleCrossbar, rows[y]);
        int topBar = 0;
        for (int y = 0; y < Math.max(1, Math.round(glyphHeight * .28f)); y++)
            topBar = Math.max(topBar, rows[y]);
        return rightSpine >= glyphHeight * .58f
                && middleCrossbar >= glyphWidth * .50f
                && topBar < glyphWidth * .68f
                && upperLeft >= Math.max(2, Math.round(area * .10f))
                && lowerLeft <= Math.max(2, Math.round(area * .16f));
    }

    /** A three has two left-open bowls joined by a continuous right side. */
    private static boolean looksLikeThree(
            byte[] gray,
            int width,
            int regionHeight,
            int area,
            int left,
            int right,
            int top,
            int bottom) {
        int w = right - left + 1, h = bottom - top + 1;
        if (h < regionHeight * .13f
                || h > regionHeight * .48f
                || w < h * .35f
                || w > h * .95f
                || area < w * h * .16f
                || area > w * h * .80f) return false;
        int[] first = new int[h], last = new int[h], columns = new int[w];
        java.util.Arrays.fill(first, w);
        java.util.Arrays.fill(last, -1);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if ((gray[(top + y) * width + left + x] & 255) <= 165) {
                    first[y] = Math.min(first[y], x);
                    last[y] = x;
                    columns[x]++;
                }
        int upperOpen = 0,
                lowerOpen = 0,
                topSpan = 0,
                middleSpan = 0,
                bottomSpan = 0,
                rightSpine = 0, upperCounters = 0, lowerCounters = 0, openWaist = 0;
        for (int y = 0; y < h; y++) {
            if (y >= h * .25f && y <= h * .45f && first[y] >= w * .45f && last[y] >= w * .75f)
                upperOpen++;
            if (y >= h * .55f && y <= h * .75f && first[y] >= w * .45f && last[y] >= w * .75f)
                lowerOpen++;
            if (first[y] <= .2f * w && last[y] >= .7f * w
                    && (gray[(top + y) * width + left + w / 2] & 255) > 175) {
                if (y >= .13f * h && y <= .40f * h) upperCounters++;
                if (y >= .58f * h && y <= .82f * h) lowerCounters++;
            }
            if (y >= .40f * h && y <= .61f * h && first[y] >= .24f * w
                    && last[y] >= .65f * w) openWaist++;
            int span = last[y] - first[y] + 1;
            if (y < h * .20f) topSpan = Math.max(topSpan, span);
            if (y >= h * .40f && y <= h * .60f) middleSpan = Math.max(middleSpan, span);
            if (y > h * .80f) bottomSpan = Math.max(bottomSpan, span);
        }
        for (int x = Math.round(w * .70f); x < w; x++)
            rightSpine = Math.max(rightSpine, columns[x]);
        if (topSpan < w * .7f || bottomSpan < w * .7f
                || middleSpan < w * .45f || rightSpine < h * .55f) return false;
        int openRows = Math.max(1, (int) (h * .06f));
        if (upperOpen >= openRows && lowerOpen >= openRows) return true;

        // Engraved ball terminals can almost close both bowls. A figure eight
        // would close its white counters, while this form opens at the waist.
        int terminalRows = Math.max(2, (int) (h * .08f));
        if (upperCounters < terminalRows || lowerCounters < terminalRows
                || openWaist < Math.max(2, (int) (h * .11f))
                || OctaveClefDigit.holes(gray, width, left, top, w, h) != 0) return false;
        var reading = JoinedDigitTemplates.inspect(gray, width, gray.length / width,
                new java.util.BitSet(), left, top, right, bottom, () -> {});
        return reading != null && reading.scores()[3] >= .70f;
    }

    private static boolean looksLikeTwo(
            byte[] gray,
            int width,
            int regionHeight,
            int area,
            int minX,
            int maxX,
            int minY,
            int maxY) {
        int glyphWidth = maxX - minX + 1, glyphHeight = maxY - minY + 1;
        if (glyphHeight < regionHeight * .13f
                || glyphHeight > regionHeight * .48f
                || glyphWidth < regionHeight * .045f
                || glyphWidth > regionHeight * .34f
                || glyphWidth < glyphHeight * .34f
                || glyphWidth > glyphHeight * 1.12f) return false;
        float fill = area / (float) (glyphWidth * glyphHeight);
        if (fill < .13f || fill > .66f) return false;
        int[] rows = new int[glyphHeight];
        int upperRight = 0, lowerLeft = 0, lowerDiagonal = 0;
        for (int y = minY; y <= maxY; y++)
            for (int x = minX; x <= maxX; x++) {
                if ((gray[y * width + x] & 0xff) > 165) continue;
                int localX = x - minX, localY = y - minY;
                rows[localY]++;
                if (localX >= glyphWidth * .52f && localY <= glyphHeight * .48f) upperRight++;
                if (localX <= glyphWidth * .48f && localY >= glyphHeight * .52f) lowerLeft++;
                if (localX <= glyphWidth * .42f
                        && localY >= glyphHeight * .52f
                        && localY <= glyphHeight * .82f) lowerDiagonal++;
            }
        int topStroke = 0, bottomStroke = 0, rightSpine = 0, leftSpine = 0;
        for (int y = 0; y <= Math.min(glyphHeight - 1, Math.round(glyphHeight * .30f)); y++)
            topStroke = Math.max(topStroke, rows[y]);
        for (int y = Math.max(0, Math.round(glyphHeight * .70f)); y < glyphHeight; y++)
            bottomStroke = Math.max(bottomStroke, rows[y]);
        for (int x = Math.max(0, Math.round(glyphWidth * .58f)); x < glyphWidth; x++) {
            int run = 0;
            for (int y = minY; y <= maxY; y++) {
                run = (gray[y * width + minX + x] & 255) <= 165 ? run + 1 : 0;
                rightSpine = Math.max(rightSpine, run);
            }
        }
        for (int x = 0; x < Math.round(glyphWidth * .42f); x++) {
            int run = 0;
            for (int y = minY; y <= maxY; y++) {
                run = (gray[y * width + minX + x] & 255) <= 165 ? run + 1 : 0;
                leftSpine = Math.max(leftSpine, run);
            }
        }
        // A two has separated left terminals, not the long left spine of a
        // mirrored digit. Serifs must not manufacture diagonal evidence.
        return topStroke >= glyphWidth * .46f
                && bottomStroke >= glyphWidth * .52f
                && rightSpine < glyphHeight * .90f
                && leftSpine < glyphHeight * .50f
                && upperRight >= Math.max(2, Math.round(area * .10f))
                && lowerLeft >= Math.max(2, Math.round(area * .10f))
                && lowerDiagonal >= Math.max(2, (int) (area * .06f));
    }

    private static void addOrMerge(List<RestBarCandidate> result, RestBarCandidate candidate) {
        for (int index = 0; index < result.size(); index++) {
            RestBarCandidate existing = result.get(index);
            if (existing.measureIndex != candidate.measureIndex) continue;
            float overlap =
                    Math.min(existing.region.right(), candidate.region.right())
                            - Math.max(existing.region.left(), candidate.region.left());
            float narrower =
                    Math.min(
                            existing.region.right() - existing.region.left(),
                            candidate.region.right() - candidate.region.left());
            if (overlap < narrower * .35f) continue;
            result.set(
                    index,
                    new RestBarCandidate(
                            candidate.measureIndex,
                            new MeasureRegion(
                                    Math.min(existing.region.left(), candidate.region.left()),
                                    Math.max(existing.region.right(), candidate.region.right()),
                                    candidate.region.top(),
                                    candidate.region.bottom())));
            return;
        }
        result.add(candidate);
    }

    private static int containingRestBar(
            List<RestBarCandidate> candidates,
            List<MeasureRegion> measures,
            float x,
            float y,
            RestBarCandidate[] matched) {
        for (RestBarCandidate candidate : candidates) {
            MeasureRegion region = candidate.region;
            float regionHeight = region.bottom() - region.top();
            float countTop = region.top() - regionHeight * .95f;
            // A rest's count window stops in the gap to the preceding row.
            // Otherwise a low tuplet number on that row can claim this rest.
            MeasureRegion parent = measures.get(candidate.measureIndex);
            for (MeasureRegion other : measures)
                if (x >= other.left() && x <= other.right() && other.bottom() < parent.top())
                    countTop = Math.max(countTop, (other.bottom() + parent.top()) * .5f);
            if (x >= region.left()
                    && x <= region.right()
                    && y >= countTop
                    && y <= region.bottom()) {
                if (matched != null) matched[0] = candidate;
                return candidate.measureIndex;
            }
        }
        return -1;
    }

    private static int countLabel(
            byte[] labels, int width, int height, MeasureRegion region, byte wanted) {
        int left = clamp(Math.round(region.left() * width), 0, width - 1);
        int right = clamp(Math.round(region.right() * width), left, width - 1);
        int top = clamp(Math.round(region.top() * height), 0, height - 1);
        int bottom = clamp(Math.round(region.bottom() * height), top, height - 1);
        int count = 0;
        for (int y = top; y <= bottom; y++)
            for (int x = left; x <= right; x++) if (labels[y * width + x] == wanted) count++;
        return count;
    }

    /** Staff lines are long but thin. A multi-rest's centered bar stays dark for several rows. */
    static boolean hasThickHorizontalBar(byte[] gray, int width, int height, MeasureRegion region) {
        int left = clamp(Math.round(region.left() * width), 0, width - 1);
        int right = clamp(Math.round(region.right() * width), left, width - 1);
        int top = clamp(Math.round(region.top() * height), 0, height - 1);
        int bottom = clamp(Math.round(region.bottom() * height), top, height - 1);
        int regionWidth = right - left + 1, regionHeight = bottom - top + 1;
        if (regionWidth < 12 || regionHeight < 8) return false;
        int requiredRun = Math.max(8, Math.round(regionWidth * .34f));
        int requiredThickness = Math.max(3, Math.min(7, Math.round(regionHeight * .035f)));
        int searchTop = top + Math.round(regionHeight * .18f);
        int searchBottom = bottom - Math.round(regionHeight * .12f);
        int consecutive = 0;
        for (int y = searchTop; y <= searchBottom; y++) {
            int current = 0, paleGap = 0;
            int row = y * width;
            boolean qualifies = false;
            for (int at = row + left, end = row + right; at <= end; at++) {
                if ((gray[at] & 255) <= 150) {
                    current += paleGap + 1;
                    paleGap = 0;
                    if (current >= requiredRun) {
                        qualifies = true;
                        break;
                    }
                } else if (current > 0 && paleGap < 1) {
                    paleGap++;
                } else {
                    current = 0;
                    paleGap = 0;
                }
            }
            consecutive = qualifies ? consecutive + 1 : 0;
            if (consecutive >= requiredThickness) return true;
            if (consecutive + searchBottom - y < requiredThickness) break;
        }
        return cappedRestWindow(gray, width, height, region) != null;
    }

    /** A short H-rest can be much narrower than the surrounding empty engraved measure. */
    private static MeasureRegion cappedRestWindow(
            byte[] gray, int width, int height, MeasureRegion region) {
        int left = clamp(Math.round(region.left() * width), 0, width - 1);
        int right = clamp(Math.round(region.right() * width), left, width - 1);
        int top = clamp(Math.round(region.top() * height), 0, height - 1);
        int bottom = clamp(Math.round(region.bottom() * height), top, height - 1);
        int regionHeight = bottom - top + 1;
        for (int y = top + Math.round(regionHeight * .18f);
                y <= bottom - Math.round(regionHeight * .12f);
                y++) {
            for (int x = left; x <= right; x++) {
                if ((gray[y * width + x] & 255) > 150) continue;
                int start = x;
                while (x < right && (gray[y * width + x + 1] & 255) <= 150) x++;
                int end = x, span = end - start + 1;
                if (span < 12 || span > regionHeight * 2.4f) continue;
                int bandTop = y, bandBottom = y;
                while (bandTop > top && filledBarRow(gray, width, start, end, bandTop - 1))
                    bandTop--;
                while (bandBottom < bottom && filledBarRow(gray, width, start, end, bandBottom + 1))
                    bandBottom++;
                if (!PrintedRestBarHeads.proved(
                        gray, width, height, start, bandTop, end, bandBottom)) continue;
                int padding = Math.max(2, Math.round(regionHeight * .08f));
                return new MeasureRegion(
                        Math.max(left, start - padding) / (float) width,
                        Math.min(right, end + padding) / (float) width,
                        region.top(),
                        region.bottom());
            }
        }
        return null;
    }

    private static boolean filledBarRow(byte[] gray, int width, int left, int right, int y) {
        int row = y * width;
        float required = (right - left + 1) * .80f;
        int ink = 0;
        for (int at = row + left, end = row + right; at <= end; at++) {
            if ((gray[at] & 255) <= 150 && ++ink >= required) return true;
            if (ink + end - at < required) return false;
        }
        return ink >= required;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
