// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Recovers a missed double rule between an isolated half dyad and a complete sixteenth bar. */
final class HalfDyadBeforeSixteenthBar {
    private HalfDyadBeforeSixteenthBar() {}

    static List<MeasureRegion> recover(List<MeasureRegion> regions, byte[] labels, byte[] gray,
            int width, int height) {
        var result = new ArrayList<MeasureRegion>();
        for (int i = 0; i < regions.size(); i++) {
            var region = regions.get(i);
            var parts = i > 0 && i + 1 < regions.size()
                    ? split(regions.get(i - 1), region, regions.get(i + 1), gray, width, height)
                    : List.<MeasureRegion>of();
            if (parts.isEmpty() || !qualifies(parts, OmrScoreInterpreter.analyze(
                    labels, gray, width, height, parts), gray, width, height)) {
                result.add(region);
                continue;
            }
            result.addAll(parts);
        }
        return result.size() == regions.size() ? regions : List.copyOf(result);
    }

    static List<MeasureRegion> split(MeasureRegion prior, MeasureRegion whole, MeasureRegion following,
            byte[] gray, int width, int height) {
        if (whole.cadenceQuarterBeats() != 0) return List.of();
        float gap = (whole.bottom() - whole.top()) * height / 8.2f;
        float left = whole.left() * width, right = whole.right() * width;
        if (gap < 8 || gap > 30 || right - left < 32 * gap || right - left > 49 * gap
                || !sameStaff(prior, whole, gap, height) || !sameStaff(following, whole, gap, height))
            return List.of();
        float opening = (prior.right() * width + left) * .5f;
        float closing = (following.left() * width + right) * .5f;
        if (Math.abs(opening - left) > gap || Math.abs(closing - right) > gap)
            return List.of();
        float hint = whole.bottom() * height - 2.1f * gap;
        if (!boundaryAt(gray, width, height, opening, hint, gap)
                || !boundaryAt(gray, width, height, closing, hint, gap)) return List.of();

        int cut = -1;
        int start = Math.round(left + 3.4f * gap), end = Math.round(left + 6.4f * gap);
        for (int x = start; x <= end; x++) {
            if (!DottedDyadMeasureSeparator.fullInk(
                    gray, width, x, Math.round(hint - 4 * gap), gap)) continue;
            var rails = DottedDyadMeasureSeparator.localRails(gray, width, height, x, hint, gap);
            if (rails == null) continue;
            int top = Math.round(rails[0] - 4 * rails[1]), bottom = Math.round(rails[0]);
            var staff = new PlayingTechniqueDetector.Staff(top, bottom, rails[1], 0, 1);
            int first = PrintedCadenceDurations.boundaryColumn(gray, width, staff, x, false, false);
            if (first < 0 || !unownedRule(gray, width, height, first, staff)) continue;
            int second = adjacentRule(gray, width, height, first, staff);
            if (second < 0) continue;
            int center = (first + second) / 2;
            if (cut >= 0 && Math.abs(center - cut) > gap * .45f) return List.of();
            cut = center;
        }
        if (cut < 0) return List.of();
        float padding = Math.round(gap * .48f);
        return List.of(new MeasureRegion(whole.left(), (cut - padding) / width,
                            whole.top(), whole.bottom(), 2),
                       new MeasureRegion((cut + padding) / width, whole.right(),
                            whole.top(), whole.bottom()));
    }

    static List<MeasureRegion> recoverDotted(
            List<MeasureRegion> regions,
            byte[] labels,
            byte[] normal,
            byte[] raw,
            int width,
            int height,
            List<PlayingTechniqueDetector.Word> words) {
        var edited = new ArrayList<MeasureRegion>();
        List<MeterChangeDetector.Crop> crops = null;
        for (int i = 0; i < regions.size(); i++) {
            var current = regions.get(i);
            List<MeasureRegion> possible = List.of();
            boolean terminalTriple = false;
            if (i > 0 && i + 1 < regions.size()) {
                var prior = regions.get(i - 1);
                var following = regions.get(i + 1);
                float gap = (current.bottom() - current.top()) * height / 8.2f;
                if (gap >= 10 && gap <= 27 && sameStaff(prior, current, gap, height)) {
                    if (sameStaff(current, following, gap, height)) {
                        possible = split(prior, current, following, normal, width, height);
                        if (!possible.equals(split(prior, current, following, raw, width, height)))
                            possible = List.of();
                    } else if ((following.top() - current.bottom()) * height > gap * 3
                            && (current.right() - current.left()) * width >= 31 * gap
                            && (current.right() - current.left()) * width <= 44 * gap) {
                        if (crops == null)
                            crops = MeterChangeDetector.candidates(labels, normal, width, height);
                        possible =
                                splitTerminalTriple(
                                        prior, current, following, crops, normal, raw, width,
                                        height);
                        terminalTriple = !possible.isEmpty();
                    }
                }
            }
            if (!possible.isEmpty()) {
                var local =
                        OmrScoreInterpreter.analyze(labels, normal, width, height, possible, raw);
                if (qualifiesDotted(
                        possible,
                        local.notes(),
                        local.rests(),
                        terminalTriple,
                        normal,
                        width,
                        height)) {
                    edited.addAll(possible);
                    continue;
                }
                if (terminalTriple && qualifiesStraightSplit(possible, local, words, normal, raw, width, height)) {
                    var run = possible.get(1);
                    edited.add(possible.get(0));
                    edited.add(new MeasureRegion(run.left(), run.right(), run.top(), run.bottom(), 3));
                    continue;
                }
            }
            edited.add(current);
        }
        return edited.size() == regions.size() ? regions : List.copyOf(edited);
    }

    private static boolean qualifiesStraightSplit(
            List<MeasureRegion> parts, OmrScoreInterpreter.Analysis local, List<PlayingTechniqueDetector.Word> words,
            byte[] gray, byte[] raw, int width, int height) {
        var half = local.notes().stream().filter(n -> n.measureIndex() == 0).toList();
        var run = parts.get(1);
        var rests = local.rests();
        if (!rests.isEmpty() && (rests.size() != 1 || rests.get(0).measureIndex() != 1
                || !InlineStraightOctaveTriples.fingeringRest(run, rests.get(0), words, gray, raw, width, height)))
            return false;
        if (!isolatedHalfOctave(parts.get(0), half, gray, width, height)
                || !isolatedHalfOctave(parts.get(0), half, raw, width, height)) return false;
        var notes = local.notes().stream().filter(n -> n.measureIndex() == 1).toList();
        return InlineStraightOctaveTriples.closed(run, notes, gray, width, height)
                && InlineStraightOctaveTriples.closed(run, notes, raw, width, height);
    }

    /**
     * An inferred count must not erase a real double rule or the signature printed between a closed
     * half dyad and the final beamed bar of a system.
     */
    static List<MeasureRegion> preserveInlineTriple(
            List<MeasureRegion> separated,
            List<MeasureRegion> fitted,
            byte[] labels,
            byte[] normal,
            byte[] raw,
            int width,
            int height) {
        if (width < 1
                || height < 1
                || normal == null
                || labels == null
                || raw == null
                || normal.length != (long) width * height
                || labels.length != normal.length
                || raw.length != normal.length
                || separated.size() < 4) return fitted;
        List<MeterChangeDetector.Crop> candidates = null;
        ArrayList<MeasureRegion> result = null;
        for (int i = 1; i + 2 < separated.size(); i++) {
            var prior = separated.get(i - 1);
            var half = separated.get(i);
            var run = separated.get(i + 1);
            var following = separated.get(i + 2);
            float gap = (half.bottom() - half.top()) * height / 8.2f;
            float left = half.left() * width, halfEnd = half.right() * width;
            float runStart = run.left() * width, right = run.right() * width;
            float separation = runStart - halfEnd;
            boolean compact = separation >= .75f * gap && separation <= 1.8f * gap;
            if (half.cadenceQuarterBeats() != 0
                    || run.cadenceQuarterBeats() != 0
                    || gap < 10
                    || gap > 27
                    || halfEnd - left < 3.5f * gap
                    || halfEnd - left > 7 * gap
                    || right - runStart < 24 * gap
                    || right - runStart > 35 * gap
                    || (!compact && (separation < 2.4f * gap || separation > 4.6f * gap))
                    || !sameStaff(prior, half, gap, height)
                    || !sameStaff(run, half, gap, height)
                    || (following.top() - run.bottom()) * height < 3 * gap) continue;
            var current = result == null ? fitted : result;
            int merged = -1;
            for (int j = 0; j < current.size(); j++) {
                var bar = current.get(j);
                if (bar.cadenceQuarterBeats() != 0
                        || !sameStaff(half, bar, gap, height)
                        || Math.abs((bar.left() - half.left()) * width) > gap * .3f
                        || Math.abs((bar.right() - run.right()) * width) > gap * .3f) continue;
                merged = j;
                break;
            }
            if (merged < 0) continue;
            if (candidates == null)
                candidates = MeterChangeDetector.candidates(labels, normal, width, height);
            var meter = compact
                    ? compactInlineTripleCrop(candidates, prior, half, run, normal, raw, width, height, gap)
                    : inlineTripleCrop(candidates, prior, half, run, normal, raw, width, height, gap);
            if (meter == null) continue;
            var parts = List.of(half, run);
            var analysis = OmrScoreInterpreter.analyze(labels, normal, width, height, parts, raw);
            if (!qualifiesStraightTriple(parts, analysis, normal, width, height, compact)
                    || !qualifiesStraightTriple(parts, analysis, raw, width, height, compact)
                    || !MeterChangeDetector.precedesNotes(
                            meter, labels, normal, width, height, 1, parts, analysis.notes())
                    || !MeterChangeDetector.precedesNotes(
                            meter, labels, raw, width, height, 1, parts, analysis.notes()))
                continue;
            if (result == null) result = new ArrayList<>(fitted);
            result.set(
                    merged,
                    new MeasureRegion(half.left(), half.right(), half.top(), half.bottom(), 2));
            result.add(merged + 1, run);
        }
        return result == null ? fitted : List.copyOf(result);
    }

    static MeterChangeDetector.Crop inlineTripleCrop(
            List<MeterChangeDetector.Crop> crops,
            MeasureRegion prior,
            MeasureRegion half,
            MeasureRegion run,
            byte[] normal,
            byte[] raw,
            int width,
            int height,
            float gap) {
        MeterChangeDetector.Crop meter = null;
        float opening = (prior.right() + half.left()) * width * .5f;
        if (half.left() * width - opening > gap) return null;
        for (var crop : crops) {
            float hint = crop.firstLine() + 4 * crop.gap();
            if (Math.abs(crop.gap() - gap) > .15f * gap
                    || crop.top() < half.top() * height
                    || crop.bottom() > half.bottom() * height
                    || crop.left() < half.right() * width + gap
                    || Math.abs(crop.right() - run.left() * width) > .45f * gap
                    || !triple(normal, width, height, crop)
                    || !triple(raw, width, height, crop)
                    || !boundaryAt(normal, width, height, opening, hint, gap)
                    || !boundaryAt(raw, width, height, opening, hint, gap)) continue;
            if (!hasPairedRulesBeforeCrop(crop, half, normal, raw, width, height, gap))
                continue;
            if (meter != null && !meter.equals(crop)) return null;
            meter = crop;
        }
        return meter;
    }

    /** A compact digit crop can start on either side of the paired rules. */
    static MeterChangeDetector.Crop compactInlineTripleCrop(
            List<MeterChangeDetector.Crop> crops,
            MeasureRegion prior,
            MeasureRegion half,
            MeasureRegion run,
            byte[] normal,
            byte[] raw,
            int width,
            int height,
            float gap) {
        MeterChangeDetector.Crop accepted = null;
        float opening = (prior.right() + half.left()) * width * .5f;
        float runStart = run.left() * width;
        if (half.left() * width - opening > gap) return null;
        for (var crop : crops) {
            float hint = crop.firstLine() + 4 * crop.gap();
            if (Math.abs(crop.gap() - gap) > .15f * gap
                    || crop.top() < half.top() * height
                    || crop.bottom() > half.bottom() * height
                    || crop.left() < half.right() * width
                    || crop.right() < runStart + .9f * gap
                    || crop.right() > runStart + 3.1f * gap) continue;
            var source = crop;
            var corrected = crop;
            if (crop.left() <= runStart - .65f * gap) {
                source = MeterDoubleRuleCrop.trimPair(crop, raw, width, height, runStart);
                corrected = MeterDoubleRuleCrop.trimPair(crop, normal, width, height, runStart);
                if (source == crop || corrected == crop
                        || Math.abs(source.left() - corrected.left()) > 1) continue;
            } else if (crop.left() < half.right() * width + gap
                    || !hasPairedRulesBeforeCrop(crop, half, normal, raw, width, height, gap)) {
                continue;
            }
            if (source.left() < runStart - .15f * gap
                    || source.left() > runStart + .65f * gap
                    || !triple(raw, width, height, source)
                    || !triple(normal, width, height, corrected)
                    || !boundaryAt(normal, width, height, opening, hint, gap)
                    || !boundaryAt(raw, width, height, opening, hint, gap)) continue;
            if (accepted != null && !accepted.equals(source)) return null;
            accepted = source;
        }
        return accepted;
    }

    private static boolean hasPairedRulesBeforeCrop(
            MeterChangeDetector.Crop crop, MeasureRegion half, byte[] normal, byte[] raw,
            int width, int height, float gap) {
        int source = meterDoubleRule(raw, width, height, crop);
        int corrected = meterDoubleRule(normal, width, height, crop);
        float halfEnd = half.right() * width;
        return source > halfEnd && source - halfEnd <= gap * 1.1f
                && Math.abs(source - corrected) <= 1;
    }

    static boolean qualifiesStraightTriple(
            List<MeasureRegion> parts,
            OmrScoreInterpreter.Analysis analysis,
            byte[] gray,
            int width,
            int height) {
        return qualifiesStraightTriple(parts, analysis, gray, width, height, false);
    }

    private static boolean qualifiesStraightTriple(
            List<MeasureRegion> parts,
            OmrScoreInterpreter.Analysis analysis,
            byte[] gray,
            int width,
            int height,
            boolean compact) {
        if (!analysis.rests().isEmpty()) return false;
        var cadence = parts.get(0);
        var run = parts.get(1);
        var heads = analysis.notes().stream().filter(n -> n.measureIndex() == 0).toList();
        if (!isolatedHalfOctave(cadence, heads, gray, width, height)) return false;
        var notes =
                analysis.notes().stream()
                        .filter(n -> n.measureIndex() == 1)
                        .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                        .toList();
        return qualifiesStraightTripleRun(notes, run, gray, width, height, compact);
    }

    private static boolean qualifiesStraightTripleRun(
            List<ScoreNoteEvent> notes, MeasureRegion run, byte[] gray,
            int width, int height, boolean compact) {
        if (notes.size() != 24) return false;
        float span = (run.right() - run.left()) * width;
        float gap = (run.bottom() - run.top()) * height / 8.2f;
        boolean recoveredSharedStem = false;
        for (int slot = 0; slot < notes.size(); slot += 2) {
            var a = notes.get(slot);
            var b = notes.get(slot + 1);
            if (!plainHead(a, compact)
                    || !plainHead(b, compact)
                    || a.stemDirection() == 0
                    || a.stemDirection() != b.stemDirection()
                    || Math.abs(a.staffStep() - b.staffStep()) != 7
                    || (b.positionInMeasure() - a.positionInMeasure()) * span > .3f * gap) return false;
            boolean regular = a.unbeamedDurationBeats() == 0
                    && b.unbeamedDurationBeats() == 0
                    && a.beamCount() == b.beamCount()
                    && (a.beamCount() == 2 || slot == notes.size() - 2 && a.beamCount() == 3);
            if (!regular) {
                if (!compact || recoveredSharedStem || slot == 0 || slot == notes.size() - 2
                        || !quarterAliasOnSharedStem(a, b, run, gray, width, height, gap)) return false;
                recoveredSharedStem = true;
            }
            if (slot < 2) continue;
            float advance =
                    (a.positionInMeasure() - notes.get(slot - 2).positionInMeasure()) * span;
            if (advance < gap || advance > 3.5f * gap) return false;
        }
        return true;
    }

    /** Restores a single quarter fallback in the double-beamed octave run after a short half bar. */
    static ScorePageInterpretation restoreCompactTripleQuarter(
            ScorePageInterpretation score, byte[] corrected, byte[] raw, int width, int height) {
        if (corrected == null || raw == null || corrected.length != (long) width * height
                || raw.length != corrected.length || width < 1 || height < 1) return score;
        var measures = score.measures();
        List<ScoreNoteEvent> repaired = null;
        for (var meter : score.meterChanges()) {
            int bar = meter.measureIndex();
            if (meter.numerator() != 3 || meter.denominator() != 4 || bar <= 0
                    || bar >= measures.size() - 1 || score.rests().stream()
                            .anyMatch(r -> r.measureIndex() == bar || r.measureIndex() == bar - 1)) continue;
            var half = measures.get(bar - 1);
            var run = measures.get(bar);
            var following = measures.get(bar + 1);
            float gap = (run.bottom() - run.top()) * height / 8.2f;
            float split = (run.left() - half.right()) * width;
            if (gap < 10 || gap > 27 || half.cadenceQuarterBeats() != 2
                    || run.cadenceQuarterBeats() != 0 || !sameStaff(half, run, gap, height)
                    || split < .75f * gap || split > 1.8f * gap
                    || (following.top() - run.bottom()) * height <= 3 * gap) continue;
            var halfNotes = score.notes().stream().filter(n -> n.measureIndex() == bar - 1).toList();
            if (!isolatedHalfOctave(half, halfNotes, corrected, width, height)
                    || !isolatedHalfOctave(half, halfNotes, raw, width, height)) continue;
            var notes = score.notes().stream().filter(n -> n.measureIndex() == bar)
                    .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
            var alias = provedCompactTripleQuarter(notes, run, corrected, width, height);
            if (alias == null || alias != provedCompactTripleQuarter(notes, run, raw, width, height)) continue;
            if (repaired == null) repaired = new ArrayList<>(score.notes());
            repaired.set(score.notes().indexOf(alias), sharedSixteenth(alias));
        }
        if (repaired == null) return score;
        return score.withNotes(repaired,
                java.util.stream.IntStream.range(0, repaired.size()).boxed().toList());
    }

    static ScoreNoteEvent provedCompactTripleQuarter(
            List<ScoreNoteEvent> notes, MeasureRegion run, byte[] gray, int width, int height) {
        float left = run.left() * width, span = (run.right() - run.left()) * width;
        float gap = (run.bottom() - run.top()) * height / 8.2f;
        if (gap < 10 || gap > 27 || !qualifiesStraightTripleRun(notes, run, gray, width, height, true))
            return null;
        var alias = notes.stream().filter(n -> n.beamCount() == 0).findFirst().orElse(null);
        if (alias == null || !plainHead(alias) || alias.harmonicSemitones() != 0) return null;
        float hint = run.bottom() * height - 2.1f * gap;
        var staff = DottedDyadMeasureSeparator.localRails(
                gray, width, height, Math.round(left + 3 * gap), hint, gap);
        if (staff == null) return null;
        for (int i = 0; i < notes.size(); i += 8) {
            var group = new ClosedFourDyadBeam.Head[4];
            for (int pair = 0; pair < group.length; pair++) {
                var a = notes.get(i + 2 * pair);
                var b = notes.get(i + 2 * pair + 1);
                if (a.beamCount() > 2 || b.beamCount() > 2
                        || (a == alias && a.pageY() <= b.pageY())
                        || (b == alias && b.pageY() <= a.pageY())) return null;
                group[pair] = new ClosedFourDyadBeam.Head(
                        left + span * (a.positionInMeasure() + b.positionInMeasure()) * .5f,
                        Math.min(a.pageY(), b.pageY()) * height,
                        Math.max(a.pageY(), b.pageY()) * height);
            }
            // Shaft direction in the raster is the opposite of the written stem sign.
            if (!ClosedFourDyadBeam.provesOctaves(
                    gray, width, height, group, gap, staff[0], -notes.get(i).stemDirection(), false))
                return null;
        }
        return alias;
    }

    private static ScoreNoteEvent sharedSixteenth(ScoreNoteEvent n) {
        return new ScoreNoteEvent(n.measureIndex(), n.positionInMeasure(), n.staffStep(),
                n.staffIndex(), n.staffCount(), n.pageY(), n.tiedFromPrevious(), n.augmentationDots(),
                2, n.writtenAccidental(), 0, n.tupletDivisor(), n.followingRestBeats(),
                n.articulations(), n.clefBottomDiatonic(), n.crossStaffBeam(), n.leadingRestBeats(),
                n.compactOpening(), n.octaveShift(), n.boundaryTies(), n.tupletNormalNotes(),
                n.stemDirection(), n.kind(), n.visualSize(), n.harmonicSemitones(), n.openStringTouch());
    }

    private static boolean quarterAliasOnSharedStem(
            ScoreNoteEvent a, ScoreNoteEvent b, MeasureRegion run,
            byte[] gray, int width, int height, float gap) {
        var beam = a.beamCount() == 2 ? a : b;
        var quarter = beam == a ? b : a;
        if (beam.beamCount() != 2 || beam.unbeamedDurationBeats() != 0
                || quarter.beamCount() != 0 || quarter.unbeamedDurationBeats() != 1) return false;
        float left = run.left() * width, span = (run.right() - run.left()) * width;
        var budget = new PrintedBeamShafts.Budget(100_000);
        var first = PrintedBeamShafts.at(gray, width, height,
                left + span * a.positionInMeasure(), a.pageY() * height, gap, budget);
        var second = PrintedBeamShafts.at(gray, width, height,
                left + span * b.positionInMeasure(), b.pageY() * height, gap, budget);
        if (first.size() != 1 || second.size() != 1) return false;
        var x = first.get(0);
        var y = second.get(0);
        return x.direction() == -a.stemDirection() && x.direction() == y.direction()
                && Math.min(x.right(), y.right()) - Math.max(x.left(), y.left()) >= 1
                && Math.abs(x.endY() - y.endY()) <= .18f * gap;
    }

    private static boolean isolatedHalfOctave(
            MeasureRegion cadence, List<ScoreNoteEvent> head, byte[] gray, int width, int height) {
        float left = cadence.left() * width, right = cadence.right() * width;
        float gap = (cadence.bottom() - cadence.top()) * height / 8.2f;
        if (head.size() != 2
                || Math.abs(head.get(0).staffStep() - head.get(1).staffStep()) != 7
                || !dyad(head.get(0), head.get(1), right - left, gap, true)) return false;
        float a = head.get(0).positionInMeasure(), b = head.get(1).positionInMeasure();
        if (Math.min(a, b) < .17f || Math.max(a, b) > .70f) return false;
        var rails =
                DottedDyadMeasureSeparator.localRails(
                        gray,
                        width,
                        height,
                        Math.round(right - .95f * gap),
                        cadence.bottom() * height - 2.1f * gap,
                        gap);
        if (rails == null) return false;
        var staff =
                new PlayingTechniqueDetector.Staff(
                        rails[0] - 4 * rails[1], rails[0], rails[1], 0, 1);
        return PrintedCadenceDurations.emptyOutsideChord(
                gray,
                width,
                staff,
                left,
                right,
                left + Math.min(a, b) * (right - left),
                left + Math.max(a, b) * (right - left),
                false);
    }

    static boolean qualifiesDotted(
            List<MeasureRegion> parts,
            List<ScoreNoteEvent> source,
            List<ScoreRestEvent> rests,
            boolean triple,
            byte[] gray,
            int width,
            int height) {
        if (!rests.isEmpty()) return false;
        var cadence = parts.get(0);
        var run = parts.get(1);
        var head = source.stream().filter(n -> n.measureIndex() == 0).toList();
        if (!isolatedHalfOctave(cadence, head, gray, width, height)) return false;
        float gap = (cadence.bottom() - cadence.top()) * height / 8.2f;

        var notes =
                source.stream()
                        .filter(n -> n.measureIndex() == 1)
                        .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                        .toList();
        if (notes.size() < (triple ? 23 : 32) || notes.size() > (triple ? 24 : 33)) return false;
        float span = (run.right() - run.left()) * width;
        var columns = new ArrayList<List<ScoreNoteEvent>>();
        int reverse = 0;
        for (var note : notes) {
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED
                    || note.staffIndex() != 0
                    || note.staffCount() != 1
                    || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || note.tupletDivisor() != 1
                    || note.crossStaffBeam()) return false;
            if (!triple
                    && note.stemDirection() == 1
                    && note.beamCount() == 1
                    && note.augmentationDots() == 0
                    && note.positionInMeasure() > .86f
                    && ++reverse == 1) continue;
            if (note.stemDirection() != -1) return false;
            var previous =
                    columns.isEmpty() ? List.<ScoreNoteEvent>of() : columns.get(columns.size() - 1);
            if (previous.isEmpty()
                    || (note.positionInMeasure() - previous.get(0).positionInMeasure()) * span
                            > .32f * gap) {
                var newColumn = new ArrayList<ScoreNoteEvent>();
                newColumn.add(note);
                columns.add(newColumn);
            } else previous.add(note);
        }
        if (columns.size() != (triple ? 12 : 16)) return false;
        int readDots = 0;
        for (int i = 0; i < columns.size(); i++) {
            var lane = columns.get(i);
            boolean dotted = i % 2 == 0;
            if (dotted && lane.stream().anyMatch(n -> n.augmentationDots() == 1)) readDots++;
            if (!dotted && lane.stream().anyMatch(n -> n.augmentationDots() != 0)) return false;
            if (lane.size() == 1 && triple && !dotted && lane.get(0).unbeamedDurationBeats() == 1)
                continue;
            if (lane.size() != 2
                    || Math.abs(lane.get(0).staffStep() - lane.get(1).staffStep()) != 7
                    || lane.stream()
                            .anyMatch(
                                    n ->
                                            n.beamCount() < 2
                                                    || n.beamCount() > 3
                                                    || n.unbeamedDurationBeats() != 0))
                return false;
        }
        return readDots >= (triple ? 6 : 7);
    }

    static List<MeasureRegion> splitTerminalTriple(
            MeasureRegion prior,
            MeasureRegion whole,
            MeasureRegion following,
            List<MeterChangeDetector.Crop> candidates,
            byte[] normal,
            byte[] raw,
            int width,
            int height) {
        float gap = (whole.bottom() - whole.top()) * height / 8.2f;
        float left = whole.left() * width, right = whole.right() * width;
        if (whole.cadenceQuarterBeats() != 0
                || gap < 10
                || gap > 27
                || right - left < 31 * gap
                || right - left > 44 * gap
                || !sameStaff(prior, whole, gap, height)
                || (following.top() - whole.bottom()) * height < 3 * gap
                || right + gap >= width) return List.of();

        MeterChangeDetector.Crop meter = null;
        for (var crop : candidates) {
            if (crop.left() - left < 3.7f * gap
                    || crop.left() - left > 5.9f * gap
                    || crop.top() < whole.top() * height
                    || crop.bottom() > whole.bottom() * height
                    || Math.abs(crop.gap() - gap) > .15f * gap
                    || !triple(normal, width, height, crop)
                    || !triple(raw, width, height, crop)) continue;
            if (meter != null && !meter.equals(crop)) return List.of();
            meter = crop;
        }
        if (meter == null) return List.of();
        float opening = (prior.right() * width + left) * .5f;
        float hint = meter.firstLine() + 4 * meter.gap();
        if (left - opening > gap
                || !boundaryAt(normal, width, height, opening, hint, gap)
                || !boundaryAt(raw, width, height, opening, hint, gap)) return List.of();
        int normalized = meterDoubleRule(normal, width, height, meter);
        int source = meterDoubleRule(raw, width, height, meter);
        if (source < 0 || Math.abs(normalized - source) > 1) return List.of();
        int padding = Math.round(gap * .48f);
        if (normalized + padding >= meter.left()
                || meter.left() - normalized - padding > gap * .40f) return List.of();
        return List.of(
                new MeasureRegion(
                        whole.left(),
                        (normalized - padding) / (float) width,
                        whole.top(),
                        whole.bottom(),
                        2),
                new MeasureRegion(
                        (normalized + padding) / (float) width,
                        whole.right(),
                        whole.top(),
                        whole.bottom()));
    }

    private static boolean triple(
            byte[] gray, int width, int height, MeterChangeDetector.Crop meter) {
        var read = MeterFontMatcher.readCrop(gray, width, height, meter);
        return read.text().equals("3/4") && read.topScore() >= .86f && read.bottomScore() >= .86f;
    }

    private static int meterDoubleRule(
            byte[] gray, int width, int height, MeterChangeDetector.Crop meter) {
        float gap = meter.gap();
        var staff =
                new PlayingTechniqueDetector.Staff(
                        meter.firstLine(), meter.firstLine() + 4 * gap, gap, 0, 1);
        int cut = -1;
        for (int x = Math.round(meter.left() - 1.55f * gap); x <= meter.left() - .64f * gap; x++) {
            if (!unownedRule(gray, width, height, x, staff)) continue;
            int next = adjacentRule(gray, width, height, x, staff);
            if (next < 0 || next >= meter.left()) continue;
            int middle = (x + next) / 2;
            if (cut >= 0 && Math.abs(middle - cut) > gap * .35f) return -1;
            cut = middle;
        }
        return cut;
    }

    static boolean qualifies(List<MeasureRegion> parts, OmrScoreInterpreter.Analysis analysis,
            byte[] gray, int width, int height) {
        if (!analysis.rests().isEmpty()) return false;
        var cadence = parts.get(0);
        var following = parts.get(1);
        var chord = analysis.notes().stream().filter(n -> n.measureIndex() == 0).toList();
        var run = analysis.notes().stream().filter(n -> n.measureIndex() == 1)
                .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
        if (chord.size() != 2 || run.size() != 32) return false;
        float gap = (cadence.bottom() - cadence.top()) * height / 8.2f;
        float left = cadence.left() * width, right = cadence.right() * width;
        if (!dyad(chord.get(0), chord.get(1), right - left, gap, true)) return false;
        float a = chord.get(0).positionInMeasure(), b = chord.get(1).positionInMeasure();
        if (Math.min(a, b) < .18f || Math.max(a, b) > .68f) return false;
        var rails = DottedDyadMeasureSeparator.localRails(gray, width, height,
                Math.round(right - 1.3f * gap), cadence.bottom() * height - 2.1f * gap, gap);
        if (rails == null) return false;
        var staff = new PlayingTechniqueDetector.Staff(rails[0] - 4 * rails[1], rails[0],
                rails[1], 0, 1);
        if (!PrintedCadenceDurations.emptyOutsideChord(gray, width, staff, left, right,
                left + Math.min(a, b) * (right - left), left + Math.max(a, b) * (right - left), false))
            return false;
        float span = (following.right() - following.left()) * width;
        for (int i = 0; i < run.size(); i += 2) {
            if (!dyad(run.get(i), run.get(i + 1), span, gap, false)) return false;
            if (i >= 2) {
                float advance = (run.get(i).positionInMeasure()
                        - run.get(i - 2).positionInMeasure()) * span;
                if (advance < gap || advance > 4.5f * gap) return false;
            }
        }
        return true;
    }

    private static boolean dyad(ScoreNoteEvent first, ScoreNoteEvent second,
            float span, float gap, boolean half) {
        return first.staffStep() != second.staffStep()
                && first.stemDirection() != 0 && first.stemDirection() == second.stemDirection()
                && Math.abs(first.positionInMeasure() - second.positionInMeasure()) * span < .35f * gap
                && head(first, half) && head(second, half);
    }

    private static boolean head(ScoreNoteEvent note, boolean half) {
        return plainHead(note)
                && note.beamCount() == (half ? 0 : 2)
                && note.unbeamedDurationBeats() == (half ? 2 : 0);
    }

    private static boolean plainHead(ScoreNoteEvent note) {
        return plainHead(note, false);
    }

    private static boolean plainHead(ScoreNoteEvent note, boolean allowInternalTie) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.staffIndex() == 0
                && note.staffCount() == 1
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && note.augmentationDots() == 0
                && note.tupletDivisor() == 1
                && !note.crossStaffBeam()
                && (allowInternalTie || !note.tiedFromPrevious())
                && note.boundaryTies() == 0
                && note.leadingRestBeats() == 0
                && note.followingRestBeats() == 0
                && (note.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static boolean sameStaff(MeasureRegion a, MeasureRegion b, float gap, int height) {
        return Math.abs(a.top() - b.top()) * height <= gap
                && Math.abs(a.bottom() - b.bottom()) * height <= gap;
    }

    private static boolean boundaryAt(byte[] gray, int width, int height, float x, float hint, float gap) {
        var rails = DottedDyadMeasureSeparator.localRails(
                gray, width, height, Math.round(x), hint, gap);
        if (rails == null) return false;
        var staff = new PlayingTechniqueDetector.Staff(rails[0] - 4 * rails[1], rails[0],
                rails[1], 0, 1);
        for (int probe = Math.round(x - gap * .35f); probe <= x + gap * .35f; probe++) {
            if (unownedRule(gray, width, height, probe, staff)) return true;
        }
        return false;
    }

    private static int adjacentRule(byte[] gray, int width, int height, int first,
            PlayingTechniqueDetector.Staff staff) {
        int top = Math.round(staff.top());
        int start = first + Math.max(3, Math.round(staff.gap() * .24f));
        int limit = first + Math.round(staff.gap() * .85f);
        for (int x = start; x <= limit; x++) {
            if (!unownedRule(gray, width, height, x, staff)) continue;
            if (!DottedDyadMeasureSeparator.fullInk(gray, width, (first + x) / 2, top, staff.gap()))
                return x;
        }
        return -1;
    }

    private static boolean unownedRule(byte[] gray, int width, int height, int x,
            PlayingTechniqueDetector.Staff staff) {
        int top = Math.round(staff.top()), bottom = Math.round(staff.bottom());
        return DottedDyadMeasureSeparator.fullInk(gray, width, x, top, staff.gap())
                && OmrMeasurePostProcessor.verticalRuleAt(gray, width, x, top, bottom, staff.gap())
                && !DottedDyadMeasureSeparator.continuesBeyondStaff(
                        gray, width, height, x, top, bottom, staff.gap())
                && !ClosedHeadBarlineGuard.attached(gray, width, height, x, top, bottom, staff.gap());
    }
}
