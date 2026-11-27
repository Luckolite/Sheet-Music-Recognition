// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
// Adapted from Music Sheets: standalone package and public API visibility.
package io.github.luckolite.interpreter;

/** Pure logical-measure mapping for PDFs that contain continuation or alternate-arrangement pages. */
public final class ScorePageTimeline {
    public record PageRange(int firstPage, int pageAfterLast) {}

    private ScorePageTimeline() {}

    /** The index refers to the later page of an adjacent pair in the input list. */
    static java.util.function.IntPredicate physicalAdjacency(
            java.util.List<ScorePageInterpretation> pages, java.util.List<Integer> physicalPages) {
        if (physicalPages != null && physicalPages.size() != pages.size())
            throw new IllegalArgumentException("Physical page roster does not match the score");
        return current -> {
            var earlier = pages.get(current - 1).clockContextSource().origin();
            var later = pages.get(current).clockContextSource().origin();
            if (!earlier.documentSha256().isEmpty() && !later.documentSha256().isEmpty()
                    && !earlier.documentSha256().equals(later.documentSha256()))
                return false;
            int from = physicalPages == null ? earlier.physicalPageIndex() : physicalPages.get(current - 1);
            int to = physicalPages == null ? later.physicalPageIndex() : physicalPages.get(current);
            // Legacy callers with no page identity retain their input list's adjacency.
            return from == -1 && to == -1 || from >= 0 && to == from + 1;
        };
    }


    /**
     * A new title page can restart an unnumbered score. Require a printed main
     * title, an independent part caption and the opening system's own meter.
     */
    static int firstMeasureAfterTitledOpening(
            int recognizedStart, java.util.List<PlayingTechniqueDetector.Word> words,
            byte[] gray, int width, int height, java.util.List<MeasureRegion> measures,
            java.util.List<ScoreMeterChange> meters) {
        if (recognizedStart != 0 || meters.stream().noneMatch(meter -> meter.measureIndex() == 0))
            return recognizedStart;
        return hasTitledOpening(words, gray, width, height, measures) ? 1 : recognizedStart;
    }

    static boolean hasTitledOpening(java.util.List<PlayingTechniqueDetector.Word> words,
            byte[] gray, int width, int height, java.util.List<MeasureRegion> measures) {
        if (words == null || words.size() > 10000 || width < 3 || height < 3
                || gray == null || gray.length != (long) width * height
                || measures.size() < 2 || OpeningPartTempo.firstMeasure(measures) != 0) return false;
        float top = measures.get(0).top();
        if (top < .09f || top > .37f) return false;
        var captions = words.stream().filter(word -> OpeningPartTempo.valid(word)
                && word.bottom() < top + .006f && (partCaption(word.text()) || arrangementCredit(word.text()))
                && OpeningPartTempo.printedWord(word, gray, width, height)).toList();
        if (captions.isEmpty() || captions.size() > 48) return false;
        float left = measures.get(0).left(), right = measures.get(0).right();
        for (var measure : measures)
            if (Math.abs(measure.top() - top) < .008f) right = Math.max(right, measure.right());
        float center = (left + right) * .5f, span = right - left;
        for (var title : words) {
            if (!OpeningPartTempo.valid(title) || title.bottom() + .035f > top
                    || title.bottom() - title.top() < .015f
                    || title.right() - title.left() < span * .16f
                    || Math.abs((title.left() + title.right()) * .5f - center) > span * .17f
                    || partCaption(title.text()) || !title.text().strip().matches(
                            "[\\p{L}][\\p{L}\\p{M} .’'-]{5,90}")
                    || title.text().strip().chars().noneMatch(Character::isWhitespace)
                    || !OpeningPartTempo.printedWord(title, gray, width, height)) continue;
            var related = captions.stream().filter(caption ->
                    (caption.top() > title.top() - .025f
                            && (caption.bottom() <= title.top() || caption.top() >= title.bottom() - .004f))
                    || (partCaption(caption.text()) && caption.bottom() <= title.top()
                            && title.top() - caption.bottom() <= .026f
                            && Math.abs(caption.left() + caption.right() - title.left() - title.right())
                                    <= span * .24f)).toList();
            if (related.stream().anyMatch(caption -> partCaption(caption.text()))) return true;
            var credits = related.stream().filter(caption -> arrangementCredit(caption.text())).toList();
            for (var first : credits)
                for (var second : credits)
                    if (first.bottom() <= second.top() + .002f
                            && second.top() - first.top() >= .008f
                            && Math.min(first.right(), second.right())
                                    > Math.max(first.left(), second.left()) + .08f) return true;
        }
        return false;
    }

    private static boolean partCaption(String text) {
        return OpeningPartTempo.part(text) || text.strip().matches(
                "(?iu)(?:piano|violin|viola|cello|flute|guitar)\\h+(?:solo\\h+)?accompaniment");
    }

    private static boolean arrangementCredit(String text) {
        return text.strip().matches(
                "(?iu)(?:[\\p{L}]+\\h+)?(?:arr\\.|arranged(?:\\h+by)?)\\h+\\p{L}.{5,100}");
    }

    /** Physical trailing pages can be excluded without changing the arrangement's final bar. */
    public static int lastEnabledPage(int[] counts, PageRange range) {
        if (counts == null || range == null) return -1;
        for (int page = Math.min(counts.length, range.pageAfterLast()) - 1;
                page >= Math.max(0, range.firstPage());
                page--) if (counts[page] > 0) return page;
        return -1;
    }

    public static java.util.List<PageRange> arrangements(int[] counts, int[] starts) {
        java.util.List<PageRange> result = new java.util.ArrayList<>();
        if (counts == null) return result;
        int first = 0;
        while (first < counts.length) {
            while (first < counts.length && counts[first] <= 0) first++;
            if (first == counts.length) break;
            int end = first + 1;
            while (end < counts.length) {
                int boundary = arrangementRestartBoundary(end, counts, starts);
                if (boundary > first) {
                    end = boundary;
                    break;
                }
                end++;
            }
            result.add(new PageRange(first, end));
            first = end;
        }
        return java.util.List.copyOf(result);
    }

    /** Explicit choice stays stable when the viewport moves; unavailable choices fall back. */
    public static PageRange selectedArrangement(int[] counts, int[] starts, int preferredPage) {
        var ranges = arrangements(counts, starts);
        for (var range : ranges)
            if (preferredPage >= range.firstPage() && preferredPage < range.pageAfterLast())
                return range;
        return ranges.isEmpty() ? new PageRange(0, 0) : ranges.get(0);
    }

    public static int[] offsets(int[] measureCounts, int[] firstMeasureNumbers) {
        if (measureCounts == null) return new int[0];
        int[] result = new int[measureCounts.length];
        int previousEnd = 0;
        for (int page = 0; page < measureCounts.length; page++) {
            int printed =
                    firstMeasureNumbers != null && page < firstMeasureNumbers.length
                            ? firstMeasureNumbers[page]
                            : 0;
            int proposed = printed > 0 ? printed - 1 : previousEnd;
            // A slightly overlapping printed number usually reflects a split/extra detected
            // bar on the preceding page, not the start of another arrangement.
            result[page] =
                    page > 0 && !restart(page, measureCounts, firstMeasureNumbers)
                            ? Math.max(previousEnd, proposed)
                            : proposed;
            previousEnd = result[page] + Math.max(0, measureCounts[page]);
        }
        return result;
    }

    public static int measureCount(int[] measureCounts, int[] firstMeasureNumbers) {
        return measureCount(
                measureCounts,
                firstMeasureNumbers,
                new PageRange(0, measureCounts == null ? 0 : measureCounts.length));
    }

    public static int measureCount(
            int[] measureCounts, int[] firstMeasureNumbers, PageRange range) {
        int[] offsets = offsets(measureCounts, firstMeasureNumbers);
        int firstPage =
                range == null ? 0 : Math.max(0, Math.min(offsets.length, range.firstPage()));
        int pageAfterLast =
                range == null
                        ? offsets.length
                        : Math.max(firstPage, Math.min(offsets.length, range.pageAfterLast()));
        int maximum = 0, played = 0;
        for (int page = firstPage; page < pageAfterLast; page++) {
            if (page > firstPage && restart(page, measureCounts, firstMeasureNumbers)) played = 0;
            played += Math.max(0, measureCounts[page]);
            maximum = Math.max(maximum, played);
        }
        return maximum;
    }

    public static int pageForMeasure(
            int logicalMeasure, int anchorPage, int[] measureCounts, int[] firstMeasureNumbers) {
        int[] offsets = offsets(measureCounts, firstMeasureNumbers);
        int best = -1, bestDistance = Integer.MAX_VALUE;
        for (int page = 0; page < offsets.length; page++) {
            int count = Math.max(0, measureCounts[page]);
            if (logicalMeasure < offsets[page] || logicalMeasure >= offsets[page] + count) continue;
            int distance = Math.abs(page - anchorPage);
            if (distance < bestDistance) {
                best = page;
                bestDistance = distance;
            }
        }
        return best;
    }

    public static int[] playbackOffsets(int[] counts, int[] starts, PageRange range) {
        int[] offsets = offsets(counts, starts);
        int origin = range.firstPage() < offsets.length ? offsets[range.firstPage()] : 0;
        for (int p = 0; p < offsets.length; p++) offsets[p] -= origin;
        // Printed numbers locate and separate arrangements. Only actual score measures
        // occupy playback time; a numbering typo or disabled page cannot invent silence.
        int played = 0;
        for (int p = range.firstPage(); p < Math.min(counts.length, range.pageAfterLast()); p++) {
            offsets[p] = played;
            played += Math.max(0, counts[p]);
        }
        return offsets;
    }

    public static int pageForMeasure(int measure, int[] counts, int[] starts, PageRange range) {
        int[] offsets = playbackOffsets(counts, starts, range);
        for (int p = range.firstPage(); p < range.pageAfterLast(); p++)
            if (measure >= offsets[p] && measure < offsets[p] + counts[p]) return p;
        return -1;
    }

    private static boolean restart(int page, int[] counts, int[] starts) {
        if (starts == null || page >= starts.length || starts[page] <= 0 || counts[page] <= 0)
            return false;
        int previous = page - 1;
        while (previous >= 0 && counts[previous] == 0) previous--;
        if (previous < 0) return false;
        int prior = previous < starts.length ? starts[previous] : 0;
        // Require a genuine backwards reset; a one-bar overlap at a page break is not one.
        return starts[page] == 1 || (prior > 0 && starts[page] < prior);
    }

    /** Keep the first enabled part. More piano notes later must not silently replace it. */
    public static PageRange primaryArrangement(
            int[] measureCounts, int[] firstMeasureNumbers, int[] noteCounts, int[] staffBreadths) {
        var ranges = arrangements(measureCounts, firstMeasureNumbers);
        return ranges.isEmpty() ? new PageRange(0, 0) : ranges.get(0);
    }

    /** A following printed number can place an unnumbered page at a part restart. */
    private static int arrangementRestartBoundary(int page, int[] counts, int[] starts) {
        if (restart(page, counts, starts)) return page;
        if (starts == null
                || page <= 1
                || page >= starts.length
                || page >= counts.length
                || starts[page] <= 1
                || counts[page - 1] <= 0
                || starts[page - 1] > 0) return -1;
        int boundary = page - 1;
        long expectedMeasures = starts[page] - 1L;
        long unnumberedMeasures = counts[boundary];
        long bestCount = unnumberedMeasures;
        int prior = boundary - 1;
        while (prior >= 0 && (counts[prior] <= 0 || starts[prior] <= 0)) {
            if (counts[prior] > 0) {
                unnumberedMeasures += counts[prior];
                if (Math.abs(unnumberedMeasures - expectedMeasures)
                        < Math.abs(bestCount - expectedMeasures)) {
                    boundary = prior;
                    bestCount = unnumberedMeasures;
                }
            }
            prior--;
        }
        if (prior < 0 || starts[page] >= starts[prior]) return -1;
        // Choose the unnumbered suffix whose measure count best fits the restart.
        // An unnumbered final page of the previous edition can precede that suffix.
        if (starts[page] > bestCount + 5
                || (unnumberedMeasures > counts[page - 1] && starts[page] < bestCount - 3)) return -1;
        return boundary;
    }
}
