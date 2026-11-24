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

    private record BeamRun(List<Integer> owners, List<Integer> originalOwners, int unnumberedTriplets) {
        BeamRun(List<Integer> owners) {
            this(owners, owners, 0);
        }
    }

    record Capture(
            ScorePageInterpretation score,
            List<List<BeamRun>> transitions,
            List<BeamRun> straightBars,
            List<BeamRun> wideMixedBars,
            List<ScoreNoteEvent> printedSharpOwners,
            List<ScoreNoteEvent> printedFingeringThreeOwners) {
        Capture(ScorePageInterpretation score, List<List<BeamRun>> transitions) {
            this(score, transitions, List.of(), List.of(), List.of(), List.of());
        }

        ScorePageInterpretation projectBeams(ScorePageInterpretation retained, int page) {
            if (transitions.isEmpty() && straightBars.isEmpty() && wideMixedBars.isEmpty()
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
            for (var bar : wideMixedBars)
                projected = replaceWideMixedBar(retained, page, projected, bar);
            return projected.equals(source) ? retained : retained.withBeamSource(projected);
        }

        private ScoreBeamSource replaceWideMixedBar(
                ScorePageInterpretation retained, int page, ScoreBeamSource source, BeamRun bar) {
            var origins = source.noteOrigins();
            var counts = new HashMap<ScoreBeamSource.Anchor, Integer>();
            for (var origin : origins) origin.ifPresent(a -> counts.merge(a, 1, Integer::sum));
            var ordered = new ArrayList<ScoreBeamSource.Anchor>(bar.owners().size());
            for (int i = 0; i < bar.owners().size(); i++) {
                int owner = bar.owners().get(i);
                var origin = origins.get(owner).orElse(null);
                var note = retained.notes().get(owner);
                var proved = score.notes().get(owner);
                if (origin == null || origin.pageIndex() != page
                        || origin.noteIndex() != bar.originalOwners().get(i)
                        || counts.getOrDefault(origin, 0) != 1
                        || note.measureIndex() != proved.measureIndex()
                        || note.positionInMeasure() != proved.positionInMeasure()
                        || note.pageY() != proved.pageY()
                        || note.staffIndex() != proved.staffIndex()
                        || note.beamCount() != proved.beamCount()
                        || note.tupletDivisor() != proved.tupletDivisor()
                        || note.tupletNormalNotes() != proved.tupletNormalNotes()) return source;
                ordered.add(origin);
            }
            var owned = new HashSet<>(ordered);
            var groups = new ArrayList<ScoreBeamSource.Group>();
            var marked = new HashMap<List<ScoreBeamSource.Anchor>, ScoreBeamSource.TupletMark>();
            var ids = new HashSet<String>();
            for (var group : source.groups()) {
                var members = group.columns().stream().flatMap(c -> c.members().stream()).toList();
                if (members.stream().noneMatch(owned::contains)) {
                    groups.add(group);
                    ids.add(group.id());
                } else if (!owned.containsAll(members)) {
                    return source;
                } else if (group.tupletMark() != null) {
                    marked.put(members, group.tupletMark());
                }
            }
            var detached = new ArrayList<ScoreBeamSource.Column>();
            for (var column : source.detachedColumns()) {
                if (column.members().stream().noneMatch(owned::contains)) detached.add(column);
                else if (!owned.containsAll(column.members())) return source;
            }
            int first = 0;
            for (int size : new int[] {3, 3, 4, 4}) {
                String id = "printed-beam:" + page + ":" + ordered.get(first).noteIndex();
                if (!ids.add(id)) return source;
                var members = ordered.subList(first, first + size);
                var columns = members.stream()
                        .map(anchor -> new ScoreBeamSource.Column(List.of(anchor))).toList();
                var mark = marked.remove(members);
                if (mark == null && size == 3 && (bar.unnumberedTriplets() & (1 << (first / 3))) != 0)
                    mark = ScoreBeamSource.TupletMark.UNNUMBERED;
                groups.add(new ScoreBeamSource.Group(id, columns,
                        Collections.nCopies(size - 1, size == 3 ? 1 : 3), List.of(), mark));
                first += size;
            }
            return marked.isEmpty() ? new ScoreBeamSource(groups, detached, origins) : source;
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
        return capture(score, source, normalized, width, height, null);
    }

    static Capture capture(
            ScorePageInterpretation score, byte[] source, byte[] normalized, int width, int height,
            ScoreMeterChange inheritedMeter) {
        boolean inheritedOpening = inheritedMeter != null && inheritedMeter.measureIndex() == 0;
        if (width < 1
                || height < 1
                || source == null
                || source.length != (long) width * height
                || score.measures().size() < 2
                || score.meterChanges().isEmpty() && !inheritedOpening) return new Capture(score, List.of());
        var notes = new ArrayList<>(score.notes());
        var printedSharpOwners = new ArrayList<ScoreNoteEvent>();
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
        int numerator = inheritedOpening ? inheritedMeter.numerator() : 0;
        int denominator = inheritedOpening ? inheritedMeter.denominator() : 0;
        boolean previousCommon = false;
        var transitions = new ArrayList<List<BeamRun>>();
        var straightBars = new ArrayList<BeamRun>();
        var wideMixedBars = new ArrayList<BeamRun>();
        boolean timingChanged = false;
        var beamAliases = new HashSet<Integer>();
        var printedFingeringThreeOwners = new ArrayList<ScoreNoteEvent>();
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
            boolean ordinaryOpening = sameMeter && !transition && !upperLedger
                    && ownsClosedSystemOpening(score, notes, owners.get(i), i, source, normalized, width, height);
            boolean wideOpening = sameMeter && !transition && !upperLedger && !ordinaryOpening
                    && ownsWideAccidentalOpening(score, notes, owners.get(i), i, source, normalized, width, height);
            if (sameMeter && !transition && !upperLedger && !ordinaryOpening && !wideOpening)
                wideOpening = ownsSplitShaftOpening(score, notes, owners.get(i), i, source, normalized, width, height);
            boolean ruledOpening = sameMeter && !transition && !upperLedger && !ordinaryOpening && !wideOpening
                    && restoreRuledOpeningShaft(score, notes, owners.get(i), i, source, normalized, width, height);
            wideOpening |= ruledOpening;
            boolean interruptedUpstaff = sameMeter && !transition && !upperLedger && !ordinaryOpening
                    && !wideOpening && restoreInterruptedUpstaff(
                            score, notes, owners.get(i), i, source, normalized, width, height);
            wideOpening |= interruptedUpstaff;
            boolean fadedDownstaff = sameMeter && !transition && !upperLedger && !ordinaryOpening
                    && !wideOpening && restoreFadedDownstaff(
                            score, notes, owners.get(i), i, source, normalized, width, height);
            wideOpening |= fadedDownstaff;
            boolean restoredSecondary = sameMeter && !transition && !upperLedger && !ordinaryOpening
                    && !wideOpening && restoreInitialSecondaryRail(
                            score, notes, owners.get(i), i, source, normalized, width, height);
            wideOpening |= restoredSecondary;
            boolean detachedTerminal = false;
            if (sameMeter && !transition && !upperLedger && !ordinaryOpening && !wideOpening) {
                var ordered = owners.get(i);
                if (ordered.size() == 14) {
                    int terminal = ordered.get(13);
                    var terminalNote = notes.get(terminal);
                    if (terminalNote.stemDirection() == 0
                            && notes.get(ordered.get(12)).stemDirection() == -1) {
                        var bar = bars.get(i);
                        float x = (bar.left() + terminalNote.positionInMeasure() * (bar.right() - bar.left())) * width;
                        float y = terminalNote.pageY() * height;
                        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
                        var rawShaft = DetachedClosingDownshaft.measure(source, width, height, x, y, gap);
                        var normalizedShaft = DetachedClosingDownshaft.measure(normalized, width, height, x, y, gap);
                        if (rawShaft != null && rawShaft.equals(normalizedShaft)) {
                            notes.set(terminal, terminalNote.withStemDirection(-1));
                            ordinaryOpening = ownsClosedSystemOpening(
                                    score, notes, ordered, i, source, normalized, width, height);
                            wideOpening = !ordinaryOpening && ownsWideAccidentalOpening(
                                    score, notes, ordered, i, source, normalized, width, height);
                            detachedTerminal = ordinaryOpening || wideOpening;
                            if (!detachedTerminal) notes.set(terminal, terminalNote);
                        }
                    }
                }
            }
            boolean ruledAlias = false;
            if (sameMeter && !transition && !upperLedger && !ordinaryOpening && !wideOpening
                    && owners.get(i).size() == 15) {
                var ordered = new ArrayList<>(owners.get(i));
                ordered.sort(Comparator.comparingDouble(index -> notes.get(index).positionInMeasure()));
                int alias = ordered.remove(13);
                if (RuledTripletFingeringAlias.proves(source, normalized, width, height, bars.get(i),
                            notes.get(alias), notes.get(ordered.get(13)))
                        && (ownsClosedSystemOpening(score, notes, ordered, i, source, normalized, width, height)
                            || ownsWideAccidentalOpening(score, notes, ordered, i, source, normalized, width, height))) {
                    owners.set(i, ordered);
                    beamAliases.add(alias);
                    printedFingeringThreeOwners.add(notes.get(ordered.get(13)));
                    ruledAlias = true;
                    wideOpening = true;
                }
            }
            if (transition || ordinaryOpening || wideOpening) {
                timingChanged = true;
                var ordered = owners.get(i);
                for (int index : ordered.subList(0, 6))
                    notes.set(index, notes.get(index).withTupletRatio(3, 2));
                if (ordinaryOpening) {
                    var sharps = MixedTripletSharpPitches.restore(
                            notes, ordered, bars.get(i), source, normalized, width, height);
                    for (int index : ordered) notes.set(index, sharps.notes().get(index));
                    for (int index : sharps.printedSharpOwners()) printedSharpOwners.add(notes.get(index));
                }
                // A verified beam is independent of a printed tuplet numeral or bracket.
                boolean ledgerHandoff = ordinaryOpening && WrappedEighthTriplets.ownsInterruptedLedgerHandoff(
                        source, normalized, width, height, bars.get(i), notes.get(ordered.get(5)));
                boolean detachedQuartet = ordinaryOpening && WrappedEighthTriplets.ownsDetachedClosingQuartet(
                        source, normalized, width, height, bars.get(i), notes.get(ordered.get(10)));
                int unnumbered = 0;
                if (ruledOpening) {
                    unnumbered = WrappedEighthTriplets.unnumberedRuledOpeningTriplets(
                            source, normalized, width, height, bars, notes, i, ordered);
                } else if (interruptedUpstaff) {
                    unnumbered = WrappedEighthTriplets.unnumberedInterruptedUpstaffTriplets(
                            source, normalized, width, height, bars, notes, i, ordered);
                } else if (fadedDownstaff) {
                    unnumbered = WrappedEighthTriplets.unnumberedFadedDownstaffTriplets(
                            source, normalized, width, height, bars, notes, i, ordered);
                } else if (restoredSecondary) {
                    unnumbered = WrappedEighthTriplets.unnumberedInterruptedUpstaffTriplets(
                            source, normalized, width, height, bars, notes, i, ordered);
                } else if (wideOpening || detachedTerminal || ordinaryOpening) {
                    unnumbered = WrappedEighthTriplets.unnumberedMixedTriplets(
                            source, normalized, width, height, bars, notes, i, ordered,
                            ruledAlias || ledgerHandoff || detachedQuartet, ledgerHandoff);
                }
                if (wideOpening || detachedTerminal || ledgerHandoff || detachedQuartet || unnumbered != 0) {
                    var retained = List.copyOf(ordered);
                    wideMixedBars.add(new BeamRun(retained, retained, unnumbered));
                } else {
                    transitions.add(List.of(
                            new BeamRun(List.copyOf(ordered.subList(6, 10))),
                            new BeamRun(List.copyOf(ordered.subList(10, 14)))));
                }
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
        var retained = IntStream.range(0, notes.size()).filter(i -> !beamAliases.contains(i)).boxed().toList();
        var captured = score.withNotes(retained.stream().map(notes::get).toList(), retained);
        if (beamAliases.isEmpty()) captured = captured.withClockContextSource(score.clockContextSource());
        return new Capture(captured, reindex(transitions, retained, score.beamSource()),
                reindex(List.of(straightBars), retained, score.beamSource()).get(0),
                reindex(List.of(wideMixedBars), retained, score.beamSource()).get(0),
                List.copyOf(printedSharpOwners), List.copyOf(printedFingeringThreeOwners));
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
            List<List<BeamRun>> transitions, List<Integer> retained, ScoreBeamSource beamSource) {
        if (transitions.isEmpty()) return transitions;
        var origins = beamSource.noteOrigins();
        int[] newIndices = new int[origins.size()];
        for (int i = 0; i < retained.size(); i++) newIndices[retained.get(i)] = i;
        return transitions.stream().map(transition -> transition.stream().map(run -> {
            var remapped = run.owners().stream().map(i -> newIndices[i]).toList();
            // Beam source acquired after this capture assigns anchors to the edited score.
            var expectedOrigins = IntStream.range(0, remapped.size()).mapToObj(i ->
                    origins.get(run.owners().get(i)).map(ScoreBeamSource.Anchor::noteIndex)
                            .orElse(remapped.get(i))).toList();
            return new BeamRun(remapped, expectedOrigins, run.unnumberedTriplets());
        }).toList()).toList();
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

    private static boolean ownsWideAccidentalOpening(
            ScorePageInterpretation score, List<ScoreNoteEvent> notes, List<Integer> owners,
            int bar, byte[] raw, byte[] normalized, int width, int height) {
        if (owners.size() != 14 || !ownsClosedSystemExtent(
                score, notes, owners, bar, raw, normalized, width, height, true)) return false;
        var region = score.measures().get(bar);
        int clef = notes.get(owners.get(0)).clefBottomDiatonic();
        for (int i = 0; i < owners.size(); i++)
            if (!ordinary(notes.get(owners.get(i)), i < 6 ? 1 : 2, clef)
                    || notes.get(owners.get(i)).stemDirection() != -1) return false;
        if (WrappedEighthTriplets.provesClosedWideMixedRails(raw, width, height, region, notes, owners)
                && WrappedEighthTriplets.provesClosedWideMixedRails(normalized, width, height, region, notes, owners))
            return true;
        var bars = score.measures();
        return MixedUpperLedgerBeamSource.printedRails(raw, width, height, bars, bar, notes, owners)
                && MixedUpperLedgerBeamSource.printedRails(normalized, width, height, bars, bar, notes, owners);
    }

    private static boolean ownsSplitShaftOpening(
            ScorePageInterpretation score, List<ScoreNoteEvent> notes, List<Integer> owners,
            int bar, byte[] raw, byte[] normalized, int width, int height) {
        if (owners.size() != 14 || !ownsClosedSystemExtent(
                score, notes, owners, bar, raw, normalized, width, height, true)) return false;
        var region = score.measures().get(bar);
        int clef = notes.get(owners.get(0)).clefBottomDiatonic();
        for (int i = 0; i < owners.size(); i++)
            if (!ordinary(notes.get(owners.get(i)), i < 6 ? 1 : 2, clef)) return false;
        float left = region.left() * width;
        float span = (region.right() - region.left()) * width;
        float gap = (region.bottom() - region.top()) * height / 8.2f;
        for (int i = 1; i < 6; i += 3) {
            var note = notes.get(owners.get(i));
            if (note.stemDirection() != -1) continue;
            float x = left + span * note.positionInMeasure(), y = note.pageY() * height;
            var split = SplitOpeningDownshaft.measure(raw, width, height, x, y, gap);
            if (split != null && split.equals(SplitOpeningDownshaft.measure(normalized, width, height, x, y, gap)))
                return WrappedEighthTriplets.provesClosedSystemMixedRails(raw, width, height, region, notes, owners)
                        && WrappedEighthTriplets.provesClosedSystemMixedRails(
                                normalized, width, height, region, notes, owners);
        }
        return false;
    }

    private static boolean restoreRuledOpeningShaft(
            ScorePageInterpretation score, List<ScoreNoteEvent> notes, List<Integer> owners,
            int measure, byte[] raw, byte[] normalized, int width, int height) {
        if (owners.size() != 14 || normalized == null || normalized.length != raw.length) return false;
        owners.sort(Comparator.comparingDouble(index -> notes.get(index).positionInMeasure()));
        int first = owners.get(0);
        var reported = notes.get(first);
        if (reported.stemDirection() != 0 || reported.beamCount() != 2
                || reported.staffStep() < 1 || reported.staffStep() > 5) return false;
        var repaired = reported.withBeamAndStem(1, -1);
        if (!ordinary(repaired, 1, reported.clefBottomDiatonic())) return false;
        var bar = score.measures().get(measure);
        float x = (bar.left() + (bar.right() - bar.left()) * reported.positionInMeasure()) * width;
        float y = reported.pageY() * height, gap = (bar.bottom() - bar.top()) * height / 8.2f;
        var shaft = RuledOpeningDownshaft.measure(raw, width, height, bar, x, y, gap);
        if (shaft == null || !shaft.equals(
                RuledOpeningDownshaft.measure(normalized, width, height, bar, x, y, gap))) return false;
        notes.set(first, repaired);
        if (ownsClosedSystemOpening(score, notes, owners, measure, raw, normalized, width, height))
            return true;
        notes.set(first, reported);
        return false;
    }

    private static boolean restoreFadedDownstaff(
            ScorePageInterpretation score, List<ScoreNoteEvent> notes, List<Integer> owners, int measure,
            byte[] raw, byte[] normalized, int width, int height) {
        if (owners.size() != 14) return false;
        var bar = score.measures().get(measure);
        int clef = notes.get(owners.get(0)).clefBottomDiatonic();
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        var replacements = new HashMap<Integer, ScoreNoteEvent>();
        for (int slot = 0; slot < owners.size(); slot++) {
            int index = owners.get(slot);
            var note = notes.get(index);
            if (note.stemDirection() == 0 && slot >= 6) {
                float x = left + span * note.positionInMeasure(), y = note.pageY() * height;
                var stem = FadedTripletDownshaft.measure(raw, width, height, x, y, gap);
                if (stem == null || !stem.equals(FadedTripletDownshaft.measure(normalized, width, height, x, y, gap)))
                    return false;
                note = note.withStemDirection(-1);
                replacements.put(index, note);
            }
            if (note.stemDirection() != -1 || !ordinary(note, slot < 6 ? 1 : 2, clef)) return false;
        }
        if (replacements.size() < 2 || !ownsClosedSystemExtent(
                score, notes, owners, measure, raw, normalized, width, height, false)) return false;
        var candidate = new ArrayList<>(notes);
        replacements.forEach(candidate::set);
        if (!WrappedEighthTriplets.provesFadedDownstaffMixedRails(raw, width, height, bar, candidate, owners)
                || !WrappedEighthTriplets.provesFadedDownstaffMixedRails(
                        normalized, width, height, bar, candidate, owners)) return false;
        replacements.forEach(notes::set);
        return true;
    }

    private static boolean restoreInterruptedUpstaff(
            ScorePageInterpretation score, List<ScoreNoteEvent> notes, List<Integer> owners,
            int measure, byte[] raw, byte[] normalized, int width, int height) {
        if (owners.size() != 14 || normalized == null || normalized.length != raw.length
                || !ownsClosedSystemExtent(score, notes, owners, measure, raw, normalized, width, height)) return false;
        var unknown = new ArrayList<Integer>();
        int clef = notes.get(owners.get(0)).clefBottomDiatonic();
        for (int i = 0; i < owners.size(); i++) {
            int owner = owners.get(i);
            var note = notes.get(owner);
            if (i >= 6 && note.stemDirection() == 0) unknown.add(owner);
            else if (note.stemDirection() != 1) return false;
            if (!ordinary(note.withStemDirection(1), i < 6 ? 1 : 2, clef)) return false;
        }
        if (unknown.isEmpty() || unknown.size() > 2) return false;
        var bar = score.measures().get(measure);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        var originalTrack = WrappedMeasureBoundaries.closedTripletStaff(raw, width, height, bar);
        var normalizedTrack = WrappedMeasureBoundaries.closedTripletStaff(normalized, width, height, bar);
        for (int owner : unknown) {
            var note = notes.get(owner);
            float x = (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
            float y = note.pageY() * height;
            var shaft = InterruptedUpstaffShaft.measure(raw, width, height, x, y, gap, originalTrack);
            if (shaft == null || !shaft.equals(InterruptedUpstaffShaft.measure(
                    normalized, width, height, x, y, gap, normalizedTrack))) return false;
        }
        for (int owner : unknown) notes.set(owner, notes.get(owner).withStemDirection(1));
        if (WrappedEighthTriplets.provesInterruptedUpstaffMixedRails(raw, width, height, bar, notes, owners)
                && WrappedEighthTriplets.provesInterruptedUpstaffMixedRails(
                        normalized, width, height, bar, notes, owners)) return true;
        for (int owner : unknown) notes.set(owner, notes.get(owner).withStemDirection(0));
        return false;
    }

    private static boolean restoreInitialSecondaryRail(
            ScorePageInterpretation score, List<ScoreNoteEvent> notes, List<Integer> owners,
            int measure, byte[] raw, byte[] normalized, int width, int height) {
        if (owners.size() != 14 || !ownsClosedSystemExtent(
                score, notes, owners, measure, raw, normalized, width, height)) return false;
        int owner = owners.get(6);
        var firstSixteenth = notes.get(owner);
        if (firstSixteenth.beamCount() != 1 || firstSixteenth.stemDirection() != 1) return false;

        // Check all four groups, including the second rail through the first sixteenth,
        // before replacing the one conflicting beam count.
        notes.set(owner, firstSixteenth.withBeamAndStem(2, 1));
        if (ownsClosedSystemOpening(score, notes, owners, measure, raw, normalized, width, height)) return true;
        notes.set(owner, firstSixteenth);
        return false;
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
                // The two region edges are independently rounded to scan pixels.
                || boundaryPocket + 1 < gap * .55f || boundaryPocket > gap * 1.75f) return false;
        owners.sort(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure()));
        var first = notes.get(owners.get(0));
        var last = notes.get(owners.get(owners.size() - 1));
        if (first.positionInMeasure() < .035f || first.positionInMeasure() > .18f
                || last.positionInMeasure() < .86f || last.positionInMeasure() > .985f) return false;
        float end = (region.left() + (region.right() - region.left()) * last.positionInMeasure()) * width;
        if (boundaryPocket < gap * .55f)
            return printedNarrowOpeningEdge(raw, width, height, region, following, end, gap)
                    && printedNarrowOpeningEdge(normalized, width, height, region, following, end, gap);
        if (sampledEdge)
            return WrappedMeasureBoundaries.confirmsSampledClosingEdge(raw, width, height, region, end)
                    && WrappedMeasureBoundaries.confirmsSampledClosingEdge(normalized, width, height, region, end);
        return WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(raw, width, height, region, end)
                && WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(normalized, width, height, region, end);
    }

    private static boolean printedNarrowOpeningEdge(byte[] ink, int width, int height,
            MeasureRegion bar, MeasureRegion following, float lastX, float gap) {
        float edge = (bar.right() + following.left()) * width * .5f;
        if (edge - lastX < 1.15f * gap || edge - lastX > 2.2f * gap) return false;
        float floor = bar.bottom() * height - gap * 2.1f;
        var rules = PrintedDirectionStaff.printedRules(
                ink, width, height, following.right() * width - 1.15f * gap, floor, gap);
        if (rules == null || Math.abs(rules[0] - floor) > gap * .55f
                || Math.abs(rules[1] - gap) > gap * .12f) return false;
        var staff = new PlayingTechniqueDetector.Staff(rules[0] - 4 * rules[1], rules[0], rules[1], 0, 1);
        return ClosedSixteenthPairBeforeHalf.ownedSeparator(ink, width, height, staff, edge);
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
