// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

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

    /** A continuous upper contour can cover two adjacent groups of octave triplets. */
    static ScoreSlurSource captureTripletPhrases(ScoreSlurSource prior, List<Integer> owners,
            List<ScoreNoteEvent> notes, MeasureRegion bar, byte[] raw, byte[] gray, byte[] labels,
            int width, int height, int page) {
        var captured = new ArrayList<>(prior.spans());
        var deferred = new ArrayList<>(prior.candidates());
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        captureUpperTripletPhrases(prior, captured, deferred, owners, notes, raw, gray, null,
                width, height, page, left, span, gap, false);
        captureUpperTripletPhrases(prior, captured, deferred, owners, notes, raw, gray, labels,
                width, height, page, left, span, gap, true);
        if (labels != null && labels.length == raw.length) {
            for (int first = 0; first < owners.size(); first += 6) {
                int last = first + 4;
                int a = bottomOwner(notes, owners, first), b = bottomOwner(notes, owners, last);
                var start = prior.noteOrigins().get(a).orElse(null);
                var end = prior.noteOrigins().get(b).orElse(null);
                if (start == null || end == null || start.pageIndex() != page || end.pageIndex() != page
                        || alreadyOwned(captured, start, end)) continue;
                var startNote = notes.get(a);
                var endNote = notes.get(b);
                float ax = left + span * startNote.positionInMeasure();
                float bx = left + span * endNote.positionInMeasure();
                var matches = deferred.stream().filter(c -> c.placement() == ScoreSlurSpan.Placement.BELOW
                        && c.lineType() == ScoreSlurSpan.LineType.SOLID && c.evidence().size() == 1
                        && c.start().pageIndex() == page && c.end().pageIndex() == page)
                        .filter(c -> {
                            var arc = c.evidence().get(0);
                            return arc.pageIndex() == page
                                    && Math.abs(arc.startX() * width - ax) <= .78f * gap
                                    && Math.abs(arc.endX() * width - bx) <= .78f * gap;
                        }).toList();
                if (matches.size() != 1) continue;
                var match = matches.get(0);
                var oldArc = match.evidence().get(0);
                var firstBow = lowerTripletContour(raw, labels, width, height, notes, owners,
                        first, last, left, span, gap, oldArc);
                var secondBow = lowerTripletContour(gray, labels, width, height, notes, owners,
                        first, last, left, span, gap, oldArc);
                if (!agrees(firstBow, secondBow)) continue;
                var arc = new ScoreSlurSpan.ArcEvidence(page,
                        firstBow.start().x() / (float) width, firstBow.start().y() / height,
                        firstBow.end().x() / (float) width, firstBow.end().y() / height);
                captured.add(new ScoreSlurPages.Span(match.id(), start, end,
                        ScoreSlurSpan.Placement.BELOW, ScoreSlurSpan.LineType.SOLID, List.of(arc)));
                deferred.remove(match);
            }
        }
        return captured.size() == prior.spans().size() ? prior
                : new ScoreSlurSource(captured, prior.noteOrigins(), deferred);
    }

    private static void captureUpperTripletPhrases(ScoreSlurSource prior,
            List<ScoreSlurPages.Span> captured, List<ScoreSlurPages.Span> deferred,
            List<Integer> owners, List<ScoreNoteEvent> notes,
            byte[] raw, byte[] gray, byte[] labels, int width, int height, int page,
            float left, float span, float gap, boolean shortGroup) {
        for (int first = 0; first < owners.size(); first += shortGroup ? 6 : 12) {
            int last = first + (shortGroup ? 4 : 10);
            int a = topOwner(notes, owners, first), b = topOwner(notes, owners, last);
            var start = prior.noteOrigins().get(a).orElse(null);
            var end = prior.noteOrigins().get(b).orElse(null);
            if (start == null || end == null || start.pageIndex() != page || end.pageIndex() != page
                    || alreadyOwned(captured, start, end)
                    || shortGroup && captured.stream().anyMatch(arc -> arc.start().pageIndex() == page
                        && arc.end().pageIndex() == page && arc.placement() == ScoreSlurSpan.Placement.ABOVE
                        && arc.start().noteIndex() <= start.noteIndex() && arc.end().noteIndex() >= end.noteIndex()))
                continue;
            var firstBow = tripletContour(raw, width, height, notes, owners, first, last, left, span, gap, shortGroup, null);
            var secondBow = tripletContour(gray, width, height, notes, owners, first, last, left, span, gap, shortGroup, null);
            if (shortGroup && labels != null && labels.length == raw.length) {
                if (firstBow == null)
                    firstBow = tripletContour(raw, width, height, notes, owners, first, last, left, span, gap, true, labels);
                if (secondBow == null)
                    secondBow = tripletContour(gray, width, height, notes, owners, first, last, left, span, gap, true, labels);
            }
            if (!agrees(firstBow, secondBow)) continue;
            var arc = new ScoreSlurSpan.ArcEvidence(page,
                    firstBow.start().x() / (float) width, firstBow.start().y() / height,
                    firstBow.end().x() / (float) width, firstBow.end().y() / height);
            var existing = deferred.stream().filter(candidate -> candidate.start().equals(start)
                    && candidate.end().equals(end) && candidate.placement() == ScoreSlurSpan.Placement.ABOVE)
                    .toList();
            if (existing.size() > 1 || existing.size() == 1
                    && existing.get(0).lineType() != ScoreSlurSpan.LineType.SOLID) continue;
            String id = existing.isEmpty() ? "printed-triplet-phrase:" + page + ":" + start.noteIndex()
                    : existing.get(0).id();
            captured.add(new ScoreSlurPages.Span(id, start, end, ScoreSlurSpan.Placement.ABOVE,
                    ScoreSlurSpan.LineType.SOLID, List.of(arc)));
            deferred.removeAll(existing);
        }
    }

    private static boolean agrees(PrintedSlurContours.Bow a, PrintedSlurContours.Bow b) {
        return a != null && b != null
                && Math.abs(a.start().x() - b.start().x()) <= 2
                && Math.abs(a.end().x() - b.end().x()) <= 2
                && Math.abs(a.start().y() - b.start().y()) <= 2
                && Math.abs(a.end().y() - b.end().y()) <= 2;
    }

    private static int bottomOwner(List<ScoreNoteEvent> notes, List<Integer> owners, int slot) {
        int a = owners.get(slot), b = owners.get(slot + 1);
        return notes.get(a).pageY() > notes.get(b).pageY() ? a : b;
    }

    /** Confirm a deferred contour from both planes before choosing the lower heads. */
    private static PrintedSlurContours.Bow lowerTripletContour(byte[] plane, byte[] labels, int width, int height,
            List<ScoreNoteEvent> notes, List<Integer> owners, int first, int last,
            float left, float span, float gap, ScoreSlurSpan.ArcEvidence prior) {
        int a = bottomOwner(notes, owners, first), b = bottomOwner(notes, owners, last);
        var start = notes.get(a);
        var end = notes.get(b);
        float ax = left + span * start.positionInMeasure(), bx = left + span * end.positionInMeasure();
        if (bx - ax < 3.5f * gap || bx - ax > 8f * gap) return null;
        boolean down = start.stemDirection() == -1 && end.stemDirection() == -1;
        float ay = start.pageY() * height, by = end.pageY() * height;
        if (down) {
            var budget = new PrintedBeamShafts.Budget(60_000);
            var firstShaft = ClosedOctaveTripletBars.markedDyadShaft(plane, width, height,
                    notes.get(topOwner(notes, owners, first)), start, left, span, gap, -1, budget);
            var lastShaft = ClosedOctaveTripletBars.markedDyadShaft(plane, width, height,
                    notes.get(topOwner(notes, owners, last)), end, left, span, gap, -1, budget);
            if (firstShaft == null || lastShaft == null) return null;
            ay = firstShaft.endY();
            by = lastShaft.endY();
        }
        float hint = start.pageY() * height + start.staffStep() * gap * .5f;
        var rules = PrintedDirectionStaff.printedRules(plane, width, height, bx, hint, gap);
        if (rules == null) return null;
        var track = StaffPitchTrack.forPrintedSlurs(plane, width, height, rules[0] - 4 * rules[1], rules[0], rules[1]);
        if (track == null) return null;
        var heads = owners.subList(first, last + 2).stream().map(i -> {
            var event = notes.get(i);
            return new PrintedSlurOwners.GeometryHead(
                    i, left + span * event.positionInMeasure(), event.pageY() * height, 0, 1);
        }).toList();
        var hints = PrintedSlurOwners.headEvidence(heads, plane, width, height, track);
        int x0 = Math.round(ax - 1.6f * gap), x1 = Math.round(bx + (down ? .86f : 1.17f) * gap);
        int y0 = Math.round(Math.min(ay, by) - (down ? 1.1f : .38f) * gap);
        int y1 = Math.round(Math.max(ay, by) + 2.64f * gap);
        if (x0 < 0 || y0 < 0 || x1 >= width || y1 >= height) return null;
        var graph = PrintedSlurContours.find(plane, labels, width, height, x0, y0, x1, y1, track, null, hints);
        if (graph.limitReached()) return null;
        PrintedSlurContours.Bow owned = null;
        for (var bow : graph.bows()) {
            if (bow.above() || bow.bendInGaps() < .56f || bow.bendInGaps() > 1.70f
                    || bow.rmsInGaps() > .075f
                    || Math.abs(bow.start().x() - prior.startX() * width) > .33f * gap
                    || Math.abs(bow.end().x() - prior.endX() * width) > .33f * gap
                    || Math.abs(bow.start().y() - prior.startY() * height) > .33f * gap
                    || Math.abs(bow.end().y() - prior.endY() * height) > .33f * gap
                    || bow.start().y() - ay < .10f * gap || bow.end().y() - by < .10f * gap
                    || bow.start().y() - ay > 1.32f * gap || bow.end().y() - by > 1.32f * gap) continue;
            if (owned != null) return null;
            owned = bow;
        }
        return owned;
    }

    private static int topOwner(List<ScoreNoteEvent> notes, List<Integer> owners, int slot) {
        int first = owners.get(slot), second = owners.get(slot + 1);
        return notes.get(first).pageY() < notes.get(second).pageY() ? first : second;
    }

    private static float upperEdge(byte[] plane, int width, int height,
            List<ScoreNoteEvent> notes, List<Integer> owners, int slot, float left, float span, float gap) {
        int upperId = topOwner(notes, owners, slot);
        var upper = notes.get(upperId);
        if (upper.stemDirection() != 1) return upper.pageY() * height;
        var lower = notes.get(upperId == owners.get(slot) ? owners.get(slot + 1) : owners.get(slot));
        var shaft = ClosedOctaveTripletBars.markedDyadShaft(plane, width, height, upper, lower,
                left, span, gap, 1, new PrintedBeamShafts.Budget(40_000));
        return shaft == null ? Float.NaN : shaft.endY();
    }

    private static PrintedSlurContours.Bow tripletContour(byte[] plane, int width, int height,
            List<ScoreNoteEvent> notes, List<Integer> owners, int first, int last,
            float left, float span, float gap, boolean shortGroup, byte[] labels) {
        var a = notes.get(topOwner(notes, owners, first));
        var b = notes.get(topOwner(notes, owners, last));
        float ax = left + span * a.positionInMeasure(), bx = left + span * b.positionInMeasure();
        if (bx - ax < (shortGroup ? 4.4f : 10f) * gap || bx - ax > (shortGroup ? 6.3f : 18f) * gap) return null;
        float ay = upperEdge(plane, width, height, notes, owners, first, left, span, gap);
        float by = upperEdge(plane, width, height, notes, owners, last, left, span, gap);
        if (!Float.isFinite(ay + by)) return null;
        float hint = height * b.pageY() + b.staffStep() * gap * .5f;
        var rules = PrintedDirectionStaff.printedRules(plane, width, height, bx, hint, gap);
        if (rules == null) return null;
        var track = StaffPitchTrack.forPrintedSlurs(plane, width, height, rules[0] - 4 * rules[1], rules[0], rules[1]);
        if (track == null) return null;
        int x0 = Math.round(ax - .86f * gap), x1 = Math.round(bx + .86f * gap);
        int y0 = Math.round(Math.min(ay, by) - 4.1f * gap), y1 = Math.round(Math.max(ay, by) + .36f * gap);
        if (x0 < 0 || x1 >= width || y0 < 0 || y1 >= height || y1 - y0 > 189) return null;
        int cropWidth = x1 - x0 + 1, cropHeight = y1 - y0 + 1;
        byte[] cropped = labels == null ? null : withoutStaffInk(
                plane, labels, width, x0, y0, cropWidth, cropHeight, track);
        int xOffset = cropped == null ? 0 : x0, yOffset = cropped == null ? 0 : y0;
        var graph = cropped == null
                ? RawNumeralComponents.captureLongWindow(plane, width, height, x0, y0, x1, y1, 162)
                : RawNumeralComponents.captureLongWindow(cropped, cropWidth, cropHeight,
                        0, 0, cropWidth - 1, cropHeight - 1, 162);
        float extension = shortGroup ? .65f : .52f;
        float minimumClearance = shortGroup ? .35f : .42f;
        PrintedSlurContours.Bow owned = null;
        for (var component : graph.components()) {
            float insetLeft = component.left + xOffset - ax, insetRight = bx - component.right - xOffset;
            if (component.left + xOffset <= x0 || component.right + xOffset >= x1
                    || component.top + yOffset <= y0 || component.bottom + yOffset >= y1
                    || insetLeft < -extension * gap || insetLeft > 1.20f * gap
                    || insetRight < -extension * gap || insetRight > 1.20f * gap
                    || component.right - component.left < (bx - ax) * .86f) continue;
            var columns = cropped == null
                    ? contourColumns(component, width, gap, 0, 0)
                    : crossingColumns(component, cropWidth, gap, xOffset, yOffset,
                            plane, width, height, track);
            if (columns.isEmpty()) continue;
            var bow = PrintedSlurContours.fitPixelCells(columns, track);
            if (bow == null || !bow.above() || bow.rmsInGaps() > .09f
                    || bow.bendInGaps() < .60f || bow.bendInGaps() > 3.0f) continue;
            float startClearance = ay - bow.start().y(), endClearance = by - bow.end().y();
            if (startClearance < minimumClearance * gap || startClearance > 1.8f * gap
                    || endClearance < minimumClearance * gap || endClearance > 1.8f * gap) continue;
            if (cropped != null && !crossesPrintedRule(bow, track, gap)) continue;
            if (owned != null) return null;
            owned = bow;
        }
        return owned;
    }

    private static List<PrintedSlurContours.ForeignRun> contourColumns(
            RawNumeralComponents.Component component, int width, float gap, int xOffset, int yOffset) {
        int size = component.right - component.left + 1;
        int[] top = new int[size], bottom = new int[size], count = new int[size];
        Arrays.fill(top, Integer.MAX_VALUE);
        for (int i = 0; i < component.size(); i++) {
            int pixel = component.pixel(i), x = pixel % width - component.left, y = pixel / width;
            top[x] = Math.min(top[x], y);
            bottom[x] = Math.max(bottom[x], y);
            count[x]++;
        }
        var columns = new ArrayList<PrintedSlurContours.ForeignRun>(size);
        for (int x = 0; x < size; x++) {
            if (count[x] == 0 || count[x] != bottom[x] - top[x] + 1
                    || count[x] > Math.round(gap * .40f)) return List.of();
            columns.add(new PrintedSlurContours.ForeignRun(component.left + x + xOffset,
                    List.of(new PrintedSlurContours.RawBand(top[x] + yOffset, bottom[x] + yOffset))));
        }
        return columns;
    }

    private static boolean crossesPrintedRule(
            PrintedSlurContours.Bow bow, StaffPitchTrack track, float gap) {
        var start = bow.start();
        var end = bow.end();
        var a = track.at(start.x());
        var b = track.at(end.x());
        for (int i = 0; i < 5; i++) {
            float from = start.y() - (a[0] - i * a[1]), to = end.y() - (b[0] - i * b[1]);
            if (Math.abs(from) >= .21f * gap && Math.abs(to) >= .21f * gap && from * to < 0)
                return true;
        }
        return false;
    }

    private static List<PrintedSlurContours.ForeignRun> crossingColumns(
            RawNumeralComponents.Component component, int stride, float gap, int x0, int y0,
            byte[] plane, int width, int height, StaffPitchTrack track) {
        var pixels = new java.util.BitSet();
        for (int i = 0; i < component.size(); i++) pixels.set(component.pixel(i));
        var columns = new ArrayList<PrintedSlurContours.ForeignRun>();
        int trailingRule = 0;
        for (int x = component.left; x <= component.right; x++) {
            var bands = new ArrayList<PrintedSlurContours.RawBand>();
            for (int y = component.top; y <= component.bottom; y++) {
                if (!pixels.get(y * stride + x)) continue;
                int first = y;
                while (y < component.bottom && pixels.get((y + 1) * stride + x)) y++;
                bands.add(new PrintedSlurContours.RawBand(first + y0, y + y0));
            }
            if (bands.isEmpty()) return List.of();
            bands.sort(java.util.Comparator.comparingInt(
                    (PrintedSlurContours.RawBand b) -> b.last() - b.first()).reversed());
            var chosen = bands.get(0);
            int column = x + x0;
            boolean rim = liesOnPrintedRule(chosen, plane, width, height, column, track, gap);
            trailingRule = rim ? trailingRule + 1 : 0;
            if (chosen.last() - chosen.first() + 1 > .52f * gap) return List.of();
            for (int i = 1; i < bands.size(); i++) {
                var spur = bands.get(i);
                if (chosen.last() - chosen.first() <= spur.last() - spur.first()
                        || !liesOnPrintedRule(spur, plane, width, height, column, track, gap))
                    return List.of();
            }
            columns.add(new PrintedSlurContours.ForeignRun(column, List.of(chosen)));
        }
        if (trailingRule > 2 || columns.size() - trailingRule < 3) return List.of();
        return columns.subList(0, columns.size() - trailingRule);
    }

    private static boolean liesOnPrintedRule(PrintedSlurContours.RawBand band, byte[] plane,
            int width, int height, int column, StaffPitchTrack track, float gap) {
        if (band.last() - band.first() + 1 > .19f * gap) return false;
        var rules = track.at(column);
        int staffLine = Math.round((rules[0] - (band.first() + band.last()) * .5f) / rules[1]);
        int center = Math.round(rules[0] - staffLine * rules[1]);
        return staffLine >= 0 && staffLine <= 4
                && Math.max(Math.abs(band.first() - center), Math.abs(band.last() - center)) <= .21f * gap
                && FlagRootStaffRule.matches(plane, width, height, center, column, rules[1], 165);
    }

    private static byte[] withoutStaffInk(byte[] plane, byte[] labels, int width, int x0, int y0,
            int cropWidth, int cropHeight, StaffPitchTrack track) {
        int height = plane.length / width;
        byte[] crop = new byte[cropWidth * cropHeight];
        for (int x = 0; x < cropWidth; x++) {
            int column = x + x0;
            var frame = track.at(column);
            for (int y = 0; y < cropHeight; y++) {
                int row = y + y0;
                int at = row * width + column;
                byte value = plane[at];
                if (labels[at] == OmrMeasurePostProcessor.STAFF) {
                    int line = Math.round((frame[0] - row) / frame[1]);
                    int center = Math.round(frame[0] - line * frame[1]);
                    if (line >= 0 && line <= 4 && Math.abs(row - center) <= .21f * frame[1]
                            && FlagRootStaffRule.matches(plane, width, height, center, column, frame[1], 165))
                        value = (byte) 255;
                }
                crop[y * cropWidth + x] = value;
            }
        }
        return crop;
    }

    /** Pick complete above-head arcs between independently proved four-column octave groups. */
    static ScoreSlurSource captureQuartets(
            ScoreSlurSource source, List<ClosedSixteenthDyads.Dyad> pairs, MeasureRegion region,
            byte[] original, byte[] normalized, byte[] labels,
            int width, int height, int page, float gap) {
        return captureGroups(source, pairs, region, original, normalized, labels, width, height, page, gap, 4, 0);
    }

    static ScoreSlurSource captureTrailingThree(
            ScoreSlurSource source, List<ClosedSixteenthDyads.Dyad> pairs, MeasureRegion region,
            byte[] original, byte[] normalized, byte[] labels,
            int width, int height, int page, float gap) {
        return captureGroups(source, pairs, region, original, normalized, labels, width, height, page, gap, 3, 1);
    }

    private static ScoreSlurSource captureGroups(
            ScoreSlurSource source, List<ClosedSixteenthDyads.Dyad> pairs, MeasureRegion region,
            byte[] original, byte[] normalized, byte[] labels,
            int width, int height, int page, float gap, int columns, int firstColumn) {
        if (labels == null) return source;
        float floor = region.bottom() * height - 2.1f * gap;
        var rawTrack = StaffPitchTrack.forPrintedSlurs(
                original, width, height, floor - 4 * gap, floor, gap);
        var normalizedTrack = StaffPitchTrack.forPrintedSlurs(
                normalized, width, height, floor - 4 * gap, floor, gap);
        if (rawTrack == null || normalizedTrack == null) return source;
        var heads = new ArrayList<PrintedSlurOwners.GeometryHead>(2 * pairs.size());
        for (var pair : pairs) {
            for (var head : List.of(pair.top(), pair.bottom()))
                heads.add(new PrintedSlurOwners.GeometryHead(head.owner(), head.x(), head.y(), 0, 1));
        }
        var rawHints = PrintedSlurOwners.headEvidence(heads, original, width, height, rawTrack);
        var normalizedHints = PrintedSlurOwners.headEvidence(
                heads, normalized, width, height, normalizedTrack);
        var spans = new ArrayList<>(source.spans());
        var existing = new ArrayList<>(spans);
        existing.addAll(source.candidates());
        var origins = new ArrayList<>(source.noteOrigins());
        boolean unowned = !source.hasOriginalOwners() && existing.isEmpty();
        for (int first = firstColumn; first + columns <= pairs.size(); first += 4) {
            int last = first + columns - 1;
            var a = heads.get(2 * first);
            var b = heads.get(2 * last);
            var start = origins.get(a.owner()).orElse(null);
            var end = origins.get(b.owner()).orElse(null);
            if (unowned) {
                start = new ScoreSlurPages.Anchor(page, a.owner());
                end = new ScoreSlurPages.Anchor(page, b.owner());
            }
            if (start == null || end == null || start.pageIndex() != page || end.pageIndex() != page
                    || alreadyOwned(existing, start, end)) continue;
            var quartet = pairs.subList(first, last + 1);
            var raw = quartetBow(original, labels, width, height, quartet, a, b, rawTrack, rawHints);
            var corrected = quartetBow(
                    normalized, labels, width, height, quartet, a, b, normalizedTrack,
                    normalizedHints);
            if (raw == null || corrected == null
                    || Math.abs(raw.start().x() - corrected.start().x()) > 2
                    || Math.abs(raw.end().x() - corrected.end().x()) > 2
                    || Math.abs(raw.start().y() - corrected.start().y()) > 2
                    || Math.abs(raw.end().y() - corrected.end().y()) > 2) continue;
            String id = "printed-octave-quartet:" + page + ":" + start.noteIndex();
            var arc = new ScoreSlurSpan.ArcEvidence(page,
                    raw.start().x() / (float) width, raw.start().y() / height,
                    raw.end().x() / (float) width, raw.end().y() / height);
            spans.add(new ScoreSlurPages.Span(id, start, end, ScoreSlurSpan.Placement.ABOVE,
                    ScoreSlurSpan.LineType.SOLID, List.of(arc)));
            origins.set(a.owner(), Optional.of(start));
            origins.set(b.owner(), Optional.of(end));
        }
        return spans.size() == source.spans().size() ? source
                : new ScoreSlurSource(spans, origins, source.candidates());
    }

    /** Finds literal bow contours over closed sixths, including a continuation across a barline. */
    static ScoreSlurSource captureSixthPhrases(ScoreSlurSource source,
            List<ClosedSixteenthDyads.Dyad> pairs, List<ClosedSixteenthDyads.Head> following,
            MeasureRegion region, byte[] original, byte[] normalized, int width, int height,
            int page, float gap) {
        var rawStaff = WrappedMeasureBoundaries.verifiedTripletStaff(original, width, height, region, true);
        var normalizedStaff = WrappedMeasureBoundaries.verifiedTripletStaff(normalized, width, height, region, true);
        if (rawStaff == null || normalizedStaff == null) return source;
        var upper = new ArrayList<ClosedSixteenthDyads.Head>();
        for (var pair : pairs) upper.add(pair.top());
        var continuation = following.stream()
                .sorted(java.util.Comparator.comparingDouble(ClosedSixteenthDyads.Head::x)).limit(8).toList();
        var lastWritten = upper.get(upper.size() - 1);
        if (continuation.size() == 8 && continuation.get(0).x() > lastWritten.x()
                && continuation.get(0).x() - lastWritten.x() <= gap * 7.0f
                && Math.abs(continuation.get(0).y() - lastWritten.y()) <= gap * 2.4f) {
            for (int i = 0; i < 8; i += 2) {
                var a = continuation.get(i);
                var b = continuation.get(i + 1);
                var hi = a.y() < b.y() ? a : b;
                var lo = hi == a ? b : a;
                if (b.x() - a.x() > .24f * gap || hi.note().staffStep() - lo.note().staffStep() != 5
                        || hi.note().beamCount() != 2 || lo.note().beamCount() != 2
                        || hi.note().stemDirection() != -1 || lo.note().stemDirection() != -1
                        || hi.note().clefBottomDiatonic() != lastWritten.note().clefBottomDiatonic()) break;
                upper.add(hi);
            }
        }
        var spans = new ArrayList<>(source.spans());
        var origins = new ArrayList<>(source.noteOrigins());
        var existing = new ArrayList<>(spans);
        existing.addAll(source.candidates());
        boolean unowned = !source.hasOriginalOwners() && existing.isEmpty();
        int lastClaim = -1;
        for (int first = 0; first < pairs.size(); first += 4) {
            if (first <= lastClaim) continue;
            var start = upper.get(first);
            var open = origins.get(start.owner()).orElse(null);
            if (unowned) open = new ScoreSlurPages.Anchor(page, start.owner());
            if (open == null || open.pageIndex() != page) continue;
            ScoreSlurPages.Span selected = null;
            int last = -1;
            for (int end : new int[] {first + 3, first + 7}) {
                if (end >= upper.size()) continue;
                var stop = upper.get(end);
                var close = origins.get(stop.owner()).orElse(null);
                if (unowned) close = new ScoreSlurPages.Anchor(page, stop.owner());
                if (close == null || close.pageIndex() != page || alreadyOwned(existing, open, close)) continue;
                float span = stop.x() - start.x();
                if (span < (end - first) * 2.35f * gap || span > (end - first) * 3.75f * gap)
                    continue;
                var raw = sixthPhraseContour(original, width, height, rawStaff, start, stop, gap);
                var corrected = sixthPhraseContour(normalized, width, height, normalizedStaff, start, stop, gap);
                if (raw == null || corrected == null
                        || Math.abs(raw.start().x() - corrected.start().x()) > .22f * gap
                        || Math.abs(raw.end().x() - corrected.end().x()) > 1
                        || Math.abs(raw.start().y() - corrected.start().y()) > .22f * gap
                        || Math.abs(raw.end().y() - corrected.end().y()) > 1) continue;
                if (selected != null) {
                    selected = null;
                    break;
                }
                selected = new ScoreSlurPages.Span("printed-sixth-phrase:" + page + ":" + open.noteIndex(),
                        open, close, ScoreSlurSpan.Placement.ABOVE, ScoreSlurSpan.LineType.SOLID,
                        List.of(new ScoreSlurSpan.ArcEvidence(page, raw.start().x() / (float) width,
                                raw.start().y() / height, raw.end().x() / (float) width, raw.end().y() / height)));
                last = end;
            }
            if (selected == null) continue;
            lastClaim = last;
            origins.set(start.owner(), Optional.of(selected.start()));
            origins.set(upper.get(last).owner(), Optional.of(selected.end()));
            spans.add(selected);
            existing.add(selected);
        }
        return spans.size() == source.spans().size() ? source
                : new ScoreSlurSource(spans, origins, source.candidates());
    }

    private static PrintedSlurContours.Bow sixthPhraseContour(byte[] plane, int width, int height,
            StaffPitchTrack staff, ClosedSixteenthDyads.Head start, ClosedSixteenthDyads.Head end, float gap) {
        int left = Math.round(start.x() - .53f * gap), right = Math.round(end.x() + .64f * gap);
        int top = Math.round(Math.min(start.y(), end.y()) - 3.8f * gap);
        int bottom = Math.round(Math.max(start.y(), end.y()) - .33f * gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height || bottom <= top
                || right - left > 480 || bottom - top > 150) return null;
        var graph = RawNumeralComponents.captureLongWindow(plane, width, height, left, top, right, bottom, 139);
        PrintedSlurContours.Bow owned = null;
        for (var component : graph.components()) {
            if (component.left <= left || component.right >= right || component.top <= top
                    || component.bottom >= bottom || Math.abs(component.left - start.x()) > .46f * gap
                    || Math.abs(component.right - end.x()) > .46f * gap) continue;
            int[] edge = new int[component.right - component.left + 1];
            java.util.Arrays.fill(edge, -1);
            for (int i = 0; i < component.size(); i++) {
                int pixel = component.pixel(i);
                int x = pixel % width - component.left, y = pixel / width;
                edge[x] = Math.max(edge[x], y);
            }
            float leftClear = (start.y() - edge[0]) / gap;
            float rightClear = (end.y() - edge[edge.length - 1]) / gap;
            if (leftClear < .43f || leftClear > 1.42f || rightClear < .43f || rightClear > 1.42f) continue;
            var cells = new ArrayList<PrintedSlurContours.ForeignRun>(edge.length);
            for (int i = 0; i < edge.length; i++) {
                if (edge[i] < 0 || i > 0 && Math.abs(edge[i] - edge[i - 1]) > gap * .24f) break;
                cells.add(new PrintedSlurContours.ForeignRun(component.left + i,
                        List.of(new PrintedSlurContours.RawBand(edge[i], edge[i]))));
            }
            if (cells.size() != edge.length) continue;
            var bow = PrintedSlurContours.fitPixelCells(cells, staff);
            if (bow == null || !bow.above() || bow.asymmetric() || bow.bendInGaps() < .82f
                    || bow.bendInGaps() > 2.75f || bow.rmsInGaps() > .085f) continue;
            if (owned != null) return null;
            owned = bow;
        }
        return owned;
    }

    private static boolean alreadyOwned(
            List<ScoreSlurPages.Span> arcs, ScoreSlurPages.Anchor a, ScoreSlurPages.Anchor b) {
        return arcs.stream().anyMatch(arc -> arc.start().equals(a) || arc.end().equals(b));
    }

    private static PrintedSlurContours.Bow quartetBow(
            byte[] gray, byte[] labels, int width, int height,
            List<ClosedSixteenthDyads.Dyad> quartet,
            PrintedSlurOwners.GeometryHead start, PrintedSlurOwners.GeometryHead end,
            StaffPitchTrack staff, PrintedSlurContours.HeadEvidence hints) {
        float gap = staff.at((start.x() + end.x()) * .5f)[1];
        int left = Math.round(.5f * (start.x() + quartet.get(0).bottom().x()) - .65f * gap);
        int right = Math.round(.5f * (end.x() + quartet.get(quartet.size() - 1).bottom().x()) + .65f * gap);
        float ymin = Math.min(start.y(), end.y()), ymax = Math.max(start.y(), end.y());
        for (var pair : quartet) {
            ymin = Math.min(ymin, pair.top().y());
            ymax = Math.max(ymax, pair.top().y());
        }
        int top = Math.round(ymin - 3 * gap), bottom = Math.round(ymax + .3f * gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return null;
        var result = PrintedSlurContours.find(
                gray, labels, width, height, left, top, right, bottom, staff, null, hints);
        if (result.limitReached()) return null;
        var bow = ownedQuartetBow(result.bows(), gray, labels, width, height, start, end, staff, hints, gap);
        if (bow != null || !result.bows().isEmpty()) return bow;
        result = PrintedSlurContours.findAboveStemTips(gray, width, height, left, top, right, bottom, staff);
        if (result.limitReached()) return null;
        return ownedQuartetBow(result.bows(), gray, labels, width, height, start, end, staff, hints, gap);
    }

    private static PrintedSlurContours.Bow ownedQuartetBow(
            List<PrintedSlurContours.Bow> bows, byte[] gray, byte[] labels, int width, int height,
            PrintedSlurOwners.GeometryHead start, PrintedSlurOwners.GeometryHead end,
            StaffPitchTrack staff, PrintedSlurContours.HeadEvidence hints, float gap) {
        PrintedSlurContours.Bow owned = null;
        for (var bow : bows) {
            float left = bow.start().x() - start.x(), right = end.x() - bow.end().x();
            float aboveStart = start.y() - bow.start().y(), aboveEnd = end.y() - bow.end().y();
            if (!bow.above() || bow.bendInGaps() < .45f || bow.bendInGaps() > 1.55f
                    || bow.rmsInGaps() > .07f || left < -.30f * gap || left > 1.16f * gap
                    || right < -.30f * gap || right > 1.16f * gap
                    || aboveStart < .30f * gap || aboveStart > 1.55f * gap
                    || aboveEnd < .30f * gap || aboveEnd > 1.55f * gap
                    || !PrintedSlurContours.originalOctaveCaps(
                            gray, labels, width, height, bow, staff, hints, start, end)) continue;
            if (owned != null) return null;
            owned = bow;
        }
        return owned;
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
