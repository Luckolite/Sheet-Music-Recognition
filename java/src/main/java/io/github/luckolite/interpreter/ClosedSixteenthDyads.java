// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/** Reject an excess dotted sixteenth when independent beam evidence fills an entire printed bar. */
final class ClosedSixteenthDyads {
    private static final float ACCIDENTAL_SPACED_LINK = 4.10f;

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
                var owners = bars.get(m);
                restoreClosedMonophonicSixFour(
                        ink, original, regions.get(m), next, owners, result);
                if (ClosedMonophonicSixFour.proves(ink, original, regions, m, owners, result)) {
                    for (var owner : owners) {
                        var note = result.get(owner.owner());
                        if (note.beamCount() != 2) note = asSharedBeam(note, 2);
                        if (note.augmentationDots() != 0) note = withoutDot(note);
                        if (note.tupletDivisor() != 1) note = note.withTupletRatio(1, 1);
                        result.set(owner.owner(), note);
                    }
                    if (physicalPage >= 0)
                        printedBeams = replaceMonophonicDoubleRails(printedBeams, physicalPage, owners);
                }
                continue;
            }
            var openingMeter = active == null ? inheritedMeter : active;
            if (count == 26
                    && original != null
                    && openingMeter != null
                    && openingMeter.numerator() == 4
                    && openingMeter.denominator() == 4
                    && regions.get(m).cadenceQuarterBeats() == 0
                    && score.rests().stream().noneMatch(r -> r.measureIndex() == bar)
                    && restoreOpeningSixteenthBeforeQuarter(
                            ink, original, regions.get(m), bars.get(m), result)) continue;
            var effective = active == null ? inheritedMeter : active;
            if (physicalPage >= 0 && original != null && effective != null
                    && effective.numerator() == 4 && effective.denominator() == 4
                    && (count == 26 || count == 32)
                    && regions.get(m).cadenceQuarterBeats() == 0
                    && beamOriginsUnclaimed(printedBeams, bars.get(m))
                    && score.rests().stream().noneMatch(r -> r.measureIndex() == bar)) {
                var thirds = straightThirdBeamOwners(ink, original, regions.get(m), bars.get(m));
                if (!thirds.isEmpty()) printedBeams = projectDoubleRails(printedBeams, physicalPage, thirds);
            }
            if (count == 26 && original != null && effective != null
                    && effective.numerator() == 4 && effective.denominator() == 4
                    && regions.get(m).cadenceQuarterBeats() == 0
                    && score.rests().stream().noneMatch(r -> r.measureIndex() == bar)
                    && closeTerminalStaccatoSixths(ink, original, regions.get(m), bars.get(m), result)) {
                if (physicalPage >= 0)
                    printedBeams = projectDoubleRails(printedBeams, physicalPage, bars.get(m));
                continue;
            }
            if (count == 24
                    && original != null
                    && effective != null
                    && effective.numerator() == 3
                    && effective.denominator() == 4
                    && regions.get(m).cadenceQuarterBeats() == 0
                    && score.rests().stream().noneMatch(r -> r.measureIndex() == bar)
                    && (closeAcrossPrintedBeams(ink, original, regions, m, bars.get(m), result, false)
                            || physicalPage >= 0 && beamOriginsUnclaimed(printedBeams, bars.get(m))
                                    && confirmsStraightChromaticSixths(
                                            ink, original, regions, m, bars.get(m), result))) {
                if (physicalPage >= 0)
                    printedBeams = projectDoubleRails(printedBeams, physicalPage, bars.get(m));
                continue;
            }
            if (count != 30 && count != 32 && count != 33
                    || regions.get(m).cadenceQuarterBeats() != 0
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == bar)) continue;
            if ((count == 32 || count == 33)
                    && original != null
                    && effective != null
                    && effective.numerator() == 4
                    && effective.denominator() == 4) {
                var quartet = terminalOctaveShaftQuartet(ink, original, regions.get(m), bars.get(m));
                if (!quartet.isEmpty()) {
                    var upper = quartet.get(6).y < quartet.get(7).y ? quartet.get(6) : quartet.get(7);
                    result.set(upper.owner, asSharedBeam(upper.note, 2));
                    if (physicalPage >= 0)
                        printedBeams = projectDoubleRails(printedBeams, physicalPage, quartet);
                    continue;
                }
            }
            if (count == 32 && original != null && effective != null
                    && effective.numerator() == 4 && effective.denominator() == 4
                    && reassignFollowingDyadStaccatos(ink, original, regions.get(m), bars.get(m), result))
                continue;
            if (count == 32 && original != null && effective != null
                    && effective.numerator() == 4 && effective.denominator() == 4
                    && closeArticulatedDyadRails(ink, original, regions, m, bars.get(m), result)) {
                if (physicalPage >= 0)
                    printedBeams = projectDoubleRails(printedBeams, physicalPage, bars.get(m));
                continue;
            }
            if (count == 32
                    && original != null
                    && effective != null
                    && effective.numerator() == 4
                    && effective.denominator() == 4
                    && (closeAcrossPrintedBeams(ink, original, regions, m, bars.get(m), result,
                            physicalPage >= 0 && beamOriginsUnclaimed(printedBeams, bars.get(m)))
                            || closeOctavesAcrossTripletCut(
                                    ink, original, regions, m, bars.get(m), result)
                            || effective.measureIndex() == m
                                    && closeFingeredOpeningOctaves(
                                            ink, original, regions, m, bars.get(m), result))) {
                if (physicalPage >= 0)
                    printedBeams = projectDoubleRails(printedBeams, physicalPage, bars.get(m));
                continue;
            }
            if (physicalPage >= 0
                    && count == 32
                    && original != null
                    && effective != null
                    && effective.numerator() == 4
                    && effective.denominator() == 4
                    && confirmsStraightOctaveBeams(
                            ink, original, regions.get(m), bars.get(m), notes)) {
                printedBeams = projectDoubleRails(printedBeams, physicalPage, bars.get(m));
                continue;
            }
            if (count == 33 || active == null || active.numerator() != 4 || active.denominator() != 4)
                continue;
            if (count == 32 && original != null
                    && clearOpeningOctaveDots(ink, original, regions, m, bars.get(m), result)) continue;
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
        if (result.equals(notes))
            return printedBeams == score.beamSource() ? score : score.withBeamSource(printedBeams);
        var corrected = score.withNotes(result, IntStream.range(0, notes.size()).boxed().toList());
        return printedBeams == score.beamSource()
                ? corrected
                : corrected.withBeamSource(printedBeams);
    }

    /** Keep the existing source identities and replace groups owned entirely by this closed bar. */
    static ScoreBeamSource replaceMonophonicDoubleRails(
            ScoreBeamSource source, int physicalPage, List<Head> heads) {
        var owned = new HashSet<ScoreBeamSource.Anchor>();
        for (var head : heads) source.noteOrigins().get(head.owner()).ifPresent(owned::add);
        var groups = new ArrayList<ScoreBeamSource.Group>();
        for (var group : source.groups()) {
            var anchors = group.columns().stream().flatMap(c -> c.members().stream()).toList();
            if (anchors.stream().noneMatch(owned::contains)) groups.add(group);
            else if (!owned.containsAll(anchors)) return source;
        }
        var detached = new ArrayList<ScoreBeamSource.Column>();
        for (var column : source.detachedColumns()) {
            if (column.members().stream().noneMatch(owned::contains)) detached.add(column);
            else if (!owned.containsAll(column.members())) return source;
        }
        var remainder = new ScoreBeamSource(groups, detached, source.noteOrigins());
        var completed = projectDoubleRails(remainder, physicalPage, heads, 1);
        return completed == remainder ? source : completed;
    }

    private static ScoreBeamSource projectDoubleRails(
            ScoreBeamSource source, int physicalPage, List<Head> sourceHeads) {
        return projectDoubleRails(source, physicalPage, sourceHeads, 2);
    }

    private static ScoreBeamSource projectDoubleRails(
            ScoreBeamSource source, int physicalPage, List<Head> sourceHeads, int headsPerColumn) {
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
        for (int group = 0; group < heads.size() / (4 * headsPerColumn); group++) {
            var columns = new ArrayList<ScoreBeamSource.Column>(4);
            int firstOwner = Integer.MAX_VALUE;
            for (int slot = group * 4; slot < group * 4 + 4; slot++) {
                var members = new ArrayList<ScoreBeamSource.Anchor>(headsPerColumn);
                for (int across = 0; across < headsPerColumn; across++) {
                    int owner = heads.get(slot * headsPerColumn + across).owner;
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

    /** Close a printed 3/4 staff opening after the initial key has settled local tie ownership. */
    static ScorePageInterpretation applyClosedMonophonicThreeFour(
            ScorePageInterpretation score,
            byte[] gray,
            byte[] raw,
            int width,
            int height,
            int physicalPage) {
        if (gray == null
                || raw == null
                || gray.length != (long) width * height
                || raw.length != gray.length
                || width < 256
                || height < 128
                || score.meterChanges().stream()
                        .noneMatch(m -> m.numerator() == 3 && m.denominator() == 4)) return score;
        var normalized = new Ink(gray, width, height);
        var original = new Ink(raw, width, height);
        var notes = score.notes();
        var regions = score.measures();
        var bars = new ArrayList<List<Head>>(regions.size());
        for (int m = 0; m < regions.size(); m++) bars.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            int m = note.measureIndex();
            if (m < 0 || m >= regions.size()) continue;
            var region = regions.get(m);
            bars.get(m)
                    .add(
                            new Head(
                                    i,
                                    note,
                                    (region.left()
                                                    + note.positionInMeasure()
                                                            * (region.right() - region.left()))
                                            * width,
                                    note.pageY() * height));
        }
        var edits = new ArrayList<>(notes);
        var beams = score.beamSource();
        ScoreMeterChange active = null;
        int meter = 0;
        for (int m = 0; m + 1 < regions.size(); m++) {
            while (meter < score.meterChanges().size()
                    && score.meterChanges().get(meter).measureIndex() <= m)
                active = score.meterChanges().get(meter++);
            final int measure = m;
            var bar = bars.get(m);
            if (active == null || active.denominator() != 4
                    || regions.get(m).cadenceQuarterBeats() != 0
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == measure)) continue;
            boolean changed = active.numerator() == 3 && bar.size() == 12
                    && restoreClosedMonophonicThreeFour(normalized, original, regions, m, bar, edits);
            if (!changed && active.numerator() == 4 && bar.size() == 16
                    && score.meterChanges().stream().anyMatch(change -> change.measureIndex() == measure + 1
                            && change.numerator() == 3 && change.denominator() == 4)) {
                changed = restoreCommonTimeTerminalRail(normalized, original, regions, m, bar, edits);
            }
            if (changed && physicalPage >= 0) beams = projectDoubleRails(beams, physicalPage, bar, 1);
        }
        if (edits.equals(notes)) return score;
        return score.withNotes(edits, IntStream.range(0, notes.size()).boxed().toList())
                .withBeamSource(beams);
    }

    /** A literal second rail reaching the final shaft completes the 4/4 bar before a 3/4 entry. */
    private static boolean restoreCommonTimeTerminalRail(Ink gray, Ink original, List<MeasureRegion> regions,
            int measure, List<Head> row, List<ScoreNoteEvent> result) {
        if (measure < 1 || row.size() != 16) return false;
        var bar = regions.get(measure);
        float gap = (bar.bottom() - bar.top()) * gray.height / 8.2f;
        float span = (bar.right() - bar.left()) * gray.width;
        if (gap < 10 || gap > 26 || span < 29 * gap || span > 47 * gap) return false;
        var heads = row.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var ending = heads.get(15);
        if (ending.note.beamCount() != 1 || ending.note.unbeamedDurationBeats() != 0) return false;
        var restored = ClosingTripletRhythm.beamedValue(ending.note, 2);
        var clef = ending.note.clefBottomDiatonic();
        int direction = ending.note.stemDirection();
        if (Math.abs(direction) != 1 || !ordinaryDoubleRail(restored, clef, direction, false)
                || heads.subList(0, 15).stream().anyMatch(
                        head -> !ordinaryDoubleRail(head.note, clef, direction, false))
                || !closesCommonTimeQuartets(gray, regions, measure, heads, gap)
                || !closesCommonTimeQuartets(original, regions, measure, heads, gap)) return false;
        result.set(ending.owner, restored);
        return true;
    }

    private static boolean closesCommonTimeQuartets(
            Ink ink, List<MeasureRegion> regions, int measure, List<Head> heads, float gap) {
        float first = heads.get(0).x, last = heads.get(15).x;
        var bar = regions.get(measure);
        if (!WrappedMeasureBoundaries.confirmsClosedTripletBar(ink.raw, ink.width, ink.height, bar, first, last)
                && !WrappedMeasureBoundaries.confirmsInitialStaffBar(
                        ink.raw, ink.width, ink.height, regions, measure, first, last)) return false;
        var budget = new PrintedBeamShafts.Budget(220_000);
        PrintedBeamShafts.Shaft previous = null;
        for (int index = 0; index < heads.size(); index++) {
            var head = heads.get(index);
            var read = PrintedBeamShafts.at(ink.raw, ink.width, ink.height, head.x, head.y, gap, budget);
            if (read.size() != 1 || budget.refused()) return false;
            var shaft = read.get(0);
            if (shaft.direction() != -head.note.stemDirection()
                    || previous != null && !WrappedEighthTriplets.matchesBeamRails(
                            ink.raw, ink.width, ink.height, previous, shaft, gap, index % 4 == 0 ? 0 : 2))
                return false;
            previous = shaft;
        }
        return true;
    }

    /** Three complete quartets close a 3/4 staff opening when the proposed marking has no ink. */
    private static boolean restoreClosedMonophonicThreeFour(
            Ink normalized,
            Ink original,
            List<MeasureRegion> regions,
            int measure,
            List<Head> row,
            List<ScoreNoteEvent> result) {
        if (original == null || measure < 1 || measure + 1 >= regions.size()) return false;
        var bar = regions.get(measure);
        float gap = (bar.bottom() - bar.top()) * normalized.height / 8.2f;
        float width = (bar.right() - bar.left()) * normalized.width;
        if (gap < 10 || gap > 26 || width < 30 * gap || width > 41 * gap) return false;
        var heads = row.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        int clef = heads.get(0).note.clefBottomDiatonic();
        int claims = 0;
        for (int i = 0; i < heads.size(); i++) {
            var head = heads.get(i);
            boolean claim = head.note.tupletDivisor() == 3;
            if (!ordinaryDoubleRail(head.note, clef, -1, claim)) return false;
            if (claim) claims |= 1 << i;
            if (i > 0) {
                float advance = head.x - heads.get(i - 1).x;
                if (advance < 1.8f * gap || advance > 3.65f * gap) return false;
            }
        }
        if (Integer.bitCount(claims) != 3 || !hasContainedTripletClaims(claims)) return false;
        int first = Integer.numberOfTrailingZeros(claims);
        if (!closedMonophonicQuartets(normalized, regions, measure, heads, gap, first)
                || !closedMonophonicQuartets(original, regions, measure, heads, gap, first))
            return false;
        for (int i = first; i < first + 3; i++) {
            var head = heads.get(i);
            result.set(head.owner, head.note.withTupletRatio(1, 1));
        }
        return true;
    }

    private static boolean closedMonophonicQuartets(
            Ink ink,
            List<MeasureRegion> regions,
            int measure,
            List<Head> heads,
            float gap,
            int first) {
        var bar = regions.get(measure);
        var rows = UnnumberedTripletInk.sourceRows(bar, regions, ink.height);
        var track =
                WrappedMeasureBoundaries.verifiedTripletStaff(ink.raw, ink.width, ink.height, bar);
        if (track == null
                || !WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(
                        ink.raw, ink.width, ink.height, bar, heads.get(11).x)) return false;
        var budget = new PrintedBeamShafts.Budget(280_000);
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(heads.size());
        for (var head : heads) {
            var read =
                    PrintedBeamShafts.at(
                            ink.raw, ink.width, ink.height, head.x, head.y, gap, budget);
            if (read.size() != 1 || read.get(0).direction() != 1 || budget.refused()) return false;
            shafts.add(read.get(0));
        }
        if (!WrappedMeasureBoundaries.confirmsShaftBoundedInitialStaffBar(
                ink.raw,
                ink.width,
                ink.height,
                regions,
                measure,
                heads.get(0).x,
                shafts.get(0).left())) return false;
        for (int i = 1; i < shafts.size(); i++) {
            boolean linked =
                    FingeringAnnotationFilter.pairedBeamRails(
                            ink.raw, ink.width, ink.height, shafts.get(i - 1), shafts.get(i), gap);
            if (linked != (i % 4 != 0)) return false;
        }
        float firstX = heads.get(first).x, lastX = heads.get(first + 2).x;
        float top = Float.POSITIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
        int tip = 0;
        for (int i = first; i < first + 3; i++) {
            top = Math.min(top, heads.get(i).y);
            bottom = Math.max(bottom, heads.get(i).y);
            tip = Math.max(tip, shafts.get(i).endY());
        }
        return !UnnumberedTripletInk.bracketBesideBeam(
                        ink.raw,
                        ink.width,
                        ink.height,
                        gap,
                        firstX,
                        lastX,
                        top,
                        bottom,
                        tip,
                        rows,
                        track,
                        true)
                && !centralTripletNumeral(ink, (firstX + lastX) / 2, top, tip, gap, rows);
    }

    private static boolean centralTripletNumeral(
            Ink ink,
            float center,
            float head,
            float tip,
            float gap,
            UnnumberedTripletInk.Rows rows) {
        int left = Math.max(0, Math.round(center - 1.50f * gap));
        int right = Math.min(ink.width - 1, Math.round(center + 1.50f * gap));
        int top = Math.max(rows.first(), Math.round(head - 4 * gap));
        int bottom = Math.min(rows.last(), Math.round(tip + 4 * gap));
        if (right <= left || bottom <= top) return true;
        var read =
                RawNumeralComponents.capture(
                        ink.raw,
                        ink.width,
                        ink.height,
                        left,
                        top,
                        right,
                        bottom,
                        () -> {},
                        () -> {});
        for (var glyph : read.components()) {
            if (glyph.left <= left
                    || glyph.right >= right
                    || glyph.top <= top
                    || glyph.bottom >= bottom
                    || Math.abs((glyph.left + glyph.right) * .5f - center) > .72f * gap
                    || glyph.size() < .18f * gap * gap
                    || glyph.size() > 1.15f * gap * gap) continue;
            int[] pixels = IntStream.range(0, glyph.size()).map(glyph::pixel).toArray();
            if (TupletNumeralBeamBand.detachedThree(
                    pixels, ink.width, glyph.left, glyph.top, glyph.right, glyph.bottom, gap))
                return true;
        }
        return false;
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

    private static boolean beamOriginsUnclaimed(ScoreBeamSource source, List<Head> heads) {
        var origins = source.noteOrigins();
        for (var head : heads)
            if (origins.get(head.owner).isPresent()) return false;
        return true;
    }

    /** Wide chromatic dyads can keep their exact 3/4 clock while losing the printed beam groups. */
    private static boolean confirmsStraightChromaticSixths(
            Ink ink, Ink original, List<MeasureRegion> regions, int measure,
            List<Head> source, List<ScoreNoteEvent> result) {
        var bar = regions.get(measure);
        float gap = (bar.bottom() - bar.top()) * ink.height / 8.2f;
        if (gap < 12 || gap > 24) return false;
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var pairs = new ArrayList<Dyad>(12);
        var columns = new ClosedFourDyadBeam.Head[12];
        boolean chromaticSpacing = false;
        int clef = heads.get(0).note.clefBottomDiatonic();
        for (int slot = 0; slot < columns.length; slot++) {
            var a = heads.get(slot * 2);
            var b = heads.get(slot * 2 + 1);
            var pair = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
            int interval = pair.top.note.staffStep() - pair.bottom.note.staffStep();
            if (interval < 4 || interval > 6 || pair.bottom.y - pair.top.y < gap * 2.20f
                    || !sameShaftDyadGeometry(a, b, gap)
                    || !ordinaryDoubleRail(a.note, clef, -1, false)
                    || !ordinaryDoubleRail(b.note, clef, -1, false)) return false;
            float x = (a.x + b.x) * .5f;
            if (slot > 0) {
                float advance = (x - columns[slot - 1].x()) / gap;
                if (advance < 1.65f || advance > 4.9f) return false;
                if (advance > 3.35f) {
                    if (!hasAccidental(pair)) return false;
                    chromaticSpacing = true;
                }
            }
            columns[slot] = new ClosedFourDyadBeam.Head(x, pair.top.y, pair.bottom.y);
            pairs.add(pair);
        }
        if (!chromaticSpacing || !closes(heads, result, 3f)) return false;
        var rows = UnnumberedTripletInk.sourceRows(bar, regions, ink.height);
        return printedFourRailGroups(
                        ink, regions, measure, pairs, columns, List.of(), gap, rows, -1, false, true)
                && printedFourRailGroups(
                        original, regions, measure, pairs, columns, List.of(), gap, rows, -1, false, true);
    }

    /** Verify all four printed double beams before clearing false tuplets or restoring unowned beams. */
    private static boolean closeAcrossPrintedBeams(
            Ink ink,
            Ink original,
            List<MeasureRegion> regions,
            int measure,
            List<Head> source,
            List<ScoreNoteEvent> result,
            boolean missingBeamOwners) {
        var region = regions.get(measure);
        float gap = (region.bottom() - region.top()) * ink.height / 8.2f;
        if (gap < 10 || gap > 26) return false;
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        int positions = source.size() / 2;
        boolean tripleMeter = positions == 12;
        var groups = new ClosedFourDyadBeam.Head[positions];
        var pairs = new ArrayList<Dyad>(positions);
        int clef = heads.get(0).note.clefBottomDiatonic();
        boolean wideSixths = Math.abs(heads.get(0).y - heads.get(1).y) >= gap * 2.20f;
        boolean compactOpening =
                !tripleMeter && !wideSixths
                        && measure > 0
                        && heads.get(0).note.tupletDivisor() == 3
                        && (region.top() - regions.get(measure - 1).bottom()) * ink.height > gap * 3;
        if (tripleMeter && (wideSixths || heads.get(0).note.tupletDivisor() != 3)) return false;
        boolean fingeredThirds = !tripleMeter && !wideSixths
                && opposedTripletFingerings(ink, heads, gap) && opposedTripletFingerings(original, heads, gap);
        int claimMask = 0, direction = 0, accidentalSpacedLink = -1, accidentalSpacedCut = -1;
        boolean wideChromatic = false, overreadThirdRail = false;
        int missedSecondRail = -1;
        for (int slot = 0; slot < groups.length; slot++) {
            var a = heads.get(slot * 2);
            var b = heads.get(slot * 2 + 1);
            var pair = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
            if (slot % 4 == 0) direction = a.note.stemDirection();
            boolean claim = a.note.tupletDivisor() == 3;
            boolean widePair = pair.bottom.y - pair.top.y >= gap * 2.20f;
            boolean thirdRail = tripleMeter && !claim && a.note.beamCount() == 3 && b.note.beamCount() == 3;
            boolean secondRail = positions == 16 && wideSixths && slot == 3 && claimMask == 7
                    && direction == -1 && !claim && hasAccidental(pair)
                    && a.note.beamCount() == 1 && b.note.beamCount() == 1;
            if (secondRail) missedSecondRail = slot;
            var first = thirdRail || secondRail ? asSharedBeam(a.note, 2) : a.note;
            var second = thirdRail || secondRail ? asSharedBeam(b.note, 2) : b.note;
            overreadThirdRail |= thirdRail;
            if (direction == 0
                    || widePair != wideSixths
                    || (compactOpening || tripleMeter || fingeredThirds)
                            && pair.top.note.staffStep() - pair.bottom.note.staffStep() != 2
                    || (!wideSixths && !fingeredThirds && claim && direction != -1)
                    || !sameShaftDyadGeometry(a, b, gap)
                    || !ordinaryDoubleRail(first, clef, direction, claim)
                    || !ordinaryDoubleRail(second, clef, direction, claim)) return false;
            float x = (a.x + b.x) * .5f;
            if (slot > 0) {
                float spacing = (x - groups[slot - 1].x()) / gap;
                float minimumSpacing = compactOpening ? 1.40f : wideSixths ? 1.55f : 1.65f;
                if (spacing < minimumSpacing) return false;
                if (tripleMeter && spacing > 3.35f) {
                    if (spacing > 4.9f || !hasAccidental(pair)) return false;
                    wideChromatic = true;
                } else if (spacing > (wideSixths || tripleMeter || fingeredThirds && slot % 4 == 0 ? 3.35f : 2.7f)) {
                    if (!wideSixths && !compactOpening || spacing > ACCIDENTAL_SPACED_LINK || !hasAccidental(pair))
                        return false;
                    if ((slot % 4 == 2 || compactOpening && slot % 4 == 3) && accidentalSpacedLink < 0) {
                        accidentalSpacedLink = slot - 1;
                    } else if (slot % 4 == 0 && accidentalSpacedCut < 0
                            && pairs.get(slot - 1).top.note.stemDirection() != direction) {
                        accidentalSpacedCut = slot - 1;
                    } else return false;
                }
            }
            groups[slot] = new ClosedFourDyadBeam.Head(x, pair.top.y, pair.bottom.y);
            pairs.add(pair);
            if (claim) claimMask |= 1 << slot;
        }
        if (overreadThirdRail && !wideChromatic) return false;
        if (claimMask == 0 && (!missingBeamOwners || !wideSixths)) return false;
        var firstClaims = new ArrayList<Integer>();
        for (int left = claimMask; left != 0; ) {
            int first = Integer.numberOfTrailingZeros(left);
            if (first + 2 >= positions || ((left >> first) & 7) != 7) return false;
            firstClaims.add(first);
            left &= ~(7 << first);
        }
        if (!wideSixths) {
            int first = firstClaims.get(0);
            if (firstClaims.size() != 1
                    || (fingeredThirds ? first != 1
                            : compactOpening || tripleMeter ? first != 0 : first / 4 == (first + 2) / 4))
                return false;
        }
        var rows = UnnumberedTripletInk.sourceRows(region, regions, ink.height);
        if (!printedFourRailGroups(
                        ink, regions, measure, pairs, groups, firstClaims, gap, rows, accidentalSpacedLink, compactOpening, wideChromatic)
                || !printedFourRailGroups(
                        original, regions, measure, pairs, groups, firstClaims, gap, rows, accidentalSpacedLink, compactOpening, wideChromatic))
            return false;
        if (missedSecondRail >= 0)
            for (int i = missedSecondRail * 2; i < missedSecondRail * 2 + 2; i++) {
                var head = heads.get(i);
                result.set(head.owner, asSharedBeam(head.note, 2));
            }
        if (overreadThirdRail)
            for (var head : heads)
                if (head.note.beamCount() == 3)
                    result.set(head.owner, asSharedBeam(head.note, 2));
        for (int first : firstClaims)
            for (int i = first * 2; i < (first + 3) * 2; i++) {
                var head = heads.get(i);
                result.set(head.owner, head.note.withTupletRatio(1, 1));
            }
        if (closes(heads, result, positions / 4f)) return true;
        for (var head : heads) result.set(head.owner, head.note);
        return false;
    }

    /** The three below an upstem quartet is a fingering when it shares the row of two fours. */
    private static boolean opposedTripletFingerings(Ink ink, List<Head> heads, float gap) {
        if (heads.get(0).note.tupletDivisor() != 1) return false;
        for (int i = 2; i < 8; i++)
            if (heads.get(i).note.stemDirection() != 1 || heads.get(i).note.tupletDivisor() != 3)
                return false;
        float glyphTop = Float.NaN;
        for (int i = 2; i < 8; i += 2) {
            var a = heads.get(i);
            var b = heads.get(i + 1);
            var lower = a.y > b.y ? a : b;
            int left = Math.round(lower.x - gap * .87f), right = Math.round(lower.x + gap * .87f);
            int top = Math.round(lower.y + gap * 1.68f), bottom = Math.round(lower.y + gap * 4.76f);
            if (left < 0 || top < 0 || right >= ink.width || bottom >= ink.height) return false;
            var components = RawNumeralComponents.capture(
                    ink.raw, ink.width, ink.height, left, top, right, bottom, 139, () -> {}, () -> {});
            RawNumeralComponents.Component digit = null;
            for (var glyph : components.components()) {
                int height = glyph.bottom - glyph.top + 1;
                if (height < gap * .78f || height > gap * 1.45f
                        || Math.abs((glyph.left + glyph.right) * .5f - lower.x) > gap * .35f
                        || glyph.left <= left || glyph.right >= right || glyph.top <= top || glyph.bottom >= bottom)
                    continue;
                if (digit != null) return false;
                digit = glyph;
            }
            if (digit == null) return false;
            if (Float.isNaN(glyphTop)) glyphTop = digit.top;
            if (Math.abs(digit.top - glyphTop) > gap * .28f) return false;
            if (i == 4) {
                if (!TripletRhythmDetector.isIsolatedThree(digit, ink.width)) return false;
            } else {
                var reading = JoinedDigitTemplates.inspect(ink.raw, ink.width, ink.height,
                        new java.util.BitSet(), digit.left, digit.top, digit.right, digit.bottom, () -> {});
                if (reading == null || reading.digit() != 4) return false;
            }
        }
        return true;
    }

    private record OctaveClock(
            MeasureRegion bar, UnnumberedTripletInk.Rows rows, int first, int cut) {}

    /** Four independently separated double beams disprove a tuplet straddling their central cut. */
    private static boolean closeOctavesAcrossTripletCut(
            Ink ink,
            Ink original,
            List<MeasureRegion> regions,
            int measure,
            List<Head> source,
            List<ScoreNoteEvent> result) {
        var bar = regions.get(measure);
        float gap = (bar.bottom() - bar.top()) * ink.height / 8.2f;
        if (gap < 12 || gap > 24) return false;
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var pairs = new ArrayList<Dyad>(16);
        int clef = heads.get(0).note.clefBottomDiatonic();
        int claims = 0, weakSteps = 0;
        for (int i = 0; i < 16; i++) {
            var a = heads.get(i * 2);
            var b = heads.get(i * 2 + 1);
            var pair = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
            boolean claim = a.note.tupletDivisor() == 3;
            int steps = pair.top.note.staffStep() - pair.bottom.note.staffStep();
            if (b.x - a.x > .21f * gap
                    || pair.bottom.y - pair.top.y < 3.15f * gap
                    || pair.bottom.y - pair.top.y > 3.85f * gap
                    || !ordinaryDoubleRail(a.note, clef, -1, claim)
                    || !ordinaryDoubleRail(b.note, clef, -1, claim)) return false;
            if (steps != 7 && (claim || Math.abs(steps - 7) != 1 || ++weakSteps > 1)) return false;
            if (i != 0) {
                float advance = pair.top.x - pairs.get(pairs.size() - 1).top.x;
                if (advance < 1.50f * gap || advance > 3.35f * gap) return false;
            }
            if (claim) claims |= 1 << i;
            pairs.add(pair);
        }
        int first = Integer.numberOfTrailingZeros(claims);
        if (first > 13 || claims != 7 << first || first / 4 == (first + 2) / 4) return false;
        int cut = first / 4 * 4 + 3;
        var clock =
                new OctaveClock(
                        bar, UnnumberedTripletInk.sourceRows(bar, regions, ink.height), first, cut);
        if (!verifiesOctaveBeamGroups(ink, pairs, gap, clock)
                || !verifiesOctaveBeamGroups(original, pairs, gap, clock)) return false;
        for (int i = first * 2; i < (first + 3) * 2; i++) {
            var head = heads.get(i);
            result.set(head.owner, head.note.withTupletRatio(1, 1));
        }
        if (closes(heads, result)) return true;
        for (var head : heads) result.set(head.owner, head.note);
        return false;
    }

    /**
     * A vertical fingering pair belongs to two heads of its chord. Keep a printed bracket and
     * require four closed double-beam quartets before correcting the overlapping triplet claim.
     */
    private static boolean closeFingeredOpeningOctaves(
            Ink ink, Ink original, List<MeasureRegion> regions, int measure,
            List<Head> source, List<ScoreNoteEvent> result) {
        var bar = regions.get(measure);
        float gap = (bar.bottom() - bar.top()) * ink.height / 8.2f;
        if (gap < 12 || gap > 24) return false;
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        for (int i = 0; i < heads.size(); i++) {
            var note = heads.get(i).note;
            boolean claimed = i >= 2 && i < 8;
            if (note.tupletDivisor() != (claimed ? 3 : 1)
                    || note.tupletNormalNotes() != (claimed ? 2 : 1)) return false;
        }
        var rows = UnnumberedTripletInk.sourceRows(bar, regions, ink.height);
        if (!openingFingeringPair(ink, heads, gap, rows)
                || !openingFingeringPair(original, heads, gap, rows)) return false;
        var verifiedNotes = new ArrayList<>(result);
        var straight = new ArrayList<Head>(heads);
        for (int i = 2; i < 8; i++) {
            var head = heads.get(i);
            var note = head.note.withTupletRatio(1, 1);
            verifiedNotes.set(head.owner, note);
            straight.set(i, new Head(head.owner, note, head.x, head.y));
        }
        if (!confirmsStraightOctaveBeams(ink, original, bar, straight, verifiedNotes)) return false;
        for (var head : straight.subList(2, 8)) result.set(head.owner, head.note);
        return true;
    }

    private static boolean openingFingeringPair(
            Ink ink, List<Head> heads, float gap, UnnumberedTripletInk.Rows rows) {
        var outer = heads.get(0).y > heads.get(1).y ? heads.get(0) : heads.get(1);
        var center = heads.get(4).y > heads.get(5).y ? heads.get(4) : heads.get(5);
        var first = heads.get(2).y > heads.get(3).y ? heads.get(2) : heads.get(3);
        var last = heads.get(6).y > heads.get(7).y ? heads.get(6) : heads.get(7);
        if (!stackedFingering(ink, outer, gap, rows, false)
                || !stackedFingering(ink, center, gap, rows, true))
            return false;
        var budget = new PrintedBeamShafts.Budget(60_000);
        var shafts = PrintedBeamShafts.at(ink.raw, ink.width, ink.height, first.x, first.y, gap, budget);
        if (shafts.size() != 1 || shafts.get(0).direction() != 1) return false;
        float highest = Math.min(heads.get(2).y, heads.get(3).y);
        highest = Math.min(highest, Math.min(heads.get(4).y, heads.get(5).y));
        highest = Math.min(highest, Math.min(heads.get(6).y, heads.get(7).y));
        float lowest = Math.max(first.y, Math.max(center.y, last.y));
        return !UnnumberedTripletInk.bracketBesideBeam(
                ink.raw, ink.width, ink.height, gap, first.x, last.x, highest, lowest,
                shafts.get(0).endY(), rows, null, true);
    }

    private static boolean stackedFingering(
            Ink ink, Head lower, float gap, UnnumberedTripletInk.Rows rows, boolean allowHairline) {
        int left = Math.round(lower.x - gap * .75f), right = Math.round(lower.x + gap * .75f);
        int top = Math.round(lower.y + gap * 2.9f), bottom = Math.min(rows.last() - 1, Math.round(lower.y + gap * 6.4f));
        if (left < 0 || right >= ink.width || top <= rows.first() || top >= bottom)
            return false;
        var glyphs = RawNumeralComponents.capture(
                ink.raw, ink.width, ink.height, left, top, right, bottom, () -> {}, () -> {});
        for (var glyph : glyphs.components()) {
            if (glyph.left <= left || glyph.right >= right || glyph.top <= top || glyph.bottom >= bottom
                    || Math.abs((glyph.left + glyph.right) * .5f - lower.x) > gap * .3f
                    || glyph.right - glyph.left > gap
                    || glyph.bottom - glyph.top < gap * .78f) continue;
            int height = glyph.bottom - glyph.top + 1;
            if (height < gap * 1.4f && TripletRhythmDetector.isIsolatedThree(glyph, ink.width)) {
                for (var digit : glyphs.components())
                    if (digit.top > glyph.bottom && digit.top - glyph.bottom < gap * .55f
                            && Math.abs(glyph.left + glyph.right - digit.left - digit.right) < gap * .5f
                            && digit.bottom < bottom && digit.bottom - digit.top >= gap * .84f
                            && TripletRhythmDetector.fingerOne(ink.raw, ink.width, digit)) return true;
            }
            if (!allowHairline || height < gap * 1.9f || height > gap * 2.65f) continue;
            // A hairline can join tightly stacked digits in scans. Below the thin join, the
            // independent lower numeral must still have the original narrow vertical spine.
            for (int seam = glyph.top + Math.round(height * .43f);
                    seam <= glyph.top + Math.round(height * .56f); seam++) {
                int count = 0;
                for (int x = glyph.left; x <= glyph.right; x++)
                    if (ink.dark(x, seam)) count++;
                if (count > 1) continue;
                var upper = RawNumeralComponents.capture(
                        ink.raw, ink.width, ink.height, left, glyph.top, right, seam - 1, () -> {}, () -> {});
                var lowerDigits = RawNumeralComponents.capture(
                        ink.raw, ink.width, ink.height, left, seam + 1, right, glyph.bottom, () -> {}, () -> {});
                if (upper.components().size() == 1 && lowerDigits.components().size() == 1
                        && detachedFingeringUpper(upper.components().get(0), gap)
                        && TripletRhythmDetector.fingerOne(ink.raw, ink.width, lowerDigits.components().get(0)))
                    return true;
            }
        }
        return false;
    }

    private static boolean detachedFingeringUpper(RawNumeralComponents.Component glyph, float gap) {
        int width = glyph.right - glyph.left + 1, height = glyph.bottom - glyph.top + 1;
        return height >= gap * .88f && height <= gap * 1.22f
                && width >= .45f * height && width <= .84f * height
                && glyph.size() >= width * height * .29f && glyph.size() <= width * height * .66f;
    }

    private static boolean hasAccidental(Dyad pair) {
        return pair.top.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                || pair.bottom.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
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

    /**
     * Recover beam columns for an already exact bar only when the print separates every quartet.
     */
    /** Capture literal four-column double beams on already timed thirds. */
    private static List<Head> straightThirdBeamOwners(
            Ink ink, Ink original, MeasureRegion bar, List<Head> source) {
        float hintGap = (bar.bottom() - bar.top()) * ink.height / 8.2f;
        if (hintGap < 12 || hintGap > 24) return List.of();
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        int columns = heads.size() / 2;
        int beamed = columns == 13 ? 12 : columns;
        int clef = heads.get(0).note.clefBottomDiatonic();
        var orderedNotes = heads.stream().map(Head::note).toList();
        var pairs = new ArrayList<Dyad>(beamed);
        for (int i = 0; i < columns; i++) {
            var a = heads.get(i * 2);
            var b = heads.get(i * 2 + 1);
            var pair = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
            boolean rail = i < beamed;
            int direction = a.note.stemDirection();
            if (b.x - a.x > hintGap * .19f || direction == 0 || b.note.stemDirection() != direction
                    || pair.top.note.staffStep() - pair.bottom.note.staffStep() != 2
                    || pair.bottom.y - pair.top.y < hintGap * .83f
                    || pair.bottom.y - pair.top.y > hintGap * 1.22f) return List.of();
            if (i > 0 && i < beamed) {
                float advance = pair.top.x - pairs.get(pairs.size() - 1).top.x;
                if (advance < hintGap * 1.56f || advance > hintGap * 4.1f) return List.of();
            }
            for (var h : List.of(a, b)) {
                var n = h.note;
                if (!ordinaryGeometry(n, clef) || n.tupletDivisor() != 1 || n.tupletNormalNotes() != 1
                        || n.augmentationDots() != 0 || n.beamCount() != (rail ? 2 : 0)
                        || n.unbeamedDurationBeats() != (rail ? 0 : 1)) return List.of();
                var timing = ScoreNoteTiming.writtenPlacement(n, orderedNotes, 4).orElse(null);
                if (timing == null || Math.abs(timing.onsetBeats() - i * .25) > 1e-6
                        || Math.abs(timing.durationBeats() - (rail ? .25 : 1)) > 1e-6) return List.of();
            }
            if (rail) pairs.add(pair);
        }
        if (!literalThirdBeamRuns(ink, bar, pairs, hintGap)
                || !literalThirdBeamRuns(original, bar, pairs, hintGap)) return List.of();
        return heads.subList(0, beamed * 2);
    }

    private static boolean literalThirdBeamRuns(
            Ink ink, MeasureRegion bar, List<Dyad> pairs, float hintGap) {
        float left = bar.left() * ink.width, right = bar.right() * ink.width;
        float bottom = bar.bottom() * ink.height - 2.1f * hintGap;
        float[] staff = null;
        for (float at : new float[] {.5f, .75f, .25f}) {
            float x = left + (right - left) * at;
            staff = PrintedDirectionStaff.printedRules(ink.raw, ink.width, ink.height, x, bottom, hintGap);
            if (staff != null) break;
        }
        if (staff == null) {
            var first = localRules(ink, left + (right - left) * .34f, bottom, hintGap);
            var second = localRules(ink, left + (right - left) * .63f, bottom, hintGap);
            if (first == null || second == null || Math.abs(first[0] - second[0]) > .16f * hintGap
                    || Math.abs(first[1] - second[1]) > .075f * hintGap) return false;
            staff = new float[] {(first[0] + second[0]) * .5f, (first[1] + second[1]) * .5f};
        }
        float gap = staff[1];
        var work = new PrintedBeamShafts.Budget(400_000);
        PrintedBeamShafts.Shaft last = null;
        for (int i = 0; i < pairs.size(); i++) {
            var stem = straightThirdShaft(ink, pairs.get(i), gap, work);
            if (stem == null) return false;
            if (i > 0 && i % 4 == 0 && !literalThirdBeamCut(ink, last, stem, staff)) return false;
            if (i % 4 != 0 && (last.direction() != stem.direction()
                    || !WrappedEighthTriplets.matchesBeamRails(
                            ink.raw, ink.width, ink.height, last, stem, gap, 2))) return false;
            last = stem;
        }
        return true;
    }

    private static PrintedBeamShafts.Shaft straightThirdShaft(
            Ink ink, Dyad pair, float gap, PrintedBeamShafts.Budget work) {
        int direction = -pair.top.note.stemDirection();
        var outer = direction > 0 ? pair.top : pair.bottom;
        var inner = direction > 0 ? pair.bottom : pair.top;
        var stems = PrintedBeamShafts.at(ink.raw, ink.width, ink.height, outer.x, outer.y, gap, work)
                .stream().filter(s -> s.direction() == direction).toList();
        if (stems.size() != 1) return null;
        var shaft = stems.get(0);
        var peers = PrintedBeamShafts.at(ink.raw, ink.width, ink.height, inner.x, inner.y, gap, work)
                .stream().filter(s -> s.direction() == direction).toList();
        if (peers.size() == 1) {
            var mate = peers.get(0);
            return Math.abs(shaft.left() - mate.left()) <= 2
                    && Math.abs(shaft.endY() - mate.endY()) <= 2 ? shaft : null;
        }
        if (!peers.isEmpty() || !FilledSparseBeamHead.completeInterior(
                ink.raw, ink.width, ink.height, inner.x, inner.y, gap)) return null;
        for (var column : shaft.columns()) {
            float side = (inner.x - column.x()) * direction;
            if (side < .43f * gap || side > .79f * gap
                    || (column.endY() - inner.y) * direction < 2.72f * gap) continue;
            boolean connected = true;
            for (int j = -2; j <= 6; j++)
                connected &= ink.dark(column.x(), Math.round(inner.y) + direction * j);
            if (connected) return shaft;
        }
        return null;
    }

    private static boolean literalThirdBeamCut(
            Ink ink, PrintedBeamShafts.Shaft left, PrintedBeamShafts.Shaft right, float[] staff) {
        float gap = staff[1];
        if (left.direction() == right.direction() && WrappedEighthTriplets.matchesBeamRails(
                ink.raw, ink.width, ink.height, left, right, gap, 1)) return false;
        int from = left.right() + Math.round(gap * .49f);
        int to = right.left() - Math.round(gap * .49f);
        int clear = 0;
        for (int x = from; x <= to; x++) {
            if (clearThirdBeamCap(ink, left, staff, x) && clearThirdBeamCap(ink, right, staff, x)) clear++;
        }
        return clear >= Math.ceil(gap * .32f);
    }

    private static boolean clearThirdBeamCap(
            Ink ink, PrintedBeamShafts.Shaft stem, float[] staff, int x) {
        float gap = staff[1];
        int paper = 0, available = 0;
        int size = Math.round(1.25f * gap);
        for (int c = -2; c <= size; c++) {
            int y = stem.endY() - c * stem.direction();
            if (y < 0 || y >= ink.height) return false;
            float rule = (staff[0] - y) / gap;
            if (rule >= -.19f && rule <= 4.19f && Math.abs(rule - Math.round(rule)) <= .19f)
                continue;
            available++;
            if ((ink.raw[y * ink.width + x] & 255) >= 205) paper++;
        }
        return available >= gap * .62f && paper >= available * .92f;
    }

    private static boolean confirmsStraightOctaveBeams(
            Ink ink,
            Ink original,
            MeasureRegion bar,
            List<Head> source,
            List<ScoreNoteEvent> notes) {
        float gap = (bar.bottom() - bar.top()) * ink.height / 8.2f;
        if (!Float.isFinite(gap) || gap < 12 || gap > 24) return false;
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        int clef = heads.get(0).note.clefBottomDiatonic();
        var pairs = new ArrayList<Dyad>(16);
        for (int slot = 0; slot < 16; slot++) {
            var a = heads.get(slot * 2);
            var b = heads.get(slot * 2 + 1);
            var pair = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
            if (Math.abs(a.note.staffStep() - b.note.staffStep()) != 7
                    || b.x - a.x > gap * .21f
                    || pair.bottom.y - pair.top.y < gap * 3.15f
                    || pair.bottom.y - pair.top.y > gap * 3.85f
                    || !ordinaryDoubleRail(a.note, clef, -1, false)
                    || !ordinaryDoubleRail(b.note, clef, -1, false)) return false;
            if (slot != 0) {
                float advance = pair.top.x - pairs.get(slot - 1).top.x;
                if (advance < gap * 1.50f || advance > gap * 3.35f) return false;
            }
            pairs.add(pair);
        }
        return closes(heads, notes)
                && verifiesOctaveBeamGroups(ink, pairs, gap)
                && verifiesOctaveBeamGroups(original, pairs, gap);
    }

    private static boolean verifiesOctaveBeamGroups(Ink ink, List<Dyad> pairs, float gap) {
        return verifiesOctaveBeamGroups(ink, pairs, gap, null);
    }

    private static boolean verifiesOctaveBeamGroups(
            Ink ink, List<Dyad> pairs, float gap, OctaveClock clock) {
        var samples = new ArrayList<float[]>();
        var axes = new ArrayList<List<Shaft>>();
        var attached = new ArrayList<PrintedBeamShafts.Shaft>();
        var budget = new PrintedBeamShafts.Budget(300_000);
        for (var pair : pairs) {
            var upper = pair.top;
            var sample =
                    localRules(ink, upper.x, upper.y + upper.note.staffStep() * gap * .5f, gap);
            if (sample != null) samples.add(new float[] {upper.x, sample[0], sample[1]});
            var shafts = shafts(ink, pair, gap, -1);
            if (shafts.isEmpty()) return false;
            axes.add(shafts);
            var lower = pair.bottom;
            var witnessed =
                    PrintedBeamShafts.at(
                            ink.raw, ink.width, ink.height, lower.x, lower.y, gap, budget);
            attached.add(
                    witnessed.size() == 1 && witnessed.get(0).direction() == 1
                            ? witnessed.get(0)
                            : null);
        }
        if (samples.size() < (clock == null ? 8 : 7)) return false;
        int sampleCount = Math.min(13, samples.size());
        var anchors = new ArrayList<float[]>(sampleCount);
        for (int i = 0; i < sampleCount; i++) {
            anchors.add(samples.get(i * (samples.size() - 1) / (sampleCount - 1)));
        }
        var staff = StaffPitchTrack.fromVerifiedSamples(anchors);
        if (staff == null
                || !outsideOctaveBeam(ink, axes.get(0), -1, gap, staff)
                || !outsideOctaveBeam(ink, axes.get(15), 1, gap, staff)) return false;
        if (clock != null
                && (!WrappedMeasureBoundaries.confirmsClosedWithVerifiedStaff(
                                ink.raw,
                                ink.width,
                                ink.height,
                                clock.bar,
                                pairs.get(0).top.x,
                                pairs.get(pairs.size() - 1).top.x,
                                staff)
                        || octaveNumeralOrBracket(ink, pairs, axes, gap, clock, staff)))
            return false;
        var strict = new ArrayList<List<Rails>>(15);
        var joins = new boolean[15];
        for (int i = 0; i < joins.length; i++) {
            var a = axes.get(i);
            var b = axes.get(i + 1);
            var ribbon = beams(ink, a, b, pairs.get(i), pairs.get(i + 1), gap, -1, staff);
            strict.add(ribbon);
            var left = attached.get(i);
            var right = attached.get(i + 1);
            joins[i] =
                    !ribbon.isEmpty()
                            || (left != null
                                    && right != null
                                    && WrappedEighthTriplets.matchesBeamRails(
                                            ink.raw, ink.width, ink.height, left, right, gap, 2));
            if (i % 4 == 3) {
                if (joins[i]) return false;
                if (!clearTwoRailCut(ink, a, b, staff, gap, -1)
                        && (clock == null
                                || i != clock.cut
                                || !outsideOctaveBeam(ink, a, 1, gap, staff)
                                || !outsideOctaveBeam(ink, b, -1, gap, staff)
                                || !clearBreak(
                                        ink, a, b, pairs.get(i), pairs.get(i + 1), gap, staff)))
                    return false;
            }
        }
        for (int i = 0; i < joins.length; i++) {
            if (i % 4 == 3 || joins[i]) continue;
            if (i % 4 != 1
                    || !joins[i - 1]
                    || !joins[i + 1]
                    || strict.get(i - 1).isEmpty()
                    || !continuedOctaveRailContrast(
                            ink, strict.get(i - 1).get(0), axes.get(i + 1), gap)) return false;
        }
        return true;
    }

    private static boolean octaveNumeralOrBracket(
            Ink ink,
            List<Dyad> pairs,
            List<List<Shaft>> axes,
            float gap,
            OctaveClock clock,
            StaffPitchTrack staff) {
        float high = Float.POSITIVE_INFINITY, low = Float.NEGATIVE_INFINITY, tip = low;
        for (int i = clock.first; i < clock.first + 3; i++) {
            var pair = pairs.get(i);
            high = Math.min(high, pair.top.y);
            low = Math.max(low, pair.bottom.y);
            tip = Math.max(tip, axes.get(i).stream().mapToInt(Shaft::tip).max().orElseThrow());
        }
        return UnnumberedTripletInk.bracketBesideBeam(
                ink.raw,
                ink.width,
                ink.height,
                gap,
                pairs.get(clock.first).top.x,
                pairs.get(clock.first + 2).top.x,
                high,
                low,
                tip,
                clock.rows,
                staff,
                true);
    }

    private static boolean outsideOctaveBeam(
            Ink ink, List<Shaft> axes, int direction, float gap, StaffPitchTrack staff) {
        int edge =
                axes.stream().mapToInt(shaft -> shaft.x * direction).max().orElseThrow()
                        * direction;
        int tip = axes.stream().mapToInt(Shaft::tip).max().orElseThrow();
        int top = tip - Math.round(gap * 1.45f), bottom = tip + Math.round(gap * .32f);
        int first = Math.round(gap * .32f), last = Math.round(gap * .88f);
        if (edge - last < 0 || edge + last >= ink.width || top < 0 || bottom >= ink.height)
            return false;
        for (int step = first; step <= last; step++) {
            int x = edge + step * direction;
            var frame = staff.at(x);
            int open = 0, offRule = 0, darkRun = 0;
            for (int y = top; y <= bottom; y++) {
                darkRun = ink.dark(x, y) ? darkRun + 1 : 0;
                if (darkRun >= Math.round(gap * .30f)) return false;
                float line = (frame[0] - y) / frame[1];
                if (line >= -.21f && line <= 4.21f && Math.abs(line - Math.round(line)) <= .21f)
                    continue;
                offRule++;
                if (!ink.dark(x, y)) open++;
            }
            if (offRule < gap * .90f || open < offRule * .90f) return false;
        }
        return true;
    }

    /**
     * An antialiased trough between two dark ribbons can still separate them when the ordinary
     * paper threshold misses it. The preceding intact span sets the separation and slope bounds.
     */
    private static boolean continuedOctaveRailContrast(
            Ink ink, Rails before, List<Shaft> right, float gap) {
        float priorSlope = (before.y1 - before.y0) / (float) (before.x1 - before.x0);
        if (priorSlope > -.09f || priorSlope < -.35f) return false;
        int margin = Math.round(gap * .35f);
        for (var end : right) {
            int span = end.x - before.x1;
            if (span < gap * 1.60f
                    || span > gap * 2.9f
                    || before.x1 + margin < 0
                    || end.x - margin >= ink.width) continue;
            int first = before.x1 + margin;
            int last = end.x - margin;
            int tolerance = Math.round(gap * .16f);
            for (int shift = -tolerance; shift <= tolerance; shift++) {
                int y0 = before.y1 + shift;
                for (int endDepth = Math.round(gap * .12f);
                        endDepth <= Math.round(gap * .39f);
                        endDepth++) {
                    int y1 = end.tip - endDepth;
                    float slope = (y1 - y0) / (float) span;
                    if (Math.abs(slope - priorSlope) > .12f) continue;
                    for (int d = before.separation - tolerance;
                            d <= before.separation + tolerance;
                            d++) {
                        if (d < gap * .46f || d > gap * .86f) continue;
                        var rails = new Rails(before.x1, y0, end.x, y1, d);
                        if (slopedTrough(ink, rails, first, last)) return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean slopedTrough(Ink ink, Rails rails, int first, int last) {
        int dark = 0, contrast = 0;
        for (int x = first; x <= last; x++) {
            int bottom = rails.at(x), top = bottom - rails.separation;
            if (top - 1 < 0 || bottom + 1 >= ink.height) return false;
            int a = darkest(ink, x, top), b = darkest(ink, x, bottom);
            if (Math.max(a, b) > 75) continue;
            dark++;
            int center = bottom - rails.separation / 2;
            int trough =
                    Math.max(
                            gray(ink, x, center),
                            Math.max(gray(ink, x, center - 1), gray(ink, x, center + 1)));
            if (trough >= Math.max(a, b) + 48) contrast++;
        }
        int columns = last - first + 1;
        return dark >= columns * .95f && contrast >= columns * .67f;
    }

    private static int darkest(Ink ink, int x, int y) {
        return Math.min(gray(ink, x, y), Math.min(gray(ink, x, y - 1), gray(ink, x, y + 1)));
    }

    private static int gray(Ink ink, int x, int y) {
        return ink.raw[y * ink.width + x] & 255;
    }

    private static boolean printedFourRailGroups(
            Ink ink,
            List<MeasureRegion> regions,
            int measure,
            List<Dyad> pairs,
            ClosedFourDyadBeam.Head[] columns,
            List<Integer> firstClaims,
            float gap,
            UnnumberedTripletInk.Rows rows,
            int accidentalSpacedLink,
            boolean compactOpening, boolean wideChromatic) {
        var bar = regions.get(measure);
        var samples = new ArrayList<float[]>();
        var stems = new ArrayList<List<Shaft>>();
        boolean wideDownstemDyads = pairs.stream().allMatch(dyad ->
                dyad.top.note.stemDirection() == -1 && dyad.bottom.y - dyad.top.y >= gap * 2.2f);
        var verifiedShafts = new ArrayList<PrintedBeamShafts.Shaft>();
        var shaftBudget = new PrintedBeamShafts.Budget(300_000);
        for (var dyad : pairs) {
            var head = dyad.top;
            float estimatedBottom = head.y + head.note.staffStep() * gap * .5f;
            var sample = localRules(ink, head.x, estimatedBottom, gap);
            if (sample != null) samples.add(new float[] {head.x, sample[0], sample[1]});
            else if (wideDownstemDyads) {
                var lower = dyad.bottom;
                var lowerRules = localRules(ink, lower.x, lower.y + lower.note.staffStep() * gap * .5f, gap);
                if (lowerRules != null) samples.add(new float[] {lower.x, lowerRules[0], lowerRules[1]});
            }
            var candidates = shafts(ink, dyad, gap, head.note.stemDirection());
            if (candidates.isEmpty()) return false;
            stems.add(candidates);
            if (wideChromatic) {
                var shaft = sharedOpticalShaft(ink, dyad, gap, shaftBudget);
                if (shaft == null) return false;
                verifiedShafts.add(shaft);
            }
        }
        StaffPitchTrack tripleTrack = columns.length == 12 && samples.size() < 8
                ? WrappedMeasureBoundaries.verifiedTripletStaff(ink.raw, ink.width, ink.height, bar, true)
                : null;
        if (wideDownstemDyads && tripleTrack == null && samples.size() >= 6 && samples.size() < 8) {
            float floor = bar.bottom() * ink.height - gap * 2.1f;
            for (float probe : new float[] {
                    bar.left() * ink.width + .4f * gap, bar.right() * ink.width - .4f * gap}) {
                var rules = localRules(ink, probe, floor, gap);
                if (rules != null && Math.abs(rules[0] - floor) <= gap * .50f)
                    samples.add(new float[] {probe, rules[0], rules[1]});
            }
            samples.sort(Comparator.comparingDouble(sample -> sample[0]));
        }
        if (samples.size() < 8 && tripleTrack == null) {
            if ((!compactOpening && columns.length != 12) || samples.size() < 2) return false;
            float floor = bar.bottom() * ink.height - 2.1f * gap;
            float left = (bar.left() * ink.width + columns[0].x()) * .5f;
            float right = (bar.right() * ink.width + columns[columns.length - 1].x()) * .5f;
            // Dense chords can cover the lines throughout the sounded part of the measure.
            // Sample the clear pockets on both sides before extending the measured staff.
            for (float probe : new float[]{left,right}) {
                var rules = localRules(ink, probe, floor, gap);
                if (rules == null || Math.abs(rules[0] - floor) > gap * .65f) return false;
                samples.add(new float[]{probe,rules[0],rules[1]});
            }
            samples.sort(Comparator.comparingDouble(sample -> sample[0]));
        }
        if (wideDownstemDyads && tripleTrack == null) {
            float opening = bar.left() * ink.width + gap * .40f;
            if (samples.get(0)[0] > opening + gap * 3) {
                float floor = bar.bottom() * ink.height - gap * 2.1f;
                var rules = localRules(ink, opening, floor, gap);
                if (rules != null && Math.abs(rules[0] - floor) <= gap * .50f)
                    samples.add(0, new float[] {opening, rules[0], rules[1]});
            }
        }
        var track = tripleTrack;
        if (track == null) {
            int sampleCount = Math.min(13, samples.size());
            var anchors = new ArrayList<float[]>(sampleCount);
            for (int i = 0; i < sampleCount; i++)
                anchors.add(samples.get(i * (samples.size() - 1) / (sampleCount - 1)));
            track = StaffPitchTrack.fromVerifiedSamples(anchors);
        }
        if (track == null) return false;
        boolean closed =
                WrappedMeasureBoundaries.confirmsClosedWithVerifiedStaff(
                        ink.raw,
                        ink.width,
                        ink.height,
                        bar,
                        columns[0].x(),
                        columns[columns.length - 1].x(),
                        track);
        var end = pairs.get(pairs.size() - 1);
        if (!closed
                && end.top.note.stemDirection() == -1
                && end.bottom.y - end.top.y >= gap * 2.20f) {
            float lastShaft = stems.get(stems.size() - 1).stream().mapToInt(Shaft::x).max().orElseThrow();
            closed =
                    WrappedMeasureBoundaries.confirmsPackedRightEdgeFromStem(
                            ink.raw,
                            ink.width,
                            ink.height,
                            bar,
                            columns[0].x(),
                            columns[columns.length - 1].x(),
                            lastShaft,
                            track);
        }
        if (!closed && columns.length == 12) {
            float first = columns[0].x(), last = columns[columns.length - 1].x();
            closed = WrappedMeasureBoundaries.confirmsClosedTripletBar(
                    ink.raw, ink.width, ink.height, bar, first, last)
                    || WrappedMeasureBoundaries.confirmsNeighborBoundedTripletBar(
                            ink.raw, ink.width, ink.height, regions, measure, first, last);
        }
        boolean openingWithMeter = !closed
                && WrappedMeasureBoundaries.confirmsOpeningFourFourWithVerifiedStaff(
                        ink.raw, ink.width, ink.height, regions, measure,
                        columns[0].x(), columns[columns.length - 1].x(), track);
        if (!closed && !openingWithMeter) {
            if (!compactOpening) return false;
            int firstShaft = stems.get(0).stream().mapToInt(Shaft::x).min().orElseThrow();
            if (!WrappedMeasureBoundaries.confirmsCompleteShaftBoundedInitialStaffBar(
                    ink.raw, ink.width, ink.height, regions, measure,
                    columns[0].x(), columns[columns.length - 1].x(), firstShaft, track)) return false;
        }
        for (int first : firstClaims)
            if (claimedBracket(ink, columns, first, gap, rows, track,
                    openingWithMeter || wideChromatic || wideDownstemDyads,
                    wideDownstemDyads ? stems : null)) return false;
        int verifiedWholeGroup = -1;
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
            } else {
                float maximumSpan = wideChromatic ? 4.9f : i == accidentalSpacedLink ? ACCIDENTAL_SPACED_LINK : 3.2f;
                boolean joined =
                        !beams(ink, stems.get(i), stems.get(i + 1), a, b, gap, direction, track, maximumSpan)
                                .isEmpty();
                if (!joined && wideChromatic)
                    joined = exactShaftDoubleRails(
                            ink, verifiedShafts.get(i), verifiedShafts.get(i + 1), track, gap);
                if (!joined && wideDownstemDyads && i != accidentalSpacedLink) {
                    // Measure both chord heads before following a candidate stem past the rails.
                    var budget = new PrintedBeamShafts.Budget(100_000);
                    var left = sharedOpticalShaft(ink, a, gap, budget);
                    var right = sharedOpticalShaft(ink, b, gap, budget);
                    joined = left != null && right != null && exactShaftDoubleRails(ink, left, right, track, gap);
                }
                if (!joined
                        && !(i % 4 == 1
                                && direction == -1
                                && obscuredMiddleRailSeam(ink, stems, pairs, i, gap, track))) {
                    // The widened accidental link needs its own exact two-rail proof.
                    if (wideChromatic || i == accidentalSpacedLink) return false;
                    int begin = i - i % 4;
                    if (verifiedWholeGroup == begin) continue;
                    var group = Arrays.copyOfRange(columns, begin, begin + 4);
                    var staff = track.at(group[1].x());
                    // Keep ordinary link rejection unless verified staff ink is needed.
                    if (ClosedFourDyadBeam.proves(ink.raw, ink.width, ink.height, group, staff[1], -direction)
                            || !ClosedFourDyadBeam.provesAcrossDistinctStaffRules(
                            ink.raw, ink.width, ink.height, group, staff[1], staff[0], -direction))
                        return false;
                    verifiedWholeGroup = begin;
                }
            }
        }
        return true;
    }

    private static PrintedBeamShafts.Shaft sharedOpticalShaft(
            Ink ink, Dyad pair, float gap, PrintedBeamShafts.Budget budget) {
        var upper = PrintedBeamShafts.at(ink.raw, ink.width, ink.height, pair.top.x, pair.top.y, gap, budget);
        var lower = PrintedBeamShafts.at(ink.raw, ink.width, ink.height, pair.bottom.x, pair.bottom.y, gap, budget);
        if (budget.refused() || upper.size() != 1 || lower.size() != 1) return null;
        var a = upper.get(0);
        var b = lower.get(0);
        if (a.direction() != -pair.top.note.stemDirection() || a.direction() != b.direction()
                || Math.max(a.left(), b.left()) > Math.min(a.right(), b.right())
                || Math.abs(a.endY() - b.endY()) > gap * .14f) return null;
        return a.direction() > 0 ? b : a;
    }

    /** Fit two rails to the shared shafts while excluding independently verified staff rules. */
    private static boolean exactShaftDoubleRails(
            Ink ink, PrintedBeamShafts.Shaft left, PrintedBeamShafts.Shaft right,
            StaffPitchTrack track, float gap) {
        return WrappedEighthTriplets.matchesExactlyTwoBeamRails(
                ink.raw, ink.width, ink.height, left, right, gap, track);
    }

    /** Adjacent spans can establish a sloping double beam where a staff rule obscures its seam. */
    private static boolean obscuredMiddleRailSeam(
            Ink ink, List<List<Shaft>> stems, List<Dyad> dyads, int i, float gap, StaffPitchTrack staff) {
        for (int n = i - 1; n <= i + 2; n++) {
            var pair = dyads.get(n);
            if (pair.bottom.y - pair.top.y < gap * 2.2f) return false;
        }
        var left = beams(ink, stems.get(i - 1), stems.get(i), dyads.get(i - 1), dyads.get(i), gap, -1, staff);
        var right = beams(ink, stems.get(i + 1), stems.get(i + 2), dyads.get(i + 1), dyads.get(i + 2), gap, -1, staff);
        if (left.isEmpty() || right.isEmpty()) return false;
        if (confirmedMiddleRailSeam(ink, left.get(0), right.get(0), gap, staff)) return true;

        // A staff line can supply a plausible axis on one side while the beam lies beside it.
        left = beams(ink, stems.get(i - 1), stems.get(i), dyads.get(i - 1), dyads.get(i), gap, -1, staff, 3.2f, true);
        right = beams(ink, stems.get(i + 1), stems.get(i + 2), dyads.get(i + 1), dyads.get(i + 2), gap, -1, staff, 3.2f, true);
        for (var before : left)
            for (var after : right)
                if (confirmedMiddleRailSeam(ink, before, after, gap, staff)) return true;
        return false;
    }

    private static boolean confirmedMiddleRailSeam(
            Ink ink, Rails before, Rails after, float gap, StaffPitchTrack staff) {
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
                if (isStaffRule(frame, y, .22f) && stroke(ink, x, y, 1) >= 2)
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
            boolean clear = offRule > gap && white >= .9f * offRule
                    || direction == -1 && offRule >= .75f * gap && white == offRule;
            columns = clear ? columns + 1 : 0;
            if (columns >= Math.max(3, Math.round(gap * .26f))) return true;
        }
        return false;
    }

    /** Recover one visually joined upper shaft above a complete bar of double-rail octave dyads. */
    /** The two aligned parts of a clipped downshaft can belong to one terminal octave. */
    private static List<Head> terminalOctaveShaftQuartet(
            Ink ink, Ink original, MeasureRegion bar, List<Head> source) {
        float gap = (bar.bottom() - bar.top()) * ink.height / 8.2f;
        if (gap < 12 || gap > 24) return List.of();
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        List<Head> match = List.of();
        for (int i = 6; i + 2 < heads.size(); i++) {
            var first = heads.get(i);
            var second = heads.get(i + 1);
            var terminal = first.y < second.y ? new Dyad(first, second) : new Dyad(second, first);
            var upper = terminal.top.note;
            int clef = upper.clefBottomDiatonic();
            if (!ordinary(upper, clef) || upper.beamCount() != 0 || upper.unbeamedDurationBeats() != 1
                    || upper.augmentationDots() != 0 || upper.tupletNormalNotes() != 1
                    || !ordinaryDoubleRail(terminal.bottom.note, clef, -1, false)
                    || !octaveShaftPair(terminal, gap)
                    || heads.get(i - 1).x > first.x - gap * 1.5f
                    || heads.get(i + 2).x < second.x + gap * 1.5f) continue;
            var group = new ArrayList<Dyad>(4);
            for (int slot = i - 6; slot < i; slot += 2) {
                var a = heads.get(slot);
                var b = heads.get(slot + 1);
                var pair = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
                if (!octaveShaftPair(pair, gap)
                        || !ordinaryDoubleRail(pair.top.note, clef, -1, false)
                        || !ordinaryDoubleRail(pair.bottom.note, clef, -1, false)) break;
                group.add(pair);
            }
            if (group.size() != 3) continue;
            group.add(terminal);
            if (!clippedOctaveQuartet(ink, group, gap) || !clippedOctaveQuartet(original, group, gap))
                continue;
            if (!match.isEmpty()) return List.of();
            match = heads.subList(i - 6, i + 2);
        }
        return match;
    }

    private static boolean octaveShaftPair(Dyad pair, float gap) {
        return Math.abs(pair.top.x - pair.bottom.x) <= gap * .13f
                && pair.top.note.staffStep() - pair.bottom.note.staffStep() == 7
                && pair.bottom.y - pair.top.y >= gap * 3.15f
                && pair.bottom.y - pair.top.y <= gap * 3.85f
                && pair.top.note.octaveShift() == pair.bottom.note.octaveShift();
    }

    private static boolean clippedOctaveQuartet(Ink ink, List<Dyad> group, float gap) {
        var samples = new ArrayList<float[]>();
        var axes = new ArrayList<List<Shaft>>();
        for (int i = 0; i < group.size(); i++) {
            var pair = group.get(i);
            var lower = pair.bottom;
            var sample = localRules(ink, lower.x, lower.y + lower.note.staffStep() * gap * .5f, gap);
            if (sample != null) samples.add(new float[] {lower.x, sample[0], sample[1]});
            var shafts = i == 3 ? clippedOctaveShaft(ink, pair, gap) : shafts(ink, pair, gap, -1);
            if (shafts.isEmpty()) return false;
            axes.add(shafts);
        }
        if (samples.size() < 3) return false;
        var staff = StaffPitchTrack.fromVerifiedSamples(samples);
        if (staff == null || !outsideOctaveBeam(ink, axes.get(0), -1, gap, staff)
                || !outsideOctaveBeam(ink, axes.get(axes.size() - 1), 1, gap, staff)) return false;
        for (int i = 0; i + 1 < group.size(); i++) {
            if (beams(ink, axes.get(i), axes.get(i + 1), group.get(i), group.get(i + 1),
                    gap, -1, staff).isEmpty()) return false;
        }
        return true;
    }

    private static List<Shaft> clippedOctaveShaft(Ink ink, Dyad pair, float gap) {
        var upper = PrintedBeamShafts.at(ink.raw, ink.width, ink.height, pair.top.x, pair.top.y,
                gap, new PrintedBeamShafts.Budget(60_000));
        if (upper.size() != 1) return List.of();
        var shaft = upper.get(0);
        if (shaft.direction() != 1 || shaft.endY() - pair.top.y < gap * 2.20f
                || pair.bottom.y - shaft.endY() < gap * .60f
                || pair.bottom.y - shaft.endY() > gap * 1.17f) return List.of();
        var lower = new ArrayList<Shaft>();
        int row = Math.round(pair.bottom.y), begin = row + Math.round(gap * .47f);
        for (int x = shaft.left() - 1; x <= shaft.right() + 1; x++) {
            if (!ink.dark(x, row) || !ink.dark(x + Math.round(gap * .36f), row)) continue;
            int first = begin;
            while (first - begin <= gap * .24f && !ink.dark(x, first)) first++;
            if (first - begin > gap * .24f) continue;
            int end = first;
            while (end - row <= gap * 4 && ink.dark(x, end)) end++;
            if (end - first >= gap * 2.25f && end - row <= gap * 3.65f)
                lower.add(new Shaft(x, end - 1));
        }
        return lower;
    }

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
        return claimedBracket(ink, geometry, first, gap, rows, track, verifyContinuingStroke, null);
    }

    private static boolean claimedBracket(
            Ink ink, ClosedFourDyadBeam.Head[] geometry, int first, float gap,
            UnnumberedTripletInk.Rows rows, StaffPitchTrack track, boolean verifyContinuingStroke,
            List<List<Shaft>> sharedShafts) {
        float top = Float.POSITIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
        int tip = 0, direction = 0;
        var budget = new PrintedBeamShafts.Budget(100_000);
        for (int i = first; i < first + 3; i++) {
            var chord = geometry[i];
            var shafts = PrintedBeamShafts.at(ink.raw, ink.width, ink.height,
                    chord.x(), chord.top(), gap, budget);
            int shaftDirection, shaftEnd;
            if (shafts.size() == 1) {
                shaftDirection = shafts.get(0).direction();
                shaftEnd = shafts.get(0).endY();
            } else if (sharedShafts != null && shafts.isEmpty()) {
                var candidates = sharedShafts.get(i);
                if (candidates.get(candidates.size() - 1).x() - candidates.get(0).x() > gap * .24f)
                    return true;
                int lowest = candidates.stream().mapToInt(Shaft::tip).max().orElseThrow();
                int highest = candidates.stream().mapToInt(Shaft::tip).min().orElseThrow();
                if (lowest - highest > gap * .30f) return true;
                shaftDirection = 1;
                shaftEnd = lowest;
                verifyContinuingStroke = true;
            } else return true;
            if (i == first) {
                direction = shaftDirection;
                tip = shaftEnd;
            } else if (shaftDirection != direction) {
                return true;
            } else {
                tip = direction == 1 ? Math.max(tip, shaftEnd) : Math.min(tip, shaftEnd);
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
        return glyph.text().equals("4/4")
                && glyph.topScore() >= .86f
                && glyph.bottomScore() >= .86f;
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

    /** A following double stop's lower staccato may sit beside the prior head in a straight bar. */
    private static boolean reassignFollowingDyadStaccatos(
            Ink ink, Ink original, MeasureRegion bar, List<Head> source, List<ScoreNoteEvent> result) {
        float gap = (bar.bottom() - bar.top()) * ink.height / 8.2f;
        if (gap < 12 || gap > 24 || source.stream().noneMatch(h -> h.note.augmentationDots() == 1))
            return false;
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        int clef = heads.get(0).note.clefBottomDiatonic();
        int interval = Math.abs(heads.get(0).note.staffStep() - heads.get(1).note.staffStep());
        if (interval != 5 && interval != 7) return false;
        var pairs = new ArrayList<Dyad>(16);
        for (int i = 0; i < heads.size(); i += 2) {
            var a = heads.get(i);
            var b = heads.get(i + 1);
            var pair = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
            if (b.x - a.x > gap * .22f
                    || pair.top.note.staffStep() - pair.bottom.note.staffStep() != interval
                    || Math.abs((pair.bottom.y - pair.top.y) / gap - interval * .5f) > .35f) return false;
            for (var h : List.of(a, b)) {
                var n = h.note;
                if (!ordinaryGeometry(n, clef) || n.beamCount() != 2 || n.unbeamedDurationBeats() != 0
                        || n.tupletDivisor() != 1 || n.tupletNormalNotes() != 1
                        || n.augmentationDots() != a.note.augmentationDots() || n.augmentationDots() > 1
                        || n.stemDirection() == 0 || n.stemDirection() != a.note.stemDirection())
                    return false;
            }
            if (!pairs.isEmpty()) {
                float advance = pair.top.x - pairs.get(pairs.size() - 1).top.x;
                if (advance < gap * 1.5f || advance > gap * 4.15f) return false;
            }
            pairs.add(pair);
        }
        boolean sixths = interval == 5;
        var dotted = new ArrayList<Integer>();
        for (int i = 0; i < pairs.size(); i++) {
            var before = pairs.get(i);
            if (before.top.note.augmentationDots() == 0) continue;
            if (i + 1 == pairs.size()) return false;
            var next = pairs.get(i + 1);
            if (!followingDyadStaccato(ink, before, next, gap, sixths)
                    || !followingDyadStaccato(original, before, next, gap, sixths)) return false;
            dotted.add(i);
        }
        for (int i : dotted) {
            var before = pairs.get(i);
            var next = pairs.get(i + 1);
            for (var h : List.of(before.top, before.bottom))
                result.set(h.owner, withoutDot(h.note));
            for (var h : List.of(next.top, next.bottom))
                result.set(h.owner, h.note.withArticulations(h.note.articulations() | NoteArticulation.STACCATO));
        }
        if (closes(heads, result)) return true;
        for (var h : heads) result.set(h.owner, h.note);
        return false;
    }

    private static boolean followingDyadStaccato(Ink ink, Dyad before, Dyad next, float gap, boolean sixths) {
        float advance = (next.bottom.x - before.bottom.x) / gap;
        float rise = (before.bottom.y - next.bottom.y) / gap;
        if (advance < 1.67f || advance > (sixths ? 2.40f : 2.24f) || rise < .84f || rise > 1.33f
                || before.bottom.note.stemDirection() != 1 || next.bottom.note.stemDirection() != 1
                || next.bottom.note.augmentationDots() != 0)
            return false;
        if (!OmrScoreInterpreter.centerOwnedLowerStaccato(ink.raw, ink.width, ink.height,
                next.bottom.x, next.bottom.y, gap, .99f, 1.39f, .66))
            return false;
        if (hasSeparateAugmentation(ink, before, next, gap, sixths)) return false;
        var left = shafts(ink, before, gap, 1);
        var right = shafts(ink, next, gap, 1);
        if (left.isEmpty() || right.isEmpty()) return false;
        if (!beams(ink, left, right, before, next, gap, 1).isEmpty()) return true;
        var budget = new PrintedBeamShafts.Budget(90_000);
        var a = PrintedBeamShafts.at(ink.raw, ink.width, ink.height, before.top.x, before.top.y, gap, budget);
        var b = PrintedBeamShafts.at(ink.raw, ink.width, ink.height, next.top.x, next.top.y, gap, budget);
        return !budget.refused() && a.size() == 1 && b.size() == 1
                && a.get(0).direction() == -1 && b.get(0).direction() == -1
                && WrappedEighthTriplets.matchesBeamRails(
                        ink.raw, ink.width, ink.height, a.get(0), b.get(0), gap, 2);
    }

    private static boolean hasSeparateAugmentation(
            Ink ink, Dyad before, Dyad next, float gap, boolean ignoreLedgerEnd) {
        float limit = next.bottom.x - gap * .17f;
        for (var h : List.of(before.top, before.bottom)) {
            var core = WrappedTripletDotInk.uniqueCore(
                    ink.raw, ink.width, ink.height, h.x, h.y, limit, gap);
            if (core != null && (!ignoreLedgerEnd || !ownLedgerEnd(ink, h, core, gap))
                    || LegacyTripletDotInk.singleDotCore(
                            ink.raw, ink.width, ink.height, h.x, h.y, limit, gap)) return true;
        }
        return false;
    }

    /** A complete printed dyad bar can lose a rail and borrow following articulation dots. */
    private static boolean closeArticulatedDyadRails(
            Ink ink, Ink original, List<MeasureRegion> regions, int measure,
            List<Head> source, List<ScoreNoteEvent> result) {
        if (source.stream().noneMatch(h -> h.note.augmentationDots() == 1
                && (h.note.beamCount() == 1 || h.note.stemDirection() == -1))) return false;
        var region = regions.get(measure);
        float gap = (region.bottom() - region.top()) * ink.height / 8.2f;
        if (gap < 12 || gap > 24) return false;
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var pairs = new ArrayList<Dyad>(16);
        var columns = new ClosedFourDyadBeam.Head[16];
        int clef = heads.get(0).note.clefBottomDiatonic();
        int direction = 0;
        int interval = Math.abs(heads.get(0).note.staffStep() - heads.get(1).note.staffStep());
        if (interval != 5 && interval != 7) return false;
        boolean sixths = interval == 5;
        for (int slot = 0; slot < columns.length; slot++) {
            var a = heads.get(slot * 2);
            var b = heads.get(slot * 2 + 1);
            var pair = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
            if (slot % 4 == 0) direction = a.note.stemDirection();
            if (direction == 0 || b.x - a.x > gap * .22f
                    || pair.top.note.staffStep() - pair.bottom.note.staffStep() != interval
                    || Math.abs((pair.bottom.y - pair.top.y) / gap - interval * .5f) > .38f)
                return false;
            for (var h : List.of(a, b)) {
                var n = h.note;
                if (!ordinaryGeometry(n, clef) || n.stemDirection() != direction
                        || n.tupletDivisor() != 1 || n.tupletNormalNotes() != 1
                        || n.unbeamedDurationBeats() != 0 || n.augmentationDots() > 1
                        || n.beamCount() != a.note.beamCount()
                        || n.beamCount() != 2 && (sixths || n.beamCount() != 1 || n.augmentationDots() != 1))
                    return false;
            }
            float x = (a.x + b.x) * .5f;
            if (slot > 0 && (x - columns[slot - 1].x() < gap * 1.5f
                            || x - columns[slot - 1].x() > gap * 3.35f))
                return false;
            columns[slot] = new ClosedFourDyadBeam.Head(x, pair.top.y, pair.bottom.y);
            pairs.add(pair);
        }

        var rows = UnnumberedTripletInk.sourceRows(region, regions, ink.height);
        for (var plane : List.of(ink, original)) {
            if (!printedFourRailGroups(plane, regions, measure, pairs, columns, List.of(), gap, rows, -1, false, false))
                return false;
            for (int slot = 0; slot < pairs.size(); slot++) {
                var before = pairs.get(slot);
                int dots = before.top.note.augmentationDots() + before.bottom.note.augmentationDots();
                if (dots == 0) continue;
                if (slot + 1 == pairs.size()) return false;
                var next = pairs.get(slot + 1);
                if (sixths) {
                    if (dots == 2 && followingUpperPortato(plane, before, next, gap)) continue;
                    return false;
                }
                if (dots == 2 && (followingDyadStaccato(plane, before, next, gap, false)
                        || followingUpperStaccato(plane, before, next, gap))) continue;
                if (dots == 1 && before.top.note.augmentationDots() == 1
                        && before.top.note.beamCount() == 2
                        && followingUpperPortato(plane, before, next, gap)) continue;
                return false;
            }
        }
        for (var h : heads) {
            var note = h.note;
            if (note.augmentationDots() != 0) note = withoutDot(note);
            if (note.beamCount() == 1) note = asSharedBeam(note, 2).withStemDirection(note.stemDirection());
            result.set(h.owner, note);
        }
        if (closes(heads, result)) return true;
        for (var h : heads) result.set(h.owner, h.note);
        return false;
    }

    private static boolean followingUpperStaccato(Ink ink, Dyad before, Dyad next, float gap) {
        float advance = (next.top.x - before.top.x) / gap;
        float descent = (next.top.y - before.top.y) / gap;
        if (before.top.note.stemDirection() != -1 || next.top.note.stemDirection() != -1
                || before.top.note.beamCount() != 2 || next.top.note.augmentationDots() != 0
                || next.bottom.note.augmentationDots() != 0
                || advance < 1.65f || advance > 2.27f || descent < .36f || descent > .66f
                || (next.top.note.articulations() & NoteArticulation.STACCATO) == 0
                || (next.bottom.note.articulations() & NoteArticulation.STACCATO) == 0
                || hasSeparateAugmentation(ink, before, next, gap, false)) return false;
        // A slur above the round mark may intrude into a fixed-width white ring.
        // The actual compact component must still be detached from both the slur and the note.
        var head = next.top;
        int x0 = Math.round(head.x - gap * .86f), x1 = Math.round(head.x + gap * .86f);
        int y0 = Math.round(head.y - gap * 2.05f), y1 = Math.round(head.y - gap * .64f);
        if (x0 < 0 || x1 >= ink.width || y0 < 0 || y1 >= ink.height) return false;
        var crop = RawNumeralComponents.capture(
                ink.raw, ink.width, ink.height, x0, y0, x1, y1, 155, () -> {}, () -> {});
        boolean found = false;
        for (var part : crop.components()) {
            int width = part.right - part.left + 1, height = part.bottom - part.top + 1;
            float midX = (part.left + part.right) * .5f, midY = (part.top + part.bottom) * .5f;
            float above = (head.y - midY) / gap;
            if (part.left <= x0 || part.right >= x1 || part.top <= y0 || part.bottom >= y1
                    || Math.abs(midX - head.x) > gap * .17f || above < .91f || above > 1.25f
                    || Math.min(width,height) < gap * .19f || Math.max(width,height) > gap * .54f
                    || Math.max(width,height) > Math.min(width,height) * 1.55f
                    || part.size() < width * height * .56f) continue;
            if (found) return false;
            found = true;
        }
        return found;
    }

    private static boolean followingUpperPortato(Ink ink, Dyad before, Dyad next, float gap) {
        int portato = NoteArticulation.STACCATO | NoteArticulation.TENUTO;
        float advance = (next.top.x - before.top.x) / gap;
        float descent = (next.top.y - before.top.y) / gap;
        return before.top.note.stemDirection() == -1 && next.top.note.stemDirection() == -1
                && advance >= 1.65f && advance <= 2.27f && descent >= .34f && descent <= .68f
                && (next.top.note.articulations() & portato) == portato
                && (next.bottom.note.articulations() & portato) == portato
                && !hasSeparateAugmentation(ink, before, next, gap, false)
                && OmrScoreInterpreter.ownsAbovePortato(ink.raw, ink.width, ink.height, next.top.x, next.top.y, gap);
    }

    private static boolean ownLedgerEnd(Ink ink, Head head, WrappedTripletDotInk.Center core, float gap) {
        int step = head.note.staffStep();
        if ((step >= 0 && step <= 8) || step % 2 != 0
                || Math.abs(core.y() - head.y) > gap * .22f || core.x() - head.x > gap)
            return false;
        int from = Math.round(head.x - gap * .72f);
        int thickRows = 0;
        int radius = Math.round(gap * .13f);
        for (int y = core.y() - radius; y <= core.y() + radius; y++) {
            int x = from;
            while (x <= core.x() && ink.dark(x, y)) x++;
            if (x > core.x()) thickRows++;
        }
        return thickRows >= 2;
    }

    /** Clear an unprinted dot on the first octave of a closed four-by-four sixteenth bar. */
    private static boolean clearOpeningOctaveDots(
            Ink ink, Ink original, List<MeasureRegion> bars, int index,
            List<Head> source, List<ScoreNoteEvent> result) {
        var bar = bars.get(index);
        float gap = (bar.bottom() - bar.top()) * ink.height / 8.2f;
        float span = (bar.right() - bar.left()) * ink.width;
        if (index == 0 || gap < 12 || gap > 24 || span < 33 * gap || span > 42 * gap
                || Math.abs(bar.top() - bars.get(index - 1).top()) * ink.height > gap * .35f)
            return false;
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var pairs = new ArrayList<Dyad>(16);
        int clef = heads.get(0).note.clefBottomDiatonic();
        for (int i = 0; i < 16; i++) {
            var a = heads.get(2 * i);
            var b = heads.get(2 * i + 1);
            var pair = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
            int direction = i < 4 ? 1 : -1;
            if (pair.top.note.staffStep() - pair.bottom.note.staffStep() != 7 || b.x - a.x > gap * .18f
                    || pair.bottom.y - pair.top.y < gap * 3.25f || pair.bottom.y - pair.top.y > gap * 3.75f)
                return false;
            for (var head : List.of(a, b)) {
                var n = head.note;
                if (!ordinaryGeometry(n, clef) || n.stemDirection() != direction
                        || n.beamCount() != 2 || n.unbeamedDurationBeats() != 0
                        || n.tupletDivisor() != 1 || n.tupletNormalNotes() != 1
                        || n.augmentationDots() != (i == 0 ? 1 : 0)) return false;
            }
            pairs.add(pair);
        }
        var first = pairs.get(0);
        if (!printedOpeningOctaveQuartet(ink, bar, bars.get(index - 1), pairs, gap)
                || !printedOpeningOctaveQuartet(original, bar, bars.get(index - 1), pairs, gap))
            return false;
        result.set(first.top.owner, withoutDot(first.top.note));
        result.set(first.bottom.owner, withoutDot(first.bottom.note));
        if (closes(heads, result)) return true;
        result.set(first.top.owner, first.top.note);
        result.set(first.bottom.owner, first.bottom.note);
        return false;
    }

    private static boolean printedOpeningOctaveQuartet(
            Ink ink, MeasureRegion bar, MeasureRegion previous, List<Dyad> pairs, float nominal) {
        var first = pairs.get(0);
        var fit = PrintedDirectionStaff.printedRules(ink.raw, ink.width, ink.height,
                first.top.x, bar.bottom() * ink.height - 2.1f * nominal, nominal);
        if (fit == null || Math.abs(fit[1] - nominal) > nominal * .12f) return false;
        float gap = fit[1];
        var staff = new PlayingTechniqueDetector.Staff(fit[0] - 4 * gap, fit[0], gap, 0, 1);
        float left = (bar.left() + previous.right()) * ink.width * .5f;
        float right = bar.right() * ink.width;
        if (!ClosedSixteenthPairBeforeHalf.ownedSeparator(ink.raw, ink.width, ink.height, staff, left)
                || !ClosedSixteenthPairBeforeHalf.ownedSeparator(ink.raw, ink.width, ink.height, staff, right))
            return false;

        float following = pairs.get(1).top.x;
        for (var owner : List.of(first.top, first.bottom)) {
            if (WrappedTripletDotInk.uniqueCore(
                            ink.raw, ink.width, ink.height, owner.x, owner.y, following, gap) != null)
                return false;
        }

        var budget = new PrintedBeamShafts.Budget(220_000);
        PrintedBeamShafts.Shaft prior = null;
        for (var pair : pairs.subList(0, 5)) {
            var high = PrintedBeamShafts.at(ink.raw, ink.width, ink.height, pair.top.x, pair.top.y, gap, budget);
            var low = PrintedBeamShafts.at(ink.raw, ink.width, ink.height, pair.bottom.x, pair.bottom.y, gap, budget);
            if (budget.refused() || high.size() != 1 || low.size() != 1) return false;
            var upper = high.get(0);
            var lower = low.get(0);
            if (upper.direction() != lower.direction()
                    || Math.max(upper.left(), lower.left()) > Math.min(upper.right(), lower.right())
                    || Math.abs(upper.endY() - lower.endY()) > gap * .14f) return false;
            var shaft = lower.direction() < 0 ? lower : upper;
            if (prior != null && !WrappedEighthTriplets.matchesBeamRails(
                    ink.raw, ink.width, ink.height, prior, shaft, gap, shaft.direction() < 0 ? 2 : 0))
                return false;
            prior = shaft;
        }
        return true;
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
        return beams(ink, left, right, a, b, gap, direction, staff, 3.2f);
    }

    /** A single middle link may be wider when a written accidental separates its heads. */
    private static List<Rails> beams(
            Ink ink,
            List<Shaft> left,
            List<Shaft> right,
            Dyad a,
            Dyad b,
            float gap,
            int direction,
            StaffPitchTrack staff,
            float maximumSpan) {
        return beams(ink, left, right, a, b, gap, direction, staff, maximumSpan, false);
    }

    private static List<Rails> beams(
            Ink ink, List<Shaft> left, List<Shaft> right, Dyad a, Dyad b, float gap,
            int direction, StaffPitchTrack staff, float maximumSpan, boolean includeAlternatives) {
        boolean wideDownstem = staff != null && direction == -1
                && a.bottom.y - a.top.y >= gap * 2.2f && b.bottom.y - b.top.y >= gap * 2.2f;
        int margin=Math.round(gap*.3f),radius=Math.max(2,Math.round(gap*.12f));
        int minOffset=Math.round(gap*(direction>0 ? -1.6f : -.81f)),maxOffset=Math.round(gap*.55f);
        int minSeparation=Math.round(gap*.66f),maxSeparation=Math.round(gap*1.15f);
        List<Rails> accepted=new ArrayList<>();
        for (var s:left) for (var t:right) {
            int span=t.x-s.x;
            if (span<gap*1.5f || span>gap*maximumSpan) continue;
            int first=s.x+margin,last=t.x-margin;
            if (maximumSpan > 3.2f && hasAdditionalRail(ink, s, t, gap, direction, first, last))
                continue;
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
                                                    direction)
                                            && !(wideDownstem && narrowPrintedRailSeam(
                                                    ink, staff, axis, first, last, middle)))) continue;
                            var rails = new Rails(s.x, y, t.x, z, d);
                            if (staff != null && !includeAlternatives) return List.of(rails);
                            accepted.add(rails);
                }
            }
        }
        return accepted;
    }

    /** Search for a third thick band across the widened link, ignoring thin staff lines. */
    private static boolean hasAdditionalRail(
            Ink ink, Shaft left, Shaft right, float gap, int direction, int first, int last) {
        var tips = new Rails(left.x, left.tip, right.x, right.tip, 0);
        int thickness = Math.max(4, Math.round(gap * .27f));
        int radius = Math.round(gap * .20f);
        for (int offset = Math.round(gap * 1.45f); offset <= Math.round(gap * 2.30f); offset++) {
            int covered = 0;
            for (int x = first; x <= last; x++) {
                if (stroke(ink, x, tips.at(x) + direction * offset, radius) >= thickness)
                    covered++;
            }
            if (covered >= (last - first + 1) * .60f) return true;
        }
        return false;
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
            else if (isStaffRule(frame, y, .19f)) rule++;
            else unexplained++;
        }
        int span = last - first + 1;
        return paper >= span * .20f && rule >= span * .35f && unexplained <= span * .08f;
    }

    /** The paper between thick rails can be only one pixel wide on a source scan. */
    private static boolean narrowPrintedRailSeam(
            Ink ink, StaffPitchTrack staff, Rails rail, int first, int last, int middle) {
        int paper = 0, rule = 0, uninterrupted = 0, longest = 0;
        for (int x = first; x <= last; x++) {
            int y = rail.at(x) - middle;
            var frame = staff.at(x);
            int tolerance = Math.max(1, Math.round(frame[1] * .15f));
            boolean slit = false;
            for (int row = y - tolerance; row <= y + tolerance; row++) {
                if (ink.dark(x, row)) continue;
                if (stroke(ink, x, row - 2, 1) >= 2 && stroke(ink, x, row + 2, 1) >= 2) {
                    slit = true;
                    break;
                }
            }
            if (slit) {
                paper++;
                uninterrupted++;
                longest = Math.max(longest, uninterrupted);
            } else {
                uninterrupted = 0;
                if (isStaffRule(frame, y, .19f) && stroke(ink, x, y, 1) >= 2) rule++;
            }
        }
        int span = last - first + 1;
        return paper >= span * .50f && longest >= Math.max(3, Math.round(span * .25f))
                && paper + rule >= span * .90f;
    }

    private static boolean isStaffRule(float[] frame, int y, float tolerance) {
        float offset = (frame[0] - y) / frame[1];
        int rule = Math.round(offset);
        return rule >= 0 && rule <= 4 && Math.abs(offset - rule) <= tolerance;
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

    /** A closed sixteenth group can borrow the upper staccato from its closing quarter. */
    private static boolean closeTerminalStaccatoSixths(
            Ink ink, Ink original, MeasureRegion bar, List<Head> source, List<ScoreNoteEvent> result) {
        if (source.stream().filter(h -> h.note.augmentationDots() == 1).count() != 2) return false;
        float gap = (bar.bottom() - bar.top()) * ink.height / 8.2f;
        if (gap < 12 || gap > 24) return false;
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var pairs = new ArrayList<Dyad>(13);
        int clef = heads.get(0).note.clefBottomDiatonic();
        for (int slot = 0; slot < 13; slot++) {
            var a = heads.get(slot * 2);
            var b = heads.get(slot * 2 + 1);
            var pair = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
            float separation = (pair.bottom.y - pair.top.y) / gap;
            if (b.x - a.x > gap * .22f || pair.top.note.staffStep() - pair.bottom.note.staffStep() != 5
                    || separation < 2.2f || separation > 2.85f) return false;
            if (slot > 0) {
                float advance = (pair.top.x - pairs.get(pairs.size() - 1).top.x) / gap;
                if (advance < 1.60f || advance > 2.60f) return false;
            }
            for (var h : List.of(a, b)) {
                var note = h.note;
                if (!ordinaryGeometry(note, clef) || note.stemDirection() != -1
                        || note.tupletDivisor() != 1 || note.tupletNormalNotes() != 1
                        || note.beamCount() != (slot == 12 ? 0 : 2)
                        || note.unbeamedDurationBeats() != (slot == 12 ? 1 : 0)
                        || note.augmentationDots() != (slot == 11 ? 1 : 0)) return false;
            }
            pairs.add(pair);
        }
        for (var plane : List.of(ink, original)) {
            if (!followingUpperStaccato(plane, pairs.get(11), pairs.get(12), gap)
                    || !terminalSixthRails(plane, bar, pairs, gap)) return false;
        }
        var revised = new ArrayList<ScoreNoteEvent>(26);
        for (int i = 0; i < heads.size(); i++) {
            var note = heads.get(i).note;
            revised.add(i / 2 == 11 ? withoutDot(note) : note);
        }
        for (int i = 0; i < revised.size(); i++) {
            var placement = ScoreNoteTiming.writtenPlacement(revised.get(i), revised, 4f).orElse(null);
            if (placement == null || Math.abs(placement.onsetBeats() - (i / 2) * .25) > 1e-6
                    || Math.abs(placement.durationBeats() - (i < 24 ? .25 : 1)) > 1e-6) return false;
        }
        result.set(heads.get(22).owner, revised.get(22));
        result.set(heads.get(23).owner, revised.get(23));
        return true;
    }

    private static boolean terminalSixthRails(Ink ink, MeasureRegion bar, List<Dyad> pairs, float gap) {
        float lastHead = pairs.get(pairs.size() - 1).bottom.x;
        if (!WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(
                        ink.raw, ink.width, ink.height, bar, lastHead)
                && !WrappedMeasureBoundaries.confirmsClosed(
                        ink.raw, ink.width, ink.height, bar, pairs.get(0).top.x, lastHead))
            return false;
        var budget = new PrintedBeamShafts.Budget(200_000);
        PrintedBeamShafts.Shaft previous = null;
        for (int slot = 0; slot < pairs.size(); slot++) {
            var h = pairs.get(slot).bottom;
            var shafts = PrintedBeamShafts.at(ink.raw, ink.width, ink.height, h.x, h.y, gap, budget);
            if (shafts.size() != 1 || shafts.get(0).direction() != 1) return false;
            var shaft = shafts.get(0);
            if (previous != null) {
                boolean cut = slot % 4 == 0;
                boolean doubleRail = WrappedEighthTriplets.matchesBeamRails(
                        ink.raw, ink.width, ink.height, previous, shaft, gap, 2);
                if (cut ? doubleRail || WrappedEighthTriplets.matchesBeamRails(
                                ink.raw, ink.width, ink.height, previous, shaft, gap, 1)
                        : !doubleRail) return false;
            }
            previous = shaft;
        }
        return !budget.refused();
    }

    /** A joined first octave owns the same two rails as its following sixteenth group. */
    private static boolean restoreOpeningSixteenthBeforeQuarter(
            Ink ink, Ink source, MeasureRegion bar, List<Head> heads, List<ScoreNoteEvent> result) {
        float gap = (bar.bottom() - bar.top()) * ink.height / 8.2f;
        if (gap < 10 || gap > 26) return false;
        var ordered = heads.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var pairs = new ArrayList<Dyad>(13);
        int clef = ordered.get(0).note.clefBottomDiatonic();
        for (int i = 0; i < 13; i++) {
            var a = ordered.get(i * 2);
            var b = ordered.get(i * 2 + 1);
            var pair = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
            if (Math.abs(b.x - a.x) > gap * .22f
                    || pair.top.note.staffStep() - pair.bottom.note.staffStep() != 7
                    || pair.bottom.y - pair.top.y < gap * 3.15f
                    || pair.bottom.y - pair.top.y > gap * 3.9f) return false;
            if (i > 0) {
                float distance = pair.top.x - pairs.get(i - 1).top.x;
                if (distance < gap * 1.55f || distance > gap * 3.2f) return false;
            }
            int beams = i == 0 ? 1 : i == 12 ? 0 : 2;
            float value = i == 12 ? 1 : 0;
            for (var head : List.of(a, b)) {
                var n = head.note;
                if (!ordinaryGeometry(n, clef)
                        || n.stemDirection() != -1
                        || n.beamCount() != beams
                        || n.unbeamedDurationBeats() != value
                        || n.tupletDivisor() != 1
                        || n.tupletNormalNotes() != 1
                        || n.augmentationDots() != 0) return false;
            }
            pairs.add(pair);
        }
        if (!openingOctaveRails(ink, bar, pairs, gap)
                || !openingOctaveRails(source, bar, pairs, gap)) return false;

        var revised = new ArrayList<ScoreNoteEvent>(26);
        for (int i = 0; i < ordered.size(); i++)
            revised.add(i < 2 ? asSharedBeam(ordered.get(i).note, 2) : ordered.get(i).note);
        for (int i = 0; i < revised.size(); i++) {
            var placement =
                    ScoreNoteTiming.writtenPlacement(revised.get(i), revised, 4f).orElse(null);
            if (placement == null
                    || Math.abs(placement.onsetBeats() - (i / 2) * .25) > 1e-6
                    || Math.abs(placement.durationBeats() - (i < 24 ? .25 : 1)) > 1e-6)
                return false;
        }
        result.set(ordered.get(0).owner, revised.get(0));
        result.set(ordered.get(1).owner, revised.get(1));
        return true;
    }

    private static boolean openingOctaveRails(
            Ink ink, MeasureRegion bar, List<Dyad> pairs, float gap) {
        var samples = new ArrayList<float[]>();
        for (var pair : pairs) {
            var lower = pair.bottom;
            float estimatedBottom = lower.y + lower.note.staffStep() * gap * .5f;
            var rails = localRules(ink, lower.x, estimatedBottom, gap);
            if (rails != null) samples.add(new float[] {lower.x, rails[0], rails[1]});
        }
        var staff =
                samples.size() >= 6
                        ? StaffPitchTrack.fromVerifiedSamples(samples)
                        : WrappedMeasureBoundaries.verifiedTripletStaff(
                                ink.raw, ink.width, ink.height, bar);
        if (staff == null
                || !WrappedMeasureBoundaries.confirmsClosedWithVerifiedStaff(
                        ink.raw,
                        ink.width,
                        ink.height,
                        bar,
                        pairs.get(0).top.x,
                        pairs.get(12).bottom.x,
                        staff)) return false;
        var budget = new PrintedBeamShafts.Budget(100_000);
        PrintedBeamShafts.Shaft previous = null;
        for (int i = 0; i < 4; i++) {
            var head = pairs.get(i).bottom;
            var shafts =
                    PrintedBeamShafts.at(
                            ink.raw, ink.width, ink.height, head.x, head.y, gap, budget);
            if (shafts.size() != 1 || shafts.get(0).direction() != 1) return false;
            var shaft = shafts.get(0);
            if (previous != null
                    && !WrappedEighthTriplets.matchesBeamRails(
                            ink.raw, ink.width, ink.height, previous, shaft, gap, 2)) return false;
            previous = shaft;
        }
        return true;
    }

    private static boolean closes(List<Head> heads,List<ScoreNoteEvent> result) {
        return closes(heads, result, 4f);
    }

    private static boolean closes(List<Head> heads, List<ScoreNoteEvent> result, float measureBeats) {
        var local=heads.stream().map(h->result.get(h.owner)).toList();
        for (int i=0;i<local.size();i++) {
            var placement=ScoreNoteTiming.writtenPlacement(local.get(i),local,measureBeats).orElse(null);
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
