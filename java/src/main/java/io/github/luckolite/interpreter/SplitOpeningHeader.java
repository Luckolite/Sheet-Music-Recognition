// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Multiple copies of a printed opening meter can project a false aligned divider. */
final class SplitOpeningHeader {
    private SplitOpeningHeader() {}

    @FunctionalInterface
    interface MeterReader {
        String read(byte[] gray, int width, int height, MeterChangeDetector.Crop crop);
    }

    record Meter(int numerator, int denominator, MeterChangeDetector.Crop crop) {}

    static List<Meter> prove(
            List<MeasureRegion> measures, byte[] labels, byte[] gray, byte[] source,
            int width, int height, MeterReader read) {
        if (measures.size() < 2 || read == null || gray == null
                || gray.length != (long) width * height || labels == null
                || source == null || labels.length != gray.length || source.length != gray.length)
            return List.of();
        var header = measures.get(0);
        var first = measures.get(1);
        float blockHeight = (header.bottom() - header.top()) * height;
        float left = header.left() * width, right = header.right() * width;
        float next = first.left() * width, split = (right + next) * .5f;
        if (header.cadenceQuarterBeats() != 0 || header.left() > .3f
                || next <= right || next - right > blockHeight * .05f
                || Math.abs(first.top() - header.top()) * height > blockHeight * .02f
                || Math.abs(first.bottom() - header.bottom()) * height > blockHeight * .02f
                || right - left > (first.right() - first.left()) * width * .36f
                || blockHeight < (right - left) * 2.5f) return List.of();
        var tracks = RawStaffLineDetector.detect(source, width, height).stream()
                .filter(s -> s.top() > header.top() * height && s.bottom() < header.bottom() * height)
                .toList();
        // A bare staff slit offers no independent ownership proof. Require a stacked
        // opening header with separate lanes, and require blank gutters at the divider.
        if (tracks.size() < 3 || !blankGutters(source, width, right, next, tracks)
                || source != gray && !blankGutters(gray, width, right, next, tracks)) return List.of();
        var candidates = MeterChangeDetector.candidates(labels, source, width, height);
        var copies = new ArrayList<Meter>();
        String reading = null;
        for (var staff : tracks) {
            float gap = staff.gap();
            if (right - left < gap * 4f || right - left > gap * 10
                    || !keyAndNoSound(labels, width, left, right, staff)) return List.of();
            MeterChangeDetector.Crop bound = null;
            for (var crop : candidates) {
                if (Math.abs(crop.firstLine() - staff.top()) > gap * .28f
                        || crop.left() > split - gap * .32f || crop.right() < next + gap * .45f
                        || crop.left() < split - gap * 1.65f || crop.right() > next + gap * 1.85f)
                    continue;
                if (MeterHeaderInkGuard.owns(crop, labels, source, width, height)) continue;
                String value = read.read(source, width, height, crop);
                if (value.isBlank()) continue;
                if (!value.matches("1[0-9]/(?:2|4|8|16|32)")) return List.of();
                if (source != gray && !value.equals(read.read(gray, width, height, crop))) continue;
                if (reading != null && !reading.equals(value)) return List.of();
                reading = value;
                bound = crop;
            }
            if (bound != null) {
                var parts = reading.split("/");
                try {
                    var validated = new ScoreMeterChange(
                            0, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                    copies.add(new Meter(validated.numerator(), validated.denominator(), bound));
                } catch (IllegalArgumentException invalid) {
                    return List.of();
                }
            }
        }
        return copies.size() < 2 ? List.of() : List.copyOf(copies);
    }

    private static boolean keyAndNoSound(
            byte[] labels, int width, float left, float right,
            RawStaffLineDetector.StaffLines staff) {
        int key = 0, playable = 0;
        float gap = staff.gap();
        int beforeMeter = Math.round(right - gap * .55f);
        for (int y = staff.top() - Math.round(gap * .35f);
                y <= staff.bottom() + Math.round(gap * .35f); y++) {
            if (y < 0 || y >= labels.length / width) return false;
            for (int x = Math.max(0, Math.round(left)); x < Math.min(beforeMeter, width); x++) {
                byte type = labels[y * width + x];
                if (type == OmrMeasurePostProcessor.CLEF_OR_KEY) key++;
                else if (type == OmrMeasurePostProcessor.NOTEHEAD
                        || type == OmrMeasurePostProcessor.STEM_OR_REST) playable++;
            }
        }
        return key >= gap * gap * 1.25f && playable <= gap * gap * .18f;
    }

    private static boolean blankGutters(
            byte[] gray, int width, float left, float right,
            List<RawStaffLineDetector.StaffLines> tracks) {
        for (int i = 1; i < tracks.size(); i++) {
            var before = tracks.get(i - 1);
            var after = tracks.get(i);
            int y0 = before.bottom() + Math.round(before.gap() * .8f);
            int y1 = after.top() - Math.round(after.gap() * .8f);
            if (y1 - y0 < before.gap()) return false;
            int x0 = Math.max(0, Math.round(left - before.gap() * .22f));
            int x1 = Math.min(width, Math.round(right + before.gap() * .22f));
            for (int x = x0; x < x1; x++) {
                int dark = 0;
                for (int y = y0; y < y1; y++)
                    if ((gray[y * width + x] & 255) < 155) dark++;
                if (dark > (y1 - y0) * .20f) return false;
            }
        }
        return true;
    }
}
