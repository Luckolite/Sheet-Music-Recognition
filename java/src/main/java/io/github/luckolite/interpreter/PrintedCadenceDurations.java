// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** A narrow, complete written cadence can be shorter than its surrounding meter. */
final class PrintedCadenceDurations {
    private PrintedCadenceDurations() {}

    static ScorePageInterpretation capture(
            ScorePageInterpretation score, byte[] labels, byte[] gray, int width, int height) {
        return captureWithRejectedLedgerRows(score, labels, gray, width, height, List.of(), null);
    }

    static ScorePageInterpretation captureWithRejectedLedgerRows(
            ScorePageInterpretation score, byte[] labels, byte[] gray, int width, int height,
            List<MeasureRegion> rejectedLedgerRows, byte[] annotationGray) {
        if (score.notes().isEmpty()) return score;
        var staffs = OmrScoreInterpreter.techniqueStaffs(
                labels, gray, width, height, score.measures(), rejectedLedgerRows);
        return captureWithStaffs(score, labels, gray, width, height, staffs, annotationGray);
    }

    static ScorePageInterpretation captureWithStaffs(
            ScorePageInterpretation score, byte[] labels, byte[] gray, int width, int height,
            List<PlayingTechniqueDetector.Staff> staffs, byte[] annotationGray) {
        if (score.notes().isEmpty()) return score;
        score = terminalHarmonicQuarter(score, labels, gray, width, height, staffs);
        return capture(score, labels, gray, width, height, staffs, annotationGray);
    }

    static ScorePageInterpretation capture(
            ScorePageInterpretation score, byte[] gray, int width, int height,
            List<PlayingTechniqueDetector.Staff> staffs) {
        return capture(score, null, gray, width, height, staffs, null);
    }

    private static ScorePageInterpretation capture(
            ScorePageInterpretation score, byte[] labels, byte[] gray, int width, int height,
            List<PlayingTechniqueDetector.Staff> staffs, byte[] annotationGray) {
        int count = score.measures().size();
        var notes = new ArrayList<List<ScoreNoteEvent>>(count);
        for (int i = 0; i < count; i++) notes.add(new ArrayList<>());
        for (var note : score.notes()) notes.get(note.measureIndex()).add(note);
        var rests = new boolean[count];
        for (var rest : score.rests()) rests[rest.measureIndex()] = true;
        var meter = new boolean[count];
        var repeatedTripleMeter = new boolean[count];
        var tripleMeter = new boolean[count];
        var interruptedTriple = new boolean[count];
        var commonToTriple = new boolean[count];
        boolean triple = false, common = false;
        for (var change : score.meterChanges()) {
            if (change.measureIndex() >= meter.length) continue;
            boolean nextTriple = change.numerator() == 3 && change.denominator() == 4;
            meter[change.measureIndex()] = true;
            tripleMeter[change.measureIndex()] = nextTriple;
            interruptedTriple[change.measureIndex()] = triple
                    && change.numerator() == 4 && change.denominator() == 4;
            repeatedTripleMeter[change.measureIndex()] = triple && nextTriple;
            commonToTriple[change.measureIndex()] = common && nextTriple;
            triple = nextTriple;
            common = change.numerator() == 4 && change.denominator() == 4;
        }
        var regions = new ArrayList<>(score.measures());
        List<ScoreNoteEvent> repairedNotes = null;
        boolean changed = false;
        var falseMeters = new java.util.HashSet<Integer>();
        for (int i = 0; i < count; i++) {
            var chord = notes.get(i);
            if (rests[i] || chord.isEmpty()) continue;
            float duration = chord.get(0).unbeamedDurationBeats();
            var measure = score.measures().get(i);
            var next = i + 1 < count ? score.measures().get(i + 1) : null;
            if (i > 0 && next != null && chord.size() == 11
                    && !rests[i - 1] && !rests[i + 1]
                    && commonTimeOnBothSides(score, i)
                    && fullSixteenthNeighbor(notes.get(i - 1))
                    && fullSixteenthNeighbor(notes.get(i + 1))
                    && closedThreeBeatRun(gray, width, height, score.measures().get(i - 1),
                            measure, next, chord, staffs)) {
                regions.set(i, new MeasureRegion(measure.left(), measure.right(),
                        measure.top(), measure.bottom(), 3));
                changed = true;
                continue;
            }
            if (i > 0 && next != null && commonToTriple[i + 1] && !meter[i]
                    && !rests[i - 1] && !rests[i + 1] && fullSixteenthDyadRun(notes.get(i - 1))
                    && leftClosedHalfDyad(gray, width, height, score.measures().get(i - 1),
                            measure, next, chord)) {
                if (repairedNotes == null) repairedNotes = new ArrayList<>(score.notes());
                var lower = chord.get(0).pageY() > chord.get(1).pageY() ? chord.get(0) : chord.get(1);
                var upper = lower == chord.get(0) ? chord.get(1) : chord.get(0);
                repairedNotes.set(score.notes().indexOf(upper), closedNote(upper, 2, lower.positionInMeasure()));
                regions.set(i, new MeasureRegion(measure.left(), measure.right(),
                        measure.top(), measure.bottom(), 2));
                changed = true;
                continue;
            }
            int harmonic = i > 0 && next != null
                    ? ClosedHarmonicQuarterCadence.duration(
                            score.measures().get(i - 1), measure, next, chord,
                            labels, gray, annotationGray, width, height)
                    : 0;
            if (harmonic > 0) {
                regions.set(i, new MeasureRegion(measure.left(), measure.right(),
                        measure.top(), measure.bottom(), harmonic));
                changed = true;
                continue;
            }
            boolean isolatedBeamAlias =
                    duration == 0
                            && next != null
                            && i > 0
                            && interruptedTriple[i + 1]
                            && !rests[i - 1]
                            && enclosedQuarterAfterSixteenths(
                                    labels,
                                    gray,
                                    width,
                                    height,
                                    score.measures().get(i - 1),
                                    measure,
                                    next,
                                    notes.get(i - 1),
                                    chord,
                                    true)
                            && chord.stream().noneMatch(note -> hasBeamOwner(score, note));
            if (isolatedBeamAlias) {
                if (repairedNotes == null) repairedNotes = new ArrayList<>(score.notes());
                for (var note : chord)
                    repairedNotes.set(score.notes().indexOf(note), closedNote(note, 1, note.positionInMeasure()));
                regions.set(
                        i,
                        new MeasureRegion(
                                measure.left(),
                                measure.right(),
                                measure.top(),
                                measure.bottom(),
                                1));
                changed = true;
                continue;
            }
            if (duration != 1 && duration != 2) continue;
            if (!chord.stream().allMatch(note -> exactCadenceNote(note, duration))) continue;
            var owner = chord.get(0);
            if (duration == 2 && chord.size() == 1 && i > 0 && next != null
                    && interruptedTriple[i + 1] && !meter[i]
                    && IsolatedDottedHalfCadence.provesMissingDot(
                            labels, gray, annotationGray, width, height,
                            score.measures().get(i - 1), measure, next, owner, staffs)) {
                if (repairedNotes == null) repairedNotes = new ArrayList<>(score.notes());
                repairedNotes.set(score.notes().indexOf(owner),
                        closedNote(owner, duration, owner.positionInMeasure(), 1));
                regions.set(i, new MeasureRegion(measure.left(), measure.right(),
                        measure.top(), measure.bottom(), 3));
                changed = true;
                continue;
            }
            boolean independentlyClosedQuarter =
                    duration == 1
                            && next != null
                            && i > 0
                            && repeatedTripleMeter[i + 1]
                            && !rests[i - 1]
                            && enclosedQuarterAfterSixteenths(
                                    labels,
                                    gray,
                                    width,
                                    height,
                                    score.measures().get(i - 1),
                                    measure,
                                    next,
                                    notes.get(i - 1),
                                    chord,
                                    false);
            boolean octaveBeforeCommon =
                    next != null && interruptedTriple[i + 1] && !meter[i] && annotationGray != null;
            boolean closedFlatSystemEnd =
                    duration == 1
                            && i > 0
                            && (next == null ? triple : repeatedTripleMeter[i + 1] || octaveBeforeCommon)
                            && !rests[i - 1]
                            && notes.get(i - 1).size() == 24
                            && notes.get(i - 1).stream()
                                    .allMatch(
                                            n ->
                                                    n.kind() == ScoreNoteEvent.Kind.PITCHED
                                                            && n.staffIndex() == 0
                                                            && n.staffCount() == 1
                                                            && !n.crossStaffBeam()
                                                            && n.beamCount() >= 2
                                                            && n.unbeamedDurationBeats() == 0)
                            && PrintedQuarterDyadAccidentals.provesClosedTripleSystemEnd(
                                    labels, gray, width, height, measure, next, chord, octaveBeforeCommon)
                            && (!octaveBeforeCommon || closedTerminalOctaveBeforeCommon(
                                    labels, gray, annotationGray, width, height, measure, next, chord));
            boolean accidentalMeterAlias = duration == 1 && next != null
                    && interruptedTriple[i] && tripleMeter[i + 1]
                    && PrintedQuarterDyadAccidentals.provesSeparatedTerminalFlats(
                            labels, gray, width, height, measure, next, chord, staffs);
            if (!accidentalMeterAlias
                    && !independentlyClosedQuarter
                    && !closedFlatSystemEnd
                    && !(duration == 1 && annotationGray != null
                            && shiftedTerminalQuarter(gray, width, height, measure, next, staffs, chord)
                            && shiftedTerminalQuarter(annotationGray, width, height, measure, next, staffs, chord))
                    && !proved(
                            gray,
                            width,
                            height,
                            measure,
                            next,
                            staffs,
                            chord,
                            next != null && meter[i + 1])
                    && !(duration == 2 && next != null && meter[i + 1] && annotationGray != null
                            && closedHalfBeforeInlineMeter(gray, width, height, measure, next, chord)
                            && closedHalfBeforeInlineMeter(
                                    annotationGray, width, height, measure, next, chord))
                    && !compactTerminalQuarter(gray, width, height, measure, next, chord)
                    && !(duration == 2 && i > 0 && next != null && !meter[i + 1]
                            && fullSixteenthDyadRun(notes.get(i - 1))
                            && closedInlineHalf(gray, width, height, score.measures().get(i - 1),
                                    measure, next, chord))
                    && !expandedTerminalDyad(gray, width, height, measure, next, chord)
                    && !PrintedQuarterDyadAccidentals.proves(
                            labels, gray, width, height, measure, next, chord, staffs)
                    && !(duration == 1 && next == null && annotationGray != null
                            && PrintedQuarterDyadAccidentals.provesWidePageEnd(
                                    labels, gray, width, height, measure, chord, staffs)
                            && PrintedQuarterDyadAccidentals.provesWidePageEnd(
                                    labels, annotationGray, width, height, measure, chord, staffs))
                    && !(duration == 1
                            && i > 0
                            && next != null
                            && repeatedTripleMeter[i + 1]
                            && notes.get(i - 1).size() == 24
                            && notes.get(i - 1).stream()
                                    .allMatch(
                                            n ->
                                                    n.beamCount() == 2
                                                            && n.augmentationDots() == 0
                                                            && n.tupletDivisor() == 1)
                            && PrintedQuarterDyadAccidentals.provesBeforeTripleRepeat(
                                    labels, gray, width, height, measure, next, chord, staffs)))
                continue;
            if (accidentalMeterAlias) falseMeters.add(i);
            regions.set(i, new MeasureRegion(measure.left(), measure.right(), measure.top(),
                    measure.bottom(), (int) duration));
            changed = true;
        }
        if (!changed) return score;
        var meterChanges = falseMeters.isEmpty() ? score.meterChanges() : score.meterChanges().stream()
                .filter(change -> !falseMeters.contains(change.measureIndex())).toList();
        return new ScorePageInterpretation(
                regions,
                repairedNotes == null ? score.notes() : repairedNotes,
                score.firstMeasureNumber(),
                score.keyChanges(),
                score.tempoChanges(),
                meterChanges,
                score.rests(),
                score.techniqueChanges(),
                score.dynamicChanges(),
                score.playbackDirections(),
                score.expressiveEvents(),
                score.slurSource(),
                score.openingPickup(),
                score.tremoloSource(),
                score.multiRestGroups(),
                score.beamSource(),
                score.clockContextSource());
    }

    static ScorePageInterpretation terminalHarmonicQuarter(
            ScorePageInterpretation score, byte[] labels, byte[] gray, int width, int height,
            List<PlayingTechniqueDetector.Staff> staffs) {
        int last = score.measures().size() - 1;
        if (last < 1 || score.rests().stream().anyMatch(r -> r.measureIndex() == last)) return score;
        var owners = new ArrayList<Integer>(2);
        for (int i = score.notes().size() - 1; i >= 0; i--) {
            if (score.notes().get(i).measureIndex() == last) owners.add(i);
            if (owners.size() > 2) return score;
        }
        if (owners.size() != 2) return score;
        owners.sort(java.util.Comparator.comparingDouble(i -> score.notes().get(i).positionInMeasure()));
        var lower = score.notes().get(owners.get(0));
        var upper = score.notes().get(owners.get(1));
        if (!exactCadenceNote(lower, 1)
                || upper.kind() != ScoreNoteEvent.Kind.PITCHED
                || upper.staffIndex() != 0 || upper.staffCount() != 1
                || upper.beamCount() != 1 || upper.unbeamedDurationBeats() != 0
                || upper.augmentationDots() != 0 || upper.tupletDivisor() != 1
                || upper.tiedFromPrevious() || upper.crossStaffBeam() || upper.boundaryTies() != 0
                || upper.leadingRestBeats() != 0 || upper.followingRestBeats() != 0
                || (upper.articulations() & NoteOrnament.GRACE) != 0
                || lower.octaveShift() != 2 || upper.octaveShift() != 2
                || lower.stemDirection() != 1 || upper.stemDirection() != -1) return score;

        var bar = score.measures().get(last);
        float left = bar.left() * width, right = bar.right() * width;
        float lowX = left + lower.positionInMeasure() * (right - left);
        float highX = left + upper.positionInMeasure() * (right - left);
        float lowY = lower.pageY() * height, highY = upper.pageY() * height;
        for (var staff : staffs) {
            float gap = staff.gap();
            if (staff.count() != 1 || staff.index() != 0 || gap < 6 || right-left > gap*8
                    || staff.top() < bar.top()*height-gap
                    || staff.bottom() > bar.bottom()*height+gap) continue;
            var rules = PrintedDirectionStaff.printedRules(
                    gray, width, height, right-gap*3f, staff.bottom(), gap);
            if (rules == null) continue;
            gap = rules[1];
            var local = new PlayingTechniqueDetector.Staff(
                    rules[0]-4*gap, rules[0], gap, staff.index(), staff.count());
            if (!TerminalSharedQuarter.proves(
                    labels, gray, width, height, lowX, lowY, highX, highY, gap, local.top())
                    || !ArtificialHarmonics.joinedQuarterStem(
                            gray, width, height, lowX, lowY, gap, lower.stemDirection())
                    || !ArtificialHarmonics.joinedQuarterStem(
                            gray, width, height, highX, highY, gap, upper.stemDirection())
                    || !ArtificialHarmonics.quarterTouchDiamond(labels,gray,width,height,lowX,lowY,gap)
                    || !ArtificialHarmonics.quarterTouchDiamond(labels,gray,width,height,highX,highY,gap)
                    || !completeCadence(gray,width,local,left,right,lowX,highX,right,false,true,false))
                continue;
            var notes = new ArrayList<>(score.notes());
            notes.set(owners.get(1), new ScoreNoteEvent(
                    upper.measureIndex(), upper.positionInMeasure(), upper.staffStep(), upper.staffIndex(),
                    upper.staffCount(), upper.pageY(), upper.tiedFromPrevious(), upper.augmentationDots(), 0,
                    upper.writtenAccidental(), 1, upper.tupletDivisor(), upper.followingRestBeats(),
                    upper.articulations(), upper.clefBottomDiatonic(), upper.crossStaffBeam(),
                    upper.leadingRestBeats(), upper.compactOpening(), upper.octaveShift(), upper.boundaryTies(),
                    upper.tupletNormalNotes(), 1, upper.kind(), upper.visualSize(),
                    upper.harmonicSemitones(), upper.openStringTouch()));
            var regions = new ArrayList<>(score.measures());
            regions.set(last,new MeasureRegion(bar.left(),bar.right(),bar.top(),bar.bottom(),1));
            return new ScorePageInterpretation(regions, notes, score.firstMeasureNumber(),
                    score.keyChanges(),score.tempoChanges(),score.meterChanges(),score.rests(),
                    score.techniqueChanges(),score.dynamicChanges(),score.playbackDirections(),
                    score.expressiveEvents(),score.slurSource(),score.openingPickup(),score.tremoloSource(),
                    score.multiRestGroups(),score.beamSource(),score.clockContextSource());
        }
        return score;
    }

    static boolean[] apply(ScorePageInterpretation score, float[] beats) {
        var simultaneous = new boolean[beats.length];
        for (int i = 0; i < beats.length; i++) {
            int duration = score.measures().get(i).cadenceQuarterBeats();
            if (duration > 0 && duration < beats[i]) {
                beats[i] = duration;
                // The closed three-beat run retains its sequential clock.
                simultaneous[i] = duration < 3;
            }
        }
        return simultaneous;
    }

    private static boolean commonTimeOnBothSides(ScorePageInterpretation score, int bar) {
        ScoreMeterChange active = null;
        for (var change : score.meterChanges()) {
            if (change.measureIndex() > bar + 1) break;
            if (change.measureIndex() >= bar - 1) return false;
            active = change;
        }
        return active != null && active.numerator() == 4 && active.denominator() == 4;
    }

    private static boolean fullSixteenthNeighbor(List<ScoreNoteEvent> notes) {
        // One missed head in an independent neighboring 4/4 bar does not authorize
        // changing that bar's clock.
        return (notes.size() == 15 || notes.size() == 16)
                && notes.stream().allMatch(note -> closedBeamMember(note, 2));
    }

    private static boolean closedBeamMember(ScoreNoteEvent note, int rails) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.staffCount() == 1 && note.staffIndex() == 0
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && note.stemDirection() == -1 && note.beamCount() == rails
                && note.unbeamedDurationBeats() == 0 && note.augmentationDots() == 0
                && note.tupletDivisor() == 1 && note.tupletNormalNotes() == 1
                && !note.crossStaffBeam() && !note.tiedFromPrevious() && note.boundaryTies() == 0
                && note.leadingRestBeats() == 0 && note.followingRestBeats() == 0
                && (note.articulations() & NoteOrnament.GRACE) == 0;
    }

    /** Two closed quarters and two sixteenths joined to a final eighth at a system end. */
    static boolean closedThreeBeatRun(byte[] gray, int width, int height,
            MeasureRegion previous, MeasureRegion bar, MeasureRegion next,
            List<ScoreNoteEvent> notes, List<PlayingTechniqueDetector.Staff> staffs) {
        if (notes.size() != 11 || bar.cadenceQuarterBeats() != 0
                || previous.cadenceQuarterBeats() != 0 || next.cadenceQuarterBeats() != 0
                || next.top() <= bar.bottom() || next.left() >= previous.left()) return false;
        float left = bar.left() * width, right = bar.right() * width;
        var ordered = notes.stream().sorted(
                java.util.Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
        for (int i = 0; i < ordered.size(); i++)
            if (!closedBeamMember(ordered.get(i), i == 10 ? 1 : 2)) return false;
        for (var fitted : staffs) {
            float gap = fitted.gap();
            if (fitted.count() != 1 || fitted.index() != 0 || gap < 10 || gap > 26
                    || fitted.top() < bar.top() * height || fitted.bottom() > bar.bottom() * height
                    || Math.abs(previous.top() - bar.top()) * height > gap * .35f
                    || Math.abs(previous.bottom() - bar.bottom()) * height > gap * .35f
                    || left < previous.right() * width || left - previous.right() * width > gap * 1.5f
                    || right - left < gap * 23 || right - left > gap * 35) continue;
            var rules = PrintedDirectionStaff.printedRules(
                    gray, width, height, (left + right) * .5f, fitted.bottom(), gap);
            if (rules == null) continue;
            gap = rules[1];
            float top = rules[0] - gap * 4;
            if (top < 0 || rules[0] >= height) continue;
            var staff = new PlayingTechniqueDetector.Staff(top, rules[0], gap, 0, 1);
            int opening = boundaryColumn(gray, width, staff,
                    (previous.right() * width + left) * .5f, true, false);
            int closing = boundaryColumn(gray, width, staff, right + gap * .3f, true, false);
            if (opening < 0 || closing <= opening) continue;
            if (threeBeatBeamRun(gray, width, height, staff, ordered, left, right, opening, closing))
                return true;
        }
        return false;
    }

    private static boolean threeBeatBeamRun(byte[] gray, int width, int height,
            PlayingTechniqueDetector.Staff staff, List<ScoreNoteEvent> notes,
            float left, float right, int opening, int closing) {
        float gap = staff.gap(), previousX = opening;
        var budget = new PrintedBeamShafts.Budget(160_000);
        PrintedBeamShafts.Shaft previous = null;
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            float x = left + note.positionInMeasure() * (right - left);
            if (x - previousX < gap * 1.55f || x - previousX > gap * 3.45f) return false;
            var axes = PrintedBeamShafts.at(gray, width, height, x, note.pageY() * height, gap, budget);
            if (axes.size() != 1 || axes.get(0).direction() != 1) return false;
            var current = axes.get(0);
            if (previous == null) {
                if (!quietStaffPocket(gray, width, staff, opening + gap * .55f,
                        current.left() - gap * .30f)) return false;
            } else {
                int rails = i == 4 || i == 8 ? 0 : i == 10 ? 1 : 2;
                if (!WrappedEighthTriplets.matchesBeamRails(
                        gray, width, height, previous, current, gap, rails)) return false;
                if (rails == 0 && !quietStaffPocket(gray, width, staff, previousX + gap * .35f,
                        current.left() - gap * .30f)) return false;
            }
            previous = current;
            previousX = x;
        }
        return closing - previousX >= gap * 1.65f && closing - previousX <= gap * 3.45f
                && quietStaffPocket(gray, width, staff, previousX + gap * .8f, closing - gap * .55f);
    }

    private static boolean quietStaffPocket(byte[] gray, int width,
            PlayingTechniqueDetector.Staff staff, float left, float right) {
        int first = Math.round(left), last = Math.round(right);
        float gap = staff.gap();
        if (first < 0 || last >= width || last - first < gap * .5f) return false;
        for (int x = first; x <= last; x++) {
            int dark = 0;
            for (int y = Math.round(staff.top()); y <= Math.round(staff.bottom()); y++) {
                float phase = (y - staff.top()) / gap;
                if (Math.abs(phase - Math.round(phase)) > .21f
                        && (gray[y * width + x] & 255) < 155 && ++dark == 2) return false;
            }
        }
        return true;
    }

    private static boolean hasBeamOwner(ScorePageInterpretation score, ScoreNoteEvent note) {
        var beam = score.beamSource();
        int slot = score.notes().indexOf(note);
        if (slot < 0 || slot >= beam.noteOrigins().size()) return false;
        var source = beam.noteOrigins().get(slot);
        return source.isPresent()
                && beam.groups().stream()
                        .flatMap(g -> g.columns().stream())
                        .anyMatch(column -> column.members().contains(source.get()));
    }

    private static ScoreNoteEvent closedNote(ScoreNoteEvent note, float duration, float position) {
        return closedNote(note, duration, position, note.augmentationDots());
    }

    private static ScoreNoteEvent closedNote(
            ScoreNoteEvent note, float duration, float position, int augmentationDots) {
        return new ScoreNoteEvent(
                note.measureIndex(),
                position,
                note.staffStep(),
                note.staffIndex(),
                note.staffCount(),
                note.pageY(),
                note.tiedFromPrevious(),
                augmentationDots,
                0,
                note.writtenAccidental(),
                duration,
                note.tupletDivisor(),
                note.followingRestBeats(),
                note.articulations(),
                note.clefBottomDiatonic(),
                note.crossStaffBeam(),
                note.leadingRestBeats(),
                note.compactOpening(),
                note.octaveShift(),
                note.boundaryTies(),
                note.tupletNormalNotes(),
                note.stemDirection(),
                note.kind(),
                note.visualSize(),
                note.harmonicSemitones(),
                note.openStringTouch());
    }

    static boolean enclosedQuarterAfterSixteenths(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            MeasureRegion before,
            MeasureRegion measure,
            MeasureRegion next,
            List<ScoreNoteEvent> prior,
            List<ScoreNoteEvent> chord,
            boolean beamAlias) {
        if (gray == null
                || gray.length != (long) width * height
                || measure.cadenceQuarterBeats() != 0
                || chord.size() != 2
                || prior.size() < 23
                || prior.size() > 24
                || prior.stream().anyMatch(n -> !exactCadenceRhythm(n, 0, 2))) return false;
        var a = chord.get(0);
        var b = chord.get(1);
        int direction = a.stemDirection();
        if (!exactCadenceRhythm(a, beamAlias ? 0 : 1, beamAlias ? 1 : 0)
                || !exactCadenceRhythm(b, beamAlias ? 0 : 1, beamAlias ? 1 : 0)
                || !ordinaryWideQuarter(a)
                || !ordinaryWideQuarter(b)
                || direction == 0
                || direction != b.stemDirection()
                || beamAlias && direction != -1
                || a.staffStep() == b.staffStep()) return false;
        float left = measure.left() * width, right = measure.right() * width;
        float gap = (measure.bottom() - measure.top()) * height / 8.2f;
        float span = right - left;
        if (gap < 8
                || gap > 28
                || span < gap * 6
                || span > gap * 9
                || Math.abs(before.top() - measure.top()) * height > gap * .35f
                || (before.right() - before.left()) * width < gap * 32
                || (measure.left() - before.right()) * width < gap * .55f
                || (measure.left() - before.right()) * width > gap * 1.55f
                || next.top() <= measure.bottom()) return false;
        float ax = left + a.positionInMeasure() * span;
        float bx = left + b.positionInMeasure() * span;
        float first = Math.min(ax, bx), last = Math.max(ax, bx);
        if (last - first > gap * .25f
                || first - left < gap * 1.5f
                || first - left > gap * 3.5f
                || right - last < gap * 3.5f) return false;
        float hint = measure.bottom() * height - gap * 2.1f;
        var rails =
                PrintedDirectionStaff.printedRules(
                        gray, width, height, right - gap * 2.5f, hint, gap);
        if (rails == null) return false;
        var staff =
                new PlayingTechniqueDetector.Staff(
                        rails[0] - 4 * rails[1], rails[0], rails[1], 0, 1);
        gap = staff.gap();
        int top = Math.round(staff.top()), bottom = Math.round(staff.bottom());
        if (top < 0 || bottom >= height) return false;
        float opening = (left + before.right() * width) * .5f;
        int start = boundaryColumn(gray, width, staff, opening, true, false);
        int end = boundaryColumn(gray, width, staff, right, !beamAlias, beamAlias);
        if (start < 0
                || end < 0
                || ClosedHeadBarlineGuard.attached(gray, width, height, start, top, bottom, gap)
                || DottedDyadMeasureSeparator.continuesBeyondStaff(
                        gray, width, height, start, top, bottom, gap)
                || !emptyOutsideChord(
                        gray, width, staff, left, end + gap * .4f, first, last, false))
            return false;
        if (beamAlias)
            return sharedDownshaftWithoutFlag(
                    labels,
                    gray,
                    width,
                    height,
                    staff,
                    ax,
                    a.pageY() * height,
                    bx,
                    b.pageY() * height);
        return terminalSingleRule(gray, width, height, end, staff);
    }

    static boolean terminalSingleRule(
            byte[] gray, int width, int height, int x, PlayingTechniqueDetector.Staff staff) {
        float gap = staff.gap();
        int top = Math.round(staff.top()), bottom = Math.round(staff.bottom());
        if (ClosedHeadBarlineGuard.attached(gray, width, height, x, top, bottom, gap)
                || DottedDyadMeasureSeparator.continuesBeyondStaff(
                        gray, width, height, x, top, bottom, gap)) return false;
        int from = x + Math.round(gap * .55f), to = x + Math.round(gap * 1.2f);
        if (to >= width) return false;
        for (int line = 0; line < 5; line++) {
            int y = Math.round(staff.top() + line * gap);
            for (int col = from; col <= to; col++)
                if ((gray[y * width + col] & 255) < 185) return false;
        }
        return true;
    }

    private static boolean sharedDownshaftWithoutFlag(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            PlayingTechniqueDetector.Staff staff,
            float ax,
            float ay,
            float bx,
            float by) {
        if (labels == null || labels.length != gray.length) return false;
        float gap = staff.gap();
        float first = Math.min(ax, bx);
        float upper = Math.min(ay, by), lower = Math.max(ay, by);
        int begin = Math.round(upper + gap * .28f);
        int stop = Math.round(lower + gap * 3.6f);
        if (lower - upper < gap * .8f
                || lower - upper > gap * 1.25f
                || upper - gap * .24f < 0
                || begin < 0
                || stop >= height) return false;
        for (int shaft = Math.round(first - gap * .75f);
                shaft <= Math.round(first - gap * .35f);
                shaft++) {
            if (shaft < 0
                    || shaft + gap >= width
                    || !joinsRightHead(labels, gray, width, shaft, ay, gap)
                    || !joinsRightHead(labels, gray, width, shaft, by, gap)) continue;
            int y = begin, stemLabels = 0;
            while (y <= stop && (gray[y * width + shaft] & 255) < 155) {
                if (labels[y * width + shaft] == OmrMeasurePostProcessor.STEM_OR_REST) stemLabels++;
                y++;
            }
            int tip = y - 1;
            if (y > stop || tip - lower < gap * 2.5f || stemLabels < (y - begin) * .68f) continue;
            if (ArtificialHarmonics.unflaggedQuarterTip(
                    gray, width, height, shaft, upper, lower, tip, -1, staff)) return true;
        }
        return false;
    }

    private static boolean joinsRightHead(
            byte[] labels, byte[] gray, int width, int shaft, float y, float gap) {
        int radius = Math.round(gap * .23f);
        int reach = Math.round(gap * .56f);
        for (int row = Math.round(y) - radius; row <= Math.round(y) + radius; row++) {
            for (int dx = 0; dx <= reach; dx++) {
                int at = row * width + shaft + dx;
                if ((gray[at] & 255) >= 155) break;
                if (dx >= reach / 2 && labels[at] == OmrMeasurePostProcessor.NOTEHEAD) return true;
            }
        }
        return false;
    }

    private static boolean exactCadenceNote(ScoreNoteEvent note, float duration) {
        return exactCadenceRhythm(note, duration, 0);
    }

    private static boolean exactCadenceRhythm(ScoreNoteEvent note, float duration, int beams) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.staffIndex() == 0
                && note.staffCount() == 1
                && note.unbeamedDurationBeats() == duration
                && note.beamCount() == beams
                && note.augmentationDots() == 0
                && note.tupletDivisor() == 1
                && !note.crossStaffBeam()
                && !note.tiedFromPrevious()
                && note.boundaryTies() == 0
                && note.leadingRestBeats() == 0
                && note.followingRestBeats() == 0
                && (note.articulations() & NoteOrnament.GRACE) == 0;
    }

    /** Re-anchor a sloped system end using five local rules and both written separators. */
    private static boolean closedTerminalOctaveBeforeCommon(
            byte[] labels, byte[] gray, byte[] original, int width, int height,
            MeasureRegion measure, MeasureRegion next, List<ScoreNoteEvent> chord) {
        if (!PrintedQuarterDyadAccidentals.provesClosedTripleSystemEnd(
                labels, original, width, height, measure, next, chord, true)) return false;
        float left = measure.left() * width;
        float span = measure.right() * width - left;
        float gap = (measure.bottom() - measure.top()) * height / 8.2f;
        int direction = chord.get(0).stemDirection();
        return quarterShaft(gray, width, height, left, span, gap, direction, chord)
                && quarterShaft(original, width, height, left, span, gap, direction, chord);
    }

    static boolean shiftedTerminalQuarter(
            byte[] gray, int width, int height, MeasureRegion measure, MeasureRegion next,
            List<PlayingTechniqueDetector.Staff> staffs, List<ScoreNoteEvent> chord) {
        if (gray == null || gray.length != (long) width * height || chord.isEmpty()
                || chord.size() > 2) return false;
        float gap = (measure.bottom() - measure.top()) * height / 8.2f;
        float left = measure.left() * width, right = measure.right() * width;
        float span = right - left;
        if (gap < 8 || gap > 30 || span < gap * 4 || span > gap * 8.5f
                || next != null && Math.abs(next.top() - measure.top()) * height < gap) return false;
        int direction = chord.get(0).stemDirection();
        double first = chord.stream().mapToDouble(ScoreNoteEvent::positionInMeasure).min().orElseThrow();
        double last = chord.stream().mapToDouble(ScoreNoteEvent::positionInMeasure).max().orElseThrow();
        if (direction == 0 || first < .23 || last > .62 || (last - first) * span > gap * .22f
                || !chord.stream().allMatch(n -> exactCadenceNote(n, 1)
                        && n.stemDirection() == direction
                        && n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY)) return false;
        float hint = measure.bottom() * height - gap * 2.1f;
        float[] rules = PrintedDirectionStaff.printedRules(
                gray, width, height, left + gap * 1.5f, hint, gap);
        if (rules == null) return false;
        float g = rules[1];
        var local = new PlayingTechniqueDetector.Staff(rules[0] - 4 * g, rules[0], g, 0, 1);
        boolean coherentShift = staffs.stream().anyMatch(staff ->
                staff.count() == 1 && staff.index() == 0
                        && staff.top() >= measure.top() * height - gap
                        && staff.bottom() <= measure.bottom() * height + gap
                        && Math.abs(staff.gap() - g) <= g * .18f
                        && Math.abs(staff.bottom() - local.bottom()) >= g * .5f
                        && Math.abs(staff.bottom() - local.bottom()) <= g * 1.45f);
        if (!coherentShift
                || boundaryColumn(gray, width, local, left - g * .53f, true, false) < 0
                || !completeCadence(gray, width, local, left, right,
                        left + first * span, left + last * span, right, false, true, false))
            return false;
        return quarterShaft(gray, width, height, left, span, g, direction, chord);
    }

    static boolean quarterShaft(
            byte[] gray, int width, int height, float left, float span, float gap, int direction,
            List<ScoreNoteEvent> chord) {
        float xLow = 0, xHigh = width - 1;
        float yLow = height, yHigh = 0;
        for (var note : chord) {
            float x = left + span * note.positionInMeasure();
            xLow = Math.max(xLow, x + (direction > 0 ? .28f : -.86f) * gap);
            xHigh = Math.min(xHigh, x + (direction > 0 ? .86f : -.28f) * gap);
            float y = note.pageY() * height;
            yLow = Math.min(yLow, y);
            yHigh = Math.max(yHigh, y);
        }
        int start = Math.round(yLow - (direction > 0 ? 1.15f : -.15f) * gap);
        int end = Math.round(yHigh + (direction < 0 ? 1.15f : -.15f) * gap);
        if (start < 0 || end >= height || xLow < 1 || xHigh >= width - 1) return false;
        for (int x = (int) Math.ceil(xLow); x <= (int) Math.floor(xHigh); x++) {
            int dark = 0;
            for (int y = start; y <= end; y++) {
                int i = y * width + x;
                if ((gray[i] & 255) < 155) dark++;
            }
            if (dark >= (end - start + 1) * .86f) return true;
        }
        return false;
    }



    static boolean proved(
            byte[] gray, int width, int height, MeasureRegion measure, MeasureRegion next,
            List<PlayingTechniqueDetector.Staff> staffs, List<ScoreNoteEvent> chord,
            boolean nextHasMeter) {
        for (var staff : staffs) {
            float gap = staff.gap();
            if (staff.count() != 1 || staff.top() < 0 || staff.top() + gap * 4 >= height
                    || staff.bottom() >= height || staff.top() < measure.top() * height - gap
                    || staff.bottom() > measure.bottom() * height + gap) continue;
            float span = (measure.right() - measure.left()) * width;
            double first = chord.stream().mapToDouble(ScoreNoteEvent::positionInMeasure).min().orElseThrow();
            double last = chord.stream().mapToDouble(ScoreNoteEvent::positionInMeasure).max().orElseThrow();
            if (first < .15 || last > .8 || (last - first) * span > gap * .4) return false;
            float left = measure.left() * width, right = measure.right() * width;
            boolean sameSystem = next != null && Math.abs(next.top() - measure.top()) * height < gap;
            boolean quarter = chord.get(0).unbeamedDurationBeats() == 1;
            // An independently closed quarter can end the exercise left of the
            // next clef and meter, even when both staves are at the same height.
            boolean detachedHeader = quarter && sameSystem && nextHasMeter
                    && next.left() * width - right > gap * 8
                    && next.left() * width - right < gap * 26;
            if (detachedHeader) sameSystem = false;
            // A wide quarter cadence still needs both closing strokes.
            boolean wideQuarter = quarter && (!sameSystem || !nextHasMeter);
            if (span > gap * (wideQuarter ? 10.25f : 8f)
                    && !wideClosedHalfOctave(
                            gray,
                            width,
                            height,
                            staff,
                            left,
                            span,
                            sameSystem && !nextHasMeter,
                            chord)) return false;
            float boundary = right;
            if (detachedHeader) boundary += gap * .55f;
            else if (sameSystem) boundary = (right + next.left() * width) * .5f;
            double firstX = left + first * span, lastX = left + last * span;
            boolean hasAccidental = chord.stream().anyMatch(
                    note -> note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY);
            if (completeCadence(gray, width, staff, left, right, firstX, lastX, boundary,
                    sameSystem && nextHasMeter, false, hasAccidental))
                return true;
            // Near a sloping edge the page-wide rails can be several pixels off.
            // Reuse a local five-rule reading before allowing an interrupted stroke.
            float[] rails = PrintedDirectionStaff.printedRules(
                    gray, width, height, left + span * .25f, staff.bottom(), gap);
            if (rails == null) return false;
            float localTop = rails[0] - rails[1] * 4;
            if (localTop < 0 || rails[0] >= height) return false;
            var local = new PlayingTechniqueDetector.Staff(
                    localTop, rails[0], rails[1], staff.index(), staff.count());
            if (completeCadence(gray, width, local, left, right, firstX, lastX, boundary,
                    sameSystem && nextHasMeter, !sameSystem, hasAccidental)) return true;
            if (quarter && !sameSystem && naturalQuarterDyad(
                    gray, width, height, left, right, firstX, lastX, local, chord)) return true;
            // The fitted right boundary can end just inside a printed closing double bar.
            if (sameSystem) return false;
            if (!quarter && !provedIsolatedHalfDyad(gray, width, left, local, chord)) return false;
            float outer = right + local.gap() * (quarter ? .25f : .35f);
            return completeCadence(gray, width, local, left, outer, firstX, lastX, outer,
                    false, true, hasAccidental);
        }
        return false;
    }

    /** A wide same-system half octave still needs an independent opening rule and double close. */
    private static boolean wideClosedHalfOctave(
            byte[] gray,
            int width,
            int height,
            PlayingTechniqueDetector.Staff staff,
            float left,
            float span,
            boolean doubleCloseRequired,
            List<ScoreNoteEvent> chord) {
        float gap = staff.gap();
        if (!doubleCloseRequired || span > gap * 8.85f || chord.size() != 2) return false;
        var a = chord.get(0);
        var b = chord.get(1);
        if (a.unbeamedDurationBeats() != 2
                || b.unbeamedDurationBeats() != 2
                || a.stemDirection() != -1
                || b.stemDirection() != -1
                || a.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                || b.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                || Math.abs(a.staffStep() - b.staffStep()) != 7) return false;
        int opening = boundaryColumn(gray, width, staff, left - gap * .53f, true, false);
        return opening >= 0
                && !ClosedHeadBarlineGuard.attached(
                        gray,
                        width,
                        height,
                        opening,
                        Math.round(staff.top()),
                        Math.round(staff.bottom()),
                        gap)
                && !DottedDyadMeasureSeparator.continuesBeyondStaff(
                        gray,
                        width,
                        height,
                        opening,
                        Math.round(staff.top()),
                        Math.round(staff.bottom()),
                        gap);
    }

    /** A complete displaced natural can extend left of the usual chord exclusion. */
    private static boolean naturalQuarterDyad(byte[] gray, int width, int height,
            float left, float right, double first, double last,
            PlayingTechniqueDetector.Staff staff, List<ScoreNoteEvent> chord) {
        if (chord.size() != 2) return false;
        var a = chord.get(0);
        var b = chord.get(1);
        if (a.staffStep() == b.staffStep() || a.stemDirection() == 0
                || a.stemDirection() != b.stemDirection()) return false;
        if (b.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL) {
            var other = a;
            a = b;
            b = other;
        }
        if (a.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_NATURAL
                || b.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) return false;
        float gap = staff.gap();
        if (boundaryColumn(gray, width, staff, left - gap * .53f, true, false) < 0)
            return false;
        int end = boundaryColumn(gray, width, staff, right, false, true);
        float edge = right;
        if (end < 0) {
            edge = right + gap * .25f;
            end = boundaryColumn(gray, width, staff, edge, false, true);
        }
        if (end < 0) return false;
        int outermost = -1, innermost = -1;
        int limit = Math.round(Math.min(edge, end + gap * .45f) - gap * .75f);
        for (int x = Math.round(left + gap * .75f); x < limit; x++) {
            if (x >= first - gap * 1.5f && x <= last + gap * 1.2f) continue;
            int ink = 0;
            for (int y = Math.round(staff.top()); y <= Math.round(staff.bottom()); y++)
                if (betweenRules(y, staff) && (gray[y * width + x] & 255) < 155) ink++;
            if (ink < gap * .55f) continue;
            if (x < first - gap * 2.1f || x >= first - gap * 1.5f
                    || innermost >= 0 && x > innermost + 1) return false;
            if (outermost < 0) outermost = x;
            innermost = x;
        }
        if (outermost < 0 || innermost - outermost > gap * .52f) return false;
        // Read both natural spines and connectors, never just the blocking dark columns.
        int from = outermost - Math.max(1, Math.round(gap * .08f));
        int to = Math.min(Math.round((float) first - gap * .95f), innermost + Math.round(gap * .45f));
        return OmrScoreInterpreter.provesNaturalMeterInk(gray, width, height,
                from, to, a.pageY() * height, gap, Math.round(staff.top()));
    }

    private static boolean provedIsolatedHalfDyad(
            byte[] gray, int width, float left,
            PlayingTechniqueDetector.Staff staff, List<ScoreNoteEvent> chord) {
        if (chord.size() != 2) return false;
        var first = chord.get(0);
        var second = chord.get(1);
        if (first.staffStep() == second.staffStep() || first.stemDirection() == 0
                || first.stemDirection() != second.stemDirection()) return false;
        return boundaryColumn(gray, width, staff, left - staff.gap() * .53f, true, false) >= 0;
    }

    private static boolean fullSixteenthDyadRun(List<ScoreNoteEvent> heads) {
        return heads.size() == 32 && heads.stream().allMatch(n ->
                exactCadenceRhythm(n, 0, 2));
    }

    /** The next playable crop can start beyond the written meter and its two closing rules. */
    static boolean closedHalfBeforeInlineMeter(
            byte[] gray, int width, int height, MeasureRegion measure, MeasureRegion next,
            List<ScoreNoteEvent> chord) {
        if (gray == null || gray.length != (long) width * height || chord.size() != 2)
            return false;
        float left = measure.left() * width, right = measure.right() * width;
        float gap = (measure.bottom() - measure.top()) * height / 8.2f;
        float span = right - left, followingGap = next.left() * width - right;
        if (gap < 9 || gap > 30 || span < 4 * gap || span > 8 * gap
                || followingGap < 2.35f * gap || followingGap > 5.5f * gap
                || Math.abs(next.top() - measure.top()) * height > gap * .5f)
            return false;
        float first = Math.min(chord.get(0).positionInMeasure(), chord.get(1).positionInMeasure());
        float last = Math.max(chord.get(0).positionInMeasure(), chord.get(1).positionInMeasure());
        if (first < .22f || last > .65f || (last - first) * span > gap * .26f
                || !chord.stream().allMatch(n -> exactCadenceNote(n, 2)
                        && n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY))
            return false;
        var rules = PrintedDirectionStaff.printedRules(
                gray, width, height, left + gap * 1.5f, measure.bottom() * height - 2.1f * gap, gap);
        if (rules == null) return false;
        gap = rules[1];
        var staff = new PlayingTechniqueDetector.Staff(rules[0] - 4 * gap, rules[0], gap, 0, 1);
        return provedIsolatedHalfDyad(gray, width, left, staff, chord)
                && completeCadence(gray, width, staff, left, right, left + first * span,
                        left + last * span, right + .55f * gap, false, false, false);
    }

    /** The two hollow heads share one shaft in an independently closed system end. */
    private static boolean leftClosedHalfDyad(
            byte[] gray, int width, int height, MeasureRegion before, MeasureRegion bar,
            MeasureRegion next, List<ScoreNoteEvent> chord) {
        if (gray == null || gray.length != (long) width * height || chord.size() != 2
                || bar.cadenceQuarterBeats() != 0 || next.top() <= bar.bottom()) return false;
        var lower = chord.get(0);
        var upper = chord.get(1);
        if (lower.pageY() < upper.pageY()) {
            var swap = lower;
            lower = upper;
            upper = swap;
        }
        if (!exactCadenceNote(lower, 2) || !exactCadenceNote(upper, 1)
                || !ordinaryWideQuarter(lower) || !ordinaryWideQuarter(upper)
                || lower.stemDirection() != 1 || upper.stemDirection() != 1) return false;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float left = bar.left() * width, right = bar.right() * width, span = right - left;
        if (gap < 8 || gap > 25 || span < gap * 4 || span > gap * 7
                || Math.abs(before.top() - bar.top()) * height > gap * .35f
                || (bar.left() - before.right()) * width < gap * .55f
                || (bar.left() - before.right()) * width > gap * 1.55f) return false;
        float ax = left + span * lower.positionInMeasure(), bx = left + span * upper.positionInMeasure();
        float first = Math.min(ax, bx), last = Math.max(ax, bx);
        float floor = bar.bottom() * height - 2.1f * gap;
        if (last - first > gap * .34f || first - left < gap * 1.4f
                || right - last < gap * 2.4f) return false;
        var rails = PrintedDirectionStaff.printedRules(gray, width, height, right - gap * 2.5f, floor, gap);
        if (rails == null) return false;
        gap = rails[1];
        var staff = new PlayingTechniqueDetector.Staff(rails[0] - 4 * gap, rails[0], gap, 0, 1);
        int top = Math.round(staff.top()), bottom = Math.round(staff.bottom());
        if (top < 0 || bottom >= height) return false;
        int opening = boundaryColumn(gray, width, staff, (left + before.right()*width)*.5f, true, false);
        int ending = boundaryColumn(gray, width, staff, right, false, true);
        if (opening < 0 || ending < 0
                || ClosedHeadBarlineGuard.attached(gray, width, height, opening, top, bottom, gap)
                || DottedDyadMeasureSeparator.continuesBeyondStaff(gray, width, height, opening, top, bottom, gap)
                || !emptyOutsideChord(gray, width, staff, left, ending + gap *.4f, first, last, false))
            return false;
        float ay=lower.pageY()*height, by=upper.pageY()*height;
        if (ay-by < gap*1.25f || ay-by > gap*2.8f
                || !hollowPocket(gray,width,height,ax,ay,gap)
                || !hollowPocket(gray,width,height,bx,by,gap)) return false;
        int start = Math.round(by-gap*1.4f), end = Math.round(ay-gap*.23f);
        if (start < 0 || end >= height) return false;
        for (int shaft = Math.round(last + gap*.16f); shaft <= Math.round(first + gap*.75f); shaft++) {
            if (shaft < 0 || shaft >= width) continue;
            int missing=0;
            for (int y=start;y<=end;y++)
                if ((gray[y*width+shaft]&255)>=155) missing++;
            if (missing<=Math.round(gap*.11f)) return true;
        }
        return false;
    }

    private static boolean hollowPocket(byte[] gray,int width,int height,float x,float y,float gap) {
        int left=Math.round(x-gap), right=Math.round(x+gap*.63f);
        int top=Math.round(y-gap*.73f), bottom=Math.round(y+gap*.73f);
        return left>=0 && right<width && top>=0 && bottom<height
                && WholeHeadPocketEvidence.hasEnclosedOpening(
                        gray,width,height,left,top,right,bottom,x-gap*.12f,y,gap,
                        Math.max(12, Math.round(gap * gap * .065f)), 3);
    }

    /** A wide same-row half dyad needs independent opening and closing rules. */
    private static boolean closedInlineHalf(byte[] gray, int width, int height,
            MeasureRegion before, MeasureRegion measure, MeasureRegion next, List<ScoreNoteEvent> chord) {
        if (gray == null || gray.length != (long) width * height || chord.size() != 2
                || measure.cadenceQuarterBeats() != 0) return false;
        var a = chord.get(0);
        var b = chord.get(1);
        if (a.staffStep() == b.staffStep() || a.stemDirection() == 0
                || a.stemDirection() != b.stemDirection()
                || a.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                || b.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) return false;
        float left = measure.left() * width, right = measure.right() * width;
        float gap = (measure.bottom() - measure.top()) * height / 8.2f, span = right - left;
        if (gap < 9 || gap > 30 || span <= 8 * gap || span > 9 * gap
                || Math.abs(before.top() - measure.top()) * height > gap * .5f
                || Math.abs(next.top() - measure.top()) * height > gap * .5f
                || (left - before.right() * width) < gap * .6f
                || (left - before.right() * width) > gap * 1.7f
                || (next.left() * width - right) < gap * .6f
                || (next.left() * width - right) > gap * 1.7f) return false;
        float first = Math.min(a.positionInMeasure(), b.positionInMeasure());
        float last = Math.max(a.positionInMeasure(), b.positionInMeasure());
        if (first < .27f || last > .54f || (last - first) * span > gap * .30f) return false;
        var rails = PrintedDirectionStaff.printedRules(gray, width, height, left + gap * 1.85f,
                measure.bottom() * height - 2.1f * gap, gap);
        if (rails == null) return false;
        int top = Math.round(rails[0] - 4 * rails[1]), bottom = Math.round(rails[0]);
        gap = rails[1];
        var staff = new PlayingTechniqueDetector.Staff(top, bottom, gap, 0, 1);
        int opening = boundaryColumn(gray, width, staff, (before.right() * width + left) / 2, true, false);
        int closing = boundaryColumn(gray, width, staff, (right + next.left() * width) / 2, false, false);
        return opening >= 0 && closing >= 0
                && !ClosedHeadBarlineGuard.attached(gray, width, height, opening, top, bottom, gap)
                && !DottedDyadMeasureSeparator.continuesBeyondStaff(gray, width, height, opening, top, bottom, gap)
                && !DottedDyadMeasureSeparator.continuesBeyondStaff(gray, width, height, closing, top, bottom, gap)
                && emptyOutsideChord(gray, width, staff, left, right,
                        left + first * span, left + last * span, false);
    }

    /** A short terminal bar cannot always provide a full-width staff sample around its chord. */
    static boolean compactTerminalQuarter(byte[] gray, int width, int height, MeasureRegion measure,
            MeasureRegion next, List<ScoreNoteEvent> chord) {
        if (gray == null || gray.length != (long) width * height || chord.size() != 2
                || measure.cadenceQuarterBeats() != 0
                || next == null || next.top() <= measure.bottom()) return false;
        var a = chord.get(0);
        var b = chord.get(1);
        if (!exactCadenceNote(a, 1) || !exactCadenceNote(b, 1)
                || a.staffStep() == b.staffStep() || a.stemDirection() == 0
                || a.stemDirection() != b.stemDirection()
                || a.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                || b.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) return false;

        float left = measure.left() * width, right = measure.right() * width;
        float gap = (measure.bottom() - measure.top()) * height / 8.2f;
        float span = right - left;
        if (gap < 8 || gap > 32 || span < gap * 3f || span > gap * 4.25f) return false;
        float first = left + Math.min(a.positionInMeasure(), b.positionInMeasure()) * span;
        float last = left + Math.max(a.positionInMeasure(), b.positionInMeasure()) * span;
        if (last - first > gap * .22f || first - left < gap * .9f || right - last < gap * 2f)
            return false;

        float hint = measure.bottom() * height - gap * 2.1f;
        var lead = compactRails(gray, width, height, left - gap * .22f, first - gap * .7f, hint, gap);
        var tail = compactRails(gray, width, height, last + gap * .86f, right - gap * .56f, hint, gap);
        if (lead == null || tail == null
                || Math.abs(lead[0] - tail[0]) > gap * .11f
                || Math.abs(lead[1] - tail[1]) > gap * .06f) return false;
        var staff = new PlayingTechniqueDetector.Staff(tail[0] - 4 * tail[1], tail[0], tail[1], 0, 1);
        int opening = boundaryColumn(gray, width, staff, left - gap * .53f, true, false);
        if (opening < 0 || ClosedHeadBarlineGuard.attached(gray, width, height, opening,
                    Math.round(staff.top()), Math.round(staff.bottom()), staff.gap())
                || DottedDyadMeasureSeparator.continuesBeyondStaff(gray, width, height, opening,
                    Math.round(staff.top()), Math.round(staff.bottom()), staff.gap())) return false;
        return completeCadence(gray, width, staff, left, right, first, last, right,
                false, false, false);
    }

    private static float[] compactRails(byte[] gray, int width, int height,
            float left, float right, float hint, float gap) {
        int x = Math.round(left), span = Math.round(right) - x;
        int top = Math.round(hint - gap * 5f), bottom = Math.round(hint + gap);
        if (x < 0 || x + span > width || top < 0 || bottom > height || span < Math.max(6, gap * .5f))
            return null;
        var strip = new byte[span * (bottom - top)];
        for (int y = top; y < bottom; y++)
            System.arraycopy(gray, y * width + x, strip, (y - top) * span, span);
        var rails = StaffPitchTrack.broadStraightPitch(strip, span, bottom - top, hint - top, gap, false);
        if (rails != null) rails[0] += top;
        return rails;
    }

    /** A wide isolated dyad closes the staff only when its bounding ink proves both edges. */
    static boolean expandedTerminalDyad(byte[] gray, int width, int height,
            MeasureRegion measure, MeasureRegion next, List<ScoreNoteEvent> chord) {
        if (gray == null || gray.length != (long) width * height || chord.size() != 2
                || measure.cadenceQuarterBeats() != 0) return false;
        var lower = chord.get(0);
        var upper = chord.get(1);
        float duration = lower.unbeamedDurationBeats();
        if ((duration != 1 && duration != 2)
                || !exactCadenceNote(lower, duration) || !exactCadenceNote(upper, duration)
                || lower.staffStep() == upper.staffStep()
                || lower.stemDirection() == 0 || lower.stemDirection() != upper.stemDirection())
            return false;
        float left = measure.left() * width, right = measure.right() * width;
        float gap = (measure.bottom() - measure.top()) * height / 8.2f;
        boolean strictWideQuarter = duration == 1 && right - left > gap * 11.25f;
        if (gap < 6
                || gap > 32
                || right - left > gap * (strictWideQuarter ? 12.25f : 11.25f)
                || right - left <= gap * (duration == 1 ? 10.25f : 8f)
                || next != null && Math.abs(next.top() - measure.top()) * height < gap)
            return false;
        if (strictWideQuarter && (!ordinaryWideQuarter(lower) || !ordinaryWideQuarter(upper)))
            return false;
        float a = lower.positionInMeasure(), b = upper.positionInMeasure();
        double first = left + Math.min(a, b) * (right - left);
        double last = left + Math.max(a, b) * (right - left);
        if (Math.min(a, b) < .2f || Math.max(a, b) > .57f || last - first > gap * .38f)
            return false;
        float hint = measure.bottom() * height - gap * 2.1f;
        var leftRails = PrintedDirectionStaff.printedRules(
                gray, width, height, left + gap * 1.85f, hint, gap);
        if (leftRails == null) return false;
        var leftStaff = new PlayingTechniqueDetector.Staff(
                leftRails[0] - 4 * leftRails[1], leftRails[0], leftRails[1], 0, 1);
        int opening =
                boundaryColumn(gray, width, leftStaff, left - leftStaff.gap() * .53f, true, false);
        if (opening < 0) return false;
        if (strictWideQuarter
                && (ClosedHeadBarlineGuard.attached(
                                gray,
                                width,
                                height,
                                opening,
                                Math.round(leftStaff.top()),
                                Math.round(leftStaff.bottom()),
                                leftStaff.gap())
                        || DottedDyadMeasureSeparator.continuesBeyondStaff(
                                gray,
                                width,
                                height,
                                opening,
                                Math.round(leftStaff.top()),
                                Math.round(leftStaff.bottom()),
                                leftStaff.gap()))) return false;
        var rightRails = PrintedDirectionStaff.printedRules(
                gray, width, height, right - gap * 2.5f, hint, gap);
        if (rightRails == null) return false;
        var rightStaff = new PlayingTechniqueDetector.Staff(
                rightRails[0] - 4 * rightRails[1], rightRails[0], rightRails[1], 0, 1);
        // Extra width requires an ordinary dyad between an independent opening rule
        // and two full closing strokes on the same uninterrupted five rails.
        if (strictWideQuarter)
            return Math.abs(leftStaff.bottom() - rightStaff.bottom()) <= gap * .28f
                    && Math.abs(leftStaff.gap() - rightStaff.gap()) <= gap * .10f
                    && completeCadence(
                            gray,
                            width,
                            rightStaff,
                            left,
                            right,
                            first,
                            last,
                            right,
                            false,
                            false,
                            false);
        boolean hasAccidental = lower.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                || upper.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
        if (completeCadence(gray, width, rightStaff, left, right, first, last,
                right, false, true, hasAccidental)) return true;
        float outer = right + gap * .35f;
        return completeCadence(gray, width, rightStaff, left, outer, first, last,
                outer, false, true, hasAccidental);
    }

    private static boolean ordinaryWideQuarter(ScoreNoteEvent note) {
        return note.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && note.harmonicSemitones() == 0
                && note.openStringTouch() == null
                && note.octaveShift() == 0
                && !note.compactOpening();
    }

    static boolean closedAfterFit(byte[] gray, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> chord, float expansion) {
        if (gray == null || gray.length != (long) width * height) return false;
        float left = bar.left() * width, right = bar.right() * width;
        float span = right - left, hint = (bar.bottom() - bar.top()) * height / 8.2f;
        if (hint < 8 || hint > 28 || span < hint * 3.5f || span > hint * 8f) return false;
        var rules = PrintedDirectionStaff.printedRules(
                gray, width, height, right - 2.6f * hint, bar.bottom() * height - 2.1f * hint, hint);
        if (rules == null) return false;
        float gap = rules[1], top = rules[0] - 4 * gap;
        if (top < 0 || rules[0] >= height) return false;
        var staff = new PlayingTechniqueDetector.Staff(top, rules[0], gap, 0, 1);
        double first = chord.stream().mapToDouble(ScoreNoteEvent::positionInMeasure).min().orElseThrow();
        double last = chord.stream().mapToDouble(ScoreNoteEvent::positionInMeasure).max().orElseThrow();
        if (first < .15 || last > .8 || (last - first) * span > gap * .4) return false;
        int opening = boundaryColumn(gray, width, staff, left - gap * .53f, true, false);
        if (opening < 0 || ClosedHeadBarlineGuard.attached(
                gray, width, height, opening, Math.round(top), Math.round(rules[0]), gap)
                || DottedDyadMeasureSeparator.continuesBeyondStaff(
                gray, width, height, opening, Math.round(top), Math.round(rules[0]), gap))
            return false;
        boolean accidental = chord.stream().anyMatch(
                note -> note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY);
        float outer = right + gap * expansion;
        return completeCadence(gray, width, staff, left, outer, left + first * span,
                left + last * span, outer, false, true, accidental);
    }

    private static boolean completeCadence(
            byte[] gray, int width, PlayingTechniqueDetector.Staff staff,
            float left, float right, double first, double last,
            float boundary, boolean nextHasMeter, boolean terminal, boolean hasAccidental) {
        int stroke = boundaryColumn(gray, width, staff, boundary, nextHasMeter, terminal);
        // A thick terminal bar may be bounded by its right edge in the measure geometry.
        // Start the exclusion at the first proved stroke, before counting any interior ink.
        return stroke >= 0 && emptyOutsideChord(gray, width, staff, left,
                Math.min(right, stroke + staff.gap() * .45f), first, last, hasAccidental);
    }

    static boolean emptyOutsideChord(
            byte[] gray, int width, PlayingTechniqueDetector.Staff staff,
            float left, float right, double first, double last, boolean hasAccidental) {
        return emptyOutsideChord(gray, width, staff, left, right, first, last, hasAccidental, null);
    }

    static boolean emptyOutsideChord(
            byte[] gray, int width, PlayingTechniqueDetector.Staff staff,
            float left, float right, double first, double last, boolean hasAccidental,
            LegacyTripletDotInk.Core printedDot) {
        float gap = staff.gap();
        for (int x = Math.max(0, Math.round(left + gap * .75f));
                x < Math.min(width, Math.round(right - gap * .75f)); x++) {
            double accidentalReach = hasAccidental ? 1.5 : 1.2;
            if (x >= first - gap * accidentalReach && x <= last + gap * 1.2) continue;
            int ink = 0;
            for (int y = Math.round(staff.top()); y <= Math.round(staff.bottom()); y++) {
                if (printedDot != null && printedDot.contains(x, y)) continue;
                if (betweenRules(y, staff) && (gray[y * width + x] & 255) < 155) ink++;
            }
            if (ink >= gap * .55f) return false;
        }
        return true;
    }

    private static boolean betweenRules(int y, PlayingTechniqueDetector.Staff staff) {
        float row = (y - staff.top()) / staff.gap();
        return Math.abs(row - Math.round(row)) > .17f;
    }

    static int boundaryColumn(
            byte[] gray, int width, PlayingTechniqueDetector.Staff staff,
            float boundary, boolean nextHasMeter, boolean terminal) {
        float gap = staff.gap();
        int left = Math.max(0, Math.round(boundary - gap));
        int right = Math.min(width - 1, Math.round(boundary + gap));
        var darkness = new float[right - left + 1];
        var strokes = new boolean[darkness.length];
        var interrupted = new boolean[darkness.length];
        for (int x = left; x <= right; x++) {
            int samples = 0, sum = 0;
            int spaces = 0;
            for (int space = 0; space < 4; space++) {
                int local = 0, dark = 0;
                for (int y = Math.round(staff.top() + (space + .2f) * gap);
                        y <= Math.round(staff.top() + (space + .8f) * gap); y++) {
                    int value = gray[y * width + x] & 255;
                    sum += value;
                    local++;
                    if (value < 155) dark++;
                }
                samples += local;
                if (dark >= local * .7f) spaces |= 1 << space;
            }
            boolean crossesStaff = spaces == 0b1111;
            darkness[x - left] = sum / (float) samples;
            if (nextHasMeter && crossesStaff && Math.abs(x - boundary) <= gap * .65f) return x;
            strokes[x - left] = crossesStaff && darkness[x - left] < 95;
            interrupted[x - left] = terminal && (spaces == 0b1101 || spaces == 0b1011)
                    && darkness[x - left] < 95;
        }
        // Two thin rules can touch at the staff lines. Compare the raw space profile
        // for a lighter valley separating both full-height strokes.
        for (int a = 0; a < darkness.length; a++) {
            if (!strokes[a] && !interrupted[a]) continue;
            for (int b = a + Math.max(3, Math.round(gap * .24f));
                    b < darkness.length && b - a <= gap * .85f; b++) {
                if (!(strokes[a] && (strokes[b] || interrupted[b]) || interrupted[a] && strokes[b])
                        || Math.abs(left + (a + b) * .5f - boundary) > gap * .65f) continue;
                for (int x = a + 1; x < b; x++)
                    if (darkness[x] - Math.max(darkness[a], darkness[b]) >= 24) return left + a;
            }
        }
        return -1;
    }
}
