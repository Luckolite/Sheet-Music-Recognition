// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Recovers an interior rule separating two complete groups of dotted sixteenth-note dyads. */
final class DottedDyadMeasureSeparator {
    private DottedDyadMeasureSeparator() {}

    static List<MeasureRegion> recover(
            List<MeasureRegion> regions, byte[] labels, byte[] gray, int width, int height) {
        if (gray == null || labels == null || gray.length != (long) width * height
                || labels.length != gray.length) return regions;
        var result = new ArrayList<MeasureRegion>();
        for (int i = 0; i < regions.size(); i++) {
            var region = regions.get(i);
            var fragments = propose(regions, i, gray, width, height);
            if (!fragments.isEmpty()) {
                var written = OmrScoreInterpreter.analyze(labels, gray, width, height, fragments);
                if (qualifies(fragments, written.notes(), written.rests(), width, height)) {
                    result.addAll(fragments);
                    continue;
                }
            }
            result.add(region);
        }
        return result.size() == regions.size() ? regions : List.copyOf(result);
    }

    static List<MeasureRegion> propose(
            List<MeasureRegion> regions, int index, byte[] gray, int width, int height) {
        if (index < 2 || index + 1 >= regions.size()) return List.of();
        var region = regions.get(index);
        var reference = regions.get(index - 2);
        var preceding = regions.get(index - 1);
        var following = regions.get(index + 1);
        if (region.cadenceQuarterBeats() != 0
                || !sameSystem(reference, region) || !sameSystem(preceding, region)
                || !sameSystem(following, region)) return List.of();
        float left = region.left() * width, right = region.right() * width;
        float gap = (region.bottom() - region.top()) * height / 8.2f;
        float referenceSpan = (reference.right() - reference.left()) * width;
        float firstShortSpan = (preceding.right() - preceding.left()) * width;
        float secondShortSpan = (following.right() - following.left()) * width;
        if (gap < 6 || gap > 36 || referenceSpan < gap * 20 || referenceSpan > gap * 44
                || firstShortSpan < gap * 2 || firstShortSpan > gap * 6
                || secondShortSpan < gap * 2 || secondShortSpan > gap * 6
                || right - left < referenceSpan * 1.82f || right - left > referenceSpan * 2.18f)
            return List.of();
        float center = (left + right) * .5f;
        float hint = region.bottom() * height - gap * 2.1f;
        var rails = localRails(gray, width, height, Math.round(center), hint, gap);
        if (rails == null) return List.of();
        gap = rails[1];
        int top = Math.round(rails[0] - 4 * gap), bottom = Math.round(rails[0]);
        if (top < 0 || bottom >= height) return List.of();
        int cut = -1;
        for (int x = Math.round(center - gap * 2.6f); x <= Math.round(center + gap * 2.6f); x++) {
            if (!fullInk(gray, width, x, top, gap)) continue;
            int begin = x;
            while (x + 1 <= Math.round(center + gap * 2.6f) && fullInk(gray, width, x + 1, top, gap)) x++;
            if (x - begin + 1 < Math.max(3, Math.round(gap * .17f)) || x - begin + 1 > gap * .45f)
                continue;
            int axis = (begin + x) / 2;
            if (!OmrMeasurePostProcessor.verticalRuleAt(gray, width, axis, top, bottom, gap)
                    || ClosedHeadBarlineGuard.attached(gray, width, height, axis, top, bottom, gap)
                    || continuesBeyondStaff(gray, width, height, axis, top, bottom, gap)) continue;
            if (cut >= 0) return List.of();
            cut = axis;
        }
        if (cut < 0) return List.of();
        int padding = Math.max(2, Math.round(gap * .55f));
        return List.of(new MeasureRegion(region.left(), (cut - padding) / (float) width,
                            region.top(), region.bottom()),
                       new MeasureRegion((cut + padding) / (float) width, region.right(),
                            region.top(), region.bottom()));
    }

    private static boolean sameSystem(MeasureRegion a, MeasureRegion b) {
        float span = b.bottom() - b.top();
        return Math.abs(a.top() - b.top()) <= span * .25f
                && Math.abs(a.bottom() - b.bottom()) <= span * .25f;
    }

    static float[] localRails(
            byte[] gray, int width, int height, int x, float hint, float gap) {
        int inside = Math.round(gap * .3f), outside = Math.round(gap * .85f);
        int search = Math.max(2, Math.round(gap * .32f));
        if (x - outside < 0 || x + outside >= width || hint - 5 * gap < 0 || hint + gap >= height)
            return null;
        int[] found = new int[5];
        for (int line = 0; line < 5; line++) {
            int expected = Math.round(hint - (4 - line) * gap), best = 0;
            for (int y = expected - search; y <= expected + search; y++) {
                int left = 0, right = 0;
                for (int offset = inside; offset <= outside; offset++) {
                    if (thinHorizontalInk(gray, width, x - offset, y)) left++;
                    if (thinHorizontalInk(gray, width, x + offset, y)) right++;
                }
                int score = Math.min(left, right);
                if (score > best) {
                    found[line] = y;
                    best = score;
                }
            }
            if (best < (outside - inside + 1) * .6f) return null;
        }
        float measuredGap = (found[4] - found[0]) / 4f;
        for (int line = 1; line < 4; line++)
            if (Math.abs(found[line] - (found[0] + measuredGap * line)) > measuredGap * .16f)
                return null;
        return new float[]{found[4], measuredGap};
    }

    private static boolean thinHorizontalInk(byte[] gray, int width, int x, int y) {
        int tone = gray[y * width + x] & 255;
        return tone < 170 && (gray[(y - 4) * width + x] & 255) >= tone + 20
                && (gray[(y + 4) * width + x] & 255) >= tone + 20;
    }

    static boolean fullInk(byte[] gray, int width, int x, int top, float gap) {
        for (int space = 0; space < 4; space++) {
            int start = Math.round(top + (space + .22f) * gap);
            int end = Math.round(top + (space + .78f) * gap);
            int dark = 0;
            for (int y = start; y <= end; y++) if ((gray[y * width + x] & 255) < 155) dark++;
            if (dark < (end - start + 1) * .8f) return false;
        }
        return true;
    }

    static boolean continuesBeyondStaff(
            byte[] gray, int width, int height, int x, int top, int bottom, float gap) {
        int first = Math.round(gap * .32f), last = Math.round(gap * .95f);
        for (int side : new int[]{-1, 1}) {
            int edge = side < 0 ? top : bottom, dark = 0;
            for (int d = first; d <= last; d++) {
                int y = edge + side * d;
                if (y < 0 || y >= height) return true;
                if ((gray[y * width + x] & 255) < 155) dark++;
            }
            if (dark >= (last - first + 1) * .6f) return true;
        }
        return false;
    }

    static boolean qualifies(
            List<MeasureRegion> regions, List<ScoreNoteEvent> notes, List<ScoreRestEvent> rests,
            int width, int height) {
        if (regions.size() != 2 || !rests.isEmpty()) return false;
        int thirdBeamWitnesses = 0;
        for (int i = 0; i < regions.size(); i++) {
            int measure = i;
            var written = notes.stream().filter(n -> n.measureIndex() == measure)
                    .filter(DottedDyadMeasureSeparator::rhythmicHead)
                    .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
            var region = regions.get(i);
            float gap = (region.bottom() - region.top()) * height / 8.2f;
            float span = (region.right() - region.left()) * width;
            if (written.size() < 20 || written.size() > 26) return false;
            int witnesses = alternatingDyads(written, gap / span);
            if (witnesses < 2) return false;
            thirdBeamWitnesses += witnesses;
        }
        return thirdBeamWitnesses >= 6;
    }

    private static boolean rhythmicHead(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED && note.staffCount() == 1
                && note.staffIndex() == 0 && note.stemDirection() != 0 && note.beamCount() > 0
                && note.unbeamedDurationBeats() == 0 && note.tupletDivisor() == 1
                && note.augmentationDots() <= 1 && !note.crossStaffBeam()
                && (note.articulations() & NoteOrnament.GRACE) == 0;
    }

    private record Attack(float x, boolean dotted, boolean dyad, boolean shortWitness) {}

    private static int alternatingDyads(List<ScoreNoteEvent> notes, float gap) {
        var attacks = new ArrayList<Attack>();
        for (int i = 0; i < notes.size();) {
            var first = notes.get(i);
            int start = i++;
            while (i < notes.size()
                    && notes.get(i).positionInMeasure() - first.positionInMeasure() <= gap * .22f) i++;
            var group = notes.subList(start, i);
            boolean dotted = group.stream().anyMatch(n -> n.augmentationDots() == 1);
            boolean dyad = group.stream().filter(n -> (n.augmentationDots() > 0) == dotted)
                    .map(ScoreNoteEvent::staffStep).distinct().count() >= 2;
            boolean thirdBeam = group.stream().anyMatch(n -> n.beamCount() == 3);
            attacks.add(new Attack(first.positionInMeasure(), dotted, dyad, thirdBeam));
        }
        var dots = attacks.stream().filter(Attack::dotted).toList();
        if (dots.size() != 6 || attacks.get(0) != dots.get(0)
                || dots.get(0).x() > gap * 2.25f
                || attacks.get(attacks.size() - 1).x() < 1f - gap * 2.25f) return -1;
        int paired = (int) dots.stream().filter(Attack::dyad).count();
        int shortBeamWitnesses = 0, unmatched = 0;
        for (int i = 0; i < dots.size(); i++) {
            float start = dots.get(i).x(), end = i + 1 < dots.size() ? dots.get(i + 1).x() : 1;
            if (i + 1 < dots.size() && (end - start < gap * 4 || end - start > gap * 7)) return -1;
            var shorts = attacks.stream().filter(a -> !a.dotted() && a.x() > start && a.x() < end).toList();
            if (shorts.isEmpty() || shorts.size() > 2 || shorts.stream().filter(Attack::dyad).count() > 1)
                return -1;
            var shortAttack = shorts.get(shorts.size() - 1);
            if (shortAttack.x() - start < gap * 1.2f || shortAttack.x() - start > gap * 3.8f)
                return -1;
            if (shortAttack.dyad()) paired++;
            if (shortAttack.shortWitness()) shortBeamWitnesses++;
            unmatched += shorts.size() - 1;
        }
        return paired >= 9 && unmatched <= 1 ? shortBeamWitnesses : -1;
    }
}
