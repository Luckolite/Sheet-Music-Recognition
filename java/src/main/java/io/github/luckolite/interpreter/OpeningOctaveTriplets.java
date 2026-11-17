// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/** Reads the four separately beamed octave triplets of a repeated common-time opening. */
final class OpeningOctaveTriplets {
    private OpeningOctaveTriplets() {}

    private record Head(int index, ScoreNoteEvent note, float x, float y) {}

    private record Pair(Head high, Head low) {
        float x() {
            return (high.x() + low.x()) * .5f;
        }
    }

    private record Bar(List<Pair> pairs, Head alias, int aliasColumn) {}

    private record SourceGroup(List<Pair> pairs, boolean unnumbered) {}

    static ScorePageInterpretation close(
            ScorePageInterpretation score,
            byte[] labels,
            byte[] corrected,
            byte[] original,
            int width,
            int height,
            int page) {
        if (width <= 0
                || height <= 0
                || labels == null
                || corrected == null
                || original == null
                || corrected.length != (long) width * height
                || original.length != corrected.length
                || labels.length != corrected.length
                || score.measures().size() < 4
                || !score.meterChanges().contains(new ScoreMeterChange(0, 4, 4))
                || score.meterChanges().stream()
                        .anyMatch(m -> m.measureIndex() > 0 && m.measureIndex() < 3)) return score;
        var bars = score.measures();
        var opening = new ArrayList<Bar>(3);
        var groups = new ArrayList<SourceGroup>(12);
        for (int index = 0; index < 3; index++) {
            var bar = bars.get(index);
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            float span = (bar.right() - bar.left()) * width;
            if (gap < 12
                    || gap > 24
                    || span < gap * 31
                    || span > gap * 40
                    || bar.cadenceQuarterBeats() != 0
                    || Math.abs(bar.top() - bars.get(0).top()) * height > gap * .35f)
                return score;
            final int barIndex = index;
            if (score.rests().stream().anyMatch(r -> r.measureIndex() == barIndex)) return score;
            var group = readOwners(score, index, width, height, gap);
            if (group == null) return score;
            var a = proves(group, bars, index, labels, corrected, width, height, gap);
            var b = proves(group, bars, index, labels, original, width, height, gap);
            if (a == null || b == null) return score;
            for (int i = 0; i < 4; i++)
                groups.add(new SourceGroup(group.pairs().subList(i * 3, i * 3 + 3), a[i] && b[i]));
            opening.add(group);
        }
        var notes = new ArrayList<>(score.notes());
        var removed = new HashSet<Integer>();
        for (var bar : opening) {
            for (int i = 0; i < bar.pairs().size(); i++) {
                int direction = i < 3 ? 1 : -1;
                var pair = bar.pairs().get(i);
                for (var head : List.of(pair.high(), pair.low())) {
                    var note = head.note();
                    if (note.beamCount() == 2) note = ClosingTripletRhythm.singleBeam(note);
                    notes.set(
                            head.index(), note.withStemDirection(direction).withTupletRatio(3, 2));
                }
            }
            if (bar.alias() != null) removed.add(bar.alias().index());
        }
        var beamSource = projectBeams(score.beamSource(), groups, page);
        if (notes.equals(score.notes()) && removed.isEmpty())
            return beamSource == score.beamSource() ? score : score.withBeamSource(beamSource);
        var owners =
                IntStream.range(0, notes.size()).filter(i -> !removed.contains(i)).boxed().toList();
        return score.withBeamSource(beamSource)
                .withNotes(owners.stream().map(notes::get).toList(), owners);
    }

    private static Bar readOwners(
            ScorePageInterpretation score, int index, int width, int height, float gap) {
        var region = score.measures().get(index);
        var heads = new ArrayList<Head>();
        Head alias = null;
        int clef = ScoreNoteEvent.CLEF_UNKNOWN;
        for (int i = 0; i < score.notes().size(); i++) {
            var note = score.notes().get(i);
            if (note.measureIndex() != index) continue;
            if (clef == ScoreNoteEvent.CLEF_UNKNOWN) clef = note.clefBottomDiatonic();
            boolean incoming =
                    index == 0
                            && note.positionInMeasure() < .12f
                            && note.boundaryTies() != 0
                            && (note.boundaryTies() & ~3) == 0;
            if (clef == ScoreNoteEvent.CLEF_UNKNOWN
                    || !ordinary(note, clef)
                    || note.boundaryTies() != 0 && !incoming) return null;
            float x =
                    (region.left() + note.positionInMeasure() * (region.right() - region.left()))
                            * width;
            var head = new Head(i, note, x, note.pageY() * height);
            if (note.beamCount() != 0 && note.unbeamedDurationBeats() == 0) heads.add(head);
            else {
                if (alias != null
                        || note.beamCount() != 0
                        || note.unbeamedDurationBeats() != 1
                        || note.stemDirection() != 1
                        || note.articulations() != 0
                        || note.tupletDivisor() != 1
                        || note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY)
                    return null;
                alias = head;
            }
        }
        if (heads.size() != 24) return null;
        heads.sort(Comparator.comparingDouble(Head::x));
        var pairs = new ArrayList<Pair>(12);
        int aliases = 0, column = -1;
        for (int i = 0; i < 12; i++) {
            var a = heads.get(i * 2);
            var b = heads.get(i * 2 + 1);
            var pair = a.y() < b.y() ? new Pair(a, b) : new Pair(b, a);
            int direction = i < 3 ? 1 : -1;
            if (b.x() - a.x() > gap * .18f
                    || pair.high().note().staffStep() - pair.low().note().staffStep() != 7
                    || pair.low().y() - pair.high().y() < gap * 3.25f
                    || pair.low().y() - pair.high().y() > gap * 3.85f) return null;
            for (var head : List.of(a, b)) {
                if (head.note().stemDirection() != 0 && head.note().stemDirection() != direction)
                    return null;
                if (head.note().beamCount() == 2 && ++aliases > 2) return null;
            }
            if (i > 0) {
                float span = pair.x() - pairs.get(pairs.size() - 1).x();
                if (span < gap * 2.35f || span > gap * 3.65f) return null;
                if (alias != null
                        && direction < 0
                        && i % 3 != 0
                        && pair.x() - alias.x() >= gap * .85f
                        && pair.x() - alias.x() <= gap * 1.25f) column = i;
            }
            pairs.add(pair);
        }
        return alias != null && column < 0 ? null : new Bar(pairs, alias, column);
    }

    private static boolean ordinary(ScoreNoteEvent note, int clef) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && note.staffIndex() == 0
                && note.staffCount() == 1
                && note.clefBottomDiatonic() == clef
                && note.beamCount() <= 2
                && note.augmentationDots() == 0
                && !note.crossStaffBeam()
                && !note.tiedFromPrevious()
                && note.octaveShift() == 0
                && note.harmonicSemitones() == 0
                && note.leadingRestBeats() == 0
                && note.followingRestBeats() == 0
                && (note.articulations() & NoteOrnament.GRACE) == 0
                && (note.tupletDivisor() == 1 && note.tupletNormalNotes() == 1
                        || note.tupletDivisor() == 3 && note.tupletNormalNotes() == 2);
    }

    private static boolean[] proves(
            Bar group,
            List<MeasureRegion> bars,
            int index,
            byte[] labels,
            byte[] ink,
            int width,
            int height,
            float gap) {
        var pairs = group.pairs();
        var bar = bars.get(index);
        if (!closed(bar, index, pairs, ink, width, height, gap)) return null;
        var shafts = new PrintedBeamShafts.Shaft[12];
        var budget = new PrintedBeamShafts.Budget(300_000);
        for (int j = 0; j < 12; j += 3) {
            int direction = j == 0 ? 1 : -1;
            var a = endShaft(ink, width, height, gap, pairs.get(j), direction, budget);
            var b = endShaft(ink, width, height, gap, pairs.get(j + 2), direction, budget);
            if (a == null || b == null || budget.refused()) return null;
            float span = axis(b) - axis(a);
            if (span <= 0 || Math.abs(b.endY() - a.endY()) > span * .48f) return null;
            for (int i = j; i < j + 3; i++) {
                var pair = pairs.get(i);
                float x = pair.x() + direction * .60f * gap;
                int tip = Math.round(a.endY() + (b.endY() - a.endY()) * (x - axis(a)) / span);
                float startY = direction > 0 ? pair.low().y() : pair.high().y();
                shafts[i] = sharedShaft(ink, width, height, gap, pair.x(), startY, direction, tip);
                if (shafts[i] == null) return null;
            }
        }
        for (int i = 0; i < pairs.size(); i++) {
            var pair = pairs.get(i);
            float next =
                    i + 1 < pairs.size() ? pairs.get(i + 1).x() : bar.right() * width + gap * .5f;
            if (i > 0
                            && !WrappedEighthTriplets.matchesRailBridge(
                                    ink, width, height, shafts[i - 1], shafts[i], gap, i % 3 != 0)
                    || dotNear(ink, width, height, gap, pair.high(), next)
                    || dotNear(ink, width, height, gap, pair.low(), next)) return null;
        }
        if (group.alias() != null
                && !ownedBeamFragment(
                        group.alias(),
                        labels,
                        ink,
                        width,
                        height,
                        gap,
                        shafts[group.aliasColumn() - 1],
                        shafts[group.aliasColumn()])) return null;
        var frame = WrappedMeasureBoundaries.verifiedTripletStaff(ink, width, height, bar);
        var rows = UnnumberedTripletInk.sourceRows(bar, bars, height);
        var unnumbered = new boolean[4];
        for (int i = 0; i < 4; i++)
            unnumbered[i] =
                    unnumbered(
                            pairs.subList(i * 3, i * 3 + 3),
                            shafts,
                            i * 3,
                            ink,
                            width,
                            height,
                            gap,
                            frame,
                            rows);
        return unnumbered;
    }

    /** Existing partial groups only qualify when each of their original columns belongs to this group. */
    private static ScoreBeamSource projectBeams(
            ScoreBeamSource source, List<SourceGroup> groups, int page) {
        if (page < 0) return source;
        var origins = new ArrayList<>(source.noteOrigins());
        var existing = new ArrayList<>(source.groups());
        var detached = new ArrayList<>(source.detachedColumns());
        for (var group : groups) {
            var columns = new ArrayList<ScoreBeamSource.Column>(3);
            var owners = new ArrayList<ScoreBeamSource.Anchor>(6);
            for (var pair : group.pairs()) {
                var high = origin(origins, pair.high(), page);
                var low = origin(origins, pair.low(), page);
                if (high == null || low == null || high.equals(low)) break;
                owners.add(high);
                owners.add(low);
                columns.add(new ScoreBeamSource.Column(List.of(high, low)));
            }
            if (columns.size() != 3 || new HashSet<>(owners).size() != 6) continue;
            var partial =
                    existing.stream()
                            .filter(
                                    r ->
                                            r.columns().stream()
                                                    .anyMatch(
                                                            c ->
                                                                    c.members().stream()
                                                                            .anyMatch(
                                                                                    owners
                                                                                            ::contains)))
                            .toList();
            var localDetached =
                    detached.stream()
                            .filter(c -> c.members().stream().anyMatch(owners::contains))
                            .toList();
            if (partial.stream().anyMatch(r -> !samePrimaryColumns(r, columns))
                    || localDetached.stream()
                            .anyMatch(
                                    c ->
                                            columns.stream()
                                                    .noneMatch(
                                                            p ->
                                                                    p.members()
                                                                            .containsAll(
                                                                                    c.members()))))
                continue;
            String id =
                    "printed-beam:"
                            + page
                            + ":"
                            + Math.min(owners.get(0).noteIndex(), owners.get(1).noteIndex());
            if (existing.stream().anyMatch(r -> r.id().equals(id) && !partial.contains(r)))
                continue;
            var mark =
                    partial.stream()
                            .map(ScoreBeamSource.Group::tupletMark)
                            .filter(m -> m != null)
                            .findFirst()
                            .orElse(
                                    group.unnumbered()
                                            ? ScoreBeamSource.TupletMark.UNNUMBERED
                                            : null);
            existing.removeAll(partial);
            detached.removeAll(localDetached);
            existing.add(new ScoreBeamSource.Group(id, columns, List.of(1, 1), List.of(), mark));
            for (int i = 0; i < 3; i++) {
                var pair = group.pairs().get(i);
                origins.set(pair.high().index(), Optional.of(owners.get(2 * i)));
                origins.set(pair.low().index(), Optional.of(owners.get(2 * i + 1)));
            }
        }
        return new ScoreBeamSource(existing, detached, origins);
    }

    private static ScoreBeamSource.Anchor origin(
            List<Optional<ScoreBeamSource.Anchor>> origins, Head head, int page) {
        var retained = origins.get(head.index()).orElse(null);
        if (retained == null) {
            var candidate = new ScoreBeamSource.Anchor(page, head.index());
            return origins.contains(Optional.of(candidate)) ? null : candidate;
        }
        return retained.pageIndex() == page
                        && java.util.Collections.frequency(origins, Optional.of(retained)) == 1
                ? retained
                : null;
    }

    private static boolean samePrimaryColumns(
            ScoreBeamSource.Group group, List<ScoreBeamSource.Column> columns) {
        if (!group.hooks().isEmpty() || group.bridges().stream().anyMatch(v -> v != 1))
            return false;
        int previous = -1;
        for (var column : group.columns()) {
            int index = previous + 1;
            while (index < columns.size()
                    && !columns.get(index).members().containsAll(column.members())) index++;
            if (index == columns.size() || previous >= 0 && index != previous + 1) return false;
            previous = index;
        }
        return true;
    }

    private static boolean unnumbered(
            List<Pair> pairs,
            PrintedBeamShafts.Shaft[] shafts,
            int offset,
            byte[] ink,
            int width,
            int height,
            float gap,
            StaffPitchTrack frame,
            UnnumberedTripletInk.Rows rows) {
        if (frame == null) return false;
        float first = pairs.get(0).x(), last = pairs.get(pairs.size() - 1).x();
        float upper = (float) pairs.stream().mapToDouble(p -> p.high().y()).min().orElseThrow();
        float lower = (float) pairs.stream().mapToDouble(p -> p.low().y()).max().orElseThrow();
        int minTip = Integer.MAX_VALUE, maxTip = 0;
        for (int i = offset; i < offset + 3; i++) {
            minTip = Math.min(minTip, shafts[i].endY());
            maxTip = Math.max(maxTip, shafts[i].endY());
        }
        if (UnnumberedTripletInk.bracketBesideBeam(
                ink, width, height, gap, first, last, upper, lower, maxTip, rows, frame, true))
            return false;
        int left = Math.round(first + gap * .55f), right = Math.round(last - gap * .55f);
        int top = Math.max(rows.first(), Math.round(Math.min(upper, minTip) - gap * 4.8f));
        int bottom = Math.min(rows.last(), Math.round(Math.max(lower, maxTip) + gap * 3f));
        if (left < 0
                || right >= width
                || right <= left
                || bottom <= top
                || top < 0
                || bottom >= height) return false;
        var glyphs =
                RawNumeralComponents.captureLongWindow(
                        ink, width, height, left, top, right, bottom);
        for (var glyph : glyphs.components()) {
            int glyphWidth = glyph.right - glyph.left + 1;
            int glyphHeight = glyph.bottom - glyph.top + 1;
            if (glyphWidth >= gap * .34f
                    && glyphWidth <= gap * 1.65f
                    && glyphHeight >= gap * .76f
                    && glyphHeight <= gap * 2.35f
                    && TripletRhythmDetector.isIsolatedThree(glyph, width)
                    && !ownedShaftInk(glyph, shafts, offset, gap)) return false;
        }
        return true;
    }

    private static boolean ownedShaftInk(
            RawNumeralComponents.Component glyph,
            PrintedBeamShafts.Shaft[] shafts,
            int offset,
            float gap) {
        if (glyph.right - glyph.left + 1 > gap * .44f) return false;
        for (int i = offset; i < offset + 3; i++) {
            var shaft = shafts[i];
            float x = axis(shaft);
            int top = Math.min(shaft.startY(), shaft.endY());
            int bottom = Math.max(shaft.startY(), shaft.endY());
            if (glyph.left >= x - gap * .31f
                    && glyph.right <= x + gap * .31f
                    && glyph.top >= top + gap * .9f
                    && glyph.bottom <= bottom - gap * .9f) return true;
        }
        return false;
    }

    private static boolean closed(
            MeasureRegion bar,
            int index,
            List<Pair> notes,
            byte[] ink,
            int width,
            int height,
            float gap) {
        float first = notes.get(0).x(), last = notes.get(notes.size() - 1).x();
        if (index > 0)
            return WrappedMeasureBoundaries.confirmsClosed(ink, width, height, bar, first, last);
        if (!WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(
                ink, width, height, bar, last)) return false;
        float left = bar.left() * width;
        var staff =
                PrintedDirectionStaff.printedRules(
                        ink,
                        width,
                        height,
                        left + gap * .45f,
                        bar.bottom() * height - 2.1f * gap,
                        gap);
        if (staff == null || first - left < gap * 1.4f || first - left > gap * 2.5f) return false;
        int top = Math.round(staff[0] - 4 * staff[1]);
        int from = Math.round(left - gap * 1.55f), to = Math.round(first - gap * .85f);
        int bottom = Math.round(staff[0] + .20f * gap);
        if (from < 0 || to >= width || top < 3 || bottom >= height) return false;
        var glyph =
                MeterFontMatcher.readCrop(
                        ink,
                        width,
                        height,
                        new MeterChangeDetector.Crop(from, top - 3, to, bottom, top, staff[1]));
        return glyph.text().equals("4/4")
                && glyph.topScore() >= .88f
                && glyph.bottomScore() >= .88f;
    }

    private static boolean dotNear(byte[] ink, int w, int h, float gap, Head head, float next) {
        return WrappedTripletDotInk.uniqueCore(ink, w, h, head.x(), head.y(), next, gap) != null;
    }

    private static PrintedBeamShafts.Shaft endShaft(
            byte[] ink,
            int w,
            int h,
            float gap,
            Pair pair,
            int direction,
            PrintedBeamShafts.Budget budget) {
        PrintedBeamShafts.Shaft best = null;
        for (var head : List.of(pair.high(), pair.low())) {
            var shafts = PrintedBeamShafts.at(ink, w, h, pair.x(), head.y(), gap, budget);
            for (var shaft : shafts)
                if (shaft.direction() == -direction
                        && (best == null || (shaft.endY() - best.endY()) * direction < 0))
                    best = shaft;
        }
        if (best != null) return best;
        for (var head : List.of(pair.high(), pair.low())) {
            var stem = PairedTremoloInk.stem(ink, w, h, pair.x(), head.y(), gap, direction);
            if (stem != null && (best == null || (stem.endY() - best.endY()) * direction < 0))
                best = shaft(stem.x(), stem.headY(), stem.endY(), direction);
        }
        return best;
    }

    /** The aperture between literal endpoints can recover an attenuated octave shaft. */
    static PrintedBeamShafts.Shaft sharedShaft(
            byte[] ink,
            int w,
            int h,
            float gap,
            float center,
            float startY,
            int direction,
            int expected) {
        int first = Math.round(center + direction * .44f * gap);
        int last = Math.round(center + direction * .79f * gap);
        int start = Math.round(startY);
        int limit = Math.round(gap * .30f);
        if (Math.min(first, last) < 2
                || Math.max(first, last) + 2 >= w
                || start < 0
                || start >= h
                || expected - limit - 1 < 0
                || expected + limit + 1 >= h) return null;
        int bestAxis = -1, bestTip = 0, bestEvidence = 0;
        for (int x = first; (last - x) * direction >= 0; x += direction) {
            int tip = expected;
            while (Math.abs(tip - expected) <= limit && darkness(ink, w, x, tip) >= 170)
                tip += direction;
            if (Math.abs(tip - expected) > limit) continue;
            while (Math.abs(tip - expected) <= limit && darkness(ink, w, x, tip - direction) < 170)
                tip -= direction;
            if (Math.abs(tip - expected) > limit) continue;
            int length = (start - tip) * direction + 1;
            if (length < gap * 5.1f || length > gap * 8.8f) continue;
            int hard = 0, soft = 0, run = 0, missing = 0;
            for (int y = start; (y - tip) * direction >= 0; y -= direction) {
                if ((ink[y * w + x] & 255) < 185) hard++;
                if (darkness(ink, w, x, y) < 185) {
                    soft++;
                    run = 0;
                } else missing = Math.max(missing, ++run);
            }
            if (hard >= length * .76f
                    && soft >= length * .82f
                    && missing <= gap * .62f
                    && hard > bestEvidence) {
                bestAxis = x;
                bestTip = tip;
                bestEvidence = hard;
            }
        }
        return bestAxis < 0 ? null : shaft(bestAxis, start, bestTip, direction);
    }

    private static float axis(PrintedBeamShafts.Shaft shaft) {
        return (shaft.left() + shaft.right()) * .5f;
    }

    private static PrintedBeamShafts.Shaft shaft(int x, int start, int end, int stemDirection) {
        return new PrintedBeamShafts.Shaft(
                x, x, start, end, -stemDirection, List.of(new PrintedBeamShafts.Column(x, end)));
    }

    private static int darkness(byte[] ink, int w, int x, int y) {
        return Math.min(
                ink[y * w + x] & 255, Math.min(ink[y * w + x - 1] & 255, ink[y * w + x + 1] & 255));
    }

    private static boolean ownedBeamFragment(
            Head alias,
            byte[] labels,
            byte[] ink,
            int w,
            int h,
            float gap,
            PrintedBeamShafts.Shaft before,
            PrintedBeamShafts.Shaft after) {
        float dx = axis(after) - alias.x();
        if (dx < gap * .26f || dx > gap * .55f || (before.endY() - after.endY()) < gap * .35f)
            return false;
        int x0 = Math.round(alias.x()), y0 = Math.round(alias.y()), radius = Math.round(gap * .7f);
        if (x0 - radius < 1 || y0 - radius < 1 || x0 + radius >= w - 1 || y0 + radius >= h - 1)
            return false;
        int first = y0 * w + x0;
        if (labels[first] != OmrMeasurePostProcessor.NOTEHEAD) return false;
        var seen = new HashSet<Integer>();
        var open = new ArrayDeque<Integer>();
        open.add(first);
        int left = x0, right = x0, top = y0, bottom = y0, dark = 0;
        while (!open.isEmpty()) {
            int pixel = open.removeFirst();
            if (labels[pixel] != OmrMeasurePostProcessor.NOTEHEAD || !seen.add(pixel)) continue;
            int x = pixel % w, y = pixel / w;
            if (Math.abs(x - x0) > radius || Math.abs(y - y0) > radius) return false;
            float tip =
                    before.endY()
                            + (after.endY() - before.endY())
                                    * (x - axis(before))
                                    / (axis(after) - axis(before));
            if (y < tip - gap * .76f || y > tip + gap * .20f) return false;
            if ((ink[pixel] & 255) < 185) dark++;
            left = Math.min(left, x);
            right = Math.max(right, x);
            top = Math.min(top, y);
            bottom = Math.max(bottom, y);
            open.add(pixel - 1);
            open.add(pixel + 1);
            open.add(pixel - w);
            open.add(pixel + w);
        }
        return seen.size() >= gap * gap * .43f
                && seen.size() <= gap * gap * .70f
                && right - left + 1 >= gap * .8f
                && right - left + 1 <= gap * 1.12f
                && bottom - top + 1 <= gap * .84f
                && dark >= seen.size() * .94f;
    }
}
