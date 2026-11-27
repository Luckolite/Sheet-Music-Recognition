// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Optical evidence for a single-measure repeat inside one five-line staff. */
final class PrintedMeasureRepeatGlyphs {
    private PrintedMeasureRepeatGlyphs() {}

    record Glyph(int measure, int left, int top, int right, int bottom, float gap) {
        boolean owns(float x, float y) {
            return x >= left && x < right && y >= top && y < bottom;
        }

        boolean ownsEighthRest(ScoreRestEvent rest, MeasureRegion bar, int width, int height) {
            if (rest.durationBeats() != .5 || rest.staffIndex() != 0 || rest.staffCount() != 1)
                return false;
            float x = (bar.left() + rest.positionInMeasure() * (bar.right() - bar.left())) * width;
            float y = rest.pageY() * height;
            float halfHeight = rest.pageHeight() * height * .5f;
            return owns(x, y - halfHeight) && owns(x, y + halfHeight);
        }
    }

    static List<Glyph> detect(
            byte[] gray, int width, int height, List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes) {
        if (gray == null || gray.length != (long) width * height || measures.size() < 2)
            return List.of();
        int[] attacks = new int[measures.size()];
        for (var note : notes)
            if (note.measureIndex() < attacks.length) attacks[note.measureIndex()]++;
        var result = new ArrayList<Glyph>();
        for (int i = 1; i < measures.size(); i++) {
            if (attacks[i] > 2) continue;
            var bar = measures.get(i);
            int left = Math.round(bar.left() * width), right = Math.round(bar.right() * width);
            int top = Math.round(bar.top() * height), bottom = Math.round(bar.bottom() * height);
            if (right - left < 24 || right - left > (bottom - top) * 1.4f) continue;
            var glyph = read(gray, width, height, i, left, right, top, bottom);
            if (glyph == null) continue;
            boolean hasRealNote = false;
            for (var note : notes) {
                if (note.measureIndex() != i) continue;
                float x = left + note.positionInMeasure() * (right - left);
                if (!glyph.owns(x, note.pageY() * height) || note.beamCount() != 0
                        || note.tiedFromPrevious()) {
                    hasRealNote = true;
                    break;
                }
            }
            if (!hasRealNote) result.add(glyph);
        }
        return List.copyOf(result);
    }

    static ScorePageInterpretation apply(
            ScorePageInterpretation score, byte[] gray, int width, int height, int pageIndex) {
        var glyphs = detect(gray, width, height, score.measures(), score.notes());
        if (glyphs.isEmpty()) return score;
        var owned = new java.util.LinkedHashMap<Integer, Glyph>();
        var directions = new ArrayList<>(score.playbackDirections());
        for (var glyph : glyphs) {
            float x = (glyph.left() + glyph.right()) * .5f / width;
            var bar = score.measures().get(glyph.measure());
            // The repeat slash and its dot can also match a small eighth-rest candidate.
            if (score.rests().stream().anyMatch(rest -> rest.measureIndex() == glyph.measure()
                    && !glyph.ownsEighthRest(rest, bar, width, height))) continue;
            int page = Math.max(0, pageIndex);
            String id = "measure-repeat:" + page + ":" + glyph.measure();
            var evidence = new ScoreExpressiveEvent.Evidence(
                    id, page, x, 0, 1, ScorePlaybackDirection.ONE_BAR_SIMILE);
            var details = new ScorePlaybackDirection.Details(
                    0, id, "", "", "", 1, List.of(), java.util.Optional.empty(),
                    ScorePlaybackDirection.AfterJumpRepeats.DEFAULT,
                    ScorePlaybackDirection.ONE_BAR_SIMILE, List.of(evidence));
            directions.add(new ScorePlaybackDirection(glyph.measure(),
                    ScorePlaybackDirection.Kind.MEASURE_REPEAT, details));
            owned.put(glyph.measure(), glyph);
        }
        if (owned.isEmpty()) return score;
        var notes = new ArrayList<ScoreNoteEvent>();
        var owners = new ArrayList<Integer>();
        for (int i = 0; i < score.notes().size(); i++) {
            var note = score.notes().get(i);
            if (owned.containsKey(note.measureIndex())) continue;
            notes.add(note);
            owners.add(i);
        }
        var recoveredRests = new ArrayList<>(score.rests());
        recoveredRests.removeIf(rest -> owned.containsKey(rest.measureIndex()));
        for (var glyph : owned.values())
            if (!owned.containsKey(glyph.measure() - 1))
                completeSourceCue(score, notes, recoveredRests, glyph, gray, width, height);
        var ownedScore = score.withNotes(notes, owners).withPlaybackDirections(directions);
        return recoveredRests.equals(score.rests()) ? ownedScore : ownedScore.withRests(recoveredRests);
    }

    /** A closed single-voice cue needs the independently printed short and terminal rests. */
    private static void completeSourceCue(
            ScorePageInterpretation score, List<ScoreNoteEvent> notes, List<ScoreRestEvent> rests,
            Glyph repeat, byte[] gray, int width, int height) {
        int measure = repeat.measure() - 1;
        if (score.openingPickup() && measure == 0) return;
        var presentRests = rests.stream().filter(rest -> rest.measureIndex() == measure).toList();
        if (presentRests.size() > 1 || presentRests.size() == 1
                && presentRests.get(0).durationBeats() != .5) return;
        var bar = score.measures().get(measure);
        var repeatBar = score.measures().get(repeat.measure());
        float gap = repeat.gap();
        if (Math.abs(bar.top() - repeatBar.top()) * height > gap * .3f) return;
        var owners = new ArrayList<Integer>();
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            if (note.measureIndex() != measure) continue;
            if (note.staffCount() != 1 || note.kind() != ScoreNoteEvent.Kind.PITCHED
                    || note.leadingRestBeats() != 0 || note.followingRestBeats() > .5
                    || note.tiedFromPrevious() || note.articulations() != 0
                    || note.tupletDivisor() != 1 || note.augmentationDots() != 0) return;
            owners.add(i);
        }
        if (owners.size() < 3 || owners.size() > 8) return;
        owners.sort(java.util.Comparator.comparingDouble(i -> notes.get(i).positionInMeasure()));
        var last = notes.get(owners.get(owners.size() - 1));
        if (last.beamCount() != 0 || last.unbeamedDurationBeats() != 1) return;
        int barLeft = Math.round(bar.left() * width), barRight = Math.round(bar.right() * width);
        int top = Math.round(repeat.top() - gap * .72f);
        int first = owners.get(0), second = owners.get(1);
        var before = notes.get(first);
        var after = notes.get(second);
        boolean repairBeam = before.beamCount() == 2 && after.beamCount() == 2
                && singleRail(gray, width, top, gap,
                        barLeft + before.positionInMeasure() * (barRight - barLeft),
                        barLeft + after.positionInMeasure() * (barRight - barLeft));
        double written = 0;
        for (int owner : owners)
            written += repairBeam && (owner == first || owner == second)
                    ? .5 : ScoreNoteTiming.advanceDurationBeats(notes.get(owner));
        float beats = Float.NaN;
        for (var meter : score.meterChanges())
            if (meter.measureIndex() <= measure) beats = meter.numerator() * 4f / meter.denominator();
        if (!Float.isFinite(beats) || Math.abs(beats - written - 1.5f) >= .001f) return;
        float lastX = barLeft + last.positionInMeasure() * (barRight - barLeft);
        int terminal = compactClosingRest(gray, width, top, gap,
                Math.round(lastX + gap * .7f), barRight - Math.round(gap * .4f));
        if (terminal < 0) return;
        int y0 = Math.max(0, Math.round(top - gap * 1.8f));
        int y1 = Math.min(height, Math.round(top + gap * 6.1f));
        int cropW = barRight - barLeft, cropH = y1 - y0;
        byte[] crop = new byte[cropW * cropH];
        for (int y = 0; y < cropH; y++)
            System.arraycopy(gray, (y0 + y) * width + barLeft, crop, y * cropW, cropW);
        var localNotes = new ArrayList<ScoreNoteEvent>();
        for (int i : owners) {
            var n = notes.get(i);
            localNotes.add(new ScoreNoteEvent(0, n.positionInMeasure(), n.staffStep(), 0, 1,
                    (n.pageY() * height - y0) / cropH, false, 0, n.beamCount(),
                    n.writtenAccidental(), n.unbeamedDurationBeats()));
        }
        var frame = new SixteenthRestDetector.Staff(top - y0, top - y0 + gap * 4, gap, 0, 1);
        var local = SixteenthRestDetector.detect(crop, cropW, cropH,
                List.of(new MeasureRegion(0, 1, 0, 1)), List.of(frame), localNotes);
        if (local.size() != 1 || local.get(0).durationBeats() != .5) return;
        var shortRest = local.get(0);
        int prior = -1;
        for (int i : owners)
            if (notes.get(i).positionInMeasure() < shortRest.positionInMeasure()) prior = i;
        if (prior < 0 || prior == owners.get(owners.size() - 1)) return;
        if (notes.get(prior).beamCount() != 1 && !(repairBeam && prior == second)) return;
        for (int i : owners)
            if (i != prior && notes.get(i).followingRestBeats() != 0) return;
        if (!presentRests.isEmpty()
                && Math.abs(presentRests.get(0).positionInMeasure() - shortRest.positionInMeasure())
                        * cropW > gap * .4f) return;
        if (repairBeam) {
            notes.set(first, before.withBeamAndStem(1, before.stemDirection()));
            notes.set(second, after.withBeamAndStem(1, after.stemDirection()));
        }
        notes.set(prior, notes.get(prior).withFollowingRest(.5f));
        notes.set(owners.get(owners.size() - 1), last.withFollowingRest(1));
        if (presentRests.isEmpty())
            rests.add(new ScoreRestEvent(measure, shortRest.positionInMeasure(),
                (y0 + shortRest.pageY() * cropH) / height, shortRest.pageHeight() * cropH / height,
                0, 1, .5));
        rests.add(new ScoreRestEvent(measure, (terminal - barLeft) / (float) cropW,
                (top + gap * 2) / height, gap * 2 / height, 0, 1, 1));
    }

    private static boolean singleRail(
            byte[] gray, int width, int top, float gap, float left, float right) {
        if (right - left < gap * 1.4f || right - left > gap * 2.8f
                || top < 0 || top + gap * 4 >= gray.length / width) return false;
        int a = Math.round(top + gap * .6f), b = Math.round(top + gap * 2.9f);
        for (float station : new float[] {.30f, .5f, .70f}) {
            int x = Math.round(left + (right - left) * station);
            int bands = 0, span = 0;
            for (int y = a; y <= b; y++) {
                if (Math.abs((y - top) / gap - Math.round((y - top) / gap)) < .13f) {
                    if (span < gap * .21f) span = 0;
                    continue;
                }
                if ((gray[y * width + x] & 255) < 138) {
                    span++;
                    continue;
                }
                if (span >= gap * .21f) {
                    if (span > gap * .52f) return false;
                    bands++;
                }
                span = 0;
            }
            if (bands != 1 || span >= gap * .21f) return false;
        }
        return true;
    }

    /** A compact rest spans the upper turn, narrow waist and lower hook between the rails. */
    static int compactClosingRest(byte[] gray, int width, int top, float gap, int left, int right) {
        if (left < 0 || right > width || right - left < gap * 1.2f
                || right - left > gap * 4 || top < 0
                || top + gap * 4 >= gray.length / width) return -1;
        float[] centers = new float[5];
        float[] spans = new float[5];
        float[] first = {1.10f, 1.38f, 2.13f, 2.35f, 2.76f};
        float[] last = {1.30f, 1.65f, 2.23f, 2.62f, 2.92f};
        for (int band = 0; band < first.length; band++) {
            int rows = 0;
            for (int y = Math.round(top + first[band] * gap); y <= Math.round(top + last[band] * gap); y++) {
                int count = 0, sum = 0;
                int xMin = right, xMax = left;
                for (int x = left; x < right; x++)
                    if ((gray[y * width + x] & 255) < 138) {
                        count++;
                        sum += x;
                        xMin = Math.min(xMin, x);
                        xMax = Math.max(xMax, x);
                    }
                // Subpixel rails may extend into a band on slanted pages.
                if (count > gap * .88f && xMax - xMin > gap) continue;
                if (count < gap * .11f || xMin == left || xMax == right - 1) continue;
                if (xMax - xMin > gap || count < (xMax - xMin + 1) * .75f) continue;
                centers[band] += sum / (float) count;
                spans[band] += count;
                rows++;
            }
            if (rows < 2) return -1;
            centers[band] /= rows;
            spans[band] /= rows;
        }
        if (centers[1] - centers[0] < gap * .12f || centers[2] - centers[3] < gap * .045f
                || Math.abs(centers[4] - centers[3]) > gap * .26f
                || spans[1] - spans[0] < gap * .085f || spans[3] - spans[2] < gap * .16f
                || spans[3] - spans[4] < gap * .15f
                || spans[3] < gap * .36f || spans[3] > gap * .77f) return -1;
        return Math.round((centers[1] + centers[3]) * .5f);
    }

    private record Run(int from, int to) {
        float center() { return (from + to - 1) * .5f; }
        int span() { return to - from; }
    }

    private static Glyph read(
            byte[] gray, int width, int height, int measure,
            int left, int right, int top, int bottom) {
        if (left < 0 || right > width || top < 0 || bottom > height) return null;
        var rails = new ArrayList<Run>();
        int edge = Math.max(2, Math.round((right - left) * .045f));
        for (int y = top; y < bottom; ){
            if (!rail(gray, width, y, left + edge, right - edge)) {
                y++;
                continue;
            }
            int start = y++;
            while (y < bottom && rail(gray, width, y, left + edge, right - edge)) y++;
            rails.add(new Run(start, y));
        }
        if (rails.size() != 5) return null;
        float gap = (rails.get(4).center() - rails.get(0).center()) * .25f;
        if (gap < 6 || gap > 35 || right - left < gap * 3.2f || right - left > gap * 10f) return null;
        for (int i = 0; i < rails.size(); i++)
            if (rails.get(i).span() > gap * .28f || Math.abs(rails.get(i).center()
                    - rails.get(0).center() - i * gap) > gap * .18f) return null;
        float center = (left + right) * .5f;
        int x0 = Math.max(left + edge, Math.round(center - gap * 1.65f));
        int x1 = Math.min(right - edge, Math.round(center + gap * 1.65f));
        int y0 = Math.round(rails.get(1).center() - gap * .28f);
        int y1 = Math.round(rails.get(3).center() + gap * .28f) + 1;
        float middle = rails.get(2).center();
        float slashCenter = 0, slashSlope = 0;
        int best = 0, bestRows = 0;
        for (float slope : new float[] {.7f, .85f, 1f, 1.15f}) {
            for (int x = Math.round(center - gap * .35f); x <= center + gap * .35f; x++) {
                int mass = 0, rows = 0;
                for (int y = y0; y < y1; y++) {
                    if (isRail(y, rails) || Math.abs(y - middle) > gap * .92f) continue;
                    float predicted = x - (y - middle) * slope;
                    int ink = 0;
                    for (int xx = Math.max(x0, Math.round(predicted - gap * .27f));
                            xx < Math.min(x1, Math.round(predicted + gap * .27f) + 1); xx++)
                        if ((gray[y * width + xx] & 255) < 138) ink++;
                    if (ink >= gap * .40f) rows++;
                    mass += ink;
                }
                if (mass > best) {
                    best = mass;
                    bestRows = rows;
                    slashCenter = x;
                    slashSlope = slope;
                }
            }
        }
        if (bestRows < gap * 1.37f) return null;
        int upperInk = 0, lowerInk = 0, extra = 0;
        for (int y = y0; y < y1; y++) {
            if (isRail(y, rails)) continue;
            float line = slashCenter - (y - middle) * slashSlope;
            for (int x = left + edge; x < right - edge; x++) {
                if ((gray[y * width + x] & 255) >= 138) continue;
                float dx = (x - slashCenter) / gap;
                float dy = (y - middle) / gap;
                if (Math.abs(x - line) < gap * .53f && Math.abs(dy) < 1.1f) continue;
                if (dx > -1.17f && dx < -.32f && dy > -.85f && dy < -.13f) upperInk++;
                else if (dx > .32f && dx < 1.17f && dy > .13f && dy < .85f) lowerInk++;
                else extra++;
            }
        }
        float area = gap * gap;
        if (upperInk < area * .115f || lowerInk < area * .115f
                || upperInk > area * .31f || lowerInk > area * .31f
                || extra > area * .16f) return null;
        return new Glyph(measure, x0, y0, x1, y1, gap);
    }

    private static boolean isRail(int y, List<Run> rails) {
        for (var rail : rails) if (y >= rail.from() - 1 && y <= rail.to()) return true;
        return false;
    }

    private static boolean rail(byte[] gray, int width, int y, int left, int right) {
        int hits = 0;
        for (int x = left; x < right; x++) if ((gray[y * width + x] & 255) < 147) hits++;
        return hits >= (right - left) * .65f;
    }
}
