// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Remap proven key or meter previews onto the next playable boundary. */
final class VerifiedCourtesyKeyTail {
    static ScorePageInterpretation apply(
            ScorePageInterpretation s, byte[] labels, byte[] gray, int width, int height) {
        if (s == null || s.measures().size() < 3) return s;
        var staffs =
                ScoreDynamicsDetector.alignStaffs(
                        OmrScoreInterpreter.techniqueStaffs(
                                labels, gray, width, height, s.measures()),
                        s.notes(),
                        height);
        // Keep recognized signatures until their preview measures can be mapped to actual time.
        s = trimMeterTails(s, labels, gray, width, height, staffs);
        for (int cut = s.measures().size() - 2; cut >= 1; cut--) {
            final int index = cut;
            if (s.keyChanges().stream().noneMatch(k -> k.measureIndex() == index)) continue;
            if (s.notes().stream().anyMatch(n -> n.measureIndex() == index)
                    || hasTimedOwners(s, index, false)) continue;
            var r = s.measures().get(cut);
            var prev = s.measures().get(cut - 1);
            var next = s.measures().get(cut + 1);
            float rowTolerance = (r.bottom() - r.top()) * .25f;
            if (Math.abs(r.top() - prev.top()) > rowTolerance || r.left() <= prev.left())
                continue;
            boolean beforeRepeat =
                    Math.abs(r.top() - next.top()) <= rowTolerance
                            && r.cadenceQuarterBeats() == 0
                            && prev.right() <= r.left()
                            && next.left() >= r.right()
                            && next.left() - r.right() <= r.right() - r.left()
                            && printedRepeatOpening(s, cut + 1);
            if (!beforeRepeat && next.top() <= r.bottom()) continue;
            // A conflicting signature at the receiving bar is not proven to be this courtesy.
            var keys = s.keyChanges();
            boolean conflict = false;
            for (var k : keys)
                if (k.measureIndex() == cut)
                    for (var other : keys)
                        if (other.measureIndex() == cut + 1 && other.fifths() != k.fifths())
                            conflict = true;
            if (conflict) continue;
            int verified = 0, owners = 0;
            for (var staff : staffs) {
                float cy = (staff.top() + staff.bottom()) * .5f / height;
                if (cy < r.top() || cy > r.bottom()) continue;
                owners++;
                int left = Math.round(r.left() * width), right = Math.round(r.right() * width);
                int top = Math.round(staff.top());
                if (beforeRepeat
                        ? CourtesyKeyTailInk.matchesBeforeRepeat(
                                labels, gray, width, height, left, right, top, staff.gap())
                        : CourtesyKeyTailInk.matches(
                                labels, gray, width, height, left, right, top, staff.gap()))
                    verified++;
            }
            if (owners > 0 && verified == owners) s = CourtesyMeasureRemap.remove(s, cut);
        }
        return s;
    }

    private static boolean printedRepeatOpening(ScorePageInterpretation score, int boundary) {
        return score.playbackDirections().stream().anyMatch(d ->
                        d.measureBoundary() == boundary
                                && d.kind() == ScorePlaybackDirection.Kind.REPEAT_START
                                && d.details().quarterBeatOffset() == 0
                                && d.details().eventId().startsWith("printed-repeat:")
                                && !d.details().evidence().isEmpty())
                || score.expressiveEvents().stream().anyMatch(e ->
                        e.kind() == ScoreExpressiveEvent.Kind.UNRESOLVED_DIRECTION
                                && e.start().filter(a -> a.measureIndex() == boundary
                                        && a.quarterBeatOffset() == 0).isPresent()
                                && e.evidence().stream().anyMatch(p ->
                                        p.sourceId().startsWith("printed-repeat-start:v1:")));
    }

    private static ScorePageInterpretation trimMeterTails(
            ScorePageInterpretation score, byte[] labels, byte[] gray, int width, int height,
            List<PlayingTechniqueDetector.Staff> staffs) {
        if (gray == null) return score;
        for (int cut = score.measures().size() - 1; cut >= 1; cut--) {
            var r = score.measures().get(cut);
            var previous = score.measures().get(cut - 1);
            boolean hasNext = cut + 1 < score.measures().size();
            if (Math.abs(r.top() - previous.top()) > (r.bottom() - r.top()) * .25f
                    || r.left() <= previous.left()
                    || hasNext && score.measures().get(cut + 1).top() <= r.bottom()
                    || hasTimedOwners(score, cut, true)) continue;
            final int index = cut;
            var tailMeter = score.meterChanges().stream()
                    .filter(m -> m.measureIndex() == index).findFirst().orElse(null);
            if (tailMeter != null && score.meterChanges().stream().anyMatch(m ->
                    m.measureIndex() == index + 1 && (m.numerator() != tailMeter.numerator()
                            || m.denominator() != tailMeter.denominator()))) continue;
            var tailKey = score.keyChanges().stream()
                    .filter(k -> k.measureIndex() == index).findFirst().orElse(null);
            if (tailKey != null && score.keyChanges().stream().anyMatch(k ->
                    k.measureIndex() == index + 1 && k.fifths() != tailKey.fifths())) continue;
            var tailNotes = score.notes().stream().filter(n -> n.measureIndex() == index).toList();
            if (!tailNotes.isEmpty() && (tailNotes.size() != 1 || tailMeter == null)) continue;
            int owners = 0;
            boolean noteOwnedByNumeral = tailNotes.isEmpty();
            for (var staff : staffs) {
                float center = (staff.top() + staff.bottom()) * .5f / height;
                if (center < r.top() || center > r.bottom()) continue;
                float gap = staff.gap();
                int left = Math.round(r.left() * width - gap * .55f);
                // Extend past a model barline on the numerator itself to the retained staff ink.
                int right = Math.min(width - 1, Math.round(r.right() * width + gap * 2.8f));
                int top = Math.round(staff.top());
                if (!MeterOnlyCourtesyTail.matchesStackedSymbols(labels, gray, width, height,
                        left, right, top, Math.round(top + gap * 4), gap)) {
                    owners = 0;
                    break;
                }
                if (!noteOwnedByNumeral) {
                    var note = tailNotes.get(0);
                    float fraction = r.left() + note.positionInMeasure() * (r.right() - r.left());
                    int x = Math.round(fraction * width);
                    int y = Math.round(note.pageY() * height);
                    noteOwnedByNumeral = y >= top && y <= top + 4 * gap
                            && hasDigitFragment(labels, gray, width, height, x, y, gap);
                }
                owners++;
            }
            if (owners == 0 || !noteOwnedByNumeral) continue;
            if (!tailNotes.isEmpty()) {
                var kept = new ArrayList<ScoreNoteEvent>();
                var sourceOwners = new ArrayList<Integer>();
                for (int i = 0; i < score.notes().size(); i++) {
                    var note = score.notes().get(i);
                    if (note.measureIndex() == cut) continue;
                    kept.add(note);
                    sourceOwners.add(i);
                }
                score = score.withNotes(kept, sourceOwners);
            }
            score = CourtesyMeasureRemap.remove(score, cut);
        }
        return score;
    }

    private static boolean hasDigitFragment(
            byte[] labels, byte[] gray, int width, int height, int x, int y, float gap) {
        int radius = Math.round(gap * .55f);
        for (int yy = Math.max(0, y - radius); yy <= Math.min(height - 1, y + radius); yy++)
            for (int xx = Math.max(0, x - radius); xx <= Math.min(width - 1, x + radius); xx++) {
                int pixel = yy * width + xx;
                if (labels[pixel] == OmrMeasurePostProcessor.NOTEHEAD && (gray[pixel] & 255) < 180)
                    return true;
            }
        return false;
    }

    private static boolean hasTimedOwners(ScorePageInterpretation s, int index, boolean allowBoundaryEnd) {
        return s.rests().stream().anyMatch(r -> r.measureIndex() == index)
                || s.tempoChanges().stream().anyMatch(t -> t.measureIndex() == index)
                || s.techniqueChanges().stream().anyMatch(t -> t.measureIndex() == index)
                || s.dynamicChanges().stream()
                            .anyMatch(
                                    d -> d.measureIndex() == index || d.endMeasureIndex() == index)
                || s.expressiveEvents().stream()
                            .anyMatch(
                                    e ->
                                            e.start()
                                                            .filter(a -> a.measureIndex() == index)
                                                            .isPresent()
                                                || e.end()
                                                            .filter(a -> a.measureIndex() == index
                                                                    && (!allowBoundaryEnd || a.quarterBeatOffset() != 0))
                                                            .isPresent())
                || s.playbackDirections().stream()
                            .anyMatch(
                                    d ->
                                            (d.measureBoundary() == index
                                                            && d.details().quarterBeatOffset() != 0)
                                                || d.details()
                                                            .end()
                                                            .filter(
                                                                    a ->
                                                                            a.measureIndex()
                                                                                            == index
                                                                                    && a
                                                                                                    .quarterBeatOffset()
                                                                                            != 0)
                                                            .isPresent());
    }
}
