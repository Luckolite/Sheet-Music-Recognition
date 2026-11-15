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
        if (score.notes().isEmpty()) return score;
        var staffs = OmrScoreInterpreter.techniqueStaffs(labels, gray, width, height, score.measures());
        score = terminalHarmonicQuarter(score, labels, gray, width, height, staffs);
        return capture(score, labels, gray, width, height, staffs);
    }

    static ScorePageInterpretation capture(
            ScorePageInterpretation score, byte[] gray, int width, int height,
            List<PlayingTechniqueDetector.Staff> staffs) {
        return capture(score, null, gray, width, height, staffs);
    }

    private static ScorePageInterpretation capture(
            ScorePageInterpretation score, byte[] labels, byte[] gray, int width, int height,
            List<PlayingTechniqueDetector.Staff> staffs) {
        int count = score.measures().size();
        var notes = new ArrayList<List<ScoreNoteEvent>>(count);
        for (int i = 0; i < count; i++) notes.add(new ArrayList<>());
        for (var note : score.notes()) notes.get(note.measureIndex()).add(note);
        var rests = new boolean[count];
        for (var rest : score.rests()) rests[rest.measureIndex()] = true;
        var meter = new boolean[count];
        var repeatedTripleMeter = new boolean[count];
        boolean triple = false;
        for (var change : score.meterChanges()) {
            if (change.measureIndex() >= meter.length) continue;
            boolean nextTriple = change.numerator() == 3 && change.denominator() == 4;
            meter[change.measureIndex()] = true;
            repeatedTripleMeter[change.measureIndex()] = triple && nextTriple;
            triple = nextTriple;
        }
        var regions = new ArrayList<>(score.measures());
        boolean changed = false;
        for (int i = 0; i < count; i++) {
            var chord = notes.get(i);
            if (rests[i] || chord.isEmpty()) continue;
            float duration = chord.get(0).unbeamedDurationBeats();
            if (duration != 1 && duration != 2) continue;
            if (!chord.stream().allMatch(note -> exactCadenceNote(note, duration))) continue;
            var measure = score.measures().get(i);
            var next = i + 1 < count ? score.measures().get(i + 1) : null;
            if (!proved(
                            gray,
                            width,
                            height,
                            measure,
                            next,
                            staffs,
                            chord,
                            next != null && meter[i + 1])
                    && !compactTerminalQuarter(gray, width, height, measure, next, chord)
                    && !expandedTerminalDyad(gray, width, height, measure, next, chord)
                    && !PrintedQuarterDyadAccidentals.proves(
                            labels, gray, width, height, measure, next, chord, staffs)
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
            regions.set(i, new MeasureRegion(measure.left(), measure.right(), measure.top(),
                    measure.bottom(), (int) duration));
            changed = true;
        }
        if (!changed) return score;
        return new ScorePageInterpretation(regions, score.notes(), score.firstMeasureNumber(),
                score.keyChanges(), score.tempoChanges(), score.meterChanges(), score.rests(),
                score.techniqueChanges(), score.dynamicChanges(), score.playbackDirections(),
                score.expressiveEvents(), score.slurSource(), score.openingPickup(), score.tremoloSource(),
                score.multiRestGroups(), score.beamSource(), score.clockContextSource());
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
        var shortened = new boolean[beats.length];
        for (int i = 0; i < beats.length; i++) {
            int duration = score.measures().get(i).cadenceQuarterBeats();
            if (duration > 0 && duration < beats[i]) {
                beats[i] = duration;
                shortened[i] = true;
            }
        }
        return shortened;
    }

    private static boolean exactCadenceNote(ScoreNoteEvent note, float duration) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.staffIndex() == 0 && note.staffCount() == 1
                && note.unbeamedDurationBeats() == duration
                && note.beamCount() == 0 && note.augmentationDots() == 0
                && note.tupletDivisor() == 1 && !note.crossStaffBeam()
                && !note.tiedFromPrevious() && note.boundaryTies() == 0
                && note.leadingRestBeats() == 0 && note.followingRestBeats() == 0
                && (note.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static boolean proved(
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
            // A wide quarter cadence still needs both closing strokes.
            boolean wideQuarter = quarter && (!sameSystem || !nextHasMeter);
            if (span > gap * (wideQuarter ? 10.25f : 8f)) return false;
            float boundary = sameSystem ? (right + next.left() * width) * .5f : right;
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
        float gap = staff.gap();
        for (int x = Math.max(0, Math.round(left + gap * .75f));
                x < Math.min(width, Math.round(right - gap * .75f)); x++) {
            double accidentalReach = hasAccidental ? 1.5 : 1.2;
            if (x >= first - gap * accidentalReach && x <= last + gap * 1.2) continue;
            int ink = 0;
            for (int y = Math.round(staff.top()); y <= Math.round(staff.bottom()); y++)
                if (betweenRules(y, staff) && (gray[y * width + x] & 255) < 155) ink++;
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
