// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** A new staff header after a closed exercise is not a playable measure. */
final class InterruptedStaffHeader {
    private InterruptedStaffHeader() {}

    static List<MeasureRegion> discard(List<MeasureRegion> measures, byte[] labels,
            byte[] gray, byte[] source, int width, int height) {
        if (gray == null || source == null || labels == null
                || gray.length != (long) width * height
                || labels.length != gray.length || source.length != gray.length)
            return measures;
        ArrayList<MeasureRegion> retained = null;
        for (int i = 1; i + 1 < measures.size(); i++) {
            var previous = measures.get(i - 1);
            var candidate = measures.get(i);
            var next = measures.get(i + 1);
            float gap = (candidate.bottom() - candidate.top()) * height / 8.2f;
            float left = candidate.left() * width, right = candidate.right() * width;
            float before = previous.right() * width, following = next.left() * width;
            if (gap < 10 || gap > 28 || candidate.cadenceQuarterBeats() != 0
                    || right - left < gap * 12 || right - left > gap * 24
                    || (previous.right() - previous.left()) * width > gap * 7
                    || left - before < gap * .4f || left - before > gap * 1.7f
                    || following - right < gap * .4f || following - right > gap * 1.7f
                    || Math.abs(previous.top() - candidate.top()) * height > gap * .35f
                    || Math.abs(next.top() - candidate.top()) * height > gap * .35f)
                continue;
            String normalized = headerMeter(gray, labels, width, height, candidate, before,
                    left, right, following, gap);
            if (normalized.isBlank() || !normalized.equals(headerMeter(source, labels, width,
                    height, candidate, before, left, right, following, gap))) continue;
            if (retained == null) retained = new ArrayList<>(measures);
            retained.remove(candidate);
        }
        var split = SplitOpeningHeader.prove(measures, labels, gray, source, width, height,
                (pixels, w, h, crop) -> {
                    var reading = MeterFontMatcher.readCrop(pixels, w, h, crop);
                    return MusicalOcrEvidence.fontMeter(reading.text(),
                            reading.topScore(), reading.bottomScore(), java.util.Set.of(), java.util.Set.of());
                });
        if (!split.isEmpty()) {
            if (retained == null) retained = new ArrayList<>(measures);
            retained.remove(measures.get(0));
        }
        return retained == null ? measures : List.copyOf(retained);
    }

    private static String headerMeter(byte[] gray, byte[] labels, int width, int height,
            MeasureRegion bar, float before, float left, float right, float following, float guess) {
        var rules = PrintedDirectionStaff.printedRules(
                gray, width, height, right - guess * 4, bar.bottom() * height - 2.1f * guess, guess);
        if (rules == null) return "";
        float gap = rules[1], top = rules[0] - 4 * gap;
        if (top < 0 || rules[0] >= height) return "";
        var staff = new PlayingTechniqueDetector.Staff(top, rules[0], gap, 0, 1);
        int boundary = PrintedCadenceDurations.boundaryColumn(
                gray, width, staff, (before + left) * .5f, false, true);
        if (boundary < 0) return "";
        int start = Math.round(left + gap);
        int end = Math.round(left + gap * 5.8f);
        for (int y : new int[] {Math.round(top), Math.round(rules[0])}) {
            if (y < 2 || y + 2 >= height) return "";
            int ink = 0;
            for (int x = start; x <= end; x++) {
                if ((gray[(y - 1) * width + x] & 255) < 155
                        || (gray[y * width + x] & 255) < 155
                        || (gray[(y + 1) * width + x] & 255) < 155) ink++;
            }
            if (ink > gap * .65f) return "";
        }
        int clefOrKey = 0, potentiallyPlayable = 0;
        for (int y = Math.round(top); y <= Math.round(rules[0]); y++) {
            for (int x = end; x < Math.round(right); x++) {
                byte label = labels[y * width + x];
                if (label == OmrMeasurePostProcessor.CLEF_OR_KEY) clefOrKey++;
                if (label == OmrMeasurePostProcessor.NOTEHEAD
                        || label == OmrMeasurePostProcessor.STEM_OR_REST) potentiallyPlayable++;
            }
        }
        if (clefOrKey < gap * gap * 4 || potentiallyPlayable > gap * gap * .22f) return "";
        var crop = new MeterChangeDetector.Crop(
                Math.round(right - gap * .75f), Math.round(top - gap * .18f),
                Math.round(following + gap * .9f), Math.round(rules[0] + gap * .22f),
                Math.round(top), gap);
        if (crop.left() < 0 || crop.right() > width || crop.top() < 0 || crop.bottom() > height
                || MeterHeaderInkGuard.owns(crop, labels, gray, width, height)) return "";
        return MeterFontMatcher.readCrop(gray, width, height, crop).text();
    }
}
