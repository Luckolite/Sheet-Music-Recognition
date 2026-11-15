// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Recover two separately printed three-quarter runs and their still-closed cadence. */
final class MidstaffSixteenthTripleBars {
    private MidstaffSixteenthTripleBars() {}

    private record MeterCrop(MeterChangeDetector.Crop crop, float gap) {}

    private record Aliases(ScoreNoteEvent meter, ScoreNoteEvent flat) {}

    private record Bounds(MeasureRegion run, MeasureRegion cadence, float gap) {}

    record Result(List<MeasureRegion> measures, int first, MeterCrop meter) {
        List<ScoreNoteEvent> retain(
                List<ScoreNoteEvent> notes,
                byte[] labels,
                byte[] gray,
                byte[] raw,
                int width,
                int height) {
            if (first < 0) return notes;
            var local = sectionNotes(notes, first, first + 2);
            var aliases = aliases(local, measures, first, meter, labels, gray, raw, width, height);
            if (aliases == null) return notes;
            var kept = new ArrayList<>(notes);
            if (aliases.meter != null) kept.remove(aliases.meter);
            if (aliases.flat != null) kept.remove(aliases.flat);
            return List.copyOf(kept);
        }
    }

    static Result recover(
            List<MeasureRegion> regions,
            byte[] labels,
            byte[] gray,
            byte[] raw,
            int width,
            int height) {
        if (width < 320
                || height < 200
                || gray == null
                || raw == null
                || labels == null
                || gray.length != (long) width * height
                || raw.length != gray.length
                || labels.length != gray.length
                || regions.size() < 4) return new Result(regions, -1, null);
        List<MeterChangeDetector.Crop> candidates = null;
        Result retained = null;
        for (int index = 1; index + 2 < regions.size(); index++) {
            var previous = regions.get(index - 1);
            var span = regions.get(index);
            var cadence = regions.get(index + 1);
            var following = regions.get(index + 2);
            float gap = (span.bottom() - span.top()) * height / 8.2f;
            if (gap < 11
                    || gap > 25
                    || span.cadenceQuarterBeats() != 0
                    || cadence.cadenceQuarterBeats() != 0
                    || (span.right() - span.left()) * width < gap * 54
                    || (span.right() - span.left()) * width > gap * 74
                    || (cadence.right() - cadence.left()) * width < gap * 4
                    || (cadence.right() - cadence.left()) * width > gap * 8
                    || !sameRow(previous, span, height, gap)
                    || !sameRow(span, cadence, height, gap)
                    || (following.top() - cadence.bottom()) * height < gap * 2) continue;
            if (candidates == null)
                candidates = MeterChangeDetector.candidates(labels, gray, width, height);
            var meter = atOpening(candidates, span, gray, raw, width, height, gap);
            if (meter == null) continue;
            var replacement = prove(span, cadence, meter, labels, gray, raw, width, height);
            if (replacement.isEmpty()) continue;
            if (retained != null) return new Result(regions, -1, null);
            var edited = new ArrayList<>(regions);
            edited.set(index, replacement.get(0));
            edited.add(index + 1, replacement.get(1));
            retained = new Result(List.copyOf(edited), index, meter);
        }
        return retained == null ? new Result(regions, -1, null) : retained;
    }

    private static boolean sameRow(MeasureRegion a, MeasureRegion b, int height, float gap) {
        return Math.abs(a.top() - b.top()) * height < gap * .65f
                && Math.abs(a.bottom() - b.bottom()) * height < gap * .65f;
    }

    private static MeterCrop atOpening(
            List<MeterChangeDetector.Crop> candidates,
            MeasureRegion span,
            byte[] gray,
            byte[] raw,
            int width,
            int height,
            float gap) {
        MeterCrop accepted = null;
        for (var crop : candidates) {
            if (Math.abs(crop.gap() - gap) > gap * .15f
                    || Math.abs(crop.left() - span.left() * width) > gap * .65f
                    || crop.top() < span.top() * height
                    || crop.bottom() > span.bottom() * height) continue;
            if (!triple(crop, gray, width, height) || !triple(crop, raw, width, height)) continue;
            if (accepted != null && !accepted.crop.equals(crop)) return null;
            accepted = new MeterCrop(crop, crop.gap());
        }
        return accepted;
    }

    private static boolean triple(MeterChangeDetector.Crop crop, byte[] gray, int w, int h) {
        var read = MeterFontMatcher.readCrop(gray, w, h, crop);
        return read.text().equals("3/4") && read.topScore() >= .86f && read.bottomScore() >= .86f;
    }

    private static List<MeasureRegion> prove(
            MeasureRegion span,
            MeasureRegion cadence,
            MeterCrop meter,
            byte[] labels,
            byte[] gray,
            byte[] raw,
            int width,
            int height) {
        float gap = meter.gap;
        int top = meter.crop.firstLine(), bottom = Math.round(top + 4 * gap);
        if (top < gap || bottom + gap >= height) return List.of();
        var staff = new PlayingTechniqueDetector.Staff(top, bottom, gap, 0, 1);
        float left = span.left() * width, right = span.right() * width;
        float close = cadence.right() * width;
        if (PrintedCadenceDurations.boundaryColumn(raw, width, staff, left, true, false) < 0
                || PrintedCadenceDurations.boundaryColumn(gray, width, staff, left, true, false) < 0
                || PrintedCadenceDurations.boundaryColumn(raw, width, staff, right, true, false) < 0
                || PrintedCadenceDurations.boundaryColumn(gray, width, staff, right, true, false)
                        < 0
                || PrintedCadenceDurations.boundaryColumn(raw, width, staff, close, false, true) < 0
                || PrintedCadenceDurations.boundaryColumn(gray, width, staff, close, false, true)
                        < 0) return List.of();
        int first = Math.round(left + (right - left) * .42f);
        int last = Math.round(left + (right - left) * .60f);
        int inner = isolatedRule(raw, width, height, top, bottom, gap, first, last);
        if (inner < 0
                || Math.abs(
                                inner
                                        - isolatedRule(
                                                gray, width, height, top, bottom, gap, first, last))
                        > 1) return List.of();
        int padding = Math.round(gap * .48f);
        var a =
                new MeasureRegion(
                        span.left(), (inner - padding) / (float) width, span.top(), span.bottom());
        var b =
                new MeasureRegion(
                        (inner + padding) / (float) width, span.right(), span.top(), span.bottom());
        var local = List.of(a, b, cadence);
        var trial = OmrScoreInterpreter.analyze(labels, gray, width, height, local, raw);
        var supportedTies = ScoreTiePitchGuard.apply(trial.notes(), trial.keyChanges());
        var aliases = aliases(supportedTies, local, 0, meter, labels, gray, raw, width, height);
        if (!trial.rests().isEmpty()) return List.of();
        if (aliases == null)
            return closedOctaveRuns(local, supportedTies, staff, raw, gray, width, height, inner)
                    ? List.of(a, b) : List.of();
        var written = new ArrayList<>(supportedTies);
        if (aliases.meter != null) written.remove(aliases.meter);
        if (aliases.flat != null) written.remove(aliases.flat);
        for (int bar = 0; bar < 2; bar++) {
            final int owner = bar;
            var owners = new ArrayList<Integer>();
            for (int i = 0; i < written.size(); i++)
                if (written.get(i).measureIndex() == owner) owners.add(i);
            if (owners.size() != 12) return List.of();
            owners.sort(Comparator.comparingDouble(i -> written.get(i).positionInMeasure()));
            for (int start = 0; start < 12; start += 4) {
                var group = owners.subList(start, start + 4);
                if (!closedRails(gray, width, height, local.get(bar), written, group, gap)
                        || !closedRails(raw, width, height, local.get(bar), written, group, gap))
                    return List.of();
            }
        }
        return List.of(a, b);
    }

    private static boolean closedOctaveRuns(List<MeasureRegion> regions, List<ScoreNoteEvent> notes,
            PlayingTechniqueDetector.Staff staff, byte[] raw, byte[] gray, int width, int height,
            int separator) {
        var cadence = sectionNotes(notes, 2, 2);
        if (cadence.size() != 2 || cadence.get(0).staffStep() == cadence.get(1).staffStep()
                || cadence.get(0).stemDirection() != cadence.get(1).stemDirection()) return false;
        for (var note : cadence) {
            if (!plainHead(note) || note.beamCount() != 0 || note.unbeamedDurationBeats() != 1
                    || note.tiedFromPrevious() || note.positionInMeasure() < .25f
                    || note.positionInMeasure() > .65f) return false;
        }
        float gap = staff.gap();
        var end = regions.get(2);
        double first = Math.min(noteX(cadence.get(0), end, width), noteX(cadence.get(1), end, width));
        double last = Math.max(noteX(cadence.get(0), end, width), noteX(cadence.get(1), end, width));
        if (last - first > gap * .25f
                || !PrintedCadenceDurations.emptyOutsideChord(gray, width, staff, end.left() * width,
                        end.right() * width - gap * .6f, first, last, false)
                || !PrintedCadenceDurations.emptyOutsideChord(raw, width, staff, end.left() * width,
                        end.right() * width - gap * .6f, first, last, false)) return false;
        for (int bar = 0; bar < 2; bar++) {
            var ordered = sectionNotes(notes, bar, bar).stream()
                    .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
            if (ordered.size() != 24) return false;
            var bounds = regions.get(bar);
            for (int group = 0; group < 24; group += 8) {
                var heads = new ClosedFourDyadBeam.Head[4];
                int direction = ordered.get(group).stemDirection();
                for (int i = 0; i < heads.length; i++) {
                    var a = ordered.get(group + 2 * i);
                    var b = ordered.get(group + 2 * i + 1);
                    float x1 = noteX(a, bounds, width), x2 = noteX(b, bounds, width);
                    if (!plainHead(a) || !plainHead(b) || a.beamCount() != 2 || b.beamCount() != 2
                            || a.unbeamedDurationBeats() != 0 || b.unbeamedDurationBeats() != 0
                            || a.stemDirection() != direction || b.stemDirection() != direction
                            || Math.abs(a.staffStep() - b.staffStep()) != 7 || x2 - x1 > gap * .24f)
                        return false;
                    heads[i] = new ClosedFourDyadBeam.Head((x1 + x2) * .5f,
                            Math.min(a.pageY(), b.pageY()) * height, Math.max(a.pageY(), b.pageY()) * height);
                }
                int leftRule = bar == 1 && group == 0 ? separator : -1;
                if (!ClosedFourDyadBeam.provesOctavesAfterRule(
                            gray, width, height, heads, gap, staff.bottom(), -direction, leftRule)
                        || !ClosedFourDyadBeam.provesOctavesAfterRule(
                            raw, width, height, heads, gap, staff.bottom(), -direction, leftRule)) return false;
            }
        }
        return true;
    }

    private static boolean plainHead(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffCount() == 1 && n.staffIndex() == 0
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL && !n.crossStaffBeam()
                && n.boundaryTies() == 0 && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0
                && n.tupletDivisor() == 1 && n.augmentationDots() == 0 && n.stemDirection() != 0
                && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static boolean closedRails(
            byte[] gray,
            int width,
            int height,
            MeasureRegion region,
            List<ScoreNoteEvent> notes,
            List<Integer> owners,
            float gap) {
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(4);
        var budget = new PrintedBeamShafts.Budget(100_000);
        int direction = -notes.get(owners.get(0)).stemDirection();
        float previous = Float.NaN;
        for (int index : owners) {
            var note = notes.get(index);
            float x = noteX(note, region, width);
            if (note.stemDirection() != -direction
                    || Float.isFinite(previous)
                            && (x - previous < gap * 1.6f || x - previous > gap * 3.8f))
                return false;
            previous = x;
            var ink =
                    PrintedBeamShafts.at(
                            gray, width, height, x, note.pageY() * height, gap, budget);
            if (ink.size() != 1 || ink.get(0).direction() != direction) return false;
            shafts.add(ink.get(0));
        }
        var first = shafts.get(0);
        var last = shafts.get(3);
        int start = (first.left() + first.right()) / 2;
        int end = (last.left() + last.right()) / 2;
        int pad = Math.round(gap * .27f);
        int near = Math.round(gap * .31f);
        int far = Math.round(gap * .51f);
        if (start - far < 0 || end + far >= width || end - start < gap * 5) return false;
        for (int left = 1; left <= gap * .47f; left++) {
            float y0 = first.endY() - direction * left;
            for (int right = 1; right <= gap * .47f; right++) {
                float slope = (last.endY() - direction * right - y0) / (end - start);
                if (Math.abs(slope) > .32f
                        || rail(gray, width, height, start, start + pad, end - pad, y0, slope, 2)
                                < .97f
                        || rail(gray, width, height, start, start - far, start - near, y0, slope, 2)
                                > .25f
                        || rail(gray, width, height, start, end + near, end + far, y0, slope, 2)
                                > .25f) continue;
                for (int depth = Math.round(gap * .56f); depth <= gap * 1.10f; depth++) {
                    float inner = y0 - direction * depth;
                    if (rail(gray, width, height, start, start + pad, end - pad, inner, slope, 2)
                                    < .97f
                            || rail(
                                            gray,
                                            width,
                                            height,
                                            start,
                                            start + pad,
                                            end - pad,
                                            (y0 + inner) * .5f,
                                            slope,
                                            0)
                                    > .50f) continue;
                    return true;
                }
            }
        }
        return false;
    }

    private static float rail(
            byte[] gray,
            int width,
            int height,
            int origin,
            int from,
            int to,
            float y0,
            float slope,
            int radius) {
        int filled = 0;
        for (int x = from; x <= to; x++) {
            int center = Math.round(y0 + slope * (x - origin));
            if (center - radius < 0 || center + radius >= height) return 0;
            boolean dark = true;
            for (int y = center - radius; y <= center + radius; y++)
                if ((gray[y * width + x] & 255) >= 155) dark = false;
            if (dark) filled++;
        }
        return filled / (float) (to - from + 1);
    }

    private static int isolatedRule(
            byte[] gray,
            int width,
            int height,
            int top,
            int bottom,
            float gap,
            int first,
            int last) {
        int accepted = -1;
        for (int x = first; x <= last; x++) {
            if (!DottedDyadMeasureSeparator.fullInk(gray, width, x, top, gap)) continue;
            int start = x;
            while (x < last && DottedDyadMeasureSeparator.fullInk(gray, width, x + 1, top, gap))
                x++;
            int thickness = x - start + 1;
            int center = (start + x) / 2;
            if (thickness < 3
                    || thickness > gap * .45f
                    || !OmrMeasurePostProcessor.verticalRuleAt(
                            gray, width, center, top, bottom, gap)
                    || ClosedHeadBarlineGuard.attached(
                            gray, width, height, center, top, bottom, gap)
                    || DottedDyadMeasureSeparator.continuesBeyondStaff(
                            gray, width, height, center, top, bottom, gap)) continue;
            if (accepted >= 0) return -1;
            accepted = center;
        }
        return accepted;
    }

    private static List<ScoreNoteEvent> sectionNotes(
            List<ScoreNoteEvent> notes, int first, int last) {
        return notes.stream()
                .filter(n -> n.measureIndex() >= first && n.measureIndex() <= last)
                .toList();
    }

    private static Aliases aliases(
            List<ScoreNoteEvent> notes,
            List<MeasureRegion> regions,
            int first,
            MeterCrop meter,
            byte[] labels,
            byte[] gray,
            byte[] raw,
            int width,
            int height) {
        int[] count = new int[3];
        ScoreNoteEvent meterAlias = null;
        ScoreNoteEvent flatAlias = null;
        ScoreNoteEvent dotted = null;
        for (var n : notes) {
            int i = n.measureIndex() - first;
            if (i < 0 || i > 2) continue;
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED
                    || n.staffCount() != 1
                    || n.staffIndex() != 0
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || n.crossStaffBeam()
                    || n.boundaryTies() != 0
                    || n.leadingRestBeats() != 0
                    || n.followingRestBeats() != 0
                    || n.tupletDivisor() != 1
                    || (n.articulations() & NoteOrnament.GRACE) != 0) return null;
            if (i < 2
                    && n.beamCount() == 2
                    && n.unbeamedDurationBeats() == 0
                    && n.augmentationDots() == 0
                    && n.stemDirection() != 0) {
                count[i]++;
                continue;
            }
            float x = noteX(n, regions.get(n.measureIndex()), width), y = n.pageY() * height;
            if (!n.tiedFromPrevious()
                    && i == 0
                    && meterAlias == null
                    && n.stemDirection() == 0
                    && n.beamCount() == 0
                    && n.unbeamedDurationBeats() == 4
                    && n.augmentationDots() == 0
                    && x >= meter.crop.left()
                    && x <= meter.crop.right()
                    && y >= meter.crop.top()
                    && y <= meter.crop.bottom()
                    && ownedAliasInk(labels, gray, meter.gap, x, y, width, height)) {
                meterAlias = n;
                continue;
            }
            if (!n.tiedFromPrevious()
                    && i == 2
                    && dotted == null
                    && n.beamCount() == 0
                    && n.unbeamedDurationBeats() == 2
                    && n.augmentationDots() == 1
                    && n.stemDirection() != 0
                    && n.positionInMeasure() >= .25f
                    && n.positionInMeasure() <= .65f) {
                dotted = n;
                continue;
            }
            if (!n.tiedFromPrevious()
                    && i == 2
                    && flatAlias == null
                    && n.beamCount() == 0
                    && n.unbeamedDurationBeats() == 1
                    && n.augmentationDots() == 0
                    && n.stemDirection() == 0
                    && ownedAliasInk(labels, gray, meter.gap, x, y, width, height)
                    && printedFlat(gray, width, height, meter.gap, x, y)
                    && printedFlat(raw, width, height, meter.gap, x, y)) {
                flatAlias = n;
                continue;
            }
            return null;
        }
        if (count[0] != 12 || count[1] != 12 || dotted == null) return null;
        if (flatAlias != null) {
            var cadence = regions.get(first + 2);
            float spacing = noteX(dotted, cadence, width) - noteX(flatAlias, cadence, width);
            if (spacing < meter.gap * .85f
                    || spacing > meter.gap * 1.45f
                    || flatAlias.staffStep() != dotted.staffStep()
                    || dotted.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT) return null;
        }
        return new Aliases(meterAlias, flatAlias);
    }

    private static float noteX(ScoreNoteEvent n, MeasureRegion m, int width) {
        return (m.left() + n.positionInMeasure() * (m.right() - m.left())) * width;
    }

    private static boolean printedFlat(byte[] gray, int w, int h, float gap, float x, float y) {
        return PrintedFlatGlyph.matches(
                gray,
                w,
                h,
                Math.round(x - gap * .95f),
                Math.round(y - gap * 1.72f),
                Math.round(x + gap * .18f),
                Math.round(y + gap * .91f),
                gap);
    }

    private static boolean ownedAliasInk(
            byte[] labels, byte[] gray, float gap, float x, float y, int w, int h) {
        int count = 0;
        for (int row = Math.max(0, Math.round(y - gap * .46f));
                row <= Math.min(h - 1, Math.round(y + gap * .46f));
                row++)
            for (int col = Math.max(0, Math.round(x - gap * .46f));
                    col <= Math.min(w - 1, Math.round(x + gap * .46f));
                    col++)
                if (labels[row * w + col] == OmrMeasurePostProcessor.NOTEHEAD
                        && (gray[row * w + col] & 255) < 160) count++;
        return count >= Math.max(3, Math.round(gap * .23f));
    }
}
