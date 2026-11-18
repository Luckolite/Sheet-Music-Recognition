// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Fits complete dark-core contours across source-verified upper octave heads. */
final class PrintedLiteralOctaveSlurs {
    private PrintedLiteralOctaveSlurs() {}

    static List<PrintedSlurContours.Bow> capture(
            byte[] gray, byte[] labels, int width, int height,
            PrintedSlurPageCapture.Frame<? extends PrintedSlurOwners.Location> frame,
            List<ScoreNoteEvent> notes, PrintedSlurContours.HeadEvidence hints,
            List<PrintedSlurContours.Bow> retained) {
        if (labels == null) return List.of();
        var heads = PrintedSlurPageCapture.upperOctaveHeads(frame, notes);
        var found = new ArrayList<PrintedSlurContours.Bow>();
        long remaining = 700_000;
        for (int first = 0; first + 1 < heads.size(); first++) {
            var start = heads.get(first);
            var a = notes.get(start.owner());
            for (int span = 1; span <= 2 && first + span < heads.size(); span++) {
                var end = heads.get(first + span);
                var b = notes.get(end.owner());
                if (a.measureIndex() != b.measureIndex()) continue;
                boolean dashed = span == 2 && a.octaveShift() == 1 && b.octaveShift() == 1
                        && notes.get(heads.get(first + 1).owner()).octaveShift() == 1;
                boolean accidental = span == 1 && b.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL;
                if (!dashed && !accidental) continue;
                float gap = frame.track().at(start.x())[1];
                float distance = end.x() - start.x();
                if (distance < gap * (dashed ? 3.52f : 1.7f)
                        || distance > gap * (dashed ? 5.12f : 3.3f)
                        || Math.abs(start.y() - end.y()) > gap * 1.55f
                        || overlaps(retained, start, end, gap)
                        || overlaps(found, start, end, gap)) continue;
                int left = Math.max(frame.left(), (int) Math.floor(start.x() - gap * .95f));
                int right = Math.min(frame.right(), (int) Math.ceil(end.x() + gap));
                int top = Math.max(frame.top(),
                        (int) Math.floor(Math.min(start.y(), end.y()) - gap * 2.7f));
                int bottom = Math.min(frame.bottom(),
                        (int) Math.ceil(Math.max(start.y(), end.y()) + gap * .5f));
                long area = (long) (right - left + 1) * (bottom - top + 1);
                if (left >= right || top >= bottom || area > 16_384) continue;
                remaining -= area;
                if (remaining < 0) return List.of();
                var bow = fitWindow(gray, labels, width, height, frame, start, end, gap,
                        left, top, right, bottom, hints, dashed);
                if (bow != null) found.add(bow);
            }
        }
        return List.copyOf(found);
    }

    private static boolean overlaps(
            List<PrintedSlurContours.Bow> bows,
            PrintedSlurOwners.Location a, PrintedSlurOwners.Location b, float gap) {
        return bows.stream().anyMatch(bow -> bow.above()
                && bow.start().x() <= a.x() + gap * .63f
                && bow.end().x() >= b.x() - gap * .63f
                && Math.abs(bow.start().y() - a.y()) <= gap * 1.65f
                && Math.abs(bow.end().y() - b.y()) <= gap * 1.65f);
    }

    private static PrintedSlurContours.Bow fitWindow(
            byte[] gray, byte[] labels, int width, int height,
            PrintedSlurPageCapture.Frame<? extends PrintedSlurOwners.Location> frame,
            PrintedSlurOwners.Location start, PrintedSlurOwners.Location end, float gap,
            int left, int top, int right, int bottom,
            PrintedSlurContours.HeadEvidence hints, boolean dashed) {
        int w = right - left + 1, h = bottom - top + 1;
        byte[] cores = new byte[w * h];
        Arrays.fill(cores, (byte) 255);
        float[] limits = new float[w];
        for (int x = 0; x < w; x++) {
            float progress = (left + x - start.x()) / (end.x() - start.x());
            limits[x] = start.y() + progress * (end.y() - start.y()) - top - gap * .34f;
            for (int y = 0; y < h && y < limits[x]; y++) {
                int pixel = (top + y) * width + left + x;
                if ((gray[pixel] & 255) < 162 && labels[pixel] != OmrMeasurePostProcessor.NOTEHEAD)
                    cores[y * w + x] = 0;
            }
        }
        var components = RawNumeralComponents.capture(
                cores, w, h, 0, 0, w - 1, h - 1, 161, () -> {}, () -> {});
        PrintedSlurContours.Bow result = null;
        for (var component : components.components()) {
            if (component.left < 2 || component.right > w - 3
                    || component.top < 2 || component.bottom > h - 3
                    || component.right - component.left < (end.x() - start.x()) * .90f
                    || Math.abs(left + component.left - start.x()) > gap * .74f
                    || Math.abs(left + component.right - end.x()) > gap * .75f)
                continue;
            var columns = bandColumns(component, w, top, left, gap, limits);
            if (columns.isEmpty()) continue;
            var bow = PrintedSlurContours.fitPixelCells(columns, frame.track());
            if (bow == null || !bow.above() || bow.asymmetric()
                    || bow.bendInGaps() < .43f || bow.bendInGaps() > 1.16f
                    || bow.rmsInGaps() > .055f
                    || dashed && !repeatedDashes(gray, width, bow, top + component.top,
                            gap, frame.left(), frame.right())) continue;
            var bound = PrintedSlurOwners.literalOctaveBinding(
                    "literal-octave", 0, bow, frame.heads(), gray, width, height, frame.track());
            if (bound.span().isEmpty()
                    || bound.span().orElseThrow().start().noteIndex() != start.owner()
                    || bound.span().orElseThrow().end().noteIndex() != end.owner()
                    || !PrintedSlurContours.originalOctaveCaps(
                            gray, labels, width, height, bow, frame.track(), hints, start, end))
                continue;
            if (result != null) return null;
            result = bow;
        }
        return result;
    }

    private static List<PrintedSlurContours.ForeignRun> bandColumns(
            RawNumeralComponents.Component component, int stride, int y0, int x0,
            float gap, float[] limits) {
        int size = component.right - component.left + 1;
        int[] top = new int[size], bottom = new int[size], count = new int[size];
        Arrays.fill(top, Integer.MAX_VALUE);
        for (int i = 0; i < component.size(); i++) {
            int pixel = component.pixel(i), x = pixel % stride - component.left, y = pixel / stride;
            top[x] = Math.min(top[x], y);
            bottom[x] = Math.max(bottom[x], y);
            count[x]++;
        }
        var columns = new ArrayList<PrintedSlurContours.ForeignRun>(size);
        for (int x = 0; x < size; x++) {
            int index = x + component.left;
            if (count[x] == 0 || count[x] != bottom[x] - top[x] + 1
                    || count[x] > Math.round(gap * .42f)
                    || limits[index] - bottom[x] < gap * .085f) return List.of();
            columns.add(new PrintedSlurContours.ForeignRun(x0 + index,
                    List.of(new PrintedSlurContours.RawBand(y0 + top[x], y0 + bottom[x]))));
        }
        return columns;
    }

    /** A row of several separated, nearly equally spaced dash cores remains above the bow. */
    private static boolean repeatedDashes(
            byte[] gray, int width, PrintedSlurContours.Bow bow, int apex, float gap,
            int frameLeft, int frameRight) {
        int left = Math.max(frameLeft, bow.start().x());
        int right = Math.min(frameRight, bow.end().x());
        for (int y = Math.max(0, apex - Math.round(gap * .86f)); y < apex - 1; y++) {
            int count = 0, previous = -1, run = 0;
            for (int x = left; x <= right + 1; x++) {
                if (x <= right && (gray[y * width + x] & 255) < 162) {
                    run++;
                    continue;
                }
                if (run == 0) continue;
                int begin = x - run;
                if (run >= Math.round(gap * .13f) && run <= Math.round(gap * .44f)) {
                    int spacing = previous < 0 ? 0 : begin - previous;
                    count = spacing >= gap * .34f && spacing <= gap * .71f ? count + 1 : 1;
                    if (count >= 6) return true;
                    previous = begin;
                } else {
                    count = 0;
                    previous = -1;
                }
                run = 0;
            }
        }
        return false;
    }
}
