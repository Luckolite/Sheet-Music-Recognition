// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;

/** A closed common-time bar can prove its four single-rail octave triplet groups. */
final class ClosedOctaveTripletBars {
    private ClosedOctaveTripletBars() {}

    static List<ScoreNoteEvent> apply(
            List<ScoreNoteEvent> notes,
            List<ScoreRestEvent> rests,
            List<MeasureRegion> measures,
            List<ScoreMeterChange> meters,
            byte[] gray, int width, int height) {
        return apply(notes, rests, measures, meters, gray, width, height, null);
    }

    static List<ScoreNoteEvent> apply(
            List<ScoreNoteEvent> notes, List<ScoreRestEvent> rests, List<MeasureRegion> measures,
            List<ScoreMeterChange> meters, byte[] gray, int width, int height, byte[] original) {
        return capture(notes, rests, measures, meters, gray, width, height, original, null).notes();
    }

    private record BeamBar(int measure, List<Integer> owners) {}

    record Capture(List<ScoreNoteEvent> notes, List<BeamBar> proved) {
        ScorePageInterpretation projectSlurs(ScorePageInterpretation score, int page,
                byte[] raw, byte[] gray, byte[] labels, int width, int height) {
            if (proved.isEmpty() || page < 0 || raw == null || gray == null
                    || score.notes().size() != notes.size()) return score;
            var slurs = score.slurSource();
            for (var bar : proved) {
                boolean stable = bar.owners().stream().allMatch(slot -> {
                    var note = score.notes().get(slot);
                    var expected = notes.get(slot);
                    return note.measureIndex() == bar.measure()
                            && note.positionInMeasure() == expected.positionInMeasure()
                            && note.pageY() == expected.pageY()
                            && note.staffIndex() == expected.staffIndex()
                            && note.beamCount() == 1 && note.tupletDivisor() == 3 && note.tupletNormalNotes() == 2;
                });
                if (stable) slurs = PrintedLiteralOctaveSlurs.captureTripletPhrases(
                        slurs, bar.owners(), score.notes(), score.measures().get(bar.measure()),
                        raw, gray, labels, width, height, page);
            }
            return slurs == score.slurSource() ? score : score.withSlurSource(slurs);
        }

        ScorePageInterpretation projectBeams(ScorePageInterpretation score, int page) {
            if (proved.isEmpty() || page < 0 || score.notes().size() != notes.size()) return score;
            var source = score.beamSource();
            var origins = new ArrayList<>(source.noteOrigins());
            var groups = new ArrayList<>(source.groups());
            var identities = new HashMap<ScoreBeamSource.Anchor, Integer>();
            for (var origin : origins)
                origin.ifPresent(anchor -> identities.merge(anchor, 1, Integer::sum));
            var detached = new ArrayList<>(source.detachedColumns());
            for (var bar : proved) {
                var slots = bar.owners();
                var anchors = new ArrayList<ScoreBeamSource.Anchor>(24);
                for (int slot : slots) {
                    var note = score.notes().get(slot);
                    var expected = notes.get(slot);
                    var anchor = new ScoreBeamSource.Anchor(page, slot);
                    var present = origins.get(slot);
                    if (note.measureIndex() != bar.measure()
                            || note.kind() != expected.kind()
                            || note.staffIndex() != expected.staffIndex()
                            || note.positionInMeasure() != expected.positionInMeasure()
                            || note.pageY() != expected.pageY()
                            || note.beamCount() != expected.beamCount()
                            || note.tupletDivisor() != expected.tupletDivisor()
                            || note.tupletNormalNotes() != expected.tupletNormalNotes()
                            || (present.isPresent() ? !present.get().equals(anchor)
                                    || identities.getOrDefault(anchor, 0) != 1 : identities.containsKey(anchor)))
                        break;
                    anchors.add(anchor);
                }
                if (anchors.size() != 24) continue;
                var owned = new HashSet<>(anchors);
                var keep = new ArrayList<ScoreBeamSource.Group>();
                var ids = new HashSet<String>();
                boolean conflict = false;
                for (var group : groups) {
                    var members = group.columns().stream().flatMap(c -> c.members().stream()).toList();
                    if (members.stream().noneMatch(owned::contains)) {
                        keep.add(group);
                        ids.add(group.id());
                    } else if (!owned.containsAll(members)
                            || group.tupletMark() == ScoreBeamSource.TupletMark.NUMBERED) {
                        conflict = true;
                        break;
                    }
                }
                if (conflict) continue;
                var keepDetached = new ArrayList<ScoreBeamSource.Column>();
                for (var column : detached) {
                    if (column.members().stream().noneMatch(owned::contains)) keepDetached.add(column);
                    else if (!owned.containsAll(column.members())) {
                        conflict = true;
                        break;
                    }
                }
                if (conflict) continue;

                var additions = new ArrayList<ScoreBeamSource.Group>(4);
                for (int start = 0; start < anchors.size(); start += 6) {
                    var columns = new ArrayList<ScoreBeamSource.Column>(3);
                    for (int i = start; i < start + 6; i += 2)
                        columns.add(new ScoreBeamSource.Column(anchors.subList(i, i + 2)));
                    int first = Math.min(anchors.get(start).noteIndex(), anchors.get(start + 1).noteIndex());
                    String id = "printed-beam:" + page + ":" + first;
                    if (!ids.add(id)) break;
                    additions.add(new ScoreBeamSource.Group(id, columns, List.of(1, 1), List.of(),
                            ScoreBeamSource.TupletMark.UNNUMBERED));
                }
                if (additions.size() != 4) continue;
                keep.addAll(additions);
                groups = keep;
                detached = keepDetached;
                for (int i = 0; i < slots.size(); i++) {
                    var anchor = anchors.get(i);
                    origins.set(slots.get(i), Optional.of(anchor));
                    identities.put(anchor, 1);
                }
            }
            return groups.equals(source.groups()) && detached.equals(source.detachedColumns())
                    ? score : score.withBeamSource(new ScoreBeamSource(groups, detached, origins));
        }
    }

    /** Keep the real octave heads when the last downstem is also read upward from its beam tip. */
    static List<Integer> retainedRailOwners(List<ScoreNoteEvent> notes, List<MeasureRegion> measures,
            byte[] gray, byte[] original, int width, int height) {
        if (original == null || gray == null || gray.length != (long) width * height
                || original.length != gray.length) return List.of();
        var grouped = new java.util.HashMap<Integer, List<Integer>>();
        for (int i = 0; i < notes.size(); i++)
            grouped.computeIfAbsent(notes.get(i).measureIndex(), key -> new ArrayList<>()).add(i);
        var rejected = new BitSet();
        for (var entry : grouped.entrySet()) {
            int measure = entry.getKey();
            var owners = entry.getValue();
            if (owners.size() != 25 || measure < 0 || measure >= measures.size()) continue;
            var bar = measures.get(measure);
            int proposed = -1;
            for (int index : owners) {
                var alias = notes.get(index);
                boolean beamed = alias.beamCount() == 2 && alias.unbeamedDurationBeats() == 0;
                boolean half = alias.beamCount() == 0 && alias.unbeamedDurationBeats() == 2;
                if ((!beamed && !half) || !ordinaryFields(alias, alias.clefBottomDiatonic())
                        || alias.stemDirection() != 1 || alias.writtenAccidental() != 2) continue;
                var remaining = owners.stream().filter(i -> i != index)
                        .sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure())).toList();
                int next = 0;
                while (next < remaining.size() && notes.get(remaining.get(next)).positionInMeasure()
                        < alias.positionInMeasure()) next++;
                if (next % 6 != 4 || next + 1 >= remaining.size()) continue;
                var a = notes.get(remaining.get(next));
                var b = notes.get(remaining.get(next + 1));
                var upper = a.pageY() < b.pageY() ? a : b;
                var lower = upper == a ? b : a;
                if (!sameReversedTip(gray, width, height, bar, alias, upper, lower)
                        || !sameReversedTip(original, width, height, bar, alias, upper, lower)) continue;
                var dirs = directionsForClosedRails(notes, remaining, measures, measure, gray, width, height, true);
                if (dirs == null || dirs[next / 2] != -1 || !java.util.Arrays.equals(dirs,
                        directionsForClosedRails(notes, remaining, measures, measure, original, width, height, true)))
                    continue;
                if (proposed != -1) {
                    proposed = -1;
                    break;
                }
                proposed = index;
            }
            if (proposed != -1) rejected.set(proposed);
        }
        if (rejected.isEmpty()) return List.of();
        return java.util.stream.IntStream.range(0, notes.size()).filter(i -> !rejected.get(i)).boxed().toList();
    }

    private static boolean sameReversedTip(byte[] ink, int width, int height, MeasureRegion bar,
            ScoreNoteEvent alias, ScoreNoteEvent upper, ScoreNoteEvent lower) {
        var staff = WrappedMeasureBoundaries.closedTripletStaff(ink, width, height, bar);
        if (staff == null) return false;
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float x = left + span * alias.positionInMeasure(), y = alias.pageY() * height;
        float upperX = left + span * upper.positionInMeasure(), upperY = upper.pageY() * height;
        float lowerX = left + span * lower.positionInMeasure(), lowerY = lower.pageY() * height;
        float gap = staff.at(x)[1];
        if (gap < 10 || gap > 25 || upper.stemDirection() != -1 || lower.stemDirection() != -1
                || lower.staffStep() - alias.staffStep() < 6 || lower.staffStep() - alias.staffStep() > 9
                || lowerX - x < gap * .8f || lowerX - x > gap * (alias.beamCount() == 0 ? 1.60f : 1.35f)
                || y - lowerY < gap * 3.2f || y - lowerY > gap * 4.35f) return false;
        var budget = new PrintedBeamShafts.Budget(65000);
        var reverse = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
        var down = PrintedBeamShafts.at(ink, width, height, upperX, upperY, gap, budget);
        if (budget.refused() || reverse.size() != 1 || down.size() != 1) return false;
        var fromTip = reverse.get(0);
        var fromHead = down.get(0);
        if (fromTip.direction() != -1 || fromHead.direction() != 1
                || Math.abs(fromTip.endY() - upperY) > .32f * gap
                || Math.abs(fromHead.endY() - y) > .24f * gap) return false;
        if (alias.beamCount() != 0)
            return Math.min(fromTip.right(), fromHead.right()) - Math.max(fromTip.left(), fromHead.left()) >= 1;
        // A false unflagged value can see only a sliver of the true stem at the beam tip.
        // Require a shared pixel column with both physical endpoints, not just overlapping bounds.
        for (var tipColumn : fromTip.columns()) {
            if (Math.abs(tipColumn.endY() - upperY) > .26f * gap) continue;
            for (var headColumn : fromHead.columns()) {
                if (tipColumn.x() == headColumn.x() && Math.abs(headColumn.endY() - y) <= .20f * gap)
                    return true;
            }
        }
        return false;
    }

    static Capture capture(
            List<ScoreNoteEvent> notes, List<ScoreRestEvent> rests, List<MeasureRegion> measures,
            List<ScoreMeterChange> meters, byte[] gray, int width, int height, byte[] original,
            ScoreMeterChange inherited) {
        return capture(notes, rests, measures, meters, gray, width, height, original, inherited, List.of(), List.of());
    }

    static Capture capture(List<ScoreNoteEvent> notes, List<ScoreRestEvent> rests,
            List<MeasureRegion> measures, List<ScoreMeterChange> meters, byte[] gray, int width, int height,
            byte[] original, ScoreMeterChange inherited, List<PlayingTechniqueDetector.Word> wordContext,
            List<PlayingTechniqueDetector.Staff> staffs) {
        if (notes.isEmpty()
                || measures.isEmpty()
                || gray == null
                || gray.length != (long) width * height) return new Capture(notes, List.of());
        var byMeasure = new ArrayList<List<Integer>>(measures.size());
        for (int i = 0; i < measures.size(); i++) byMeasure.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) byMeasure.get(notes.get(i).measureIndex()).add(i);
        var hasRest = new BitSet(measures.size());
        for (var rest : rests) hasRest.set(rest.measureIndex());
        List<ScoreNoteEvent> result = null;
        var proved = new ArrayList<BeamBar>();
        int next = 0;
        byte[] unmarkedRaw = null, unmarkedNormalized = null;
        byte[] rimlessRaw = null, rimlessNormalized = null;
        // Four quarters are the reader's inherited beat window until source meter overrides it.
        ScoreMeterChange meter = inherited == null ? new ScoreMeterChange(0, 4, 4) : inherited;
        for (int m = 0; m < measures.size(); m++) {
            while (next < meters.size() && meters.get(next).measureIndex() <= m)
                meter = meters.get(next++);
            var owners = byMeasure.get(m);
            if (meter.numerator() != 4
                    || meter.denominator() != 4
                    || hasRest.get(m)
                    || owners.size() != 24
                    || measures.get(m).cadenceQuarterBeats() != 0) continue;
            if (ownsBar(notes, owners, measures, m, gray, width, height)) {
                if (result == null) result = new ArrayList<>(notes);
                for (int owner : owners) {
                    var note = notes.get(owner);
                    if (note.tupletDivisor() != 3) result.set(owner, note.withTupletRatio(3, 2));
                }
                continue;
            }
            var order =
                    owners.stream()
                            .sorted(
                                    Comparator.comparingDouble(
                                            i -> notes.get(i).positionInMeasure()))
                            .toList();
            int seeds = seededDyads(order.stream().map(notes::get).toList());
            boolean displacedSeed = displacedSeed(seeds);
            var openingHead = notes.get(order.get(0));
            var otherHead = notes.get(order.get(1));
            boolean pageOpening = m == 0 && seeds == 0b111
                    && (openingHead.pageY() < otherHead.pageY() ? openingHead : otherHead).boundaryTies() == 2;
            boolean engravingOnly = seeds == ALL_OCTAVE_DYADS;
            var directions = directionsForClosedRails(notes, order, measures, m, gray, width, height, false);
            int articulationGroups = 0;
            boolean literalTriplets = false;
            if (original != null && original.length == gray.length) {
                boolean relaxed = directions == null;
                var normalized = relaxed
                        ? directionsForClosedRails(notes, order, measures, m, gray, width, height, true)
                        : directions;
                if (normalized != null && java.util.Arrays.equals(normalized,
                        directionsForClosedRails(notes, order, measures, m, original, width, height, relaxed))) {
                    directions = normalized;
                    int unnumbered = clearUnnumberedGroups(notes, order, measures, m,
                            gray, original, width, height, wordContext);
                    if (unnumbered != 15 && !staffs.isEmpty()) {
                        if (unmarkedRaw == null) {
                            unmarkedRaw = withoutPrintedOctaveDashes(original, width, height, staffs);
                            unmarkedNormalized = original == gray ? unmarkedRaw
                                    : withoutPrintedOctaveDashes(gray, width, height, staffs);
                        }
                        if (unmarkedRaw != original || unmarkedNormalized != gray)
                            unnumbered |= clearUnnumberedGroups(notes, order, measures, m,
                                    unmarkedNormalized, unmarkedRaw, width, height, wordContext);
                    }
                    if (unnumbered != 15 && displacedSeed && !staffs.isEmpty()
                            && order.stream().anyMatch(i -> notes.get(i).octaveShift() > 0)) {
                        if (rimlessRaw == null) {
                            rimlessRaw = withoutPrintedOctaveDashes(original, width, height, staffs, true);
                            rimlessNormalized = original == gray ? rimlessRaw
                                    : withoutPrintedOctaveDashes(gray, width, height, staffs, true);
                        }
                        unnumbered |= clearUnnumberedGroups(notes, order, measures, m,
                                rimlessNormalized, rimlessRaw, width, height, wordContext);
                    }
                    if (unnumbered == 15) {
                        literalTriplets = true;
                        proved.add(new BeamBar(m, order));
                        for (int group = 0; !engravingOnly && group < 4; group++) {
                            if (directions[group * 3] != 1) continue;
                            var members = order.subList(group * 6, group * 6 + 6)
                                    .stream().map(notes::get).toList();
                            var bar = measures.get(m);
                            if (ownsTripletTenutos(gray, width, height, bar, members)
                                    && ownsTripletTenutos(original, width, height, bar, members))
                                articulationGroups |= 1 << group;
                        }
                    }
                }
            }
            if (engravingOnly || directions == null || (displacedSeed || pageOpening) && !literalTriplets) continue;
            if (result == null) result = new ArrayList<>(notes);
            for (int i = 0; i < order.size(); i++) {
                var note = notes.get(order.get(i));
                if (note.beamCount() != 1) note = ClosingTripletRhythm.singleBeam(note);
                if (note.stemDirection() == 0) note = note.withStemDirection(directions[i / 2]);
                if ((articulationGroups & (1 << (i / 6))) != 0)
                    note = note.withArticulations(note.articulations() | NoteArticulation.TENUTO);
                result.set(order.get(i), note.withTupletRatio(3, 2));
            }
            if (literalTriplets) recoverPairedFlat(result, order, measures.get(m), original, gray, width, height);
        }
        return new Capture(result == null ? notes : List.copyOf(result), List.copyOf(proved));
    }

    private static boolean ownsBar(
            List<ScoreNoteEvent> notes,
            List<Integer> unsorted,
            List<MeasureRegion> measures,
            int index,
            byte[] gray,
            int width,
            int height) {
        var bar = measures.get(index);
        var owners =
                unsorted.stream()
                        .map(notes::get)
                        .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                        .toList();
        float left = bar.left() * width, span = bar.right() * width - left;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (!Float.isFinite(left + span + gap)
                || gap < 10
                || gap > 25
                || span < gap * 30
                || span > gap * 40) return false;
        int clef = owners.get(0).clefBottomDiatonic();
        if (clef == ScoreNoteEvent.CLEF_UNKNOWN) return false;
        int seeds = seededDyads(owners);
        if (Integer.bitCount(seeds) != 3) return false;
        float first = octaveCenter(owners, 0, left, span);
        float last = octaveCenter(owners, 22, left, span);
        boolean closed =
                WrappedMeasureBoundaries.confirmsClosed(gray, width, height, bar, first, last);
        boolean interior = closed && oneSeededTriplet(seeds);
        float previous = 0, upperX = 0, upperY = 0, lowerX = 0, lowerY = 0;
        boolean startsSystem = false;
        var budget = new PrintedBeamShafts.Budget(280_000);
        PrintedBeamShafts.Shaft prior = null;
        for (int i = 0; i < owners.size(); i += 2) {
            var a = owners.get(i);
            var b = owners.get(i + 1);
            var upper = a.pageY() < b.pageY() ? a : b;
            var lower = upper == a ? b : a;
            if (!ordinary(upper, clef)
                    || !ordinary(lower, clef)
                    || upper.staffStep() - lower.staffStep() != 7
                    || upper.tupletDivisor() != lower.tupletDivisor()) return false;
            float x0 = left + span * upper.positionInMeasure(), y0 = height * upper.pageY();
            float x1 = left + span * lower.positionInMeasure(), y1 = height * lower.pageY();
            float offset = Math.abs(x0 - x1);
            if (offset > gap * (interior ? .21f : .18f)
                    || y1 - y0 < gap * 3.15f
                    || y1 - y0 > gap * 3.9f) return false;
            float x = (x0 + x1) * .5f;
            if (i != 0
                    && (x - previous < gap * 1.75f
                            || x - previous > gap * 4.1f
                            || competingDot(
                                    gray,
                                    width,
                                    height,
                                    upperX,
                                    upperY,
                                    x,
                                    gap,
                                    startsSystem || interior)
                            || competingDot(
                                    gray,
                                    width,
                                    height,
                                    lowerX,
                                    lowerY,
                                    x,
                                    gap,
                                    startsSystem || interior))) return false;
            var u = PrintedBeamShafts.at(gray, width, height, x0, y0, gap, budget);
            var v = PrintedBeamShafts.at(gray, width, height, x1, y1, gap, budget);
            if (v.isEmpty() && startsSystem && !budget.refused()) {
                v = contrastShaft(gray, width, height, x1, y1, gap, budget);
            }
            if (v.size() != 1 || budget.refused()) return false;
            var low = v.get(0);
            if (low.direction() != 1) return false;
            if (i == 0) {
                startsSystem =
                        WrappedMeasureBoundaries.confirmsShaftBoundedInitialStaffBar(
                                gray, width, height, measures, index, x, low.left());
            }
            if (u.size() > 1) return false;
            var high = u.isEmpty() ? null : u.get(0);
            boolean shared =
                    high != null
                            && high.direction() == 1
                            && Math.min(high.right(), low.right())
                                    >= Math.max(high.left(), low.left())
                            && Math.abs(high.endY() - low.endY()) <= gap * .15f;
            // A larger centroid offset still requires both owners to resolve the same shaft.
            if (offset > gap * .18f && !shared) return false;
            if (!shared
                    && (!(startsSystem || interior)
                            || high != null && high.direction() != 1
                            || !continuesUpperShaft(gray, width, height, x0, y0, low, gap)))
                return false;
            if (prior != null
                    && !WrappedEighthTriplets.matchesRailBridge(
                            gray, width, height, prior, low, gap, i % 6 != 0)) return false;
            upperX = x0;
            upperY = y0;
            lowerX = x1;
            lowerY = y1;
            prior = low;
            previous = x;
        }
        float next = left + span + gap * .5f;
        return !competingDot(
                        gray, width, height, upperX, upperY, next, gap, startsSystem || interior)
                && !competingDot(
                        gray, width, height, lowerX, lowerY, next, gap, startsSystem || interior)
                && (closed
                        || startsSystem
                                && WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(
                                        gray, width, height, bar, previous));
    }

    private static boolean ownsTripletTenutos(byte[] plane, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> members) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>();
        var budget = new PrintedBeamShafts.Budget(82000);
        for (int i = 0; i < 6; i += 2) {
            var first = members.get(i);
            var second = members.get(i + 1);
            var top = first.pageY() < second.pageY() ? first : second;
            var bottom = top == first ? second : first;
            var shaft = markedDyadShaft(plane, width, height, top, bottom, left, span, gap, 1, budget);
            if (shaft == null) return false;
            shafts.add(shaft);
        }
        return UnnumberedTripletInk.beamSideTenutoRows(plane, width, shafts, gap);
    }

    private static byte[] withoutPrintedOctaveDashes(byte[] source, int width, int height,
            List<PlayingTechniqueDetector.Staff> staffs) {
        return withoutPrintedOctaveDashes(source, width, height, staffs, false);
    }

    private static byte[] withoutPrintedOctaveDashes(byte[] source, int width, int height,
            List<PlayingTechniqueDetector.Staff> staffs, boolean includeRim) {
        var ink = OctaveMarkDetector.printedDashInk(source, width, height, staffs);
        if (ink.isEmpty()) return source;
        byte[] cleared = source.clone();
        for (int pixel = ink.nextSetBit(0); pixel >= 0; pixel = ink.nextSetBit(pixel + 1)) {
            cleared[pixel] = (byte) 255;
            if (!includeRim) continue;
            // The contrast pass can leave a fragment of an already-owned dot in the original plane.
            int x = pixel % width, y = pixel / width;
            for (int dy = -2; dy <= 2; dy++)
                for (int dx = -1; dx <= 1; dx++)
                    if (x + dx >= 0 && x + dx < width && y + dy >= 0 && y + dy < height)
                        cleared[pixel + dy * width + dx] = (byte) 255;
        }
        return cleared;
    }

    private static int clearUnnumberedGroups(
            List<ScoreNoteEvent> notes, List<Integer> order, List<MeasureRegion> bars, int index,
            byte[] normalized, byte[] original, int w, int h, List<PlayingTechniqueDetector.Word> directions) {
        var heads = order.stream().map(notes::get).toList();
        int seeds = seededDyads(heads);
        if (seeds != 0 && seeds != ALL_OCTAVE_DYADS && !displacedSeed(seeds) && !oneSeededTriplet(seeds)) return 0;
        var bar = bars.get(index);
        float left = bar.left() * w, span = (bar.right() - bar.left()) * w;
        float gap = (bar.bottom() - bar.top()) * h / 8.2f;
        var rows = UnnumberedTripletInk.sourceRows(bar, bars, h);
        var rawStaff = WrappedMeasureBoundaries.verifiedTripletStaff(original, w, h, bar, true);
        var correctedStaff = WrappedMeasureBoundaries.verifiedTripletStaff(normalized, w, h, bar, true);
        if (rawStaff == null) rawStaff = pairedHeadStaff(original, w, h, heads, left, span, gap);
        if (correctedStaff == null) correctedStaff = pairedHeadStaff(normalized, w, h, heads, left, span, gap);
        if (rawStaff == null || correctedStaff == null) return 0;
        float first = octaveCenter(heads, 0, left, span);
        float last = octaveCenter(heads, 22, left, span);
        boolean closedRails = WrappedMeasureBoundaries.confirmsClosed(original, w, h, bar, first, last)
                && WrappedMeasureBoundaries.confirmsClosed(normalized, w, h, bar, first, last);
        int mask = 0;
        for (int group = 0; group < 4; group++) {
            var members = heads.subList(group * 6, group * 6 + 6);
            if (clearsOctaveTripletMark(original, w, h, members, left, span, gap, rows, rawStaff, directions, closedRails)
                    && clearsOctaveTripletMark(normalized, w, h, members, left, span, gap, rows, correctedStaff, directions, closedRails))
                mask |= 1 << group;
        }
        return mask;
    }

    private static StaffPitchTrack pairedHeadStaff(byte[] plane, int width, int height,
            List<ScoreNoteEvent> heads, float left, float span, float nominal) {
        if (heads.size() != 24 || heads.get(0).stemDirection() != 1 || heads.get(1).stemDirection() != 1)
            return null;
        var samples = new ArrayList<float[]>();
        for (int i = 0; i < heads.size(); i += 2) {
            var first = heads.get(i);
            var second = heads.get(i + 1);
            var lower = first.pageY() > second.pageY() ? first : second;
            var upper = lower == first ? second : first;
            float gap = (lower.pageY() - upper.pageY()) * height / 3.5f;
            if (upper.staffStep() - lower.staffStep() != 7 || gap < nominal * .83f || gap > nominal * 1.08f)
                return null;
            float x = left + span * (upper.positionInMeasure() + lower.positionInMeasure()) * .5f;
            float hint = lower.pageY() * height + lower.staffStep() * gap * .5f;
            float[] offsets = i == 0 ? new float[]{-.70f} : i == 22 ? new float[]{.70f}
                    : new float[]{-.70f, 0, .70f};
            float[] point = null;
            for (float dx : offsets) {
                float x0 = x + dx * gap;
                var rules = PrintedDirectionStaff.printedRules(plane, width, height, x0, hint, gap);
                if (rules != null && Math.abs(rules[1] - gap) <= .10f * nominal) {
                    point = new float[]{x0, rules[0], rules[1]};
                    break;
                }
            }
            if (point == null) {
                if (i == 0 || i == 22) return null;
                continue;
            }
            if (!samples.isEmpty()) {
                var previous = samples.get(samples.size() - 1);
                float dx = point[0] - previous[0];
                if (dx <= 0 || dx > 5.7f * nominal || Math.abs(point[1] - previous[1]) > .065f * dx + 1
                        || Math.abs(point[2] - previous[2]) > .08f * nominal) return null;
            }
            samples.add(point);
        }
        return samples.size() >= 8 ? StaffPitchTrack.fromVerifiedSamples(samples) : null;
    }

    private static boolean clearsOctaveTripletMark(byte[] source, int w, int h, List<ScoreNoteEvent> heads,
            float left, float span, float gap, UnnumberedTripletInk.Rows rows, StaffPitchTrack track,
            List<PlayingTechniqueDetector.Word> words, boolean closedRails) {
        if (noPrintedTripletMark(source, w, h, heads, left, span, gap, rows, track, words)) return true;
        float[] x = new float[3], y = new float[3];
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>();
        var budget = new PrintedBeamShafts.Budget(82_000);
        int direction = heads.get(0).stemDirection();
        if (direction == 0) return false;
        for (int i = 0; i < 3; i++) {
            var a = heads.get(2 * i);
            var b = heads.get(2 * i + 1);
            var top = a.pageY() < b.pageY() ? a : b;
            var bottom = top == a ? b : a;
            var beamSide = direction > 0 ? top : bottom;
            var far = beamSide == top ? bottom : top;
            var found = markedDyadShaft(source, w, h, top, bottom, left, span, gap, direction, budget);
            if (found == null) return false;
            shafts.add(found);
            x[i] = left + span * far.positionInMeasure();
            y[i] = h * far.pageY();
        }
        return UnnumberedTripletInk.clearOctaveWithTrackedStaff(source, w, h, gap, x, y, shafts, rows, track, words, closedRails);
    }

    static PrintedBeamShafts.Shaft markedDyadShaft(byte[] ink, int width, int height,
            ScoreNoteEvent upper, ScoreNoteEvent lower, float left, float span, float gap, int direction,
            PrintedBeamShafts.Budget budget) {
        var atBeam = direction > 0 ? upper : lower;
        var found = PrintedBeamShafts.at(ink, width, height, left + span * atBeam.positionInMeasure(),
                height * atBeam.pageY(), gap, budget);
        if (budget.refused() || found.size() > 1) return null;
        if (found.size() == 1) return found.get(0).direction() == -direction ? found.get(0) : null;
        var pair = new Dyad(upper, lower, left + span * upper.positionInMeasure(), height * upper.pageY(),
                left + span * lower.positionInMeasure(), height * lower.pageY());
        if (direction == -1 && upper.beamCount() == 2 && lower.beamCount() == 2
                && upper.stemDirection() == -1 && lower.stemDirection() == -1) {
            var shared = continuousDyadShaft(ink, width, height, pair, gap, budget);
            if (shared != null) return shared;
        }
        var faded = fadedOpeningShaft(ink, width, height, pair, gap, budget);
        return faded != null && faded.direction() == -direction ? faded : null;
    }

    /** Inspect both sides of a literal primary rail for a separately printed numeral or bracket. */
    static boolean noPrintedTripletMark(byte[] source, int w, int h, List<ScoreNoteEvent> heads,
            float left, float span, float gap, UnnumberedTripletInk.Rows rows, StaffPitchTrack track) {
        return noPrintedTripletMark(source, w, h, heads, left, span, gap, rows, track, List.of());
    }

    private static boolean noPrintedTripletMark(byte[] source, int w, int h, List<ScoreNoteEvent> heads,
            float left, float span, float gap, UnnumberedTripletInk.Rows rows, StaffPitchTrack track,
            List<PlayingTechniqueDetector.Word> directions) {
        float first = octaveCenter(heads, 0, left, span), last = octaveCenter(heads, 4, left, span);
        float headTop = Float.POSITIVE_INFINITY, headBottom = 0;
        int tipTop = h, tipBottom = -1;
        var budget = new PrintedBeamShafts.Budget(82_000);
        for (int i = 0; i < heads.size(); i += 2) {
            var a = heads.get(i);
            var b = heads.get(i + 1);
            var upper = a.pageY() < b.pageY() ? a : b;
            var lower = upper == a ? b : a;
            headTop = Math.min(headTop, upper.pageY() * h);
            headBottom = Math.max(headBottom, lower.pageY() * h);
            var shaft = markedDyadShaft(source, w, h, upper, lower, left, span, gap, -1, budget);
            if (shaft == null) return false;
            tipTop = Math.min(tipTop, shaft.endY());
            tipBottom = Math.max(tipBottom, shaft.endY());
        }
        var accidentalHeads = heads.stream().filter(n -> n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_SHARP
                        || n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL)
                .map(n -> new UnnumberedTripletInk.AccidentalHead(
                        left + span * n.positionInMeasure(), n.pageY() * h, n.writtenAccidental()))
                .toList();
        return UnnumberedTripletInk.clearFingeringBands(source, w, h, gap,
                first, last, headTop, headBottom, tipTop, tipBottom, rows, track, null, accidentalHeads, directions, true);
    }

    private record Dyad(
            ScoreNoteEvent upper,
            ScoreNoteEvent lower,
            float upperX,
            float upperY,
            float lowerX,
            float lowerY) {
        float center() {
            return (upperX + lowerX) * .5f;
        }
    }

    static int[] directionsForClosedRails(
            List<ScoreNoteEvent> notes, List<Integer> order, List<MeasureRegion> bars, int index,
            byte[] gray, int w, int h, boolean allowStaffOpening) {
        var bar = bars.get(index);
        float left = bar.left() * w, span = (bar.right() - bar.left()) * w;
        float gap = (bar.bottom() - bar.top()) * h / 8.2f;
        if (!Float.isFinite(left + span + gap)
                || gap < 10
                || gap > 25
                || span < gap * (allowStaffOpening ? 29.5f : 30f)
                || span > gap * 40) return null;
        var heads = order.stream().map(notes::get).toList();
        int seeds = seededDyads(heads);
        if (seeds != 0 && seeds != ALL_OCTAVE_DYADS && !oneSeededTriplet(seeds) && !displacedSeed(seeds)) return null;
        float firstHead = octaveCenter(heads, 0, left, span);
        float lastHead = octaveCenter(heads, 22, left, span);
        var initial = heads.get(0);
        var firstAbove = initial.pageY() < heads.get(1).pageY() ? initial : heads.get(1);
        boolean printedPageOpening = allowStaffOpening && index == 0 && seeds == 0b111
                && firstAbove.boundaryTies() == 2
                && verifiesPrintedPageOpening(gray, w, h, bars, firstHead, lastHead, gap);
        boolean closed = WrappedMeasureBoundaries.confirmsClosed(gray, w, h, bar, firstHead, lastHead);
        if (!closed && !allowStaffOpening) return null;
        var pairs = new Dyad[12];
        var shafts = new PrintedBeamShafts.Shaft[12];
        var incomplete = new PrintedBeamShafts.Shaft[12];
        var budget = new PrintedBeamShafts.Budget(280_000);
        int clef = heads.get(0).clefBottomDiatonic();
        if (clef == ScoreNoteEvent.CLEF_UNKNOWN) return null;
        boolean repairedBeam = false, recoveredShaft = false, recoveredOpeningUpstem = false;
        for (int i = 0; i < pairs.length; i++) {
            var a = heads.get(i * 2);
            var b = heads.get(i * 2 + 1);
            var upper = a.pageY() < b.pageY() ? a : b;
            var lower = upper == a ? b : a;
            float x0 = left + span * upper.positionInMeasure(), y0 = upper.pageY() * h;
            float x1 = left + span * lower.positionInMeasure(), y1 = lower.pageY() * h;
            // The lower head can inherit extra rails from overlapping staff ink.
            // Use the complete two-plane shared shaft and single-rail proof in that case.
            boolean beamAlias = allowStaffOpening && !repairedBeam && upper.beamCount() == 1
                    && lower.beamCount() == 3 && ordinaryFields(lower, clef)
                    && lower.unbeamedDurationBeats() == 0;
            float alignment = allowStaffOpening && upper.beamCount() == 2 && lower.beamCount() == 1
                    && upper.stemDirection() == -1 && lower.stemDirection() == -1
                    ? .25f : allowStaffOpening ? .21f : .18f;
            var rhythmicUpper = i == 0 && printedPageOpening ? upper.withBoundaryTies(0) : upper;
            if (!rhythmCandidate(rhythmicUpper, clef)
                    || !rhythmCandidate(lower, clef) && !beamAlias
                    || upper.staffStep() - lower.staffStep() != 7
                    || upper.tupletDivisor() != lower.tupletDivisor()
                    || Math.abs(x0 - x1) > gap * alignment
                    || y1 - y0 < gap * 3.15f
                    || y1 - y0 > gap * 3.9f) return null;
            repairedBeam |= beamAlias;
            var p = new Dyad(upper, lower, x0, y0, x1, y1);
            pairs[i] = p;
            if (i > 0) {
                float dx = p.center() - pairs[i - 1].center();
                if (dx < gap * 1.7f || dx > gap * 4.1f) return null;
            }
            var u = PrintedBeamShafts.at(gray, w, h, x0, y0, gap, budget);
            var v = PrintedBeamShafts.at(gray, w, h, x1, y1, gap, budget);
            if (allowStaffOpening && upper.beamCount() == 2 && lower.beamCount() == 2
                    && upper.stemDirection() == -1 && lower.stemDirection() == -1
                    && (u.isEmpty() || v.isEmpty()) && u.size() <= 1 && v.size() <= 1) {
                var shared = continuousDyadShaft(gray, w, h, p, gap, budget);
                if (shared != null) {
                    shafts[i] = shared;
                    recoveredShaft = true;
                    continue;
                }
            }

            if (allowStaffOpening && (!closed || displacedSeed(seeds)) && (u.isEmpty() || v.isEmpty())
                    && u.size() <= 1 && v.size() <= 1) {
                var shared = fadedOpeningShaft(gray, w, h, p, gap, budget);
                if (shared != null) {
                    shafts[i] = shared;
                    recoveredShaft |= shared.direction() == 1;
                    recoveredOpeningUpstem |= i == 0 && shared.direction() == -1;
                    continue;
                }
            }
            if (budget.refused()
                    || u.size() > 1 || v.size() > 1
                    || (u.isEmpty() || v.isEmpty()) && beamAlias
                    || u.isEmpty() && v.isEmpty())
                return null;
            if (u.size() == 1 && v.size() == 1) {
                var first = u.get(0);
                var second = v.get(0);
                boolean tall = (allowStaffOpening || closed) && joinedTallUpstem(gray, w, h, p, first, second, gap);
                recoveredOpeningUpstem |= tall && i == 0;
                tall |= (allowStaffOpening || closed)
                        && steppedDownstem(gray, w, h, p, first, second, gap);
                if (!tall) {
                    if (first.direction() != second.direction()
                            || Math.max(first.left(), second.left()) > Math.min(first.right(), second.right()))
                        return null;
                    if (Math.abs(first.endY() - second.endY()) > gap * .15f
                            && (!allowStaffOpening || !sharedDownstemTip(first, second, gap)
                                            && !bridgesInterruptedStaffShaft(gray, w, h, p, first, second, gap)))
                        return null;
                }
                shafts[i] = first.direction() == 1 ? second : first;
            } else {
                if (allowStaffOpening && closed && i == pairs.length - 1 && u.isEmpty()
                        && upper.stemDirection() == -1 && lower.stemDirection() == -1
                        && upper.beamCount() == 1 && lower.beamCount() == 1
                        && upper.tupletDivisor() == 3 && lower.tupletDivisor() == 3
                        && interruptedTerminalShaft(gray, w, h, p, v.get(0), gap, budget)) {
                    shafts[i] = v.get(0);
                    continue;
                }
                if (i % 3 != 1 || Math.abs(x0 - x1) > gap * .18f) return null;
                incomplete[i] = u.isEmpty() ? v.get(0) : u.get(0);
            }
        }
        var directions = new int[12];
        for (int i = 0; i < pairs.length; i++) {
            var p = pairs[i];
            if (shafts[i] == null) {
                if (shafts[i - 1] == null || shafts[i + 1] == null) return null;
                shafts[i] =
                        middleShaft(
                                gray, w, h, p, incomplete[i], shafts[i - 1], shafts[i + 1], gap);
                if (shafts[i] == null) return null;
            }
            var stem = shafts[i];
            directions[i] = -stem.direction();
            if (p.upper.stemDirection() != 0 && p.upper.stemDirection() != directions[i]
                    || p.lower.stemDirection() != 0 && p.lower.stemDirection() != directions[i]
                    || i % 3 != 0 && directions[i - 1] != directions[i]) return null;
            if (i > 0
                    && !WrappedEighthTriplets.matchesRailBridge(
                            gray, w, h, shafts[i - 1], stem, gap, i % 3 != 0)) return null;
            float next = i + 1 < pairs.length ? pairs[i + 1].center() : left + span + gap * .5f;
            var following = allowStaffOpening && i + 1 < pairs.length ? pairs[i + 1] : null;
            if (competingOctaveDot(gray, w, h, p.upperX, p.upperY, next, gap, following, true)
                    || competingOctaveDot(gray, w, h, p.lowerX, p.lowerY, next, gap, following, false))
                return null;
        }
        if (!closed && !printedPageOpening && !(displacedSeed(seeds) && shafts[0].direction() == -1
                        && provesUpstemHeaderPocket(gray, w, h, bars, index, pairs[0], lastHead, gap))
                && !provedInitialBar(gray, w, h, bars, index, firstHead, lastHead, shafts[0], gap)
                && !((recoveredOpeningUpstem || recoveredShaft && index == 0)
                        && provesMeasuredSystemOpening(
                                gray, w, h, bars, index, pairs[0], pairs[11], shafts[0], gap))
                && !(recoveredShaft && shafts[0].direction() == 1
                        && WrappedMeasureBoundaries.confirmsWideShaftBoundedOpening(
                                gray, w, h, bars, index, firstHead, shafts[0].left())
                        && WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(
                                gray, w, h, bar, lastHead))) return null;
        return directions;
    }

    private static void recoverPairedFlat(List<ScoreNoteEvent> notes, List<Integer> order,
            MeasureRegion measure, byte[] source, byte[] gray, int width, int height) {
        float gap = (measure.bottom() - measure.top()) * height / 8.2f;
        float left = measure.left() * width, span = (measure.right() - measure.left()) * width;
        var recoveredSteps = new java.util.HashSet<Integer>();
        for (int i = 0; i < order.size(); i += 2) {
            int upperIndex = order.get(i), lowerIndex = order.get(i + 1);
            if (notes.get(upperIndex).pageY() > notes.get(lowerIndex).pageY()) {
                int swap = upperIndex;
                upperIndex = lowerIndex;
                lowerIndex = swap;
            }
            var upper = notes.get(upperIndex);
            var lower = notes.get(lowerIndex);
            if (upper.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) {
                recoveredSteps.remove(upper.staffStep());
                continue;
            }
            if (lower.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT
                    || upper.staffStep() - lower.staffStep() != 7
                    || upper.stemDirection() != -1 || lower.stemDirection() != -1) continue;
            if (provedFarFlat(source, gray, width, height, left, span, gap, upper)
                    && provedFarFlat(source, gray, width, height, left, span, gap, lower))
                recoveredSteps.add(upper.staffStep());
            if (recoveredSteps.contains(upper.staffStep()))
                notes.set(upperIndex, upper.withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_FLAT));
        }
    }

    static boolean provedFarFlat(byte[] source, byte[] gray, int width, int height,
            float left, float span, float gap, ScoreNoteEvent note) {
        float x = left + span * note.positionInMeasure(), y = height * note.pageY();
        int start = Math.round(x - 1.78f * gap), end = start + Math.round(.98f * gap);
        int top = Math.round(y - 1.35f * gap), bottom = Math.round(y + .75f * gap);
        return PrintedFlatGlyph.matches(source, width, height, start, top, end, bottom, gap)
                && PrintedFlatGlyph.matches(gray, width, height, start, top, end, bottom, gap);
    }

    private static boolean joinedTallUpstem(byte[] ink, int width, int height, Dyad pair,
            PrintedBeamShafts.Shaft upper, PrintedBeamShafts.Shaft lower, float gap) {
        if (upper.direction() != -1 || lower.direction() != -1 || upper.right() + 1 < lower.left()
                || lower.right() + 1 < upper.left()
                || pair.upper.stemDirection() != 1 || pair.lower.stemDirection() != 1
                || Math.abs(lower.endY() - pair.upperY) > .82f * gap
                || pair.upperY - upper.endY() < gap * 2.2f) return false;
        int first = upper.endY() + Math.round(.20f * gap), last = lower.startY() - 2;
        if (first < 0 || last >= height || last - first < 4.6f * gap) return false;
        if (Math.abs(lower.endY() - pair.upperY) <= .30f * gap) {
        for (var column : upper.columns()) {
                int x = column.x();
                if (x < 0 || x >= width || x < lower.left() - 1 || x > lower.right() + 1
                        || Math.abs(column.endY() - upper.endY()) > 2) continue;
                int paper = 0, consecutive = 0;
                for (int y = first; y <= last; y++) {
                    if ((ink[y * width + x] & 255) < 175) consecutive = 0;
                    else {
                        paper++;
                        if (++consecutive > 1) break;
                    }
                }
                if (consecutive <= 1 && paper <= .035f * (last - first + 1)) return true;
            }
        }
        return connectedSteppedStem(ink, width, upper, lower, first, last, gap);
    }

    private static boolean steppedDownstem(byte[] ink, int width, int height, Dyad pair,
            PrintedBeamShafts.Shaft upper, PrintedBeamShafts.Shaft lower, float gap) {
        if (upper.direction() != 1 || lower.direction() != 1
                || upper.right() + 1 < lower.left() || lower.right() + 1 < upper.left()
                || pair.upper.stemDirection() != -1 || pair.lower.stemDirection() != -1
                || Math.abs(upper.endY() - pair.lowerY) > .82f * gap
                || lower.endY() - pair.lowerY < gap * 2.2f) return false;
        int first = lower.endY() - Math.round(.20f * gap), last = upper.startY() + 2;
        return last >= 0 && first < height && first - last >= 4.6f * gap
                && connectedSteppedStem(ink, width, lower, upper, first, last, gap);
    }

    private static boolean connectedSteppedStem(byte[] ink, int width,
            PrintedBeamShafts.Shaft beamSide, PrintedBeamShafts.Shaft headSide, int first, int last, float gap) {
        // Follow connected ink in a narrow corridor, without using a staff rule to shift columns.
        int left = Math.min(beamSide.left(), headSide.left());
        int right = Math.max(beamSide.right(), headSide.right());
        int columns = right - left + 1;
        if (left < 4 || right + 4 >= width || columns > Math.min(12, Math.round(gap * .42f))) return false;
        int connected = 0;
        for (var column : beamSide.columns()) {
            if (Math.abs(column.endY() - beamSide.endY()) <= 2) {
                connected |= 1 << (column.x() - left);
            }
        }
        int full = (1 << columns) - 1;
        int step = first <= last ? 1 : -1;
        for (int y = first; y != last + step; y += step) {
            int printed = 0;
            for (int x = left; x <= right; x++) {
                if ((ink[y * width + x] & 255) < 175) printed |= 1 << (x - left);
            }
            connected &= printed;
            if (connected == 0) return false;
            if ((ink[y * width + left - 4] & 255) >= 175 || (ink[y * width + right + 4] & 255) >= 175) {
                int grown;
                do {
                    grown = connected;
                    connected |= ((connected << 1) | (connected >>> 1)) & printed & full;
                } while (connected != grown);
            }
        }
        for (var column : headSide.columns()) {
            if ((connected & (1 << (column.x() - left))) != 0) return true;
        }
        return false;
    }

    private static boolean sharedDownstemTip(PrintedBeamShafts.Shaft first,
            PrintedBeamShafts.Shaft second, float gap) {
        if (first.direction() != 1 || second.direction() != 1
                || Math.abs(first.endY() - second.endY()) > .29f * gap) return false;
        for (var a : first.columns()) {
            if (Math.abs(a.endY() - first.endY()) > 1) continue;
            for (var b : second.columns()) {
                if (a.x() == b.x() && Math.abs(a.endY() - b.endY()) <= 1) return true;
            }
        }
        return false;
    }

    private static boolean verifiesPrintedPageOpening(byte[] ink, int width, int height,
            List<MeasureRegion> bars, float first, float last, float nominal) {
        if (bars.size() < 2) return false;
        var bar = bars.get(0);
        var next = bars.get(1);
        float inset = first - bar.left() * width;
        if (inset < 1.75f * nominal || inset > 2.75f * nominal
                || Math.abs(bar.top() - next.top()) * height > .45f * nominal
                || Math.abs(bar.bottom() - next.bottom()) * height > .55f * nominal
                || next.left() < bar.right() || (next.left() - bar.right()) * width > 1.35f * nominal)
            return false;
        var staff = WrappedMeasureBoundaries.verifiedTripletStaff(ink, width, height, bar, true);
        if (staff == null || !WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(
                ink, width, height, bar, last, staff)) return false;
        var frame = staff.at(first - 2 * nominal);
        float gap = frame[1];
        int x0 = Math.round(first - 2.7f * gap), x1 = Math.round(first - .85f * gap);
        int top = Math.round(frame[0] - 4 * gap), bottom = Math.round(frame[0] + .2f * gap);
        if (x0 < 0 || x1 >= width || top < 4 || bottom >= height) return false;
        var crop = new MeterChangeDetector.Crop(x0, top - 3, x1, bottom, top, gap);
        var glyph = MeterFontMatcher.readCrop(ink, width, height, crop);
        if (!"4/4".equals(glyph.text()) || glyph.topScore() < .86f || glyph.bottomScore() < .86f)
            return false;
        int clearEnd = Math.round(first - .73f * gap);
        for (int x = x1 + 1; x <= clearEnd; x++)
            for (int y = top; y <= Math.round(frame[0]); y++) {
                float step = (frame[0] - y) / gap;
                if (Math.abs(step - Math.round(step)) > .15f && (ink[y * width + x] & 255) < 165)
                    return false;
            }
        return clearEnd > x1;
    }

    private static boolean provesUpstemHeaderPocket(byte[] plane, int width, int height,
            List<MeasureRegion> bars, int index, Dyad first, float lastHead, float gap) {
        if (index == 0 || index + 1 >= bars.size()) return false;
        var bar = bars.get(index);
        var next = bars.get(index + 1);
        if ((bar.top() - bars.get(index - 1).bottom()) * height < 3 * gap
                || Math.abs(bar.top() - next.top()) * height > .75f * gap
                || Math.abs(bar.bottom() - next.bottom()) * height > gap
                || next.left() < bar.right() || (next.left() - bar.right()) * width > 1.4f * gap
                || first.center() - bar.left() * width < 3.5f * gap) return false;
        float left = Math.min(first.upperX, first.lowerX) - 1.25f * gap;
        float right = Math.min(first.upperX, first.lowerX) - .76f * gap;
        float hint = first.lowerY + first.lower.staffStep() * gap * .5f;
        var rules = PrintedDirectionStaff.printedRules(plane, width, height, (left + right) * .5f, hint, gap);
        if (rules == null || !rulesCrossHeaderPocket(plane, width, height, left, right, rules)) return false;
        for (int x = Math.round(left); x <= Math.round(right); x++) {
            int run = 0;
            for (int y = Math.round(rules[0] - 4 * rules[1]); y <= Math.round(rules[0]); y++) {
                run = (plane[y * width + x] & 255) < 170 ? run + 1 : 0;
                if (run >= .66f * rules[1]) return false;
            }
        }
        return WrappedMeasureBoundaries.confirmsSampledClosingEdge(plane, width, height, bar, lastHead);
    }

    private static boolean provesMeasuredSystemOpening(byte[] plane, int width, int height,
            List<MeasureRegion> bars, int index, Dyad first, Dyad last,
            PrintedBeamShafts.Shaft firstShaft, float nominal) {
        if (index < 0 || index + 1 >= bars.size()) return false;
        var bar = bars.get(index);
        boolean pageOpening = index == 0 && firstShaft.direction() == 1;
        if (index == 0 && !pageOpening) return false;
        var next = bars.get(index + 1);
        float left = bar.left() * width, right = bar.right() * width, nextLeft = next.left() * width;
        float gap = (first.lowerY - first.upperY) / 3.5f;
        if (gap < nominal * .86f || gap > nominal * 1.06f
                || index > 0 && (bar.top() - bars.get(index - 1).bottom()) * height < gap * 3
                || Math.abs(bar.top() - next.top()) * height > .75f * gap
                || Math.abs(bar.bottom() - next.bottom()) * height > 1.05f * gap
                || nextLeft - right < 0 || nextLeft - right > 1.4f * gap) return false;
        float start = left + gap * (pageOpening ? .24f : 1.42f);
        float finish = pageOpening ? firstShaft.left() - gap * .23f : first.upperX - gap * 1.35f;
        boolean narrowHeader = !pageOpening && finish - start < gap * .9f;
        if (narrowHeader) start = left + gap * .43f;
        if (finish - start < gap * .9f || start < 0 || finish >= width) return false;
        float hint = first.lowerY + first.lower.staffStep() * gap * .5f;
        var near = PrintedDirectionStaff.printedRules(plane, width, height, start, hint, gap);
        if (narrowHeader && near == null) {
            float lastHint = last.lowerY + last.lower.staffStep() * gap * .5f;
            var closingRules = PrintedDirectionStaff.printedRules(
                    plane, width, height, right - gap * .76f, lastHint, gap);
            if (closingRules != null
                    && rulesCrossHeaderPocket(plane, width, height, start, finish, closingRules))
                near = closingRules;
        }
        var far = (pageOpening || narrowHeader) && near != null
                && rulesCrossHeaderPocket(plane, width, height, start, finish, near)
                ? near : PrintedDirectionStaff.printedRules(plane, width, height, finish, hint, gap);
        if (near == null || far == null || Math.abs(near[0] - far[0]) > .22f * gap
                || Math.abs(near[1] - far[1]) > .09f * gap) return false;
        int top = Math.round(near[0] - near[1] * 4), bottom = Math.round(near[0]);
        if (top < 0 || bottom >= height) return false;
        for (int x = Math.round(start); x <= Math.round(finish); x++) {
            int run = 0;
            for (int y = top; y <= bottom; y++) {
                run = (plane[y * width + x] & 255) < 170 ? run + 1 : 0;
                if (run >= .66f * near[1]) return false;
            }
        }
        float boundary = (right + nextLeft) * .5f;
        if (boundary - last.lowerX < 1.2f * gap || boundary - last.lowerX > 3.2f * gap)
            return false;
        float endHint = last.lowerY + last.lower.staffStep() * gap * .5f;
        var rules = PrintedDirectionStaff.printedRules(plane, width, height,
                right - gap * 1.11f, endHint, gap);
        if (rules == null || Math.abs(rules[1] - near[1]) > .08f * gap
                || Math.abs(rules[0] - far[0]) > gap * .90f) return false;
        top = Math.round(rules[0] - 4 * rules[1]);
        bottom = Math.round(rules[0]);
        int hits = 0;
        for (int x = Math.round(boundary - .36f * gap); x <= Math.round(boundary + .36f * gap); x++) {
            if (x < 0 || x >= width || top < 0 || bottom >= height) return false;
            int count = 0, longest = 0, consecutive = 0;
            for (int y = top; y <= bottom; y++) {
                boolean dark = (plane[y * width + x] & 255) < 165;
                if (dark) count++;
                consecutive = dark ? 0 : consecutive + 1;
                longest = Math.max(longest, consecutive);
            }
            if (count >= .92f * (bottom - top + 1) && longest <= 2) hits++;
        }
        return hits >= 2 && hits <= .44f * rules[1];
    }

    private static boolean rulesCrossHeaderPocket(byte[] ink, int width, int height, float first,
            float last, float[] staff) {
        int left = Math.round(first), right = Math.round(last);
        for (int line = 0; line < 5; line++) {
            int midY = Math.round(staff[0] - line * staff[1]);
            if (midY < 1 || midY + 1 >= height) return false;
            int columns = 0;
            for (int x = left; x <= right; x++)
                if ((ink[(midY - 1) * width + x] & 255) < 170
                        || (ink[midY * width + x] & 255) < 170
                        || (ink[(midY + 1) * width + x] & 255) < 170) columns++;
            if (columns < .77f * (right - left + 1)) return false;
        }
        return true;
    }

    /** The upper axis can pause immediately after crossing a ruled staff line. */
    private static boolean bridgesInterruptedStaffShaft(byte[] gray, int width, int height, Dyad pair,
            PrintedBeamShafts.Shaft upper, PrintedBeamShafts.Shaft lower, float gap) {
        if (upper.direction() != 1 || lower.direction() != 1
                || upper.endY() - pair.upperY < 1.45f * gap
                || upper.endY() - pair.upperY > 2.25f * gap
                || pair.lowerY - upper.endY() < 1.05f * gap
                || lower.endY() - pair.lowerY < 2.25f * gap) return false;
        int first = Math.max(upper.left(), lower.left()), last = Math.min(upper.right(), lower.right());
        int tip = upper.endY(), reach = Math.round(gap * .86f);
        int center = (first + last) / 2;
        if (last - first < 1 || center - reach < 0 || center + reach >= width || tip < 1
                || lower.startY() >= height || lower.startY() <= tip) return false;
        int points = 0;
        for (int x = center - reach; x <= center + reach; x++) {
            if ((gray[(tip - 1) * width + x] & 255) < 165
                    && (gray[tip * width + x] & 255) < 165) points++;
        }
        if (points < Math.round(reach * 1.8f)) return false;
        int occupied = 0, opening = 0, bleed = 0;
        for (int y = tip + 1; y <= lower.startY(); y++) {
            boolean joined = false;
            for (int x = first; x <= last; x++) joined |= (gray[y * width + x] & 255) < 175;
            if (joined) {
                if (opening != 0) occupied++;
                else if (++bleed > 2) return false;
            } else if (occupied != 0 || ++opening > Math.round(gap * .36f)) {
                return false;
            }
        }
        return opening >= 1 && occupied >= gap * .79f;
    }

    /** Recover a downstem cut into two apparent rails only from a continuous shared axis. */
    /** The terminal beam tip is already owned by the lower head. Prove its route to the upper head. */
    private static boolean interruptedTerminalShaft(byte[] ink, int width, int height,
            Dyad pair, PrintedBeamShafts.Shaft shaft, float gap, PrintedBeamShafts.Budget budget) {
        int start = Math.round(pair.upperY + .18f * gap);
        int end = Math.round(pair.lowerY + .20f * gap);
        if (shaft.direction() != 1 || shaft.columns().size() < 2
                || start < 0 || end >= height || start >= end
                || !budget.take((long) shaft.columns().size() * (end - start + 1))) return false;
        for (var column : shaft.columns()) {
            int x = column.x();
            int missing = 0;
            for (int y = start; y <= end; y++) {
                if ((ink[y * width + x] & 255) < 170) continue;
                if (++missing > 2 || !hasAdjacentStaffRule(ink, width, height, x, y, -1, gap))
                    return false;
            }
        }
        return true;
    }

    private static PrintedBeamShafts.Shaft continuousDyadShaft(byte[] ink, int width, int height,
            Dyad pair, float gap, PrintedBeamShafts.Budget budget) {
        int left = (int) Math.ceil(Math.max(pair.upperX, pair.lowerX) - gap * .80f);
        int right = (int) Math.floor(Math.min(pair.upperX, pair.lowerX) - gap * .43f);
        int start = Math.round(pair.upperY + gap * .20f);
        int minTip = Math.round(pair.lowerY + gap * 2.35f);
        int limit = Math.round(pair.lowerY + gap * 4.35f);
        if (left < 0 || right >= width || right < left || start < 0 || limit >= height
                || !budget.take((long) (right - left + 1) * (limit - start + 1))) return null;
        var columns = new ArrayList<PrintedBeamShafts.Column>();
        for (int x = left; x <= right; x++) {
            int y = start;
            while (y <= limit && (ink[y * width + x] & 255) < 170) y++;
            if (y > minTip && y <= limit) columns.add(new PrintedBeamShafts.Column(x, y - 1));
        }
        if (columns.size() < 2 || columns.size() > Math.round(gap * .32f)) return null;
        var first = columns.get(0);
        var last = columns.get(columns.size() - 1);
        if (last.x() - first.x() + 1 != columns.size()) return null;
        int top = columns.stream().mapToInt(PrintedBeamShafts.Column::endY).min().orElseThrow();
        int tip = columns.stream().mapToInt(PrintedBeamShafts.Column::endY).max().orElseThrow();
        if (tip - top > gap * .20f) return null;
        return new PrintedBeamShafts.Shaft(first.x(), last.x(), start, tip, 1, columns);
    }

    /**
     * Recover a faded system-opening stem from nearly uninterrupted source ink across
     * both noteheads and into the beam. Later checks still require all rails and the bar edge.
     */
    private static PrintedBeamShafts.Shaft fadedOpeningShaft(byte[] ink, int width, int height,
            Dyad pair, float gap, PrintedBeamShafts.Budget budget) {
        int upperDirection = pair.upper.stemDirection();
        int lowerDirection = pair.lower.stemDirection();
        if (upperDirection != 0 && lowerDirection != 0 && upperDirection != lowerDirection) return null;
        int direction = -(upperDirection == 0 ? lowerDirection : upperDirection);
        if (Math.abs(direction) != 1) return null;
        float farY = direction == 1 ? pair.upperY : pair.lowerY;
        float nearY = direction == 1 ? pair.lowerY : pair.upperY;
        float headLeft = Math.max(pair.upperX, pair.lowerX);
        float headRight = Math.min(pair.upperX, pair.lowerX);
        int left = (int) Math.ceil(headLeft + gap * (direction == 1 ? -.80f : .43f));
        int right = (int) Math.floor(headRight + gap * (direction == 1 ? -.43f : .80f));
        int start = Math.round(farY + direction * .20f * gap);
        int limit = Math.round(nearY + direction * 4.35f * gap);
        if (left < 0 || right >= width || right < left
                || Math.min(start, limit) < 0 || Math.max(start, limit) >= height
                || !budget.take((long) (right - left + 1) * (1 + Math.abs(limit - start)))) return null;
        var columns = new ArrayList<PrintedBeamShafts.Column>();
        for (int x = left; x <= right; x++) {
            if ((ink[start * width + x] & 255) >= 175) continue;
            int tip = start, missing = 0, run = 0;
            boolean crossedStaffGap = false;
            for (int y = start; (limit - y) * direction >= 0; y += direction) {
                if ((ink[y * width + x] & 255) >= 175) {
                    if (++run > Math.round(.64f * gap)) break;
                    continue;
                }
                if (run > 2) {
                    if (crossedStaffGap || !hasAdjacentStaffRule(ink, width, height, x, y, direction, gap)) break;
                    crossedStaffGap = true;
                } else {
                    missing += run;
                }
                if (missing > 5) break;
                run = 0;
                tip = y;
            }
            float reach = (tip - nearY) * direction;
            if (reach >= 2.35f * gap && reach <= 4.2f * gap)
                columns.add(new PrintedBeamShafts.Column(x, tip));
        }
        if (columns.isEmpty() || columns.size() > Math.round(.32f * gap)) return null;
        int tip = columns.stream().mapToInt(c -> direction * c.endY()).max().orElseThrow() * direction;
        columns.removeIf(c -> (tip - c.endY()) * direction > .24f * gap);
        int first = columns.get(0).x(), last = columns.get(columns.size() - 1).x();
        if (last - first + 1 != columns.size()) return null;
        return new PrintedBeamShafts.Shaft(first, last, start, tip, direction, columns);
    }

    private static boolean hasAdjacentStaffRule(byte[] ink, int width, int height, int x, int y,
            int direction, float gap) {
        int radius = Math.round(gap * .75f);
        if (x - radius < 0 || x + radius >= width) return false;
        for (int dy = 0; dy <= Math.round(.26f * gap); dy++) {
            int row = y + direction * dy;
            if (row < 0 || row >= height) return false;
            int leading = 0, trailing = 0;
            for (int dx = 3; dx <= radius; dx++) {
                if ((ink[row * width + x - dx] & 255) < 170) leading++;
                if ((ink[row * width + x + dx] & 255) < 170) trailing++;
            }
            if (leading >= radius - 3 && trailing >= radius - 3) return true;
        }
        return false;
    }

    private static boolean competingOctaveDot(byte[] gray, int width, int height,
            float x, float y, float next, float gap, Dyad following, boolean upper) {
        var dot = WrappedTripletDotInk.uniqueCore(gray, width, height, x, y, next, gap);
        if (dot == null || linkedDot(gray, width, height, x, y, gap, dot)) return false;
        if (following == null) return true;
        var note = upper ? following.upper : following.lower;
        return note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT
                || !WrappedTripletDotInk.compactSingleFlatOwns(gray, width, height, dot,
                        upper ? following.upperX : following.lowerX,
                        upper ? following.upperY : following.lowerY, gap);
    }

    private static boolean provedInitialBar(byte[] gray, int width, int height, List<MeasureRegion> bars,
            int index, float first, float last, PrintedBeamShafts.Shaft shaft, float nominal) {
        if (shaft.direction() == 1 && WrappedMeasureBoundaries.confirmsSampledInitialTripletBar(
                gray, width, height, bars, index, first, last, shaft.left())) return true;
        if (WrappedMeasureBoundaries.confirmsInitialStaffBar(
                gray, width, height, bars, index, first, last)) return true;
        float inset = first - shaft.left();
        if (shaft.direction() == 1 && inset >= nominal * .35f && inset <= nominal * .85f
                && WrappedMeasureBoundaries.confirmsInitialStaffBar(
                        gray, width, height, bars, index, first, last, shaft.left())
                && WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(
                        gray, width, height, bars.get(index), last)) return true;
        if (shaft.direction() != 1 || !WrappedMeasureBoundaries.confirmsShaftBoundedInitialStaffBar(
                gray, width, height, bars, index, first, shaft.left())) return false;
        var bar = bars.get(index);
        if (WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(gray, width, height, bar, last))
            return true;
        var right = (bar.right() + bars.get(index + 1).left()) * width * .5f;
        if (right - last < nominal * .7f) return false;
        var rules = PrintedDirectionStaff.printedRules(gray, width, height, right - nominal * 1.4f,
                bar.bottom() * height - nominal * 2.1f, nominal);
        if (rules == null) return false;
        var staff = new PlayingTechniqueDetector.Staff(rules[0] - 4 * rules[1], rules[0], rules[1], 0, 1);
        return PrintedCadenceDurations.boundaryColumn(gray, width, staff, right, false, false) >= 0;
    }

    private static PrintedBeamShafts.Shaft middleShaft(
            byte[] gray,
            int w,
            int h,
            Dyad pair,
            PrintedBeamShafts.Shaft shortStroke,
            PrintedBeamShafts.Shaft prior,
            PrintedBeamShafts.Shaft next,
            float gap) {
        int direction = prior.direction();
        if (direction != next.direction() || direction != shortStroke.direction()) return null;
        float a = (prior.left() + prior.right()) * .5f, b = (next.left() + next.right()) * .5f;
        if (b <= a) return null;
        float beamSide = direction == 1 ? pair.lowerY : pair.upperY;
        float back = direction == 1 ? pair.upperY : pair.lowerY;
        var columns = new ArrayList<PrintedBeamShafts.Column>();
        for (int x = shortStroke.left(); x <= shortStroke.right(); x++) {
            float delta0 = (pair.upperX - x) * direction, delta1 = (pair.lowerX - x) * direction;
            if (delta0 < gap * .4f
                    || delta1 < gap * .4f
                    || delta0 > gap * .84f
                    || delta1 > gap * .84f) continue;
            int expected =
                    Math.round(prior.endY() + (next.endY() - prior.endY()) * (x - a) / (b - a));
            if ((expected - shortStroke.endY()) * direction < gap * .4f
                    || (expected - shortStroke.endY()) * direction > gap * 1.9f) continue;
            int start = Math.round(back + direction * gap * .2f);
            int limit = expected + direction * Math.round(gap * .34f);
            if (Math.min(start, limit) < 0 || Math.max(start, limit) >= h) continue;
            int run = 0, gaps = 0, end = start;
            boolean valid = true;
            for (int y = start; (limit - y) * direction >= 0; y += direction) {
                if ((gray[y * w + x] & 255) >= 165) {
                    if (++run > Math.round(gap * .36f)) break;
                    continue;
                }
                if (run > 0) {
                    if (++gaps != 1 || (y - beamSide) * direction < gap * .7f) {
                        valid = false;
                        break;
                    }
                    run = 0;
                }
                end = y;
            }
            if (valid && gaps == 1 && Math.abs(end - expected) <= gap * .27f)
                columns.add(new PrintedBeamShafts.Column(x, end));
        }
        if (columns.size() < 2
                || columns.get(columns.size() - 1).x() - columns.get(0).x() != columns.size() - 1)
            return null;
        int end =
                columns.stream()
                        .mapToInt(PrintedBeamShafts.Column::endY)
                        .reduce((x, y) -> direction == 1 ? Math.max(x, y) : Math.min(x, y))
                        .orElseThrow();
        return new PrintedBeamShafts.Shaft(
                columns.get(0).x(),
                columns.get(columns.size() - 1).x(),
                shortStroke.startY(),
                end,
                direction,
                columns);
    }

    private static boolean rhythmCandidate(ScoreNoteEvent note, int clef) {
        return ordinaryFields(note, clef)
                && note.beamCount() <= 2
                && note.unbeamedDurationBeats() == (note.beamCount() == 0 ? 1 : 0);
    }

    private static float octaveCenter(
            List<ScoreNoteEvent> owners, int first, float left, float span) {
        return ((left + span * owners.get(first).positionInMeasure())
                        + (left + span * owners.get(first + 1).positionInMeasure()))
                * .5f;
    }

    private static final int ALL_OCTAVE_DYADS = (1 << 12) - 1;

    private static int seededDyads(List<ScoreNoteEvent> owners) {
        int seeds = 0;
        for (int i = 0; i < owners.size(); i += 2) {
            if (owners.get(i).tupletDivisor() == 3) seeds |= 1 << (i / 2);
        }
        return seeds;
    }

    /** The recognizer can attach one unmarked triplet across a physical beam break. */
    private static boolean displacedSeed(int seeds) {
        int first = Integer.numberOfTrailingZeros(seeds);
        return first < 12 && first % 3 != 0 && seeds == (0b111 << first);
    }

    private static boolean oneSeededTriplet(int seeds) {
        int first = Integer.numberOfTrailingZeros(seeds);
        return first % 3 == 0 && seeds == (0b111 << first);
    }

    private static boolean competingDot(
            byte[] gray,
            int width,
            int height,
            float headX,
            float headY,
            float nextX,
            float gap,
            boolean allowLinkedMarks) {
        var dot = WrappedTripletDotInk.uniqueCore(gray, width, height, headX, headY, nextX, gap);
        return dot != null && (!allowLinkedMarks || !linkedDot(gray, width, height, headX, headY, gap, dot));
    }

    private static boolean linkedDot(byte[] gray, int width, int height, float headX, float headY,
            float gap, WrappedTripletDotInk.Center dot) {
        return WrappedEighthTriplets.headConnectedLedger(gray, width, height, headX, headY, dot, gap, .40f)
                || OctaveMarkDetector.dotBelongsToDashOrigin(gray, width, height, headX, headY, dot, gap);
    }

    static List<PrintedBeamShafts.Shaft> contrastShaft(
            byte[] gray,
            int width,
            int height,
            float x,
            float y,
            float gap,
            PrintedBeamShafts.Budget budget) {
        var contrast =
                new PrintedBeamShaftPixels.Frame(
                        gray,
                        gray,
                        width,
                        height,
                        0,
                        units -> {
                            if (!budget.take(units)) throw new ShaftQuotaExceeded();
                        },
                        true);
        try {
            return PrintedBeamShafts.at(gray, width, height, x, y, gap, budget, contrast);
        } catch (ShaftQuotaExceeded exhausted) {
            return List.of();
        }
    }

    private static final class ShaftQuotaExceeded extends RuntimeException {}

    /** Faint intersections may interrupt an upper read while the common lower shaft remains literal. */
    private static boolean continuesUpperShaft(
            byte[] gray,
            int width,
            int height,
            float headX,
            float headY,
            PrintedBeamShafts.Shaft lower,
            float gap) {
        int axis = (lower.left() + lower.right()) / 2;
        int left = axis - 2,
                right = axis + 1,
                top = Math.round(headY + gap * .2f),
                bottom = lower.startY();
        if (left < 0
                || right >= width
                || top < 0
                || bottom >= height
                || bottom - top < gap * 2.8f
                || headX - axis < gap * .42f
                || headX - axis > gap * .78f) return false;
        int soft = 0, run = 0;
        for (int y = top; y <= bottom; y++) {
            int minimum = 255;
            for (int x = left; x <= right; x++)
                minimum = Math.min(minimum, gray[y * width + x] & 255);
            if (minimum >= 235) return false;
            if (minimum >= 160) {
                if (++soft > 4 || ++run > 3) return false;
            } else run = 0;
        }
        return true;
    }

    private static boolean ordinary(ScoreNoteEvent note, int clef) {
        return note.stemDirection() == -1
                && note.beamCount() == 1
                && note.unbeamedDurationBeats() == 0
                && ordinaryFields(note, clef);
    }

    private static boolean ordinaryFields(ScoreNoteEvent note, int clef) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.staffCount() == 1
                && note.staffIndex() == 0
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && note.augmentationDots() == 0
                && !note.crossStaffBeam()
                && note.leadingRestBeats() == 0
                && note.followingRestBeats() == 0
                && !note.tiedFromPrevious()
                && note.boundaryTies() == 0
                && note.clefBottomDiatonic() == clef
                && (note.articulations() & NoteOrnament.GRACE) == 0
                && (note.tupletDivisor() == 1 && note.tupletNormalNotes() == 1
                        || note.tupletDivisor() == 3 && note.tupletNormalNotes() == 2);
    }
}
