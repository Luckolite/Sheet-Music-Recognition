// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;

/** Conservative clearance for optional engraving on an independently proved primary-beam triplet. */
final class UnnumberedTripletInk {
    private UnnumberedTripletInk() {}

    record Rows(int first, int last) {}

    /** Preserve missing numerals only on closed single-rail groups recovered against both source planes. */
    static ScorePageInterpretation markRecoveredDownstemTriplets(ScorePageInterpretation score,
            List<Integer> recovered, byte[] raw, byte[] normalized, int width, int height) {
        if (recovered.isEmpty() || raw == null || normalized == null
                || raw.length != (long) width * height || normalized.length != raw.length) return score;
        var beam = score.beamSource();
        var bars = score.measures();
        var notes = score.notes();
        var selected = new java.util.HashSet<String>();
        for (var group : beam.resolve().groups()) {
            var original = group.source();
            if (original.tupletMark() != null || !original.bridges().equals(List.of(1, 1))
                    || !original.hooks().isEmpty()
                    || group.columns().stream().anyMatch(c -> !c.usable() || c.owners().size() != 1)) continue;
            var members = group.columns().stream().map(c -> notes.get(c.owners().get(0))).toList();
            int measure = members.get(0).measureIndex();
            if (!recovered.contains(measure) || members.stream().anyMatch(note ->
                    note.measureIndex() != measure || note.stemDirection() != -1 || note.beamCount() != 1
                            || note.tupletDivisor() != 3 || note.tupletNormalNotes() != 2
                            || note.augmentationDots() != 0)) continue;
            var bar = bars.get(measure);
            var rows = sourceRows(bar, bars, height);
            if (clearRecoveredDownstemTriplet(raw, width, height, bar, rows, members)
                    && clearRecoveredDownstemTriplet(normalized, width, height, bar, rows, members))
                selected.add(original.id());
        }
        if (selected.isEmpty()) return score;
        var groups = beam.groups().stream().map(g -> selected.contains(g.id())
                ? new ScoreBeamSource.Group(g.id(), g.columns(), g.bridges(), g.hooks(),
                        ScoreBeamSource.TupletMark.UNNUMBERED) : g).toList();
        return score.withBeamSource(new ScoreBeamSource(groups, beam.detachedColumns(), beam.noteOrigins()));
    }

    private static boolean clearRecoveredDownstemTriplet(byte[] ink, int width, int height,
            MeasureRegion bar, Rows rows, List<ScoreNoteEvent> members) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25) return false;
        var staff = WrappedMeasureBoundaries.verifiedTripletStaff(ink, width, height, bar);
        if (staff == null) staff = WrappedMeasureBoundaries.verifiedTripletStaff(ink, width, height, bar, true);
        if (staff == null) return false;
        float[] x = new float[3], y = new float[3];
        int firstTip = height, lastTip = 0;
        var traced = new PrintedBeamShafts.Shaft[3];
        boolean usedContrastTrace = false;
        var budget = new PrintedBeamShafts.Budget(100_000);
        for (int i = 0; i < 3; i++) {
            var note = members.get(i);
            x[i] = (bar.left() + note.positionInMeasure() * (bar.right() - bar.left())) * width;
            y[i] = note.pageY() * height;
            var shafts = PrintedBeamShafts.at(ink, width, height, x[i], y[i], gap, budget);
            if (budget.refused() || shafts.size() > 1) return false;
            var shaft = shafts.isEmpty() ? null : shafts.get(0);
            if (shaft == null) {
                // Timing can already have proved a faint shaft using local contrast.
                if (usedContrastTrace || OpeningShaftTrace.scan(ink, width, height, x[i], y[i], gap, -1) != null)
                    return false;
                shaft = OpeningShaftTrace.scan(ink, width, height, x[i], y[i], gap, 1);
                usedContrastTrace = true;
            }
            if (shaft == null || shaft.direction() != 1) return false;
            traced[i] = shaft;
            int tip = shaft.endY();
            firstTip = Math.min(firstTip, tip);
            lastTip = Math.max(lastTip, tip);
        }
        if (usedContrastTrace
                && (!WrappedEighthTriplets.matchesBeamRails(ink, width, height, traced[0], traced[1], gap, 1)
                        || !WrappedEighthTriplets.matchesBeamRails(ink, width, height, traced[1], traced[2], gap, 1)))
            return false;
        float top = Math.min(y[0], Math.min(y[1], y[2])), bottom = Math.max(y[0], Math.max(y[1], y[2]));
        return !centeredThree(ink, width, height, x[1], top - 3.4f * gap, top - .4f * gap, gap, rows, staff)
                && !centeredThree(ink, width, height, x[1], lastTip + .35f * gap,
                        lastTip + 3.65f * gap, gap, rows, staff)
                && clearRuledFingeringBands(ink, width, height, gap, x, y,
                        top, bottom, firstTip, lastTip, rows, staff);
    }

    /** A nearby system can place strokes inside an otherwise plausible search window. */
    static Rows sourceRows(MeasureRegion bar, List<MeasureRegion> page, int height) {
        float first = 0, last = 1;
        for (var region : page) {
            if (region.left() >= bar.right() || region.right() <= bar.left()) continue;
            if (region.bottom() < bar.top())
                first = Math.max(first, (region.bottom() + bar.top()) * .5f);
            if (region.top() > bar.bottom())
                last = Math.min(last, (region.top() + bar.bottom()) * .5f);
        }
        return new Rows((int)Math.ceil(first * height), Math.min(height - 1, (int)Math.floor(last * height)));
    }

    /** Check the bracket separately when a numeral alone could be a fingering. */
    static boolean bracketBesideBeam(
            byte[] ink, int width, int height, float gap, float first, float last,
            float headTop, float headBottom, float shaftTip, Rows rows, StaffPitchTrack track) {
        return bracketBesideBeam(ink, width, height, gap, first, last,
                headTop, headBottom, shaftTip, rows, track, false);
    }

    static boolean bracketBesideBeam(
            byte[] ink, int width, int height, float gap, float first, float last,
            float headTop, float headBottom, float shaftTip, Rows rows, StaffPitchTrack track,
            boolean verifyContinuingStroke) {
        return bracketBesideBeam(ink, width, height, gap, first, last, headTop, headBottom,
                shaftTip, rows, track, verifyContinuingStroke, List.of());
    }

    private static boolean bracketBesideBeam(
            byte[] ink, int width, int height, float gap, float first, float last,
            float headTop, float headBottom, float shaftTip, Rows rows, StaffPitchTrack track,
            boolean verifyContinuingStroke, List<PlayingTechniqueDetector.Word> directions) {
        return bracketBesideBeam(ink,width,height,gap,first,last,headTop,headBottom,shaftTip,rows,track,
                verifyContinuingStroke,directions,null,null);
    }

    private static boolean bracketBesideBeam(
            byte[] ink, int width, int height, float gap, float first, float last,
            float headTop, float headBottom, float shaftTip, Rows rows, StaffPitchTrack track,
            boolean verifyContinuingStroke, List<PlayingTechniqueDetector.Word> directions, float[] headsX, float[] headsY) {
        return bracketBesideBeam(ink, width, height, gap, first, last, headTop, headBottom, shaftTip,
                rows, track, verifyContinuingStroke, directions, headsX, headsY, false);
    }

    private static boolean bracketBesideBeam(
            byte[] ink, int width, int height, float gap, float first, float last,
            float headTop, float headBottom, float shaftTip, Rows rows, StaffPitchTrack track,
            boolean verifyContinuingStroke, List<PlayingTechniqueDetector.Word> directions,
            float[] headsX, float[] headsY, boolean octaveMarks) {
        int margin = Math.round(2.5f * gap);
        int left = Math.round(first - gap), right = Math.round(last + gap);
        float upper = Math.min(headTop, shaftTip), lower = Math.max(headBottom, shaftTip);
        int top = Math.max(0, Math.round(upper - 7 * gap) - 12);
        int bottom = Math.min(height - 1, Math.round(lower + 7 * gap) + 12);
        if (left < margin || right + margin >= width) return true;
        var mask = trackedStaffMask(ink, width, height, left, right, top, bottom, track, gap);
        var window = new Window(ink, width, left, top, right - left + 1, bottom - top + 1, mask);
        return window.bracket(window.staffRows(margin, gap), first, last, headTop,
                lower, upper, lower, gap, rows, verifyContinuingStroke, directions, track, headsX, headsY, octaveMarks);
    }

    /** Optional marks in a broad harmonic triplet, after its literal rail is established. */
    static boolean clearWideHarmonic(byte[] ink, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PrintedBeamShafts.Shaft> shafts,
            Rows sourceRows, StaffPitchTrack staffTrack) {
        return staffTrack != null && clear(ink, width, height, gap, headsX, headsY, shafts,
                sourceRows, staffTrack, List.of(), null, false, true, false);
    }

    /** Some complete primary rails lie beside printed fingering along a staff rule. */
    static boolean clearFingeringBands(byte[] source, int w, int h, float gap,
            float first, float last, float headTop, float headBottom, int tipTop,
            int tipBottom, Rows rows, StaffPitchTrack track) {
        return clearFingeringBands(source, w, h, gap, first, last, headTop, headBottom,
                tipTop, tipBottom, rows, track, null);
    }

    static boolean clearFingeringBands(byte[] source, int w, int h, float gap,
            float first, float last, float headTop, float headBottom, int tipTop,
            int tipBottom, Rows rows, StaffPitchTrack track, PlayingTechniqueDetector.Word fingeringTwo) {
        return clearFingeringBands(source, w, h, gap, first, last, headTop, headBottom,
                tipTop, tipBottom, rows, track, fingeringTwo, List.of());
    }

    static boolean clearFingeringBands(byte[] source, int w, int h, float gap,
            float first, float last, float headTop, float headBottom, int tipTop, int tipBottom,
            Rows rows, StaffPitchTrack track, PlayingTechniqueDetector.Word fingeringTwo,
            List<AccidentalHead> accidentalHeads) {
        return clearFingeringBands(source, w, h, gap, first, last, headTop, headBottom,
                tipTop, tipBottom, rows, track, fingeringTwo, accidentalHeads, List.of());
    }

    static boolean clearFingeringBands(byte[] source, int w, int h, float gap,
            float first, float last, float headTop, float headBottom, int tipTop, int tipBottom,
            Rows rows, StaffPitchTrack track, PlayingTechniqueDetector.Word fingeringTwo,
            List<AccidentalHead> accidentalHeads, List<PlayingTechniqueDetector.Word> directions) {
        return clearFingeringBands(source, w, h, gap, first, last, headTop, headBottom,
                tipTop, tipBottom, rows, track, fingeringTwo, accidentalHeads, directions, false);
    }

    static boolean clearFingeringBands(byte[] source, int w, int h, float gap,
            float first, float last, float headTop, float headBottom, int tipTop, int tipBottom,
            Rows rows, StaffPitchTrack track, PlayingTechniqueDetector.Word fingeringTwo,
            List<AccidentalHead> accidentalHeads, List<PlayingTechniqueDetector.Word> directions,
            boolean octaveMarks) {
        if (track == null) return false;
        if (bracketBesideBeam(
                source, w, h, gap, first, last, headTop, headBottom, tipBottom, rows, track, true, directions, null, null, octaveMarks)) return false;
        int top = Math.max(rows.first(), Math.round(headTop - gap * 4.8f));
        int above = Math.min(rows.last(), Math.round(headTop - gap * .22f));
        int below = Math.max(rows.first(), tipTop - Math.round(gap * .21f));
        int bottom = Math.min(rows.last(), Math.round(tipBottom + gap * 3f));
        int start = Math.round(first - gap), stop = Math.round(last + gap);
        if (start < 0 || stop >= w || top >= above || below >= bottom) return false;
        return hasOnlyFingeringInk(source, w, h, start, stop, top, above, first, last, gap, fingeringTwo, accidentalHeads, directions, track)
                && hasOnlyFingeringInk(source, w, h, start, stop, below, bottom, first, last, gap, null);
    }

    static boolean clearRuledFingeringBands(byte[] source, int w, int h, float gap,
            float[] headsX, float[] headsY, float headTop, float headBottom, int tipTop, int tipBottom,
            Rows rows, StaffPitchTrack track) {
        float first = headsX[0], last = headsX[2];
        if (bracketBesideBeam(source,w,h,gap,first,last,headTop,headBottom,tipBottom,rows,track,
                             true,List.of(),headsX,headsY)) return false;
        int top = Math.max(rows.first(), Math.round(headTop - gap * 4.8f));
        int above = Math.min(rows.last(), Math.round(headTop - gap * .22f));
        int below = Math.max(rows.first(), tipTop - Math.round(gap * .21f));
        int bottom = Math.min(rows.last(), Math.round(tipBottom + gap * 3f));
        int start = Math.round(first - gap), stop = Math.round(last + gap);
        return start >= 0 && stop < w && below < bottom
                && (top >= above || hasOnlyFingeringInk(source,w,h,start,stop,top,above,first,last,gap,null,
                            List.of(),List.of(),track))
                && hasOnlyFingeringInk(source,w,h,start,stop,below,bottom,first,last,gap,null);
    }

    static boolean hasOnlyFingeringInk(byte[] source, int w, int h, int left, int right, int top,
            int bottom, float firstHead, float lastHead, float gap, PlayingTechniqueDetector.Word fingeringTwo) {
        return hasOnlyFingeringInk(source, w, h, left, right, top, bottom, firstHead, lastHead, gap,
                fingeringTwo, List.of(), List.of(), null);
    }

    private static boolean hasOnlyFingeringInk(byte[] source, int w, int h, int left, int right, int top,
            int bottom, float firstHead, float lastHead, float gap, PlayingTechniqueDetector.Word fingeringTwo,
            List<AccidentalHead> accidentalHeads, List<PlayingTechniqueDetector.Word> directions,
            StaffPitchTrack staff) {
        var ink = RawNumeralComponents.captureLongWindow(source, w, h, left, top, right, bottom);
        for (var glyph : ink.components()) {
            float center = (glyph.left + glyph.right) * .5f;
            int span = glyph.right - glyph.left + 1, height = glyph.bottom - glyph.top + 1;
            if (center < firstHead + gap * .65f || center > lastHead - gap * .65f
                    || span < gap * .34f || span > gap * 1.5f
                    || height < gap * .76f || height > gap * 2.35f) continue;
            if (!isUnambiguousOne(source, w, glyph)
                    && !(staff != null && restoresClippedFingeringOne(source, w, h, glyph, bottom, gap))
                    && !isUnambiguousTwo(source, w, glyph)
                    && !ownedBoldTwo(source, w, h, glyph, fingeringTwo)
                    && !ownedClippedAccidental(source, w, h, glyph, bottom, gap, accidentalHeads, staff)
                    && !coveredByBowCaption(directions, w, h, gap,
                            glyph.left, glyph.top, glyph.right, glyph.bottom)) return false;
        }
        return true;
    }

    private static boolean restoresClippedFingeringOne(byte[] source, int width, int height,
            RawNumeralComponents.Component clipped, int bottom, float gap) {
        if (clipped.bottom != bottom) return false;
        int collar = Math.max(2, Math.round(.36f * gap));
        int left = clipped.left - collar, right = clipped.right + collar;
        int top = clipped.top - 2, end = bottom + collar;
        if (left < 0 || right >= width || top < 0 || end >= height) return false;
        var nearby = RawNumeralComponents.captureLongWindow(source, width, height, left, top, right, end);
        for (var whole : nearby.components()) {
            if (whole.top == clipped.top && whole.left > left && whole.left <= clipped.left
                    && whole.right < right && whole.right >= clipped.right
                    && whole.bottom > bottom && whole.bottom < end
                    && whole.bottom - whole.top + 1 <= 1.65f * gap
                    && isUnambiguousOne(source, width, whole)
                    && containsClippedComponent(whole, clipped, width, bottom)) return true;
        }
        return false;
    }

    private static boolean containsClippedComponent(RawNumeralComponents.Component whole,
            RawNumeralComponents.Component clipped, int width, int bottom) {
        int clippedPixels = 0;
        boolean sameSeed = false;
        for (int i = 0; i < whole.size(); i++) {
            int pixel = whole.pixel(i);
            if (pixel / width <= bottom) clippedPixels++;
            if (pixel == clipped.pixel(0)) sameSeed = true;
        }
        return sameSeed && clippedPixels == clipped.size();
    }

    private static boolean coveredByBowCaption(List<PlayingTechniqueDetector.Word> words,
            int width, int height, float gap, float first, float top, float last, float bottom) {
        for (var word : words) {
            if (!ExpressiveDirectionText.isBowCaption(word.text())) continue;
            float upper = word.top() * height, lower = word.bottom() * height;
            if (lower - upper >= gap * .8f && lower - upper <= gap * 3.2f
                    && word.left() * width <= first && word.right() * width >= last
                    && upper <= top && lower >= bottom) return true;
        }
        return false;
    }

    record AccidentalHead(float x, float y, int accidental) {}

    private static boolean ownedClippedAccidental(byte[] source, int width, int height,
            RawNumeralComponents.Component glyph, int bottom, float gap, List<AccidentalHead> accidentalHeads,
            StaffPitchTrack staff) {
        if (glyph.bottom != bottom) return false;
        AccidentalHead owner = null;
        for (var head : accidentalHeads) {
            float dx = head.x() - (glyph.left + glyph.right) * .5f;
            if (dx < gap || dx > gap * 2.15f
                    || head.y() - bottom < 0 || head.y() - bottom > gap * .46f) continue;
            if (owner != null) return false;
            owner = head;
        }
        if (owner == null) return false;
        if (owner.accidental() == ScoreNoteEvent.ACCIDENTAL_SHARP)
            return OmrScoreInterpreter.printedSharpBeforeDyad(
                    source, width, height, glyph.left, owner.x(), owner.y(), gap);
        if (owner.accidental() != ScoreNoteEvent.ACCIDENTAL_NATURAL || staff == null) return false;
        float[] frame = staff.at(owner.x());
        int topRule = Math.round(frame[0] - 4 * frame[1]);
        float collar = Math.round(gap * .25f);
        for (float vertical : new float[] {0, -collar, collar})
            for (int offset = -1; offset <= 1; offset++)
                if (OmrScoreInterpreter.provesNaturalMeterInk(source, width, height,
                        glyph.left + offset, glyph.right + offset, owner.y() + vertical, gap, topRule))
                    return true;
        return false;
    }

    private static boolean ownedBoldTwo(byte[] source, int width, int height,
            RawNumeralComponents.Component glyph, PlayingTechniqueDetector.Word word) {
        if (word == null || glyph.left <= word.left() * width || glyph.right >= word.right() * width
                || glyph.top <= word.top() * height || glyph.bottom >= word.bottom() * height) return false;
        int widthOfGlyph = glyph.right - glyph.left + 1;
        int heightOfGlyph = glyph.bottom - glyph.top + 1;
        int pocket = 0, base = 0;
        for (int y = 0; y < heightOfGlyph; y++) {
            int row = (glyph.top + y) * width + glyph.left;
            if (y >= heightOfGlyph * .20f && y < heightOfGlyph * .48f
                    && (source[row + Math.round(widthOfGlyph * .25f)] & 255) < 155
                    && (source[row + Math.round(widthOfGlyph * .78f)] & 255) < 155
                    && (source[row + widthOfGlyph / 2] & 255) >= 155) pocket++;
            if (y < heightOfGlyph * .58f || y > heightOfGlyph * .88f) continue;
            int dark = 0;
            for (int x = 0; x < widthOfGlyph; x++) if ((source[row + x] & 255) < 155) dark++;
            if (dark >= widthOfGlyph * .80f) base++;
        }
        return pocket >= 3 && base >= 2;
    }

    static boolean isUnambiguousOne(byte[] source, int width, RawNumeralComponents.Component glyph) {
        if (!TripletRhythmDetector.fingerOne(source, width, glyph)) return false;
        int tall = glyph.bottom - glyph.top + 1, wide = glyph.right - glyph.left + 1;
        int crossbar = 0;
        for (int y = glyph.top + Math.round(tall * .43f); y <= glyph.top + Math.round(tall * .64f); y++) {
            int filled = 0;
            for (int x = glyph.left; x <= glyph.right; x++)
                if ((source[y * width + x] & 255) <= 154) filled++;
            if (filled >= wide * .78f) crossbar++;
        }
        return crossbar < 2;
    }

    private static boolean isUnambiguousTwo(byte[] source, int width, RawNumeralComponents.Component glyph) {
        if (!TripletRhythmDetector.fingerTwo(source, width, glyph)) return false;
        int tall = glyph.bottom - glyph.top + 1, wide = glyph.right - glyph.left + 1;
        int base = 0;
        for (int y = glyph.top + Math.round(tall * .69f); y <= glyph.bottom; y++) {
            int filled = 0;
            for (int x = glyph.left; x <= glyph.right; x++)
                if ((source[y * width + x] & 255) <= 154) filled++;
            base = filled >= wide * .78f ? base + 1 : 0;
            if (base >= 2) return true;
        }
        return false;
    }

    static boolean clear(byte[] ink, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PrintedBeamShafts.Shaft> shafts) {
        return clear(ink, width, height, gap, headsX, headsY, shafts, new Rows(0, height - 1));
    }

    static boolean clear(byte[] ink, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PrintedBeamShafts.Shaft> shafts, Rows sourceRows) {
        return clear(ink, width, height, gap, headsX, headsY, shafts, sourceRows, null, List.of());
    }

    static boolean clearWithTrackedStaff(byte[] ink, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PrintedBeamShafts.Shaft> shafts, Rows sourceRows,
            StaffPitchTrack staff) {
        return clearWithTrackedStaff(ink,width,height,gap,headsX,headsY,shafts,sourceRows,staff,List.of());
    }

    static boolean clearWithTrackedStaff(byte[] ink, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PrintedBeamShafts.Shaft> shafts, Rows sourceRows,
            StaffPitchTrack staff, List<PlayingTechniqueDetector.Word> words) {
        return staff != null && clear(ink,width,height,gap,headsX,headsY,shafts,sourceRows,staff,words);
    }

    static boolean clearWithTrackedBows(byte[] ink, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PrintedBeamShafts.Shaft> shafts, Rows sourceRows,
            StaffPitchTrack staffTrack) {
        return staffTrack != null && clear(ink, width, height, gap, headsX, headsY, shafts, sourceRows,
                staffTrack, List.of(), null, false, false, false, true);
    }

    static boolean clearFollowing(byte[] ink, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PrintedBeamShafts.Shaft> shafts, Rows sourceRows,
            StaffPitchTrack staffTrack, List<PlayingTechniqueDetector.Word> words, List<Head> lowerHeads) {
        return staffTrack != null && clear(ink, width, height, gap, headsX, headsY, shafts, sourceRows,
                staffTrack, words, lowerHeads);
    }

    record Head(float x, float y) {}

    private static boolean clear(byte[] ink, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PrintedBeamShafts.Shaft> shafts, Rows sourceRows,
            StaffPitchTrack staffTrack, List<PlayingTechniqueDetector.Word> words) {
        return clear(ink, width, height, gap, headsX, headsY, shafts, sourceRows, staffTrack, words, null);
    }

    static boolean clearOctaveWithTrackedStaff(
            byte[] ink, int width, int height, float gap, float[] headsX, float[] headsY,
            List<PrintedBeamShafts.Shaft> shafts, Rows sourceRows, StaffPitchTrack staffTrack,
            List<PlayingTechniqueDetector.Word> words, boolean closedRails) {
        return staffTrack != null && clear(ink, width, height, gap, headsX, headsY, shafts,
                sourceRows, staffTrack, words, null, true, false, closedRails);
    }

    private static boolean clear(byte[] ink, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PrintedBeamShafts.Shaft> shafts, Rows sourceRows,
            StaffPitchTrack staffTrack, List<PlayingTechniqueDetector.Word> words, List<Head> lowerHeads) {
        return clear(ink, width, height, gap, headsX, headsY, shafts, sourceRows,
                staffTrack, words, lowerHeads, false);
    }

    private static boolean clear(byte[] ink, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PrintedBeamShafts.Shaft> shafts, Rows sourceRows,
            StaffPitchTrack staffTrack, List<PlayingTechniqueDetector.Word> words, List<Head> lowerHeads,
            boolean octaveFingering) {
        return clear(ink, width, height, gap, headsX, headsY, shafts, sourceRows,
                staffTrack, words, lowerHeads, octaveFingering, false, false);
    }

    private static boolean clear(byte[] ink, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PrintedBeamShafts.Shaft> shafts, Rows sourceRows,
            StaffPitchTrack staffTrack, List<PlayingTechniqueDetector.Word> words, List<Head> lowerHeads,
            boolean octaveFingering, boolean wideHarmonic, boolean closedRails) {
        return clear(ink, width, height, gap, headsX, headsY, shafts, sourceRows, staffTrack,
                words, lowerHeads, octaveFingering, wideHarmonic, closedRails, false);
    }

    private static boolean clear(byte[] ink, int width, int height, float gap,
            float[] headsX, float[] headsY, List<PrintedBeamShafts.Shaft> shafts, Rows sourceRows,
            StaffPitchTrack staffTrack, List<PlayingTechniqueDetector.Word> words, List<Head> lowerHeads,
            boolean octaveFingering, boolean wideHarmonic, boolean closedRails, boolean allowBow) {
        if (ink == null || width < 1 || height < 1 || ink.length != (long) width * height
                || !Float.isFinite(gap) || gap < 10 || gap > 25
                || headsX.length != 3 || headsY.length != 3 || shafts.size() != 3) return false;
        for (int i = 0; i < 3; i++)
            if (!Float.isFinite(headsX[i]) || !Float.isFinite(headsY[i])
                    || i > 0 && headsX[i - 1] >= headsX[i]) return false;
        var first = shafts.get(0);
        var last = shafts.get(2);
        int travel = first.direction();
        if (Math.abs(travel) != 1 || shafts.stream().anyMatch(s -> s.direction() != travel)) return false;
        float lo = (first.left() + first.right()) * .5f;
        float hi = (last.left() + last.right()) * .5f;
        boolean wideOctave = closedRails && octaveFingering && travel < 0 && hi - lo > gap * 5.95f;
        float maxSpan = wideHarmonic ? 8.8f : wideOctave || octaveFingering && !closedRails ? 6.8f : octaveFingering ? 5.95f : 5.5f;
        if (hi - lo < gap * (wideHarmonic ? 5.6f : 2.8f) || hi - lo > gap * maxSpan) return false;
        float topHead = Math.min(headsY[0], Math.min(headsY[1], headsY[2]));
        float bottomHead = Math.max(headsY[0], Math.max(headsY[1], headsY[2]));
        float top = Math.min(topHead, shafts.stream().mapToInt(PrintedBeamShafts.Shaft::endY).min().orElseThrow());
        float bottom = Math.max(bottomHead, shafts.stream().mapToInt(PrintedBeamShafts.Shaft::endY).max().orElseThrow());
        int left = Math.round(lo - gap), right = Math.round(hi + gap);
        int start = Math.round(top - gap * 7), end = Math.round(bottom + gap * 7);
        int tailRadius = Math.round(gap * 2.5f);
        if (left - tailRadius < 0 || right + tailRadius >= width || start < 0 || end >= height) return false;
        boolean[] verifiedStaff = trackedStaffMask(ink, width, height, left, right, start, end, staffTrack, gap);
        var window = new Window(ink, width, left, start, right - left + 1, end - start + 1, verifiedStaff);
        boolean[] staff = window.staffRows(tailRadius, gap);
        List<BeamFingeringStack> beamFingerings = octaveFingering && travel > 0
                ? beamFingeringStacks(ink, width, height, words, shafts, gap) : List.of();
        for (int x = Math.round(lo + .15f * (hi - lo)); x <= Math.round(lo + .85f * (hi - lo)); x++) {
            float beam = first.endY() + (last.endY() - first.endY()) * (x - lo) / (hi - lo);
            boolean joined = true;
            for (int margin = Math.round(.25f * gap); margin <= Math.round(.78f * gap); margin++)
                if (!window.dark(x, Math.round(beam) + travel * margin, staff)) {
                    joined = false;
                    break;
                }
            if (joined && octaveFingering) {
                // The run alone can start at an independent fingering below the beam.
                // Require an actual connection before treating it as a prolongation of the beam.
                for (int offset = 1; offset < Math.round(.25f * gap); offset++) {
                    if (!window.dark(x, Math.round(beam) + travel * offset, staff)) {
                        joined = false;
                        break;
                    }
                }
            }
            if (joined && !ownsBeamFingeringContact(beamFingerings, x) && !(octaveFingering && travel > 0
                    && separatedBeamFingeringOne(window, x, Math.round(beam), gap, shafts))) return false;
        }
        if (window.bracket(staff, Math.min(lo, headsX[0]), Math.max(hi, headsX[2]),
                topHead, bottomHead, top, bottom, gap, sourceRows, octaveFingering,
                words, staffTrack, headsX, headsY, octaveFingering)) return false;
        boolean ownedStack = false;
        int cells = window.width * window.height;
        boolean[] used = new boolean[cells];
        int[] queue = new int[cells];
        for (int seed = 0; seed < cells; seed++) {
            int x = seed % window.width + left, y = seed / window.width + start;
            if (used[seed] || !window.dark(x, y, staff)) continue;
            used[seed] = true;
            queue[0] = seed;
            int size = 1, minX = x, minY = y, maxX = x, maxY = y;
            for (int i = 0; i < size; i++) {
                int px = queue[i] % window.width + left, py = queue[i] / window.width + start;
                minX = Math.min(minX, px);
                minY = Math.min(minY, py);
                maxX = Math.max(maxX, px);
                maxY = Math.max(maxY, py);
                for (int yy = Math.max(start, py - 1); yy <= Math.min(end, py + 1); yy++)
                    for (int xx = Math.max(left, px - 1); xx <= Math.min(right, px + 1); xx++) {
                        int next = (yy - start) * window.width + xx - left;
                        if (used[next] || !window.dark(xx, yy, staff)) continue;
                        used[next] = true;
                        queue[size++] = next;
                    }
            }
            if (maxY < sourceRows.first || minY > sourceRows.last
                    || size < Math.max(3, Math.round(.35f * gap))) continue;
            float center = (minX + maxX) * .5f;
            float fraction = (center - lo) / (hi - lo);
            if (fraction < .15f || fraction > .85f) continue;
            float beam = first.endY() + (last.endY() - first.endY()) * fraction;
            float beamSide = travel > 0 ? minY - beam : beam - maxY;
            float headSide = travel > 0 ? topHead - maxY : minY - bottomHead;
            boolean markBand = beamSide >= gap * .18f && beamSide <= gap * 1.8f;
            boolean headBand = headSide >= gap * .95f && headSide <= gap * 5f;
            if (!(markBand || headBand)) continue;
            if (lowerHeads != null && !markBand && minY >= bottomHead + 2.5f * gap
                    && memberOwnsLowerHead(queue, size, window, minX, maxX, minY, maxY, lowerHeads, gap))
                continue;
            int inkWidth = maxX - minX + 1, inkHeight = maxY - minY + 1;
            if (lowerHeads != null && !markBand && minY >= bottomHead + 1.8f * gap
                    && inkWidth <= .60f * gap && inkHeight <= .60f * gap
                    && size >= .60f * inkWidth * inkHeight) {
                float dotY = (minY + maxY) * .5f;
                if (lowerHeads.stream().filter(head -> Math.abs(head.x() - center) <= .27f * gap
                        && head.y() - dotY >= 1.15f * gap && head.y() - dotY <= 1.9f * gap).count() == 1)
                    continue;
            }
            if (markBand && ownedReturningCurve(queue, size, window, minX, maxX, shafts, travel, gap,
                    lowerHeads != null ? headsX : null)) continue;
            if (wideHarmonic && travel > 0 && headBand && !markBand
                    && Math.abs(minX - headsX[0]) <= gap * .48f
                    && Math.abs(maxX - headsX[2]) <= gap * .55f
                    && thinReturningProfile(queue, size, window, minX, maxX, false, gap)) continue;
            if (wideHarmonic && headBand && !markBand && inkWidth >= gap * .78f
                    && inkWidth <= gap * 1.5f && inkHeight >= gap * .12f && inkHeight <= gap * .43f
                    && size >= .78f * inkWidth * inkHeight
                    && ownsHarmonicTenuto(center, minY, maxY, headsX, headsY, travel, gap)) continue;
            if (octaveFingering && headBand && !markBand
                    && ownsSegueBeforeOctave(ink, width, height, gap, minX, minY, maxX, maxY, words))
                continue;
            if (travel > 0 && markBand && ownedDirectionCaption(
                    ink,width,height,gap,minX,minY,maxX,maxY,words)) continue;
            if (octaveFingering && travel < 0 && headBand && !markBand
                    && ownsStackedOpenString(ink,width,height,words,minX,minY,maxX,maxY,headsX,headsY,gap)) {
                ownedStack = true;
                continue;
            }
            if (octaveFingering && headBand && !markBand && travel < 0
                    && ownsSplitStackedFingering(ink, width, height, words,
                            minX, minY, maxX, maxY, headsX, headsY, gap)) continue;
            if (octaveFingering && headBand && !markBand && travel < 0
                    && ownsSplitRomanBelowHeads(ink, width, height, words,
                            minX, minY, maxX, maxY, headsX, headsY, gap)) continue;
            if (octaveFingering && markBand && !headBand && travel > 0
                    && ownsBeamFingeringComponent(beamFingerings, minX, minY, maxX, maxY)) continue;
            if (octaveFingering && ownsOctaveAnnotation(ink, width, height, words,
                    queue, size, window, minX, minY, maxX, maxY, headBand, headsX, shafts, gap)) continue;
            if (octaveFingering && markBand && !headBand && travel > 0
                    && ownsRuledFingeringOne(window, staff, minX, minY, maxX, maxY, gap, shafts, used)) continue;
            if (octaveFingering && markBand && !headBand && travel > 0
                    && ownsRuledFingeringTwo(window, used, words, minX, minY, maxX, maxY, gap, shafts)) continue;
            if (octaveFingering && markBand && !headBand && minY == maxY && travel > 0
                    && ownsStaffFringe(window, staff, minX, maxX, minY, size, gap)) continue;
            if (octaveFingering && markBand && !headBand && travel < 0
                    && ownsBeamSideTenutoRow(window, minX, minY, maxX, maxY, size, shafts, gap)) continue;
            if (octaveFingering && headBand && !markBand
                    && window.ownsDottedRibbonGlyph(minX, minY, maxX, maxY, gap, words)) continue;
            if (allowBow && markBand && !headBand && travel < 0
                    && (centeredUpBow(queue, size, window, minX, maxX, minY, maxY, headsX[1], gap)
                        || centeredOpenString(ink, width, height, minX, maxX, minY, maxY, headsX[1], gap)))
                continue;
            if (inkWidth > .58f * gap || inkHeight > .58f * gap || size < .74f * inkWidth * inkHeight)
                return false;
            boolean dot = false;
            for (int i = 0; i < 3; i++) {
                var shaft = shafts.get(i);
                float ownerX = markBand ? (shaft.left() + shaft.right()) * .5f : headsX[i];
                if (Math.abs(ownerX - center) <= .3f * gap) dot = true;
            }
            if (!dot) return false;
        }
        return !wideOctave || ownedStack
                || isolatedOpeningOctaveFingeringOne(ink, width, height, words, headsX[1], headsY[1], gap);
    }

    private static boolean centeredOpenString(byte[] ink, int width, int height,
            int left, int right, int top, int bottom, float owner, float gap) {
        int w = right - left + 1, h = bottom - top + 1;
        if (w < .59f * gap || w > 1.04f * gap || h < .83f * gap || h > 1.28f * gap
                || Math.abs((left + right) * .5f - owner) > .30f * gap) return false;
        var zero = JoinedDigitTemplates.inspect(
                ink, width, height, new java.util.BitSet(), left, top, right, bottom, () -> {});
        if (zero == null || zero.digit() != 0 || zero.scores()[0] < .58f
                || zero.scores()[0] - zero.runner() < .10f) return false;
        int center = (left + right) / 2;
        int hollow = 0;
        for (int y = top + h / 4; y <= bottom - h / 4; y++) {
            if ((ink[y * width + center] & 255) < 165) continue;
            int a = center, b = center;
            while (a >= left && (ink[y * width + a] & 255) >= 165) a--;
            while (b <= right && (ink[y * width + b] & 255) >= 165) b++;
            if (a >= left && b <= right && b - a - 1 >= .34f * w) hollow++;
        }
        if (hollow < h / 2) return false;
        boolean upper = false, lower = false;
        for (int y = 0; y <= h / 4; y++) {
            upper |= (ink[(top + y) * width + center] & 255) < 165;
            lower |= (ink[(bottom - y) * width + center] & 255) < 165;
        }
        return upper && lower;
    }

    private static boolean centeredUpBow(int[] pixels, int size, Window window,
            int left, int right, int top, int bottom, float owner, float gap) {
        int w = right - left + 1, h = bottom - top + 1;
        if (w < .74f * gap || w > 1.28f * gap || h < 1.65f * gap || h > 2.25f * gap
                || Math.abs((left + right) * .5f - owner) > .27f * gap) return false;
        int[] rowMin = new int[h], rowMax = new int[h], rowCount = new int[h];
        java.util.Arrays.fill(rowMin, right + 1);
        for (int i = 0; i < size; i++) {
            int p = pixels[i], x = p % window.width + window.left;
            int y = p / window.width + window.top - top;
            rowMin[y] = Math.min(rowMin[y], x);
            rowMax[y] = Math.max(rowMax[y], x);
            rowCount[y]++;
        }
        int spread = 0, joined = 0;
        for (int y = h / 8; y < h / 2; y++)
            if (rowMin[y] < owner - .22f * gap && rowMax[y] > owner + .13f * gap
                    && rowCount[y] <= .62f * (rowMax[y] - rowMin[y] + 1)) spread++;
        for (int y = h * 3 / 4; y < h; y++)
            if (rowCount[y] > 0 && rowMax[y] - rowMin[y] + 1 <= .46f * gap
                    && rowCount[y] >= .72f * (rowMax[y] - rowMin[y] + 1)) joined++;
        return spread >= h / 6 && joined >= h / 7 && rowCount[h - 1] > 0
                && Math.abs((rowMin[h - 1] + rowMax[h - 1]) * .5f - owner) < .26f * gap;
    }

    private static boolean isolatedOpeningOctaveFingeringOne(byte[] ink, int width, int height,
            List<PlayingTechniqueDetector.Word> words, float x, float y, float gap) {
        for (var word : words) {
            if (!"1".equals(word.text())) continue;
            int left = (int) Math.floor(word.left() * width), right = (int) Math.ceil(word.right() * width);
            int top = (int) Math.floor(word.top() * height), bottom = (int) Math.ceil(word.bottom() * height);
            if (Math.abs((left + right) * .5f - x) > .25f * gap
                    || top - y < .55f * gap || top - y > 1.1f * gap
                    || right - left > 1.3f * gap || bottom - top > 1.6f * gap
                    || left < 0 || right >= width || top < 0 || bottom >= height) continue;
            var glyphs = RawNumeralComponents.captureLongWindow(ink, width, height, left, top, right, bottom)
                    .components().stream().filter(c -> c.size() >= 10).toList();
            if (glyphs.size() != 1) continue;
            var glyph = glyphs.get(0);
            if (glyph.left > left && glyph.right < right && glyph.top > top && glyph.bottom < bottom
                    && glyph.bottom - glyph.top + 1 >= .75f * gap
                    && TripletRhythmDetector.fingerOne(ink, width, glyph)) return true;
        }
        return false;
    }

    private record BeamFingeringStack(int owner, String digits, int[] high, int[] low) {}

    private static List<BeamFingeringStack> beamFingeringStacks(byte[] ink, int width, int height,
            List<PlayingTechniqueDetector.Word> words, List<PrintedBeamShafts.Shaft> shafts, float gap) {
        var stacks = new java.util.ArrayList<BeamFingeringStack>();
        var first = shafts.get(0);
        var last = shafts.get(2);
        float left = (first.left() + first.right()) * .5f;
        float right = (last.left() + last.right()) * .5f;
        float slope = (last.endY() - first.endY()) / (right - left);
        for (var word : words) {
            String text = word.text();
            if (!"31".equals(text) && !"42".equals(text)) continue;
            int x0 = Math.round(word.left() * width), x1 = Math.round(word.right() * width);
            int y0 = Math.round(word.top() * height), y1 = Math.round(word.bottom() * height);
            if (x0 < left - .75f * gap || x1 > right + 1.8f * gap || x0 < 0 || x1 >= width
                    || y0 < 0 || y1 >= height || x1 - x0 > 2.25f * gap
                    || y1 - y0 < 2.65f * gap || y1 - y0 > 3.65f * gap) continue;
            float center = (x0 + x1) * .5f;
            int owner = -1;
            for (int i = 0; i < shafts.size(); i++) {
                var shaft = shafts.get(i);
                float offset = center - (shaft.left() + shaft.right()) * .5f;
                if (offset >= -.28f * gap && offset <= 1.30f * gap
                        && y0 >= shaft.endY() - .98f * gap && y0 <= shaft.endY() + .65f * gap) owner = i;
            }
            if (owner < 0) continue;
            int split = (y0 + y1) / 2;
            int[][] bands = {{x1,y1,x0,y0},{x1,y1,x0,y0}};
            int scanLeft = owner == 2 && center > right + .44f * gap
                    ? Math.max(x0, (int) Math.ceil(right + .20f * gap)) : x0;
            int scanRight = owner == 2 && center > right + .44f * gap
                    ? Math.min(x1, Math.round(center + .62f * gap)) : x1;
            int digitTop = y0;
            if (scanLeft != x0 && scanRight - scanLeft >= .85f * gap) {
                int strong = 0, blank = 0;
                for (int yy = y0; yy <= y0 + .67f * gap; yy++) {
                    int dark = 0;
                    for (int xx = scanLeft; xx <= scanRight; xx++)
                        if ((ink[yy * width + xx] & 255) < 165) dark++;
                    if (dark >= .90f * (scanRight - scanLeft + 1)) strong++;
                    if (strong == 0) continue;
                    blank = dark == 0 ? blank + 1 : 0;
                    if (blank == 3) {
                        digitTop = yy + 1;
                        break;
                    }
                }
            }
            for (int xx = scanLeft; xx <= scanRight; xx++) {
                int stop = Math.round(first.endY() + slope * (xx - left));
                int startRow = xx > right + .14f * gap ? digitTop : Math.max(y0, stop + 2);
                for (int yy = startRow; yy <= y1; yy++) {
                    if ((ink[yy * width + xx] & 255) >= 165
                            || owner == 2 && xx > right + .14f * gap && yy < y0 + .40f * gap
                            && FlagRootStaffRule.matches(ink, width, height, yy, Math.round(center), gap, 165))
                        continue;
                    int[] b = bands[yy < split ? 0 : 1];
                    b[0] = Math.min(b[0], xx);
                    b[1] = Math.min(b[1], yy);
                    b[2] = Math.max(b[2], xx);
                    b[3] = Math.max(b[3], yy);
                }
            }
            int upper = text.charAt(0) - '0', lower = text.charAt(1) - '0';
            if (Math.abs(bands[0][0] + bands[0][2] - bands[1][0] - bands[1][2]) > .85f * gap
                    || !matchesBeamFingeringDigit(ink, width, height, bands[0], upper, gap)
                    || !matchesBeamFingeringDigit(ink, width, height, bands[1], lower, gap)) continue;
            stacks.add(new BeamFingeringStack(owner, text, bands[0], bands[1]));
        }
        return List.copyOf(stacks);
    }

    private static boolean matchesBeamFingeringDigit(byte[] ink, int width, int height,
            int[] box, int digit, float gap) {
        int across = box[2] - box[0] + 1, tall = box[3] - box[1] + 1;
        if (across < .40f * gap || across > 1.22f * gap || tall < .82f * gap || tall > 1.45f * gap)
            return false;
        var reading = JoinedDigitTemplates.inspect(ink, width, height, new java.util.BitSet(),
                box[0], box[1], box[2], box[3], () -> {});
        if (reading == null) return false;
        if (reading.digit() == digit) return true;
        float[] scores = reading.scores();
        if (digit == 2) return scores[2] >= .58f && scores[2] - scores[3] >= .05f
                && scores[2] - scores[9] >= .025f;
        if (digit != 3 || scores[3] < .63f || scores[3] + .027f < scores[8]
                || scores[3] - scores[5] < .015f) return false;
        return openFingeringBowl(ink, width, box, .32f, .46f)
                && openFingeringBowl(ink, width, box, .51f, .66f);
    }

    private static boolean openFingeringBowl(byte[] ink, int width, int[] box, float first, float last) {
        int wide = box[2] - box[0] + 1, tall = box[3] - box[1] + 1;
        for (int y = box[1] + Math.round(first * tall); y <= box[1] + Math.round(last * tall); y++) {
            int clear = 0, dark = 0;
            for (int x = box[0]; x <= box[2]; x++) {
                int value = ink[y * width + x] & 255;
                if (x < box[0] + .44f * wide && value >= 183) clear++;
                if (x >= box[0] + .50f * wide && value < 165) dark++;
            }
            if (clear >= .145f * wide && dark >= .18f * wide) return true;
        }
        return false;
    }

    private static boolean ownsBeamFingeringContact(List<BeamFingeringStack> words, int x) {
        for (var word : words) {
            if (x < word.high()[0] || x > word.high()[2]) continue;
            if (words.stream().anyMatch(other -> other.owner() != word.owner()
                    && other.digits().equals(word.digits()))) return true;
        }
        return false;
    }

    private static boolean ownsBeamFingeringComponent(List<BeamFingeringStack> words,
            int left, int top, int right, int bottom) {
        for (var word : words)
            for (int[] band : List.of(word.high(), word.low()))
                if (left >= band[0] - 1 && right <= band[2] + 1
                        && top >= band[1] - 1 && bottom <= band[3] + 1) return true;
        return false;
    }

    private static boolean ownsSplitStackedFingering(byte[] ink, int width, int height,
            List<PlayingTechniqueDetector.Word> words, int left, int top, int right, int bottom,
            float[] headsX, float[] headsY, float gap) {
        float x = (left + right) * .5f;
        int owner = -1;
        for (int i = 0; i < headsX.length; i++) {
            if (Math.abs(x - headsX[i]) > .42f * gap) continue;
            if (owner >= 0 || top - headsY[i] < 1.25f * gap || top - headsY[i] > 3.2f * gap) return false;
            owner = i;
        }
        if (owner < 0) return false;
        for (var word : words) {
            String text = word.text();
            if (text.length() != 2 || text.charAt(0) < '1' || text.charAt(0) > '4'
                    || text.charAt(1) != '2') continue;
            int x0 = Math.round(word.left() * width), x1 = Math.round(word.right() * width);
            int y0 = Math.round(word.top() * height), y1 = Math.round(word.bottom() * height);
            if (x0 < 0 || x1 >= width || y0 < 0 || y1 >= height
                    || x0 >= left || right >= x1 || y0 >= top || bottom >= y1
                    || x1 - x0 > 2.05f * gap || y1 - y0 > 3.7f * gap
                    || Math.abs((x0 + x1) * .5f - headsX[owner]) > .35f * gap) continue;

            // Separate the printed rows by the clear interval between the two glyphs.
            int[][] bands = {{x1, y1, x0, y0}, {x1, y1, x0, y0}};
            int split = (y0 + y1) / 2;
            if (split - y0 < gap || y1 - split < gap) continue;
            int firstRow = Math.max(y0, (int) Math.ceil(headsY[owner] + .62f * gap));
            for (int yy = firstRow; yy <= y1; yy++)
                for (int xx = x0; xx <= x1; xx++) {
                    if ((ink[yy * width + xx] & 255) >= 165) continue;
                    int[] b = bands[yy < split ? 0 : 1];
                    b[0] = Math.min(b[0], xx);
                    b[1] = Math.min(b[1], yy);
                    b[2] = Math.max(b[2], xx);
                    b[3] = Math.max(b[3], yy);
                }
            var upper = bands[0];
            var lower = bands[1];
            if (lower[1] - upper[3] < .18f * gap || lower[1] - upper[3] > .72f * gap
                    || Math.abs(upper[0] + upper[2] - lower[0] - lower[2]) > .58f * gap
                    || (top < lower[1] - 1 || bottom > lower[3] + 1)
                            && (top < upper[1] - 1 || bottom > upper[3] + 1))
                continue;
            if (matchesStackedFingeringDigit(ink, width, height, upper, text.charAt(0) - '0', gap)
                    && matchesStackedFingeringDigit(ink, width, height, lower, 2, gap)) return true;
        }
        return false;
    }

    private static boolean ownsSplitRomanBelowHeads(byte[] ink, int width, int height,
            List<PlayingTechniqueDetector.Word> words, int left, int top, int right, int bottom,
            float[] headsX, float[] headsY, float gap) {
        if (bottom - top < 1.12f * gap || bottom - top > 1.9f * gap
                || right - left < .45f * gap || right - left > .87f * gap) return false;
        for (var word : words) {
            if (!"II".equals(word.text())) continue;
            int x0 = (int)Math.floor(word.left() * width), x1 = (int)Math.ceil(word.right() * width);
            int y0 = (int)Math.floor(word.top() * height), y1 = (int)Math.ceil(word.bottom() * height);
            if (x0 < 0 || x1 >= width || y0 < 0 || y1 >= height || x0 >= left
                    || x1 <= right || y0 >= top || y1 <= bottom
                    || x1 - x0 > 2.55f * gap || y1 - y0 > 2.9f * gap) continue;
            float x = (x0 + x1) * .5f;
            boolean between = false;
            for (int i = 1; i < headsX.length; i++) {
                float highHead = Math.min(headsY[i - 1], headsY[i]);
                float lowHead = Math.max(headsY[i - 1], headsY[i]);
                if (x > headsX[i - 1] + .45f * gap && x < headsX[i] - .45f * gap
                        && top - lowHead > 1.4f * gap && bottom - highHead < 7.9f * gap)
                    between = true;
            }
            if (!between) continue;
            var letters = RawNumeralComponents.captureLongWindow(ink, width, height, x0, y0, x1, y1)
                    .components().stream()
                    .filter(g -> !(g.top == y1 && g.bottom == y1)
                            && !(g.top >= bottom + .15f * gap && g.bottom == y1))
                    .sorted(java.util.Comparator.comparingInt(g -> g.left)).toList();
            if (letters.size() != 2) continue;
            var a = letters.get(0);
            var b = letters.get(1);
            int blank = b.left - a.right - 1;
            if (blank < 1 || blank > .35f * gap || Math.abs(a.top - b.top) > 2
                    || Math.abs(a.bottom - b.bottom) > 2
                    || Math.abs((a.right - a.left) - (b.right - b.left)) > 2) continue;
            if ((Math.abs(a.left - left) > 1 || Math.abs(a.right - right) > 1)
                    && (Math.abs(b.left - left) > 1 || Math.abs(b.right - right) > 1)) continue;
            if (PrintedRomanStrings.hasRomanSerifs(a, width, gap)
                    && PrintedRomanStrings.hasRomanSerifs(b, width, gap)) return true;
        }
        return false;
    }

    private static boolean matchesStackedFingeringDigit(byte[] ink, int width, int height,
            int[] box, int digit, float gap) {
        int w = box[2] - box[0] + 1, h = box[3] - box[1] + 1;
        if (w < .51f * gap || w > 1.18f * gap || h < .85f * gap || h > 1.35f * gap) return false;
        var candidate = JoinedDigitTemplates.inspect(ink, width, height, new java.util.BitSet(),
                box[0], box[1], box[2], box[3], () -> {});
        if (candidate == null) return false;
        float[] similarity = candidate.scores();
        return candidate.digit() == digit || digit == 2 && similarity[2] >= .66f
                && similarity[2] - similarity[3] >= .05f && similarity[2] - similarity[9] >= .03f;
    }

    private static boolean ownsStackedOpenString(byte[] ink, int width, int height,
            List<PlayingTechniqueDetector.Word> words, int left, int top, int right, int bottom,
            float[] headsX, float[] headsY, float gap) {
        int across = right - left + 1, tall = bottom - top + 1;
        if (across < .65f * gap || across > 1.12f * gap
                || tall < .85f * gap || tall > 1.35f * gap) return false;
        float x = (left + right) * .5f;
        int owner = -1;
        for (int i = 0; i < headsX.length; i++) {
            if (Math.abs(x - headsX[i]) > .38f * gap) continue;
            if (owner >= 0 || top - headsY[i] < 1.9f * gap || top - headsY[i] > 2.6f * gap)
                return false;
            owner = i;
        }
        if (owner < 0) return false;
        var zero = JoinedDigitTemplates.inspect(ink, width, height, new java.util.BitSet(),
                left, top, right, bottom, () -> {});
        if (zero == null || zero.digit() != 0 || zero.scores()[0] < .73f
                || zero.scores()[0] - zero.runner() < .13f) return false;
        for (var word : words) {
            if (!"20".equals(word.text()) || word.left() * width >= left || word.right() * width <= right
                    || word.top() * height >= top || word.bottom() * height <= bottom
                    || (word.right() - word.left()) * width > 2.4f * gap
                    || (word.bottom() - word.top()) * height > 3.9f * gap) continue;
            int edge = Math.round(gap * .48f);
            int x0 = left - edge, x1 = right + edge, y0 = top - Math.round(1.64f * gap);
            if (x0 < 0 || x1 >= width || y0 < 0) return false;
            var above = RawNumeralComponents.captureLongWindow(ink, width, height, x0, y0, x1, top - 1);
            for (var digit : above.components()) {
                if (digit.top <= y0 || digit.left <= x0 || digit.right >= x1
                        || Math.abs((digit.left + digit.right) * .5f - x) > .21f * gap
                        || top - digit.bottom < .15f * gap || top - digit.bottom > .52f * gap
                        || digit.bottom - digit.top + 1 < .83f * gap
                        || digit.bottom - digit.top + 1 > 1.35f * gap) continue;
                if (isUnambiguousTwo(ink, width, digit)
                        || matchesStackedFingeringDigit(ink, width, height,
                                new int[]{digit.left, digit.top, digit.right, digit.bottom}, 2, gap))
                    return true;
            }
        }
        return false;
    }

    private static boolean ownsRuledFingeringOne(Window window, boolean[] staff,
            int left, int top, int right, int bottom, float gap, List<PrintedBeamShafts.Shaft> shafts, boolean[] used) {
        int w = right - left + 1, h = bottom - top + 1;
        if (w < .42f * gap || w > .72f * gap || h < .55f * gap || h > .83f * gap) return false;
        int center = right - w / 4;
        int matches = 0;
        for (var shaft : shafts) {
            float offset = center - (shaft.left() + shaft.right()) * .5f;
            float separation = top - shaft.endY();
            if (offset >= .20f * gap && offset <= .57f * gap
                    && separation >= .28f * gap && separation <= .98f * gap) matches++;
        }
        if (matches != 1 || !window.dark(center, top, staff)
                || window.dark(left + 1, top, staff) || !window.dark(left + 1, top + h / 2, staff)
                || window.dark(left + 1, bottom, staff)) return false;
        int row = bottom + 1;
                int flank = Math.round(.38f * gap);
        if (left - flank < window.left || right + flank >= window.left + window.width) return false;
        while (row < window.top + window.height) {
            int before = 0, after = 0;
            for (int d = 1; d <= flank; d++) {
                if ((window.plane[row * window.stride + left - d] & 255) < 188) before++;
                if ((window.plane[row * window.stride + right + d] & 255) < 188) after++;
            }
            if (Math.min(before, after) < flank - 1) {
                if (row == bottom + 1 && before == 0 && after == 0
                        && (window.plane[row * window.stride + center] & 255) < 188) {
                    row++;
                    continue;
                }
                break;
            }
            row++;
        }
        int crossing = row - bottom - 1;
        if (crossing < 2 || crossing > 1 + Math.round(.24f * gap)
                || row + 5 >= window.top + window.height
                || right + 3 >= window.left + window.width) return false;
        int base = row, cropRight = right + 2;
        int limit = Math.min(row + Math.round(.40f * gap), window.top + window.height);
        while (base < limit && (window.plane[base * window.stride + center] & 255) < 188) {
            if ((window.plane[base * window.stride + left - 1] & 255) < 188
                    || (window.plane[base * window.stride + cropRight + 1] & 255) < 188) return false;
            base++;
        }
        if (base - row < 4 || base >= limit) return false;
        var mask = new java.util.BitSet();
        for (int y = bottom + 1; y < row; y++) {
            // Keep a partial glyph row while masking only the verified continuous rules.
            if ((window.plane[y * window.stride + left - flank] & 255) < 188
                    && (window.plane[y * window.stride + right + flank] & 255) < 188)
                mask.set(y * window.stride + left, y * window.stride + cropRight + 1);
        }
        var digit = JoinedDigitTemplates.inspect(window.plane, window.stride,
                window.plane.length / window.stride, mask, left, top, cropRight, base - 1, () -> {});
        if (digit == null || digit.digit() != 1) return false;
        for (int y = row; y < base; y++) {
            for (int x = left; x <= cropRight; x++)
                used[(y - window.top) * window.width + x - window.left] = true;
        }
        return true;
    }

    private static boolean ownsStaffFringe(Window window, boolean[] staff, int left, int right,
            int row, int count, float gap) {
        int length = right - left + 1;
        if (length < .40f * gap || length > .72f * gap || count < length - 1) return false;
        int extension = Math.round(1.24f * gap);
        int low = left - extension, high = right + extension;
        if (low < window.left || high >= window.left + window.width
                || row + 2 >= window.top + window.height) return false;
        int center = (left + right) / 2;
        return strongRun(window, row + 1, low, high)
                && strongRun(window, row + 2, low, high)
                && !window.dark(center, row + 1, staff)
                && !window.dark(center, row + 2, staff);
    }

    private static boolean ownsRuledFingeringTwo(Window window, boolean[] used,
            List<PlayingTechniqueDetector.Word> words, int left, int top, int right, int bottom,
            float gap, List<PrintedBeamShafts.Shaft> shafts) {
        int glyphWidth = right - left + 1, upperHeight = bottom - top + 1;
        if (glyphWidth < .68f * gap || glyphWidth > 1.02f * gap
                || upperHeight < .21f * gap || upperHeight > .44f * gap) return false;
        int shaftOwners = 0;
        for (var shaft : shafts) {
            float dx = (left + right - shaft.left() - shaft.right()) * .5f;
            float dy = top - shaft.endY();
            if (dx >= .18f * gap && dx <= .72f * gap
                    && dy >= .17f * gap && dy <= .65f * gap) shaftOwners++;
        }
        if (shaftOwners != 1) return false;

        int flank = Math.round(.42f * gap);
        int first = bottom + 1, last = first;
        int stop = window.top + window.height;
        if (left - flank < window.left || right + flank >= window.left + window.width
                || first + Math.round(gap * 1.5f) >= stop) return false;
        if (!strongRun(window, first, left - flank, right + flank)) first++;
        last = first;
        while (last < stop && strongRun(window, last, left - flank, right + flank)) last++;
        if (last - first < 2 || last - first > Math.round(gap * .30f)) return false;
        // A short fringe may be solid across the glyph and its flanks without reaching the outer rule.
        if (strongRun(window, last, left - 3, right + 3)) last++;
        int cropRight = right + 1, end = last;
        int limit = Math.min(stop, top + Math.round(gap * 1.4f));
        while (end < limit) {
            int dark = 0;
            for (int x = left; x <= cropRight; x++) {
                if ((window.plane[end * window.stride + x] & 255) < 188) dark++;
            }
            if (dark < 2) break;
            if ((window.plane[end * window.stride + left - 2] & 255) < 188
                    || (window.plane[end * window.stride + cropRight + 2] & 255) < 188) return false;
            end++;
        }
        if (end >= limit || end - top < .95f * gap || end - last < .42f * gap) return false;
        int h = window.plane.length / window.stride, corroborating = 0;
        for (var word : words) {
            if (!"2 2 2".equals(word.text()) || word.left() * window.stride > left
                    || word.right() * window.stride < cropRight
                    || word.top() * h > top || word.bottom() * h < end - 1
                    || (word.right() - word.left()) * window.stride > 8f * gap
                    || (word.bottom() - word.top()) * h > 2.6f * gap) continue;
            corroborating++;
        }
        if (corroborating != 1) return false;
        var rule = new java.util.BitSet();
        for (int y = first; y < last; y++)
            rule.set(y * window.stride + left, y * window.stride + cropRight + 1);
        var reading = JoinedDigitTemplates.inspect(window.plane, window.stride, h, rule,
                left, top, cropRight, end - 1, () -> {});
        if (reading == null || reading.scores()[2] < .62f
                || reading.scores()[2] - reading.runner() < .035f) return false;
        for (int y = last; y < end; y++)
            for (int x = left; x <= cropRight; x++)
                used[(y - window.top) * window.width + x - window.left] = true;
        return true;
    }

    private static boolean strongRun(Window window, int row, int left, int right) {
        int dark = 0;
        for (int x = left; x <= right; x++)
            if ((window.plane[row * window.stride + x] & 255) < 188) dark++;
        return dark >= right - left - 1;
    }

    private static boolean ownsBeamSideTenutoRow(Window window, int left, int top,
            int right, int bottom, int size, List<PrintedBeamShafts.Shaft> shafts, float gap) {
        int width = right - left + 1, height = bottom - top + 1;
        if (width < gap * .93f || width > gap * 1.5f || height < gap * .21f || height > gap * .47f
                || size < width * height * .73f) return false;
        float center = (left + right) * .5f;
        boolean attached = false;
        for (var shaft : shafts) {
            float axis = (shaft.left() + shaft.right()) * .5f;
            if (center < axis - .74f * gap || center > axis - .13f * gap) continue;
            if (shaft.endY() - bottom >= .19f * gap && shaft.endY() - top <= .92f * gap) attached = true;
        }
        if (!attached) return false;
        return beamSideTenutoRows(window.plane, window.stride, shafts, gap);
    }

    static boolean beamSideTenutoRows(byte[] plane, int stride,
            List<PrintedBeamShafts.Shaft> shafts, float gap) {
        if (shafts.size() != 3) return false;
        int height = plane.length / stride;
        for (var shaft : shafts) {
            float axis = (shaft.left() + shaft.right()) * .5f;
            int a = Math.round(axis - gap * 1.31f), b = Math.round(axis + gap * .49f);
            int y0 = shaft.endY() - Math.round(.94f * gap), y1 = shaft.endY() - Math.round(.19f * gap);
            if (a < 0 || b >= stride || y0 < 0 || y1 >= height) return false;
            int enough = 0, consecutive = 0;
            for (int y = y0; y <= y1; y++) {
                int run = 0, longest = 0;
                for (int x = a; x <= b; x++) {
                    run = (plane[y * stride + x] & 255) < 188 ? run + 1 : 0;
                    longest = Math.max(longest, run);
                }
                consecutive = longest >= gap * .93f && longest <= gap * 1.5f ? consecutive + 1 : 0;
                enough = Math.max(enough, consecutive);
            }
            if (enough < Math.round(gap * .23f) || enough > Math.round(gap * .49f)) return false;
        }
        return true;
    }

    private static boolean separatedBeamFingeringOne(Window window, int x, int rail, float gap,
            List<PrintedBeamShafts.Shaft> shafts) {
        int pad = Math.round(gap * 1.04f), left = x - pad, right = x + pad;
        int top = rail - Math.round(.43f * gap), bottom = rail + Math.round(1.55f * gap);
        int height = window.plane.length / window.stride;
        if (left < 0 || right >= window.stride || top < 0 || bottom >= height) return false;
        var marks = RawNumeralComponents.capture(window.plane, window.stride, height,
                left, top, right, bottom, 197, () -> {}, () -> {});
        int hits = 0;
        for (var mark : marks.components()) {
            int w = mark.right - mark.left + 1, h = mark.bottom - mark.top + 1;
            if (mark.left <= left || mark.right >= right || mark.top <= top || mark.bottom >= bottom
                    || x < mark.left || x > mark.right || w < .52f * gap || w > .94f * gap
                    || h < .96f * gap || h > 1.35f * gap || mark.size() < w * h * .50f) continue;
            int owners = 0;
            for (var shaft : shafts) {
                float center = (mark.left + mark.right - shaft.left() - shaft.right()) * .5f;
                if (Math.abs(center) <= .63f * gap
                        && Math.abs(mark.top - shaft.endY()) < .85f * gap) owners++;
            }
            if (owners != 1) continue;
            var fit = JoinedDigitTemplates.inspect(window.plane, window.stride, height,
                    new java.util.BitSet(), mark.left, mark.top, mark.right, mark.bottom, () -> {});
            if (fit != null && fit.digit() == 1) hits++;
        }
        return hits == 1;
    }

    private static boolean ownsHarmonicTenuto(float x, int top, int bottom,
            float[] headsX, float[] stopsY, int direction, float gap) {
        int owners = 0;
        for (int i = 0; i < headsX.length; i++) {
            if (Math.abs(x - headsX[i]) > gap * .26f) continue;
            float clearance = direction > 0 ? stopsY[i] - bottom : top - stopsY[i];
            if (clearance >= gap * (direction > 0 ? 2.25f : .65f)
                    && clearance <= gap * (direction > 0 ? 3.2f : 1.75f)) owners++;
        }
        return owners == 1;
    }

    private static boolean ownsOctaveAnnotation(byte[] ink, int width, int height,
            List<PlayingTechniqueDetector.Word> words, int[] pixels, int count, Window window,
            int left, int top, int right, int bottom, boolean headBand, float[] headsX,
            List<PrintedBeamShafts.Shaft> shafts, float gap) {
        int glyphWidth = right - left + 1, glyphHeight = bottom - top + 1;
        if (glyphHeight < .78f * gap || glyphHeight > 1.9f * gap || glyphWidth < .38f * gap
                || glyphWidth > 2.45f * gap) return false;
        var center = (left + right) * .5f;
        boolean nearHead = false;
        for (int i = 0; i < headsX.length; i++) {
            var shaft = shafts.get(i);
            float ownerX = headBand ? headsX[i] : (shaft.left() + shaft.right()) * .5f;
            if (Math.abs(ownerX - center) <= .62f * gap) nearHead = true;
        }
        if (!headBand && ownsWideBeamFingeringOne(ink, width, height, words, left, top, right, bottom, shafts, gap))
            return true;
        if (!nearHead && headBand && glyphWidth >= 1.25f * gap && glyphWidth <= 1.55f * gap) {
            int owners = 0;
            for (float headX : headsX) if (Math.abs(headX - center) <= .76f * gap) owners++;
            if (owners == 1 && PrintedRomanStrings.hasRomanSerifs(
                    component(pixels, count, window, width, left, top, right, bottom), width, gap)) return true;
        }
        if (!headBand && ownsSeparatedRomanSerifs(ink, width, height, left, top, right,
                bottom, shafts, gap)) return true;
        if (!nearHead) return false;
        if (glyphWidth >= 1.65f * gap) {
            // The broad printed IV has two letter strokes inside its retained OCR box.
            for (var word : words)
                if ("IV".equals(word.text()) && word.left() * width <= left && word.right() * width > right
                        && word.top() * height <= top && word.bottom() * height > bottom
                        && (word.right() - word.left()) * width <= 3.3f * gap
                        && (word.bottom() - word.top()) * height <= 2.6f * gap) return true;
            if (headBand || glyphWidth < 1.9f * gap) return false;
            var glyph = component(pixels, count, window, width, left, top, right, bottom);
            return PrintedRomanStrings.hasJoinedThreeSerifs(glyph, width, gap);
        }
        var glyph = component(pixels, count, window, width, left, top, right, bottom);
        if (!headBand && glyphWidth >= .6f * gap && glyphWidth <= .98f * gap
                && glyphHeight >= .95f * gap && glyphHeight <= 1.3f * gap) {
            var reading = JoinedDigitTemplates.inspect(ink, width, height, new java.util.BitSet(),
                    left, top, right, bottom, () -> {});
            if (reading != null && reading.digit() == 1) return true;
        }
        if (isUnambiguousOne(ink, width, glyph) || isUnambiguousTwo(ink, width, glyph)
                || headBand && PrintedRomanStrings.hasRomanSerifs(glyph, width, gap)) return true;
        if (!headBand && glyphWidth >= .65f * gap && glyphWidth <= 1.2f * gap
                && glyphHeight <= 1.2f * gap && count >= .61f * glyphWidth * glyphHeight) {
            var reading = JoinedDigitTemplates.inspect(ink, width, height, new java.util.BitSet(),
                    left, top, right, bottom, () -> {});
            if (reading != null) {
                if (reading.digit() == 2) return true;
                var scores = reading.scores();
                boolean shapedTwo = scores[2] >= .64f && scores[2] - scores[3] >= .05f
                        && scores[2] - scores[9] >= .032f;
                if (shapedTwo) {
                    for (var word : words) {
                        if ("2".equals(word.text()) && word.left() * width < left
                                && word.right() * width > right && word.top() * height < top
                                && word.bottom() * height > bottom
                                && (word.right() - word.left()) * width <= 1.7f * gap
                                && (word.bottom() - word.top()) * height <= 2.1f * gap) return true;
                    }
                }
            }
        }
        for (var word : words)
            if ("2".equals(word.text()) && ownedBoldTwo(ink, width, height, glyph, word)) return true;
        return false;
    }

    private static boolean ownsSeparatedRomanSerifs(byte[] ink, int width, int height,
            int left, int top, int right, int bottom, List<PrintedBeamShafts.Shaft> shafts, float gap) {
        int wide = right - left + 1, tall = bottom - top + 1;
        if (shafts.get(0).direction() != -1 || wide < .53f * gap || wide > .87f * gap
                || tall < 1.26f * gap || tall > 1.64f * gap) return false;
        int pad = Math.round(2.25f * gap);
        int x0 = left - pad, x1 = right + pad, y0 = top - 3, y1 = bottom + 3;
        if (x0 < 0 || x1 >= width || y0 < 0 || y1 >= height) return false;
        var letters = new java.util.ArrayList<RawNumeralComponents.Component>();
        for (var glyph : RawNumeralComponents.captureLongWindow(ink, width, height, x0, y0, x1, y1).components()) {
            if (glyph.left <= x0 || glyph.right >= x1
                    || Math.abs(glyph.top - top) > 2 || Math.abs(glyph.bottom - bottom) > 2
                    || Math.abs(glyph.right - glyph.left + 1 - wide) > 2
                    || !PrintedRomanStrings.hasRomanSerifs(glyph, width, gap)) continue;
            letters.add(glyph);
        }
        letters.sort(java.util.Comparator.comparingInt(g -> g.left));
        if (letters.size() < 2 || letters.size() > 3
                || letters.stream().noneMatch(g -> Math.abs(g.left - left) <= 1
                        && Math.abs(g.right - right) <= 1)) return false;
        for (int i = 1; i < letters.size(); i++) {
            int blank = letters.get(i).left - letters.get(i - 1).right - 1;
            if (blank < 1 || blank > .28f * gap) return false;
        }
        float center = (letters.get(0).left + letters.get(letters.size() - 1).right) * .5f;
        return shafts.stream().filter(s -> Math.abs(center - (s.left() + s.right()) * .5f) <= .8f * gap
                && s.endY() - bottom >= .1f * gap && s.endY() - bottom <= 1.55f * gap).count() == 1;
    }

    private static RawNumeralComponents.Component component(int[] pixels, int count, Window window,
            int width, int left, int top, int right, int bottom) {
        int[] raw = new int[count];
        for (int i = 0; i < count; i++)
            raw[i] = (pixels[i] / window.width + window.top) * width + pixels[i] % window.width + window.left;
        return new RawNumeralComponents.Component(raw, count, left, top, right, bottom);
    }

    private static boolean ownsWideBeamFingeringOne(byte[] ink, int width, int height,
            List<PlayingTechniqueDetector.Word> words, int left, int top, int right, int bottom,
            List<PrintedBeamShafts.Shaft> shafts, float gap) {
        int w = right - left + 1, h = bottom - top + 1;
        if (w <= .65f * h || w > .83f * h || w > gap || h < 1.07f * gap || h > 1.4f * gap) return false;
        int ocr = 0, owners = 0;
        for (var word : words) {
            if ("1".equals(word.text()) && word.left() * width <= left && word.right() * width >= right
                    && word.top() * height <= top && word.bottom() * height >= bottom
                    && (word.right() - word.left()) * width <= 2f * gap
                    && (word.bottom() - word.top()) * height <= 2f * gap) ocr++;
        }
        if (ocr > 1) return false;
        for (var shaft : shafts) {
            float dx = Math.abs((left + right - shaft.left() - shaft.right()) * .5f);
            if (dx <= .77f * gap && Math.abs(top - shaft.endY()) <= .85f * gap) owners++;
        }
        if (owners != 1) return false;
        int spines = 0, barRows = 0;
        for (int x = left + 2; x <= right - 1; x++) {
            int filled = 0;
            for (int y = top + 1; y < bottom; y++)
                if ((ink[y * width + x] & 255) <= 165) filled++;
            if (filled >= h * .78f) spines++;
        }
        for (int y = top + Math.round(h * .43f); y <= top + Math.round(h * .66f); y++) {
            int filled = 0;
            for (int x = left; x <= right; x++)
                if ((ink[y * width + x] & 255) <= 165) filled++;
            if (filled >= w * .78f) barRows++;
        }
        if (spines < 2 || spines > .54f * w || barRows >= 2) return false;
        var reading = JoinedDigitTemplates.inspect(ink, width, height, new java.util.BitSet(),
                left, top, right, bottom, () -> {});
        if (reading == null) return false;
        float one = reading.scores()[1], margin = one - reading.runner();
        return ocr == 1 ? one >= .46f && margin >= (one >= .50f ? .01f : .019f)
                : one >= .56f && margin >= .05f;
    }

    private static boolean memberOwnsLowerHead(int[] pixels, int size, Window window, int left, int right,
            int top, int bottom, List<Head> lowerHeads, float gap) {
        int radius = Math.round(gap * .17f);
        int area = (2 * radius + 1) * (2 * radius + 1);
        for (var head : lowerHeads) {
            int x = Math.round(head.x()), y = Math.round(head.y());
            if (x - radius < left || x + radius > right || y - radius < top || y + radius > bottom) continue;
            int core = 0;
            for (int i = 0; i < size; i++) {
                int xx = pixels[i] % window.width + window.left;
                int yy = pixels[i] / window.width + window.top;
                if (Math.abs(xx - x) <= radius && Math.abs(yy - y) <= radius) core++;
            }
            if (core >= .8f * area) return true;
        }
        return false;
    }

    private static boolean ownsSegueBeforeOctave(byte[] raw, int width, int height, float gap,
            int minX, int minY, int maxX, int maxY, List<PlayingTechniqueDetector.Word> words) {
        for (var word : words) {
            if (!CrossedDyadOctaveRibbon.isSegueEight(word) || word.left() * width >= minX
                    || word.top() * height >= minY || word.bottom() * height <= maxY) continue;
            int last = Math.round(word.right() * width);
            int firstDot = last - Math.round(.60f * gap);
            int firstRow = Math.round(word.top() * height + .64f * gap);
            for (int row = firstRow; row <= firstRow + Math.round(.40f * gap); row++) {
                int digit = CrossedDyadOctaveRibbon.recognizedLeadingEight(
                        raw, width, height, firstDot, row, gap, List.of(word));
                if (digit < 0) continue;
                float captionEnd = digit - gap * .78f;
                float captionBottom = Math.min(word.bottom() * height, word.top() * height + gap * 2.95f);
                if (captionEnd <= maxX || captionBottom <= maxY) break;
                var caption = new PlayingTechniqueDetector.Word("segue", word.left(), word.top(),
                        captionEnd / width, captionBottom / height);
                if (ownedDirectionCaption(raw, width, height, gap, minX, minY, maxX, maxY,
                        List.of(caption))) return true;
            }
        }
        return false;
    }

    private static boolean ownedDirectionCaption(
            byte[] raw, int width, int height, float gap, int minX, int minY, int maxX, int maxY,
            List<PlayingTechniqueDetector.Word> words) {
        for (var word : words) {
            if (!ExpressiveDirectionText.isBowCaption(word.text())
                    && !word.text().strip().equalsIgnoreCase("segue")) continue;
            int l = Math.round(word.left() * width), r = Math.round(word.right() * width);
            int t = Math.round(word.top() * height), b = Math.round(word.bottom() * height);
            if (l < 0 || r >= width || t < 0 || b >= height
                    || r-l < gap * 3.2f || r-l > gap * 9 || b-t < gap || b-t > gap * 3
                    || minX <= l || maxX >= r || minY <= t || maxY >= b) continue;
            var source = RawNumeralComponents.captureLongWindow(raw, width, height, l, t, r, b, 165);
            int letters = 0;
            int left = width, right = -1, top = height, bottom = -1;
            boolean numeral = false;
            for (var glyph : source.components()) {
                if (glyph.size() < gap * gap * .08f) continue;
                letters++;
                if (glyph.left <= maxX && glyph.right >= minX && glyph.top <= maxY && glyph.bottom >= minY) {
                    left = Math.min(left, glyph.left);
                    right = Math.max(right, glyph.right);
                    top = Math.min(top, glyph.top);
                    bottom = Math.max(bottom, glyph.bottom);
                }
                if (glyph.bottom-glyph.top < gap * .65f || glyph.right-glyph.left > gap * 1.6f)
                    continue;
                int[] pixels = java.util.stream.IntStream.range(0,glyph.size()).map(glyph::pixel).toArray();
                if (TupletNumeralBeamBand.detachedThree(
                        pixels,width,glyph.left,glyph.top,glyph.right,glyph.bottom,gap)
                        || TripletRhythmDetector.isIsolatedThree(glyph,width)) numeral = true;
            }
            // Thin antialiasing may join two letters. Keep their bounded shared
            // ink envelope only after ruling out any separate numeral.
            boolean owns = left <= minX + 1 && right >= maxX - 1 && top <= minY + 1 && bottom >= maxY - 1;
            if (owns && letters >= 4 && !numeral) return true;
        }
        return false;
    }

    private static boolean ownedReturningCurve(int[] pixels, int size, Window window, int left, int right,
            List<PrintedBeamShafts.Shaft> shafts, int travel, float gap, float[] headsX) {
        if (right - left < gap * 2.1f || right - left > gap * 3f) return false;
        boolean owned = false;
        for (int i = 0; i < 2; i++) {
            var a = shafts.get(i);
            var b = shafts.get(i + 1);
            if (Math.abs(left - (a.left() + a.right()) * .5f) <= gap * .38f
                    && Math.abs(right - (b.left() + b.right()) * .5f) <= gap * .38f) owned = true;
            if (headsX != null && travel < 0 && left > headsX[i] && right < headsX[i + 1]
                    && left - headsX[i] <= gap * .62f && headsX[i + 1] - right <= gap * .73f) owned = true;
        }
        if (!owned) return false;
        return thinReturningProfile(pixels, size, window, left, right, travel > 0, gap);
    }

    private static boolean thinReturningProfile(int[] pixels, int size, Window window,
            int left, int right, boolean invert, float gap) {
        int length = right - left + 1;
        int[] low = new int[length], high = new int[length], counts = new int[length];
        Arrays.fill(low, Integer.MAX_VALUE);
        Arrays.fill(high, -1);
        for (int i = 0; i < size; i++) {
            int x = pixels[i] % window.width + window.left - left;
            int y = pixels[i] / window.width;
            if (invert) y = window.height - 1 - y;
            low[x] = Math.min(low[x], y);
            high[x] = Math.max(high[x], y);
            counts[x]++;
        }
        for (int i = 0; i < length; i++)
            if (counts[i] == 0 || high[i] - low[i] + 1 != counts[i])
                return false;
        for (int i = 0; i < length; i++) {
            int before = Math.max(0, i - 2), after = Math.min(length - 1, i + 2);
            float slope = (high[after] + low[after] - high[before] - low[before])
                    / (2f * (after - before));
            if (counts[i] > gap * .36f * Math.hypot(1, slope)) return false;
        }
        return PrintedSlurContours.completeReturningProfile(low, high, 0, length - 1, gap);
    }

    /** A separate centered numeral can be joined to a staff rule at this print scale. */
    static boolean centeredThree(byte[] gray, int width, int height, float x,
            float top, float bottom, float gap, Rows rows, StaffPitchTrack staff) {
        return centeredNumeral(gray, width, height, x, top, bottom, gap, rows, staff, false);
    }

    static boolean centeredNumeralCandidate(byte[] gray, int width, int height, float x,
            float top, float bottom, float gap, Rows rows, StaffPitchTrack staff) {
        return centeredNumeral(gray, width, height, x, top, bottom, gap, rows, staff, true);
    }

    private static boolean centeredNumeral(byte[] gray, int width, int height, float x,
            float top, float bottom, float gap, Rows rows, StaffPitchTrack staff, boolean unresolved) {
        int l = Math.round(x - gap * 1.05f), r = Math.round(x + gap * 1.05f);
        int t = Math.max(rows.first(), Math.round(top));
        int b = Math.min(rows.last(), Math.round(bottom));
        if (l < 0 || r >= width || t < 0 || b >= height || t >= b) return false;
        int span = r - l + 1, depth = b - t + 1;
        var mask = trackedStaffMask(gray, width, height, l, r, t, b, staff, gap);
        var crop = new byte[span * depth];
        for (int row = 0; row < depth; row++) {
            for (int col = 0; col < span; col++) {
                int dst = row * span + col;
                crop[dst] = mask != null && mask[dst] ? (byte) 255 : gray[(t + row) * width + l + col];
            }
        }
        var ink = RawNumeralComponents.capture(crop, span, depth, 0, 0, span - 1, depth - 1, 165, () -> {}, () -> {});
        for (var glyph : ink.components()) {
            int w = glyph.right - glyph.left + 1, h = glyph.bottom - glyph.top + 1;
            if (glyph.left <= 0 || glyph.right >= span - 1 || glyph.top <= 0 || glyph.bottom >= depth - 1
                    || w < gap * .36f || w > gap * 1.35f || h < gap * .72f || h > gap * 2.18f
                    || Math.abs(l + (glyph.left + glyph.right) * .5f - x) > gap * .65f) continue;
            if (unresolved) {
                if (glyph.size() >= gap * gap * .16f) return true;
                continue;
            }
            if (TripletRhythmDetector.isIsolatedThree(glyph, span)) return true;
            var shape = JoinedDigitTemplates.inspect(crop, span, depth, new java.util.BitSet(),
                    glyph.left, glyph.top, glyph.right, glyph.bottom, () -> {});
            if (shape != null && shape.digit() == 3) return true;
        }
        return false;
    }

    private static boolean[] trackedStaffMask(byte[] raw, int w, int h, int left, int right,
            int top, int bottom, StaffPitchTrack staff, float gap) {
        if (staff == null) return null;
        int step = Math.max(1, Math.round(gap * .22f));
        int band = Math.max(1, Math.round(gap * .19f));
        int bridge = Math.round(gap * 2f);
        int span = right - left + 1;
        boolean[] mask = new boolean[span * (bottom - top + 1)];
        for (int x = left; x <= right; x++) {
            if (!staff.verifiedAt(x)) continue;
            var frame = staff.at(x);
            for (int r = 0; r < 5; r++) {
                int y = Math.round(frame[0] - r * frame[1]);
                if (y - band < top || y + band > bottom) continue;
                // Only an independently tracked rule continuous on both sides may hide dark ink.
                for (int delta = -band; delta <= band; delta++) {
                    if ((raw[(y + delta) * w + x] & 255) < 205
                            && continuousStaffRule(raw, w, h, x, step, bridge, staff, r, delta))
                        mask[(y + delta - top) * span + x - left] = true;
                }
            }
        }
        return mask;
    }

    private static boolean continuousStaffRule(byte[] raw, int w, int h, int x, int step, int bridge,
            StaffPitchTrack staff, int rule, int delta) {
        for (int direction : new int[]{-1,1}) {
            int count = 0, dark = 0;
            for (int d = step; d <= bridge; d += step) {
                int col = x + d * direction;
                if (col < 0 || col >= w || !staff.verifiedAt(col)) return false;
                var frame = staff.at(col);
                int y = Math.round(frame[0] - rule * frame[1]) + delta;
                if (y < 1 || y + 1 >= h) return false;
                count++;
                for (int yy = y - 1; yy <= y + 1; yy++) {
                    if ((raw[yy * w + col] & 255) >= 205) continue;
                    dark++;
                    break;
                }
            }
            if (dark < count * .88f) return false;
        }
        return true;
    }

    private record Window(byte[] plane, int stride, int left, int top, int width, int height, boolean[] trackedMask) {
        private boolean dark(int x, int y, boolean[] staff) {
            return !staff[y - top]
                    && (trackedMask == null || !trackedMask[(y - top) * width + x - left])
                    && (plane[y * stride + x] & 255) < 205;
        }

        private boolean[] staffRows(int tail, float gap) {
            boolean[] broad = new boolean[height];
            int end = left + width - 1 + tail;
            int leftMargin = left - tail;
            int tailLength = Math.round(1.6f * gap);
            for (int row = 0; row < height; row++) {
                int count = 0, leftTail = 0, rightTail = 0;
                for (int x = leftMargin; x <= end; x++) {
                    if ((plane[(top + row) * stride + x] & 255) >= 200) continue;
                    count++;
                    if (x < leftMargin + tailLength) leftTail++;
                    if (x > end - tailLength) rightTail++;
                }
                broad[row] = count >= .92f * (end - leftMargin + 1)
                        && leftTail >= .90f * tailLength && rightTail >= .90f * tailLength;
            }
            boolean[] result = new boolean[height];
            for (int row = 0; row < height; row++) {
                int consecutive = 0;
                if (broad[row]) {
                    int first = row, last = row;
                    while (first > 0 && broad[first - 1]) first--;
                    while (last + 1 < height && broad[last + 1]) last++;
                    consecutive = last - first + 1;
                }
                result[row] = consecutive > 0 && consecutive <= Math.round(.25f * gap);
            }
            return result;
        }

        private boolean bracket(boolean[] staff, float start, float end, float headTop,
                float headBottom, float upper, float lower, float gap, Rows sourceRows) {
            return bracket(staff, start, end, headTop, headBottom, upper, lower, gap, sourceRows,
                    false, List.of(), null, null, null);
        }

        private boolean bracket(boolean[] staff, float start, float end, float headTop,
                float headBottom, float upper, float lower, float gap, Rows sourceRows,
                boolean verifyContinuingStroke, List<PlayingTechniqueDetector.Word> directions,
                StaffPitchTrack track, float[] headsX, float[] headsY) {
            return bracket(staff, start, end, headTop, headBottom, upper, lower, gap, sourceRows,
                    verifyContinuingStroke, directions, track, headsX, headsY, false);
        }

        private boolean bracket(boolean[] staff, float start, float end, float headTop,
                float headBottom, float upper, float lower, float gap, Rows sourceRows,
                boolean verifyContinuingStroke, List<PlayingTechniqueDetector.Word> directions,
                StaffPitchTrack track, float[] headsX, float[] headsY, boolean octaveMarks) {
            float middle = (start + end) * .5f;
            int a = Math.round(start - gap * .32f), b = Math.round(middle - gap * .72f);
            int c = Math.round(middle + gap * .72f), d = Math.round(end + gap * .32f);
            int pad = Math.max(6, Math.round(.12f * Math.max(middle - a, d - middle)) + 1);
            int first = Math.max(sourceRows.first, Math.max(top + pad, Math.round(headTop - 7 * gap)));
            int last = Math.min(sourceRows.last, Math.min(top + height - pad - 1, Math.round(headBottom + 7 * gap)));
            for (int y = first; y <= last; y++) {
                if (y > upper - gap * .48f && y < lower + gap * .48f) continue;
                for (int slope = -2; slope <= 2; slope++)
                    if (arm(staff, y, middle, slope * .06f, a, b)
                            && arm(staff, y, middle, slope * .06f, c, d)
                            && !(track != null && continuedStaffFringe(track, y, middle, slope * .06f, a, d, gap)
                                    && !hasProtrudingStaffTick(start, end, gap, y, middle, slope * .06f, a, d))
                            && !(headsX != null && slope == 0
                                    && ownsTopStaffAndHeads(track,y,a,d,gap,headsX,headsY))
                            && !(headsX != null && track != null && y > lower + 3.65f * gap
                                    && y >= sourceRows.last - 1.65f * gap
                                    && continuingBoundaryBow(staff, track, y, middle, slope * .06f, a, b, c, d, gap))
                            && (!octaveMarks
                                    || !dottedRibbonOwnsArm(staff, y, middle, slope * .06f, a, b, c, d, gap)
                                    && !archOwnsBracketArm(staff, y, middle, slope * .06f, a, b, c, d, gap))
                            && (!verifyContinuingStroke || !continuingStroke(
                                    staff, start, end, gap, y, middle, slope * .06f, a, b, c, d, directions)))
                        return true;
            }
            return false;
        }

        /** The untracked outer staff can still continue through the apparent bracket arms. */
        private boolean continuedStaffFringe(StaffPitchTrack track, int y, float middle, float slope,
                int first, int last, float gap) {
            int tail = Math.round(1.35f * gap), step = Math.max(2, Math.round(.3f * gap));
            int from = first - tail, to = last + tail;
            if (from < 0 || to >= stride) return false;
            for (int anchor : new int[]{from + step, to - step}) {
                if (!track.verifiedAt(anchor) || !track.verifiedAt(anchor - step)
                        || !track.verifiedAt(anchor + step)) continue;
                var frame = track.at(anchor);
                for (int rule = 0; rule < 5; rule++) {
                    int core = Math.round(frame[0] - rule * frame[1]);
                    if (core < 1 || core + 1 >= plane.length / stride
                            || Math.abs(core - y) > Math.max(2, Math.round(.18f * gap))) continue;
                    if (ownsStaffFringe(from, to, first, last, y, core, middle, slope, gap)) return true;
                }
            }
            return false;
        }

        /** A verified reference from the same staff and its parallel second rule disambiguate a top edge. */
        private boolean ownsTopStaffAndHeads(StaffPitchTrack track, int row, int first, int last, float gap,
                                             float[] headsX, float[] headsY) {
            if (track == null || !track.verified()) return false;
            int from = first - Math.round(1.25f * gap), to = last + Math.round(1.25f * gap);
            if (from < 0 || to >= stride) return false;
            var frame = track.at(from);
            int core = Math.round(frame[0] - 4 * frame[1]);
            if (Math.abs(core-row) > .24f*gap || core < gap || core+gap+3 >= plane.length/stride) return false;
            int second = core+Math.round(frame[1]);
            for (int x=from;x<=to;x++) {
                if (!nearRule(x,core) || !nearRule(x,second)) return false;
            }
            int vertical = Math.round(gap * .62f);
            for (int x=first;x<=last;x++) {
                for (int side : new int[]{-1,1}) {
                    int end = row+side*vertical, k=row;
                    while (k!=end && (plane[k*stride+x]&255)<205) k+=side;
                    if (k!=end || (plane[k*stride+x]&255)>=205) continue;
                    boolean owned=false;
                    if (side==1) for (int i=0;i<headsX.length;i++) {
                        if (Math.abs(x-headsX[i])<=.68f*gap && Math.abs(end-headsY[i])<=.62f*gap
                                && FilledSparseBeamHead.completeInterior(plane,stride,plane.length/stride,
                                        headsX[i],headsY[i],gap)) owned=true;
                    }
                    if (!owned) return false;
                }
            }
            return true;
        }

        private boolean nearRule(int x,int center) {
            for (int y=center-2;y<=center+2;y++) if ((plane[y*stride+x]&255)<205) return true;
            return false;
        }

        private boolean hasProtrudingStaffTick(float start, float end, float gap, int row,
                float middle, float slope, int left, int right) {
            int reach = Math.max(5, Math.round(.58f * gap));
            for (int x = left; x <= right; x++) {
                if (Math.abs(x - start) > .45f * gap && Math.abs(x - end) > .45f * gap) continue;
                int center = row + Math.round(slope * (x - middle));
                for (int direction : new int[]{-1, 1}) {
                    int distance = 0;
                    while (distance <= reach && center + distance * direction >= top
                            && center + distance * direction < top + height
                            && (plane[(center + distance * direction) * stride + x] & 255) < 205) distance++;
                    if (distance > reach) return true;
                }
            }
            return false;
        }

        private boolean ownsStaffFringe(int from, int to, int first, int last, int y, int core,
                float middle, float slope, float gap) {
            int linked = 0, visible = 0;
            for (int x = from; x <= to; x++) {
                if ((plane[(core - 1) * stride + x] & 255) >= 205
                        && (plane[core * stride + x] & 255) >= 205
                        && (plane[(core + 1) * stride + x] & 255) >= 205) return false;
                int sample = y + Math.round(slope * (x - middle));
                if (x < first || x > last || sample < top || sample >= top + height
                        || (plane[sample * stride + x] & 255) >= 205) continue;
                visible++;
                if (Math.abs(sample - core) > Math.max(3, Math.round(.24f * gap))) continue;
                int towards = Integer.signum(core - sample);
                while (Math.abs(sample - core) > 1 && (plane[sample * stride + x] & 255) < 205) sample += towards;
                if (Math.abs(sample - core) <= 1 && (plane[sample * stride + x] & 255) < 205) linked++;
            }
            return visible > 0 && linked >= .94f * visible;
        }

        /** Require source ownership of both arm traces by one continuing stroke. */
        private boolean continuingStroke(
                boolean[] staff, float start, float end, float gap,
                int y, float middle, float slope, int a, int b, int c, int d, List<PlayingTechniqueDetector.Word> directions) {
            int x0 = Math.max(0, Math.round(start - gap * 2.4f));
            int x1 = Math.min(stride - 1, Math.round(end + gap * 2.4f));
            int sheetHeight = plane.length / stride;
            int y0 = Math.max(0, Math.round(y - gap * 1.65f));
            int y1 = Math.min(sheetHeight - 1, Math.round(y + gap * 1.65f));
            if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) > 16_384) return false;
            if (Math.abs(slope) <= .06f
                    && !hasBracketTick(staff, start, end, gap, y, middle, slope, a, d)) {
                // The edge of the same dotted ribbon may cover an arm's row
                // after its shorter dashes have fallen below the threshold.
                int fringe = Math.max(1, Math.round(gap * .12f));
                for (int row = y - fringe; row <= y + fringe; row++)
                    if (continuedDotRow(row, middle, slope, x0, a, d, x1, gap)
                            && !hasBracketTick(staff, start, end, gap, row, middle, slope, a, d)) return true;
            }
            if (sharesBowCaption(directions, a, d, y, middle, slope, gap)
                    || hasOctaveRail(directions, staff, start, end, gap, y, middle, slope, a, c, d, x1))
                return true;
            int expectedLeft = 0, expectedRight = 0;
            for (int x = a; x <= b; x++)
                if (dark(x, y + Math.round(slope * (x - middle)), staff)) expectedLeft++;
            for (int x = c; x <= d; x++)
                if (dark(x, y + Math.round(slope * (x - middle)), staff)) expectedRight++;

            var components = RawNumeralComponents.capture(plane, stride, sheetHeight,
                    x0, y0, x1, y1, 204, () -> {}, () -> {}).components();
            for (var ink : components) {
                boolean extendsLeft = start - ink.left >= gap * 1.25f;
                boolean extendsRight = ink.right - end >= gap * 1.25f;
                if (ink.left > b || ink.right < c || !extendsLeft && !extendsRight)
                    continue;
                int[] high = new int[ink.right - ink.left + 1], low = new int[high.length];
                Arrays.fill(high, sheetHeight);
                int leftInk = 0, rightInk = 0;
                for (int i = 0; i < ink.size(); i++) {
                    int pixel = ink.pixel(i), x = pixel % stride, row = pixel / stride;
                    int column = x - ink.left;
                    high[column] = Math.min(high[column], row);
                    low[column] = Math.max(low[column], row);
                    int modeled = y + Math.round(slope * (x - middle));
                    if (row != modeled) continue;
                    if (x >= a && x <= b && dark(x, row, staff)) leftInk++;
                    if (x >= c && x <= d && dark(x, row, staff)) rightInk++;
                }
                if (leftInk != expectedLeft || rightInk != expectedRight) continue;
                boolean thin = true;
                for (int i = 0; i < high.length; i++)
                    if (low[i] - high[i] + 1 > gap * .43f) {
                        thin = false;
                        break;
                    }
                if ((thin || extendsLeft && extendsRight || smoothCurvedStroke(high, low, gap)
                            || smoothAttachedSlurEdge(high, low, gap))
                        && !hasBracketTick(staff, start, end, gap, y, middle, slope, a, d)) return true;
            }
            return false;
        }

        /** Follow the outside edge when clef-free note or fingering ink joins a thin arch from inside. */
        private static boolean smoothAttachedSlurEdge(int[] upper, int[] lower, float gap) {
            int width = upper.length;
            if (width < gap * 7) return false;
            int inset = Math.max(1, Math.round(gap * .18f));
            int end = width - 1 - inset;
            int middle = width / 2;
            if (upper[inset] - upper[middle] < .38f * gap
                    || upper[end] - upper[middle] < .62f * gap) return false;
            int narrowOutside = 0;
            int outwardSteps = 0;
            int sideColumns = 0;
            for (int i = inset; i <= end; i++) {
                int thickness = lower[i] - upper[i] + 1;
                if (thickness < 1 || thickness > gap * 2.1f
                        || i > inset && Math.abs(upper[i] - upper[i - 1]) > gap * .29f) return false;
                if (i > inset) {
                    int step = upper[i] - upper[i - 1];
                    if (i < middle && step > 0 || i > middle && step < 0) outwardSteps++;
                }
                if (i < width * .30f || i > width * .70f) {
                    sideColumns++;
                    if (thickness <= gap * .38f) narrowOutside++;
                }
            }
            return narrowOutside >= .54f * sideColumns && outwardSteps <= .09f * (end - inset);
        }

        /** A continuous slur can supply both apparent arms across the next staff row. */
        private static boolean smoothCurvedStroke(int[] high, int[] low, float gap) {
            int inset = Math.round((high.length - 1) * .08f);
            int last = high.length - 1 - inset, middle = high.length / 2;
            if (last - inset < gap * 4) return false;
            float center = (high[middle] + low[middle]) * .5f;
            float left = (high[inset] + low[inset]) * .5f - center;
            float right = (high[last] + low[last]) * .5f - center;
            if (Math.signum(left) != Math.signum(right)
                    || Math.min(Math.abs(left), Math.abs(right)) < gap * .48f) return false;
            int broad = 0;
            float previous = (high[inset] + low[inset]) * .5f;
            for (int x = inset; x <= last; x++) {
                int thickness = low[x] - high[x] + 1;
                if (thickness < 1 || thickness > gap * .75f) return false;
                if (thickness > gap * .55f) broad++;
                float row = (high[x] + low[x]) * .5f;
                if (Math.abs(row - previous) > gap * .18f) return false;
                previous = row;
            }
            return broad < (last - inset + 1) * .10f;
        }

        private boolean sharesBowCaption(List<PlayingTechniqueDetector.Word> words, int first,
                int last, int y, float middle, float slope, float gap) {
            float firstRow = y + slope * (first - middle), lastRow = y + slope * (last - middle);
            if (coveredByBowCaption(words, stride, plane.length / stride, gap,
                    first, Math.min(firstRow, lastRow), last, Math.max(firstRow, lastRow))) return true;
            return ownedDirectionCaption(plane, stride, plane.length / stride, gap, first,
                    (int) Math.floor(Math.min(firstRow, lastRow)), last,
                    (int) Math.ceil(Math.max(firstRow, lastRow)), words.stream()
                            .filter(word -> word.text().strip().equalsIgnoreCase("segue")).toList());
        }

        /** An octave numeral owns the repeated dashes that continue beyond this candidate bracket. */
        private boolean hasOctaveRail(List<PlayingTechniqueDetector.Word> words, boolean[] staff,
                float start, float end, float gap, int y, float middle, float slope, int a, int c, int d,
                int right) {
            if (Math.abs(slope) > .06f) return false;
            int sheetHeight = plane.length / stride;
            for (var word : words) {
                if (!"8".equals(word.text().strip())) continue;
                int l = Math.round(word.left() * stride), r = Math.round(word.right() * stride);
                int t = Math.round(word.top() * sheetHeight), b = Math.round(word.bottom() * sheetHeight);
                if (l < 0 || r >= stride || t < 0 || b >= sheetHeight
                        || l > a || r < a || r >= c || r - l > gap * 3
                        || b - t < gap || b - t > gap * 3.2f || y < t || y > b
                        || OctaveClefDigit.holes(plane, stride, l, t, r - l + 1, b - t + 1) != 2)
                    continue;
                if (extendedDotRow(y, middle, slope, r, right, d, gap)
                        && !hasBracketTick(staff, start, end, gap, y, middle, slope, r, d))
                    return true;
            }
            return false;
        }

        /** A near-horizontal rail made from repeated isolated dashes can extend past the triplet. */
        private boolean continuedDotRow(int y, float middle, float slope, int left, int start,
                int end, int right, float gap) {
            if (Math.abs(slope) > .06f) return false;
            return extendedDotRow(y, middle, slope, left, end, start, gap)
                    || extendedDotRow(y, middle, slope, start, right, end, gap);
        }

        private boolean extendedDotRow(int y, float middle, float slope, int left, int right,
                int boundary, float gap) {
            int minInk = Math.max(2, Math.round(gap * .13f)), maxInk = Math.round(gap * .60f);
            int maxGap = Math.round(gap * .47f), dots = 0, across = 0, run = 0, white = 0;
            if (right - left < gap * 4) return false;
            for (int x = left; x <= right + 1; x++) {
                int yy = y + Math.round(slope * (x - middle));
                if (yy < 0 || yy >= plane.length / stride) return false;
                if (x <= right && (plane[yy * stride + x] & 255) < 205) {
                    if (run == 0 && dots > 0 && white > maxGap) return false;
                    run++;
                    white = 0;
                } else {
                    if (run > 0) {
                        if (run > maxInk || run < minInk && x - run > left && x <= right) return false;
                        if (run >= minInk) {
                            dots++;
                            if (x <= boundary) across++;
                        }
                        run = 0;
                    }
                    white++;
                }
            }
            return dots >= 9 && across >= 3 && dots - across >= 3;
        }

        /** Ink near the lower system boundary can trace a shallow curve through both arm windows. */
        private boolean continuingBoundaryBow(boolean[] staff, StaffPitchTrack track, int y,
                float middle, float slope, int a, int b, int c, int d, float gap) {
            int sheetHeight = plane.length / stride;
            int extension = Math.round(4.5f * gap), vertical = Math.round(2.25f * gap);
            int from = a - extension, to = d + extension;
            if (from < 0 || to >= stride || y < vertical || y + vertical >= sheetHeight) return false;
            var ink = RawNumeralComponents.captureLongWindow(
                    plane, stride, sheetHeight, from, y - vertical, to, y + vertical, 205);
            for (var stroke : ink.components()) {
                if (stroke.left != from || stroke.right != to
                        || stroke.top == y - vertical || stroke.bottom == y + vertical) continue;
                int[] first = new int[to - from + 1], last = new int[first.length], counts = new int[first.length];
                java.util.Arrays.fill(first, Integer.MAX_VALUE);
                for (int i = 0; i < stroke.size(); i++) {
                    int pixel = stroke.pixel(i), x = pixel % stride - from, row = pixel / stride;
                    first[x] = Math.min(first[x], row);
                    last[x] = Math.max(last[x], row);
                    counts[x]++;
                }
                var columns = new java.util.ArrayList<PrintedSlurContours.ForeignRun>(first.length);
                for (int i = 0; i < first.length; i++) {
                    int thickness = last[i] - first[i] + 1;
                    if (counts[i] == 0 || counts[i] != thickness || thickness > .62f * gap) break;
                    columns.add(new PrintedSlurContours.ForeignRun(from + i,
                            List.of(new PrintedSlurContours.RawBand(first[i], last[i]))));
                }
                if (columns.size() != first.length) continue;
                var bow = PrintedSlurContours.fitPixelCells(columns, track);
                if (bow == null || !bow.above() || bow.bendInGaps() < .30f || bow.rmsInGaps() > .07f) continue;
                int leftResidual = 0, rightResidual = 0;
                for (int x = a; x <= d; x++) {
                    if (x > b && x < c) continue;
                    int row = y + Math.round(slope * (x - middle));
                    if (!dark(x, row, staff) || row >= first[x - from] && row <= last[x - from]) continue;
                    if (x <= b) leftResidual++;
                    else rightResidual++;
                }
                if (leftResidual < .63f * (b - a + 1) && rightResidual < .63f * (d - c + 1)) return true;
            }
            return false;
        }

        private boolean archOwnsBracketArm(boolean[] staff, int y, float middle, float slope,
                int a, int b, int c, int d, float gap) {
            int step = Math.max(1, Math.round(.21f * gap));
            int h = plane.length / stride;
            // A continuing curved stroke can cross one arm while a nearby annotation supplies the other.
            for (int side = 0; side < 2; side++) {
                int left = side == 0 ? a : c, right = side == 0 ? b : d;
                int edge = side == 0 ? left : right;
                int low = edge - Math.round((side == 0 ? 2.4f : .4f) * gap);
                int high = edge + Math.round((side == 0 ? .4f : 1.25f) * gap);
                for (int cx = low; cx <= high; cx += step) {
                    for (int apex = y - Math.round(1.0f * gap); apex <= y + Math.round(.16f * gap); apex++) {
                        for (float bend : new float[]{.46f, .59f, .72f, .85f}) {
                            if (!followsSlurArch(cx, apex, gap, bend, h)) continue;
                            int residual = 0, explained = 0;
                            for (int x = left; x <= right; x++) {
                                int row = y + Math.round(slope * (x - middle));
                                if (!dark(x, row, staff)) continue;
                                float distance = (x - cx) / gap;
                                float expected = apex + bend * gap * distance * distance / 9;
                                if (Math.abs(row - expected) > .19f * gap) residual++;
                                else explained++;
                            }
                            if (explained >= Math.round(.65f * gap)
                                    && residual < .63f * (right - left + 1)) return true;
                        }
                    }
                }
            }
            return false;
        }

        private boolean followsSlurArch(int cx, int apex, float gap, float bend, int height) {
            int hits = 0, missing = 0, left = 0, right = 0;
            int tolerance = Math.max(1, Math.round(.085f * gap));
            for (int k = -8; k <= 8; k++) {
                float d = k * .43f;
                int x = Math.round(cx + d * gap), y = Math.round(apex + bend * gap * d * d / 9);
                if (x < 0 || x >= stride || y - tolerance < 0 || y + tolerance >= height) return false;
                boolean traced = false;
                for (int yy = y - tolerance; yy <= y + tolerance; yy++)
                    if ((plane[yy * stride + x] & 255) < 171) traced = true;
                missing = traced ? 0 : missing + 1;
                if (missing > 1) return false;
                if (traced) {
                    hits++;
                    if (k <= -5) left++;
                    if (k >= 5) right++;
                }
            }
            return hits >= 15 && left >= 3 && right >= 3;
        }

        private boolean dottedRibbonOwnsArm(boolean[] staff, int y, float middle, float slope,
                int a, int b, int c, int d, float gap) {
            if (Math.abs(slope) > .06) return false;
            int begin = Math.max(0, a - Math.round(2.1f * gap));
            int end = Math.min(stride - 1, d + Math.round(2.1f * gap));
            var dots = ribbonDots(y, gap, begin, end);
            var owned = new java.util.BitSet();
            int leftDots = 0, rightDots = 0, adjacent = 0;
            for (int i = 0; i < dots.size(); i++) {
                var dot = dots.get(i);
                if (i > 0) {
                    if (adjacentRibbonDots(dots.get(i - 1), dot, gap)) adjacent++;
                }
                if (dot.right >= a && dot.left <= b) leftDots++;
                if (dot.right >= c && dot.left <= d) rightDots++;
                for (int k = 0; k < dot.size(); k++) owned.set(dot.pixel(k));
            }
            return dots.size() >= 9 && adjacent >= 7 && leftDots >= 2 && rightDots >= 2
                    && (!otherArmInk(staff, y, middle, slope, a, b, owned)
                        || !otherArmInk(staff, y, middle, slope, c, d, owned));
        }

        private List<RawNumeralComponents.Component> ribbonDots(int y, float gap, int begin, int end) {
            int sheetHeight = plane.length / stride;
            int top = Math.max(0, y - Math.round(.62f * gap)), bottom = Math.min(sheetHeight - 1,
                    y + Math.round(.62f * gap));
            var marks = RawNumeralComponents.capture(plane, stride, sheetHeight,
                    begin, top, end, bottom, 201, () -> {}, () -> {}).components();
            var dots = new java.util.ArrayList<RawNumeralComponents.Component>();
            for (var mark : marks) {
                int length = mark.right - mark.left + 1, height = mark.bottom - mark.top + 1;
                if (mark.left <= begin || mark.top <= top || mark.right >= end || mark.bottom >= bottom
                        || length < .13f * gap || length > .65f * gap
                        || height < .11f * gap || height > .44f * gap
                        || Math.abs((mark.bottom + mark.top) * .5f - y) > .16f * gap
                        || mark.size() < .56f * length * height) continue;
                dots.add(mark);
            }
            dots.sort(java.util.Comparator.comparingInt(dot -> dot.left));
            return dots;
        }

        private boolean otherArmInk(boolean[] staff, int y, float middle, float slope,
                int left, int right, java.util.BitSet owned) {
            int ink = 0;
            for (int x = left; x <= right; x++) {
                int row = y + Math.round(slope * (x - middle));
                if (dark(x, row, staff) && !owned.get(row * stride + x)) ink++;
            }
            return ink >= .63f * (right - left + 1);
        }

        private boolean ownsDottedRibbonGlyph(int left, int first, int right, int last, float gap,
                List<PlayingTechniqueDetector.Word> words) {
            int width = right - left + 1, height = last - first + 1;
            if (width < .13f * gap || width > .65f * gap || height < .11f * gap || height > .44f * gap)
                return false;
            int row = (first + last + 1) / 2, center = (left + right) / 2;
            int radius = Math.round(3.65f * gap);
            int begin = Math.max(0, center - radius), end = Math.min(stride - 1, center + radius);
            var dots = ribbonDots(row, gap, begin, end);
            int earlier = 0, later = 0, adjacent = 0;
            boolean owned = false;
            for (int i = 0; i < dots.size(); i++) {
                var dot = dots.get(i);
                if (dot.right < left) earlier++;
                if (dot.left > right) later++;
                if (Math.abs(dot.left-left) <= 1 && Math.abs(dot.right-right) <= 1
                        && Math.abs(dot.top-first) <= 1 && Math.abs(dot.bottom-last) <= 1)
                    owned = true;
                if (i > 0 && adjacentRibbonDots(dots.get(i-1), dot, gap)) adjacent++;
            }
            if (!owned) return false;
            if (earlier >= 2 && later >= 2 && dots.size() >= 9 && adjacent >= 7) return true;
            return earlier <= 1 && later >= 6 && adjacent == dots.size() - 1
                    && CrossedDyadOctaveRibbon.recognizedLeadingEight(
                            plane, stride, plane.length / stride, dots.get(0).left, row, gap, words) >= 0;
        }

        private static boolean adjacentRibbonDots(RawNumeralComponents.Component first,
                RawNumeralComponents.Component next, float gap) {
            float pitch = (next.left + next.right - first.left - first.right) * .5f;
            return pitch >= .37f * gap && pitch <= .88f * gap;
        }

        private boolean hasBracketTick(
                boolean[] staff, float start, float end, float gap,
                int y, float middle, float slope, int left, int right) {
            int reach = Math.round(gap * .77f), minimum = Math.round(gap * .49f);
            for (int x = left; x <= right; x++) {
                if (Math.abs(x - start) > gap * .45f && Math.abs(x - end) > gap * .45f) continue;
                int center = y + Math.round(slope * (x - middle));
                if (!dark(x, center, staff)) continue;
                int first = center, last = center;
                while (first > Math.max(top, center - reach) && dark(x, first - 1, staff)) first--;
                while (last < Math.min(top + height - 1, center + reach)
                        && dark(x, last + 1, staff)) last++;
                if (last - first + 1 >= minimum) return true;
            }
            return false;
        }

        private boolean arm(boolean[] staff, int y, float center, float slope, int left, int right) {
            int ink = 0;
            for (int x = left; x <= right; x++)
                if (dark(x, y + Math.round(slope * (x - center)), staff)) ink++;
            return ink >= .63f * (right - left + 1);
        }
    }
}
