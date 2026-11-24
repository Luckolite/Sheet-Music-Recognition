// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Recover omitted upper octave heads and remove beam fragments in a closed sixteenth-note run. */
final class ClosedOctaveRailRun {
    private ClosedOctaveRailRun() {}

    private record Head(int owner, ScoreNoteEvent note, float x, float y) {}
    private record Column(Head upper, Head lower) {}

    static ScorePageInterpretation recover(ScorePageInterpretation score, ScoreMeterChange inherited,
            byte[] labels, byte[] normalized, byte[] raw, int w, int h, int page) {
        if (labels == null || normalized == null || raw == null || labels.length != (long) w * h
                || normalized.length != labels.length || raw.length != labels.length) return score;
        score = recoverOpeningPair(score, inherited, labels, normalized, raw, w, h, page);
        var notes = score.notes();
        var bars = score.measures();
        var owners = new ArrayList<List<Integer>>(bars.size());
        for (int i = 0; i < bars.size(); i++) owners.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) owners.get(notes.get(i).measureIndex()).add(i);
        var meter = inherited;
        int nextMeter = 0;
        for (int bar = 1; bar < bars.size(); bar++) {
            while (nextMeter < score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex() <= bar)
                meter = score.meterChanges().get(nextMeter++);
            if (meter == null || meter.numerator() != 4 || meter.denominator() != 4
                    || owners.get(bar).size() != 32) continue;
            score = recoverBar(score, labels, normalized, raw, w, h, page, bar, owners.get(bar));
        }
        score = recoverThreeFour(score, inherited, labels, normalized, raw, w, h, page);
        return removeClosedRailFragments(score, inherited, labels, normalized, raw, w, h);
    }

    /** Recover a whole octave attack when both retained head components share a faded double-beam shaft. */
    private static ScorePageInterpretation recoverOpeningPair(ScorePageInterpretation score, ScoreMeterChange inherited,
            byte[] labels, byte[] normalized, byte[] raw, int w, int h, int page) {
        if (score.measures().isEmpty() || score.openingPickup()) return score;
        var meter = inherited;
        if (!score.meterChanges().isEmpty() && score.meterChanges().get(0).measureIndex() == 0)
            meter = score.meterChanges().get(0);
        var bar = score.measures().get(0);
        if (meter == null || meter.numerator() != 4 || meter.denominator() != 4 || bar.cadenceQuarterBeats() != 0
                || score.rests().stream().anyMatch(r -> r.measureIndex() == 0)) return score;
        float width = (bar.right() - bar.left()) * w;
        float gap = (bar.bottom() - bar.top()) * h / 8.2f;
        if (gap < 12 || gap > 24 || width < 36 * gap || width > 47 * gap) return score;
        var heads = new ArrayList<Head>();
        for (int i = 0; i < score.notes().size(); i++) {
            var note = score.notes().get(i);
            if (note.measureIndex() != 0) continue;
            if (!ordinary(note) || note.stemDirection() > 0
                    || !sixteenth(note) && !(note.beamCount() == 0 && note.unbeamedDurationBeats() == 1)
                    || score.beamSource().noteOrigins().get(i).isPresent()) return score;
            heads.add(new Head(i, note, bar.left() * w + width * note.positionInMeasure(), note.pageY() * h));
        }
        if (heads.size() != 30) return score;
        heads.sort(Comparator.comparingDouble(Head::x));
        var columns = new ArrayList<Column>(16);
        int hole = -1;
        Column weak = null;
        for (int i = 0; i < 30; i += 2) {
            var a = heads.get(i);
            var b = heads.get(i + 1);
            var upper = a.y < b.y ? a : b;
            var lower = upper == a ? b : a;
            if (b.x - a.x > .24f * gap || upper.note.staffStep() - lower.note.staffStep() != 7
                    || Math.abs(lower.y - upper.y - 3.5f * gap) > .29f * gap) return score;
            if (i > 0) {
                float dx = (lower.x - columns.get(columns.size() - 1).lower.x) / gap;
                if (dx > 4.25f && dx < 5.3f && hole < 0 && i <= 6) hole = i / 2;
                else if (dx < 1.85f || dx > 2.85f) return score;
            }
            if (!sixteenth(lower.note)) return score;
            var column = new Column(upper, lower);
            if (!sixteenth(upper.note)) {
                if (weak != null || upper.note.stemDirection() != -1 || lower.note.stemDirection() != 0)
                    return score;
                weak = column;
            } else if (upper.note.stemDirection() != -1 || lower.note.stemDirection() != -1) return score;
            columns.add(column);
        }
        if (hole < 1 || hole > 2) return score;
        var before = columns.get(hole - 1);
        var after = columns.get(hole);
        if (Math.abs(before.lower.note.staffStep() - after.lower.note.staffStep()) != 2) return score;
        var upper = openingHead(labels, w, h, bar, before.upper, after.upper, gap);
        var lower = openingHead(labels, w, h, bar, before.lower, after.lower, gap);
        if (upper == null || lower == null || Math.abs(upper.x - lower.x) > .16f * gap
                || Math.abs(lower.y - upper.y - 3.5f * gap) > .23f * gap) return score;
        columns.add(hole, new Column(upper, lower));
        var opening = columns.subList(0, 4);
        float lastX = columns.get(columns.size() - 1).lower.x;
        if (!openingPairInk(raw, w, h, bar, opening, null, lastX, gap)
                || !openingPairInk(normalized, w, h, bar, opening, null, lastX, gap)) return score;
        var projected = new ArrayList<>(opening);
        if (weak != null) {
            int group = columns.indexOf(weak) / 4 * 4;
            if (group == 0) return score;
            var peer = columns.subList(group, group + 4);
            if (!openingPairInk(raw, w, h, bar, peer, weak, lastX, gap)
                    || !openingPairInk(normalized, w, h, bar, peer, weak, lastX, gap)) return score;
            projected.addAll(peer);
        }

        var original = score.notes();
        var repaired = new ArrayList<ScoreNoteEvent>(original.size() + 2);
        var remap = new ArrayList<Integer>(original.size() + 2);
        int insert = Math.min(after.upper.owner, after.lower.owner);
        for (int i = 0; i < original.size(); i++) {
            if (i == insert) {
                repaired.add(upper.note);
                repaired.add(lower.note);
                remap.add(-1);
                remap.add(-1);
            }
            var retained = original.get(i);
            if (weak != null && (i == weak.upper.owner || i == weak.lower.owner)) {
                retained = ClosingTripletRhythm.beamedValue(retained, 2).withStemDirection(-1);
            }
            repaired.add(retained);
            remap.add(i);
        }
        var result = score.withNotes(repaired, remap);
        if (page < 0) return result;
        var beam = new ArrayList<ClosedSixteenthDyads.Head>(2 * projected.size());
        for (var column : projected) for (var head : List.of(column.upper, column.lower)) {
            int owner = head.owner < 0 ? (head == upper ? insert : insert + 1)
                    : head.owner + (head.owner >= insert ? 2 : 0);
            beam.add(new ClosedSixteenthDyads.Head(owner, repaired.get(owner), head.x, head.y));
        }
        return result.withBeamSource(ClosedSixteenthDyads.projectDoubleRails(result.beamSource(), page, beam));
    }

    private static Head openingHead(byte[] labels, int w, int h, MeasureRegion bar, Head before, Head after, float gap) {
        float center = (before.x + after.x) * .5f, phase = (before.y + after.y) * .5f;
        int left = Math.round(center - .85f * gap), right = Math.round(center + .85f * gap);
        int top = Math.round(phase - .81f * gap), bottom = Math.round(phase + .81f * gap);
        if (left < 0 || right >= w || top < 0 || bottom >= h) return null;
        int count = 0, sumX = 0, sumY = 0;
        for (int y = top; y <= bottom; y++) for (int x = left; x <= right; x++) {
            if (labels[y * w + x] != OmrMeasurePostProcessor.NOTEHEAD) continue;
            if (x == left || x == right || y == top || y == bottom) return null;
            count++;
            sumX += x;
            sumY += y;
        }
        if (count < 1.03f * gap * gap || count > 1.65f * gap * gap) return null;
        float x = sumX / (float) count, y = sumY / (float) count;
        if (Math.abs(x - center) > .22f * gap || Math.abs(y - phase) > .24f * gap) return null;
        var note = new ScoreNoteEvent(0, (x / w - bar.left()) / (bar.right() - bar.left()),
                (before.note.staffStep() + after.note.staffStep()) / 2, 0, 1, y / h,
                false, 0, 2, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0, 1, 0, 0,
                ScoreNoteEvent.CLEF_TREBLE).withStemDirection(-1);
        return new Head(-1, note, x, y);
    }

    private static boolean openingPairInk(byte[] ink, int w, int h, MeasureRegion bar,
            List<Column> columns, Column weak, float lastX, float gap) {
        var track = StaffPitchTrack.linear(w, bar.bottom() * h - 2.1f * gap, gap, 0);
        if (!WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(ink, w, h, bar, lastX, track)) return false;
        var budget = new PrintedBeamShafts.Budget(210_000);
        PrintedBeamShafts.Shaft previous = null;
        Column previousColumn = null;
        for (var column : columns) {
            var upper = column.upper;
            var lower = column.lower;
            PrintedBeamShafts.Shaft current;
            if (upper.owner < 0 || column == weak) {
                if (!ArtificialHarmonics.filledQuarterCore(ink, w, h, upper.x, upper.y, gap)
                        || !ArtificialHarmonics.filledQuarterCore(ink, w, h, lower.x, lower.y, gap)) return false;
                current = FadedTripletDownshaft.measure(ink, w, h, lower.x, lower.y, gap);
                if (current == null || !upperFadedAttachment(ink, w, upper, lower, current, gap)) return false;
                if (upper.owner < 0 && (previousColumn == null
                        || !BeamedDoubleFlatCarry.clearAccidentalSpace(
                                ink, w, h, previousColumn.upper.x, upper.x, upper.y, gap)
                        || !BeamedDoubleFlatCarry.clearAccidentalSpace(
                                ink, w, h, previousColumn.lower.x, lower.x, lower.y, gap))) return false;
            } else {
                var a = PrintedBeamShafts.at(ink, w, h, upper.x, upper.y, gap, budget);
                var b = PrintedBeamShafts.at(ink, w, h, lower.x, lower.y, gap, budget);
                if (a.size() != 1 || b.size() != 1 || budget.refused()) return false;
                var high = a.get(0);
                current = b.get(0);
                if (high.direction() != 1 || current.direction() != 1 || high.left() > current.right()
                        || current.left() > high.right() || Math.abs(high.endY() - current.endY()) > .25f * gap)
                    return false;
            }
            if (previous != null && !WrappedEighthTriplets.matchesExactlyTwoBeamRails(
                    ink, w, h, previous, current, gap, track)) return false;
            previous = current;
            previousColumn = column;
        }
        return true;
    }

    private static boolean upperFadedAttachment(byte[] ink, int width, Head upper, Head lower,
            PrintedBeamShafts.Shaft stem, float gap) {
        int top = Math.round(upper.y + .25f * gap), bottom = Math.round(lower.y - .22f * gap), supported = 0;
        for (var col : stem.columns()) {
            int x = col.x();
            float offset = upper.x - x;
            if (offset < .44f * gap || offset > .81f * gap) continue;
            int dark = 0, blank = 0, longestBlank = 0;
            for (int y = top; y <= bottom; y++) {
                if ((ink[y * width + x] & 255) < 213) {
                    dark++;
                    blank = 0;
                } else longestBlank = Math.max(longestBlank, ++blank);
            }
            if (dark >= .80f * (bottom - top + 1) && longestBlank < .68f * gap) supported++;
        }
        return supported >= 2;
    }

    /** Suppress a stemless quarter whose entire observed footprint is owned by a terminal double beam. */
    private static ScorePageInterpretation removeClosedRailFragments(
            ScorePageInterpretation score, ScoreMeterChange inherited, byte[] labels,
            byte[] normalized, byte[] raw, int w, int h) {
        var bars = score.measures();
        var notes = score.notes();
        var byBar = new ArrayList<List<Integer>>(bars.size());
        for (int i = 0; i < bars.size(); i++) byBar.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) byBar.get(notes.get(i).measureIndex()).add(i);
        var removed = new java.util.HashSet<Integer>();
        var meter = inherited;
        int meterIndex = 0;
        for (int i = 0; i + 1 < bars.size(); i++) {
            while (meterIndex < score.meterChanges().size()
                    && score.meterChanges().get(meterIndex).measureIndex() <= i)
                meter = score.meterChanges().get(meterIndex++);
            int bar = i;
            if (meter == null || meter.numerator() != 4 || meter.denominator() != 4
                    || byBar.get(bar).size() != 33 || bars.get(bar).cadenceQuarterBeats() != 0
                    || score.rests().stream().anyMatch(rest -> rest.measureIndex() == bar)) continue;
            var fragment = closedRailFragment(score, bars.get(bar), bars.get(bar + 1),
                    byBar.get(bar), labels, normalized, raw, w, h);
            if (fragment != null) removed.add(fragment.owner);
        }
        if (removed.isEmpty()) return score;
        var retained = new ArrayList<ScoreNoteEvent>(notes.size() - removed.size());
        var owners = new ArrayList<Integer>(retained.size());
        for (int i = 0; i < notes.size(); i++) {
            if (removed.contains(i)) continue;
            retained.add(notes.get(i));
            owners.add(i);
        }
        return score.withNotes(retained, owners);
    }

    private static Head closedRailFragment(ScorePageInterpretation score, MeasureRegion bar,
            MeasureRegion following, List<Integer> owners, byte[] labels, byte[] normalized,
            byte[] raw, int w, int h) {
        float span = (bar.right() - bar.left()) * w;
        float gap = (bar.bottom() - bar.top()) * h / 8.2f;
        if (gap < 11 || gap > 25 || span < gap * 30 || span > gap * 42
                || Math.abs(bar.top() - following.top()) * h > .45f * gap) return null;
        var heads = new ArrayList<Head>(32);
        Head fragment = null;
        for (int owner : owners) {
            var note = score.notes().get(owner);
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.staffIndex() != 0 || note.staffCount() != 1
                    || note.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE
                    || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || note.tupletDivisor() != 1 || note.tupletNormalNotes() != 1
                    || note.augmentationDots() != 0 || note.crossStaffBeam()
                    || note.tiedFromPrevious() || note.boundaryTies() != 0
                    || note.leadingRestBeats() != 0 || note.followingRestBeats() != 0
                    || note.harmonicSemitones() != 0 || note.openStringTouch() != null) return null;
            var head = new Head(owner, note, bar.left() * w + span * note.positionInMeasure(), note.pageY() * h);
            if (note.stemDirection() == -1 && sixteenth(note)) heads.add(head);
            else if (note.stemDirection() == 0 && note.beamCount() == 0 && note.unbeamedDurationBeats() == 1
                    && note.articulations() == 0 && note.octaveShift() == 0
                    && note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                    && !score.beamSource().noteOrigins().get(owner).isPresent() && fragment == null)
                fragment = head;
            else return null;
        }
        if (fragment == null || heads.size() != 32) return null;
        heads.sort(Comparator.comparingDouble(Head::x));
        var ending = new ArrayList<Column>(4);
        for (int i = 0; i < heads.size(); i += 2) {
            var a = heads.get(i);
            var b = heads.get(i + 1);
            var upper = a.y < b.y ? a : b;
            var lower = upper == a ? b : a;
            if (b.x - a.x > gap * .24f || upper.note.staffStep() - lower.note.staffStep() != 7
                    || lower.y - upper.y < gap * 3.12f || lower.y - upper.y > gap * 3.88f
                    || i > 0 && a.x - heads.get(i - 1).x < gap * 1.55f) return null;
            if (i >= 24) ending.add(new Column(upper, lower));
        }
        return terminalRailOwnsFragment(raw, labels, w, h, bar, following, ending, fragment, gap)
                && terminalRailOwnsFragment(normalized, labels, w, h, bar, following, ending, fragment, gap)
                ? fragment : null;
    }

    private static boolean terminalRailOwnsFragment(byte[] ink, byte[] labels, int w, int h,
            MeasureRegion bar, MeasureRegion following, List<Column> columns, Head fragment, float gap) {
        float floor = bar.bottom() * h - gap * 2.1f;
        var staff = new PlayingTechniqueDetector.Staff(floor - 4 * gap, floor, gap, 0, 1);
        if (!ClosedSixteenthPairBeforeHalf.ownedSeparator(
                ink, w, h, staff, (bar.right() + following.left()) * w * .5f)) return false;
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(4);
        var budget = new PrintedBeamShafts.Budget(120_000);
        for (var column : columns) {
            var upper = column.upper;
            var lower = column.lower;
            var a = PrintedBeamShafts.at(ink, w, h, upper.x, upper.y, gap, budget);
            var b = PrintedBeamShafts.at(ink, w, h, lower.x, lower.y, gap, budget);
            if (a.size() != 1 || b.size() != 1 || budget.refused()
                    || !ArtificialHarmonics.filledQuarterCore(ink, w, h, upper.x, upper.y, gap)
                    || !ArtificialHarmonics.filledQuarterCore(ink, w, h, lower.x, lower.y, gap)) return false;
            var top = a.get(0);
            var base = b.get(0);
            if (top.direction() != 1 || base.direction() != 1 || top.left() > base.right()
                    || base.left() > top.right() || Math.abs(top.endY() - base.endY()) > gap * .27f)
                return false;
            if (!shafts.isEmpty()
                    && !WrappedEighthTriplets.matchesBeamRails(ink, w, h, shafts.get(shafts.size() - 1), base, gap, 2))
                return false;
            shafts.add(base);
        }

        // The separate candidate sits just inside the penultimate bridge, behind its real left shaft.
        var left = shafts.get(1);
        var right = shafts.get(2);
        int from = Math.round(fragment.x - .84f * gap), to = Math.round(fragment.x + .84f * gap);
        int top = Math.round(fragment.y - .65f * gap), bottom = Math.round(fragment.y + .65f * gap);
        int ax = (left.left() + left.right()) / 2, bx = (right.left() + right.right()) / 2;
        if (from < 0 || to >= w || top < 0 || bottom >= h || bx <= ax
                || fragment.x - ax < .34f * gap || fragment.x - ax > .78f * gap
                || bx - fragment.x < 1.02f * gap || bx - fragment.x > 1.65f * gap
                || !PrintedBeamShafts.at(ink, w, h, fragment.x, fragment.y, gap, budget).isEmpty()
                || budget.refused()) return false;
        int pixels = 0;
        for (int y = top; y <= bottom; y++) {
            for (int x = from; x <= to; x++) {
                if (labels[y * w + x] != OmrMeasurePostProcessor.NOTEHEAD) continue;
                float tip = left.endY() + (right.endY() - left.endY()) * (x - ax) / (float) (bx - ax);
                if (x == from || x == to || y == top || y == bottom || tip - y < gap * .13f
                        || tip - y > gap * 1.24f || (ink[y * w + x] & 255) >= 170) return false;
                pixels++;
            }
        }
        return pixels >= gap * gap * .69f && pixels <= gap * gap * .85f;
    }

    private static ScorePageInterpretation recoverThreeFour(
            ScorePageInterpretation score, ScoreMeterChange inherited, byte[] labels,
            byte[] normalized, byte[] raw, int w, int h, int page) {
        var regions = score.measures();
        var meter = inherited;
        int meterIndex = 0;
        for (int i = 1; i < regions.size(); i++) {
            while (meterIndex < score.meterChanges().size()
                    && score.meterChanges().get(meterIndex).measureIndex() <= i)
                meter = score.meterChanges().get(meterIndex++);
            if (meter == null || meter.numerator() != 3 || meter.denominator() != 4) continue;
            final int measure = i;
            var notes = score.notes();
            var owners = java.util.stream.IntStream.range(0, notes.size())
                    .filter(j -> notes.get(j).measureIndex() == measure).boxed().toList();
            if (owners.size() == 23)
                score = recoverCompactRow(score, labels, normalized, raw, w, h, page, i, owners);
        }
        return score;
    }

    private static ScorePageInterpretation recoverCompactRow(ScorePageInterpretation score, byte[] labels,
            byte[] normalized, byte[] raw, int w, int h, int page, int measure, List<Integer> owners) {
        var notes = score.notes();
        var bar = score.measures().get(measure);
        var preceding = score.measures().get(measure - 1);
        float span = (bar.right() - bar.left()) * w, gap = (bar.bottom() - bar.top()) * h / 8.2f;
        if (bar.cadenceQuarterBeats() != 0 || gap < 12 || gap > 24 || span < gap * 26 || span > gap * 38
                || Math.abs(bar.top() - preceding.top()) * h > .3f * gap
                || score.rests().stream().anyMatch(r -> r.measureIndex() == measure)) return score;
        var heads = new ArrayList<Head>(22);
        Head fragment = null;
        for (int i : owners) {
            var note = notes.get(i);
            if (!ordinaryHead(note)) return score;
            var head = new Head(i, note, bar.left() * w + span * note.positionInMeasure(), note.pageY() * h);
            if (note.stemDirection() == 0 && note.beamCount() == 3 && note.unbeamedDurationBeats() == 0
                    && note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY) {
                if (fragment != null) return score;
                fragment = head;
            } else if (note.stemDirection() == -1 && sixteenth(note)) {
                heads.add(head);
            } else return score;
        }
        if (fragment == null) return score;
        heads.sort(Comparator.comparingDouble(Head::x));
        var columns = new ArrayList<Column>(12);
        var recovered = new java.util.HashMap<Integer, Head>();
        for (int i = 0; i < heads.size(); i++) {
            var a = heads.get(i);
            if (i + 1 == heads.size() || heads.get(i + 1).x - a.x > gap * .24f) {
                if (recovered.size() == 2 || a.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY)
                    return score;
                var upper = findUpper(labels, w, h, bar, a, gap);
                if (upper == null) return score;
                recovered.put(a.owner, upper);
                columns.add(new Column(upper, a));
                continue;
            }
            var b = heads.get(++i);
            var upper = a.y < b.y ? a : b;
            var lower = a == upper ? b : a;
            if (upper.note.staffStep() - lower.note.staffStep() != 7
                    || lower.y - upper.y < gap * 3.15f || lower.y - upper.y > gap * 3.85f) return score;
            columns.add(new Column(upper, lower));
        }
        if (columns.size() != 12 || recovered.size() != 2
                || !sourceRails(raw, labels, w, h, bar, preceding, columns, fragment, gap, true)
                || !sourceRails(normalized, labels, w, h, bar, preceding, columns, fragment, gap, true)) return score;

        var retained = new ArrayList<ScoreNoteEvent>(notes.size() + 1);
        var sourceOwners = new ArrayList<Integer>(notes.size() + 1);
        var projected = new ArrayList<ClosedSixteenthDyads.Head>(24);
        for (int i = 0; i < notes.size(); i++) {
            if (i == fragment.owner) continue;
            var missing = recovered.get(i);
            if (missing != null) {
                projected.add(new ClosedSixteenthDyads.Head(retained.size(), missing.note, missing.x, missing.y));
                retained.add(missing.note);
                sourceOwners.add(-1);
            }
            var note = notes.get(i);
            if (note.measureIndex() == measure)
                projected.add(new ClosedSixteenthDyads.Head(retained.size(), note,
                        bar.left() * w + span * note.positionInMeasure(), note.pageY() * h));
            retained.add(note);
            sourceOwners.add(i);
        }
        var result = score.withNotes(retained, sourceOwners);
        return page < 0 ? result : result.withBeamSource(
                ClosedSixteenthDyads.replaceDyadDoubleRails(result.beamSource(), page, projected));
    }

    private static ScorePageInterpretation recoverBar(ScorePageInterpretation score, byte[] labels,
            byte[] normalized, byte[] raw, int w, int h, int page, int measure, List<Integer> owners) {
        var notes = score.notes();
        var bar = score.measures().get(measure);
        var preceding = score.measures().get(measure - 1);
        float span = (bar.right() - bar.left()) * w, gap = (bar.bottom() - bar.top()) * h / 8.2f;
        if (bar.cadenceQuarterBeats() != 0 || gap < 12 || gap > 24
                || span < gap * 36 || span > gap * 47
                || Math.abs((bar.top() - preceding.top()) * h) > .3f * gap
                || score.rests().stream().anyMatch(r -> r.measureIndex() == measure)) return score;
        var heads = new ArrayList<Head>(32);
        Head fragment = null;
        for (int i : owners) {
            var note = notes.get(i);
            if (!ordinary(note) || score.beamSource().noteOrigins().get(i).isPresent()) return score;
            var head = new Head(i, note, bar.left() * w + span * note.positionInMeasure(), note.pageY() * h);
            if (note.stemDirection() == 0 && note.beamCount() == 2 && note.unbeamedDurationBeats() == 0) {
                if (fragment != null) return score;
                fragment = head;
            } else {
                if (note.stemDirection() != -1) return score;
                heads.add(head);
            }
        }
        if (fragment == null) return score;
        heads.sort(Comparator.comparingDouble(Head::x));
        var columns = new ArrayList<Column>(16);
        Head missing = null, lowerOwner = null;
        for (int i = 0; i < heads.size(); i++) {
            var a = heads.get(i);
            if (i + 1 == heads.size() || heads.get(i + 1).x - a.x > gap * .24f) {
                if (missing != null || columns.size() != 1 || !sixteenth(a.note)) return score;
                missing = findUpper(labels, w, h, bar, a, gap);
                if (missing == null) return score;
                lowerOwner = a;
                columns.add(new Column(missing, a));
                continue;
            }
            var b = heads.get(++i);
            var upper = a.y < b.y ? a : b;
            var lower = a == upper ? b : a;
            if (upper.note.staffStep() - lower.note.staffStep() != 7
                    || lower.y - upper.y < gap * 3.15f || lower.y - upper.y > gap * 3.85f
                    || !sixteenth(lower.note)) return score;
            if (columns.size() < 15 ? !sixteenth(upper.note)
                    : upper.note.beamCount() != 0 || upper.note.unbeamedDurationBeats() != 1) return score;
            columns.add(new Column(upper, lower));
        }
        if (columns.size() != 16 || missing == null
                || !sourceRails(raw, labels, w, h, bar, preceding, columns, fragment, gap, false)
                || !sourceRails(normalized, labels, w, h, bar, preceding, columns, fragment, gap, false)) return score;
        int tail = columns.get(columns.size() - 1).upper.owner;
        var retained = new ArrayList<ScoreNoteEvent>(notes.size());
        var sourceOwners = new ArrayList<Integer>(notes.size());
        var projected = new ArrayList<ClosedSixteenthDyads.Head>(32);
        for (int i = 0; i < notes.size(); i++) {
            if (i == fragment.owner) continue;
            if (i == lowerOwner.owner) {
                projected.add(new ClosedSixteenthDyads.Head(retained.size(), missing.note, missing.x, missing.y));
                retained.add(missing.note);
                sourceOwners.add(-1);
            }
            var note = i == tail ? ClosingTripletRhythm.beamedValue(notes.get(i), 2) : notes.get(i);
            if (note.measureIndex() == measure)
                projected.add(new ClosedSixteenthDyads.Head(retained.size(), note,
                        bar.left() * w + span * note.positionInMeasure(), note.pageY() * h));
            retained.add(note);
            sourceOwners.add(i);
        }
        var result = score.withNotes(retained, sourceOwners);
        return page < 0 ? result : result.withBeamSource(
                ClosedSixteenthDyads.projectDoubleRails(result.beamSource(), page, projected));
    }

    private static boolean ordinary(ScoreNoteEvent n) {
        return ordinaryHead(n) && n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
    }

    private static boolean ordinaryHead(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffIndex() == 0 && n.staffCount() == 1
                && n.clefBottomDiatonic() == ScoreNoteEvent.CLEF_TREBLE
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.augmentationDots() == 0 && n.tupletDivisor() == 1 && n.tupletNormalNotes() == 1
                && n.articulations() == 0 && n.octaveShift() == 0
                && !n.tiedFromPrevious() && n.boundaryTies() == 0 && !n.crossStaffBeam()
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0
                && n.harmonicSemitones() == 0 && n.openStringTouch() == null;
    }

    private static boolean sixteenth(ScoreNoteEvent n) {
        return n.beamCount() == 2 && n.unbeamedDurationBeats() == 0;
    }

    private static Head findUpper(byte[] labels, int w, int h, MeasureRegion bar, Head lower, float gap) {
        float expectedY = lower.y - 3.5f * gap;
        int left = Math.round(lower.x - .85f * gap), right = Math.round(lower.x + .85f * gap);
        int top = Math.round(expectedY - .81f * gap), bottom = Math.round(expectedY + .81f * gap);
        if (left < 0 || right >= w || top < 0 || bottom >= h) return null;
        int count = 0, sx = 0, sy = 0;
        for (int y = top; y <= bottom; y++)
            for (int x = left; x <= right; x++)
                if (labels[y * w + x] == OmrMeasurePostProcessor.NOTEHEAD) {
                    if (x == left || x == right || y == top || y == bottom) return null;
                    count++;
                    sx += x;
                    sy += y;
                }
        if (count < 1.03f * gap * gap || count > 1.65f * gap * gap) return null;
        float x = sx / (float) count, y = sy / (float) count;
        if (Math.abs(x - lower.x) > .19f * gap || Math.abs(y - expectedY) > .25f * gap) return null;
        var note = new ScoreNoteEvent(lower.note.measureIndex(),
                (x / w - bar.left()) / (bar.right() - bar.left()), lower.note.staffStep() + 7,
                0, 1, y / h, false, 0, 2, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0, 1, 0, 0,
                ScoreNoteEvent.CLEF_TREBLE).withStemDirection(-1);
        return new Head(-1, note, x, y);
    }

    private static boolean sourceRails(byte[] ink, byte[] labels, int w, int h, MeasureRegion bar,
            MeasureRegion preceding, List<Column> columns, Head fragment, float gap, boolean compact) {
        var frame = PrintedDirectionStaff.printedRules(ink, w, h, preceding.right() * w - .45f * gap,
                bar.bottom() * h - 2.1f * gap, gap);
        if (frame == null || Math.abs(frame[1] - gap) > .13f * gap) return false;
        var staff = new PlayingTechniqueDetector.Staff(frame[0] - 4 * frame[1], frame[0], frame[1], 0, 1);
        int left = PrintedCadenceDurations.boundaryColumn(
                ink, w, staff, (bar.left() + preceding.right()) * w * .5f, true, false);
        int right = PrintedCadenceDurations.boundaryColumn(ink, w, staff, bar.right() * w, true, false);
        if (left < 0 || right < 0 || columns.get(0).lower.x - left < gap * 1.4f
                || right - columns.get(columns.size() - 1).lower.x < gap * 1.0f) return false;
        var track = compact ? StaffPitchTrack.forPrintedSlurs(ink, w, h, staff.top(), staff.bottom(), staff.gap()) : null;
        if (compact && track == null) return false;
        var budget = new PrintedBeamShafts.Budget(350_000);
        PrintedBeamShafts.Shaft previous = null;
        boolean fragmentProved = false;
        for (int i = 0; i < columns.size(); i++) {
            var column = columns.get(i);
            var upper = column.upper;
            var lower = column.lower;
            var a = PrintedBeamShafts.at(ink, w, h, upper.x, upper.y, gap, budget);
            var b = PrintedBeamShafts.at(ink, w, h, lower.x, lower.y, gap, budget);
            if (a.size() != 1 || b.size() != 1 || budget.refused()) return false;
            var top = a.get(0);
            var base = b.get(0);
            if (top.direction() != 1 || base.direction() != 1) return false;
            if (compact || i < columns.size() - 1) {
                if (top.left() > base.right() || base.left() > top.right()
                        || Math.abs(top.endY() - base.endY()) > .3f * gap) return false;
            } else if (!ClosedFourDyadBeam.sharesSteppedDownstem(ink, w, top, base, gap)) return false;
            if (upper.owner < 0 && !ArtificialHarmonics.filledQuarterCore(ink, w, h, upper.x, upper.y, gap))
                return false;
            if (previous != null) {
                boolean cut = i % 4 == 0;
                boolean joined = !compact
                        ? WrappedEighthTriplets.matchesBeamRails(ink, w, h, previous, base, gap, cut ? 0 : 2)
                        : cut ? WrappedEighthTriplets.cutBetweenRecoveredGroups(ink, w, h, bar, previous, base, gap)
                              : WrappedEighthTriplets.matchesExactlyTwoBeamRails(ink, w, h, previous, base, gap, track);
                if (!joined) return false;
                if (!cut && fragment.x > previous.right() && fragment.x < base.left())
                    fragmentProved = coversRail(ink, labels, w, h, previous, base, fragment, gap, compact);
            }
            previous = base;
        }
        return fragmentProved;
    }

    private static boolean coversRail(byte[] ink, byte[] labels, int w, int h,
            PrintedBeamShafts.Shaft previous, PrintedBeamShafts.Shaft next, Head mask, float gap, boolean compact) {
        int left = Math.round(mask.x - gap * .72f), right = Math.round(mask.x + gap * .72f);
        int top = Math.round(mask.y - gap * .50f), bottom = Math.round(mask.y + gap * .50f);
        int ax = (previous.left() + previous.right()) / 2, bx = (next.left() + next.right()) / 2;
        if (left < 0 || right >= w || top < 0 || bottom >= h || bx <= ax
                || mask.x - ax < gap * .7f || bx - mask.x < gap * .7f) return false;
        int count = 0, minX = right, maxX = left, minY = bottom, maxY = top;
        for (int y = top; y <= bottom; y++)
            for (int x = left; x <= right; x++)
                if (labels[y * w + x] == OmrMeasurePostProcessor.NOTEHEAD) {
                    float tip = previous.endY() + (next.endY() - previous.endY()) * (x - ax) / (float) (bx - ax);
                    if (x == left || x == right || y == top || y == bottom
                            || tip - y < gap * .12f || tip - y > gap * 1.15f
                            || (ink[y * w + x] & 255) >= 170) return false;
                    count++;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
        float span = maxX - minX + 1, depth = maxY - minY + 1;
        if (compact && (span < .72f * gap || span > .96f * gap || depth < .43f * gap || depth > .64f * gap))
            return false;
        return count >= gap * gap * (compact ? .26f : .38f) && count <= gap * gap * (compact ? .37f : .62f)
                && PrintedBeamShafts.at(ink, w, h, mask.x, mask.y, gap,
                        new PrintedBeamShafts.Budget(50_000)).isEmpty();
    }
}
