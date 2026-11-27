// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A printed tap caption can establish unpitched ownership for complete slash rhythms. */
final class PrintedTapRhythm {
    private record Attack(int x, int beams, boolean dotted) {}

    private PrintedTapRhythm() {}

    static List<ScoreNoteEvent> recover(
            List<ScoreNoteEvent> notes, List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs, List<MeasureRegion> measures,
            List<ScoreMeterChange> meters, byte[] gray, int width, int height) {
        if (words.isEmpty() || staffs.isEmpty() || meters.isEmpty() || gray == null
                || gray.length != (long) width * height) return notes;
        var captions = words.stream().filter(word -> OpeningPartTempo.valid(word)
                && word.text().strip().equalsIgnoreCase("tap rhythm")
                && OpeningPartTempo.printedWord(word, gray, width, height)).toList();
        if (captions.isEmpty()) return notes;
        var result = new ArrayList<>(notes);
        boolean changed = false;
        for (var word : captions) {
            var owner = staffs.stream().filter(staff -> staff.count() >= 2
                    && word.top() * height - staff.bottom() >= staff.gap() * .25f
                    && word.bottom() * height - staff.bottom() <= staff.gap() * 5f)
                    .min(Comparator.comparingDouble(staff -> word.top() * height - staff.bottom()))
                    .orElse(null);
            if (owner == null) continue;
            boolean started = false;
            for (int m = 0; m < measures.size(); m++) {
                var measure = measures.get(m);
                if (!started && (!inMeasure(owner, measure, height)
                        || Math.abs((word.left() - measure.left()) * width) > owner.gap() * 1.25f)) continue;
                started = true;
                var staff = staffs.stream().filter(row -> row.index() == owner.index()
                        && row.count() == owner.count() && inMeasure(row, measure, height))
                        .findFirst().orElse(null);
                if (staff == null) break;
                var attacks = attacks(gray, width, height, measure, staff);
                float total = 0;
                int beamed = 0;
                for (var attack : attacks) {
                    total += attack.beams() == 2 ? .25f : attack.beams() == 1 ? .5f : 1.5f;
                    if (attack.beams() > 0) beamed++;
                }
                // Every slash must occupy a complete written bar before replacing pitched candidates.
                if (beamed < 6 || Math.abs(total - barBeats(meters, m)) > .01f) break;
                int measureIndex = m;
                var replaced = result.stream().filter(note -> note.measureIndex() == measureIndex
                        && note.staffIndex() == staff.index()).toList();
                if (replaced.isEmpty()) break;
                result.removeAll(replaced);
                float span = measure.right() - measure.left();
                int clef = replaced.get(0).clefBottomDiatonic();
                for (var attack : attacks) {
                    float x = (attack.x() - staff.gap() * .70f) / width;
                    float position = Math.max(0, Math.min(1, (x - measure.left()) / span));
                    var previous = replaced.stream().min(Comparator.comparingDouble(
                            n -> Math.abs(n.positionInMeasure() - position))).orElseThrow();
                    int articulation = Math.abs(previous.positionInMeasure() - position) * span * width
                            <= staff.gap() * .45f ? previous.articulations() : 0;
                    result.add(new ScoreNoteEvent(m, position, 4, staff.index(), staff.count(),
                            staff.top() / height + 2 * staff.gap() / height, false,
                            attack.dotted() ? 1 : 0, attack.beams(), ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                            attack.beams() == 0 ? 1 : 0, 1, 0, articulation, clef, false,
                            0, false, 0, 0, 1, 1).withKind(ScoreNoteEvent.Kind.UNPITCHED));
                }
                changed = true;
            }
        }
        if (!changed) return notes;
        result.sort(Comparator.comparingInt(ScoreNoteEvent::measureIndex)
                .thenComparingDouble(ScoreNoteEvent::positionInMeasure));
        return List.copyOf(result);
    }

    private static boolean inMeasure(PlayingTechniqueDetector.Staff staff, MeasureRegion measure, int height) {
        return staff.top() >= measure.top() * height - staff.gap()
                && staff.bottom() <= measure.bottom() * height + staff.gap();
    }

    private static float barBeats(List<ScoreMeterChange> meters, int measure) {
        float beats = -1;
        for (var meter : meters)
            if (meter.measureIndex() <= measure && meter.denominator() > 0)
                beats = meter.numerator() * 4f / meter.denominator();
        return beats;
    }

    private static List<Attack> attacks(byte[] gray, int width, int height, MeasureRegion measure,
            PlayingTechniqueDetector.Staff staff) {
        float gap = staff.gap();
        if (gap < 8 || gap > 55) return List.of();
        int left = Math.max(Math.round(3 * gap), Math.round(width * measure.left()));
        int right = Math.min(width - Math.round(3 * gap), Math.round(width * measure.right()));
        int top = Math.round(staff.top());
        if (top < 3 * gap || top + 4 * gap >= height) return List.of();
        var result = new ArrayList<Attack>();
        int first = -1;
        for (int x = left; x <= right; x++) {
            int op = Math.round(top - .55f * gap), end = Math.round(top + 1.14f * gap);
            int covered = 0;
            for (int y = op; y <= end; y++) if (dark(gray, width, x, y)) covered++;
            if (x < right && covered >= (end - op + 1) * .86f) {
                if (first < 0) first = x;
                continue;
            }
            if (first < 0) continue;
            int shaft = (first + x - 1) / 2;
            if (x - first <= gap * .42f && slash(gray, width, shaft, top, gap)) {
                int beams = beams(gray, width, shaft, top, gap);
                boolean dotted = beams == 0 && dot(gray, width, shaft, top, gap);
                if (beams == 1 || beams == 2 || dotted) result.add(new Attack(shaft, beams, dotted));
                else return List.of();
            }
            first = -1;
        }
        return result;
    }

    private static boolean slash(byte[] gray, int width, int x, int top, float gap) {
        float[] offsets = {1.35f, 1.65f, 2.30f, 2.60f};
        float[] leads = {.27f, .58f, 1.25f, 1.58f};
        int clear = 0;
        for (int i = 0; i < offsets.length; i++) {
            int y = Math.round(top + offsets[i] * gap);
            int center = Math.round(x - leads[i] * gap);
            if (!dark(gray, width, center, y)) return false;
            if (!dark(gray, width, Math.round(center - .42f * gap), y)
                    && !dark(gray, width, Math.round(center + .42f * gap), y)) clear++;
        }
        return clear >= 3;
    }

    private static int beams(byte[] gray, int width, int x, int top, float gap) {
        int rails = 0, count = 0, length = Math.round(.8f * gap);
        for (int y = Math.round(top - 2.65f * gap); y <= Math.round(top - .16f * gap); y++) {
            int left = 0, right = 0;
            for (int d = 2; d <= length; d++) {
                if (dark(gray, width, x - d, y)) left++;
                if (dark(gray, width, x + d, y)) right++;
            }
            if (Math.max(left, right) >= length - 2) count++;
            else {
                if (count >= Math.max(2, Math.round(.15f * gap))) rails++;
                count = 0;
            }
        }
        return rails + (count >= Math.max(2, Math.round(.15f * gap)) ? 1 : 0);
    }

    private static boolean dot(byte[] gray, int width, int x, int top, float gap) {
        int left = Math.round(x + .45f * gap), right = Math.round(x + .9f * gap);
        int upper = Math.round(top + 1.45f * gap), lower = Math.round(top + 1.78f * gap);
        int fill = 0;
        for (int y = upper; y <= lower; y++)
            for (int at = left; at <= right; at++) if (dark(gray, width, at, y)) fill++;
        return fill >= Math.max(4, Math.round(gap * gap * .05f));
    }

    private static boolean dark(byte[] gray, int width, int x, int y) {
        return (gray[y * width + x] & 255) < 125;
    }
}
