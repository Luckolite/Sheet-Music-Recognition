// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;

/** Preserves an isolated old-style fingering three when its dark lobes open to the left. */
final class DetachedFingeringThrees {
    private DetachedFingeringThrees() {}

    private record Box(int left, int top, int right, int bottom) {
        boolean same(Box other) {
            return other != null && Math.abs(left - other.left) <= 2 && Math.abs(top - other.top) <= 2
                    && Math.abs(right - other.right) <= 2 && Math.abs(bottom - other.bottom) <= 2;
        }
        boolean overlaps(Box other) {
            return left <= other.right && right >= other.left && top <= other.bottom && bottom >= other.top;
        }
    }

    private record Window(int left, int top, int right, int bottom, float center, float gap, float minimumShaftScore) {
        long area() {
            return (long) (right - left + 1) * (bottom - top + 1);
        }
    }

    static List<PrintedFingeringDigits.Mark> additional(
            List<PrintedFingeringDigits.Mark> original, List<PrintedFingeringDigits.Mark> added,
            List<ScoreNoteEvent> notes, List<PrintedSlurPageCapture.Frame<PrintedSlurOwners.GeometryHead>> frames,
            byte[] raw, byte[] normalized, int width, int height) {
        var occupied = new HashSet<Integer>();
        for (var prior : original) occupied.add(prior.noteIndex());
        for (var prior : added) occupied.add(prior.noteIndex());
        var recovered = new ArrayList<PrintedFingeringDigits.Mark>();
        var boxes = new ArrayList<Box>();
        var references = new ArrayList<MatchedDetachedThreeShape.Reference>();
        long[] budget = {1_400_000};
        for (var frame : frames) for (var head : frame.heads()) {
            if (occupied.contains(head.owner()) || head.grace() || !eligible(notes.get(head.owner())))
                continue;
            float[] at = frame.track().at(head.x());
            float gap = at[1];
            if (gap < 10 || gap > 24 || frame.heads().stream().anyMatch(other -> other.owner() != head.owner()
                    && Math.abs(other.x() - head.x()) < gap * .8f
                    && Math.abs(other.y() - head.y()) < gap * 3f)) continue;
            var note = notes.get(head.owner());
            var rawWindow = window(raw, width, height, head, note.stemDirection(), at[0], gap);
            var a = read(raw, width, height, rawWindow, budget);
            if (budget[0] < 0) return List.of();
            if (a == null) continue;
            var normalizedWindow = window(normalized, width, height, head, note.stemDirection(), at[0], gap);
            var b = read(normalized, width, height, normalizedWindow, budget);
            if (budget[0] < 0) return List.of();
            if (!a.same(b) || boxes.stream().anyMatch(box -> box.overlaps(a))) continue;
            boxes.add(a);
            occupied.add(head.owner());
            recovered.add(new PrintedFingeringDigits.Mark(head.owner(), 3, PrintedFingeringDigits.Placement.BELOW));
            if (references.size() < 32)
                references.add(reference(raw, normalized, width, height, rawWindow, normalizedWindow, a, b));
        }
        if (references.size() >= 2) {
            recovered.addAll(matched(occupied, boxes, references, notes, frames, raw, normalized, width, height));
            recovered.addAll(DetachedUpperStemFingerings.threes(original, added, recovered,
                    notes, frames, raw, normalized, width, height, references));
        }
        return recovered;
    }

    static List<MatchedDetachedThreeShape.Reference> provedReferences(
            List<PrintedFingeringDigits.Mark> marks, List<ScoreNoteEvent> notes,
            List<PrintedSlurPageCapture.Frame<PrintedSlurOwners.GeometryHead>> frames,
            byte[] raw, byte[] normalized, int width, int height) {
        var owners = new HashSet<Integer>();
        for (var mark : marks)
            if (mark.digit() == 3 && mark.placement() == PrintedFingeringDigits.Placement.BELOW)
                owners.add(mark.noteIndex());
        var found = new ArrayList<MatchedDetachedThreeShape.Reference>();
        long[] remaining = {600_000};
        for (var frame : frames) for (var head : frame.heads()) {
            if (!owners.contains(head.owner()) || !eligibleShape(notes.get(head.owner()))) continue;
            var at = frame.track().at(head.x());
            float gap = at[1];
            if (gap < 10 || gap > 24) continue;
            int stem = notes.get(head.owner()).stemDirection();
            var aWindow = window(raw, width, height, head, stem, at[0], gap);
            var bWindow = window(normalized, width, height, head, stem, at[0], gap);
            var a = retainedReference(raw, width, height, aWindow, remaining);
            var b = retainedReference(normalized, width, height, bWindow, remaining);
            if (remaining[0] < 0) return List.of();
            if (a == null || !a.same(b) || hasEnclosure(raw, width, height, a, gap, remaining)
                    || hasEnclosure(normalized, width, height, b, gap, remaining)) continue;
            owners.remove(head.owner());
            var rawForms = new ArrayList<float[]>();
            var normalizedForms = new ArrayList<float[]>();
            rawForms.add(vector(raw, width, a));
            normalizedForms.add(vector(normalized, width, b));
            for (int level : new int[]{80, 105, 165}) {
                var aa = retainedReference(raw, width, height, aWindow, remaining, level);
                var bb = retainedReference(normalized, width, height, bWindow, remaining, level);
                if (a.same(aa) && b.same(bb) && aa.same(bb)) {
                    rawForms.add(vector(raw, width, aa));
                    normalizedForms.add(vector(normalized, width, bb));
                }
            }
            found.add(new MatchedDetachedThreeShape.Reference(rawForms, normalizedForms));
            if (found.size() == 32) return List.copyOf(found);
        }
        return remaining[0] < 0 ? List.of() : List.copyOf(found);
    }

    private static Box retainedReference(byte[] image, int width, int height, Window window, long[] remaining) {
        return retainedReference(image, width, height, window, remaining, 135);
    }

    private static Box retainedReference(byte[] image, int width, int height, Window window,
            long[] remaining, int threshold) {
        if (window == null) return null;
        remaining[0] -= window.area();
        if (remaining[0] < 0) return null;
        var graph = RawNumeralComponents.capture(image, width, height,
                window.left(), window.top(), window.right(), window.bottom(), threshold, () -> {}, () -> {});
        Box found = null;
        for (var glyph : graph.components()) {
            if (!fits(glyph, window)) continue;
            var reading = JoinedDigitTemplates.inspect(image, width, height, new BitSet(),
                    glyph.left, glyph.top, glyph.right, glyph.bottom, () -> {});
            if (reading == null || reading.scores()[3] < .62f || reading.scores()[3] < reading.best() - .10f)
                continue;
            if (found != null) return null;
            found = new Box(glyph.left, glyph.top, glyph.right, glyph.bottom);
        }
        return found;
    }

    static boolean eligible(ScoreNoteEvent note) {
        return note.tupletDivisor() == 1 && eligibleShape(note);
    }

    private static boolean eligibleShape(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED && note.staffCount() == 1
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL && Math.abs(note.stemDirection()) == 1
                && note.beamCount() >= 1 && note.beamCount() <= 2
                && !note.crossStaffBeam() && note.harmonicSemitones() == 0 && note.openStringTouch() == null;
    }

    private static Window window(byte[] image, int width, int height, PrintedSlurOwners.Location head,
            int stem, float floor, float gap) {
        return window(image, width, height, head, stem, floor, gap, null, null);
    }

    private static Window window(byte[] image, int width, int height, PrintedSlurOwners.Location head,
            int stem, float floor, float gap, PrintedBeamShafts.Budget measuredTipWork,
            ContrastProbe contrast) {
        float minimumShaftScore = 0;
        float center = head.x();
        int top = Math.round(head.y() + gap * .9f), bottom = Math.round(head.y() + gap * 3.7f);
        if (stem == -1) {
            var shaft = PairedTremoloInk.stem(image, width, height, head.x(), head.y(), gap, -1);
            if (shaft == null) return null;
            center = shaft.x();
            top = Math.round(shaft.endY() + gap * .18f);
            if (measuredTipWork != null) {
                int tip = MeasuredStaffFingerings.physicalDownstemEnd(
                        image, width, height, head, shaft, gap, true, measuredTipWork);
                if (tip >= 0) {
                    boolean ordinaryTip = Math.abs(shaft.endY() - tip) <= gap * .18f;
                    minimumShaftScore = ordinaryTip ? .70f : .65f;
                    if (!ordinaryTip) top = tip + 1;
                } else if (contrast.ownsPrefix(image, width, height, head, shaft, gap)) {
                    minimumShaftScore = .69f;
                }
            }
            bottom = Math.round(Math.max(floor + gap * 2.5f, top + gap * 1.85f));
        }
        int left = Math.round(center - gap * .92f), right = Math.round(center + gap * .92f);
        if (left < 0 || right >= width || top < 0 || bottom >= height || top >= bottom
                || bottom - top > gap * 6.5f) return null;
        return new Window(left, top, right, bottom, center, gap, minimumShaftScore);
    }

    private record ContrastProbe(PrintedBeamShafts.Budget work, PrintedBeamShaftPixels.Frame frame) {
        ContrastProbe(byte[] image, int width, int height, PrintedBeamShafts.Budget work) {
            this(work, new PrintedBeamShaftPixels.Frame(image, image, width, height, 0, work::take, true));
        }

        boolean ownsPrefix(byte[] image, int width, int height, PrintedSlurOwners.Location head,
                PairedTremoloInk.Stem trace, float gap) {
            if (work.refused()) return false;
            var shafts = PrintedBeamShafts.at(image, width, height, head.x(), head.y(), gap, work, frame);
            if (work.refused() || shafts.size() != 1) return false;
            var prefix = shafts.get(0);
            // A short break between the shaft and the lower ink can hide its connected prefix.
            int shortfall = trace.endY() - prefix.endY();
            return prefix.direction() == 1 && shortfall > 0 && shortfall <= gap * .30f
                    && Math.abs(trace.x() - (prefix.left() + prefix.right()) * .5f) <= gap * .18f;
        }
    }

    private static Box read(byte[] image, int width, int height, PrintedSlurOwners.Location head,
            int stem, float floor, float gap, long[] budget) {
        return read(image, width, height, window(image, width, height, head, stem, floor, gap), budget);
    }

    private static Box read(byte[] image, int width, int height, Window window, long[] budget) {
        if (window == null) return null;
        budget[0] -= window.area();
        if (budget[0] < 0) return null;
        var dark = band(image, width, height, window, 80);
        if (dark == null) return null;
        budget[0] -= window.area();
        if (budget[0] < 0) return null;
        var core = band(image, width, height, window, 90);
        return dark.same(core) ? dark : null;
    }

    private static Box band(byte[] image, int width, int height, Window window, int threshold) {
        var graph = RawNumeralComponents.capture(image, width, height,
                window.left(), window.top(), window.right(), window.bottom(),
                threshold, () -> {}, () -> {});
        Box result = null;
        for (var glyph : graph.components()) {
            if (!fits(glyph, window) || !TripletRhythmDetector.isIsolatedThree(glyph, width)) continue;
            var shape = JoinedDigitTemplates.inspect(image, width, height, new BitSet(),
                    glyph.left, glyph.top, glyph.right, glyph.bottom, () -> {});
            if (shape == null || shape.digit() >= 0 && shape.digit() != 3 && shape.digit() != 8
                    || Math.max(shape.scores()[3], shape.scores()[8]) < .67f) continue;
            if (result != null) return null;
            result = new Box(glyph.left, glyph.top, glyph.right, glyph.bottom);
        }
        return result;
    }

    private static List<PrintedFingeringDigits.Mark> matched(
            HashSet<Integer> occupied, List<Box> boxes, List<MatchedDetachedThreeShape.Reference> references,
            List<ScoreNoteEvent> notes, List<PrintedSlurPageCapture.Frame<PrintedSlurOwners.GeometryHead>> frames,
            byte[] raw, byte[] normalized, int width, int height) {
        var recovered = new ArrayList<PrintedFingeringDigits.Mark>();
        long[] budget = {2_800_000};
        var measuredTipWork = new PrintedBeamShafts.Budget(1_200_000);
        var contrastWork = new PrintedBeamShafts.Budget(500_000);
        var rawContrast = new ContrastProbe(raw, width, height, contrastWork);
        var normalizedContrast = raw == normalized ? rawContrast
                : new ContrastProbe(normalized, width, height, contrastWork);
        for (var frame : frames) for (var head : frame.heads()) {
            var note = notes.get(head.owner());
            if (occupied.contains(head.owner()) || head.grace() || !eligibleShape(note)) continue;
            float[] at = frame.track().at(head.x());
            float gap = at[1];
            if (gap < 10 || gap > 24 || frame.heads().stream().anyMatch(other -> other.owner() != head.owner()
                    && Math.abs(other.x() - head.x()) < gap * .8f
                    && Math.abs(other.y() - head.y()) < gap * 3f)) continue;

            var a = window(raw, width, height, head, note.stemDirection(), at[0], gap, measuredTipWork, rawContrast);
            var candidates = matching(raw, width, height, a, references, false, budget);
            if (budget[0] < 0) return List.of();
            if (candidates.isEmpty()) continue;
            var b = window(normalized, width, height, head, note.stemDirection(), at[0], gap, measuredTipWork, normalizedContrast);
            var corroborated = matching(normalized, width, height, b, references, true, budget);
            if (budget[0] < 0) return List.of();
            Box match = null;
            boolean ambiguous = false;
            for (var candidate : candidates) {
                if (corroborated.stream().noneMatch(candidate::same)) continue;
                if (match != null && !match.same(candidate)) {
                    ambiguous = true;
                    break;
                }
                match = candidate;
            }
            if (ambiguous || match == null || boxes.stream().anyMatch(match::overlaps)) continue;
            if (note.tupletDivisor() > 1 && !separateTupletOwner(raw, normalized, width, height,
                    head, frame.heads(), notes, match, gap, budget)) continue;
            occupied.add(head.owner());
            boxes.add(match);
            recovered.add(new PrintedFingeringDigits.Mark(head.owner(), 3, PrintedFingeringDigits.Placement.BELOW));
        }
        return recovered;
    }

    private static List<Box> matching(byte[] image, int width, int height, Window window,
            List<MatchedDetachedThreeShape.Reference> references, boolean normalized, long[] remaining) {
        if (window == null) return List.of();
        remaining[0] -= 4 * window.area();
        if (remaining[0] < 0) return List.of();
        var matches = new ArrayList<Box>();
        var tested = new HashSet<Box>();
        for (int level : new int[]{80, 105, 135, 165}) {
            var graph = RawNumeralComponents.capture(image, width, height,
                    window.left(), window.top(), window.right(), window.bottom(), level, () -> {}, () -> {});
            for (var glyph : graph.components()) {
                if (!fits(glyph, window)) continue;
                var box = new Box(glyph.left, glyph.top, glyph.right, glyph.bottom);
                if (!tested.add(box)) continue;
                if (!MatchedDetachedThreeShape.proves(image, width, box.left(), box.top(), box.right(),
                        box.bottom(), references, normalized)
                        && (window.minimumShaftScore() == 0
                                || !matchesMeasuredTipThree(image, width, height, box, references, normalized,
                                        window.minimumShaftScore())))
                    continue;
                if (!hasEnclosure(image, width, height, box, window.gap(), remaining)) matches.add(box);
            }
        }
        return matches;
    }

    private static boolean matchesMeasuredTipThree(byte[] image, int width, int height, Box box,
            List<MatchedDetachedThreeShape.Reference> references, boolean normalized, float minimum) {
        var reading = JoinedDigitTemplates.inspect(image, width, height, new BitSet(),
                box.left(), box.top(), box.right(), box.bottom(), () -> {});
        return reading != null && (reading.digit() < 0 || reading.digit() == 3)
                && reading.scores()[3] >= minimum && reading.scores()[3] + .08f >= reading.best()
                && MatchedDetachedThreeShape.matchesReferences(image, width, box.left(), box.top(),
                        box.right(), box.bottom(), references, normalized);
    }

    private static boolean separateTupletOwner(byte[] raw, byte[] normalized, int width, int height,
            PrintedSlurOwners.Location owner, List<? extends PrintedSlurOwners.Location> heads,
            List<ScoreNoteEvent> notes, Box glyph, float gap, long[] remaining) {
        var note = notes.get(owner.owner());
        if (note.tupletDivisor() != 3 || note.beamCount() != 1) return false;
        PrintedSlurOwners.Location middle = null, first = null;
        for (var head : heads) {
            if (head.x() >= owner.x() || owner.x() - head.x() > gap * 10) continue;
            if (middle == null || head.x() > middle.x()) {
                first = middle;
                middle = head;
            } else if (head.x() < middle.x() && (first == null || head.x() > first.x())) {
                first = head;
            }
        }
        if (first == null || glyph.left() <= middle.x() + gap * .7f
                || !sameTuplet(notes.get(first.owner()), note) || !sameTuplet(notes.get(middle.owner()), note))
            return false;
        remaining[0] -= Math.round(gap * gap * 44);
        return remaining[0] >= 0 && endsTupletBeam(raw, width, height, first, middle, owner, gap, note.stemDirection())
                && endsTupletBeam(normalized, width, height, first, middle, owner, gap, note.stemDirection());
    }

    private static boolean sameTuplet(ScoreNoteEvent a, ScoreNoteEvent b) {
        return a.measureIndex() == b.measureIndex() && a.staffIndex() == b.staffIndex()
                && a.tupletDivisor() == b.tupletDivisor() && a.beamCount() == b.beamCount()
                && a.stemDirection() == b.stemDirection() && eligibleShape(a);
    }

    private static boolean endsTupletBeam(byte[] gray, int width, int height,
            PrintedSlurOwners.Location first, PrintedSlurOwners.Location middle,
            PrintedSlurOwners.Location owner, float gap, int direction) {
        var a = PairedTremoloInk.stem(gray, width, height, first.x(), first.y(), gap, direction);
        var b = PairedTremoloInk.stem(gray, width, height, middle.x(), middle.y(), gap, direction);
        var c = PairedTremoloInk.stem(gray, width, height, owner.x(), owner.y(), gap, direction);
        if (a == null || b == null || c == null || b.x() <= a.x() || c.x() <= b.x()) return false;
        if (!thickBeam(gray, width, height, a, b, direction, gap)
                || !thickBeam(gray, width, height, b, c, direction, gap))
            return false;

        int firstPaper = c.x() + Math.round(gap * .36f), lastPaper = c.x() + Math.round(gap * 1.13f);
        int reach = Math.round(gap * .40f), limit = Math.max(3, Math.round(gap * .22f));
        if (lastPaper >= width || c.endY() - 2 * reach < 0 || c.endY() + 2 * reach >= height)
            return false;
        float slope = (c.endY() - b.endY()) / (float) (c.x() - b.x());
        for (int x = firstPaper; x <= lastPaper; x++) {
            int center = Math.round(c.endY() + slope * (x - c.x()));
            int run = 0;
            for (int y = center - reach; y <= center + reach; y++) {
                run = (gray[y * width + x] & 255) < 155 ? run + 1 : 0;
                if (run >= limit) return false;
            }
        }
        return true;
    }

    private static boolean thickBeam(byte[] gray, int width, int height, PairedTremoloInk.Stem a,
            PairedTremoloInk.Stem b, int direction, float gap) {
        int span = b.x() - a.x(), delta = b.endY() - a.endY();
        if (span < gap * 1.7f || span > gap * 5 || Math.abs(delta) > gap * .75f) return false;
        int inward = direction * Math.round(gap * .13f);
        int ay = a.endY() + inward, by = b.endY() + inward;
        if (!BeamInkConnectivity.connected(gray, width, height, a.x(), ay, b.x(), by, gap))
            return false;
        int reach = Math.round(gap * .33f), thickness = Math.max(4, Math.round(gap * .25f));
        if (Math.min(ay, by) - reach < 0 || Math.max(ay, by) + reach >= height) return false;
        for (int x = a.x() + 2; x <= b.x() - 2; x++) {
            int y = Math.round(ay + (by - ay) * (x - a.x()) / (float) span);
            int run = 0;
            for (int yy = y - reach; yy <= y + reach && run < thickness; yy++)
                run = (gray[yy * width + x] & 255) < 155 ? run + 1 : 0;
            if (run < thickness) return false;
        }
        return true;
    }

    private static boolean fits(RawNumeralComponents.Component glyph, Window window) {
        float gap = window.gap();
        int w = glyph.right - glyph.left + 1, h = glyph.bottom - glyph.top + 1;
        return glyph.left > window.left() && glyph.right < window.right()
                && glyph.top > window.top() && glyph.bottom < window.bottom()
                && w >= gap * .39f && w <= gap * 1.10f && h >= gap * .8f && h <= gap * 1.55f
                && Math.abs((glyph.left + glyph.right) * .5f - window.center()) <= gap * .46f
                && glyph.size() >= w * h * .20f;
    }

    private static boolean hasEnclosure(byte[] raster, int width, int height, Box glyph, float gap, long[] remaining) {
        int reach = Math.round(gap * .68f), extra = Math.round(gap * .24f);
        int left = Math.max(0, glyph.left() - reach), right = Math.min(width - 1, glyph.right() + reach);
        int top = Math.max(0, glyph.top() - extra), bottom = Math.min(height - 1, glyph.bottom() + extra);
        remaining[0] -= (long) (right - left + 1) * (bottom - top + 1);
        if (remaining[0] < 0) return true;
        return enclosureFlank(raster, width, height, left, top, glyph.left() - 1, bottom, glyph, gap)
                || enclosureFlank(raster, width, height, glyph.right() + 1, top, right, bottom, glyph, gap);
    }

    private static boolean enclosureFlank(byte[] raster, int width, int height, int left,
            int top, int right, int bottom, Box glyph, float gap) {
        if (left >= right) return false;
        var graph = RawNumeralComponents.capture(raster, width, height, left, top, right, bottom, 135,
                () -> {}, () -> {});
        for (var c : graph.components()) {
            int separation = c.right < glyph.left() ? glyph.left() - c.right : c.left - glyph.right();
            int w = c.right - c.left + 1, h = c.bottom - c.top + 1;
            int overlap = Math.min(c.bottom, glyph.bottom()) - Math.max(c.top, glyph.top()) + 1;
            if (separation <= gap * .42f && w <= gap * .48f && h >= gap * .72f
                    && overlap >= (glyph.bottom() - glyph.top() + 1) * .66f) return true;
        }
        return false;
    }
    private static MatchedDetachedThreeShape.Reference reference(byte[] raw, byte[] normalized,
            int width, int height, Window rawWindow, Window normalizedWindow, Box a, Box b) {
        var first = new ArrayList<float[]>();
        var second = new ArrayList<float[]>();
        first.add(vector(raw, width, a));
        second.add(vector(normalized, width, b));
        var rawVariant = band(raw, width, height, rawWindow, 105);
        var normalizedVariant = band(normalized, width, height, normalizedWindow, 105);
        if (a.same(rawVariant) && b.same(normalizedVariant) && rawVariant.same(normalizedVariant)
                && !(a.equals(rawVariant) && b.equals(normalizedVariant))) {
            first.add(vector(raw, width, rawVariant));
            second.add(vector(normalized, width, normalizedVariant));
        }
        return new MatchedDetachedThreeShape.Reference(first, second);
    }

    private static float[] vector(byte[] image, int width, Box box) {
        return MatchedDetachedThreeShape.vector(image, width, box.left(), box.top(), box.right(), box.bottom());
    }




}
