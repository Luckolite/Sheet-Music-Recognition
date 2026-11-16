// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/** Reject an excess dotted sixteenth when independent beam evidence fills an entire printed bar. */
final class ClosedSixteenthDyads {
    private ClosedSixteenthDyads() {}

    record Ink(byte[] raw,int width,int height) {
        boolean dark(int x,int y) {
            return x>=0 && x<width && y>=0 && y<height && (raw[y*width+x]&255)<162;
        }
    }
    record Head(int owner,ScoreNoteEvent note,float x,float y) {}
    record Dyad(Head top,Head bottom) {}
    record Shaft(int x,int tip) {}
    record Rails(int x0,int y0,int x1,int y1,int separation) {
        int at(int x) {return Math.round(y0+(y1-y0)*(x-x0)/(float)(x1-x0));}
    }

    static ScorePageInterpretation apply(ScorePageInterpretation score,byte[] gray,int w,int h) {
        return apply(score, gray, gray, w, h);
    }

    static ScorePageInterpretation apply(
            ScorePageInterpretation score, byte[] gray, byte[] raw, int w, int h) {
        return apply(score, null, gray, raw, w, h);
    }

    static ScorePageInterpretation apply(
            ScorePageInterpretation score,
            ScoreMeterChange inheritedMeter,
            byte[] gray,
            byte[] raw,
            int w,
            int h) {
        return apply(score, inheritedMeter, gray, raw, w, h, -1);
    }

    static ScorePageInterpretation apply(
            ScorePageInterpretation score,
            ScoreMeterChange inheritedMeter,
            byte[] gray,
            byte[] raw,
            int w,
            int h,
            int physicalPage) {
        if (gray==null || gray.length!=(long)w*h || w<256 || h<128) return score;
        var ink=new Ink(gray,w,h);
        var original = raw != null && raw.length == gray.length ? new Ink(raw,w,h) : null;
        var notes=score.notes();
        var regions=score.measures();
        var bars=new ArrayList<List<Head>>();
        for (int i=0;i<regions.size();i++) bars.add(new ArrayList<>());
        ScoreMeterChange active=null;
        int meter=0;
        for (int i=0;i<notes.size();i++) {
            var n=notes.get(i);
            if (n.measureIndex() < 0 || n.measureIndex() >= regions.size()) continue;
            var b=regions.get(n.measureIndex());
            bars.get(n.measureIndex()).add(new Head(i,n,
                    (b.left()+n.positionInMeasure()*(b.right()-b.left()))*w,n.pageY()*h));
        }
        var result=new ArrayList<>(notes);
        var printedBeams = score.beamSource();
        for (int m=0;m<regions.size();m++) {
            while (meter<score.meterChanges().size() && score.meterChanges().get(meter).measureIndex()<=m)
                active=score.meterChanges().get(meter++);
            final int bar=m;
            int count = bars.get(m).size();
            if (active != null
                    && active.numerator() == 6
                    && active.denominator() == 4
                    && count == 24
                    && regions.get(m).cadenceQuarterBeats() == 0
                    && score.rests().stream().noneMatch(r -> r.measureIndex() == bar)) {
                var next = m + 1 < regions.size() ? regions.get(m + 1) : null;
                restoreClosedMonophonicSixFour(
                        ink, original, regions.get(m), next, bars.get(m), result);
                continue;
            }
            if (count != 30 && count != 32
                    || regions.get(m).cadenceQuarterBeats() != 0
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == bar)) continue;
            var effective = active == null ? inheritedMeter : active;
            if (count == 32
                    && original != null
                    && effective != null
                    && effective.numerator() == 4
                    && effective.denominator() == 4
                    && closeAcrossPrintedBeams(ink, original, regions, m, bars.get(m), result)) {
                if (physicalPage >= 0)
                    printedBeams = projectDoubleRails(printedBeams, physicalPage, bars.get(m));
                continue;
            }
            if (active == null || active.numerator() != 4 || active.denominator() != 4) continue;
            if (count == 30) {
                closeToEighth(ink, original, regions.get(m), bar == 0,
                        regions, bars.get(m), result);
                continue;
            }
            boolean header = active.measureIndex() == m;
            boolean compact = header && closedHeaderSixteenths(ink, regions.get(m), bars.get(m));
            boolean wide = header && !compact && closedWideHeaderThirds(ink, original, regions.get(m),
                    m + 1 < regions.size() ? regions.get(m + 1) : null, bars.get(m));
            if (compact || wide) {
                for (var head:bars.get(m)) {
                    var note=head.note.withTupletRatio(1,1);
                    result.set(head.owner,note.augmentationDots()==0 ? note : withoutDot(note));
                }
                if (!closes(bars.get(m),result)) {
                    for (var head:bars.get(m)) result.set(head.owner,head.note);
                } else if (wide && physicalPage >= 0) {
                    printedBeams = projectDoubleRails(printedBeams, physicalPage, bars.get(m));
                }
                continue;
            }
            if (restoreSteppedOctaveShaft(ink, regions.get(m), bars.get(m), result)) continue;
            var pair=prove(ink,regions.get(m),bars.get(m));
            if (pair == null) pair = terminalOctaveHookDot(ink, regions.get(m), bars.get(m));
            if (pair==null) continue;
            result.set(pair.top.owner,withoutDot(pair.top.note));
            result.set(pair.bottom.owner,withoutDot(pair.bottom.note));
            if (!closes(bars.get(m),result)) {
                result.set(pair.top.owner,pair.top.note);
                result.set(pair.bottom.owner,pair.bottom.note);
            }
        }
        if (result.equals(notes)) return score;
        var corrected = score.withNotes(result, IntStream.range(0, notes.size()).boxed().toList());
        return printedBeams == score.beamSource()
                ? corrected
                : corrected.withBeamSource(printedBeams);
    }

    private static ScoreBeamSource projectDoubleRails(
            ScoreBeamSource source, int physicalPage, List<Head> sourceHeads) {
        var heads = sourceHeads.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var origins = new ArrayList<>(source.noteOrigins());
        var counts = new HashMap<ScoreBeamSource.Anchor, Integer>();
        for (var origin : origins) origin.ifPresent(a -> counts.merge(a, 1, Integer::sum));
        var claimed = new HashSet<ScoreBeamSource.Anchor>();
        var ids = new HashSet<String>();
        for (var group : source.groups()) {
            ids.add(group.id());
            for (var column : group.columns()) claimed.addAll(column.members());
        }
        for (var column : source.detachedColumns()) claimed.addAll(column.members());
        var groups = new ArrayList<>(source.groups());
        for (int group = 0; group < 4; group++) {
            var columns = new ArrayList<ScoreBeamSource.Column>(4);
            int firstOwner = Integer.MAX_VALUE;
            for (int slot = group * 4; slot < group * 4 + 4; slot++) {
                var members = new ArrayList<ScoreBeamSource.Anchor>(2);
                for (int across = 0; across < 2; across++) {
                    int owner = heads.get(slot * 2 + across).owner;
                    var retained = origins.get(owner);
                    var anchor =
                            retained.orElseGet(
                                    () -> new ScoreBeamSource.Anchor(physicalPage, owner));
                    int expectedCount = retained.isPresent() ? 1 : 0;
                    if (anchor.pageIndex() != physicalPage
                            || counts.getOrDefault(anchor, 0) != expectedCount
                            || !claimed.add(anchor)) return source;
                    origins.set(owner, Optional.of(anchor));
                    counts.put(anchor, 1);
                    members.add(anchor);
                    firstOwner = Math.min(firstOwner, anchor.noteIndex());
                }
                columns.add(new ScoreBeamSource.Column(members));
            }
            String id = "beam-page:" + physicalPage + ":" + firstOwner;
            if (!ids.add(id)) return source;
            groups.add(new ScoreBeamSource.Group(id, columns, List.of(3, 3, 3), List.of()));
        }
        return new ScoreBeamSource(groups, source.detachedColumns(), origins);
    }

    /** Four literal stems on each side of a beam break contradict a triplet spanning that break. */
    private static void restoreClosedMonophonicSixFour(
            Ink normalized,
            Ink original,
            MeasureRegion bar,
            MeasureRegion next,
            List<Head> row,
            List<ScoreNoteEvent> result) {
        if (original == null || next == null || row.size() != 24) return;
        float gap = (bar.bottom() - bar.top()) * normalized.height / 8.2f;
        float left = bar.left() * normalized.width, right = bar.right() * normalized.width;
        if (gap < 10
                || gap > 26
                || right - left < 44 * gap
                || right - left > 55 * gap
                || Math.abs(next.top() - bar.top()) * normalized.height > gap * .35f) return;
        var heads = row.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        for (int i = 0; i < heads.size(); i++) {
            var head = heads.get(i);
            var note = head.note();
            int written = i >= 2 && i <= 4 ? 3 : 1;
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED
                    || note.staffIndex() != 0
                    || note.staffCount() != 1
                    || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || note.beamCount() != 2
                    || note.augmentationDots() != 0
                    || note.unbeamedDurationBeats() != 0
                    || note.tupletDivisor() != written
                    || note.tupletNormalNotes() != (written == 3 ? 2 : 1)
                    || note.leadingRestBeats() != 0
                    || note.followingRestBeats() != 0
                    || note.tiedFromPrevious()
                    || note.boundaryTies() != 0
                    || note.crossStaffBeam()
                    || (note.articulations() & NoteOrnament.GRACE) != 0) return;
            if (i > 0) {
                float advance = head.x() - heads.get(i - 1).x();
                if (advance < gap * 1.5f || advance > gap * 3.5f) return;
            }
        }
        if (!closedOpposingQuartets(normalized, bar, next, heads, gap)
                || !closedOpposingQuartets(original, bar, next, heads, gap)) return;
        for (int i = 2; i <= 4; i++) {
            var owner = heads.get(i);
            result.set(owner.owner(), owner.note().withTupletRatio(1, 1));
        }
    }

    private static boolean closedOpposingQuartets(
            Ink ink, MeasureRegion bar, MeasureRegion next, List<Head> heads, float gap) {
        float close = (bar.right() + next.left()) * ink.width / 2;
        float bottom = bar.bottom() * ink.height - 2.1f * gap;
        var lines =
                PrintedDirectionStaff.printedRules(
                        ink.raw, ink.width, ink.height, close - gap * 1.25f, bottom, gap);
        if (lines == null) return false;
        var staff =
                new PlayingTechniqueDetector.Staff(
                        lines[0] - 4 * lines[1], lines[0], lines[1], 0, 1);
        if (PrintedCadenceDurations.boundaryColumn(ink.raw, ink.width, staff, close, true, false)
                < 0) return false;
        var budget = new PrintedBeamShafts.Budget(180_000);
        PrintedBeamShafts.Shaft previous = null;
        for (int i = 0; i < 8; i++) {
            var head = heads.get(i);
            int direction = i < 4 ? -1 : 1;
            if (head.note().stemDirection() != -direction) return false;
            var shafts =
                    PrintedBeamShafts.at(
                            ink.raw, ink.width, ink.height, head.x(), head.y(), gap, budget);
            if (shafts.size() != 1 || shafts.get(0).direction() != direction || budget.refused())
                return false;
            var shaft = shafts.get(0);
            if (i % 4 != 0
                    && !FingeringAnnotationFilter.pairedBeamRails(
                            ink.raw, ink.width, ink.height, previous, shaft, gap)) return false;
            previous = shaft;
        }
        return true;
    }

    /** Recover one visually joined upper shaft above a complete bar of double-rail octave dyads. */
    /** Clear false triplet claims only when four complete printed double beams fill the measure. */
    private static boolean closeAcrossPrintedBeams(
            Ink ink,
            Ink original,
            List<MeasureRegion> regions,
            int measure,
            List<Head> source,
            List<ScoreNoteEvent> result) {
        var region = regions.get(measure);
        float gap = (region.bottom() - region.top()) * ink.height / 8.2f;
        if (gap < 10 || gap > 26) return false;
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var groups = new ClosedFourDyadBeam.Head[16];
        var pairs = new ArrayList<Dyad>(16);
        int clef = heads.get(0).note.clefBottomDiatonic();
        boolean wideSixths = Math.abs(heads.get(0).y - heads.get(1).y) >= gap * 2.20f;
        int claimMask = 0, direction = 0;
        for (int slot = 0; slot < groups.length; slot++) {
            var a = heads.get(slot * 2);
            var b = heads.get(slot * 2 + 1);
            var pair = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
            if (slot % 4 == 0) direction = a.note.stemDirection();
            boolean claim = a.note.tupletDivisor() == 3;
            boolean widePair = pair.bottom.y - pair.top.y >= gap * 2.20f;
            if (direction == 0
                    || widePair != wideSixths
                    || (!wideSixths && claim && direction != -1)
                    || !sameShaftDyadGeometry(a, b, gap)
                    || !ordinaryDoubleRail(a.note, clef, direction, claim)
                    || !ordinaryDoubleRail(b.note, clef, direction, claim)) return false;
            float x = (a.x + b.x) * .5f;
            if (slot > 0) {
                float spacing = (x - groups[slot - 1].x()) / gap;
                if (spacing < (wideSixths ? 1.55f : 1.65f) || spacing > (wideSixths ? 3.35f : 2.7f))
                    return false;
            }
            groups[slot] = new ClosedFourDyadBeam.Head(x, pair.top.y, pair.bottom.y);
            pairs.add(pair);
            if (claim) claimMask |= 1 << slot;
        }
        if (claimMask == 0) return false;
        var firstClaims = new ArrayList<Integer>();
        for (int left = claimMask; left != 0; ) {
            int first = Integer.numberOfTrailingZeros(left);
            if (first > 13 || ((left >> first) & 7) != 7) return false;
            firstClaims.add(first);
            left &= ~(7 << first);
        }
        if (!wideSixths) {
            int first = firstClaims.get(0);
            if (firstClaims.size() != 1 || first / 4 == (first + 2) / 4) return false;
        }
        var rows = UnnumberedTripletInk.sourceRows(region, regions, ink.height);
        if (!printedFourRailGroups(ink, regions, measure, pairs, groups, firstClaims, gap, rows)
                || !printedFourRailGroups(original, regions, measure, pairs, groups, firstClaims, gap, rows))
            return false;
        for (int first : firstClaims)
            for (int i = first * 2; i < (first + 3) * 2; i++) {
                var head = heads.get(i);
                result.set(head.owner, head.note.withTupletRatio(1, 1));
            }
        if (closes(heads, result)) return true;
        for (var head : heads) result.set(head.owner, head.note);
        return false;
    }

    private static boolean sameShaftDyadGeometry(Head a, Head b, float gap) {
        float separation = Math.abs(b.y - a.y) / gap;
        return (b.x - a.x <= gap * .16f && separation >= .65f && separation <= 1.4f)
                || (b.x - a.x <= gap * .21f && separation >= 2.20f && separation <= 2.84f);
    }

    private static boolean ordinaryDoubleRail(
            ScoreNoteEvent note, int clef, int direction, boolean claim) {
        return ordinaryGeometry(note, clef)
                && note.stemDirection() == direction
                && note.beamCount() == 2
                && note.augmentationDots() == 0
                && note.unbeamedDurationBeats() == 0
                && note.tupletDivisor() == (claim ? 3 : 1)
                && note.tupletNormalNotes() == (claim ? 2 : 1);
    }

    private static boolean printedFourRailGroups(
            Ink ink,
            List<MeasureRegion> regions,
            int measure,
            List<Dyad> pairs,
            ClosedFourDyadBeam.Head[] columns,
            List<Integer> firstClaims,
            float gap,
            UnnumberedTripletInk.Rows rows) {
        var bar = regions.get(measure);
        var samples = new ArrayList<float[]>();
        var stems = new ArrayList<List<Shaft>>();
        for (var dyad : pairs) {
            var head = dyad.top;
            float estimatedBottom = head.y + head.note.staffStep() * gap * .5f;
            var sample = localRules(ink, head.x, estimatedBottom, gap);
            if (sample != null) samples.add(new float[] {head.x, sample[0], sample[1]});
            var candidates = shafts(ink, dyad, gap, head.note.stemDirection());
            if (candidates.isEmpty()) return false;
            stems.add(candidates);
        }
        if (samples.size() < 8) return false;
        int sampleCount = Math.min(13, samples.size());
        var anchors = new ArrayList<float[]>(sampleCount);
        for (int i = 0; i < sampleCount; i++)
            anchors.add(samples.get(i * (samples.size() - 1) / (sampleCount - 1)));
        var track = StaffPitchTrack.fromVerifiedSamples(anchors);
        if (track == null) return false;
        boolean closed =
                WrappedMeasureBoundaries.confirmsClosedWithVerifiedStaff(
                        ink.raw,
                        ink.width,
                        ink.height,
                        bar,
                        columns[0].x(),
                        columns[15].x(),
                        track);
        var end = pairs.get(15);
        if (!closed
                && end.top.note.stemDirection() == -1
                && end.bottom.y - end.top.y >= gap * 2.20f) {
            float lastShaft = stems.get(15).stream().mapToInt(Shaft::x).max().orElseThrow();
            closed =
                    WrappedMeasureBoundaries.confirmsPackedRightEdgeFromStem(
                            ink.raw,
                            ink.width,
                            ink.height,
                            bar,
                            columns[0].x(),
                            columns[15].x(),
                            lastShaft,
                            track);
        }
        boolean openingWithMeter = !closed
                && WrappedMeasureBoundaries.confirmsOpeningFourFourWithVerifiedStaff(
                        ink.raw, ink.width, ink.height, regions, measure,
                        columns[0].x(), columns[15].x(), track);
        if (!closed && !openingWithMeter) return false;
        for (int first : firstClaims)
            if (claimedBracket(ink, columns, first, gap, rows, track, openingWithMeter)) return false;
        for (int i = 0; i < columns.length - 1; i++) {
            var a = pairs.get(i);
            var b = pairs.get(i + 1);
            int direction = a.top.note.stemDirection();
            boolean cut = i % 4 == 3;
            if (direction != b.top.note.stemDirection()) {
                if (!cut) return false;
                continue;
            }
            if (cut) {
                if (!clearTwoRailCut(ink, stems.get(i), stems.get(i + 1), track, gap, direction)
                        || !beams(ink, stems.get(i), stems.get(i + 1), a, b, gap, direction)
                                .isEmpty()) return false;
            } else if (beams(ink, stems.get(i), stems.get(i + 1), a, b, gap, direction, track)
                            .isEmpty()
                    && !(i % 4 == 1
                            && direction == -1
                            && obscuredMiddleRailSeam(ink, stems, pairs, i, gap, track))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Adjacent spans of the same sloping double beam can establish the separation where
     * the bottom staff rule fills the paper between the rails over the middle span.
     */
    private static boolean obscuredMiddleRailSeam(
            Ink ink,
            List<List<Shaft>> stems,
            List<Dyad> dyads,
            int i,
            float gap,
            StaffPitchTrack staff) {
        for (int n = i - 1; n <= i + 2; n++) {
            var pair = dyads.get(n);
            if (pair.bottom.y - pair.top.y < gap * 2.2f) return false;
        }
        var left =
                beams(
                        ink,
                        stems.get(i - 1),
                        stems.get(i),
                        dyads.get(i - 1),
                        dyads.get(i),
                        gap,
                        -1,
                        staff);
        var right =
                beams(
                        ink,
                        stems.get(i + 1),
                        stems.get(i + 2),
                        dyads.get(i + 1),
                        dyads.get(i + 2),
                        gap,
                        -1,
                        staff);
        if (left.isEmpty() || right.isEmpty()) return false;
        var before = left.get(0);
        var after = right.get(0);
        int x0 = before.x1, x1 = after.x0;
        if (x1 - x0 < gap * 1.5f
                || x1 - x0 > gap * 3.2f
                || Math.abs(before.separation - after.separation) > gap * .22f) return false;
        float from = (before.y1 - before.y0) / (float) (before.x1 - before.x0);
        float through = (after.y1 - before.y0) / (float) (after.x1 - before.x0);
        float to = (after.y1 - after.y0) / (float) (after.x1 - after.x0);
        if (Math.abs(through) < .10f
                || Math.abs(through) > .35f
                || Math.abs(from - through) > .12f
                || Math.abs(to - through) > .12f) return false;
        int y0 = Math.round(before.y0 + through * (x0 - before.x0));
        int y1 = Math.round(before.y0 + through * (x1 - before.x0));
        if (Math.abs(y0 - before.y1) > .30f * gap || Math.abs(y1 - after.y0) > .30f * gap)
            return false;

        int margin = Math.round(gap * .3f), radius = Math.max(2, Math.round(gap * .12f));
        int first = x0 + margin, last = x1 - margin;
        int d = Math.round((before.separation + after.separation) * .5f);
        int offset = Math.round(gap * .26f);
        for (int shift = -offset; shift <= offset; shift++) {
            var rail = new Rails(x0, y0 + shift, x1, y1 + shift, d);
            if (band(ink, rail, first, last, 0, radius) < .97f
                    || band(ink, rail, first, last, d, radius) < .97f
                    || band(ink, rail, first, last, 2 * d, radius) >= .60f) continue;
            int coveredByRule = 0;
            for (int x = first; x <= last; x++) {
                int y = rail.at(x) - d / 2;
                var frame = staff.at(x);
                if (Math.abs(y - frame[0]) <= frame[1] * .22f && stroke(ink, x, y, 1) >= 2)
                    coveredByRule++;
            }

            if (coveredByRule >= (last - first + 1) * .60f) return true;
        }
        return false;
    }

    private static boolean clearTwoRailCut(
            Ink ink,
            List<Shaft> left,
            List<Shaft> right,
            StaffPitchTrack staff,
            float gap,
            int direction) {
        int from = left.stream().mapToInt(Shaft::x).max().orElseThrow() + Math.round(gap * .42f);
        int to = right.stream().mapToInt(Shaft::x).min().orElseThrow() - Math.round(gap * .42f);
        int inward = -direction;
        int a = inward * left.stream().mapToInt(s -> s.tip * inward).max().orElseThrow();
        int b = inward * right.stream().mapToInt(s -> s.tip * inward).max().orElseThrow();
        int top = Math.min(a, b), bottom = Math.max(a, b);
        int margin = Math.round(gap * .32f), depth = Math.round(gap * 1.1f);
        if (inward > 0) {
            top -= depth;
            bottom += margin;
        } else {
            top -= margin;
            bottom += depth;
        }
        if (from < 0 || to >= ink.width || top < 0 || bottom >= ink.height) return false;
        int columns = 0;
        for (int x = from; x <= to; x++) {
            var frame = staff.at(x);
            int offRule = 0, white = 0;
            for (int y = top; y <= bottom; y++) {
                float level = (frame[0] - y) / frame[1];
                if (level >= -.2f && level <= 4.2f && Math.abs(level - Math.round(level)) <= .19f)
                    continue;
                offRule++;
                if (!ink.dark(x, y)) white++;
            }
            columns = offRule > gap && white >= .9f * offRule ? columns + 1 : 0;
            if (columns >= Math.max(3, Math.round(gap * .26f))) return true;
        }
        return false;
    }

    /** Recover one visually joined upper shaft above a complete bar of double-rail octave dyads. */
    private static boolean restoreSteppedOctaveShaft(
            Ink ink, MeasureRegion bar, List<Head> source, List<ScoreNoteEvent> result) {
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        float gap = (bar.bottom() - bar.top()) * ink.height / 8.2f;
        if (gap < 10 || gap > 26) return false;
        float bottom = bar.bottom() * ink.height - 2.1f * gap;
        int clef = heads.get(0).note.clefBottomDiatonic();
        var columns = new ClosedFourDyadBeam.Head[16];
        var directions = new int[4];
        int missing = -1;
        for (int slot = 0; slot < columns.length; slot++) {
            var a = heads.get(slot * 2);
            var b = heads.get(slot * 2 + 1);
            var upper = a.y < b.y ? a : b;
            var lower = a.y < b.y ? b : a;
            var topNote = upper.note;
            var bottomNote = lower.note;
            int direction = bottomNote.stemDirection();
            if (!ordinary(topNote, clef) || !ordinary(bottomNote, clef)
                    || topNote.augmentationDots() != 0 || bottomNote.augmentationDots() != 0
                    || direction == 0 || topNote.stemDirection() != direction
                    || bottomNote.beamCount() != 2 || bottomNote.unbeamedDurationBeats() != 0
                    || Math.abs(topNote.staffStep() - bottomNote.staffStep()) != 7
                    || b.x - a.x > gap * .16f
                    || lower.y - upper.y < gap * 3.15f
                    || lower.y - upper.y > gap * 3.8f) return false;
            if (slot % 4 == 0) directions[slot / 4] = -direction;
            else if (directions[slot / 4] != -direction) return false;
            if (topNote.beamCount() == 0 && topNote.unbeamedDurationBeats() == 1) {
                if (missing >= 0 || direction != -1 || slot % 4 == 0 || slot % 4 == 3) return false;
                missing = slot;
            } else if (topNote.beamCount() != 2 || topNote.unbeamedDurationBeats() != 0) {
                return false;
            }
            columns[slot] = new ClosedFourDyadBeam.Head((a.x + b.x) * .5f, upper.y, lower.y);
        }
        if (missing < 0) return false;
        var openingRails = DottedDyadMeasureSeparator.localRails(
                ink.raw, ink.width, ink.height, Math.round(bar.left() * ink.width), bottom, gap);
        if (openingRails == null) return false;
        var staff = StaffPitchTrack.linear(ink.width, openingRails[0], openingRails[1], 0);
        if (!WrappedMeasureBoundaries.confirmsClosedWithVerifiedStaff(
                ink.raw, ink.width, ink.height, bar, columns[0].x(), columns[15].x(), staff)) return false;
        for (int group = 0; group < directions.length; group++) {
            int start = group * 4;
            var four = java.util.Arrays.copyOfRange(columns, start, start + 4);
            if (group == missing / 4) {
                if (!ClosedFourDyadBeam.provesOctavesWithSteppedStem(
                        ink.raw, ink.width, ink.height, four, gap, bottom, directions[group], missing % 4)) return false;
            } else if (!ClosedFourDyadBeam.provesOctaves(
                    ink.raw, ink.width, ink.height, four, gap, bottom, directions[group], false)) {
                return false;
            }
        }
        var headA = heads.get(missing * 2);
        var headB = heads.get(missing * 2 + 1);
        var owner = headA.y < headB.y ? headA : headB;
        result.set(owner.owner, asSharedBeam(owner.note, 2));
        if (closes(source, result)) return true;
        result.set(owner.owner, owner.note);
        return false;
    }

    private static void closeToEighth(
            Ink ink, Ink original, MeasureRegion region, boolean opening,
            List<MeasureRegion> regions, List<Head> heads, List<ScoreNoteEvent> result) {
        heads.sort(Comparator.comparingDouble(Head::x));
        var terminal = heads.subList(28, 30);
        boolean intact = terminal.stream().allMatch(head -> head.note.beamCount() == 1
                && head.note.stemDirection() == -1 && head.note.unbeamedDurationBeats() == 0);
        float gap = (region.bottom() - region.top()) * ink.height / 8.2f;
        if (gap < 12
                || gap > 23
                || (region.right() - region.left()) * ink.width < gap * 42
                || (region.right() - region.left()) * ink.width > gap * 62
                || !printedBoundaries(ink, region, opening && intact, heads))
            return;
        if (intact && (original == null || !printedBoundaries(original, region, opening, heads))) return;

        if (!intact) {
            float sum = 0;
            int sampleCount = 0;
            float floor = region.bottom() * ink.height - gap * 2.1f;
            for (int slot = 0; slot < 15; slot += 2) {
                var sample =
                        PrintedDirectionStaff.printedRules(
                                ink.raw, ink.width, ink.height, heads.get(slot * 2).x, floor, gap);
                if (sample != null && Math.abs(sample[1] - gap) <= gap * .10f) {
                    sum += sample[1];
                    sampleCount++;
                }
            }
            if (sampleCount >= 3) gap = sum / sampleCount;
            else return;
        }
        int clef = heads.get(0).note.clefBottomDiatonic();
        var geometry = new ClosedFourDyadBeam.Head[15];
        int tripletClaims = 0;
        for (int slot = 0; slot < 15; slot++) {
            var a = heads.get(slot * 2);
            var b = heads.get(slot * 2 + 1);
            if (b.x - a.x > gap * .15f
                    || Math.abs(b.y - a.y) < gap * .65f
                    || Math.abs(b.y - a.y) > gap * 1.4f) return;
            int actual = a.note.tupletDivisor();
            if (actual != 1 && actual != 3) return;
            if (actual == 3) tripletClaims |= 1 << slot;
            for (var head : List.of(a, b)) {
                var n = head.note;
                boolean allowIncomingTie = intact && slot == 0 && actual == 1;
                if (!ordinaryGeometry(n, clef, allowIncomingTie)
                        || n.augmentationDots() != 0
                        || n.tupletDivisor() != actual
                        || n.tupletNormalNotes() != (actual == 3 ? 2 : 1)) return;
                if (slot < 14
                        && (n.beamCount() != 2
                                || n.stemDirection() != -1
                                || n.unbeamedDurationBeats() != 0)) return;
            }
            var upper = a.y < b.y ? a : b;
            var lower = a.y < b.y ? b : a;
            if (slot == 14 && actual != 1) return;
            if (slot == 14 && !intact
                    && (upper.note.beamCount() != 0
                            || upper.note.stemDirection() != -1
                            || upper.note.unbeamedDurationBeats() != 1
                            || lower.note.beamCount() != 1
                            || lower.note.stemDirection() != 0
                            || lower.note.unbeamedDurationBeats() != 0)) return;
            geometry[slot] = new ClosedFourDyadBeam.Head((a.x + b.x) * .5f, upper.y, lower.y);
        }
        if (!hasContainedTripletClaims(tripletClaims)) return;
        for (int i = 0; i < 12; i += 4) {
            var group = java.util.Arrays.copyOfRange(geometry, i, i + 4);
            boolean roomForPrintedAccidentals = true;
            for (int j = 1; j < 4; j++) {
                if (group[j].x() - group[j - 1].x() <= gap * 4.7f) continue;
                roomForPrintedAccidentals &=
                        heads.get((i + j) * 2).note.writtenAccidental()
                                        != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                                || heads.get((i + j) * 2 + 1).note.writtenAccidental()
                                        != ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
            }
            if (!roomForPrintedAccidentals
                    || !ClosedFourDyadBeam.provesWithAccidentalSpacing(
                            ink.raw, ink.width, ink.height, group, gap, 1)
                    || intact && !ClosedFourDyadBeam.provesWithAccidentalSpacing(
                            original.raw, original.width, original.height, group, gap, 1)) return;
        }
        var close = java.util.Arrays.copyOfRange(geometry, 12, 15);
        if (intact) {
            if (!ClosedFourDyadBeam.provesIntactTwoSixteenthsAndEighth(
                            ink.raw, ink.width, ink.height, close, gap)
                    || !ClosedFourDyadBeam.provesIntactTwoSixteenthsAndEighth(
                            original.raw, original.width, original.height, close, gap)) return;
        } else if (!ClosedFourDyadBeam.provesTwoSixteenthsAndEighth(
                ink.raw, ink.width, ink.height, close, gap)) return;
        if (intact) {
            var normalizedTrack = WrappedMeasureBoundaries.verifiedTripletStaff(
                    ink.raw, ink.width, ink.height, region);
            var originalTrack = WrappedMeasureBoundaries.verifiedTripletStaff(
                    original.raw, original.width, original.height, region);
            if (normalizedTrack == null || originalTrack == null) return;
            var rows = UnnumberedTripletInk.sourceRows(region, regions, ink.height);
            for (int i = 0; i < 12; i += 4) {
                int claims = tripletClaims >> i & 0xf;
                if (claims == 0) continue;
                int first = i + Integer.numberOfTrailingZeros(claims);
                if (claimedBracket(ink, geometry, first, gap, rows, normalizedTrack, false)
                        || claimedBracket(original, geometry, first, gap, rows, originalTrack, false)) return;
            }
        }
        var revised = new ArrayList<ScoreNoteEvent>(30);
        for (int i = 0; i < 30; i++) {
            var note = heads.get(i).note;
            revised.add(i < 28 ? note.withTupletRatio(1, 1)
                    : intact ? note : asSharedBeam(note, 1));
        }
        for (int i = 0; i < 30; i++) {
            var placed = ScoreNoteTiming.writtenPlacement(revised.get(i), revised, 4f).orElse(null);
            if (placed == null
                    || Math.abs(placed.onsetBeats() - (i / 2) * .25) > 1e-6
                    || Math.abs(placed.durationBeats() - (i < 28 ? .25 : .5)) > 1e-6) return;
        }
        for (int i = 0; i < 30; i++) result.set(heads.get(i).owner, revised.get(i));
    }

    private static boolean claimedBracket(
            Ink ink, ClosedFourDyadBeam.Head[] geometry, int first, float gap,
            UnnumberedTripletInk.Rows rows, StaffPitchTrack track, boolean verifyContinuingStroke) {
        float top = Float.POSITIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
        int tip = 0, direction = 0;
        var budget = new PrintedBeamShafts.Budget(100_000);
        for (int i = first; i < first + 3; i++) {
            var chord = geometry[i];
            var shafts = PrintedBeamShafts.at(ink.raw, ink.width, ink.height,
                    chord.x(), chord.top(), gap, budget);
            if (shafts.size() != 1) return true;
            var shaft = shafts.get(0);
            if (i == first) {
                direction = shaft.direction();
                tip = shaft.endY();
            } else if (shaft.direction() != direction) {
                return true;
            } else {
                tip = direction == 1 ? Math.max(tip, shaft.endY()) : Math.min(tip, shaft.endY());
            }
            top = Math.min(top, chord.top());
            bottom = Math.max(bottom, chord.bottom());
        }
        return UnnumberedTripletInk.bracketBesideBeam(ink.raw, ink.width, ink.height, gap,
                geometry[first].x(), geometry[first + 2].x(), top, bottom, tip, rows, track, verifyContinuingStroke);
    }

    private static boolean printedBoundaries(
            Ink ink, MeasureRegion region, boolean opening, List<Head> heads) {
        float first = heads.get(0).x, last = heads.get(29).x;
        if (WrappedMeasureBoundaries.confirmsClosed(ink.raw, ink.width, ink.height, region, first, last)) return true;
        if (!opening || !WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(
                ink.raw, ink.width, ink.height, region, last)) return false;
        var track = WrappedMeasureBoundaries.verifiedTripletStaff(
                ink.raw, ink.width, ink.height, region);
        if (track == null) return false;
        var frame = track.at(first - 2 * track.at(first)[1]);
        float gap = frame[1];
        int top = Math.round(frame[0] - 4 * gap);
        int left = Math.max(Math.round(region.left() * ink.width), Math.round(first - 3.2f * gap));
        int right = Math.round(first - .7f * gap);
        if (right - left < gap * 1.5f || top < 3 || Math.round(frame[0]) + 3 >= ink.height) return false;
        var crop = new MeterChangeDetector.Crop(left, top - 3, right, Math.round(frame[0]) + 3, top, gap);
        var glyph = MeterFontMatcher.readCrop(ink.raw, ink.width, ink.height, crop);
        return glyph.text().equals("4/4") && glyph.topScore() >= .86f && glyph.bottomScore() >= .86f;
    }

    /** Each candidate claim must contain three adjacent dyads under one closed double rail. */
    static boolean hasContainedTripletClaims(int claims) {
        if (claims == 0 || (claims & ~0xfff) != 0) return false;
        for (int bit = 0; bit < 12; bit += 4) {
            int underOneRail = claims >> bit & 0xf;
            if (underOneRail != 0 && underOneRail != 0b0111 && underOneRail != 0b1110) return false;
        }
        return true;
    }

    private static ScoreNoteEvent asSharedBeam(ScoreNoteEvent n, int beams) {
        return new ScoreNoteEvent(
                n.measureIndex(),
                n.positionInMeasure(),
                n.staffStep(),
                n.staffIndex(),
                n.staffCount(),
                n.pageY(),
                n.tiedFromPrevious(),
                n.augmentationDots(),
                beams,
                n.writtenAccidental(),
                0,
                n.tupletDivisor(),
                n.followingRestBeats(),
                n.articulations(),
                n.clefBottomDiatonic(),
                n.crossStaffBeam(),
                n.leadingRestBeats(),
                n.compactOpening(),
                n.octaveShift(),
                n.boundaryTies(),
                n.tupletNormalNotes(),
                -1,
                n.kind(),
                n.visualSize(),
                n.harmonicSemitones(),
                n.openStringTouch());
    }


    /** A complete 4/4 heading can enclose four printed quartets of parallel thirds. */
    private static boolean closedWideHeaderThirds(
            Ink normalized, Ink original, MeasureRegion bar, MeasureRegion next, List<Head> source) {
        if (original == null || next == null) return false;
        float gap = (bar.bottom() - bar.top()) * normalized.height / 8.2f;
        float left = bar.left() * normalized.width, right = bar.right() * normalized.width;
        float closingGap = (next.left() - bar.right()) * normalized.width;
        if (gap < 10 || gap > 27 || right - left < 46 * gap || right - left > 55 * gap
                || closingGap < .65f * gap || closingGap > 1.3f * gap
                || Math.abs(next.top() - bar.top()) * normalized.height > gap * .35f)
            return false;
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        int clef = heads.get(0).note().clefBottomDiatonic();
        var columns = new ArrayList<Dyad>(16);
        int inferredTriplets = 0;
        for (int i = 0; i < 16; i++) {
            var a = heads.get(2 * i);
            var b = heads.get(2 * i + 1);
            var pair = a.y() < b.y() ? new Dyad(a, b) : new Dyad(b, a);
            int ratio = a.note().tupletDivisor();
            if (ratio != 1 && ratio != 3 || b.note().tupletDivisor() != ratio
                    || b.x() - a.x() > .17f * gap
                    || Math.abs(a.note().staffStep() - b.note().staffStep()) != 2
                    || pair.bottom().y() - pair.top().y() < .84f * gap
                    || pair.bottom().y() - pair.top().y() > 1.26f * gap)
                return false;
            for (var head : List.of(a, b)) {
                var n = head.note();
                if (!ordinaryGeometry(n, clef) || n.stemDirection() != -1
                        || n.beamCount() != 2 || n.augmentationDots() != 0
                        || n.unbeamedDurationBeats() != 0
                        || n.tupletNormalNotes() != (ratio == 3 ? 2 : 1)) return false;
            }
            if (i > 0) {
                float advance = pair.top().x() - columns.get(i - 1).top().x();
                if (advance < 2.55f * gap || advance > 3.95f * gap) return false;
            }
            if (ratio == 3) inferredTriplets++;
            columns.add(pair);
        }
        float first = columns.get(0).top().x(), last = columns.get(15).top().x();
        return inferredTriplets == 6 && first - left >= 1.9f * gap && first - left <= 3.25f * gap
                && right - last >= 2.3f * gap && right - last <= 3.5f * gap
                && provesWideHeaderThirds(normalized, bar, next, columns, gap)
                && provesWideHeaderThirds(original, bar, next, columns, gap);
    }

    private static boolean provesWideHeaderThirds(
            Ink ink, MeasureRegion bar, MeasureRegion next, List<Dyad> columns, float gap) {
        float left = bar.left() * ink.width, close = (bar.right() + next.left()) * ink.width * .5f;
        float hint = bar.bottom() * ink.height - 2.1f * gap;
        var opening = PrintedDirectionStaff.printedRules(ink.raw, ink.width, ink.height, left + 2.8f * gap, hint, gap);
        var closing = PrintedDirectionStaff.printedRules(ink.raw, ink.width, ink.height, close - 1.25f * gap, hint, gap);
        if (opening == null || closing == null || Math.abs(opening[1] - closing[1]) > gap * .12f
                || !closedSingleRule(ink, close - closing[1] * .5f, closing)) return false;
        float first = columns.get(0).top().x(), g = opening[1];
        int staffTop = Math.round(opening[0] - 4 * g);
        var crop = new MeterChangeDetector.Crop(
                Math.round(first - 4.35f * g), Math.round(staffTop - g * .45f),
                Math.round(first - 1.45f * g), Math.round(opening[0] + g * .65f), staffTop, g);
        var meter = MeterFontMatcher.readCrop(ink.raw, ink.width, ink.height, crop);
        if (!meter.text().equals("4/4") || meter.topScore() < .86f || meter.bottomScore() < .86f)
            return false;
        var budget = new PrintedBeamShafts.Budget(420_000);
        PrintedBeamShafts.Shaft previous = null;
        boolean contact = false;
        for (int i = 0; i < columns.size(); i++) {
            var pair = columns.get(i);
            var upper = PrintedBeamShafts.at(ink.raw, ink.width, ink.height, pair.top().x(), pair.top().y(), gap, budget);
            var lower = PrintedBeamShafts.at(ink.raw, ink.width, ink.height, pair.bottom().x(), pair.bottom().y(), gap, budget);
            if (upper.size() != 1 || lower.size() != 1 || budget.refused()) return false;
            var a = upper.get(0);
            var b = lower.get(0);
            if (a.direction() != 1 || b.direction() != 1
                    || Math.max(a.left(), b.left()) > Math.min(a.right(), b.right())
                    || Math.abs(a.endY() - b.endY()) > .20f * gap) return false;
            if (previous != null) {
                boolean connected = FingeringAnnotationFilter.pairedBeamRails(
                        ink.raw, ink.width, ink.height, previous, b, gap);
                if (i % 4 == 0) {
                    if (connected || railsWithContactingMiddle(ink, previous, b, gap)) return false;
                } else if (!connected) {
                    if (contact || !railsWithContactingMiddle(ink, previous, b, gap)) return false;
                    contact = true;
                }
            }
            previous = b;
        }
        return true;
    }

    /** Two full rail cores can touch in the middle while leaving separate ends. */
    private static boolean railsWithContactingMiddle(
            Ink ink, PrintedBeamShafts.Shaft first, PrintedBeamShafts.Shaft second, float gap) {
        int x0 = (first.left() + first.right()) / 2, x1 = (second.left() + second.right()) / 2;
        int span = x1 - x0;
        if (span < 2.6f * gap || span > 3.35f * gap) return false;
        int margin = Math.round(gap * .35f), edge = Math.round(gap * .42f);
        int start = x0 + margin, end = x1 - margin;
        int width = end - start + 1;
        int lowOffset = Math.round(.2f * gap), highOffset = Math.round(.57f * gap);
        int radius = Math.max(2, Math.round(.12f * gap));
        for (int a = lowOffset; a <= highOffset; a++) {
            int y0 = first.endY() - a;
            for (int b = lowOffset; b <= highOffset; b++) {
                int y1 = second.endY() - b;
                if (Math.abs(y1 - y0) > span * .22f) continue;
                var axis = new Rails(x0, y0, x1, y1, 0);
                if (band(ink, axis, start, end, 0, radius) < .97f) continue;
                for (int d = Math.round(.62f * gap); d <= Math.round(.84f * gap); d++) {
                    if (band(ink, axis, start, end, d, radius) < .97f
                            || hasThirdRail(ink, axis, start, end, d, gap, radius)) continue;
                    int open = 0, left = 0, right = 0;
                    for (int x = start; x <= end; x++) {
                        if (ink.dark(x, axis.at(x) - d / 2)) continue;
                        open++;
                        if (x < start + edge) left++;
                        if (x > end - edge) right++;
                    }
                    if (open >= width * .42f && left >= edge * .70f && right >= edge * .70f)
                        return true;
                }
            }
        }
        return false;
    }

    private static boolean hasThirdRail(
            Ink ink, Rails axis, int start, int end, int separation, float gap, int radius) {
        int min = separation + Math.round(.62f * gap);
        int max = separation + Math.round(.84f * gap);
        for (int shift = min; shift <= max; shift++) {
            if (band(ink, axis, start, end, shift, radius) >= .72f) return true;
        }
        return false;
    }

    private static boolean closedHeaderSixteenths(Ink ink,MeasureRegion bar,List<Head> heads) {
        long triplets=heads.stream().filter(h -> h.note.tupletDivisor()==3).count();
        long dots=heads.stream().filter(h -> h.note.augmentationDots()!=0).count();
        if (triplets!=12 || dots!=2) return false;
        float left=bar.left()*ink.width,right=bar.right()*ink.width;
        float gap=(bar.bottom()-bar.top())*ink.height/8.2f;
        if (gap<10 || gap>26 || right-left<32*gap || right-left>42*gap) return false;
        heads.sort(Comparator.comparingDouble(Head::x));
        var samples=new ArrayList<float[]>();
        for (int slot=0;slot<16;slot++) {
            if (slot%5==2) continue;
            var head=heads.get(slot*2);
            float hint=head.y+head.note.staffStep()*gap*.5f;
            var sample=localRules(ink,head.x,hint,gap);
            if (sample!=null) samples.add(new float[]{head.x,sample[0],sample[1]});
        }
        if (samples.size()<8) return false;
        var track=StaffPitchTrack.fromVerifiedSamples(samples);
        var entry=track.at(heads.get(0).x);
        gap=entry[1];
        float first=heads.get(0).x, last=heads.get(31).x;
        if (first-left<gap*4.7f || first-left>gap*6.6f || right-last<gap*1.15f
                || right-last>gap*2.0f || !closedSingleRule(ink,right,track.at(right))) return false;
        int top=Math.round(entry[0]-4*gap);
        int from=Math.round(first-gap*4.35f), to=Math.round(first-gap*1.45f);
        var crop=new MeterChangeDetector.Crop(from,Math.round(top-gap*.45f),to,
                Math.round(entry[0]+gap*.65f),top,gap);
        var meter=MeterFontMatcher.readCrop(ink.raw,ink.width,ink.height,crop);
        if (!meter.text().equals("4/4") || meter.topScore()<.80f || meter.bottomScore()<.80f)
            return false;

        List<Dyad> pairs=new ArrayList<>();
        int dotted=-1;
        int clef=heads.get(0).note.clefBottomDiatonic();
        for (int slot=0;slot<16;slot++) {
            var a=heads.get(slot*2);var b=heads.get(slot*2+1);
            var pair=a.y<b.y?new Dyad(a,b):new Dyad(b,a);
            int direction=slot<4 || slot>=12 ? 1 : -1;
            int ratio=slot<3 || slot>=13 ? 3 : 1;
            for (var head:List.of(a,b)) {
                var n=head.note;
                if (!ordinaryGeometry(n,clef) || n.stemDirection()!=direction || n.beamCount()!=2
                        || n.unbeamedDurationBeats()!=0 || n.tupletDivisor()!=ratio
                        || n.tupletNormalNotes()!=(ratio==3?2:1)) return false;
            }
            if (b.x-a.x>gap*.16f || pair.bottom.y-pair.top.y<gap*2.25f
                    || pair.bottom.y-pair.top.y>gap*2.85f
                    || a.note.augmentationDots()!=b.note.augmentationDots()) return false;
            if (a.note.augmentationDots()!=0) {
                if (dotted>=0 || slot<4 || slot>=8 || a.note.augmentationDots()!=2) return false;
                dotted=slot;
            }
            if (slot>0) {
                float spacing=pair.top.x-pairs.get(slot-1).top.x;
                if (spacing<gap*1.52f || spacing>gap*3.35f) return false;
            }
            pairs.add(pair);
        }
        if (dotted<0 || dotsByHead(ink,pairs.get(dotted),pairs.get(dotted+1),gap,track)) return false;
        List<List<Shaft>> axes=new ArrayList<>();
        for (int i=0;i<16;i++) {
            var stems=shafts(ink,pairs.get(i),gap,pairs.get(i).top.note.stemDirection());
            if (stems.isEmpty()) return false;
            axes.add(stems);
        }
        for (int i=0;i<15;i++) {
            var a=pairs.get(i);var b=pairs.get(i+1);
            int direction=a.top.note.stemDirection();
            int nextDirection=b.top.note.stemDirection();
            if (direction!=nextDirection) {
                if (i%4!=3) return false;
                continue;
            }
            var binding=beams(ink,axes.get(i),axes.get(i+1),a,b,gap,direction);
            if (i%4==3 ? !binding.isEmpty() : binding.isEmpty()) return false;
        }
        return true;
    }

    private static boolean closedSingleRule(Ink ink,float boundary,float[] rules) {
        float gap=rules[1],top=rules[0]-4*gap;
        int column=PrintedCadenceDurations.boundaryColumn(ink.raw,ink.width,
                new PlayingTechniqueDetector.Staff(top,rules[0],gap,0,1),
                boundary+gap*.5f,true,false);
        return column>=0 && !DottedDyadMeasureSeparator.continuesBeyondStaff(
                ink.raw,ink.width,ink.height,column,Math.round(top),Math.round(rules[0]),gap)
                && !ClosedHeadBarlineGuard.attached(
                        ink.raw,ink.width,ink.height,column,Math.round(top),Math.round(rules[0]),gap);
    }

    private static boolean dotsByHead(Ink ink,Dyad pair,Dyad next,float gap,StaffPitchTrack track) {
        for (var head:List.of(pair.top,pair.bottom)) {
            int from=Math.round(head.x+gap*.7f);
            int to=Math.round(Math.min(head.x+gap*2.15f,next.top.x-gap*.88f));
            int minY=Math.round(head.y-gap*.50f),maxY=Math.round(head.y+gap*.50f);
            if (to-from<gap*.6f) return true;
            int accidental=0;
            for (int x=from+Math.round(gap*.47f);x<=to;x++)
                for (int y=minY;y<=maxY;y++) {
                    int span=Math.round(gap*.52f);
                    if (DoubleSharpGlyph.matchesRaw(ink.raw,ink.width,ink.height,
                            x-span,y-span,x+span,y+span,gap)) {
                        accidental=x;
                        break;
                    }
                }
            for (int x=from;x<=to;x++) {
                if (accidental!=0 && Math.abs(x-accidental)<gap*.55f) continue;
                var frame=track.at(x);
                for (int y=minY;y<=maxY;y++) {
                    float rule=(frame[0]-y)/frame[1];
                    if (rule>=-.2f && rule<=4.2f && Math.abs(rule-Math.round(rule))<.23f) continue;
                    if (ink.dark(x,y) && ink.dark(x,y+1) && ink.dark(x+1,y) && ink.dark(x+1,y+1)
                            && !ink.dark(x-3,y) && !ink.dark(x+4,y)) return true;
                }
            }
        }
        return false;
    }

    private static Dyad prove(Ink ink,MeasureRegion bar,List<Head> heads) {
        float left=bar.left()*ink.width,right=bar.right()*ink.width;
        float gap=(bar.bottom()-bar.top())*ink.height/8.2f;
        if (gap<10 || gap>26 || right-left<32*gap || right-left>47*gap) return null;
        heads.sort(Comparator.comparingDouble(Head::x));
        var samples=new ArrayList<float[]>();
        for (int i=0;i<=12;i++) {
            float x=left+(right-left)*i/12;
            var nearest=heads.stream().min(Comparator.comparingDouble(s->Math.abs(x-s.x))).orElseThrow();
            float hint=nearest.y+nearest.note.staffStep()*gap*.5f;
            var sample=localRules(ink,x,hint,gap);
            if (sample!=null) samples.add(new float[]{x,sample[0],sample[1]});
        }
        if (samples.size()<7) return null;
        var track=StaffPitchTrack.fromVerifiedSamples(samples);
        gap=track.at((left+right)*.5f)[1];
        if (!barline(ink,left,track.at(left)) || !barline(ink,right,track.at(right))
                || heads.get(0).x-left<gap*.95f || heads.get(0).x-left>gap*2.8f
                || right-heads.get(31).x<gap*.75f || right-heads.get(31).x>gap*2.4f) return null;
        List<Dyad> pairs=new ArrayList<>();
        int dots=-1,unbeamed=-1;
        int clef=heads.get(0).note.clefBottomDiatonic();
        for (int slot=0;slot<16;slot++) {
            var first=heads.get(slot*2);var second=heads.get(slot*2+1);
            var pair=first.y<second.y?new Dyad(first,second):new Dyad(second,first);
            var a=pair.top.note;var b=pair.bottom.note;
            if (!ordinary(a,clef) || !ordinary(b,clef) || a.beamCount()!=b.beamCount()
                    || a.augmentationDots()!=b.augmentationDots()
                    || second.x-first.x>gap*.18f || pair.bottom.y-pair.top.y<gap*2.35f
                    || pair.bottom.y-pair.top.y>gap*2.8f) return null;
            if (a.beamCount()==0) {
                if (unbeamed>=0 || slot%4==0 || slot%4==3 || a.unbeamedDurationBeats()!=1
                        || b.unbeamedDurationBeats()!=1 || a.augmentationDots()!=0) return null;
                unbeamed=slot;
            } else if (a.beamCount()!=2 || a.unbeamedDurationBeats()!=0
                    || b.unbeamedDurationBeats()!=0) return null;
            if (a.augmentationDots()!=0) {
                if (dots>=0 || a.augmentationDots()!=1 || slot==0 || slot==15) return null;
                dots=slot;
            }
            if (slot>0) {
                float dx=pair.top.x-pairs.get(slot-1).top.x;
                if (dx<gap*1.75f || dx>gap*2.95f) return null;
            }
            pairs.add(pair);
        }
        if (dots<0) return null;
        List<List<Shaft>> axes=new ArrayList<>();
        for (Dyad pair:pairs) {
            var shafts=shafts(ink,pair,gap);
            if (shafts.isEmpty()) return null;
            axes.add(shafts);
        }
        var bindings=new ArrayList<List<Rails>>();
        for (int slot=0;slot<15;slot++) {
            var rails=beams(ink,axes.get(slot),axes.get(slot+1),pairs.get(slot),pairs.get(slot+1),gap);
            bindings.add(rails);
            if (slot%4==3) {
                if (!rails.isEmpty() || !clearBreak(ink,axes.get(slot),axes.get(slot+1),pairs.get(slot),pairs.get(slot+1),gap,track))
                    return null;
            } else if (rails.isEmpty()) return null;
        }
        if (compensatingInk(ink,bindings,pairs,dots,gap,track)) return null;
        return pairs.get(dots);
    }

    private static Dyad terminalOctaveHookDot(Ink ink, MeasureRegion region, List<Head> source) {
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        float gap = (region.bottom() - region.top()) * ink.height / 8.2f;
        if (gap < 10 || gap > 26) return null;
        int clef = heads.get(0).note.clefBottomDiatonic();
        for (int i = 0; i < heads.size(); i++) {
            var note = heads.get(i).note;
            if (!ordinary(note, clef)
                    || note.beamCount() != 2
                    || note.unbeamedDurationBeats() != 0
                    || note.augmentationDots() != (i >= 30 ? 1 : 0)) return null;
        }

        var last = new ClosedFourDyadBeam.Head[4];
        Dyad finalPair = null;
        for (int slot = 0; slot < 16; slot++) {
            var a = heads.get(slot * 2);
            var b = heads.get(slot * 2 + 1);
            if (b.x - a.x > gap * .20f || Math.abs(a.note.staffStep() - b.note.staffStep()) != 7)
                return null;
            if (slot >= 12) {
                var pair = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
                last[slot - 12] =
                        new ClosedFourDyadBeam.Head((a.x + b.x) * .5f, pair.top.y, pair.bottom.y);
                finalPair = pair;
            }
        }
        var pair = finalPair;
        float edge = region.right() * ink.width;
        float hint = region.bottom() * ink.height - gap * 2.1f;
        if (edge - pair.top.x < gap * 1.2f || edge - pair.top.x > gap * 2.55f) return null;
        var staff = localRules(ink, pair.top.x, hint, gap);
        if (staff == null
                || !barline(ink, edge, staff)
                || !ClosedFourDyadBeam.provesOctaves(
                        ink.raw, ink.width, ink.height, last, staff[1], staff[0], 1, false))
            return null;
        int column =
                OctaveMarkDetector.hookedOctaveDashBesideHead(
                        ink.raw, ink.width, ink.height, edge, pair.top.x, pair.top.y, staff[1]);
        if (column < 0
                || detachedDotBeforeHook(ink, pair.top, column, staff)
                || detachedDotBeforeHook(ink, pair.bottom, column, staff)) return null;
        return pair;
    }

    private static boolean detachedDotBeforeHook(Ink ink, Head head, int hook, float[] rules) {
        float gap = rules[1];
        int left = Math.round(head.x + gap * .84f), right = Math.round(hook - gap * .39f);
        if (right - left < gap * .54f) return true;
        int top = Math.round(head.y - gap * .65f), bottom = Math.round(head.y + gap * .67f);
        int found = 0;
        for (int y = top; y <= bottom; y++) {
            float level = (rules[0] - y) / gap;
            if (Math.abs(level - Math.round(level)) < .19f) continue;
            for (int x = left; x <= right; x++) if (ink.dark(x, y)) found++;
        }
        return found >= Math.max(3, Math.round(gap * gap * .025f));
    }

    private static boolean ordinary(ScoreNoteEvent n,int clef) {
        return ordinaryGeometry(n,clef) && n.stemDirection()==-1 && n.tupletDivisor()==1;
    }

    private static boolean ordinaryGeometry(ScoreNoteEvent n,int clef) {
        return ordinaryGeometry(n, clef, false);
    }

    private static boolean ordinaryGeometry(ScoreNoteEvent n, int clef, boolean allowIncomingTie) {
        // Incoming boundary evidence on the unchanged opening chord is kept as written.
        int allowedTies = allowIncomingTie ? 3 : 0;
        return n.kind()==ScoreNoteEvent.Kind.PITCHED && n.staffCount()==1 && n.staffIndex()==0
                && n.visualSize()==ScoreNoteEvent.VisualSize.FULL
                && !n.crossStaffBeam() && !n.tiedFromPrevious()
                && (n.boundaryTies() & ~allowedTies) == 0
                && n.leadingRestBeats()==0 && n.followingRestBeats()==0
                && n.openStringTouch()==null && n.harmonicSemitones()==0
                && (n.articulations()&NoteOrnament.GRACE)==0
                && clef!=ScoreNoteEvent.CLEF_UNKNOWN && n.clefBottomDiatonic()==clef;
    }

    private static float[] localRules(Ink ink,float x,float floor,float gap) {
        int center=Math.round(x), measure=Math.round(gap*2.7f);
        int flank=Math.round(gap*.27f), range=Math.round(gap*.32f);
        int[] rows=new int[5];
        for (int level=0;level<5;level++) {
            int expected=Math.round(floor-level*gap);
            int best=0;
            for(int y=expected-range;y<=expected+range;y++) {
                int hit=0;
                for (int px=center-measure;px<=center+measure;px++)
                    if (ink.dark(px,y) && !ink.dark(px,y-flank) && !ink.dark(px,y+flank)) hit++;
                if (hit>best) {best=hit; rows[level]=y;}
            }
            if (best<measure*.53f) return null;
            if (level>0 && Math.abs(rows[level-1]-rows[level]-gap)>gap*.3f) return null;
        }
        float exactGap=(rows[0]-rows[4])/4f;
        if(exactGap<gap*.85f || exactGap>gap*1.15f) return null;
        return new float[]{rows[0],exactGap};
    }

    private static boolean barline(Ink ink,float x,float[] rules) {
        float g=rules[1],top=rules[0]-4*g;
        if (top<1 || rules[0]>=ink.height-1 || g<10 || g>26) return false;
        var staff=new PlayingTechniqueDetector.Staff(top,rules[0],g,0,1);
        return PrintedCadenceDurations.boundaryColumn(ink.raw,ink.width,staff,x,true,false)>=0;
    }

    private static boolean clearBreak(Ink ink,List<Shaft> a,List<Shaft> b,Dyad left,Dyad right,
            float g,StaffPitchTrack track) {
        int x=Math.round((left.top.x+right.top.x)*.5f-g*.60f);
        int last=Math.max(a.stream().mapToInt(Shaft::tip).max().orElseThrow(),
                b.stream().mapToInt(Shaft::tip).max().orElseThrow())+Math.round(g*.3f);
        int first=Math.round(Math.min(left.bottom.y,right.bottom.y)+g);
        int light=0,possible=0;
        for(int xx=x-2;xx<=x+2;xx++) {
            var rules=track.at(xx);
            for (int y=first;y<=last;y++) {
                float level=(rules[0]-y)/rules[1];
                if (level>=-.15f && level<=4.15f && Math.abs(level-Math.round(level))<.2f) continue;
                possible++;
                if (!ink.dark(xx,y)) light++;
            }
        }
        return possible>20 && light>possible*.91f;
    }

    private static List<Shaft> shafts(Ink ink,Dyad p,float g) {
        return shafts(ink,p,g,-1);
    }

    private static List<Shaft> shafts(Ink ink,Dyad p,float g,int direction) {
        var out=new ArrayList<Shaft>();
        int outward=-direction;
        Head inner=outward>0?p.top:p.bottom,outer=outward>0?p.bottom:p.top;
        int begin=Math.round(inner.y+outward*g*.24f),end=Math.round(outer.y+outward*g*4.95f);
        int left=Math.round(inner.x+g*(direction>0?.39f:-.83f));
        int right=Math.round(inner.x+g*(direction>0?.83f:-.39f));
        for (int x=left;x<=right;x++) {
            int last=begin-outward,hits=0;
            for (int y=begin;(end-y)*outward>0;y+=outward) {
                if (ink.dark(x,y)) {
                    last=y;
                    hits++;
                } else if ((y-last)*outward>g*.38f) break;
            }
            if ((last-outer.y)*outward>=g*1.7f && hits>=((last-begin)*outward+1)*.83f)
                out.add(new Shaft(x,last));
        }
        return out;
    }

    private static List<Rails> beams(Ink ink,List<Shaft> left,List<Shaft> right,Dyad a,Dyad b,
            float gap) {
        return beams(ink,left,right,a,b,gap,-1);
    }

    private static List<Rails> beams(Ink ink,List<Shaft> left,List<Shaft> right,Dyad a,Dyad b,
            float gap,int direction) {
        return beams(ink, left, right, a, b, gap, direction, null);
    }

    private static List<Rails> beams(
            Ink ink,
            List<Shaft> left,
            List<Shaft> right,
            Dyad a,
            Dyad b,
            float gap,
            int direction,
            StaffPitchTrack staff) {
        int margin=Math.round(gap*.3f),radius=Math.max(2,Math.round(gap*.12f));
        int minOffset=Math.round(gap*(direction>0 ? -1.6f : -.81f)),maxOffset=Math.round(gap*.55f);
        int minSeparation=Math.round(gap*.66f),maxSeparation=Math.round(gap*1.15f);
        List<Rails> accepted=new ArrayList<>();
        for (var s:left) for (var t:right) {
            int span=t.x-s.x;
            if (span<gap*1.5f || span>gap*3.2f) continue;
            int first=s.x+margin,last=t.x-margin;
            for (int dy=minOffset;dy<=maxOffset;dy++) for (int dz=minOffset;dz<=maxOffset;dz++) {
                int y=s.tip-direction*dy,z=t.tip-direction*dz;
                float ay=direction>0?a.top.y:a.bottom.y,by=direction>0?b.top.y:b.bottom.y;
                if (Math.abs(y-z)>span*.35f || (ay-y)*direction<gap*1.9f
                        || (by-z)*direction<gap*1.9f)
                    continue;
                var axis=new Rails(s.x,y,t.x,z,0);
                if (band(ink,axis,first,last,0,radius)<.97f) continue;
                for (int d=minSeparation;d<=maxSeparation;d++) {
                    if (band(ink,axis,first,last,-direction*d,radius)<.97f
                            || band(ink,axis,first,last,-direction*2*d,radius)>=.60f) continue;
                            int middle = -direction * (d / 2);
                            if (band(ink, axis, first, last, middle, 1) >= .65f
                                    && (staff == null
                                            || !staffBridgesRailSeam(
                                                    ink, staff, axis, first, last, middle,
                                                    direction))) continue;
                            var rails = new Rails(s.x, y, t.x, z, d);
                            if (staff != null) return List.of(rails);
                            accepted.add(rails);
                }
            }
        }
        return accepted;
    }

    private static boolean staffBridgesRailSeam(
            Ink ink,
            StaffPitchTrack staff,
            Rails rail,
            int first,
            int last,
            int middle,
            int direction) {
        if (direction != -1) return false;
        int paper = 0, rule = 0, unexplained = 0;
        for (int x = first; x <= last; x++) {
            int y = rail.at(x) - middle;
            var frame = staff.at(x);
            if (stroke(ink, x, y, 1) < 2) paper++;
            else if (Math.abs(y - frame[0]) <= .19f * frame[1]) rule++;
            else unexplained++;
        }
        int span = last - first + 1;
        return paper >= span * .20f && rule >= span * .35f && unexplained <= span * .08f;
    }

    private static float band(Ink ink,Rails axis,int first,int last,int shift,int radius) {
        int full=0;
        for (int x=first;x<=last;x++) {
            int y=axis.at(x)-shift;
            if (stroke(ink,x,y,radius)>=2*radius) full++;
        }
        return full/(float)(last-first+1);
    }

    private static int stroke(Ink ink,int x,int y,int radius) {
        int hits=0;
        for (int dy=-radius;dy<=radius;dy++) if (ink.dark(x,y+dy)) hits++;
        return hits;
    }

    private static boolean compensatingInk(Ink ink,List<List<Rails>> edges,List<Dyad> pairs,
            int dot,float gap,StaffPitchTrack track) {
        for(int i=0;i<pairs.size();i++) {
            if (dot==i) continue;
            for (int outward:new int[]{-1,1}) {
                int edge=outward<0?i-1:i;
                if (edge<0 || edge>=edges.size() || edges.get(edge).isEmpty()) continue;
                var fits=edges.get(edge);
                int positives=0;
                for(var f:fits) if(peerHook(ink,f,outward,gap,track)) positives++;
                if (positives>fits.size()*.20f) return true;
            }
        }
        return false;
    }

    private static boolean peerHook(Ink ink,Rails axis,int direction,float gap,StaffPitchTrack track) {
        int x=direction<0?axis.x1:axis.x0;
        int reach=Math.round(gap*.70f),radius=Math.round(gap*.13f);
        for(int depth=Math.round(gap*.65f);depth<=Math.round(gap*1.05f);depth++) {
            int candidates=0,solid=0;
            for(int k=2;k<=reach;k++) {
                int col=x+direction*k;
                int y=axis.at(col)-axis.separation-depth;
                var rail=track.at(col);
                float level=(rail[0]-y)/rail[1];
                if (level>=-.22f && level<=4.22f && Math.abs(level-Math.round(level))<.27f) continue;
                candidates++;
                if(stroke(ink,col,y,radius)>=2*radius) solid++;
            }
            if (candidates>=reach-3 && solid>=candidates-1) return true;
        }
        return false;
    }

    private static boolean closes(List<Head> heads,List<ScoreNoteEvent> result) {
        var local=heads.stream().map(h->result.get(h.owner)).toList();
        for (int i=0;i<local.size();i++) {
            var placement=ScoreNoteTiming.writtenPlacement(local.get(i),local,4f).orElse(null);
            if (placement==null || Math.abs(placement.onsetBeats()-(i/2)*.25)>1e-6
                    || Math.abs(placement.durationBeats()-.25)>1e-6) return false;
        }
        return true;
    }

    private static ScoreNoteEvent withoutDot(ScoreNoteEvent n) {
        return new ScoreNoteEvent(n.measureIndex(),n.positionInMeasure(),n.staffStep(),n.staffIndex(),
                n.staffCount(),n.pageY(),n.tiedFromPrevious(),0,n.beamCount(),n.writtenAccidental(),
                n.unbeamedDurationBeats(),n.tupletDivisor(),n.followingRestBeats(),n.articulations(),
                n.clefBottomDiatonic(),n.crossStaffBeam(),n.leadingRestBeats(),n.compactOpening(),
                n.octaveShift(),n.boundaryTies(),n.tupletNormalNotes(),n.stemDirection(),n.kind(),
                n.visualSize(),n.harmonicSemitones(),n.openStringTouch());
    }
}
