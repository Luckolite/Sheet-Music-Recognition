// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Verifies three undotted, single-rail groups in a three-beat system fragment. */
final class WrappedEighthTriplets {
    private WrappedEighthTriplets() {}

    private static final class ShaftQuotaExceeded extends RuntimeException {}

    private record Ribbon(float coverage, int leftY, int rightY) {}

    private record RuleBand(int first, int[] rows, int radius) {
        boolean contains(int x, int y) {
            return Math.abs(y - rows[x - first]) <= radius;
        }
    }

    static WrappedTripletMeasure.Half half(byte[] gray, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> candidates) {
        return half(gray, width, height, bar, notes, candidates, null);
    }

    static WrappedTripletMeasure.Half half(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            List<ScoreNoteEvent> notes,
            List<Integer> candidates,
            byte[] labels) {
        if (candidates.size() != 9) return null;
        return singleRailGroups(gray, width, height, bar, notes, candidates, false, false, labels);
    }

    /** A closed six-triplet bar can precede a proved wrap even when a natural enters a dot window. */
    static WrappedTripletMeasure.Half closedNaturalPredecessor(
            byte[] gray, int width, int height, List<MeasureRegion> regions, int measure,
            List<ScoreNoteEvent> notes, List<Integer> candidates, byte[] labels) {
        if (labels == null || gray == null || labels.length != gray.length || measure < 1
                || measure + 1 >= regions.size() || candidates.size() != 18) return null;
        var bar = regions.get(measure);
        var before = regions.get(measure - 1);
        var after = regions.get(measure + 1);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float leftPocket = (bar.left() - before.right()) * width;
        float rightPocket = (after.left() - bar.right()) * width;
        if (gap < 10 || gap > 30
                || Math.abs(bar.top() - before.top()) * height > gap * .35f
                || Math.abs(bar.top() - after.top()) * height > gap * .35f
                || leftPocket < gap * .4f || leftPocket > gap * 1.6f
                || rightPocket < gap * .4f || rightPocket > gap * 1.6f) return null;
        var proof = singleRailGroups(gray, width, height, bar, notes, candidates, false, false, labels);
        if (proof == null) return null;
        var boundaries = new MeasureRegion((bar.left() + before.right()) * .5f,
                (bar.right() + after.left()) * .5f, bar.top(), bar.bottom());
        return WrappedMeasureBoundaries.confirmsClosedTripletBar(
                gray, width, height, boundaries, proof.x()[0], proof.x()[17]) ? proof : null;
    }

    /** Verify a reverse-facing mask on a genuine triplet's single rail in the following bar. */
    static boolean followingRailAlias(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            List<ScoreNoteEvent> notes,
            List<Integer> candidates) {
        if (candidates.size() != 19) return false;
        var ordered =
                candidates.stream()
                        .map(notes::get)
                        .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                        .toList();
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (!Float.isFinite(gap) || gap < 10 || gap > 30) return false;
        float left = width * bar.left(), span = width * (bar.right() - bar.left());
        int aliases = 0;
        var budget = new PrintedBeamShafts.Budget(250_000);
        for (int i = 2; i + 1 < ordered.size(); i++) {
            // The two flanks belong to the same group after dropping this mask from the count.
            if (i % 3 != 2) continue;
            var a = ordered.get(i - 2);
            var b = ordered.get(i - 1);
            var mask = ordered.get(i);
            var c = ordered.get(i + 1);
            if (a.stemDirection() != 1
                    || b.stemDirection() != 1
                    || c.stemDirection() != 1
                    || mask.stemDirection() != -1
                    || a.kind() != ScoreNoteEvent.Kind.PITCHED
                    || b.kind() != ScoreNoteEvent.Kind.PITCHED
                    || mask.kind() != ScoreNoteEvent.Kind.PITCHED
                    || c.kind() != ScoreNoteEvent.Kind.PITCHED
                    || a.beamCount() != 1
                    || b.beamCount() != 1
                    || c.beamCount() != 1
                    || mask.beamCount() != 1
                    || mask.augmentationDots() != 0
                    || mask.tiedFromPrevious()
                    || mask.boundaryTies() != 0
                    || mask.staffCount() != 1) continue;

            float ax = left + span * a.positionInMeasure(),
                    bx = left + span * b.positionInMeasure();
            float mx = left + span * mask.positionInMeasure(),
                    cx = left + span * c.positionInMeasure();
            float ay = a.pageY() * height, by = b.pageY() * height, my = mask.pageY() * height;
            float cy = c.pageY() * height;
            if (bx - ax < gap * 1.55f
                    || bx - ax > gap * 3.25f
                    || cx - bx < gap * 1.55f
                    || cx - bx > gap * 3.25f
                    || mx - bx < gap * .68f
                    || mx - bx > gap * 1.15f
                    || cx - mx < gap * 1.25f
                    || cx - mx > gap * 2.15f
                    || !(my + gap * 1.6f < cy && cy + gap * .60f < by && by + gap * .60f < ay))
                continue;
            var first = PrintedBeamShafts.at(gray, width, height, ax, ay, gap, budget);
            var last = PrintedBeamShafts.at(gray, width, height, cx, cy, gap, budget);
            if (first.size() != 1
                    || last.size() != 1
                    || first.get(0).direction() != -1
                    || last.get(0).direction() != -1
                    || !matchesRailBridge(
                            gray, width, height, first.get(0), last.get(0), gap, true)) continue;
            var ribbon = bestRibbon(gray, width, height, first.get(0), last.get(0), gap);
            float start = (first.get(0).left() + first.get(0).right()) * .5f;
            float end = (last.get(0).left() + last.get(0).right()) * .5f;
            if (start >= mx || mx >= end) continue;
            float railY =
                    ribbon.leftY()
                            + (ribbon.rightY() - ribbon.leftY()) * (mx - start) / (end - start);
            if (Math.abs(railY - my) <= gap * .40f && ++aliases > 1) return false;
        }
        return aliases == 1;
    }

    /** Printed groups of three eighths fill a physically closed bar with a quarter-note beat. */
    static List<ScoreNoteEvent> closeQuarterBeatBars(
            List<ScoreNoteEvent> notes,
            List<ScoreRestEvent> rests,
            List<MeasureRegion> regions,
            List<ScoreMeterChange> meters,
            byte[] gray,
            int width,
            int height) {
        return closeQuarterBeatBars(notes, rests, regions, meters, gray, width, height, null);
    }

    static List<ScoreNoteEvent> closeQuarterBeatBars(
            List<ScoreNoteEvent> notes,
            List<ScoreRestEvent> rests,
            List<MeasureRegion> regions,
            List<ScoreMeterChange> meters,
            byte[] gray,
            int width,
            int height,
            byte[] labels) {
        return closeQuarterBeatBars(notes, rests, regions, meters, gray, width, height, labels, null);
    }

    static List<ScoreNoteEvent> closeQuarterBeatBars(
            List<ScoreNoteEvent> notes, List<ScoreRestEvent> rests, List<MeasureRegion> regions,
            List<ScoreMeterChange> meters, byte[] gray, int width, int height,
            byte[] labels, byte[] original) {
        return closeQuarterBeatBars(notes, rests, regions, meters, gray, width, height, labels, original, null);
    }

    static List<ScoreNoteEvent> closeQuarterBeatBars(
            List<ScoreNoteEvent> notes, List<ScoreRestEvent> rests, List<MeasureRegion> regions,
            List<ScoreMeterChange> meters, byte[] gray, int width, int height,
            byte[] labels, byte[] original, List<Integer> neighborRecoveredBars) {
        if (meters.isEmpty() || notes.isEmpty()) return notes;
        boolean hasOriginal = original != null && original.length == gray.length;
        var bars = new ArrayList<List<Integer>>(regions.size());
        for (int i = 0; i < regions.size(); i++) bars.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) bars.get(notes.get(i).measureIndex()).add(i);
        var result = notes;
        int next = 0;
        ScoreMeterChange meter = null;
        List<ScoreMeterChange> literalMeters = null;
        List<ScoreMeterChange> originalMeters = null;
        var withRests = new java.util.BitSet(regions.size());
        for (var rest : rests) withRests.set(rest.measureIndex());
        for (int m = 0; m < regions.size(); m++) {
            while (next < meters.size() && meters.get(next).measureIndex() <= m)
                meter = meters.get(next++);
            var owners = bars.get(m);
            if (meter == null
                    || meter.denominator() != 4
                    || meter.numerator() != 4 && meter.numerator() != 6
                    || withRests.get(m)
                    || owners.size() != 3 * meter.numerator()
                    || owners.stream().map(notes::get).allMatch(n -> n.tupletDivisor() == 3))
                continue;
            var bar = regions.get(m);
            if (meter.numerator() == 4 && hasOriginal && owners.stream().map(notes::get)
                    .anyMatch(note -> note.beamCount() == 2 || note.augmentationDots() != 0)) {
                boolean literalOpening = meter.measureIndex() == m;
                var common = proveCommonTimeSingleRails(notes, owners, regions, m, gray, original, width, height,
                        false, literalOpening);
                if (common != null) {
                    if (neighborRecoveredBars != null && (literalOpening
                            || WrappedMeasureBoundaries.verifiedTripletStaff(gray, width, height, bar) == null))
                        neighborRecoveredBars.add(m);
                    if (result == notes) result = new ArrayList<>(notes);
                    for (int owner : owners) result.set(owner, common.get(owner).withTupletRatio(3, 2));
                    continue;
                }
            }
            boolean canUseHeader =
                    meter.numerator() == 6 && labels != null && labels.length == gray.length;
            boolean canUseOpening = m == 0 && canUseHeader;
            List<ScoreNoteEvent> proofNotes = notes;
            if (m > 0 && canUseHeader && hasOriginal && bars.get(m - 1).size() == 18
                    && bars.get(m - 1).stream().map(result::get)
                            .allMatch(n -> n.beamCount() == 1 && n.tupletDivisor() == 3
                                    && n.tupletNormalNotes() == 2)) {
                var following = proveNaturalNeighbor(
                        gray, original, labels, width, height, regions, m, notes, owners);
                if (following != null) {
                    if (result == notes) result = new ArrayList<>(notes);
                    for (int i : following.group().owners()) {
                        var restored = following.notes().get(i);
                        result.set(i, ClosingTripletRhythm.singleBeam(restored).withTupletRatio(3, 2));
                    }
                    continue;
                }
            }
            if (m > 0 && canUseHeader && hasOriginal && meter.measureIndex() == m
                    && (bar.top() - regions.get(m - 1).bottom()) * height
                            > (bar.bottom() - bar.top()) * height / 4.1f) {
                var restored = faintTerminalOpening(
                        gray, original, labels, width, height, regions, bar, meter, notes, owners);
                if (restored != null) {
                    if (result == notes) result = new ArrayList<>(notes);
                    for (int owner : owners) result.set(owner, restored.get(owner).withTupletRatio(3, 2));
                    continue;
                }
            }
            boolean recoverEndpoint = owners.stream().anyMatch(i -> notes.get(i).beamCount() != 1);
            if (recoverEndpoint && hasOriginal && meter.numerator() == 6) {
                var aliases = owners.stream().filter(i -> notes.get(i).beamCount() != 1).toList();
                if (aliases.size() == 1 && notes.get(aliases.get(0)).beamCount() == 2
                        && (canUseOpening
                                ? openingSingleRailCorrection(
                                        gray, original, labels, width, height, regions, notes, owners, meter)
                                : closedSingleRailCorrection(
                                        gray, original, width, height, bar, notes, owners, null, false))) {
                    if (result == notes) result = new ArrayList<>(notes);
                    int alias = aliases.get(0);
                    for (int i : owners) {
                        var restored = i == alias ? ClosingTripletRhythm.singleBeam(notes.get(i)) : notes.get(i);
                        result.set(i, restored.withTupletRatio(3, 2));
                    }
                    continue;
                }
            }
            if (m > 0 && hasOriginal && canUseHeader) {
                var beamAliases = owners.stream().filter(i -> notes.get(i).beamCount() != 1).toList();
                if (beamAliases.isEmpty()
                        || beamAliases.size() == 1 && notes.get(beamAliases.get(0)).beamCount() == 2) {
                    var bounded = closedSixFourSingleRails(gray, original, width, height, bar, notes, owners);
                    if (bounded) {
                        if (literalMeters == null)
                            literalMeters = MeterFontMatcher.standaloneReadings(
                                    labels, gray, width, height, regions, notes);
                        if (originalMeters == null)
                            originalMeters = MeterFontMatcher.standaloneReadings(
                                    labels, original, width, height, regions, notes);
                        if (literalMeters.contains(meter) && originalMeters.contains(meter)) {
                            if (result == notes) result = new ArrayList<>(notes);
                            for (int owner : owners) {
                                var note = notes.get(owner);
                                if (note.beamCount() != 1) note = ClosingTripletRhythm.singleBeam(note);
                                result.set(owner, note.withTupletRatio(3, 2));
                            }
                            continue;
                        }
                    }
                }
            }
            if (recoverEndpoint) {
                if (!canUseOpening) continue;
                int lastOwner = owners.stream()
                        .max(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure()))
                        .orElseThrow();
                var last = notes.get(lastOwner);
                if (last.beamCount() != 0
                        || last.unbeamedDurationBeats() != ScoreNoteEvent.DURATION_QUARTER
                        || last.tupletDivisor() != 1
                        || last.stemDirection() != -1
                        || owners.stream().anyMatch(i -> i != lastOwner && notes.get(i).beamCount() != 1))
                    continue;
                proofNotes = new ArrayList<>(notes);
                proofNotes.set(lastOwner, ClosingTripletRhythm.singleBeam(last));
            }
            var proof = singleRailGroups(gray, width, height, bar, proofNotes, owners, true);
            if (proof == null && m > 0 && hasOriginal && meter.numerator() == 6 && !recoverEndpoint
                    && owners.stream().map(notes::get)
                            .anyMatch(n -> n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FLAT)
                    && closedSingleRailCorrection(gray, original, width, height, bar, notes, owners, labels, true)) {
                if (result == notes) result = new ArrayList<>(notes);
                for (int owner : owners) result.set(owner, notes.get(owner).withTupletRatio(3, 2));
                continue;
            }
            boolean numberedOpening = false;
            if (proof == null && canUseOpening && hasOriginal && !recoverEndpoint) {
                proof = thinStaffOpening(gray, original, labels, width, height, regions, proofNotes, owners, meter);
                numberedOpening = proof != null;
            }
            if (proof == null && canUseOpening && hasOriginal && !recoverEndpoint) {
                var opening = clearBorrowedOpeningStaccatos(
                        bar, proofNotes, owners, labels, original, width, height);
                if (opening != proofNotes) {
                    var verified = singleRailGroups(gray, width, height, bar, opening, owners, true);
                    if (verified != null
                            && singleRailGroups(original, width, height, bar, opening, owners, true) != null
                            && WrappedMeasureBoundaries.confirmsOpeningTripletBar(
                                    original, width, height, regions, verified.x()[0],
                                    verified.x()[owners.size() - 1], true)
                            && MeterFontMatcher.standaloneReadings(
                                    labels, original, width, height, List.of(bar),
                                    owners.stream().map(notes::get).toList()).equals(List.of(meter))) {
                        numberedOpening = true;
                        proof = verified;
                        proofNotes = opening;
                    }
                }
            }
            if (proof == null && m > 0 && hasOriginal && meter.numerator() == 6 && !recoverEndpoint) {
                var closedStaff = singleRailGroups(
                        gray, width, height, bar, proofNotes, owners, true, false, null, true);
                if (closedStaff != null && singleRailGroups(
                                original, width, height, bar, proofNotes, owners, true, false, null, true) != null
                        && WrappedMeasureBoundaries.confirmsClosedTripletBar(
                                gray, width, height, bar, closedStaff.x()[0], closedStaff.x()[17])
                        && WrappedMeasureBoundaries.confirmsClosedTripletBar(
                                original, width, height, bar, closedStaff.x()[0], closedStaff.x()[17])) {
                    if (result == notes) result = new ArrayList<>(notes);
                    for (int owner : owners) result.set(owner, notes.get(owner).withTupletRatio(3, 2));
                    continue;
                }
            }
            if (proof == null && hasOriginal && meter.numerator() == 4 && owners.size() == 12
                    && owners.stream().map(notes::get).anyMatch(n -> n.tupletDivisor() == 3)) {
                proof = proveStaffRuleAndFlatTriplets(notes, owners, regions, m, gray, original, width, height);
                if (proof != null && neighborRecoveredBars != null) neighborRecoveredBars.add(m);
            }
            if (proof == null) continue;
            float firstHead = proof.x()[0], lastHead = proof.x()[owners.size() - 1];
            boolean closed = numberedOpening || WrappedMeasureBoundaries.confirmsClosedTripletBar(
                    gray, width, height, bar, firstHead, lastHead);
            if (!closed && m > 0 && meter.numerator() == 6 && !recoverEndpoint && hasOriginal)
                closed = WrappedMeasureBoundaries.confirmsPocketClosedTripletBar(
                                gray, width, height, regions.get(m - 1), bar, firstHead, lastHead)
                        && WrappedMeasureBoundaries.confirmsPocketClosedTripletBar(
                                original, width, height, regions.get(m - 1), bar, firstHead, lastHead)
                        && singleRailGroups(original, width, height, bar, proofNotes, owners, true) != null;
            if (recoverEndpoint || !closed) {
                if (canUseOpening) {
                    if (!WrappedMeasureBoundaries.confirmsOpeningTripletBar(
                                    gray,
                                    width,
                                    height,
                                    regions,
                                    firstHead,
                                    lastHead,
                                    recoverEndpoint)
                            || !MeterFontMatcher.standaloneReadings(
                                            labels,
                                            gray,
                                            width,
                                            height,
                                            List.of(bar),
                                            owners.stream().map(notes::get).toList())
                                    .equals(List.of(meter))) continue;
                } else {
                    // A physically new staff can introduce its own 6/4 without a left divider.
                    if (!canUseHeader
                            || m == 0
                            || meter.measureIndex() != m
                            || bar.top() <= regions.get(m - 1).bottom()
                            || !WrappedMeasureBoundaries.confirmsOpeningTripletBar(
                                    gray,
                                    width,
                                    height,
                                    regions.subList(m, regions.size()),
                                    firstHead,
                                    lastHead,
                                    false)) continue;
                    if (literalMeters == null)
                        literalMeters =
                                MeterFontMatcher.standaloneReadings(
                                        labels, gray, width, height, regions, notes);
                    if (!literalMeters.contains(meter)) continue;
                }
            }
            if (result == notes) result = new ArrayList<>(notes);
            for (int i : owners) {
                var n = proofNotes.get(i);
                if (n.tupletDivisor() != 3) n = n.withTupletRatio(3, 2);
                if (n != notes.get(i)) result.set(i, n);
            }
        }
        return result == notes ? notes : List.copyOf(result);
    }

    private static boolean provesStaffOverlaidOpeningSixFour(byte[] gray, byte[] raw, byte[] labels,
            int width, int height, List<MeasureRegion> bars, int index, ScoreMeterChange meter,
            List<ScoreNoteEvent> notes, List<Integer> owners) {
        var opening = bars.get(index);
        var candidate = singleRailGroups(
                gray, width, height, opening, notes, owners, true, false, null, true, null, true);
        if (candidate == null || singleRailGroups(
                raw, width, height, opening, notes, owners, true, false, null, true, null, true) == null)
            return false;
        var following = bars.subList(index, bars.size());
        return WrappedMeasureBoundaries.confirmsOpeningTripletBar(
                        gray, width, height, following, candidate.x()[0], candidate.x()[17], false)
                && WrappedMeasureBoundaries.confirmsOpeningTripletBar(
                        raw, width, height, following, candidate.x()[0], candidate.x()[17], false)
                && MeterFontMatcher.standaloneReadings(labels, gray, width, height, bars, notes).contains(meter)
                && MeterFontMatcher.standaloneReadings(labels, raw, width, height, bars, notes).contains(meter);
    }

    /** A local faded bridge at the last stem can obscure an otherwise closed six-quarter phrase. */
    private static List<ScoreNoteEvent> faintTerminalOpening(byte[] gray, byte[] raw, byte[] labels,
            int w, int h, List<MeasureRegion> bars, MeasureRegion bar, ScoreMeterChange meter,
            List<ScoreNoteEvent> notes, List<Integer> owners) {
        var ordered = owners.stream().sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure()))
                .toList();
        int last = ordered.get(ordered.size() - 1);
        if (notes.get(last).stemDirection() != 0
                || ordered.subList(0, 17).stream().map(notes::get).anyMatch(n -> n.stemDirection() == 0)
                || notes.get(ordered.get(15)).stemDirection() != -1
                || notes.get(ordered.get(16)).stemDirection() != -1) return null;
        var restored = new ArrayList<>(notes);
        restored.set(last, notes.get(last).withStemDirection(-1));
        var first = singleRailGroups(gray, w, h, bar, restored, ordered, true, false, null,
                false, null, false, false, true);
        var second = singleRailGroups(raw, w, h, bar, restored, ordered, true, false, null,
                false, null, false, false, true);
        if (first == null || second == null
                || !WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(gray, w, h, bar, first.x()[17])
                || !WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(raw, w, h, bar, first.x()[17])
                || !MeterFontMatcher.standaloneReadings(labels, gray, w, h, bars, notes).contains(meter)
                || !MeterFontMatcher.standaloneReadings(labels, raw, w, h, bars, notes).contains(meter)) return null;
        return restored;
    }

    /** An opening 6/4 takes the place of the left divider for one beam-count correction. */
    private static boolean openingSingleRailCorrection(
            byte[] gray,
            byte[] original,
            byte[] labels,
            int width,
            int height,
            List<MeasureRegion> regions,
            List<ScoreNoteEvent> notes,
            List<Integer> owners,
            ScoreMeterChange meter) {
        var bar = regions.get(0);
        var proof = singleRailGroups(gray, width, height, bar, notes, owners, true);
        if (proof == null
                || singleRailGroups(original, width, height, bar, notes, owners, true) == null)
            return false;
        float first = proof.x()[0], last = proof.x()[owners.size() - 1];
        var attacks = owners.stream().map(notes::get).toList();
        return WrappedMeasureBoundaries.confirmsOpeningTripletBar(
                        gray, width, height, regions, first, last, false)
                && WrappedMeasureBoundaries.confirmsOpeningTripletBar(
                        original, width, height, regions, first, last, false)
                && MeterFontMatcher.standaloneReadings(
                                labels, gray, width, height, List.of(bar), attacks).equals(List.of(meter))
                && MeterFontMatcher.standaloneReadings(
                                labels, original, width, height, List.of(bar), attacks).equals(List.of(meter));
    }

    /** Treat an intervening thin rule as staff ink only with two independent beam proofs. */
    private static boolean closedSixFourSingleRails(
            byte[] gray, byte[] original, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> owners) {
        for (boolean staffOverlap : new boolean[] {false, true}) {
            var proof = singleRailGroups(
                    gray, width, height, bar, notes, owners, true, false, null, true, null, staffOverlap);
            if (proof == null
                    || singleRailGroups(original, width, height, bar, notes, owners,
                            true, false, null, true, null, staffOverlap) == null) continue;
            float first = proof.x()[0], last = proof.x()[owners.size() - 1];
            if (WrappedMeasureBoundaries.confirmsClosedTripletBar(gray, width, height, bar, first, last)
                    && WrappedMeasureBoundaries.confirmsClosedTripletBar(
                            original, width, height, bar, first, last)) return true;
        }
        return false;
    }

    private static boolean closedSingleRailCorrection(byte[] gray, byte[] original, int width, int height,
            MeasureRegion bar, List<ScoreNoteEvent> notes, List<Integer> owners,
            byte[] labels, boolean allowSingleFlat) {
        var proof = singleRailGroups(gray, width, height, bar, notes, owners, true, allowSingleFlat, labels);
        if (proof == null || singleRailGroups(
                original, width, height, bar, notes, owners, true, allowSingleFlat, labels) == null) return false;
        float first = proof.x()[0], last = proof.x()[owners.size() - 1];
        return WrappedMeasureBoundaries.confirmsClosedTripletBar(gray, width, height, bar, first, last)
                && WrappedMeasureBoundaries.confirmsClosedTripletBar(
                        original, width, height, bar, first, last);
    }

    /** A detached triplet numeral cannot also supply a rest or an inverted fermata. */
    static OpeningNumeralRestClosure closeOpeningSixFourNumeralRest(
            ScorePageInterpretation score, byte[] labels, byte[] gray, byte[] raw,
            int width, int height) {
        if (raw == null || gray == null || labels == null
                || raw.length != (long) width * height || gray.length != raw.length
                || labels.length != raw.length) return new OpeningNumeralRestClosure(score, List.of());
        var bars = score.measures();
        var notes = score.notes();
        var closedRests = new java.util.HashSet<ScoreRestEvent>();
        var numeralOwners = new ArrayList<OpeningNumeralInk>();
        var corrected = notes;
        for (var meter : score.meterChanges()) {
            int barIndex = meter.measureIndex();
            if (barIndex <= 0 || barIndex + 1 >= bars.size()
                    || meter.numerator() != 6 || meter.denominator() != 4) continue;
            var bar = bars.get(barIndex);
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (gap < 10 || gap > 28 || bar.cadenceQuarterBeats() != 0
                    || (bar.top() - bars.get(barIndex - 1).bottom()) * height < gap * 2)
                continue;
            var rests = score.rests().stream().filter(r -> r.measureIndex() == barIndex).toList();
            if (rests.size() > 1) continue;
            var rest = rests.isEmpty() ? null : rests.get(0);
            if (rest != null && (rest.kind() != ScoreRestEvent.Kind.LITERAL || rest.durationBeats() != .5
                    || rest.staffIndex() != 0 || rest.staffCount() != 1)) continue;
            var owners = java.util.stream.IntStream.range(0, notes.size())
                    .filter(i -> notes.get(i).measureIndex() == barIndex)
                    .boxed()
                    .sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure()))
                    .toList();
            if (owners.size() != 18 || owners.stream().map(notes::get)
                    .anyMatch(n -> n.beamCount() != 1)) continue;
            float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
            var middle = notes.get(owners.get(1));
            float middleX = left + span * middle.positionInMeasure(), middleY = middle.pageY() * height;
            float lowestY = Math.max(notes.get(owners.get(0)).pageY(), notes.get(owners.get(2)).pageY()) * height;
            RawNumeralComponents.Component firstGlyph, secondGlyph;
            if (rest == null) {
                firstGlyph = firstTripletNumeral(gray, width, height, middleX, middleY, lowestY, gap);
                secondGlyph = firstTripletNumeral(raw, width, height, middleX, middleY, lowestY, gap);
            } else {
                float restX = left + span * rest.positionInMeasure(), restY = rest.pageY() * height;
                if (Math.abs(restX - middleX) > gap * .23f
                        || restY - middleY < gap * 1.85f || restY - middleY > gap * 3.15f
                        || Math.max(lowestY, middleY) > restY - gap * .9f) continue;
                firstGlyph = detachedTripletThree(gray, width, height, restX, restY, gap);
                secondGlyph = detachedTripletThree(raw, width, height, restX, restY, gap);
            }
            if (firstGlyph == null || secondGlyph == null
                    || Math.abs(firstGlyph.left - secondGlyph.left) > 1
                    || Math.abs(firstGlyph.right - secondGlyph.right) > 1
                    || Math.abs(firstGlyph.top - secondGlyph.top) > 1
                    || Math.abs(firstGlyph.bottom - secondGlyph.bottom) > 1) continue;
            if (rest == null) {
                if (!provesStaffOverlaidOpeningSixFour(
                        gray, raw, labels, width, height, bars, barIndex, meter, notes, owners))
                    continue;
            } else {
                var first = singleRailGroups(gray, width, height, bar, notes, owners, true);
                var second = singleRailGroups(raw, width, height, bar, notes, owners, true);
                if (first == null || second == null
                        || !WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(
                                gray, width, height, bar, first.x()[17])
                        || !WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(
                                raw, width, height, bar, first.x()[17])
                        || !MeterFontMatcher.standaloneReadings(labels, gray, width, height, bars, notes).contains(meter)
                        || !MeterFontMatcher.standaloneReadings(labels, raw, width, height, bars, notes).contains(meter))
                    continue;
            }
            if (corrected == notes) corrected = new ArrayList<>(notes);
            for (int owner : owners) corrected.set(owner, corrected.get(owner).withTupletRatio(3, 2));
            if (rest != null) closedRests.add(rest);
            numeralOwners.add(new OpeningNumeralInk(barIndex, middle.positionInMeasure(),
                    firstGlyph.left - gap * .25f, firstGlyph.right + gap * .13f,
                    firstGlyph.top, firstGlyph.bottom));
        }
        if (!closedRests.isEmpty())
            score = score.withRests(score.rests().stream().filter(r -> !closedRests.contains(r)).toList());
        if (corrected != notes)
            score = score.withNotes(corrected, java.util.stream.IntStream.range(0, notes.size()).boxed().toList());
        return new OpeningNumeralRestClosure(score, List.copyOf(numeralOwners));
    }

    private static RawNumeralComponents.Component firstTripletNumeral(byte[] gray, int width, int height,
            float middleX, float middleY, float lowestY, float gap) {
        int left = Math.round(middleX - gap * 1.12f), right = Math.round(middleX + gap * 1.16f);
        int top = Math.round(middleY + gap * .6f), bottom = Math.round(middleY + gap * 3.1f);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return null;
        var components = RawNumeralComponents.capture(
                gray, width, height, left, top, right, bottom, 155, () -> {}, () -> {});
        var candidates = components.components().stream()
                .filter(c -> c.left > left && c.right < right && c.top > top && c.bottom < bottom)
                .filter(c -> Math.abs((c.left + c.right) * .5f - middleX) <= gap * .23f
                        && (c.top + c.bottom) * .5f > Math.max(lowestY, middleY) + gap * .16f)
                .filter(c -> c.right - c.left + 1 >= gap * .6f && c.right - c.left + 1 <= gap * 1.14f
                        && c.bottom - c.top + 1 >= gap * 1.06f && c.bottom - c.top + 1 <= gap * 1.58f)
                .filter(c -> TripletRhythmDetector.isIsolatedThree(c, width))
                .toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private record OpeningNumeralInk(
            int measure, float notePosition, float left, float right, float top, float bottom) {
        boolean owns(ScoreNoteEvent n, NoteArticulationDetector.FermataMark mark) {
            return mark.inverted() && n.measureIndex() == measure && n.positionInMeasure() == notePosition
                    && mark.x() >= left && mark.x() <= right && mark.y() >= top && mark.y() <= bottom;
        }
    }

    record OpeningNumeralRestClosure(ScorePageInterpretation score, List<OpeningNumeralInk> numeralInk) {
        boolean ownsFermataInk(ScoreNoteEvent note, NoteArticulationDetector.FermataMark mark) {
            return numeralInk.stream().anyMatch(owned -> owned.owns(note, mark));
        }
    }

    private static RawNumeralComponents.Component detachedTripletThree(
            byte[] gray, int width, int height, float x, float y, float gap) {
        int left = Math.round(x - gap * 1.1f), right = Math.round(x + gap * 1.1f);
        int top = Math.round(y - gap * .95f), bottom = Math.round(y + gap * 1.14f);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return null;
        var components = RawNumeralComponents.capture(
                gray, width, height, left, top, right, bottom, 155, () -> {}, () -> {});
        var numerals = components.components().stream()
                .filter(c -> c.left > left && c.right < right && c.top > top && c.bottom < bottom)
                .filter(c -> x >= c.left && x <= c.right && y >= c.top && y <= c.bottom)
                .filter(c -> Math.abs((c.left + c.right) * .5f - x) <= gap * .16f
                        && Math.abs((c.top + c.bottom) * .5f - y) <= gap * .17f)
                .filter(c -> c.right - c.left + 1 >= gap * .6f && c.right - c.left + 1 <= gap * 1.14f
                        && c.bottom - c.top + 1 >= gap * 1.06f && c.bottom - c.top + 1 <= gap * 1.58f)
                .filter(c -> TripletRhythmDetector.isIsolatedThree(c, width))
                .toList();
        return numerals.size() == 1 ? numerals.get(0) : null;
    }

    record NumeralAliasBars(ScorePageInterpretation score, List<Integer> repairedBars) {}

    static NumeralAliasBars closeNumeralAliasBars(
            ScorePageInterpretation score, List<PlayingTechniqueDetector.Word> words,
            byte[] normalized, byte[] raw, int width, int height) {
        if (raw == null || normalized == null || raw.length != normalized.length
                || raw.length != (long) width*height || words.isEmpty()
                || score.meterChanges().isEmpty()) return new NumeralAliasBars(score, List.of());
        var notes = score.notes();
        var measures = score.measures();
        var owners = new ArrayList<List<Integer>>(measures.size());
        for (int i = 0; i < measures.size(); i++) owners.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) owners.get(notes.get(i).measureIndex()).add(i);
        var removed = new java.util.BitSet(notes.size());
        var repaired = new ArrayList<Integer>();
        var restBars = new java.util.BitSet(measures.size());
        for (var rest : score.rests()) restBars.set(rest.measureIndex());
        var changed = new ArrayList<>(notes);
        int nextMeter = 0;
        ScoreMeterChange meter = null;
        for (int index = 0; index < measures.size(); index++) {
            var group = owners.get(index);
            while (nextMeter < score.meterChanges().size() && score.meterChanges().get(nextMeter).measureIndex() <= index)
                meter = score.meterChanges().get(nextMeter++);
            if (meter == null || meter.numerator() != 4 || meter.denominator() != 4
                    || restBars.get(index) || group.size() != 13) continue;
            var bar = measures.get(index);
            var ghosts = group.stream().filter(i -> FingeringAnnotationFilter.beamedNumeralAlias(
                    words, bar, notes.get(i), raw, normalized, width, height)).toList();
            if (ghosts.size() != 1) continue;
            int ghost = ghosts.get(0);
            var surviving = group.stream().filter(i -> i != ghost).toList();
            var proved = proveCommonTimeSingleRails(notes, surviving, measures, index, normalized, raw, width, height);
            if (proved == null && notes.get(ghost).staffStep() == -2)
                proved = proveCommonTimeSingleRails(notes, surviving, measures, index, normalized, raw, width, height, true);
            if (proved == null) continue;
            for (int owner : surviving) changed.set(owner, proved.get(owner).withTupletRatio(3, 2));
            removed.set(ghost);
            repaired.add(index);
        }
        if (removed.isEmpty()) return new NumeralAliasBars(score, List.of());
        var retained = new ArrayList<ScoreNoteEvent>();
        var sourceOwners = new ArrayList<Integer>();
        for (int i = 0; i < notes.size(); i++) {
            if (removed.get(i)) continue;
            retained.add(changed.get(i));
            sourceOwners.add(i);
        }
        return new NumeralAliasBars(score.withNotes(retained, sourceOwners), List.copyOf(repaired));
    }

    static List<ScoreNoteEvent> proveCommonTimeSingleRails(
            List<ScoreNoteEvent> notes, List<Integer> owners, List<MeasureRegion> regions,
            int index, byte[] gray, byte[] original, int width, int height) {
        return proveCommonTimeSingleRails(notes, owners, regions, index, gray, original, width, height, false);
    }

    private static List<ScoreNoteEvent> proveCommonTimeSingleRails(
            List<ScoreNoteEvent> notes, List<Integer> owners, List<MeasureRegion> regions,
            int index, byte[] gray, byte[] original, int width, int height, boolean staffOverlap) {
        return proveCommonTimeSingleRails(notes, owners, regions, index, gray, original, width, height,
                staffOverlap, false);
    }

    private static List<ScoreNoteEvent> proveCommonTimeSingleRails(
            List<ScoreNoteEvent> notes, List<Integer> owners, List<MeasureRegion> regions,
            int index, byte[] gray, byte[] original, int width, int height, boolean staffOverlap,
            boolean meterChange) {
        var bar = regions.get(index);
        if (owners.size() != 12 || bar.cadenceQuarterBeats() != 0) return null;
        var order = owners.stream()
                .sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure())).toList();
        float left = bar.left() * width, right = bar.right() * width, span = right - left;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25) return null;
        var normalized = new ArrayList<>(notes);
        int railAliases = 0, dotAliases = 0;
        for (int i = 0; i < order.size(); i++) {
            int owner = order.get(i);
            var note = notes.get(owner);
            if (note.beamCount() == 2) {
                if (++railAliases > 1) return null;
                note = ClosingTripletRhythm.singleBeam(note);
            }
            if (note.augmentationDots() != 0) {
                if (++dotAliases > 1 || note.augmentationDots() != 1) return null;
                float x = left + span * note.positionInMeasure(), y = note.pageY() * height;
                float next = i + 1 < order.size()
                        ? left + span * notes.get(order.get(i + 1)).positionInMeasure() : right;
                var dot = WrappedTripletDotInk.uniqueCore(gray, width, height, x, y, next, gap);
                var originalDot = WrappedTripletDotInk.uniqueCore(original, width, height, x, y, next, gap);
                if (dot != null || originalDot != null) {
                    if (!staffOverlap || dot == null || !dot.equals(originalDot)
                            || !headConnectedLedger(gray, width, height, x, y, dot, gap)
                            || !headConnectedLedger(original, width, height, x, y, originalDot, gap))
                        return null;
                }
                note = withoutDots(note);
            }
            normalized.set(owner, note);
        }
        var read = singleRailGroups(
                gray, width, height, bar, normalized, order, true, false, null, false, null, staffOverlap);
        var retained = singleRailGroups(
                original, width, height, bar, normalized, order, true, false, null, false, null, staffOverlap);
        if (read == null || retained == null) return null;
        float first = read.x()[0], last = read.x()[11];
        if (commonTimeTripletBoundary(gray, width, height, regions, index, first, last)
                && commonTimeTripletBoundary(original, width, height, regions, index, first, last))
            return normalized;
        if (!meterChange || index == 0
                || (bar.top() - regions.get(index - 1).bottom()) * height < gap * 3) return null;
        float bottom = bar.bottom() * height - gap * 2.1f;
        for (byte[] ink : new byte[][]{gray, original}) {
            var staff = StaffPitchTrack.forPrintedSlurs(ink, width, height, bottom - gap * 4, bottom, gap);
            if (staff == null || !staff.verifiedAt(first) || !staff.verifiedAt(last)
                    || !WrappedMeasureBoundaries.confirmsOpeningFourFourWithVerifiedStaff(
                            ink, width, height, regions, index, first, last, staff)) return null;
        }
        return normalized;
    }

    private static boolean commonTimeTripletBoundary(byte[] gray, int w, int h,
            List<MeasureRegion> bars, int index, float first, float last) {
        var bar = bars.get(index);
        return WrappedMeasureBoundaries.confirmsClosedTripletBar(gray, w, h, bar, first, last)
                || index > 0 && WrappedMeasureBoundaries.confirmsPocketClosedTripletBar(
                        gray, w, h, bars.get(index - 1), bar, first, last)
                || WrappedMeasureBoundaries.confirmsNeighborBoundedTripletBar(
                        gray, w, h, bars, index, first, last);
    }

    private static WrappedTripletMeasure.Half proveStaffRuleAndFlatTriplets(
            List<ScoreNoteEvent> notes, List<Integer> owners, List<MeasureRegion> regions,
            int index, byte[] normalized, byte[] raw, int width, int height) {
        var bar = regions.get(index);
        var proof = singleRailGroups(normalized, width, height, bar, notes, owners,
                true, false, null, false, null, false, true);
        if (proof == null || singleRailGroups(raw, width, height, bar, notes, owners,
                        true, false, null, false, null, false, true) == null) return null;
        return WrappedMeasureBoundaries.confirmsClosedTripletBar(
                        raw, width, height, bar, proof.x()[0], proof.x()[11])
                && WrappedMeasureBoundaries.confirmsClosedTripletBar(
                        normalized, width, height, bar, proof.x()[0], proof.x()[11]) ? proof : null;
    }

    private record NaturalNeighbor(WrappedTripletMeasure.Half group, List<ScoreNoteEvent> notes) {}

    private static NaturalNeighbor proveNaturalNeighbor(
            byte[] gray, byte[] original, byte[] labels, int width, int height,
            List<MeasureRegion> regions, int index, List<ScoreNoteEvent> notes, List<Integer> owners) {
        var prior = regions.get(index - 1);
        var bar = regions.get(index);
        if (owners.size() != 18 || bar.top() > prior.bottom() || prior.right() >= bar.left()
                || bar.cadenceQuarterBeats() != 0) return null;
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 26) return null;
        var order = owners.stream().sorted(
                Comparator.comparingDouble(i -> notes.get(i).positionInMeasure())).toList();
        List<ScoreNoteEvent> cleaned = notes;
        for (int i = 0; i < order.size(); i++) {
            int owner = order.get(i);
            var note = notes.get(owner);
            if (note.augmentationDots() == 0) continue;
            if (note.augmentationDots() != 1 || i + 1 == order.size()) return null;
            float x = left + note.positionInMeasure() * span;
            float y = note.pageY() * height;
            float nextX = left + notes.get(order.get(i + 1)).positionInMeasure() * span;
            if (WrappedTripletDotInk.uniqueCore(gray, width, height, x, y, nextX, gap) != null
                    || WrappedTripletDotInk.uniqueCore(original, width, height, x, y, nextX, gap) != null)
                return null;
            if (cleaned == notes) cleaned = new ArrayList<>(notes);
            cleaned.set(owner, withoutDots(note));
        }
        var normalized = singleRailGroups(
                gray, width, height, bar, cleaned, owners, true, false, null, true, labels);
        if (normalized == null || singleRailGroups(
                original, width, height, bar, cleaned, owners, true, false, null, true, labels) == null)
            return null;
        float first = normalized.x()[0], last = normalized.x()[17];
        if (!WrappedMeasureBoundaries.confirmsPocketClosedTripletBar(
                        gray, width, height, prior, bar, first, last)
                || !WrappedMeasureBoundaries.confirmsPocketClosedTripletBar(
                        original, width, height, prior, bar, first, last)) return null;
        return new NaturalNeighbor(normalized, cleaned);
    }

    private static WrappedTripletMeasure.Half thinStaffOpening(
            byte[] gray, byte[] original, byte[] labels, int width, int height,
            List<MeasureRegion> regions, List<ScoreNoteEvent> notes, List<Integer> owners,
            ScoreMeterChange meter) {
        var bar = regions.get(0);
        var proof = singleRailGroups(gray, width, height, bar, notes, owners, true, false, null, true);
        if (proof == null
                || singleRailGroups(original, width, height, bar, notes, owners, true, false, null, true) == null)
            return null;
        float first = proof.x()[0], last = proof.x()[owners.size() - 1];
        if (!WrappedMeasureBoundaries.confirmsOpeningTripletBar(
                        gray, width, height, regions, first, last, true, true)
                || !WrappedMeasureBoundaries.confirmsOpeningTripletBar(
                        original, width, height, regions, first, last, true, true)
                || !MeterFontMatcher.standaloneReadings(
                        labels, original, width, height, List.of(bar), owners.stream().map(notes::get).toList())
                        .equals(List.of(meter))) return null;
        return proof;
    }

    private static List<ScoreNoteEvent> clearBorrowedOpeningStaccatos(
            MeasureRegion bar, List<ScoreNoteEvent> notes, List<Integer> indexes,
            byte[] labels, byte[] original, int width, int height) {
        var order = indexes.stream()
                .sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure())).toList();
        var first = notes.get(order.get(0));
        var middle = notes.get(order.get(1));
        var last = notes.get(order.get(2));
        if (first.augmentationDots() != 1 || middle.augmentationDots() != 1 || last.augmentationDots() != 0
                || first.stemDirection() != 1 || middle.stemDirection() != 1 || last.stemDirection() != 1
                || (first.articulations() & NoteArticulation.STACCATO) != 0
                || (middle.articulations() & NoteArticulation.STACCATO) == 0
                || (last.articulations() & NoteArticulation.STACCATO) == 0
                || indexes.stream().filter(i -> notes.get(i).augmentationDots() != 0).count() != 2) return notes;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float a = left + span * first.positionInMeasure(), b = left + span * middle.positionInMeasure();
        float c = left + span * last.positionInMeasure();
        float ay = first.pageY() * height, by = middle.pageY() * height, cy = last.pageY() * height;
        if (gap < 10 || gap > 25 || ay - by < gap * .7f || ay - by > gap * 1.3f
                || by - cy < gap * .7f || by - cy > gap * 1.3f
                || !OmrScoreInterpreter.centerOwnedLowerStaccato(original,width,height,b,by,gap,1.14f,1.62f,.68)
                || !OmrScoreInterpreter.centerOwnedLowerStaccato(original,width,height,c,cy,gap,1.14f,1.62f,.68))
            return notes;
        var dot = WrappedTripletDotInk.uniqueCore(original, width, height, a, ay, b, gap);
        if (dot != null && !headConnectedLedger(original, width, height, a, ay, dot, gap)) return notes;
        if (WrappedTripletDotInk.uniqueCore(original, width, height, b, by, c, gap) != null)
            return notes;
        if (!ownedOpeningNumeral(original, labels, width, height, b, by, gap)) return notes;
        var edited = new ArrayList<>(notes);
        edited.set(order.get(0), withoutDots(first));
        edited.set(order.get(1), withoutDots(middle));
        return edited;
    }

    static boolean ownedOpeningNumeral(
            byte[] original, byte[] labels, int width, int height, float middleX, float middleY, float gap) {
        var shaft = PrintedBeamShafts.at(original, width, height, middleX, middleY, gap,
                new PrintedBeamShafts.Budget(100_000));
        if (shaft.size() != 1 || shaft.get(0).direction() != -1) return false;
        int tip = shaft.get(0).endY();
        int left = Math.max(0, Math.round(middleX - 1.0f * gap));
        int right = Math.min(width - 1, Math.round(middleX + 1.10f * gap));
        int top = Math.max(0, Math.round(tip - 2.2f * gap));
        int bottom = Math.min(height - 1, Math.round(tip - .15f * gap));
        var ink = RawNumeralComponents.capture(original, width, height, left, top, right, bottom,
                () -> {}, () -> {});
        int numberCount = 0;
        for (var glyph : ink.components()) {
            if (glyph.top <= top || glyph.bottom >= bottom || glyph.left <= left || glyph.right >= right
                    || Math.abs((glyph.left + glyph.right) * .5f - middleX) > gap * .4f
                    || glyph.size() < gap * gap * .19f
                    || glyph.size() > gap * gap * .8f) continue;
            int[] pixels = java.util.stream.IntStream.range(0, glyph.size()).map(glyph::pixel).toArray();
            int symbol = 0;
            for (int pixel : pixels) if (labels[pixel] == OmrMeasurePostProcessor.SYMBOL) symbol++;
            if (symbol >= .45f * pixels.length && TupletNumeralBeamBand.detachedThree(
                    pixels, width, glyph.left, glyph.top, glyph.right, glyph.bottom, gap)) numberCount++;
        }
        return numberCount == 1;
    }

    static ScoreNoteEvent withoutDots(ScoreNoteEvent n) {
        return new ScoreNoteEvent(n.measureIndex(), n.positionInMeasure(), n.staffStep(),
                n.staffIndex(), n.staffCount(), n.pageY(), n.tiedFromPrevious(), 0, n.beamCount(),
                n.writtenAccidental(), n.unbeamedDurationBeats(), n.tupletDivisor(), n.followingRestBeats(),
                n.articulations(), n.clefBottomDiatonic(), n.crossStaffBeam(), n.leadingRestBeats(),
                n.compactOpening(), n.octaveShift(), n.boundaryTies(), n.tupletNormalNotes(),
                n.stemDirection(), n.kind(), n.visualSize(), n.harmonicSemitones(), n.openStringTouch());
    }

    /** Recognize six singly beamed triplets when a staff rule resembles the second rail. */
    static ScorePageInterpretation restoreStaffOverlaidSixFourTriplets(
            ScorePageInterpretation score,
            byte[] gray,
            byte[] original,
            int width,
            int height,
            List<PlayingTechniqueDetector.Word> words,
            int physicalPage) {
        if (gray == null
                || original == null
                || gray.length != (long) width * height
                || original.length != gray.length
                || score.meterChanges().isEmpty()
                || !score.meterChanges().get(0).equals(new ScoreMeterChange(0, 6, 4))
                || score.meterChanges().stream().anyMatch(m -> m.measureIndex() == 1)
                || score.measures().size() < 3
                || fullSixFourOwners(score, 0).isEmpty()
                || score.rests().stream().anyMatch(r -> r.measureIndex() == 1)) return score;
        var notes = score.notes();
        var owners =
                java.util.stream.IntStream.range(0, notes.size())
                        .filter(i -> notes.get(i).measureIndex() == 1)
                        .boxed()
                        .toList();
        var bar = score.measures().get(1);
        var preceding = score.measures().get(0);
        var following = score.measures().get(2);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (owners.size() != 18
                || !(gap >= 10 && gap <= 28)
                || bar.cadenceQuarterBeats() != 0
                || Math.abs(preceding.top() - bar.top()) * height > gap * .4f
                || Math.abs(following.top() - bar.top()) * height > gap * .4f
                || owners.stream().map(notes::get).anyMatch(n -> n.beamCount() != 1)
                || owners.stream().map(notes::get).allMatch(n -> n.tupletDivisor() == 3))
            return score;
        var proof =
                singleRailGroups(
                        gray, width, height, bar, notes, owners, true, false, null, false, null,
                        true);
        if (proof == null
                || singleRailGroups(
                                original, width, height, bar, notes, owners, true, false, null,
                                false, null, true)
                        == null) return score;
        float first = proof.x()[0], last = proof.x()[17];
        if (!WrappedMeasureBoundaries.confirmsClosedTripletBar(
                        gray, width, height, bar, first, last)
                || !WrappedMeasureBoundaries.confirmsClosedTripletBar(
                        original, width, height, bar, first, last)) return score;
        var changed = new ArrayList<>(notes);
        for (int owner : proof.owners()) changed.set(owner, notes.get(owner).withTupletRatio(3, 2));
        var result =
                score.withNotes(
                        changed,
                        java.util.stream.IntStream.range(0, notes.size()).boxed().toList());
        if (physicalPage < 0) return result;
        return projectStaffOverlaidGroups(
                result, proof, gray, original, width, height, words, physicalPage);
    }

    private static ScorePageInterpretation projectStaffOverlaidGroups(
            ScorePageInterpretation score,
            WrappedTripletMeasure.Half proof,
            byte[] gray,
            byte[] original,
            int width,
            int height,
            List<PlayingTechniqueDetector.Word> words,
            int physicalPage) {
        var projected = addPrintedSixFourGroups(score, proof, physicalPage, null);
        if (projected == score) return score;
        var source = projected.beamSource();
        var origins = source.noteOrigins();
        var groups = source.groups();
        var marked = new ArrayList<>(groups);
        var bar = score.measures().get(1);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        var rs = WrappedMeasureBoundaries.verifiedTripletStaff(original, width, height, bar);
        var cs = WrappedMeasureBoundaries.verifiedTripletStaff(gray, width, height, bar);
        var marks =
                OpeningTripletContextInk.withoutStaffCrossingCurve(
                        original, gray, width, height, proof.x()[0], proof.x()[17], gap, rs, cs);
        markNaturalNeighborGroups(
                projected,
                marked,
                proof,
                marks.corrected(),
                marks.original(),
                width,
                height,
                words);
        var interpreted = projected.withBeamSource(new ScoreBeamSource(marked, source.detachedColumns(), origins));
        int firstOwner = proof.owners().get(0), lastOwner = proof.owners().get(17);
        var first = score.notes().get(firstOwner);
        var last = score.notes().get(lastOwner);
        if (first.diatonicPitchIdentity() == last.diatonicPitchIdentity()) return interpreted;
        var arc = OpeningTripletContextInk.staffCrossingSlur(original, gray, width, height, physicalPage,
                proof.x()[0], first.pageY() * height, proof.x()[17], last.pageY() * height, gap, rs, cs);
        return arc == null ? interpreted : attachStaffCrossingSlur(interpreted, arc, firstOwner, lastOwner);
    }

    /** Recover unnumbered groups only when the whole closed 6/4 bar has literal single rails. */
    static ScorePageInterpretation projectClosedSixFourTripletBeams(
            ScorePageInterpretation score, byte[] normalized, byte[] original, int width, int height,
            List<PlayingTechniqueDetector.Word> words, int physicalPage) {
        if (physicalPage < 0 || original == null || normalized == null
                || original.length != (long) width * height || normalized.length != original.length)
            return score;
        var regions = score.measures();
        var result = score;
        int change = 0;
        ScoreMeterChange active = null;
        for (int m = 0; m + 1 < regions.size(); m++) {
            while (change < score.meterChanges().size()
                    && score.meterChanges().get(change).measureIndex() <= m)
                active = score.meterChanges().get(change++);
            if (m == 0 || active == null || active.numerator() != 6 || active.denominator() != 4)
                continue;
            var bar = regions.get(m);
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (bar.cadenceQuarterBeats() != 0
                    || Math.abs(regions.get(m - 1).top() - bar.top()) * height > .35f * gap
                    || Math.abs(regions.get(m + 1).top() - bar.top()) * height > .35f * gap)
                continue;
            var proof = fullSixFourTriplets(score, m, normalized, original, width, height);
            if (proof == null || proof.owners().stream().anyMatch(
                    owner -> score.beamSource().noteOrigins().get(owner).isPresent())) continue;
            float left = proof.x()[0], right = proof.x()[17];
            if (!WrappedMeasureBoundaries.confirmsClosedTripletBar(
                            normalized, width, height, bar, left, right)
                    || !WrappedMeasureBoundaries.confirmsClosedTripletBar(
                            original, width, height, bar, left, right)) continue;
            var rows = UnnumberedTripletInk.sourceRows(bar, regions, height);
            if (unnumberedClosedSixFourGroups(score, proof, bar, rows, original, width, height, gap, words)
                    && unnumberedClosedSixFourGroups(
                            score, proof, bar, rows, normalized, width, height, gap, words))
                result = addPrintedSixFourGroups(result, proof, physicalPage,
                        ScoreBeamSource.TupletMark.UNNUMBERED);
        }
        return result;
    }

    private static boolean unnumberedClosedSixFourGroups(
            ScorePageInterpretation score, WrappedTripletMeasure.Half proof, MeasureRegion bar,
            UnnumberedTripletInk.Rows rows, byte[] plane, int width, int height, float gap,
            List<PlayingTechniqueDetector.Word> words) {
        var staff = WrappedMeasureBoundaries.verifiedTripletStaff(plane, width, height, bar);
        if (staff == null) return false;
        var budget = new PrintedBeamShafts.Budget(900_000);
        for (int i = 0; i < proof.owners().size(); i += 3) {
            float[] x = java.util.Arrays.copyOfRange(proof.x(), i, i + 3), y = new float[3];
            var shafts = new PrintedBeamShafts.Shaft[3];
            int direction = -score.notes().get(proof.owners().get(i)).stemDirection();
            if (Math.abs(direction) != 1) return false;
            for (int j = 0; j < 3; j++) {
                var member = score.notes().get(proof.owners().get(i + j));
                if (-member.stemDirection() != direction) return false;
                y[j] = member.pageY() * height;
                var source = PrintedBeamShafts.at(plane, width, height, x[j], y[j], gap, budget);
                if (source.size() == 1 && source.get(0).direction() == direction)
                    shafts[j] = source.get(0);
            }
            if (budget.refused()) return false;
            var group = WrappedShaftContinuation.recoverGroup(plane, width, height, gap, x, y, direction, shafts);
            if (group == null) return false;
            var owned = java.util.Arrays.asList(group.shafts());
            float headTop = Math.min(y[0], Math.min(y[1], y[2]));
            float headBottom = Math.max(y[0], Math.max(y[1], y[2]));
            int tipTop = owned.stream().mapToInt(PrintedBeamShafts.Shaft::endY).min().orElseThrow();
            int tipBottom = owned.stream().mapToInt(PrintedBeamShafts.Shaft::endY).max().orElseThrow();
            if (localPrintedThree(plane, width, height, x[1], Math.min(headTop, tipTop),
                    Math.max(headBottom, tipBottom), gap, rows, words)) return false;
            if (UnnumberedTripletInk.clearWithTrackedStaff(
                    plane, width, height, gap, x, y, owned, rows, staff, words)) continue;
            if (!UnnumberedTripletInk.clearFingeringBands(
                            plane, width, height, gap, x[0], x[2], headTop, headBottom,
                            tipTop, tipBottom, rows, staff)) return false;
        }
        return true;
    }

    /** The fingering path must still preserve a numeral beyond the ordinary beam-side window. */
    private static boolean localPrintedThree(
            byte[] plane, int width, int height, float middle, float headTop, float headBottom,
            float gap, UnnumberedTripletInk.Rows rows, List<PlayingTechniqueDetector.Word> words) {
        int left = Math.round(middle - 1.1f * gap), right = Math.round(middle + 1.1f * gap);
        int top = Math.max(rows.first(), Math.round(headTop - 3.7f * gap));
        int bottom = Math.min(rows.last(), Math.round(headBottom + 3.7f * gap));
        if (left < 0 || right >= width || top < 0 || bottom >= height || bottom <= top) return true;
        if (words.stream().anyMatch(word -> word.text().strip().equals("3")
                && (word.left() + word.right()) * width * .5f > left
                && (word.left() + word.right()) * width * .5f < right
                && (word.top() + word.bottom()) * height * .5f > top
                && (word.top() + word.bottom()) * height * .5f < bottom)) return true;
        var glyphs = RawNumeralComponents.captureLongWindow(plane, width, height, left, top, right, bottom, 165);
        for (var glyph : glyphs.components()) {
            if (glyph.left <= left || glyph.right >= right || glyph.top <= top || glyph.bottom >= bottom
                    || glyph.bottom - glyph.top + 1 < .70f * gap
                    || glyph.bottom - glyph.top + 1 > 2.2f * gap
                    || glyph.right - glyph.left + 1 < .40f * gap
                    || glyph.right - glyph.left + 1 > 1.65f * gap) continue;
            if (TripletRhythmDetector.isIsolatedThree(glyph, width)) return true;
        }
        return false;
    }

    private static ScorePageInterpretation addPrintedSixFourGroups(
            ScorePageInterpretation score, WrappedTripletMeasure.Half proof, int physicalPage,
            ScoreBeamSource.TupletMark mark) {
        var source = score.beamSource();
        var origins = new ArrayList<>(source.noteOrigins());
        var groups = new ArrayList<>(source.groups());
        var ids = new HashSet<String>();
        for (var group : groups) ids.add(group.id());
        for (int i = 0; i < proof.owners().size(); i += 3) {
            var owned = proof.owners().subList(i, i + 3);
            var columns = new ArrayList<ScoreBeamSource.Column>(3);
            for (int index : owned) {
                var current = origins.get(index);
                var anchor =
                        current.orElseGet(() -> new ScoreBeamSource.Anchor(physicalPage, index));
                var existing = java.util.Optional.of(anchor);
                if (anchor.pageIndex() != physicalPage
                        || java.util.Collections.frequency(origins, existing)
                                != (current.isPresent() ? 1 : 0)
                        || hasBeamOwner(source, anchor)) return score;
                origins.set(index, existing);
                columns.add(new ScoreBeamSource.Column(List.of(anchor)));
            }
            String id =
                    "beam-page:" + physicalPage + ":" + columns.get(0).members().get(0).noteIndex();
            if (!ids.add(id)) return score;
            groups.add(new ScoreBeamSource.Group(id, columns, List.of(1, 1), List.of(), mark));
        }
        return score.withBeamSource(new ScoreBeamSource(groups, source.detachedColumns(), origins));
    }

    private static ScorePageInterpretation attachStaffCrossingSlur(
            ScorePageInterpretation score, ScoreSlurSpan.ArcEvidence arc, int firstOwner, int lastOwner) {
        var source = score.slurSource();
        var owners = new ArrayList<>(source.noteOrigins());
        var a = owners.get(firstOwner).orElseGet(() -> new ScoreSlurPages.Anchor(arc.pageIndex(), firstOwner));
        var b = owners.get(lastOwner).orElseGet(() -> new ScoreSlurPages.Anchor(arc.pageIndex(), lastOwner));
        if (a.pageIndex() != arc.pageIndex() || b.pageIndex() != arc.pageIndex()
                || owners.get(firstOwner).isEmpty() && owners.contains(java.util.Optional.of(a))
                || owners.get(lastOwner).isEmpty() && owners.contains(java.util.Optional.of(b)))
            return score;
        var id = "staff-crossing-slur:" + a.pageIndex() + ":" + a.noteIndex() + ":" + b.noteIndex();
        var conflicts = java.util.stream.Stream.concat(source.spans().stream(), source.candidates().stream())
                .anyMatch(s -> s.id().equals(id) || s.start().equals(a) || s.end().equals(b));
        if (conflicts) return score;
        owners.set(firstOwner, java.util.Optional.of(a));
        owners.set(lastOwner, java.util.Optional.of(b));
        var spans = new ArrayList<>(source.spans());
        spans.add(new ScoreSlurPages.Span(id, a, b, ScoreSlurSpan.Placement.ABOVE,
                ScoreSlurSpan.LineType.SOLID, List.of(arc)));
        return score.withSlurSource(new ScoreSlurSource(spans, owners, source.candidates()));
    }

    static ScorePageInterpretation projectOpenedQuarterTripletBeams(
            ScorePageInterpretation score, byte[] labels, byte[] corrected, byte[] original,
            int width, int height, List<PlayingTechniqueDetector.Word> words) {
        if (original == null || corrected == null || labels == null || original.length != (long) width * height
                || labels.length != original.length || corrected.length != original.length
                || score.measures().size() < 2
                || score.meterChanges().isEmpty()
                || !score.meterChanges().get(0).equals(new ScoreMeterChange(0, 6, 4))) return score;
        var scoreNotes = score.notes();
        var bars = score.measures();
        var previous = score.beamSource();
        var opening = fullSixFourTriplets(score, 0, corrected, original, width, height);
        boolean thinStaffOpening = opening == null;
        if (thinStaffOpening) {
            var owners = fullSixFourOwners(score, 0);
            if (owners.isEmpty()) return score;
            opening = thinStaffOpening(corrected, original, labels, width, height,
                    bars, scoreNotes, owners, score.meterChanges().get(0));
        }
        if (opening == null || !WrappedMeasureBoundaries.confirmsOpeningTripletBar(
                original, width, height, bars, opening.x()[0], opening.x()[17], true, thinStaffOpening)) return score;
        var first = bars.get(0);
        float openingGap = (first.bottom() - first.top()) * height / 8.2f;
        var middle = scoreNotes.get(opening.owners().get(1));
        float middleX = opening.x()[1], middleY = middle.pageY() * height;
        if (!ownedOpeningNumeral(original, labels, width, height, middleX, middleY, openingGap)
                && !(thinStaffOpening && OpeningTripletNumeral.present(
                        words, original, corrected, labels, width, height, middleX, middleY, openingGap)))
            return score;
        var groups = new ArrayList<>(previous.groups());
        var firstColumns = existingTripletColumns(previous, opening.owners().subList(0, 3));
        if (firstColumns.isEmpty()) return score;
        int firstGroup = groupStartingAt(groups, firstColumns.get(0));
        if (firstGroup < 0) return score;
        var owner = groups.get(firstGroup);
        if (!owner.columns().equals(firstColumns) || !owner.bridges().equals(List.of(1, 1))
                || !owner.hooks().isEmpty() || owner.tupletMark() == ScoreBeamSource.TupletMark.UNNUMBERED)
            return score;
        if (owner.tupletMark() == null) groups.set(firstGroup, new ScoreBeamSource.Group(
                owner.id(), owner.columns(), owner.bridges(), owner.hooks(), ScoreBeamSource.TupletMark.NUMBERED));

        // A shortened source record can end before the verified terminal stem of the following bar.
        var following = thinStaffOpening ? null : fullSixFourTriplets(score, 1, corrected, original, width, height);
        if (following != null
                && score.meterChanges().stream().noneMatch(m -> m.measureIndex() == 1)
                && WrappedMeasureBoundaries.confirmsPocketClosedTripletBar(
                        corrected, width, height, first, bars.get(1), following.x()[0], following.x()[17])
                && WrappedMeasureBoundaries.confirmsPocketClosedTripletBar(
                        original, width, height, first, bars.get(1), following.x()[0], following.x()[17])) {
            var tail = existingTripletColumns(previous, following.owners().subList(15, 18));
            if (!tail.isEmpty()) {
                int tailGroup = groupStartingAt(groups, tail.get(0));
                var free = tail.get(2).members().get(0);
                if (tailGroup >= 0 && !hasBeamOwner(previous, free)) {
                    var partial = groups.get(tailGroup);
                    if (partial.columns().equals(tail.subList(0, 2))
                            && partial.bridges().equals(List.of(1)) && partial.hooks().isEmpty()) {
                        groups.set(tailGroup, new ScoreBeamSource.Group(partial.id(), tail, List.of(1, 1), List.of()));
                    }
                }
            }
        }
        markUnnumberedTripletGroups(score, groups, original, corrected, width, height, words,
                opening, thinStaffOpening);
        projectNaturalNeighborBeams(score, groups, labels, corrected, original, width, height, words);
        return groups.equals(previous.groups()) ? score : score.withBeamSource(
                new ScoreBeamSource(groups, previous.detachedColumns(), previous.noteOrigins()));
    }

    static ScorePageInterpretation completeRestoredTripletBeams(
            ScorePageInterpretation score, List<Integer> repairedBars,
            byte[] normalized, byte[] raw, int width, int height) {
        if (repairedBars.isEmpty() || score.beamSource().groups().isEmpty()) return score;
        var source = score.beamSource();
        var groups = new ArrayList<>(source.groups());
        var notes = score.notes();
        var bars = score.measures();
        for (int index : repairedBars) {
            if (index >= bars.size() || score.rests().stream().anyMatch(rest -> rest.measureIndex() == index))
                continue;
            var owners = java.util.stream.IntStream.range(0, notes.size())
                    .filter(i -> notes.get(i).measureIndex() == index)
                    .boxed()
                    .sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure())).toList();
            if (owners.size() != 12 || owners.stream().map(notes::get).anyMatch(
                    n -> n.beamCount() != 1 || n.tupletDivisor() != 3 || n.tupletNormalNotes() != 2))
                continue;
            var proof = proveCommonTimeSingleRails(notes, owners, bars, index, normalized, raw, width, height);
            if (proof == null)
                proof = proveCommonTimeSingleRails(notes, owners, bars, index, normalized, raw, width, height, true);
            if (proof == null) continue;
            for (int start = 0; start < owners.size(); start += 3) {
                var columns = existingTripletColumns(source, owners.subList(start, start + 3));
                if (columns.isEmpty()) continue;
                int completed = completeSingleRailTriplet(source, groups, columns);
                if (completed < 0 || start + 3 != owners.size()) continue;
                var members = owners.subList(start, start + 3).stream().map(notes::get).toList();
                var bar = bars.get(index);
                var rows = UnnumberedTripletInk.sourceRows(bar, bars, height);
                if (terminalTripletUnnumbered(raw, width, height, bar, members, rows)
                        && terminalTripletUnnumbered(normalized, width, height, bar, members, rows)) {
                    var group = groups.get(completed);
                    groups.set(completed, new ScoreBeamSource.Group(group.id(), group.columns(),
                            group.bridges(), group.hooks(), ScoreBeamSource.TupletMark.UNNUMBERED));
                }
            }
        }
        return groups.equals(source.groups()) ? score : score.withBeamSource(
                new ScoreBeamSource(groups, source.detachedColumns(), source.noteOrigins()));
    }

    static ScorePageInterpretation projectNeighborBoundedTripletBeams(
            ScorePageInterpretation score, byte[] normalized, byte[] raw, int width, int height,
            List<PlayingTechniqueDetector.Word> words, List<Integer> recoveredBars) {
        var source = score.beamSource();
        if (recoveredBars.isEmpty() || source.groups().isEmpty() || normalized == null || raw == null
                || raw.length != (long) width * height || normalized.length != raw.length) return score;
        var groups = new ArrayList<>(source.groups());
        var notes = score.notes();
        var bars = score.measures();
        for (int m : recoveredBars) {
            if (m <= 0 || m + 1 >= bars.size()) continue;
            final int index = m;
            var owners = java.util.stream.IntStream.range(0, notes.size())
                    .filter(i -> notes.get(i).measureIndex() == index).boxed()
                    .sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure())).toList();
            if (owners.size() != 12 || owners.stream().map(notes::get).anyMatch(n -> n.beamCount() != 1
                        || n.tupletDivisor() != 3 || n.tupletNormalNotes() != 2)
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == index)) continue;
            var bar = bars.get(m);
            float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
            float first = left + span * notes.get(owners.get(0)).positionInMeasure();
            float last = left + span * notes.get(owners.get(11)).positionInMeasure();
            boolean neighborRules = WrappedMeasureBoundaries.confirmsNeighborBoundedTripletBar(
                        normalized, width, height, bars, m, first, last)
                    && WrappedMeasureBoundaries.confirmsNeighborBoundedTripletBar(
                        raw, width, height, bars, m, first, last)
                    && proveCommonTimeSingleRails(notes, owners, bars, m, normalized, raw, width, height) != null;
            boolean openingRules = score.meterChanges().contains(new ScoreMeterChange(m, 4, 4))
                    && proveCommonTimeSingleRails(notes, owners, bars, m, normalized, raw, width, height) == null
                    && proveCommonTimeSingleRails(notes, owners, bars, m, normalized, raw, width, height,
                            false, true) != null;
            if (!neighborRules && !openingRules
                    && proveStaffRuleAndFlatTriplets(notes, owners, bars, m, normalized, raw, width, height) == null)
                continue;
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            float top = bar.top() * height + 2.1f * gap, bottom = bar.bottom() * height - 2.1f * gap;
            var rawStaff = StaffPitchTrack.forPrintedSlurs(raw, width, height, top, bottom, gap);
            var correctedStaff = StaffPitchTrack.forPrintedSlurs(normalized, width, height, top, bottom, gap);
            var rows = UnnumberedTripletInk.sourceRows(bar, bars, height);
            for (int i = 0; i < owners.size(); i += 3) {
                var slice = owners.subList(i, i + 3);
                var columns = existingTripletColumns(source, slice);
                if (columns.isEmpty()) continue;
                completeSingleRailTriplet(source, groups, columns);
                int g = groupStartingAt(groups, columns.get(0));
                if (g < 0 && openingRules && columns.stream().flatMap(c -> c.members().stream())
                        .noneMatch(anchor -> hasBeamOwner(source, anchor))) {
                    var anchor = columns.get(0).members().get(0);
                    String id = "beam-page:" + anchor.pageIndex() + ":" + anchor.noteIndex();
                    if (groups.stream().noneMatch(existing -> existing.id().equals(id))) {
                        g = groups.size();
                        groups.add(new ScoreBeamSource.Group(id, columns, List.of(1, 1), List.of()));
                    }
                }
                if (g < 0) continue;
                var group = groups.get(g);
                if (!group.columns().equals(columns) || !group.bridges().equals(List.of(1, 1))
                        || !group.hooks().isEmpty() || group.tupletMark() != null) continue;
                var members = slice.stream().map(notes::get).toList();
                boolean unnumbered = openingRules
                        ? openingTripletClear(raw, width, height, bar, members, rows, rawStaff, words)
                            && openingTripletClear(normalized, width, height, bar, members, rows, correctedStaff, words)
                        : neighborTripletClear(raw, width, height, bar, members, rows, rawStaff, words)
                            && neighborTripletClear(normalized, width, height, bar, members, rows, correctedStaff, words);
                if (unnumbered)
                    groups.set(g, new ScoreBeamSource.Group(group.id(), group.columns(),
                            group.bridges(), group.hooks(), ScoreBeamSource.TupletMark.UNNUMBERED));
            }
        }
        return groups.equals(source.groups()) ? score : score.withBeamSource(
                new ScoreBeamSource(groups, source.detachedColumns(), source.noteOrigins()));
    }

    private static boolean openingTripletClear(byte[] ink, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> heads, UnnumberedTripletInk.Rows rows, StaffPitchTrack staff,
            List<PlayingTechniqueDetector.Word> words) {
        if (staff == null) return false;
        float offset = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(3);
        var budget = new PrintedBeamShafts.Budget(100_000);
        float[] x = new float[3], y = new float[3];
        for (int i = 0; i < heads.size(); i++) {
            var head = heads.get(i);
            x[i] = offset + span * head.positionInMeasure();
            y[i] = head.pageY() * height;
            var axis = PrintedBeamShafts.at(ink, width, height, x[i], y[i], gap, budget);
            if (axis.size() != 1 || axis.get(0).direction() != -head.stemDirection() || budget.refused())
                return false;
            shafts.add(axis.get(0));
        }
        if (UnnumberedTripletInk.clearWithTrackedStaff(ink, width, height, gap, x, y, shafts, rows, staff, words))
            return true;
        float headTop = Math.min(y[0], Math.min(y[1], y[2])), headBottom = Math.max(y[0], Math.max(y[1], y[2]));
        int tipTop = shafts.stream().mapToInt(PrintedBeamShafts.Shaft::endY).min().orElseThrow();
        int tipBottom = shafts.stream().mapToInt(PrintedBeamShafts.Shaft::endY).max().orElseThrow();
        var accidentals = new ArrayList<UnnumberedTripletInk.AccidentalHead>(3);
        for (int i = 0; i < heads.size(); i++)
            accidentals.add(new UnnumberedTripletInk.AccidentalHead(x[i], y[i], heads.get(i).writtenAccidental()));
        return !localPrintedThree(ink, width, height, x[1], Math.min(headTop, tipTop),
                        Math.max(headBottom, tipBottom), gap, rows, words)
                && UnnumberedTripletInk.clearFingeringBands(ink, width, height, gap, x[0], x[2], headTop,
                        headBottom, tipTop, tipBottom, rows, staff, null, accidentals, words);
    }

    private static boolean neighborTripletClear(byte[] source, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> members, UnnumberedTripletInk.Rows rows, StaffPitchTrack staff,
            List<PlayingTechniqueDetector.Word> words) {
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float first = left + span * members.get(0).positionInMeasure();
        float last = left + span * members.get(2).positionInMeasure();
        float headTop = Float.POSITIVE_INFINITY, headBottom = 0;
        int tipTop = height, tipBottom = -1;
        var budget = new PrintedBeamShafts.Budget(82_000);
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(3);
        var positions = new float[3];
        var ordinates = new float[3];
        for (int i = 0; i < members.size(); i++) {
            var note = members.get(i);
            float x = positions[i] = left + span * note.positionInMeasure(), y = ordinates[i] = note.pageY() * height;
            var shaft = PrintedBeamShafts.at(source, width, height, x, y, gap, budget);
            if (note.stemDirection() != -1 || shaft.size() != 1
                    || shaft.get(0).direction() != 1 || budget.refused()) return false;
            shafts.add(shaft.get(0));
            headTop = Math.min(headTop, y);
            headBottom = Math.max(headBottom, y);
            tipTop = Math.min(tipTop, shaft.get(0).endY());
            tipBottom = Math.max(tipBottom, shaft.get(0).endY());
        }
        if (UnnumberedTripletInk.clearWithTrackedStaff(
                source, width, height, gap, positions, ordinates, shafts, rows, staff)) return true;
        var middle = members.get(1);
        float middleX = left + span * middle.positionInMeasure(), middleY = middle.pageY() * height;
        var twos = words.stream().filter(word -> "2".equals(word.text())
                && Math.abs((word.left() + word.right()) * width * .5f - middleX) <= gap * .36f
                && middleY - (word.top() + word.bottom()) * height * .5f >= gap * 1.2f
                && middleY - (word.top() + word.bottom()) * height * .5f <= gap * 3.3f).toList();
        return UnnumberedTripletInk.clearFingeringBands(
                source, width, height, gap, first, last, headTop, headBottom, tipTop, tipBottom, rows, staff,
                twos.size() == 1 ? twos.get(0) : null);
    }

    static boolean terminalTripletUnnumbered(byte[] source, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> members, UnnumberedTripletInk.Rows rows) {
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (members.size() != 3 || gap < 10 || gap > 25) return false;
        float first = left + span * members.get(0).positionInMeasure();
        float last = left + span * members.get(2).positionInMeasure();
        if (last - first < gap * 2.8f || last - first > gap * 5.5f
                || left + span - last > gap * 2.5f) return false;
        var staff = WrappedMeasureBoundaries.verifiedTripletStaff(source, width, height, bar);
        if (staff == null) return false;
        float headTop = Float.POSITIVE_INFINITY, headBottom = 0;
        int tipTop = height, tipBottom = -1;
        var budget = new PrintedBeamShafts.Budget(82_000);
        for (var note : members) {
            float x = left + span * note.positionInMeasure(), y = note.pageY() * height;
            var shaft = PrintedBeamShafts.at(source, width, height, x, y, gap, budget);
            if (note.stemDirection() != -1 || shaft.size() != 1
                    || shaft.get(0).direction() != 1 || budget.refused()) return false;
            headTop = Math.min(headTop, y);
            headBottom = Math.max(headBottom, y);
            tipTop = Math.min(tipTop, shaft.get(0).endY());
            tipBottom = Math.max(tipBottom, shaft.get(0).endY());
        }
        return UnnumberedTripletInk.clearFingeringBands(
                source, width, height, gap, first, last, headTop, headBottom, tipTop, tipBottom, rows, staff);
    }

    private static int completeSingleRailTriplet(
            ScoreBeamSource source, List<ScoreBeamSource.Group> groups, List<ScoreBeamSource.Column> columns) {
        for (int missing : new int[] {0, 2}) {
            if (hasBeamOwner(source, columns.get(missing).members().get(0))) continue;
            int first = missing == 0 ? 1 : 0;
            var present = columns.subList(first, first + 2);
            int i = groupStartingAt(groups, present.get(0));
            if (i < 0) continue;
            var group = groups.get(i);
            if (group.columns().equals(present) && group.bridges().equals(List.of(1))
                    && group.hooks().isEmpty() && group.tupletMark() == null) {
                groups.set(i, new ScoreBeamSource.Group(group.id(), columns, List.of(1, 1), List.of()));
                return i;
            }
        }
        return -1;
    }

    private static void projectNaturalNeighborBeams(ScorePageInterpretation score,
            List<ScoreBeamSource.Group> groups, byte[] labels, byte[] gray, byte[] original, int width, int height,
            List<PlayingTechniqueDetector.Word> words) {
        if (score.measures().size() < 2 || score.meterChanges().stream().anyMatch(m -> m.measureIndex() == 1))
            return;
        var owners = fullSixFourOwners(score, 1);
        if (owners.isEmpty()) return;
        var recovery = proveNaturalNeighbor(
                gray, original, labels, width, height, score.measures(), 1, score.notes(), owners);
        if (recovery == null) return;
        var source = score.beamSource();
        for (int i = 0; i < owners.size(); i += 3) {
            var columns = existingTripletColumns(source, recovery.group().owners().subList(i, i + 3));
            if (!columns.isEmpty()) completeSingleRailTriplet(source, groups, columns);
        }
        markNaturalNeighborGroups(score, groups, recovery.group(), gray, original, width, height, words);
    }

    private static void markNaturalNeighborGroups(ScorePageInterpretation score,
            List<ScoreBeamSource.Group> groups, WrappedTripletMeasure.Half proof, byte[] gray,
            byte[] original, int width, int height, List<PlayingTechniqueDetector.Word> words) {
        var bar = score.measures().get(1);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        var rawStaff = WrappedMeasureBoundaries.verifiedTripletStaff(original, width, height, bar);
        var correctedStaff = WrappedMeasureBoundaries.verifiedTripletStaff(gray, width, height, bar);
        if (rawStaff == null || correctedStaff == null) return;
        var scan = new PrintedBeamShafts.Budget(800_000);
        var x = proof.x();
        var y = new float[x.length];
        var tips = new float[x.length];
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(x.length);
        for (int i = 0; i < x.length; i++) {
            var note = score.notes().get(proof.owners().get(i));
            y[i] = note.pageY() * height;
            int direction = -note.stemDirection();
            var own = PrintedBeamShafts.at(original, width, height, x[i], y[i], gap, scan).stream()
                    .filter(s -> s.direction() == direction).toList();
            if (own.size() != 1) return;
            shafts.add(own.get(0));
            tips[i] = own.get(0).endY();
        }
        var context = OpeningTripletContextInk.withoutFollowingContext(
                original, gray, width, height, gap, x, y, words, tips);
        var rows = UnnumberedTripletInk.sourceRows(bar, score.measures(), height);
        var lowerHeads = nextStaffHeads(score, bar, width, height, gap);
        for (int i = 0; i < x.length; i += 3) {
            var columns = existingTripletColumns(score.beamSource(), proof.owners().subList(i, i + 3));
            if (columns.isEmpty()) continue;
            int j = groupStartingAt(groups, columns.get(0));
            if (j < 0) continue;
            var group = groups.get(j);
            if (group.tupletMark() != null || !group.columns().equals(columns)
                    || !group.bridges().equals(List.of(1, 1)) || !group.hooks().isEmpty()) continue;
            float[] localX = java.util.Arrays.copyOfRange(x, i, i + 3);
            float[] localY = java.util.Arrays.copyOfRange(y, i, i + 3);
            var own = shafts.subList(i, i + 3);
            if (UnnumberedTripletInk.clearFollowing(context.original(), width, height, gap,
                            localX, localY, own, rows, rawStaff, words, lowerHeads)
                    && UnnumberedTripletInk.clearFollowing(context.corrected(), width, height, gap,
                            localX, localY, own, rows, correctedStaff, words, lowerHeads)) {
                groups.set(j, new ScoreBeamSource.Group(group.id(), group.columns(), group.bridges(),
                        group.hooks(), ScoreBeamSource.TupletMark.UNNUMBERED));
            }
        }
    }

    private static List<UnnumberedTripletInk.Head> nextStaffHeads(
            ScorePageInterpretation score, MeasureRegion bar, int width, int height, float gap) {
        var bars = score.measures();
        double nextTop = bars.stream().filter(m -> m.top() > bar.bottom()
                        && m.left() < bar.right() && m.right() > bar.left())
                .mapToDouble(MeasureRegion::top).min().orElse(Double.POSITIVE_INFINITY);
        if (nextTop > bar.bottom() + 9 * gap / height) return List.of();
        var heads = new ArrayList<UnnumberedTripletInk.Head>();
        for (var note : score.notes()) {
            var region = bars.get(note.measureIndex());
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || region.top() <= bar.bottom()
                    || Math.abs(region.top() - nextTop) * height > gap) continue;
            float x = (region.left() + note.positionInMeasure() * (region.right() - region.left())) * width;
            if (x >= bar.left() * width - gap && x <= bar.right() * width + gap)
                heads.add(new UnnumberedTripletInk.Head(x, note.pageY() * height));
        }
        return List.copyOf(heads);
    }

    private static void markUnnumberedTripletGroups(
            ScorePageInterpretation score, List<ScoreBeamSource.Group> groups, byte[] original,
            byte[] corrected, int width, int height, List<PlayingTechniqueDetector.Word> words,
            WrappedTripletMeasure.Half opening, boolean openingOnly) {
        var bars = score.measures();
        var source = score.beamSource();
        var notes = score.notes();
        int meterChange = bars.size();
        if (score.meterChanges().size() > 1) meterChange = score.meterChanges().get(1).measureIndex();
        if (openingOnly) meterChange = Math.min(meterChange, 1);
        for (int m = 0; m < meterChange; m++) {
            var proof = m == 0 ? opening : fullSixFourTriplets(score, m, corrected, original, width, height);
            if (proof == null) continue;
            var bar = bars.get(m);
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            var rows = UnnumberedTripletInk.sourceRows(bar, bars, height);
            byte[] markInk = original;
            OpeningTripletContextInk.Pair context = null;
            StaffPitchTrack correctedStaff = null;
            if (openingOnly) {
                float[] openingY = new float[proof.owners().size()];
                for (int i = 0; i < openingY.length; i++)
                    openingY[i] = notes.get(proof.owners().get(i)).pageY() * height;
                context = OpeningTripletContextInk.withoutContext(original, corrected, width, height,
                        gap, proof.x(), openingY, words);
                markInk = context.original();
                correctedStaff = WrappedMeasureBoundaries.verifiedTripletStaff(corrected, width, height, bar);
                if (correctedStaff == null) continue;
            }
            StaffPitchTrack local = null, broad = null;
            boolean fitted = false;
            var budget = new PrintedBeamShafts.Budget(800_000);
            PrintedBeamShaftPixels.Frame contrast = null;
            for (int i = 0; i < 18; i += 3) {
                var owners = proof.owners().subList(i, i + 3);
                var expected = existingTripletColumns(source, owners);
                if (expected.isEmpty()) continue;
                int groupIndex = groupStartingAt(groups, expected.get(0));
                if (groupIndex < 0) continue;
                var group = groups.get(groupIndex);
                if (group.tupletMark() != null || !group.columns().equals(expected)
                        || !group.bridges().equals(List.of(1, 1)) || !group.hooks().isEmpty()) continue;
                var x = java.util.Arrays.copyOfRange(proof.x(), i, i + 3);
                var y = new float[3];
                var shafts = new ArrayList<PrintedBeamShafts.Shaft>(3);
                for (int n = 0; n < 3; n++) {
                    var note = notes.get(owners.get(n));
                    y[n] = note.pageY() * height;
                    var possible = PrintedBeamShafts.at(original, width, height, x[n], y[n], gap, budget);
                    if (possible.isEmpty() && !budget.refused()) {
                        if (contrast == null) contrast = new PrintedBeamShaftPixels.Frame(
                                original, original, width, height, 0,
                                units -> { if (!budget.take(units)) throw new ShaftQuotaExceeded(); }, true);
                        try {
                            possible = PrintedBeamShafts.at(
                                    original, width, height, x[n], y[n], gap, budget, contrast);
                        } catch (ShaftQuotaExceeded exhausted) {
                            break;
                        }
                    }
                    if (possible.size() != 1 || possible.get(0).direction() != -note.stemDirection()) break;
                    shafts.add(possible.get(0));
                }
                if (shafts.size() != 3) continue;
                boolean clear = UnnumberedTripletInk.clear(markInk, width, height, gap, x, y, shafts, rows);
                if (!clear) {
                    if (!fitted) {
                        fitted = true;
                        local = WrappedMeasureBoundaries.verifiedTripletStaff(original, width, height, bar);
                        float top = bar.top() * height + 2.1f * gap;
                        float bottom = bar.bottom() * height - 2.1f * gap;
                        broad = StaffPitchTrack.forPrintedSlurs(original, width, height, top, bottom, gap);
                    }
                    clear = UnnumberedTripletInk.clearWithTrackedStaff(
                                    markInk, width, height, gap, x, y, shafts, rows, local, words)
                            || UnnumberedTripletInk.clearWithTrackedStaff(
                                    markInk, width, height, gap, x, y, shafts, rows, broad, words);
                }
                if (clear && context != null)
                    clear = UnnumberedTripletInk.clearWithTrackedStaff(context.corrected(),
                            width, height, gap, x, y, shafts, rows, correctedStaff, words);
                if (clear) groups.set(groupIndex, new ScoreBeamSource.Group(
                        group.id(), group.columns(), group.bridges(), group.hooks(), ScoreBeamSource.TupletMark.UNNUMBERED));
            }
        }
    }

    private static WrappedTripletMeasure.Half fullSixFourTriplets(
            ScorePageInterpretation score, int bar, byte[] corrected, byte[] original, int width, int height) {
        var notes = score.notes();
        var owners = fullSixFourOwners(score, bar);
        if (owners.isEmpty()) return null;
        var region = score.measures().get(bar);
        var proof = singleRailGroups(corrected, width, height, region, notes, owners, true);
        return proof != null && singleRailGroups(original, width, height, region, notes, owners, true) != null
                ? proof : null;
    }

    private static List<Integer> fullSixFourOwners(ScorePageInterpretation score, int bar) {
        if (score.rests().stream().anyMatch(r -> r.measureIndex() == bar)) return List.of();
        var notes = score.notes();
        var owners = java.util.stream.IntStream.range(0, notes.size())
                .filter(i -> notes.get(i).measureIndex() == bar).boxed().toList();
        return owners.size() == 18 && owners.stream().map(notes::get).allMatch(
                n -> n.beamCount() == 1 && n.tupletDivisor() == 3 && n.tupletNormalNotes() == 2)
                ? owners : List.of();
    }

    private static List<ScoreBeamSource.Column> existingTripletColumns(
            ScoreBeamSource source, List<Integer> owners) {
        var columns = new ArrayList<ScoreBeamSource.Column>(3);
        for (int i : owners) {
            var anchor = source.noteOrigins().get(i);
            if (anchor.isEmpty() || java.util.Collections.frequency(source.noteOrigins(), anchor) != 1)
                return List.of();
            columns.add(new ScoreBeamSource.Column(List.of(anchor.get())));
        }
        return List.copyOf(columns);
    }

    private static int groupStartingAt(List<ScoreBeamSource.Group> groups, ScoreBeamSource.Column column) {
        for (int i = 0; i < groups.size(); i++)
            if (groups.get(i).columns().get(0).equals(column)) return i;
        return -1;
    }

    private static boolean hasBeamOwner(ScoreBeamSource beamSource, ScoreBeamSource.Anchor owner) {
        if (beamSource.detachedColumns().stream().anyMatch(c -> c.members().contains(owner))) return true;
        return beamSource.groups().stream()
                .flatMap(g -> g.columns().stream()).anyMatch(c -> c.members().contains(owner));
    }

    record AccidentalTripletBars(ScorePageInterpretation score, List<ScoreNoteEvent> sharpOwners) {}

    /** A printed accidental can occupy the dot window beside a complete set of eighth triplets. */
    static AccidentalTripletBars closeAccidentalMarkedTripletBars(
            ScorePageInterpretation score, byte[] labels, byte[] gray, int width, int height) {
        if (score.beamSource().groups().isEmpty()
                || score.meterChanges().isEmpty()
                || labels == null
                || gray == null
                || labels.length != gray.length
                || gray.length != (long) width * height)
            return new AccidentalTripletBars(score, List.of());
        var singleGroups = new HashSet<List<Integer>>();
        for (var group : score.beamSource().resolve().groups()) {
            var columns = group.columns();
            if (columns.size() != 3
                    || group.bridgeMask(0) != 1
                    || group.bridgeMask(1) != 1
                    || !group.source().hooks().isEmpty()
                    || columns.stream()
                            .anyMatch(
                                    c ->
                                            !c.usable()
                                                    || c.owners().size() != 1
                                                    || c.source().members().size() != 1)) continue;
            singleGroups.add(columns.stream().map(c -> c.owners().get(0)).toList());
        }
        if (singleGroups.size() < 6) return new AccidentalTripletBars(score, List.of());
        var notes = score.notes();
        var regions = score.measures();
        var edited = notes;
        var sharpOwners = new ArrayList<ScoreNoteEvent>();
        List<ScoreMeterChange> literalMeters = null;
        var bars = new ArrayList<List<Integer>>(regions.size());
        for (int m = 0; m < regions.size(); m++) bars.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) bars.get(notes.get(i).measureIndex()).add(i);
        var meterChanges = score.meterChanges();
        int nextMeter = 0;
        ScoreMeterChange meter = null;
        for (int measure = 1; measure < regions.size(); measure++) {
            while (nextMeter < meterChanges.size()
                    && meterChanges.get(nextMeter).measureIndex() <= measure) {
                meter = meterChanges.get(nextMeter++);
            }
            if (meter == null || meter.numerator() != 6 || meter.denominator() != 4
                    || bars.get(measure).size() != 18) continue;
            var region = regions.get(measure);
            var meterRegion = regions.get(meter.measureIndex());
            boolean atHeader = meter.measureIndex() == measure;
            if (atHeader
                    ? region.top() <= regions.get(measure - 1).bottom()
                    : region.top() >= meterRegion.bottom() || region.bottom() <= meterRegion.top()) {
                continue;
            }
            final int barIndex = measure;
            if (score.rests().stream().anyMatch(r -> r.measureIndex() == barIndex)) continue;
            var owners =
                    bars.get(measure).stream()
                            .sorted(
                                    Comparator.comparingDouble(
                                            i -> notes.get(i).positionInMeasure()))
                            .toList();
            if (owners.size() != 18
                    || notes.get(owners.get(0)).beamCount() != 2
                    || owners.subList(1, 18).stream().anyMatch(i -> notes.get(i).beamCount() != 1))
                continue;
            boolean complete = true;
            for (int group = 0; group < owners.size(); group += 3)
                complete &= singleGroups.contains(owners.subList(group, group + 3));
            if (!complete) continue;
            var proof = singleRailGroups(
                    gray, width, height, region, notes, owners, true, atHeader,
                    atHeader ? null : labels);
            if (proof == null
                    || !WrappedMeasureBoundaries.confirmsClosedTripletBar(
                            gray,
                            width,
                            height,
                            region,
                            proof.x()[0],
                            proof.x()[owners.size() - 1])) continue;
            if (literalMeters == null)
                literalMeters =
                        MeterFontMatcher.standaloneReadings(
                                labels, gray, width, height, regions, notes);
            if (!literalMeters.contains(meter)) continue;
            if (edited == notes) edited = new ArrayList<>(notes);
            float gap = (region.bottom() - region.top()) * height / 8.2f;
            for (int i = 0; i < owners.size(); i++) {
                int owner = owners.get(i);
                var note = notes.get(owner);
                if (!atHeader
                        && note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_SHARP
                        && closedBarHasPrintedSharp(
                                gray,
                                labels,
                                width,
                                height,
                                proof.x()[i],
                                note.pageY() * height,
                                gap)) sharpOwners.add(note);
                if (i == 0) note = ClosingTripletRhythm.singleBeam(note);
                edited.set(owner, note.withTupletRatio(3, 2));
            }
        }
        if (edited == notes) return new AccidentalTripletBars(score, List.of());
        return new AccidentalTripletBars(
                score.withNotes(
                        edited, java.util.stream.IntStream.range(0, notes.size()).boxed().toList()),
                List.copyOf(sharpOwners));
    }

    /** A native alteration can be inherited. A sharp tag needs its own glyph at the head. */
    static boolean closedBarHasPrintedSharp(
            byte[] gray, byte[] labels, int width, int height, float x, float y, float gap) {
        if (gray == null
                || labels == null
                || width <= 0
                || height <= 0
                || gray.length != (long) width * height
                || labels.length != gray.length
                || !Float.isFinite(x)
                || !Float.isFinite(y)
                || !Float.isFinite(gap)
                || x < 0
                || x >= width
                || y < 0
                || y >= height
                || gap < 10
                || gap > 30) return false;
        int top = Math.round(y - gap * 1.1f);
        int middle = Math.round(y);
        int bottom = Math.round(y + gap * 1.4f);
        if (top < 0 || bottom >= height) return false;
        int first = Math.round(x - gap * 2.1f), last = Math.round(x - gap * 1.1f);
        int pad = Math.round(gap * .3f), reach = Math.round(gap * 1.3f);
        int headEdge = Math.round(x - gap * .72f);
        int minimum = Math.max(3, Math.round(gap * gap * .10f));
        for (int seam = first; seam <= last; seam++) {
            int left = seam - pad, right = Math.min(seam + reach, headEdge);
            if (left < 0 || right >= width || left >= right) continue;
            int above = 0, below = 0;
            for (int yy = top; yy <= bottom; yy++) {
                for (int xx = left; xx <= right; xx++) {
                    int at = yy * width + xx;
                    if (labels[at] != OmrMeasurePostProcessor.CLEF_OR_KEY
                            || (gray[at] & 255) >= 150) continue;
                    if (yy < middle) above++;
                    else below++;
                }
            }
            if (above >= minimum
                    && below >= minimum
                    && OmrScoreInterpreter.printedSharpBeforeDyad(
                            gray, width, height, seam, x, y, gap)) return true;
        }
        return false;
    }

    static WrappedTripletMeasure.Half closedFourGroups(
            byte[] gray, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> candidates) {
        if (candidates.size() != 12) return null;
        return singleRailGroups(gray, width, height, bar, notes, candidates, true);
    }

    private static WrappedTripletMeasure.Half singleRailGroups(
            byte[] gray, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> candidates, boolean closedBar) {
        return singleRailGroups(gray, width, height, bar, notes, candidates, closedBar, false);
    }

    private static WrappedTripletMeasure.Half singleRailGroups(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            List<ScoreNoteEvent> notes,
            List<Integer> candidates,
            boolean closedBar,
            boolean allowSingleFlat) {
        return singleRailGroups(
                gray, width, height, bar, notes, candidates, closedBar, allowSingleFlat, null);
    }

    private static WrappedTripletMeasure.Half singleRailGroups(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            List<ScoreNoteEvent> notes,
            List<Integer> candidates,
            boolean closedBar,
            boolean allowSingleFlat,
            byte[] labels) {
        return singleRailGroups(gray, width, height, bar, notes, candidates, closedBar,
                allowSingleFlat, labels, false);
    }

    private static WrappedTripletMeasure.Half singleRailGroups(
            byte[] gray, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> candidates, boolean closedBar,
            boolean allowSingleFlat, byte[] labels, boolean thinStaffOpening) {
        return singleRailGroups(gray, width, height, bar, notes, candidates, closedBar,
                allowSingleFlat, labels, thinStaffOpening, null);
    }

    private static WrappedTripletMeasure.Half singleRailGroups(
            byte[] gray, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> candidates, boolean closedBar,
            boolean allowSingleFlat, byte[] labels, boolean thinStaffOpening, byte[] naturalNeighborLabels) {
        return singleRailGroups(
                gray,
                width,
                height,
                bar,
                notes,
                candidates,
                closedBar,
                allowSingleFlat,
                labels,
                thinStaffOpening,
                naturalNeighborLabels,
                false);
    }

    private static WrappedTripletMeasure.Half singleRailGroups(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            List<ScoreNoteEvent> notes,
            List<Integer> candidates,
            boolean closedBar,
            boolean allowSingleFlat,
            byte[] labels,
            boolean thinStaffOpening,
            byte[] naturalNeighborLabels,
            boolean staffOverlap) {
        return singleRailGroups(gray, width, height, bar, notes, candidates, closedBar, allowSingleFlat,
                labels, thinStaffOpening, naturalNeighborLabels, staffOverlap, false);
    }

    private static WrappedTripletMeasure.Half singleRailGroups(
            byte[] gray, int width, int height, MeasureRegion bar, List<ScoreNoteEvent> notes,
            List<Integer> candidates, boolean closedBar, boolean allowSingleFlat, byte[] labels,
            boolean thinStaffOpening, byte[] naturalNeighborLabels, boolean staffOverlap, boolean closeFlatAndStaffCut) {
        return singleRailGroups(gray, width, height, bar, notes, candidates, closedBar, allowSingleFlat,
                labels, thinStaffOpening, naturalNeighborLabels, staffOverlap, closeFlatAndStaffCut, false);
    }

    private static WrappedTripletMeasure.Half singleRailGroups(
            byte[] gray, int width, int height, MeasureRegion bar, List<ScoreNoteEvent> notes,
            List<Integer> candidates, boolean closedBar, boolean allowSingleFlat, byte[] labels,
            boolean thinStaffOpening, byte[] naturalNeighborLabels, boolean staffOverlap,
            boolean closeFlatAndStaffCut, boolean faintClosingShaft) {
        boolean labeled = !closedBar && labels != null && labels.length == gray.length;
        boolean closedNaturalPredecessor = labeled && candidates.size() == 18;
        boolean wrapped = labeled && !closedNaturalPredecessor;
        boolean closedLabelledBar = closedBar && labels != null && labels.length == gray.length
                && candidates.size() == 18;
        boolean closedSharpBar = closedLabelledBar && !allowSingleFlat;
        var owners = candidates.stream()
                .sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure())).toList();
        float[] x = new float[owners.size()], y = new float[owners.size()];
        float left = bar.left() * width, right = bar.right() * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (!Float.isFinite(gap) || gap < 10 || gap > 30) return null;
        // Four closed triplet groups can prove rhythm even when staff pitch is inferred.
        // Four closed triplet groups can prove rhythm even when staff pitch is inferred.
        int clef = notes.get(owners.get(0)).clefBottomDiatonic();
        int reportedExtraRail = 0;
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>();
        var budget = new PrintedBeamShafts.Budget(800_000);
        PrintedBeamShaftPixels.Frame contrast = null;
        StaffPitchTrack accidentalStaff = null;
        boolean rejectedNaturalShaft = false;
        for (int i = 0; i < owners.size(); i++) {
            var n = notes.get(owners.get(i));
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED
                    || n.staffIndex() != 0
                    || n.staffCount() != 1
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || n.beamCount() < 1
                    || n.beamCount() > 2
                    || n.unbeamedDurationBeats() != 0
                    || n.crossStaffBeam()
                    || n.leadingRestBeats() != 0
                    || n.followingRestBeats() != 0
                    || n.tiedFromPrevious()
                    || n.boundaryTies() != 0
                    || (n.articulations() & NoteOrnament.GRACE) != 0
                    || (clef == ScoreNoteEvent.CLEF_UNKNOWN && !(closedBar && x.length == 12))
                    || n.clefBottomDiatonic() != clef
                    || !(n.tupletDivisor() == 1
                            || n.tupletDivisor() == 3 && n.tupletNormalNotes() == 2)
                    || Math.abs(n.stemDirection()) != 1) return null;
            if (n.beamCount() == 2 && ++reportedExtraRail > 1) return null;
            x[i] = left + (right - left) * n.positionInMeasure();
            y[i] = n.pageY() * height;
            if (n.augmentationDots() != 0
                    && !(wrapped
                            && n.augmentationDots() == 1
                            && i + 1 < owners.size()
                            && fingeringThreeOwnsDot(
                                    gray,
                                    width,
                                    height,
                                    left,
                                    right,
                                    x[i],
                                    y[i],
                                    notes.get(owners.get(i + 1)),
                                    gap))) return null;
            if (i > 0) {
                float space = x[i] - x[i - 1];
                if (space < gap * 1.65f) return null;
                if (space > gap * (closedBar ? 3.85f : 3.4f)
                        && !(closedBar
                                && x.length == 12
                                && space <= gap * 4.2f
                                && pairedFlatAfter(
                                        gray, width, height, x[i - 1], y[i - 1], x[i], gap, n)))
                    return null;
            }
            var source = PrintedBeamShafts.at(gray, width, height, x[i], y[i], gap, budget);
            if (closedBar && source.isEmpty() && !budget.refused()) {
                if (contrast == null)
                    contrast =
                            new PrintedBeamShaftPixels.Frame(
                                    gray,
                                    gray,
                                    width,
                                    height,
                                    0,
                                    units -> {
                                        if (!budget.take(units)) throw new ShaftQuotaExceeded();
                                    },
                                    true);
                try {
                    source =
                            PrintedBeamShafts.at(
                                    gray, width, height, x[i], y[i], gap, budget, contrast);
                } catch (ShaftQuotaExceeded exhausted) {
                    return null;
                }
            }
            if (faintClosingShaft && i == owners.size() - 1 && source.isEmpty() && !budget.refused()) {
                var retained = faintDownstem(gray, width, height, x[i], y[i], gap);
                if (retained != null) source = List.of(retained);
            }
            if (closedBar && i % 3 == 1 && source.isEmpty() && !budget.refused()) {
                shafts.add(null);
                continue;
            }
            if (wrapped && source.size() == 2 && i + 1 < owners.size()) {
                var next = notes.get(owners.get(i + 1));
                var unintended =
                        source.stream()
                                .filter(shaft -> shaft.direction() == n.stemDirection())
                                .findFirst();
                if (unintended.isPresent()
                        && next.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_SHARP) {
                    var rival = unintended.get();
                    float nextX = left + (right - left) * next.positionInMeasure();
                    float nextY = next.pageY() * height;
                    if (rival.left() > x[i] + gap * .55f
                            && rival.right() < nextX - gap * .85f
                            && OmrScoreInterpreter.printedSharpBeforeDyad(
                                    gray, width, height, rival.left(), nextX, nextY, gap)) {
                        source = source.stream().filter(shaft -> shaft != rival).toList();
                    }
                }
            }
            if (naturalNeighborLabels != null && source.size() == 2 && i + 1 < owners.size()) {
                var next = notes.get(owners.get(i + 1));
                float nextX = left + (right - left) * next.positionInMeasure();
                float nextY = next.pageY() * height;
                if (accidentalStaff == null)
                    accidentalStaff = WrappedMeasureBoundaries.verifiedTripletStaff(gray, width, height, bar);
                if (accidentalStaff != null && next.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL) {
                    var rival = source.stream().filter(a -> a.direction() == n.stemDirection()).findFirst();
                    if (rival.isPresent() && followingNaturalOwnsShaft(
                            gray, naturalNeighborLabels, width, height, accidentalStaff,
                            rival.get(), x[i], nextX, nextY, gap)) {
                        var owned = rival.get();
                        source = source.stream().filter(a -> a != owned).toList();
                        rejectedNaturalShaft = true;
                    }
                }
            }
            if (source.size() != 1 || source.get(0).direction() != -n.stemDirection()) return null;
            shafts.add(source.get(0));
        }
        if (naturalNeighborLabels != null && !rejectedNaturalShaft) return null;
        if (wrapped) {
            for (int middle = 1; middle < x.length; middle += 3) {
                var earlier = shafts.get(middle - 1);
                var axis = shafts.get(middle);
                var later = shafts.get(middle + 1);
                if (earlier.direction() != axis.direction()
                        || later.direction() != axis.direction()
                        || singleRail(gray, width, height, earlier, axis, gap)
                                && singleRail(gray, width, height, axis, later, gap)) continue;
                var adjusted =
                        trimExtendedMiddleShaft(gray, width, height, earlier, axis, later, gap);
                if (adjusted != null) shafts.set(middle, adjusted);
            }
        }
        if (x.length == 18) {
            for (int middle = 1; middle < x.length; middle += 3) {
                var earlier = shafts.get(middle - 1);
                var axis = shafts.get(middle);
                var later = shafts.get(middle + 1);
                if (earlier == null
                        || axis == null
                        || later == null
                        || axis.pixels() == null
                        || earlier.direction() != axis.direction()
                        || later.direction() != axis.direction()) continue;
                if (singleRail(gray, width, height, earlier, axis, gap)
                        && singleRail(gray, width, height, axis, later, gap)) continue;
                var continued = continueMiddleShaft(gray, width, height, earlier, axis, later, gap);
                if (continued != null) shafts.set(middle, continued);
            }
        }
        boolean flatOwnsDot = false;
        boolean naturalOwnsDot = false;
        boolean sharpOwnsDot = false;
        int staffRailLinks = 0, staffCuts = 0;
        boolean closeFlat = false;
        StaffPitchTrack beamStaff = null;
        for (int i = 0; i + 1 < x.length; i++) {
            var dot =
                    WrappedTripletDotInk.uniqueCore(gray, width, height, x[i], y[i], x[i + 1], gap);
            boolean ownedNatural = (labeled || naturalNeighborLabels != null) && dot != null
                    && notes.get(owners.get(i + 1)).writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL
                    && naturalOwnsDot(gray, naturalNeighborLabels != null ? naturalNeighborLabels : labels,
                            width, height, dot, x[i + 1], y[i + 1], gap);
            naturalOwnsDot |= ownedNatural;
            boolean ownedSharp = closedSharpBar
                    && dot != null
                    && notes.get(owners.get(i + 1)).writtenAccidental()
                            == ScoreNoteEvent.ACCIDENTAL_SHARP
                    && closedBarSharpOwnsDot(gray, labels, width, height, dot, x[i + 1], y[i + 1], gap);
            sharpOwnsDot |= ownedSharp;
            boolean closeFlatOwns = closeFlatAndStaffCut && dot != null
                    && notes.get(owners.get(i + 1)).writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FLAT
                    && compactFlatOwnsCore(gray, width, height, dot, x[i + 1], y[i + 1], gap);
            closeFlat |= closeFlatOwns;
            if (dot != null && !closeFlatOwns
                    && !(wrapped
                            && highLedgerOwnsDot(
                                    gray, labels, width, height, bar, x[i], y[i], dot, gap))
                    && !(wrapped
                            && notes.get(owners.get(i + 1)).writtenAccidental()
                                    == ScoreNoteEvent.ACCIDENTAL_SHARP
                            && sharpOwnsDot(
                                    gray, labels, width, height, x[i], y[i], dot, x[i + 1],
                                    y[i + 1], gap))
                    && !ownedNatural
                    && !ownedSharp
                    && (x.length != 18 && !staffOverlap
                            || !headConnectedLedger(gray, width, height, x[i], y[i], dot, gap))
                    && !(x.length == 12
                            && closedBar
                            && notes.get(owners.get(i + 1)).writtenAccidental()
                                    == ScoreNoteEvent.ACCIDENTAL_FLAT
                            && WrappedTripletDotInk.pairedFlatOwns(
                                    gray, width, height, dot, gap))) {
                if (!allowSingleFlat
                        || notes.get(owners.get(i + 1)).writtenAccidental()
                                != ScoreNoteEvent.ACCIDENTAL_FLAT
                        || !WrappedTripletDotInk.singleFlatOwns(gray, width, height, dot, x[i + 1], y[i + 1], gap)
                                && !(closedLabelledBar && WrappedTripletDotInk.labelledFlatOwns(
                                        labels, gray, width, height, dot, x[i + 1], y[i + 1], gap))) return null;
                flatOwnsDot = true;
            }
            var a = shafts.get(i);
            var b = shafts.get(i+1);
            if (a == null || b == null) {
                int middle = a == null ? i : i + 1;
                int stem = notes.get(owners.get(middle)).stemDirection();
                if (stem != notes.get(owners.get(i)).stemDirection()
                        || stem != notes.get(owners.get(i+1)).stemDirection()
                        || !PrintedTupletBeamOwner.connectedHeads(
                                gray, width, height, x[i], y[i], x[i+1], y[i+1], gap, stem)
                        || !singleRail(gray, width, height, shafts.get(middle-1), shafts.get(middle+1), gap))
                    return null;
                continue;
            }
            boolean same = a.direction() == b.direction();
            if (i % 3 == 2) {
                if (same) {
                    var ribbon = bestRibbon(gray, width, height, a, b, gap);
                    if (ribbon.coverage() > .46f) {
                        boolean staffRule = closedBar
                                && (x.length == 12
                                        && staffRuleOnlyCut(gray, width, height, bar, a, b, gap, ribbon, false)
                                    || thinStaffOpening && x.length == 18
                                        && thinStaffRuleBetweenRails(gray, width, height, bar, a, b, gap, ribbon));
                        if (!staffRule && closeFlatAndStaffCut
                                && continuingStaffOnlyCut(gray, width, height, bar, a, b, gap, ribbon)) {
                            staffRule = true;
                            staffCuts++;
                        }
                        if (!staffRule) return null;
                    }
                }
                continue;
            }
            if (!same) return null;
            if (!singleRail(gray, width, height, a, b, gap)) {
                // A literal 6/4 staff opener can place more of its six triplets over
                // staff rules. Each apparent extra rail must still match its local rule.
                int staffRailLimit = thinStaffOpening ? 6 : 2;
                if (!staffOverlap || a.direction() != 1 || staffRailLinks >= staffRailLimit) return null;
                if (beamStaff == null)
                    beamStaff =
                            WrappedMeasureBoundaries.closedTripletStaff(gray, width, height, bar);
                if (beamStaff == null
                        || !secondaryRibbonIsStaffRule(gray, width, height, a, b, gap, beamStaff))
                    return null;
                staffRailLinks++;
            }
        }
        return (!closeFlatAndStaffCut || closeFlat && staffCuts > 0)
                        && (!staffOverlap || staffRailLinks > 0)
                        && (!allowSingleFlat || flatOwnsDot)
                        && (!closedNaturalPredecessor || naturalOwnsDot)
                        && (!closedSharpBar || sharpOwnsDot)
                ? new WrappedTripletMeasure.Half(owners, x)
                : null;
    }

    private static PrintedBeamShafts.Shaft faintDownstem(
            byte[] gray, int width, int height, float x, float y, float gap) {
        int left = Math.round(x - gap * .79f), right = Math.round(x - gap * .43f);
        int start = Math.round(y + gap * .22f), limit = Math.round(y + gap * 3.05f);
        if (left < 4 || right + 4 >= width || start < 0 || limit >= height) return null;
        var columns = new ArrayList<PrintedBeamShafts.Column>();
        for (int col = left; col <= right; col++) {
            int faint = 0, longest = 0, run = 0, row = start;
            for (; row <= limit; row++) {
                int pixel = row * width + col, value = gray[pixel] & 255;
                if (value < 165) {
                    run = 0;
                    continue;
                }
                int a = gray[pixel - 4] & 255, b = gray[pixel + 4] & 255;
                if (value > 238 || Math.max(a, b) - value < 15) break;
                faint++;
                longest = Math.max(longest, ++run);
            }
            if (row > limit || row - 1 - y < gap * 1.86f || faint == 0 || faint > gap * .45f
                    || longest > gap * .25f) continue;
            columns.add(new PrintedBeamShafts.Column(col, row - 1));
        }
        if (columns.size() != 2 || columns.get(0).x() + 1 != columns.get(1).x()) return null;
        var a = columns.get(0);
        var b = columns.get(1);
        int end = Math.min(a.endY(), b.endY());
        if (Math.abs(a.endY() - b.endY()) > 2) return null;
        return new PrintedBeamShafts.Shaft(a.x(), b.x(), start, end, 1, columns);
    }

    /** Keep a proved beam endpoint when the scanner continues into adjacent vertical ink. */
    static PrintedBeamShafts.Shaft trimExtendedMiddleShaft(
            byte[] gray,
            int width,
            int height,
            PrintedBeamShafts.Shaft a,
            PrintedBeamShafts.Shaft middle,
            PrintedBeamShafts.Shaft c,
            float gap) {
        int x0 = (a.left() + a.right()) / 2;
        int x = (middle.left() + middle.right()) / 2;
        int x1 = (c.left() + c.right()) / 2;
        if (x0 >= x || x >= x1 || !singleRail(gray, width, height, a, c, gap)) return null;
        int end = Math.round(a.endY() + (c.endY() - a.endY()) * (x - x0) / (float) (x1 - x0));
        int direction = middle.direction();
        float overrun = (middle.endY() - end) * direction;
        if (overrun < gap * .72f || overrun > gap * 1.55f) return null;
        int inner = end - direction * Math.round(gap * .45f);
        if (inner < 0 || inner >= height || end < 0 || end >= height) return null;
        for (int y = inner; (end - y) * direction >= 0; y += direction) {
            boolean occupied = false;
            for (int col = middle.left(); col <= middle.right(); col++)
                occupied |= (gray[y * width + col] & 255) < 165;
            if (!occupied) return null;
        }
        var shortened =
                new PrintedBeamShafts.Shaft(
                        middle.left(),
                        middle.right(),
                        middle.startY(),
                        end,
                        direction,
                        middle.columns());
        return singleRail(gray, width, height, a, shortened, gap)
                        && singleRail(gray, width, height, shortened, c, gap)
                ? shortened
                : null;
    }

    /** A sharp's shaft can be slightly off-center inside the apparent dot window. */
    static boolean closedBarSharpOwnsDot(
            byte[] gray,
            byte[] labels,
            int width,
            int height,
            WrappedTripletDotInk.Center core,
            float nextX,
            float nextY,
            float gap) {
        int y = core.y();
        int offset = y * width;
        if (labels[offset + core.x()] != OmrMeasurePostProcessor.CLEF_OR_KEY
                || nextX - core.x() < gap * 1.35f
                || nextX - core.x() > gap * 2.15f
                || y - nextY < gap * .80f
                || y - nextY > gap * 1.55f) return false;
        int tolerance = Math.round(gap * .12f);
        int left = Math.max(0, core.x() - tolerance);
        int right = Math.min(width - 1, core.x() + tolerance);
        for (int x = left; x <= right; x++) {
            if (labels[offset + x] == OmrMeasurePostProcessor.CLEF_OR_KEY
                    && (gray[offset + x] & 255) < 150
                    && OmrScoreInterpreter.printedSharpBeforeDyad(
                            gray, width, height, x, nextX, nextY, gap)) return true;
        }
        return false;
    }

    /** The core lies on the next pitch's printed sharp or on the current head's ledger tip. */
    private static boolean sharpOwnsDot(
            byte[] gray,
            byte[] labels,
            int width,
            int height,
            float headX,
            float headY,
            WrappedTripletDotInk.Center core,
            float nextX,
            float nextY,
            float gap) {
        if (nextX - core.x() < gap * 1.35f
                || nextX - core.x() > gap * 2.15f
                || core.y() - nextY < gap * .80f
                || core.y() - nextY > gap * 1.55f
                || !OmrScoreInterpreter.printedSharpBeforeDyad(
                        gray, width, height, core.x(), nextX, nextY, gap)) return false;
        int label = labels[core.y() * width + core.x()] & 255;
        if (label == OmrMeasurePostProcessor.CLEF_OR_KEY) return true;
        if (label != OmrMeasurePostProcessor.SYMBOL || Math.abs(headY - core.y()) > gap * .24f)
            return false;
        int left = Math.round(headX + gap * .46f);
        for (int y = core.y() - 1; y <= core.y() + 1; y++) {
            boolean linked = true;
            for (int x = left; x <= core.x(); x++) linked &= (gray[y * width + x] & 255) < 155;
            if (linked) return true;
        }
        return false;
    }

    /** A detached natural may cross the previous note's stem search window. */
    static boolean followingNaturalOwnsShaft(byte[] gray, byte[] labels, int width, int height,
            StaffPitchTrack staff, PrintedBeamShafts.Shaft rival, float earlierX, float nextX,
            float nextY, float gap) {
        if (rival.right() - rival.left() + 1 > gap * .33f || rival.left() - earlierX < gap * .58f
                || nextX - rival.right() < gap * 1.15f || nextX - rival.right() > gap * 2.35f)
            return false;
        int left = Math.round(rival.left() - gap * .10f);
        int right = Math.round(nextX - gap * .80f);
        int x = (left + right) / 2;
        if (left < 0 || right >= width || !staff.verifiedAt(x)) return false;
        var rails = staff.at(x);
        float localGap = rails[1];
        int top = Math.max(0, Math.round(nextY - 1.9f * localGap));
        int bottom = Math.min(height - 1, Math.round(nextY + 1.9f * localGap));
        int support = 0;
        for (int y = top; y <= bottom; y++) {
            for (int col = left; col <= right; col++) {
                int pixel = y * width + col;
                if (labels[pixel] == OmrMeasurePostProcessor.CLEF_OR_KEY && (gray[pixel] & 255) < 170)
                    support++;
            }
        }
        return support >= localGap * localGap * .16f
                && OmrScoreInterpreter.provesNaturalMeterInk(
                        gray, width, height, left, right, nextY,
                        localGap, Math.round(rails[0] - 4 * localGap));
    }

    /** The dot-window stroke belongs to the next attack's literal natural sign. */
    static boolean naturalOwnsDot(
            byte[] gray,
            byte[] labels,
            int width,
            int height,
            WrappedTripletDotInk.Center core,
            float nextX,
            float nextY,
            float gap) {
        if (labels == null || labels.length != gray.length || gap < 6 || gap > 32
                || nextX - core.x() < gap * .94f || nextX - core.x() > gap * 2.30f
                || Math.abs(nextY - core.y()) > gap * 1.48f
                || labels[core.y() * width + core.x()] != OmrMeasurePostProcessor.CLEF_OR_KEY)
            return false;
        int left = Math.max(0, Math.round(nextX - gap * 3.10f));
        int top = Math.max(0, Math.round(nextY - gap * 2.22f));
        int right = Math.min(width - 1, Math.round(nextX - gap * .64f));
        int bottom = Math.min(height - 1, Math.round(nextY + gap * 1.92f));
        if (core.x() < left || core.x() > right || core.y() < top || core.y() > bottom)
            return false;
        int w = right - left + 1, h = bottom - top + 1;
        byte[] semantic = new byte[w * h];
        java.util.Arrays.fill(semantic, (byte) 255);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int source = (top + y) * width + left + x;
                if (labels[source] == OmrMeasurePostProcessor.CLEF_OR_KEY
                        && (gray[source] & 255) < 170) semantic[y * w + x] = 0;
            }
        }
        var components = RawNumeralComponents.capture(
                semantic, w, h, 0, 0, w - 1, h - 1, () -> {}, () -> {});
        int corePixel = (core.y() - top) * w + core.x() - left;
        for (var glyph : components.components()) {
            boolean containsCore = false;
            for (int i = 0; i < glyph.size(); i++) containsCore |= glyph.pixel(i) == corePixel;
            if (!containsCore) continue;
            java.util.Arrays.fill(semantic, (byte) 255);
            for (int i = 0; i < glyph.size(); i++) semantic[glyph.pixel(i)] = 0;
            return OmrScoreInterpreter.provesNaturalMeterInk(
                    semantic, w, h, glyph.left, glyph.right, nextY - top, gap, h + 1);
        }
        return false;
    }

    /** A clipped lower lobe can be part of a completely printed fingering above the next note. */
    static boolean fingeringThreeOwnsDot(
            byte[] gray,
            int width,
            int height,
            float left,
            float right,
            float headX,
            float headY,
            ScoreNoteEvent next,
            float gap) {
        if (next.stemDirection() != -1 || next.visualSize() != ScoreNoteEvent.VisualSize.FULL)
            return false;
        float nextX = left + (right - left) * next.positionInMeasure();
        float nextY = next.pageY() * height;
        if (nextX - headX < gap * 1.65f
                || nextX - headX > gap * 2.45f
                || nextY - headY < gap * 1.05f
                || nextY - headY > gap * 1.75f
                || WrappedTripletDotInk.uniqueCore(gray, width, height, headX, headY, nextX, gap)
                        != null) return false;
        int l = Math.round(nextX - gap * .84f), r = Math.round(nextX + gap * .84f);
        int t = Math.round(nextY - gap * 2.65f), b = Math.round(nextY - gap * .66f);
        if (l < 0 || r >= width || t < 0 || b >= height) return false;
        var shape =
                RawNumeralComponents.capture(
                        gray, width, height, l, t, r, b, 139, () -> {}, () -> {});
        int owners = 0;
        for (var ink : shape.components()) {
            float x = (ink.left + ink.right) * .5f;
            float y = (ink.top + ink.bottom) * .5f;
            if (ink.left <= l
                    || ink.right >= r
                    || ink.top <= t
                    || ink.bottom >= b
                    || Math.abs(x - nextX) > gap * .35f
                    || Math.abs(y - headY) > gap * .39f
                    || ink.bottom - ink.top + 1 < gap * .7f
                    || ink.bottom - ink.top + 1 > gap * 1.5f
                    || !TripletRhythmDetector.isIsolatedThree(ink, width)) continue;
            owners++;
        }
        return owners == 1;
    }

    /** A short, head-connected ledger tip is part of the printed pitch. */
    static boolean highLedgerOwnsDot(
            byte[] gray,
            byte[] labels,
            int width,
            int height,
            MeasureRegion bar,
            float headX,
            float headY,
            WrappedTripletDotInk.Center core,
            float gap) {
        float staffTop = bar.bottom() * height - gap * 6.1f;
        return headY < staffTop - gap * .8f
                && labels[core.y() * width + core.x()] == OmrMeasurePostProcessor.SYMBOL
                && Math.abs(headY - core.y()) <= gap * .25f
                && headConnectedLedger(gray, width, height, headX, headY, core, gap, .48f);
    }

    private static boolean pairedFlatAfter(
            byte[] gray,
            int width,
            int height,
            float x,
            float y,
            float next,
            float gap,
            ScoreNoteEvent following) {
        if (following.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT) return false;
        var dot = WrappedTripletDotInk.uniqueCore(gray, width, height, x, y, next, gap);
        return dot != null && WrappedTripletDotInk.pairedFlatOwns(gray, width, height, dot, gap);
    }

    /** A compact ledger cap can fill the same dark window as an augmentation dot. */
    private static boolean headConnectedLedger(
            byte[] gray,
            int width,
            int height,
            float headX,
            float headY,
            WrappedTripletDotInk.Center core,
            float gap) {
        return headConnectedLedger(gray, width, height, headX, headY, core, gap, .35f);
    }

    static boolean headConnectedLedger(
            byte[] gray,
            int width,
            int height,
            float headX,
            float headY,
            WrappedTripletDotInk.Center core,
            float gap,
            float maxTip) {
        int left = Math.round(headX - gap), right = Math.round(core.x() + gap * .4f);
        int top = Math.round(Math.min(core.y(), headY) - gap * .65f);
        int bottom = Math.round(Math.max(core.y(), headY) + gap * .65f);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return false;
        var graph =
                RawNumeralComponents.capture(
                        gray, width, height, left, top, right, bottom, 100, () -> {}, () -> {});
        int radius = Math.max(1, Math.round(gap * .13f));
        int cx = Math.round(headX), cy = Math.round(headY);
        int leftLedger = Math.round(headX - gap * .60f);
        int dark = 0;
        for (int x = leftLedger; x < cx; x++) {
            for (int y = core.y() - 2; y <= core.y() + 2; y++) {
                if ((gray[y * width + x] & 255) > 120) continue;
                dark++;
                break;
            }
        }
        if (dark < (cx - leftLedger) * .83f) return false;
        if (!taperingLedgerTip(gray, width, headX, core, gap, maxTip)) return false;
        for (var glyph : graph.components()) {
            if (core.x() < glyph.left
                    || core.x() > glyph.right
                    || core.y() < glyph.top
                    || core.y() > glyph.bottom
                    || glyph.left > leftLedger + 1
                    || glyph.size() < gap * gap * .55f) continue;
            int center = 0;
            boolean containsCore = false;
            for (int i = 0; i < glyph.size(); i++) {
                int pixel = glyph.pixel(i), x = pixel % width, y = pixel / width;
                containsCore |= x == core.x() && y == core.y();
                if (Math.abs(x - cx) <= radius && Math.abs(y - cy) <= radius) center++;
            }
            if (containsCore && center >= radius * radius * 4) return true;
        }
        return false;
    }

    private static boolean taperingLedgerTip(
            byte[] gray,
            int width,
            float headX,
            WrappedTripletDotInk.Center core,
            float gap,
            float maxTip) {
        int prior = Integer.MAX_VALUE;
        int thinFrom = Math.round(headX + gap * .74f);
        int right = core.x() + Math.round(gap * .28f);
        int radius = Math.round(gap * .37f);
        int thicknessAtCore = 0, occupied = 0;
        for (int x = thinFrom; x <= right; x++) {
            int thickness = 0;
            for (int y = core.y() - radius; y <= core.y() + radius; y++)
                if ((gray[y * width + x] & 255) < 155) thickness++;
            if (prior != Integer.MAX_VALUE && thickness > prior + 1) return false;
            if (x <= core.x() && thickness == 0) return false;
            if (x == core.x()) thicknessAtCore = thickness;
            if (x > core.x() && thickness > 0) occupied++;
            prior = thickness;
        }
        return thicknessAtCore <= gap * maxTip && occupied >= 2;
    }

    /** Keep a head-connected middle shaft only if its ink reappears at both flanks' real rail. */
    private static PrintedBeamShafts.Shaft continueMiddleShaft(
            byte[] gray,
            int width,
            int height,
            PrintedBeamShafts.Shaft earlier,
            PrintedBeamShafts.Shaft axis,
            PrintedBeamShafts.Shaft later,
            float gap) {
        int x0 = (earlier.left() + earlier.right()) / 2;
        int x = (axis.left() + axis.right()) / 2;
        int x1 = (later.left() + later.right()) / 2;
        int direction = axis.direction();
        if (!(x0 < x && x < x1) || !singleRail(gray, width, height, earlier, later, gap))
            return null;
        int cap =
                Math.round(
                        earlier.endY()
                                + (later.endY() - earlier.endY()) * (x - x0) / (float) (x1 - x0));
        int remaining = (cap - axis.endY()) * direction;
        if (remaining < gap * .75f || remaining > gap * 2.25f) return null;
        int endpoint = cap - direction * Math.round(gap * .15f);
        int shoulder = Math.round(gap * .25f);
        int first = axis.endY() + direction;
        if (first < 0
                || first >= height
                || endpoint < 0
                || endpoint >= height
                || axis.left() < 0
                || axis.right() >= width
                || x < shoulder
                || x + shoulder >= width) return null;
        int missing = 0;
        for (int y = first; direction * (endpoint - y) >= 0; y += direction) {
            boolean ink = false;
            for (int column = axis.left(); column <= axis.right(); column++)
                ink |= (gray[y * width + column] & 255) < 160;
            if (!ink && ++missing > 2) return null;
        }
        for (int column = x - shoulder; column <= x + shoulder; column++)
            if ((gray[endpoint * width + column] & 255) >= 155) return null;
        var revised =
                new PrintedBeamShafts.Shaft(
                        axis.left(), axis.right(), axis.startY(), cap, direction, axis.columns());
        return singleRail(gray, width, height, earlier, revised, gap)
                        && singleRail(gray, width, height, revised, later, gap)
                ? revised
                : null;
    }

    static boolean matchesRailBridge(
            byte[] gray,
            int width,
            int height,
            PrintedBeamShafts.Shaft first,
            PrintedBeamShafts.Shaft last,
            float gap,
            boolean connected) {
        return connected
                ? singleRail(gray, width, height, first, last, gap)
                : bestRibbon(gray, width, height, first, last, gap).coverage() <= .46f;
    }

    static boolean matchesBeamRails(byte[] gray, int width, int height,
            PrintedBeamShafts.Shaft left, PrintedBeamShafts.Shaft right, float gap, int rails) {
        return rails == 0 ? matchesRailBridge(gray, width, height, left, right, gap, false)
                : matchesRailCount(gray, width, height, left, right, gap, rails == 2);
    }

    /** Verify both bands on joined shafts and reject a further thick band off the staff rules. */
    static boolean matchesExactlyTwoBeamRails(
            byte[] gray, int width, int height, PrintedBeamShafts.Shaft a,
            PrintedBeamShafts.Shaft b, float gap, StaffPitchTrack staff) {
        var outer = bestRibbon(gray, width, height, a, b, gap);
        if (outer.coverage() < .9f) return false;
        int side = -a.direction(), inner = 0;
        float best = .87f;
        for (float separation : new float[]{.69f, .84f, .99f, 1.14f}) {
            int offset = side * Math.round(gap * separation);
            float confidence = coverage(gray, width, height, a, b, gap,
                    outer.leftY() + offset, outer.rightY() + offset, .19f);
            if (confidence > best) {
                best = confidence;
                inner = offset;
            }
        }
        if (inner == 0) return false;
        int first = (a.left() + a.right()) / 2, last = (b.left() + b.right()) / 2;
        for (int offset = Math.round(gap * .68f); offset <= Math.round(gap * 1.35f); offset++) {
            int shift = inner + side * offset;
            int[] rules = new int[last - first + 1];
            for (int x = first; x <= last; x++) {
                var frame = staff.at(x);
                int expected = outer.leftY() + shift
                        + Math.round((outer.rightY() - outer.leftY()) * (x - first) / (float) (last - first));
                int closest = Math.round((frame[0] - expected) / frame[1]);
                rules[x - first] = closest >= 0 && closest <= 4
                        ? Math.round(frame[0] - closest * frame[1]) : -width;
            }
            if (coverage(gray, width, height, a, b, gap, outer.leftY() + shift, outer.rightY() + shift,
                    .19f, new RuleBand(first, rules, Math.round(gap * .15f))) >= .65f) return false;
        }
        return true;
    }

    private static boolean singleRail(byte[] gray, int width, int height,
            PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, float gap) {
        return matchesRailCount(gray, width, height, a, b, gap, false);
    }

    private static boolean matchesRailCount(
            byte[] gray,
            int width,
            int height,
            PrintedBeamShafts.Shaft a,
            PrintedBeamShafts.Shaft b,
            float gap,
            boolean doubleRail) {
        var ribbon = bestRibbon(gray, width, height, a, b, gap);
        if (ribbon.coverage() < .9f) return false;
        int side = -a.direction();
        for (float separation : new float[] {.69f, .84f, .99f, 1.14f}) {
            int dy = side * Math.round(gap * separation);
            if (coverage(
                            gray,
                            width,
                            height,
                            a,
                            b,
                            gap,
                            ribbon.leftY() + dy,
                            ribbon.rightY() + dy,
                            .19f)
                    >= .88f) return doubleRail;
        }
        return !doubleRail;
    }

    /** Count a beam crossing a staff rule only after rejecting every apparent second rail as thin staff ink. */
    private static boolean secondaryRibbonIsStaffRule(
            byte[] gray,
            int width,
            int height,
            PrintedBeamShafts.Shaft a,
            PrintedBeamShafts.Shaft b,
            float gap,
            StaffPitchTrack staff) {
        var ribbon = bestRibbon(gray, width, height, a, b, gap);
        if (ribbon.coverage() < .90f) return false;
        int left = (a.left() + a.right()) / 2, right = (b.left() + b.right()) / 2;
        int margin = Math.round(gap * .40f), start = left + margin, end = right - margin;
        if (end <= start || !staff.verifiedAt(left) || !staff.verifiedAt(right)) return false;
        boolean observed = false;
        for (float separation : new float[] {.69f, .84f, .99f, 1.14f}) {
            int offset = Math.round(gap * separation);
            int y0 = ribbon.leftY() - offset, y1 = ribbon.rightY() - offset;
            if (coverage(gray, width, height, a, b, gap, y0, y1, .19f) < .88f) continue;
            observed = true;
            var middle = staff.at((left + right) * .5f);
            int rule = Math.round((middle[0] - (y0 + y1) * .5f) / middle[1]);
            if (rule < 0 || rule >= 5) return false;
            int clean = 0, band = Math.round(.28f * gap), thin = Math.round(.26f * gap);
            int neighbor = rule < 4 ? rule + 1 : rule - 1;
            for (int x = start; x <= end; x++) {
                var frame = staff.at(x);
                int y = Math.round(frame[0] - rule * frame[1]);
                int adjacent = Math.round(frame[0] - neighbor * frame[1]);
                float apparent = y0 + (y1 - y0) * (x - left) / (float) (right - left);
                int ink = ruleThickness(gray, width, height, x, y, band);
                int other = ruleThickness(gray, width, height, x, adjacent, band);
                if (Math.abs(apparent - y) <= .32f * gap
                        && ink > 0
                        && ink <= thin
                        && other > 0
                        && other <= thin
                        && Math.abs(ink - other) <= 2) clean++;
            }
            if (clean < .88f * (end - start + 1)) return false;
        }
        return observed;
    }

    static boolean mixedCut(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            ScoreNoteEvent left,
            ScoreNoteEvent right) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (!Float.isFinite(gap)
                || gap < 10
                || gap > 30
                || width < 1
                || height < 1
                || gray == null
                || gray.length != (long) width * height) return false;
        float ax = (bar.left() + (bar.right() - bar.left()) * left.positionInMeasure()) * width;
        float bx = (bar.left() + (bar.right() - bar.left()) * right.positionInMeasure()) * width;
        if (bx - ax < gap * 1.65f || bx - ax > gap * 4) return false;
        var budget = new PrintedBeamShafts.Budget(150_000);
        var a = PrintedBeamShafts.at(gray, width, height, ax, left.pageY() * height, gap, budget);
        var b = PrintedBeamShafts.at(gray, width, height, bx, right.pageY() * height, gap, budget);
        return a.size() == 1
                && b.size() == 1
                && a.get(0).direction() == -left.stemDirection()
                && b.get(0).direction() == -right.stemDirection()
                && (a.get(0).direction() != b.get(0).direction()
                        || bestRibbon(gray, width, height, a.get(0), b.get(0), gap).coverage()
                                <= .46f);
    }

    static WrappedTripletMeasure.Half doubleRailGroups(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            List<ScoreNoteEvent> notes,
            List<Integer> candidates) {
        if (candidates.size() != 8) return null;
        return mixedRailGroups(gray, width, height, bar, notes, candidates, 4, true);
    }

    static WrappedTripletMeasure.Half mixedOpeningGroups(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            List<ScoreNoteEvent> notes,
            List<Integer> candidates) {
        if (candidates.isEmpty() || candidates.size() % 3 != 0) return null;
        return mixedRailGroups(gray, width, height, bar, notes, candidates, 3, false);
    }

    /** A stemless prediction inside a proved sloped rail has no independent head lobes. */
    static boolean slopedTripletOwnsBeamAlias(byte[] gray, int width, int height, MeasureRegion bar,
            ScoreNoteEvent first, ScoreNoteEvent middle, ScoreNoteEvent last, ScoreNoteEvent alias) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25) return false;
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float x0 = left + span * first.positionInMeasure(), x1 = left + span * middle.positionInMeasure();
        float x2 = left + span * last.positionInMeasure();
        float xx = left + span * alias.positionInMeasure(), yy = alias.pageY() * height;
        if (xx - x1 < gap * .2f || xx - x1 > gap * .68f
                || x1 - x0 < gap * 2 || x2 - x1 < gap * 2
                || x1 - x0 > gap * 4 || x2 - x1 > gap * 4
                || yy - middle.pageY() * height < gap * 3.2f) return false;
        var budget = new PrintedBeamShafts.Budget(500_000);
        var a = PrintedBeamShafts.at(gray, width, height, x0, first.pageY() * height, gap, budget);
        var c = PrintedBeamShafts.at(gray, width, height, x2, last.pageY() * height, gap, budget);
        if (a.size() != 1 || c.size() != 1 || a.get(0).direction() != 1 || c.get(0).direction() != 1)
            return false;
        var start = a.get(0);
        var end = c.get(0);
        var rail = bestRibbon(gray, width, height, start, end, gap);
        int startX = (start.left() + start.right()) / 2, endX = (end.left() + end.right()) / 2;
        float slope = (rail.rightY() - rail.leftY()) / (float) (endX - startX);
        if (rail.coverage() < .96f || slope > -.11f || slope < -.33f
                || !matchesRailCount(gray, width, height, start, end, gap, false)
                || Math.abs(yy - (rail.leftY() + slope * (xx - startX))) > gap * .27f) return false;
        var b = ClosedOctaveTripletBars.contrastShaft(
                gray, width, height, x1, middle.pageY() * height, gap, budget);
        if (b.size() != 1 || b.get(0).direction() != 1
                || !PrintedBeamShafts.at(gray, width, height, xx, yy, gap, budget).isEmpty())
            return false;
        var shaft = b.get(0);
        int center = (shaft.left() + shaft.right()) / 2;
        float expected = rail.leftY() + slope * (center - startX);
        if (expected - shaft.endY() < gap * .40f || expected - shaft.endY() > gap * .86f)
            return false;
        // Only the short fade immediately before the same rail may separate the middle shaft.
        int step = (int) Math.ceil(gap * .21f);
        for (var column : shaft.columns()) {
            boolean touches = false;
            for (int y = column.endY() + 1; y <= Math.min(height - 1, column.endY() + step); y++) {
                if ((gray[y * width + column.x()] & 255) < 155) {
                    touches = true;
                    break;
                }
            }
            if (!touches) return false;
        }

        var track = WrappedMeasureBoundaries.verifiedTripletStaff(gray, width, height, bar);
        if (track == null || !track.verifiedAt(xx)) return false;
        int from = Math.round(xx - gap * .66f), to = Math.round(xx + gap * .66f);
        int ribbonHalf = Math.round(gap * .55f), ruledHalf = Math.round(gap * .13f);
        if (from < 0 || to >= width) return false;
        int darkColumns = 0;
        int radius = Math.round(gap * 1.12f);
        for (int x = from; x <= to; x++) {
            int row = Math.round(rail.leftY() + slope * (x - startX));
            if (row - radius < 0 || row + radius >= height) return false;
            float[] staff = track.at(x);
            if ((gray[row * width + x] & 255) < 155) darkColumns++;
            for (int y = row - radius; y <= row + radius; y++) {
                if (Math.abs(y - row) <= ribbonHalf || (gray[y * width + x] & 255) >= 155) continue;
                int rule = Math.round((staff[0] - y) / staff[1]);
                if (rule < 0 || rule > 4 || Math.abs(y - (staff[0] - rule * staff[1])) > ruledHalf)
                    return false;
            }
        }
        return darkColumns >= (to - from + 1) * .94f;
    }

    /** Count actual thick beam bands on a ruled staff before using them as rhythmic groups. */
    static boolean provesUpperLedgerRailSequence(byte[] gray, int width, int height,
            List<MeasureRegion> bars, int measure, List<ScoreNoteEvent> notes,
            List<Integer> owners, boolean mixed) {
        var bar = bars.get(measure);
        var track = WrappedMeasureBoundaries.verifiedTripletStaff(gray, width, height, bar);
        if (track == null) return false;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25) return false;
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(owners.size());
        var budget = new PrintedBeamShafts.Budget(800_000);
        float[] xs = new float[owners.size()], ys = new float[owners.size()];
        for (int i = 0; i < owners.size(); i++) {
            var note = notes.get(owners.get(i));
            float x = (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
            float y = note.pageY() * height;
            var staff = track.at(x);
            boolean verified = track.verifiedAt(x)
                    || i == owners.size() - 1 && track.verifiedAt(x - gap * 2);
            if (note.stemDirection() != -1 || !verified
                    || Math.abs(staff[1] - gap) > gap * .14f
                    || y > staff[0] - staff[1] * 4 - gap * .80f
                    || i > 0 && (x - xs[i - 1] < gap * 1.65f || x - xs[i - 1] > gap * 3.7f))
                return false;
            var source = PrintedBeamShafts.at(gray, width, height, x, y, gap, budget);
            if (source.size() != 1 || source.get(0).direction() != 1) return false;
            shafts.add(source.get(0));
            xs[i] = x;
            ys[i] = y;
        }
        float first = xs[0], last = xs[xs.length - 1];
        if (mixed ? !WrappedMeasureBoundaries.confirmsInitialStaffBar(
                            gray, width, height, bars, measure, first, last)
                : !WrappedMeasureBoundaries.confirmsClosedTripletBar(
                            gray, width, height, bar, first, last)) return false;
        for (int i = 0; i + 1 < xs.length; i++) {
            var dot = WrappedTripletDotInk.uniqueCore(gray, width, height, xs[i], ys[i], xs[i + 1], gap);
            if (dot != null && !headConnectedLedger(gray, width, height, xs[i], ys[i], dot, gap, .48f))
                return false;
            int rails = i < 6 || !mixed ? (i % 3 == 2 ? 0 : 1) : (i == 9 ? 0 : 2);
            if (!trackedRailCount(gray, width, height, track, shafts.get(i), shafts.get(i + 1),
                            gap, rails)) return false;
        }
        return true;
    }

    static boolean trackedRailCount(byte[] gray, int width, int height,
            StaffPitchTrack track, PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b,
            float gap, int expected) {
        int left = (a.left() + a.right()) / 2, right = (b.left() + b.right()) / 2;
        int margin = Math.round(gap * .50f), radius = Math.round(gap * .34f);
        int count = right - left + 1 - 2 * margin, minimum = (int) Math.ceil(gap * .37f);
        if (count < gap * .65f) return false;
        int matching = 0;
        for (int x = left + margin; x <= right - margin; x++) {
            var staff = track.at(x);
            float end = a.endY() + (b.endY() - a.endY()) * (x - left) / (float) (right - left);
            int outer = Math.round(end - gap * .16f), inner = Math.round(end - gap * 1.04f);
            // The shaft ends must run along the same staff rule throughout each bridge or cut.
            if (Math.abs(outer - (staff[0] - 2 * staff[1])) > gap * .36f
                    || inner - radius < 0 || outer + radius >= height) continue;
            int outside = thickStaffBeamEnd(gray, width, x, outer, radius, minimum);
            int inside = thickStaffBeamEnd(gray, width, x, inner, radius, minimum);
            if (expected == 0 ? outside == -1 && inside == -1
                    : outside != -1 && (expected == 1 ? inside == -1 : inside != -1
                            && inside + 2 < outside
                            && paperBetween(gray, width, x, inside, outside, minimum))) matching++;
        }
        return matching >= count * .86f;
    }

    private static int thickStaffBeamEnd(byte[] gray, int width, int x, int center,
            int radius, int minimum) {
        int run = 0, last = -1;
        for (int y = center - radius; y <= center + radius; y++) {
            if ((gray[y * width + x] & 255) >= 155) run = 0;
            else if (++run >= minimum) last = y;
        }
        return last;
    }

    private static boolean paperBetween(byte[] gray, int width, int x, int inside, int outside,
            int minimum) {
        int run = 0;
        for (int y = inside + 1; y <= outside - minimum; y++) {
            if ((gray[y * width + x] & 255) < 155) run = 0;
            else if (++run == 2) return true;
        }
        return false;
    }

    static boolean provesClosedStraightSixteenths(byte[] gray, int width, int height,
            MeasureRegion bar, List<ScoreNoteEvent> notes, List<Integer> owners) {
        return owners.size() == 16 && mixedRailGroups(gray, width, height, bar, notes, owners,
                4, true, true) != null;
    }

    static boolean provesPairedFingeringStraightSixteenths(
            byte[] gray, int width, int height, MeasureRegion bar, List<ScoreNoteEvent> notes) {
        var first = java.util.stream.IntStream.range(0, 8).boxed().toList();
        var last = java.util.stream.IntStream.range(8, 16).boxed().toList();
        return notes.size() == 16 && mixedRailGroups(gray, width, height, bar, notes, first, 4, true, true) != null
                && mixedRailGroups(gray, width, height, bar, notes, last, 4, true, true) != null
                && mixedCut(gray, width, height, bar, notes.get(7), notes.get(8));
    }

    static boolean provesClosedEighthAndSixteenthRuns(byte[] gray, int width, int height,
            MeasureRegion bar, List<ScoreNoteEvent> notes, List<Integer> owners) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(owners.size());
        var xs = new float[owners.size()];
        var ys = new float[owners.size()];
        var budget = new PrintedBeamShafts.Budget(800_000);
        for (int i = 0; i < owners.size(); i++) {
            var note = notes.get(owners.get(i));
            xs[i] = (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
            ys[i] = note.pageY() * height;
            if (i > 0 && (xs[i] - xs[i - 1] < gap * 1.65f || xs[i] - xs[i - 1] > gap * 4.1f))
                return false;
            var shaft = PrintedBeamShafts.at(gray, width, height, xs[i], ys[i], gap, budget);
            if (shaft.size() != 1 || shaft.get(0).direction() != -note.stemDirection()) return false;
            shafts.add(shaft.get(0));
        }
        for (int i = 0; i + 1 < shafts.size(); i++) {
            var left = shafts.get(i);
            var right = shafts.get(i + 1);
            int column = i % 7;
            if (WrappedTripletDotInk.uniqueCore(gray, width, height, xs[i], ys[i], xs[i + 1], gap) != null)
                return false;
            if (column == 2 || column == 6) {
                if (left.direction() == right.direction()
                        && !cutBetweenRecoveredGroups(gray, width, height, bar, left, right, gap)) return false;
            } else if (left.direction() != right.direction()
                    || !matchesBeamRails(gray, width, height, left, right, gap, column == 0 ? 1 : 2))
                return false;
        }
        return true;
    }

    static boolean provesClosedSystemMixedRails(
            byte[] gray, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> candidates) {
        return candidates.size() == 14
                && mixedRailGroups(gray, width, height, bar, notes, candidates.subList(0, 6),
                        3, false, true) != null
                && mixedRailGroups(gray, width, height, bar, notes, candidates.subList(6, 14),
                        4, true, true) != null
                && mixedCut(gray, width, height, bar,
                        notes.get(candidates.get(5)), notes.get(candidates.get(6)));
    }

    private static WrappedTripletMeasure.Half mixedRailGroups(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            List<ScoreNoteEvent> notes,
            List<Integer> candidates,
            int groupSize,
            boolean doubleRail) {
        return mixedRailGroups(gray, width, height, bar, notes, candidates, groupSize, doubleRail, false);
    }

    private static WrappedTripletMeasure.Half mixedRailGroups(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            List<ScoreNoteEvent> notes,
            List<Integer> candidates,
            int groupSize,
            boolean doubleRail,
            boolean systemOpening) {
        if (width < 1 || height < 1 || gray == null || gray.length != (long) width * height)
            return null;
        var owners =
                candidates.stream()
                        .sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure()))
                        .toList();
        float[] x = new float[owners.size()], y = new float[owners.size()];
        float left = bar.left() * width, right = bar.right() * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (!Float.isFinite(gap) || gap < 10 || gap > 30) return null;
        int clef = notes.get(owners.get(0)).clefBottomDiatonic();
        int reportedExtraRail = 0;
        boolean recoveredInnerShaft = false;
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>();
        var budget = new PrintedBeamShafts.Budget(800_000);
        for (int i = 0; i < owners.size(); i++) {
            var n = notes.get(owners.get(i));
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED
                    || n.staffIndex() != 0
                    || n.staffCount() != 1
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || n.beamCount() < 1
                    || n.beamCount() > 2
                    || doubleRail && (n.beamCount() != 2 || n.tupletDivisor() != 1)
                    || n.augmentationDots() != 0
                    || n.unbeamedDurationBeats() != 0
                    || n.crossStaffBeam()
                    || n.leadingRestBeats() != 0
                    || n.followingRestBeats() != 0
                    || n.tiedFromPrevious()
                    || n.boundaryTies() != 0
                    || (n.articulations() & NoteOrnament.GRACE) != 0
                    || clef == ScoreNoteEvent.CLEF_UNKNOWN
                    || n.clefBottomDiatonic() != clef
                    || !(n.tupletDivisor() == 1
                            || n.tupletDivisor() == 3 && n.tupletNormalNotes() == 2)
                    || Math.abs(n.stemDirection()) != 1) return null;
            if (!doubleRail && n.beamCount() == 2 && ++reportedExtraRail > 1) return null;
            x[i] = left + (right - left) * n.positionInMeasure();
            y[i] = n.pageY() * height;
            if (i > 0
                    && (x[i] - x[i - 1] < gap * 1.65f
                            || x[i] - x[i - 1] > gap * (owners.size() == 6 ? 4f : 3.4f)))
                return null;
            var source = PrintedBeamShafts.at(gray, width, height, x[i], y[i], gap, budget);
            // A tight upper-ledger double beam can leave the downshaft just inside
            // the nominal wedge. Both neighboring rails still have to meet that shaft.
            if (source.isEmpty() && systemOpening && doubleRail && n.staffStep() > 8
                    && n.stemDirection() == -1 && i % groupSize > 0 && i % groupSize < groupSize - 1)
                source = PrintedBeamShafts.at(gray, width, height, x[i] + .16f * gap, y[i], gap, budget);
            if (source.isEmpty() && systemOpening && doubleRail && owners.size() == 8
                    && !recoveredInnerShaft && i % groupSize > 0 && i % groupSize < groupSize - 1) {
                // A staff crossing can split the contrast alongside an interior stem.
                // Both adjacent double rails still have to connect to this one shaft.
                var contrasted = ClosedOctaveTripletBars.contrastShaft(gray, width, height,
                        x[i], y[i], gap, budget);
                if (contrasted.size() == 1 && contrasted.get(0).right() > contrasted.get(0).left()) {
                    source = contrasted;
                    recoveredInnerShaft = true;
                }
            }
            if (source.size() != 1 || source.get(0).direction() != -n.stemDirection()) return null;
            shafts.add(source.get(0));
        }
        for (int i = 0; i + 1 < x.length; i++) {
            var dot = WrappedTripletDotInk.uniqueCore(gray, width, height, x[i], y[i], x[i + 1], gap);
            if (dot != null) {
                int step = notes.get(owners.get(i)).staffStep();
                if (!systemOpening || step >= 0 && step <= 8
                        || !headConnectedLedger(gray, width, height, x[i], y[i], dot, gap)) return null;
            }
            var a = shafts.get(i);
            var b = shafts.get(i + 1);
            boolean same = a.direction() == b.direction();
            if (i % groupSize == groupSize - 1) {
                if (same && !(systemOpening
                        ? cutBetweenRecoveredGroups(gray, width, height, bar, a, b, gap)
                        : matchesRailBridge(gray, width, height, a, b, gap, false))) return null;
                continue;
            }
            if (!same) return null;
            if (!matchesRailCount(gray, width, height, a, b, gap, doubleRail)) return null;
        }
        return new WrappedTripletMeasure.Half(owners, x);
    }

    static boolean cutBetweenRecoveredGroups(
            byte[] gray, int width, int height, MeasureRegion bar,
            PrintedBeamShafts.Shaft left, PrintedBeamShafts.Shaft right, float gap) {
        var ribbon = bestRibbon(gray, width, height, left, right, gap);
        return ribbon.coverage() <= .46f
                || staffRuleOnlyCut(gray, width, height, bar, left, right, gap, ribbon, true);
    }

    /** A staff crossing can imitate part of a beam without joining the two shafts. */
    /** A staff rule running through the endpoints of two groups is thinner than both beams. */
    static boolean thinStaffRuleBetweenRails(
            byte[] gray, int width, int height, MeasureRegion bar,
            PrintedBeamShafts.Shaft left, PrintedBeamShafts.Shaft right, float gap, Ribbon ribbon) {
        if (ribbon.coverage() < .75f) return false;
        var track = WrappedMeasureBoundaries.closedTripletStaff(gray, width, height, bar);
        if (track == null) return false;
        int from = (left.left() + left.right()) / 2;
        int to = (right.left() + right.right()) / 2;
        int inset = Math.round(gap * .45f);
        int first = from + inset, last = to - inset;
        if (last - first < gap * .70f || !track.verifiedAt(from) || !track.verifiedAt(to)) return false;
        var mid = track.at((from + to) * .5f);
        int rule = Math.round((mid[0] - (ribbon.leftY() + ribbon.rightY()) * .5f) / mid[1]);
        if (rule < 0 || rule >= 5) return false;
        var a = track.at(from);
        var b = track.at(to);
        // A diagonal endpoint search can follow the same horizontal staff rule only within
        // its wide sampling band. The subsequent thickness and masked-coverage tests still
        // require that the independently tracked rule accounts for the full apparent seam.
        if (Math.abs(a[0] - rule * a[1] - ribbon.leftY()) > gap * .65f
                || Math.abs(b[0] - rule * b[1] - ribbon.rightY()) > gap * .65f)
            return false;

        int reference = rule < 4 ? rule + 1 : rule - 1;
        int band = Math.round(gap * .28f), thin = Math.round(gap * .25f);
        int darkColumns = 0;
        for (int x = first; x <= last; x++) {
            var at = track.at(x);
            int same = ruleThickness(gray, width, height, x, Math.round(at[0] - rule * at[1]), band);
            int near = ruleThickness(gray, width, height, x, Math.round(at[0] - reference * at[1]), band);
            if (same > 0 && same <= thin && near > 0 && Math.abs(same - near) <= 1) darkColumns++;
        }
        if (darkColumns < (last - first + 1) * .88f) return false;

        var bandInk = new RuleBand(first, java.util.stream.IntStream.rangeClosed(first, last)
                .map(col -> {
                    var ruleTrack = track.at(col);
                    return Math.round(ruleTrack[0] - rule * ruleTrack[1]);
                }).toArray(), band);
        return bestRibbon(gray, width, height, left, right, gap, bandInk).coverage() <= .46f;
    }

    private static int ruleThickness(byte[] gray, int width, int height, int x, int y, int band) {
        if (x < 0 || x >= width || y - band < 0 || y + band >= height) return 0;
        int count = 0;
        for (int row = y - band; row <= y + band; row++)
            if ((gray[row * width + x] & 255) < 155) count++;
        return count;
    }

    private static boolean compactFlatOwnsCore(byte[] gray, int width, int height,
            WrappedTripletDotInk.Center core, float noteX, float noteY, float gap) {
        float before = (noteX - core.x()) / gap;
        float above = (noteY - core.y()) / gap;
        if (before < .76f || before > 1.18f || above < .16f || above > .46f) return false;
        int top = core.y() - Math.round(gap * 1.41f), bottom = core.y() + Math.round(gap * .92f);
        int left = core.x() - Math.round(gap * .80f), right = core.x() + Math.round(gap * .30f);
        if (!PrintedFlatGlyph.matches(gray, width, height, left, top, right, bottom, gap)) return false;
        var lowerInk = RawNumeralComponents.capture(
                gray, width, height, left, core.y(), right, bottom, 155, () -> {}, () -> {});
        for (int spine = left; spine <= core.x() - Math.round(gap * .43f); spine++) {
            int attached = 0, y0 = core.y() - Math.round(gap * 1.05f), y1 = core.y() + Math.round(gap * .68f);
            for (int y = y0; y <= y1; y++)
                if ((gray[y * width + spine] & 255) < 155) attached++;
            if (attached < (y1 - y0 + 1) * .92f) continue;
            for (var part : lowerInk.components()) {
                boolean containsCore = false, connectsSpine = false;
                for (int i = 0; i < part.size(); i++) {
                    int pixel = part.pixel(i);
                    containsCore |= pixel == core.y() * width + core.x();
                    connectsSpine |= pixel % width == spine && pixel / width >= core.y() + gap * .40f;
                }
                if (containsCore && connectsSpine) return true;
            }
        }
        return false;
    }

    /** Thick beam ends stop on each side of a thinner continuous staff rule. */
    private static boolean continuingStaffOnlyCut(
            byte[] gray, int width, int height, MeasureRegion bar,
            PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, float gap, Ribbon ribbon) {
        if (a.direction() != 1 || b.direction() != 1 || ribbon.coverage() < .67f) return false;
        int left = (a.left() + a.right()) / 2, right = (b.left() + b.right()) / 2;
        int inset = Math.round(gap * .43f), first = left + inset, last = right - inset;
        int outside = Math.round(gap * .37f), flank = Math.round(gap * .37f);
        if (left - outside < 0 || right + outside >= width || last - first < gap * .85f) return false;
        var staff = WrappedMeasureBoundaries.verifiedTripletStaff(gray, width, height, bar);
        if (staff == null || !staff.verifiedAt(left) || !staff.verifiedAt(right)) return false;
        float[] center = staff.at((left + right) * .5f);
        float centerY = (ribbon.leftY() + ribbon.rightY()) * .5f;
        int rule = Math.round((center[0] - centerY) / center[1]);
        if (rule < 0 || rule >= 5 || Math.abs(centerY - (center[0] - rule * center[1])) > gap * .33f)
            return false;
        int[] rows = new int[last - first + 1];
        int clean = 0, thickness = Math.round(gap * .31f);
        for (int x = first; x <= last; x++) {
            float[] at = staff.at(x);
            int row = Math.round(at[0] - rule * at[1]);
            if (row < flank || row + flank >= height) return false;
            rows[x - first] = row;
            int ink = 0;
            for (int y = row - flank; y <= row + flank; y++)
                if ((gray[y * width + x] & 255) < 155) ink++;
            if (ink <= thickness && (gray[row * width + x] & 255) < 155
                    && (gray[(row - flank) * width + x] & 255) >= 155
                    && (gray[(row + flank) * width + x] & 255) >= 155) clean++;
        }
        if (clean < rows.length * .90f) return false;
        for (int x : new int[] {left - outside, right + outside}) {
            float[] at = staff.at(x);
            int row = Math.round(at[0] - rule * at[1]);
            int radius = Math.round(gap * .56f), band = Math.round(gap * .18f);
            if (row < radius || row + radius >= height) return false;
            int ink = 0;
            for (int y = row - radius; y <= row + radius; y++)
                if (Math.abs(y - row) > band && (gray[y * width + x] & 255) < 155) ink++;
            if (ink < 2) return false;
        }
        return bestRibbon(gray, width, height, a, b, gap,
                new RuleBand(first, rows, Math.round(gap * .21f))).coverage() <= .46f;
    }

    private static boolean staffRuleOnlyCut(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            PrintedBeamShafts.Shaft left,
            PrintedBeamShafts.Shaft right,
            float gap,
            Ribbon ribbon,
            boolean recoveredShaft) {
        if (ribbon.coverage() >= .75f) return false;
        int a = (left.left() + left.right()) / 2, b = (right.left() + right.right()) / 2;
        int inset = Math.round(gap * .45f), first = a + inset, last = b - inset;
        if (first < 0 || last >= width || last <= first) return false;
        var staff = WrappedMeasureBoundaries.closedTripletStaff(gray, width, height, bar);
        if (staff == null || !staff.verifiedAt(a) || !staff.verifiedAt(b)) return false;
        var centre = staff.at((a + b) * .5f);
        int rule = Math.round((centre[0] - (ribbon.leftY() + ribbon.rightY()) * .5f) / centre[1]);
        if (rule < 0 || rule >= 5) return false;

        // A real beam running on top of a rule has no independent separation witness.
        var before = staff.at(a);
        var after = staff.at(b);
        float start = ribbon.leftY() - (before[0] - rule * before[1]);
        float end = ribbon.rightY() - (after[0] - rule * after[1]);
        float near = Math.min(Math.abs(start), Math.abs(end));
        float far = Math.max(Math.abs(start), Math.abs(end));
        if (start * end >= 0
                || (recoveredShaft ? near < gap * .12f || far < gap * .36f : near < gap * .31f))
            return false;

        int[] rows = new int[last - first + 1];
        int isolated = 0, flank = Math.max(3, Math.round(gap * .22f));
        for (int x = first; x <= last; x++) {
            var local = staff.at(x);
            int y = Math.round(local[0] - rule * local[1]);
            if (y < flank || y + flank >= height) return false;
            rows[x - first] = y;
            if ((gray[y * width + x] & 255) < 155
                    && (gray[(y - flank) * width + x] & 255) >= 155
                    && (gray[(y + flank) * width + x] & 255) >= 155) isolated++;
        }
        if (isolated < rows.length * .84f) return false;
        var ruleInk = new RuleBand(first, rows, Math.round(gap * .16f));
        return bestRibbon(gray, width, height, left, right, gap, ruleInk).coverage() <= .46f;
    }

    private static Ribbon bestRibbon(byte[] gray, int width, int height,
            PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, float gap) {
        return bestRibbon(gray, width, height, a, b, gap, null);
    }

    private static Ribbon bestRibbon(
            byte[] gray,
            int width,
            int height,
            PrintedBeamShafts.Shaft a,
            PrintedBeamShafts.Shaft b,
            float gap,
            RuleBand excluded) {
        var best = new Ribbon(0, a.endY(), b.endY());
        int direction = a.direction();
        for (int dy = -Math.round(gap * .28f); dy <= Math.round(gap * .28f); dy++) {
            for (int slope = -1; slope <= 1; slope++) {
                int centre = -direction * Math.round(gap * .14f);
                int leftY = a.endY() + centre + dy;
                int rightY = b.endY() + centre + dy + Math.round(slope * gap * .24f);
                float cover =
                        coverage(gray, width, height, a, b, gap, leftY, rightY, .31f, excluded);
                int distance = Math.abs(leftY-a.endY()-centre) + Math.abs(rightY-b.endY()-centre);
                int prior = Math.abs(best.leftY()-a.endY()-centre)
                        + Math.abs(best.rightY()-b.endY()-centre);
                if (cover > best.coverage() || cover == best.coverage() && distance < prior)
                    best = new Ribbon(cover, leftY, rightY);
            }
        }
        return best;
    }

    private static float coverage(byte[] gray, int width, int height,
            PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, float gap,
            int leftY, int rightY, float band) {
        return coverage(gray, width, height, a, b, gap, leftY, rightY, band, null);
    }

    private static float coverage(
            byte[] gray,
            int width,
            int height,
            PrintedBeamShafts.Shaft a,
            PrintedBeamShafts.Shaft b,
            float gap,
            int leftY,
            int rightY,
            float band,
            RuleBand excluded) {
        int x0 = (a.left() + a.right()) / 2, x1 = (b.left() + b.right()) / 2;
        int padding = Math.round(gap * .45f);
        if (x0 < 0 || x1 >= width || x1 - x0 <= padding * 2) return 0;
        int hits = 0, columns = 0, radius = Math.round(gap * band);
        int thickness = Math.round(gap * .27f);
        for (int x = x0 + padding; x <= x1 - padding; x++) {
            float progress = (x - x0) / (float) (x1 - x0);
            int middle = Math.round(leftY * (1 - progress) + rightY * progress);
            if (middle - radius < 0 || middle + radius >= height) return 0;
            int run = 0;
            for (int y = middle - radius; y <= middle + radius; y++) {
                if ((gray[y * width + x] & 255) < 155
                        && (excluded == null || !excluded.contains(x, y))) {
                    if (++run >= thickness) {
                        hits++;
                        break;
                    }
                } else run = 0;
            }
            columns++;
        }
        return hits / (float) columns;
    }
}
