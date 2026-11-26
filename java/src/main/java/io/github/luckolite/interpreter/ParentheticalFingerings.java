// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** Retains a printed opening and closing parenthesis around two independently owned fingerings. */
final class ParentheticalFingerings {
    private ParentheticalFingerings() {}

    record Group(int startNoteIndex, int endNoteIndex, PrintedFingeringDigits.Placement placement) {}
    record Result(List<PrintedFingeringDigits.Mark> marks, List<Group> groups) {}
    private record Box(int left, int top, int right, int bottom) {
        int width() { return right - left + 1; }
        int height() { return bottom - top + 1; }
    }
    private record Glyph(Box digit, Box curve, boolean opening, boolean ruleClipped) {}
    private record Owner(int index, int measure, float x, float gap, Glyph glyph, PrintedFingeringDigits.Placement side) {}

    static Result capture(List<PrintedFingeringDigits.Mark> existing, ScorePageInterpretation score,
            List<SheetInterpreter.Word> words, byte[] labels, byte[] raw, byte[] normalized, int width, int height) {
        var unchanged = new Result(existing, List.of());
        if (score.notes().isEmpty() || score.notes().size() > 2048 || labels == null || raw == null
                || normalized == null || labels.length != (long) width * height
                || raw.length != labels.length || normalized.length != raw.length) return unchanged;
        var ruled = staffRulePair(existing, score, words, raw, normalized, width, height);
        ruled = captureDetachedQuartetPairs(ruled, score, raw, normalized, width, height);
        ruled = captureRuledQuartetPairs(ruled, score, raw, normalized, width, height);
        if (!ruled.groups().isEmpty()) {
            existing = ruled.marks();
            unchanged = ruled;
        }
        var proposals = words.stream().filter(w -> orientation(w.text()) != 0).toList();
        if (proposals.isEmpty() || proposals.size() > 128) return unchanged;
        var notes = score.notes();
        var staffs = ScoreDynamicsDetector.alignStaffs(
                OmrScoreInterpreter.techniqueStaffs(labels, normalized, width, height, score.measures()), notes, height);
        if (staffs.isEmpty() || staffs.size() > 128) return unchanged;
        var heads = PrintedSlurContext.geometryHeads(notes, score.measures(), width, height);
        var prepared = PrintedSlurFrames.prepare(normalized, width, height, staffs, notes, heads);
        var extra = PrintedSlurFrames.prepareSupplemental(
                normalized, width, height, staffs, notes, heads, prepared, score.measures());
        var frames = new ArrayList<>(prepared.frames());
        frames.addAll(extra.frames());
        var references = DetachedFingeringThrees.provedReferences(
                existing, notes, frames, raw, normalized, width, height);
        var owners = new ArrayList<Owner>();
        for (var word : proposals) {
            var found = new ArrayList<Owner>();
            for (var frame : frames) {
                float center = (word.left() + word.right()) * width * .5f;
                if (center < frame.left() || center > frame.right()) continue;
                var local = frame.track().at(center);
                float gap = local[1], floor = local[0];
                boolean above = word.bottom() * height < floor - 4.6f * gap
                        && word.top() * height > floor - 10f * gap;
                if (gap < 10 || gap > 24 || !above && (word.top() * height < floor - .35f * gap
                        || word.bottom() * height > floor + 3.8f * gap)
                        || (word.right() - word.left()) * width < gap
                        || (word.right() - word.left()) * width > gap * 3.2f) continue;
                var a = read(raw, width, height, word, floor, gap, references, false);
                var b = read(normalized, width, height, word, floor, gap, references, true);
                if (a == null || (above ? !agreeAbove(a,b) : !a.equals(b))) continue;
                float x = (a.digit().left() + a.digit().right()) * .5f;
                var offers = frame.heads().stream().filter(head -> {
                    var note = notes.get(head.owner());
                    if (!eligible(note)) return false;
                    if (above) return Math.abs(head.x() - x) < .60f * gap
                            && head.y() - a.digit().bottom() > 1.6f * gap
                            && head.y() - a.digit().bottom() < 6.9f * gap
                            && aboveShaftOwns(raw, normalized, width, height, head, note, gap);
                    return Math.abs(head.x() - x) < (note.stemDirection() < 0 ? 1.05f : .55f) * gap
                            && a.digit().top() - head.y() > .8f * gap
                            && shaftOwns(raw, normalized, width, height, head, note, a.digit(), gap);
                }).toList();
                if (offers.size() != 1) continue;
                var head = offers.get(0);
                var note = notes.get(head.owner());
                if (!frame.track().verifiedAt(head.x())
                        && !frame.track().verifiedAt(head.x() - gap * 2)) continue;
                found.add(new Owner(head.owner(), note.measureIndex(), head.x(), gap, a, above ? PrintedFingeringDigits.Placement.ABOVE
                        : PrintedFingeringDigits.Placement.BELOW));
            }
            if (found.size() == 1) owners.add(found.get(0));
        }
        owners.sort(Comparator.comparingInt(Owner::measure).thenComparing(Owner::side).thenComparingDouble(Owner::x));
        var marks = new ArrayList<>(existing);
        var groups = new ArrayList<>(ruled.groups());
        var used = new HashSet<Integer>();
        for (var group : ruled.groups()) {
            used.add(group.startNoteIndex());
            used.add(group.endNoteIndex());
        }
        var retainedMarks = existing;
        for (int i = 1; i < owners.size(); i++) {
            var start = owners.get(i - 1);
            var stop = owners.get(i);
            if (!start.glyph().opening() || stop.glyph().opening() || start.measure() != stop.measure()
                    || start.side() != stop.side()
                    || start.side() == PrintedFingeringDigits.Placement.ABOVE
                            && Math.abs(start.glyph().digit().top() - stop.glyph().digit().top()) > .45f * start.gap()
                    || stop.x() - start.x() < 1.4f * start.gap()
                    || stop.x() - start.x() > 24 * start.gap()
                    || used.contains(start.index()) || used.contains(stop.index())
                    || retainedMarks.stream().anyMatch(mark -> (mark.noteIndex() == start.index() || mark.noteIndex() == stop.index())
                            && mark.placement() == start.side() && mark.digit() != 3)) continue;
            for (var owner : List.of(start, stop)) {
                var mark = new PrintedFingeringDigits.Mark(owner.index(), 3, owner.side());
                if (!marks.contains(mark)) marks.add(mark);
                used.add(owner.index());
            }
            groups.add(new Group(start.index(), stop.index(), start.side()));
        }
        if (groups.isEmpty()) return unchanged;
        marks.sort(Comparator.comparingInt(PrintedFingeringDigits.Mark::noteIndex)
                .thenComparing(PrintedFingeringDigits.Mark::placement));
        return new Result(List.copyOf(marks), List.copyOf(groups));
    }

    private record DetachedPair(Glyph opening, Glyph closing) {}

    /** The same detached fingering pair can supply expression and disprove a false head. */
    static boolean detachedQuartetFingeringOwnsHead(List<ScoreNoteEvent> row, MeasureRegion bar,
            ScoreNoteEvent response, byte[] raw, byte[] gray, int width, int height) {
        var pair = agreedDetachedQuartetPair(row, bar, raw, gray, width, height);
        if (pair == null) return false;
        var digit = pair.opening().digit();
        float x = headColumn(bar, response, width), y = response.pageY() * height;
        if (x < digit.left() || x > digit.right() || y < digit.top() || y > digit.bottom()) return false;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        for (var raster : new byte[][]{raw, gray}) {
            var budget = new PrintedBeamShafts.Budget(35_000);
            if (!PrintedBeamShafts.at(raster, width, height, x, y, gap, budget).isEmpty() || budget.refused())
                return false;
        }
        return true;
    }

    private static Result captureDetachedQuartetPairs(Result existing, ScorePageInterpretation score,
            byte[] raw, byte[] gray, int width, int height) {
        var bars = score.measures();
        var notes = score.notes();
        var rows = new ArrayList<List<Integer>>(bars.size());
        for (int i = 0; i < bars.size(); i++) rows.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) rows.get(notes.get(i).measureIndex()).add(i);
        var marks = new ArrayList<>(existing.marks());
        var groups = new ArrayList<>(existing.groups());
        for (int i = 0; i < bars.size(); i++) {
            var owners = rows.get(i);
            if (owners.size() != 16) continue;
            owners.sort(Comparator.comparingDouble(j -> notes.get(j).positionInMeasure()));
            int first = owners.get(5), last = owners.get(11);
            if (conflict(marks, first) || conflict(marks, last)
                    || groups.stream().anyMatch(g -> g.placement() == PrintedFingeringDigits.Placement.BELOW
                            && g.startNoteIndex() <= last && g.endNoteIndex() >= first)) continue;
            var row = owners.stream().map(notes::get).toList();
            var bar = bars.get(i);
            if (agreedDetachedQuartetPair(row, bar, raw, gray, width, height) == null
                    && !agreesOnDetachedDottedPair(row, bar, raw, gray, width, height)) continue;
            var side = PrintedFingeringDigits.Placement.BELOW;
            addMark(marks, first, 3, side);
            addMark(marks, last, 3, side);
            groups.add(new Group(first, last, side));
        }
        if (groups.size() == existing.groups().size()) return existing;
        marks.sort(Comparator.comparingInt(PrintedFingeringDigits.Mark::noteIndex)
                .thenComparing(PrintedFingeringDigits.Mark::placement));
        groups.sort(Comparator.comparingInt(Group::startNoteIndex));
        return new Result(List.copyOf(marks), List.copyOf(groups));
    }

    private static DetachedPair agreedDetachedQuartetPair(List<ScoreNoteEvent> row, MeasureRegion bar,
            byte[] raw, byte[] gray, int width, int height) {
        if (row.size() != 16 || row.stream().anyMatch(n -> !ClosedMonophonicSixteenths.regularQuartetHead(n)
                || n.stemDirection() != -1 || n.tupletDivisor() != 1 || n.augmentationDots() != 0
                || n.staffStep() < 9)) return null;
        var a = detachedQuartetPair(row, bar, raw, width, height);
        var b = detachedQuartetPair(row, bar, gray, width, height);
        return a != null && b != null && agreeAbove(a.opening(), b.opening())
                && agreeAbove(a.closing(), b.closing()) ? a : null;
    }

    private static DetachedPair detachedQuartetPair(List<ScoreNoteEvent> row, MeasureRegion bar,
            byte[] ink, int width, int height) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25) return null;
        var track = WrappedMeasureBoundaries.verifiedTripletStaff(ink, width, height, bar, true);
        if (track == null) return null;
        float first = headColumn(bar, row.get(5), width), last = headColumn(bar, row.get(11), width);
        if (!track.verifiedAt(first) || !track.verifiedAt(last)
                || last - first < 10f * gap || last - first > 16f * gap) return null;
        var a = readDetachedQuartetGlyph(ink, width, height, first, track.at(first)[0], gap, true);
        var b = readDetachedQuartetGlyph(ink, width, height, last, track.at(last)[0], gap, false);
        if (a == null || b == null || Math.abs(a.digit().top() - b.digit().top()) > .20f * gap
                || Math.abs(a.digit().bottom() - b.digit().bottom()) > .20f * gap
                || !MixedUpperLedgerBeamSource.provesUpperSixteenthLine(ink, width, height, track, bar, row)) return null;
        return new DetachedPair(a, b);
    }

    private static boolean agreesOnDetachedDottedPair(List<ScoreNoteEvent> row, MeasureRegion bar,
            byte[] raw, byte[] gray, int width, int height) {
        if (row.stream().anyMatch(n -> n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffCount() != 1
                    || n.staffStep() < 7 || n.stemDirection() != -1 || n.beamCount() < 2
                    || n.tupletDivisor() != 1 || n.crossStaffBeam())
                || row.stream().filter(n -> n.augmentationDots() == 1).count() < 6) return false;
        var a = detachedDottedPair(row, bar, raw, width, height);
        var b = raw == gray ? a : detachedDottedPair(row, bar, gray, width, height);
        return a != null && b != null && agreeAbove(a.opening(), b.opening())
                && agreeAbove(a.closing(), b.closing());
    }

    private static DetachedPair detachedDottedPair(List<ScoreNoteEvent> row, MeasureRegion bar,
            byte[] ink, int width, int height) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25) return null;
        var first = row.get(5);
        var last = row.get(11);
        float x1 = headColumn(bar, first, width), x2 = headColumn(bar, last, width);
        if (x2 - x1 < 10 * gap || x2 - x1 > 16 * gap) return null;
        var a = detachedDottedGlyph(ink, width, height, bar, gap, row.get(4), first, null);
        if (a == null) return null;
        var b = detachedDottedGlyph(ink, width, height, bar, gap, row.get(10), last, a);
        if (b == null || Math.abs(a.digit().top() - b.digit().top()) > .36f * gap
                || Math.abs(a.digit().bottom() - b.digit().bottom()) > .36f * gap) return null;
        return new DetachedPair(a, b);
    }

    private static Glyph detachedDottedGlyph(byte[] ink, int width, int height, MeasureRegion bar,
            float gap, ScoreNoteEvent previous, ScoreNoteEvent owner, Glyph openingGlyph) {
        float x = headColumn(bar, owner, width), y = owner.pageY() * height;
        float estimatedFloor = y + owner.staffStep() * gap * .5f;
        var rules = ClosedDottedSixteenths.localDottedPairStaff(ink, width, height, x, estimatedFloor, gap);
        // The sloping beam can cover the staff next to the owner. Sample the same staff alongside it.
        float beside = x - 4 * gap;
        if (rules == null && beside > bar.left() * width)
            rules = ClosedDottedSixteenths.localDottedPairStaff(ink, width, height, beside, estimatedFloor, gap);
        if (rules == null || Math.abs(rules[0] - estimatedFloor) > .5f * gap) return null;
        var glyph = readDetachedQuartetGlyph(ink, width, height, x, rules[0], gap,
                openingGlyph == null, true, openingGlyph);
        if (glyph == null) return null;
        var budget = new PrintedBeamShafts.Budget(50_000);
        var shafts = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
        var preceding = PrintedBeamShafts.at(ink, width, height,
                headColumn(bar, previous, width), previous.pageY() * height, gap, budget);
        return !budget.refused() && shafts.size() == 1 && preceding.size() == 1
                && shafts.get(0).direction() == 1 && preceding.get(0).direction() == 1
                && WrappedEighthTriplets.matchesBeamRails(ink, width, height, preceding.get(0), shafts.get(0), gap, 2)
                ? glyph : null;
    }

    private static float headColumn(MeasureRegion bar, ScoreNoteEvent note, int width) {
        return (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
    }

    private static Glyph readDetachedQuartetGlyph(byte[] ink, int width, int height,
            float x, float floor, float gap, boolean opening) {
        return readDetachedQuartetGlyph(ink, width, height, x, floor, gap, opening, false, null);
    }

    private static Glyph readDetachedQuartetGlyph(byte[] ink, int width, int height,
            float x, float floor, float gap, boolean opening, boolean dotted, Glyph reference) {
        float halfWidth = (dotted ? 1.62f : 1.45f) * gap;
        int left = Math.round(x - halfWidth), right = Math.round(x + halfWidth);
        int top = Math.round(floor + (dotted ? .12f : .22f) * gap), bottom = Math.round(floor + 2.35f * gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return null;
        var parts = RawNumeralComponents.captureLongWindow(ink, width, height, left, top, right, bottom, 165)
                .components().stream().filter(c -> c.size() >= .17f * gap * gap).toList();
        if (parts.size() != 2) return null;
        var a = parts.get(0);
        var b = parts.get(1);
        var parenthesis = (a.left < b.left) == opening ? a : b;
        var digit = parenthesis == a ? b : a;
        int gapBetween = opening ? digit.left - parenthesis.right : parenthesis.left - digit.right;
        var box = new Box(digit.left, digit.top, digit.right, digit.bottom);
        if (parenthesis.left <= left || parenthesis.right >= right || parenthesis.bottom >= bottom
                || Math.abs(x - (digit.left + digit.right) * .5f) > .32f * gap
                || gapBetween < .15f * gap || gapBetween > .48f * gap
                || parenthesis.top > digit.top - (dotted ? .12f : .15f) * gap || parenthesis.bottom < digit.bottom + (dotted ? -.12f : .20f) * gap
                || curve(parenthesis, width, gap, new BitSet()) != (opening ? 1 : -1)
                || box.width() < .63f * gap || box.width() > .98f * gap
                || box.height() < .93f * gap || box.height() > (dotted ? 1.32f : 1.27f) * gap) return null;
        var fit = JoinedDigitTemplates.inspect(ink, width, height, new BitSet(),
                box.left(), box.top(), box.right(), box.bottom(), () -> {});
        if (fit == null || fit.scores()[3] < (dotted ? .65f : .69f)
                || fit.best() - fit.scores()[3] > (dotted ? .08f : opening ? .015f : .045f)) return null;
        boolean lower = FingeringThreeInk.opening(ink, width, null, box.left(), box.top(),
                box.width(), box.height(), .57f, .83f);
        if (dotted && !FingeringThreeInk.opening(ink, width, null, box.left(), box.top(),
                box.width(), box.height(), .17f, .46f)) return null;
        if (!lower && (reference == null || !matchesPairedDetachedThree(ink, width, box, reference.digit()))) return null;
        return new Glyph(box, new Box(parenthesis.left, parenthesis.top, parenthesis.right, parenthesis.bottom),
                opening, false);
    }

    private static boolean matchesPairedDetachedThree(byte[] ink, int width, Box box, Box reference) {
        var shape = MatchedDetachedThreeShape.vector(ink, width, box.left(), box.top(), box.right(), box.bottom());
        var other = MatchedDetachedThreeShape.vector(
                ink, width, reference.left(), reference.top(), reference.right(), reference.bottom());
        float similarity = 0;
        for (int i = 0; i < shape.length; i++) similarity += shape[i] * other[i];
        return similarity >= Math.max(.90f, JoinedDigitTemplates.maxEightSimilarity(shape) + .12f);
    }

    private static Result captureRuledQuartetPairs(Result existing, ScorePageInterpretation score,
            byte[] raw, byte[] gray, int width, int height) {
        var notes = score.notes();
        var bars = score.measures();
        var rows = new ArrayList<List<Integer>>(bars.size());
        for (int i = 0; i < bars.size(); i++) rows.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) rows.get(notes.get(i).measureIndex()).add(i);
        var rests = new BitSet(bars.size());
        for (var rest : score.rests()) rests.set(rest.measureIndex());
        var marks = new ArrayList<>(existing.marks());
        var groups = new ArrayList<>(existing.groups());
        int[] sixteenthQuartets = {4, 4, 4, 4};
        ScoreMeterChange meter = null;
        int nextMeter = 0;
        for (int i = 0; i < bars.size(); i++) {
            while (nextMeter < score.meterChanges().size() && score.meterChanges().get(nextMeter).measureIndex() <= i)
                meter = score.meterChanges().get(nextMeter++);
            var owners = rows.get(i);
            var bar = bars.get(i);
            if (meter == null || meter.numerator() != 4 || meter.denominator() != 4 || owners.size() != 16
                    || rests.get(i) || bar.cadenceQuarterBeats() != 0) continue;
            owners.sort(Comparator.comparingDouble(j -> notes.get(j).positionInMeasure()));
            var row = owners.stream().map(notes::get).toList();
            if (row.stream().anyMatch(n -> !ClosedMonophonicSixteenths.regularQuartetHead(n)
                    || n.stemDirection() != -1 || n.tupletDivisor() != 1 || n.augmentationDots() != 0)) continue;
            int first = owners.get(5), last = owners.get(11);
            for (var side : PrintedFingeringDigits.Placement.values()) {
                boolean above = side == PrintedFingeringDigits.Placement.ABOVE;
                if (groups.stream().anyMatch(g -> g.placement() == side && g.startNoteIndex() <= last
                            && g.endNoteIndex() >= first)
                        || marks.stream().anyMatch(m -> m.placement() == side && m.digit() != 3
                                && (m.noteIndex() == last || m.noteIndex() == first && !(above && m.digit() == 2))))
                    continue;
                var original = ruledQuartetPair(row, bar, raw, width, height, above);
                var normalized = ruledQuartetPair(row, bar, gray, width, height, above);
                if (original == null || normalized == null
                        || !(above ? agreeAbove(original.opening(), normalized.opening())
                                && agreeAbove(original.closing(), normalized.closing()) : original.equals(normalized))
                        || !ClosedMonophonicSixteenths.confirmsCommonTimeRailClock(
                                raw, width, height, bars, i, row, sixteenthQuartets, true)
                        || !ClosedMonophonicSixteenths.confirmsCommonTimeRailClock(
                                gray, width, height, bars, i, row, sixteenthQuartets, true)) continue;
                addMark(marks, first, 3, side);
                addMark(marks, last, 3, side);
                groups.add(new Group(first, last, side));
            }
        }
        if (groups.size() == existing.groups().size()) return existing;
        marks.sort(Comparator.comparingInt(PrintedFingeringDigits.Mark::noteIndex)
                .thenComparing(PrintedFingeringDigits.Mark::placement));
        groups.sort(Comparator.comparingInt(Group::startNoteIndex)
                .thenComparing(Group::placement));
        return new Result(List.copyOf(marks), List.copyOf(groups));
    }

    private static DetachedPair ruledQuartetPair(List<ScoreNoteEvent> row, MeasureRegion bar,
            byte[] ink, int width, int height, boolean above) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25) return null;
        float floor = bar.bottom() * height - 2.1f * gap;
        var track = StaffPitchTrack.forPrintedSlurs(ink, width, height, floor - 4 * gap, floor, gap);
        if (track == null) return null;
        var first = row.get(5);
        var last = row.get(11);
        float x1 = headColumn(bar, first, width), x2 = headColumn(bar, last, width);
        if (x2 - x1 < 9 * gap || x2 - x1 > 16 * gap) return null;
        var a = readQuartetGlyph(ink, width, height, track, x1, first.pageY() * height, gap, true, above);
        var b = readQuartetGlyph(ink, width, height, track, x2, last.pageY() * height, gap, false, above);
        if (a == null || b == null || Math.abs(a.digit().top() - b.digit().top()) > .32f * gap
                || Math.abs(a.digit().bottom() - b.digit().bottom()) > .34f * gap) return null;
        return new DetachedPair(a, b);
    }

    private static Glyph readQuartetGlyph(byte[] ink, int width, int height, StaffPitchTrack track,
            float x, float headY, float gap, boolean opening, boolean above) {
        if (!track.verified() || above && !track.verifiedAt(x) && !track.verifiedAt(x - 2 * gap)) return null;
        float floor = track.at(x)[0];
        int center = Math.round(x);
        int left = center - Math.round(1.65f * gap), right = center + Math.round(1.4f * gap);
        int top = Math.round(floor + (above ? opening ? -8.03f : -7.79f : .045f) * gap);
        int bottom = Math.round(floor + (above ? opening ? -6.32f : -5.98f : 1.76f) * gap);
        var glyph = read(ink, width, height, left, top, right, bottom,
                opening, floor, gap, List.of(), false, above && !opening);
        if (glyph == null || glyph.ruleClipped() == above
                || Math.abs(x - (glyph.digit().left() + glyph.digit().right()) * .5f) > .46f * gap)
            return null;
        float separation = above ? headY - glyph.digit().bottom() : glyph.digit().top() - headY;
        return separation >= 1.85f * gap && separation <= 6.2f * gap ? glyph : null;
    }

    private static Result staffRulePair(List<PrintedFingeringDigits.Mark> existing,
            ScorePageInterpretation score, List<SheetInterpreter.Word> words,
            byte[] raw, byte[] gray, int width, int height) {
        var cues = words.stream().filter(word -> {
            String text = word.text().replace(" ", "");
            return text.equals("(s") || orientation(text) == 1;
        }).toList();
        if (cues.isEmpty() || cues.size() > 128) return new Result(existing, List.of());

        var byMeasure = new HashMap<Integer, List<Integer>>();
        var notes = score.notes();
        for (int i = 0; i < notes.size(); i++)
            byMeasure.computeIfAbsent(notes.get(i).measureIndex(), unused -> new ArrayList<>()).add(i);
        var marks = new ArrayList<>(existing);
        var groups = new ArrayList<Group>();
        var retainedMeasures = new HashSet<Integer>();
        var measures = score.measures();
        for (var cue : cues) {
            float y = (cue.top() * .32f + cue.bottom() * .68f) * height;
            for (var entry : byMeasure.entrySet()) {
                int index = entry.getKey();
                var owners = entry.getValue();
                if (index < 0 || index >= measures.size() || owners.size() != 16
                        || retainedMeasures.contains(index)) continue;
                var bar = measures.get(index);
                if (y < bar.top() * height || y > bar.bottom() * height
                        || cue.right() < bar.left() || cue.left() > bar.right()) continue;
                int start = owners.get(5), end = owners.get(11);
                if (conflict(existing, start) || conflict(existing, end)
                        || !FingeringAnnotationFilter.provesRuledParentheticalPair(
                                owners.stream().map(notes::get).toList(), bar, raw, gray, width, height,
                                cue.left() * width, cue.right() * width, y)) continue;
                addMark(marks, start, 3, PrintedFingeringDigits.Placement.BELOW);
                addMark(marks, end, 3, PrintedFingeringDigits.Placement.BELOW);
                groups.add(new Group(start, end, PrintedFingeringDigits.Placement.BELOW));
                retainedMeasures.add(index);
                retainUpperTwo(marks, notes, bar, start, owners.get(9), owners.get(13), raw, gray,
                        width, height);
            }
        }
        if (groups.isEmpty()) return new Result(existing, List.of());
        marks.sort(Comparator.comparingInt(PrintedFingeringDigits.Mark::noteIndex)
                .thenComparing(PrintedFingeringDigits.Mark::placement));
        groups.sort(Comparator.comparingInt(Group::startNoteIndex));
        return new Result(List.copyOf(marks), List.copyOf(groups));
    }

    private static void addMark(List<PrintedFingeringDigits.Mark> marks, int owner, int digit,
            PrintedFingeringDigits.Placement placement) {
        var mark = new PrintedFingeringDigits.Mark(owner, digit, placement);
        if (!marks.contains(mark)) marks.add(mark);
    }

    /** Two independently retained same-line samples disambiguate a weaker detached two. */
    private static void retainUpperTwo(List<PrintedFingeringDigits.Mark> marks, List<ScoreNoteEvent> notes,
            MeasureRegion bar, int owner, int referenceA, int referenceB,
            byte[] raw, byte[] gray, int width, int height) {
        var above = PrintedFingeringDigits.Placement.ABOVE;
        if (marks.stream().anyMatch(mark -> mark.noteIndex() == owner && mark.placement() == above)
                || !marks.contains(new PrintedFingeringDigits.Mark(referenceA, 2, above))
                || !marks.contains(new PrintedFingeringDigits.Mark(referenceB, 2, above))) return;
        for (byte[] ink : new byte[][] {raw, gray}) {
            var target = upperTwo(ink, width, height, bar, notes.get(owner));
            var first = upperTwo(ink, width, height, bar, notes.get(referenceA));
            var second = upperTwo(ink, width, height, bar, notes.get(referenceB));
            if (target == null || first == null || second == null
                    || !sameTwo(target, first) || !sameTwo(target, second)) return;
        }
        addMark(marks, owner, 2, above);
    }

    private static boolean sameTwo(float[] a, float[] b) {
        float similarity = 0;
        for (int i = 0; i < a.length; i++) similarity += a[i] * b[i];
        return similarity >= .87f;
    }

    private static float[] upperTwo(byte[] ink, int width, int height, MeasureRegion bar, ScoreNoteEvent head) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float x = (bar.left() + (bar.right() - bar.left()) * head.positionInMeasure()) * width;
        float y = head.pageY() * height;
        int left = Math.round(x - 1.16f * gap), right = Math.round(x + 1.16f * gap);
        int top = Math.round(y - 2.91f * gap), bottom = Math.round(y - .75f * gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return null;
        float[] found = null;
        for (var glyph : RawNumeralComponents.capture(ink, width, height, left, top, right, bottom,
                165, () -> {}, () -> {}).components()) {
            int w = glyph.right - glyph.left + 1, h = glyph.bottom - glyph.top + 1;
            if (glyph.left <= left || glyph.right >= right || glyph.top <= top || glyph.bottom >= bottom
                    || w < .70f * gap || w > 1.10f * gap || h < 1.00f * gap || h > 1.42f * gap
                    || Math.abs((glyph.left + glyph.right) * .5f - x) > .30f * gap) continue;
            var read = JoinedDigitTemplates.inspect(ink, width, height, new BitSet(),
                    glyph.left, glyph.top, glyph.right, glyph.bottom, () -> {});
            if (read == null || read.scores()[2] != read.best() || read.best() < .55f || read.best() - read.runner() < .012f)
                continue;
            if (found != null) return null;
            found = MatchedDetachedThreeShape.vector(ink, width, glyph.left, glyph.top, glyph.right, glyph.bottom);
        }
        return found;
    }

    static List<PrintedFingeringDigits.Mark> staffCrossedTwos(
            Result groups, List<PrintedFingeringDigits.Mark> retained, ScorePageInterpretation score,
            byte[] raw, byte[] normalized, int width, int height) {
        var above = PrintedFingeringDigits.Placement.ABOVE;
        var notes = score.notes();
        var measures = score.measures();
        var result = new ArrayList<PrintedFingeringDigits.Mark>();
        for (var group : groups.groups()) {
            if (group.placement() != above) continue;
            int owner = group.startNoteIndex();
            var head = notes.get(owner);
            int measure = head.measureIndex();
            if (head.stemDirection() != -1 || head.beamCount() != 2
                    || retained.stream().anyMatch(row -> row.noteIndex() == owner
                            && row.placement() == above && row.digit() != 3)) continue;
            var box = measures.get(measure);
            var rawProof = staffCrossedTwo(raw, width, height, box, head);
            var normalizedProof = staffCrossedTwo(normalized, width, height, box, head);
            if (rawProof == null || normalizedProof == null
                    || !sameBox(rawProof.box(), normalizedProof.box())) continue;
            int matches = 0;
            var supportingMeasures = new HashSet<Integer>();
            for (var ref : retained) {
                if (ref.digit() != 2 || ref.placement() != above) continue;
                var reference = notes.get(ref.noteIndex());
                if (reference.measureIndex() == measure) continue;
                var region = measures.get(reference.measureIndex());
                var a = upperTwo(raw, width, height, region, reference);
                var b = upperTwo(normalized, width, height, region, reference);
                if (!matchesObservedTwo(rawProof, a) || !matchesObservedTwo(normalizedProof, b)) continue;
                matches++;
                supportingMeasures.add(reference.measureIndex());
                if (matches >= 3 && supportingMeasures.size() >= 2) break;
            }
            if (matches >= 3 && supportingMeasures.size() >= 2)
                result.add(new PrintedFingeringDigits.Mark(owner, 2, above));
        }
        return List.copyOf(result);
    }

    private record StaffCrossedTwo(Box box, float[] shape, BitSet visible) {}

    private static StaffCrossedTwo staffCrossedTwo(byte[] ink, int width, int height,
            MeasureRegion bar, ScoreNoteEvent note) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 11 || gap > 24) return null;
        float x = (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
        float y = note.pageY() * height;
        int left = Math.round(x - .78f * gap), right = Math.round(x + .82f * gap);
        int top = Math.round(y - 3f * gap), bottom = Math.round(y - .96f * gap);
        int flank = Math.round(.65f * gap);
        if (left < flank || right + flank >= width || top < 0 || bottom >= height) return null;
        var ruled = new BitSet();
        int l = right, r = left, t = bottom, b = top;
        for (int row = top; row <= bottom; row++) {
            int west = 0, east = 0;
            for (int d = 1; d <= flank; d++) {
                if ((ink[row * width + left - d] & 255) < 165) west++;
                if ((ink[row * width + right + d] & 255) < 165) east++;
            }
            if (west >= .85f * flank && east >= .85f * flank) {
                ruled.set(row);
                continue;
            }
            for (int col = left; col <= right; col++) {
                if ((ink[row * width + col] & 255) >= 165) continue;
                l = Math.min(l, col);
                r = Math.max(r, col);
                t = Math.min(t, row);
                b = Math.max(b, row);
            }
        }
        if (l <= left || r >= right || t <= top || b >= bottom
                || r - l + 1 < .72f * gap || r - l + 1 > 1.13f * gap
                || b - t + 1 < .95f * gap || b - t + 1 > 1.47f * gap) return null;
        int staffStart = ruled.nextSetBit(t);
        if (staffStart <= t || staffStart >= b) return null;
        int staffEnd = ruled.nextClearBit(staffStart);
        int next = ruled.nextSetBit(staffEnd);
        if (staffEnd >= b || staffEnd - staffStart < 2 || staffEnd - staffStart > .30f * gap
                || next >= 0 && next <= b) return null;
        var seen = new BitSet(16 * 24);
        for (int row = 0; row < 24; row++) {
            float rawY = t + (row + .5f) * (b - t + 1) / 24 - .5f;
            if (rawY <= staffStart - 1 || rawY >= staffEnd)
                seen.set(row * 16, (row + 1) * 16);
        }
        if (seen.cardinality() < 16 * 24 * .65f) return null;
        return new StaffCrossedTwo(new Box(l,t,r,b),
                MatchedDetachedThreeShape.vector(ink, width, l, t, r, b), seen);
    }

    private static boolean matchesObservedTwo(StaffCrossedTwo proof, float[] other) {
        if (other == null) return false;
        float xy = 0, xx = 0, yy = 0;
        var target = proof.shape();
        for (int i = proof.visible().nextSetBit(0); i >= 0; i = proof.visible().nextSetBit(i + 1)) {
            xy += target[i] * other[i];
            xx += target[i] * target[i];
            yy += other[i] * other[i];
        }
        return xx > 0 && yy > 0 && xy >= .88f * Math.sqrt(xx * yy);
    }

    private static boolean conflict(List<PrintedFingeringDigits.Mark> existing, int owner) {
        return existing.stream().anyMatch(m -> m.noteIndex() == owner
                && m.placement() == PrintedFingeringDigits.Placement.BELOW && m.digit() != 3);
    }

    private static boolean agreeAbove(Glyph a, Glyph b) {
        return b != null && a.opening() == b.opening() && !a.ruleClipped() && !b.ruleClipped()
                && sameBox(a.digit(), b.digit()) && sameBox(a.curve(), b.curve());
    }

    private static boolean sameBox(Box a, Box b) {
        return Math.abs(a.left() - b.left()) <= 2 && Math.abs(a.top() - b.top()) <= 2
                && Math.abs(a.right() - b.right()) <= 2 && Math.abs(a.bottom() - b.bottom()) <= 2;
    }

    private static boolean aboveShaftOwns(byte[] raw, byte[] normalized, int width, int height,
            PrintedSlurOwners.Location head, ScoreNoteEvent note, float gap) {
        if (note.beamCount() == 0 || note.stemDirection() == 0) return false;
        var a = PairedTremoloInk.stem(raw, width, height, head.x(), head.y(), gap, note.stemDirection());
        var b = PairedTremoloInk.stem(normalized, width, height, head.x(), head.y(), gap, note.stemDirection());
        return a != null && b != null && Math.abs(a.x() - b.x()) <= 1 && Math.abs(a.endY() - b.endY()) <= 2;
    }

    private static boolean shaftOwns(byte[] raw, byte[] normalized, int width, int height,
            PrintedSlurOwners.Location head, ScoreNoteEvent note, Box digit, float gap) {
        var a = PairedTremoloInk.stem(raw, width, height, head.x(), head.y(), gap, note.stemDirection());
        var b = PairedTremoloInk.stem(normalized, width, height, head.x(), head.y(), gap, note.stemDirection());
        if (a == null || b == null || Math.abs(a.x() - b.x()) > 1 || Math.abs(a.endY() - b.endY()) > 2)
            return false;
        float center = (digit.left() + digit.right()) * .5f;
        return note.stemDirection() > 0 || digit.top() - a.endY() >= .1f * gap
                && Math.abs(a.x() - center) <= .55f * gap;
    }

    private static int orientation(String text) {
        var compact = text.replace(" ", "");
        return compact.equals("(3") || compact.equals("(8") ? 1
                : compact.equals("3)") || compact.equals("8)") ? -1 : 0;
    }

    private static boolean eligible(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED && note.staffIndex() == 0 && note.staffCount() == 1
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL && Math.abs(note.stemDirection()) == 1
                && note.beamCount() == 2 && note.tupletDivisor() == 1 && !note.crossStaffBeam()
                && note.openStringTouch() == null && note.harmonicSemitones() == 0;
    }

    private static Glyph read(byte[] image, int width, int height, SheetInterpreter.Word word,
            float floor, float gap, List<MatchedDetachedThreeShape.Reference> references, boolean normalized) {
        int left = Math.round(word.left() * width - .18f * gap);
        int right = Math.round(word.right() * width + .18f * gap);
        int top = Math.round(word.top() * height - .18f * gap);
        int bottom = Math.round(word.bottom() * height + .18f * gap);
        return read(image, width, height, left, top, right, bottom,
                orientation(word.text()) > 0, floor, gap, references, normalized, false);
    }

    private static Glyph read(byte[] image, int width, int height, int left, int top, int right, int bottom,
            boolean opening, float floor, float gap, List<MatchedDetachedThreeShape.Reference> references,
            boolean normalized, boolean compactClosingCurve) {
        int flank = Math.round(.9f * gap);
        if (left < flank || right + flank >= width || top < 0 || bottom >= height) return null;
        int stride = right - left + 1, tall = bottom - top + 1;
        if ((long) stride * tall > 16384) return null;
        var copied = new byte[stride * tall];
        var rule = new BitSet(tall);
        for (int y = top; y <= bottom; y++) {
            System.arraycopy(image, y * width + left, copied, (y - top) * stride, stride);
            if (Math.abs(y - floor) > .26f * gap) continue;
            int a = 0, b = 0;
            for (int x = 1; x <= flank; x++) {
                if ((image[y * width + left - x] & 255) < 165) a++;
                if ((image[y * width + right + x] & 255) < 165) b++;
            }
            if (Math.max(a, b) < .65f * flank) continue;
            Arrays.fill(copied, (y - top) * stride, (y - top + 1) * stride, (byte)255);
            rule.set(y - top);
        }
        var cc = RawNumeralComponents.capture(copied, stride, tall, 0, 0, stride - 1, tall - 1, () -> {}, () -> {});
        var curves = new ArrayList<Box>();
        var digits = new ArrayList<Box>();
        boolean clipped = false;
        for (var c : cc.components()) {
            var box = new Box(left + c.left, top + c.top, left + c.right, top + c.bottom);
            int direction = curve(c, stride, gap, rule, compactClosingCurve);
            if (direction == (opening ? 1 : -1)) {
                curves.add(box);
                clipped = c.top > 0 && rule.get(c.top - 1);
            }
            if (box.width() < .50f * gap || box.width() > 1.1f * gap
                    || box.height() < .80f * gap || box.height() > 1.4f * gap || c.size() < box.width() * box.height() * .25f)
                continue;
            var fit = JoinedDigitTemplates.inspect(image, width, height, new BitSet(),
                    box.left(), box.top(), box.right(), box.bottom(), () -> {});
            boolean openLobes = FingeringThreeInk.opening(image, width, null, box.left(), box.top(),
                    box.width(), box.height(), .17f, .46f)
                    && FingeringThreeInk.opening(image, width, null, box.left(), box.top(),
                            box.width(), box.height(), .57f, .83f);
            boolean literal = openLobes && fit != null && fit.scores()[3] >= .62f
                    && fit.scores()[3] >= fit.best() - .10f;
            if (literal || matchesFont(image, width, box, references, normalized)) digits.add(box);
        }
        if (curves.size() != 1 || digits.size() != 1) return null;
        var curve = curves.get(0);
        var digit = digits.get(0);
        int separation = opening ? digit.left() - curve.right() : curve.left() - digit.right();
        if (separation < -1 || separation > .42f * gap
                || curve.bottom() - digit.top() < .7f * digit.height()
                || Math.abs(curve.bottom() - digit.bottom()) > .5f * gap) return null;
        return new Glyph(digit, curve, opening, clipped);
    }

    private static boolean matchesFont(byte[] image, int width, Box box,
            List<MatchedDetachedThreeShape.Reference> references, boolean normalized) {
        var shape = MatchedDetachedThreeShape.vector(image, width, box.left(), box.top(), box.right(), box.bottom());
        float required = Math.max(.84f, JoinedDigitTemplates.maxEightSimilarity(shape) + .12f);
        int matches = 0;
        for (var reference : references) {
            for (var variant : normalized ? reference.normalized() : reference.raw()) {
                float dot = 0;
                for (int i = 0; i < shape.length; i++) dot += shape[i] * variant[i];
                if (dot < required) continue;
                matches++;
                break;
            }
        }
        return matches >= 2;
    }

    private static int curve(RawNumeralComponents.Component c, int stride, float gap, BitSet rule) {
        return curve(c, stride, gap, rule, false);
    }

    private static int curve(RawNumeralComponents.Component c, int stride, float gap, BitSet rule,
            boolean compactClosingCurve) {
        int width = c.right - c.left + 1, height = c.bottom - c.top + 1;
        if (width < .25f * gap || width > .72f * gap || c.size() < height * 1.7f
                || c.size() > width * height * .65f) return 0;
        float[] medians = new float[3];
        for (int band = 0; band < 3; band++) {
            int[] xs = new int[c.size()];
            int count = 0;
            for (int i = 0; i < c.size(); i++) {
                int p = c.pixel(i), y = p / stride - c.top;
                float fraction = y / (float) height;
                boolean include = switch (band) {
                    case 0 -> fraction < .25f;
                    case 1 -> fraction >= .38f && fraction < .62f;
                    default -> fraction >= .75f;
                };
                if (include) xs[count++] = p % stride;
            }
            if (count == 0) return 0;
            Arrays.sort(xs, 0, count);
            medians[band] = (xs[(count - 1) / 2] + xs[count / 2]) * .5f;
        }
        float bend = (medians[0] + medians[2]) * .5f - medians[1];
        if (height >= 1.35f * gap && height <= 2.7f * gap) {
            if (bend >= .10f * gap) return 1;
            if (bend <= -.10f * gap) return -1;
        }
        if (compactClosingCurve && height >= 1.26f * gap && height < 1.35f * gap && bend <= -.10f * gap)
            return -1;
        if (height >= 1.15f * gap && height < 1.35f * gap && bend >= .08f * gap
                && c.top > 0 && rule.get(c.top - 1)) return 1;
        return 0;
    }
}
