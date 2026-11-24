// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Set;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
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
        return apply(score, inheritedMeter, gray, raw, w, h, physicalPage, note -> {});
    }

    static ScorePageInterpretation apply(
            ScorePageInterpretation score,
            ScoreMeterChange inheritedMeter,
            byte[] gray,
            byte[] raw,
            int w,
            int h,
            int physicalPage,
            Consumer<SourceLocalAccidental.ProvedMark> printedAccidental) {
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
        var printedSlurs = score.slurSource();
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
            if (count == 28 && original != null && effective != null
                    && effective.numerator() == 4 && effective.denominator() == 4
                    && regions.get(m).cadenceQuarterBeats() == 0
                    && score.rests().stream().noneMatch(r -> r.measureIndex() == bar)) {
                var row = bars.get(m).stream().sorted(Comparator.comparingDouble(Head::x)).toList();
                if (restoreMixedTenthRail(ink, original, regions.get(m), row, result)) {
                    if (physicalPage >= 0)
                        printedBeams = projectRailGroups(printedBeams, physicalPage, row, 2,
                                List.of(List.of(1, 3), List.of(3, 3, 3),
                                        List.of(1, 3), List.of(3, 3, 3)));
                    continue;
                }
            }
            if (original != null && effective != null && effective.denominator() == 4
                    && (effective.numerator() == 3 || effective.numerator() == 4)
                    && count == effective.numerator() * 8
                    && regions.get(m).cadenceQuarterBeats() == 0
                    && score.rests().stream().noneMatch(r -> r.measureIndex() == bar)
                    && clearDoubleSharpAliasDots(
                            ink, original, regions.get(m), bars.get(m), result, effective.numerator()))
                continue;
            if (physicalPage >= 0 && original != null && effective != null
                    && effective.numerator() == 4 && effective.denominator() == 4
                    && (count == 26 || count == 32)
                    && regions.get(m).cadenceQuarterBeats() == 0
                    && beamOriginsUnclaimed(printedBeams, bars.get(m))
                    && score.rests().stream().noneMatch(r -> r.measureIndex() == bar)) {
                var thirds = straightThirdBeamOwners(ink, original, regions.get(m), bars.get(m));
                if (!thirds.isEmpty()) printedBeams = projectDoubleRails(printedBeams, physicalPage, thirds);
            }
            if (count == 32 && original != null && effective != null
                    && effective.numerator() == 4 && effective.denominator() == 4
                    && regions.get(m).cadenceQuarterBeats() == 0
                    && score.rests().stream().noneMatch(r -> r.measureIndex() == bar)
                    && restorePrintedOctaveRails(ink, original, regions, m, bars.get(m), result)) {
                if (physicalPage >= 0)
                    printedBeams = projectDoubleRails(printedBeams, physicalPage, bars.get(m));
                continue;
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
                    && clearClosingFlatAliasDots(ink, original, regions.get(m), bars.get(m), result))
                continue;
            if (count == 24 && original != null && effective != null
                    && effective.numerator() == 3 && effective.denominator() == 4
                    && regions.get(m).cadenceQuarterBeats() == 0
                    && score.rests().stream().noneMatch(r -> r.measureIndex() == bar)
                    && (clearNaturalStackDots(ink, original, regions, m, bars.get(m), result, printedAccidental)
                            || effective.measureIndex() == m
                                    && clearOpeningLedgerAliasDots(ink, original, regions, m, bars.get(m), result)
                            || closeChromaticThirdsAndRecoverStaggeredFlats(
                                    ink, original, regions, m, bars.get(m), result, score.keyChanges(), printedAccidental)
                            || physicalPage >= 0 && beamOriginsUnclaimed(printedBeams, bars.get(m))
                                    && confirmsStraightChromaticSixths(
                                            ink, original, regions, m, bars.get(m), result))) {
                if (physicalPage >= 0) {
                    printedBeams = projectDoubleRails(printedBeams, physicalPage, bars.get(m));
                    printedSlurs = projectChromaticThirdTailSlur(ink, original, regions.get(m),
                            bars.get(m), result, printedSlurs, physicalPage);
                }
                continue;
            }
            if ((count == 16 || count == 17) && original != null && effective != null
                    && effective.numerator() == 4 && effective.denominator() == 4
                    && regions.get(m).cadenceQuarterBeats() == 0
                    && score.rests().stream().noneMatch(r -> r.measureIndex() == bar))
                closeHarmonicStaccatoSixteenths(ink, original, regions.get(m), bars.get(m), result);
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
                    && reassignFollowingDyadStaccatos(ink, original, regions.get(m), bars.get(m),
                            score.slurSource(), result))
                continue;
            if (count == 32 && original != null && effective != null
                    && effective.numerator() == 4 && effective.denominator() == 4
                    && closeArticulatedDyadRails(ink, original, regions, m, bars.get(m), result)) {
                if (physicalPage >= 0)
                    printedBeams = projectDoubleRails(printedBeams, physicalPage, bars.get(m));
                continue;
            }
            if (count == 32 && original != null && effective != null
                    && effective.numerator() == 4 && effective.denominator() == 4
                    && regions.get(m).cadenceQuarterBeats() == 0
                    && score.rests().stream().noneMatch(r -> r.measureIndex() == bar)
                    && clearOctaveQuartetDots(ink, original, regions.get(m), bars.get(m), result)) {
                var heads = bars.get(m);
                var first = heads.stream().min(Comparator.comparingDouble(Head::x)).orElseThrow();
                if (physicalPage >= 0 && first.note.augmentationDots() == 1)
                    printedBeams = projectDoubleRails(printedBeams, physicalPage, heads);
                continue;
            }
            if (count == 32
                    && original != null
                    && effective != null
                    && effective.numerator() == 4
                    && effective.denominator() == 4
                    && (closeCompactOpeningThirdQuartets(ink, original, regions, m, bars.get(m), result)
                            || closeAcrossPrintedBeams(ink, original, regions, m, bars.get(m), result,
                            physicalPage >= 0 && beamOriginsUnclaimed(printedBeams, bars.get(m)))
                            || closeOctavesAcrossTripletCut(
                                    ink, original, regions, m, bars.get(m), result)
                            || closeStackedFingeredOpeningOctaves(
                                    ink, original, regions, m, bars.get(m), result)
                            || effective.measureIndex() == m
                                    && closeFingeredOpeningOctaves(
                                            ink, original, regions, m, bars.get(m), result))) {
                if (physicalPage >= 0) {
                    var owners = bars.get(m);
                    var projected = projectDoubleRails(printedBeams, physicalPage, owners);
                    if (projected == printedBeams && hasOpeningUpstemTriplet(owners))
                        projected = completeProvedDyadQuartets(printedBeams, physicalPage, owners);
                    printedBeams = projected;
                }
                continue;
            }
            if (count == 32 && original != null && effective != null
                    && effective.numerator() == 4 && effective.denominator() == 4) {
                var closing = closingOctaveSecondRail(ink, original, regions.get(m), bars.get(m));
                if (!closing.isEmpty()) {
                    var retained = physicalPage < 0 ? printedBeams : extendClosingOctaveBeam(printedBeams, closing);
                    if (physicalPage < 0 || retained != printedBeams) {
                        printedBeams = retained;
                        for (var head : closing.subList(8, 10))
                            result.set(head.owner(), asSharedBeam(head.note(), 2));
                        continue;
                    }
                }
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
            boolean printedCommonTime = active != null && active.numerator() == 4 && active.denominator() == 4;
            if (count == 30 && effective != null && effective.quarterBeats() == 4f
                    && (printedCommonTime || regions.get(m).cadenceQuarterBeats() == 0
                            && score.rests().stream().noneMatch(r -> r.measureIndex() == bar))) {
                var owners = bars.get(m);
                if (closeToEighth(ink, original, regions.get(m), bar == 0, regions, owners, result, printedCommonTime)
                        && physicalPage >= 0)
                    printedBeams = projectTerminalEighthRails(printedBeams, physicalPage, owners);
                continue;
            }
            if (count == 33 || !printedCommonTime) continue;
            if (count == 32 && original != null
                    && clearOpeningOctaveDots(ink, original, regions, m, bars.get(m), result)) continue;
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
            if (restoreSteppedOctaveShaft(ink, original, regions.get(m), bars.get(m), result)) continue;
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
        var corrected = result.equals(notes)
                ? score : score.withNotes(result, IntStream.range(0, notes.size()).boxed().toList());
        if (printedBeams != score.beamSource()) corrected = corrected.withBeamSource(printedBeams);
        if (printedSlurs != score.slurSource()) corrected = corrected.withSlurSource(printedSlurs);
        return corrected;
    }

    /** The staggered flats on the closing third can intrude into both preceding dot apertures. */
    private static boolean clearClosingFlatAliasDots(
            Ink ink, Ink original, MeasureRegion region, List<Head> source, List<ScoreNoteEvent> result) {
        float gap = (region.bottom() - region.top()) * ink.height / 8.2f;
        if (gap < 12 || gap > 24) return false;
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        int clef = heads.get(0).note.clefBottomDiatonic();
        var pairs = new ArrayList<Dyad>(12);
        for (int i = 0; i < heads.size(); i += 2) {
            var a = heads.get(i);
            var b = heads.get(i + 1);
            var pair = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
            int dots = i == 20 ? 1 : 0;
            if (a.note.augmentationDots() != dots || b.note.augmentationDots() != dots
                    || (i >= 16 && (!ordinaryDoubleRail(dots == 0 ? a.note : withoutDot(a.note), clef, -1, false)
                            || !ordinaryDoubleRail(dots == 0 ? b.note : withoutDot(b.note), clef, -1, false)))
                    || b.x - a.x > .22f * gap || pair.top.note.staffStep() - pair.bottom.note.staffStep() != 2
                    || Math.abs(pair.bottom.y - pair.top.y - gap) > .28f * gap) return false;
            pairs.add(pair);
        }
        var before = pairs.get(10);
        var ending = pairs.get(11);
        if (before.top.note.staffStep() != ending.top.note.staffStep()
                || before.top.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_NATURAL
                || before.bottom.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_NATURAL
                || ending.top.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT
                || ending.bottom.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT
                || ending.bottom.x - before.bottom.x < 2.75f * gap
                || ending.bottom.x - before.bottom.x > 3.55f * gap) return false;
        for (var plane : List.of(ink, original)) {
            if (hasSeparateAugmentation(plane, before, ending, gap, false)
                    || !closingFlatRails(plane, pairs.subList(8, 12), gap)
                    || !staggeredClosingFlats(plane, ending, gap)) return false;
        }
        result.set(before.top.owner, withoutDot(before.top.note));
        result.set(before.bottom.owner, withoutDot(before.bottom.note));
        if (closes(heads, result, 3f)) return true;
        result.set(before.top.owner, before.top.note);
        result.set(before.bottom.owner, before.bottom.note);
        return false;
    }

    private static boolean closingFlatRails(Ink ink, List<Dyad> tail, float gap) {
        var budget = new PrintedBeamShafts.Budget(120_000);
        PrintedBeamShafts.Shaft previous = null;
        for (var pair : tail) {
            var head = pair.bottom;
            var candidates = PrintedBeamShafts.at(ink.raw, ink.width, ink.height, head.x, head.y, gap, budget);
            if (budget.refused() || candidates.size() != 1 || candidates.get(0).direction() != 1) return false;
            var shaft = candidates.get(0);
            if (previous != null && !WrappedEighthTriplets.matchesBeamRails(
                    ink.raw, ink.width, ink.height, previous, shaft, gap, 2)) return false;
            previous = shaft;
        }
        return true;
    }

    private static boolean staggeredClosingFlats(Ink ink, Dyad ending, float gap) {
        for (int upper = Math.round(ending.top.x - 1.62f * gap);
                upper <= ending.top.x - 1.16f * gap; upper++) {
            if (!BeamedFlatBarline.flatAt(ink.raw, ink.width, ink.height, upper, ending.top.y, gap)) continue;
            for (int lower = upper - Math.round(1.18f * gap); lower <= upper - .54f * gap; lower++) {
                if (BeamedFlatBarline.flatAt(ink.raw, ink.width, ink.height, lower, ending.bottom.y, gap)) return true;
            }
        }
        return false;
    }

    /** Four connected double-rail quartets and a clear dot aperture close a straight octave bar. */
    private static boolean clearOctaveQuartetDots(
            Ink ink, Ink raw, MeasureRegion bar, List<Head> source, List<ScoreNoteEvent> result) {
        if (source.stream().filter(head -> head.note.augmentationDots() == 1).count() != 2) return false;
        float gap = (bar.bottom() - bar.top()) * ink.height / 8.2f;
        if (gap < 12 || gap > 24) return false;
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        int clef = heads.get(0).note.clefBottomDiatonic();
        boolean opening = heads.get(0).note.augmentationDots() == 1;
        var pairs = new ArrayList<Dyad>(16);
        int dot = -1;
        boolean positionMismatch = false;
        for (int i = 0; i < heads.size(); i += 2) {
            var a = heads.get(i);
            var b = heads.get(i + 1);
            var pair = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
            int dots = a.note.augmentationDots(), column = i / 2;
            int stepDifference = pair.top.note.staffStep() - pair.bottom.note.staffStep();
            if (stepDifference != 7) {
                if (!opening || column < 4 || positionMismatch || Math.abs(stepDifference - 7) != 1)
                    return false;
                positionMismatch = true;
            }
            if (dots != b.note.augmentationDots() || dots > 1
                    || dots == 1 && (opening ? column != 0 : column % 4 != 3 || column == 15)
                    || b.x - a.x > (opening ? .20f : .18f) * gap
                    || Math.abs(pair.bottom.y - pair.top.y - 3.5f * gap) > .35f * gap
                    || !ordinaryDoubleRail(dots == 0 ? a.note : withoutDot(a.note), clef, -1, false)
                    || !ordinaryDoubleRail(dots == 0 ? b.note : withoutDot(b.note), clef, -1, false))
                return false;
            if (column > 0) {
                float advance = pair.bottom.x - pairs.get(column - 1).bottom.x;
                if (advance < 1.60f * gap || advance > 2.6f * gap) return false;
            }
            if (dots == 1) dot = column;
            pairs.add(pair);
        }
        var dotted = pairs.get(dot);
        var next = pairs.get(dot + 1);
        for (var plane : List.of(ink, raw)) {
            if (opening && !WrappedMeasureBoundaries.confirmsSampledClosingEdge(
                        plane.raw, plane.width, plane.height, bar, pairs.get(pairs.size() - 1).top.x))
                return false;
            if (hasSeparateAugmentation(plane, dotted, next, gap, false)
                    || !closedOctaveQuartetRails(plane, pairs, gap)) return false;
        }
        result.set(dotted.top.owner, withoutDot(dotted.top.note));
        result.set(dotted.bottom.owner, withoutDot(dotted.bottom.note));
        if (closes(heads, result)) return true;
        result.set(dotted.top.owner, dotted.top.note);
        result.set(dotted.bottom.owner, dotted.bottom.note);
        return false;
    }

    private static boolean closedOctaveQuartetRails(Ink ink, List<Dyad> pairs, float gap) {
        var budget = new PrintedBeamShafts.Budget(300_000);
        PrintedBeamShafts.Shaft previous = null;
        for (int i = 0; i < pairs.size(); i++) {
            var shaft = sharedOpticalShaft(ink, pairs.get(i), gap, budget);
            if (shaft == null) return false;
            if (previous != null && !WrappedEighthTriplets.matchesBeamRails(
                    ink.raw, ink.width, ink.height, previous, shaft, gap, i % 4 == 0 ? 0 : 2))
                return false;
            previous = shaft;
        }
        return true;
    }

    private static boolean clearNaturalStackDots(
            Ink ink, Ink original, List<MeasureRegion> regions, int measure,
            List<Head> source, List<ScoreNoteEvent> result,
            Consumer<SourceLocalAccidental.ProvedMark> printedAccidental) {
        if (source.stream().filter(h -> h.note.augmentationDots() == 1).count() != 2) return false;
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var region = regions.get(measure);
        float gap = (region.bottom() - region.top()) * ink.height / 8.2f;
        if (gap < 12 || gap > 24) return false;
        var chords = new ArrayList<Dyad>(12);
        var columns = new ClosedFourDyadBeam.Head[12];
        int dotted = -1;
        int clef = heads.get(0).note.clefBottomDiatonic();
        for (int i = 0; i < columns.length; i++) {
            var a = heads.get(i * 2);
            var b = heads.get(i * 2 + 1);
            var chord = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
            if (!sameShaftDyadGeometry(a, b, gap) || chord.top.note.staffStep() - chord.bottom.note.staffStep() != 2
                    || a.note.augmentationDots() != b.note.augmentationDots()) return false;
            for (var head : List.of(a, b)) {
                var n = head.note;
                if (!ordinaryGeometry(n, clef) || n.stemDirection() != -1 || n.beamCount() != 2
                        || n.tupletDivisor() != 1 || n.tupletNormalNotes() != 1
                        || n.unbeamedDurationBeats() != 0 || n.augmentationDots() > 1
                        || n.harmonicSemitones() != 0 || n.openStringTouch() != null
                        || (n.articulations() & NoteOrnament.GRACE) != 0) return false;
            }
            if (a.note.augmentationDots() == 1) {
                if (dotted >= 0 || i % 4 == 3) return false;
                dotted = i;
            }
            chords.add(chord);
            columns[i] = new ClosedFourDyadBeam.Head((a.x + b.x) * .5f, chord.top.y, chord.bottom.y);
        }
        if (dotted < 0 || dotted + 1 >= columns.length) return false;
        var before = chords.get(dotted);
        var following = chords.get(dotted + 1);
        if (following.top.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_SHARP
                || following.bottom.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY)
            return false;
        var rows = UnnumberedTripletInk.sourceRows(region, regions, ink.height);
        for (var plane : List.of(ink, original)) {
            if (!naturalStackOwnsDots(plane, before, following, gap)
                    || !printedFourRailGroups(plane, regions, measure, chords, columns, List.of(),
                            gap, rows, -1, false, true)) return false;
        }
        result.set(before.top.owner, withoutDot(before.top.note));
        result.set(before.bottom.owner, withoutDot(before.bottom.note));
        if (!closes(heads, result, 3)) {
            result.set(before.top.owner, before.top.note);
            result.set(before.bottom.owner, before.bottom.note);
            return false;
        }
        var natural = following.bottom.note.withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_NATURAL);
        result.set(following.bottom.owner, natural);
        printedAccidental.accept(new SourceLocalAccidental.ProvedMark(natural, false));
        restoreStaggeredThirdFlats(ink, original, chords, gap, result, printedAccidental);
        restoreClosingThirdAccidentals(ink, original, chords, gap, result, printedAccidental);
        return true;
    }

    private static void restoreStaggeredThirdFlats(
            Ink ink, Ink original, List<Dyad> chords, float gap, List<ScoreNoteEvent> result,
            Consumer<SourceLocalAccidental.ProvedMark> printedAccidental) {
        for (int i = 1; i < chords.size(); i++) {
            var chord = chords.get(i);
            if (chord.top.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT
                    || chord.bottom.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY)
                continue;
            int upper = Math.round(chord.top.x - 1.58f * gap);
            int lower = Math.round(chord.bottom.x - 2.58f * gap);
            if (lower - chords.get(i - 1).bottom.x < .9f * gap) continue;
            boolean proved = true;
            for (var pixels : List.of(ink, original)) {
                if (!BeamedFlatBarline.flatAt(pixels.raw, pixels.width, pixels.height, upper, chord.top.y, gap)
                        || !BeamedFlatBarline.flatAt(
                                pixels.raw, pixels.width, pixels.height, lower, chord.bottom.y, gap)) {
                    proved = false;
                    break;
                }
            }
            if (!proved) continue;
            var note = chord.bottom.note.withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_FLAT);
            result.set(chord.bottom.owner, note);
            printedAccidental.accept(new SourceLocalAccidental.ProvedMark(note, false));
        }
    }

    private static void restoreClosingThirdAccidentals(
            Ink ink, Ink original, List<Dyad> chords, float gap, List<ScoreNoteEvent> result,
            Consumer<SourceLocalAccidental.ProvedMark> printedAccidental) {
        var closing = chords.get(chords.size() - 1);
        float space = closing.bottom.x - chords.get(chords.size() - 2).bottom.x;
        if (space < 4.6f * gap || space > 5.3f * gap
                || closing.bottom.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                || closing.top.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) return;
        for (var pixels : List.of(ink, original)) {
            var lower = closing.bottom;
            var upper = closing.top;
            if (!OmrScoreInterpreter.provesBoundedNaturalInk(pixels.raw, pixels.width, pixels.height,
                        lower.x, lower.y, gap, Math.round(lower.x - 3.75f * gap),
                        Math.round(lower.x - 3.02f * gap))
                    || !OmrScoreInterpreter.provesBoundedNaturalInk(pixels.raw, pixels.width, pixels.height,
                        upper.x, upper.y, gap, Math.round(upper.x - 2.72f * gap),
                        Math.round(upper.x - 1.98f * gap))
                    || !OmrScoreInterpreter.provesBoundedSharpInk(pixels.raw, pixels.width, pixels.height,
                        upper.x, upper.y, gap, Math.round(upper.x - 1.68f * gap),
                        Math.round(upper.x - .76f * gap))) return;
        }
        var natural = closing.bottom.note.withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_NATURAL);
        var sharp = closing.top.note.withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_SHARP);
        result.set(closing.bottom.owner, natural);
        result.set(closing.top.owner, sharp);
        printedAccidental.accept(new SourceLocalAccidental.ProvedMark(natural, false));
        printedAccidental.accept(new SourceLocalAccidental.ProvedMark(sharp, true));
    }

    private static boolean naturalStackOwnsDots(Ink ink, Dyad before, Dyad after, float gap) {
        float x = (before.top.x + before.bottom.x) * .5f;
        float next = (after.top.x + after.bottom.x) * .5f;
        if (next - x < 4.5f * gap || next - x > 5.45f * gap) return false;
        int left = Math.round(x + .86f * gap);
        int split = Math.round(x + 2.14f * gap);
        int literalEnd = Math.round(x + 3.20f * gap);
        int dotLeft = Math.round(x + .68f * gap);
        int dotRight = Math.round(x + 1.16f * gap);
        if (dotLeft < 3 || literalEnd >= ink.width - 3) return false;
        for (var head : List.of(before.top, before.bottom)) {
            int top = Math.round(head.y - .68f * gap), bottom = Math.round(head.y + .31f * gap);
            if (top < 2 || bottom + 2 >= ink.height
                    || !ClosedDyadsBeforeHalf.noSeparateDot(ink.raw, ink.width, dotLeft, dotRight, top, bottom))
                return false;
        }
        float estimatedBottom = after.bottom.y + after.bottom.note.staffStep() * gap * .5f;
        // The packed accidental stack interrupts the staff rules. Measure the exposed
        // rules on both sides and require agreement before masking the first line.
        var leftRules = localRules(ink, x - 3.2f * gap, estimatedBottom, gap);
        var rightRules = localRules(ink, next + 4.4f * gap, estimatedBottom, gap);
        if (leftRules == null || rightRules == null
                || Math.abs(leftRules[0] - rightRules[0]) > .25f * gap
                || Math.abs(leftRules[1] - rightRules[1]) > .08f * gap) return false;
        int first = Math.round(rightRules[0] - 4 * rightRules[1]);
        // Allow one pixel for the upper edge of the antialiased staff rule.
        for (int line = first - 1; line <= first; line++) {
            if (OmrScoreInterpreter.provesNaturalMeterInk(ink.raw, ink.width, ink.height,
                        left, split - 3, after.bottom.y, gap, line)
                    && OmrScoreInterpreter.provesNaturalMeterInk(ink.raw, ink.width, ink.height,
                        split, literalEnd, after.top.y, gap, line)) return true;
        }
        return false;
    }

    /** Both dotted heads must borrow ink from the next octave's printed crosses. */
    private static boolean clearDoubleSharpAliasDots(
            Ink ink, Ink original, MeasureRegion region, List<Head> source,
            List<ScoreNoteEvent> result, int beats) {
        if (source.stream().filter(head -> head.note.augmentationDots() == 1).count() != 2)
            return false;
        float gap = (region.bottom() - region.top()) * ink.height / 8.2f;
        if (gap < 12 || gap > 24) return false;
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var pairs = new ArrayList<Dyad>(heads.size() / 2);
        int dotted = -1;
        for (int i = 0; i < heads.size(); i += 2) {
            var a = heads.get(i);
            var b = heads.get(i + 1);
            var pair = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
            if (b.x - a.x > gap * .22f || pair.top.note.staffStep() - pair.bottom.note.staffStep() != 7
                    || Math.abs(pair.bottom.y - pair.top.y - gap * 3.5f) > gap * .35f
                    || a.note.augmentationDots() != b.note.augmentationDots()
                    || a.note.stemDirection() != b.note.stemDirection()) return false;
            for (var h : List.of(a, b)) {
                var n = h.note;
                if (!ClosedSixteenthPairBeforeHalf.writtenHead(n, false)
                        || n.beamCount() != 2 || n.unbeamedDurationBeats() != 0
                        || n.harmonicSemitones() != 0 || n.openStringTouch() != null
                        || (n.articulations() & NoteOrnament.GRACE) != 0) return false;
            }
            if (a.note.augmentationDots() > 0) {
                if (dotted >= 0 || a.note.augmentationDots() != 1) return false;
                dotted = pairs.size();
            }
            pairs.add(pair);
        }
        if (dotted < 0 || dotted + 1 >= pairs.size()) return false;
        var before = pairs.get(dotted);
        var next = pairs.get(dotted + 1);
        if (!doubleSharpOwnsOctaveDot(ink, before.top, next.top, gap)
                || !doubleSharpOwnsOctaveDot(ink, before.bottom, next.bottom, gap)
                || !doubleSharpOwnsOctaveDot(original, before.top, next.top, gap)
                || !doubleSharpOwnsOctaveDot(original, before.bottom, next.bottom, gap)) return false;
        result.set(before.top.owner, withoutDot(before.top.note));
        result.set(before.bottom.owner, withoutDot(before.bottom.note));
        if (closes(heads, result, beats)) return true;
        result.set(before.top.owner, before.top.note);
        result.set(before.bottom.owner, before.bottom.note);
        return false;
    }

    private static boolean doubleSharpOwnsOctaveDot(Ink ink, Head before, Head next, float gap) {
        int accidental = next.note.writtenAccidental();
        if (accidental != ScoreNoteEvent.ACCIDENTAL_DOUBLE_SHARP
                && (accidental != ScoreNoteEvent.ACCIDENTAL_SHARP || !next.note.tiedFromPrevious()
                    || before.note.writtenAccidental() != accidental
                    || before.note.staffStep() != next.note.staffStep())) return false;
        return ClosedDyadsBeforeHalf.sharpOwnsDotGap(
                ink.raw, ink.width, ink.height, gap, before.x, before.y, next.x, next.y);
    }

    /** Keep the existing source identities and replace groups owned entirely by this closed bar. */
    /** A restored double rail can be enclosed by staff ink and an accidental near the shaft. */
    private static boolean restorePrintedOctaveRails(Ink ink, Ink raw, List<MeasureRegion> bars,
            int measure, List<Head> source, List<ScoreNoteEvent> result) {
        if (measure < 1) return false;
        var bar = bars.get(measure);
        var prior = bars.get(measure - 1);
        float gap = (bar.bottom() - bar.top()) * ink.height() / 8.2f;
        float span = (bar.right() - bar.left()) * ink.width();
        if (gap < 12 || gap > 24 || span < 36 * gap || span > 47 * gap
                || Math.abs(bar.top() - prior.top()) * ink.height() > .25f * gap) return false;
        var row = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var pairs = new ArrayList<Dyad>(16);
        int eighths = 0, thirtySeconds = 0;
        for (int i = 0; i < row.size(); i += 2) {
            var a = row.get(i);
            var b = row.get(i + 1);
            var pair = a.y() < b.y() ? new Dyad(a, b) : new Dyad(b, a);
            var upper = pair.top().note();
            var lower = pair.bottom().note();
            int beams = upper.beamCount();
            if (!octaveShaftPair(pair, gap)
                    || beams < 1 || beams > 3 || lower.beamCount() != beams
                    || lower.stemDirection() != 0 && lower.stemDirection() != -1
                    || !ordinaryRailGeometry(upper, ScoreNoteEvent.CLEF_TREBLE, -1, false)
                    || !ordinaryRailGeometry(lower.withStemDirection(-1), ScoreNoteEvent.CLEF_TREBLE, -1, false))
                return false;
            if (beams == 1) eighths++;
            if (beams == 3) thirtySeconds++;
            pairs.add(pair);
        }
        if (eighths != 2 || thirtySeconds != 2 || pairs.get(pairs.size() - 1).top().note().beamCount() != 1) return false;
        var standard = octaveRailShafts(ink, pairs, gap, null);
        if (standard.isEmpty()) return false;
        var original = octaveRailShafts(raw, pairs, gap, standard);
        if (original.isEmpty()
                || !closedOctaveRailBridges(ink, bar, prior, pairs, standard, gap)
                || !closedOctaveRailBridges(raw, bar, prior, pairs, original, gap)) return false;
        for (var pair : pairs)
            for (var head : List.of(pair.top(), pair.bottom())) {
                var note = head.note();
                if (note.beamCount() != 2 || note.stemDirection() == 0)
                    result.set(head.owner(), asSharedBeam(note, 2).withStemDirection(-1));
            }
        return true;
    }

    private static List<PrintedBeamShafts.Shaft> octaveRailShafts(
            Ink ink, List<Dyad> pairs, float gap, List<PrintedBeamShafts.Shaft> recovered) {
        var budget = new PrintedBeamShafts.Budget(400_000);
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(pairs.size());
        boolean usedRawSupport = false;
        for (int i = 0; i < pairs.size(); i++) {
            var pair = pairs.get(i);
            var top = octaveFacingShafts(ink, pair.top(), gap, budget);
            var bottom = octaveFacingShafts(ink, pair.bottom(), gap, budget);
            if (budget.refused()) return List.of();
            if (top.size() != 1 || bottom.size() != 1) {
                if (recovered == null || usedRawSupport || !hasAccidental(pair)) return List.of();
                var axis = recovered.get(i);
                if (!rawOctaveShaftSupport(ink, pair, axis, gap)) return List.of();
                shafts.add(axis);
                usedRawSupport = true;
                continue;
            }
            var a = top.get(0);
            var b = bottom.get(0);
            if (a.left() > b.right() || b.left() > a.right()
                    || Math.abs(a.endY() - b.endY()) > gap * .20f) return List.of();
            shafts.add(b);
        }
        return shafts;
    }

    private static List<PrintedBeamShafts.Shaft> octaveFacingShafts(
            Ink ink, Head note, float gap, PrintedBeamShafts.Budget budget) {
        return PrintedBeamShafts.at(ink.raw(), ink.width(), ink.height(), note.x(), note.y(), gap, budget)
                .stream().filter(s -> s.direction() == 1).toList();
    }

    private static boolean rawOctaveShaftSupport(
            Ink ink, Dyad pair, PrintedBeamShafts.Shaft support, float gap) {
        var hi = pair.top();
        var lo = pair.bottom();
        if (!ArtificialHarmonics.filledQuarterCore(ink.raw(), ink.width(), ink.height(), hi.x(), hi.y(), gap)
                || !ArtificialHarmonics.filledQuarterCore(ink.raw(), ink.width(), ink.height(), lo.x(), lo.y(), gap)
                || support.endY() - lo.y() < gap * 2.4f) return false;
        int first = Math.round(hi.y() + .28f * gap);
        int last = support.endY() - Math.round(.12f * gap);
        for (int x = support.left(); x <= support.right(); x++) {
            int holes = 0;
            for (int y = first; y <= last; y++)
                if (!ink.dark(x, y)) holes++;
            if (holes <= 3) return true;
        }
        return false;
    }

    private static boolean closedOctaveRailBridges(Ink ink, MeasureRegion bar, MeasureRegion prior,
            List<Dyad> pairs, List<PrintedBeamShafts.Shaft> axes, float gap) {
        float expected = bar.bottom() * ink.height() - 2.1f * gap;
        var f = DottedDyadMeasureSeparator.localRails(ink.raw(), ink.width(), ink.height(),
                Math.round(prior.right() * ink.width() - .25f * gap), expected, gap);
        if (f == null || Math.abs(f[1] - gap) > gap * .12f) return false;
        var staff = new PlayingTechniqueDetector.Staff(f[0] - 4 * f[1], f[0], f[1], 0, 1);
        int left = PrintedCadenceDurations.boundaryColumn(ink.raw(), ink.width(), staff,
                (prior.right() + bar.left()) * ink.width() * .5f, true, false);
        int right = PrintedCadenceDurations.boundaryColumn(ink.raw(), ink.width(), staff,
                bar.right() * ink.width(), true, false);
        if (left < 0 || right < 0 || pairs.get(0).bottom().x() - left < gap * 1.55f
                || right - pairs.get(pairs.size() - 1).bottom().x() < gap * 1.05f) return false;
        var rules = StaffPitchTrack.linear(ink.width(), expected, gap, 0);
        for (int i = 1; i < axes.size(); i++) {
            var a = axes.get(i - 1);
            var b = axes.get(i);
            boolean match = i % 4 == 0
                    ? WrappedEighthTriplets.matchesBeamRails(ink.raw(), ink.width(), ink.height(), a, b, gap, 0)
                    : WrappedEighthTriplets.matchesExactlyTwoBeamRails(
                            ink.raw(), ink.width(), ink.height(), a, b, gap, rules);
            if (!match) return false;
        }
        return true;
    }

    /** A physical second rail reaching both heads' shaft closes the middle quartet of wide tenths. */
    private static boolean restoreMixedTenthRail(
            Ink ink, Ink original, MeasureRegion bar, List<Head> row, List<ScoreNoteEvent> result) {
        float gap = (bar.bottom() - bar.top()) * ink.height() / 8.2f;
        if (gap < 12 || gap > 24 || (bar.right() - bar.left()) * ink.width() < 39 * gap) return false;
        var pairs = new ArrayList<Dyad>(14);
        for (int i = 0; i < 14; i++) {
            var a = row.get(2 * i);
            var b = row.get(2 * i + 1);
            var pair = a.y() < b.y() ? new Dyad(a, b) : new Dyad(b, a);
            if (b.x() - a.x() > .18f * gap
                    || pair.top().note().staffStep() - pair.bottom().note().staffStep() != 9
                    || pair.bottom().y() - pair.top().y() < 4.2f * gap
                    || pair.bottom().y() - pair.top().y() > 4.8f * gap) return false;
            int recognized = i == 0 || i == 6 || i == 7 ? 1 : 2;
            for (var h : List.of(a, b))
                if (h.note().beamCount() != recognized
                        || !ordinaryRailGeometry(h.note(), ScoreNoteEvent.CLEF_TREBLE, -1, false)) return false;
            pairs.add(pair);
        }
        if (!mixedTenthRailGroups(ink, pairs, gap) || !mixedTenthRailGroups(original, pairs, gap)) return false;
        for (var h : List.of(pairs.get(6).top(), pairs.get(6).bottom()))
            result.set(h.owner(), asSharedBeam(h.note(), 2));
        return true;
    }

    private static boolean mixedTenthRailGroups(Ink img, List<Dyad> pairs, float gap) {
        var budget = new PrintedBeamShafts.Budget(240_000);
        PrintedBeamShafts.Shaft previous = null;
        int[] links = {1, 2, 0, 2, 2, 2, 0, 1, 2, 0, 2, 2, 2};
        for (int i = 0; i < pairs.size(); i++) {
            var pair = pairs.get(i);
            var a = PrintedBeamShafts.at(img.raw(), img.width(), img.height(),
                    pair.top().x(), pair.top().y(), gap, budget);
            var b = PrintedBeamShafts.at(img.raw(), img.width(), img.height(),
                    pair.bottom().x(), pair.bottom().y(), gap, budget);
            if (budget.refused() || a.size() != 1 || b.size() != 1) return false;
            var upper = a.get(0);
            var lower = b.get(0);
            if (upper.direction() != 1 || lower.direction() != 1
                    || upper.columns().stream().filter(column -> column.endY() >= lower.endY() - .45f * gap)
                    .noneMatch(column -> lower.columns().contains(column))) return false;
            if (previous != null && !WrappedEighthTriplets.matchesBeamRails(
                    img.raw(), img.width(), img.height(), previous, lower, gap, links[i - 1])) return false;
            previous = lower;
        }
        return true;
    }

    static ScoreBeamSource replaceMonophonicDoubleRails(
            ScoreBeamSource source, int physicalPage, List<Head> heads) {
        return replaceDoubleRails(source, physicalPage, heads, 1);
    }

    static ScoreBeamSource replaceDyadDoubleRails(
            ScoreBeamSource source, int physicalPage, List<Head> heads) {
        return replaceDoubleRails(source, physicalPage, heads, 2);
    }

    private static ScoreBeamSource replaceDoubleRails(
            ScoreBeamSource source, int physicalPage, List<Head> heads, int headsPerColumn) {
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
        var completed = projectDoubleRails(remainder, physicalPage, heads, headsPerColumn);
        return completed == remainder ? source : completed;
    }

    static ScoreBeamSource projectDoubleRails(
            ScoreBeamSource source, int physicalPage, List<Head> sourceHeads) {
        return projectDoubleRails(source, physicalPage, sourceHeads, 2);
    }

    private static ScoreBeamSource projectDoubleRails(
            ScoreBeamSource source, int physicalPage, List<Head> sourceHeads, int headsPerColumn) {
        var bridges = java.util.Collections.nCopies(sourceHeads.size() / (4 * headsPerColumn), List.of(3, 3, 3));
        return projectRailGroups(source, physicalPage, sourceHeads, headsPerColumn, bridges);
    }

    private static boolean hasOpeningUpstemTriplet(List<Head> sourceHeads) {
        var heads = sourceHeads.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        for (int i = 0; i < heads.size(); i++) {
            var n = heads.get(i).note();
            if (i < 6 ? n.tupletDivisor() != 3 || n.stemDirection() != 1 : n.tupletDivisor() != 1)
                return false;
            if (i % 2 == 1 && Math.abs(n.staffStep() - heads.get(i - 1).note().staffStep()) != 2)
                return false;
        }
        return true;
    }

    static ScoreBeamSource completeProvedDyadQuartets(
            ScoreBeamSource source, int page, List<Head> sourceHeads) {
        if (sourceHeads.size() != 32) return source;
        var owners = new HashSet<ScoreBeamSource.Anchor>();
        for (var h : sourceHeads) {
            var anchor = source.noteOrigins().get(h.owner()).orElse(null);
            if (anchor != null) owners.add(anchor);
        }
        var retained = new ArrayList<ScoreBeamSource.Group>();
        var fragments = new ArrayList<ScoreBeamSource.Group>();
        for (var group : source.groups()) {
            var members = group.columns().stream().flatMap(c -> c.members().stream()).toList();
            if (members.stream().noneMatch(owners::contains)) retained.add(group);
            else {
                if (!owners.containsAll(members) || group.tupletMark() != null || !group.hooks().isEmpty()
                        || !group.bridges().stream().allMatch(level -> level == 3)) return source;
                fragments.add(group);
            }
        }
        if (fragments.isEmpty() || source.detachedColumns().stream()
                .flatMap(c -> c.members().stream()).anyMatch(owners::contains)) return source;
        var cleared = new ScoreBeamSource(retained, source.detachedColumns(), source.noteOrigins());
        var projected = projectDoubleRails(cleared, page, sourceHeads);
        if (projected == cleared) return source;
        var newGroups = projected.groups().subList(retained.size(), projected.groups().size());
        for (var fragment : fragments) {
            var columns = fragment.columns().stream().map(c -> Set.copyOf(c.members())).toList();
            boolean aligned = newGroups.stream().anyMatch(group -> java.util.Collections.indexOfSubList(
                    group.columns().stream().map(c -> Set.copyOf(c.members())).toList(), columns) >= 0);
            if (!aligned) return source;
        }
        return projected;
    }

    private static ScoreBeamSource projectRailGroups(
            ScoreBeamSource source, int physicalPage, List<Head> sourceHeads, int headsPerColumn,
            List<List<Integer>> railGroups) {
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
        int offset = 0;
        for (var bridges : railGroups) {
            int size = bridges.size() + 1;
            var columns = new ArrayList<ScoreBeamSource.Column>(size);
            int firstOwner = Integer.MAX_VALUE;
            for (int slot = offset; slot < offset + size; slot++) {
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
            groups.add(new ScoreBeamSource.Group(id, columns, bridges, List.of()));
            offset += size;
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
        return applyClosedMonophonicThreeFour(score, gray, raw, width, height, physicalPage, List.of());
    }

    static ScorePageInterpretation applyClosedMonophonicThreeFour(
            ScorePageInterpretation score, byte[] gray, byte[] raw, int width, int height,
            int physicalPage, List<SheetInterpreter.Word> words) {
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
                    && (restoreClosedMonophonicThreeFour(normalized, original, regions, m, bar, edits)
                            || closeShaftFingeredQuartet(normalized, original, regions, m, bar, edits, words)
                            || closeMixedStemFingeredQuartets(normalized, original, regions, m, bar, edits, words));
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

    private static boolean closeShaftFingeredQuartet(
            Ink normalized, Ink original, List<MeasureRegion> regions, int measure, List<Head> source,
            List<ScoreNoteEvent> edits, List<SheetInterpreter.Word> words) {
        if (words.isEmpty() || measure < 1 || measure + 1 >= regions.size()) return false;
        var bar = regions.get(measure);
        float gap = (bar.bottom() - bar.top()) * normalized.height / 8.2f;
        if (gap < 10 || gap > 24) return false;
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        int clef = heads.get(0).note.clefBottomDiatonic();
        for (int i = 0; i < heads.size(); i++) {
            var head = heads.get(i);
            if (!ordinaryDoubleRail(head.note, clef, -1, i >= 8 && i <= 10)) return false;
            if (i > 0 && (head.x - heads.get(i - 1).x < 1.7f * gap
                    || head.x - heads.get(i - 1).x > 3.8f * gap)) return false;
        }
        if (!shaftFingeredQuartet(normalized, regions, measure, heads, gap, words)
                || !shaftFingeredQuartet(original, regions, measure, heads, gap, words)) return false;
        for (var head : heads.subList(8, 11)) edits.set(head.owner, head.note.withTupletRatio(1, 1));
        return true;
    }

    private static boolean shaftFingeredQuartet(
            Ink plane, List<MeasureRegion> regions, int measure, List<Head> heads, float gap,
            List<SheetInterpreter.Word> words) {
        var bar = regions.get(measure);
        var prior = regions.get(measure - 1);
        if (!WrappedMeasureBoundaries.confirmsSampledClosingEdge(
                        plane.raw, plane.width, plane.height, prior, prior.right() * plane.width - 1.9f * gap)
                || !WrappedMeasureBoundaries.confirmsSampledClosingEdge(
                        plane.raw, plane.width, plane.height, bar, heads.get(heads.size() - 1).x)) return false;
        var budget = new PrintedBeamShafts.Budget(250_000);
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>();
        for (var head : heads) {
            var owners = PrintedBeamShafts.at(plane.raw, plane.width, plane.height, head.x, head.y, gap, budget);
            if (owners.size() != 1 || owners.get(0).direction() != 1 || budget.refused()) return false;
            var shaft = owners.get(0);
            int i = shafts.size();
            if (i > 0 && !WrappedEighthTriplets.matchesBeamRails(plane.raw, plane.width, plane.height,
                    shafts.get(shafts.size() - 1), shaft, gap, i % 4 == 0 ? 0 : 2)) return false;
            shafts.add(shaft);
        }
        String digits = "04321";
        for (int i = 7; i < 12; i++) {
            if (!digitUnderShaft(plane, shafts.get(i), gap, digits.substring(i - 7, i - 6), words)) return false;
        }
        var guide = PrintedDirectionStaff.printedRules(plane.raw, plane.width, plane.height,
                heads.get(heads.size() - 1).x, bar.bottom() * plane.height - 2.1f * gap, gap);
        if (guide == null) return false;
        var track = StaffPitchTrack.fromVerifiedSamples(List.of(
                new float[] {heads.get(8).x, guide[0], guide[1]},
                new float[] {heads.get(heads.size() - 1).x, guide[0], guide[1]}));
        return !UnnumberedTripletInk.bracketBesideBeam(
                plane.raw, plane.width, plane.height, gap, heads.get(8).x, heads.get(10).x,
                heads.get(8).y, heads.get(10).y, shafts.get(10).endY(),
                UnnumberedTripletInk.sourceRows(bar, regions, plane.height), track, true);
    }

    /** Two claimed triplets both have fingering on otherwise straight quartets. */
    private static boolean closeMixedStemFingeredQuartets(
            Ink normalized, Ink original, List<MeasureRegion> regions, int measure,
            List<Head> row, List<ScoreNoteEvent> result, List<SheetInterpreter.Word> words) {
        if (words.isEmpty()) return false;
        var bar = regions.get(measure);
        float gap = (bar.bottom() - bar.top()) * normalized.height / 8.2f;
        if (gap < 10 || gap > 26) return false;
        var heads = row.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        int clef = heads.get(0).note.clefBottomDiatonic();
        int claims = 0;
        for (int i = 0; i < heads.size(); i++) {
            var head = heads.get(i);
            boolean claim = head.note.tupletDivisor() == 3;
            if (!ordinaryDoubleRail(head.note, clef, i < 8 ? -1 : 1, claim)) return false;
            if (claim) claims |= 1 << i;
            if (i > 0) {
                float spacing = head.x - heads.get(i - 1).x;
                if (spacing < 1.6f * gap || spacing > 3.65f * gap) return false;
            }
        }
        if (Integer.bitCount(claims) != 6 || !hasContainedTripletClaims(claims)) return false;
        if (!mixedStemFingeringRails(normalized, regions, measure, heads, words, gap, claims)
                || !mixedStemFingeringRails(original, regions, measure, heads, words, gap, claims))
            return false;
        for (int i = 0; i < 8; i++) {
            if ((claims & 1 << i) == 0) continue;
            var head = heads.get(i);
            result.set(head.owner, head.note.withTupletRatio(1, 1));
        }
        return true;
    }

    private static boolean mixedStemFingeringRails(
            Ink ink, List<MeasureRegion> regions, int measure, List<Head> heads,
            List<SheetInterpreter.Word> words, float gap, int claims) {
        var bar = regions.get(measure);
        float floor = bar.bottom() * ink.height - 2.1f * gap;
        var track = StaffPitchTrack.forPrintedSlurs(
                ink.raw, ink.width, ink.height, floor - 4 * gap, floor, gap);
        if (track == null || !WrappedMeasureBoundaries.confirmsClosedWithVerifiedStaff(
                    ink.raw, ink.width, ink.height, bar, heads.get(0).x, heads.get(heads.size() - 1).x, track))
            return false;
        var budget = new PrintedBeamShafts.Budget(280_000);
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(heads.size());
        for (int i = 0; i < heads.size(); i++) {
            var head = heads.get(i);
            var candidates = PrintedBeamShafts.at(ink.raw, ink.width, ink.height, head.x, head.y, gap, budget);
            if (budget.refused() || candidates.size() != 1
                    || candidates.get(0).direction() != -head.note.stemDirection()) return false;
            var shaft = candidates.get(0);
            if (i > 0) {
                var previous = shafts.get(shafts.size() - 1);
                if (i % 4 == 0
                        ? !WrappedEighthTriplets.cutBetweenRecoveredGroups(
                                ink.raw, ink.width, ink.height, bar, previous, shaft, gap)
                        : !WrappedEighthTriplets.matchesExactlyTwoBeamRails(
                                ink.raw, ink.width, ink.height, previous, shaft, gap, track)) return false;
            }
            shafts.add(shaft);
        }
        var rows = UnnumberedTripletInk.sourceRows(bar, regions, ink.height);
        for (int group = 0; group < 8; group += 4) {
            int first = group + Integer.numberOfTrailingZeros(claims >> group & 0xf);
            int middle = first + 1;
            if (!digitUnderShaft(ink, shafts.get(middle), gap, "3", words)) return false;
            boolean anotherFinger = false;
            for (int i = group; i < group + 4 && !anotherFinger; i++) {
                if (i == middle) continue;
                var shaft = shafts.get(i);
                for (String digit : List.of("1", "2", "3", "4"))
                    if (digitUnderShaft(ink, shaft, gap, digit, words)
                            || digitBelowBeam(ink, heads.get(i).x, shaft.endY(), gap, digit, words)) {
                        anotherFinger = true;
                        break;
                    }
            }
            var triple = heads.subList(first, first + 3);
            float top = (float) triple.stream().mapToDouble(Head::y).min().orElseThrow();
            float bottom = (float) triple.stream().mapToDouble(Head::y).max().orElseThrow();
            int tip = shafts.get(first + 2).endY();
            var above = new UnnumberedTripletInk.Rows(rows.first(), Math.min(rows.last(), Math.round(top - .42f * gap)));
            if (!anotherFinger || UnnumberedTripletInk.bracketBesideBeam(
                        ink.raw, ink.width, ink.height, gap, triple.get(0).x, triple.get(triple.size() - 1).x,
                        top, bottom, tip, rows, track, true)
                    || centralTripletNumeral(ink, heads.get(middle).x, top, tip, gap, above)) return false;
        }
        return true;
    }


    private static boolean digitUnderShaft(Ink plane, PrintedBeamShafts.Shaft shaft, float gap,
            String value, List<SheetInterpreter.Word> words) {
        return digitBelowBeam(plane, (shaft.left() + shaft.right()) * .5f, shaft.endY(), gap, value, words);
    }

    private static boolean digitBelowBeam(Ink plane, float x, int tip, float gap, String value,
            List<SheetInterpreter.Word> words) {
        int left = Math.round(x - gap), right = Math.round(x + gap);
        int top = tip + 1, bottom = tip + Math.round(2.5f * gap);
        if (left < 0 || right >= plane.width || top < 0 || bottom >= plane.height) return false;
        var wordsHere = words.stream().filter(w -> value.equals(w.text().strip())
                && Math.abs((w.left() + w.right()) * plane.width * .5f - x) < .35f * gap
                && w.top() * plane.height >= top - .22f * gap && w.bottom() * plane.height < bottom).toList();
        if (wordsHere.size() != 1) return false;
        var word = wordsHere.get(0);
        var glyphs = RawNumeralComponents.capture(plane.raw, plane.width, plane.height, left, top, right, bottom,
                165, () -> {}, () -> {}).components().stream().filter(g -> g.size() > .11f * gap * gap).toList();
        if (glyphs.size() != 1) return false;
        var glyph = glyphs.get(0);
        return glyph.left > word.left() * plane.width && glyph.right < word.right() * plane.width
                && glyph.top > Math.max(top, word.top() * plane.height) && glyph.bottom < word.bottom() * plane.height
                && glyph.bottom - glyph.top >= .85f * gap && glyph.bottom - glyph.top <= 1.38f * gap;
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

    /** A fragmented upper ledger can lend one apparent dot to both notes of a wide dyad. */
    private static boolean clearOpeningLedgerAliasDots(Ink ink, Ink original,
            List<MeasureRegion> regions, int measure, List<Head> source, List<ScoreNoteEvent> result) {
        var bar = regions.get(measure);
        float gap = (bar.bottom() - bar.top()) * ink.height() / 8.2f;
        if (gap < 12 || gap > 24 || measure == 0
                || (bar.top() - regions.get(measure - 1).bottom()) * ink.height() < gap * 3
                || source.stream().filter(h -> h.note().augmentationDots() == 1).count() != 2)
            return false;
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var row = new ArrayList<Dyad>(12);
        int dotted = -1;
        for (int i = 0; i < heads.size(); i += 2) {
            var a = heads.get(i);
            var b = heads.get(i + 1);
            var dyad = a.y() < b.y() ? new Dyad(a, b) : new Dyad(b, a);
            var up = dyad.top().note();
            var down = dyad.bottom().note();
            int interval = up.staffStep() - down.staffStep();
            int dots = up.augmentationDots();
            if (interval != 5 || dyad.bottom().y() - dyad.top().y() < gap * 2.20f
                    || !sameShaftDyadGeometry(a, b, gap) || dots != down.augmentationDots()
                    || dots > 1 || !ordinaryDoubleRail(dots == 0 ? up : withoutDot(up), ScoreNoteEvent.CLEF_TREBLE, -1, false)
                    || !ordinaryDoubleRail(dots == 0 ? down : withoutDot(down), ScoreNoteEvent.CLEF_TREBLE, -1, false))
                return false;
            if (dots == 1) dotted = i / 2;
            row.add(dyad);
        }
        if (dotted < 0 || dotted % 4 != 0) return false;
        var alias = row.get(dotted);
        var next = row.get(dotted + 1);
        for (var plane : List.of(ink, original)) {
            if (!ownsLedgerTail(plane, alias, next, gap) || !closedLedgerQuartets(plane, bar, row, dotted, gap))
                return false;
        }
        result.set(alias.top().owner(), withoutDot(alias.top().note()));
        result.set(alias.bottom().owner(), withoutDot(alias.bottom().note()));
        if (closes(heads, result, 3f)) return true;
        result.set(alias.top().owner(), alias.top().note());
        result.set(alias.bottom().owner(), alias.bottom().note());
        return false;
    }

    private static boolean ownsLedgerTail(Ink ink, Dyad dyad, Dyad next, float gap) {
        var upper = dyad.top();
        var lower = dyad.bottom();
        float limit = Math.min(next.top().x(), next.bottom().x()) - gap * .17f;
        int step = upper.note().staffStep();
        if (step < 10 || step % 2 != 0
                || LegacyTripletDotInk.singleDotCore(ink.raw(), ink.width(), ink.height(),
                        lower.x(), lower.y(), limit, gap)
                || WrappedTripletDotInk.uniqueCore(ink.raw(), ink.width(), ink.height(),
                        lower.x(), lower.y(), limit, gap) != null
                || WrappedTripletDotInk.uniqueCore(ink.raw(), ink.width(), ink.height(),
                        upper.x(), upper.y(), limit, gap) != null) return false;
        var core = LegacyTripletDotInk.uniqueDotCore(ink.raw(), ink.width(), ink.height(),
                upper.x(), upper.y(), limit, gap);
        if (core == null || core.left() - upper.x() < gap
                || core.right() - upper.x() > gap * 1.45f
                || Math.abs((core.top() + core.bottom()) * .5f - upper.y()) > gap * .16f) return false;

        // The dark island follows the head's own outward ledger through a narrow pale seam.
        int from = Math.round(upper.x() + gap * .58f);
        int linked = 0;
        for (int y = core.top(); y <= core.bottom(); y++) {
            int pale = 0;
            for (int x = from; x < core.left(); x++) {
                if (!ink.dark(x, y)) pale++;
                if ((ink.raw()[y * ink.width() + x] & 255) > 225) pale += 2;
            }
            if (pale <= 2 && ++linked == 2) return true;
        }
        return false;
    }

    private static boolean closedLedgerQuartets(Ink ink, MeasureRegion bar, List<Dyad> row, int dotted, float gap) {
        var track = WrappedMeasureBoundaries.verifiedTripletStaff(ink.raw(), ink.width(), ink.height(), bar, true);
        if (track == null || !ClosedDottedSixteenths.hasClosingRule(ink.raw(), ink.width(), ink.height(), bar))
            return false;
        var budget = new PrintedBeamShafts.Budget(350_000);
        PrintedBeamShafts.Shaft previous = null;
        for (int i = 0; i < row.size(); i++) {
            var head = row.get(i).bottom();
            var shafts = PrintedBeamShafts.at(ink.raw(), ink.width(), ink.height(), head.x(), head.y(), gap, budget);
            if (shafts.size() != 1 || shafts.get(0).direction() != 1) return false;
            var shaft = shafts.get(0);
            if ((i == dotted || i == dotted + 1)
                    && (OwnedTertiaryBeamlets.hasShortHook(ink.raw(), ink.width(), ink.height(), shaft, gap, -1)
                        || OwnedTertiaryBeamlets.hasShortHook(ink.raw(), ink.width(), ink.height(), shaft, gap, 1)))
                return false;
            if (previous != null && !(i % 4 == 0
                    ? WrappedEighthTriplets.cutBetweenRecoveredGroups(ink.raw(), ink.width(), ink.height(),
                            bar, previous, shaft, gap)
                    : WrappedEighthTriplets.matchesExactlyTwoBeamRails(ink.raw(), ink.width(), ink.height(),
                            previous, shaft, gap, track))) return false;
            previous = shaft;
        }
        return !budget.refused();
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

    /** A compact row of double-beamed thirds can inherit a spurious first-triplet claim. */
    private static boolean closeCompactOpeningThirdQuartets(Ink ink, Ink original, List<MeasureRegion> bars,
            int measure, List<Head> source, List<ScoreNoteEvent> result) {
        var bar = bars.get(measure);
        float gap = (bar.bottom() - bar.top()) * ink.height / 8.2f;
        if (!Float.isFinite(gap) || gap < 12 || gap > 24 || source.size() != 32) return false;
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var pairs = new ArrayList<Dyad>(16);
        var columns = new ClosedFourDyadBeam.Head[16];
        int clef = heads.get(0).note.clefBottomDiatonic();
        for (int i = 0; i < columns.length; i++) {
            var a = heads.get(2 * i);
            var b = heads.get(2 * i + 1);
            var pair = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
            int direction = i < 4 ? -1 : 1;
            if (!sameShaftDyadGeometry(a, b, gap)
                    || pair.top.note.staffStep() - pair.bottom.note.staffStep() != 2
                    || !ordinaryDoubleRail(a.note, clef, direction, i < 3)
                    || !ordinaryDoubleRail(b.note, clef, direction, i < 3)) return false;
            float x = (a.x + b.x) * .5f;
            if (i > 0) {
                float advance = (x - columns[i - 1].x()) / gap;
                if (advance < 1.40f || advance > 2.80f) return false;
            }
            columns[i] = new ClosedFourDyadBeam.Head(x, pair.top.y, pair.bottom.y);
            pairs.add(pair);
        }
        var rows = UnnumberedTripletInk.sourceRows(bar, bars, ink.height);
        for (var plane : List.of(ink, original))
            if (!printedFourRailGroups(plane, bars, measure, pairs, columns,
                    List.of(0), gap, rows, -1, true, false)) return false;
        for (var head : heads.subList(0, 6)) result.set(head.owner(), head.note().withTupletRatio(1, 1));
        return true;
    }

    /** Verify all four printed double beams before clearing false tuplets or restoring unowned beams. */
    private static boolean closeChromaticThirdsAndRecoverStaggeredFlats(
            Ink ink, Ink original, List<MeasureRegion> regions, int measure, List<Head> row,
            List<ScoreNoteEvent> result, List<ScoreKeyChange> keys, Consumer<SourceLocalAccidental.ProvedMark> printed) {
        var bar = regions.get(measure);
        float gap = (bar.bottom() - bar.top()) * ink.height / 8.2f;
        var heads = new ArrayList<>(row.stream().sorted(Comparator.comparingDouble(Head::x)).toList());
        var separated = separateCollapsedUpstemThirds(ink, original, bar, heads, gap);
        if (!separated.isEmpty()) {
            for (var moved : separated)
                heads.replaceAll(h -> h.owner == moved.owner ? moved : h);
            heads.sort(Comparator.comparingDouble(Head::x));
        }
        var repaired = repeatedTerminalLedgerSpace(ink, original, heads, gap);
        for (var moved : separated) {
            result.set(moved.owner, moved.note);
            row.replaceAll(h -> h.owner == moved.owner ? moved : h);
        }
        if (!separated.isEmpty()) {
            recoverCompressedThirdAccidentals(ink, original, heads, gap, result, printed);
            var untied = ScoreTiePitchGuard.recheckWithKeyContext(result, keys);
            heads.replaceAll(h -> new Head(h.owner, untied.get(h.owner), h.x, h.y));
            for (var h : heads) result.set(h.owner, h.note);
        }
        if (!closeAcrossPrintedBeams(ink, original, regions, measure, heads, result, false)) return false;
        if (repaired != null) result.set(repaired.owner, repaired.note);
        if (!separated.isEmpty()) retainLiteralChromaticThirdMarks(ink, original, heads, row, gap, printed);
        for (int i = 2; i < heads.size(); i += 2) {
            var a = heads.get(i);
            var b = heads.get(i + 1);
            var upper = a.y < b.y ? a : b;
            var lower = a.y < b.y ? b : a;
            if (upper.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                    || lower.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_NATURAL
                    || upper.note.stemDirection() != -1) continue;
            int upperSpine = Math.round(upper.x - 1.76f * gap);
            int lowerSpine = Math.round(lower.x - 2.90f * gap);
            if (lowerSpine - heads.get(i - 1).x < 1.2f * gap
                    || !pairedFlatInk(ink, upper, lower, upperSpine, lowerSpine, gap)
                    || !pairedFlatInk(original, upper, lower, upperSpine, lowerSpine, gap)) continue;
            for (var h : List.of(upper, lower)) {
                var flat = result.get(h.owner).withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_FLAT);
                result.set(h.owner, flat);
                printed.accept(new SourceLocalAccidental.ProvedMark(flat, false));
            }
        }
        return true;
    }

    /** In tight chromatic thirds, one accidental can reach across the adjoining head's aperture. */
    private static void recoverCompressedThirdAccidentals(
            Ink ink, Ink original, List<Head> heads, float gap, List<ScoreNoteEvent> result,
            Consumer<SourceLocalAccidental.ProvedMark> printed) {
        Head priorNatural = null;
        for (int i = 2; i < heads.size(); i += 2) {
            var pair = tallerFirst(heads.get(i), heads.get(i + 1));
            var left = tallerFirst(heads.get(i - 2), heads.get(i - 1));
            var upper = pair.top;
            var lower = pair.bottom;
            if (upper.note.staffStep() - lower.note.staffStep() != 2) continue;
            boolean repeats = upper.note.staffStep() == left.top.note.staffStep()
                    && lower.note.staffStep() == left.bottom.note.staffStep();
            int u = upper.note.writtenAccidental(), l = lower.note.writtenAccidental();
            if (repeats && (u == ScoreNoteEvent.ACCIDENTAL_FROM_KEY || u == ScoreNoteEvent.ACCIDENTAL_FLAT)
                    && l == ScoreNoteEvent.ACCIDENTAL_NATURAL
                    && upper.note.stemDirection() == -1
                    && pairedFlatInk(ink, upper, lower, Math.round(upper.x - 1.42f * gap),
                            Math.round(lower.x - 2.19f * gap), gap)
                    && pairedFlatInk(original, upper, lower, Math.round(upper.x - 1.42f * gap),
                            Math.round(lower.x - 2.19f * gap), gap)) {
                writeProvedAccidental(upper, ScoreNoteEvent.ACCIDENTAL_FLAT, result, printed);
                writeProvedAccidental(lower, ScoreNoteEvent.ACCIDENTAL_FLAT, result, printed);
            }
            if (repeats && upper.note.stemDirection() == 1 && u == ScoreNoteEvent.ACCIDENTAL_NATURAL
                    && l == ScoreNoteEvent.ACCIDENTAL_SHARP && left.bottom.note.writtenAccidental() == l
                    && provedNaturalAndFlat(ink, upper, lower, gap)
                    && provedNaturalAndFlat(original, upper, lower, gap)) {
                writeProvedAccidental(upper, ScoreNoteEvent.ACCIDENTAL_FLAT, result, printed);
                writeProvedAccidental(lower, ScoreNoteEvent.ACCIDENTAL_NATURAL, result, printed);
                priorNatural = lower;
            }
            if (priorNatural != null && upper.note.staffStep() == priorNatural.note.staffStep()
                    && upper.note.stemDirection() == 1 && u == ScoreNoteEvent.ACCIDENTAL_SHARP
                    && l == ScoreNoteEvent.ACCIDENTAL_NATURAL && !repeats
                    && provedLowerNaturalOnly(ink, upper, lower, gap)
                    && provedLowerNaturalOnly(original, upper, lower, gap)) {
                result.set(upper.owner, result.get(upper.owner).withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_FROM_KEY));
            }
            if (repeats && upper.note.stemDirection() == 1 && u == ScoreNoteEvent.ACCIDENTAL_SHARP
                    && l == ScoreNoteEvent.ACCIDENTAL_NATURAL && lower.note.tiedFromPrevious()
                    && provedCompressedFlatPair(ink, upper, lower, gap)
                    && provedCompressedFlatPair(original, upper, lower, gap)) {
                writeProvedAccidental(upper, ScoreNoteEvent.ACCIDENTAL_FLAT, result, printed);
                writeProvedAccidental(lower, ScoreNoteEvent.ACCIDENTAL_FLAT, result, printed);
            }
        }
    }

    /** Publish already sounding marks only when both planes establish the literal stroke. */
    private static void retainLiteralChromaticThirdMarks(Ink ink, Ink original, List<Head> heads, List<Head> prior,
            float gap, Consumer<SourceLocalAccidental.ProvedMark> printed) {
        var previous = new HashMap<Integer, Integer>();
        for (var h : prior) previous.put(h.owner, h.note.writtenAccidental());
        for (int i = 2; i < heads.size(); i += 2) {
            var pair = tallerFirst(heads.get(i), heads.get(i + 1));
            var upper = pair.top;
            var lower = pair.bottom;
            int u = upper.note.writtenAccidental(), l = lower.note.writtenAccidental();
            if (upper.note.staffStep() - lower.note.staffStep() != 2 || previous.get(lower.owner) != l) continue;
            if (u == ScoreNoteEvent.ACCIDENTAL_FROM_KEY && l == ScoreNoteEvent.ACCIDENTAL_NATURAL
                    && provesIsolatedLowerNatural(ink, lower, gap)
                    && provesIsolatedLowerNatural(original, lower, gap)) {
                printed.accept(new SourceLocalAccidental.ProvedMark(lower.note, false));
            } else if (u == ScoreNoteEvent.ACCIDENTAL_FLAT && l == ScoreNoteEvent.ACCIDENTAL_FLAT
                    && previous.get(upper.owner) == u) {
                int left = Math.round(lower.x - 2.24f * gap), right = Math.round(upper.x - 1.31f * gap);
                if (right - left < .75f * gap
                        || !pairedFlatInk(ink, upper, lower, right, left, gap)
                        || !pairedFlatInk(original, upper, lower, right, left, gap)) continue;
                printed.accept(new SourceLocalAccidental.ProvedMark(lower.note, false));
                printed.accept(new SourceLocalAccidental.ProvedMark(upper.note, false));
            } else if (u == ScoreNoteEvent.ACCIDENTAL_NATURAL && l == ScoreNoteEvent.ACCIDENTAL_SHARP
                    && previous.get(upper.owner) == u
                    && provesNaturalAboveSharp(ink, upper, lower, gap)
                    && provesNaturalAboveSharp(original, upper, lower, gap)) {
                printed.accept(new SourceLocalAccidental.ProvedMark(lower.note, false));
                printed.accept(new SourceLocalAccidental.ProvedMark(upper.note, false));
            }
        }
    }

    private static boolean provesIsolatedLowerNatural(Ink plane, Head lower, float gap) {
        int left = Math.round(lower.x - 1.55f * gap);
        return OmrScoreInterpreter.provesBoundedNaturalInk(plane.raw, plane.width, plane.height,
                lower.x, lower.y, gap, left, left + Math.round(.74f * gap));
    }

    private static boolean provesNaturalAboveSharp(Ink plane, Head upper, Head lower, float gap) {
        int sharp = Math.round(lower.x - 2.27f * gap), natural = Math.round(upper.x - 1.47f * gap);
        int span = Math.round(.74f * gap);
        return sharp + span <= natural
                && OmrScoreInterpreter.provesBoundedSharpInk(plane.raw, plane.width, plane.height,
                        lower.x, lower.y, gap, sharp, sharp + span)
                && OmrScoreInterpreter.provesBoundedNaturalInk(plane.raw, plane.width, plane.height,
                        upper.x, upper.y, gap, natural, natural + span);
    }

    private static ScoreSlurSource projectChromaticThirdTailSlur(
            Ink ink, Ink original, MeasureRegion bar, List<Head> row, List<ScoreNoteEvent> corrected,
            ScoreSlurSource source, int page) {
        var heads = row.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var a = tallerFirst(heads.get(20), heads.get(21)).bottom;
        var b = tallerFirst(heads.get(22), heads.get(23)).bottom;
        var target = corrected.get(b.owner);
        if (!b.note.tiedFromPrevious() || target.tiedFromPrevious()
                || a.note.stemDirection() != 1 || b.note.stemDirection() != 1
                || a.note.staffStep() != b.note.staffStep()
                || a.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_NATURAL
                || target.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT) return source;
        float gap = (bar.bottom() - bar.top()) * ink.height / 8.2f;
        if (gap < 10 || gap > 26 || b.x - a.x < 2.5f * gap || b.x - a.x > 3.4f * gap
                || Math.abs(a.y - b.y) > .16f * gap) return source;
        float[] raw = fitsShortBelowBow(original, a, b, gap);
        float[] normalized = fitsShortBelowBow(ink, a, b, gap);
        if (raw == null || !Arrays.equals(raw, normalized)) return source;
        var owners = new ArrayList<>(source.noteOrigins());
        var start = owners.get(a.owner).orElseGet(() -> new ScoreSlurPages.Anchor(page, a.owner));
        var end = owners.get(b.owner).orElseGet(() -> new ScoreSlurPages.Anchor(page, b.owner));
        if (start.pageIndex() != page || end.pageIndex() != page) return source;
        if (java.util.stream.Stream.concat(source.spans().stream(), source.candidates().stream())
                .anyMatch(arc -> arc.start().equals(start) && arc.end().equals(end))) return source;
        if (owners.get(a.owner).isEmpty() && owners.contains(Optional.of(start))
                || owners.get(b.owner).isEmpty() && owners.contains(Optional.of(end))) return source;
        owners.set(a.owner, Optional.of(start));
        owners.set(b.owner, Optional.of(end));
        var arcs = new ArrayList<>(source.spans());
        var evidence = new ScoreSlurSpan.ArcEvidence(page, raw[0] / ink.width, raw[1] / ink.height,
                raw[2] / ink.width, raw[3] / ink.height);
        arcs.add(new ScoreSlurPages.Span("chromatic-tail-bow:" + page + ":" + start.noteIndex(),
                start, end, ScoreSlurSpan.Placement.BELOW, ScoreSlurSpan.LineType.SOLID, List.of(evidence)));
        return new ScoreSlurSource(arcs, owners, source.candidates());
    }

    private static float[] fitsShortBelowBow(Ink plane, Head a, Head b, float gap) {
        int left = Math.round(a.x + .35f * gap), right = Math.round(b.x - .34f * gap);
        int base = Math.round(Math.max(a.y, b.y) + 1.07f * gap);
        int margin = Math.round(.18f * gap);
        int support = 0, longestBlank = 0, blank = 0;
        for (int x = left; x <= right; x++) {
            float t = (x - left) / (float)(right - left);
            int bow = Math.round(base + 2.1f * gap * t * (1 - t));
            boolean ink = false;
            for (int y = bow - margin; y <= bow + margin; y++) ink |= plane.dark(x, y);
            if (ink) { support++; blank = 0; }
            else longestBlank = Math.max(longestBlank, ++blank);
        }
        if (support < .90f * (right - left + 1) || longestBlank > .17f * gap) return null;
        int mid = (left + right) / 2;
        for (int x = mid - 2; x <= mid + 2; x++)
            if (plane.dark(x, base) || plane.dark(x, base - 1)) return null;
        return new float[]{left, base, right, base};
    }

    private static Dyad tallerFirst(Head a, Head b) {
        return a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
    }

    private static void writeProvedAccidental(Head head, int accidental, List<ScoreNoteEvent> result,
            Consumer<SourceLocalAccidental.ProvedMark> printed) {
        var note = result.get(head.owner).withWrittenAccidental(accidental);
        result.set(head.owner, note);
        printed.accept(new SourceLocalAccidental.ProvedMark(note, false));
    }

    private static boolean provedNaturalAndFlat(Ink plane, Head upper, Head lower, float gap) {
        int spine = Math.round(lower.x - 2.22f * gap);
        return OmrScoreInterpreter.provesBoundedNaturalInk(plane.raw, plane.width, plane.height,
                        lower.x, lower.y, gap, spine, spine + Math.round(.74f * gap))
                && BeamedFlatBarline.flatAt(plane.raw, plane.width, plane.height,
                        Math.round(upper.x - 1.38f * gap), upper.y, gap);
    }

    private static boolean provedLowerNaturalOnly(Ink plane, Head upper, Head lower, float gap) {
        int spine = Math.round(lower.x - 1.51f * gap);
        return OmrScoreInterpreter.provesBoundedNaturalInk(plane.raw, plane.width, plane.height,
                        lower.x, lower.y, gap, spine, spine + Math.round(.74f * gap))
                && !OmrScoreInterpreter.provesBoundedSharpInk(plane.raw, plane.width, plane.height,
                        upper.x, upper.y, gap, Math.round(upper.x - 2.0f * gap), Math.round(upper.x - .60f * gap));
    }

    private static boolean provedCompressedFlatPair(Ink plane, Head upper, Head lower, float gap) {
        int spine = Math.round(lower.x - 1.93f * gap);
        int upperSpine = Math.round(upper.x - 1.42f * gap);
        if (upperSpine - spine < .45f * gap
                || !BeamedFlatBarline.flatAt(plane.raw, plane.width, plane.height, upperSpine, upper.y, gap))
            return false;
        int stem = 0, hole = 0, closure = 0;
        for (int y = Math.round(lower.y - 1.40f * gap); y <= lower.y + .53f * gap; y++) {
            if (plane.dark(spine, y) && plane.dark(spine + 1, y)) stem++;
            int lip = Math.round(.55f * gap);
            if (y >= lower.y - .30f * gap && y <= lower.y + .20f * gap
                    && plane.dark(spine, y) && plane.dark(spine + lip, y)
                    && !plane.dark(spine + Math.round(.30f * gap), y)) hole++;
            if (y > lower.y + .20f * gap && plane.dark(spine, y)
                    && plane.dark(spine + Math.round(.30f * gap), y)
                    && !plane.dark(spine + Math.round(.82f * gap), y)) closure++;
        }
        return stem >= 1.65f * gap && hole >= .27f * gap && closure >= .18f * gap;
    }

    static List<Head> separateCollapsedUpstemThirds(
            Ink ink, Ink original, MeasureRegion bar, List<Head> ordered, float gap) {
        if (gap < 10 || gap > 26) return List.of();
        var repaired = List.<Head>of();
        for (int i = 2; i + 5 < ordered.size(); i += 2) {
            var four = ordered.subList(i, i + 4);
            float x = four.get(0).x;
            if (four.get(3).x - x > .05f * gap
                    || x - ordered.get(i - 1).x < gap
                    || ordered.get(i + 4).x - x < 3.6f * gap
                    || four.stream().anyMatch(h -> !ordinaryDoubleRail(h.note,
                            four.get(0).note.clefBottomDiatonic(), 1, false))) continue;
            var byHeight = four.stream().sorted(Comparator.comparingDouble(Head::y)).toList();
            var a = byHeight.get(0);
            var b = byHeight.get(1);
            var c = byHeight.get(2);
            var d = byHeight.get(3);
            if (a.note.staffStep() - b.note.staffStep() != 1
                    || b.note.staffStep() - c.note.staffStep() != 1
                    || c.note.staffStep() - d.note.staffStep() != 1
                    || !sameShaftDyadGeometry(a, c, gap) || !sameShaftDyadGeometry(b, d, gap)) continue;
            float grayShift = provedAdjacentStemShift(ink, x, a, b, c, d, gap);
            float rawShift = provedAdjacentStemShift(original, x, a, b, c, d, gap);
            if (grayShift == 0 || rawShift != grayShift) continue;
            float moved = x + rawShift;
            float barLeft = bar.left() * ink.width, barRight = bar.right() * ink.width;
            if (moved < barLeft || moved >= barRight || ordered.get(i + 4).x - moved < 1.5f * gap
                    || !repaired.isEmpty()) return List.of();
            float position = (moved - barLeft) / (barRight - barLeft);
            repaired = List.of(new Head(b.owner, withPosition(b.note, position), moved, b.y),
                               new Head(d.owner, withPosition(d.note, position), moved, d.y));
        }
        return repaired;
    }

    private static float provedAdjacentStemShift(Ink ink, float x, Head a, Head b, Head c, Head d, float gap) {
        var budget = new PrintedBeamShafts.Budget(160_000);
        var first = sharedOpticalShaft(ink, new Dyad(a, c), gap, budget);
        if (first == null || first.direction() != -1) return 0;
        int foundLeft = -1;
        for (int advance = Math.round(1.27f * gap); advance <= 1.60f * gap; advance++) {
            var upper = new Head(b.owner, b.note, x + advance, b.y);
            var lower = new Head(d.owner, d.note, x + advance, d.y);
            var second = sharedOpticalShaft(ink, new Dyad(upper, lower), gap, budget);
            if (second == null || second.direction() != -1 || second.right() - second.left() < 3
                    || second.left() - first.left() < 1.27f * gap
                    || second.left() - first.left() > 1.60f * gap
                    || Math.abs(second.endY() - first.endY()) > .35f * gap
                    || !FingeringAnnotationFilter.pairedBeamRails(ink.raw, ink.width, ink.height, first, second, gap))
                continue;
            if (foundLeft >= 0 && foundLeft != second.left()) return 0;
            foundLeft = second.left();
        }
        return foundLeft < 0 || budget.refused() ? 0 : foundLeft - first.left();
    }

    private static ScoreNoteEvent withPosition(ScoreNoteEvent n, float position) {
        return new ScoreNoteEvent(n.measureIndex(), position, n.staffStep(), n.staffIndex(),
                n.staffCount(), n.pageY(), n.tiedFromPrevious(), n.augmentationDots(), n.beamCount(),
                n.writtenAccidental(), n.unbeamedDurationBeats(), n.tupletDivisor(), n.followingRestBeats(),
                n.articulations(), n.clefBottomDiatonic(), n.crossStaffBeam(), n.leadingRestBeats(),
                n.compactOpening(), n.octaveShift(), n.boundaryTies(), n.tupletNormalNotes(),
                n.stemDirection(), n.kind(), n.visualSize(), n.harmonicSemitones(), n.openStringTouch());
    }

    private static Head repeatedTerminalLedgerSpace(Ink ink, Ink original, List<Head> row, float gap) {
        var p = row.get(20);
        var q = row.get(21);
        var previous = p.y < q.y ? new Dyad(p, q) : new Dyad(q, p);
        var a = row.get(22);
        var b = row.get(23);
        var end = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
        int step = previous.top.note.staffStep();
        if (step < 11 || (step & 1) == 0 || previous.bottom.note.staffStep() != step - 2
                || end.top.note.staffStep() != step + 1 || end.bottom.note.staffStep() != step - 2
                || end.top.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_SHARP
                || end.bottom.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_NATURAL
                || end.top.note.tupletDivisor() != 1
                || Math.abs(previous.top.y - end.top.y) > .16f * gap
                || Math.abs(previous.bottom.y - end.bottom.y) > .16f * gap
                || !matchedLedgerSpace(ink, previous, end, gap)
                || !matchedLedgerSpace(original, previous, end, gap)) return null;

        var n = end.top;
        var corrected = new Head(n.owner, withStaffStep(n.note, step), n.x, n.y);
        row.set(a == n ? 22 : 23, corrected);
        return corrected;
    }

    private static boolean matchedLedgerSpace(Ink plane, Dyad previous, Dyad current, float gap) {
        for (var dyad : List.of(previous, current)) {
            if (inkRightOfHead(plane, dyad.top.x, dyad.top.y, gap) != 0
                    || inkRightOfHead(plane, dyad.top.x, (dyad.top.y + dyad.bottom.y) * .5f, gap) < 2
                    || inkRightOfHead(plane, dyad.bottom.x, dyad.bottom.y + .58f * gap, gap) < 2)
                return false;
        }
        return true;
    }

    private static int inkRightOfHead(Ink plane, float x, float y, float gap) {
        int inkRows = 0;
        for (int row = Math.round(y - .18f * gap); row <= Math.round(y + .18f * gap); row++) {
            int dark = 0;
            int left = Math.round(x + .76f * gap), right = Math.round(x + 1.02f * gap);
            for (int col = left; col <= right; col++) if (plane.dark(col, row)) dark++;
            if (dark >= .60f * (right - left + 1)) inkRows++;
        }
        return inkRows;
    }

    private static boolean pairedFlatInk(Ink plane, Head upper, Head lower, int upperSpine, int lowerSpine, float gap) {
        return BeamedFlatBarline.flatAt(plane.raw, plane.width, plane.height, upperSpine, upper.y, gap)
                && BeamedFlatBarline.flatAt(plane.raw, plane.width, plane.height, lowerSpine, lower.y, gap);
    }

    /** Corrects a one-step upper-head drift in a complete run of printed sixths. */
    static ScorePageInterpretation retainPhaseVerifiedSixths(ScorePageInterpretation score,
            byte[] gray, byte[] original, int width, int height, int page,
            java.util.function.Consumer<SourceLocalAccidental.ProvedMark> printedMark) {
        if (page < 0 || gray == null || original == null || gray.length != (long) width * height
                || original.length != gray.length) return score;
        var regions = score.measures();
        var bars = new ArrayList<List<Head>>();
        for (int i = 0; i < regions.size(); i++) bars.add(new ArrayList<>());
        for (int i = 0; i < score.notes().size(); i++) {
            var note = score.notes().get(i);
            var bar = regions.get(note.measureIndex());
            bars.get(note.measureIndex()).add(new Head(i, note,
                    (bar.left() + note.positionInMeasure() * (bar.right() - bar.left())) * width,
                    note.pageY() * height));
        }
        var beams = score.beamSource();
        var slurs = score.slurSource();
        var corrected = new ArrayList<>(score.notes());
        boolean changed = false;
        for (int measure = 0; measure < bars.size(); measure++) {
            var row = bars.get(measure);
            var bar = regions.get(measure);
            int index = measure;
            if (row.size() != 32 || bar.cadenceQuarterBeats() != 0
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == index)) continue;
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (gap < 12 || gap > 23) continue;
            row.sort(Comparator.comparingDouble(Head::x));
            var pairs = new ArrayList<Dyad>(16);
            var columns = new ClosedFourDyadBeam.Head[16];
            int clef = row.get(0).note.clefBottomDiatonic(), spacedLink = -1;
            Head moved = null;
            for (int slot = 0; slot < 16; slot++) {
                var a = row.get(slot * 2);
                var b = row.get(slot * 2 + 1);
                var pair = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
                if (!sameShaftDyadGeometry(a, b, gap) || Math.abs(pair.bottom.y - pair.top.y - 2.5f * gap) > .28f * gap
                        || !ordinaryDoubleRail(a.note, clef, -1, false)
                        || !ordinaryDoubleRail(b.note, clef, -1, false)) break;
                int steps = pair.top.note.staffStep() - pair.bottom.note.staffStep();
                if (steps != 5) {
                    if (moved != null || steps != 6 || pair.top.note.staffStep() < 11
                            || pair.top.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_NATURAL) break;
                    moved = pair.top;
                }
                float x = (a.x + b.x) * .5f;
                if (slot > 0) {
                    float spacing = (x - columns[slot - 1].x()) / gap;
                    if (spacing < 1.45f || spacing > 3.9f || spacing > 3.35f && !hasAccidental(pair)) break;
                    if (spacing > 3.35f && spacedLink < 0 && slot % 4 != 0) spacedLink = slot - 1;
                }
                pairs.add(pair);
                columns[slot] = new ClosedFourDyadBeam.Head(x, pair.top.y, pair.bottom.y);
            }
            if (moved == null || pairs.size() != 16 || !closes(row, score.notes(), 4f)) continue;
            var rows = UnnumberedTripletInk.sourceRows(bar, regions, height);
            if (!provesSixthStaffAndRails(new Ink(gray, width, height), regions, measure, pairs,
                            columns, moved, gap, rows, spacedLink)
                    || !provesSixthStaffAndRails(new Ink(original, width, height), regions, measure, pairs,
                            columns, moved, gap, rows, spacedLink)) continue;
            var natural = withStaffStep(moved.note, moved.note.staffStep() - 1);
            corrected.set(moved.owner, natural);
            printedMark.accept(new SourceLocalAccidental.ProvedMark(natural, false));
            if (beamOriginsUnclaimed(beams, row)) beams = projectDoubleRails(beams, page, row);
            slurs = PrintedLiteralOctaveSlurs.captureSixthPhrases(slurs, pairs,
                    measure + 1 < bars.size() ? bars.get(measure + 1) : List.of(),
                    bar, original, gray, width, height, page, gap);
            changed = true;
        }
        if (!changed) return score;
        return score.withBeamSource(beams).withNotes(corrected,
                java.util.stream.IntStream.range(0, corrected.size()).boxed().toList()).withSlurSource(slurs);
    }

    private static boolean provesSixthStaffAndRails(Ink ink, List<MeasureRegion> regions, int measure,
            List<Dyad> pairs, ClosedFourDyadBeam.Head[] columns, Head moved, float gap,
            UnnumberedTripletInk.Rows rows, int spacedLink) {
        var bar = regions.get(measure);
        var staff = WrappedMeasureBoundaries.verifiedTripletStaff(ink.raw, ink.width, ink.height, bar, true);
        if (staff == null) return false;
        for (var pair : pairs) {
            for (var head : List.of(pair.top, pair.bottom)) {
                if (!staff.verifiedAt(head.x)) return false;
                var reference = staff.at(head.x);
                float step = (reference[0] - head.y) * 2 / reference[1];
                float expected = head.note.staffStep() - (head == moved ? 1 : 0);
                if (Math.abs(step - expected) > .32f) return false;
            }
        }
        return OmrScoreInterpreter.provesBoundedNaturalInk(ink.raw, ink.width, ink.height,
                        moved.x, moved.y, gap, Math.round(moved.x - 1.9f * gap),
                        Math.round(moved.x - .58f * gap))
                && printedFourRailGroups(ink, regions, measure, pairs, columns,
                        List.of(), gap, rows, spacedLink, true, false);
    }

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
        boolean upstemFirstThirds = positions == 16 && !wideSixths
                && heads.subList(0, 6).stream().allMatch(
                        h -> h.note.stemDirection() == 1 && h.note.tupletDivisor() == 3)
                && heads.subList(6, heads.size()).stream().allMatch(h -> h.note.tupletDivisor() == 1);
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
                    || (compactOpening || tripleMeter || fingeredThirds || upstemFirstThirds)
                            && pair.top.note.staffStep() - pair.bottom.note.staffStep() != 2
                    || (!wideSixths && !fingeredThirds && !upstemFirstThirds && claim && direction != -1)
                    || !sameShaftDyadGeometry(a, b, gap)
                    || !ordinaryDoubleRail(first, clef, direction, claim)
                    || !ordinaryDoubleRail(second, clef, direction, claim)) return false;
            float x = (a.x + b.x) * .5f;
            if (slot > 0) {
                float spacing = (x - groups[slot - 1].x()) / gap;
                float minimumSpacing = compactOpening ? 1.40f : wideSixths ? 1.55f : 1.65f;
                if (spacing < minimumSpacing
                        && !(tripleMeter && direction == 1 && slot % 4 != 0 && spacing >= 1.33f)) return false;
                if (tripleMeter && spacing > 3.35f) {
                    boolean staggeredMarks = pair.top.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                            && pair.bottom.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
                    if (spacing > (staggeredMarks ? 5.55f : 4.9f) || !hasAccidental(pair)) return false;
                    wideChromatic = true;
                } else if (spacing > (wideSixths || tripleMeter || fingeredThirds && slot % 4 == 0 ? 3.35f : upstemFirstThirds ? 3.2f : 2.7f)) {
                    if (!wideSixths && !compactOpening && !upstemFirstThirds || spacing > ACCIDENTAL_SPACED_LINK || !hasAccidental(pair))
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
                            : compactOpening || tripleMeter || upstemFirstThirds ? first != 0 : first / 4 == (first + 2) / 4))
                return false;
        }
        var rows = UnnumberedTripletInk.sourceRows(region, regions, ink.height);
        if (upstemFirstThirds && (centeredUpstemClaimNumeral(ink, pairs, rows, gap)
                || centeredUpstemClaimNumeral(original, pairs, rows, gap))) return false;
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


    /** Retain a literal centered three even when its primary beam needs no bracket. */
    private static boolean centeredUpstemClaimNumeral(
            Ink ink, List<Dyad> pairs, UnnumberedTripletInk.Rows rows, float gap) {
        var budget = new PrintedBeamShafts.Budget(75_000);
        int tip = ink.height, bottomHead = 0;
        for (int i = 0; i < 3; i++) {
            var pair = pairs.get(i);
            var shaft = sharedOpticalShaft(ink, pair, gap, budget);
            if (shaft == null) return true;
            tip = Math.min(tip, shaft.endY());
            bottomHead = Math.max(bottomHead, Math.round(pair.bottom.y));
        }
        float x = (pairs.get(1).top.x + pairs.get(1).bottom.x) * .5f;
        int left = Math.round(x - gap * .85f), right = Math.round(x + gap * .85f);
        int aboveTop = Math.max(rows.first(), Math.round(tip - gap * 4f));
        int aboveBottom = Math.min(rows.last(), Math.round(tip - gap * .28f));
        int belowTop = Math.max(rows.first(), Math.round(bottomHead + gap * .28f));
        int belowBottom = Math.min(rows.last(), Math.round(bottomHead + gap * 3.4f));
        return centeredDetachedThree(ink, x, gap, left, right, aboveTop, aboveBottom)
                || centeredDetachedThree(ink, x, gap, left, right, belowTop, belowBottom);
    }

    private static boolean centeredDetachedThree(
            Ink ink, float x, float gap, int left, int right, int top, int bottom) {
        if (left < 0 || right >= ink.width || top >= bottom) return false;
        var window = RawNumeralComponents.captureLongWindow(
                ink.raw, ink.width, ink.height, left, top, right, bottom, 165);
        for (var component : window.components()) {
            float center = (component.left + component.right) * .5f;
            int w = component.right - component.left + 1, h = component.bottom - component.top + 1;
            if (component.left <= left || component.right >= right || component.top <= top
                    || component.bottom >= bottom || Math.abs(center - x) > gap * .42f
                    || w < gap * .42f || w > gap * 1.10f || h < gap * .72f || h > gap * 1.55f
                    || component.size() < gap * gap * .19f
                    || UnnumberedTripletInk.isUnambiguousOne(ink.raw, ink.width, component)) continue;
            if (TripletRhythmDetector.isIsolatedThree(component, ink.width)) return true;
            var fit = JoinedDigitTemplates.inspect(ink.raw, ink.width, ink.height, new java.util.BitSet(),
                    component.left, component.top, component.right, component.bottom, () -> {});
            if (fit != null && fit.digit() == 3) return true;
        }
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
            List<MeasureRegion> regions, int measure, UnnumberedTripletInk.Rows rows, int first, int cut) {}

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
        if (first > 13 || claims != 7 << first) return false;
        boolean endsQuartet = first % 4 == 1;
        if (!endsQuartet && first / 4 == (first + 2) / 4) return false;
        int cut = first / 4 * 4 + 3;
        var clock =
                new OctaveClock(
                        regions, measure, UnnumberedTripletInk.sourceRows(bar, regions, ink.height), first, cut);
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

    /** A stacked 3/0 between matching 4/1 fingerings belongs to an opening octave chord. */
    private static boolean closeStackedFingeredOpeningOctaves(
            Ink ink, Ink original, List<MeasureRegion> regions, int measure,
            List<Head> source, List<ScoreNoteEvent> result) {
        if (measure != 0 || source.size() != 32) return false;
        var bar = regions.get(measure);
        float gap = (bar.bottom() - bar.top()) * ink.height / 8.2f;
        if (gap < 12 || gap > 24) return false;
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        boolean crossing = heads.get(0).note.tupletDivisor() == 1 && heads.get(4).note.tupletDivisor() == 3;
        var pairs = new ArrayList<Dyad>(16);
        var columns = new ClosedFourDyadBeam.Head[16];
        int clef = heads.get(0).note.clefBottomDiatonic();
        for (int slot = 0; slot < 16; slot++) {
            var a = heads.get(slot * 2);
            var b = heads.get(slot * 2 + 1);
            var pair = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
            int direction = a.note.stemDirection();
            boolean claim = crossing ? slot >= 2 && slot <= 4 : slot < 3;
            int expectedDirection = slot < 4 || slot >= 12 ? 1 : -1;
            float spacing = (pair.top.x - (slot == 0 ? pair.top.x : pairs.get(slot - 1).top.x)) / gap;
            if (Math.abs(direction) != 1 || slot < 4 && direction != 1 || crossing && direction != expectedDirection
                    || Math.abs(a.x - b.x) > gap * .21f
                    || pair.top.note.staffStep() - pair.bottom.note.staffStep() != 7
                    || pair.bottom.y - pair.top.y < gap * 3.15f
                    || pair.bottom.y - pair.top.y > gap * 3.85f
                    || !ordinaryDoubleRail(a.note, clef, direction, claim)
                    || !ordinaryDoubleRail(b.note, clef, direction, claim)
                    || slot > 0 && (spacing < 1.5f || spacing > (slot % 4 == 0 ? 4.5f : 3.35f))) return false;
            pairs.add(pair);
            columns[slot] = new ClosedFourDyadBeam.Head((a.x + b.x) * .5f, pair.top.y, pair.bottom.y);
        }
        var rows = UnnumberedTripletInk.sourceRows(bar, regions, ink.height);
        for (var plane : List.of(ink, original)) {
            if (!WrappedMeasureBoundaries.confirmsOpeningTripletBar(
                    plane.raw, plane.width, plane.height, regions, columns[0].x(), columns[15].x(), false)
                    || !(crossing ? stackedCrossCutFingerings(plane, bar, pairs, gap, rows)
                            : stackedOpeningFingerings(plane, bar, pairs, gap, rows))
                    || !printedFourRailGroups(plane, regions, measure, pairs, columns, List.of(),
                            gap, rows, -1, true, false, true)) return false;
        }
        var corrected = new ArrayList<>(result);
        var claimed = heads.subList(crossing ? 4 : 0, crossing ? 10 : 6);
        for (var head : claimed)
            corrected.set(head.owner, head.note.withTupletRatio(1, 1));
        if (!closes(heads, corrected, 4f)) return false;
        for (var head : claimed) result.set(head.owner, corrected.get(head.owner));
        return true;
    }

    static ScorePageInterpretation retainCrossCutOpeningOctaves(ScorePageInterpretation score,
            byte[] normalized, byte[] raw, byte[] labels, int width, int height, int page) {
        if (page < 0 || raw == null || normalized == null) return score;
        var pairs = sourceOpeningStacks(score, raw, normalized, width, height);
        if (pairs.isEmpty()) return score;
        var heads = pairs.stream().flatMap(p -> java.util.stream.Stream.of(p.top, p.bottom)).toList();
        var source = score.beamSource();
        if (beamOriginsUnclaimed(source, heads)) {
            var projected = projectDoubleRails(source, page, heads);
            if (projected != source) score = score.withBeamSource(projected);
        }
        var region = score.measures().get(0);
        float gap = (region.bottom() - region.top()) * height / 8.2f;
        // The two inner quartets have downstems, leaving space for a bow over their last three attacks.
        var originalSlurs = score.slurSource();
        var slurs = PrintedLiteralOctaveSlurs.captureTrailingThree(originalSlurs, pairs.subList(4, 12),
                region, raw, normalized, labels, width, height, page, gap);
        return slurs == originalSlurs ? score : score.withSlurSource(slurs)
                .withClockContextSource(score.clockContextSource());
    }

    private static List<Dyad> sourceOpeningStacks(ScorePageInterpretation score,
            byte[] raw, byte[] normalized, int width, int height) {
        if (score.measures().size() < 2 || raw.length != (long) width * height || normalized.length != raw.length)
            return List.of();
        var regions = score.measures();
        var bar = regions.get(0);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 12 || gap > 24 || bar.cadenceQuarterBeats() != 0
                || score.rests().stream().anyMatch(r -> r.measureIndex() == 0)) return List.of();
        var heads = new ArrayList<Head>();
        for (int i = 0; i < score.notes().size(); i++) {
            var note = score.notes().get(i);
            if (note.measureIndex() != 0) continue;
            heads.add(new Head(i, note, (bar.left() + note.positionInMeasure() * (bar.right() - bar.left())) * width,
                    note.pageY() * height));
        }
        if (heads.size() != 32) return List.of();
        heads.sort(Comparator.comparingDouble(Head::x));
        var pairs = straightOctaves(heads, gap, true);
        if (pairs.isEmpty() || !closes(heads, score.notes(), 4f)) return List.of();
        var columns = pairs.stream().map(p -> new ClosedFourDyadBeam.Head((p.top.x + p.bottom.x) * .5f,
                p.top.y, p.bottom.y)).toArray(ClosedFourDyadBeam.Head[]::new);
        var rows = UnnumberedTripletInk.sourceRows(bar, regions, height);
        for (var pixels : List.of(raw, normalized)) {
            var ink = new Ink(pixels, width, height);
            if (!WrappedMeasureBoundaries.confirmsOpeningTripletBar(
                    pixels, width, height, regions, columns[0].x(), columns[15].x(), false)
                    || !stackedCrossCutFingerings(ink, bar, pairs, gap, rows)
                    || !printedFourRailGroups(ink, regions, 0, pairs, columns, List.of(),
                            gap, rows, -1, true, false, true)) return List.of();
        }
        return pairs;
    }

    static List<PrintedFingeringDigits.Mark> captureCrossCutOpeningFingerings(ScorePageInterpretation score,
            byte[] raw, byte[] normalized, int width, int height) {
        var pairs = sourceOpeningStacks(score, raw, normalized, width, height);
        if (pairs.isEmpty()) return List.of();
        var result = new ArrayList<PrintedFingeringDigits.Mark>(6);
        for (int slot : new int[] {0, 3, 4}) {
            var pair = pairs.get(slot);
            result.add(new PrintedFingeringDigits.Mark(pair.top.owner, slot == 3 ? 3 : 4,
                    PrintedFingeringDigits.Placement.ABOVE));
            result.add(new PrintedFingeringDigits.Mark(pair.bottom.owner, slot == 3 ? 0 : 1,
                    PrintedFingeringDigits.Placement.ABOVE));
        }
        return result;
    }

    private static boolean stackedCrossCutFingerings(
            Ink ink, MeasureRegion bar, List<Dyad> pairs, float gap, UnnumberedTripletInk.Rows rows) {
        var first = pairs.get(0);
        var last = pairs.get(3);
        var next = pairs.get(4);
        var beginShaft = shafts(ink, first, gap, 1);
        var endShaft = shafts(ink, last, gap, 1);
        if (beginShaft.isEmpty() || endShaft.isEmpty()) return false;
        int openingTip = beginShaft.stream().mapToInt(Shaft::tip).min().orElseThrow();
        int lastTip = endShaft.stream().mapToInt(Shaft::tip).min().orElseThrow();
        if (!readsOctaveFingerStack(ink, first.top.x, openingTip, gap, rows, 4, 1)
                || !readsOctaveFingerStack(ink, last.top.x, lastTip, gap, rows, 3, 0)
                || !readsOctaveFingerStack(ink, next.top.x, Math.round(next.top.y - .13f * gap), gap, rows, 4, 1))
            return false;
        var track = WrappedMeasureBoundaries.verifiedTripletStaff(ink.raw, ink.width, ink.height, bar, true);
        if (track == null) return false;
        var claimStart = pairs.get(2);
        return !UnnumberedTripletInk.bracketBesideBeam(ink.raw, ink.width, ink.height, gap,
                claimStart.top.x, next.top.x, Math.min(last.top.y, next.top.y),
                Math.max(claimStart.bottom.y, next.bottom.y), lastTip, rows, track, true);
    }

    private static boolean readsOctaveFingerStack(Ink ink, float x, int cap, float gap,
            UnnumberedTripletInk.Rows rows, int high, int low) {
        int left = Math.round(x - .98f * gap), right = Math.round(x + .75f * gap);
        int top = Math.max(rows.first(), Math.round(cap - 5.5f * gap));
        int bottom = Math.round(cap + (low == 0 ? -.12f : .25f) * gap);
        if (left < 0 || right >= ink.width || top < 0 || bottom >= ink.height || top >= bottom) return false;
        var marks = RawNumeralComponents.capture(
                ink.raw, ink.width, ink.height, left, top, right, bottom, 165, () -> {}, () -> {}).components()
                .stream().filter(c -> c.left > left && c.right < right && c.top > top && (c.bottom < bottom || low == 0 && c.bottom == bottom)
                        && c.bottom - c.top + 1 >= .73f * gap && c.bottom - c.top + 1 <= 1.42f * gap
                        && c.right - c.left + 1 <= 1.25f * gap
                        && Math.abs((c.left + c.right) * .5f - x) < .53f * gap)
                .sorted(Comparator.comparingInt(c -> c.top)).toList();
        if (marks.size() != 2) return false;
        var a = marks.get(0);
        var b = marks.get(1);
        if (b.top <= a.bottom || b.top - a.bottom > .65f * gap
                || Math.abs(a.left + a.right - b.left - b.right) > .55f * gap) return false;
        var strong = JoinedDigitTemplates.inspect(ink.raw, ink.width, ink.height, new java.util.BitSet(),
                a.left, a.top, a.right, a.bottom, () -> {});
        var weak = JoinedDigitTemplates.inspect(ink.raw, ink.width, ink.height, new java.util.BitSet(),
                b.left, b.top, b.right, b.bottom, () -> {});
        return (high == 3 ? TripletRhythmDetector.isIsolatedThree(a, ink.width) || standsOutAsFingeringThree(strong)
                : strong != null && strong.digit() == high)
                && (low == 1 ? TripletRhythmDetector.fingerOne(ink.raw, ink.width, b) : weak != null && weak.digit() == low);
    }

    private static boolean standsOutAsFingeringThree(JoinedDigitTemplates.Reading reading) {
        if (reading == null) return false;
        var scores = reading.scores();
        return scores[3] >= .68f && scores[3] - Math.max(Math.max(scores[0], scores[1]),
                Math.max(scores[2], scores[4])) >= .08f;
    }

    private static boolean stackedOpeningFingerings(
            Ink ink, MeasureRegion bar, List<Dyad> pairs, float gap, UnnumberedTripletInk.Rows rows) {
        float top = Float.POSITIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
        int tip = Integer.MAX_VALUE;
        for (int slot = 0; slot < 4; slot++) {
            var pair = pairs.get(slot);
            var stems = shafts(ink, pair, gap, 1);
            if (stems.isEmpty()
                    || stems.get(stems.size() - 1).x - stems.get(0).x > gap * .24f) return false;
            int firstTip = stems.stream().mapToInt(Shaft::tip).min().orElseThrow();
            int lastTip = stems.stream().mapToInt(Shaft::tip).max().orElseThrow();
            if (lastTip - firstTip > gap * .30f) return false;
            int left = Math.round(pair.top.x - gap * .8f), right = Math.round(pair.top.x + gap * .8f);
            int upper = Math.max(rows.first(), Math.round(firstTip - gap * 5.5f));
            int lower = Math.round(firstTip - gap * .30f);
            if (left < 0 || right >= ink.width || upper < 0 || lower >= ink.height) return false;
            var components = RawNumeralComponents.capture(
                    ink.raw, ink.width, ink.height, left, upper, right, lower, 165, () -> {}, () -> {});
            var digits = new ArrayList<RawNumeralComponents.Component>(2);
            for (var c : components.components()) {
                int height = c.bottom - c.top + 1, width = c.right - c.left + 1;
                if (c.left <= left || c.right >= right || c.top <= upper || c.bottom >= lower
                        || height < gap * .76f || height > gap * 1.35f
                        || width < gap * .1f || width > gap * 1.15f
                        || Math.abs((c.left + c.right) * .5f - pair.top.x) > gap * .35f) continue;
                digits.add(c);
            }
            if (digits.size() != 2) return false;
            digits.sort(Comparator.comparingInt(c -> c.top));
            var a = digits.get(0);
            var b = digits.get(1);
            if (b.top - a.bottom < gap * .10f || b.top - a.bottom > gap * .65f
                    || Math.abs(a.left + a.right - b.left - b.right) > gap * .60f) return false;
            if (slot == 1) {
                var zero = JoinedDigitTemplates.inspect(ink.raw, ink.width, ink.height,
                        new java.util.BitSet(), b.left, b.top, b.right, b.bottom, () -> {});
                if (!TripletRhythmDetector.isIsolatedThree(a, ink.width)
                        || zero == null || zero.digit() != 0) return false;
            } else {
                var four = JoinedDigitTemplates.inspect(ink.raw, ink.width, ink.height,
                        new java.util.BitSet(), a.left, a.top, a.right, a.bottom, () -> {});
                if (four == null || four.digit() != 4
                        || b.right - b.left + 1 > (b.bottom - b.top + 1) * .65f
                        || !TripletRhythmDetector.fingerOne(ink.raw, ink.width, b)
                        || TripletRhythmDetector.isIsolatedThree(b, ink.width)) return false;
            }
            if (slot < 3) {
                tip = Math.min(tip, firstTip);
                top = Math.min(top, pair.top.y);
                bottom = Math.max(bottom, pair.bottom.y);
            }
        }
        var track = WrappedMeasureBoundaries.verifiedTripletStaff(
                ink.raw, ink.width, ink.height, bar, true);
        return !UnnumberedTripletInk.bracketBesideBeam(ink.raw, ink.width, ink.height, gap,
                pairs.get(0).top.x, pairs.get(2).top.x, top, bottom, tip, rows, track, true);
    }

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
        return note.beamCount() == 2 && ordinaryRailGeometry(note, clef, direction, claim);
    }

    private static boolean ordinaryRailGeometry(
            ScoreNoteEvent note, int clef, int direction, boolean claim) {
        return ordinaryGeometry(note, clef)
                && note.stemDirection() == direction
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

    /** The two-rail run must end in the two heads of one shared downstem, not a separate eighth. */
    private static List<Head> closingOctaveSecondRail(Ink ink, Ink original,
            MeasureRegion bar, List<Head> source) {
        float gap = (bar.bottom() - bar.top()) * ink.height() / 8.2f;
        if (gap < 12 || gap > 24) return List.of();
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        if (bar.right() * ink.width() - heads.get(31).x() > gap * 1.8f) return List.of();
        var pairs = new ArrayList<Dyad>();
        for (int slot = 0; slot < 16; slot++) {
            var a = heads.get(slot * 2);
            var b = heads.get(slot * 2 + 1);
            var pair = a.y() < b.y() ? new Dyad(a, b) : new Dyad(b, a);
            if (pair.top().note().staffStep() - pair.bottom().note().staffStep() != 7
                    || pair.bottom().y() - pair.top().y() < gap * 3.15f
                    || pair.bottom().y() - pair.top().y() > gap * 3.85f
                    || a.x() + gap * (slot < 11 ? .21f : .13f) < b.x()
                    || a.note().octaveShift() != b.note().octaveShift()) return List.of();
            for (var head : List.of(a, b)) {
                var n = head.note();
                int beams = slot == 15 ? 1 : 2;
                if (n.beamCount() != beams
                        || !ordinaryDoubleRail(slot == 15 ? asSharedBeam(n, 2) : n,
                                ScoreNoteEvent.CLEF_TREBLE, -1, false)) return List.of();
            }
            pairs.add(pair);
        }
        return provesClosingOctaveSecondRail(ink, pairs.subList(11, 16), gap)
                && provesClosingOctaveSecondRail(original, pairs.subList(11, 16), gap)
                ? heads.subList(22, 32) : List.of();
    }

    private static boolean provesClosingOctaveSecondRail(Ink ink, List<Dyad> columns, float gap) {
        var held = columns.get(3).bottom();
        var sample = localRules(ink, held.x(), held.y() + held.note().staffStep() * gap * .5f, gap);
        if (sample == null) return false;
        var staff = StaffPitchTrack.linear(ink.width(), sample[0], sample[1], 0);
        var budget = new PrintedBeamShafts.Budget(120_000);
        PrintedBeamShafts.Shaft previous = null;
        for (int slot = 0; slot < columns.size(); slot++) {
            var pair = columns.get(slot);
            var upper = PrintedBeamShafts.at(ink.raw(), ink.width(), ink.height(),
                    pair.top().x(), pair.top().y(), gap, budget);
            var lower = PrintedBeamShafts.at(ink.raw(), ink.width(), ink.height(),
                    pair.bottom().x(), pair.bottom().y(), gap, budget);
            if (budget.refused() || upper.size() != 1 || lower.size() != 1) return false;
            var top = upper.get(0);
            var bottom = lower.get(0);
            if (top.direction() != 1 || bottom.direction() != 1
                    || Math.max(top.left(), bottom.left()) > Math.min(top.right(), bottom.right())
                    || Math.abs(top.endY() - bottom.endY()) > gap * .15f) return false;
            if (slot == 1 && !WrappedEighthTriplets.matchesBeamRails(
                    ink.raw(), ink.width(), ink.height(), previous, bottom, gap, 0)) return false;
            if (slot > 1 && !WrappedEighthTriplets.matchesExactlyTwoBeamRails(
                    ink.raw(), ink.width(), ink.height(), previous, bottom, gap, staff)) return false;
            previous = bottom;
        }
        return true;
    }

    private static ScoreBeamSource extendClosingOctaveBeam(ScoreBeamSource source, List<Head> closing) {
        var columns = new ArrayList<ScoreBeamSource.Column>(4);
        for (int slot = 2; slot < closing.size(); slot += 2) {
            var a = source.noteOrigins().get(closing.get(slot).owner()).orElse(null);
            var b = source.noteOrigins().get(closing.get(slot + 1).owner()).orElse(null);
            if (a == null || b == null || a.equals(b) || a.pageIndex() != b.pageIndex()) return source;
            columns.add(new ScoreBeamSource.Column(List.of(a, b)));
        }
        var groups = new ArrayList<>(source.groups());
        for (int i = 0; i < groups.size(); i++) {
            var g = groups.get(i);
            if (!g.bridges().equals(List.of(3, 3)) || !g.hooks().isEmpty()
                    || g.columns().size() != 3) continue;
            for (int offset = 0; offset < 2; offset++) {
                boolean same = true;
                for (int j = 0; j < 3; j++)
                    same &= new HashSet<>(g.columns().get(j).members())
                            .equals(new HashSet<>(columns.get(j + offset).members()));
                if (!same) continue;
                var missing = columns.get(offset == 0 ? 3 : 0);
                if (source.detachedColumns().stream()
                            .anyMatch(c -> c.members().stream().anyMatch(missing.members()::contains))
                        || groups.stream().flatMap(owner -> owner.columns().stream())
                            .anyMatch(c -> c.members().stream().anyMatch(missing.members()::contains))) return source;
                groups.set(i, new ScoreBeamSource.Group(g.id(), columns, List.of(3, 3, 3), List.of()));
                return new ScoreBeamSource(groups, source.detachedColumns(), source.noteOrigins());
            }
        }
        return source;
    }

    /** Retain literal engraving once the final event clock independently proves every sixteenth. */
    static ScorePageInterpretation captureWrittenOctaveQuartets(
            ScorePageInterpretation score, List<Map<String, Object>> events, byte[] original,
            byte[] normalized, byte[] labels, int width, int height, int physicalPage,
            float[] measureBeats, double[] starts) {
        if (original == null || normalized == null || labels == null
                || original.length != (long) width * height
                || normalized.length != original.length || labels.length != original.length
                || events.size() != score.notes().size()) return score;
        var regions = score.measures();
        var bars = new HashMap<Integer, List<Head>>();
        for (int owner = 0; owner < score.notes().size(); owner++) {
            var note = score.notes().get(owner);
            int measure = note.measureIndex();
            if (measure < 0 || measure >= regions.size()
                    || measure >= measureBeats.length || measure >= starts.length
                    || measureBeats[measure] != 4f) continue;
            var region = regions.get(measure);
            float x = (region.left() + note.positionInMeasure() * (region.right() - region.left()))
                    * width;
            bars.computeIfAbsent(measure, k -> new ArrayList<>())
                    .add(new Head(owner, note, x, note.pageY() * height));
        }
        var rawInk = new Ink(original, width, height);
        var normalizedInk = new Ink(normalized, width, height);
        for (int measure = 0; measure < regions.size(); measure++) {
            var heads = bars.getOrDefault(measure, List.of());
            if (heads.size() != 32 || !beamOriginsUnclaimed(score.beamSource(), heads)) continue;
            var region = regions.get(measure);
            float gap = (region.bottom() - region.top()) * height / 8.2f;
            var pairs = straightOctavePairs(heads, gap, true);
            boolean knownClef = !pairs.isEmpty();
            if (!knownClef) pairs = completeWrittenOctaves(heads, gap);
            if (pairs.isEmpty() || !writtenSixteenths(pairs, events, measure, starts[measure])) continue;
            boolean printedRails = knownClef
                    ? verifiesOctaveBeamGroups(rawInk, pairs, gap)
                        && verifiesOctaveBeamGroups(normalizedInk, pairs, gap)
                    : completeWrittenOctaveRails(rawInk, labels, region, pairs, gap)
                        && completeWrittenOctaveRails(normalizedInk, labels, region, pairs, gap);
            if (!printedRails) continue;
            var beams = projectDoubleRails(score.beamSource(), physicalPage, heads);
            if (beams == score.beamSource()) continue;
            if (!knownClef) {
                score = score.withBeamSource(beams);
                continue;
            }
            var slurs = PrintedLiteralOctaveSlurs.captureQuartets(
                    score.slurSource(), pairs, region, original, normalized, labels,
                    width, height, physicalPage, gap);
            score = score.withSlurSource(slurs).withClockContextSource(score.clockContextSource())
                    .withBeamSource(beams);
        }
        return score;
    }

    private static List<Dyad> completeWrittenOctaves(List<Head> heads, float gap) {
        if (gap < 12 || gap > 24 || !Float.isFinite(gap)) return List.of();
        var sorted = heads.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var pairs = new ArrayList<Dyad>(16);
        int clef = sorted.get(0).note.clefBottomDiatonic();
        if (clef != ScoreNoteEvent.CLEF_UNKNOWN) return List.of();
        for (int slot = 0; slot < 16; slot++) {
            var a = sorted.get(slot * 2);
            var b = sorted.get(slot * 2 + 1);
            var pair = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
            float dy = pair.bottom.y - pair.top.y;
            if (!unlabelledOctaveStem(a.note) || !unlabelledOctaveStem(b.note)
                    || pair.top.note.staffStep() - pair.bottom.note.staffStep() != 7
                    || b.x - a.x > .27f * gap || dy < 3.15f * gap || dy > 3.85f * gap)
                return List.of();
            if (slot > 0) {
                float advance = pair.top.x - pairs.get(pairs.size() - 1).top.x;
                if (advance < 1.50f * gap || advance > 3.55f * gap) return List.of();
            }
            pairs.add(pair);
        }
        return pairs;
    }

    private static boolean unlabelledOctaveStem(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffCount() == 1 && n.staffIndex() == 0
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL && !n.crossStaffBeam()
                && !n.tiedFromPrevious() && n.boundaryTies() == 0
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0
                && n.openStringTouch() == null && n.harmonicSemitones() == 0
                && (n.articulations() & NoteOrnament.GRACE) == 0
                && n.clefBottomDiatonic() == ScoreNoteEvent.CLEF_UNKNOWN
                && n.stemDirection() == -1 && n.beamCount() == 2 && n.augmentationDots() == 0
                && n.unbeamedDurationBeats() == 0 && n.tupletDivisor() == 1 && n.tupletNormalNotes() == 1;
    }

    private static boolean completeWrittenOctaveRails(
            Ink ink, byte[] labels, MeasureRegion region, List<Dyad> pairs, float gap) {
        var axes = new ArrayList<PrintedBeamShafts.Shaft>(pairs.size());
        var budget = new PrintedBeamShafts.Budget(300_000);
        boolean usedSharedContour = false;
        for (var pair : pairs) {
            var upper = PrintedBeamShafts.at(
                    ink.raw, ink.width, ink.height, pair.top.x, pair.top.y, gap, budget);
            var lower = PrintedBeamShafts.at(
                    ink.raw, ink.width, ink.height, pair.bottom.x, pair.bottom.y, gap, budget);
            if (budget.refused() || upper.size() > 1 || lower.size() != 1) return false;
            var b = lower.get(0);
            if (b.direction() != 1) return false;
            if (upper.isEmpty()) {
                if (usedSharedContour || !sourceShaftBetweenHeads(ink, labels, pair, b, gap)) return false;
                usedSharedContour = true;
            } else {
                var a = upper.get(0);
                if (a.direction() != 1 || a.left() > b.right() || b.left() > a.right()
                        || Math.abs(a.endY() - b.endY()) > .28f * gap) return false;
            }
            axes.add(b);
        }
        float floor = region.bottom() * ink.height - 2.1f * gap;
        var left = sourceStaffAtEdge(ink, labels, region.left() * ink.width, floor, gap);
        var right = sourceStaffAtEdge(ink, labels, region.right() * ink.width, floor, gap);
        if (left == null || right == null) return false;
        var staff = StaffPitchTrack.fromVerifiedSamples(List.of(left, right));
        if (staff == null) return false;
        float firstHead = pairs.get(0).top.x, lastHead = pairs.get(pairs.size() - 1).top.x;
        if (!WrappedMeasureBoundaries.confirmsClosedWithVerifiedStaff(
                    ink.raw, ink.width, ink.height, region, firstHead, lastHead, staff)
                && !WrappedMeasureBoundaries.confirmsPackedRightEdgeFromStem(
                    ink.raw, ink.width, ink.height, region, firstHead, lastHead,
                    axes.get(axes.size() - 1).right(), staff)) return false;
        boolean extended = false;
        for (int i = 1; i < axes.size(); i++) {
            var a = axes.get(i - 1);
            var b = axes.get(i);
            if (i % 4 == 0) {
                if (!WrappedEighthTriplets.cutBetweenRecoveredGroups(
                            ink.raw, ink.width, ink.height, region, a, b, gap)) return false;
            } else if (!WrappedEighthTriplets.matchesExactlyTwoBeamRails(
                        ink.raw, ink.width, ink.height, a, b, gap, staff)) {
                if (extended || !joinsAtStaffRule(ink, labels, a, b, gap, staff)) return false;
                extended = true;
            }
        }
        return true;
    }

    private static float[] sourceStaffAtEdge(Ink ink, byte[] labels, float x, float hint, float gap) {
        int right = Math.round(x) - 1, length = Math.round(.5f * gap), left = right - length + 1;
        int first = Math.round(hint - .39f * gap), last = Math.round(hint + .39f * gap);
        if (left < 0 || right >= ink.width || first - 4 * gap < 2 || last >= ink.height - 1)
            return null;
        float best = 0;
        int selected = -1;
        for (int floor = first; floor <= last; floor++) {
            int support = 0;
            for (int line = 0; line < 5; line++) {
                int y = Math.round(floor - line * gap), peak = 0;
                for (int row = y - 1; row <= y + 1; row++) {
                    int hits = 0;
                    for (int col = left; col <= right; col++)
                        if (labels[row * ink.width + col] == OmrMeasurePostProcessor.STAFF
                                && ink.dark(col, row)) hits++;
                    peak = Math.max(peak, hits);
                }
                if (peak < .58f * length) {
                    support = 0;
                    break;
                }
                support += peak;
            }
            float score = support - .15f * Math.abs(hint - floor);
            if (score > best) {
                best = score;
                selected = floor;
            }
        }
        return selected < 0 ? null : new float[]{x, selected, gap};
    }

    private static boolean sourceShaftBetweenHeads(
            Ink ink, byte[] labels, Dyad pair, PrintedBeamShafts.Shaft stem, float gap) {
        if (pair.top.x - stem.right() < .32f * gap || pair.top.x - stem.left() > .86f * gap)
            return false;
        int first = Math.round(pair.top.y + .43f * gap);
        int last = Math.round(pair.bottom.y - .46f * gap);
        if (first < 0 || last >= ink.height) return false;
        for (int x = stem.left(); x <= stem.right(); x++) {
            int witnessed = 0, gapLength = 0, longestGap = 0;
            for (int y = first; y <= last; y++) {
                if (labels[y * ink.width + x] == OmrMeasurePostProcessor.STEM_OR_REST && ink.dark(x, y)) {
                    witnessed++;
                    gapLength = 0;
                } else {
                    longestGap = Math.max(longestGap, ++gapLength);
                }
            }
            if (witnessed >= .90f * (last - first + 1) && longestGap <= Math.round(.16f * gap))
                return true;
        }
        return false;
    }

    private static boolean joinsAtStaffRule(
            Ink ink, byte[] labels, PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, float gap,
            StaffPitchTrack staff) {
        var frame = staff.at(b.left());
        int line = Math.round((frame[0] - b.endY()) / frame[1]);
        if (line < 0 || line > 4) return false;
        float rule = frame[0] - line * frame[1];
        if (Math.abs(rule - b.endY()) > .3f * gap || rule >= a.endY()) return false;
        int first = b.endY() + 1;
        if (first >= ink.height || (labels[first * ink.width + b.left()] != OmrMeasurePostProcessor.SYMBOL
                && labels[first * ink.width + b.right()] != OmrMeasurePostProcessor.SYMBOL)) return false;
        for (int extension = 1; extension <= Math.round(.2f * gap); extension++) {
            int y = b.endY() + extension;
            if (y >= ink.height || (ink.raw[y * ink.width + b.left()] & 255) > 205
                    || (ink.raw[y * ink.width + b.right()] & 255) > 205) return false;
            var tip = new PrintedBeamShafts.Shaft(
                    b.left(), b.right(), b.startY(), y, b.direction(), b.columns());
            if (WrappedEighthTriplets.matchesExactlyTwoBeamRails(
                        ink.raw, ink.width, ink.height, a, tip, gap, staff)) return true;
        }
        return false;
    }



    private static boolean writtenSixteenths(
            List<Dyad> pairs, List<Map<String, Object>> events, int measure, double start) {
        for (int slot = 0; slot < pairs.size(); slot++) {
            var pair = pairs.get(slot);
            for (var head : List.of(pair.top, pair.bottom)) {
                var event = events.get(head.owner);
                if (!(event.get("sourceNoteIndex") instanceof Number owner)
                        || owner.intValue() != head.owner
                        || !(event.get("measureIndex") instanceof Number bar)
                        || bar.intValue() != measure
                        || !(event.get("startBeat") instanceof Number onset)
                        || Math.abs(onset.doubleValue() - (start + slot * .25)) > 1e-6
                        || !(event.get("durationBeats") instanceof Number duration)
                        || duration.doubleValue() != .25
                        || Boolean.TRUE.equals(event.get("durationFallback"))) return false;
            }
        }
        return true;
    }

    private static boolean confirmsStraightOctaveBeams(
            Ink ink,
            Ink original,
            MeasureRegion bar,
            List<Head> source,
            List<ScoreNoteEvent> notes) {
        float gap = (bar.bottom() - bar.top()) * ink.height / 8.2f;
        var pairs = straightOctavePairs(source, gap, false);
        return !pairs.isEmpty()
                && closes(source.stream().sorted(Comparator.comparingDouble(Head::x)).toList(), notes)
                && verifiesOctaveBeamGroups(ink, pairs, gap)
                && verifiesOctaveBeamGroups(original, pairs, gap);
    }

    private static List<Dyad> straightOctavePairs(
            List<Head> source, float gap, boolean allowSecondaryUndercount) {
        if (source.size() != 32 || !Float.isFinite(gap) || gap < 12 || gap > 24) return List.of();
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        int clef = heads.get(0).note.clefBottomDiatonic();
        var pairs = new ArrayList<Dyad>(16);
        int undercounts = 0;
        for (int slot = 0; slot < 16; slot++) {
            var a = heads.get(slot * 2);
            var b = heads.get(slot * 2 + 1);
            var pair = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
            if (Math.abs(a.note.staffStep() - b.note.staffStep()) != 7
                    || b.x - a.x > gap * .21f
                    || pair.bottom.y - pair.top.y < gap * 3.15f
                    || pair.bottom.y - pair.top.y > gap * 3.85f
                    || !ordinaryRailGeometry(a.note, clef, -1, false)
                    || !ordinaryRailGeometry(b.note, clef, -1, false)
                    || a.note.beamCount() < 1 || a.note.beamCount() > 2
                    || b.note.beamCount() < 1 || b.note.beamCount() > 2
                    || a.note.beamCount() + b.note.beamCount() < 3) return List.of();
            undercounts += 4 - a.note.beamCount() - b.note.beamCount();
            if (slot != 0) {
                float advance = pair.top.x - pairs.get(slot - 1).top.x;
                if (advance < gap * 1.50f || advance > gap * 3.35f) return List.of();
            }
            pairs.add(pair);
        }
        return undercounts == (allowSecondaryUndercount ? 1 : 0) ? pairs : List.of();
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
        boolean terminalFingering = clock != null && clock.first % 4 == 1;
        if (terminalFingering && !provesAttachedOctaveFingering(
                ink, pairs.get(clock.first + 2), axes.get(clock.first + 2), gap)) return false;
        if (staff == null
                || !outsideOctaveBeam(ink, axes.get(0), -1, gap, staff, terminalFingering ? .70f : .88f)
                || !outsideOctaveBeam(ink, axes.get(15), 1, gap, staff)) return false;
        if (clock != null) {
            var bar = clock.regions.get(clock.measure);
            float first = pairs.get(0).top.x, last = pairs.get(pairs.size() - 1).top.x;
            boolean enclosed = WrappedMeasureBoundaries.confirmsClosedWithVerifiedStaff(
                    ink.raw, ink.width, ink.height, bar, first, last, staff);
            if (!enclosed && !terminalFingering) {
                int firstShaft = axes.get(0).stream().mapToInt(Shaft::x).min().orElseThrow();
                enclosed = WrappedMeasureBoundaries.confirmsCompleteShaftBoundedInitialStaffBar(
                        ink.raw, ink.width, ink.height, clock.regions, clock.measure, first, last, firstShaft, staff);
            }
            if (!enclosed || octaveNumeralOrBracket(ink, pairs, axes, gap, clock, staff)) return false;
        }
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

    /** A prior clock repair can leave the correct note ratios without literal beam ownership. */
    static ScorePageInterpretation retainAttachedOctaveQuartets(ScorePageInterpretation score,
            byte[] normalized, byte[] raw, int width, int height, int physicalPage) {
        if (physicalPage < 0 || raw == null || normalized == null
                || raw.length != (long) width * height || normalized.length != raw.length) return score;
        var original = new Ink(raw, width, height);
        var ink = new Ink(normalized, width, height);
        var regions = score.measures();
        var measures = new ArrayList<List<Head>>(regions.size());
        for (int i = 0; i < regions.size(); i++) measures.add(new ArrayList<>());
        for (int i = 0; i < score.notes().size(); i++) {
            var n = score.notes().get(i);
            if (n.measureIndex() < 0 || n.measureIndex() >= regions.size()) continue;
            var b = regions.get(n.measureIndex());
            measures.get(n.measureIndex()).add(new Head(i, n,
                    (b.left() + n.positionInMeasure() * (b.right() - b.left())) * width, n.pageY() * height));
        }
        var beams = score.beamSource();
        for (int i = 0; i < measures.size(); i++) {
            var bar = regions.get(i);
            var heads = measures.get(i);
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (heads.size() != 32 || gap < 12 || gap > 24 || bar.cadenceQuarterBeats() != 0
                    || !beamOriginsUnclaimed(beams, heads)) continue;
            final int measure = i;
            if (score.rests().stream().anyMatch(r -> r.measureIndex() == measure)) continue;
            heads.sort(Comparator.comparingDouble(Head::x));
            var pairs = straightOctaves(heads, gap, false);
            if (pairs.isEmpty() || !closes(heads, score.notes())) continue;
            for (int end = 3; end < 16; end += 4) {
                var terminal = pairs.get(end);
                var rawShafts = shafts(original, terminal, gap, -1);
                if (rawShafts.isEmpty() || !provesAttachedOctaveFingering(original, terminal, rawShafts, gap)) continue;
                var rows = UnnumberedTripletInk.sourceRows(bar, regions, height);
                var clock = new OctaveClock(regions, i, rows, end - 2, end);
                if (verifiesOctaveBeamGroups(ink, pairs, gap, clock)
                        && verifiesOctaveBeamGroups(original, pairs, gap, clock)) {
                    beams = projectDoubleRails(beams, physicalPage, heads);
                    break;
                }
            }
        }
        return beams == score.beamSource() ? score : score.withBeamSource(beams);
    }

    record UnderBeamOctaveStack(int upper, int lower) {}

    static List<UnderBeamOctaveStack> captureUnderBeamOctaveFingerings(
            ScorePageInterpretation score, byte[] raw, byte[] normalized, int width, int height) {
        if (raw == null || normalized == null || raw.length != (long) width * height
                || normalized.length != raw.length) return List.of();
        var result = new ArrayList<UnderBeamOctaveStack>();
        var original = new Ink(raw, width, height);
        var prepared = new Ink(normalized, width, height);
        var byMeasure = new ArrayList<List<Head>>(score.measures().size());
        for (int i = 0; i < score.measures().size(); i++) byMeasure.add(new ArrayList<>());
        for (int i = 0; i < score.notes().size(); i++) {
            var note = score.notes().get(i);
            var bar = score.measures().get(note.measureIndex());
            byMeasure.get(note.measureIndex()).add(new Head(i, note,
                    (bar.left() + note.positionInMeasure() * (bar.right() - bar.left())) * width,
                    note.pageY() * height));
        }
        for (int i = 0; i < byMeasure.size(); i++) {
            var heads = byMeasure.get(i);
            var bar = score.measures().get(i);
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (heads.size() != 32 || gap < 12 || gap > 24 || bar.cadenceQuarterBeats() != 0) continue;
            heads.sort(Comparator.comparingDouble(Head::x));
            var pairs = straightOctaves(heads, gap, false);
            if (pairs.isEmpty() || !closes(heads, score.notes())) continue;
            for (int column = 0; column < pairs.size(); column += 4) {
                var owner = pairs.get(column);
                var next = pairs.get(column + 1);
                if (readsUnderBeamThreeOne(original, owner, next, gap)
                        && (raw == normalized || readsUnderBeamThreeOne(prepared, owner, next, gap)))
                    result.add(new UnderBeamOctaveStack(owner.top.owner, owner.bottom.owner));
            }
        }
        return List.copyOf(result);
    }

    private static boolean readsUnderBeamThreeOne(Ink ink, Dyad owner, Dyad next, float gap) {
        float x = (owner.top.x + owner.bottom.x) * .5f;
        float floor = owner.bottom.y + owner.bottom.note.staffStep() * gap * .5f;
        int left = Math.round(x - .87f * gap), right = Math.round(x + .72f * gap);
        int top = Math.round(floor + 1.96f * gap), bottom = Math.round(floor + 3.76f * gap);
        if (left < 0 || right >= ink.width || top < 0 || bottom >= ink.height) return false;
        var ones = RawNumeralComponents.capture(ink.raw, ink.width, ink.height,
                left, top, right, bottom, 145, () -> {}, () -> {}).components().stream()
                .filter(c -> c.top > top && c.bottom < bottom && c.left > left && c.right < right
                        && c.bottom - c.top + 1 >= .92f * gap && c.bottom - c.top + 1 <= 1.28f * gap
                        && Math.abs((c.left + c.right) * .5f - x) <= .35f * gap
                        && TripletRhythmDetector.fingerOne(ink.raw, ink.width, c)).toList();
        if (ones.size() != 1) return false;
        var one = ones.get(0);
        int upper = one.top - Math.round(1.40f * gap);
        int lower = one.top - Math.round(.16f * gap);
        var threes = RawNumeralComponents.capture(ink.raw, ink.width, ink.height,
                left, upper, right, lower, 145, () -> {}, () -> {}).components().stream()
                .filter(c -> c.left > left && c.right < right && c.bottom < lower
                        && c.bottom - c.top + 1 >= .96f * gap
                        && c.right - c.left + 1 >= .63f * gap && c.right - c.left + 1 <= 1.05f * gap
                        && Math.abs(c.left + c.right - one.left - one.right) < .50f * gap).toList();
        if (threes.size() != 1 || !readsOpenThree(ink, threes.get(0))) return false;
        var shafts = shafts(ink, owner, gap);
        var nextShafts = shafts(ink, next, gap);
        var staff = StaffPitchTrack.linear(ink.width, floor, gap, 0);
        for (var rail : beams(ink, shafts, nextShafts, owner, next, gap, -1, staff, 3.2f, true)) {
            float separation = threes.get(0).top - rail.at(Math.round(x)) - rail.separation;
            // The digit can meet the edge of the lower rail.
            if (separation >= -.25f * gap && separation <= .45f * gap) return true;
        }
        return false;
    }

    private static boolean readsOpenThree(Ink ink, RawNumeralComponents.Component glyph) {
        var reading = JoinedDigitTemplates.inspect(ink.raw, ink.width, ink.height, new java.util.BitSet(),
                glyph.left, glyph.top, glyph.right, glyph.bottom, () -> {});
        if (reading == null || reading.scores()[3] < .67f || reading.scores()[8] > reading.scores()[3] + .04f)
            return false;
        for (int i = 0; i < reading.scores().length; i++)
            if (i != 3 && i != 8 && reading.scores()[3] - reading.scores()[i] < .07f) return false;

        int width = glyph.right - glyph.left + 1, height = glyph.bottom - glyph.top + 1;
        var open = new java.util.BitSet(width * height);
        var queue = new java.util.ArrayDeque<Integer>();
        for (int y = 0; y < height; y++)
            if ((ink.raw[(glyph.top + y) * ink.width + glyph.left] & 255) >= 145) {
                open.set(y * width);
                queue.add(y * width);
            }
        int[] dx = {-1, 1, 0, 0}, dy = {0, 0, -1, 1};
        while (!queue.isEmpty()) {
            int cell = queue.remove();
            for (int side = 0; side < dx.length; side++) {
                int x = cell % width + dx[side], y = cell / width + dy[side];
                if (x < 0 || x >= width || y < 0 || y >= height || open.get(y * width + x)
                        || (ink.raw[(glyph.top + y) * ink.width + glyph.left + x] & 255) < 145) continue;
                open.set(y * width + x);
                queue.add(y * width + x);
            }
        }
        int upper = 0, lower = 0;
        for (int y = 0; y < height; y++) {
            if (!open.get(y * width + (width - 1) / 2)
                    || (ink.raw[(glyph.top + y) * ink.width + glyph.right - 1] & 255) >= 145) continue;
            if (y >= height * .17f && y <= height * .40f) upper++;
            if (y >= height * .61f && y <= height * .87f) lower++;
        }
        return upper >= 2 && lower >= 2;
    }

    private static List<Dyad> straightOctaves(List<Head> heads, float gap, boolean mixed) {
        var pairs = new ArrayList<Dyad>(16);
        int clef = heads.get(0).note.clefBottomDiatonic();
        for (int i = 0; i < heads.size(); i += 2) {
            var a = heads.get(i);
            var b = heads.get(i + 1);
            var pair = tallerFirst(a, b);
            int direction = mixed && (i < 8 || i >= 24) ? 1 : -1;
            if (!ordinaryDoubleRail(a.note, clef, direction, false) || !ordinaryDoubleRail(b.note, clef, direction, false)
                    || pair.top.note.staffStep() - pair.bottom.note.staffStep() != 7
                    || b.x - a.x > .21f * gap
                    || pair.bottom.y - pair.top.y < 3.15f * gap
                    || pair.bottom.y - pair.top.y > 3.85f * gap) return List.of();
            if (!pairs.isEmpty()) {
                float advance = pair.top.x - pairs.get(pairs.size() - 1).top.x;
                if (advance < 1.50f * gap || advance > (mixed && i % 8 == 0 ? 4.5f : 3.35f) * gap) return List.of();
            }
            pairs.add(pair);
        }
        return pairs;
    }

    /** The 3 reaches the terminal beam flank. Its two bowls and the detached 1 remain visible. */
    private static boolean provesAttachedOctaveFingering(Ink ink, Dyad side, List<Shaft> axes, float gap) {
        int tip = axes.stream().mapToInt(Shaft::tip).max().orElseThrow();
        int shaftRight = axes.stream().mapToInt(Shaft::x).max().orElseThrow();
        int x = Math.round(side.bottom.x);
        int inner = x - Math.round(.13f * gap), outer = x + Math.round(.18f * gap);
        if (inner - shaftRight < .31f * gap || outer - inner < .24f * gap
                || !fingeringBowlBand(ink, inner, outer, tip - Math.round(.75f * gap), true)
                || !fingeringBowlBand(ink, inner, outer, tip - Math.round(.55f * gap), false)
                || !fingeringBowlBand(ink, inner, outer, tip - Math.round(.31f * gap), true)
                || !fingeringBowlBand(ink, inner, outer, tip, false)
                || !fingeringBowlBand(ink, inner, outer, tip + Math.round(.24f * gap), true))
            return false;
        int left = x - Math.round(.90f * gap), right = x + Math.round(.90f * gap);
        int top = tip + Math.round(.43f * gap), bottom = tip + Math.round(2.05f * gap);
        if (left < 0 || right >= ink.width || top < 0 || bottom >= ink.height) return false;
        var marks = RawNumeralComponents.capture(
                ink.raw, ink.width, ink.height, left, top, right, bottom, 165, () -> {}, () -> {}).components();
        if (marks.size() != 1) return false;
        var one = marks.get(0);
        return one.top > top && one.bottom < bottom && one.left > left && one.right < right
                && Math.abs((one.left + one.right) * .5f - x) <= gap * .31f
                && one.bottom - one.top >= gap * .92f
                && TripletRhythmDetector.fingerOne(ink.raw, ink.width, one);
    }

    static boolean retainsTerminalOctaveFingering(byte[] raw, int width, int height,
            int upperIndex, ScoreNoteEvent upper, float upperX, float upperY,
            int lowerIndex, ScoreNoteEvent lower, float lowerX, float lowerY, float gap) {
        if (gap < 12 || gap > 24 || upper.stemDirection() != -1 || lower.stemDirection() != -1
                || upper.tupletDivisor() != 1 || lower.tupletDivisor() != 1
                || upper.beamCount() != 2 || lower.beamCount() != 2
                || upper.staffStep() - lower.staffStep() != 7
                || Math.abs(upperX - lowerX) > .21f * gap
                || lowerY - upperY < 3.15f * gap || lowerY - upperY > 3.85f * gap) return false;
        var ink = new Ink(raw, width, height);
        var dyad = new Dyad(new Head(upperIndex, upper, upperX, upperY),
                new Head(lowerIndex, lower, lowerX, lowerY));
        var axes = shafts(ink, dyad, gap, -1);
        return !axes.isEmpty() && provesAttachedOctaveFingering(ink, dyad, axes, gap);
    }

    private static boolean fingeringBowlBand(Ink ink, int inner, int outer, int row, boolean bridge) {
        int hits = 0;
        for (int y = row - 1; y <= row + 1; y++)
            if (ink.dark(outer, y) && ink.dark(inner, y) == bridge) hits++;
        return hits >= 2;
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
        return outsideOctaveBeam(ink, axes, direction, gap, staff, .88f);
    }

    private static boolean outsideOctaveBeam(
            Ink ink, List<Shaft> axes, int direction, float gap, StaffPitchTrack staff, float reach) {
        int edge =
                axes.stream().mapToInt(shaft -> shaft.x * direction).max().orElseThrow()
                        * direction;
        int tip = axes.stream().mapToInt(Shaft::tip).max().orElseThrow();
        int top = tip - Math.round(gap * 1.45f), bottom = tip + Math.round(gap * .32f);
        int first = Math.round(gap * .32f), last = Math.round(gap * reach);
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
        return printedFourRailGroups(ink, regions, measure, pairs, columns, firstClaims,
                gap, rows, accidentalSpacedLink, compactOpening, wideChromatic, false);
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
            boolean compactOpening, boolean wideChromatic, boolean provedOpeningExtent) {
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
                if (shaft == null && columns.length == 12 && head.note.stemDirection() == -1)
                    shaft = joinedFadedDyadShaft(ink, dyad, gap);
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
        boolean tightNeighbors = !closed && columns.length == 12 && pairs.get(0).top.note.stemDirection() == -1
                && end.top.note.stemDirection() == 1
                && WrappedMeasureBoundaries.confirmsTightNeighborBoundedChromaticBar(
                    ink.raw, ink.width, ink.height, regions, measure,
                    columns[0].x(), columns[columns.length - 1].x(), track);
        closed |= tightNeighbors;
        boolean openingWithMeter = !closed
                && WrappedMeasureBoundaries.confirmsOpeningMeterWithVerifiedStaff(
                        ink.raw, ink.width, ink.height, regions, measure,
                        columns[0].x(), columns[columns.length - 1].x(), track,
                        columns.length == 12 ? "3/4" : "4/4");
        if (!closed && !openingWithMeter && !(provedOpeningExtent && measure == 0)) {
            if (!compactOpening) return false;
            int firstShaft = stems.get(0).stream().mapToInt(Shaft::x).min().orElseThrow();
            if (!WrappedMeasureBoundaries.confirmsCompleteShaftBoundedInitialStaffBar(
                    ink.raw, ink.width, ink.height, regions, measure,
                    columns[0].x(), columns[columns.length - 1].x(), firstShaft, track)) return false;
        }
        for (int first : firstClaims)
            if (claimedBracket(ink, columns, first, gap, rows, track,
                    openingWithMeter || wideChromatic || wideDownstemDyads || tightNeighbors,
                    tightNeighbors ? continuousDownstemTips(stems, first, gap) : wideDownstemDyads ? stems : null)) return false;
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
                float maximumSpan = wideChromatic ? 5.55f : i == accidentalSpacedLink ? ACCIDENTAL_SPACED_LINK : 3.2f;
                boolean joined =
                        !beams(ink, stems.get(i), stems.get(i + 1), a, b, gap, direction, track, maximumSpan)
                                .isEmpty();
                if (!joined && tightNeighbors && direction == 1
                        && columns[i + 1].x() - columns[i].x() < gap * 1.55f) {
                    var budget = new PrintedBeamShafts.Budget(85_000);
                    var left = sharedOpticalShaft(ink, a, gap, budget);
                    var right = sharedOpticalShaft(ink, b, gap, budget);
                    joined = left != null && right != null
                            && FingeringAnnotationFilter.pairedBeamRails(ink.raw, ink.width, ink.height, left, right, gap);
                }
                if (!joined && tightNeighbors)
                    joined = provesTightDyadRails(ink, stems.get(i), stems.get(i + 1), direction, gap);
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

    private static List<List<Shaft>> continuousDownstemTips(List<List<Shaft>> stems, int first, float gap) {
        var low = stems.get(first);
        var high = stems.get(first + 2);
        int leftTip = low.stream().mapToInt(Shaft::tip).min().orElseThrow();
        int rightTip = high.stream().mapToInt(Shaft::tip).min().orElseThrow();
        int leftX = low.get(0).x, rightX = high.get(0).x;
        if (rightX <= leftX || Math.abs(rightTip - leftTip) > .95f * gap) return stems;
        var accepted = new ArrayList<>(stems);
        for (int i = first; i <= first + 2; i++) {
            var match = stems.get(i).stream().filter(p ->
                    Math.abs(p.tip - (leftTip + (rightTip - leftTip) * (p.x - leftX) / (float)(rightX - leftX)))
                            <= .29f * gap).toList();
            if (match.size() < 2 || match.get(1).x - match.get(0).x != 1) return stems;
            accepted.set(i, match.subList(0, 2));
        }
        return accepted;
    }

    private static boolean provesTightDyadRails(Ink ink, List<Shaft> left, List<Shaft> right,
            int direction, float gap) {
        for (int i = 1; i < left.size(); i++) {
            var a = adjacentTipPair(left.get(i - 1), left.get(i), direction, gap);
            if (a == null) continue;
            for (int j = 1; j < right.size(); j++) {
                var b = adjacentTipPair(right.get(j - 1), right.get(j), direction, gap);
                if (b != null && FingeringAnnotationFilter.pairedBeamRails(
                        ink.raw, ink.width, ink.height, a, b, gap)) return true;
            }
        }
        return false;
    }

    private static PrintedBeamShafts.Shaft adjacentTipPair(Shaft a, Shaft b, int direction, float gap) {
        if (b.x - a.x != 1 || Math.abs(a.tip - b.tip) > .20f * gap) return null;
        int tip = (a.tip + b.tip) / 2;
        return new PrintedBeamShafts.Shaft(a.x, b.x, tip, tip, -direction,
                List.of(new PrintedBeamShafts.Column(a.x, a.tip), new PrintedBeamShafts.Column(b.x, b.tip)));
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
            Ink ink, Ink original, MeasureRegion bar, List<Head> source, List<ScoreNoteEvent> result) {
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        float gap = (bar.bottom() - bar.top()) * ink.height / 8.2f;
        if (gap < 10 || gap > 26) return false;
        float bottom = bar.bottom() * ink.height - 2.1f * gap;
        int clef = heads.get(0).note.clefBottomDiatonic();
        var columns = new ClosedFourDyadBeam.Head[16];
        var directions = new int[4];
        int missing = -1;
        boolean inferLowerStem = false;
        for (int slot = 0; slot < columns.length; slot++) {
            var a = heads.get(slot * 2);
            var b = heads.get(slot * 2 + 1);
            var upper = a.y < b.y ? a : b;
            var lower = a.y < b.y ? b : a;
            var topNote = upper.note;
            var bottomNote = lower.note;
            boolean lostUpperBeam = topNote.beamCount() == 0 && topNote.unbeamedDurationBeats() == 1;
            boolean lostLowerStem = lostUpperBeam && bottomNote.stemDirection() == 0;
            int direction = lostLowerStem ? topNote.stemDirection() : bottomNote.stemDirection();
            if (!ordinary(topNote, clef) || !ordinary(lostLowerStem ? bottomNote.withStemDirection(direction) : bottomNote, clef)
                    || topNote.augmentationDots() != 0 || bottomNote.augmentationDots() != 0
                    || direction == 0 || topNote.stemDirection() != direction
                    || bottomNote.beamCount() != 2 || bottomNote.unbeamedDurationBeats() != 0
                    || Math.abs(topNote.staffStep() - bottomNote.staffStep()) != 7
                    || b.x - a.x > gap * .16f
                    || lower.y - upper.y < gap * 3.15f
                    || lower.y - upper.y > gap * 3.8f) return false;
            if (slot % 4 == 0) directions[slot / 4] = -direction;
            else if (directions[slot / 4] != -direction) return false;
            if (lostUpperBeam) {
                if (missing >= 0 || direction != -1 || slot % 4 == 0 || slot % 4 == 3) return false;
                missing = slot;
                inferLowerStem = lostLowerStem;
            } else if (topNote.beamCount() != 2 || topNote.unbeamedDurationBeats() != 0) {
                return false;
            }
            columns[slot] = new ClosedFourDyadBeam.Head((a.x + b.x) * .5f, upper.y, lower.y);
        }
        if (missing < 0) return false;
        if (inferLowerStem) {
            if (missing / 4 != 3 || !brokenLowerOctaveQuartet(ink, bar, heads, missing, gap, bottom)
                    || !brokenLowerOctaveQuartet(original, bar, heads, missing, gap, bottom)) return false;
        } else if (!steppedOctaveRails(ink, bar, columns, directions, missing, gap, bottom)) {
            return false;
        }
        var headA = heads.get(missing * 2);
        var headB = heads.get(missing * 2 + 1);
        var upper = headA.y < headB.y ? headA : headB;
        var lower = upper == headA ? headB : headA;
        result.set(upper.owner, asSharedBeam(upper.note, 2));
        if (inferLowerStem) result.set(lower.owner, lower.note.withStemDirection(-1));
        if (closes(source, result)) return true;
        result.set(upper.owner, upper.note);
        if (inferLowerStem) result.set(lower.owner, lower.note);
        return false;
    }

    private static boolean brokenLowerOctaveQuartet(Ink ink, MeasureRegion bar, List<Head> heads,
            int missing, float gap, float hint) {
        var rails = DottedDyadMeasureSeparator.localRails(
                ink.raw, ink.width, ink.height, Math.round(bar.left() * ink.width), hint, gap);
        if (rails == null) return false;
        var staff = StaffPitchTrack.linear(ink.width(), rails[0], rails[1], 0);
        if (!WrappedMeasureBoundaries.confirmsClosedWithVerifiedStaff(
                ink.raw, ink.width, ink.height, bar, heads.get(0).x, heads.get(heads.size() - 1).x, staff)) return false;
        var pairs = new ArrayList<Dyad>(4);
        var axes = new ArrayList<List<Shaft>>(4);
        for (int i = 12; i < 16; i++) {
            var a = heads.get(i * 2);
            var b = heads.get(i * 2 + 1);
            var pair = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
            var candidates = i == missing ? shortShaftBreak(ink, pair, gap) : shafts(ink, pair, gap, -1);
            if (candidates.isEmpty()) return false;
            pairs.add(pair);
            axes.add(candidates);
        }
        if (!outsideOctaveBeam(ink, axes.get(0), -1, gap, staff)
                || !outsideOctaveBeam(ink, axes.get(axes.size() - 1), 1, gap, staff)) return false;
        for (int i = 0; i + 1 < pairs.size(); i++)
            if (beams(ink, axes.get(i), axes.get(i + 1), pairs.get(i), pairs.get(i + 1),
                    gap, -1, staff).isEmpty()) return false;
        return true;
    }

    private static List<Shaft> shortShaftBreak(Ink ink, Dyad pair, float gap) {
        var witness = PrintedBeamShafts.at(ink.raw, ink.width, ink.height, pair.top.x, pair.top.y,
                gap, new PrintedBeamShafts.Budget(60_000));
        if (witness.size() != 1 || witness.get(0).direction() != 1) return List.of();
        var result = new ArrayList<Shaft>();
        for (var col : witness.get(0).columns()) {
            float through = col.endY() - pair.bottom.y;
            if (through < gap * 1.2f || through > gap * 1.75f) continue;
            int restart = col.endY() + 1;
            while (restart - col.endY() <= gap * .46f && !ink.dark(col.x(), restart)) restart++;
            if (restart - col.endY() < gap * .2f || restart - col.endY() > gap * .46f) continue;
            int end = restart;
            while (end - pair.bottom.y <= gap * 4.10f && ink.dark(col.x(), end)) end++;
            if (end - restart >= gap * 1.45f && end - pair.bottom.y <= gap * 4.10f)
                result.add(new Shaft(col.x(), end - 1));
        }
        return result;
    }

    private static boolean steppedOctaveRails(Ink ink, MeasureRegion bar, ClosedFourDyadBeam.Head[] columns,
            int[] directions, int missing, float gap, float bottom) {
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
        return true;
    }

    private static boolean closeToEighth(
            Ink ink, Ink original, MeasureRegion region, boolean opening,
            List<MeasureRegion> regions, List<Head> heads, List<ScoreNoteEvent> result, boolean allowBrokenTerminal) {
        heads.sort(Comparator.comparingDouble(Head::x));
        var terminal = heads.subList(28, 30);
        int terminalDirection = terminal.get(0).note.stemDirection();
        boolean intact = terminalDirection != 0 && terminal.stream().allMatch(head -> head.note.beamCount() == 1
                && head.note.stemDirection() == terminalDirection && head.note.unbeamedDurationBeats() == 0);
        if (!intact && !allowBrokenTerminal) return false;
        float gap = (region.bottom() - region.top()) * ink.height / 8.2f;
        if (gap < 12
                || gap > 23
                || (region.right() - region.left()) * ink.width < gap * 42
                || (region.right() - region.left()) * ink.width > gap * 62
                || !terminalEighthBoundaries(ink, region, opening && intact, heads, intact))
            return false;
        if (intact && (original == null || !terminalEighthBoundaries(original, region, opening, heads, true))) return false;

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
            else return false;
        }
        int clef = heads.get(0).note.clefBottomDiatonic();
        var geometry = new ClosedFourDyadBeam.Head[15];
        int tripletClaims = 0;
        for (int slot = 0; slot < 15; slot++) {
            var a = heads.get(slot * 2);
            var b = heads.get(slot * 2 + 1);
            if (b.x - a.x > gap * .15f
                    || Math.abs(b.y - a.y) < gap * .65f
                    || Math.abs(b.y - a.y) > gap * 1.4f) return false;
            int actual = a.note.tupletDivisor();
            if (actual != 1 && actual != 3) return false;
            if (actual == 3) tripletClaims |= 1 << slot;
            int direction = slot >= 12 && intact ? terminalDirection : -1;
            for (var head : List.of(a, b)) {
                var n = head.note;
                boolean allowIncomingTie = intact && slot == 0 && actual == 1;
                if (!ordinaryGeometry(n, clef, allowIncomingTie)
                        || n.augmentationDots() != 0
                        || n.tupletDivisor() != actual
                        || n.tupletNormalNotes() != (actual == 3 ? 2 : 1)) return false;
                if (slot < 14
                        && (n.beamCount() != 2
                                || n.stemDirection() != direction
                                || n.unbeamedDurationBeats() != 0)) return false;
            }
            var upper = a.y < b.y ? a : b;
            var lower = a.y < b.y ? b : a;
            if (slot == 14 && actual != 1) return false;
            if (slot == 14 && !intact
                    && (upper.note.beamCount() != 0
                            || upper.note.stemDirection() != -1
                            || upper.note.unbeamedDurationBeats() != 1
                            || lower.note.beamCount() != 1
                            || lower.note.stemDirection() != 0
                            || lower.note.unbeamedDurationBeats() != 0)) return false;
            geometry[slot] = new ClosedFourDyadBeam.Head((a.x + b.x) * .5f, upper.y, lower.y);
        }
        if (!hasContainedTripletClaims(tripletClaims)) return false;
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
            if (!roomForPrintedAccidentals) return false;
            boolean normalRails = ClosedFourDyadBeam.provesWithAccidentalSpacing(
                            ink.raw, ink.width, ink.height, group, gap, 1)
                    && (!intact || ClosedFourDyadBeam.provesWithAccidentalSpacing(
                            original.raw, original.width, original.height, group, gap, 1));
            if (!normalRails && !(intact
                    && provesOpeningQuartetFromJoinedDyadShafts(ink, region, heads.subList(i * 2, i * 2 + 8), gap)
                    && provesOpeningQuartetFromJoinedDyadShafts(original, region, heads.subList(i * 2, i * 2 + 8), gap))) return false;
        }
        var close = java.util.Arrays.copyOfRange(geometry, 12, 15);
        if (intact) {
            if (!ClosedFourDyadBeam.provesIntactTwoSixteenthsAndEighth(
                            ink.raw, ink.width, ink.height, close, gap, -terminalDirection)
                    || !ClosedFourDyadBeam.provesIntactTwoSixteenthsAndEighth(
                            original.raw, original.width, original.height, close, gap, -terminalDirection)) return false;
        } else if (!ClosedFourDyadBeam.provesTwoSixteenthsAndEighth(
                ink.raw, ink.width, ink.height, close, gap)) return false;
        if (intact) {
            var normalizedTrack = WrappedMeasureBoundaries.verifiedTripletStaff(
                    ink.raw, ink.width, ink.height, region, true);
            var originalTrack = WrappedMeasureBoundaries.verifiedTripletStaff(
                    original.raw, original.width, original.height, region, true);
            if (normalizedTrack == null || originalTrack == null) return false;
            var rows = UnnumberedTripletInk.sourceRows(region, regions, ink.height);
            for (int i = 0; i < 12; i += 4) {
                int claims = tripletClaims >> i & 0xf;
                if (claims == 0) continue;
                int first = i + Integer.numberOfTrailingZeros(claims);
                if (claimedBracket(ink, geometry, first, gap, rows, normalizedTrack, false)
                        || claimedBracket(original, geometry, first, gap, rows, originalTrack, false)) return false;
            }
        }
        var revised = new ArrayList<ScoreNoteEvent>(30);
        for (int i = 0; i < 30; i++) {
            var note = heads.get(i).note;
            revised.add(i < 28 ? note.withTupletRatio(1, 1)
                    : intact ? note : asSharedBeam(note, 1));
        }
        if (intact && tripletClaims == 7) {
            int upperIndex = heads.get(0).y < heads.get(1).y ? 0 : 1;
            var upper = heads.get(upperIndex);
            var lower = heads.get(1 - upperIndex);
            if (upper.note.staffStep() == 15 && lower.note.staffStep() == 12
                    && thirdUpperLedger(ink, region, upper, lower)
                    && thirdUpperLedger(original, region, upper, lower))
                revised.set(upperIndex, withStaffStep(revised.get(upperIndex), 14));
        }
        for (int i = 0; i < 30; i++) {
            var placed = ScoreNoteTiming.writtenPlacement(revised.get(i), revised, 4f).orElse(null);
            if (placed == null
                    || Math.abs(placed.onsetBeats() - (i / 2) * .25) > 1e-6
                    || Math.abs(placed.durationBeats() - (i < 28 ? .25 : .5)) > 1e-6) return false;
        }
        for (int i = 0; i < 30; i++) result.set(heads.get(i).owner, revised.get(i));
        return intact;
    }

    private static boolean terminalEighthBoundaries(
            Ink ink, MeasureRegion region, boolean opening, List<Head> heads, boolean intact) {
        if (printedBoundaries(ink, region, opening, heads)) return true;
        if (opening || !intact) return false;
        var track = WrappedMeasureBoundaries.verifiedTripletStaff(ink.raw, ink.width, ink.height, region, true);
        return track != null && WrappedMeasureBoundaries.confirmsClosedWithVerifiedStaff(
                ink.raw, ink.width, ink.height, region, heads.get(0).x, heads.get(29).x, track);
    }

    static ScoreBeamSource projectTerminalEighthRails(
            ScoreBeamSource source, int page, List<Head> heads) {
        return projectRailGroups(source, page, heads, 2,
                List.of(List.of(3, 3, 3), List.of(3, 3, 3), List.of(3, 3, 3), List.of(3, 1)));
    }

    /** A displaced head may round to a space even when both wings of its ledger are printed. */
    private static boolean thirdUpperLedger(Ink ink, MeasureRegion region, Head upper, Head lower) {
        var track = WrappedMeasureBoundaries.verifiedTripletStaff(ink.raw, ink.width, ink.height, region);
        if (track == null || !track.verifiedAt(upper.x)) return false;
        float[] staff = track.at(upper.x);
        float gap = staff[1];
        int x = Math.round(upper.x);
        int inner = Math.round(gap * .70f), outer = Math.round(gap * 1.02f);
        if (x - outer < 0 || x + outer >= ink.width) return false;
        float[] ledger = new float[3];
        for (int i = 0; i < ledger.length; i++) {
            float expected = staff[0] - (5 + i) * gap;
            int from = Math.max(0, (int) Math.ceil(expected - .39f * gap));
            int to = Math.min(ink.height - 1, (int) Math.floor(expected + .39f * gap));
            int first = -1, last = -1;
            for (int y = from; y <= to; y++) {
                int left = 0, right = 0;
                for (int j = inner; j <= outer; j++) {
                    if ((ink.raw[y * ink.width + x - j] & 255) < 150) left++;
                    if ((ink.raw[y * ink.width + x + j] & 255) < 150) right++;
                }
                if (Math.min(left, right) < outer - inner) continue;
                if (last >= 0 && y != last + 1) return false;
                if (first < 0) first = y;
                last = y;
            }
            if (first < 0 || last - first < 1 || last - first > .28f * gap) return false;
            ledger[i] = (first + last) * .5f;
            if (i > 0 && Math.abs(ledger[i - 1] - ledger[i] - gap) > gap * .22f) return false;
        }
        return Math.abs(upper.y - ledger[2]) < gap * .15f
                && Math.abs(lower.y - ledger[1]) < gap * .19f;
    }

    private static ScoreNoteEvent withStaffStep(ScoreNoteEvent n, int step) {
        return new ScoreNoteEvent(n.measureIndex(), n.positionInMeasure(), step, n.staffIndex(),
                n.staffCount(), n.pageY(), n.tiedFromPrevious(), n.augmentationDots(), n.beamCount(),
                n.writtenAccidental(), n.unbeamedDurationBeats(), n.tupletDivisor(), n.followingRestBeats(),
                n.articulations(), n.clefBottomDiatonic(), n.crossStaffBeam(), n.leadingRestBeats(),
                n.compactOpening(), n.octaveShift(), n.boundaryTies(), n.tupletNormalNotes(),
                n.stemDirection(), n.kind(), n.visualSize(), n.harmonicSemitones(), n.openStringTouch());
    }

    private static boolean provesOpeningQuartetFromJoinedDyadShafts(
            Ink ink, MeasureRegion bar, List<Head> heads, float gap) {
        var track = WrappedMeasureBoundaries.verifiedTripletStaff(ink.raw, ink.width, ink.height, bar, true);
        if (track == null) return false;
        var budget = new PrintedBeamShafts.Budget(120_000);
        PrintedBeamShafts.Shaft first = null, previous = null;
        for (int i = 0; i < heads.size(); i += 2) {
            var a = heads.get(i);
            var b = heads.get(i + 1);
            var pair = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
            var axis = sharedOpticalShaft(ink, pair, gap, budget);
            if (axis == null) axis = joinedFadedDyadShaft(ink, pair, gap);
            if (axis == null || budget.refused()
                    || previous != null && !exactShaftDoubleRails(ink, previous, axis, track, gap))
                return false;
            if (first == null) first = axis;
            previous = axis;
        }
        return first != null
                && outsideOctaveBeam(ink, List.of(new Shaft(first.left(), first.endY())), -1, gap, track)
                && outsideOctaveBeam(ink, List.of(new Shaft(previous.right(), previous.endY())), 1, gap, track);
    }

    private static PrintedBeamShafts.Shaft joinedFadedDyadShaft(Ink ink, Dyad pair, float gap) {
        var upper = pair.top;
        var lower = pair.bottom;
        var stem = FadedTripletDownshaft.measure(ink.raw, ink.width, ink.height, lower.x, lower.y, gap);
        if (stem == null) return null;
        int top = Math.round(upper.y + .22f * gap), end = Math.round(lower.y + .24f * gap);
        if (top < 0 || end >= ink.height || top >= end) return null;
        var attached = new ArrayList<PrintedBeamShafts.Column>();
        for (var line : stem.columns()) {
            int x = line.x();
            float attachment = upper.x - x;
            if (attachment < .48f * gap || attachment > .81f * gap) continue;
            int dark = 0, weak = 0, consecutive = 0;
            for (int y = top; y <= end; y++) {
                if ((ink.raw[y * ink.width + x] & 255) < 150) { dark++; consecutive = 0; }
                else weak = Math.max(weak, ++consecutive);
            }
            if (weak <= 2 && dark >= .88f * (end - top + 1)) attached.add(line);
        }
        if (attached.size() != 2 || attached.get(1).x() - attached.get(0).x() != 1) return null;
        return new PrintedBeamShafts.Shaft(attached.get(0).x(), attached.get(1).x(),
                top, stem.endY(), 1, List.copyOf(attached));
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

    /** Recheck beamed harmonic attacks when two detached quarter-ending marks were read as dots. */
    private static void closeHarmonicStaccatoSixteenths(
            Ink ink, Ink raw, MeasureRegion bar, List<Head> source, List<ScoreNoteEvent> result) {
        float gap = (bar.bottom() - bar.top()) * ink.height / 8.2f;
        if (gap < 12 || gap > 22
                || source.stream().filter(h -> h.note.octaveShift() == 2).count() < 10
                || source.stream().filter(h -> h.note.augmentationDots() == 1).count() != 2)
            return;
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var columns = new ArrayList<List<Head>>();
        int clef = heads.get(0).note.clefBottomDiatonic();
        for (var head : heads) {
            var n = head.note;
            if (!ordinaryGeometry(n, clef) || n.beamCount() != 2 || n.unbeamedDurationBeats() != 0
                    || n.augmentationDots() > 1 || n.tupletDivisor() != 1 || n.tupletNormalNotes() != 1)
                return;
            var column = columns.isEmpty() ? List.<Head>of() : columns.get(columns.size()-1);
            if (!column.isEmpty() && head.x - column.get(0).x <= gap * .22f) {
                if (column.size() != 1 || Math.abs(head.y - column.get(0).y) < gap * 1.20f
                        || Math.abs(head.y - column.get(0).y) > gap * 1.74f
                        || head.note.stemDirection() != column.get(0).note.stemDirection()) return;
                column.add(head);
            } else {
                if (!column.isEmpty()) {
                    float advance = (head.x - column.get(0).x) / gap;
                    if (advance < 1.55f || advance > 2.95f) return;
                }
                columns.add(new ArrayList<>(List.of(head)));
            }
        }
        if (columns.size() != 16) return;
        var axes = columns.stream()
                .map(c -> c.stream().max(Comparator.comparingDouble(Head::y)).orElseThrow()).toList();
        for (int i = 0; i < axes.size(); i++) {
            int dots = i == 6 || i == 10 ? 1 : 0;
            int direction = i < 12 ? 1 : -1;
            if (columns.get(i).size() > 1 && dots != 0
                    || axes.get(i).note.augmentationDots() != dots
                    || axes.get(i).note.stemDirection() != direction
                    || i % 4 == 3 && (axes.get(i).note.articulations() & NoteArticulation.STACCATO) == 0)
                return;
        }
        if (!harmonicRailsAndStaccatos(ink, bar, axes, gap)
                || !harmonicRailsAndStaccatos(raw, bar, axes, gap)) return;
        var corrected = new ArrayList<ScoreNoteEvent>();
        for (var column : columns)
            for (var head : column)
                corrected.add(head.note.augmentationDots() == 0 ? head.note : withoutDot(head.note));
        int owner = 0;
        for (int i = 0; i < columns.size(); i++) {
            for (var ignored : columns.get(i)) {
                var placement = ScoreNoteTiming.writtenPlacement(corrected.get(owner++), corrected, 4).orElse(null);
                if (placement == null || placement.onsetBeats() != i * .25 || placement.durationBeats() != .25)
                    return;
            }
        }
        for (var note : List.of(axes.get(6), axes.get(10))) result.set(note.owner, withoutDot(note.note));
    }

    private static boolean harmonicRailsAndStaccatos(
            Ink ink, MeasureRegion bar, List<Head> axes, float gap) {
        var budget = new PrintedBeamShafts.Budget(410_000);
        var stems = new ArrayList<PrintedBeamShafts.Shaft>();
        for (var head : axes) {
            var found = PrintedBeamShafts.at(ink.raw, ink.width, ink.height, head.x, head.y, gap, budget)
                    .stream().filter(s -> s.direction() == -head.note.stemDirection()).toList();
            if (budget.refused() || found.size() != 1) return false;
            stems.add(found.get(0));
        }
        for (int i = 0; i < stems.size() - 1; i++) {
            boolean connects = WrappedEighthTriplets.matchesBeamRails(ink.raw, ink.width, ink.height,
                    stems.get(i), stems.get(i+1), gap, 2);
            if (connects != (i % 4 != 3)) return false;
        }
        if (!WrappedMeasureBoundaries.confirmsPackedRightEdgeFromStem(ink.raw, ink.width, ink.height,
                bar, axes.get(0).x, axes.get(15).x, stems.get(15).left())) return false;
        for (int i : new int[]{6,10}) {
            var before = axes.get(i);
            var next = axes.get(i+1);
            if (!separateLowerStaccatoDot(ink, next, gap)
                    || LegacyTripletDotInk.singleDotCore(ink.raw, ink.width, ink.height,
                            before.x, before.y, next.x, gap)
                    || WrappedTripletDotInk.uniqueCore(ink.raw, ink.width, ink.height,
                            before.x, before.y, next.x, gap) != null) return false;
        }
        return true;
    }

    private static boolean separateLowerStaccatoDot(Ink ink, Head next, float gap) {
        int left = Math.round(next.x - .25f * gap), right = Math.round(next.x + .25f * gap);
        int top = Math.round(next.y + .80f * gap), bottom = Math.round(next.y + 1.36f * gap);
        if (left < 0 || top < 0 || right >= ink.width || bottom >= ink.height) return false;
        var marks = RawNumeralComponents.capture(ink.raw, ink.width, ink.height, left, top, right,
                bottom, 110, () -> {}, () -> {}).components();
        if (marks.size() != 1) return false;
        var dot = marks.get(0);
        int w = dot.right - dot.left + 1, h = dot.bottom - dot.top + 1;
        return dot.left > left && dot.right < right && dot.top > top && dot.bottom < bottom
                && w >= gap * .23f && h >= gap * .23f && w <= gap * .42f && h <= gap * .42f
                && dot.size() >= w * h * .68f;
    }

    /** A following double stop's lower staccato may sit beside the prior head in a straight bar. */
    private static boolean reassignFollowingDyadStaccatos(
            Ink ink, Ink original, MeasureRegion bar, List<Head> source,
            ScoreSlurSource slurs, List<ScoreNoteEvent> result) {
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
        var staccatos = new ArrayList<Head>();
        for (int i = 0; i < pairs.size(); i++) {
            var before = pairs.get(i);
            if (before.top.note.augmentationDots() == 0) continue;
            if (i + 1 == pairs.size()) return false;
            var next = pairs.get(i + 1);
            if (followingDyadStaccato(ink, before, next, gap, sixths)
                    && followingDyadStaccato(original, before, next, gap, sixths)) {
                staccatos.add(next.top);
                staccatos.add(next.bottom);
            } else if (!sixths && i > 0 && slurredLowerStaccato(ink, original, slurs,
                    pairs.get(i - 1).bottom, before, next, gap)) {
                staccatos.add(next.bottom);
            } else {
                return false;
            }
            dotted.add(i);
        }
        for (int i : dotted) {
            var before = pairs.get(i);
            result.set(before.top.owner, withoutDot(before.top.note));
            result.set(before.bottom.owner, withoutDot(before.bottom.note));
        }
        for (var head : staccatos) {
            var note = result.get(head.owner);
            result.set(head.owner, note.withArticulations(note.articulations() | NoteArticulation.STACCATO));
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
        return followsSharedRails(ink, before, next, gap);
    }

    /** A staccato inside a lower slur can be displaced left of its closing head. */
    private static boolean slurredLowerStaccato(
            Ink ink, Ink original, ScoreSlurSource slurs, Head start, Dyad before, Dyad next, float gap) {
        float advance = (next.bottom.x - before.bottom.x) / gap;
        float rise = (before.bottom.y - next.bottom.y) / gap;
        if (advance < 1.80f || advance > 2.24f || rise < .42f || rise > .63f
                || before.bottom.note.stemDirection() != 1 || next.bottom.note.stemDirection() != 1
                || next.bottom.note.augmentationDots() != 0) return false;
        var begin = slurs.noteOrigins().get(start.owner).orElse(null);
        var end = slurs.noteOrigins().get(next.bottom.owner).orElse(null);
        if (begin == null || end == null || begin.pageIndex() != end.pageIndex()) return false;
        var evidence = java.util.stream.Stream.concat(slurs.spans().stream(), slurs.candidates().stream())
                .filter(span -> span.start().equals(begin) && span.end().equals(end)
                        && span.placement() == ScoreSlurSpan.Placement.BELOW
                        && span.lineType() == ScoreSlurSpan.LineType.SOLID
                        && span.evidence().size() == 1)
                .map(span -> span.evidence().get(0))
                .filter(arc -> arc.pageIndex() == begin.pageIndex()
                        && Math.abs(arc.startX() * ink.width - start.x) <= gap * .34f
                        && Math.abs(arc.endX() * ink.width - next.bottom.x) <= gap * .30f)
                .toList();
        if (evidence.size() != 1) return false;
        var arc = evidence.get(0);
        var a = slurInteriorDot(ink, before, next, arc, gap);
        var b = slurInteriorDot(original, before, next, arc, gap);
        return a != null && a.equals(b)
                && followsSharedRails(ink, before, next, gap)
                && followsSharedRails(original, before, next, gap);
    }

    private static LegacyTripletDotInk.Core slurInteriorDot(
            Ink ink, Dyad before, Dyad next, ScoreSlurSpan.ArcEvidence arc, float gap) {
        var dot = LegacyTripletDotInk.uniqueDotCore(ink.raw, ink.width, ink.height,
                before.bottom.x, before.bottom.y, next.bottom.x + gap * .23f, gap);
        if (dot == null) return null;
        float x = (dot.left() + dot.right()) * .5f;
        float y = (dot.top() + dot.bottom()) * .5f;
        float beforeHead = (next.bottom.x - x) / gap;
        float belowHead = (y - next.bottom.y) / gap;
        float insideSlur = (arc.endY() * ink.height - dot.bottom()) / gap;
        if (beforeHead < .45f || beforeHead > .73f || belowHead < .66f || belowHead > .97f
                || insideSlur < .16f || insideSlur > .51f
                || WrappedTripletDotInk.uniqueCore(ink.raw, ink.width, ink.height,
                        before.top.x, before.top.y, next.top.x, gap) != null
                || LegacyTripletDotInk.singleDotCore(ink.raw, ink.width, ink.height,
                        before.top.x, before.top.y, next.top.x, gap)) return null;
        return dot;
    }

    private static boolean followsSharedRails(Ink ink, Dyad before, Dyad next, float gap) {
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
        if (source.stream().noneMatch(h -> h.note.augmentationDots() == 1)) return false;
        var region = regions.get(measure);
        float gap = (region.bottom() - region.top()) * ink.height / 8.2f;
        if (gap < 12 || gap > 24) return false;
        var heads = source.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var pairs = new ArrayList<Dyad>(16);
        var columns = new ClosedFourDyadBeam.Head[16];
        int clef = heads.get(0).note.clefBottomDiatonic();
        int direction = 0;
        int interval = Math.abs(heads.get(0).note.staffStep() - heads.get(1).note.staffStep());
        boolean thirds = interval == 2;
        if (thirds && source.stream().filter(h -> h.note.augmentationDots() != 0).count() != 2
                || !thirds && interval != 5 && interval != 7) return false;
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
                        || n.beamCount() != 2
                                && (thirds || sixths || n.beamCount() != 1 || n.augmentationDots() != 1))
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
                if (thirds) {
                    if (slot % 4 == 0 && dots == 2 && isolatedFleckBesideRisingThird(plane, before, next, gap))
                        continue;
                    return false;
                }
                if (sixths) {
                    if (dots == 2 && (followingUpperPortato(plane, before, next, gap)
                            || slot == pairs.size() - 3 && followingFingeredSixth(plane, before, next, gap)))
                        continue;
                    return false;
                }
                if (dots == 2 && (followingDyadStaccato(plane, before, next, gap, false)
                        || followingUpperStaccato(plane, before, next, gap))) continue;
                if (dots == 2 && before.top.note.stemDirection() == 1
                        && before.top.note.beamCount() == 2
                        && !hasSeparateAugmentation(plane, before, next, gap, false)
                        && unbrokenUpperDyadLink(plane, before, next, gap)) continue;
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

    private static boolean unbrokenUpperDyadLink(Ink ink, Dyad before, Dyad next, float gap) {
        var budget = new PrintedBeamShafts.Budget(45_000);
        var left = sharedOpticalShaft(ink, before, gap, budget);
        var right = sharedOpticalShaft(ink, next, gap, budget);
        if (left == null || right == null || left.direction() != -1 || right.direction() != -1
                || budget.refused()) return false;
        int first = (left.left() + left.right()) / 2;
        int last = (right.left() + right.right()) / 2;
        if (last - first < gap) return false;
        int missing = 0;
        for (int x = first + 2; x <= last - 2; x++) {
            int tip = Math.round(left.endY() + (right.endY() - left.endY()) * (x - first) / (float) (last - first));
            boolean inkOnLink = false;
            for (int y = tip - Math.round(.14f * gap); y <= tip + Math.round(1.45f * gap); y++) {
                if (ink.dark(x, y)) {
                    inkOnLink = true;
                    break;
                }
            }
            missing = inkOnLink ? 0 : missing + 1;
            if (missing >= Math.round(.24f * gap)) return false;
        }
        return true;
    }

    private static boolean isolatedFleckBesideRisingThird(Ink ink, Dyad before, Dyad next, float gap) {
        var lower = before.bottom;
        float advance = (next.bottom.x - lower.x) / gap;
        float rise = (lower.y - next.bottom.y) / gap;
        if (lower.note.stemDirection() != 1 || next.bottom.note.stemDirection() != 1
                || advance < 2.24f || advance > 2.68f || rise < .83f || rise > 1.16f
                || hasSeparateAugmentation(ink, before, next, gap, false)) return false;
        int left = Math.round(lower.x + gap * .80f), right = Math.round(lower.x + gap * 1.36f);
        int top = Math.round(lower.y + gap * .18f), bottom = Math.round(lower.y + gap * .72f);
        if (left < 0 || right >= ink.width || bottom >= ink.height) return false;
        var crop = RawNumeralComponents.capture(
                ink.raw, ink.width, ink.height, left, top, right, bottom, 185, () -> {}, () -> {});
        if (crop.components().size() != 1) return false;
        var spot = crop.components().get(0);
        if (spot.left <= left || spot.right >= right || spot.top <= top || spot.bottom >= bottom
                || spot.size() > .053f * gap * gap) return false;
        int dark = 0, firstX = spot.right, lastX = spot.left;
        for (int i = 0; i < spot.size(); i++) {
            int pixel = spot.pixel(i);
            if ((ink.raw[pixel] & 255) >= 120) continue;
            int x = pixel % ink.width;
            firstX = Math.min(firstX, x);
            lastX = Math.max(lastX, x);
            dark++;
        }
        return dark >= 2 && dark <= .026f * gap * gap && lastX - firstX + 1 <= .16f * gap;
    }


    private static boolean followingFingeredSixth(Ink ink, Dyad before, Dyad next, float gap) {
        float advance = (next.top.x - before.top.x) / gap;
        float descent = (next.top.y - before.top.y) / gap;
        if (before.top.note.stemDirection() != -1 || next.top.note.stemDirection() != -1
                || advance < 1.65f || advance > 2.25f || descent < .36f || descent > .65f
                || next.top.note.augmentationDots() != 0 || next.bottom.note.augmentationDots() != 0
                || hasSeparateAugmentation(ink, before, next, gap, false)) return false;
        var head = next.top;
        int left = Math.round(head.x - .90f * gap), right = Math.round(head.x + .9f * gap);
        int top = Math.round(head.y - 2.45f * gap), bottom = Math.round(head.y - .60f * gap);
        if (left < 0 || right >= ink.width || top < 0 || bottom >= ink.height) return false;
        var crop = RawNumeralComponents.capture(
                ink.raw, ink.width, ink.height, left, top, right, bottom, 150, () -> {}, () -> {});
        int owned = 0;
        for (var glyph : crop.components()) {
            float center = (glyph.left + glyph.right) * .5f;
            float height = glyph.bottom - glyph.top + 1;
            if (glyph.left <= left || glyph.right >= right || glyph.top <= top || glyph.bottom >= bottom
                    || Math.abs(center - head.x) > .28f * gap
                    || height < .85f * gap || height > 1.45f * gap
                    || !TripletRhythmDetector.isIsolatedThree(glyph, ink.width)) continue;
            owned++;
        }
        return owned == 1;
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

    /** A false triplet and a borrowed dot can shorten one otherwise closed four-chord beat. */
    static ScorePageInterpretation restoreStraightDottedQuartets(
            ScorePageInterpretation score, ScoreMeterChange inherited, byte[] gray, byte[] raw, int w, int h, Consumer<ScoreNoteEvent> printedCross) {
        if (raw == null || gray.length != (long) w * h || raw.length != gray.length) return score;
        var normal = new Ink(gray,w,h);
        var original = new Ink(raw,w,h);
        var notes = score.notes();
        var regions = score.measures();
        List<ScoreNoteEvent> edits = null;
        for (var group : score.beamSource().resolve().groups()) {
            if (group.source().tupletMark() != null || !group.source().hooks().isEmpty()
                    || !group.source().bridges().equals(List.of(3,3,3))
                    || group.columns().stream().anyMatch(c -> c.ambiguous() || c.owners().size()!=2)) continue;
            int first=group.columns().get(0).owners().get(0);
            var opening=notes.get(first);
            int m=opening.measureIndex(),direction=opening.stemDirection();
            if (m<0 || m>=regions.size() || Math.abs(direction)!=1 || opening.tupletDivisor()!=3) continue;
            var bar=regions.get(m);
            float gap=(bar.bottom()-bar.top())*h/8.2f;
            if (gap<10 || gap>26 || bar.cadenceQuarterBeats()!=0) continue;
            var pairs=new ArrayList<Dyad>(4);
            var claimed=new java.util.HashSet<Integer>();
            for (int i=0;i<4;i++) {
                var owners=group.columns().get(i).owners();
                var a=quartetHead(notes,bar,owners.get(0),w,h);
                var b=quartetHead(notes,bar,owners.get(1),w,h);
                if (a.note.measureIndex()!=m || b.note.measureIndex()!=m || !sameShaftDyadGeometry(
                        a.x<=b.x?a:b, a.x<=b.x?b:a, gap)
                        || !dottedQuartetNote(a.note,opening.clefBottomDiatonic(),direction,i)
                        || !dottedQuartetNote(b.note,opening.clefBottomDiatonic(),direction,i)
                        || !claimed.add(a.owner) || !claimed.add(b.owner)) break;
                pairs.add(a.y<b.y?new Dyad(a,b):new Dyad(b,a));
            }
            if (pairs.size()!=4) continue;
            ScoreMeterChange meter=inherited;
            for (var change:score.meterChanges()) {
                if(change.measureIndex()>m) break;
                meter=change;
            }
            if(meter==null || meter.denominator()!=4 || (meter.numerator()!=3 && meter.numerator()!=4)
                    || score.rests().stream().anyMatch(r -> r.measureIndex()==m)) continue;
            var row=new ArrayList<Head>();
            for(int i=0;i<notes.size();i++) if(notes.get(i).measureIndex()==m) row.add(quartetHead(notes,bar,i,w,h));
            if(row.size()!=meter.numerator()*8) continue;
            row.sort(Comparator.comparingDouble(Head::x));
            int start=0;
            while(start<row.size() && !claimed.contains(row.get(start).owner)) start++;
            if(start%8!=0 || start+8>=row.size() || row.subList(start,start+8).stream().anyMatch(x -> !claimed.contains(x.owner))
                    || row.stream().anyMatch(x -> !claimed.contains(x.owner) && (x.note.beamCount()!=2
                        || x.note.tupletDivisor()!=1 || x.note.augmentationDots()!=0
                        || x.note.unbeamedDurationBeats()!=0 || x.note.staffCount()!=1
                        || x.note.kind()!=ScoreNoteEvent.Kind.PITCHED))) continue;
            var a=row.get(start+8);
            var b=row.get(start+9);
            var next=a.y<b.y?new Dyad(a,b):new Dyad(b,a);
            if(!sameShaftDyadGeometry(a,b,gap)) continue;
            var crop=pairs.stream().map(pair -> new ClosedFourDyadBeam.Head(
                    (pair.top.x+pair.bottom.x)*.5f,pair.top.y,pair.bottom.y)).toArray(ClosedFourDyadBeam.Head[]::new);
            var rows=UnnumberedTripletInk.sourceRows(bar,regions,h);
            var lower=next.bottom;
            if (lower.note.writtenAccidental()!=ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                    || lower.note.tiedFromPrevious() || lower.note.boundaryTies()!=0) continue;
            var cross=ClosedDoubleSharpBeams.uniqueCross(gray,raw,w,h,pairs.get(3).bottom.x,lower,gap);
            if(cross==null || !borrowedQuartetDot(original,pairs.get(3),next,cross,gap)
                    || !borrowedQuartetDot(normal,pairs.get(3),next,cross,gap)
                    || !straightDottedQuartet(original,bar,pairs,crop,gap,-direction,rows)
                    || !straightDottedQuartet(normal,bar,pairs,crop,gap,-direction,rows)) continue;
            if(edits==null) edits=new ArrayList<>(notes);
            for(var pair:pairs) for(var owner:List.of(pair.top,pair.bottom)) {
                var note=owner.note;
                edits.set(owner.owner,note.augmentationDots()==0?note.withTupletRatio(1,1):withoutDot(note));
            }
            var marked=lower.note.withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_DOUBLE_SHARP);
            edits.set(lower.owner,marked);
            printedCross.accept(marked);
        }
        return edits==null?score:score.withNotes(edits,
                java.util.stream.IntStream.range(0,notes.size()).boxed().toList());
    }

    private static Head quartetHead(List<ScoreNoteEvent> notes,MeasureRegion bar,int i,int width,int height) {
        var n=notes.get(i);
        return new Head(i,n,(bar.left()+n.positionInMeasure()*(bar.right()-bar.left()))*width,n.pageY()*height);
    }

    private static boolean dottedQuartetNote(ScoreNoteEvent n,int clef,int direction,int slot) {
        if(slot<3) return ordinaryDoubleRail(n,clef,direction,true);
        return n.augmentationDots()==1 && ordinaryDoubleRail(withoutDot(n),clef,direction,false);
    }

    private static boolean borrowedQuartetDot(Ink ink,Dyad dotted,Dyad next,
            DoubleSharpGlyph.Crop cross,float gap) {
        float limit=next.bottom.x-gap*.17f;
        var upper=WrappedTripletDotInk.uniqueCore(ink.raw,ink.width,ink.height,dotted.top.x,dotted.top.y,limit,gap);
        var lower=WrappedTripletDotInk.uniqueCore(ink.raw,ink.width,ink.height,dotted.bottom.x,dotted.bottom.y,limit,gap);
        return upper==null && !LegacyTripletDotInk.singleDotCore(
                    ink.raw,ink.width,ink.height,dotted.top.x,dotted.top.y,limit,gap)
                && !LegacyTripletDotInk.singleDotCore(ink.raw,ink.width,ink.height,dotted.bottom.x,dotted.bottom.y,limit,gap)
                && lower!=null && lower.x()>=cross.left() && lower.x()<=cross.right()
                && lower.y()>=cross.top() && lower.y()<=cross.bottom()
                && ClosedDyadsBeforeHalf.sharpOwnsDotGap(ink.raw,ink.width,ink.height,gap,
                    dotted.bottom.x,dotted.bottom.y,next.bottom.x,next.bottom.y);
    }
    private static boolean straightDottedQuartet(Ink ink,MeasureRegion bar,List<Dyad> pairs,
            ClosedFourDyadBeam.Head[] columns,float gap,int direction,UnnumberedTripletInk.Rows rows) {
        if(!ClosedFourDyadBeam.provesWithAccidentalSpacing(ink.raw,ink.width,ink.height,columns,gap,direction)) return false;
        var middle=columns[1];
        var shaft=sharedOpticalShaft(ink,pairs.get(1),gap,new PrintedBeamShafts.Budget(45_000));
        if(shaft==null) return false;
        float top=Math.min(columns[0].top(),Math.min(columns[1].top(),columns[2].top()));
        float bottom=Math.max(columns[0].bottom(),Math.max(columns[1].bottom(),columns[2].bottom()));
        var track=WrappedMeasureBoundaries.verifiedTripletStaff(ink.raw,ink.width,ink.height,bar,true);
        float upperEdge=Math.min(top,shaft.endY());
        float lowerEdge=Math.max(bottom,shaft.endY());
        return !UnnumberedTripletInk.centeredThree(ink.raw,ink.width,ink.height,middle.x(),
                    upperEdge-4*gap,upperEdge+.15f*gap,gap,rows,track)
                && !UnnumberedTripletInk.centeredThree(ink.raw,ink.width,ink.height,middle.x(),
                    lowerEdge-.15f*gap,lowerEdge+4*gap,gap,rows,track)
                && !UnnumberedTripletInk.bracketBesideBeam(ink.raw,ink.width,ink.height,gap,
                    columns[0].x(),columns[2].x(),top,bottom,shaft.endY(),rows,track,true);
    }

    /** Recover a natural-sharp cancellation before a dyad while keeping the upper natural separate. */
    static ScorePageInterpretation restoreStackedSharpCancellation(ScorePageInterpretation score,
            byte[] gray, byte[] raw, int w, int h, Consumer<SourceLocalAccidental.ProvedMark> printed) {
        if (raw == null || gray.length != (long)w*h || raw.length != gray.length) return score;
        var notes=score.notes();
        List<ScoreNoteEvent> edits=null;
        for(var group:score.beamSource().resolve().groups()) {
            for(int i=1;i<group.columns().size();i++) {
                var before=group.columns().get(i-1);
                var after=group.columns().get(i);
                if(group.bridgeMask(i-1)!=3 || before.owners().size()!=2 || after.owners().size()!=2) continue;
                int first=after.owners().get(0),second=after.owners().get(1);
                int lower=notes.get(first).pageY()>notes.get(second).pageY()?first:second;
                int upper=lower==first?second:first;
                var n=notes.get(lower);
                var u=notes.get(upper);
                if(n.writtenAccidental()!=ScoreNoteEvent.ACCIDENTAL_DOUBLE_SHARP
                        || u.writtenAccidental()!=ScoreNoteEvent.ACCIDENTAL_NATURAL
                        || n.measureIndex()!=u.measureIndex() || n.stemDirection()!=u.stemDirection()
                        || !ordinaryDoubleRail(n,n.clefBottomDiatonic(),n.stemDirection(),false)
                        || !ordinaryDoubleRail(u,n.clefBottomDiatonic(),n.stemDirection(),false)) continue;
                int m=n.measureIndex();
                if(m<0 || m>=score.measures().size()) continue;
                var previous=before.owners().stream().map(notes::get).filter(p ->
                        p.measureIndex()==m && p.diatonicPitchIdentity()==n.diatonicPitchIdentity()
                        && p.writtenAccidental()==ScoreNoteEvent.ACCIDENTAL_DOUBLE_SHARP).toList();
                if(previous.size()!=1) continue;
                var box=score.measures().get(m);
                var lo=quartetHead(notes,box,lower,w,h);
                var hi=quartetHead(notes,box,upper,w,h);
                float gap=(box.bottom()-box.top())*h/8.2f;
                float space=(n.positionInMeasure()-previous.get(0).positionInMeasure())*(box.right()-box.left())*w;
                if(gap<10 || gap>26 || space<4.35f*gap || space>5.2f*gap
                        || Math.abs(lo.x-hi.x)>.18f*gap || lo.y-hi.y<.85f*gap || lo.y-hi.y>1.3f*gap) continue;
                if(!stackedSharpCancellation(raw,w,h,lo,hi,gap)
                        || !stackedSharpCancellation(gray,w,h,lo,hi,gap)) continue;
                if(edits==null) edits=new ArrayList<>(notes);
                var sharp=n.withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_SHARP);
                edits.set(lower,sharp);
                printed.accept(new SourceLocalAccidental.ProvedMark(sharp,true));
            }
        }
        return edits==null?score:score.withNotes(edits,
                java.util.stream.IntStream.range(0,notes.size()).boxed().toList());
    }

    private static boolean stackedSharpCancellation(byte[] plane,int w,int h,Head low,Head high,float gap) {
        float x=low.x;
        return OmrScoreInterpreter.provesBoundedNaturalInk(plane,w,h,x,low.y,gap,
                    Math.round(x-4.12f*gap),Math.round(x-3.05f*gap))
                && OmrScoreInterpreter.provesBoundedSharpInk(plane,w,h,x,low.y,gap,
                    Math.round(x-2.94f*gap),Math.round(x-1.81f*gap))
                && OmrScoreInterpreter.provesBoundedNaturalInk(plane,w,h,high.x,high.y,gap,
                    Math.round(high.x-1.75f*gap),Math.round(high.x-.68f*gap));
    }

    /** Four printed double-beamed chords can borrow a triplet claim from nearby fingerings. */
    static ScorePageInterpretation restoreStraightOpeningDyadQuartets(ScorePageInterpretation score,
            ScoreMeterChange inherited,byte[] gray,byte[] raw,int w,int h,int physicalPage) {
        return restoreStraightDyadQuartets(score, inherited, gray, raw, w, h, physicalPage, null);
    }

    static ScorePageInterpretation restoreOpposedFingeringQuartets(ScorePageInterpretation score,
            ScoreMeterChange inherited, byte[] gray, byte[] raw, int w, int h, int physicalPage,
            Consumer<ScoreNoteEvent> printedThree) {
        return restoreStraightDyadQuartets(score, inherited, gray, raw, w, h, physicalPage, printedThree);
    }

    private static ScorePageInterpretation restoreStraightDyadQuartets(ScorePageInterpretation score,
            ScoreMeterChange inherited, byte[] gray, byte[] raw, int w, int h, int physicalPage,
            Consumer<ScoreNoteEvent> printedThree) {
        if(raw==null || gray.length!=(long)w*h || raw.length!=gray.length) return score;
        var notes=score.notes();
        var regions=score.measures();
        var bars=new ArrayList<List<Head>>(regions.size());
        for(int m=0;m<regions.size();m++) bars.add(new ArrayList<>());
        for(int i=0;i<notes.size();i++) {
            var n=notes.get(i);
            int m=n.measureIndex();
            if(m>=0 && m<regions.size()) bars.get(m).add(quartetHead(notes,regions.get(m),i,w,h));
        }
        var grayInk=new Ink(gray,w,h);
        var rawInk=new Ink(raw,w,h);
        ScoreMeterChange meter=inherited;
        int nextMeter=0;
        List<ScoreNoteEvent> edits=null;
        var beams=score.beamSource();
        for(int m=0;m<regions.size();m++) {
            while(nextMeter<score.meterChanges().size() && score.meterChanges().get(nextMeter).measureIndex()<=m)
                meter=score.meterChanges().get(nextMeter++);
            var bar=regions.get(m);
            var row=bars.get(m);
            int measure=m;
            if (meter == null || meter.denominator() != 4 || bar.cadenceQuarterBeats() != 0
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == measure)) continue;
            boolean octaveQuartets = meter.numerator() == 3 && row.size() == 24;
            if (printedThree != null && !octaveQuartets) continue;
            if (!octaveQuartets && (meter.numerator() != 4 || row.size() < 31 || row.size() > 32))
                continue;
            float gap=(bar.bottom()-bar.top())*h/8.2f;
            if(gap<12 || gap>24) continue;
            row.sort(Comparator.comparingDouble(Head::x));
            var interior = octaveQuartets ? List.<Head>of()
                    : interiorSixthQuartet(rawInk, grayInk, regions, m, row, gap, beams);
            if (!interior.isEmpty()) {
                if (edits == null) edits = new ArrayList<>(notes);
                for (var head : interior) {
                    if (head.note.tupletDivisor() == 3)
                        edits.set(head.owner, head.note.withTupletRatio(1, 1));
                }
                if (physicalPage >= 0) beams = projectDoubleRails(beams, physicalPage, interior);
                continue;
            }
            var headGroup=row.subList(0,8);
            if(!beamOriginsUnclaimed(beams,headGroup)) continue;
            int direction=row.get(0).note.stemDirection();
            int start=row.get(0).note.tupletDivisor()==3?0:1;
            int clef=row.get(0).note.clefBottomDiatonic();
            if (Math.abs(direction) != 1
                    || !completeSixteenthColumns(row, gap, clef, meter.numerator() * 4)) continue;
            boolean fingeredThirds = octaveQuartets && direction == 1 && start == 0
                    && completePairedIntervals(row, gap, 2, 1f, .24f);
            if (fingeredThirds != (printedThree != null)
                    || octaveQuartets && !fingeredThirds && !completePairedIntervals(row, gap, 7, 3.5f, .26f))
                continue;
            var pairs=new ArrayList<Dyad>(4);
            var columns=new ClosedFourDyadBeam.Head[4];
            for(int i=0;i<4;i++) {
                var a=row.get(i*2);
                var b=row.get(i*2+1);
                boolean triplet=i>=start && i<start+3;
                if ((!octaveQuartets && !sameShaftDyadGeometry(a, b, gap))
                        || !ordinaryDoubleRail(a.note,clef,direction,triplet)
                        || !ordinaryDoubleRail(b.note,clef,direction,triplet)) break;
                var pair=a.y<b.y?new Dyad(a,b):new Dyad(b,a);
                pairs.add(pair);
                columns[i]=new ClosedFourDyadBeam.Head((a.x+b.x)*.5f,pair.top.y,pair.bottom.y);
            }
            if(pairs.size()!=4) continue;
            var rows=UnnumberedTripletInk.sourceRows(bar,regions,h);
            if (octaveQuartets) {
                if (!straightQuartetInk(rawInk, bar, pairs, gap, start, rows, false, fingeredThirds)
                        || !straightQuartetInk(grayInk, bar, pairs, gap, start, rows, false, fingeredThirds))
                    continue;
            } else if (!straightOpeningQuartetInk(rawInk,bar,pairs,columns,gap,-direction,start,rows)
                    || !straightOpeningQuartetInk(grayInk,bar,pairs,columns,gap,-direction,start,rows)) continue;
            if (fingeredThirds) printedThree.accept(pairs.get(1).bottom.note);
            if(edits==null) edits=new ArrayList<>(notes);
            for(var h1:headGroup) if(h1.note.tupletDivisor()==3) edits.set(h1.owner,h1.note.withTupletRatio(1,1));
            if(physicalPage>=0) beams=projectDoubleRails(beams,physicalPage,headGroup);
        }
        return edits==null?score:score.withBeamSource(beams).withNotes(edits,IntStream.range(0,notes.size()).boxed().toList());
    }

    /** Keep the complete common-time clock when a fingering has claimed part of an interior quartet. */
    private static List<Head> interiorSixthQuartet(Ink raw, Ink normalized, List<MeasureRegion> regions,
            int measure, List<Head> row, float gap, ScoreBeamSource beams) {
        if (row.size() != 32) return List.of();
        var claimedSlots = new ArrayList<Integer>();
        int clef = row.get(0).note.clefBottomDiatonic();
        for (int slot = 0; slot < 16; slot++) {
            var a = row.get(2 * slot);
            var b = row.get(2 * slot + 1);
            if (b.x - a.x > .24f * gap || slot > 0
                    && (a.x - row.get(2 * slot - 2).x < 1.25f * gap
                    || a.x - row.get(2 * slot - 2).x > 4.8f * gap)) return List.of();
            boolean claim = a.note.tupletDivisor() == 3;
            for (var n : List.of(a.note, b.note)) {
                if (!ordinaryGeometry(n, clef) || n.beamCount() != 2 || n.unbeamedDurationBeats() != 0
                        || n.augmentationDots() != 0 || n.tupletDivisor() != (claim ? 3 : 1)
                        || n.tupletNormalNotes() != (claim ? 2 : 1)) return List.of();
            }
            if (claim) claimedSlots.add(slot);
        }
        if (claimedSlots.size() == 6 && claimedSlots.equals(List.of(5, 6, 7, 9, 10, 11))) {
            var first = provedInteriorSixthQuartet(raw, normalized, regions, measure, row, gap, beams, 5, true);
            var second = provedInteriorSixthQuartet(raw, normalized, regions, measure, row, gap, beams, 9, true);
            if (first.isEmpty() || second.isEmpty()) return List.of();
            var both = new ArrayList<>(first);
            both.addAll(second);
            return both;
        }
        if (claimedSlots.size() != 3) return List.of();
        int first = claimedSlots.get(0);
        if (first < 4 || first % 4 > 1 || claimedSlots.get(1) != first + 1 || claimedSlots.get(2) != first + 2)
            return List.of();
        return provedInteriorSixthQuartet(raw, normalized, regions, measure, row, gap, beams, first, false);
    }

    private static List<Head> provedInteriorSixthQuartet(Ink raw, Ink normalized, List<MeasureRegion> regions,
            int measure, List<Head> row, float gap, ScoreBeamSource beams, int first, boolean opposedFingerings) {
        int group = first - first % 4;
        var owners = row.subList(2 * group, 2 * group + 8);
        if (!beamOriginsUnclaimed(beams, owners)) return List.of();
        var pairs = new ArrayList<Dyad>(4);
        int stem = owners.get(0).note.stemDirection();
        if (stem == 0) return List.of();
        for (int i = 0; i < 8; i += 2) {
            var a = owners.get(i);
            var b = owners.get(i + 1);
            var dyad = a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
            float height = (dyad.bottom.y - dyad.top.y) / gap;
            if (a.note.stemDirection() != stem || b.note.stemDirection() != stem
                    || dyad.top.note.staffStep() - dyad.bottom.note.staffStep() != 5
                    || height < 2.22f || height > 2.85f) return List.of();
            pairs.add(dyad);
        }
        var bar = regions.get(measure);
        var rows = UnnumberedTripletInk.sourceRows(bar, regions, raw.height);
        return straightQuartetInk(raw, bar, pairs, gap, first % 4, rows, true, false, opposedFingerings)
                && straightQuartetInk(normalized, bar, pairs, gap, first % 4, rows, true, false, opposedFingerings)
                ? owners : List.of();
    }

    private static boolean completeSixteenthColumns(List<Head> row, float gap, int clef, int attacks) {
        int count=0, members=0;
        float previous=-1000;
        for(int i=0;i<row.size();i++) {
            var h=row.get(i);
            var n=h.note;
            if(i>=8 && (!ordinaryGeometry(n,clef) || n.beamCount()!=2 || n.tupletDivisor()!=1
                    || n.unbeamedDurationBeats()!=0 || n.augmentationDots()!=0)) return false;
            if(h.x-previous>.28f*gap) {
                if(count>0 && (h.x-previous<1.25f*gap || h.x-previous>4.8f*gap)) return false;
                count++;
                members=0;
                previous=h.x;
            }
            if(++members>2) return false;
        }
        return count == attacks;
    }

    private static boolean completePairedIntervals(List<Head> row, float gap, int steps,
            float distance, float tolerance) {
        for (int head = 0; head < row.size(); head += 2) {
            var a = row.get(head);
            var b = row.get(head + 1);
            if (b.x - a.x > .22f * gap || Math.abs(Math.abs(a.y - b.y) - distance * gap) > tolerance * gap
                    || Math.abs(a.note.staffStep() - b.note.staffStep()) != steps
                    || a.note.stemDirection() != b.note.stemDirection()) return false;
        }
        return true;
    }

    /** Require both ribbons and independent ownership of any printed numeral. */
    private static boolean straightQuartetInk(Ink ink, MeasureRegion bar, List<Dyad> pairs,
            float gap, int start, UnnumberedTripletInk.Rows rows, boolean allowPeerFingering,
            boolean fingeredThirds) {
        return straightQuartetInk(ink, bar, pairs, gap, start, rows, allowPeerFingering, fingeredThirds, false);
    }

    private static boolean straightQuartetInk(Ink ink, MeasureRegion bar, List<Dyad> pairs,
            float gap, int start, UnnumberedTripletInk.Rows rows, boolean allowPeerFingering,
            boolean fingeredThirds, boolean opposedFingerings) {
        var track = WrappedMeasureBoundaries.verifiedTripletStaff(ink.raw, ink.width, ink.height, bar, true);
        if (track == null && opposedFingerings) {
            float floor = bar.bottom() * ink.height - 2.1f * gap;
            track = StaffPitchTrack.forPrintedSlurs(ink.raw, ink.width, ink.height, floor - 4 * gap, floor, gap);
        }
        if (track == null) {
            var first = pairs.get(0).bottom;
            var rules = localRules(ink, first.x, first.y + first.note.staffStep() * gap * .5f, gap);
            if (rules == null) return false;
            track = StaffPitchTrack.linear(ink.width, rules[0], rules[1], 0);
        }
        var budget = new PrintedBeamShafts.Budget(200_000);
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(pairs.size());
        for (var pair : pairs) {
            var shaft = sharedOpticalShaft(ink, pair, gap, budget);
            if (shaft == null || !shafts.isEmpty()
                    && !exactShaftDoubleRails(ink, shafts.get(shafts.size() - 1), shaft, track, gap))
                return false;
            shafts.add(shaft);
        }
        var claim = pairs.subList(start, start + 3);
        float top = (float) claim.stream().mapToDouble(p -> p.top.y).min().orElseThrow();
        float bottom = (float) claim.stream().mapToDouble(p -> p.bottom.y).max().orElseThrow();
        var middle = claim.get(1);
        float center = middle.top.x;
        var shaft = shafts.get(start + 1);
        float beam = shaft.endY();
        float topCaption = shaft.direction() < 0 ? beam : top;
        float bottomCaption = shaft.direction() > 0 ? beam : bottom;
        if (UnnumberedTripletInk.bracketBesideBeam(ink.raw, ink.width, ink.height, gap,
                claim.get(0).top.x, claim.get(2).top.x, top, bottom, shaft.endY(), rows, track, true)) return false;
        if (opposedFingerings)
            return shaft.direction() < 0 && !opposedQuartetGlyphs(ink, middle, claim.get(2),
                    shaft, shafts.get(start + 2), gap, rows).isEmpty();
        boolean below = UnnumberedTripletInk.centeredThree(ink.raw, ink.width, ink.height, center,
                bottomCaption - .15f * gap, bottomCaption + 4 * gap, gap, rows, track);
        if (fingeredThirds ? !opposedOpeningDigits(ink, claim.get(0), middle, shaft, gap) : below) return false;
        if (!UnnumberedTripletInk.centeredThree(ink.raw, ink.width, ink.height, center,
                topCaption - 4 * gap, topCaption + .15f * gap, gap, rows, track)) return true;
        return allowPeerFingering && start == 1 && shaft.direction() < 0
                && matchingBeamSideThree(ink, pairs.get(0), shafts.get(0), middle, shaft, gap);
    }

    /** Each of two adjacent shafts has separate upper and lower fingering ink. */
    private static List<RawNumeralComponents.Component> opposedQuartetGlyphs(Ink ink, Dyad middle, Dyad end,
            PrintedBeamShafts.Shaft middleShaft, PrintedBeamShafts.Shaft endShaft, float gap,
            UnnumberedTripletInk.Rows rows) {
        var a = isolatedQuartetFingering(ink, middle.top.x, middleShaft.endY() - 2.5f * gap,
                middleShaft.endY() - .05f * gap, gap, rows);
        var b = isolatedQuartetFingering(ink, end.top.x, endShaft.endY() - 2.5f * gap,
                endShaft.endY() - .05f * gap, gap, rows);
        var c = isolatedQuartetFingering(ink, middle.bottom.x, middle.bottom.y + .30f * gap,
                middle.bottom.y + 2.7f * gap, gap, rows);
        var d = isolatedQuartetFingering(ink, end.bottom.x, end.bottom.y + .30f * gap,
                end.bottom.y + 2.7f * gap, gap, rows);
        return a != null && b != null && c != null && d != null
                && Math.abs((a.bottom - middleShaft.endY()) - (b.bottom - endShaft.endY())) <= .17f * gap
                && Math.abs((c.top - middle.bottom.y) - (d.top - end.bottom.y)) <= .23f * gap
                ? List.of(a, c, b, d) : List.of();
    }

    static List<PrintedFingeringDigits.Mark> captureOpposedSixthFingerings(ScorePageInterpretation score,
            byte[] raw, byte[] normalized, int width, int height) {
        if (raw == null || normalized == null || raw.length != (long) width * height
                || normalized.length != raw.length) return List.of();
        var closedQuartets = new HashSet<List<Integer>>();
        for (var group : score.beamSource().resolve().groups()) {
            var beam = group.source();
            if (beam.tupletMark() == null && beam.hooks().isEmpty() && beam.bridges().equals(List.of(3, 3, 3))
                    && group.columns().stream().allMatch(c -> !c.ambiguous() && c.owners().size() == 2)) {
                closedQuartets.add(group.columns().stream().flatMap(c -> c.owners().stream()).sorted().toList());
            }
        }
        if (closedQuartets.isEmpty()) return List.of();
        var measures = score.measures();
        var notes = score.notes();
        var byMeasure = new ArrayList<List<Head>>(measures.size());
        for (var ignored : measures) byMeasure.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) {
            int m = notes.get(i).measureIndex();
            if (m >= 0 && m < measures.size()) byMeasure.get(m).add(quartetHead(notes, measures.get(m), i, width, height));
        }
        var original = new Ink(raw, width, height);
        var prepared = new Ink(normalized, width, height);
        var result = new ArrayList<PrintedFingeringDigits.Mark>();
        for (int m = 0; m < byMeasure.size(); m++) {
            var row = byMeasure.get(m);
            var bar = measures.get(m);
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (row.size() != 32 || gap < 12 || gap > 24 || bar.cadenceQuarterBeats() != 0) continue;
            row.sort(Comparator.comparingDouble(Head::x));
            if (!completePairedIntervals(row, gap, 5, 2.5f, .28f)
                    || !completeSixteenthColumns(row, gap, row.get(0).note.clefBottomDiatonic(), 16)) continue;
            var pairs = new ArrayList<Dyad>(16);
            for (int i = 0; i < row.size(); i += 2) {
                var a = row.get(i);
                var b = row.get(i + 1);
                pairs.add(a.y < b.y ? new Dyad(a, b) : new Dyad(b, a));
            }
            if (!closedQuartetOwners(closedQuartets, row.subList(8, 16))
                    || !closedQuartetOwners(closedQuartets, row.subList(16, 24))
                    || pairs.subList(4, 12).stream().anyMatch(p -> p.top.note.stemDirection() != 1)
                    || pairs.subList(0, 4).stream().anyMatch(p -> p.top.note.stemDirection() != -1)
                    || pairs.subList(12, 16).stream().anyMatch(p -> p.top.note.stemDirection() != -1)) continue;
            var rows = UnnumberedTripletInk.sourceRows(bar, measures, height);
            var a = readOpposedSixthFingers(original, pairs, gap, rows);
            var b = raw == normalized ? a : readOpposedSixthFingers(prepared, pairs, gap, rows);
            for (var mark : a) if (b.contains(mark)) result.add(mark);
        }
        return List.copyOf(result);
    }

    private static boolean closedQuartetOwners(Set<List<Integer>> groups, List<Head> heads) {
        return groups.contains(heads.stream().map(Head::owner).sorted().toList());
    }

    private static List<PrintedFingeringDigits.Mark> readOpposedSixthFingers(
            Ink ink, List<Dyad> pairs, float gap, UnnumberedTripletInk.Rows rows) {
        var budget = new PrintedBeamShafts.Budget(150_000);
        var result = new ArrayList<PrintedFingeringDigits.Mark>();
        for (int middle : new int[]{6, 10}) {
            var a = pairs.get(middle);
            var b = pairs.get(middle + 1);
            var aShaft = sharedOpticalShaft(ink, a, gap, budget);
            var bShaft = sharedOpticalShaft(ink, b, gap, budget);
            if (aShaft == null || bShaft == null) return List.of();
            var glyphs = opposedQuartetGlyphs(ink, a, b, aShaft, bShaft, gap, rows);
            if (glyphs.isEmpty()) return List.of();
            var owners = List.of(a.top, a.bottom, b.top, b.bottom);
            for (int i = 0; i < owners.size(); i++) {
                int digit = quartetFingeringDigit(ink, glyphs.get(i), true);
                if (digit >= 0) result.add(new PrintedFingeringDigits.Mark(owners.get(i).owner, digit,
                        i % 2 == 0 ? PrintedFingeringDigits.Placement.ABOVE : PrintedFingeringDigits.Placement.BELOW));
            }
        }
        for (var pair : pairs) {
            if (pair.top.note.stemDirection() != -1) continue;
            var head = pair.top;
            var glyph = isolatedQuartetFingering(ink, head.x,
                    head.y - 3.42f * gap, head.y - .56f * gap, gap, rows);
            int digit = quartetFingeringDigit(ink, glyph, false);
            if (digit >= 0)
                result.add(new PrintedFingeringDigits.Mark(head.owner, digit, PrintedFingeringDigits.Placement.ABOVE));
        }
        return result;
    }

    private static int quartetFingeringDigit(Ink ink, RawNumeralComponents.Component glyph, boolean opposed) {
        if (glyph == null) return -1;
        var reading = JoinedDigitTemplates.inspect(ink.raw, ink.width, ink.height, new java.util.BitSet(),
                glyph.left, glyph.top, glyph.right, glyph.bottom, () -> {});
        if (reading == null) return -1;
        if (!opposed) return reading.digit() <= 4 ? reading.digit() : -1;
        // A confirmed opposing finger position limits the reading to violin finger numbers.
        var scores = reading.scores();
        int best = 0;
        for (int digit = 1; digit <= 4; digit++) if (scores[digit] > scores[best]) best = digit;
        if (scores[best] < .59f) return -1;
        for (int digit = 0; digit <= 4; digit++) {
            if (digit != best && scores[best] - scores[digit] < .06f) return -1;
        }
        return best;
    }

    private static RawNumeralComponents.Component isolatedQuartetFingering(Ink ink, float x,
            float first, float last, float gap, UnnumberedTripletInk.Rows rows) {
        int left = Math.round(x - .84f * gap), right = Math.round(x + .84f * gap);
        int top = Math.max(rows.first(), Math.round(first));
        int bottom = Math.min(rows.last(), Math.round(last));
        if (left < 0 || right >= ink.width || top >= bottom) return null;
        RawNumeralComponents.Component result = null;
        for (var c : RawNumeralComponents.capture(ink.raw, ink.width, ink.height, left, top, right, bottom,
                165, () -> {}, () -> {}).components()) {
            int height = c.bottom - c.top + 1, width = c.right - c.left + 1;
            if (c.left <= left || c.right >= right || c.top <= top || c.bottom >= bottom
                    || height < .80f * gap || height > 1.28f * gap || width < .48f * gap || width > 1.08f * gap
                    || Math.abs((c.left + c.right) * .5f - x) > .48f * gap) continue;
            if (result != null) return null;
            result = c;
        }
        return result;
    }

    private static boolean opposedOpeningDigits(Ink ink, Dyad first, Dyad center,
            PrintedBeamShafts.Shaft shaft, float gap) {
        var before = first.bottom;
        var lower = center.bottom;
        var a = openingFinger(ink, before.x, before.y + 1.65f * gap, before.y + 4.8f * gap, gap);
        var three = openingFinger(ink, lower.x, lower.y + 2.4f * gap, lower.y + 5f * gap, gap);
        var one = openingFinger(ink, center.top.x, shaft.endY() - 2.1f * gap,
                shaft.endY() - .16f * gap, gap);
        if (a == null || three == null || one == null || !TripletRhythmDetector.fingerOne(ink.raw, ink.width, one)
                || !TripletRhythmDetector.isIsolatedThree(three, ink.width)
                || three.top - a.top < .55f * gap || three.top - a.top > 1.55f * gap
                || Math.abs((a.bottom - a.top) - (three.bottom - three.top)) > .2f * gap) return false;
        var reading = JoinedDigitTemplates.inspect(ink.raw, ink.width, ink.height,
                new java.util.BitSet(), a.left, a.top, a.right, a.bottom, () -> {});
        return reading != null && reading.digit() == 4;
    }

    /** Equal isolated digits follow two upstem shafts at the same beam-relative height. */
    private static boolean matchingBeamSideThree(Ink ink, Dyad before, PrintedBeamShafts.Shaft beforeShaft,
            Dyad center, PrintedBeamShafts.Shaft centerShaft, float gap) {
        var a = openingFinger(ink, before.top.x, beforeShaft.endY() - 1.9f * gap,
                beforeShaft.endY() - .08f * gap, gap);
        var b = openingFinger(ink, center.top.x, centerShaft.endY() - 1.9f * gap,
                centerShaft.endY() - .08f * gap, gap);
        return a != null && b != null
                && TripletRhythmDetector.isIsolatedThree(a, ink.width)
                && TripletRhythmDetector.isIsolatedThree(b, ink.width)
                && Math.abs((a.bottom - beforeShaft.endY()) - (b.bottom - centerShaft.endY())) <= .15f * gap
                && Math.abs((a.bottom - a.top) - (b.bottom - b.top)) <= .15f * gap
                && Math.abs((a.right - a.left) - (b.right - b.left)) <= .15f * gap;
    }

    private static boolean straightOpeningQuartetInk(Ink ink,MeasureRegion bar,List<Dyad> pairs,
            ClosedFourDyadBeam.Head[] columns,float gap,int direction,int start,UnnumberedTripletInk.Rows rows) {
        var track=WrappedMeasureBoundaries.verifiedTripletStaff(ink.raw,ink.width,ink.height,bar,true);
        if(!ClosedFourDyadBeam.provesWithAccidentalSpacing(ink.raw,ink.width,ink.height,columns,gap,direction)) {
            var left=pairs.get(0).bottom;
            var right=pairs.get(3).bottom;
            var l=localRules(ink,left.x,left.y+left.note.staffStep()*gap*.5f,gap);
            var r=localRules(ink,right.x,right.y+right.note.staffStep()*gap*.5f,gap);
            if(l==null || r==null || Math.abs(l[0]-r[0])>.12f*gap || Math.abs(l[1]-r[1])>.03f*gap)
                return false;
            float floor=(l[0]+r[0])*.5f, staffGap=(l[1]+r[1])*.5f;
            if(!ClosedFourDyadBeam.provesAcrossDistinctStaffRules(
                    ink.raw,ink.width,ink.height,columns,staffGap,floor,direction)) return false;
            if(track==null) track=StaffPitchTrack.linear(ink.width,floor,staffGap,0);
        }
        var shaft=sharedOpticalShaft(ink,pairs.get(start+1),gap,new PrintedBeamShafts.Budget(45000));
        if(shaft==null) return false;
        float top=Float.POSITIVE_INFINITY,bottom=Float.NEGATIVE_INFINITY;
        for(int i=start;i<start+3;i++) {
            top=Math.min(top,columns[i].top());
            bottom=Math.max(bottom,columns[i].bottom());
        }

        float x=columns[start+1].x();
        if(UnnumberedTripletInk.bracketBesideBeam(ink.raw,ink.width,ink.height,gap,
                columns[start].x(),columns[start+2].x(),top,bottom,shaft.endY(),rows,track,true)) return false;
        if(direction>0) return !UnnumberedTripletInk.centeredThree(ink.raw,ink.width,ink.height,x,
                    top-4*gap,top+.15f*gap,gap,rows,track)
                && !UnnumberedTripletInk.centeredThree(ink.raw,ink.width,ink.height,x,
                    shaft.endY()-.15f*gap,shaft.endY()+4*gap,gap,rows,track);
        if(UnnumberedTripletInk.centeredThree(ink.raw,ink.width,ink.height,x,
                shaft.endY()-4*gap,shaft.endY()+.15f*gap,gap,rows,track)) return false;
        // The head-side numeral has the same head-relative row as the adjacent detached finger fours.
        var center=pairs.get(start+1);
        var one=openingFinger(ink,center.top.x,shaft.endY()-2.45f*gap,shaft.endY()-.35f*gap,gap);
        var lower=openingFinger(ink,center.bottom.x,center.bottom.y+.70f*gap,center.bottom.y+2.70f*gap,gap);
        if(one==null || lower==null || !TripletRhythmDetector.fingerOne(ink.raw,ink.width,one)) return false;
        for(int slot:new int[]{start,start+2}) {
            var h=pairs.get(slot).bottom;
            var digit=openingFinger(ink,h.x,h.y+.70f*gap,h.y+2.70f*gap,gap);
            if(digit==null || Math.abs((digit.top-h.y)-(lower.top-center.bottom.y))>.35f*gap) return false;
            var fit=JoinedDigitTemplates.inspect(ink.raw,ink.width,ink.height,new java.util.BitSet(),
                    digit.left,digit.top,digit.right,digit.bottom,()->{});
            if(fit==null || fit.digit()!=4) return false;
        }
        return true;
    }

    private static RawNumeralComponents.Component openingFinger(Ink ink,float x,float top,float bottom,float gap) {
        int l=Math.round(x-.83f*gap),r=Math.round(x+.83f*gap),t=Math.round(top),b=Math.round(bottom);
        if(l<0 || r>=ink.width || t<0 || b>=ink.height) return null;
        var glyphs=RawNumeralComponents.capture(ink.raw,ink.width,ink.height,l,t,r,b,165,()->{},()->{});
        RawNumeralComponents.Component result=null;
        for(var c:glyphs.components()) {
            int width=c.right-c.left+1,height=c.bottom-c.top+1;
            if(c.left<=l || c.right>=r || c.top<=t || c.bottom>=b
                    || width<.40f*gap || width>1.08f*gap || height<.76f*gap || height>1.35f*gap
                    || Math.abs((c.left+c.right)*.5f-x)>.3f*gap) continue;
            if(result!=null) return null;
            result=c;
        }
        return result;
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
