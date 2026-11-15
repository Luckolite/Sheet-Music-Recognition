// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Reads physically closed runs of dotted sixteenths and their hooks. */
final class ClosedDottedSixteenths {
    private ClosedDottedSixteenths() {}

    private record Head(int index,ScoreNoteEvent note,float x,float y) {}
    private record Shaft(int x,int tip) {}
    private record Beam(Shaft a,Shaft b,int depth,int separation) {
        float outer(float x) {return a.tip+(b.tip-a.tip)*(x-a.x)/(b.x-a.x)-depth;}
    }
    private record Ink(byte[] pixels,int w,int h) {
        boolean dark(int x,int y) {
            return x>=0 && x<w && y>=0 && y<h && (pixels[y*w+x]&255)<130;
        }

        int weight(int x,int y,int radius) {
            int n=0;
            for(int k=-radius;k<=radius;k++) if(dark(x,y+k)) n++;
            return n;
        }
    }

    static ScorePageInterpretation apply(ScorePageInterpretation score,byte[] gray,int w,int h) {
        if (gray==null || gray.length!=(long)w*h || w<256 || h<128) return score;
        var ink=new Ink(gray,w,h);
        var notes=score.notes();
        var bars = groupedHeads(score, w, h);
        var corrected=new ScoreNoteEvent[notes.size()];
        var suppressed=new boolean[notes.size()];
        int nextMeter=0;
        ScoreMeterChange meter=null;
        boolean changed=false;
        for(int m=0;m<bars.size();m++) {
            while (nextMeter<score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex()<=m)
                meter=score.meterChanges().get(nextMeter++);
            final int bar=m;
            var row=bars.get(m);
            if (meter==null || meter.numerator()!=3 || meter.denominator()!=4 || row.size()!=13
                    || score.rests().stream().anyMatch(r->r.measureIndex()==bar)) continue;
            var proof=prove(ink,score.measures().get(m),row);
            if (proof == null && meter.measureIndex() == m && m + 1 < bars.size())
                proof =
                        inlineMeterHooks(
                                ink, score.measures().get(m), score.measures().get(m + 1), row);
            if (proof == null) {
                var hook = backwardHookAlias(ink, score.measures().get(m), row);
                if (hook == null) continue;
                suppressed[hook.index] = true;
                changed = true;
                continue;
            }
            suppressed[proof.fragment.index]=true;
            for(int i=0;i<proof.kept.size();i++)
                corrected[proof.kept.get(i).index]=rhythm(proof.kept.get(i).note,i%2==0);
            changed=true;
        }
        if (!changed) return score;
        var retained=new ArrayList<ScoreNoteEvent>();
        var owners=new ArrayList<Integer>();
        for(int i=0;i<notes.size();i++) {
            if (suppressed[i]) continue;
            owners.add(i);
            retained.add(corrected[i]==null?notes.get(i):corrected[i]);
        }
        return score.withNotes(retained,owners);
    }

    static ScorePageInterpretation applyClosedOctaves(
            ScorePageInterpretation score, ScoreMeterChange inherited, byte[] gray, int w, int h) {
        return applyClosedOctaves(score, inherited, gray, w, h, false, -1);
    }

    static ScorePageInterpretation applyClosedOctaveFragments(
            ScorePageInterpretation score, ScoreMeterChange inherited, byte[] gray, int w, int h) {
        return applyClosedOctaveFragments(score, inherited, gray, w, h, -1);
    }

    static ScorePageInterpretation applyClosedOctaveFragments(
            ScorePageInterpretation score,
            ScoreMeterChange inherited,
            byte[] gray,
            int w,
            int h,
            int physicalPage) {
        return applyClosedOctaves(score, inherited, gray, w, h, true, physicalPage);
    }

    private static ScorePageInterpretation applyClosedOctaves(
            ScorePageInterpretation score,
            ScoreMeterChange inherited,
            byte[] gray,
            int w,
            int h,
            boolean inspectFragments,
            int physicalPage) {
        if (gray == null || gray.length != (long) w * h || w < 256 || h < 128) return score;
        var bars = groupedHeads(score, w, h);
        var notes = score.notes();
        var revised = new ArrayList<>(notes);
        var suppressed = new boolean[notes.size()];
        var ink = new Ink(gray, w, h);
        var regions = score.measures();
        var meter = inherited;
        var beams = score.beamSource();
        int nextMeter = 0;
        for (int i = 1; i + 1 < bars.size(); i++) {
            while (nextMeter < score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex() <= i)
                meter = score.meterChanges().get(nextMeter++);
            var row = bars.get(i);
            if (inspectFragments
                    && meter != null
                    && meter.numerator() == 3
                    && meter.denominator() == 4
                    && row.size() == 25
                    && closedTripleOctaves(ink, score, regions, i, row, revised, suppressed))
                continue;
            if (meter == null
                    || meter.numerator() != 4
                    || meter.denominator() != 4
                    || row.size() != (inspectFragments ? 33 : 32)
                    || regions.get(i).cadenceQuarterBeats() != 0) continue;
            final int bar = i;
            if (score.rests().stream().anyMatch(r -> r.measureIndex() == bar)
                    || score.multiRestGroups().stream()
                            .anyMatch(
                                    g ->
                                            g.firstMeasureIndex() <= bar
                                                    && g.firstMeasureIndex() + g.measureCount()
                                                            > bar)) continue;
            Head fragment = null;
            List<Dyad> cadence = List.of();
            if (inspectFragments) {
                var suspects = row.stream().filter(head -> head.note.stemDirection() == 0).toList();
                if (suspects.size() != 1) continue;
                fragment = suspects.get(0);
                if (fragment.note.augmentationDots() != 0 || fragment.note.articulations() != 0)
                    continue;
                if (fragment.note.beamCount() == 0
                        && fragment.note.unbeamedDurationBeats() == 1
                        && regularMarkings(fragment.note, 1)
                        && score.rests().stream().noneMatch(r -> r.measureIndex() == bar - 1)) {
                    cadence = halfOctaveCadence(ink, regions.get(i - 1), bars.get(i - 1));
                    if (cadence.isEmpty()) continue;
                } else if (!regular(fragment.note) || fragment.note.beamCount() != 3) continue;
                var owners = new ArrayList<>(row);
                owners.remove(fragment);
                row = owners;
            }
            var dyads =
                    closedOctaves(
                            ink,
                            regions.get(i - 1),
                            regions.get(i),
                            regions.get(i + 1),
                            row,
                            fragment,
                            cadence.isEmpty() ? null : cadence.get(cadence.size() - 1));
            if (dyads.isEmpty()) continue;
            var trial = new ArrayList<ScoreNoteEvent>(32);
            for (int j = 0; j < dyads.size(); j++) {
                trial.add(rhythm(dyads.get(j).upper.note, j % 2 == 0));
                trial.add(rhythm(dyads.get(j).lower.note, j % 2 == 0));
            }
            if (!fillsDottedClock(trial, 4f)) continue;
            if (fragment != null) {
                suppressed[fragment.index] = true;
                if (physicalPage >= 0 && cadence.isEmpty()) {
                    var attacks = dyads.stream().map(ClosedDottedSixteenths::beamAttack).toList();
                    var barRegion = regions.get(i);
                    float gap = (barRegion.bottom() - barRegion.top()) * h / 8.2f;
                    beams =
                            ClosedOctaveBeamSource.capture(
                                    beams, physicalPage, gray, w, h, gap, attacks);
                }
            }
            for (int j = 0; j < dyads.size(); j++) {
                revised.set(dyads.get(j).upper.index, trial.get(j * 2));
                revised.set(dyads.get(j).lower.index, trial.get(j * 2 + 1));
            }
        }
        var retained = new ArrayList<ScoreNoteEvent>(notes.size());
        var owners = new ArrayList<Integer>(notes.size());
        for (int i = 0; i < notes.size(); i++) {
            if (suppressed[i]) continue;
            owners.add(i);
            retained.add(revised.get(i));
        }
        if (retained.equals(notes)) return score;
        var result =
                (beams == score.beamSource() ? score : score.withBeamSource(beams))
                        .withNotes(retained, owners);
        return inspectFragments
                ? result
                : result.withClockContextSource(score.clockContextSource());
    }

    private static boolean closedTripleOctaves(
            Ink ink,
            ScorePageInterpretation score,
            List<MeasureRegion> regions,
            int bar,
            List<Head> row,
            ArrayList<ScoreNoteEvent> revised,
            boolean[] suppressed) {
        if (regions.get(bar).cadenceQuarterBeats() != 0
                || score.rests().stream().anyMatch(r -> r.measureIndex() == bar)
                || score.multiRestGroups().stream()
                        .anyMatch(
                                g ->
                                        g.firstMeasureIndex() <= bar
                                                && g.firstMeasureIndex() + g.measureCount() > bar))
            return false;
        var fragments = row.stream().filter(h -> h.note.stemDirection() == 1).toList();
        if (fragments.size() != 1) return false;
        var fragment = fragments.get(0);
        if (!regular(fragment.note)
                || fragment.note.beamCount() != 1
                || fragment.note.augmentationDots() != 0
                || fragment.note.articulations() != 0) return false;

        var region = regions.get(bar);
        var prior = regions.get(bar - 1);
        var following = regions.get(bar + 1);
        float left = region.left() * ink.w, right = region.right() * ink.w;
        float gap = (region.bottom() - region.top()) * ink.h / 8.2f;
        if (gap < 10
                || gap > 27
                || right - left < 28 * gap
                || right - left > 38 * gap
                || Math.abs(prior.top() - region.top()) * ink.h > gap
                || Math.abs(following.top() - region.top()) * ink.h > gap
                || left - prior.right() * ink.w < .35f * gap
                || left - prior.right() * ink.w > 1.65f * gap
                || following.left() * ink.w - right < .35f * gap
                || following.left() * ink.w - right > 1.65f * gap) return false;

        var sorted =
                row.stream()
                        .filter(h -> h != fragment)
                        .sorted(Comparator.comparingDouble(Head::x))
                        .toList();
        var track = readOctaveStaff(ink, left, right, region.bottom() * ink.h - 2.1f * gap, gap);
        float first = sorted.get(0).x, last = sorted.get(23).x;
        if (track == null
                || first - left < .88f * gap
                || first - left > 2.4f * gap
                || !singleStaffStroke(
                        ink, track, prior.right() * ink.w, left, Float.NEGATIVE_INFINITY, gap)
                || !closingStroke(ink, track, right, following.left() * ink.w, last, gap))
            return false;

        var dyads = new ArrayList<Dyad>(12);
        var trial = new ArrayList<ScoreNoteEvent>(24);
        for (int i = 0; i < sorted.size(); i += 2) {
            var a = sorted.get(i);
            var b = sorted.get(i + 1);
            var upper = a.y < b.y ? a : b;
            var lower = upper == a ? b : a;
            boolean dotted = i % 4 == 0;
            float next = i + 2 < sorted.size() ? sorted.get(i + 2).x : right;
            if (!regular(upper.note)
                    || !regular(lower.note)
                    || upper.note.stemDirection() != -1
                    || lower.note.stemDirection() != -1
                    || upper.note.beamCount() < 2
                    || lower.note.beamCount() < 2
                    || dotted
                            && (upper.note.beamCount() != 2
                                    || lower.note.beamCount() != 2
                                    || upper.note.augmentationDots() != 1
                                    || lower.note.augmentationDots() != 1)
                    || upper.note.clefBottomDiatonic() != sorted.get(0).note.clefBottomDiatonic()
                    || lower.note.clefBottomDiatonic() != upper.note.clefBottomDiatonic()
                    || upper.note.staffStep() - lower.note.staffStep() != 7
                    || upper.note.octaveShift() != lower.note.octaveShift()
                    || b.x - a.x > .24f * gap
                    || lower.y - upper.y < 3.18f * gap
                    || lower.y - upper.y > 3.8f * gap
                    || !threeBeatDots(ink, upper, lower, next, gap, dotted)) return false;
            dyads.add(new Dyad(upper, lower));
            trial.add(rhythm(upper.note, dotted));
            trial.add(rhythm(lower.note, dotted));
        }
        if (!fillsDottedClock(trial, 3f)) return false;
        var attacks = dyads.stream().map(ClosedDottedSixteenths::beamAttack).toList();
        if (!ClosedOctaveBeamSource.provesTripleClock(
                ink.pixels,
                ink.w,
                ink.h,
                gap,
                attacks,
                new ClosedOctaveBeamSource.Fragment(fragment.x, fragment.y))) return false;
        suppressed[fragment.index] = true;
        for (int i = 0; i < dyads.size(); i++) {
            var dyad = dyads.get(i);
            revised.set(dyad.upper.index, trial.get(2 * i));
            revised.set(dyad.lower.index, trial.get(2 * i + 1));
        }
        return true;
    }

    private static boolean threeBeatDots(
            Ink ink, Head upper, Head lower, float next, float gap, boolean dotted) {
        var above =
                WrappedTripletDotInk.uniqueCore(
                        ink.pixels, ink.w, ink.h, upper.x, upper.y, next, gap);
        var below =
                WrappedTripletDotInk.uniqueCore(
                        ink.pixels, ink.w, ink.h, lower.x, lower.y, next, gap);
        return (above != null) == dotted && (below != null) == dotted;
    }

    private static ClosedOctaveBeamSource.Attack beamAttack(Dyad dyad) {
        return new ClosedOctaveBeamSource.Attack(
                dyad.upper.index,
                dyad.lower.index,
                dyad.upper.x,
                dyad.upper.y,
                dyad.lower.x,
                dyad.lower.y);
    }

    private static ArrayList<List<Head>> groupedHeads(ScorePageInterpretation score, int w, int h) {
        var notes = score.notes();
        var bars = new ArrayList<List<Head>>();
        for (var ignored : score.measures()) bars.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) {
            var n = notes.get(i);
            var bar = score.measures().get(n.measureIndex());
            bars.get(n.measureIndex())
                    .add(
                            new Head(
                                    i,
                                    n,
                                    (bar.left()
                                                    + n.positionInMeasure()
                                                            * (bar.right() - bar.left()))
                                            * w,
                                    n.pageY() * h));
        }
        return bars;
    }

    private record Dyad(Head upper, Head lower) {}

    private static List<Dyad> closedOctaves(
            Ink ink,
            MeasureRegion prior,
            MeasureRegion bar,
            MeasureRegion following,
            List<Head> row,
            Head fragment,
            Dyad openingCadence) {
        boolean fragmentClock = fragment != null;
        float height = ink.h, left = bar.left() * ink.w, right = bar.right() * ink.w;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float next = following.left() * ink.w;
        if (gap < 10 || gap > 27 || right - left < gap * 32 || right - left > gap * 49)
            return List.of();
        if (openingCadence != null) {
            if (Math.abs(bar.top() - prior.top()) * height > gap
                    || Math.abs(bar.bottom() - prior.bottom()) * height > gap
                    || (following.top() - bar.bottom()) * height < 3 * gap
                    || left - prior.right() * ink.w < gap * .25f
                    || left - prior.right() * ink.w > gap * 1.5f
                    || right + gap >= ink.w) return List.of();
            next = right + gap * .8f;
        } else if ((bar.top() - prior.bottom()) * height < gap * 3
                || Math.abs(following.top() - bar.top()) * height > gap
                || next - right < gap * .3f
                || next - right > gap * 1.75f) return List.of();
        var sorted = row.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var dyads = new ArrayList<Dyad>(16);
        boolean needsRepair = false;
        for (int i = 0; i < sorted.size(); i += 2) {
            var a = sorted.get(i);
            var b = sorted.get(i + 1);
            var upper = a.y < b.y ? a : b;
            var lower = upper == a ? b : a;
            boolean dotted = i % 4 == 0;
            if (!octaveClockOwner(upper.note, dotted, fragmentClock)
                    || !octaveClockOwner(lower.note, dotted, fragmentClock)
                    || upper.note.stemDirection() != -1
                    || lower.note.stemDirection() != -1
                    || upper.note.clefBottomDiatonic() != row.get(0).note.clefBottomDiatonic()
                    || lower.note.clefBottomDiatonic() != upper.note.clefBottomDiatonic()
                    || upper.note.staffStep() - lower.note.staffStep() != 7
                    || !fragmentClock && upper.note.octaveShift() != lower.note.octaveShift()
                    || b.x - a.x > gap * .24f
                    || lower.y - upper.y < gap * 3.18f
                    || lower.y - upper.y > gap * 3.8f) return List.of();
            if (!dyads.isEmpty()) {
                float distance = lower.x - dyads.get(dyads.size() - 1).lower.x;
                if (distance < gap * 1.50f || distance > gap * 4.15f) return List.of();
            }
            needsRepair |=
                    rhythm(upper.note, dotted) != upper.note
                            || rhythm(lower.note, dotted) != lower.note;
            dyads.add(new Dyad(upper, lower));
        }
        if (!needsRepair) return List.of();
        var track = readOctaveStaff(ink, left, right, bar.bottom() * height - 2.1f * gap, gap);
        if (track == null) return List.of();
        float first = dyads.get(0).lower.x, last = dyads.get(15).lower.x;
        if (first - left < gap * (openingCadence == null ? 1.7f : 1.1f)
                || first - left > gap * 3.4f
                || !closingStroke(ink, track, right, next, last, gap)) return List.of();
        if (openingCadence != null && (fragment.x <= first || fragment.x >= dyads.get(1).lower.x))
            return List.of();
        var axes = new ArrayList<List<Shaft>>(16);
        var budget = new PrintedBeamShafts.Budget(250_000);
        if (fragmentClock
                && !PrintedBeamShafts.at(
                                ink.pixels, ink.w, ink.h, fragment.x, fragment.y, gap, budget)
                        .isEmpty()) return List.of();
        var needsRails = new boolean[dyads.size()];
        for (int i = 0; i < dyads.size(); i++) {
            var dyad = dyads.get(i);
            float after = i + 1 < dyads.size() ? dyads.get(i + 1).upper.x : right;
            boolean dotted = i % 2 == 0;
            if (openingCadence == null) {
                boolean upperDot = dotAgreement(ink, dyad.upper, after, gap, dotted);
                boolean lowerDot = dotAgreement(ink, dyad.lower, after, gap, dotted);
                if ((!fragmentClock || !dotted) ? !upperDot || !lowerDot : !upperDot && !lowerDot)
                    return List.of();
            }
            if (openingCadence != null
                    && dotted
                    && (dyad.upper.note.beamCount() != 2 || dyad.lower.note.beamCount() != 2))
                return List.of();
            // The terminal run separately proves each under-read hook. Resolve shared axes
            // here only where the alias ribbon or a group division consumes them.
            boolean needsAxes = openingCadence == null || i < 2 || i % 4 == 0 || i % 4 == 3;
            List<Shaft> partners = needsAxes ? commonShafts(ink, dyad, gap, budget) : List.of();
            boolean changing =
                    rhythm(dyad.upper.note, dotted) != dyad.upper.note
                            || rhythm(dyad.lower.note, dotted) != dyad.lower.note;
            if (needsAxes && partners.isEmpty() && (!fragmentClock || changing)) return List.of();
            needsRails[i] = fragmentClock && dotted && changing;
            if (!dotted
                    && (dyad.upper.note.beamCount() < 3 || dyad.lower.note.beamCount() < 3)
                    && !provesRestoredHook(ink, dyad, dyads.get(i - 1), gap, budget))
                return List.of();
            axes.add(partners);
        }
        if (axes.get(0).isEmpty()) return List.of();
        int firstShaft = axes.get(0).stream().mapToInt(Shaft::x).min().orElseThrow();
        if (openingCadence == null) {
            if (!paperBetweenHeaderAndShaft(ink, track, left, firstShaft, gap)) return List.of();
        } else if (!closingStroke(
                ink, track, prior.right() * ink.w, left, openingCadence.lower.x, gap)) {
            return List.of();
        }
        boolean boundFragment = false;
        for (int i = 0; i + 1 < dyads.size(); i++) {
            var a = axes.get(i);
            var b = axes.get(i + 1);
            var leftDyad = dyads.get(i).lower;
            var rightDyad = dyads.get(i + 1).lower;
            if (i % 4 == 3) {
                if (a.isEmpty() || b.isEmpty()) return List.of();
                if (!gapBetween(ink, leftDyad, rightDyad, a, b, gap, track)) return List.of();
                continue;
            }
            Head suspect =
                    fragmentClock && leftDyad.x < fragment.x && fragment.x < rightDyad.x
                            ? fragment
                            : null;
            if (!fragmentClock || suspect != null || needsRails[i] || needsRails[i + 1]) {
                if (!ownedLink(ink, a, b, i, gap, track, suspect, true)) return List.of();
                boundFragment |= suspect != null;
            }
        }
        return fragmentClock && (!boundFragment || budget.refused()) ? List.of() : dyads;
    }

    private static List<Dyad> halfOctaveCadence(Ink ink, MeasureRegion bar, List<Head> row) {
        if (row.size() != 18 || bar.cadenceQuarterBeats() != 0) return List.of();
        float gap = (bar.bottom() - bar.top()) * ink.h / 8.2f;
        if (gap < 10 || gap > 27) return List.of();
        var sorted = row.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var pairs = new ArrayList<Dyad>();
        var trial = new ArrayList<ScoreNoteEvent>();
        var budget = new PrintedBeamShafts.Budget(160_000);
        for (int i = 0; i < sorted.size(); i += 2) {
            var a = sorted.get(i);
            var b = sorted.get(i + 1);
            var upper = a.y < b.y ? a : b;
            var lower = upper == a ? b : a;
            boolean half = i == 16;
            boolean dotted = i % 4 == 0 && !half;
            if (!cadenceHead(upper.note, half, dotted)
                    || !cadenceHead(lower.note, half, dotted)
                    || upper.note.staffStep() - lower.note.staffStep() != 7
                    || upper.note.clefBottomDiatonic() != lower.note.clefBottomDiatonic()
                    || b.x - a.x > gap * .24f
                    || lower.y - upper.y < gap * 3.18f
                    || lower.y - upper.y > gap * 3.8f) return List.of();
            var pair = new Dyad(upper, lower);
            if (!half) {
                if (!dotted
                        && (upper.note.beamCount() < 3 || lower.note.beamCount() < 3)
                        && !provesRestoredHook(ink, pair, pairs.get(pairs.size() - 1), gap, budget))
                    return List.of();
            }
            pairs.add(pair);
            trial.add(half ? upper.note : rhythm(upper.note, dotted));
            trial.add(half ? lower.note : rhythm(lower.note, dotted));
        }
        for (int i = 0; i < trial.size(); i++) {
            int slot = i / 2;
            boolean half = slot == 8;
            boolean dotted = slot % 2 == 0;
            var p = ScoreNoteTiming.writtenPlacement(trial.get(i), trial, 4).orElse(null);
            if (p == null
                    || p.onsetBeats() != (half ? 2 : slot / 2 * .5 + (dotted ? 0 : .375))
                    || p.durationBeats() != (half ? 2 : dotted ? .375 : .125)) return List.of();
        }
        return budget.refused() ? List.of() : pairs;
    }

    private static boolean cadenceHead(ScoreNoteEvent n, boolean half, boolean dotted) {
        if (!regularMarkings(n, 1)
                || n.stemDirection() != -1
                || n.augmentationDots() != (dotted ? 1 : 0)) return false;
        if (half) return n.beamCount() == 0 && n.unbeamedDurationBeats() == 2;
        return n.unbeamedDurationBeats() == 0
                && (n.beamCount() == 2 || !dotted && n.beamCount() == 3);
    }

    private static boolean octaveClockOwner(
            ScoreNoteEvent note, boolean dotted, boolean fragmentClock) {
        if (!fragmentClock) return regular(note, 2) && note.beamCount() >= 2;
        // Keep the declared alternating dot phase. Only the physically proved rails may change.
        return regularMarkings(note, 1)
                && note.augmentationDots() == (dotted ? 1 : 0)
                && (note.beamCount() != 0 && note.unbeamedDurationBeats() == 0
                        || !dotted && note.beamCount() == 0 && note.unbeamedDurationBeats() == 1)
                && (!dotted || note.beamCount() <= 2);
    }

    private static StaffPitchTrack readOctaveStaff(
            Ink ink, float left, float right, float hint, float gap) {
        var samples = new ArrayList<float[]>();
        for (int i = 0; i <= 12; i++) {
            float x = left + (right - left) * i / 12;
            var local = localRules(ink, x, hint, gap);
            if (local != null) samples.add(new float[] {x, local[0], local[1]});
        }
        return samples.size() >= 5
                        && samples.get(0)[0] <= left + (right - left) * .27f
                        && samples.get(samples.size() - 1)[0] >= right - (right - left) * .27f
                ? StaffPitchTrack.fromVerifiedSamples(samples)
                : null;
    }

    private static List<Shaft> commonShafts(
            Ink ink, Dyad dyad, float gap, PrintedBeamShafts.Budget budget) {
        var upper = downwardShaft(ink, dyad.upper, gap, budget);
        var lower = downwardShaft(ink, dyad.lower, gap, budget);
        if (upper == null || lower == null) return List.of();
        var shared = new ArrayList<Shaft>();
        for (var a : upper.columns())
            for (var b : lower.columns())
                if (a.x() == b.x() && Math.abs(a.endY() - b.endY()) <= 1)
                    shared.add(new Shaft(a.x(), Math.max(a.endY(), b.endY())));
        return shared;
    }

    private static PrintedBeamShafts.Shaft downwardShaft(
            Ink ink, Head head, float gap, PrintedBeamShafts.Budget budget) {
        var at = PrintedBeamShafts.at(ink.pixels, ink.w, ink.h, head.x, head.y, gap, budget);
        return at.size() == 1 && at.get(0).direction() == 1 ? at.get(0) : null;
    }

    private static boolean provesRestoredHook(
            Ink ink, Dyad own, Dyad peer, float gap, PrintedBeamShafts.Budget budget) {
        return OwnedTertiaryBeamlets.provesClosingDyadHook(
                ink.pixels,
                ink.w,
                ink.h,
                own.lower.x,
                own.lower.y,
                own.upper.x,
                own.upper.y,
                peer.upper.x,
                peer.upper.y,
                peer.lower.x,
                peer.lower.y,
                gap,
                budget);
    }

    private static boolean dotAgreement(Ink ink, Head head, float next, float gap, boolean dotted) {
        var first =
                WrappedTripletDotInk.uniqueCore(
                        ink.pixels, ink.w, ink.h, head.x, head.y, next, gap);
        boolean separate =
                LegacyTripletDotInk.singleDotCore(
                        ink.pixels, ink.w, ink.h, head.x, head.y, next, gap);
        if (!dotted) return first == null && !separate;
        if (first == null || !separate) return false;
        // The next shaft can crop a genuine second dot out of the ordinary single-dot scan.
        // Check the remaining paper beside the first dot, short of the next head's own marks.
        var second =
                WrappedTripletDotInk.uniqueCore(
                        ink.pixels,
                        ink.w,
                        ink.h,
                        first.x() - gap * .42f,
                        first.y() + gap * .18f,
                        next + gap * .33f,
                        gap);
        return second == null || Math.abs(second.y() - first.y()) > gap * .34f;
    }

    private static boolean paperBetweenHeaderAndShaft(
            Ink ink, StaffPitchTrack track, float crop, int shaft, float gap) {
        int start = Math.round(crop - gap * .32f), end = shaft - Math.round(gap * .22f);
        if (end - start < gap * 1.45f) return false;
        for (int x = start; x <= end; x++) {
            float[] f = track.at(x);
            if (staffSpaces(ink, x, f, .24f) > 1) return false;
            if ((x == start || x == end) && !fiveRulesAt(ink, x, f)) return false;
        }
        return true;
    }

    private static boolean fiveRulesAt(Ink ink, int x, float[] frame) {
        for (int i = 0; i < 5; i++)
            if (ink.weight(x, Math.round(frame[0] - i * frame[1]), 2) < 1) return false;
        return true;
    }

    private static boolean closingStroke(
            Ink ink, StaffPitchTrack track, float edge, float next, float last, float gap) {
        return singleStaffStroke(ink, track, edge, next, last + gap * 1.3f, gap);
    }

    private static boolean singleStaffStroke(
            Ink ink, StaffPitchTrack track, float edge, float next, float after, float gap) {
        int run = 0, strokes = 0;
        for (int x = Math.round(edge); x <= Math.round(next) + 1; x++) {
            float[] frame = track.at(x);
            int bottom = Math.round(frame[0]), top = Math.round(frame[0] - 4 * frame[1]);
            if (x <= Math.round(next)
                    && x > after
                    && staffSpaces(ink, x, frame, .90f) == 4
                    && !DottedDyadMeasureSeparator.continuesBeyondStaff(
                            ink.pixels, ink.w, ink.h, x, top, bottom, gap)) run++;
            else {
                if (run >= Math.max(2, Math.round(gap * .12f))) strokes++;
                run = 0;
            }
        }
        return strokes == 1;
    }

    private static int staffSpaces(Ink ink, int x, float[] frame, float fill) {
        int occupied = 0;
        for (int i = 0; i < 4; i++) {
            int from = Math.round(frame[0] - (i + .73f) * frame[1]);
            int to = Math.round(frame[0] - (i + .27f) * frame[1]);
            int dark = 0;
            for (int y = from; y <= to; y++) if (ink.dark(x, y)) dark++;
            if (dark >= (to - from + 1) * fill) occupied++;
        }
        return occupied;
    }

    private static boolean fillsDottedClock(List<ScoreNoteEvent> trial, float meter) {
        for (int i = 0; i < trial.size(); i++) {
            int attack = i / 2;
            boolean dotted = attack % 2 == 0;
            var placement =
                    ScoreNoteTiming.writtenPlacement(trial.get(i), trial, meter).orElse(null);
            if (placement == null
                    || placement.onsetBeats() != attack / 2 * .5 + (dotted ? 0 : .375)
                    || placement.durationBeats() != (dotted ? .375 : .125)) return false;
        }
        return true;
    }

    private record Proof(Head fragment,List<Head> kept) {}

    private static Proof prove(Ink ink,MeasureRegion bar,List<Head> row) {
        if(bar.cadenceQuarterBeats()!=0 || row.stream().anyMatch(h->!regular(h.note))) return null;
        float left=bar.left()*ink.w,right=bar.right()*ink.w;
        float gap=(bar.bottom()-bar.top())*ink.h/8.2f;
        if (gap<10 || gap>27 || right-left<28*gap || right-left>39*gap) return null;
        var candidates=row.stream().filter(h->h.note.stemDirection()==0).toList();
        if(candidates.size()!=1) return null;
        var fragment=candidates.get(0);
        if(fragment.note.beamCount()!=3 || fragment.note.augmentationDots()!=0
                || fragment.note.articulations()!=0) return null;
        var owners=row.stream().filter(h->h!=fragment).sorted(Comparator.comparingDouble(Head::x)).toList();
        if(owners.stream().anyMatch(h->h.note.stemDirection()!=-1
                || h.note.clefBottomDiatonic()!=owners.get(0).note.clefBottomDiatonic())) return null;
        if(owners.get(0).x-left<gap*1.4f || owners.get(0).x-left>gap*3.25f
                || right-owners.get(11).x<gap*.85f || right-owners.get(11).x>gap*2.1f) return null;
        var samples=new ArrayList<float[]>();
        for(int i=0;i<=12;i++) {
            float x=left+(right-left)*i/12;
            var near=owners.stream().min(Comparator.comparingDouble(a->Math.abs(a.x-x))).orElseThrow();
            float hint=near.y+near.note.staffStep()*gap*.5f;
            var rules=localRules(ink,x,hint,gap);
            if(rules!=null) samples.add(new float[]{x,rules[0],rules[1]});
        }
        var track=samples.size()>=4?StaffPitchTrack.fromVerifiedSamples(samples):null;
        // The narrow right rule closes the run. The first gap can be a system header.
        if(track==null || !barline(ink,right,track.at(right))
                || PrintedStemDirection.detect(ink.pixels,ink.w,ink.h,fragment.x,fragment.y,gap)!=0)
            return null;
        gap=track.at((left+right)*.5f)[1];
        int fragmentGroup=-1;
        List<List<Shaft>> axes=new ArrayList<>();
        for(int i=0;i<owners.size();i++) {
            var a=owners.get(i);
            float next=i+1<owners.size()?owners.get(i+1).x:right;
            if(i<11 && (next-a.x<gap*1.50f || next-a.x>gap*4.15f)) return null;
            var aShafts=shafts(ink,a,gap);
            if(aShafts.isEmpty()) return null;
            axes.add(aShafts);
            if(dot(ink,a,next,gap)!=(i%2==0)) return null;
            if(i%4==0 && Math.abs(fragment.x-a.x)<=gap*.31f && fragment.y-a.y>gap*3)
                fragmentGroup=i;
        }
        if(fragmentGroup<0 || !shafts(ink,fragment,gap).isEmpty()) return null;
        for(int i=0;i<11;i++) {
            var a=owners.get(i);
            var b=owners.get(i+1);
            if(i%4==3) {
                if(!gapBetween(ink,a,b,axes.get(i),axes.get(i+1),gap,track)) return null;
            } else if (!ownedLink(ink,axes.get(i),axes.get(i+1),i,gap,track,
                    i==fragmentGroup?fragment:null)) return null;
        }
        var trial=new ArrayList<ScoreNoteEvent>();
        for(int i=0;i<owners.size();i++) trial.add(rhythm(owners.get(i).note,i%2==0));
        for(int i=0;i<trial.size();i++) {
            var p=ScoreNoteTiming.writtenPlacement(trial.get(i),trial,3f).orElse(null);
            if(p==null || p.onsetBeats()!=i/2*.5+(i%2==1?.375:0)
                    || p.durationBeats()!=(i%2==0?.375:.125)) return null;
        }
        return new Proof(fragment,owners);
    }

    /** A printed inline meter leaves extra room before the first of three alternating groups. */
    private static Proof inlineMeterHooks(
            Ink ink, MeasureRegion bar, MeasureRegion following, List<Head> row) {
        if (bar.cadenceQuarterBeats() != 0 || row.stream().anyMatch(h -> !regular(h.note)))
            return null;
        float left = bar.left() * ink.w,
                right = bar.right() * ink.w,
                next = following.left() * ink.w;
        float gap = (bar.bottom() - bar.top()) * ink.h / 8.2f;
        if (gap < 10
                || gap > 27
                || right - left < gap * 29
                || right - left > gap * 39
                || Math.abs(following.top() - bar.top()) * ink.h > gap
                || next - right < gap * .35f
                || next - right > gap * 1.65f) return null;
        var suspects = row.stream().filter(h -> h.note.stemDirection() == 1).toList();
        if (suspects.size() != 1) return null;
        var fragment = suspects.get(0);
        if (fragment.note.beamCount() != 1
                || fragment.note.augmentationDots() != 0
                || fragment.note.articulations() != 0) return null;
        var owners =
                row.stream()
                        .filter(h -> h != fragment)
                        .sorted(Comparator.comparingDouble(Head::x))
                        .toList();
        var first = owners.get(0);
        var last = owners.get(11);
        var peer = owners.get(10);
        if (first.x - left < gap * 3.65f
                || first.x - left > gap * 5.5f
                || right - last.x < gap * .8f
                || right - last.x > gap * 1.75f
                || fragment.x <= peer.x
                || fragment.x >= last.x) return null;
        var track = readOctaveStaff(ink, left, right, bar.bottom() * ink.h - 2.1f * gap, gap);
        if (track == null) return null;
        gap = track.at(last.x)[1];
        if (!closingStroke(ink, track, right, next, last.x, gap)
                || !OwnedTertiaryBeamlets.ownsTerminalBackwardHookAlias(
                        ink.pixels,
                        ink.w,
                        ink.h,
                        fragment.x,
                        fragment.y,
                        last.x,
                        last.y,
                        peer.x,
                        peer.y,
                        gap)) return null;

        var trial = new ArrayList<ScoreNoteEvent>(owners.size());
        for (int i = 0; i < owners.size(); i++) {
            var own = owners.get(i);
            boolean dotted = i % 2 == 0;
            if (own.note.stemDirection() != -1
                    || own.note.clefBottomDiatonic() != first.note.clefBottomDiatonic()
                    || own.note.augmentationDots() != (dotted ? 1 : 0)
                    || own.note.beamCount() < 2
                    || dotted && own.note.beamCount() != 2) return null;
            if (i > 0
                    && (own.x - owners.get(i - 1).x < gap * 1.5f
                            || own.x - owners.get(i - 1).x > gap * 4.2f)) return null;
            if (!dotted && own != last && own.note.beamCount() == 2) {
                var leftHead = owners.get(i - 1);
                if (!OwnedTertiaryBeamlets.provesBackwardHook(
                        ink.pixels, ink.w, ink.h, own.x, own.y, leftHead.x, leftHead.y, gap))
                    return null;
            }
            trial.add(rhythm(own.note, dotted));
        }
        for (int i = 0; i < trial.size(); i++) {
            boolean dotted = i % 2 == 0;
            var placement = ScoreNoteTiming.writtenPlacement(trial.get(i), trial, 3f).orElse(null);
            if (placement == null
                    || placement.onsetBeats() != i / 2 * .5 + (dotted ? 0 : .375)
                    || placement.durationBeats() != (dotted ? .375 : .125)) return null;
        }
        return new Proof(fragment, owners);
    }

    private static Head backwardHookAlias(Ink ink, MeasureRegion bar, List<Head> row) {
        if (bar.cadenceQuarterBeats() != 0 || row.stream().anyMatch(h -> !regular(h.note)))
            return null;
        float left = bar.left() * ink.w, right = bar.right() * ink.w;
        float gap = (bar.bottom() - bar.top()) * ink.h / 8.2f;
        if (gap < 10 || gap > 27 || right - left < 28 * gap || right - left > 39 * gap) return null;

        var suspects =
                row.stream()
                        .filter(
                                h ->
                                        h.note.beamCount() == 1
                                                && h.note.augmentationDots() == 0
                                                && h.note.stemDirection() == 1)
                        .toList();
        if (suspects.size() != 1) return null;
        var suspect = suspects.get(0);
        if (suspect.note.articulations() != 0) return null;
        var owners =
                row.stream()
                        .filter(h -> h != suspect)
                        .sorted(Comparator.comparingDouble(Head::x))
                        .toList();
        if (owners.get(0).x - left > gap * 2
                || right - owners.get(owners.size() - 1).x > gap * 2.6f) return null;
        var trial = owners.stream().map(Head::note).toList();
        int hooked = -1;
        for (int i = 0; i < owners.size(); i++) {
            var head = owners.get(i);
            boolean dotted = i % 2 == 0;
            if (head.note.beamCount() != (dotted ? 2 : 3)
                    || head.note.augmentationDots() != (dotted ? 1 : 0)
                    || head.note.clefBottomDiatonic() != suspect.note.clefBottomDiatonic()
                    || head.note.stemDirection() == 0
                    || !dotted
                            && head.note.stemDirection() != owners.get(i - 1).note.stemDirection())
                return null;
            if (i != 0
                    && (head.x - owners.get(i - 1).x < gap * 1.5f
                            || head.x - owners.get(i - 1).x > gap * 4.4f)) return null;
            if (!dotted && owners.get(i - 1).x < suspect.x && suspect.x < head.x) hooked = i;
            var timing = ScoreNoteTiming.writtenPlacement(head.note, trial, 3).orElse(null);
            if (timing == null
                    || timing.onsetBeats() != i / 2 * .5 + (dotted ? 0 : .375)
                    || timing.durationBeats() != (dotted ? .375 : .125)) return null;
        }
        if (hooked < 0) return null;

        var terminal = owners.get(owners.size() - 1);
        float sample = right - gap * 2.4f;
        float hint = terminal.y + terminal.note.staffStep() * gap * .5f;
        var rules = localRules(ink, sample, hint, gap);
        if (rules == null || !barline(ink, right, rules)) return null;
        var owner = owners.get(hooked);
        var peer = owners.get(hooked - 1);
        return OwnedTertiaryBeamlets.ownsBackwardHookAlias(
                        ink.pixels,
                        ink.w,
                        ink.h,
                        suspect.x,
                        suspect.y,
                        owner.x,
                        owner.y,
                        peer.x,
                        peer.y,
                        rules[1])
                ? suspect
                : null;
    }

    private static boolean regular(ScoreNoteEvent n) {
        return regular(n, 1);
    }

    private static boolean regular(ScoreNoteEvent n, int maxDots) {
        return n.unbeamedDurationBeats() == 0 && n.beamCount() > 0 && regularMarkings(n, maxDots);
    }

    private static boolean regularMarkings(ScoreNoteEvent n, int maxDots) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED
                && n.staffIndex() == 0
                && n.staffCount() == 1
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && !n.crossStaffBeam()
                && n.tupletDivisor() == 1
                && n.beamCount() <= 3
                && n.augmentationDots() <= maxDots
                && !n.tiedFromPrevious()
                && n.boundaryTies() == 0
                && n.leadingRestBeats() == 0
                && n.followingRestBeats() == 0
                && n.harmonicSemitones() == 0
                && n.openStringTouch() == null
                && n.clefBottomDiatonic() != ScoreNoteEvent.CLEF_UNKNOWN
                && (n.articulations() & NoteOrnament.GRACE) == 0;
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

    private static boolean barline(Ink p,float x,float[] r) {
        if(r[1]<10 || r[1]>27 || r[0]-4*r[1]<1 || r[0]>=p.h) return false;
        var staff=new PlayingTechniqueDetector.Staff(r[0]-4*r[1],r[0],r[1],0,1);
        return PrintedCadenceDurations.boundaryColumn(p.pixels,p.w,staff,x,true,false)>=0;
    }

    private static List<Shaft> shafts(Ink p,Head h,float gap) {
        int first=Math.round(h.y+gap*.2f),limit=first+Math.round(gap*8.05f);
        var out=new ArrayList<Shaft>();
        for(int x=Math.round(h.x-gap*.81f);x<=Math.round(h.x-gap*.41f);x++) {
            int hit=0,last=first-1;
            for(int y=first;y<limit;y++) {
                if(p.dark(x,y)) {last=y;hit++;}
                if(y-last>Math.round(gap*.33f)) break;
            }
            int span=last-first+1;
            if(span>=gap*2 && hit>=span*.88f && last<limit-2)
                out.add(new Shaft(x,last));
        }
        return out;
    }

    private static boolean dot(Ink p,Head a,float next,float gap) {
        return WrappedTripletDotInk.uniqueCore(p.pixels,p.w,p.h,a.x,a.y,next,gap)!=null
                || LegacyTripletDotInk.singleDotCore(p.pixels,p.w,p.h,a.x,a.y,next,gap);
    }

    private static boolean gapBetween(Ink p,Head a,Head b,List<Shaft> left,List<Shaft> right,
            float g,StaffPitchTrack track) {
        int x=Math.round((a.x+b.x)*.5f-g*.57f);
        int tail=Math.max(left.stream().mapToInt(Shaft::tip).max().orElseThrow(),
                right.stream().mapToInt(Shaft::tip).max().orElseThrow());
        int dark=0,pts=0;
        for(int col=x-2;col<=x+2;col++) {
            var r=track.at(col);
            for(int y=tail-Math.round(g*2.25f);y<=tail+2;y++) {
                if(onRule(r,y,.19f)) continue;
                pts++;
                if(p.dark(col,y)) dark++;
            }
        }
        return pts>30 && dark<=pts*.13f;
    }

    private static boolean ownedLink(Ink p,List<Shaft> left,List<Shaft> right,int slot,
            float g,StaffPitchTrack track,Head fragment) {
        return ownedLink(p, left, right, slot, g, track, fragment, false);
    }

    private static boolean ownedLink(
            Ink p,
            List<Shaft> left,
            List<Shaft> right,
            int slot,
            float g,
            StaffPitchTrack track,
            Head fragment,
            boolean boundedThirdRail) {
        int radius=Math.max(2,Math.round(g*.12f)),margin=Math.round(g*.29f);
        for(var a:left) for(var b:right) {
            if(Math.abs(a.tip-b.tip)>g*.6f || b.x-a.x<g*1.35f) continue;
            int first=a.x+margin,last=b.x-margin;
            for(int inset=Math.round(g*.26f);inset<=Math.round(g*.58f);inset++)
                for(int separation=Math.round(g*.65f);separation<=Math.round(g*.96f);separation++) {
                    var f=new Beam(a,b,inset,separation);
                    if (coverage(p,f,first,last,0,radius)<.94
                            || coverage(p,f,first,last,separation,radius)<.94
                            || coverage(p,f,first,last,separation/2,1)>.93) continue;
                    if (slot%2==0 && !hook(p,f,g,radius,track)) continue;
                        if (boundedThirdRail
                                && continuingThirdRail(p, f, first, last, g, radius, track))
                            continue;
                        if (fragment != null
                                && !beamFragment(p, fragment, f, g, track, boundedThirdRail))
                            continue;
                    return true;
                }
        }
        return false;
    }

    private static boolean continuingThirdRail(
            Ink ink, Beam beam, int first, int last, float gap, int radius, StaffPitchTrack track) {
        for (int shift = Math.round(gap * .53f); shift <= Math.round(gap * 1.12f); shift++) {
            int visible = 0, dark = 0;
            for (int x = first; x <= last; x++) {
                int y = Math.round(beam.outer(x)) - beam.separation - shift;
                if (onRule(track.at(x), y, .18f)) continue;
                visible++;
                if (ink.weight(x, y, radius) >= 2 * radius) dark++;
            }
            if (visible >= gap * .8f && dark >= visible * .86f) return true;
        }
        return false;
    }

    private static float coverage(Ink p,Beam f,int first,int last,int shift,int radius) {
        int count=0;
        for(int x=first;x<=last;x++)
            if(p.weight(x,Math.round(f.outer(x))-shift,radius)>=2*radius) count++;
        return count/(float)(last-first+1);
    }

    private static boolean hook(Ink p,Beam f,float g,int radius,StaffPitchTrack track) {
        int shaft=f.b.x,len=Math.round(g*.66f),outside=Math.round(g*1.1f);
        for(int step=Math.round(g*.53f);step<=Math.round(g*1.12f);step++) {
            int base=Math.round(f.outer(shaft))-f.separation-step;
            int hit=0;
            for(int x=shaft-len;x<=shaft;x++) {
                int y=Math.round(base+(f.outer(x)-f.outer(shaft)));
                if(p.weight(x,y,radius)>=2*radius) hit++;
            }
            if(hit<len || p.weight(shaft,base,1)<2) continue;
            int flank=0;
            for(int dx=outside;dx<=outside+Math.round(g*.36f);dx++) {
                int x=shaft-dx;
                int y=Math.round(base+(f.outer(x)-f.outer(shaft)));
                if(p.weight(x,y,radius)>=2*radius && !onRule(track.at(x),y,.14f)) flank++;
            }
            if(flank<=1 && p.weight(shaft+Math.round(g*.35f),base,radius)<2*radius) return true;
        }
        return false;
    }

    private static boolean beamFragment(
            Ink p, Head h, Beam f, float g, StaffPitchTrack track, boolean octaveRibbon) {
        float maxDepth = octaveRibbon ? 1.55f : 1.12f;
        if (h.x - f.a.x < g * .47f
                || h.x - f.a.x > g * maxDepth
                || h.y < f.outer(h.x) - f.separation - g * .45f
                || h.y > f.outer(h.x) + g * .35f) return false;
        int exterior=0,core=0;
        int left=Math.round(h.x-g*.68f),right=Math.round(h.x+g*.64f);
        for(int x=left;x<=right;x++) {
            var lines=track.at(x);
            for(int y=Math.round(h.y-g*.91f);y<=Math.round(h.y+g*.97f);y++) {
                if(!p.dark(x,y) || onRule(lines,y,.14f)) continue;
                float distance=Math.min(Math.abs(y-f.outer(x)),Math.abs(y-f.outer(x)+f.separation));
                if(distance<=g*.36f) core++;
                else exterior++;
            }
        }
        return core>=g*g*.55f && exterior<=g*.52f;
    }

    private static boolean onRule(float[] rules,float y,float tolerance) {
        float level=(rules[0]-y)/rules[1];
        return level>=-.15f && level<=4.15f && Math.abs(level-Math.round(level))<tolerance;
    }

    private static ScoreNoteEvent rhythm(ScoreNoteEvent n,boolean dotted) {
        if(n.augmentationDots()==(dotted?1:0) && n.beamCount()==(dotted?2:3)) return n;
        return new ScoreNoteEvent(
                n.measureIndex(),
                n.positionInMeasure(),
                n.staffStep(),
                n.staffIndex(),
                n.staffCount(),
                n.pageY(),
                n.tiedFromPrevious(),
                dotted ? 1 : 0,
                dotted ? 2 : 3,
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
                n.stemDirection(),
                n.kind(),
                n.visualSize(),
                n.harmonicSemitones(),
                n.openStringTouch());
    }
}
