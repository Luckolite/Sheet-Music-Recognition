// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** Verifies three undotted, single-rail groups in a three-beat system fragment. */
final class WrappedEighthTriplets {
    private WrappedEighthTriplets() {}

    private static final class ShaftQuotaExceeded extends RuntimeException {}

    private record Ribbon(float coverage, int leftY, int rightY) {}

    @FunctionalInterface
    private interface ExcludedInk {
        boolean contains(int x, int y);
    }

    private record RuleBand(int first, int[] rows, int radius) implements ExcludedInk {
        public boolean contains(int x, int y) {
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
        return closedNaturalPredecessor(gray, null, width, height, regions, measure, notes, candidates, labels);
    }

    static WrappedTripletMeasure.Half closedNaturalPredecessor(
            byte[] gray, byte[] original, int width, int height, List<MeasureRegion> regions, int measure,
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
        if (WrappedMeasureBoundaries.confirmsClosedTripletBar(
                gray, width, height, boundaries, proof.x()[0], proof.x()[17])) return proof;
        var last = notes.get(proof.owners().get(proof.owners().size() - 1));
        if (original == null || original.length != gray.length || last.stemDirection() != -1
                || !packedPredecessorEdge(gray, width, height, boundaries, proof, last, gap)
                || (gray != original && (!packedPredecessorEdge(
                                original, width, height, boundaries, proof, last, gap)
                        || singleRailGroups(original, width, height, bar,
                                notes, candidates, false, false, labels) == null))) return null;
        return proof;
    }

    private static boolean packedPredecessorEdge(
            byte[] plane, int width, int height, MeasureRegion boundaries,
            WrappedTripletMeasure.Half proof, ScoreNoteEvent last, float gap) {
        float lastX = proof.x()[17];
        var stems = PrintedBeamShafts.at(plane, width, height, lastX, last.pageY() * height,
                gap, new PrintedBeamShafts.Budget(50_000));
        if (stems.size() != 1 || stems.get(0).direction() != 1) return false;
        var stem = stems.get(0);
        return WrappedMeasureBoundaries.confirmsPackedTripletPredecessor(
                plane, width, height, boundaries, proof.x()[0], lastX, (stem.left() + stem.right()) * .5f);
    }

    /** Keep the primary beams and omit only tuplet numbers and brackets absent from both planes. */
    static ScorePageInterpretation projectPredecessorBeams(ScorePageInterpretation score,
            int measure, WrappedTripletMeasure.Half proof, byte[] labels, byte[] gray, byte[] original,
            int width, int height, List<PlayingTechniqueDetector.Word> words) {
        var bar = score.measures().get(measure);
        if (original == null || original.length != gray.length || (gray != original
                && singleRailGroups(original, width, height, bar, score.notes(), proof.owners(),
                        false, false, labels) == null)) return score;
        var source = score.beamSource();
        var groups = new ArrayList<>(source.groups());
        var rows = UnnumberedTripletInk.sourceRows(bar, score.measures(), height);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        byte[] unmarkedGray = null, unmarkedOriginal = null;
        for (int i = 0; i < proof.owners().size(); i += 3) {
            var owners = proof.owners().subList(i, i + 3);
            var columns = existingTripletColumns(source, owners);
            if (columns.size() != 3) continue;
            int index = groupStartingAt(groups, columns.get(0));
            if (index < 0 || !groups.get(index).columns().equals(columns))
                index = completeSingleRailTriplet(source, groups, columns);
            if (index < 0) continue;
            var group = groups.get(index);
            if (group.tupletMark() != null || !group.bridges().equals(List.of(1, 1))
                    || !group.hooks().isEmpty()) continue;
            var segment = new WrappedTripletMeasure.Half(owners,
                    java.util.Arrays.copyOfRange(proof.x(), i, i + 3));
            boolean clear = unnumberedClosedSixFourGroups(score, segment, bar, rows, gray, width, height, gap, words)
                    && (gray == original
                            || unnumberedClosedSixFourGroups(score, segment, bar, rows, original, width, height, gap, words));
            if (!clear) {
                if (unmarkedGray == null) {
                    float middle = (proof.x()[0] + proof.x()[17]) * .5f;
                    unmarkedGray = withoutOctaveDashes(gray, width, height, bar, middle);
                    unmarkedOriginal = gray == original ? unmarkedGray
                            : withoutOctaveDashes(original, width, height, bar, middle);
                }
                clear = unmarkedGray != gray && unmarkedOriginal != original
                        && !predecessorNumeral(score, segment, gray, width, height, gap, rows, words)
                        && (gray == original || !predecessorNumeral(
                                score, segment, original, width, height, gap, rows, words))
                        && unnumberedClosedSixFourGroups(
                                score, segment, bar, rows, unmarkedGray, width, height, gap, words)
                        && (gray == original || unnumberedClosedSixFourGroups(
                                score, segment, bar, rows, unmarkedOriginal, width, height, gap, words));
            }
            if (clear) groups.set(index, new ScoreBeamSource.Group(group.id(), group.columns(),
                    group.bridges(), group.hooks(), ScoreBeamSource.TupletMark.UNNUMBERED));
        }
        return groups.equals(source.groups()) ? score : score.withBeamSource(
                new ScoreBeamSource(groups, source.detachedColumns(), source.noteOrigins()));
    }

    private static boolean predecessorNumeral(ScorePageInterpretation score, WrappedTripletMeasure.Half triplet,
            byte[] plane, int width, int height, float gap, UnnumberedTripletInk.Rows rows,
            List<PlayingTechniqueDetector.Word> words) {
        float top = Float.POSITIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
        for (int owner : triplet.owners()) {
            float y = score.notes().get(owner).pageY() * height;
            top = Math.min(top, y);
            bottom = Math.max(bottom, y);
        }
        return localPrintedThree(plane, width, height, triplet.x()[1], top - gap * 3, bottom + gap * 3,
                gap, rows, words);
    }

    private static byte[] withoutOctaveDashes(byte[] gray, int width, int height, MeasureRegion bar, float x) {
        var track = WrappedMeasureBoundaries.verifiedTripletStaff(gray, width, height, bar);
        if (track == null) return gray;
        var staff = track.at(x);
        var row = new PlayingTechniqueDetector.Staff(staff[0] - 4 * staff[1], staff[0], staff[1], 0, 1);
        var ink = OctaveMarkDetector.printedDashInk(gray, width, height, List.of(row));
        if (ink.isEmpty()) return gray;
        var clean = gray.clone();
        for (int pixel = ink.nextSetBit(0); pixel >= 0; pixel = ink.nextSetBit(pixel + 1))
            clean[pixel] = (byte) 255;
        return clean;
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
            boolean literalOpening = meter.measureIndex() == m;
            if (meter.numerator() == 4 && hasOriginal && (literalOpening
                    || owners.stream().map(notes::get)
                            .anyMatch(note -> note.beamCount() == 2 || note.augmentationDots() != 0))) {
                var common = proveCommonTimeSingleRails(notes, owners, regions, m, gray, original, width, height,
                        false, literalOpening);
                if (common == null && m == 0 && literalOpening && labels != null
                        && labels.length == gray.length && !withRests.get(1)
                        && MeterFontMatcher.standaloneReadings(labels, gray, width, height, List.of(bar),
                                owners.stream().map(notes::get).toList()).equals(List.of(meter))
                        && MeterFontMatcher.standaloneReadings(labels, original, width, height, List.of(bar),
                                owners.stream().map(notes::get).toList()).equals(List.of(meter))
                        && provesBottomRuleOpening(notes, owners, regions, gray, original, width, height))
                    common = notes;
                if (common != null) {
                    if (neighborRecoveredBars != null) neighborRecoveredBars.add(m);
                    if (result == notes) result = new ArrayList<>(notes);
                    for (int owner : owners) result.set(owner, common.get(owner).withTupletRatio(3, 2));
                    continue;
                }
            }
            boolean canUseHeader =
                    meter.numerator() == 6 && labels != null && labels.length == gray.length;
            boolean canUseOpening = m == 0 && canUseHeader;
            List<ScoreNoteEvent> proofNotes = notes;
            if (canUseOpening && hasOriginal && literalOpening) {
                var recovered = traceNumberedSixFourOpening(gray, original, labels, width, height,
                        regions, meter, notes, owners);
                if (recovered != null) {
                    if (result == notes) result = new ArrayList<>(notes);
                    for (int owner : owners) result.set(owner, recovered.get(owner).withTupletRatio(3, 2));
                    continue;
                }
            }
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
                if (restored != null || owners.stream().map(notes::get)
                        .anyMatch(n -> n.tupletDivisor() == 3 && n.tupletNormalNotes() == 2)
                        && provesFlatMarkedSixFourOpening(gray, original, labels, width, height,
                                regions, m, meter, notes, owners)) {
                    var provenNotes = restored == null ? notes : restored;
                    if (result == notes) result = new ArrayList<>(notes);
                    for (int owner : owners) result.set(owner, provenNotes.get(owner).withTupletRatio(3, 2));
                    continue;
                }
            }
            boolean recoverEndpoint = owners.stream().anyMatch(i -> notes.get(i).beamCount() != 1);
            if (recoverEndpoint && hasOriginal && meter.numerator() == 6) {
                var aliases = owners.stream().filter(i -> notes.get(i).beamCount() != 1).toList();
                if (aliases.size() == 1 && notes.get(aliases.get(0)).beamCount() == 2
                        && ((canUseOpening
                                ? openingSingleRailCorrection(
                                        gray, original, labels, width, height, regions, notes, owners, meter)
                                : closedSingleRailCorrection(
                                        gray, original, width, height, bar, notes, owners, null, false))
                            || canUseHeader && m > 0 && meter.measureIndex() == m
                                && bar.top() > regions.get(m - 1).bottom()
                                && provesStaffOverlaidPairedFlatOpening(gray, original, labels, width, height,
                                        regions, m, meter, notes, owners, aliases.get(0)))) {
                    if (result == notes) result = new ArrayList<>(notes);
                    int alias = aliases.get(0);
                    for (int i : owners) {
                        var restored = i == alias ? ClosingTripletRhythm.singleBeam(notes.get(i)) : notes.get(i);
                        result.set(i, restored.withTupletRatio(3, 2));
                    }
                    continue;
                }
            }
            if (recoverEndpoint && m > 0 && hasOriginal && meter.numerator() == 6) {
                var repaired = packedSixFourSingleRails(gray, original, width, height,
                        regions.get(m - 1), bar, notes, owners);
                if (repaired != null) {
                    if (result == notes) result = new ArrayList<>(notes);
                    for (int i : owners) result.set(i, repaired.get(i).withTupletRatio(3, 2));
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
                if (labels != null && labels.length == gray.length
                        && hasClosedContinuingSixFourRails(gray, original, labels, width, height,
                                regions, m, proofNotes, owners)) {
                    if (result == notes) result = new ArrayList<>(notes);
                    for (int owner : owners) result.set(owner, notes.get(owner).withTupletRatio(3, 2));
                    continue;
                }
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
            if (!closed && m > 0 && hasOriginal && meter.numerator() == 4 && !recoverEndpoint
                    && sampledCommonTimeTripletBar(proofNotes, regions, m, proof, gray, original, width, height)) {
                closed = true;
                if (neighborRecoveredBars != null) neighborRecoveredBars.add(m);
            }
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

    /** Prove a literal opening whose first beamed group lost all three shaft assignments. */
    private static List<ScoreNoteEvent> traceNumberedSixFourOpening(
            byte[] gray, byte[] raw, byte[] labels, int width, int height, List<MeasureRegion> bars,
            ScoreMeterChange meter, List<ScoreNoteEvent> notes, List<Integer> owners) {
        var ordered = owners.stream().sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure()))
                .toList();
        if (ordered.size() != 18) return null;
        var repaired = new ArrayList<>(notes);
        for (int slot = 0; slot < ordered.size(); slot++) {
            int owner = ordered.get(slot);
            var note = notes.get(owner);
            if (slot < 3) {
                if (note.stemDirection() != 0 || note.beamCount() != 2 || note.augmentationDots() > 1)
                    return null;
                repaired.set(owner, withoutDots(ClosingTripletRhythm.singleBeam(note).withStemDirection(1)));
            } else if (note.beamCount() != 1 || note.augmentationDots() != 0) return null;
        }
        return tracedOpeningProof(gray, raw, labels, width, height, bars, meter, repaired, ordered,
                null, List.of()) == null ? null : repaired;
    }

    private static WrappedTripletMeasure.Half tracedOpeningProof(
            byte[] gray, byte[] raw, byte[] labels, int width, int height, List<MeasureRegion> bars,
            ScoreMeterChange meter, List<ScoreNoteEvent> notes, List<Integer> ordered,
            ScorePageInterpretation engraving, List<PlayingTechniqueDetector.Word> words) {
        var bar = bars.get(0);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        var shaftProof = new ArrayList<PrintedBeamShafts.Shaft>();
        PrintedBeamShafts.Shaft firstMiddle = null;
        WrappedTripletMeasure.Half verified = null;
        for (byte[] plane : new byte[][] {gray, raw}) {
            shaftProof.clear();
            var proof = singleRailGroups(plane, width, height, bar, notes, ordered,
                    true, false, null, false, null, false, false, false, null,
                    (shafts, recovered) -> shaftProof.addAll(shafts), true);
            if (proof == null || shaftProof.get(1) == null
                    || !WrappedMeasureBoundaries.confirmsOpeningTripletBar(
                            plane, width, height, bars, proof.x()[0], proof.x()[17], true)
                    || !MeterFontMatcher.standaloneReadings(labels, plane, width, height, List.of(bar),
                            ordered.stream().map(notes::get).toList()).equals(List.of(meter))) return null;
            var middle = shaftProof.get(1);
            if (firstMiddle != null && (Math.abs(firstMiddle.left() - middle.left()) > .25f * gap
                    || Math.abs(firstMiddle.endY() - middle.endY()) > .30f * gap)) return null;
            if (!ownedTracedOpeningNumeral(plane, labels, width, height, proof.x()[1], gap, middle.endY()))
                return null;
            if (engraving != null) {
                var remaining = new WrappedTripletMeasure.Half(ordered.subList(3, ordered.size()),
                        java.util.Arrays.copyOfRange(proof.x(), 3, ordered.size()));
                var rows = UnnumberedTripletInk.sourceRows(bar, bars, height);
                if (!unnumberedClosedSixFourGroups(engraving, remaining, bar, rows, plane, width, height, gap,
                        words, false, shaftProof.subList(3, shaftProof.size()))) return null;
            }
            firstMiddle = middle;
            verified = proof;
        }
        return verified;
    }

    private static boolean ownedTracedOpeningNumeral(
            byte[] original, byte[] labels, int width, int height, float middleX, float gap, int tip) {
        int left = Math.max(0, Math.round(middleX - 1.0f * gap));
        int right = Math.min(width - 1, Math.round(middleX + 1.10f * gap));
        int top = Math.max(0, Math.round(tip - 4.85f * gap));
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

    /** A real shaft and the complete set of cut single rails can resolve two detector aliases. */
    private static List<ScoreNoteEvent> packedSixFourSingleRails(
            byte[] gray, byte[] raw, int width, int height, MeasureRegion previous, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> owners) {
        if (bar.cadenceQuarterBeats() != 0) return null;
        var ordered = owners.stream().sorted(
                Comparator.comparingDouble(i -> notes.get(i).positionInMeasure())).toList();
        if (ordered.stream().map(notes::get).filter(n -> n.stemDirection() == 0).count() != 1
                || ordered.stream().map(notes::get).filter(n -> n.beamCount() == 2).count() != 1)
            return null;
        var normalized = packedSingleRailShafts(gray, width, height, previous, bar, notes, ordered);
        if (normalized == null || !normalized.equals(
                packedSingleRailShafts(raw, width, height, previous, bar, notes, ordered))) return null;
        var corrected = new ArrayList<>(notes);
        for (int i = 0; i < ordered.size(); i++) {
            int owner = ordered.get(i);
            var note = notes.get(owner);
            if (note.beamCount() == 2) note = ClosingTripletRhythm.singleBeam(note);
            if (note.stemDirection() == 0) note = note.withStemDirection(-normalized.get(i).direction());
            corrected.set(owner, note);
        }
        return corrected;
    }

    private static List<PrintedBeamShafts.Shaft> packedSingleRailShafts(
            byte[] plane, int width, int height, MeasureRegion previous, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> owners) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25) return null;
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float[] x = new float[owners.size()], y = new float[owners.size()];
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(owners.size());
        var budget = new PrintedBeamShafts.Budget(200_000);
        for (int i = 0; i < owners.size(); i++) {
            var note = notes.get(owners.get(i));
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.staffIndex() != 0 || note.staffCount() != 1
                    || note.clefBottomDiatonic() == ScoreNoteEvent.CLEF_UNKNOWN
                    || note.clefBottomDiatonic() != notes.get(owners.get(0)).clefBottomDiatonic()
                    || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || note.beamCount() < 1 || note.beamCount() > 2 || note.unbeamedDurationBeats() != 0
                    || note.tupletDivisor() != 1 || note.augmentationDots() != 0 || note.crossStaffBeam()
                    || note.leadingRestBeats() != 0 || note.followingRestBeats() != 0
                    || note.tiedFromPrevious() || note.boundaryTies() != 0
                    || (note.articulations() & NoteOrnament.GRACE) != 0) return null;
            x[i] = left + span * note.positionInMeasure();
            y[i] = note.pageY() * height;
            if (i > 0) {
                float distance = x[i] - x[i - 1];
                if (distance < gap * 1.65f || distance > gap * 4.1f
                        || distance > gap * 3.85f && note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT
                        || WrappedTripletDotInk.uniqueCore(plane, width, height,
                                x[i - 1], y[i - 1], x[i], gap) != null) return null;
            }
            var source = PrintedBeamShafts.at(plane, width, height, x[i], y[i], gap, budget);
            if (source.size() != 1 || note.stemDirection() != 0 && source.get(0).direction() != -note.stemDirection()
                    || note.stemDirection() == 0
                            && !FilledSparseBeamHead.completeInterior(plane, width, height, x[i], y[i], gap))
                return null;
            var shaft = source.get(0);
            if (i > 0 && !matchesRailBridge(plane, width, height, shafts.get(i - 1), shaft, gap, i % 3 != 0))
                return null;
            shafts.add(shaft);
        }
        var last = shafts.get(shafts.size() - 1);
        return last.direction() == 1 && WrappedMeasureBoundaries.confirmsPocketAndPackedClosingEdge(
                plane, width, height, previous, bar, x[0], x[x.length - 1], (last.left() + last.right()) * .5f)
                ? shafts : null;
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

    /** Restore six complete eighth-triplets when the suspect dot belongs to a ledger line. */
    static ScorePageInterpretation restoreClosedLedgerTriplets(
            ScorePageInterpretation score, byte[] labels, byte[] gray, byte[] raw, int width, int height,
            List<PlayingTechniqueDetector.Word> words, int page) {
        if (gray == null || raw == null || labels == null || gray.length != (long) width*height
                || raw.length != gray.length || labels.length != gray.length) return score;
        var notes = score.notes();
        var bars = score.measures();
        var rows = new ArrayList<List<Integer>>(bars.size());
        for (int i=0; i<bars.size(); i++) rows.add(new ArrayList<>());
        for (int i=0; i<notes.size(); i++) rows.get(notes.get(i).measureIndex()).add(i);
        var starts = new java.util.BitSet(bars.size());
        for (var rest : score.rests()) starts.set(rest.measureIndex());
        List<ScoreNoteEvent> repaired = null;
        var projected = score;
        ScoreMeterChange active = null;
        int next = 0;
        for (int bar=0; bar+1<bars.size(); bar++) {
            while (next<score.meterChanges().size() && score.meterChanges().get(next).measureIndex()<=bar)
                active = score.meterChanges().get(next++);
            var owners = rows.get(bar);
            if (active==null || active.numerator()!=6 || active.denominator()!=4
                    || starts.get(bar) || owners.size()!=18 || bars.get(bar).cadenceQuarterBeats()!=0)
                continue;
            owners.sort(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure()));
            if (bar == 0) {
                if (owners.stream().map(notes::get).allMatch(n -> n.beamCount() == 1
                        && n.augmentationDots() == 0 && n.tupletDivisor() == 3 && n.tupletNormalNotes() == 2)
                        && owners.subList(0, 3).stream().map(notes::get).allMatch(n -> n.stemDirection() == 1)) {
                    var proof = tracedOpeningProof(gray, raw, labels, width, height, bars, active,
                            notes, owners, score, words);
                    if (proof != null) projected = markRetainedSixFourGroups(projected, proof, page, false, true);
                }
                continue;
            }
            var flatGroups = proveFlatOwnedSixFour(score, gray, raw, labels, width, height, words,
                    bar, owners, active);
            if (flatGroups != null) {
                if (repaired == null) repaired = new ArrayList<>(notes);
                for (int owner : owners) repaired.set(owner, flatGroups.notes().get(owner).withTupletRatio(3, 2));
                projected = markRetainedSixFourGroups(projected, flatGroups.proof(), page,
                        false, flatGroups.numbered());
                continue;
            }
            if (owners.stream().map(notes::get).anyMatch(n -> n.beamCount()!=1 || n.augmentationDots()>1))
                continue;
            if (owners.stream().map(notes::get).allMatch(n -> n.tupletDivisor()==3 && n.tupletNormalNotes()==2)) {
                float gap = (bars.get(bar).bottom() - bars.get(bar).top()) * height / 8.2f;
                boolean numberedOpening = active.measureIndex() == bar
                        && (bars.get(bar).top() - bars.get(bar - 1).bottom()) * height > 1.7f * gap;
                var proof = proveSixFourEngraving(score, gray, raw, labels, width, height, words, bar, owners,
                        numberedOpening ? active : null);
                if (proof != null) projected = markRetainedSixFourGroups(projected, proof, page, false, numberedOpening);
                continue;
            }
            if (owners.stream().map(notes::get).allMatch(n -> n.tupletDivisor()==3)) continue;
            var dotted = owners.stream().filter(i -> notes.get(i).augmentationDots()==1).toList();
            if (dotted.size()!=1) continue;
            int dotOwner=dotted.get(0), dotSlot=owners.indexOf(dotOwner);
            if (dotSlot+1==owners.size()) continue;
            var restated = new ArrayList<>(notes);
            restated.set(dotOwner, ClosedMonophonicSixteenths.withoutAugmentationDot(notes.get(dotOwner)));
            var numbered = dotSlot == 1 && active.measureIndex() == bar
                    ? numberedStaccatoOpening(score, gray, raw, labels, width, height, words,
                            bar, active, restated, owners)
                    : null;
            if (numbered == null
                    && (!provesLedgerTriplets(gray,labels,width,height,bars,bar,restated,owners,dotSlot)
                        || !provesLedgerTriplets(raw,labels,width,height,bars,bar,restated,owners,dotSlot)))
                continue;
            if (repaired == null) repaired = new ArrayList<>(notes);
            for (int owner : owners) repaired.set(owner, restated.get(owner).withTupletRatio(3,2));
            projected = numbered != null
                    ? markRetainedSixFourGroups(projected, numbered, page, false, true)
                    : retainLedgerTripletBeams(projected, gray, raw, width, height, words, bar, owners, page);
        }
        return repaired == null ? projected : projected.withNotes(repaired,
                java.util.stream.IntStream.range(0, notes.size()).boxed().toList());
    }

    private record FlatOwnedSixFour(List<ScoreNoteEvent> notes, WrappedTripletMeasure.Half proof, boolean numbered) {}

    private static FlatOwnedSixFour proveFlatOwnedSixFour(
            ScorePageInterpretation score, byte[] normalized, byte[] raw, byte[] labels, int width, int height,
            List<PlayingTechniqueDetector.Word> words, int index, List<Integer> owners, ScoreMeterChange meter) {
        var notes = score.notes();
        var bars = score.measures();
        var bar = bars.get(index);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25) return null;
        var mid = notes.get(owners.get(1));
        boolean numbered = meter.measureIndex() == index;
        if (numbered) {
            if (mid.augmentationDots() != 1 || mid.beamCount() != 1
                    || mid.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT
                    || (bar.top() - bars.get(index - 1).bottom()) * height <= 1.7f * gap) return null;
            for (int j : new int[]{0, 2})
                if ((notes.get(owners.get(j)).articulations() & NoteArticulation.STACCATO) == 0) return null;
        } else if (mid.beamCount() != 2 || mid.augmentationDots() != 0) return null;

        for (int owner : owners) {
            if (owner == owners.get(1)) continue;
            var note = notes.get(owner);
            if (note.beamCount() != 1 || note.augmentationDots() != 0) return null;
        }
        if (notes.get(owners.get(0)).stemDirection() != 1 || mid.stemDirection() != 1
                || notes.get(owners.get(2)).stemDirection() != 1) return null;
        var restored = new ArrayList<>(notes);
        var corrected = numbered ? withoutDots(mid).withArticulations(mid.articulations() | NoteArticulation.STACCATO)
                : ClosingTripletRhythm.singleBeam(mid);
        restored.set(owners.get(1), corrected);
        var editedScore = score.withNotes(restored,
                java.util.stream.IntStream.range(0, restored.size()).boxed().toList());
        var rows = UnnumberedTripletInk.sourceRows(bar, bars, height);
        WrappedTripletMeasure.Half proved = null;
        for (byte[] plane : new byte[][]{raw, normalized}) {
            var proof = singleRailGroups(plane, width, height, bar, restored, owners, true, !numbered,
                    numbered ? null : labels, true, null, false, false, false, numbered ? labels : null);
            if (proof == null || !MeterFontMatcher.standaloneReadings(labels, plane, width, height, bars, restored)
                    .contains(meter)) return null;
            var unnumbered = proof;
            if (numbered) {
                if (!WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(plane, width, height, bar, proof.x()[17])
                        || !OpeningTripletNumeral.joinedLowerStaccato(plane, labels, width, height,
                                proof.x()[1], mid.pageY() * height, gap)
                        || WrappedTripletDotInk.uniqueCore(plane, width, height, proof.x()[1],
                                mid.pageY() * height, proof.x()[2], gap) != null) return null;
                for (int j : new int[]{0, 2})
                    if (!OmrScoreInterpreter.centerOwnedLowerStaccato(plane, width, height, proof.x()[j],
                            notes.get(owners.get(j)).pageY() * height, gap, .70f, 1.35f, .68)) return null;
                unnumbered = new WrappedTripletMeasure.Half(owners.subList(3, owners.size()),
                        java.util.Arrays.copyOfRange(proof.x(), 3, owners.size()));
            } else if (!WrappedMeasureBoundaries.confirmsClosedTripletBar(
                    plane, width, height, bar, proof.x()[0], proof.x()[17])) return null;
            if (!unnumberedClosedSixFourGroups(editedScore, unnumbered, bar, rows, plane,
                    width, height, gap, words)) return null;
            proved = proof;
        }
        return new FlatOwnedSixFour(restored, proved, numbered);
    }

    private static WrappedTripletMeasure.Half proveSixFourEngraving(
            ScorePageInterpretation score, byte[] gray, byte[] raw, byte[] labels, int width, int height,
            List<PlayingTechniqueDetector.Word> words, int index, List<Integer> owners, ScoreMeterChange opener) {
        var bars = score.measures();
        var bar = bars.get(index);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (opener == null && Math.abs(bars.get(index - 1).top() - bar.top()) * height > .35f * gap
                || owners.stream().map(score.notes()::get).anyMatch(n -> n.augmentationDots() != 0))
            return null;
        var rows = UnnumberedTripletInk.sourceRows(bar, bars, height);
        WrappedTripletMeasure.Half verified = null;
        for (var plane : new byte[][] {gray, raw}) {
            var proof = singleRailGroups(plane, width, height, bar, score.notes(), owners,
                    true, false, null, false, null, false, false, false, labels);
            if (proof == null) return null;
            float first = proof.x()[0], last = proof.x()[17];
            var unnumbered = proof;
            if (opener == null) {
                if (!WrappedMeasureBoundaries.confirmsClosedTripletBar(plane, width, height, bar, first, last))
                    return null;
            } else {
                var track = WrappedMeasureBoundaries.verifiedTripletStaff(plane, width, height, bar);
                float middle = proof.x()[1];
                if (track == null || !track.verifiedAt(middle)
                        || score.notes().get(owners.get(1)).stemDirection() != 1
                        || !WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(plane, width, height, bar, last)
                        || !MeterFontMatcher.standaloneReadings(labels, plane, width, height, bars, score.notes())
                                .contains(opener)) return null;
                float[] staff = track.at(middle);
                float topRule = staff[0] - 4 * staff[1];
                if (!TupletNumeralBeamBand.matchesOverVerifiedBeam(
                        plane, width, height, Math.round(middle), rows.first(), rows.last(), topRule, staff[1]))
                    return null;
                unnumbered = new WrappedTripletMeasure.Half(owners.subList(3, owners.size()),
                        java.util.Arrays.copyOfRange(proof.x(), 3, owners.size()));
            }
            if (!unnumberedClosedSixFourGroups(score, unnumbered, bar, rows, plane, width, height, gap, words))
                return null;
            verified = proof;
        }
        return verified;
    }

    private static WrappedTripletMeasure.Half numberedStaccatoOpening(
            ScorePageInterpretation score, byte[] gray, byte[] raw, byte[] labels, int width, int height,
            List<PlayingTechniqueDetector.Word> words, int index, ScoreMeterChange meter,
            List<ScoreNoteEvent> undotted, List<Integer> owners) {
        var bars = score.measures();
        var bar = bars.get(index);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25 || (bar.top() - bars.get(index - 1).bottom()) * height <= gap * 1.7f)
            return null;
        for (int i = 0; i < 3; i++) {
            var note = undotted.get(owners.get(i));
            if (note.stemDirection() != 1
                    || ((note.articulations() & NoteArticulation.STACCATO) != 0) != (i != 0)) return null;
        }
        var rows = UnnumberedTripletInk.sourceRows(bar, bars, height);
        WrappedTripletMeasure.Half result = null;
        for (var plane : new byte[][]{raw, gray}) {
            var proof = singleRailGroups(plane, width, height, bar, undotted, owners,
                    true, false, null, true, null, false);
            if (proof == null || !WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(
                    plane, width, height, bar, proof.x()[17])
                    || !MeterFontMatcher.standaloneReadings(labels, plane, width, height, bars, undotted).contains(meter))
                return null;
            float a = proof.x()[0], b = proof.x()[1], c = proof.x()[2];
            float ay = undotted.get(owners.get(0)).pageY() * height;
            float by = undotted.get(owners.get(1)).pageY() * height;
            float cy = undotted.get(owners.get(2)).pageY() * height;
            if (!OmrScoreInterpreter.centerOwnedLowerStaccato(plane, width, height, b, by, gap, 1.14f, 1.62f, .68)
                    || !OmrScoreInterpreter.centerOwnedLowerStaccato(plane, width, height, c, cy, gap, 1.14f, 1.62f, .68)
                    || WrappedTripletDotInk.uniqueCore(plane, width, height, a, ay, b, gap) != null
                    || WrappedTripletDotInk.uniqueCore(plane, width, height, b, by, c, gap) != null
                    || !largeTripletOpeningThree(plane, labels, width, height, b, by, gap)
                    || !unnumberedClosedSixFourGroups(score, new WrappedTripletMeasure.Half(
                            owners.subList(3, owners.size()), java.util.Arrays.copyOfRange(proof.x(), 3, owners.size())),
                            bar, rows, plane, width, height, gap, words)) return null;
            result = proof;
        }
        return result;
    }

    private static boolean largeTripletOpeningThree(
            byte[] plane, byte[] labels, int width, int height, float x, float y, float gap) {
        var shafts = PrintedBeamShafts.at(plane, width, height, x, y, gap, new PrintedBeamShafts.Budget(100_000));
        if (shafts.size() != 1 || shafts.get(0).direction() != -1) return false;
        int tip = shafts.get(0).endY();
        int left = Math.round(x - 1.35f * gap), right = Math.round(x + 1.45f * gap);
        int top = Math.round(tip - 3.2f * gap), bottom = Math.round(tip - .25f * gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return false;
        var window = RawNumeralComponents.captureLongWindow(plane, width, height, left, top, right, bottom, 180);
        int count = 0;
        for (var glyph : window.components()) {
            if (glyph.left <= left || glyph.right >= right || glyph.top <= top || glyph.bottom >= bottom
                    || glyph.size() < gap * gap * .80f || glyph.size() > gap * gap * 1.35f
                    || Math.abs((glyph.left + glyph.right) * .5f - x) > .52f * gap) continue;
            int[] pixels = java.util.stream.IntStream.range(0, glyph.size()).map(glyph::pixel).toArray();
            int labelCount = 0;
            for (int pixel : pixels) if (labels[pixel] == OmrMeasurePostProcessor.SYMBOL) labelCount++;
            if (labelCount >= pixels.length * .75f && TupletNumeralBeamBand.detachedThree(
                    pixels, width, glyph.left, glyph.top, glyph.right, glyph.bottom, gap)) count++;
        }
        return count == 1;
    }

    static boolean provesLedgerTriplets(byte[] gray, byte[] labels, int width, int height,
            List<MeasureRegion> regions, int index, List<ScoreNoteEvent> undotted,
            List<Integer> owners, int dotSlot) {
        var bar=regions.get(index);
        float gap=(bar.bottom()-bar.top())*height/8.2f;
        var edges=ClosedMonophonicSixteenths.adjacentDividers(regions,index,width,height,gap);
        if (edges==null || ClosedMonophonicSixteenths.dividerStaff(gray,width,height,bar,edges,gap)==null)
            return false;
        var proof=singleRailGroups(gray,width,height,bar,undotted,owners,
                true,false,null,true,labels,true);
        if (proof==null || proof.x()[0]-edges[0]<.85f*gap || proof.x()[0]-edges[0]>3.25f*gap
                || edges[1]-proof.x()[17]<.85f*gap || edges[1]-proof.x()[17]>3.25f*gap)
            return false;
        var head=undotted.get(owners.get(dotSlot));
        float x=proof.x()[dotSlot], y=head.pageY()*height;
        var core=WrappedTripletDotInk.uniqueCore(gray,width,height,x,y,proof.x()[dotSlot+1],gap);
        return core!=null && headConnectedLedger(gray,width,height,x,y,core,gap);
    }

    private static ScorePageInterpretation retainLedgerTripletBeams(
            ScorePageInterpretation score, byte[] gray, byte[] raw, int width, int height,
            List<PlayingTechniqueDetector.Word> words, int index, List<Integer> owners, int page) {
        var source = score.beamSource();
        if (page<0 || source.noteOrigins().size()!=score.notes().size()) return score;
        var anchors=new ArrayList<ScoreBeamSource.Anchor>();
        for (int owner : owners) {
            var expected=new ScoreBeamSource.Anchor(page,owner);
            if (!source.noteOrigins().get(owner).filter(expected::equals).isPresent()) return score;
            anchors.add(expected);
        }
        var owned=new java.util.HashSet<>(anchors);
        if (source.detachedColumns().stream().anyMatch(c -> c.members().stream().anyMatch(owned::contains)))
            return score;
        var groups = new ArrayList<>(source.groups());
        var expected = new java.util.HashMap<ScoreBeamSource.Column,List<ScoreBeamSource.Column>>();
        for (int group = 0; group < anchors.size(); group+=3) {
            var columns=anchors.subList(group,group+3).stream()
                    .map(a -> new ScoreBeamSource.Column(List.of(a))).toList();
            expected.put(columns.get(0),columns);
        }
        for (var group : groups) {
            if (group.columns().stream().noneMatch(c -> c.members().stream().anyMatch(owned::contains)))
                continue;
            if (!group.columns().equals(expected.get(group.columns().get(0)))
                    || !group.bridges().equals(List.of(1,1)) || !group.hooks().isEmpty()) return score;
        }
        var bar=score.measures().get(index);
        var rows=UnnumberedTripletInk.sourceRows(bar,score.measures(),height);
        float left=bar.left()*width, span=(bar.right()-bar.left())*width;
        float gap=(bar.bottom()-bar.top())*height/8.2f;
        var additions=new ArrayList<ScoreBeamSource.Group>();
        for (int start=0; start<owners.size(); start+=3) {
            var first=score.notes().get(owners.get(start));
            var mid=score.notes().get(owners.get(start+1));
            var last=score.notes().get(owners.get(start+2));
            float x=left+span*mid.positionInMeasure();
            float top=Math.min(first.pageY(),Math.min(mid.pageY(),last.pageY()))*height;
            float bottom=Math.max(first.pageY(),Math.max(mid.pageY(),last.pageY()))*height;
            var columns = expected.get(new ScoreBeamSource.Column(List.of(anchors.get(start))));
            int existing = groupStartingAt(groups, columns.get(0));
            if (existing>=0 && groups.get(existing).tupletMark()!=null) continue;
            if (localPrintedThree(gray,width,height,x,top,bottom,gap,rows,words)
                    || localPrintedThree(raw,width,height,x,top,bottom,gap,rows,words)) continue;
            String id = existing<0 ? "printed-beam:"+page+":"+anchors.get(start).noteIndex()
                    : groups.get(existing).id();
            var proved = new ScoreBeamSource.Group(id,columns,List.of(1,1),List.of(),
                    ScoreBeamSource.TupletMark.UNNUMBERED);
            if (existing>=0) groups.set(existing,proved);
            else if (groups.stream().noneMatch(g -> g.id().equals(id))) additions.add(proved);
        }
        groups.addAll(additions);
        return groups.equals(source.groups()) ? score
                : score.withBeamSource(new ScoreBeamSource(groups,source.detachedColumns(),source.noteOrigins()));
    }

    /** The printed meter can close the left side of a new staff without a repeat barline. */
    private static boolean provesFlatMarkedSixFourOpening(
            byte[] gray, byte[] raw, byte[] labels, int width, int height,
            List<MeasureRegion> regions, int index, ScoreMeterChange meter,
            List<ScoreNoteEvent> notes, List<Integer> owners) {
        if (owners.stream().map(notes::get).anyMatch(n -> n.beamCount() != 1
                || n.augmentationDots() != 0 || Math.abs(n.stemDirection()) != 1)
                || owners.stream().map(notes::get)
                        .noneMatch(n -> n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FLAT)) return false;
        var bar = regions.get(index);
        for (var plane : new byte[][]{gray, raw}) {
            var proof = singleRailGroups(plane, width, height, bar, notes, owners,
                    true, false, null, false, null, false, false, false, labels);
            if (proof == null || !WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(
                    plane, width, height, bar, proof.x()[17])
                    || !MeterFontMatcher.standaloneReadings(labels, plane, width, height, regions, notes)
                            .contains(meter)) return false;
        }
        return true;
    }

    /** Prove all six rails and both barlines while excluding tracked rule and accidental ink. */
    private static boolean hasClosedContinuingSixFourRails(
            byte[] gray, byte[] raw, byte[] labels, int width, int height,
            List<MeasureRegion> regions, int index, List<ScoreNoteEvent> notes, List<Integer> owners) {
        var bar = regions.get(index);
        var proof = singleRailGroups(gray, width, height, bar, notes, owners,
                true, false, null, false, null, false, false, false, labels);
        if (proof == null) return false;
        float first = proof.x()[0], last = proof.x()[17];
        boolean closed = WrappedMeasureBoundaries.confirmsClosedTripletBar(gray, width, height, bar, first, last)
                && WrappedMeasureBoundaries.confirmsClosedTripletBar(raw, width, height, bar, first, last);
        if (!closed)
            closed = WrappedMeasureBoundaries.confirmsNarrowTripletBarPockets(
                            gray, width, height, regions, index, first, last)
                    && WrappedMeasureBoundaries.confirmsNarrowTripletBarPockets(
                            raw, width, height, regions, index, first, last);
        return closed
                && singleRailGroups(raw, width, height, bar, notes, owners,
                        true, false, null, false, null, false, false, false, labels) != null;
    }

    private static boolean provesStaffOverlaidPairedFlatOpening(
            byte[] gray, byte[] raw, byte[] labels, int width, int height, List<MeasureRegion> regions,
            int index, ScoreMeterChange meter, List<ScoreNoteEvent> notes, List<Integer> owners, int alias) {
        var bar = regions.get(index);
        var proof = singleRailGroups(gray, width, height, bar, notes, owners,
                true, false, null, false, null, true, false, false, labels);
        if (proof == null || proof.owners().get(0) != alias) return false;
        var following = regions.subList(index, regions.size());
        float first = proof.x()[0], last = proof.x()[17];
        return WrappedMeasureBoundaries.confirmsOpeningTripletBar(gray, width, height, following, first, last,
                        true, true)
                && WrappedMeasureBoundaries.confirmsOpeningTripletBar(raw, width, height, following, first, last,
                        true, true)
                && singleRailGroups(raw, width, height, bar, notes, owners,
                        true, false, null, false, null, true, false, false, labels) != null
                && MeterFontMatcher.standaloneReadings(labels, gray, width, height, regions, notes).contains(meter)
                && MeterFontMatcher.standaloneReadings(labels, raw, width, height, regions, notes).contains(meter);
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

    record ReversedUpstemBars(ScorePageInterpretation score, List<Integer> measures) {}

    /** Close four triplets after disambiguating the low shaft read from its beam end. */
    static ReversedUpstemBars closeReversedUpstemTripletBars(ScorePageInterpretation score,
            ScoreMeterChange inherited, byte[] gray, byte[] raw, int width, int height) {
        if (gray == null || raw == null || gray.length != (long) width * height || raw.length != gray.length)
            return new ReversedUpstemBars(score, List.of());
        var bars = score.measures();
        var notes = score.notes();
        var owners = new ArrayList<List<Integer>>(bars.size());
        for (int i = 0; i < bars.size(); i++) owners.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) owners.get(notes.get(i).measureIndex()).add(i);
        var restBars = new java.util.BitSet(bars.size());
        for (var rest : score.rests()) restBars.set(rest.measureIndex());
        var removed = new java.util.BitSet(notes.size());
        var repaired = new ArrayList<Integer>();
        List<ScoreNoteEvent> edits = null;
        ScoreMeterChange meter = inherited;
        int next = 0;
        for (int m = 0; m < bars.size(); m++) {
            while (next < score.meterChanges().size() && score.meterChanges().get(next).measureIndex() <= m)
                meter = score.meterChanges().get(next++);
            if (meter == null || meter.numerator() != 4 || meter.denominator() != 4
                    || restBars.get(m) || owners.get(m).size() != 13 || bars.get(m).cadenceQuarterBeats() != 0)
                continue;
            var ordered = owners.get(m).stream()
                    .sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure())).toList();
            var reversed = ordered.stream().filter(i -> notes.get(i).stemDirection() == -1).toList();
            if (reversed.size() != 1) continue;
            int alias = reversed.get(0), i = ordered.indexOf(alias);
            if (i < 1 || i % 3 != 1) continue;
            int lowOwner = ordered.get(i - 1);
            var low = notes.get(lowOwner);
            var tip = notes.get(alias);
            if (low.stemDirection() != 1 || low.staffStep() >= 0 || tip.staffStep() < 0 || tip.staffStep() > 8
                    || low.clefBottomDiatonic() != tip.clefBottomDiatonic()
                    || !plainReversibleQuarter(low) || !plainReversibleQuarter(tip)) continue;
            var retained = ordered.stream().filter(owner -> owner != alias).toList();
            if (retained.stream().filter(owner -> owner != lowOwner)
                    .map(notes::get).anyMatch(n -> n.stemDirection() != 1 || n.beamCount() != 1)) continue;
            var bar = bars.get(m);
            if (!hasReciprocalLowShaft(gray, width, height, bar, low, tip)
                    || !hasReciprocalLowShaft(raw, width, height, bar, low, tip)) continue;
            var corrected = new ArrayList<>(notes);
            corrected.set(lowOwner, ClosingTripletRhythm.singleBeam(low));
            if (tracedClosedUpstemRails(gray, width, height, bars, m, corrected, retained, null) == null
                    || tracedClosedUpstemRails(raw, width, height, bars, m, corrected, retained, null) == null)
                continue;
            if (edits == null) edits = new ArrayList<>(notes);
            for (int owner : retained)
                edits.set(owner, corrected.get(owner).withTupletRatio(3, 2));
            removed.set(alias);
            repaired.add(m);
        }
        if (edits == null) return new ReversedUpstemBars(score, List.of());
        var kept = new ArrayList<ScoreNoteEvent>(notes.size() - removed.cardinality());
        var origins = new ArrayList<Integer>(kept.size());
        for (int i = 0; i < notes.size(); i++) {
            if (removed.get(i)) continue;
            kept.add(edits.get(i));
            origins.add(i);
        }
        return new ReversedUpstemBars(score.withNotes(kept, origins), List.copyOf(repaired));
    }

    private static boolean plainReversibleQuarter(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffCount() == 1 && n.staffIndex() == 0
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL && n.beamCount() == 0
                && n.unbeamedDurationBeats() == ScoreNoteEvent.DURATION_QUARTER
                && n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && n.augmentationDots() == 0 && n.tupletDivisor() == 1 && !n.crossStaffBeam()
                && !n.tiedFromPrevious() && n.boundaryTies() == 0 && n.articulations() == 0
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0
                && n.harmonicSemitones() == 0 && n.openStringTouch() == null && n.octaveShift() == 0;
    }

    private static boolean hasReciprocalLowShaft(byte[] ink, int width, int height, MeasureRegion bar,
            ScoreNoteEvent low, ScoreNoteEvent tip) {
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float x = left + span * low.positionInMeasure(), y = low.pageY() * height;
        float tx = left + span * tip.positionInMeasure(), ty = tip.pageY() * height;
        if (gap < 10 || gap > 25 || tx - x < gap * .85f || tx - x > gap * 1.30f
                || y - ty < gap * 2.8f || y - ty > gap * 5.0f) return false;
        var budget = new PrintedBeamShafts.Budget(90_000);
        var shaft = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
        var reverse = PrintedBeamShafts.at(ink, width, height, tx, ty, gap, budget);
        return !budget.refused() && shaft.size() == 1 && reverse.size() == 1
                && shaft.get(0).direction() == -1 && reverse.get(0).direction() == 1
                && LedgerBeamTerminalAlias.reciprocalColumns(reverse.get(0), shaft.get(0), ty, y, gap);
    }

    private static WrappedTripletMeasure.Half tracedClosedUpstemRails(
            byte[] ink, int width, int height, List<MeasureRegion> bars, int index,
            List<ScoreNoteEvent> notes, List<Integer> owners,
            java.util.function.BiConsumer<List<PrintedBeamShafts.Shaft>, Boolean> onShafts) {
        var bar = bars.get(index);
        var proof = singleRailGroups(ink, width, height, bar, notes, owners, true, false, null,
                false, null, false, false, false, null, onShafts, true);
        return proof != null && WrappedMeasureBoundaries.confirmsClosedTripletBar(
                ink, width, height, bar, proof.x()[0], proof.x()[11]) ? proof : null;
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
                    || restBars.get(index) || (group.size() != 13 && group.size() != 14)) continue;
            var bar = measures.get(index);
            List<Integer> ghosts;
            boolean crossedFingering = false;
            if (group.size() == 13) {
                ghosts = group.stream().filter(i -> FingeringAnnotationFilter.beamedNumeralAlias(
                        words, bar, notes.get(i), raw, normalized, width, height)).toList();
                if (ghosts.isEmpty()) {
                    ghosts = group.stream().filter(i -> FingeringAnnotationFilter.crossedFingeringThreeAlias(
                            words, bar, notes.get(i), notes, raw, normalized, width, height)).toList();
                    crossedFingering = ghosts.size() == 1;
                }
                if (ghosts.size() != 1) continue;
            } else {
                var whole = group.stream().filter(i -> notes.get(i).stemDirection() == 0
                        && notes.get(i).unbeamedDurationBeats() == 4).toList();
                var beamed = group.stream().filter(i -> notes.get(i).stemDirection() == 0
                        && notes.get(i).beamCount() == 1).toList();
                if (whole.size() != 1 || beamed.size() != 1
                        || !FingeringAnnotationFilter.pairedTripletFingeringAliases(words, bar,
                                notes.get(whole.get(0)), notes.get(beamed.get(0)), notes,
                                normalized, raw, width, height))
                    continue;
                ghosts = List.of(whole.get(0), beamed.get(0));
            }
            var surviving = new ArrayList<>(group);
            surviving.removeAll(ghosts);
            var proved = proveCommonTimeSingleRails(notes, surviving, measures, index, normalized, raw, width, height);
            if (proved == null && ghosts.size() == 1 && (crossedFingering || notes.get(ghosts.get(0)).staffStep() == -2))
                proved = proveCommonTimeSingleRails(notes, surviving, measures, index, normalized, raw, width, height, true);
            if (proved == null) continue;
            for (int owner : surviving) changed.set(owner, proved.get(owner).withTupletRatio(3, 2));
            for (int ghost : ghosts) removed.set(ghost);
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
        if (read == null || retained == null) {
            // A run of ascending staves can leave all three-note beams visible even
            // when the stricter common-time spacing or staff clearance check fails.
            if (railAliases != 0 || order.stream().map(normalized::get).anyMatch(n -> n.stemDirection() != 1))
                return null;
            float first = left + span * normalized.get(order.get(0)).positionInMeasure();
            float last = left + span * normalized.get(order.get(11)).positionInMeasure();
            return provesBoundedUpstemTriplets(gray, width, height, bar, normalized, order, first, last)
                            && provesBoundedUpstemTriplets(original, width, height, bar, normalized, order, first, last)
                    ? normalized : null;
        }
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

    private static boolean provesBoundedUpstemTriplets(byte[] ink, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> owners, float first, float last) {
        return WrappedMeasureBoundaries.confirmsClosedTripletBar(ink, width, height, bar, first, last)
                && mixedRailGroups(ink, width, height, bar, notes, owners, 3, false, true) != null;
    }

    /** A bottom staff rule can look like a second rail or bridge beside an opening beam. */
    private static boolean provesBottomRuleOpening(List<ScoreNoteEvent> notes, List<Integer> owners,
            List<MeasureRegion> regions, byte[] gray, byte[] original, int width, int height) {
        if (regions.size() < 2 || owners.size() != 12) return false;
        var bar = regions.get(0);
        var following = regions.get(1);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 24 || Math.abs(bar.top() - following.top()) * height > .34f * gap
                || (following.left() - bar.right()) * width < .6f * gap
                || (following.left() - bar.right()) * width > 1.6f * gap) return false;
        var ordered = owners.stream().map(notes::get)
                .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
        int clef = ordered.get(0).clefBottomDiatonic();
        if (clef == ScoreNoteEvent.CLEF_UNKNOWN || ordered.stream().anyMatch(n ->
                n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffCount() != 1 || n.staffIndex() != 0
                || n.clefBottomDiatonic() != clef || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                || n.crossStaffBeam() || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0
                || n.tiedFromPrevious() || n.boundaryTies() != 0 || (n.articulations() & NoteOrnament.GRACE) != 0
                || n.beamCount() != 1 || n.augmentationDots() != 0 || n.unbeamedDurationBeats() != 0
                || !(n.tupletDivisor() == 1 || n.tupletDivisor() == 3 && n.tupletNormalNotes() == 2))) return false;
        int upwardCount = ordered.get(6).stemDirection() == 1 ? 9 : 6;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).stemDirection() != (i < upwardCount ? 1 : -1)) return false;
        }
        var next = java.util.stream.IntStream.range(0, notes.size()).filter(i -> notes.get(i).measureIndex() == 1)
                .boxed().toList();
        if (next.size() != 12) return false;
        return bottomRuleOpening(gray, width, height, bar, following, ordered, notes, next, gap, upwardCount)
                && bottomRuleOpening(original, width, height, bar, following, ordered, notes, next, gap, upwardCount);
    }

    private static boolean bottomRuleOpening(byte[] ink, int width, int height, MeasureRegion bar,
            MeasureRegion following, List<ScoreNoteEvent> owners, List<ScoreNoteEvent> all,
            List<Integer> next, float gap, int upwardCount) {
        var continuation = singleRailGroups(ink, width, height, following, all, next, true);
        if (continuation == null || !WrappedMeasureBoundaries.confirmsClosedTripletBar(
                ink, width, height, following, continuation.x()[0], continuation.x()[11])) return false;
        boolean threeUpwardGroups = upwardCount == 9;
        var staff = WrappedMeasureBoundaries.verifiedTripletStaff(ink, width, height, bar, threeUpwardGroups);
        if (staff == null) return false;
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float first = left + owners.get(0).positionInMeasure() * span;
        float last = left + owners.get(owners.size() - 1).positionInMeasure() * span;
        if (first - left < 4.5f * gap || first - left > 8.3f * gap) return false;
        boolean closed = threeUpwardGroups
                ? WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(ink, width, height, bar, last, staff)
                : WrappedMeasureBoundaries.confirmsSampledClosingEdge(ink, width, height, bar, last);
        if (!closed) return false;
        var budget = new PrintedBeamShafts.Budget(220_000);
        PrintedBeamShafts.Shaft previous = null;
        float previousX = 0;
        int staffAliases = 0;
        for (int i = 0; i < owners.size(); i++) {
            var note = owners.get(i);
            float x = left + note.positionInMeasure() * span;
            var ownersHere = PrintedBeamShafts.at(ink, width, height, x, note.pageY() * height, gap, budget);
            if (ownersHere.size() != 1 || budget.refused()) return false;
            var shaft = ownersHere.get(0);
            if (shaft.direction() != -note.stemDirection()) return false;
            if (previous != null) {
                boolean cut = i % 3 == 0;
                float maximum = threeUpwardGroups && cut && previous.direction() != shaft.direction() ? 3f : 2.75f;
                if (x - previousX < 1.7f * gap || x - previousX > maximum * gap) return false;
                if (!matchesBeamRails(ink, width, height, previous, shaft, gap, cut ? 0 : 1)) {
                    if (i < 7 || shaft.direction() != 1 || previous.direction() != 1 || ++staffAliases > 2)
                        return false;
                    var rule = isolatedBottomRule(ink, width, height, previous, shaft, gap, staff);
                    boolean bottomRule = rule != null && (cut
                            ? bestRibbon(ink, width, height, previous, shaft, gap, rule).coverage() <= .46f
                            : matchesRailCount(ink, width, height, previous, shaft, gap, false, rule));
                    if (!bottomRule && !(threeUpwardGroups && !cut
                            && mixedSingleRailOverStaff(ink, width, height, previous, shaft, gap, staff))) return false;
                }
            }
            previous = shaft;
            previousX = x;
        }
        return staffAliases > 0;
    }

    private static RuleBand isolatedBottomRule(byte[] ink, int width, int height,
            PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, float gap, StaffPitchTrack staff) {
        int inset = Math.round(.45f * gap);
        int start = (a.left() + a.right()) / 2 + inset, end = (b.left() + b.right()) / 2 - inset;
        if (start < 0 || end >= width || end - start < .6f * gap) return null;
        int[] rows = new int[end - start + 1];
        int clean = 0, radius = Math.round(.29f * gap);
        for (int x = start; x <= end; x++) {
            int y = Math.round(staff.at(x)[0]);
            int thickness = ruleThickness(ink, width, height, x, y, radius);
            if (thickness >= 1 && thickness <= Math.round(.28f * gap)) {
                rows[x - start] = y;
                clean++;
            } else rows[x - start] = -height;
        }
        return clean >= .86f * rows.length ? new RuleBand(start, rows, radius) : null;
    }


    /** The printed flat beside a staccato note may enter the preceding augmentation search. */
    static ScorePageInterpretation closeDottedCommonTimeWithFlat(
            ScorePageInterpretation score, ScoreMeterChange inherited, byte[] labels,
            byte[] gray, byte[] raw, int width, int height) {
        if (labels == null || gray == null || raw == null || gray.length != (long) width * height
                || raw.length != gray.length || labels.length != gray.length) return score;
        var original = score.notes();
        List<ScoreNoteEvent> edited = original;
        var regions = score.measures();
        var beam = score.beamSource();
        var groups = new ArrayList<>(beam.groups());
        var byMeasure = new ArrayList<List<Integer>>(regions.size());
        for (int i = 0; i < regions.size(); i++) byMeasure.add(new ArrayList<>());
        for (int i = 0; i < original.size(); i++) byMeasure.get(original.get(i).measureIndex()).add(i);
        var rests = new java.util.BitSet(regions.size());
        for (var rest : score.rests()) rests.set(rest.measureIndex());
        ScoreMeterChange meter = inherited == null ? new ScoreMeterChange(0, 4, 4) : inherited;
        int next = 0;
        for (int m = 0; m < regions.size(); m++) {
            while (next < score.meterChanges().size()
                    && score.meterChanges().get(next).measureIndex() <= m) meter = score.meterChanges().get(next++);
            var owners = byMeasure.get(m);
            var bar = regions.get(m);
            if (meter.numerator() != 4 || meter.denominator() != 4 || owners.size() != 12 || rests.get(m)
                    || bar.cadenceQuarterBeats() != 0) continue;
            var withDot = owners.stream().filter(i -> original.get(i).augmentationDots() != 0).toList();
            if (withDot.size() != 1) continue;
            int marked = withDot.get(0);
            var note = original.get(marked);
            if (note.augmentationDots() != 1 || note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT
                    || note.stemDirection() != 1 || (note.articulations() & NoteArticulation.STACCATO) == 0
                    || owners.stream().map(original::get).anyMatch(n -> n.beamCount() != 1
                            || n.stemDirection() != 1 || n.tupletDivisor() != 1)) continue;
            var order = owners.stream().sorted(Comparator.comparingDouble(i -> original.get(i).positionInMeasure())).toList();
            int ordinal = order.indexOf(marked);
            if (ordinal == 0 || ordinal == order.size() - 1) continue;
            float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            float x = left + span * note.positionInMeasure(), y = note.pageY() * height;
            float before = left + span * original.get(order.get(ordinal - 1)).positionInMeasure();
            float after = left + span * original.get(order.get(ordinal + 1)).positionInMeasure();
            if (!ownsFlatWithoutDot(gray, labels, width, height, x, y, before,
                            original.get(order.get(ordinal - 1)).pageY() * height, after, gap)
                    || !ownsFlatWithoutDot(raw, labels, width, height, x, y, before,
                            original.get(order.get(ordinal - 1)).pageY() * height, after, gap)) continue;
            var restored = new ArrayList<>(edited);
            restored.set(marked, withoutDots(note));
            var normal = singleRailGroups(gray, width, height, bar, restored, order, true, true, labels);
            var source = singleRailGroups(raw, width, height, bar, restored, order, true, true, labels);
            if (normal == null || source == null
                    || !commonTimeTripletBoundary(gray, width, height, regions, m, normal.x()[0], normal.x()[11])
                    || !commonTimeTripletBoundary(raw, width, height, regions, m, source.x()[0], source.x()[11]))
                continue;
            for (int i : order) restored.set(i, restored.get(i).withTupletRatio(3, 2));
            edited = restored;
            markFlatTripletGroups(beam, groups, restored, order, regions, m, gray, raw, width, height);
        }
        if (edited == original) return score;
        var result = score.withNotes(edited, java.util.stream.IntStream.range(0, edited.size()).boxed().toList());
        return groups.equals(beam.groups()) ? result
                : result.withBeamSource(new ScoreBeamSource(groups, beam.detachedColumns(), beam.noteOrigins()));
    }

    private static boolean ownsFlatWithoutDot(byte[] gray, byte[] labels, int width, int height,
            float x, float y, float before, float beforeY, float after, float gap) {
        if (gap < 10 || gap > 25 || WrappedTripletDotInk.uniqueCore(gray, width, height, x, y, after, gap) != null)
            return false;
        var core = WrappedTripletDotInk.uniqueCore(gray, width, height, before, beforeY, x, gap);
        return core != null && WrappedTripletDotInk.labelledFlatOwns(labels, gray, width, height, core, x, y, gap);
    }

    private static void markFlatTripletGroups(ScoreBeamSource beam, List<ScoreBeamSource.Group> groups,
            List<ScoreNoteEvent> notes, List<Integer> order, List<MeasureRegion> regions, int m,
            byte[] gray, byte[] raw, int width, int height) {
        if (!flatTripletMarkingClear(gray, width, height, notes, order, regions, m)
                || !flatTripletMarkingClear(raw, width, height, notes, order, regions, m)) return;
        for (int i = 0; i < order.size(); i += 3) {
            var columns = existingTripletColumns(beam, order.subList(i, i + 3));
            if (columns.isEmpty()) continue;
            int index = groupStartingAt(groups, columns.get(0));
            if (index < 0) continue;
            var group = groups.get(index);
            if (group.tupletMark() == null && group.columns().equals(columns)
                    && group.bridges().equals(List.of(1, 1)) && group.hooks().isEmpty())
                groups.set(index, new ScoreBeamSource.Group(group.id(), columns, group.bridges(), group.hooks(),
                        ScoreBeamSource.TupletMark.UNNUMBERED));
        }
    }

    private static boolean flatTripletMarkingClear(byte[] gray, int width, int height,
            List<ScoreNoteEvent> notes, List<Integer> order, List<MeasureRegion> regions, int m) {
        var bar = regions.get(m);
        var staff = WrappedMeasureBoundaries.verifiedTripletStaff(gray, width, height, bar);
        if (staff == null) return false;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float[] xs = new float[order.size()], ys = new float[order.size()];
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(order.size());
        var budget = new PrintedBeamShafts.Budget(300_000);
        for (int i = 0; i < order.size(); i++) {
            var n = notes.get(order.get(i));
            xs[i] = left + span * n.positionInMeasure();
            ys[i] = n.pageY() * height;
            var source = PrintedBeamShafts.at(gray, width, height, xs[i], ys[i], gap, budget);
            if (source.size() != 1 || budget.refused()) return false;
            shafts.add(source.get(0));
        }
        return clearUnnumberedFingeringTriplets(gray, width, height, regions, m, notes, order,
                gap, staff, xs, ys, shafts);
    }

    private static boolean sampledCommonTimeTripletBar(
            List<ScoreNoteEvent> notes, List<MeasureRegion> regions, int index,
            WrappedTripletMeasure.Half proof, byte[] gray, byte[] original, int width, int height) {
        if (index < 1 || proof.owners().size() != 12) return false;
        var prior = regions.get(index - 1);
        var region = regions.get(index);
        var previous = notes.stream().filter(n -> n.measureIndex() == index - 1
                        && n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffCount() == 1 && n.staffIndex() == 0)
                .mapToDouble(n -> (prior.left() + (prior.right() - prior.left()) * n.positionInMeasure()) * width)
                .max();
        if (previous.isEmpty()) return false;
        float first = proof.x()[0], last = proof.x()[11], lastPrior = (float) previous.getAsDouble();
        return WrappedMeasureBoundaries.confirmsSampledTripletBar(
                        gray, width, height, prior, region, lastPrior, first, last)
                && WrappedMeasureBoundaries.confirmsSampledTripletBar(
                        original, width, height, prior, region, lastPrior, first, last)
                && singleRailGroups(original, width, height, region, notes, proof.owners(), true) != null;
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
                || singleRailGroups(original, width, height, bar, notes, owners, true, false, null, true) == null) {
            // A second-looking rail is valid only when the measured staff owns it in both planes.
            proof = singleRailGroups(
                    gray, width, height, bar, notes, owners, true, false, null, false, null, true);
            if (proof == null || singleRailGroups(
                    original, width, height, bar, notes, owners, true, false, null, false, null, true) == null)
                return null;
        }
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
            List<PlayingTechniqueDetector.Word> words, int physicalPage, byte[] labels) {
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
                    || Math.abs(regions.get(m - 1).top() - bar.top()) * height > .35f * gap)
                continue;
            var proof = fullSixFourTriplets(score, m, normalized, original, width, height);
            boolean ruledRails = false;
            if (proof == null) {
                var owners = fullSixFourOwners(score, m);
                if (!owners.isEmpty()) {
                    var overlap = singleRailGroups(normalized, width, height, bar, score.notes(), owners,
                            true, false, null, true, null, true);
                    if (overlap != null && singleRailGroups(original, width, height, bar, score.notes(), owners,
                            true, false, null, true, null, true) != null) {
                        proof = overlap;
                        ruledRails = true;
                    }
                }
            }
            var rawShafts = new ArrayList<PrintedBeamShafts.Shaft>();
            var normalizedShafts = new ArrayList<PrintedBeamShafts.Shaft>();
            if (proof == null && labels != null && labels.length == original.length) {
                var owners = fullSixFourOwners(score, m);
                if (!owners.isEmpty()) {
                    var faint = new java.util.concurrent.atomic.AtomicBoolean();
                    var faded = singleRailGroups(normalized, width, height, bar, score.notes(), owners,
                            true, false, null, false, null, false, false, false, labels,
                            (shafts, recovered) -> { normalizedShafts.addAll(shafts); if (recovered) faint.set(true); });
                    var rawFaded = singleRailGroups(original, width, height, bar, score.notes(), owners,
                            true, false, null, false, null, false, false, false, labels,
                            (shafts, recovered) -> { rawShafts.addAll(shafts); if (recovered) faint.set(true); });
                    if (faded != null && rawFaded != null && faint.get()
                            && rawShafts.size() == owners.size() && normalizedShafts.size() == owners.size()) {
                        proof = faded;
                    } else {
                        rawShafts.clear();
                        normalizedShafts.clear();
                    }
                }
            }
            boolean requireRetainedRails = proof == null || ruledRails;
            if (proof == null) {
                var owners = fullSixFourOwners(score, m).stream()
                        .sorted(Comparator.comparingDouble(i -> score.notes().get(i).positionInMeasure())).toList();
                if (owners.isEmpty() || owners.stream().map(score.notes()::get).anyMatch(n ->
                        n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffIndex() != 0 || n.staffCount() != 1
                                || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                                || n.augmentationDots() != 0 || n.unbeamedDurationBeats() != 0
                                || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0
                                || n.tiedFromPrevious() || n.boundaryTies() != 0 || n.crossStaffBeam()))
                    continue;
                float[] centers = new float[owners.size()];
                for (int i = 0; i < centers.length; i++)
                    centers[i] = width * (bar.left() + (bar.right() - bar.left())
                            * score.notes().get(owners.get(i)).positionInMeasure());
                proof = new WrappedTripletMeasure.Half(owners, centers);
            }
            float left = proof.x()[0], right = proof.x()[17];
            if (!WrappedMeasureBoundaries.confirmsClosedTripletBar(
                            normalized, width, height, bar, left, right)
                    || !WrappedMeasureBoundaries.confirmsClosedTripletBar(
                            original, width, height, bar, left, right)) continue;
            var rows = UnnumberedTripletInk.sourceRows(bar, regions, height);
            if (unnumberedClosedSixFourGroups(
                            score, proof, bar, rows, original, width, height, gap, words, ruledRails, rawShafts)
                    && unnumberedClosedSixFourGroups(
                            score, proof, bar, rows, normalized, width, height, gap, words, ruledRails, normalizedShafts))
                result = !requireRetainedRails && proof.owners().stream().allMatch(
                                owner -> score.beamSource().noteOrigins().get(owner).isEmpty())
                        ? addPrintedSixFourGroups(result, proof, physicalPage, ScoreBeamSource.TupletMark.UNNUMBERED)
                        : markRetainedSixFourGroups(result, proof, physicalPage, requireRetainedRails, false);
        }
        return result;
    }

    /** Exact source rails can already have anchors even though the numeral scan has no verdict. */
    private static ScorePageInterpretation markRetainedSixFourGroups(
            ScorePageInterpretation score, WrappedTripletMeasure.Half proof, int page, boolean requireExact,
            boolean firstNumbered) {
        var source = score.beamSource();
        var origins = source.noteOrigins();
        var groups = new ArrayList<>(source.groups());
        var originsAfter = new ArrayList<>(origins);
        var counts = new HashMap<ScoreBeamSource.Anchor, Integer>();
        for (var origin : origins) origin.ifPresent(a -> counts.merge(a, 1, Integer::sum));
        for (int start = 0; start < proof.owners().size(); start += 3) {
            var columns = new ArrayList<ScoreBeamSource.Column>(3);
            for (int i = start; i < start + 3; i++) {
                var anchor = origins.get(proof.owners().get(i)).orElse(null);
                int owner = proof.owners().get(i);
                if (anchor == null && i == start + 2) {
                    anchor = new ScoreBeamSource.Anchor(page, owner);
                    if (counts.containsKey(anchor) || hasBeamOwner(source, anchor)) return score;
                    originsAfter.set(owner, java.util.Optional.of(anchor));
                    counts.put(anchor, 1);
                }
                if (anchor == null || anchor.pageIndex() != page || counts.get(anchor) != 1) return score;
                columns.add(new ScoreBeamSource.Column(List.of(anchor)));
            }
            if (source.detachedColumns().stream().anyMatch(c ->
                    c.members().stream().anyMatch(anchor -> columns.stream().anyMatch(v -> v.members().contains(anchor)))))
                return score;
            int index = -1;
            for (int j = 0; j < groups.size(); j++) {
                if (groups.get(j).columns().stream().noneMatch(c ->
                        c.members().stream().anyMatch(anchor -> columns.stream().anyMatch(v -> v.members().contains(anchor)))))
                    continue;
                if (index >= 0) return score;
                index = j;
            }
            var mark = start == 0 && firstNumbered
                    ? ScoreBeamSource.TupletMark.NUMBERED : ScoreBeamSource.TupletMark.UNNUMBERED;
            String id;
            if (index < 0) {
                if (requireExact) return score;
                id = "beam-page:" + page + ":" + columns.get(0).members().get(0).noteIndex();
                if (groups.stream().anyMatch(g -> g.id().equals(id))) return score;
            } else {
                var group = groups.get(index);
                boolean exact = group.columns().equals(columns) && group.bridges().equals(List.of(1, 1));
                boolean partial = group.bridges().equals(List.of(1))
                        && (group.columns().equals(columns.subList(0, 2))
                            || group.columns().equals(columns.subList(1, 3)));
                if ((!exact && (requireExact || !partial)) || !group.hooks().isEmpty()
                        || group.tupletMark() != null && group.tupletMark() != mark) return score;
                id = group.id();
                groups.remove(index);
            }
            groups.add(index < 0 ? groups.size() : index, new ScoreBeamSource.Group(
                    id, columns, List.of(1, 1), List.of(), mark));
        }
        return groups.equals(source.groups()) ? score
                : score.withBeamSource(new ScoreBeamSource(groups, source.detachedColumns(), originsAfter));
    }

    private static boolean unnumberedClosedSixFourGroups(
            ScorePageInterpretation score, WrappedTripletMeasure.Half proof, MeasureRegion bar,
            UnnumberedTripletInk.Rows rows, byte[] plane, int width, int height, float gap,
            List<PlayingTechniqueDetector.Word> words) {
        return unnumberedClosedSixFourGroups(score, proof, bar, rows, plane, width, height, gap, words, false);
    }

    private static boolean unnumberedClosedSixFourGroups(
            ScorePageInterpretation score, WrappedTripletMeasure.Half proof, MeasureRegion bar,
            UnnumberedTripletInk.Rows rows, byte[] plane, int width, int height, float gap,
            List<PlayingTechniqueDetector.Word> words, boolean ruledRails) {
        return unnumberedClosedSixFourGroups(score, proof, bar, rows, plane, width, height, gap, words,
                ruledRails, List.of());
    }

    private static boolean unnumberedClosedSixFourGroups(
            ScorePageInterpretation score, WrappedTripletMeasure.Half proof, MeasureRegion bar,
            UnnumberedTripletInk.Rows rows, byte[] plane, int width, int height, float gap,
            List<PlayingTechniqueDetector.Word> words, boolean ruledRails, List<PrintedBeamShafts.Shaft> proven) {
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
                if (!proven.isEmpty()) {
                    shafts[j] = proven.get(i + j);
                    continue;
                }
                var source = PrintedBeamShafts.at(plane, width, height, x[j], y[j], gap, budget);
                if (source.size() == 1 && source.get(0).direction() == direction)
                    shafts[j] = source.get(0);
            }
            if (budget.refused()) return false;
            var group = !proven.isEmpty() && java.util.Arrays.stream(shafts).noneMatch(java.util.Objects::isNull)
                    ? new WrappedShaftContinuation.Packed(shafts, -1)
                    : WrappedShaftContinuation.recoverGroup(plane, width, height, gap, x, y, direction, shafts);
            if (group == null && ruledRails && direction == 1
                    && java.util.Arrays.stream(shafts).allMatch(java.util.Objects::nonNull)
                    && (singleRail(plane, width, height, shafts[0], shafts[1], gap)
                        || secondaryRibbonIsStaffRule(plane, width, height, shafts[0], shafts[1], gap, staff))
                    && (singleRail(plane, width, height, shafts[1], shafts[2], gap)
                        || secondaryRibbonIsStaffRule(plane, width, height, shafts[1], shafts[2], gap, staff)))
                group = new WrappedShaftContinuation.Packed(shafts, -1);
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
            var twos = words.stream().filter(word -> {
                if (!word.text().strip().equals("2")) return false;
                float gx = (word.left() + word.right()) * width * .5f;
                float gy = (word.top() + word.bottom()) * height * .5f;
                return direction == -1 && Math.abs(gx - x[1]) <= .48f * gap
                        && tipTop - gy >= .14f * gap && tipTop - gy <= gap * 2.15f
                        && (word.right() - word.left()) * width <= gap * 2f
                        && (word.bottom() - word.top()) * height <= gap * 2.2f;
            }).toList();
            if (ruledRails && UnnumberedTripletInk.clearRuledFingeringBands(
                            plane,width,height,gap,x,y,headTop,headBottom,tipTop,tipBottom,rows,staff)) continue;
            if (!UnnumberedTripletInk.clearFingeringBands(
                            plane, width, height, gap, x[0], x[2], headTop, headBottom,
                            tipTop, tipBottom, rows, staff, twos.size() == 1 ? twos.get(0) : null,
                            List.of(), proven.isEmpty() ? List.of() : words)) return false;
        }
        return true;
    }

    static ScorePageInterpretation retainTerminalFingerTriplets(
            ScorePageInterpretation score, List<PrintedFingeringDigits.Mark> fingers,
            byte[] raw, byte[] normalized, int width, int height) {
        if (fingers.isEmpty() || raw == null || normalized == null
                || raw.length != (long) width * height || normalized.length != raw.length) return score;
        var terminalFingers = new HashSet<Integer>();
        for (var finger : fingers) terminalFingers.add(finger.noteIndex());
        if (terminalFingers.isEmpty()) return score;
        var source = score.beamSource();
        var notes = score.notes();
        var bars = score.measures();
        var updates = new java.util.HashMap<String, ScoreBeamSource.Group>();
        for (var resolved : source.resolve().groups()) {
            var group = resolved.source();
            if (group.tupletMark() != null || !group.bridges().equals(List.of(1, 1)) || !group.hooks().isEmpty()
                    || resolved.columns().stream().anyMatch(c -> !c.usable() || c.owners().size() != 1)) continue;
            var owners = resolved.columns().stream().map(c -> c.owners().get(0)).toList();
            if (!terminalFingers.contains(owners.get(2))) continue;
            var members = owners.stream().map(notes::get).toList();
            int measure = members.get(0).measureIndex();
            if (measure < 0 || measure >= bars.size() || members.stream().anyMatch(n ->
                    n.measureIndex() != measure || n.stemDirection() != 1 || n.staffCount() != 1
                            || n.kind() != ScoreNoteEvent.Kind.PITCHED || n.beamCount() != 1
                            || n.tupletDivisor() != 3 || n.tupletNormalNotes() != 2
                            || n.augmentationDots() != 0 || n.crossStaffBeam()
                            || n.visualSize() != ScoreNoteEvent.VisualSize.FULL)) continue;
            var bar = bars.get(measure);
            var rows = UnnumberedTripletInk.sourceRows(bar, bars, height);
            if (!terminalFingerClear(raw, width, height, bar, rows, members)
                    || !terminalFingerClear(normalized, width, height, bar, rows, members)) continue;
            updates.put(group.id(), new ScoreBeamSource.Group(group.id(), group.columns(), group.bridges(),
                    group.hooks(), ScoreBeamSource.TupletMark.UNNUMBERED));
        }
        if (updates.isEmpty()) return score;
        return score.withBeamSource(new ScoreBeamSource(source.groups().stream()
                .map(group -> updates.getOrDefault(group.id(), group)).toList(),
                source.detachedColumns(), source.noteOrigins()));
    }

    private static boolean terminalFingerClear(byte[] ink, int width, int height, MeasureRegion bar,
            UnnumberedTripletInk.Rows rows, List<ScoreNoteEvent> members) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 24) return false;
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float[] x = new float[3], y = new float[3];
        var budget = new PrintedBeamShafts.Budget(72_000);
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(3);
        for (int i = 0; i < 3; i++) {
            var n = members.get(i);
            x[i] = left + span * n.positionInMeasure();
            y[i] = n.pageY() * height;
            if (i > 0 && (x[i] - x[i - 1] < 1.85f * gap || x[i] - x[i - 1] > 3.6f * gap))
                return false;
            var axes = PrintedBeamShafts.at(ink, width, height, x[i], y[i], gap, budget);
            if (axes.size() != 1 || axes.get(0).direction() != -1
                    || i > 0 && !matchesBeamRails(ink, width, height, shafts.get(i - 1), axes.get(0), gap, 1))
                return false;
            shafts.add(axes.get(0));
        }
        float headTop = Math.min(y[0], Math.min(y[1], y[2]));
        float headBottom = Math.max(y[0], Math.max(y[1], y[2]));
        int tip = shafts.stream().mapToInt(PrintedBeamShafts.Shaft::endY).min().orElseThrow();
        var track = WrappedMeasureBoundaries.closedTripletStaff(ink, width, height, bar);
        return !budget.refused() && track != null
                && !UnnumberedTripletInk.bracketBesideBeam(ink, width, height, gap,
                        x[0], x[2], headTop, headBottom, tip, rows, track, true)
                && !localPrintedThree(ink, width, height, x[1], Math.min(headTop, tip), headBottom,
                        gap, rows, List.of())
                && !centeredDetachedOpeningInk(ink, width, height, x[1], Math.min(headTop, tip),
                        headBottom, gap, rows);
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
            if (m < 0 || m + 1 >= bars.size()) continue;
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
            boolean commonRails = proveCommonTimeSingleRails(
                    notes, owners, bars, m, normalized, raw, width, height) != null;
            boolean neighborRules = commonRails && WrappedMeasureBoundaries.confirmsNeighborBoundedTripletBar(
                        normalized, width, height, bars, m, first, last)
                    && WrappedMeasureBoundaries.confirmsNeighborBoundedTripletBar(
                        raw, width, height, bars, m, first, last);
            boolean openingRules = score.meterChanges().contains(new ScoreMeterChange(m, 4, 4))
                    && (m == 0 && provesBottomRuleOpening(notes, owners, bars, normalized, raw, width, height)
                            || !commonRails
                            && proveCommonTimeSingleRails(notes, owners, bars, m, normalized, raw, width, height,
                                    false, true) != null);
            if (m == 0 && !openingRules) continue;
            boolean sampledRules = false;
            if (m > 0 && !commonRails && !openingRules) {
                var proof = singleRailGroups(normalized, width, height, bar, notes, owners, true);
                sampledRules = proof != null && sampledCommonTimeTripletBar(
                        notes, bars, m, proof, normalized, raw, width, height);
            }
            if (!commonRails && !openingRules && !sampledRules
                    && proveStaffRuleAndFlatTriplets(notes, owners, bars, m, normalized, raw, width, height) == null)
                continue;
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            float top = bar.top() * height + 2.1f * gap, bottom = bar.bottom() * height - 2.1f * gap;
            var rawStaff = StaffPitchTrack.forPrintedSlurs(raw, width, height, top, bottom, gap);
            var correctedStaff = StaffPitchTrack.forPrintedSlurs(normalized, width, height, top, bottom, gap);
            var rows = UnnumberedTripletInk.sourceRows(bar, bars, height);
            CommonRailNotation rawNotation = null, correctedNotation = null;
            if (commonRails && (rawStaff == null || correctedStaff == null)) {
                rawNotation = commonRailNotation(raw, width, height, bar, notes, owners);
                correctedNotation = commonRailNotation(normalized, width, height, bar, notes, owners);
            }
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
                boolean unnumbered = openingRules || sampledRules || commonRails && !neighborRules
                        ? openingTripletClear(raw, width, height, bar, members, rows, rawStaff, words, m == 0 || sampledRules)
                            && openingTripletClear(normalized, width, height, bar, members, rows, correctedStaff, words,
                                    m == 0 || sampledRules)
                        : neighborTripletClear(raw, width, height, bar, members, rows, rawStaff, words)
                            && neighborTripletClear(normalized, width, height, bar, members, rows, correctedStaff, words);
                if (!unnumbered && rawNotation != null && correctedNotation != null) {
                    unnumbered = rawNotation.unnumbered(raw, width, height, members, rows, words, gap, i)
                            && correctedNotation.unnumbered(normalized, width, height, members, rows, words, gap, i);
                }
                if (unnumbered)
                    groups.set(g, new ScoreBeamSource.Group(group.id(), group.columns(),
                            group.bridges(), group.hooks(), ScoreBeamSource.TupletMark.UNNUMBERED));
            }
        }
        return groups.equals(source.groups()) ? score : score.withBeamSource(
                new ScoreBeamSource(groups, source.detachedColumns(), source.noteOrigins()));
    }

    static ScorePageInterpretation projectReversedUpstemTripletBeams(ScorePageInterpretation score,
            byte[] gray, byte[] raw, int width, int height, List<PlayingTechniqueDetector.Word> words,
            List<Integer> repairedBars) {
        if (repairedBars.isEmpty()) return score;
        var source = score.beamSource();
        var groups = new ArrayList<>(source.groups());
        var detached = new ArrayList<>(source.detachedColumns());
        var notes = score.notes();
        var bars = score.measures();
        for (int index : repairedBars) {
            if (index >= bars.size() || score.rests().stream().anyMatch(r -> r.measureIndex() == index)) continue;
            var owners = java.util.stream.IntStream.range(0, notes.size())
                    .filter(i -> notes.get(i).measureIndex() == index).boxed()
                    .sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure())).toList();
            if (owners.size() != 12 || owners.stream().map(notes::get).anyMatch(n -> n.stemDirection() != 1
                    || n.beamCount() != 1 || n.tupletDivisor() != 3 || n.tupletNormalNotes() != 2)) continue;
            var columns = existingTripletColumns(source, owners);
            if (columns.size() != 12) continue;
            var bar = bars.get(index);
            var norm = upwardRailNotation(gray, width, height, bars, index, notes, owners);
            var orig = upwardRailNotation(raw, width, height, bars, index, notes, owners);
            if (norm == null || orig == null) continue;
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            var rows = UnnumberedTripletInk.sourceRows(bar, bars, height);
            for (int n = 0; n < owners.size(); n += 3) {
                var triplet = columns.subList(n, n + 3);
                var keys = triplet.stream().map(c -> c.members().get(0)).toList();
                var prior = groups.stream().filter(g -> g.columns().stream()
                        .anyMatch(c -> c.members().stream().anyMatch(keys::contains))).toList();
                if (prior.size() > 1 || prior.stream().anyMatch(g -> !g.hooks().isEmpty()
                        || g.bridges().stream().anyMatch(bridge -> bridge != 1)
                        || java.util.Collections.indexOfSubList(triplet, g.columns()) < 0)
                        || detached.stream().anyMatch(c -> c.members().stream().anyMatch(keys::contains)
                                && !triplet.contains(c))) continue;
                var first = keys.get(0);
                String id = prior.isEmpty()
                        ? "printed-beam:" + first.pageIndex() + ":" + first.noteIndex()
                        : prior.get(0).id();
                if (prior.isEmpty() && groups.stream().anyMatch(g -> g.id().equals(id))) continue;
                var mark = prior.isEmpty() ? null : prior.get(0).tupletMark();
                var heads = owners.subList(n, n + 3).stream().map(notes::get).toList();
                if (mark == null && norm.unnumbered(gray, width, height, heads, rows, words, gap, n)
                        && orig.unnumbered(raw, width, height, heads, rows, words, gap, n))
                    mark = ScoreBeamSource.TupletMark.UNNUMBERED;
                var group = new ScoreBeamSource.Group(id, triplet, List.of(1, 1), List.of(), mark);
                if (prior.isEmpty()) groups.add(group);
                else groups.set(groups.indexOf(prior.get(0)), group);
                detached.removeAll(triplet);
            }
        }
        return groups.equals(source.groups()) && detached.equals(source.detachedColumns()) ? score
                : score.withBeamSource(new ScoreBeamSource(groups, detached, source.noteOrigins()));
    }

    private static CommonRailNotation upwardRailNotation(byte[] ink, int width, int height,
            List<MeasureRegion> bars, int index, List<ScoreNoteEvent> notes, List<Integer> owners) {
        var bar = bars.get(index);
        var staff = WrappedMeasureBoundaries.verifiedTripletStaff(ink, width, height, bar, true);
        if (staff == null) return null;
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(owners.size());
        var rails = tracedClosedUpstemRails(ink, width, height, bars, index, notes, owners,
                (found, ignored) -> shafts.addAll(found));
        return rails == null || shafts.size() != owners.size() ? null
                : new CommonRailNotation(staff, rails.x(), shafts);
    }

    private record CommonRailNotation(StaffPitchTrack staff, float[] positions,
            List<PrintedBeamShafts.Shaft> shafts) {
        boolean unnumbered(byte[] ink, int width, int height, List<ScoreNoteEvent> members,
                UnnumberedTripletInk.Rows rows, List<PlayingTechniqueDetector.Word> words, float gap, int first) {
            float[] x = java.util.Arrays.copyOfRange(positions, first, first + 3);
            float[] y = new float[3];
            for (int i = 0; i < 3; i++) y[i] = members.get(i).pageY() * height;
            return openingTripletClearWithShafts(ink, width, height, members, rows, staff, words,
                    true, gap, shafts.subList(first, first + 3), x, y);
        }
    }

    private static CommonRailNotation commonRailNotation(byte[] ink, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> owners) {
        var staff = WrappedMeasureBoundaries.verifiedStaffPockets(ink, width, height, bar, true);
        if (staff == null) return null;
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(owners.size());
        var rails = singleRailGroups(ink, width, height, bar, notes, owners, true, false, null,
                false, null, false, false, false, null, (found, ignored) -> shafts.addAll(found));
        return rails == null || shafts.size() != owners.size() ? null
                : new CommonRailNotation(staff, rails.x(), shafts);
    }

    private static boolean openingTripletClear(byte[] ink, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> heads, UnnumberedTripletInk.Rows rows, StaffPitchTrack staff,
            List<PlayingTechniqueDetector.Word> words, boolean openingFloor) {
        return openingTripletClear(ink, width, height, bar, heads, rows, staff, words, openingFloor, false);
    }

    private static boolean openingTripletClear(byte[] ink, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> heads, UnnumberedTripletInk.Rows rows, StaffPitchTrack staff,
            List<PlayingTechniqueDetector.Word> words, boolean openingFloor, boolean interruptedEnd) {
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
            if (axis.isEmpty() && interruptedEnd && i == heads.size() - 1) {
                var shaft = LedgerInterruptedDownshaft.measure(ink, width, height, bar, x[i], y[i], gap);
                if (shaft != null) axis = List.of(shaft);
            }
            if (axis.size() != 1 || axis.get(0).direction() != -head.stemDirection() || budget.refused())
                return false;
            shafts.add(axis.get(0));
        }
        return openingTripletClearWithShafts(
                ink, width, height, heads, rows, staff, words, openingFloor, gap, shafts, x, y);
    }

    private static boolean openingTripletClearWithShafts(byte[] ink, int width, int height,
            List<ScoreNoteEvent> heads, UnnumberedTripletInk.Rows rows, StaffPitchTrack staff,
            List<PlayingTechniqueDetector.Word> words, boolean openingFloor, float gap,
            List<PrintedBeamShafts.Shaft> shafts, float[] x, float[] y) {
        float headTop = Math.min(y[0], Math.min(y[1], y[2])), headBottom = Math.max(y[0], Math.max(y[1], y[2]));
        int tipTop = shafts.stream().mapToInt(PrintedBeamShafts.Shaft::endY).min().orElseThrow();
        int tipBottom = shafts.stream().mapToInt(PrintedBeamShafts.Shaft::endY).max().orElseThrow();
        if (openingFloor && (localPrintedThree(ink, width, height, x[1], Math.min(headTop, tipTop),
                        Math.max(headBottom, tipBottom), gap, rows, words)
                || centeredDetachedOpeningInk(ink, width, height, x[1], Math.min(headTop, tipTop),
                        Math.max(headBottom, tipBottom), gap, rows))) return false;
        if (UnnumberedTripletInk.clearWithTrackedStaff(ink, width, height, gap, x, y, shafts, rows, staff, words))
            return true;
        var accidentals = new ArrayList<UnnumberedTripletInk.AccidentalHead>(3);
        for (int i = 0; i < heads.size(); i++)
            accidentals.add(new UnnumberedTripletInk.AccidentalHead(x[i], y[i], heads.get(i).writtenAccidental()));
        return !localPrintedThree(ink, width, height, x[1], Math.min(headTop, tipTop),
                        Math.max(headBottom, tipBottom), gap, rows, words)
                && UnnumberedTripletInk.clearFingeringBands(ink, width, height, gap, x[0], x[2], headTop,
                        headBottom, tipTop, tipBottom, rows, staff, null, accidentals, words);
    }

    /** Retain a possible printed number even when OCR has no token for the detached shape. */
    private static boolean centeredDetachedOpeningInk(byte[] ink, int width, int height, float x,
            float musicTop, float musicBottom, float gap, UnnumberedTripletInk.Rows rows) {
        int left = Math.round(x - .86f * gap), right = Math.round(x + .86f * gap);
        int top = Math.max(rows.first(), Math.round(musicTop - 3.3f * gap));
        int bottom = Math.min(rows.last(), Math.round(musicBottom + 3.3f * gap));
        if (left < 0 || right >= width || top < 0 || bottom >= height || bottom <= top) return true;
        var components = RawNumeralComponents.captureLongWindow(ink, width, height, left, top, right, bottom, 165);
        return components.components().stream().anyMatch(c -> c.left > left && c.right < right
                && c.top > top && c.bottom < bottom
                && (c.bottom <= musicTop - .25f * gap || c.top >= musicBottom + .25f * gap)
                && c.right - c.left + 1 >= .40f * gap && c.right - c.left + 1 <= 1.10f * gap
                && c.bottom - c.top + 1 >= .72f * gap && c.bottom - c.top + 1 <= 1.55f * gap
                && c.size() >= .22f * gap * gap
                && Math.abs((c.left + c.right) * .5f - x) < .45f * gap
                && !UnnumberedTripletInk.isUnambiguousOne(ink, width, c));
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
        return singleRailGroups(gray, width, height, bar, notes, candidates, closedBar, allowSingleFlat,
                labels, thinStaffOpening, naturalNeighborLabels, staffOverlap, closeFlatAndStaffCut,
                faintClosingShaft, null);
    }

    private static WrappedTripletMeasure.Half singleRailGroups(
            byte[] gray, int width, int height, MeasureRegion bar, List<ScoreNoteEvent> notes,
            List<Integer> candidates, boolean closedBar, boolean allowSingleFlat, byte[] labels,
            boolean thinStaffOpening, byte[] naturalNeighborLabels, boolean staffOverlap,
            boolean closeFlatAndStaffCut, boolean faintClosingShaft, byte[] continuingSixFourLabels) {
        return singleRailGroups(gray, width, height, bar, notes, candidates, closedBar, allowSingleFlat,
                labels, thinStaffOpening, naturalNeighborLabels, staffOverlap, closeFlatAndStaffCut,
                faintClosingShaft, continuingSixFourLabels, null);
    }

    private static WrappedTripletMeasure.Half singleRailGroups(
            byte[] gray, int width, int height, MeasureRegion bar, List<ScoreNoteEvent> notes,
            List<Integer> candidates, boolean closedBar, boolean allowSingleFlat, byte[] labels,
            boolean thinStaffOpening, byte[] naturalNeighborLabels, boolean staffOverlap,
            boolean closeFlatAndStaffCut, boolean faintClosingShaft, byte[] continuingSixFourLabels,
            java.util.function.BiConsumer<List<PrintedBeamShafts.Shaft>, Boolean> validatedShafts) {
        return singleRailGroups(gray, width, height, bar, notes, candidates, closedBar, allowSingleFlat,
                labels, thinStaffOpening, naturalNeighborLabels, staffOverlap, closeFlatAndStaffCut,
                faintClosingShaft, continuingSixFourLabels, validatedShafts, false);
    }

    private static WrappedTripletMeasure.Half singleRailGroups(
            byte[] gray, int width, int height, MeasureRegion bar, List<ScoreNoteEvent> notes,
            List<Integer> candidates, boolean closedBar, boolean allowSingleFlat, byte[] labels,
            boolean thinStaffOpening, byte[] naturalNeighborLabels, boolean staffOverlap,
            boolean closeFlatAndStaffCut, boolean faintClosingShaft, byte[] continuingSixFourLabels,
            java.util.function.BiConsumer<List<PrintedBeamShafts.Shaft>, Boolean> validatedShafts,
            boolean traceOpening) {
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
        int unclassifiedStem = -1;
        boolean packedUnclassifiedStem = false;
        boolean cancellationOwnsRival = false;
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>();
        boolean recoveredFaintShaft = false;
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
                    || (clef == ScoreNoteEvent.CLEF_UNKNOWN && !traceOpening && !(closedBar && x.length == 12))
                    || n.clefBottomDiatonic() != clef
                    || !(n.tupletDivisor() == 1
                            || n.tupletDivisor() == 3 && n.tupletNormalNotes() == 2)
                    || (Math.abs(n.stemDirection()) != 1
                            && (n.stemDirection() != 0 || continuingSixFourLabels == null
                                    || unclassifiedStem >= 0))) return null;
            if (n.stemDirection() == 0) unclassifiedStem = i;
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
                if (space < gap * 1.65f) {
                    if (i != unclassifiedStem || i % 3 != 0 || space < gap * 1.5f) return null;
                    packedUnclassifiedStem = true;
                }
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
            if (!recoveredFaintShaft && closedBar && continuingSixFourLabels != null && i % 3 == 2
                    && n.stemDirection() == -1 && source.isEmpty() && !budget.refused()) {
                var recovered = faintDownstem(gray, width, height, x[i], y[i], gap, true);
                if (recovered != null) {
                    recoveredFaintShaft = true;
                    source = List.of(recovered);
                }
            }
            if (faintClosingShaft && i == owners.size() - 1 && source.isEmpty() && !budget.refused()) {
                var retained = faintDownstem(gray, width, height, x[i], y[i], gap);
                if (retained != null) source = List.of(retained);
            }
            if (traceOpening && source.size() <= 1) {
                int direction = -n.stemDirection();
                var trace = OpeningShaftTrace.scan(gray, width, height, x[i], y[i], gap, direction);
                if (i == 1 && trace == null) {
                    var next = notes.get(owners.get(i + 1));
                    float xx = left + (right - left) * next.positionInMeasure();
                    var third = OpeningShaftTrace.scan(gray, width, height, xx,
                            next.pageY() * height, gap, direction);
                    trace = OpeningShaftTrace.anchored(gray, width, height, x[i], y[i], gap,
                            shafts.get(0), third);
                }
                if (trace != null && (i < 3 || source.isEmpty() || source.size() == 1
                        && source.get(0).direction() == direction
                        && direction * (trace.endY() - source.get(0).endY()) >= .75f * gap)) {
                    if (OpeningShaftTrace.scan(gray, width, height, x[i], y[i], gap, -direction) != null)
                        return null;
                    source = List.of(trace);
                }
            }
            if (closedBar && i != unclassifiedStem && i % 3 == 1 && source.isEmpty() && !budget.refused()) {
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
            if (i == unclassifiedStem && i % 3 == 0 && source.size() == 2) {
                var next = notes.get(owners.get(i + 1));
                float nextX = left + (right - left) * next.positionInMeasure();
                var rival = source.stream().filter(shaft -> shaft.direction() == -1).findFirst();
                if (rival.isPresent() && next.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FLAT
                        && cancellationNaturalOwnsShaft(gray, continuingSixFourLabels, width, height,
                                rival.get(), x[i], nextX, next.pageY() * height, gap)) {
                    source = source.stream().filter(shaft -> shaft != rival.get()).toList();
                    cancellationOwnsRival = true;
                }
            }
            if (source.size() != 1 || i != unclassifiedStem
                    && source.get(0).direction() != -n.stemDirection()) return null;
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
        boolean closedDoubleFlatOwnsDot = false;
        boolean sharpOwnsDot = false;
        int staffRailLinks = 0, staffCuts = 0;
        boolean verifiedLedgerCap = false;
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
            boolean ownedDoubleFlat = continuingSixFourLabels != null && dot != null
                    && (notes.get(owners.get(i + 1)).writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_DOUBLE_FLAT
                            || staffOverlap
                                && notes.get(owners.get(i + 1)).writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FLAT)
                    && WrappedTripletDotInk.splitFlatPairOwns(continuingSixFourLabels, gray,
                            width, height, dot, x[i + 1], y[i + 1], gap);
            closedDoubleFlatOwnsDot |= ownedDoubleFlat;
            if (dot != null && !closeFlatOwns
                    && !ownedDoubleFlat
                    && !(continuingSixFourLabels != null
                            && (naturalOwnsDot(gray, continuingSixFourLabels, width, height, dot,
                                            x[i + 1], y[i + 1], gap)
                                    || notes.get(owners.get(i + 1)).writtenAccidental()
                                            == ScoreNoteEvent.ACCIDENTAL_FLAT
                                        && WrappedTripletDotInk.connectedFlatOwns(
                                                continuingSixFourLabels, gray, width, height, dot,
                                                x[i + 1], y[i + 1], gap)))
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
                            || !headConnectedLedger(gray, width, height, x[i], y[i], dot, gap,
                                    x.length == 12 && staffOverlap ? .48f : .35f))
                    && !(continuingSixFourLabels != null && x.length == 18
                            && Math.abs(dot.y() - y[i]) <= .15f * gap
                            && headConnectedLedger(gray, width, height, x[i], y[i], dot, gap, .48f))
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
            if (dot != null && staffOverlap && x.length == 12 && !flatOwnsDot
                    && headConnectedLedger(gray, width, height, x[i], y[i], dot, gap, .48f))
                verifiedLedgerCap = true;
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
                                && ((x.length == 12 || continuingSixFourLabels != null)
                                        && staffRuleOnlyCut(gray, width, height, bar, a, b, gap, ribbon, false)
                                    || continuingSixFourLabels != null
                                        && shortStaffRuleOnlyCut(gray, width, height, bar, a, b, gap, ribbon)
                                    || thinStaffOpening && x.length == 18
                                        && thinStaffRuleBetweenRails(gray, width, height, bar, a, b, gap, ribbon));
                        if (!staffRule && closedBar && x.length == 12 && i == 8)
                            staffRule = closedTripletStaffCut(gray, width, height, bar, a, b, gap);
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
            if (recoveredFaintShaft && i + 2 == owners.size() && a.direction() == -1
                    && !singleRail(gray, width, height, a, b, gap)) {
                var extended = faintLastUpstem(gray, width, height, shafts.get(i - 1), a, b, gap);
                if (extended != null) {
                    b = extended;
                    shafts.set(i + 1, extended);
                }
            }
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
        boolean valid = ((unclassifiedStem < 0 && (!staffOverlap || continuingSixFourLabels == null))
                        || closedDoubleFlatOwnsDot || cancellationOwnsRival)
                        && (!packedUnclassifiedStem || cancellationOwnsRival)
                        && (!closeFlatAndStaffCut || closeFlat && staffCuts > 0)
                        && (!staffOverlap || staffRailLinks > 0 || verifiedLedgerCap)
                        && (!allowSingleFlat || flatOwnsDot)
                        && (!closedNaturalPredecessor || naturalOwnsDot)
                        && (!closedSharpBar || sharpOwnsDot);
        if (!valid) return null;
        if (validatedShafts != null)
            validatedShafts.accept(shafts, recoveredFaintShaft);
        return new WrappedTripletMeasure.Half(owners, x);
    }

    private static PrintedBeamShafts.Shaft faintDownstem(
            byte[] gray, int width, int height, float x, float y, float gap) {
        return faintDownstem(gray, width, height, x, y, gap, false);
    }

    private static PrintedBeamShafts.Shaft faintDownstem(
            byte[] gray, int width, int height, float x, float y, float gap, boolean longStem) {
        int left = Math.round(x - gap * .79f), right = Math.round(x - gap * .43f);
        int start = Math.round(y + gap * .22f), limit = Math.round(y + gap * (longStem ? 7.4f : 3.05f));
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
            if (row > limit || row - 1 - y < gap * (longStem ? 4.6f : 1.86f) || faint == 0
                    || faint > gap * (longStem ? 1.25f : .45f)
                    || longest > gap * (longStem ? .48f : .25f)) continue;
            columns.add(new PrintedBeamShafts.Column(col, row - 1));
        }
        if (columns.size() != 2 || columns.get(0).x() + 1 != columns.get(1).x()) return null;
        var a = columns.get(0);
        var b = columns.get(1);
        int end = Math.min(a.endY(), b.endY());
        if (Math.abs(a.endY() - b.endY()) > 2) return null;
        return new PrintedBeamShafts.Shaft(a.x(), b.x(), start, end, 1, columns);
    }

    /** Extend a faint terminal stem only to a proved continuation of the preceding beam. */
    private static PrintedBeamShafts.Shaft faintLastUpstem(
            byte[] gray, int width, int height, PrintedBeamShafts.Shaft first,
            PrintedBeamShafts.Shaft middle, PrintedBeamShafts.Shaft last, float gap) {
        if (first == null || first.direction() != -1 || middle.direction() != -1 || last.direction() != -1
                || !singleRail(gray, width, height, first, middle, gap)) return null;
        int a = (first.left() + first.right()) / 2, b = (middle.left() + middle.right()) / 2;
        int x = (last.left() + last.right()) / 2;
        if (a >= b || b >= x || x < 4 || width - x <= 4) return null;
        int target = Math.round(middle.endY() + (middle.endY() - first.endY()) * (x - b) / (float) (b - a));
        int length = last.endY() - target;
        if (length < gap * .65f || length > gap * 1.9f || target < 2 || target + 2 >= height) return null;
        for (int candidate = target - 2; candidate <= target + 2; candidate++) {
            int seen = 0, run = 0, longest = 0;
            for (int y = candidate; y <= last.endY(); y++) {
                int at = y * width + x, value = gray[at] & 255;
                int contrast = Math.max(gray[at - 4] & 255, gray[at + 4] & 255) - value;
                if (value < 180 || value < 245 && contrast >= 12) {
                    seen++;
                    run = 0;
                } else longest = Math.max(longest, ++run);
            }
            if (seen < (last.endY() - candidate + 1) * .77f || longest > gap * .29f) continue;
            final int end = candidate;
            var columns = last.columns().stream().map(c -> new PrintedBeamShafts.Column(c.x(), end)).toList();
            var proved = new PrintedBeamShafts.Shaft(last.left(), last.right(), last.startY(), end, -1, columns);
            if (singleRail(gray, width, height, middle, proved, gap)) return proved;
        }
        return null;
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

    /** The putative upshaft is the first spine of a natural immediately followed by a flat. */
    private static boolean cancellationNaturalOwnsShaft(byte[] gray, byte[] labels, int width, int height,
            PrintedBeamShafts.Shaft rival, float headX, float nextX, float nextY, float gap) {
        float beyondHead = rival.left() - headX, beforeFlat = nextX - rival.right();
        int length = rival.startY() - rival.endY() + 1;
        if (rival.right() - rival.left() + 1 > gap * .27f
                || beyondHead < gap * .58f || beyondHead > gap * 1.05f
                || beforeFlat < gap * 2.65f || beforeFlat > gap * 3.25f
                || length < gap * 1.8f || length > gap * 3.2f
                || !PrintedFlatGlyph.matchesOwnedInk(labels, gray, width, height, nextX, nextY, gap)) return false;
        int owned = 0, spineRight = Math.min(width - 1, rival.right() + Math.round(gap * .20f));
        for (int y = rival.endY(); y <= rival.startY(); y++) {
            for (int x = rival.left(); x <= spineRight; x++) {
                int pixel = y * width + x;
                if (labels[pixel] != OmrMeasurePostProcessor.CLEF_OR_KEY || (gray[pixel] & 255) >= 170)
                    continue;
                owned++;
                break;
            }
        }
        if (owned < length * .74f) return false;

        int left = rival.left() - Math.round(gap * .13f);
        int top = Math.round(nextY - gap * 2f), bottom = Math.round(nextY + gap * 2f);
        int w = Math.round(gap * 1.5f), h = bottom - top + 1;
        if (left < 0 || left + w > width || top < 0 || bottom >= height) return false;
        var ink = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = (top + y) * width + left + x;
                ink[y * w + x] = labels[pixel] == OmrMeasurePostProcessor.CLEF_OR_KEY
                        ? gray[pixel] : (byte) 255;
            }
        }
        for (int last = Math.round(gap * .85f); last < w; last++) {
            if (OmrScoreInterpreter.provesNaturalMeterInk(ink, w, h, 0, last, nextY - top, gap, h + 1))
                return true;
        }
        return false;
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

    /** At a proven staff row, reject a bridge unless it adds ink above the rule. */
    static boolean matchesLowerStaffCut(byte[] plane, int w, int h,
            PrintedBeamShafts.Shaft left, PrintedBeamShafts.Shaft right, float staffTop, float gap) {
        if (left.direction() != 1 || right.direction() != 1) return false;
        int lo = left.right() + Math.round(gap * .34f);
        int hi = right.left() - Math.round(gap * .34f);
        int center = Math.round(staffTop + 2 * gap);
        if (lo < 0 || hi >= w || hi - lo < .7f * gap || center < gap || center >= h
                || Math.abs(left.endY() - center) > gap * .22f
                || Math.abs(right.endY() - center) > gap * .22f) return false;
        int cut = Math.round(gap * .16f), height = Math.round(gap * .58f);
        for (int x = lo; x <= hi; x++) {
            if ((plane[center * w + x] & 255) >= 205) return false;
            for (int dy = cut; dy <= height; dy++)
                if ((plane[(center - dy) * w + x] & 255) < 205) return false;
        }
        return true;
    }

    /** A continuous band tightly follows the ends of two down-stems through a rule crossing. */
    static boolean matchesContinuedLowerRail(byte[] plane, int w, int h,
            PrintedBeamShafts.Shaft left, PrintedBeamShafts.Shaft right, float gap) {
        if (left.direction() != 1 || right.direction() != 1) return false;
        int x0 = (left.left() + left.right()) / 2, x1 = (right.left() + right.right()) / 2;
        if (x1 - x0 < 1.5f * gap || x1 - x0 > 3.5f * gap
                || Math.abs(right.endY() - left.endY()) > .22f * (x1 - x0)) return false;
        int margin = Math.round(gap * .25f), thick = Math.round(gap * .25f);
        for (int x = x0 + margin; x <= x1 - margin; x++) {
            int y = Math.round(left.endY() + (right.endY() - left.endY()) * (x - x0) / (float) (x1 - x0));
            if (x < 0 || x >= w || y - thick < 0 || y + margin >= h) return false;
            for (int dy = 1; dy <= thick; dy++)
                if ((plane[(y - dy) * w + x] & 255) >= 205) return false;
            int below = 0;
            for (int dy = 2; dy <= margin; dy++)
                if ((plane[(y + dy) * w + x] & 255) >= 205) below++;
            if (below < margin - 2) return false;
        }
        return true;
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

    /** Discard an apparent link only when every intervening column proves five thin staff rules. */
    static boolean separatedOctaveQuartets(byte[] ink, int width, int height,
            PrintedBeamShafts.Shaft left, PrintedBeamShafts.Shaft right, float gap, float staffBottom) {
        if (bestRibbon(ink, width, height, left, right, gap).coverage() < .90f) return true;
        int padding = Math.round(gap * .45f);
        int from = (left.left() + left.right()) / 2 + padding;
        int to = (right.left() + right.right()) / 2 - padding;
        if (from < 0 || to >= width || to - from < gap) return false;
        var rules = new PrintedBeamRuleWitness.Witness[to - from + 1];
        for (int x = from; x <= to; x++) {
            var witness = PrintedBeamRuleWitness.at(ink, width, height, x, staffBottom, gap);
            if (witness == null) return false;
            int min = Integer.MAX_VALUE, max = 0;
            for (var band : witness.rules()) {
                int thickness = band.bottom() - band.top() + 1;
                min = Math.min(min, thickness);
                max = Math.max(max, thickness);
            }
            if (max - min > 1) return false;
            rules[x - from] = witness;
        }
        return bestRibbon(ink, width, height, left, right, gap,
                (x, y) -> rules[x - from].contains(y)).coverage() <= .46f;
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
        return matchesRailCount(gray, width, height, a, b, gap, doubleRail, null);
    }

    private static boolean matchesRailCount(byte[] gray, int width, int height,
            PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, float gap,
            boolean doubleRail, RuleBand ignored) {
        var ribbon = bestRibbon(gray, width, height, a, b, gap, ignored);
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
                            .19f,
                            ignored)
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
            byte[] gray, int width, int height, MeasureRegion bar,
            ScoreNoteEvent left, ScoreNoteEvent right) {
        return mixedCut(gray, width, height, bar, left, right, false);
    }

    private static boolean mixedCut(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            ScoreNoteEvent left,
            ScoreNoteEvent right,
            boolean alignedRuleGap) {
        return mixedCut(gray, width, height, bar, left, right, alignedRuleGap, false);
    }

    private static boolean mixedCut(byte[] gray, int width, int height, MeasureRegion bar,
            ScoreNoteEvent left, ScoreNoteEvent right, boolean alignedRuleGap, boolean fadedDownstaff) {
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
        if (a.isEmpty() && alignedRuleGap && left.staffStep() > 8 && left.stemDirection() == -1) {
            var shaft = LedgerInterruptedDownshaft.measure(gray, width, height, bar,
                    ax, left.pageY() * height, gap);
            if (shaft != null) a = List.of(shaft);
        }
        if (fadedDownstaff)
            b = fadedDownstaffAxes(gray, width, height, bx, right.pageY() * height, gap, b);
        if (a.size() != 1 || b.size() != 1
                || a.get(0).direction() != -left.stemDirection()
                || b.get(0).direction() != -right.stemDirection()) return false;
        if (a.get(0).direction() != b.get(0).direction()) return true;
        var ribbon = bestRibbon(gray, width, height, a.get(0), b.get(0), gap);
        return ribbon.coverage() <= .46f
                || alignedRuleGap && ribbon.coverage() < .75f
                        && thinStaffRuleBetweenRails(gray, width, height, bar, a.get(0), b.get(0), gap, ribbon);
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
        return provesUpperLedgerRailSequence(gray, width, height, bars, measure, notes, owners, mixed, false);
    }

    static boolean provesClosedUpperLedgerTriplets(byte[] gray, int width, int height,
            List<MeasureRegion> bars, int measure, List<ScoreNoteEvent> notes, List<Integer> owners) {
        return owners.size() == 12
                && provesUpperLedgerRailSequence(gray, width, height, bars, measure, notes, owners, false, true);
    }

    /** A closed 4/4 lane can start on the staff and continue into separate high single-rail groups. */
    static boolean provesStaffOverlaidLedgerTriplets(byte[] ink, int width, int height,
            MeasureRegion bar, List<ScoreNoteEvent> notes, List<Integer> owners) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (owners.size() != 12 || gap < 12 || gap > 24) return false;
        var staff = WrappedMeasureBoundaries.verifiedTripletStaff(ink, width, height, bar);
        if (staff == null) staff = WrappedMeasureBoundaries.verifiedTripletStaff(ink, width, height, bar, true);
        if (staff == null) return false;
        float[] xs = new float[12], ys = new float[12];
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(12);
        var budget = new PrintedBeamShafts.Budget(800_000);
        for (int i = 0; i < 12; i++) {
            var note = notes.get(owners.get(i));
            float x = (bar.left() + note.positionInMeasure() * (bar.right() - bar.left())) * width;
            float y = note.pageY() * height;
            float[] rules = staff.at(x);
            if (Math.abs(rules[1] - gap) > .14f * gap || y > rules[0] - 3.25f * rules[1]
                    || i > 0 && (x - xs[i - 1] < 1.55f * gap || x - xs[i - 1] > 4.65f * gap)) return false;
            var axes = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
            if (axes.size() != 1 || axes.get(0).direction() != 1 || budget.refused()) return false;
            shafts.add(axes.get(0));
            xs[i] = x;
            ys[i] = y;
        }
        if (!WrappedMeasureBoundaries.confirmsClosedTripletBar(ink, width, height, bar, xs[0], xs[11])) return false;
        for (int i = 0; i < 11; i++) {
            var dot = WrappedTripletDotInk.uniqueCore(ink, width, height, xs[i], ys[i], xs[i + 1], gap);
            if (dot != null && (notes.get(owners.get(i + 1)).writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_DOUBLE_SHARP
                    || !ClosedDyadsBeforeHalf.sharpOwnsDotGap(ink, width, height, gap,
                            xs[i], ys[i], xs[i + 1], ys[i + 1]))) return false;
            var a = shafts.get(i);
            var b = shafts.get(i + 1);
            if (i % 3 == 2) {
                if (!cutBetweenRecoveredGroups(ink, width, height, bar, a, b, gap)
                        && !thinStaffRuleBetweenTripletShafts(ink, width, height, staff, a, b, gap)) return false;
            } else if (!trackedRailCount(ink, width, height, staff, a, b, gap, 1)
                    && !matchesBeamRails(ink, width, height, a, b, gap, 1)) return false;
        }
        return true;
    }

    private static boolean provesUpperLedgerRailSequence(byte[] gray, int width, int height,
            List<MeasureRegion> bars, int measure, List<ScoreNoteEvent> notes,
            List<Integer> owners, boolean mixed, boolean staffRuleFingerings) {
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
            if (dot != null && !headConnectedLedger(gray, width, height, xs[i], ys[i], dot, gap, .48f)
                    && !(staffRuleFingerings
                            && notes.get(owners.get(i + 1)).writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FLAT
                            && WrappedTripletDotInk.singleFlatOwns(
                                    gray, width, height, dot, xs[i + 1], ys[i + 1], gap))) return false;
            int rails = i < 6 || !mixed ? (i % 3 == 2 ? 0 : 1) : (i == 9 ? 0 : 2);
            var a = shafts.get(i);
            var b = shafts.get(i + 1);
            if (!trackedRailCount(gray, width, height, track, a, b, gap, rails)
                    && !(staffRuleFingerings && rails == 0
                            && thinStaffRuleBetweenTripletShafts(gray, width, height, track, a, b, gap))) return false;
        }
        return !staffRuleFingerings || clearUnnumberedFingeringTriplets(
                gray, width, height, bars, measure, notes, owners, gap, track, xs, ys, shafts);
    }

    private static boolean clearUnnumberedFingeringTriplets(
            byte[] gray, int width, int height, List<MeasureRegion> bars, int measure,
            List<ScoreNoteEvent> notes, List<Integer> owners, float gap, StaffPitchTrack track,
            float[] xs, float[] ys, List<PrintedBeamShafts.Shaft> shafts) {
        var rows = UnnumberedTripletInk.sourceRows(bars.get(measure), bars, height);
        for (int i = 0; i < xs.length; i += 3) {
            var accidentals = new ArrayList<UnnumberedTripletInk.AccidentalHead>(3);
            float top = Float.POSITIVE_INFINITY, bottom = 0;
            int tipTop = height, tipBottom = 0;
            for (int j = i; j < i + 3; j++) {
                int accidental = notes.get(owners.get(j)).writtenAccidental();
                if (accidental != ScoreNoteEvent.ACCIDENTAL_FROM_KEY)
                    accidentals.add(new UnnumberedTripletInk.AccidentalHead(xs[j], ys[j], accidental));
                top = Math.min(top, ys[j]);
                bottom = Math.max(bottom, ys[j]);
                tipTop = Math.min(tipTop, shafts.get(j).endY());
                tipBottom = Math.max(tipBottom, shafts.get(j).endY());
            }
            float upper = Math.min(top, tipTop), lower = Math.max(bottom, tipBottom);
            if (UnnumberedTripletInk.centeredThree(gray, width, height, xs[i + 1], upper - gap * 3.4f,
                            upper - gap * .40f, gap, rows, track)
                    || UnnumberedTripletInk.centeredThree(gray, width, height, xs[i + 1], lower + gap * .35f,
                            lower + gap * 3.65f, gap, rows, track)
                    || !UnnumberedTripletInk.clearFingeringBands(gray, width, height, gap,
                            xs[i], xs[i + 2], top, bottom, tipTop, tipBottom, rows, track, null, accidentals))
                return false;
        }
        return true;
    }

    /** A staff rule and its attached fingering may continue across a real break in the beam. */
    private static boolean thinStaffRuleBetweenTripletShafts(byte[] gray, int width, int height,
            StaffPitchTrack track, PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, float gap) {
        int first = (a.left() + a.right()) / 2 + Math.round(gap * .55f);
        int last = (b.left() + b.right()) / 2 - Math.round(gap * .43f);
        int collar = Math.round(gap * .45f), thickest = Math.round(gap * .255f);
        int streak = 0, minimum = Math.round(gap * .90f);
        for (int x = first; x <= last; x++) {
            var staff = track.at(x);
            int row = Math.round(staff[0] - 2 * staff[1]);
            if (row - collar < 0 || row + collar >= height) return false;
            // Require actual paper either side of the rule for an unbroken interval.
            int ink = 0;
            for (int y = row - collar; y <= row + collar; y++) {
                if ((gray[y * width + x] & 255) < 155) ink++;
            }
            if ((gray[row * width + x] & 255) < 155 && ink <= thickest) {
                if (++streak >= minimum) return true;
            } else streak = 0;
        }
        return false;
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
                        notes.get(candidates.get(5)), notes.get(candidates.get(6)), true);
    }

    static boolean provesInterruptedUpstaffMixedRails(byte[] ink, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> owners) {
        var track = WrappedMeasureBoundaries.closedTripletStaff(ink, width, height, bar);
        if (track == null || owners.size() != 14) return false;
        if (mixedRailGroups(ink, width, height, bar, notes, owners.subList(0, 6),
                        3, false, true, null, track) == null
                || mixedRailGroups(ink, width, height, bar, notes, owners.subList(6, 14),
                        4, true, true, null, track) == null) return false;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        var first = notes.get(owners.get(5));
        var second = notes.get(owners.get(6));
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        var budget = new PrintedBeamShafts.Budget(150_000);
        var a = upstaffAxes(ink, width, height, left + span * first.positionInMeasure(), first.pageY() * height,
                gap, track, budget);
        var b = upstaffAxes(ink, width, height, left + span * second.positionInMeasure(), second.pageY() * height,
                gap, track, budget);
        return a.size() == 1 && b.size() == 1
                && cutBetweenRecoveredGroups(ink, width, height, bar, a.get(0), b.get(0), gap);
    }

    static int unnumberedInterruptedUpstaffTriplets(byte[] raw, byte[] normalized, int width, int height,
            List<MeasureRegion> bars, List<ScoreNoteEvent> notes, int measure, List<Integer> owners) {
        var bar = bars.get(measure);
        var rows = UnnumberedTripletInk.sourceRows(bar, bars, height);
        float offset = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        int clear = 3;
        for (var ink : List.of(raw, normalized)) {
            var staff = WrappedMeasureBoundaries.closedTripletStaff(ink, width, height, bar);
            if (staff == null) return 0;
            for (int group = 0; group < 2; group++) {
                var heads = owners.subList(group * 3, group * 3 + 3).stream().map(notes::get).toList();
                var budget = new PrintedBeamShafts.Budget(100_000);
                var shafts = new ArrayList<PrintedBeamShafts.Shaft>(3);
                float[] x = new float[3], y = new float[3];
                for (int i = 0; i < 3; i++) {
                    var head = heads.get(i);
                    x[i] = offset + span * head.positionInMeasure();
                    y[i] = head.pageY() * height;
                    var axis = upstaffAxes(ink, width, height, x[i], y[i], gap, staff, budget);
                    if (axis.size() != 1 || budget.refused()) break;
                    shafts.add(axis.get(0));
                }
                if (shafts.size() != 3 || !openingTripletClearWithShafts(
                        ink, width, height, heads, rows, staff, List.of(), true, gap, shafts, x, y))
                    clear &= ~(1 << group);
            }
        }
        return clear;
    }

    private static List<PrintedBeamShafts.Shaft> upstaffAxes(byte[] ink, int width, int height,
            float x, float y, float gap, StaffPitchTrack track, PrintedBeamShafts.Budget budget) {
        var ordinary = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
        if (!ordinary.isEmpty()) return ordinary.size() == 1 && ordinary.get(0).direction() == -1 ? ordinary : List.of();
        var contrast = ClosedOctaveTripletBars.contrastShaft(ink, width, height, x, y, gap, budget);
        if (!contrast.isEmpty()) return contrast.size() == 1 && contrast.get(0).direction() == -1 ? contrast : List.of();
        var split = InterruptedUpstaffShaft.measure(ink, width, height, x, y, gap, track);
        return split == null ? List.of() : List.of(split);
    }

    static boolean provesFadedDownstaffMixedRails(byte[] gray, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> owners) {
        return mixedRailGroups(gray, width, height, bar, notes, owners.subList(0, 6),
                        3, false, true, null, null, true) != null
                && mixedRailGroups(gray, width, height, bar, notes, owners.subList(6, 14),
                        4, true, true, null, null, true) != null
                && mixedCut(gray, width, height, bar,
                        notes.get(owners.get(5)), notes.get(owners.get(6)), true, true);
    }

    private static List<PrintedBeamShafts.Shaft> fadedDownstaffAxes(byte[] ink, int width, int height,
            float x, float y, float gap, List<PrintedBeamShafts.Shaft> ordinary) {
        if (!ordinary.isEmpty() && (ordinary.size() != 1 || ordinary.get(0).direction() != 1
                || ordinary.get(0).endY() - y >= gap * 2.65f)) return ordinary;
        var faded = FadedTripletDownshaft.measure(ink, width, height, x, y, gap);
        return faded == null ? ordinary : List.of(faded);
    }

    static int unnumberedFadedDownstaffTriplets(byte[] raw, byte[] normalized, int width, int height,
            List<MeasureRegion> bars, List<ScoreNoteEvent> notes, int measure, List<Integer> owners) {
        var bar = bars.get(measure);
        var rows = UnnumberedTripletInk.sourceRows(bar, bars, height);
        float offset = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        int clear = 3;
        for (var ink : List.of(raw, normalized)) {
            var staff = WrappedMeasureBoundaries.closedTripletStaff(ink, width, height, bar);
            if (staff == null) return 0;
            for (int group = 0; group < 2; group++) {
                var heads = owners.subList(group * 3, group * 3 + 3).stream().map(notes::get).toList();
                var budget = new PrintedBeamShafts.Budget(100_000);
                var shafts = new ArrayList<PrintedBeamShafts.Shaft>(3);
                var x = new float[3];
                var y = new float[3];
                for (int i = 0; i < heads.size(); i++) {
                    var head = heads.get(i);
                    x[i] = offset + span * head.positionInMeasure();
                    y[i] = head.pageY() * height;
                    var ordinary = PrintedBeamShafts.at(ink, width, height, x[i], y[i], gap, budget);
                    var axis = fadedDownstaffAxes(ink, width, height, x[i], y[i], gap, ordinary);
                    if (axis.size() != 1 || axis.get(0).direction() != 1 || budget.refused()) break;
                    shafts.add(axis.get(0));
                }
                if (shafts.size() != 3 || !openingTripletClearWithShafts(
                        ink, width, height, heads, rows, staff, List.of(), true, gap, shafts, x, y))
                    clear &= ~(1 << group);
            }
        }
        return clear;
    }

    static boolean provesClosedWideMixedRails(byte[] ink, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> owners) {
        int left = Math.round(bar.left() * width), right = Math.round(bar.right() * width);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        var natural = notes.get(owners.get(8));
        var flat = notes.get(owners.get(9));
        float space = (flat.positionInMeasure() - natural.positionInMeasure()) * (right - left);
        if (natural.writtenAccidental() != 0 || flat.writtenAccidental() != -1
                || space <= gap * 3.4f || space > gap * 4.45f) return false;
        var track = wideOpeningStaff(ink, width, height, bar);
        if (track == null) return false;
        return mixedRailGroups(ink, width, height, bar, notes, owners.subList(0, 6), 3, false, true, track) != null
                && mixedRailGroups(ink, width, height, bar, notes, owners.subList(6, 14), 4, true, true, track) != null
                && mixedCut(ink, width, height, bar, notes.get(owners.get(5)), notes.get(owners.get(6)));
    }

    private static StaffPitchTrack wideOpeningStaff(byte[] ink, int width, int height, MeasureRegion bar) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float floor = bar.bottom() * height - 2.1f * gap;
        int from = Math.round(bar.left() * width) + Math.round(gap * 1.6f);
        int to = Math.round(bar.right() * width) - Math.round(gap * .43f);
        if (to <= from) return null;
        var a = DottedDyadMeasureSeparator.localRails(ink, width, height, from, floor, gap);
        var b = DottedDyadMeasureSeparator.localRails(ink, width, height, to, floor, gap);
        if (a == null || b == null || Math.abs(a[0] - b[0]) > gap * .25f
                || Math.abs(a[1] - b[1]) > gap * .10f) return null;
        float slope = (b[0] - a[0]) / (to - from);
        return StaffPitchTrack.linear(width, a[0] - slope * from, (a[1] + b[1]) * .5f, slope);
    }

    /** Check engraving separately from the already verified triplet clock and two beam rails. */
    static int unnumberedWideMixedTriplets(byte[] raw, byte[] normalized, int width, int height,
            List<MeasureRegion> bars, List<ScoreNoteEvent> notes, int measure, List<Integer> owners) {
        return unnumberedMixedTriplets(raw, normalized, width, height, bars, notes, measure, owners, false);
    }

    static boolean ownsDetachedClosingQuartet(byte[] raw, byte[] normalized,
            int width, int height, MeasureRegion bar, ScoreNoteEvent head) {
        if (head.staffStep() <= 8 || head.stemDirection() != -1) return false;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float x = (bar.left() + (bar.right() - bar.left()) * head.positionInMeasure()) * width;
        float y = head.pageY() * height;
        var original = LongDetachedDownshaft.measure(raw, width, height, x, y, gap);
        return original != null && original.equals(
                LongDetachedDownshaft.measure(normalized, width, height, x, y, gap));
    }

    static boolean ownsInterruptedLedgerHandoff(byte[] raw, byte[] normalized,
            int width, int height, MeasureRegion bar, ScoreNoteEvent head) {
        if (head.staffStep() <= 8 || head.stemDirection() != -1) return false;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float x = (bar.left() + (bar.right() - bar.left()) * head.positionInMeasure()) * width;
        float y = head.pageY() * height;
        var original = LedgerInterruptedDownshaft.measure(raw, width, height, bar, x, y, gap);
        return original != null && original.equals(
                LedgerInterruptedDownshaft.measure(normalized, width, height, bar, x, y, gap));
    }

    static int unnumberedMixedTriplets(byte[] raw, byte[] normalized, int width, int height,
            List<MeasureRegion> bars, List<ScoreNoteEvent> notes, int measure, List<Integer> owners,
            boolean closedStaff) {
        return unnumberedMixedTriplets(raw, normalized, width, height, bars, notes, measure, owners,
                closedStaff, false);
    }

    static int unnumberedMixedTriplets(byte[] raw, byte[] normalized, int width, int height,
            List<MeasureRegion> bars, List<ScoreNoteEvent> notes, int measure, List<Integer> owners,
            boolean closedStaff, boolean interruptedHandoff) {
        var bar = bars.get(measure);
        var rows = UnnumberedTripletInk.sourceRows(bar, bars, height);
        var rawStaff = closedStaff ? WrappedMeasureBoundaries.closedTripletStaff(raw, width, height, bar)
                : wideOpeningStaff(raw, width, height, bar);
        var normalizedStaff = closedStaff ? WrappedMeasureBoundaries.closedTripletStaff(normalized, width, height, bar)
                : wideOpeningStaff(normalized, width, height, bar);
        if (rawStaff == null || normalizedStaff == null) return 0;
        int unnumbered = 0;
        for (int i = 0; i < 2; i++) {
            var group = owners.subList(3 * i, 3 * i + 3).stream().map(notes::get).toList();
            boolean interruptedEnd = interruptedHandoff && i == 1;
            if (wideTripletClear(raw, width, height, bar, group, rows, rawStaff, interruptedEnd)
                    && wideTripletClear(normalized, width, height, bar, group, rows, normalizedStaff, interruptedEnd))
                unnumbered |= 1 << i;
        }
        return unnumbered;
    }

    static int unnumberedRuledOpeningTriplets(byte[] raw, byte[] normalized, int width, int height,
            List<MeasureRegion> bars, List<ScoreNoteEvent> notes, int measure, List<Integer> owners) {
        var bar = bars.get(measure);
        var rows = UnnumberedTripletInk.sourceRows(bar, bars, height);
        var opening = owners.subList(0, 3).stream().map(notes::get).toList();
        var following = owners.subList(3, 6).stream().map(notes::get).toList();
        int clear = 3;
        for (var ink : List.of(raw, normalized)) {
            var staff = WrappedMeasureBoundaries.closedTripletStaff(ink, width, height, bar);
            if (staff == null) return 0;
            if (!ruledOpeningTripletClear(ink, width, height, bar, opening, rows, staff)) clear &= ~1;
            if (!wideTripletClear(ink, width, height, bar, following, rows, staff, false)) clear &= ~2;
        }
        return clear;
    }

    private static boolean ruledOpeningTripletClear(byte[] ink, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> heads, UnnumberedTripletInk.Rows rows, StaffPitchTrack staff) {
        float offset = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(3);
        var budget = new PrintedBeamShafts.Budget(100_000);
        float[] x = new float[3], y = new float[3];
        for (int i = 0; i < heads.size(); i++) {
            var head = heads.get(i);
            if (head.stemDirection() != -1) return false;
            x[i] = offset + span * head.positionInMeasure();
            y[i] = head.pageY() * height;
            PrintedBeamShafts.Shaft shaft;
            if (i == 0) {
                shaft = RuledOpeningDownshaft.measure(ink, width, height, bar, x[i], y[i], gap);
            } else {
                var axis = PrintedBeamShafts.at(ink, width, height, x[i], y[i], gap, budget);
                if (axis.size() != 1 || axis.get(0).direction() != 1 || budget.refused()) return false;
                shaft = i == 1 ? RuledOpeningDownshaft.continueNeighbor(
                        ink, width, height, bar, x[i], y[i], gap, axis.get(0)) : axis.get(0);
            }
            if (shaft == null) return false;
            shafts.add(shaft);
        }
        return openingTripletClearWithShafts(
                ink, width, height, heads, rows, staff, List.of(), true, gap, shafts, x, y);
    }

    private static boolean wideTripletClear(byte[] ink, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> heads, UnnumberedTripletInk.Rows rows, StaffPitchTrack staff,
            boolean interruptedEnd) {
        if (openingTripletClear(ink, width, height, bar, heads, rows, staff, List.of(), true, interruptedEnd))
            return true;
        var last = heads.get(heads.size() - 1);
        if (last.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT) return false;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float x = (bar.left() + (bar.right() - bar.left()) * last.positionInMeasure()) * width;
        float y = last.pageY() * height;
        int left = Math.round(x - 3.2f * gap), right = Math.round(x - .75f * gap);
        int top = Math.max(rows.first(), Math.round(y - 3.1f * gap));
        int bottom = Math.round(y - .22f * gap);
        if (left < 0 || right >= width || top >= bottom || bottom >= height) return false;
        var window = RawNumeralComponents.captureLongWindow(ink, width, height, left, top, right, bottom);
        var clipped = window.components().stream().filter(c -> c.bottom == bottom
                        && c.left > left && c.right < right && c.top > top
                        && c.right - c.left + 1 >= gap * .7f && c.right - c.left + 1 <= gap * 1.12f
                        && c.bottom - c.top + 1 >= gap * 1.04f && c.bottom - c.top + 1 <= gap * 1.8f)
                .sorted(Comparator.comparingInt(c -> c.left)).toList();
        if (clipped.size() != 2) return false;
        var first = clipped.get(0);
        var second = clipped.get(clipped.size() - 1);
        float separation = second.left - first.left;
        int pad = Math.round(gap * .13f);
        int fullBottom = Math.round(y + gap * .72f);
        if (separation < .95f * gap || separation > 1.25f * gap
                || x - second.left < 1.65f * gap || x - second.left > 2.1f * gap
                || first.right >= second.left || Math.abs(first.top - second.top) > .25f * gap
                || !PrintedFlatGlyph.matches(ink, width, height,
                        first.left, first.top, first.right, fullBottom, gap)
                || !PrintedFlatGlyph.matches(ink, width, height,
                        second.left - pad, second.top, second.right, fullBottom, gap)) return false;
        // Ignore only the two proven flat components in the upper numeral band.
        byte[] numeralInk = ink.clone();
        for (var flat : clipped)
            for (int i = 0; i < flat.size(); i++) numeralInk[flat.pixel(i)] = (byte) 255;
        return openingTripletClear(numeralInk, width, height, bar, heads, rows, staff, List.of(), true, interruptedEnd);
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
        return mixedRailGroups(gray, width, height, bar, notes, candidates, groupSize, doubleRail, systemOpening, null);
    }

    private static WrappedTripletMeasure.Half mixedRailGroups(
            byte[] gray, int width, int height, MeasureRegion bar, List<ScoreNoteEvent> notes,
            List<Integer> candidates, int groupSize, boolean doubleRail, boolean systemOpening, StaffPitchTrack wideStaff) {
        return mixedRailGroups(gray, width, height, bar, notes, candidates, groupSize,
                doubleRail, systemOpening, wideStaff, null);
    }

    private static WrappedTripletMeasure.Half mixedRailGroups(
            byte[] gray, int width, int height, MeasureRegion bar, List<ScoreNoteEvent> notes,
            List<Integer> candidates, int groupSize, boolean doubleRail, boolean systemOpening,
            StaffPitchTrack wideStaff, StaffPitchTrack upstaff) {
        return mixedRailGroups(gray, width, height, bar, notes, candidates, groupSize,
                doubleRail, systemOpening, wideStaff, upstaff, false);
    }

    private static WrappedTripletMeasure.Half mixedRailGroups(
            byte[] gray, int width, int height, MeasureRegion bar, List<ScoreNoteEvent> notes,
            List<Integer> candidates, int groupSize, boolean doubleRail, boolean systemOpening,
            StaffPitchTrack wideStaff, StaffPitchTrack upstaff, boolean fadedDownstaff) {
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
        boolean recoveredRuledOpening = false;
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
            float maximumSpan = gap * (owners.size() == 6 ? 4f : wideStaff != null && i == 3 ? 4.45f : 3.4f);
            if (i > 0 && (x[i] - x[i - 1] < gap * 1.65f
                    || x[i] - x[i - 1] > maximumSpan
                            && !mixedAccidentalPixelSpan(gray, width, height, x[i], y[i], gap,
                                    x[i] - x[i - 1], maximumSpan, n, systemOpening && doubleRail && i % groupSize != 0)))
                return null;
            var source = upstaff == null ? PrintedBeamShafts.at(gray, width, height, x[i], y[i], gap, budget)
                    : upstaffAxes(gray, width, height, x[i], y[i], gap, upstaff, budget);
            // A tight upper-ledger double beam can leave the downshaft just inside
            // the nominal wedge. Both neighboring rails still have to meet that shaft.
            if (source.isEmpty() && systemOpening && doubleRail && n.staffStep() > 8
                    && n.stemDirection() == -1 && i % groupSize > 0 && i % groupSize < groupSize - 1)
                source = PrintedBeamShafts.at(gray, width, height, x[i] + .16f * gap, y[i], gap, budget);
            if (source.isEmpty() && systemOpening && !doubleRail && owners.size() == 6
                    && i == 0 && n.stemDirection() == -1) {
                var ruled = RuledOpeningDownshaft.measure(gray, width, height, bar, x[i], y[i], gap);
                if (ruled != null) {
                    source = List.of(ruled);
                    recoveredRuledOpening = true;
                }
            }
            if (recoveredRuledOpening && i == 1 && source.size() == 1 && n.stemDirection() == -1) {
                var continued = RuledOpeningDownshaft.continueNeighbor(
                        gray, width, height, bar, x[i], y[i], gap, source.get(0));
                if (continued != null) source = List.of(continued);
            }
            if (source.isEmpty() && systemOpening && !doubleRail && owners.size() == 6
                    && !recoveredInnerShaft && i % groupSize == 1 && n.stemDirection() == -1) {
                var split = SplitOpeningDownshaft.measure(gray, width, height, x[i], y[i], gap);
                if (split != null) {
                    source = List.of(split);
                    recoveredInnerShaft = true;
                }
            }
            if (source.isEmpty() && systemOpening && doubleRail && owners.size() == 8
                    && !recoveredInnerShaft && i % groupSize > 0
                    && (i % groupSize < groupSize - 1 || i == groupSize - 1 && n.stemDirection() == -1)) {
                // A staff crossing can split the contrast alongside an interior stem.
                // A beam group endpoint also has to prove a cut to the next group.
                // The rail checks below bind this shaft to its group.
                var contrasted = ClosedOctaveTripletBars.contrastShaft(gray, width, height,
                        x[i], y[i], gap, budget);
                if (contrasted.size() == 1 && contrasted.get(0).right() > contrasted.get(0).left()) {
                    source = contrasted;
                    recoveredInnerShaft = true;
                }
            }
            if (source.isEmpty() && systemOpening && doubleRail && owners.size() == 8
                    && i == owners.size() - 1 && n.stemDirection() == -1) {
                var stem = DetachedClosingDownshaft.measure(gray, width, height, x[i], y[i], gap);
                if (stem != null) source = List.of(stem);
            }
            if (source.isEmpty() && systemOpening && !doubleRail && owners.size() == 6
                    && i == owners.size() - 1 && n.staffStep() > 8 && n.stemDirection() == -1) {
                var shaft = LedgerInterruptedDownshaft.measure(gray, width, height, bar, x[i], y[i], gap);
                if (shaft != null) source = List.of(shaft);
            }
            if (source.isEmpty() && systemOpening && doubleRail && owners.size() == 8
                    && i == groupSize && n.staffStep() > 8 && n.stemDirection() == -1) {
                var shaft = LongDetachedDownshaft.measure(gray, width, height, x[i], y[i], gap);
                if (shaft != null) source = List.of(shaft);
            }
            if (fadedDownstaff)
                source = fadedDownstaffAxes(gray, width, height, x[i], y[i], gap, source);
            if (source.size() != 1 || source.get(0).direction() != -n.stemDirection()) return null;
            shafts.add(source.get(0));
        }
        for (int i = 0; i + 1 < x.length; i++) {
            var dot = WrappedTripletDotInk.uniqueCore(gray, width, height, x[i], y[i], x[i + 1], gap);
            if (dot != null) {
                int step = notes.get(owners.get(i)).staffStep();
                if (!systemOpening || step >= 0 && step <= 8
                        || !headConnectedLedger(gray, width, height, x[i], y[i], dot, gap,
                                systemOpening ? Math.round(gap * (wideStaff == null ? .35f : .48f)) / gap
                                        : wideStaff == null ? .35f : .48f)) {
                    var nextNote = notes.get(owners.get(i+1));
                    boolean wideAccidental = wideStaff != null && doubleRail
                            && wideMixedAccidentalOwnsCore(gray, width, height, x[i+1], y[i+1], gap, nextNote, dot);
                    boolean naturalCrossbar = systemOpening && doubleRail && nextNote.writtenAccidental() == 0
                            && NaturalCrossbarDotOwnership.owns(gray, width, x[i], x[i+1], y[i+1], gap, dot);
                    boolean sharpSide = fadedDownstaff && doubleRail
                            && nextNote.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_SHARP
                            && ownsSharpSideCore(gray, width, x[i], x[i+1], y[i+1], gap, dot);
                    if (!wideAccidental && !naturalCrossbar && !sharpSide) return null;
                }
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
            if (!matchesRailCount(gray, width, height, a, b, gap, doubleRail)
                    && !(wideStaff != null && !doubleRail
                            && mixedSingleRailOverStaff(gray, width, height, a, b, gap, wideStaff))) return null;
        }
        return new WrappedTripletMeasure.Half(owners, x);
    }

    private static boolean mixedAccidentalPixelSpan(byte[] ink, int width, int height,
            float x, float y, float gap, float distance, float limit, ScoreNoteEvent note, boolean eligible) {
        return eligible && distance <= limit + .5f && note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_SHARP
                && OmrScoreInterpreter.provesBoundedSharpInk(ink, width, height, x, y, gap,
                        Math.round(x - 2 * gap), Math.round(x - .65f * gap));
    }

    private static boolean ownsSharpSideCore(byte[] ink, int width, float priorX, float ownerX,
            float ownerY, float gap, WrappedTripletDotInk.Center dot) {
        if (dot.x() - priorX < .85f * gap || ownerX - dot.x() < 1.45f * gap
                || ownerX - dot.x() > 2.1f * gap || dot.y() - ownerY < .60f * gap
                || dot.y() - ownerY > 1.08f * gap
                || dot.x() < 2 || ownerX >= width - 2 || ownerY - 1.2f * gap < 0
                || ownerY + 1.2f * gap >= ink.length / width) return false;
        int top = Math.round(ownerY - .95f * gap), bottom = Math.round(ownerY + 1.12f * gap);
        for (int left = dot.x() + 1; left <= dot.x() + Math.round(.25f * gap); left++) {
            if (!darkSharpRow(ink, width, dot.y(), dot.x(), left)) break;
            for (int right = left + Math.round(.42f * gap); right <= left + Math.round(.66f * gap); right++) {
                if (ownerX - right < gap * .95f || ownerX - right > gap * 1.35f) continue;
                int shared = 0, upper = 0, lower = 0, opening = 0;
                for (int row = top; row <= bottom; row++) {
                    if (!darkSharpRow(ink, width, row, left, left)
                            || !darkSharpRow(ink, width, row, right, right)) continue;
                    shared++;
                    var dy = row - ownerY;
                    if (dy < -.25f * gap && darkSharpRow(ink, width, row, dot.x(), right)) upper++;
                    if (dy > .22f * gap && dy < .81f * gap
                            && darkSharpRow(ink, width, row, dot.x(), right)) lower++;
                    if (dy >= -.25f * gap && dy <= .22f * gap
                            && (ink[row * width + (left + right) / 2] & 255) > 170) opening++;
                }
                if (shared >= gap * 1.72f && upper >= 3 && lower >= 3 && opening >= 4) return true;
            }
        }
        return false;
    }

    private static boolean darkSharpRow(byte[] ink, int width, int y, int left, int right) {
        for (int x = left; x <= right; x++) if ((ink[y * width + x] & 255) >= 145) return false;
        return true;
    }

    private static boolean wideMixedAccidentalOwnsCore(byte[] gray, int width, int height, float x, float y,
            float gap, ScoreNoteEvent note, WrappedTripletDotInk.Center dot) {
        if (note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FLAT)
            return WrappedTripletDotInk.singleFlatOwns(gray, width, height, dot, x, y, gap);
        if (note.writtenAccidental() != 0 || x - dot.x() < .7f * gap || x - dot.x() > 2.2f * gap)
            return false;
        return OmrScoreInterpreter.provesNaturalMeterInk(gray, width, height,
                Math.round(x - 2.5f * gap), Math.round(x - .58f * gap), y, gap, height);
    }

    private static boolean mixedSingleRailOverStaff(byte[] ink, int width, int height,
            PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, float gap, StaffPitchTrack staff) {
        int left = (a.left() + a.right()) / 2, right = (b.left() + b.right()) / 2;
        if (right - left < gap * 1.65f) return false;
        var middle = staff.at((left + right) * .5f);
        float tip = (a.endY() + b.endY()) * .5f;
        int rule = Math.round((middle[0] - tip + 1.12f * gap) / middle[1]);
        if (rule < 0 || rule >= 5) return false;
        int[] rows = new int[right - left + 1];
        int margin = Math.round(gap * .40f);
        int band = Math.round(gap * .27f);
        int good = 0;
        for (int x = left; x <= right; x++) {
            var frame = staff.at(x);
            int y = Math.round(frame[0] - rule * frame[1]);
            rows[x - left] = y;
            if (x < left + margin || x > right - margin) continue;
            int count = ruleThickness(ink, width, height, x, y, band);
            if (count >= 1 && count <= gap * .27f) good++;
        }
        return good >= (right - left + 1 - 2 * margin) * .90f
                && matchesRailCount(ink, width, height, a, b, gap, false, new RuleBand(left, rows, band));
    }

    static boolean cutBetweenRecoveredGroups(
            byte[] gray, int width, int height, MeasureRegion bar,
            PrintedBeamShafts.Shaft left, PrintedBeamShafts.Shaft right, float gap) {
        var ribbon = bestRibbon(gray, width, height, left, right, gap);
        return ribbon.coverage() <= .46f
                || staffRuleOnlyCut(gray, width, height, bar, left, right, gap, ribbon, true)
                || ribbon.coverage() < .75f
                        && thinStaffRuleBetweenRails(gray, width, height, bar, left, right, gap, ribbon);
    }

    /** A staff crossing can imitate part of a beam without joining the two shafts. */
    /** A staff rule running through the endpoints of two groups is thinner than both beams. */
    static boolean thinStaffRuleBetweenRails(
            byte[] gray, int width, int height, MeasureRegion bar,
            PrintedBeamShafts.Shaft left, PrintedBeamShafts.Shaft right, float gap, Ribbon ribbon) {
        if (ribbon.coverage() <= .46f) return false;
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
        if (ribbon.coverage() < .75f && !alignedStaffRuleGap(track, left, right, gap, ribbon, rule))
            return false;
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

    /** Nearby up-stem beam tips end above a rule which alone crosses their open handoff. */
    private static boolean alignedStaffRuleGap(
            StaffPitchTrack track, PrintedBeamShafts.Shaft left, PrintedBeamShafts.Shaft right,
            float gap, Ribbon ribbon, int rule) {
        if (left.direction() != -1 || right.direction() != -1) return false;
        int from = (left.left() + left.right()) / 2, to = (right.left() + right.right()) / 2;
        if (to - from < gap * 2.5f || to - from > gap * 4) return false;
        var before = track.at(from);
        var after = track.at(to);
        float startRule = before[0] - rule * before[1], endRule = after[0] - rule * after[1];
        float start = ribbon.leftY() - startRule, end = ribbon.rightY() - endRule;
        float near = Math.min(Math.abs(start), Math.abs(end));
        float far = Math.max(Math.abs(start), Math.abs(end));
        if (near > gap * .12f || far < gap * .15f || far > gap * .36f
                || Math.max(start, end) > gap * .12f || Math.min(start, end) > -gap * .15f)
            return false;
        float firstTip = left.endY() - startRule, lastTip = right.endY() - endRule;
        return firstTip < -gap * .08f && lastTip < -gap * .08f
                && Math.min(firstTip, lastTip) <= -gap * .30f
                && Math.min(firstTip, lastTip) >= -gap * .55f
                && Math.max(firstTip, lastTip) >= -gap * .24f;
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

    /** Two nearby rail tips can see the same staff line across a short, steep gap. */
    /** A narrow cutoff can cross only staff ink when its two shaft tips straddle a rule. */
    private static boolean closedTripletStaffCut(byte[] ink, int width, int height, MeasureRegion bar,
            PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, float gap) {
        if (a.direction() != 1 || b.direction() != 1) return false;
        int left = (a.left() + a.right()) / 2, right = (b.left() + b.right()) / 2;
        if (right - left < gap * 1.68f || right - left > gap * 2.25f) return false;
        var staff = WrappedMeasureBoundaries.closedTripletStaff(ink, width, height, bar);
        if (staff == null || !staff.verifiedAt(left) || !staff.verifiedAt(right)) return false;
        var centre = staff.at((left + right) * .5f);
        int rule = Math.round((centre[0] - (a.endY() + b.endY()) * .5f) / centre[1]);
        if (rule < 0 || rule > 4) return false;
        var l = staff.at(left);
        var r = staff.at(right);
        float la = a.endY() - (l[0] - rule * l[1]), rb = b.endY() - (r[0] - rule * r[1]);
        if (la * rb >= 0 || Math.min(Math.abs(la), Math.abs(rb)) < .27f * gap
                || Math.max(Math.abs(la), Math.abs(rb)) > .65f * gap) return false;
        int from = left + Math.round(gap * .55f), to = right - Math.round(gap * .43f);
        int top = Math.round(Math.min(a.endY(), b.endY()) - gap * .60f);
        int bottom = Math.round(Math.max(a.endY(), b.endY()) + gap * .27f);
        if (from < 0 || to >= width || top < 0 || bottom >= height) return false;
        for (int x = from; x <= to; x++) {
            var frame = staff.at(x);
            int contiguous = 0;
            for (int y = top; y <= bottom; y++) {
                if ((ink[y * width + x] & 255) >= 155) {
                    contiguous = 0;
                    continue;
                }
                float line = (frame[0] - y) / frame[1];
                int nearest = Math.round(line);
                if (nearest < 0 || nearest > 4 || Math.abs(line - nearest) > .21f
                        || ++contiguous > Math.round(gap * .31f)) return false;
            }
        }
        return true;
    }

    private static boolean shortStaffRuleOnlyCut(byte[] gray, int width, int height, MeasureRegion bar,
            PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, float gap, Ribbon ribbon) {
        if (a.direction() != 1 || b.direction() != 1 || ribbon.coverage() < .75f) return false;
        int from = (a.left() + a.right()) / 2, to = (b.left() + b.right()) / 2;
        int inset = Math.round(gap * .45f), first = from + inset, last = to - inset;
        if (last - first < gap * .65f || to - from > gap * 2.5f) return false;
        var staff = WrappedMeasureBoundaries.verifiedTripletStaff(gray, width, height, bar);
        if (staff == null || !staff.verifiedAt(from) || !staff.verifiedAt(to)) return false;
        var left = staff.at(from);
        var right = staff.at(to);
        int rule = Math.round((left[0] - ribbon.leftY()) / left[1]);
        if (rule < 0 || rule > 4
                || rule != Math.round((right[0] - ribbon.rightY()) / right[1])
                || Math.abs(left[0] - rule * left[1] - ribbon.leftY()) > gap * .53f
                || Math.abs(right[0] - rule * right[1] - ribbon.rightY()) > gap * .53f) return false;

        int radius = Math.round(gap * .29f), maximumInk = Math.round(gap * .31f);
        int[] rows = new int[last - first + 1];
        int clean = 0;
        for (int x = first; x <= last; x++) {
            var at = staff.at(x);
            int row = Math.round(at[0] - rule * at[1]);
            int ink = ruleThickness(gray, width, height, x, row, radius);
            if (ink > 0 && ink <= maximumInk) {
                rows[x - first] = row;
                clean++;
            } else {
                rows[x - first] = -height;
            }
        }
        return clean >= rows.length * .90f
                && bestRibbon(gray, width, height, a, b, gap,
                        new RuleBand(first, rows, radius)).coverage() <= .46f;
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
            ExcludedInk excluded) {
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
            ExcludedInk excluded) {
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
