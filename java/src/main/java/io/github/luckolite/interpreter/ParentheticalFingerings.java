// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
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
    private record Owner(int index, int measure, float x, float gap, Glyph glyph) {}

    static Result capture(List<PrintedFingeringDigits.Mark> existing, ScorePageInterpretation score,
            List<SheetInterpreter.Word> words, byte[] labels, byte[] raw, byte[] normalized, int width, int height) {
        var unchanged = new Result(existing, List.of());
        if (score.notes().isEmpty() || score.notes().size() > 2048 || labels == null || raw == null
                || normalized == null || labels.length != (long) width * height
                || raw.length != labels.length || normalized.length != raw.length) return unchanged;
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
                if (gap < 10 || gap > 24 || word.top() * height < floor - .35f * gap
                        || word.bottom() * height > floor + 3.8f * gap
                        || (word.right() - word.left()) * width < gap
                        || (word.right() - word.left()) * width > gap * 3.2f) continue;
                var a = read(raw, width, height, word, floor, gap, references, false);
                var b = read(normalized, width, height, word, floor, gap, references, true);
                if (a == null || !a.equals(b)) continue;
                float x = (a.digit().left() + a.digit().right()) * .5f;
                var offers = frame.heads().stream().filter(head ->
                        eligible(notes.get(head.owner())) && Math.abs(head.x() - x) < (notes.get(head.owner()).stemDirection() < 0 ? 1.05f : .55f) * gap
                                && a.digit().top() - head.y() > .8f * gap
                                && shaftOwns(raw, normalized, width, height, head,
                                        notes.get(head.owner()), a.digit(), gap)).toList();
                if (offers.size() != 1) continue;
                var head = offers.get(0);
                var note = notes.get(head.owner());
                if (!frame.track().verifiedAt(head.x())
                        && !frame.track().verifiedAt(head.x() - gap * 2)) continue;
                found.add(new Owner(head.owner(), note.measureIndex(), head.x(), gap, a));
            }
            if (found.size() == 1) owners.add(found.get(0));
        }
        owners.sort(Comparator.comparingInt(Owner::measure).thenComparingDouble(Owner::x));
        var marks = new ArrayList<>(existing);
        var groups = new ArrayList<Group>();
        var used = new HashSet<Integer>();
        for (int i = 1; i < owners.size(); i++) {
            var start = owners.get(i - 1);
            var stop = owners.get(i);
            if (!start.glyph().opening() || stop.glyph().opening() || start.measure() != stop.measure()
                    || stop.x() - start.x() < 1.4f * start.gap()
                    || stop.x() - start.x() > 24 * start.gap()
                    || used.contains(start.index()) || used.contains(stop.index())
                    || conflict(existing, start.index()) || conflict(existing, stop.index())) continue;
            for (var owner : List.of(start, stop)) {
                var mark = new PrintedFingeringDigits.Mark(owner.index(), 3, PrintedFingeringDigits.Placement.BELOW);
                if (!marks.contains(mark)) marks.add(mark);
                used.add(owner.index());
            }
            groups.add(new Group(start.index(), stop.index(), PrintedFingeringDigits.Placement.BELOW));
        }
        if (groups.isEmpty()) return unchanged;
        marks.sort(Comparator.comparingInt(PrintedFingeringDigits.Mark::noteIndex)
                .thenComparing(PrintedFingeringDigits.Mark::placement));
        return new Result(List.copyOf(marks), List.copyOf(groups));
    }

    private static boolean conflict(List<PrintedFingeringDigits.Mark> existing, int owner) {
        return existing.stream().anyMatch(m -> m.noteIndex() == owner
                && m.placement() == PrintedFingeringDigits.Placement.BELOW && m.digit() != 3);
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
        boolean opening = orientation(word.text()) > 0;
        boolean clipped = false;
        for (var c : cc.components()) {
            var box = new Box(left + c.left, top + c.top, left + c.right, top + c.bottom);
            int direction = curve(c, stride, gap, rule);
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
        if (height >= 1.15f * gap && height < 1.35f * gap && bend >= .08f * gap
                && c.top > 0 && rule.get(c.top - 1)) return 1;
        return 0;
    }
}
