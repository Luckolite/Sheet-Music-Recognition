// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;

/** A stopped note plus a hollow diamond a fourth above sounds two octaves higher. */
final class ArtificialHarmonics {
    private ArtificialHarmonics() {}

    record TouchHead(ScoreNoteEvent stop, float x, float y, float gap) {}
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
        if (gray == null || notes.isEmpty()) return notes;
        var replacements = new HashMap<ScoreNoteEvent, ScoreNoteEvent>();
        var remove = new HashSet<ScoreNoteEvent>();
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
            ScoreNoteEvent shaftTouch = n.beamCount() >= 2 && n.stemDirection() != 0
                    ? shaftPairedFourth(gray, w, h, x, y, gap, n, m, notes) : null;
            boolean shaftPairedTouch = shaftTouch != null;
            boolean rawTouch = pairedTouch == null && shaftTouch == null && !strictDiamond
                    && !symbolTouch && !ruleCrossedTouch && n.beamCount() >= 2
                    && n.stemDirection() != 0 && unlabelledShaftTouch(gray, w, h, x, y, gap,
                            n.stemDirection());

            if (!strictDiamond
                    && !symbolTouch
                    && !joinedQuarterTouch
                    && !joinedEighthTouch
                    && !ruleCrossedTouch
                    && !ledgerJoinedTouch
                    && !shaftPairedTouch
                    && !rawTouch
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
                    && !joinedEighthTouch
                    && !ruleCrossedTouch
                    && !ledgerJoinedTouch
                    && !shaftPairedTouch
                    && !rawTouch
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
        for (var n : notes) if (!remove.contains(n)) result.add(replacements.getOrDefault(n, n));
        return List.copyOf(result);
    }

    static boolean symbolDiamond(
            byte[] labels, byte[] gray, int w, int h, float x, float y, float gap) {
        return labels != null
                && labels.length == (long) w * h
                && diamondEvidence(gray, labels, w, h, x, y, gap, true);
    }

    private static boolean quarterTouchDiamond(
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
    private static boolean joinedQuarterStem(
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
        for (int dx = -horizontal; dx <= horizontal; dx++)
            for (int dy = -vertical; dy <= vertical; dy++)
                candidate:
                for (float size : DiamondSizes.VALUES) {
                    float cx = x + dx, cy = y + dy, r = gap * size;
                    int hit = 0, misses = 0, symbolHits = 0;
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
                            if (dark(g, w, h, xx, yy)
                                    || dark(g, w, h, xx - 1, yy)
                                    || dark(g, w, h, xx + 1, yy)) {
                                hit++;
                                if (labels != null
                                        && (symbol(labels, w, h, xx, yy)
                                                || symbol(labels, w, h, xx - 1, yy)
                                                || symbol(labels, w, h, xx + 1, yy))) symbolHits++;
                            } else if (++misses > allowedMisses) {
                                continue candidate;
                            }
                        }
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
        return false;
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

    private static boolean unlabelledShaftTouch(
            byte[] gray, int width, int height, float x, float y, float gap, int direction) {
        for (int offset = Math.round(gap * .4f); offset <= Math.round(gap * .8f); offset++) {
            int shaft = Math.round(x) + direction * offset;
            if (!darkRun(gray, width, height, shaft, y - gap * 1.5f, y - gap * .2f)) continue;
            boolean tail = direction > 0
                    ? darkRun(gray, width, height, shaft, y - gap * 2.5f, y - gap * 1.5f)
                    : darkRun(gray, width, height, shaft, y + gap * .2f, y + gap * 1.4f);
            if (tail && shaftCrossedDiamond(gray, width, height, x, y - gap * 1.5f,
                    gap, shaft, direction, true)) return true;
        }
        return false;
    }

    private static boolean shaftCrossedDiamond(
            byte[] gray, int width, int height, float x, float y, float gap, int shaft,
            int direction) {
        return shaftCrossedDiamond(gray, width, height, x, y, gap, shaft, direction, false);
    }

    private static boolean shaftCrossedDiamond(
            byte[] gray, int width, int height, float x, float y, float gap, int shaft,
            int direction, boolean allowSplitCounter) {
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
                    if (outline >= 11 && outside >= 6 && exposedSides >= 3) return true;
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

    private static boolean enclosedCounter(
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

    private static boolean symbol(byte[] labels, int w, int h, int x, int y) {
        return x >= 0
                && x < w
                && y >= 0
                && y < h
                && labels[y * w + x] == OmrMeasurePostProcessor.SYMBOL;
    }

    private static boolean dark(byte[] g, int w, int h, int x, int y) {
        return x >= 0 && x < w && y >= 0 && y < h && (g[y * w + x] & 255) < 165;
    }
}
