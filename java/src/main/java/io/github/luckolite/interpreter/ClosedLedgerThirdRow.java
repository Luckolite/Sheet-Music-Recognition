// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

/** Recover fused close thirds only when all sixteen shared shafts fill a closed common-time bar. */
final class ClosedLedgerThirdRow {
    private ClosedLedgerThirdRow() {}

    private record Head(float x, float y, int step) {}
    private record Pair(Head upper, Head lower) {}
    private record Kept(int index, float x, float y, ScoreNoteEvent note) {}

    /** Restore a suppressed separator after a complete run of printed ledger thirds. */
    static List<MeasureRegion> recoverBoundaries(List<MeasureRegion> measures,
            byte[] labels, byte[] raw, byte[] gray, int w, int h) {
        if (raw == null || gray == null || labels == null || labels.length != (long) w * h
                || raw.length != labels.length || gray.length != labels.length) return measures;
        ArrayList<MeasureRegion> result = null;
        for (int i = 0; i < measures.size(); i++) {
            var region = measures.get(i);
            float left = region.left() * w, right = region.right() * w;
            float gap = (region.bottom() - region.top()) * h / 8.2f;
            if (region.cadenceQuarterBeats() != 0 || gap < 12 || gap > 24
                    || right - left <= 34 * gap) {
                if (result != null) result.add(region);
                continue;
            }
            float floor = region.bottom() * h - 2.1f * gap;
            var staff = new PlayingTechniqueDetector.Staff(floor - 4 * gap, floor, gap, 0, 1);
            var leading = ledgerBar(region, labels, raw, gray, w, h, staff);
            if (leading == null) {
                if (result != null) result.add(region);
                continue;
            }
            if (result == null) result = new ArrayList<>(measures.subList(0, i));
            result.add(leading);
            float nextLeft = leading.right() + Math.round(gap * 1.10f) / (float) w;
            result.add(new MeasureRegion(nextLeft, region.right(), region.top(), region.bottom()));
        }
        return result == null ? measures : List.copyOf(result);
    }

    private static MeasureRegion ledgerBar(MeasureRegion region, byte[] labels, byte[] raw, byte[] gray,
            int w, int h, PlayingTechniqueDetector.Staff staff) {
        float gap = staff.gap();
        int left = Math.round(region.left() * w), right = Math.round(region.right() * w);
        int clearance = Math.round(gap * .55f);
        int top = Math.round(staff.top()), bottom = Math.round(staff.bottom());
        if (left < 0 || right >= w || top < 0 || bottom >= h) return null;
        int last = Math.min(left + Math.round(34 * gap) + clearance, right - Math.round(5 * gap));
        MeasureRegion winner = null;
        int previous = -1;
        for (int hint = left + Math.round(25 * gap) + clearance; hint <= last; hint += 3) {
            int cut = PrintedCadenceDurations.boundaryColumn(gray, w, staff, hint, true, false);
            if (cut < 0 || cut == previous) continue;
            previous = cut;
            int sourceCut = PrintedCadenceDurations.boundaryColumn(raw, w, staff, hint, true, false);
            if (Math.abs(sourceCut - cut) > 2
                    || DottedDyadMeasureSeparator.continuesBeyondStaff(gray, w, h, cut, top, bottom, gap)
                    || DottedDyadMeasureSeparator.continuesBeyondStaff(raw, w, h, sourceCut, top, bottom, gap))
                continue;
            var candidate = alignPrintedLedgerBar(region, (cut - clearance) / (float) w,
                    labels, raw, gray, w, h, gap);
            if (candidate == null) continue;
            if (winner != null && Math.abs(winner.right() * w - candidate.right() * w) > 3)
                return null;
            winner = candidate;
        }
        return winner;
    }

    private static MeasureRegion alignPrintedLedgerBar(MeasureRegion region, float right,
            byte[] labels, byte[] raw, byte[] gray, int w, int h, float gap) {
        // Staff geometry can shift by a few raster pixels after segmentation.
        // Only accept nearby bounds when the labelled pairs and both ink planes agree.
        int tolerance = Math.max(1, Math.round(gap * .19f));
        for (int change = 0; change <= tolerance; change++) {
            for (int topExpansion = 0; topExpansion <= change; topExpansion++) {
                int bottomExpansion = change - topExpansion;
                var candidate = new MeasureRegion(region.left(), right,
                        region.top() - topExpansion / (float) h,
                        region.bottom() + bottomExpansion / (float) h);
                float candidateGap = (candidate.bottom() - candidate.top()) * h / 8.2f;
                float floor = candidate.bottom() * h - 2.1f * candidateGap;
                var pairs = columns(labels, w, h, candidate, floor, candidateGap);
                if (pairs.size() == 16
                        && printedRow(raw, w, h, candidate, pairs, floor, candidateGap)
                        && printedRow(gray, w, h, candidate, pairs, floor, candidateGap)) return candidate;
            }
        }
        return null;
    }

    static ScorePageInterpretation repair(ScorePageInterpretation score, ScoreMeterChange inherited,
            byte[] labels, byte[] raw, byte[] gray, int w, int h, int page,
            Consumer<SourceLocalAccidental.ProvedMark> printed) {
        if (raw == null || gray == null || labels == null || labels.length != (long) w * h
                || raw.length != labels.length || gray.length != labels.length) return score;
        var meter = inherited;
        int meterIndex = 0;
        for (int bar = 0; bar < score.measures().size(); bar++) {
            while (meterIndex < score.meterChanges().size() && score.meterChanges().get(meterIndex).measureIndex() <= bar)
                meter = score.meterChanges().get(meterIndex++);
            int measure = bar;
            var region = score.measures().get(measure);
            if (meter == null || meter.numerator() != 4 || meter.denominator() != 4
                    || bar == 0 && score.openingPickup() || region.cadenceQuarterBeats() != 0
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == measure)) continue;
            float gap = (region.bottom() - region.top()) * h / 8.2f;
            float span = (region.right() - region.left()) * w;
            if (gap < 12 || gap > 24 || span < 25 * gap || span > 34 * gap) continue;
            score = recover(score, labels, raw, gray, w, h, page, measure, region, gap, printed);
        }
        return score;
    }

    private static ScorePageInterpretation recover(ScorePageInterpretation score, byte[] labels,
            byte[] raw, byte[] gray, int w, int h, int page, int measure, MeasureRegion bar, float gap,
            Consumer<SourceLocalAccidental.ProvedMark> printed) {
        var notes = score.notes();
        var existing = new ArrayList<Kept>();
        int unsupportedWhole = -1;
        float floor = bar.bottom() * h - 2.1f * gap;
        for (int i = 0; i < notes.size(); i++) {
            var n = notes.get(i);
            if (n.measureIndex() != measure) continue;
            float x = (bar.left() + n.positionInMeasure() * (bar.right() - bar.left())) * w;
            float y = n.pageY() * h;
            if (n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffIndex() == 0 && n.staffCount() == 1
                    && n.clefBottomDiatonic() == ScoreNoteEvent.CLEF_TREBLE && n.stemDirection() == 0
                    && n.beamCount() == 0 && n.unbeamedDurationBeats() == 4 && n.augmentationDots() == 0
                    && n.tupletDivisor() == 1 && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                    && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0 && !n.tiedFromPrevious()
                    && n.boundaryTies() == 0 && n.articulations() == 0 && !n.crossStaffBeam()
                    && n.octaveShift() == 0 && n.harmonicSemitones() == 0 && n.openStringTouch() == null
                    && y >= floor + gap * .25f && y <= floor + gap
                    && !PrintedCadenceDurations.hollowPocket(raw, w, h, x, y, gap)
                    && !PrintedCadenceDurations.hollowPocket(gray, w, h, x, y, gap)
                    && score.beamSource().noteOrigins().get(i).isEmpty()) {
                if (unsupportedWhole >= 0) return score;
                unsupportedWhole = i;
                continue;
            }
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffCount() != 1 || n.staffIndex() != 0
                    || n.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE || n.stemDirection() != -1
                    || n.beamCount() != 2 || n.unbeamedDurationBeats() != 0 || n.augmentationDots() != 0
                    || n.tupletDivisor() != 1 || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0
                    || n.tiedFromPrevious() || n.boundaryTies() != 0 || n.crossStaffBeam()
                    || n.octaveShift() != 0 || n.harmonicSemitones() != 0 || n.openStringTouch() != null
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || score.beamSource().noteOrigins().get(i).isPresent()) return score;
            existing.add(new Kept(i, x, y, n));
        }
        if (existing.size() < 24 || existing.size() >= 32) return score;
        var pairs = columns(labels, w, h, bar, floor, gap);
        if (pairs.size() != 16 || !printedRow(raw, w, h, bar, pairs, floor, gap)
                || !printedRow(gray, w, h, bar, pairs, floor, gap)) return score;
        var replaced = new HashSet<Integer>();
        if (unsupportedWhole >= 0) replaced.add(unsupportedWhole);
        var owners = new HashSet<Integer>();
        var result = new ArrayList<ScoreNoteEvent>(32);
        var remap = new ArrayList<Integer>(32);
        var letters = new ArrayList<Head>(32);
        var flats = new ArrayList<ScoreNoteEvent>();
        for (var pair : pairs) {
            for (var head : List.of(pair.upper, pair.lower)) {
                Kept matched = null;
                for (var old : existing) {
                    if (Math.abs(old.x - head.x) > .23f * gap || Math.abs(old.y - head.y) > .24f * gap) continue;
                    if (matched != null || old.note.staffStep() != head.step || !owners.add(old.index)) return score;
                    matched = old;
                }
                boolean flat = PrintedFlatGlyph.matchesOwnedInk(labels, raw, w, h, head.x, head.y, gap)
                        && PrintedFlatGlyph.matchesOwnedInk(labels, gray, w, h, head.x, head.y, gap);
                var n = matched == null ? new ScoreNoteEvent(measure,
                        (head.x / w - bar.left()) / (bar.right() - bar.left()), head.step, 0, 1, head.y / h,
                        false, 0, 2, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0, 1, 0, 0,
                        ScoreNoteEvent.CLEF_TREBLE).withStemDirection(-1) : matched.note;
                if (flat) {
                    n = n.withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_FLAT);
                    flats.add(n);
                }
                letters.add(head);
                result.add(n);
                remap.add(matched == null ? -1 : matched.index);
            }
        }
        for (var old : existing) {
            replaced.add(old.index);
            if (owners.contains(old.index)) continue;
            boolean fused = pairs.stream().anyMatch(pair ->
                    Math.abs(old.x - (pair.upper.x + pair.lower.x) * .5f) <= .19f * gap
                    && Math.abs(old.y - (pair.upper.y + pair.lower.y) * .5f) <= .19f * gap
                    && old.note.staffStep() == pair.lower.step + 1);
            if (!fused) return score;
        }
        if (existing.size() - owners.size() == 0) return score;
        var edited = new ArrayList<ScoreNoteEvent>(notes.size() + 32 - existing.size());
        var identities = new ArrayList<Integer>(edited.size());
        int first = existing.get(0).index;
        for (int i = 0; i < notes.size(); i++) {
            if (i == first) {
                edited.addAll(result);
                identities.addAll(remap);
            }
            if (!replaced.contains(i)) {
                edited.add(notes.get(i));
                identities.add(i);
            }
        }
        flats.forEach(n -> printed.accept(new SourceLocalAccidental.ProvedMark(n, false)));
        var repaired = score.withNotes(edited, identities);
        if (page < 0) return repaired;
        var beams = new ArrayList<ClosedSixteenthDyads.Head>(32);
        for (int i = 0; i < result.size(); i++) {
            var h0 = letters.get(i);
            beams.add(new ClosedSixteenthDyads.Head(first + i, result.get(i), h0.x, h0.y));
        }
        return repaired.withBeamSource(ClosedSixteenthDyads.projectDoubleRails(repaired.beamSource(), page, beams));
    }

    private static List<Pair> columns(byte[] labels, int w, int h, MeasureRegion bar, float floor, float gap) {
        int left = Math.round(bar.left() * w), right = Math.round(bar.right() * w + .37f * gap);
        int top = Math.round(floor - 10.25f * gap), bottom = Math.round(floor - 2.55f * gap);
        if (left < 0 || right >= w || top < 0 || bottom >= h) return List.of();
        var pairs = new ArrayList<Pair>(16);
        int start = -1;
        for (int x = left; x <= right; x++) {
            int pixels = 0;
            for (int y = top; y <= bottom; y++)
                if (labels[y * w + x] == OmrMeasurePostProcessor.NOTEHEAD) pixels++;
            if (x < right && pixels >= .72f * gap) {
                if (start < 0) start = x;
                continue;
            }
            if (start < 0) continue;
            if (x - start < .95f * gap || x - start > 1.53f * gap || pairs.size() == 16) return List.of();
            var pair = split(labels, w, start, x, top, bottom, floor, gap);
            if (pair == null) return List.of();
            if (!pairs.isEmpty()) {
                float dx = pair.lower.x - pairs.get(pairs.size() - 1).lower.x;
                if (dx < 1.50f * gap || dx > 2.80f * gap) return List.of();
            }
            pairs.add(pair);
            start = -1;
        }
        return pairs;
    }

    private static Pair split(byte[] labels, int w, int left, int right, int top, int bottom, float floor, float gap) {
        int first = bottom, last = top;
        for (int y = top; y <= bottom; y++) for (int x = left; x < right; x++) {
            if (labels[y * w + x] != OmrMeasurePostProcessor.NOTEHEAD) continue;
            first = Math.min(first, y);
            last = Math.max(last, y);
        }
        if (first == top || last == bottom || last - first < 1.68f * gap || last - first > 2.43f * gap)
            return null;
        int middle = (first + last) / 2;
        var upper = head(labels, w, left, right, first, middle, floor, gap);
        var lower = head(labels, w, left, right, middle + 1, last, floor, gap);
        if (upper == null || lower == null || upper.step - lower.step != 2
                || Math.abs(upper.x - lower.x) > .18f * gap
                || Math.abs(lower.y - upper.y - gap) > .20f * gap) return null;
        return new Pair(upper, lower);
    }

    private static Head head(byte[] labels, int w, int left, int right, int top, int bottom, float floor, float gap) {
        int count = 0, sumX = 0, sumY = 0;
        for (int y = top; y <= bottom; y++) for (int x = left; x < right; x++) {
            if (labels[y * w + x] != OmrMeasurePostProcessor.NOTEHEAD) continue;
            count++;
            sumX += x;
            sumY += y;
        }
        if (count < .97f * gap * gap || count > 1.55f * gap * gap) return null;
        float x = sumX / (float) count, y = sumY / (float) count;
        float step = (floor - y) * 2 / gap;
        return Math.abs(step - Math.round(step)) <= .34f ? new Head(x, y, Math.round(step)) : null;
    }

    private static boolean printedRow(byte[] ink, int w, int h, MeasureRegion bar,
            List<Pair> pairs, float floor, float gap) {
        var staff = new PlayingTechniqueDetector.Staff(floor - 4 * gap, floor, gap, 0, 1);
        int left = PrintedCadenceDurations.boundaryColumn(ink, w, staff, bar.left() * w, true, false);
        int right = PrintedCadenceDurations.boundaryColumn(ink, w, staff, bar.right() * w, true, false);
        if (left < 0 || right < 0 || pairs.get(0).lower.x - left < 1.1f * gap
                || right - pairs.get(15).lower.x < .64f * gap) return false;
        var track = StaffPitchTrack.linear(w, floor, gap, 0);
        var budget = new PrintedBeamShafts.Budget(600_000);
        PrintedBeamShafts.Shaft prior = null;
        for (int i = 0; i < pairs.size(); i++) {
            var upper = pairs.get(i).upper;
            var lower = pairs.get(i).lower;
            if (!ArtificialHarmonics.filledQuarterCore(ink, w, h, upper.x, upper.y, gap)
                    || !ArtificialHarmonics.filledQuarterCore(ink, w, h, lower.x, lower.y, gap)) return false;
            var a = PrintedBeamShafts.at(ink, w, h, upper.x, upper.y, gap, budget);
            var b = PrintedBeamShafts.at(ink, w, h, lower.x, lower.y, gap, budget);
            if (a.size() != 1 || b.size() != 1 || budget.refused()) return false;
            var u = a.get(0);
            var l = b.get(0);
            if (u.direction() != 1 || l.direction() != 1 || u.left() > l.right()
                    || l.left() > u.right() || Math.abs(u.endY() - l.endY()) > .24f * gap) return false;
            if (prior != null && !(i % 4 == 0
                    ? WrappedEighthTriplets.matchesBeamRails(ink, w, h, prior, l, gap, 0)
                    : WrappedEighthTriplets.matchesExactlyTwoBeamRails(ink, w, h, prior, l, gap, track))) return false;
            prior = l;
        }
        return true;
    }
}
