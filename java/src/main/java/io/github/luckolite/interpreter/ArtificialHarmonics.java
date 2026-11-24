// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;

/** A stopped note plus a hollow diamond a fourth above sounds two octaves higher. */
final class ArtificialHarmonics {
    private ArtificialHarmonics() {}

    record TouchHead(ScoreNoteEvent stop, float x, float y, float gap) {}

    record BeamedTouch(TouchHead glyph, int touchDiatonic, int accidental) {}
    record ThirdPitch(int writtenMidi, int touchMidi, int soundingMidi) {}

    static ScorePageInterpretation applyThirdPitches(
            byte[] labels, byte[] gray, int width, int height, ScorePageInterpretation score) {
        var touches = thirdTouches(labels, gray, width, height, score);
        var accidentals =
                OmrScoreInterpreter.harmonicTouchAccidentals(labels, gray, width, height, score, touches);
        if (accidentals.isEmpty()) return score;
        var notes = new ArrayList<>(score.notes());
        boolean changed = false;
        for (int i = 0; i < notes.size(); i++) {
            var stop = notes.get(i);
            Integer accidental = accidentals.get(stop);
            if (accidental == null) continue;
            int step = stop.diatonicPitchIdentity();
            if (accidental == ScoreNoteEvent.ACCIDENTAL_FROM_KEY)
                accidental = carriedTouchAccidental(score, stop, step + 2, accidentals);
            if (provedMajorThird(score.keyChanges(), stop, accidental)) {
                notes.set(i, stop.withHarmonicSemitones(28));
                changed = true;
            }
        }
        if (!changed) return score;
        var owners = java.util.stream.IntStream.range(0, notes.size()).boxed().toList();
        return score.withNotes(notes, owners).withClockContextSource(score.clockContextSource());
    }

    static Map<ScoreNoteEvent, ThirdPitch> resolveThirdPitches(
            byte[] labels, byte[] gray, int width, int height, ScorePageInterpretation score,
            int initialKey) {
        var touches = thirdTouches(labels, gray, width, height, score);
        var accidentals =
                OmrScoreInterpreter.harmonicTouchAccidentals(labels, gray, width, height, score, touches);
        var result = new IdentityHashMap<ScoreNoteEvent, ThirdPitch>();
        for (var touch : touches) {
            var stop = touch.stop();
            Integer accidental = accidentals.get(stop);
            if (accidental == null) continue;
            int step = stop.diatonicPitchIdentity(), key = initialKey;
            for (var change : score.keyChanges())
                if (change.measureIndex() <= stop.measureIndex()) key = change.fifths();
            if (accidental == ScoreNoteEvent.ACCIDENTAL_FROM_KEY)
                accidental = carriedTouchAccidental(score, stop, step + 2, accidentals);
            int written = ScoreTiePitchGuard.writtenMidi(step, stop.writtenAccidental(), key);
            int touched = ScoreTiePitchGuard.writtenMidi(step + 2, accidental, key);
            if (touched - written == 4 && written + 28 <= 127)
                result.put(stop, new ThirdPitch(written, touched, written + 28));
        }
        return result;
    }

    private static boolean provedMajorThird(
            List<ScoreKeyChange> keys, ScoreNoteEvent stop, int touchAccidental) {
        ScoreKeyChange local = null;
        for (var key : keys)
            if (key.measureIndex() <= stop.measureIndex()
                    && (local == null || key.measureIndex() >= local.measureIndex())) local = key;
        if (local != null) return isMajorThird(stop, touchAccidental, local.fifths());
        // A page may start under an inherited signature that its raster does not print.
        // Only store a resolved offset when every possible inherited signature agrees.
        for (int fifths = -7; fifths <= 7; fifths++)
            if (!isMajorThird(stop, touchAccidental, fifths)) return false;
        return true;
    }

    private static boolean isMajorThird(ScoreNoteEvent stop, int touchAccidental, int fifths) {
        int step = stop.diatonicPitchIdentity();
        int written = ScoreTiePitchGuard.writtenMidi(step, stop.writtenAccidental(), fifths);
        int touch = ScoreTiePitchGuard.writtenMidi(step + 2, touchAccidental, fifths);
        return touch - written == 4 && written + 28 <= 127;
    }

    private static int carriedTouchAccidental(
            ScorePageInterpretation score, ScoreNoteEvent stop, int step,
            Map<ScoreNoteEvent, Integer> touchAccidentals) {
        float closest = -1;
        int accidental = ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
        for (var prior : score.notes()) {
            float position = prior.positionInMeasure();
            if (prior.measureIndex() != stop.measureIndex() || prior.staffIndex() != stop.staffIndex()
                    || position <= closest || position >= stop.positionInMeasure() - .005f
                    || prior.kind() != ScoreNoteEvent.Kind.PITCHED) continue;
            int written = prior.diatonicPitchIdentity();
            int mark = written == step ? prior.writtenAccidental()
                    : written + 2 == step ? touchAccidentals.getOrDefault(
                            prior, ScoreNoteEvent.ACCIDENTAL_FROM_KEY)
                    : ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
            if (mark != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) {
                closest = position;
                accidental = mark;
            }
        }
        return accidental;
    }

    static List<TouchHead> thirdTouches(
            byte[] labels, byte[] gray, int width, int height, ScorePageInterpretation score) {
        if (score.notes().isEmpty()) return List.of();
        var byMeasure = new HashMap<Integer, List<ScoreNoteEvent>>();
        for (var n : score.notes())
            byMeasure.computeIfAbsent(n.measureIndex(), unused -> new ArrayList<>()).add(n);
        var staffs = OmrScoreInterpreter.techniqueStaffs(labels, gray, width, height, score.measures());
        var result = new ArrayList<TouchHead>();
        for (var stop : score.notes()) {
            if (stop.kind() != ScoreNoteEvent.Kind.PITCHED || stop.octaveShift() != 0
                    || stop.clefBottomDiatonic() == ScoreNoteEvent.CLEF_UNKNOWN
                    || stop.stemDirection() == 0 || stop.beamCount() != 0
                    || stop.unbeamedDurationBeats() != ScoreNoteEvent.DURATION_QUARTER
                    || stop.tiedFromPrevious() || stop.boundaryTies() != 0) continue;
            var region = score.measures().get(stop.measureIndex());
            float span = (region.right() - region.left()) * width;
            float x = region.left() * width + stop.positionInMeasure() * span;
            float y = stop.pageY() * height;
            for (var staff : staffs) {
                float gap = staff.gap();
                if (staff.index() != stop.staffIndex() || staff.count() != stop.staffCount()
                        || staff.top() / height < region.top() - .02f
                        || staff.bottom() / height > region.bottom() + .02f) continue;
                boolean partner = false, rival = false;
                for (var other : byMeasure.get(stop.measureIndex())) {
                    if (other == stop || other.staffIndex() != stop.staffIndex()
                            || Math.abs(other.positionInMeasure() - stop.positionInMeasure()) * span
                                    > gap * .45f) continue;
                    int steps = other.staffStep() - stop.staffStep();
                    partner |= steps >= 4 && steps <= 6 && other.kind() == ScoreNoteEvent.Kind.PITCHED
                            && other.stemDirection() == stop.stemDirection();
                    rival |= steps == 2 || steps == 3;
                }
                float touchY = y - gap;
                if (partner && !rival
                        && joinedQuarterStem(gray, width, height, x, y, gap, stop.stemDirection())
                        && enclosedCounter(gray, width, height, x, touchY, gap * .7f, gap * .24f)
                        && symbolDiamond(labels, gray, width, height, x, touchY, gap)
                        && !quarterTouchDiamond(labels, gray, width, height, x, y, gap))
                    result.add(new TouchHead(stop, x, touchY, gap));
                break;
            }
        }
        return List.copyOf(result);
    }

    private static final class ProbeSigns {
        private static final int[] VALUES = {-1, 1};
    }

    private static final class DiamondSizes {
        private static final float[] VALUES = {.5f, .6f, .7f, .8f};
    }

    static List<ScoreNoteEvent> apply(
            byte[] gray,
            int w,
            int h,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            List<PlayingTechniqueDetector.Staff> staffs) {
        return apply(null, gray, w, h, measures, notes, staffs);
    }

    static List<ScoreNoteEvent> apply(
            byte[] labels,
            byte[] gray,
            int w,
            int h,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            List<PlayingTechniqueDetector.Staff> staffs) {
        return apply(labels, gray, null, w, h, measures, notes, staffs);
    }

    static List<ScoreNoteEvent> apply(
            byte[] labels,
            byte[] gray,
            byte[] original,
            int w,
            int h,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            List<PlayingTechniqueDetector.Staff> staffs) {
        return apply(labels, gray, original, w, h, measures, notes, staffs, List.of());
    }

    static List<ScoreNoteEvent> apply(
            byte[] labels,
            byte[] gray,
            byte[] original,
            int w,
            int h,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<OctaveAccidentalCarry> accidentalCarries) {
        return apply(labels, gray, original, w, h, measures, notes, staffs, accidentalCarries, new ArrayList<>());
    }

    static List<ScoreNoteEvent> apply(
            byte[] labels, byte[] gray, byte[] original, int w, int h, List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes, List<PlayingTechniqueDetector.Staff> staffs,
            List<OctaveAccidentalCarry> accidentalCarries, List<BeamedTouch> beamedTouches) {
        if (gray == null || notes.isEmpty()) return notes;
        var replacements = new HashMap<ScoreNoteEvent, ScoreNoteEvent>();
        var remove = new HashSet<ScoreNoteEvent>();
        var retainedAccidentalOwners = new HashSet<ScoreNoteEvent>();
        for (var n : notes) {
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED
                    || n.octaveShift() != 0
                    || n.measureIndex() < 0
                    || n.measureIndex() >= measures.size()) continue;
            var m = measures.get(n.measureIndex());
            float x = (m.left() + n.positionInMeasure() * (m.right() - m.left())) * w,
                    y = n.pageY() * h;
            PlayingTechniqueDetector.Staff staff = null;
            for (var s : staffs)
                if (s.index() == n.staffIndex()
                        && s.top() / h >= m.top() - .02f
                        && s.bottom() / h <= m.bottom() + .02f) {
                    staff = s;
                    break;
                }
            if (staff == null) continue;
            float gap = staff.gap();

            boolean unpitchedTouch = false;
            for (var upper : notes)
                if (upper.kind() == ScoreNoteEvent.Kind.UNPITCHED
                        && upper.measureIndex() == n.measureIndex()
                        && upper.staffIndex() == n.staffIndex()
                        && upper.staffStep() - n.staffStep() == 3
                        && Math.abs(upper.pageY() * h - (y - 1.5f * gap)) < gap * .35f
                        && Math.abs(
                                        (upper.positionInMeasure() - n.positionInMeasure())
                                                * (m.right() - m.left())
                                                * w)
                                < gap * .55f) unpitchedTouch = true;
            if (unpitchedTouch) continue;
            ScoreNoteEvent pairedTouch = null;
            for (var upper : notes)
                if (upper.kind() == ScoreNoteEvent.Kind.PITCHED
                        && upper != n
                        && upper.measureIndex() == n.measureIndex()
                        && upper.staffIndex() == n.staffIndex()
                        && upper.staffStep() - n.staffStep() == 3
                        && Math.abs(upper.pageY() * h - (y - 1.5f * gap)) < gap * .35f
                        && Math.abs(
                                        (upper.positionInMeasure() - n.positionInMeasure())
                                                * (m.right() - m.left())
                                                * w)
                                < gap * .55f) {
                    pairedTouch = upper;
                    break;
                }
            if (pairedTouch != null && original != null && original.length == gray.length
                    && (beamedTouchAlias(n, pairedTouch, notes, m, original, gray, w, h, x, y, gap)
                            || singleBeamedLedgerTouch(n, pairedTouch, m, original, gray, w, h,
                                    x, y, gap, staff.top()))) {
                replacements.put(n, n.withOctaveShift(2));
                remove.add(pairedTouch);
                if (pairedTouch.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FLAT
                        && (pairedTouch.unbeamedDurationBeats() == ScoreNoteEvent.DURATION_WHOLE
                                || pairedTouch.unbeamedDurationBeats() == ScoreNoteEvent.DURATION_QUARTER)) {
                    // The diamond still supplies an accidental to the written pitch in this bar.
                    retainedAccidentalOwners.add(pairedTouch);
                    float tx = (m.left() + pairedTouch.positionInMeasure() * (m.right() - m.left())) * w;
                    beamedTouches.add(new BeamedTouch(new TouchHead(n, tx, pairedTouch.pageY() * h, gap),
                            pairedTouch.diatonicPitchIdentity(), ScoreNoteEvent.ACCIDENTAL_FLAT));
                }
                continue;
            }
            if (pairedTouch != null && original != null && original.length == gray.length) {
                int stem = repairedDottedTouchShaft(
                        labels, original, gray, w, h, n, pairedTouch, notes, m, x, y, gap);
                if (stem != 0) {
                    replacements.put(n, n.withOctaveShift(2).withStemDirection(stem));
                    remove.add(pairedTouch);
                    float tx = (m.left() + pairedTouch.positionInMeasure() * (m.right() - m.left())) * w;
                    beamedTouches.add(new BeamedTouch(new TouchHead(n, tx, pairedTouch.pageY() * h, gap),
                            pairedTouch.diatonicPitchIdentity(), ScoreNoteEvent.ACCIDENTAL_FROM_KEY));
                    continue;
                }
            }
            boolean strictDiamond = diamond(gray, w, h, x, y - 1.5f * gap, gap);
            boolean symbolTouch =
                    pairedTouch == null
                            && n.beamCount() >= 2
                            && n.unbeamedDurationBeats() < ScoreNoteEvent.DURATION_HALF
                            && symbolDiamond(labels, gray, w, h, x, y - 1.5f * gap, gap);
            boolean joinedQuarterTouch =
                    pairedTouch == null
                            && n.beamCount() == 0
                            && n.unbeamedDurationBeats() == ScoreNoteEvent.DURATION_QUARTER
                            && n.stemDirection() != 0
                            && joinedQuarterStem(gray, w, h, x, y, gap, n.stemDirection())
                            && quarterTouchDiamond(labels, gray, w, h, x, y, gap);
            boolean joinedHalfTouch = pairedTouch == null && n.beamCount() == 0
                    && n.staffCount() == 1 && n.stemDirection() != 0
                    && n.unbeamedDurationBeats() == ScoreNoteEvent.DURATION_HALF
                    && original != null && original.length == gray.length
                    && joinedQuarterStem(gray, w, h, x, y, gap, n.stemDirection())
                    && joinedQuarterStem(original, w, h, x, y, gap, n.stemDirection())
                    && quarterTouchDiamond(labels, gray, w, h, x, y, gap)
                    && quarterTouchDiamond(labels, original, w, h, x, y, gap);
            // One printed beam is enough when a closed touch diamond is joined to
            // this stopped note by the same shaft. Do not borrow a nearby hollow head.
            boolean joinedEighthTouch =
                    n.beamCount() == 1
                            && n.unbeamedDurationBeats() < ScoreNoteEvent.DURATION_HALF
                            && n.stemDirection() != 0
                            && joinedQuarterStem(gray, w, h, x, y, gap, n.stemDirection())
                            && enclosedCounter(gray, w, h, x, y - 1.5f * gap, gap * .7f, gap * .24f)
                            && quarterTouchDiamond(labels, gray, w, h, x, y, gap);
            boolean ruleCrossedTouch =
                    !strictDiamond && !symbolTouch && !joinedQuarterTouch && !joinedEighthTouch
                            && n.stemDirection() != 0
                            && joinedQuarterStem(gray, w, h, x, y, gap, n.stemDirection())
                            && ruleCrossedDiamond(gray, w, h, x, y - 1.5f * gap, gap);
            boolean ledgerJoinedTouch = pairedTouch != null && n.beamCount() >= 2
                    && pairedTouch.beamCount() == 0
                    && pairedTouch.unbeamedDurationBeats() == ScoreNoteEvent.DURATION_HALF
                    && n.stemDirection() != 0
                    && joinedQuarterStem(gray, w, h, x, y, gap, n.stemDirection())
                    && ledgerJoinedDiamond(gray, w, h,
                            (m.left() + pairedTouch.positionInMeasure() * (m.right() - m.left())) * w,
                            pairedTouch.pageY() * h, y, gap, n.stemDirection());
            // A tied-to-shaft hollow diamond can be misread as a half-note attack.
            boolean pairedHalfEighth = n.beamCount() == 1
                    && n.unbeamedDurationBeats() == 0 && n.augmentationDots() == 0
                    && pairedTouch != null && pairedTouch.beamCount() == 0
                    && pairedTouch.unbeamedDurationBeats() == ScoreNoteEvent.DURATION_HALF
                    && pairedTouch.stemDirection() == n.stemDirection();
            ScoreNoteEvent shaftTouch = (n.beamCount() >= 2 || pairedHalfEighth)
                    && n.stemDirection() != 0
                    ? shaftPairedFourth(gray, w, h, x, y, gap, n, m, notes) : null;
            if (pairedHalfEighth && shaftTouch != pairedTouch) shaftTouch = null;
            boolean shaftPairedTouch = shaftTouch != null;
            // Raw shared-shaft evidence also applies to a single printed beam. For an
            // unlabelled touch, require a full stopped eighth and keep the optical proof.
            boolean rawTouch = pairedTouch == null && shaftTouch == null && !strictDiamond
                    && !symbolTouch && !ruleCrossedTouch
                    && (n.beamCount() >= 2
                            || n.beamCount() == 1 && n.unbeamedDurationBeats() == 0
                                    && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                                    && !n.crossStaffBeam()
                                    && labelledTouchRegion(labels, gray, w, h, x, y - 1.5f * gap, gap))
                    && n.stemDirection() != 0 && unlabelledShaftTouch(
                            gray, w, h, x, y, gap, n, m, notes);

            boolean ledgerFourth = (pairedTouch == null
                            || pairedTouch.beamCount() == n.beamCount()
                                    && pairedTouch.stemDirection() == n.stemDirection())
                    && !strictDiamond && !symbolTouch && !ruleCrossedTouch && !rawTouch && n.staffCount() == 1
                    && n.beamCount() == 2 && n.unbeamedDurationBeats() == 0
                    && n.stemDirection() == -1 && !n.crossStaffBeam()
                    && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                    && (n.articulations() & NoteOrnament.GRACE) == 0
                    && y - 1.5f * gap < staff.top() - gap * .4f
                    && beamedLedgerFourth(labels, gray, original, w, h, x, y, gap);

            ScoreNoteEvent quarterTouch = pairedTouch;
            if (quarterTouch == null && n.beamCount() == 0 && n.unbeamedDurationBeats() == 1) {
                for (var possible : notes) {
                    if (possible.kind() != ScoreNoteEvent.Kind.PITCHED
                            || possible.measureIndex() != n.measureIndex() || possible.staffIndex() != n.staffIndex()
                            || possible.staffStep() - n.staffStep() != 3
                            || Math.abs(possible.pageY() * h - (y - gap * 1.5f)) > gap * .5f
                            || Math.abs(possible.positionInMeasure() - n.positionInMeasure())
                                    * (m.right() - m.left()) * w > gap * .55f) continue;
                    quarterTouch = possible;
                    break;
                }
            }
            if (quarterTouch != null && n.staffCount() == 1 && n.stemDirection() != 0
                    && isolatedQuarterTouch(labels, gray, w, h, measures, notes, n,
                            quarterTouch, m, staff, x, y, gap)) {
                replacements.put(n, n.withOctaveShift(2));
                remove.add(quarterTouch);
                continue;
            }

            if (!strictDiamond
                    && !symbolTouch
                    && !joinedQuarterTouch
                    && !joinedHalfTouch
                    && !joinedEighthTouch
                    && !ruleCrossedTouch
                    && !ledgerJoinedTouch
                    && !shaftPairedTouch
                    && !rawTouch
                    && !ledgerFourth
                    && !(pairedTouch != null
                            && n.beamCount() >= 2
                            && ((pairedTouch.beamCount() == 0
                                            && pairedTouch.unbeamedDurationBeats()
                                                    >= ScoreNoteEvent.DURATION_HALF)
                                    || pairedTouch.beamCount() >= 2)
                            && diamond(gray, w, h, x, y - 1.5f * gap, gap, true))) continue;
            // Require the stopped stem, or a paired beamed lower note when a crowded touch
            // diamond interrupts that stem and the decoded upper fourth is a spurious half note.
            if (!joinedQuarterTouch
                    && !joinedHalfTouch
                    && !joinedEighthTouch
                    && !ruleCrossedTouch
                    && !ledgerJoinedTouch
                    && !shaftPairedTouch
                    && !rawTouch
                    && !ledgerFourth
                    && !stem(
                            gray,
                            w,
                            h,
                            x,
                            y,
                            gap,
                            n.unbeamedDurationBeats() < ScoreNoteEvent.DURATION_HALF)
                    && !(symbolTouch && paleJoinedStem(gray, w, h, x, y, gap))
                    && !(pairedTouch != null
                            && n.beamCount() >= 2
                            && !strictDiamond
                            && pairedTouch.beamCount() == 0
                            && pairedTouch.unbeamedDurationBeats() >= ScoreNoteEvent.DURATION_HALF))
                continue;
            replacements.put(n, n.withOctaveShift(2));
            if (shaftPairedTouch) remove.add(shaftTouch);
            for (var upper : notes)
                if (upper.kind() == ScoreNoteEvent.Kind.PITCHED
                        && upper != n
                        && upper.measureIndex() == n.measureIndex()
                        && upper.staffIndex() == n.staffIndex()
                        && upper.staffStep() - n.staffStep() == 3
                        && Math.abs(upper.pageY() * h - (y - 1.5f * gap)) < gap * .35f
                        && Math.abs(
                                        (upper.positionInMeasure() - n.positionInMeasure())
                                                * (m.right() - m.left())
                                                * w)
                                < gap * .55f) remove.add(upper);
        }
        if (replacements.isEmpty()) return notes;
        var result = new ArrayList<ScoreNoteEvent>();
        var removedAccidentalOwners = new HashSet<>(remove);
        removedAccidentalOwners.removeAll(retainedAccidentalOwners);
        OctaveAccidentalCarry.restoreStoppedPitches(notes, accidentalCarries, removedAccidentalOwners, replacements);
        for (var n : notes) if (!remove.contains(n)) result.add(replacements.getOrDefault(n, n));
        return List.copyOf(result);
    }

    /** Confirm a ledger-filled fourth on the stopped head's own downstem in both rasters. */
    static boolean beamedLedgerFourth(byte[] labels, byte[] gray, byte[] source, int width, int height,
            float x, float y, float gap) {
        if (labels == null || source == null || source.length != gray.length
                || labels.length != gray.length || gap < 10 || gap > 26) return false;
        var budget = new PrintedBeamShafts.Budget(150_000);
        var normalized = PrintedBeamShafts.at(gray, width, height, x, y, gap, budget);
        var printed = PrintedBeamShafts.at(source, width, height, x, y, gap, budget);
        if (budget.refused() || normalized.size() != 1 || printed.size() != 1) return false;
        var a = normalized.get(0);
        var b = printed.get(0);
        if (a.direction() != 1 || b.direction() != 1) return false;
        float touch = y - gap * 1.5f;
        int dx = Math.round(gap * .24f);
        int dy = Math.round(gap * .25f);
        for (int col = Math.max(a.left(), b.left()); col <= Math.min(a.right(), b.right()); col++) {
            int from = Math.round(touch + gap * .22f), through = Math.round(y - gap * .32f);
            if (!sharedLedgerTouchShaft(gray, width, col, from, through)
                    || !sharedLedgerTouchShaft(source, width, col, from, through)) continue;
            for (int oy = -dy; oy <= dy; oy++)
                for (int ox = -dx; ox <= dx; ox++)
                    if (LedgerCrossedHarmonicDiamond.proved(labels, gray, width, height,
                            x + ox, touch + oy, gap, col, -1)
                            && LedgerCrossedHarmonicDiamond.proved(labels, source, width, height,
                                    x + ox, touch + oy, gap, col, -1))
                        return true;
        }
        return false;
    }

    private static boolean sharedLedgerTouchShaft(byte[] ink, int width, int col, int from, int through) {
        int missing = 0;
        boolean interrupted = false;
        for (int row = from; row <= through; row++) {
            int center = row * width + col;
            boolean paper = (ink[center - 1] & 255) >= 170 && (ink[center] & 255) >= 170
                    && (ink[center + 1] & 255) >= 170;
            if (paper && (interrupted || ++missing > 2)) return false;
            interrupted = paper;
        }
        return true;
    }

    /** A ledger or a shared shaft can leave a hollow fourth in the whole-head detector. */
    private static boolean beamedTouchAlias(ScoreNoteEvent stop, ScoreNoteEvent touch,
            List<ScoreNoteEvent> notes, MeasureRegion measure, byte[] original, byte[] gray,
            int width, int height, float x, float y, float gap) {
        boolean flatTouch = touch.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FLAT;
        boolean quarterAlias = flatTouch && touch.unbeamedDurationBeats() == ScoreNoteEvent.DURATION_QUARTER
                && !touch.tiedFromPrevious() && touch.articulations() == 0;
        boolean sameTenuto = flatTouch && (touch.articulations() == NoteArticulation.TENUTO)
                && (stop.articulations() & NoteArticulation.TENUTO) != 0;
        if (gap < 10 || gap > 25 || stop.staffIndex() != 0 || stop.staffCount() != 1
                || touch.staffCount() != 1 || stop.clefBottomDiatonic() != touch.clefBottomDiatonic()
                || stop.beamCount() != 2 || stop.stemDirection() == 0 || stop.unbeamedDurationBeats() != 0
                || !ordinaryIsolatedHarmonicHead(stop)
                || stop.harmonicSemitones() != 0 || stop.openStringTouch() != null
                || stop.visualSize() != ScoreNoteEvent.VisualSize.FULL
                || touch.unbeamedDurationBeats() != ScoreNoteEvent.DURATION_WHOLE && !quarterAlias
                || touch.visualSize() != ScoreNoteEvent.VisualSize.FULL
                || touch.stemDirection() != 0 || touch.beamCount() != 0
                || !flatTouch && touch.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                || touch.augmentationDots() != 0 || touch.tupletDivisor() != 1
                || !sameTenuto && touch.articulations() != 0 || touch.boundaryTies() != 0
                || touch.followingRestBeats() != 0 || touch.leadingRestBeats() != 0 || touch.crossStaffBeam()
                || touch.octaveShift() != 0 || touch.harmonicSemitones() != 0 || touch.openStringTouch() != null)
            return false;
        float tx = (measure.left() + touch.positionInMeasure() * (measure.right() - measure.left())) * width;
        float ty = touch.pageY() * height;
        for (var other : notes)
            if (other != stop && other != touch && other.kind() == ScoreNoteEvent.Kind.PITCHED
                    && other.measureIndex() == stop.measureIndex() && other.staffIndex() == stop.staffIndex()
                    && Math.abs(other.positionInMeasure() - touch.positionInMeasure())
                            * (measure.right() - measure.left()) * width <= gap * .48f
                    && other.pageY() * height >= ty - gap * .30f
                    && other.pageY() * height <= y + gap * .30f) return false;
        int direction = stop.stemDirection();
        if (flatTouch) {
            int printed = beamedFlatTouchShaft(original, width, height, x, y, tx, ty, gap, direction, quarterAlias);
            int decoded = beamedFlatTouchShaft(gray, width, height, x, y, tx, ty, gap, direction, quarterAlias);
            return printed >= 0 && decoded >= 0 && Math.abs(printed - decoded) <= 1;
        }
        return sameBeamedDiamondShaft(original, width, height, x, y, tx, ty, gap, direction, true)
                && sameBeamedDiamondShaft(gray, width, height, x, y, tx, ty, gap, direction, true);
    }

    /**
     * A dotted stopped sixteenth can lose its shaft to a raised diamond. Require the filled
     * base, enclosed diamond and joining double beam independently in both source planes.
     */
    private static int repairedDottedTouchShaft(byte[] labels, byte[] original, byte[] normalized,
            int width, int height, ScoreNoteEvent stop, ScoreNoteEvent touch,
            List<ScoreNoteEvent> notes, MeasureRegion measure, float x, float y, float gap) {
        if (gap < 10 || gap > 25 || stop.staffCount() != 1 || touch.staffCount() != 1
                || stop.clefBottomDiatonic() != touch.clefBottomDiatonic()
                || stop.beamCount() != 2
                || stop.unbeamedDurationBeats() != 0
                || !ordinaryIsolatedHarmonicHead(stop, 1) || !ordinaryIsolatedHarmonicHead(touch)
                || stop.visualSize() != ScoreNoteEvent.VisualSize.FULL
                || touch.visualSize() != ScoreNoteEvent.VisualSize.FULL
                || touch.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                || stop.harmonicSemitones() != 0 || touch.harmonicSemitones() != 0
                || stop.openStringTouch() != null || touch.openStringTouch() != null
                || touch.octaveShift() != 0 || touch.articulations() != 0)
            return 0;

        boolean up = touch.stemDirection() == 1 && touch.beamCount() == 0
                && touch.unbeamedDurationBeats() == ScoreNoteEvent.DURATION_HALF
                && stop.stemDirection() == 0;
        boolean down = touch.stemDirection() == 0 && touch.beamCount() == 2
                && touch.unbeamedDurationBeats() == 0 && stop.stemDirection() == -1;
        if (!up && !down) return 0;
        int direction = up ? 1 : -1;

        float span = (measure.right() - measure.left()) * width;
        float tx = measure.left() * width + touch.positionInMeasure() * span;
        float ty = touch.pageY() * height;
        if (Math.abs(tx - x) > gap * .3f || y - ty < gap * 1.38f || y - ty > gap * 1.76f)
            return 0;
        ScoreNoteEvent neighbor = null;
        float distance = gap * 3.8f;
        for (var other : notes) {
            if (other == stop || other == touch || other.measureIndex() != stop.measureIndex()
                    || other.staffIndex() != stop.staffIndex()) continue;
            float dx = (stop.positionInMeasure() - other.positionInMeasure()) * span;
            if (Math.abs(dx) <= gap * .45f) return 0;
            if (other.kind() == ScoreNoteEvent.Kind.PITCHED && dx >= gap * 1.35f && dx < distance) {
                distance = dx;
                neighbor = other;
            }
        }
        if (neighbor == null || neighbor.stemDirection() != direction || neighbor.beamCount() < 2)
            return 0;
        float nx = measure.left() * width + neighbor.positionInMeasure() * span;
        float ny = neighbor.pageY() * height;
        int printed = dottedTouchColumn(labels, original, width, height, x, y, tx, ty, nx, ny, gap, direction);
        int decoded = dottedTouchColumn(labels, normalized, width, height, x, y, tx, ty, nx, ny, gap, direction);
        return printed >= 0 && decoded >= 0 && Math.abs(printed - decoded) <= 1 ? direction : 0;
    }

    private static int dottedTouchColumn(byte[] labels, byte[] ink, int width, int height,
            float x, float y, float tx, float ty, float nx, float ny, float gap, int direction) {
        if (!filledQuarterCore(ink, width, height, x, y, gap)
                || filledQuarterCore(ink, width, height, tx, ty, gap)
                || !quarterTouchDiamond(labels, ink, width, height, x, y, gap)
                || !enclosedCounter(ink, width, height, tx, ty, gap * .76f, gap * .25f)) return -1;

        var budget = new PrintedBeamShafts.Budget(130_000);
        var touchShafts = PrintedBeamShafts.at(ink, width, height, tx, ty, gap, budget);
        var priorShafts = PrintedBeamShafts.at(ink, width, height, nx, ny, gap, budget);
        if (budget.refused() || touchShafts.size() != 1 || priorShafts.size() != 1) return -1;
        var touchShaft = touchShafts.get(0);
        var prior = priorShafts.get(0);
        if (touchShaft.direction() != -direction || prior.direction() != -direction) return -1;
        var beamShaft = touchShaft;
        if (direction == -1) {
            var resumed = PrintedBeamShafts.at(ink, width, height, x, y + gap, gap, budget);
            if (budget.refused() || resumed.size() != 1) return -1;
            beamShaft = resumed.get(0);
            if (beamShaft.direction() != 1 || beamShaft.endY() < y + gap * 2.5f) return -1;
        } else if (touchShaft.endY() > ty - gap * 2.5f) return -1;

        if (!WrappedEighthTriplets.matchesBeamRails(ink, width, height, prior, beamShaft, gap, 2))
            return -1;
        for (int column = Math.max(touchShaft.left(), beamShaft.left());
                column <= Math.min(touchShaft.right(), beamShaft.right()); column++) {
            float bridgeEnd = direction > 0 ? y - gap * .1f : y + gap;
            if (shaftCrossedDiamond(ink, width, height, tx, ty, gap, column, direction, true)
                    && singleShaftDropout(ink, width, column, Math.round(ty + gap * .12f),
                            Math.round(bridgeEnd), Math.round(gap * .4f)))
                return column;
        }
        return -1;
    }

    private static boolean singleShaftDropout(byte[] ink, int width, int column,
            int first, int last, int tolerance) {
        if (first < 0 || last >= ink.length / width || first >= last
                || (ink[first * width + column] & 255) >= 155
                || (ink[last * width + column] & 255) >= 155) return false;
        int firstBlank = -1;
        for (int row = first + 1; row < last; row++) {
            if ((ink[row * width + column] & 255) < 155) continue;
            if (firstBlank < 0) firstBlank = row;
            if (row - firstBlank + 1 > tolerance) return false;
        }
        return true;
    }

    /** Start from the stopped head's shaft. A flat can shift the decoder's touch center left. */
    static boolean beamedShaftCrossedFourth(byte[] raw, byte[] normalized, int width, int height,
            float x, float y, float touchY, float gap, int direction) {
        int source = beamedFlatTouchShaft(raw, width, height, x, y, x, touchY, gap, direction, true);
        if (source < 0 || raw == normalized) return source >= 0;
        int proof = beamedFlatTouchShaft(normalized, width, height, x, y, x, touchY, gap, direction, true);
        return proof >= 0 && Math.abs(source - proof) <= 1;
    }

    private static int beamedFlatTouchShaft(byte[] ink, int width, int height,
            float x, float y, float tx, float ty, float gap, int direction, boolean quarterAlias) {
        if (Math.abs(x - tx) > .45f * gap || y - ty < 1.25f * gap || y - ty > 1.78f * gap
                || !filledQuarterCore(ink, width, height, x, y, gap)) return -1;
        var shafts = PrintedBeamShafts.at(ink, width, height, x, y, gap, new PrintedBeamShafts.Budget(70_000));
        if (shafts.size() != 1 || shafts.get(0).direction() != -direction) return -1;
        var shaft = shafts.get(0);
        float beyondHeads = direction > 0 ? ty - shaft.endY() : shaft.endY() - y;
        if (beyondHeads < 2.2f * gap || beyondHeads > 4.8f * gap) return -1;
        // A stemless quarter alias can round the extrapolated diamond outline out of the scan.
        // Keep the original staff gap for shaft admission, and retry only the contour at pixel scale.
        float pixelGap = Math.round(gap);
        for (int column = shaft.left(); column <= shaft.right(); column++)
            if (darkRun(ink, width, height, column, ty - gap * .15f, y - gap * .15f)
                    && (shaftCrossedDiamond(ink, width, height, tx, ty, gap, column, direction, true)
                            || quarterAlias && pixelGap < gap
                                    && shaftCrossedDiamond(ink, width, height, tx, ty, pixelGap,
                                            column, direction, true)))
                return column;
        return -1;
    }

    /** A ledger through a downstem diamond can leave its counter above the decoded head center. */
    private static boolean singleBeamedLedgerTouch(ScoreNoteEvent stop, ScoreNoteEvent touch,
            MeasureRegion measure, byte[] original, byte[] gray, int width, int height,
            float x, float y, float gap, float staffTop) {
        if (gap < 10 || gap > 25 || stop.staffCount() != 1 || touch.staffCount() != 1
                || stop.clefBottomDiatonic() != touch.clefBottomDiatonic()
                || stop.stemDirection() != -1 || touch.stemDirection() != -1
                || stop.beamCount() != 1 || touch.beamCount() != 1
                || stop.unbeamedDurationBeats() != 0 || touch.unbeamedDurationBeats() != 0
                || stop.visualSize() != ScoreNoteEvent.VisualSize.FULL
                || touch.visualSize() != ScoreNoteEvent.VisualSize.FULL
                || touch.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                || touch.octaveShift() != 0 || touch.harmonicSemitones() != 0 || touch.openStringTouch() != null
                || !ordinaryIsolatedHarmonicHead(stop) || !ordinaryIsolatedHarmonicHead(touch))
            return false;
        float tx = (measure.left() + touch.positionInMeasure() * (measure.right() - measure.left())) * width;
        float ty = touch.pageY() * height;
        return ty < staffTop - gap * .4f
                && sameBeamedDiamondShaft(original, width, height, x, y, tx, ty, gap, -1, false)
                && sameBeamedDiamondShaft(gray, width, height, x, y, tx, ty, gap, -1, false);
    }

    static boolean ownsRaisedPortatoDiamond(byte[] labels, byte[] ink, int width, int height,
            float x, float stoppedY, float raisedY, float gap) {
        return symbolDiamond(labels,ink,width,height,x,raisedY,gap)
                && sameBeamedDiamondShaft(ink,width,height,x,stoppedY,x,raisedY,gap,-1,true);
    }

    private static boolean sameBeamedDiamondShaft(byte[] ink, int width, int height,
            float x, float y, float tx, float ty, float gap, int direction, boolean wholeTouch) {
        if (wholeTouch && !enclosedCounter(ink, width, height, tx, ty, gap * .76f, gap * .25f))
            return false;
        var budget = new PrintedBeamShafts.Budget(100_000);
        var stopped = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
        var raised = PrintedBeamShafts.at(ink, width, height, tx, ty, gap, budget);
        if (budget.refused() || stopped.size() != 1 || raised.size() != 1) return false;
        var a = stopped.get(0);
        var b = raised.get(0);
        if (a.direction() != -direction || b.direction() != -direction
                || Math.abs(a.endY() - b.endY()) > gap * .12f) return false;
        boolean ledger = wholeTouch && ledgerJoinedDiamond(ink, width, height, tx, ty, y, gap, direction);
        int first = Math.max(a.left(), b.left()), last = Math.min(a.right(), b.right());
        for (int column = first; column <= last; column++) {
            if (!darkRun(ink, width, height, column, ty - gap * .15f, y - gap * .15f))
                continue;
            if (ledger || shaftCrossedDiamond(ink, width, height, tx, ty, gap, column, direction, wholeTouch))
                return true;
        }
        return false;
    }

    /** A stemless whole placeholder can be the raised diamond already owned by a beamed harmonic. */
    static List<ScoreNoteEvent> removeWholeTouchAliases(
            List<ScoreNoteEvent> notes,
            List<MeasureRegion> measures,
            byte[] labels,
            byte[] normalized,
            byte[] original,
            int width,
            int height) {
        if (labels == null
                || normalized == null
                || original == null
                || labels.length != (long) width * height
                || normalized.length != labels.length
                || original.length != labels.length) return notes;
        List<ScoreNoteEvent> kept = null;
        for (int i = 0; i < notes.size(); i++) {
            var touch = notes.get(i);
            if (wholeTouchAlias(
                    touch, notes, measures, labels, normalized, original, width, height)) {
                if (kept == null) kept = new ArrayList<>(notes.subList(0, i));
            } else if (kept != null) kept.add(touch);
        }
        return kept == null ? notes : List.copyOf(kept);
    }

    private static boolean wholeTouchAlias(
            ScoreNoteEvent touch,
            List<ScoreNoteEvent> notes,
            List<MeasureRegion> measures,
            byte[] labels,
            byte[] normalized,
            byte[] original,
            int width,
            int height) {
        boolean halfTouch = touch.unbeamedDurationBeats() == ScoreNoteEvent.DURATION_HALF;
        boolean downstemHalf = halfTouch && touch.stemDirection() == -1;
        if (touch.kind() != ScoreNoteEvent.Kind.PITCHED
                || touch.visualSize() != ScoreNoteEvent.VisualSize.FULL
                || touch.openStringTouch() != null
                || touch.harmonicSemitones() != 0
                || !ordinaryIsolatedHarmonicHead(touch)
                || touch.staffIndex() != 0
                || touch.staffCount() != 1
                || touch.beamCount() != 0
                || touch.stemDirection() != 0 && !downstemHalf
                || !halfTouch && touch.unbeamedDurationBeats() != ScoreNoteEvent.DURATION_WHOLE
                || touch.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                || touch.octaveShift() != 0
                || touch.articulations() != 0) return false;
        var measure = measures.get(touch.measureIndex());
        float span = (measure.right() - measure.left()) * width;
        float nominal = (measure.bottom() - measure.top()) * height / 8.2f;
        if (nominal < 10 || nominal > 25) return false;
        ScoreNoteEvent stop = null;
        for (var note : notes) {
            if (note.measureIndex() != touch.measureIndex()
                    || note.staffIndex() != touch.staffIndex()
                    || note.staffCount() != touch.staffCount()
                    || note.staffStep() >= touch.staffStep()
                    || Math.abs(note.positionInMeasure() - touch.positionInMeasure()) * span
                            > nominal * .3f
                    || Math.abs((touch.pageY() - note.pageY()) * height + nominal * 1.75f)
                            > nominal * .36f) continue;
            if (stop != null) return false;
            stop = note;
        }
        if (stop == null
                || stop.kind() != ScoreNoteEvent.Kind.PITCHED
                || stop.visualSize() != ScoreNoteEvent.VisualSize.FULL
                || stop.openStringTouch() != null
                || stop.harmonicSemitones() != 0
                || !ordinaryIsolatedHarmonicHead(stop)
                || stop.clefBottomDiatonic() != touch.clefBottomDiatonic()
                || stop.octaveShift() != 2
                || stop.stemDirection() != (halfTouch ? -1 : 1)
                || halfTouch && stop.beamCount() != (downstemHalf ? 1 : 2)
                || stop.beamCount() < 1
                || stop.beamCount() > 2
                || stop.unbeamedDurationBeats() != 0
                || touch.staffStep() - stop.staffStep() != 4) return false;
        float left = measure.left() * width;
        float x = left + stop.positionInMeasure() * span, y = stop.pageY() * height;
        float tx = left + touch.positionInMeasure() * span, ty = touch.pageY() * height;
        if (downstemHalf) {
            return filledQuarterCore(original, width, height, x, y, nominal)
                    && filledQuarterCore(normalized, width, height, x, y, nominal)
                    && symbolDiamond(labels, original, width, height, tx, ty, nominal)
                    && symbolDiamond(labels, normalized, width, height, tx, ty, nominal)
                    && sameBeamedDiamondShaft(original, width, height, x, y, tx, ty, nominal, -1, true)
                    && sameBeamedDiamondShaft(normalized, width, height, x, y, tx, ty, nominal, -1, true);
        }
        if (halfTouch) {
            return ownsBeamedHalfTouch(labels, original, width, height, x, y, tx, ty, nominal)
                    && ownsBeamedHalfTouch(labels, normalized, width, height, x, y, tx, ty, nominal);
        }
        return ownedWholeTouch(labels, original, width, height, measure, x, y, tx, ty)
                && ownedWholeTouch(labels, normalized, width, height, measure, x, y, tx, ty);
    }

    /** A stemless half-like mask can be the hollow end of a printed artificial harmonic. */
    private static boolean ownsBeamedHalfTouch(byte[] labels, byte[] gray, int width, int height,
            float x, float y, float touchX, float touchY, float gap) {
        if (!symbolDiamond(labels, gray, width, height, touchX, touchY, gap)) return false;
        var shafts = PrintedBeamShafts.at(gray, width, height, x, y, gap,
                new PrintedBeamShafts.Budget(40_000));
        if (shafts.size() != 1) return false;
        var stem = shafts.get(0);
        if (stem.direction() != 1 || stem.left() < touchX - gap * .64f
                || stem.left() > touchX - gap * .17f || stem.endY() < y + 2.5f * gap) return false;
        int first = Math.round(touchY + .15f * gap), last = Math.round(y);
        if (first < 0 || last >= height || first >= last) return false;
        for (int col = stem.left(); col <= stem.right(); col++) {
            int misses = 0;
            for (int row = first; row <= last; row++)
                if ((gray[row * width + col] & 255) >= 155) misses++;
            if (misses <= 1) return true;
        }
        return false;
    }

    private static boolean ownedWholeTouch(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            MeasureRegion measure,
            float x,
            float y,
            float tx,
            float ty) {
        var track = WrappedMeasureBoundaries.verifiedTripletStaff(gray, width, height, measure);
        if (track == null) return false;
        float gap = track.at(x)[1];
        if (!quarterTouchDiamond(labels, gray, width, height, x, y, gap)
                || !symbolDiamond(labels, gray, width, height, tx, ty, gap)
                || !joinedQuarterStem(gray, width, height, x, y, gap, 1)) return false;
        var budget = new PrintedBeamShafts.Budget(100_000);
        var heads = PrintedBeamShafts.at(gray, width, height, x, y, gap, budget);
        var touches = PrintedBeamShafts.at(gray, width, height, tx, ty, gap, budget);
        if (heads.size() != 1 || touches.size() != 1) return false;
        var a = heads.get(0);
        var b = touches.get(0);
        return a.direction() == -1
                && b.direction() == -1
                && Math.abs(a.endY() - b.endY()) <= gap * .12f
                && Math.max(a.left(), b.left()) <= Math.min(a.right(), b.right());
    }

    static boolean isolatedQuarterTouch(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            ScoreNoteEvent stopped,
            ScoreNoteEvent touch,
            MeasureRegion measure,
            PlayingTechniqueDetector.Staff staff,
            float x,
            float y,
            float gap) {
        boolean wholeLikeTouch =
                touch.beamCount() == 0
                        && touch.unbeamedDurationBeats() == ScoreNoteEvent.DURATION_WHOLE;
        if (stopped.beamCount() != 0
                || stopped.unbeamedDurationBeats() != 1
                || touch.stemDirection() != 0
                || !(wholeLikeTouch || touch.beamCount() == 1 && touch.unbeamedDurationBeats() == 0)
                || !ordinaryIsolatedHarmonicHead(stopped)
                || !ordinaryIsolatedHarmonicHead(touch)) return false;

        int index = stopped.measureIndex();
        if (index > 0 && !onSameStaff(measure, measures.get(index - 1), height, gap)
                || index == 0) return false;
        if (index + 1 < measures.size()
                && onSameStaff(measure, measures.get(index + 1), height, gap)) return false;
        if (notes.stream().filter(note -> note.measureIndex() == index).count() != 2) return false;

        float left = measure.left() * width, right = measure.right() * width;
        if (right - left < gap * 3.5f || right - left > gap * 8) return false;
        var rails = PrintedDirectionStaff.printedRules(
                gray, width, height, right - 2.6f * gap, staff.bottom(), gap);
        if (rails == null) return false;
        float localGap = rails[1];
        if (Math.abs(rails[0] - touch.pageY() * height) > localGap * .7f) return false;
        var local = new PlayingTechniqueDetector.Staff(
                rails[0] - 4 * localGap, rails[0], localGap, 0, 1);
        if (PrintedCadenceDurations.boundaryColumn(
                    gray, width, local, left - localGap * .53f, true, false) < 0
                || PrintedCadenceDurations.boundaryColumn(gray, width, local, right, false, true) < 0)
            return false;

        int direction = stopped.stemDirection();
        if (!joinedQuarterStem(gray, width, height, x, y, localGap, direction)) return false;
        float touchX = left + touch.positionInMeasure() * (right - left);
        float touchY = touch.pageY() * height;
        for (int offset = Math.round(localGap * .35f); offset <= Math.round(localGap * .8f); offset++) {
            int shaft = Math.round(x) + direction * offset;
            if (!darkRun(gray, width, height, shaft, touchY - localGap * .18f, y - localGap * .2f))
                continue;
            float tip = direction > 0 ? touchY - localGap * 2.65f : y + localGap * 1.65f;
            if (!unflaggedQuarterTip(gray, width, height, shaft, touchY, y, tip, direction, local))
                continue;
            if (wholeLikeTouch) {
                if (joinsWiderQuarterTouch(
                                gray, width, height, touchX, touchY, shaft, direction, local)
                        && symbolDiamond(labels, gray, width, height, touchX, touchY, localGap))
                    return true;
            } else if (shaftCrossedDiamond(
                    gray, width, height, touchX, touchY, localGap, shaft, direction, true))
                return true;
        }
        return false;
    }

    private static boolean joinsWiderQuarterTouch(
            byte[] gray,
            int width,
            int height,
            float x,
            float y,
            int shaft,
            int direction,
            PlayingTechniqueDetector.Staff staff) {
        float gap = staff.gap();
        float distance = (shaft - x) * direction;
        if (distance < gap * .55f || distance > gap * 1.02f) return false;
        int edge = Math.round(x + direction * gap * .55f);
        int rows = 0;
        for (int yy = Math.round(y - gap * .26f); yy <= Math.round(y + gap * .26f); yy++) {
            float phase = (staff.bottom() - yy) / gap;
            if (Math.abs(phase - Math.round(phase)) <= .23f) {
                rows = 0;
                continue;
            }
            boolean joined = true;
            for (int xx = edge; (shaft - xx) * direction >= 0; xx += direction) {
                if (!dark(gray, width, height, xx, yy)) {
                    joined = false;
                    break;
                }
            }
            rows = joined ? rows + 1 : 0;
            if (rows == 2) return true;
        }
        return false;
    }

    static ScorePageInterpretation recoverClosedQuarterTouches(ScorePageInterpretation score,
            byte[] labels, byte[] gray, byte[] source, int width, int height) {
        return recoverClosedQuarterTouches(score, labels, gray, source, width, height, null);
    }

    static ScorePageInterpretation recoverClosedQuarterTouches(ScorePageInterpretation score,
            byte[] labels, byte[] gray, byte[] source, int width, int height, Integer violinKeyFifths) {
        if (source == null || gray == null || labels == null || gray.length != (long) width * height
                || source.length != gray.length || labels.length != gray.length) return score;
        var bars = score.measures();
        var groups = new ArrayList<List<Integer>>(bars.size());
        for (int i = 0; i < bars.size(); i++) groups.add(new ArrayList<>());
        for (int i = 0; i < score.notes().size(); i++)
            groups.get(score.notes().get(i).measureIndex()).add(i);
        var removed = new java.util.BitSet();
        var replacements = new HashMap<Integer, ScoreNoteEvent>();
        for (int i = 1; i < bars.size(); i++) {
            var bar = bars.get(i);
            var notes = groups.get(i);
            if (violinKeyFifths != null && notes.size() == 15
                    && i + 1 < bars.size() && bar.cadenceQuarterBeats() == 0
                    && onSameStaff(bar, bars.get(i + 1), height, (bar.bottom() - bar.top()) * height / 8.2f)) {
                recoverPackedClosingFourth(score, notes, labels, gray, source, width, height, bar,
                        violinKeyFifths, removed, replacements);
            }
            boolean afterRun = violinKeyFifths != null && (notes.size() == 14 || notes.size() == 15);
            if ((!afterRun && (notes.size() < 2 || notes.size() > 3)) || bar.cadenceQuarterBeats() != 0
                    || i + 1 < bars.size() && bars.get(i + 1).top() <= bar.bottom()) continue;
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (gap < 8 || gap > 28 || !onSameStaff(bar, bars.get(i - 1), height, gap)) continue;
            int index = i;
            if (score.rests().stream().anyMatch(rest -> rest.measureIndex() == index)) continue;
            if (afterRun) {
                recoverFourthAfterRun(score, notes, labels, gray, source, width, height, bar, gap,
                        violinKeyFifths, removed, replacements);
                continue;
            }
            if (!notes.stream().allMatch(owner -> {
                var n = score.notes().get(owner);
                return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                        && n.staffIndex() == 0 && n.staffCount() == 1 && n.beamCount() == 0
                        && n.openStringTouch() == null && n.harmonicSemitones() == 0
                        && n.octaveShift() == 0 && ordinaryIsolatedHarmonicHead(n)
                        && score.beamSource().noteOrigins().get(owner).isEmpty();
            })) continue;
            notes.sort(java.util.Comparator.comparingDouble(owner -> score.notes().get(owner).pageY()));
            var lower = score.notes().get(notes.get(notes.size() - 1));
            if (lower.stemDirection() != 1 || lower.unbeamedDurationBeats() != 1) continue;
            if (notes.size() == 2 && i + 1 < bars.size()) {
                var touch = score.notes().get(notes.get(0));
                if (touch.unbeamedDurationBeats() != 1 || touch.stemDirection() != 1
                        || touch.staffStep() - lower.staffStep() != 3
                        || touch.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) continue;
                if (closedQuarterTouch(labels, gray, width, height, bar, lower, touch, null)
                        && closedQuarterTouch(labels, source, width, height, bar, lower, touch, null)) {
                    removed.set(notes.get(0));
                    replacements.put(notes.get(1), lower.withOctaveShift(2));
                }
            } else if (notes.size() == 3 && i == bars.size() - 1) {
                var meter = score.meterChanges().stream().filter(change -> change.measureIndex() <= index)
                        .max(java.util.Comparator.comparingInt(ScoreMeterChange::measureIndex)).orElse(null);
                if (meter == null || meter.numerator() != 2 || meter.denominator() != 4) continue;
                var upper = score.notes().get(notes.get(0));
                var touch = score.notes().get(notes.get(1));
                if (upper.unbeamedDurationBeats() != 1 || upper.stemDirection() < 0
                        || upper.staffStep() - lower.staffStep() != 4
                        || touch.unbeamedDurationBeats() != 2 || touch.stemDirection() != 0
                        || touch.staffStep() - lower.staffStep() != 2
                        || touch.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) continue;
                if (closedQuarterTouch(labels, gray, width, height, bar, lower, touch, upper)
                        && closedQuarterTouch(labels, source, width, height, bar, lower, touch, upper))
                    removed.set(notes.get(1));
            }
        }
        if (removed.isEmpty()) return score;
        var retained = new ArrayList<ScoreNoteEvent>();
        var owners = new ArrayList<Integer>();
        for (int i = 0; i < score.notes().size(); i++) {
            if (removed.get(i)) continue;
            retained.add(replacements.getOrDefault(i, score.notes().get(i)));
            owners.add(i);
        }
        return score.withNotes(retained, owners);
    }

    /** A dense sixteenth run closes with one filled quarter and a same-shaft touch diamond. */
    private static void recoverPackedClosingFourth(
            ScorePageInterpretation score, List<Integer> owners, byte[] labels, byte[] gray, byte[] raw,
            int width, int height, MeasureRegion bar, int initialKey, BitSet removed,
            Map<Integer, ScoreNoteEvent> replacements) {
        var notes = score.notes();
        owners.sort(Comparator.comparingDouble(owner -> notes.get(owner).positionInMeasure()));
        int stopOwner = owners.get(owners.size() - 1), touchOwner = owners.get(owners.size() - 2);
        var stop = notes.get(stopOwner);
        var touch = notes.get(touchOwner);
        int measure = stop.measureIndex();
        if (score.rests().stream().anyMatch(rest -> rest.measureIndex() == measure)
                || stop.stemDirection() != 1 || stop.beamCount() != 0
                || stop.unbeamedDurationBeats() != ScoreNoteEvent.DURATION_QUARTER
                || touch.stemDirection() != 1 || touch.beamCount() != 0
                || touch.unbeamedDurationBeats() != ScoreNoteEvent.DURATION_HALF
                || stop.staffStep() != -4 || touch.staffStep() != 0
                || stop.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                || touch.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                || stop.octaveShift() != 0 || touch.octaveShift() != 0
                || stop.harmonicSemitones() != 0 || touch.harmonicSemitones() != 0
                || stop.compactOpening()
                || stop.positionInMeasure() < .82f || stop.positionInMeasure() > .96f) return;
        var meter = score.meterChanges().stream().filter(change -> change.measureIndex() <= measure)
                .max(Comparator.comparingInt(ScoreMeterChange::measureIndex)).orElse(null);
        if (meter == null || meter.numerator() != 4 || meter.denominator() != 4) return;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float span = (bar.right() - bar.left()) * width;
        if (gap < 12 || gap > 24 || span < 23 * gap || span > 37 * gap) return;
        for (int owner : owners) {
            var n = notes.get(owner);
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffIndex() != 0 || n.staffCount() != 1
                    || n.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL || n.openStringTouch() != null
                    || !ordinaryIsolatedHarmonicHead(n)) return;
        }

        var run = new ArrayList<Integer>();
        int upperOwner = -1;
        for (int owner : owners.subList(0, owners.size() - 2)) {
            var n = notes.get(owner);
            if (n.beamCount() != 2 || n.unbeamedDurationBeats() != 0 || n.stemDirection() == 0) return;
            if (!run.isEmpty() && (n.positionInMeasure() - notes.get(run.get(run.size() - 1)).positionInMeasure())
                    * span < gap * .24f) {
                var lower = notes.get(run.get(run.size() - 1));
                if (upperOwner >= 0 || lower.stemDirection() != 1 || n.stemDirection() != 1
                        || n.staffStep() - lower.staffStep() != 3 || n.octaveShift() != 0
                        || n.harmonicSemitones() != 0 || n.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                        || lower.octaveShift() != 0 || lower.harmonicSemitones() != 0) return;
                upperOwner = owner;
                continue;
            }
            run.add(owner);
        }
        if (run.size() != 12 || upperOwner < 0
                || !unlinkedTouch(score, upperOwner) || !unlinkedTouch(score, touchOwner)) return;
        int fourthOwner = run.get(8);
        var fourth = notes.get(fourthOwner);
        var diamond = notes.get(upperOwner);
        if (!(diamond.positionInMeasure() > fourth.positionInMeasure()
                    && diamond.positionInMeasure() < notes.get(run.get(9)).positionInMeasure())) return;
        for (int slot = 0; slot < run.size(); slot++) {
            var n = notes.get(run.get(slot));
            if (n.stemDirection() != (slot < 8 ? -1 : 1)
                    || slot != 8 && n.octaveShift() != 2 && n.harmonicSemitones() != 24) return;
        }
        var previous = notes.get(run.get(run.size() - 1));
        if (previous.staffStep() != -2) return;
        int key = score.keyChanges().stream().filter(change -> change.measureIndex() <= measure)
                .max(Comparator.comparingInt(ScoreKeyChange::measureIndex)).map(ScoreKeyChange::fifths)
                .orElse(initialKey);
        int adjusted = stop.diatonicPitchIdentity() + 1;
        int stopped = ScoreTiePitchGuard.writtenMidi(adjusted, stop.writtenAccidental(), key);
        int touching = ScoreTiePitchGuard.writtenMidi(touch.diatonicPitchIdentity(), touch.writtenAccidental(), key);
        int fourthMidi = ScoreTiePitchGuard.writtenMidi(fourth.diatonicPitchIdentity(), fourth.writtenAccidental(), key);
        int diamondMidi = ScoreTiePitchGuard.writtenMidi(diamond.diatonicPitchIdentity(), diamond.writtenAccidental(), key);
        if (touching - stopped != 5 || diamondMidi - fourthMidi != 5) return;
        float left = bar.left() * width;
        float fx = left + fourth.positionInMeasure() * span, fy = fourth.pageY() * height;
        float dx = left + diamond.positionInMeasure() * span, dy = diamond.pageY() * height;
        int normalizedShaft = packedClosingFourthShaft(labels, gray, width, height, bar, stop, touch, previous, gap);
        int printedShaft = packedClosingFourthShaft(labels, raw, width, height, bar, stop, touch, previous, gap);
        if (normalizedShaft < 0 || printedShaft < 0 || Math.abs(normalizedShaft - printedShaft) > 2
                || !sameBeamedDiamondShaft(gray, width, height, fx, fy, dx, dy, gap, 1, false)
                || !sameBeamedDiamondShaft(raw, width, height, fx, fy, dx, dy, gap, 1, false)) return;
        removed.set(touchOwner);
        removed.set(upperOwner);
        replacements.put(fourthOwner, fourth.withHarmonicSemitones(24));
        replacements.put(stopOwner, new ScoreNoteEvent(
                measure, stop.positionInMeasure(), stop.staffStep() + 1, 0, 1,
                stop.pageY(), false, 0, 0, stop.writtenAccidental(),
                ScoreNoteEvent.DURATION_QUARTER, 1, 0, 0, stop.clefBottomDiatonic())
                .withStemDirection(1).withHarmonicSemitones(24));
    }

    private static boolean unlinkedTouch(ScorePageInterpretation score, int owner) {
        var beams = score.beamSource();
        var anchor = beams.noteOrigins().get(owner).orElse(null);
        return anchor == null || beams.groups().stream().flatMap(group -> group.columns().stream())
                .noneMatch(column -> column.members().contains(anchor))
                && beams.detachedColumns().stream().noneMatch(column -> column.members().contains(anchor));
    }

    private static int packedClosingFourthShaft(
            byte[] labels, byte[] ink, int width, int height, MeasureRegion bar,
            ScoreNoteEvent stop, ScoreNoteEvent touch, ScoreNoteEvent previous, float nominal) {
        float left = bar.left() * width, right = bar.right() * width, span = right - left;
        var rules = PrintedDirectionStaff.printedRules(
                ink, width, height, right - 2.6f * nominal, bar.bottom() * height - 2.1f * nominal, nominal);
        if (rules == null) return -1;
        float gap = rules[1], floor = rules[0];
        var staff = new PlayingTechniqueDetector.Staff(floor - 4 * gap, floor, gap, 0, 1);
        float x = left + stop.positionInMeasure() * span, y = stop.pageY() * height;
        float tx = left + touch.positionInMeasure() * span, ty = touch.pageY() * height;
        float px = left + previous.positionInMeasure() * span, py = previous.pageY() * height;
        if (x - px < 1.55f * gap || x - px > 2.65f * gap || Math.abs(x - tx) > .22f * gap
                || Math.abs(ty - floor) > .28f * gap || py - floor < 1.03f * gap || py - floor > 1.4f * gap
                || y - py < .53f * gap || y - py > .80f * gap
                || !filledQuarterCore(ink, width, height, x, y, gap)
                || !symbolDiamond(labels, ink, width, height, tx, ty, gap)
                || !ledgerAcrossHead(ink, width, px, py, gap)
                || !ledgerAcrossHead(ink, width, x, py, gap)
                || ledgerAcrossHead(ink, width, x, y, gap)) return -1;
        int boundary = PrintedCadenceDurations.boundaryColumn(ink, width, staff, right, true, false);
        if (boundary < x + 2 * gap || boundary > x + 4 * gap
                || !PrintedCadenceDurations.emptyOutsideChord(ink, width, staff, x, boundary, tx, x, false))
            return -1;
        var shafts = PrintedBeamShafts.at(ink, width, height, x, y, gap, new PrintedBeamShafts.Budget(40_000));
        if (shafts.size() != 1 || shafts.get(0).direction() != -1) return -1;
        var shaft = shafts.get(0);
        for (int column = shaft.left(); column <= shaft.right(); column++) {
            if (darkRun(ink, width, height, column, ty - gap * .18f, y - gap * .12f)
                    && shaftCrossedDiamond(ink, width, height, tx, ty, gap, column, 1, true)
                    && unflaggedQuarterTip(ink, width, height, column, ty, y, shaft.endY(), 1, staff))
                return column;
        }
        return -1;
    }

    /** The preceding C ledger continues on both sides of the stopped column, with no second ledger. */
    private static boolean ledgerAcrossHead(byte[] ink, int width, float x, float y, float gap) {
        int left = Math.round(x - gap * .62f), right = Math.round(x + gap * .80f);
        for (int row = Math.round(y - gap * .22f); row <= y + gap * .22f; row++) {
            int column = left;
            while (column <= right && dark(ink, width, ink.length / width, column, row)) column++;
            if (column > right) return true;
        }
        return false;
    }

    private static void recoverFourthAfterRun(ScorePageInterpretation score, List<Integer> owners,
            byte[] labels, byte[] gray, byte[] source, int width, int height, MeasureRegion bar,
            float nominal, int initialKey, BitSet removed, Map<Integer, ScoreNoteEvent> replacements) {
        var notes = score.notes();
        owners.sort(Comparator.comparingDouble(owner -> notes.get(owner).positionInMeasure()));
        boolean openingTouch = owners.size() == 15;
        if (openingTouch) {
            // Its shared shaft establishes the run independently of the leading printed accidental.
            if (!leadingWholeTouchIsOnBeamedShaft(score, owners, labels, gray, source, width, height, bar, nominal))
                return;
            owners = owners.subList(1, owners.size());
        }
        int touchOwner = owners.get(12), stopOwner = owners.get(13);
        var touch = notes.get(touchOwner);
        var stop = notes.get(stopOwner);
        if (stop.stemDirection() != 1 || stop.beamCount() != 0 || stop.unbeamedDurationBeats() != 1
                || touch.beamCount() != 0 || touch.staffStep() - stop.staffStep() != 3
                || !(touch.unbeamedDurationBeats() == 2 && touch.stemDirection() == 1
                        || touch.unbeamedDurationBeats() == 4 && touch.stemDirection() == 0)
                || touch.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                || touch.positionInMeasure() < .75f) return;
        var beams = score.beamSource();
        var touchAnchor = beams.noteOrigins().get(touchOwner).orElse(null);
        if (touchAnchor != null && (beams.groups().stream().flatMap(g -> g.columns().stream())
                    .anyMatch(c -> c.members().contains(touchAnchor))
                || beams.detachedColumns().stream().anyMatch(c -> c.members().contains(touchAnchor)))) return;
        float previous = -1;
        for (int slot = 0; slot < owners.size(); slot++) {
            var note = notes.get(owners.get(slot));
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.staffIndex() != 0 || note.staffCount() != 1
                    || note.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE
                    || note.visualSize() != ScoreNoteEvent.VisualSize.FULL || note.openStringTouch() != null
                    || note.harmonicSemitones() != 0 || !ordinaryIsolatedHarmonicHead(note)) return;
            if (slot < 12 && (note.beamCount() != 2 || note.unbeamedDurationBeats() != 0
                    || note.stemDirection() == 0 && !(openingTouch && slot == 11)
                    || note.octaveShift() != 2 || note.positionInMeasure() <= previous)) return;
            if (slot >= 12 && note.octaveShift() != 0) return;
            previous = note.positionInMeasure();
        }
        int measure = stop.measureIndex();
        var meter = score.meterChanges().stream().filter(change -> change.measureIndex() <= measure)
                .max(Comparator.comparingInt(ScoreMeterChange::measureIndex)).orElse(null);
        if (meter == null || meter.numerator() != 4 || meter.denominator() != 4) return;
        int key = score.keyChanges().stream().filter(change -> change.measureIndex() <= measure)
                .max(Comparator.comparingInt(ScoreKeyChange::measureIndex)).map(ScoreKeyChange::fifths)
                .orElse(initialKey);
        int written = ScoreTiePitchGuard.writtenMidi(stop.diatonicPitchIdentity(), stop.writtenAccidental(), key);
        int upper = ScoreTiePitchGuard.writtenMidi(touch.diatonicPitchIdentity(), touch.writtenAccidental(), key);
        if (upper - written != 5 || written + 24 > 127) return;

        int correctedShaft = closingFourthShaft(labels, gray, width, height, bar, stop, touch, nominal);
        int printedShaft = closingFourthShaft(labels, source, width, height, bar, stop, touch, nominal);
        if (correctedShaft < 0 || printedShaft < 0 || Math.abs(correctedShaft - printedShaft) > 2) return;
        removed.set(touchOwner);
        replacements.put(stopOwner, stop.withHarmonicSemitones(24));
    }

    private static boolean leadingWholeTouchIsOnBeamedShaft(ScorePageInterpretation score,
            List<Integer> owners, byte[] labels, byte[] gray, byte[] raw,
            int width, int height, MeasureRegion bar, float gap) {
        var touch = score.notes().get(owners.get(0));
        var stopped = score.notes().get(owners.get(1));
        float span = (bar.right() - bar.left()) * width;
        float dx = (stopped.positionInMeasure() - touch.positionInMeasure()) * span;
        float dy = (stopped.pageY() - touch.pageY()) * height;
        if (touch.kind() != ScoreNoteEvent.Kind.PITCHED || touch.visualSize() != ScoreNoteEvent.VisualSize.FULL
                || touch.staffIndex() != 0 || touch.staffCount() != 1
                || touch.clefBottomDiatonic() != stopped.clefBottomDiatonic()
                || !ordinaryIsolatedHarmonicHead(touch) || touch.openStringTouch() != null
                || touch.octaveShift() != 0 || touch.harmonicSemitones() != 0
                || touch.stemDirection() != 0 || touch.beamCount() != 0
                || touch.unbeamedDurationBeats() != ScoreNoteEvent.DURATION_WHOLE
                || stopped.stemDirection() != -1 || stopped.beamCount() != 2 || stopped.octaveShift() != 2
                || touch.positionInMeasure() > .12f || dx > gap * .22f
                || dy < gap * 1.35f || dy > gap * 1.75f || !unlinkedTouch(score, owners.get(0))) return false;

        float tx = bar.left() * width + touch.positionInMeasure() * span;
        float ty = touch.pageY() * height, x = tx + dx, y = stopped.pageY() * height;
        for (byte[] ink : List.of(gray, raw)) {
            if (!symbolDiamond(labels, ink, width, height, tx, ty, gap)
                    || !sameBeamedDiamondShaft(ink, width, height, x, y, tx, ty, gap, -1, true)) return false;
        }
        return true;
    }

    private static int closingFourthShaft(byte[] labels, byte[] ink, int width, int height,
            MeasureRegion bar, ScoreNoteEvent stop, ScoreNoteEvent touch, float nominal) {
        float left = bar.left() * width, right = bar.right() * width, span = right - left;
        var rails = PrintedDirectionStaff.printedRules(
                ink, width, height, right - 2.6f * nominal, bar.bottom() * height - 2.1f * nominal, nominal);
        if (rails == null) return -1;
        float gap = rails[1], floor = rails[0];
        float x = left + stop.positionInMeasure() * span, y = stop.pageY() * height;
        float tx = left + touch.positionInMeasure() * span, ty = touch.pageY() * height;
        if (x - tx > gap * .42f || Math.abs(y - (floor - stop.staffStep() * gap * .5f)) > gap * .25f
                || Math.abs(ty - (floor - touch.staffStep() * gap * .5f)) > gap * .25f
                || !filledQuarterCore(ink, width, height, x, y, gap)
                || !symbolDiamond(labels, ink, width, height, tx, ty, gap)) return -1;
        var staff = new PlayingTechniqueDetector.Staff(floor - 4 * gap, floor, gap, 0, 1);
        int boundary = PrintedCadenceDurations.boundaryColumn(ink, width, staff, right, false, true);
        if (boundary < x + 2 * gap || boundary > x + 8 * gap
                || !PrintedCadenceDurations.emptyOutsideChord(ink, width, staff, x, boundary, tx, x, false))
            return -1;

        for (int shaft = Math.round(x + gap * .35f); shaft <= Math.round(x + gap * .85f); shaft++) {
            if (darkRun(ink, width, height, shaft, ty - gap * .18f, y - gap * .12f)
                    && joinsWiderQuarterTouch(ink, width, height, tx, ty, shaft, 1, staff)
                    && shaftCrossedDiamond(ink, width, height, tx, ty, gap, shaft, 1, true)
                    && unflaggedQuarterTip(ink, width, height, shaft, ty, y, ty - gap * 2.65f, 1, staff))
                return shaft;
        }
        return -1;
    }

    private static boolean closedQuarterTouch(byte[] labels, byte[] gray, int width, int height,
            MeasureRegion bar, ScoreNoteEvent lower, ScoreNoteEvent touch, ScoreNoteEvent upper) {
        var stopped = upper == null ? List.of(lower) : List.of(lower, upper);
        if (!PrintedCadenceDurations.closedAfterFit(
                gray, width, height, bar, stopped, upper == null ? .35f : 0)) return false;
        float left = bar.left() * width, right = bar.right() * width, span = right - left;
        float nominal = (bar.bottom() - bar.top()) * height / 8.2f;
        var rails = PrintedDirectionStaff.printedRules(
                gray, width, height, right - 2.6f * nominal, bar.bottom() * height - 2.1f * nominal, nominal);
        if (rails == null) return false;
        float gap = rails[1];
        var staff = new PlayingTechniqueDetector.Staff(rails[0] - 4 * gap, rails[0], gap, 0, 1);
        float x = left + lower.positionInMeasure() * span, y = lower.pageY() * height;
        float tx = left + touch.positionInMeasure() * span, ty = touch.pageY() * height;
        if (Math.abs(x - tx) > gap * .40f || !filledQuarterCore(gray, width, height, x, y, gap))
            return false;
        if (upper == null) {
            if (Math.abs(y - ty - 1.5f * gap) > gap * .24f
                    || !symbolDiamond(labels, gray, width, height, tx, ty, gap)) return false;
            for (int shaft = Math.round(x + gap * .35f); shaft <= Math.round(x + gap * .85f); shaft++)
                if (darkRun(gray, width, height, shaft, ty - gap * .18f, y - gap * .22f)
                        && shaftCrossedDiamond(gray, width, height, tx, ty, gap, shaft, 1, true)
                        && unflaggedQuarterTip(
                                gray, width, height, shaft, ty, y, ty - gap * 2.65f, 1, staff))
                    return true;
            return false;
        }
        float ux = left + upper.positionInMeasure() * span, uy = upper.pageY() * height;
        float upperTouch = uy - 1.5f * gap;
        if (Math.abs(x - ux) > gap * .18f || Math.abs(y - uy - 2 * gap) > gap * .22f
                || Math.abs(y - ty - 1.25f * gap) > gap * .25f
                || !filledQuarterCore(gray, width, height, ux, uy, gap)
                || !quarterTouchDiamond(labels, gray, width, height, ux, uy, gap)
                || !enclosedCounter(gray, width, height, tx + gap * .25f, ty, gap * .68f,
                        gap * .28f, 8)) return false;
        for (int shaft = Math.round(x + gap * .35f); shaft <= Math.round(x + gap * .85f); shaft++)
            if (darkRun(gray, width, height, shaft, upperTouch - gap * .13f, y - gap * .13f)
                    && joinsWiderQuarterTouch(gray, width, height, tx, ty, shaft, 1, staff)
                    && unflaggedQuarterTip(gray, width, height, shaft, upperTouch, uy,
                            upperTouch - gap * 2.6f, 1, staff)) return true;
        return false;
    }

    static boolean filledQuarterCore(
            byte[] gray, int width, int height, float x, float y, float gap) {
        int left = Math.round(x - gap * .20f), right = Math.round(x + gap * .20f);
        int top = Math.round(y - gap * .13f), bottom = Math.round(y + gap * .13f);
        int filled = 0;
        for (int row = top; row <= bottom; row++)
            for (int col = left; col <= right; col++)
                if (dark(gray, width, height, col, row)) filled++;
        return filled >= (right - left + 1) * (bottom - top + 1) * .76f;
    }

    private static boolean ordinaryIsolatedHarmonicHead(ScoreNoteEvent note) {
        return ordinaryIsolatedHarmonicHead(note, 0);
    }

    private static boolean ordinaryIsolatedHarmonicHead(ScoreNoteEvent note, int dots) {
        return note.tupletDivisor() == 1 && note.augmentationDots() == dots && !note.crossStaffBeam()
                && !note.tiedFromPrevious() && note.boundaryTies() == 0
                && note.leadingRestBeats() == 0 && note.followingRestBeats() == 0
                && (note.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static boolean onSameStaff(MeasureRegion first, MeasureRegion second, int height, float gap) {
        return Math.abs(first.top() - second.top()) * height < gap;
    }

    static boolean unflaggedQuarterTip(
            byte[] gray,
            int width,
            int height,
            int shaft,
            float touchY,
            float stoppedY,
            float limit,
            int direction,
            PlayingTechniqueDetector.Staff staff) {
        float gap = staff.gap();
        int start = direction > 0 ? Math.round(touchY - gap * .65f)
                                 : Math.round(stoppedY + gap * .55f);
        int connected = 0;
        for (int y = start; direction > 0 ? y >= limit : y <= limit; y -= direction) {
            if (!dark(gray, width, height, shaft, y)) break;
            connected++;
            int attachedRows = 0;
            for (int dy = -1; dy <= 1; dy++) {
                float phase = (staff.bottom() - y - dy) / gap;
                int nearest = Math.round(phase);
                if (nearest >= 0 && nearest <= 4 && Math.abs(phase - nearest) < .16f) continue;
                int wideSides = 0;
                for (int side : ProbeSigns.VALUES) {
                    int dx = 1;
                    for (; dx <= Math.round(gap * .75f); dx++)
                        if (!dark(gray, width, height, shaft + side * dx, y + dy)) break;
                    if (dx > gap * .6f) wideSides++;
                }
                attachedRows += wideSides;
            }
            if (attachedRows >= 3) return false;
        }
        return connected >= Math.round(gap * .5f);
    }

    static boolean symbolDiamond(
            byte[] labels, byte[] gray, int w, int h, float x, float y, float gap) {
        return labels != null
                && labels.length == (long) w * h
                && diamondEvidence(gray, labels, w, h, x, y, gap, true);
    }

    static boolean quarterTouchDiamond(
            byte[] labels, byte[] gray, int w, int h, float x, float stoppedY, float gap) {
        float touchY = stoppedY - 1.5f * gap;
        if (symbolDiamond(labels, gray, w, h, x, touchY, gap)) return true;
        // The shaft and adjacent rails can skew the outside contour. The closed counter
        // still has to occupy the fourth's position, inside half a staff step.
        float radius = gap * .7f, tolerance = gap * .24f;
        int shift = Math.round(gap * .36f);
        float pixelCenter = (float) Math.floor(stoppedY) + .5f;
        return labels != null && labels.length == (long) w * h
                && enclosedCounter(gray, w, h, x, touchY, radius, tolerance)
                && !enclosedCounter(gray, w, h, x, stoppedY - gap, radius, tolerance)
                && (symbolDiamond(labels, gray, w, h, x, touchY + shift, gap)
                        || symbolDiamond(labels, gray, w, h, x,
                                pixelCenter - 1.5f * gap + shift, gap));
    }

    /** An unbeamed touch must share the printed shaft with the stopped quarter note. */
    static boolean joinedQuarterStem(
            byte[] gray, int w, int h, float x, float y, float gap, int direction) {
        for (int offset = Math.round(gap * .35f); offset <= Math.round(gap * .9f); offset++) {
            int column = Math.round(x) + direction * offset;
            if (!darkRun(gray, w, h, column, y - gap * 1.45f, y - gap * .2f)) continue;
            if (direction > 0
                    ? darkRun(gray, w, h, column, y - gap * 2.15f, y - gap * 1.45f)
                    : darkRun(gray, w, h, column, y + gap * .2f, y + gap * 1.15f)) return true;
        }
        return false;
    }

    private static boolean darkRun(
            byte[] gray, int w, int h, int x, float from, float to) {
        int start = Math.round(from), end = Math.round(to), ink = 0;
        for (int y = start; y <= end; y++) if (dark(gray, w, h, x, y)) ink++;
        return ink >= (end - start + 1) * .9f;
    }

    private static boolean paleJoinedStem(byte[] gray, int w, int h, float x, float y, float gap) {
        int[] paper = new int[256];
        int samples = 0;
        for (int yy = Math.max(0, Math.round(y - gap * 1.1f));
                yy <= Math.min(h - 1, Math.round(y + gap * 1.2f));
                yy++)
            for (int sign : ProbeSigns.VALUES) {
                int xx = Math.round(x + sign * gap * 1.2f);
                if (xx >= 0 && xx < w) {
                    paper[gray[yy * w + xx] & 255]++;
                    samples++;
                }
            }
        int background = 0, count = 0;
        for (; background < 255; background++) {
            count += paper[background];
            if (count >= Math.max(1, (samples * 3 + 3) / 4)) break;
        }
        int limit = Math.min(210, background - 45);
        if (samples < 8 || limit <= 165) return false;
        for (int direction : ProbeSigns.VALUES)
            for (int offset = Math.round(gap * .3f); offset <= Math.round(gap * .95f); offset++) {
                int xx = Math.round(x) - direction * offset, countStem = 0, total = 0;
                for (int distance = Math.round(gap * .2f);
                        distance <= Math.round(gap * 1.2f);
                        distance++) {
                    int yy = Math.round(y) + direction * distance;
                    total++;
                    if (ink(gray, w, h, xx, yy, limit)) countStem++;
                }
                if (total < 4 || countStem < total * .9f) continue;
                int joined = 0, span = 0, missing = 0, longestGap = 0;
                // A lower stem and touch diamond must share the same actual ink column.
                for (int yy = Math.round(y - gap * 1.05f); yy <= Math.round(y - gap * .3f); yy++) {
                    span++;
                    if (ink(gray, w, h, xx, yy, limit)) {
                        joined++;
                        missing = 0;
                    } else {
                        missing++;
                        longestGap = Math.max(longestGap, missing);
                    }
                }
                if (span >= 4
                        && joined >= span * .7f
                        && longestGap <= Math.max(1, Math.round(gap * .25f))) return true;
            }
        return false;
    }

    private static boolean ink(byte[] gray, int w, int h, int x, int y, int limit) {
        return x >= 0 && x < w && y >= 0 && y < h && (gray[y * w + x] & 255) < limit;
    }

    private static boolean stem(
            byte[] g, int w, int h, float x, float y, float gap, boolean filledStoppedHead) {
        // Both conventional stem directions occur in artificial harmonics.
        for (int direction : ProbeSigns.VALUES) {
            for (int offset = Math.round(gap * .3f); offset <= Math.round(gap * .95f); offset++) {
                // Hollow stopped heads need stronger shape evidence: ordinary open
                // fourths can otherwise resemble a diamond pair in low-resolution scans.
                if (direction > 0 && !filledStoppedHead) continue;
                int xx = Math.round(x) - direction * offset;
                int count = 0, total = 0;
                for (int distance = Math.round(gap * .2f);
                        distance <= Math.round(gap * 1.2f);
                        distance++) {
                    int yy = Math.round(y) + direction * distance;
                    total++;
                    if (dark(g, w, h, xx, yy)) count++;
                }
                if (total > 0 && count >= total * .9) {
                    if (direction < 0) return true;
                    // A down-stem also joins the touch diamond above the stopped head.
                    // A detached fingering zero must not become an artificial harmonic.
                    int joined = 0, span = 0;
                    for (int yy = Math.round(y - gap * 1.05f);
                            yy <= Math.round(y - gap * .3f);
                            yy++) {
                        span++;
                        if (dark(g, w, h, xx, yy)) joined++;
                    }
                    if (span > 0 && joined >= span * .85f) return true;
                }
            }
        }
        return false;
    }

    static boolean diamond(byte[] g, int w, int h, float x, float y, float gap) {
        return diamond(g, w, h, x, y, gap, false);
    }

    private static boolean diamond(
            byte[] g, int w, int h, float x, float y, float gap, boolean crowded) {
        return diamondEvidence(g, null, w, h, x, y, gap, crowded);
    }

    private static boolean diamondEvidence(
            byte[] g, byte[] labels, int w, int h, float x, float y, float gap, boolean crowded) {
        if (gap < 8) return false;
        int horizontal = Math.round(gap * .4f), vertical = Math.round(gap * .25f);
        int total = 16;
        int allowedMisses = total - (int) Math.ceil(total * (crowded ? .9f : .94f));
        for (int dx = -horizontal; dx <= horizontal; dx++) {
            float cx = x + dx;
            for (int dy = -vertical; dy <= vertical; dy++) {
                float cy = y + dy;
                candidate:
                for (float size : DiamondSizes.VALUES) {
                    float r = gap * size;
                    int misses = 0, symbolHits = 0;
                    for (int side = 0; side < 4; side++)
                        for (int i = 1; i <= 4; i++) {
                            float t = i / 5f, px = (1 - t) * r, py = t * r;
                            if (side == 1) {
                                px = -px;
                            }
                            if (side == 2) {
                                px = -px;
                                py = -py;
                            }
                            if (side == 3) py = -py;
                            int xx = Math.round(cx + px), yy = Math.round(cy + py);
                            int support = diamondSideSupport(g, labels, w, h, xx, yy);
                            if (support == 0 && ++misses > allowedMisses) continue candidate;
                            if (support == 2) symbolHits++;
                        }
                    int hit = total - misses;
                    // Model support belongs to the four diamond sides, not neighboring staff/stem
                    // ink.
                    if (labels != null && symbolHits < Math.max(8, (hit + 1) / 2)) continue;
                    // Side samples alone also fit a tilted oval at small staff sizes.
                    // A touch diamond has four actual vertices, including its high and low tips.
                    int centerX = Math.round(cx), centerY = Math.round(cy);
                    for (int sign : ProbeSigns.VALUES) {
                        int vx = Math.round(cx + sign * r), vy = Math.round(cy + sign * r);
                        if (!(dark(g, w, h, vx, centerY)
                                || dark(g, w, h, vx, centerY - 1)
                                || dark(g, w, h, vx, centerY + 1))) continue candidate;
                        if (!(dark(g, w, h, centerX, vy)
                                || dark(g, w, h, centerX - 1, vy)
                                || dark(g, w, h, centerX + 1, vy))) continue candidate;
                    }
                    // An enclosed paper counter proves a thick hollow outline even when
                    // its small opening occupies less of a fixed center sample rectangle.
                    if (labels != null
                            && hit == total
                            && enclosedCounter(g, w, h, cx, cy, r, r * .4f)
                            && diamondExterior(g, w, h, cx, cy, r, gap, y + 1.5f * gap))
                        return true;
                    int clear = 0, inside = 0;
                    int left = Math.round(cx - r * .25f), right = Math.round(cx + r * .25f);
                    int upper = Math.round(cy - r * .3f), lower = Math.round(cy + r * .3f);
                    int ruleLeft = Math.round(cx - r - 3), ruleRight = Math.round(cx + r + 3);
                    for (int yy = upper; yy <= lower; yy++) {
                        // Ignore a staff or ledger line crossing the hollow center.
                        if (dark(g, w, h, ruleLeft, yy) && dark(g, w, h, ruleRight, yy)) continue;
                        for (int xx = left; xx <= right; xx++) {
                            inside++;
                            if (!dark(g, w, h, xx, yy)) clear++;
                        }
                    }
                    if (inside < 4 || (!crowded && clear < inside * .65)) continue;
                    int outside = 0;
                    for (int a : ProbeSigns.VALUES)
                        for (int b : ProbeSigns.VALUES)
                            if (!dark(
                                    g,
                                    w,
                                    h,
                                    Math.round(cx + a * r * .75f),
                                    Math.round(cy + b * r * .75f))) outside++;
                    if (crowded) {
                        if ((hit == total && clear >= inside * .8)
                                || (hit >= total * .9f && clear >= inside * .6 && outside >= 3))
                            return true;
                    } else if (outside == 4) return true;
                }
            }
        }
        return false;
    }

    /** Returns no ink, raw ink, or raw ink with semantic support in the three-pixel probe. */
    private static int diamondSideSupport(
            byte[] gray, byte[] labels, int width, int height, int x, int y) {
        if (y < 0 || y >= height) return 0;
        int row = y * width;
        byte symbol = OmrMeasurePostProcessor.SYMBOL;
        if (x > 0 && x < width - 1) {
            int at = row + x;
            if ((gray[at] & 255) >= 165
                    && (gray[at - 1] & 255) >= 165
                    && (gray[at + 1] & 255) >= 165) return 0;
            return labels != null
                            && (labels[at] == symbol || labels[at - 1] == symbol || labels[at + 1] == symbol)
                    ? 2 : 1;
        }
        int from = Math.max(0, x - 1), to = Math.min(width - 1, x + 1);
        for (int column = from; column <= to; column++) {
            if ((gray[row + column] & 255) >= 165) continue;
            if (labels != null) {
                for (int labelColumn = from; labelColumn <= to; labelColumn++)
                    if (labels[row + labelColumn] == symbol) return 2;
            }
            return 1;
        }
        return 0;
    }

    /** Re-read a hollow diamond between two continuous staff rules. */
    private static boolean ruleCrossedDiamond(
            byte[] gray, int width, int height, float x, float y, float gap) {
        float radius = gap * .68f;
        if (!enclosedCounter(gray, width, height, x, y, radius, gap * .24f)
                || enclosedCounter(gray, width, height, x, y + gap * .5f, radius, gap * .24f))
            return false;
        boolean above = false, below = false;
        int centerRow = Math.round(y), maxDistance = Math.round(gap * .65f);
        for (int d = Math.round(gap * .37f); d <= maxDistance && !(above && below); d++) {
            if (!above) above = horizontalRule(gray, width, height, centerRow - d, x, radius);
            if (!below) below = horizontalRule(gray, width, height, centerRow + d, x, radius);
        }
        if (!above || !below) return false;
        int minX = Math.round(x - gap * .26f), maxX = Math.round(x + gap * .26f);
        int minY = Math.round(y - gap * .19f), maxY = Math.round(y + gap * .19f);
        for (int cy = minY; cy <= maxY; cy++)
            for (int cx = minX; cx <= maxX; cx++) {
                if (!enclosedCounter(gray, width, height, cx, cy, gap * .53f, gap * .14f)) continue;
                candidate:
                for (float size : DiamondSizes.VALUES) {
                    float r = gap * size;
                    int incompleteSides = 0;
                    for (int dx : ProbeSigns.VALUES)
                        for (int dy : ProbeSigns.VALUES) {
                            int darkSide = 0;
                            for (float fraction : new float[] {.25f, .5f, .75f}) {
                                int xx = Math.round(cx + dx * r * fraction);
                                int yy = Math.round(cy + dy * r * (1 - fraction));
                                // Rounded coordinates can fall just outside a sloped edge.
                                if (dark(gray, width, height, xx, yy)
                                        || dark(gray, width, height, xx - dx, yy)) darkSide++;
                            }
                            if (darkSide < 2 || darkSide == 2 && ++incompleteSides > 1)
                                continue candidate;
                        }
                    // Engraving against a rule or shaft can displace one corner sample.
                    // Keep the counter, exterior and shared-shaft proof for that case.
                    if (diamondExterior(gray, width, height, cx, cy, r, gap, y + 1.5f * gap))
                        return true;
                }
            }
        return false;
    }

    /** A paired hollow diamond can meet a ledger at its lower tip. */
    private static boolean ledgerJoinedDiamond(
            byte[] gray, int width, int height, float x, float y, float stoppedY,
            float gap, int direction) {
        for (int cy = Math.round(y - gap * .15f); cy <= Math.round(y + gap * .15f); cy++)
            for (int cx = Math.round(x - gap * .3f); cx <= Math.round(x + gap * .3f); cx++) {
                if (dark(gray, width, height, cx, cy)
                        || !enclosedCounter(gray, width, height, cx, cy, gap * .53f, gap * .14f))
                    continue;
                for (float size : DiamondSizes.VALUES) {
                    float radius = gap * size;
                    boolean ledger = false;
                    int lastRow = Math.round(cy + radius * 1.2f);
                    for (int row = Math.round(cy + radius * .65f); row <= lastRow && !ledger; row++)
                        ledger = horizontalRule(gray, width, height, row, cx, radius * .85f);
                    if (!ledger) continue;
                    int shaftX = Math.round(cx + direction * radius * .9f);
                    int shaftY = Math.round(cy + radius * 1.15f);
                    if (shaftY >= stoppedY - gap * .35f
                            || !dark(gray, width, height, shaftX, shaftY)) continue;
                    boolean outline = true;
                    for (int dx : ProbeSigns.VALUES)
                        for (int dy : ProbeSigns.VALUES) {
                            int outside = 0;
                            for (float fraction : new float[] {.25f, .5f, .75f}) {
                                float px = dx * radius * fraction;
                                float py = dy * radius * (1 - fraction);
                                outline &= dark(gray, width, height,
                                        Math.round(cx + px), Math.round(cy + py));
                                if (!dark(gray, width, height, Math.round(cx + px * 1.35f),
                                        Math.round(cy + py * 1.35f))) outside++;
                            }
                            if (dy < 0 && outside < 2 || dy > 0 && dx != direction && outside == 0)
                                outline = false;
                        }
                    if (outline) return true;
                }
            }
        return false;
    }

    /** Re-read a decoded upper head whose staff rule can bias its reported step. */
    private static ScoreNoteEvent shaftPairedFourth(
            byte[] gray, int width, int height, float x, float y, float gap,
            ScoreNoteEvent stopped, MeasureRegion measure, List<ScoreNoteEvent> notes) {
        ScoreNoteEvent found = null;
        int direction = stopped.stemDirection();
        for (ScoreNoteEvent upper : notes) {
            int steps = upper.staffStep() - stopped.staffStep();
            if (upper.kind() != ScoreNoteEvent.Kind.PITCHED
                    || upper.measureIndex() != stopped.measureIndex()
                    || upper.staffIndex() != stopped.staffIndex()
                    || (steps != 3 && steps != 4)
                    || upper.stemDirection() == -direction
                    || upper.unbeamedDurationBeats() > ScoreNoteEvent.DURATION_HALF) continue;
            float touchX = (measure.left()
                    + upper.positionInMeasure() * (measure.right() - measure.left())) * width;
            float touchY = upper.pageY() * height;
            if (Math.abs(touchX - x) > gap * .52f
                    || Math.abs(touchY - (y - 1.5f * gap)) > gap * .5f) continue;
            for (int offset = Math.round(gap * .35f); offset <= Math.round(gap * .85f); offset++) {
                int shaft = Math.round(x) + direction * offset;
                if (!darkRun(gray, width, height, shaft, touchY - gap * .18f, y - gap * .2f))
                    continue;
                boolean stemTail = direction > 0
                        ? darkRun(gray, width, height, shaft, touchY - gap * .9f, touchY - gap * .18f)
                        : darkRun(gray, width, height, shaft, y + gap * .2f, y + gap * 1.15f);
                if (!stemTail || !shaftCrossedDiamond(gray, width, height, touchX, touchY,
                        gap, shaft, direction)) continue;
                if (found != null) return null;
                found = upper;
                break;
            }
        }
        return found;
    }

    /** Require a tall symbol body before searching for the shaft-owned touch of an eighth. */
    private static boolean labelledTouchRegion(
            byte[] labels, byte[] gray, int width, int height, float x, float y, float gap) {
        if (labels == null || labels.length != gray.length || gap < 6 || gap > 36) return false;
        int r = Math.round(gap * .65f);
        int left = Math.round(x) - r, right = Math.round(x) + r;
        int top = Math.round(y) - r, bottom = Math.round(y) + r;
        if (left < 0 || right >= width || top < 0 || bottom >= height) return false;
        int rows = 0, symbols = 0, heads = 0;
        int rowMinimum = Math.max(2, Math.round(gap * .2f));
        for (int row = top; row <= bottom; row++) {
            int supported = 0;
            for (int column = left; column <= right; column++) {
                int pixel = row * width + column;
                if ((gray[pixel] & 255) >= 185) continue;
                if (labels[pixel] == OmrMeasurePostProcessor.SYMBOL) supported++;
                if (labels[pixel] == OmrMeasurePostProcessor.NOTEHEAD) heads++;
            }
            if (supported >= rowMinimum) rows++;
            symbols += supported;
        }
        return rows >= Math.round(gap * .95f)
                && symbols >= gap * gap * .48f && symbols >= heads * 2;
    }

    private static boolean unlabelledShaftTouch(
            byte[] gray, int width, int height, float x, float y, float gap, ScoreNoteEvent note,
            MeasureRegion measure, List<ScoreNoteEvent> notes) {
        if (filledInnerChordHead(gray, width, height, x, y, gap, note, measure, notes)) return false;
        int direction = note.stemDirection();
        for (int offset = Math.round(gap * .4f); offset <= Math.round(gap * .8f); offset++) {
            int shaft = Math.round(x) + direction * offset;
            if (!darkRun(gray, width, height, shaft, y - gap * 1.5f, y - gap * .2f)) continue;
            boolean tail = direction > 0
                    ? darkRun(gray, width, height, shaft, y - gap * 2.5f, y - gap * 1.5f)
                    : darkRun(gray, width, height, shaft, y + gap * .2f, y + gap * 1.4f);
            if (!tail || !shaftCrossedDiamond(gray, width, height, x, y - gap * 1.5f,
                    gap, shaft, direction, true)) continue;
            var beam = direction > 0 && note.beamCount() == 3
                    ? confirmedThirdBeam(gray, width, height, x, y, gap, note, measure, notes) : null;
            if (beam == null || shaft < beam.left() || shaft > beam.right()
                    || shaftCrossedDiamond(gray, width, height, x, y - gap * 1.5f,
                            gap, shaft, direction, true, beam.endY())) return true;
        }
        return false;
    }

    /** A filled inner third owns the contour below the proposed unlabelled touch. */
    private static boolean filledInnerChordHead(
            byte[] gray, int width, int height, float x, float y, float gap, ScoreNoteEvent note,
            MeasureRegion measure, List<ScoreNoteEvent> notes) {
        if (note.visualSize() != ScoreNoteEvent.VisualSize.FULL || note.beamCount() < 2) return false;
        for (var other : notes) {
            if (other == note || other.kind() != ScoreNoteEvent.Kind.PITCHED
                    || other.measureIndex() != note.measureIndex() || other.staffIndex() != note.staffIndex()
                    || other.staffCount() != note.staffCount() || other.staffStep() - note.staffStep() != 2
                    || other.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || other.stemDirection() != note.stemDirection() || other.beamCount() != note.beamCount()
                    || other.octaveShift() != 0 || other.harmonicSemitones() != 0
                    || other.openStringTouch() != null) continue;
            float ox = (measure.left() + other.positionInMeasure() * (measure.right() - measure.left())) * width;
            float oy = other.pageY() * height;
            if (Math.abs(ox - x) <= gap * .4f && Math.abs(y - oy - gap) <= gap * .2f
                    && filledQuarterCore(gray, width, height, ox, oy, gap)) return true;
        }
        return false;
    }

    private static PrintedBeamShafts.Shaft confirmedThirdBeam(
            byte[] gray, int width, int height, float x, float y, float gap, ScoreNoteEvent note,
            MeasureRegion measure, List<ScoreNoteEvent> notes) {
        ScoreNoteEvent previous = null;
        float previousX = -1;
        for (var other : notes) {
            if (other.measureIndex() != note.measureIndex() || other.staffIndex() != note.staffIndex()
                    || other.kind() != ScoreNoteEvent.Kind.PITCHED || other.beamCount() < 2
                    || other.stemDirection() != note.stemDirection()) continue;
            float px = (measure.left() + other.positionInMeasure() * (measure.right() - measure.left())) * width;
            float distance = x - px;
            if (distance >= gap * 1.55f && distance <= gap * 3.30f && px > previousX) {
                previous = other;
                previousX = px;
            }
        }
        if (previous == null) return null;
        var budget = new PrintedBeamShafts.Budget(140_000);
        var own = PrintedBeamShafts.at(gray, width, height, x, y, gap, budget);
        var peer = PrintedBeamShafts.at(gray, width, height, previousX, previous.pageY() * height, gap, budget);
        if (budget.refused() || own.size() != 1 || peer.size() != 1) return null;
        var a = own.get(0);
        var b = peer.get(0);
        if (a.direction() != -1 || b.direction() != -1
                || !WrappedEighthTriplets.matchesBeamRails(gray, width, height, b, a, gap, 2)) return null;
        int[] terminal = {(a.left() + a.right()) / 2, a.endY(), a.direction()};
        int[] left = {(b.left() + b.right()) / 2, b.endY(), b.direction()};
        return OwnedTertiaryBeamlets.ownsThirdRailAtUpStemEnd(gray, width, height, terminal, left, gap)
                ? a : null;
    }

    /** A filled third hook at the proposed counter is the wrong owner for its paper pocket. */
    private static boolean beamOccupiesTouchCounter(
            byte[] gray, int width, int height, int cx, int cy, float gap, int shaft, int tip) {
        float depth = cy - tip;
        if (tip < 0 || depth < gap * 1.60f || depth > gap * 2.44f) return false;
        int start = cx - Math.round(gap * .12f), span = shaft - start + 1;
        int half = Math.max(2, Math.round(gap * .15f)), rows = 0;
        if (span < gap * .28f) return false;
        for (int row = cy - half; row <= cy + half; row++) {
            int solid = 0;
            for (int col = start; col <= shaft; col++) if (dark(gray, width, height, col, row)) solid++;
            if (solid >= span * .90f) rows++;
        }
        return rows >= Math.max(4, Math.round(gap * .25f));
    }

    private static boolean shaftCrossedDiamond(
            byte[] gray, int width, int height, float x, float y, float gap, int shaft,
            int direction) {
        return shaftCrossedDiamond(gray, width, height, x, y, gap, shaft, direction, false);
    }

    static boolean shaftCrossedDiamond(
            byte[] gray, int width, int height, float x, float y, float gap, int shaft,
            int direction, boolean allowSplitCounter) {
        return shaftCrossedDiamond(gray, width, height, x, y, gap, shaft, direction, allowSplitCounter, -1);
    }

    private static boolean shaftCrossedDiamond(
            byte[] gray, int width, int height, float x, float y, float gap, int shaft,
            int direction, boolean allowSplitCounter, int thirdBeamTip) {
        for (int cy = Math.round(y - gap * .32f); cy <= Math.round(y + gap * .32f); cy++)
            for (int cx = Math.round(x - gap * .4f); cx <= Math.round(x + gap * .4f); cx++) {
                int distance = (shaft - cx) * direction;
                if (distance < gap * .3f || distance > gap * .85f) continue;
                if (!enclosedCounter(gray, width, height, cx, cy, gap * .76f, gap * .25f)
                        && !(allowSplitCounter && splitDiamondCounter(gray, width, height, cx, cy, gap)))
                    continue;
                for (float size : DiamondSizes.VALUES) {
                    float radius = gap * size;
                    if (Math.abs(distance - radius) > gap * .23f) continue;
                    boolean rule = false;
                    for (int row = Math.round(cy - radius); row <= Math.round(cy + radius); row++)
                        if (horizontalRule(gray, width, height, row, cx, radius)) {
                            rule = true;
                            break;
                        }
                    if (!rule) continue;
                    int outline = 0, outside = 0, exposedSides = 0;
                    for (int dx : ProbeSigns.VALUES)
                        for (int dy : ProbeSigns.VALUES) {
                            int edge = 0, paper = 0;
                            for (float f : new float[] {.25f, .5f, .75f}) {
                                float px = dx * radius * f, py = dy * radius * (1 - f);
                                if (dark(gray, width, height, Math.round(cx + px), Math.round(cy + py)))
                                    edge++;
                                if (!dark(gray, width, height, Math.round(cx + px * 1.45f),
                                        Math.round(cy + py * 1.45f))) paper++;
                            }
                            if (edge >= 2) outline += edge;
                            if (paper > 0) exposedSides++;
                            outside += paper;
                        }
                    if (outline >= 11 && outside >= 6 && exposedSides >= 3
                            && !beamOccupiesTouchCounter(gray, width, height, cx, cy, gap, shaft, thirdBeamTip))
                        return true;
                }
            }
        return false;
    }

    private static boolean splitDiamondCounter(
            byte[] gray, int width, int height, int cx, int cy, float gap) {
        int limit = Math.round(gap * .28f);
        for (int row = cy - limit; row <= cy + limit; row++) {
            if (!horizontalRule(gray, width, height, row, cx, gap * .65f)) continue;
            if (enclosedCounter(gray, width, height, cx, row - gap * .25f, gap * .82f,
                        gap * .12f, 1)
                    && enclosedCounter(gray, width, height, cx, row + gap * .25f, gap * .82f,
                        gap * .12f, 1)) return true;
        }
        return false;
    }

    private static boolean diamondExterior(
            byte[] gray, int w, int h, float cx, float cy, float r, float gap, float stoppedY) {
        int unexplained = 0;
        boolean ownedQuadrant = false;
        for (int a : ProbeSigns.VALUES) {
            int firstX = Math.round(cx + a * r * (1 - .4f) * 1.25f);
            int secondX = Math.round(cx + a * r * (1 - .6f) * 1.25f);
            for (int b : ProbeSigns.VALUES) {
                boolean localPaper = false;
                int localOwned = 0;
                for (int probe = 0; probe < 2; probe++) {
                    float t = probe == 0 ? .4f : .6f;
                    int x = probe == 0 ? firstX : secondX;
                    int y = Math.round(cy + b * r * t * 1.25f);
                    if (!dark(gray, w, h, x, y)) {
                        localPaper = true;
                        continue;
                    }
                    if (probe == 1 && !localPaper && (ownedQuadrant || localOwned == 0))
                        return false;

                    if (horizontalRule(gray, w, h, y, cx, r)
                            || outwardStemBeforeHead(gray, w, h, x, y, cx, cy, r, gap, stoppedY)) {
                        localOwned++;
                    } else if (++unexplained > 2) {
                        return false;
                    }
                }
                if (localPaper) continue;
                // At least three quadrants must show paper at a near probe.
                if (localOwned != 2
                        || dark(gray, w, h, Math.round(cx + a * r * 1.1f),
                                Math.round(cy + b * r * 1.1f))) return false;
                ownedQuadrant = true;
            }
        }
        return true;
    }

    private static boolean horizontalRule(byte[] gray, int w, int h, int y, float cx, float r) {
        int from = Math.round(cx - r * 1.5f);
        int to = Math.round(cx + r * 1.5f);
        if (!dark(gray, w, h, from, y) || !dark(gray, w, h, to, y)) return false;
        int span = to - from + 1;
        float requiredHits = span * .9f;
        int possibleHits = span;
        int row = y * w;
        for (int pixel = row + from + 1, end = row + to; pixel < end; pixel++) {
            if ((gray[pixel] & 255) >= 165 && --possibleHits < requiredHits) return false;
        }
        int limit = Math.max(1, Math.round(r * .5f));
        return thinRuleEndpoint(gray, w, h, from, y, limit)
                && thinRuleEndpoint(gray, w, h, to, y, limit);
    }

    private static boolean thinRuleEndpoint(byte[] gray, int w, int h, int x, int y, int limit) {
        int thickness = 1;
        for (int direction : ProbeSigns.VALUES)
            for (int d = 1; d <= limit; d++) {
                if (!dark(gray, w, h, x, y + direction * d)) break;
                if (++thickness > limit) return false;
            }
        return true;
    }

    private static boolean outwardStemBeforeHead(
            byte[] gray,
            int w,
            int h,
            int x,
            int y,
            float cx,
            float cy,
            float r,
            float gap,
            float stoppedY) {
        int direction = y >= cy ? 1 : -1;
        int endpoint = y + direction * Math.round(r * 2);
        // Measure the stem in the gap between the heads, before its filled-head attachment.
        if (direction > 0) endpoint = Math.min(endpoint, Math.round(stoppedY - gap * .5f));
        int distance = direction * (endpoint - y);
        if (distance < 2) return false;
        if (direction > 0) {
            int from = Math.round(stoppedY + gap * .2f), to = Math.round(stoppedY + gap * 1.2f);
            if (!stemCoverage(gray, w, h, x, from, to)) return false;
        } else if (distance < Math.round(r * .5f)) return false;
        if (!stemCoverage(gray, w, h, x, Math.min(y, endpoint), Math.max(y, endpoint)))
            return false;
        int limit = Math.max(1, Math.round(r * .5f)), outward = x < cx ? -1 : 1;
        boolean reachesLimit = true;
        for (int d = 1; d <= limit; d++) {
            if (!dark(gray, w, h, x + outward * d, y)) {
                reachesLimit = false;
                break;
            }
        }
        if (reachesLimit) return false;

        int farThickness = 1;
        for (int side : ProbeSigns.VALUES)
            for (int d = 1; d <= limit; d++) {
                if (!dark(gray, w, h, x + side * d, endpoint)) break;
                if (++farThickness > limit) return false;
            }
        return true;
    }

    private static boolean stemCoverage(byte[] gray, int w, int h, int x, int from, int to) {
        float required = (to - from + 1) * .9f;
        if (required <= 0) return true;
        if (x < 0 || x >= w) return false;
        int first = Math.max(0, from), last = Math.min(h - 1, to);
        int possible = last - first + 1;
        if (possible < required) return false;
        for (int y = first, pixel = first * w + x; y <= last; y++, pixel += w) {
            if ((gray[pixel] & 255) >= 165 && --possible < required) return false;
        }
        return true;
    }

    static boolean enclosedCounter(
            byte[] gray, int w, int h, float cx, float cy, float r,
            float verticalTolerance) {
        return enclosedCounter(gray, w, h, cx, cy, r, verticalTolerance, 4);
    }

    private static boolean enclosedCounter(
            byte[] gray, int w, int h, float cx, float cy, float r,
            float verticalTolerance, int minArea) {
        int left = (int) Math.floor(cx - r), top = (int) Math.floor(cy - r);
        int right = (int) Math.ceil(cx + r), bottom = (int) Math.ceil(cy + r);
        if (left < 0 || top < 0 || right >= w || bottom >= h) return false;
        int width = right - left + 1, height = bottom - top + 1;
        int minimumDimension = minArea == 1 ? 3 : 4;
        if (width < minimumDimension || height < minimumDimension
                || (long) (width - 2) * (height - 2) < minArea) return false;
        int rasterBase = top * w + left;
        int needed = minArea == 1 ? 1 : 2;
        // Each occupied row and column of a closed component has a light
        // run bounded by dark pixels on both sides.
        if (!hasBoundedLightRuns(gray, rasterBase + w, height - 2, w, width, 1, needed)
                || !hasBoundedLightRuns(gray, rasterBase + 1, width - 2, 1, height, w, needed))
            return false;

        boolean[] visited = new boolean[width * height];
        int[] queue = null;
        for (int iy = 0; iy < height; iy++)
            for (int ix = 0, first = iy * width, seed = rasterBase + iy * w;
                    ix < width; ix++, first++, seed++) {
                if (visited[first]) continue;
                visited[first] = true;
                if ((gray[seed] & 255) < 165) continue;
                if (queue == null) queue = new int[visited.length];
                int head = 0, tail = 1;
                boolean boundary = ix == 0 || iy == 0 || ix == width - 1 || iy == height - 1;
                long sumX = 0, sumY = 0;
                boolean spansX = false, spansY = false;
                queue[0] = first;
                while (head < tail) {
                    int value = queue[head++], y = value / width, x = value - y * width;
                    if (!boundary) {
                        boundary = x == 0 || y == 0 || x == width - 1 || y == height - 1;
                        sumX += x;
                        sumY += y;
                        spansX |= x != ix;
                        spansY |= y != iy;
                    }
                    int pixel = rasterBase + y * w + x;
                    if (x > 0 && !visited[value - 1]) {
                        visited[value - 1] = true;
                        if ((gray[pixel - 1] & 255) >= 165) queue[tail++] = value - 1;
                    }
                    if (x < width - 1 && !visited[value + 1]) {
                        visited[value + 1] = true;
                        if ((gray[pixel + 1] & 255) >= 165) queue[tail++] = value + 1;
                    }
                    if (y > 0 && !visited[value - width]) {
                        visited[value - width] = true;
                        if ((gray[pixel - w] & 255) >= 165) queue[tail++] = value - width;
                    }
                    if (y < height - 1 && !visited[value + width]) {
                        visited[value + width] = true;
                        if ((gray[pixel + w] & 255) >= 165) queue[tail++] = value + width;
                    }
                }
                if (boundary || tail < minArea) continue;
                if ((minArea == 1 || spansX && spansY)
                        && Math.abs(left + sumX / (float) tail - cx) <= r * .4f
                        && Math.abs(top + sumY / (float) tail - cy) <= verticalTolerance) return true;
            }
        return false;
    }


    private static boolean hasBoundedLightRuns(
            byte[] gray, int start, int lines, int lineStride,
            int length, int pixelStride, int needed) {
        for (int line = 0; line < lines; line++, start += lineStride) {
            boolean dark = false, boundedStart = false;
            for (int p = 0, index = start; p < length; p++, index += pixelStride) {
                if ((gray[index] & 255) < 165) {
                    if (boundedStart) {
                        if (--needed == 0) return true;
                        break;
                    }
                    dark = true;
                } else if (dark) {
                    boundedStart = true;
                }
            }
        }
        return false;
    }

    private static boolean dark(byte[] g, int w, int h, int x, int y) {
        return x >= 0 && x < w && y >= 0 && y < h && (g[y * w + x] & 255) < 165;
    }
}
