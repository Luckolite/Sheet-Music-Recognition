// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Recover a missing opening octave head and remove an attack inside the corresponding double beam. */
final class ClosingOctaveSixteenths {
    private ClosingOctaveSixteenths() {}

    private record Pair(int lower, int upper) {}
    private record Point(float x, float y) {}
    private record Run(List<Pair> pairs, int fragment) {}
    private record MissingHead(Point point, int accidental) {}
    private record Repair(MeasureRegion bar, Run run, Map<Integer, MissingHead> missing,
            Map<Integer, ScoreNoteEvent> pitches) {}

    static ScorePageInterpretation restore(ScorePageInterpretation score, ScoreMeterChange initial,
            byte[] labels, byte[] gray, byte[] raw, int w, int h) {
        return restore(score, initial, labels, gray, raw, w, h, false);
    }

    static ScorePageInterpretation restoreThreeBeat(ScorePageInterpretation score, ScoreMeterChange initial,
            byte[] labels, byte[] gray, byte[] raw, int w, int h) {
        return restore(score, initial, labels, gray, raw, w, h, true);
    }

    private static ScorePageInterpretation restore(ScorePageInterpretation score, ScoreMeterChange initial,
            byte[] labels, byte[] gray, byte[] raw, int w, int h, boolean threeBeat) {
        if (labels == null || gray == null || raw == null || gray.length != (long) w * h
                || labels.length != gray.length || raw.length != gray.length) return score;
        var notes = score.notes();
        var rows = new ArrayList<List<Integer>>();
        for (var ignored : score.measures()) rows.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) rows.get(notes.get(i).measureIndex()).add(i);
        var rests = new java.util.BitSet(rows.size());
        for (var rest : score.rests()) rests.set(rest.measureIndex());
        int next = 0;
        var meter = initial;
        var repairs = new ArrayList<Repair>();
        for (int i = 0; i < rows.size(); i++) {
            while (next < score.meterChanges().size() && score.meterChanges().get(next).measureIndex() <= i)
                meter = score.meterChanges().get(next++);
            var owners = rows.get(i);
            var bar = score.measures().get(i);
            if (meter == null || meter.numerator() != (threeBeat ? 3 : 4) || meter.denominator() != 4
                    || rests.get(i) || (threeBeat ? owners.size() < 22 || owners.size() > 23 : owners.size() != 30)
                    || bar.cadenceQuarterBeats() != 0) continue;
            float gap = (bar.bottom() - bar.top()) * h / 8.2f;
            if (gap < 12 || gap > 24) continue;
            owners.sort(Comparator.comparingDouble(j -> notes.get(j).positionInMeasure()));
            var run = threeBeat ? matchThreeBeat(owners, notes, bar, w, h, gap)
                    : matchRun(owners, notes, bar, w, h, gap);
            if (run == null) continue;
            var lower = notes.get(run.pairs().get(0).lower());
            var candidate = openingHead(labels, gray, w, h, bar, lower, gap);
            if (candidate == null) continue;
            var missing = new HashMap<Integer, MissingHead>();
            missing.put(run.pairs().get(0).lower(), new MissingHead(candidate, ScoreNoteEvent.ACCIDENTAL_FROM_KEY));
            if (threeBeat) {
                var rowsForInk = UnnumberedTripletInk.sourceRows(bar, score.measures(), h);
                if (!collectMissingUpperFlats(labels, gray, raw, w, h, bar, notes, run, missing, gap)
                        || !provesThreeBeat(gray, w, h, bar, notes, run, missing, gap, rowsForInk)
                        || !provesThreeBeat(raw, w, h, bar, notes, run, missing, gap, rowsForInk)) continue;
            } else if (!provesInk(gray, w, h, bar, notes, run, candidate, gap)
                    || !provesInk(raw, w, h, bar, notes, run, candidate, gap)) continue;
            var pitchEdits = threeBeat
                    ? upperLedgerEdits(labels, gray, raw, w, h, bar, notes, run, gap)
                    : Map.<Integer, ScoreNoteEvent>of();
            repairs.add(new Repair(bar, run, missing, pitchEdits));
        }
        var result = score;
        // Inserting a head preserves the original owner indices of all earlier measures.
        for (int i = repairs.size() - 1; i >= 0; i--) {
            var repair = repairs.get(i);
            result = restoreRun(result, repair.run(), repair.missing(), repair.bar(), w, h, repair.pitches());
        }
        return result;
    }

    private static boolean collectMissingUpperFlats(byte[] labels, byte[] gray, byte[] raw, int w, int h,
            MeasureRegion bar, List<ScoreNoteEvent> notes, Run run, Map<Integer, MissingHead> missing, float gap) {
        for (var pair : run.pairs().subList(1, 3)) {
            if (pair.upper() >= 0) continue;
            var low = notes.get(pair.lower());
            if (low.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT) return false;
            var point = openingHead(labels, gray, w, h, bar, low, gap);
            if (point == null || !agreesOnFlat(labels, gray, raw, w, h, point.x(), point.y(), gap)) return false;
            missing.put(pair.lower(), new MissingHead(point, ScoreNoteEvent.ACCIDENTAL_FLAT));
        }
        return true;
    }

    private static Map<Integer, ScoreNoteEvent> upperLedgerEdits(byte[] labels, byte[] gray, byte[] raw,
            int w, int h, MeasureRegion bar, List<ScoreNoteEvent> notes, Run run, float gap) {
        var edits = new HashMap<Integer, ScoreNoteEvent>();
        for (int i = 1; i < run.pairs().size(); i++) {
            var pair = run.pairs().get(i);
            if (pair.upper() < 0) continue;
            var low = notes.get(pair.lower());
            var high = notes.get(pair.upper());
            int step = low.staffStep() + 7;
            if (high.staffStep() != step + 1 || high.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT)
                continue;
            float x = x(bar, high, w), y = high.pageY() * h;
            if (!FilledSparseBeamHead.completeInterior(gray, w, h, x, y, gap)
                    || !FilledSparseBeamHead.completeInterior(raw, w, h, x, y, gap)) continue;
            if (agreesOnFlat(labels, gray, raw, w, h, x, y, gap)) {
                edits.put(pair.upper(), high.withStaffStep(step));
            } else {
                float previous = x(bar, notes.get(run.pairs().get(i - 1).lower()), w);
                if (PrintedAccidentalSpineRecovery.unmarkedRepeat(labels, raw, gray, w, h, previous, x, y, gap))
                    edits.put(pair.upper(), high.withStaffStep(step)
                            .withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_FROM_KEY));
            }
        }
        return edits;
    }

    private static boolean agreesOnFlat(byte[] labels, byte[] gray, byte[] raw,
            int w, int h, float x, float y, float gap) {
        // Buffer the integer optical window against subpixel staff-box rounding.
        for (float windowGap : new float[]{gap, gap * 1.02f}) {
            if (PrintedFlatGlyph.matchesOwnedInk(labels, gray, w, h, x, y, windowGap)
                    && PrintedFlatGlyph.matchesOwnedInk(labels, raw, w, h, x, y, windowGap)) return true;
        }
        return false;
    }

    private static Run matchThreeBeat(List<Integer> owners, List<ScoreNoteEvent> notes,
            MeasureRegion bar, int w, int h, float gap) {
        int firstOwner = owners.get(0);
        var first = notes.get(firstOwner);
        if (!clockHead(first, first.staffStep(), 2, true)
                || first.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) return null;
        var pairs = new ArrayList<Pair>();
        pairs.add(new Pair(firstOwner, -1));
        var previous = first;
        for (int j = 1; j < owners.size();) {
            int a = owners.get(j++);
            var note = notes.get(a);
            int b = j < owners.size() && Math.abs(x(bar, note, w) - x(bar, notes.get(owners.get(j)), w))
                    <= .22f * gap ? owners.get(j++) : -1;
            int lower = b < 0 || note.pageY() > notes.get(b).pageY() ? a : b;
            int upper = lower == a ? b : a;
            var bottom = notes.get(lower);
            float spacing = x(bar, bottom, w) - x(bar, previous, w);
            int descent = previous.staffStep() - bottom.staffStep();
            boolean tuple = pairs.size() < 3;
            if (!clockHead(bottom, bottom.staffStep(), 2, tuple)
                    || bottom.clefBottomDiatonic() != first.clefBottomDiatonic()
                    || spacing < 1.50f * gap || spacing > 3.10f * gap || descent < 0 || descent > 1) return null;
            if (upper < 0) {
                if (!tuple) return null;
            } else {
                var top = notes.get(upper);
                boolean ledgerPitchMayHaveClaimedTie = !tuple && top.staffStep() == bottom.staffStep() + 8
                        && top.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FLAT;
                if (!clockHead(top, top.staffStep(), 2, tuple, ledgerPitchMayHaveClaimedTie)
                        || top.clefBottomDiatonic() != first.clefBottomDiatonic()
                        || Math.abs(top.staffStep() - bottom.staffStep() - 7) > 1
                        || Math.abs((bottom.pageY() - top.pageY()) * h - 3.5f * gap) > .31f * gap) return null;
            }
            pairs.add(new Pair(lower, upper));
            previous = bottom;
        }
        return pairs.size() == 12 ? new Run(pairs, -1) : null;
    }

    private static boolean provesThreeBeat(byte[] ink, int w, int h, MeasureRegion bar,
            List<ScoreNoteEvent> notes, Run run, Map<Integer, MissingHead> missing, float gap,
            UnnumberedTripletInk.Rows rows) {
        float floor = bar.bottom() * h - 2.1f * gap;
        var track = StaffPitchTrack.forPrintedSlurs(ink, w, h, floor - 4 * gap, floor, gap);
        if (track == null || !ClosedDottedSixteenths.hasClosingRule(ink, w, h, bar)) return false;
        var budget = new PrintedBeamShafts.Budget(420_000);
        var axes = new ArrayList<PrintedBeamShafts.Shaft>();
        for (var pair : run.pairs()) {
            var low = notes.get(pair.lower());
            var high = pair.upper() < 0 ? missing.get(pair.lower()).point()
                    : new Point(x(bar, notes.get(pair.upper()), w), notes.get(pair.upper()).pageY() * h);
            if (pair.upper() < 0 && !FilledSparseBeamHead.completeInterior(ink, w, h, high.x(), high.y(), gap))
                return false;
            float y = low.pageY() * h;
            var shafts = PrintedBeamShafts.at(ink, w, h, x(bar, low, w), y, gap, budget);
            if (shafts.size() != 1 || shafts.get(0).direction() != 1
                    || !sameStem(ink, w, shafts.get(0), high.x(), high.y(), y, gap)) return false;
            axes.add(shafts.get(0));
        }
        if (budget.refused()) return false;
        for (int i = 1; i < axes.size(); i++) {
            var left = axes.get(i - 1);
            var right = axes.get(i);
            if (!(i % 4 == 0
                    ? WrappedEighthTriplets.cutBetweenRecoveredGroups(ink, w, h, bar, left, right, gap)
                    : WrappedEighthTriplets.matchesExactlyTwoBeamRails(ink, w, h, left, right, gap, track)))
                return false;
        }
        var first = notes.get(run.pairs().get(0).lower());
        var second = notes.get(run.pairs().get(1).lower());
        var third = notes.get(run.pairs().get(2).lower());
        float center = x(bar, second, w), tip = axes.get(1).endY();
        float high = missing.get(run.pairs().get(0).lower()).point().y();
        if (UnnumberedTripletInk.bracketBesideBeam(ink, w, h, gap, x(bar, first, w), x(bar, third, w),
                high, third.pageY() * h, tip, rows, track, true)
                || UnnumberedTripletInk.centeredThree(ink, w, h, center,
                        high - 4 * gap, high + .15f * gap, gap, rows, track)) return false;
        return hasStackedFingering(ink, w, h, center, tip, gap, rows);
    }

    private static boolean hasStackedFingering(byte[] ink, int w, int h, float x, float beamTip,
            float gap, UnnumberedTripletInk.Rows rows) {
        int left = Math.round(x - .93f * gap), right = Math.round(x + .93f * gap);
        int top = Math.max(rows.first(), Math.round(beamTip + 1.7f * gap));
        int bottom = Math.min(rows.last(), Math.round(beamTip + 4.85f * gap));
        if (left < 0 || right >= w || top >= bottom) return false;
        var glyphs = RawNumeralComponents.capture(ink, w, h, left, top, right, bottom, 165, () -> {}, () -> {})
                .components().stream().filter(c -> c.left > left && c.right < right
                        && c.top > top && c.bottom < bottom
                        && c.bottom - c.top + 1 >= .77f * gap && c.bottom - c.top + 1 <= 1.35f * gap
                        && c.right - c.left + 1 >= .44f * gap && c.right - c.left + 1 <= 1.05f * gap
                        && Math.abs((c.left + c.right) * .5f - x) <= .30f * gap)
                .sorted(Comparator.comparingInt(c -> c.top)).toList();
        return glyphs.size() == 2 && glyphs.get(1).top - glyphs.get(0).bottom <= .40f * gap
                && TripletRhythmDetector.fingerOne(ink, w, glyphs.get(1));
    }

    private static Run matchRun(List<Integer> owners, List<ScoreNoteEvent> notes,
            MeasureRegion bar, int w, int h, float gap) {
        var pairs = new ArrayList<Pair>();
        var first = notes.get(owners.get(0));
        int fragment = -1;
        for (int j = 0; j < owners.size();) {
            int owner = owners.get(j++);
            var note = notes.get(owner);
            int attack = pairs.size();
            if (note.beamCount() == 0) {
                if (attack != 9 || fragment >= 0 || note.stemDirection() != 1 || note.unbeamedDurationBeats() != 1
                        || note.tiedFromPrevious() || note.kind() != ScoreNoteEvent.Kind.PITCHED
                        || note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) return null;
                fragment = owner;
                continue;
            }
            if (attack >= 15) return null;
            boolean lone = j == 1;
            if (!lone && j == owners.size()) return null;
            int other = lone ? -1 : owners.get(j++);
            if (other >= 0 && notes.get(other).pageY() > note.pageY()) {
                int upper = owner;
                owner = other;
                other = upper;
                note = notes.get(owner);
            }
            int step = attack == 14 ? first.staffStep() - 9 : first.staffStep() - attack / 2 - 2 * (attack % 2);
            int rails = attack == 14 ? 1 : 2;
            boolean tuple = attack < 3;
            if (!clockHead(note, step, rails, tuple)) return null;
            if (other >= 0) {
                var upper = notes.get(other);
                if (!clockHead(upper, step + 7, rails, tuple)
                        || Math.abs(x(bar, note, w) - x(bar, upper, w)) > .22f * gap
                        || Math.abs((note.pageY() - upper.pageY()) * h - 3.5f * gap) > .24f * gap) return null;
            }
            pairs.add(new Pair(owner, other));
        }
        return pairs.size() == 15 && fragment >= 0 ? new Run(pairs, fragment) : null;
    }

    private static boolean clockHead(ScoreNoteEvent n, int step, int rail, boolean tuple) {
        return clockHead(n, step, rail, tuple, false);
    }

    private static boolean clockHead(ScoreNoteEvent n, int step, int rail, boolean tuple, boolean allowLocalTie) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffCount() == 1 && n.staffIndex() == 0
                && n.staffStep() == step && n.stemDirection() == -1 && n.beamCount() == rail
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL && n.unbeamedDurationBeats() == 0
                && n.augmentationDots() == 0 && n.tupletDivisor() == (tuple ? 3 : 1)
                && n.tupletNormalNotes() == (tuple ? 2 : 1) && n.leadingRestBeats() == 0
                && n.followingRestBeats() == 0 && n.harmonicSemitones() == 0 && n.openStringTouch() == null
                && (allowLocalTie || !n.tiedFromPrevious()) && n.boundaryTies() == 0 && !n.crossStaffBeam()
                && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static Point openingHead(byte[] labels, byte[] gray, int w, int h,
            MeasureRegion bar, ScoreNoteEvent lower, float gap) {
        float x = x(bar, lower, w), y = lower.pageY() * h - 3.5f * gap;
        int left = Math.round(x - .83f * gap), right = Math.round(x + .86f * gap);
        int top = Math.round(y - .58f * gap), bottom = Math.round(y + .58f * gap);
        if (left < 0 || top < 0 || right >= w || bottom >= h) return null;
        int count = 0;
        long sumX = 0, sumY = 0;
        for (int at = top; at <= bottom; at++)
            for (int col = left; col <= right; col++) {
                int pixel = at * w + col;
                if (labels[pixel] != OmrMeasurePostProcessor.NOTEHEAD || (gray[pixel] & 255) >= 155) continue;
                count++;
                sumX += col;
                sumY += at;
            }
        if (count < .68f * gap * gap || count > 1.44f * gap * gap) return null;
        var point = new Point((float) sumX / count, (float) sumY / count);
        return Math.abs(point.x() - x) <= .24f * gap && Math.abs(point.y() - y) <= .24f * gap ? point : null;
    }

    private static boolean provesInk(byte[] ink, int w, int h, MeasureRegion bar,
            List<ScoreNoteEvent> notes, Run run, Point missing, float gap) {
        if (!ClosedDottedSixteenths.hasClosingRule(ink, w, h, bar)
                || !FilledSparseBeamHead.completeInterior(ink, w, h, missing.x(), missing.y(), gap)) return false;
        var budget = new PrintedBeamShafts.Budget(420_000);
        var axes = new ArrayList<PrintedBeamShafts.Shaft>();
        float priorX = 0;
        for (var pair : run.pairs()) {
            var lower = notes.get(pair.lower());
            float x = x(bar, lower, w), y = lower.pageY() * h;
            if (!axes.isEmpty() && (x - priorX < 1.52f * gap || x - priorX > 2.92f * gap)) return false;
            var candidates = PrintedBeamShafts.at(ink, w, h, x, y, gap, budget);
            if (candidates.size() != 1 || candidates.get(0).direction() != 1) return false;
            var axis = candidates.get(0);
            float upperX = pair.upper() < 0 ? missing.x() : x(bar, notes.get(pair.upper()), w);
            float upperY = pair.upper() < 0 ? missing.y() : notes.get(pair.upper()).pageY() * h;
            if (!sameStem(ink, w, axis, upperX, upperY, y, gap)) return false;
            axes.add(axis);
            priorX = x;
        }
        if (budget.refused()) return false;
        float floor = bar.bottom() * h - 2.1f * gap;
        var track = StaffPitchTrack.forPrintedSlurs(ink, w, h, floor - 4 * gap, floor, gap);
        if (track == null) return false;
        for (int i = 1; i < axes.size(); i++) {
            var a = axes.get(i - 1);
            var b = axes.get(i);
            if (!(i % 4 == 0 ? WrappedEighthTriplets.cutBetweenRecoveredGroups(ink, w, h, bar, a, b, gap)
                    : i == 14 ? WrappedEighthTriplets.matchesBeamRails(ink, w, h, a, b, gap, 1)
                    : WrappedEighthTriplets.matchesExactlyTwoBeamRails(ink, w, h, a, b, gap, track))) return false;
        }
        return insideBeam(ink, w, h, bar, notes.get(run.fragment()), axes.get(8), axes.get(9), gap);
    }

    private static boolean sameStem(byte[] ink, int w, PrintedBeamShafts.Shaft axis,
            float upperX, float upperY, float lowerY, float gap) {
        int top = Math.round(upperY + .20f * gap), bottom = Math.round(lowerY - .15f * gap);
        for (int col = axis.left(); col <= axis.right(); col++) {
            if (upperX - col < .34f * gap || upperX - col > .86f * gap) continue;
            int white = 0;
            for (int y = top; y <= bottom; y++)
                if ((ink[y * w + col] & 255) >= 170) white++;
            if (white <= 2) return true;
        }
        return false;
    }

    private static boolean insideBeam(byte[] ink, int w, int h, MeasureRegion bar, ScoreNoteEvent note,
            PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, float gap) {
        float center = x(bar, note, w), y = note.pageY() * h;
        if (center < a.right() + .38f * gap || center > b.left() - .33f * gap) return false;
        float lower = a.endY() + (b.endY() - a.endY()) * (center - a.right()) / (b.left() - a.right());
        if (y < lower - 1.3f * gap || y > lower + .24f * gap) return false;
        int left = Math.round(center - .38f * gap), right = Math.round(center + .38f * gap);
        int top = Math.round(y - .22f * gap), bottom = Math.round(y + .22f * gap);
        if (left < 0 || right >= w || top < 0 || bottom >= h) return false;
        int inkRows = 0;
        for (int at = top; at <= bottom; at++) {
            int dark = 0;
            for (int col = left; col <= right; col++)
                if ((ink[at * w + col] & 255) < 150) dark++;
            if (dark >= .90f * (right - left + 1)) inkRows++;
        }
        return inkRows >= Math.round(.20f * gap);
    }

    private static ScorePageInterpretation restoreRun(ScorePageInterpretation score, Run run,
            Map<Integer, MissingHead> missing,
            MeasureRegion bar, int width, int height, Map<Integer, ScoreNoteEvent> pitchEdits) {
        var notes = score.notes();
        var edited = new ArrayList<ScoreNoteEvent>();
        var sourceOwners = new ArrayList<Integer>();
        var changed = new HashSet<Integer>();
        for (int i = 0; i < 3; i++) {
            var pair = run.pairs().get(i);
            changed.add(pair.lower());
            if (pair.upper() >= 0) changed.add(pair.upper());
        }
        var added = new HashMap<Integer, Integer>();
        for (int i = 0; i < notes.size(); i++) {
            if (i == run.fragment()) continue;
            var recovered = missing.get(i);
            if (recovered != null) {
                var lower = notes.get(i);
                var point = recovered.point();
                float position = (point.x() / width - bar.left()) / (bar.right() - bar.left());
                added.put(i, edited.size());
                edited.add(new ScoreNoteEvent(lower.measureIndex(), position, lower.staffStep() + 7,
                        lower.staffIndex(), lower.staffCount(), point.y() / height, false, 0, 2,
                        recovered.accidental(), 0, 1, 0, 0, lower.clefBottomDiatonic()).withStemDirection(-1));
                sourceOwners.add(-1);
            }
            var note = pitchEdits.getOrDefault(i, notes.get(i));
            edited.add(changed.contains(i) ? note.withTupletRatio(1, 1) : note);
            sourceOwners.add(i);
        }
        if (!pitchEdits.isEmpty()) {
            var verifiedTies = ScoreTiePitchGuard.recheckWithKeyContext(edited, score.keyChanges());
            for (int i = 0; i < edited.size(); i++) {
                if (pitchEdits.containsKey(sourceOwners.get(i))) edited.set(i, verifiedTies.get(i));
            }
        }
        var result = score.withNotes(edited, sourceOwners);
        var projected = beams(result.beamSource(), score.beamSource(), run, added);
        return projected == result.beamSource() ? score : result.withBeamSource(projected);
    }

    private static ScoreBeamSource beams(ScoreBeamSource remapped, ScoreBeamSource original, Run run,
            Map<Integer, Integer> added) {
        var origins = new ArrayList<>(remapped.noteOrigins());
        var first = original.noteOrigins().get(run.pairs().get(0).lower());
        if (first.isEmpty() || added.isEmpty()) return remapped;
        int page = first.get().pageIndex();
        for (int owner : added.values()) {
            var fresh = Optional.of(new ScoreBeamSource.Anchor(page, ScoreBeamSource.MAX_NOTES - 1 - owner));
            if (origins.contains(fresh)) return remapped;
            origins.set(owner, fresh);
        }
        var columns = new ArrayList<ScoreBeamSource.Column>();
        var touched = new HashSet<ScoreBeamSource.Anchor>();
        for (var pair : run.pairs()) {
            var low = original.noteOrigins().get(pair.lower());
            var high = pair.upper() < 0 ? origins.get(added.get(pair.lower())) : original.noteOrigins().get(pair.upper());
            if (low.isEmpty() || high.isEmpty() || low.get().pageIndex() != page || high.get().pageIndex() != page)
                return remapped;
            columns.add(new ScoreBeamSource.Column(List.of(low.get(), high.get())));
            if (!touched.add(low.get()) || !touched.add(high.get())) return remapped;
        }
        if (run.fragment() >= 0) original.noteOrigins().get(run.fragment()).ifPresent(touched::add);
        if (original.detachedColumns().stream().anyMatch(c -> c.members().stream().anyMatch(touched::contains)))
            return remapped;
        var groups = new ArrayList<ScoreBeamSource.Group>();
        for (var g : original.groups()) {
            var anchors = g.columns().stream().flatMap(c -> c.members().stream()).toList();
            if (anchors.stream().noneMatch(touched::contains)) groups.add(g);
            else if (!touched.containsAll(anchors) || !g.hooks().isEmpty()) return remapped;
        }
        for (int i = 0; i < columns.size(); i += 4) {
            var members = columns.subList(i, Math.min(i + 4, columns.size()));
            groups.add(new ScoreBeamSource.Group("printed-beam:" + page + ":" + members.get(0).members().get(0).noteIndex(),
                    members, members.size() == 3 ? List.of(3, 1) : List.of(3, 3, 3), List.of()));
        }
        return new ScoreBeamSource(groups, remapped.detachedColumns(), origins);
    }

    private static float x(MeasureRegion bar, ScoreNoteEvent note, int width) {
        return (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
    }
}
