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
        ScorePageInterpretation projectBeams(ScorePageInterpretation score, int page) {
            if (proved.isEmpty() || page < 0 || score.notes().size() != notes.size()) return score;
            var source = score.beamSource();
            var origins = new ArrayList<>(source.noteOrigins());
            var groups = new ArrayList<>(source.groups());
            var identities = new HashMap<ScoreBeamSource.Anchor, Integer>();
            var claimed = new HashSet<ScoreBeamSource.Anchor>();
            var ids = new HashSet<String>();
            for (var origin : origins) origin.ifPresent(anchor -> identities.merge(anchor, 1, Integer::sum));
            for (var group : groups) {
                ids.add(group.id());
                for (var column : group.columns()) claimed.addAll(column.members());
            }
            for (var column : source.detachedColumns()) claimed.addAll(column.members());
            for (var bar : proved) {
                var slots = bar.owners();
                var anchors = new ArrayList<ScoreBeamSource.Anchor>(24);
                for (int slot : slots) {
                    var note = score.notes().get(slot);
                    var expected = notes.get(slot);
                    var anchor = new ScoreBeamSource.Anchor(page, slot);
                    var present = origins.get(slot);
                    if (claimed.contains(anchor) || note.measureIndex() != bar.measure()
                            || !note.equals(expected)
                            || (present.isPresent() ? !present.get().equals(anchor)
                                    || identities.getOrDefault(anchor, 0) != 1 : identities.containsKey(anchor)))
                        break;
                    anchors.add(anchor);
                }
                if (anchors.size() != 24) continue;
                var additions = new ArrayList<ScoreBeamSource.Group>(4);
                for (int start = 0; start < anchors.size(); start += 6) {
                    var columns = new ArrayList<ScoreBeamSource.Column>(3);
                    for (int i = start; i < start + 6; i += 2)
                        columns.add(new ScoreBeamSource.Column(anchors.subList(i, i + 2)));
                    int first = Math.min(anchors.get(start).noteIndex(), anchors.get(start + 1).noteIndex());
                    String id = "printed-beam:" + page + ":" + first;
                    if (ids.contains(id)) break;
                    additions.add(new ScoreBeamSource.Group(id, columns, List.of(1, 1), List.of(),
                            ScoreBeamSource.TupletMark.UNNUMBERED));
                }
                if (additions.size() != 4) continue;
                groups.addAll(additions);
                for (var group : additions) ids.add(group.id());
                for (int i = 0; i < slots.size(); i++) {
                    var anchor = anchors.get(i);
                    origins.set(slots.get(i), Optional.of(anchor));
                    claimed.add(anchor);
                    identities.put(anchor, 1);
                }
            }
            return groups.size() == source.groups().size() ? score
                    : score.withBeamSource(new ScoreBeamSource(groups, source.detachedColumns(), origins));
        }
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
            var directions = directionsForClosedRails(notes, order, measures, m, gray, width, height, false);
            if (directions == null && original != null && original.length == gray.length) {
                var normalized = directionsForClosedRails(notes, order, measures, m, gray, width, height, true);
                if (normalized != null && java.util.Arrays.equals(normalized,
                        directionsForClosedRails(notes, order, measures, m, original, width, height, true))) {
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
                    if (unnumbered == 15) proved.add(new BeamBar(m, order));
                }
            }
            if (directions == null) continue;
            if (result == null) result = new ArrayList<>(notes);
            for (int i = 0; i < order.size(); i++) {
                var note = notes.get(order.get(i));
                if (note.beamCount() != 1) note = ClosingTripletRhythm.singleBeam(note);
                if (note.stemDirection() == 0) note = note.withStemDirection(directions[i / 2]);
                result.set(order.get(i), note.withTupletRatio(3, 2));
            }
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

    private static byte[] withoutPrintedOctaveDashes(byte[] source, int width, int height,
            List<PlayingTechniqueDetector.Staff> staffs) {
        var ink = OctaveMarkDetector.printedDashInk(source, width, height, staffs);
        if (ink.isEmpty()) return source;
        byte[] cleared = source.clone();
        for (int pixel = ink.nextSetBit(0); pixel >= 0; pixel = ink.nextSetBit(pixel + 1))
            cleared[pixel] = (byte) 255;
        return cleared;
    }

    private static int clearUnnumberedGroups(
            List<ScoreNoteEvent> notes, List<Integer> order, List<MeasureRegion> bars, int index,
            byte[] normalized, byte[] original, int w, int h, List<PlayingTechniqueDetector.Word> directions) {
        var heads = order.stream().map(notes::get).toList();
        if (seededDyads(heads) != 0) return 0;
        var bar = bars.get(index);
        float left = bar.left() * w, span = (bar.right() - bar.left()) * w;
        float gap = (bar.bottom() - bar.top()) * h / 8.2f;
        var rows = UnnumberedTripletInk.sourceRows(bar, bars, h);
        var rawStaff = WrappedMeasureBoundaries.verifiedTripletStaff(original, w, h, bar, true);
        var correctedStaff = WrappedMeasureBoundaries.verifiedTripletStaff(normalized, w, h, bar, true);
        if (rawStaff == null || correctedStaff == null) return 0;
        int mask = 0;
        for (int group = 0; group < 4; group++) {
            var members = heads.subList(group * 6, group * 6 + 6);
            if (noPrintedTripletMark(original, w, h, members, left, span, gap, rows, rawStaff, directions)
                    && noPrintedTripletMark(normalized, w, h, members, left, span, gap, rows, correctedStaff, directions))
                mask |= 1 << group;
        }
        return mask;
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
            var shaft = PrintedBeamShafts.at(source, w, h, left + span * lower.positionInMeasure(),
                    lower.pageY() * h, gap, budget);
            if (shaft.size() != 1 || shaft.get(0).direction() != 1 || budget.refused()) return false;
            tipTop = Math.min(tipTop, shaft.get(0).endY());
            tipBottom = Math.max(tipBottom, shaft.get(0).endY());
        }
        var accidentalHeads = heads.stream().filter(n -> n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_SHARP
                        || n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL)
                .map(n -> new UnnumberedTripletInk.AccidentalHead(
                        left + span * n.positionInMeasure(), n.pageY() * h, n.writtenAccidental()))
                .toList();
        return UnnumberedTripletInk.clearFingeringBands(source, w, h, gap,
                first, last, headTop, headBottom, tipTop, tipBottom, rows, track, null, accidentalHeads, directions);
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
                || span < gap * 30
                || span > gap * 40) return null;
        var heads = order.stream().map(notes::get).toList();
        int seeds = seededDyads(heads);
        if (seeds != 0 && !oneSeededTriplet(seeds)) return null;
        float firstHead = octaveCenter(heads, 0, left, span);
        float lastHead = octaveCenter(heads, 22, left, span);
        boolean closed = WrappedMeasureBoundaries.confirmsClosed(gray, w, h, bar, firstHead, lastHead);
        if (!closed && !allowStaffOpening) return null;
        var pairs = new Dyad[12];
        var shafts = new PrintedBeamShafts.Shaft[12];
        var incomplete = new PrintedBeamShafts.Shaft[12];
        var budget = new PrintedBeamShafts.Budget(280_000);
        int clef = heads.get(0).clefBottomDiatonic();
        if (clef == ScoreNoteEvent.CLEF_UNKNOWN) return null;
        boolean repairedBeam = false;
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
            if (!rhythmCandidate(upper, clef)
                    || !rhythmCandidate(lower, clef) && !beamAlias
                    || upper.staffStep() - lower.staffStep() != 7
                    || upper.tupletDivisor() != lower.tupletDivisor()
                    || Math.abs(x0 - x1) > gap * (allowStaffOpening ? .21f : .18f)
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
            if (budget.refused()
                    || u.size() > 1 || v.size() > 1
                    || (u.isEmpty() || v.isEmpty()) && beamAlias
                    || u.isEmpty() && v.isEmpty())
                return null;
            if (u.size() == 1 && v.size() == 1) {
                var first = u.get(0);
                var second = v.get(0);
                if (first.direction() != second.direction()
                        || Math.max(first.left(), second.left())
                                > Math.min(first.right(), second.right())
                        || Math.abs(first.endY() - second.endY()) > gap * .15f) return null;
                shafts[i] = first.direction() == 1 ? second : first;
            } else {
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
            if (competingDot(gray, w, h, p.upperX, p.upperY, next, gap, true)
                    || competingDot(gray, w, h, p.lowerX, p.lowerY, next, gap, true)) return null;
        }
        if (!closed && !provedInitialBar(
                gray, w, h, bars, index, firstHead, lastHead, shafts[0], gap)) return null;
        return directions;
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

    private static int seededDyads(List<ScoreNoteEvent> owners) {
        int seeds = 0;
        for (int i = 0; i < owners.size(); i += 2) {
            if (owners.get(i).tupletDivisor() == 3) seeds |= 1 << (i / 2);
        }
        return seeds;
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
        return dot != null
                && (!allowLinkedMarks
                        || !WrappedEighthTriplets.headConnectedLedger(
                                        gray, width, height, headX, headY, dot, gap, .40f)
                                && !OctaveMarkDetector.dotBelongsToDashOrigin(
                                        gray, width, height, headX, headY, dot, gap));
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
