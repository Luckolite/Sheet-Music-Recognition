// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Recognizes accidentals mistaken for dividers between continuously beamed chords. */
final class BeamedFlatBarline {
    private BeamedFlatBarline() {}

    static List<MeasureRegion> join(
            List<MeasureRegion> measures, byte[] labels, byte[] gray, int width, int height) {
        if (gray == null
                || labels == null
                || width < 1
                || height < 1
                || gray.length != (long) width * height
                || labels.length != gray.length) return measures;
        List<MeasureRegion> result = null;
        for (int i = 0; i < measures.size(); i++) {
            var left = measures.get(i);
            if (i + 1 < measures.size()
                    && joined(labels, gray, width, height, left, measures.get(i + 1))) {
                var right = measures.get(i + 1);
                if (result == null) result = new ArrayList<>(measures.subList(0, i));
                result.add(
                        new MeasureRegion(
                                left.left(),
                                right.right(),
                                Math.min(left.top(), right.top()),
                                Math.max(left.bottom(), right.bottom())));
                i++;
            } else if (result != null) result.add(left);
        }
        return result == null ? measures : List.copyOf(result);
    }

    /**
     * A tall ledger-note region can make the measure bounding box much taller than its five printed
     * staff rules. Rejoin its opening or closing octave only when a flat and two continuous
     * downstem beams own the ink at the proposed separator.
     */
    static List<MeasureRegion> joinLedgerOctaveFragments(
            List<MeasureRegion> measures,
            byte[] labels,
            byte[] gray,
            byte[] raw,
            int width,
            int height) {
        if (width < 1
                || height < 1
                || labels == null
                || gray == null
                || raw == null
                || gray.length != (long) width * height
                || labels.length != gray.length
                || raw.length != gray.length) return measures;
        List<RawStaffLineDetector.StaffLines> staffs = null;
        List<MeasureRegion> joined = null;
        for (int i = 0; i + 1 < measures.size(); i++) {
            var left = measures.get(i);
            var right = measures.get(i + 1);
            float leftWidth = (left.right() - left.left()) * width;
            float rightWidth = (right.right() - right.left()) * width;
            boolean atOpening = leftWidth < rightWidth;
            float shortWidth = Math.min(leftWidth, rightWidth);
            float longWidth = Math.max(leftWidth, rightWidth);
            float cutWidth = (right.left() - left.right()) * width;
            if (left.cadenceQuarterBeats() != 0
                    || right.cadenceQuarterBeats() != 0
                    || shortWidth < 20
                    || shortWidth > longWidth * .19f
                    || cutWidth <= 0
                    || cutWidth > shortWidth * .55f
                    || Math.abs(left.top() - right.top()) * height > cutWidth * .3f
                    || Math.abs(left.bottom() - right.bottom()) * height > cutWidth * .3f) continue;
            if (staffs == null) staffs = RawStaffLineDetector.detect(raw, width, height);
            var staff = ledgerStaff(staffs, left, shortWidth, longWidth, cutWidth, height);
            if (staff == null) continue;
            var bar =
                    new MeasureRegion(
                            left.left(),
                            right.right(),
                            Math.min(left.top(), right.top()),
                            Math.max(left.bottom(), right.bottom()));
            boolean confirmed =
                    undivided(gray, raw, width, height, left, right, staff)
                            && (atOpening
                                    ? provesLedgerOctaveOpening(
                                            labels, gray, raw, width, height, left, right, bar,
                                            staff)
                                    : i + 2 < measures.size()
                                            && provesLedgerOctaveEnding(
                                                    labels,
                                                    gray,
                                                    raw,
                                                    width,
                                                    height,
                                                    left,
                                                    right,
                                                    measures.get(i + 2),
                                                    bar,
                                                    staff));
            if (!confirmed) continue;
            if (joined == null) joined = new ArrayList<>(measures);
            int edit = i - (measures.size() - joined.size());
            joined.set(edit, bar);
            joined.remove(edit + 1);
            i++;
        }
        return joined == null ? measures : List.copyOf(joined);
    }

    private static RawStaffLineDetector.StaffLines ledgerStaff(
            List<RawStaffLineDetector.StaffLines> candidates,
            MeasureRegion opening,
            float shortWidth,
            float longWidth,
            float seamWidth,
            int height) {
        RawStaffLineDetector.StaffLines staff = null;
        for (var candidate : candidates) {
            float gap = candidate.gap();
            float padding = opening.bottom() * height - candidate.bottom();
            if (gap < 10
                    || gap > 26
                    || padding < 1.65f * gap
                    || padding > 2.55f * gap
                    || candidate.top() - opening.top() * height < 5.5f * gap
                    || shortWidth < 2.3f * gap
                    || shortWidth > 4.2f * gap
                    || longWidth < 21 * gap
                    || longWidth > 33 * gap
                    || seamWidth < .75f * gap
                    || seamWidth > 1.3f * gap) continue;
            if (staff != null) return null;
            staff = candidate;
        }
        return staff;
    }

    private static boolean undivided(
            byte[] gray,
            byte[] raw,
            int width,
            int height,
            MeasureRegion left,
            MeasureRegion right,
            RawStaffLineDetector.StaffLines staff) {
        float gap = staff.gap();
        int from = (int) Math.ceil(left.right() * width);
        int to = (int) Math.floor(right.left() * width);
        if (from < gap * 4.2f
                || to + gap * 4.2f >= width
                || staff.top() < 0
                || staff.bottom() >= height) return false;
        for (int column = from; column <= to; column++) {
            if (seamRule(gray, width, height, column, staff, gap)
                    || seamRule(raw, width, height, column, staff, gap)) return false;
        }
        return true;
    }

    private static boolean provesLedgerOctaveOpening(
            byte[] labels,
            byte[] gray,
            byte[] raw,
            int width,
            int height,
            MeasureRegion opening,
            MeasureRegion run,
            MeasureRegion bar,
            RawStaffLineDetector.StaffLines staff) {
        float gap = staff.gap();
        int from = (int) Math.ceil(opening.right() * width);
        int to = (int) Math.floor(run.left() * width);
        var analysis = OmrScoreInterpreter.analyze(labels, gray, width, height, List.of(bar), raw);
        if (!analysis.rests().isEmpty() || analysis.notes().size() != 24) return false;

        var notes =
                analysis.notes().stream()
                        .sorted(
                                java.util.Comparator.comparingDouble(
                                        ScoreNoteEvent::positionInMeasure))
                        .toList();
        var columns = new ArrayList<Dyad>(12);
        for (int i = 0; i < notes.size(); i += 2) {
            var a = notes.get(i);
            var b = notes.get(i + 1);
            float x1 = (bar.left() + (bar.right() - bar.left()) * a.positionInMeasure()) * width;
            float x2 = (bar.left() + (bar.right() - bar.left()) * b.positionInMeasure()) * width;
            if (!plainDownstemSixteenth(a)
                    || !plainDownstemSixteenth(b)
                    || x2 - x1 > .28f * gap
                    || Math.abs(a.staffStep() - b.staffStep()) != 7) return false;
            float y1 = a.pageY() * height, y2 = b.pageY() * height;
            columns.add(new Dyad((x1 + x2) * .5f, Math.min(y1, y2), Math.max(y1, y2)));
        }
        var first = columns.get(0);
        var second = columns.get(1);
        var third = columns.get(2);
        if (first.x() >= from
                || second.x() <= to
                || first.upper() > staff.top() - gap * 1.5f
                || first.lower() <= staff.top()
                || second.upper() > staff.top() - gap * 1.5f
                || third.x() - first.x() > 5 * gap) return false;

        var left = sharedDownstem(gray, width, height, first, gap);
        var right = sharedDownstem(gray, width, height, third, gap);
        var sourceLeft = sharedDownstem(raw, width, height, first, gap);
        var sourceRight = sharedDownstem(raw, width, height, third, gap);
        var secondStem = sharedDownstem(gray, width, height, second, gap);
        var sourceSecondStem = sharedDownstem(raw, width, height, second, gap);
        if (left == null
                || right == null
                || sourceLeft == null
                || sourceRight == null
                || secondStem == null
                || sourceSecondStem == null
                || Math.abs(left.x() - sourceLeft.x()) > 1
                || Math.abs(right.x() - sourceRight.x()) > 1) return false;

        for (int x = from; x <= to; x++) {
            if (hasAccidentalSpine(labels, width, height, x, staff.top(), staff.bottom(), gap)
                    && flatBeforeShaft(gray, width, height, x, secondStem, second.lower(), staff)
                    && flatBeforeShaft(
                            raw, width, height, x, sourceSecondStem, second.lower(), staff)
                    && continuesAcross(gray, width, height, x, left, right, gap, -1)
                    && continuesAcross(raw, width, height, x, sourceLeft, sourceRight, gap, -1))
                return true;
        }
        return false;
    }

    private static boolean provesLedgerOctaveEnding(
            byte[] labels,
            byte[] gray,
            byte[] raw,
            int width,
            int height,
            MeasureRegion run,
            MeasureRegion ending,
            MeasureRegion following,
            MeasureRegion bar,
            RawStaffLineDetector.StaffLines staff) {
        float gap = staff.gap();
        if (Math.abs((following.top() - ending.top()) * height) > gap * .35f
                || Math.abs((following.bottom() - ending.bottom()) * height) > gap * .35f)
            return false;
        int closeFrom = (int) Math.ceil(ending.right() * width);
        int closeTo = (int) Math.floor(following.left() * width);
        if (closeFrom < 0 || closeTo >= width || closeTo - closeFrom > 1.4f * gap) return false;
        boolean closed = false;
        for (int x = closeFrom; x <= closeTo; x++) {
            if (seamRule(gray, width, height, x, staff, gap)
                    && seamRule(raw, width, height, x, staff, gap)) {
                closed = true;
                break;
            }
        }
        if (!closed) return false;
        var analysis = OmrScoreInterpreter.analyze(labels, gray, width, height, List.of(bar), raw);
        if (!analysis.rests().isEmpty() || analysis.notes().size() != 24) return false;
        var notes =
                analysis.notes().stream()
                        .filter(BeamedFlatBarline::isDownstemSixteenth)
                        .sorted(
                                java.util.Comparator.comparingDouble(
                                        ScoreNoteEvent::positionInMeasure))
                        .toList();
        // The terminal ink can prove the separator even if another part of the bar has a weak head.
        if (notes.size() < 23) return false;
        var group = new Dyad[4];
        for (int i = 0; i < group.length; i++) {
            var a = notes.get(notes.size() - 8 + 2 * i);
            var b = notes.get(notes.size() - 7 + 2 * i);
            float x1 = (bar.left() + (bar.right() - bar.left()) * a.positionInMeasure()) * width;
            float x2 = (bar.left() + (bar.right() - bar.left()) * b.positionInMeasure()) * width;
            if (Math.abs(a.staffStep() - b.staffStep()) != 7 || x2 - x1 > .28f * gap) return false;
            float y1 = a.pageY() * height, y2 = b.pageY() * height;
            group[i] = new Dyad((x1 + x2) * .5f, Math.min(y1, y2), Math.max(y1, y2));
            if (i > 0) {
                float separation = group[i].x() - group[i - 1].x();
                if (separation < gap * 1.3f || separation > gap * 3.5f) return false;
            }
        }
        int from = (int) Math.ceil(run.right() * width);
        int to = (int) Math.floor(ending.left() * width);
        if (group[2].x() >= from
                || group[3].x() <= to
                || group[3].upper() > staff.top() - 1.5f * gap
                || group[3].lower() <= staff.top()) return false;

        var stem = terminalShaftWithRails(gray, width, height, group, gap);
        var sourceStem = terminalShaftWithRails(raw, width, height, group, gap);
        if (stem == null || sourceStem == null || Math.abs(stem.x() - sourceStem.x()) > 1)
            return false;
        int flatTop = Math.round(group[3].lower() - 1.65f * gap);
        int flatBottom = Math.round(group[3].lower() + .8f * gap);
        for (int x = from; x <= to; x++) {
            if (hasAccidentalSpine(labels, width, height, x, flatTop, flatBottom, gap)
                    && flatBeforeShaft(gray, width, height, x, stem, group[3].lower(), staff)
                    && flatBeforeShaft(raw, width, height, x, sourceStem, group[3].lower(), staff))
                return true;
        }
        return false;
    }

    static PairedTremoloInk.Stem terminalShaftWithRails(
            byte[] gray, int width, int height, Dyad[] group, float gap) {
        var budget = new PrintedBeamShafts.Budget(120_000);
        PrintedBeamShafts.Shaft previous = null;
        PairedTremoloInk.Stem terminal = null;
        for (var pair : group) {
            terminal = sharedDownstem(gray, width, height, pair, gap);
            if (terminal == null) return null;
            var shaft =
                    PrintedBeamShafts.at(gray, width, height, pair.x(), pair.lower(), gap, budget);
            if (shaft.size() != 1) return null;
            var owned = shaft.get(0);
            if (owned.direction() != 1
                    || terminal.x() < owned.left() - 1
                    || terminal.x() > owned.right() + 1
                    || Math.abs(owned.endY() - terminal.endY()) > 1
                    || previous != null
                            && !WrappedEighthTriplets.matchesBeamRails(
                                    gray, width, height, previous, owned, gap, 2)) return null;
            previous = owned;
        }
        return terminal;
    }

    static boolean flatBeforeShaft(
            byte[] gray,
            int width,
            int height,
            int x,
            PairedTremoloInk.Stem stem,
            float headY,
            RawStaffLineDetector.StaffLines staff) {
        float gap = staff.gap();
        int inner = x + Math.round(.46f * gap);
        int outer = x + Math.round(.85f * gap);
        if (outer >= stem.x() - 1 || headY - 1.50f * gap < 0 || headY + .73f * gap >= height - 1)
            return false;
        // Exclude the measured staff rows when testing the flat.
        return interlineInk(gray, width, staff.rows(), x, x, headY, gap, -1.50f, .72f) >= .85f
                && interlineInk(gray, width, staff.rows(), inner, outer, headY, gap, -1.46f, -.81f)
                        <= .12f
                && interlineInk(gray, width, staff.rows(), inner, outer, headY, gap, -.53f, .19f)
                        >= .47f
                && interlineInk(
                                gray,
                                width,
                                staff.rows(),
                                x + Math.round(.22f * gap),
                                x + Math.round(.44f * gap),
                                headY,
                                gap,
                                -.30f,
                                .35f)
                        <= .76f
                && interlineInk(gray, width, staff.rows(), inner, outer, headY, gap, .48f, .73f)
                        <= .18f;
    }

    private static float interlineInk(
            byte[] gray,
            int width,
            int[] rules,
            int left,
            int right,
            float headY,
            float gap,
            float from,
            float to) {
        int ink = 0, samples = 0;
        scan:
        for (int y = Math.round(headY + from * gap); y <= Math.round(headY + to * gap); y++) {
            for (int rule : rules) if (Math.abs(rule - y) <= 1) continue scan;
            samples += right - left + 1;
            for (int x = left; x <= right; x++) if ((gray[y * width + x] & 255) < 155) ink++;
        }
        return samples == 0 ? -1 : (float) ink / samples;
    }

    static boolean seamRule(
            byte[] gray,
            int width,
            int height,
            int x,
            RawStaffLineDetector.StaffLines staff,
            float gap) {
        return DottedDyadMeasureSeparator.fullInk(gray, width, x, staff.top(), gap)
                && !DottedDyadMeasureSeparator.continuesBeyondStaff(
                        gray, width, height, x, staff.top(), staff.bottom(), gap);
    }

    private static boolean plainDownstemSixteenth(ScoreNoteEvent note) {
        return !note.tiedFromPrevious() && isDownstemSixteenth(note);
    }

    private static boolean isDownstemSixteenth(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.staffIndex() == 0
                && note.staffCount() == 1
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && note.stemDirection() == -1
                && note.beamCount() == 2
                && note.tupletDivisor() == 1
                && note.augmentationDots() == 0
                && !note.crossStaffBeam()
                && note.boundaryTies() == 0
                && note.leadingRestBeats() == 0
                && note.followingRestBeats() == 0
                && (note.articulations() & NoteOrnament.GRACE) == 0;
    }



    private static boolean joined(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            MeasureRegion left,
            MeasureRegion right) {
        float gap = (left.bottom() - left.top()) * height / 8.2f;
        float seam = (right.left() - left.right()) * width;
        if (left.cadenceQuarterBeats() != 0
                || right.cadenceQuarterBeats() != 0
                || gap < 8
                || gap > 32
                || seam <= 0
                || seam > gap * 1.40f
                || Math.abs(left.top() - right.top()) * height > gap * .35f
                || Math.abs(left.bottom() - right.bottom()) * height > gap * .35f) return false;
        int column = Math.round((left.right() + right.left()) * width * .5f);
        float hint = left.bottom() * height - gap * 2.1f;
        if (!hasAccidentalSpine(
                labels, width, height, column, Math.round(hint - gap * 4), Math.round(hint), gap))
            return false;
        float[] rules =
                PrintedDirectionStaff.printedRules(
                        gray, width, height, column + gap * 2.05f, hint, gap);
        if (rules == null)
            rules =
                    PrintedDirectionStaff.printedRules(
                            gray, width, height, column - gap * 2.05f, hint, gap);
        if (rules != null
                && (owns(
                                labels,
                                gray,
                                width,
                                height,
                                column,
                                Math.round(rules[0] - 4 * rules[1]),
                                Math.round(rules[0]),
                                rules[1])
                        || ownsUpstemSharp(labels, gray, width, height, column, rules)))
            return true;
        if (rules == null && column + gap * 4.8f < right.right() * width - gap) {
            var sharpRules =
                    PrintedDirectionStaff.printedRules(
                            gray, width, height, column + gap * 4.8f, hint, gap);
            if (sharpRules != null
                    && ownsUpstemSharp(labels, gray, width, height, column, sharpRules))
                return true;
        }
        // Ledger-supported chords can cover the nearest five-rule windows.
        if (rules == null) {
            float sample = column + gap * 6f;
            if (sample < right.right() * width - gap)
                rules = PrintedDirectionStaff.printedRules(gray, width, height, sample, hint, gap);
        }
        return rules != null
                && ownsDownstemPair(
                        labels,
                        gray,
                        width,
                        height,
                        column,
                        Math.round(rules[0] - 4 * rules[1]),
                        Math.round(rules[0]),
                        rules[1]);
    }

    private static boolean hasAccidentalSpine(
            byte[] labels, int width, int height, int column, int top, int bottom, float gap) {
        if (column < 0 || column >= width || top < 0 || bottom >= height) return false;
        int marked = 0;
        for (int y = top; y <= bottom; y++)
            if (labels[y * width + column] == OmrMeasurePostProcessor.CLEF_OR_KEY) marked++;
        return marked >= gap * .85f;
    }

    static boolean owns(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            int column,
            int topRule,
            int bottomRule,
            float gap) {
        int reach = Math.round(gap * 4.2f);
        int top = topRule - Math.round(gap), bottom = bottomRule + Math.round(gap * 1.2f);
        if (gap < 8
                || gap > 32
                || column - reach < 0
                || column + reach >= width
                || top < 0
                || bottom >= height) return false;
        if (!hasAccidentalSpine(labels, width, height, column, topRule, bottomRule, gap))
            return false;
        if (solidDivider(gray, width, column, topRule, gap)) return false;
        var leftHead = nearestDyad(labels, width, column, top, bottom, gap, -1);
        var rightHead = nearestDyad(labels, width, column, top, bottom, gap, 1);
        if (leftHead == null || rightHead == null) return false;
        var left =
                PairedTremoloInk.stem(gray, width, height, leftHead.x(), leftHead.lower(), gap, 1);
        var right =
                PairedTremoloInk.stem(
                        gray, width, height, rightHead.x(), rightHead.lower(), gap, 1);
        return left != null
                && right != null
                && continuesAcross(gray, width, height, column, left, right, gap)
                && flatAt(gray, width, height, column, rightHead.upper(), gap);
    }

    private static boolean ownsUpstemSharp(
            byte[] labels, byte[] gray, int width, int height, int column, float[] rules) {
        float gap = rules[1];
        int topRule = Math.round(rules[0] - 4 * gap), bottomRule = Math.round(rules[0]);
        int top = topRule - Math.round(gap), bottom = bottomRule + Math.round(gap * 1.2f);
        int reach = Math.round(gap * 4.2f);
        if (gap < 8
                || gap > 32
                || column - reach < 0
                || column + reach >= width
                || top < 0
                || bottom >= height
                || !hasAccidentalSpine(labels, width, height, column, topRule, bottomRule, gap)
                || solidDivider(gray, width, column, topRule, gap)) return false;
        var leftHead = nearestDyad(labels, width, column, top, bottom, gap, -1);
        var rightHead = nearestDyad(labels, width, column, top, bottom, gap, 1);
        if (leftHead == null || rightHead == null) return false;
        var left =
                PairedTremoloInk.stem(gray, width, height, leftHead.x(), leftHead.lower(), gap, 1);
        var right =
                PairedTremoloInk.stem(
                        gray, width, height, rightHead.x(), rightHead.lower(), gap, 1);
        return left != null
                && right != null
                && continuesAcross(gray, width, height, column, left, right, gap)
                && OmrScoreInterpreter.printedSharpBeforeDyad(
                        gray, width, height, column, rightHead.x(), rightHead.upper(), gap);
    }

    private static boolean ownsDownstemPair(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            int column,
            int topRule,
            int bottomRule,
            float gap) {
        int top = topRule - Math.round(gap * 4), bottom = bottomRule + Math.round(gap * .6f);
        int reach = Math.round(gap * 4.2f);
        if (column - reach < 0
                || column + reach >= width
                || top < 0
                || bottom >= height
                || !hasAccidentalSpine(labels, width, height, column, topRule, bottomRule, gap)
                || solidDivider(gray, width, column, topRule, gap)) return false;
        var prior = nearestDyad(labels, width, column, top, bottom, gap, -1, 3.85f);
        var next = nearestDyad(labels, width, column, top, bottom, gap, 1, 3.85f);
        if (prior == null || next == null) return false;
        var left = sharedDownstem(gray, width, height, prior, gap);
        var right = sharedDownstem(gray, width, height, next, gap);
        return left != null
                && right != null
                && continuesAcross(gray, width, height, column, left, right, gap, -1);
    }

    private static PairedTremoloInk.Stem sharedDownstem(
            byte[] gray, int width, int height, Dyad chord, float gap) {
        var lower = PairedTremoloInk.stem(gray, width, height, chord.x(), chord.lower(), gap, -1);
        var upper = PairedTremoloInk.stem(gray, width, height, chord.x(), chord.upper(), gap, -1);
        return lower != null
                        && upper != null
                        && Math.abs(lower.x() - upper.x()) <= gap * .15f
                        && Math.abs(lower.endY() - upper.endY()) <= gap * .14f
                ? lower
                : null;
    }

    private static boolean solidDivider(byte[] gray, int width, int column, int top, float gap) {
        int radius = Math.max(1, Math.round(gap * .18f));
        for (int x = column - radius; x <= column + radius; x++) {
            int supported = 0;
            for (int space = 0; space < 4; space++) {
                int first = Math.round(top + (space + .2f) * gap);
                int last = Math.round(top + (space + .8f) * gap);
                int ink = 0;
                for (int y = first; y <= last; y++) if ((gray[y * width + x] & 255) < 155) ink++;
                if (ink >= (last - first + 1) * .90f) supported++;
            }
            if (supported == 4) return true;
        }
        return false;
    }

    record Dyad(float x, float upper, float lower) {}

    private static Dyad nearestDyad(
            byte[] labels, int width, int column, int top, int bottom, float gap, int side) {
        return nearestDyad(labels, width, column, top, bottom, gap, side, 3.25f);
    }

    private static Dyad nearestDyad(
            byte[] labels,
            int width,
            int column,
            int top,
            int bottom,
            float gap,
            int side,
            float maximumSpan) {
        int first = -1, last = -1, upper = bottom, lower = top;
        for (int distance = Math.round(gap * .65f);
                distance <= Math.round(gap * 4.2f);
                distance++) {
            int x = column + side * distance, count = 0, y0 = bottom, y1 = top;
            for (int y = top; y <= bottom; y++)
                if (labels[y * width + x] == OmrMeasurePostProcessor.NOTEHEAD) {
                    count++;
                    y0 = Math.min(y0, y);
                    y1 = Math.max(y1, y);
                }
            if (count < gap * .62f) {
                if (first >= 0) break;
                continue;
            }
            if (first < 0) first = x;
            last = x;
            upper = Math.min(upper, y0);
            lower = Math.max(lower, y1);
        }
        int span = Math.abs(last - first) + 1;
        int vertical = lower - upper + 1;
        if (span < gap * .54f
                || span > gap * 1.65f
                || vertical < gap * 1.65f
                || vertical > gap * maximumSpan) return null;
        return new Dyad((first + last) * .5f, upper + gap * .55f, lower - gap * .55f);
    }

    private static boolean continuesAcross(
            byte[] gray,
            int width,
            int height,
            int column,
            PairedTremoloInk.Stem left,
            PairedTremoloInk.Stem right,
            float gap) {
        return continuesAcross(gray, width, height, column, left, right, gap, 1);
    }

    private static boolean continuesAcross(
            byte[] gray,
            int width,
            int height,
            int column,
            PairedTremoloInk.Stem left,
            PairedTremoloInk.Stem right,
            float gap,
            int direction) {
        int inset = Math.round(gap * .28f);
        int start = left.x() + inset, end = right.x() - inset;
        if (column - start < gap * .52f || end - column < gap * .52f) return false;
        float slope = (right.endY() - left.endY()) / (float) (right.x() - left.x());
        if (Math.abs(slope) > .26f) return false;
        int radius = Math.max(1, Math.round(gap * .13f)), samples = end - start + 1;
        for (int primary = Math.round(gap * .19f); primary <= Math.round(gap * .51f); primary++)
            for (int spacing = Math.round(gap * .53f);
                    spacing <= Math.round(gap * .96f);
                    spacing++) {
                int first = 0, second = 0, seam = 0;
                for (int x = start; x <= end; x++) {
                    int axis =
                            Math.round(left.endY() + (x - left.x()) * slope) + direction * primary;
                    int companion = axis + direction * spacing;
                    if (Math.min(axis, companion) - radius < 0
                            || Math.max(axis, companion) + radius >= height) return false;
                    boolean outer = rail(gray, width, x, axis, radius);
                    boolean inner = rail(gray, width, x, companion, radius);
                    if (x == column && (!outer || !inner)) break;
                    if (outer) first++;
                    if (inner) second++;
                    if ((gray[(axis + direction * (spacing / 2)) * width + x] & 255) >= 185) seam++;
                }
                if (first >= samples * .94f && second >= samples * .94f && seam >= samples * .28f)
                    return true;
            }
        return false;
    }

    static boolean closedDownstemPair(
            byte[] gray, int width, int height, int[] first, int[] last, float gap) {
        if (first[2] != 1 || last[2] != 1 || last[0] <= first[0]) return false;
        var left = new PairedTremoloInk.Stem(first[0], 0, first[1]);
        var right = new PairedTremoloInk.Stem(last[0], 0, last[1]);
        return continuesAcross(gray, width, height, (first[0] + last[0]) / 2, left, right, gap, -1);
    }

    private static boolean rail(byte[] gray, int width, int x, int y, int radius) {
        for (int dy = -radius; dy <= radius; dy++)
            if ((gray[(y + dy) * width + x] & 255) >= 165) return false;
        return true;
    }

    private static boolean flatAt(
            byte[] gray, int width, int height, int column, float headY, float gap) {
        for (int left = column - Math.round(gap * .35f); left <= column; left++)
            for (int right = left + Math.round(gap * .58f); right <= left + gap * 1.2f; right++) {
                if (column > left + (right - left) * .42f) continue;
                for (int top = Math.round(headY - gap * 2.35f); top <= headY - gap * 1.5f; top += 2)
                    for (int bottom = Math.round(headY + gap * .50f);
                            bottom <= headY + gap * .89f;
                            bottom += 2)
                        if (PrintedFlatGlyph.matches(
                                gray, width, height, left, top, right, bottom, gap)) return true;
            }
        return false;
    }
}
