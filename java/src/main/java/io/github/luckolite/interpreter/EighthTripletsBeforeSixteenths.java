// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

/** A closed common-time bar can continue an unnumbered eighth-triplet run into sixteenths. */
final class EighthTripletsBeforeSixteenths {
    private EighthTripletsBeforeSixteenths() {}

    private record BeamRun(List<Integer> owners, List<Integer> originalOwners) {
        BeamRun(List<Integer> owners) {
            this(owners, owners);
        }
    }

    record Capture(ScorePageInterpretation score, List<List<BeamRun>> transitions, List<BeamRun> straightBars) {
        Capture(ScorePageInterpretation score, List<List<BeamRun>> transitions) {
            this(score, transitions, List.of());
        }

        ScorePageInterpretation projectBeams(ScorePageInterpretation retained, int page) {
            if (transitions.isEmpty() && straightBars.isEmpty()
                    || page < 0
                    || !score.measures().equals(retained.measures())
                    || score.notes().size() != retained.notes().size()) return retained;
            var source = retained.beamSource();
            var origins = source.noteOrigins();
            var originCount = new HashMap<ScoreBeamSource.Anchor, Integer>();
            for (var origin : origins) origin.ifPresent(a -> originCount.merge(a, 1, Integer::sum));
            var claims = new HashSet<ScoreBeamSource.Anchor>();
            var ids = new HashSet<String>();
            for (var group : source.groups()) {
                ids.add(group.id());
                for (var column : group.columns()) claims.addAll(column.members());
            }
            for (var detached : source.detachedColumns()) claims.addAll(detached.members());
            var groups = new ArrayList<>(source.groups());
            for (var transition : transitions) {
                var additions = new ArrayList<ScoreBeamSource.Group>(transition.size());
                for (var run : transition) {
                    var columns = new ArrayList<ScoreBeamSource.Column>(run.owners().size());
                    for (int i = 0; i < run.owners().size(); i++) {
                        int owner = run.owners().get(i);
                        var origin = origins.get(owner).orElse(null);
                        if (origin == null
                                || origin.pageIndex() != page
                                || origin.noteIndex() != run.originalOwners().get(i)
                                || originCount.getOrDefault(origin, 0) != 1
                                || claims.contains(origin)
                                || !score.notes().get(owner).equals(retained.notes().get(owner)))
                            break;
                        columns.add(new ScoreBeamSource.Column(List.of(origin)));
                    }
                    if (columns.size() != run.owners().size()) break;
                    String id =
                            "printed-beam:"
                                    + page
                                    + ":"
                                    + columns.get(0).members().get(0).noteIndex();
                    if (ids.contains(id)) break;
                    additions.add(
                            new ScoreBeamSource.Group(
                                    id,
                                    columns,
                                    Collections.nCopies(columns.size() - 1, 3),
                                    List.of()));
                }
                if (additions.size() == transition.size()) {
                    groups.addAll(additions);
                    for (var group : additions) {
                        ids.add(group.id());
                        for (var column : group.columns()) claims.addAll(column.members());
                    }
                }
            }
            var projected = groups.size() == source.groups().size()
                    ? source
                    : new ScoreBeamSource(groups, source.detachedColumns(), origins);
            for (var bar : straightBars)
                projected = projectStraightBar(retained, page, projected, bar);
            return projected.equals(source) ? retained : retained.withBeamSource(projected);
        }

        private ScoreBeamSource projectStraightBar(
                ScorePageInterpretation retained, int page, ScoreBeamSource source, BeamRun bar) {
            var heads = new ArrayList<ClosedSixteenthDyads.Head>(bar.owners().size());
            for (int i = 0; i < bar.owners().size(); i++) {
                int owner = bar.owners().get(i);
                var origin = source.noteOrigins().get(owner).orElse(null);
                var note = retained.notes().get(owner);
                if (origin == null || origin.pageIndex() != page || origin.noteIndex() != bar.originalOwners().get(i)
                        || !note.equals(score.notes().get(owner))) return source;
                heads.add(new ClosedSixteenthDyads.Head(owner, note, note.positionInMeasure(), note.pageY()));
            }
            return ClosedSixteenthDyads.replaceMonophonicDoubleRails(source, page, heads);
        }
    }

    static Capture capture(ScorePageInterpretation score, byte[] source, int width, int height) {
        return capture(score, source, null, width, height);
    }

    static Capture capture(
            ScorePageInterpretation score, byte[] source, byte[] normalized, int width, int height) {
        if (width < 1
                || height < 1
                || source == null
                || source.length != (long) width * height
                || score.measures().size() < 2
                || score.meterChanges().isEmpty()) return new Capture(score, List.of());
        var notes = new ArrayList<>(score.notes());
        var bars = score.measures();
        var owners = new ArrayList<List<Integer>>(bars.size());
        for (int i = 0; i < bars.size(); i++) owners.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) {
            int measure = notes.get(i).measureIndex();
            if (measure >= 0 && measure < bars.size()) owners.get(measure).add(i);
        }
        int[] numerators = new int[bars.size()], denominators = new int[bars.size()];
        for (var m : score.meterChanges())
            if (m.measureIndex() >= 0 && m.measureIndex() < bars.size()) {
                numerators[m.measureIndex()] = m.numerator();
                denominators[m.measureIndex()] = m.denominator();
            }
        int numerator = 0, denominator = 0;
        boolean previousCommon = false;
        var transitions = new ArrayList<List<BeamRun>>();
        var straightBars = new ArrayList<BeamRun>();
        boolean timingChanged = false;
        var beamAliases = new HashSet<Integer>();
        for (int i = 0; i < bars.size(); i++) {
            if (denominators[i] != 0) {
                numerator = numerators[i];
                denominator = denominators[i];
            }
            boolean common = numerator == 4 && denominator == 4;
            boolean sameMeter = i > 0 && common && previousCommon;
            boolean transition = sameMeter && ownsClosedTransition(
                    score, notes, owners.get(i - 1), owners.get(i), i, source, width, height);
            boolean upperLedger = sameMeter && !transition && ownsUpperLedgerTransition(
                    score, notes, owners.get(i - 1), owners.get(i), i, source, normalized, width, height);
            if (transition || sameMeter && !upperLedger && ownsClosedSystemOpening(
                    score, notes, owners.get(i), i, source, normalized, width, height)) {
                timingChanged = true;
                var ordered = owners.get(i);
                for (int index : ordered.subList(0, 6))
                    notes.set(index, notes.get(index).withTupletRatio(3, 2));
                // The single rails establish timing, but do not prove a printed tuplet mark.
                transitions.add(
                        List.of(
                                new BeamRun(List.copyOf(ordered.subList(6, 10))),
                                new BeamRun(List.copyOf(ordered.subList(10, 14)))));
            } else if (upperLedger) {
                timingChanged = true;
                for (int index : owners.get(i - 1))
                    notes.set(index, notes.get(index).withTupletRatio(3, 2));
                for (int index : owners.get(i).subList(0, 6))
                    notes.set(index, notes.get(index).withTupletRatio(3, 2));
            } else if (i > 0 && common
                    && normalizeStraightOpening(score, notes, owners.get(i), i, source, normalized, width, height)) {
                timingChanged = true;
            } else if (i > 0 && common
                    && normalizePairedFingerings(score, notes, owners.get(i), i, source, normalized, width, height)) {
                timingChanged = true;
                straightBars.add(new BeamRun(List.copyOf(owners.get(i))));
            } else if (sameMeter
                    && ownsBeamAliasInTransition(score, notes, owners.get(i - 1), owners.get(i),
                            i, source, normalized, width, height)) {
                timingChanged = true;
                var current = owners.get(i);
                beamAliases.add(current.get(2));
                for (int j : new int[] {0, 1, 3, 4, 5, 6}) {
                    int owner = current.get(j);
                    notes.set(owner, notes.get(owner).withTupletRatio(3, 2));
                }
            }
            previousCommon = common;
        }
        if (!timingChanged) return new Capture(score, List.of());
        if (!beamAliases.isEmpty()) {
            // Existing score provenance is reindexed through the retained ownership map.
            var retained = IntStream.range(0, notes.size()).filter(i -> !beamAliases.contains(i)).boxed().toList();
            return new Capture(score.withNotes(retained.stream().map(notes::get).toList(), retained),
                    reindex(transitions, retained, notes.size()),
                    reindex(List.of(straightBars), retained, notes.size()).get(0));
        }
        return new Capture(
                score.withNotes(notes, IntStream.range(0, notes.size()).boxed().toList())
                        .withClockContextSource(score.clockContextSource()),
                List.copyOf(transitions), List.copyOf(straightBars));
    }

    private static boolean ownsClosedTransition(
            ScorePageInterpretation score,
            List<ScoreNoteEvent> notes,
            List<Integer> previous,
            List<Integer> owners,
            int bar,
            byte[] source,
            int width,
            int height) {
        var region = score.measures().get(bar);
        var before = score.measures().get(bar - 1);
        if (previous.size() != 12
                || owners.size() != 14
                || region.cadenceQuarterBeats() != 0
                || before.cadenceQuarterBeats() != 0
                || score.rests().stream()
                        .anyMatch(r -> r.measureIndex() == bar || r.measureIndex() == bar - 1)
                || score.multiRestGroups().stream()
                        .anyMatch(
                                g ->
                                        g.firstMeasureIndex() <= bar
                                                && g.firstMeasureIndex() + g.measureCount()
                                                        > bar - 1)) return false;
        Comparator<Integer> engraved =
                Comparator.comparingDouble(i -> notes.get(i).positionInMeasure());
        previous.sort(engraved);
        owners.sort(engraved);
        int clef = notes.get(previous.get(0)).clefBottomDiatonic();
        for (int j = 0; j < previous.size(); j++) {
            var n = notes.get(previous.get(j));
            if (!ordinary(n, 1, clef)
                    && !(n.tupletDivisor() == 3 && n.tupletNormalNotes() == 2
                            && ordinary(n.withTupletRatio(1, 1), 1, clef))) return false;
            if (j > 0
                    && n.positionInMeasure() - notes.get(previous.get(j - 1)).positionInMeasure()
                            <= .018f) return false;
        }
        if (notes.get(previous.get(0)).positionInMeasure() > .18f
                || notes.get(previous.get(11)).positionInMeasure() < .8f
                || notes.get(owners.get(0)).positionInMeasure() > .18f
                || notes.get(owners.get(13)).positionInMeasure() < .86f) return false;
        for (int j = 0; j < owners.size(); j++)
            if (!ordinary(notes.get(owners.get(j)), j < 6 ? 1 : 2, clef)) return false;

        var opening = owners.subList(0, 6);
        var ending = owners.subList(6, 14);
        return WrappedEighthTriplets.mixedOpeningGroups(
                                source, width, height, before, notes, previous.subList(0, 6))
                        != null
                && WrappedEighthTriplets.mixedOpeningGroups(
                                source, width, height, region, notes, opening)
                        != null
                && WrappedEighthTriplets.doubleRailGroups(
                                source, width, height, region, notes, ending)
                        != null
                && WrappedEighthTriplets.mixedCut(
                        source,
                        width,
                        height,
                        region,
                        notes.get(owners.get(5)),
                        notes.get(owners.get(6)));
    }

    private static List<List<BeamRun>> reindex(
            List<List<BeamRun>> transitions, List<Integer> retained, int oldSize) {
        if (transitions.isEmpty()) return transitions;
        int[] newIndices = new int[oldSize];
        for (int i = 0; i < retained.size(); i++) newIndices[retained.get(i)] = i;
        return transitions.stream().map(transition -> transition.stream().map(run ->
                new BeamRun(run.owners().stream().map(i -> newIndices[i]).toList(), run.originalOwners()))
                .toList()).toList();
    }

    private static boolean ownsBeamAliasInTransition(
            ScorePageInterpretation score, List<ScoreNoteEvent> notes, List<Integer> previous,
            List<Integer> owners, int measure, byte[] raw, byte[] normalized, int width, int height) {
        var bars = score.measures();
        if (owners.size() != 15 || previous.size() != 12
                || !ownsClosedSystemExtent(score, notes, owners, measure, raw, normalized, width, height)
                || bars.get(measure - 1).cadenceQuarterBeats() != 0
                || score.rests().stream().anyMatch(r -> r.measureIndex() == measure - 1)
                || score.multiRestGroups().stream().anyMatch(r -> r.firstMeasureIndex() <= measure - 1
                        && r.firstMeasureIndex() + r.measureCount() > measure - 1)) return false;
        var before = bars.get(measure - 1);
        var bar = bars.get(measure);
        previous.sort(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure()));
        int clef = notes.get(previous.get(0)).clefBottomDiatonic();
        var firstPrior = notes.get(previous.get(0));
        int knownDivisor = firstPrior.tupletDivisor(), knownNormal = firstPrior.tupletNormalNotes();
        if (knownDivisor != 1 && knownDivisor != 3
                || knownNormal != (knownDivisor == 1 ? 1 : 2)) return false;
        for (int i : previous) {
            var note = notes.get(i);
            if (note.tupletDivisor() != knownDivisor || note.tupletNormalNotes() != knownNormal
                    || !ordinary(note.withTupletRatio(1, 1), 1, clef)) return false;
        }
        var alias = notes.get(owners.get(2));
        if (alias.stemDirection() != 0 || alias.articulations() != 0
                || alias.kind() != ScoreNoteEvent.Kind.PITCHED || alias.staffIndex() != 0
                || alias.staffCount() != 1 || alias.visualSize() != ScoreNoteEvent.VisualSize.FULL
                || alias.beamCount() != 1 || alias.augmentationDots() != 0
                || alias.unbeamedDurationBeats() != 0 || alias.tupletDivisor() != 1
                || alias.tiedFromPrevious() || alias.boundaryTies() != 0 || alias.crossStaffBeam()
                || alias.leadingRestBeats() != 0 || alias.followingRestBeats() != 0
                || alias.harmonicSemitones() != 0 || alias.openStringTouch() != null
                || alias.clefBottomDiatonic() != clef) return false;
        for (int i = 0; i < owners.size(); i++)
            if (i != 2 && !ordinary(notes.get(owners.get(i)), i < 7 ? 1 : 2, clef)) return false;
        var first = notes.get(owners.get(0));
        var middle = notes.get(owners.get(1));
        var endOfFirst = notes.get(owners.get(3));
        for (var source : List.of(raw, normalized)) {
            if (WrappedEighthTriplets.mixedOpeningGroups(source, width, height, before,
                            notes, previous.subList(0, 6)) == null
                    || !WrappedEighthTriplets.slopedTripletOwnsBeamAlias(
                            source, width, height, bar, first, middle, endOfFirst, alias)
                    || WrappedEighthTriplets.mixedOpeningGroups(source, width, height, bar,
                            notes, owners.subList(4, 7)) == null
                    || WrappedEighthTriplets.doubleRailGroups(source, width, height, bar,
                            notes, owners.subList(7, 15)) == null
                    || !WrappedEighthTriplets.mixedCut(source, width, height, bar,
                            endOfFirst, notes.get(owners.get(4)))
                    || !WrappedEighthTriplets.mixedCut(source, width, height, bar,
                            notes.get(owners.get(6)), notes.get(owners.get(7)))) return false;
        }
        return true;
    }

    private static boolean ownsUpperLedgerTransition(
            ScorePageInterpretation score, List<ScoreNoteEvent> notes, List<Integer> previous,
            List<Integer> owners, int measure, byte[] original, byte[] normalized,
            int width, int height) {
        var bars = score.measures();
        if (normalized == null || normalized.length != original.length
                || previous.size() != 12 || owners.size() != 14 || measure + 1 >= bars.size()
                || bars.get(measure - 1).cadenceQuarterBeats() != 0
                || bars.get(measure).cadenceQuarterBeats() != 0
                || score.rests().stream().anyMatch(r -> r.measureIndex() == measure - 1
                        || r.measureIndex() == measure)
                || score.multiRestGroups().stream().anyMatch(r -> r.firstMeasureIndex() <= measure
                        && r.firstMeasureIndex() + r.measureCount() > measure - 1)) return false;
        int clef = notes.get(previous.get(0)).clefBottomDiatonic();
        if (previous.stream().anyMatch(i -> !ordinary(notes.get(i), 1, clef))) return false;
        Comparator<Integer> position = Comparator.comparingDouble(i -> notes.get(i).positionInMeasure());
        previous.sort(position);
        owners.sort(position);
        for (int i = 0; i < owners.size(); i++)
            if (!ordinary(notes.get(owners.get(i)), i < 6 ? 1 : 2, clef)) return false;
        return WrappedEighthTriplets.provesUpperLedgerRailSequence(
                        original, width, height, bars, measure - 1, notes, previous, false)
                && WrappedEighthTriplets.provesUpperLedgerRailSequence(
                        normalized, width, height, bars, measure - 1, notes, previous, false)
                && WrappedEighthTriplets.provesUpperLedgerRailSequence(
                        original, width, height, bars, measure, notes, owners, true)
                && WrappedEighthTriplets.provesUpperLedgerRailSequence(
                        normalized, width, height, bars, measure, notes, owners, true);
    }

    private static boolean ownsClosedSystemOpening(
            ScorePageInterpretation score, List<ScoreNoteEvent> notes, List<Integer> owners,
            int bar, byte[] raw, byte[] normalized, int width, int height) {
        if (owners.size() != 14 || !ownsClosedSystemExtent(
                score, notes, owners, bar, raw, normalized, width, height)) return false;
        var region = score.measures().get(bar);
        var first = notes.get(owners.get(0));
        int clef = first.clefBottomDiatonic();
        for (int i = 0; i < owners.size(); i++)
            if (!ordinary(notes.get(owners.get(i)), i < 6 ? 1 : 2, clef)) return false;
        return WrappedEighthTriplets.provesClosedSystemMixedRails(raw, width, height, region, notes, owners)
                && WrappedEighthTriplets.provesClosedSystemMixedRails(
                        normalized, width, height, region, notes, owners);
    }

    private static boolean ownsClosedSystemExtent(
            ScorePageInterpretation score, List<ScoreNoteEvent> notes, List<Integer> owners,
            int bar, byte[] raw, byte[] normalized, int width, int height) {
        return ownsClosedSystemExtent(score, notes, owners, bar, raw, normalized, width, height, false);
    }

    private static boolean ownsClosedSystemExtent(
            ScorePageInterpretation score, List<ScoreNoteEvent> notes, List<Integer> owners,
            int bar, byte[] raw, byte[] normalized, int width, int height, boolean sampledEdge) {
        var bars = score.measures();
        if (normalized == null || normalized.length != raw.length
                || bar + 1 >= bars.size() || score.rests().stream().anyMatch(r -> r.measureIndex() == bar)
                || score.multiRestGroups().stream().anyMatch(g -> g.firstMeasureIndex() <= bar
                        && g.firstMeasureIndex() + g.measureCount() > bar)) return false;
        var region = bars.get(bar);
        var previous = bars.get(bar - 1);
        var following = bars.get(bar + 1);
        float gap = (region.bottom() - region.top()) * height / 8.2f;
        float boundaryPocket = (following.left() - region.right()) * width;
        if (region.cadenceQuarterBeats() != 0 || gap < 10 || gap > 25
                || (region.top() - previous.bottom()) * height < gap * 2.35f
                || Math.abs(following.top() - region.top()) * height > gap * .40f
                || boundaryPocket < gap * .55f || boundaryPocket > gap * 1.75f) return false;
        owners.sort(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure()));
        var first = notes.get(owners.get(0));
        var last = notes.get(owners.get(owners.size() - 1));
        if (first.positionInMeasure() < .035f || first.positionInMeasure() > .18f
                || last.positionInMeasure() < .86f || last.positionInMeasure() > .985f) return false;
        float end = (region.left() + (region.right() - region.left()) * last.positionInMeasure()) * width;
        if (sampledEdge)
            return WrappedMeasureBoundaries.confirmsSampledClosingEdge(raw, width, height, region, end)
                    && WrappedMeasureBoundaries.confirmsSampledClosingEdge(normalized, width, height, region, end);
        return WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(raw, width, height, region, end)
                && WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(normalized, width, height, region, end);
    }

    private static boolean normalizeStraightOpening(
            ScorePageInterpretation score, List<ScoreNoteEvent> notes, List<Integer> owners,
            int bar, byte[] raw, byte[] normalized, int width, int height) {
        boolean sixteenths = owners.size() == 16;
        if (!(sixteenths || owners.size() == 14)
                || !ownsClosedSystemExtent(score, notes, owners, bar, raw, normalized, width, height)) return false;
        var bars = score.measures();
        var region = bars.get(bar);
        int clef = notes.get(owners.get(0)).clefBottomDiatonic();
        var fixed = new ArrayList<ScoreNoteEvent>(owners.size());
        var edited = new ArrayList<Integer>();
        for (int i = 0; i < owners.size(); i++) {
            var note = notes.get(owners.get(i));
            var stable = note;
            if (sixteenths && note.tupletDivisor() == 3 && note.tupletNormalNotes() == 2) {
                stable = note.withTupletRatio(1, 1);
            } else if (!sixteenths) {
                if (note.staffStep() <= 8) return false;
                if (note.augmentationDots() == 1) {
                    if (i == owners.size() - 1) return false;
                    stable = WrappedEighthTriplets.withoutDots(note);
                }
            }
            if (!ordinary(stable, !sixteenths && i % 7 == 0 ? 1 : 2, clef)) return false;
            if (stable != note) edited.add(i);
            fixed.add(stable);
        }
        if (edited.isEmpty()) return false;
        if (sixteenths && (edited.size() != 3 || edited.get(0) % 4 != 0
                || edited.get(1) != edited.get(0) + 1 || edited.get(2) != edited.get(0) + 2)) return false;
        var indices = IntStream.range(0, fixed.size()).boxed().toList();
        for (byte[] ink : new byte[][]{raw, normalized}) {
            boolean proved = sixteenths
                    ? WrappedEighthTriplets.provesClosedStraightSixteenths(ink, width, height, region, fixed, indices)
                    : WrappedEighthTriplets.provesClosedEighthAndSixteenthRuns(ink, width, height, region, fixed, indices);
            if (!proved) return false;
        }
        for (int index : edited) notes.set(owners.get(index), fixed.get(index));
        return true;
    }

    private static boolean normalizePairedFingerings(
            ScorePageInterpretation score, List<ScoreNoteEvent> notes, List<Integer> owners,
            int bar, byte[] raw, byte[] normalized, int width, int height) {
        if (owners.size() != 16) return false;
        owners.sort(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure()));
        var edited = new ArrayList<Integer>(6);
        var fixed = new ArrayList<ScoreNoteEvent>(16);
        int clef = notes.get(owners.get(0)).clefBottomDiatonic();
        for (int i = 0; i < owners.size(); i++) {
            var note = notes.get(owners.get(i));
            if (note.tupletDivisor() == 3 && note.tupletNormalNotes() == 2) {
                note = note.withTupletRatio(1, 1);
                edited.add(i);
            }
            if (!ordinary(note, 2, clef) || note.stemDirection() != -1) return false;
            fixed.add(note);
        }
        if (edited.size() != 6) return false;
        int first = edited.get(0), second = edited.get(3);
        if (!edited.equals(List.of(first, first + 1, first + 2, second, second + 1, second + 2))
                || first % 4 != 0 || (second % 4) + 2 < 4 || second - first < 5) return false;
        var region = score.measures().get(bar);
        for (byte[] plane : new byte[][] {raw, normalized}) {
            if (!TripletRhythmDetector.pairedFingeringsWithinBar(
                    plane, width, height, region, fixed, first, second)
                    || !WrappedEighthTriplets.provesPairedFingeringStraightSixteenths(
                            plane, width, height, region, fixed)) return false;
        }
        if (!ownsClosedSystemExtent(score, notes, owners, bar, raw, normalized, width, height, true)) return false;
        for (int i : edited) notes.set(owners.get(i), fixed.get(i));
        return true;
    }

    private static boolean ordinary(ScoreNoteEvent n, int beams, int clef) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED
                && n.staffIndex() == 0
                && n.staffCount() == 1
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.beamCount() == beams
                && n.augmentationDots() == 0
                && n.unbeamedDurationBeats() == 0
                && !n.crossStaffBeam()
                && n.leadingRestBeats() == 0
                && n.followingRestBeats() == 0
                && !n.tiedFromPrevious()
                && n.boundaryTies() == 0
                && (n.articulations() & NoteOrnament.GRACE) == 0
                && clef != ScoreNoteEvent.CLEF_UNKNOWN
                && n.clefBottomDiatonic() == clef
                && n.tupletDivisor() == 1
                && n.tupletNormalNotes() == 1
                && Math.abs(n.stemDirection()) == 1;
    }
}
