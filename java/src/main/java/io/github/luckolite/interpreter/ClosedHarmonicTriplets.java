// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;

/** A closed common-time bar of four independent beamed harmonic triples. */
final class ClosedHarmonicTriplets {
    private ClosedHarmonicTriplets() {}

    record Capture(List<ScoreNoteEvent> notes, List<List<Integer>> bars) {
        ScorePageInterpretation projectBeams(ScorePageInterpretation score, int page) {
            if (page < 0 || bars.isEmpty() || score.notes().size() != notes.size()) return score;
            var previous = score.beamSource();
            var origins = new ArrayList<>(previous.noteOrigins());
            var groups = new ArrayList<>(previous.groups());
            var claimed = new java.util.HashSet<ScoreBeamSource.Anchor>();
            var ids = new java.util.HashSet<String>();
            var presence = new java.util.HashMap<ScoreBeamSource.Anchor, Integer>();
            for (var origin : origins) origin.ifPresent(a -> presence.merge(a, 1, Integer::sum));
            for (var group : groups) {
                ids.add(group.id());
                for (var column : group.columns()) claimed.addAll(column.members());
            }
            for (var column : previous.detachedColumns()) claimed.addAll(column.members());
            for (var bar : bars) {
                var candidates = new ArrayList<ScoreBeamSource.Anchor>(12);
                for (int i : bar) {
                    var anchor = new ScoreBeamSource.Anchor(page, i);
                    var existing = origins.get(i);
                    int count = presence.getOrDefault(anchor, 0);
                    if (claimed.contains(anchor)
                            || !score.notes().get(i).equals(notes.get(i))
                            || (existing.isPresent() ? !existing.get().equals(anchor) || count != 1 : count != 0))
                        break;
                    candidates.add(anchor);
                }
                if (candidates.size() != bar.size()) continue;
                var additions = new ArrayList<ScoreBeamSource.Group>(4);
                for (int i = 0; i < candidates.size(); i += 3) {
                    String id = "printed-beam:" + page + ":" + candidates.get(i).noteIndex();
                    if (ids.contains(id)) break;
                    var columns = candidates.subList(i, i + 3).stream()
                            .map(a -> new ScoreBeamSource.Column(List.of(a))).toList();
                    additions.add(new ScoreBeamSource.Group(id, columns, List.of(1, 1), List.of(),
                            ScoreBeamSource.TupletMark.UNNUMBERED));
                }
                if (additions.size() != 4) continue;
                groups.addAll(additions);
                for (var group : additions) ids.add(group.id());
                for (int i = 0; i < bar.size(); i++) {
                    var anchor = candidates.get(i);
                    origins.set(bar.get(i), java.util.Optional.of(anchor));
                    claimed.add(anchor);
                    presence.put(anchor, 1);
                }
            }
            return groups.size() == previous.groups().size() ? score
                    : score.withBeamSource(new ScoreBeamSource(groups, previous.detachedColumns(), origins));
        }
    }

    private record Proved(List<ScoreNoteEvent> notes, boolean engravingClear) {}

    static Capture capture(
            List<ScoreNoteEvent> notes, List<ScoreRestEvent> rests, List<MeasureRegion> measures,
            List<ScoreMeterChange> meters, ScoreMeterChange inherited, byte[] source,
            int width, int height) {
        if (notes.isEmpty() || width < 3 || height < 3 || source == null
                || source.length != (long) width * height) return new Capture(notes, List.of());
        var byMeasure = new ArrayList<List<Integer>>(measures.size());
        for (int i = 0; i < measures.size(); i++) byMeasure.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) byMeasure.get(notes.get(i).measureIndex()).add(i);
        var isRest = new BitSet(measures.size());
        for (var rest : rests) isRest.set(rest.measureIndex());
        List<ScoreNoteEvent> result = null;
        var bars = new ArrayList<List<Integer>>();
        ScoreMeterChange meter = inherited;
        int meterIndex = 0;
        for (int bar = 0; bar + 1 < measures.size(); bar++) {
            while (meterIndex < meters.size() && meters.get(meterIndex).measureIndex() <= bar)
                meter = meters.get(meterIndex++);
            if (meter == null || meter.numerator() != 4 || meter.denominator() != 4 || isRest.get(bar)
                    || isRest.get(bar + 1) || byMeasure.get(bar).size() != 12
                    || byMeasure.get(bar + 1).size() != 1) continue;
            var here = measures.get(bar);
            var next = measures.get(bar + 1);
            var cadence = notes.get(byMeasure.get(bar + 1).get(0));
            if (here.cadenceQuarterBeats() != 0 || next.cadenceQuarterBeats() != 1
                    || here.top() != next.top() || here.bottom() != next.bottom()
                    || !ordinary(cadence) || cadence.beamCount() != 0
                    || cadence.octaveShift() != 2 || cadence.unbeamedDurationBeats() != 1
                    || cadence.augmentationDots() != 0 || cadence.tupletDivisor() != 1) continue;
            var ordinals = byMeasure.get(bar).stream()
                    .sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure())).toList();
            var line = ordinals.stream().map(notes::get).toList();
            var rows = UnnumberedTripletInk.sourceRows(here, measures, height);
            var adjusted = prove(line, here, next, cadence, source, width, height, rows);
            if (adjusted == null || adjusted.notes().equals(line)) continue;
            if (adjusted.engravingClear()) bars.add(ordinals);
            if (result == null) result = new ArrayList<>(notes);
            for (int i = 0; i < ordinals.size(); i++) result.set(ordinals.get(i), adjusted.notes().get(i));
        }
        return result == null ? new Capture(notes, List.of())
                : new Capture(List.copyOf(result), List.copyOf(bars));
    }

    private static boolean ordinary(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && note.staffCount() == 1 && note.staffIndex() == 0
                && Math.abs(note.stemDirection()) == 1 && !note.crossStaffBeam()
                && !note.tiedFromPrevious() && note.boundaryTies() == 0
                && note.leadingRestBeats() == 0 && note.followingRestBeats() == 0
                && (note.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static Proved prove(
            List<ScoreNoteEvent> line, MeasureRegion bar, MeasureRegion following,
            ScoreNoteEvent cadence, byte[] source, int w, int h, UnnumberedTripletInk.Rows rows) {
        float gap = (bar.bottom() - bar.top()) * h / 8.2f;
        float span = (bar.right() - bar.left()) * w;
        if (gap < 10 || gap > 25 || span < gap * 22f || span > gap * 28f) return null;
        float[] xs = new float[12], ys = new float[12];
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(12);
        var budget = new PrintedBeamShafts.Budget(425_000);
        boolean[] borrowedDots = new boolean[12];
        int harmonics = 0;
        for (int i = 0; i < line.size(); i++) {
            var note = line.get(i);
            if (!ordinary(note) || note.beamCount() < 1 || note.beamCount() > 2
                    || note.unbeamedDurationBeats() != 0 || note.augmentationDots() > 1
                    || !(note.tupletDivisor() == 1 && note.tupletNormalNotes() == 1
                            || note.tupletDivisor() == 3 && note.tupletNormalNotes() == 2)) return null;
            if (note.octaveShift() == 2) harmonics++;
            if (i % 3 == 2) {
                if (harmonics < 2 || note.augmentationDots() != 0) return null;
                harmonics = 0;
            }
            xs[i] = bar.left() * w + span * note.positionInMeasure();
            ys[i] = note.pageY() * h;
            if (!(xs[i] >= 0 && xs[i] < w && ys[i] >= 0 && ys[i] < h)) return null;
            var current = PrintedBeamShafts.at(source, w, h, xs[i], ys[i], gap, budget);
            if (current.size() != 1 || current.get(0).direction() != -note.stemDirection()) return null;
            var shaft = current.get(0);
            if (i > 0) {
                if (xs[i] - xs[i-1] < gap * 1.5f || xs[i] - xs[i-1] > gap * 2.6f
                        || SystemQuarterCadence.directHarmonicRail(
                                source, w, h, shafts.get(i-1), shaft, gap) != (i % 3 != 0)) return null;
                if (line.get(i-1).augmentationDots() != 0) {
                    if (i % 3 == 0 || note.stemDirection() != 1
                            || ys[i-1] - ys[i] < gap * .82f || ys[i-1] - ys[i] > gap * 1.15f
                            || !dotBelongsToNext(source, w, h, xs[i-1], ys[i-1], xs[i], ys[i], gap))
                        return null;
                    borrowedDots[i] = true;
                }
            }
            shafts.add(shaft);
        }
        if (!WrappedMeasureBoundaries.confirmsClosedTripletBar(source, w, h, bar, xs[0], xs[11]))
            return null;
        float lastX = (following.left()+cadence.positionInMeasure()*(following.right()-following.left()))*w;
        float lastY = cadence.pageY()*h;
        if (!ArtificialHarmonics.joinedQuarterStem(source,w,h,lastX,lastY,gap,cadence.stemDirection()))
            return null;
        boolean engravingClear = true;
        for (int i = 0; i < 12; i += 3) {
            if (BoundedSecondaryBeam.proves(source, w, h,
                    Arrays.copyOfRange(xs, i, i+3), Arrays.copyOfRange(ys, i, i+3), gap)) return null;
            for (int j = i; j < i+3; j++) {
                var shaft = shafts.get(j);
                int center = (shaft.left()+shaft.right())/2;
                if (j > i && containsAdditionalRail(source, w, h, shafts.get(j-1), shaft, gap))
                    return null;
                if (StemOwnedBeamlet.proves(source, w, h,
                        new int[]{center,shaft.endY(),shaft.direction()}, gap)) return null;
            }
            if (engravingClear)
                engravingClear = UnnumberedTripletInk.clear(source, w, h, gap,
                        Arrays.copyOfRange(xs, i, i + 3), Arrays.copyOfRange(ys, i, i + 3), shafts.subList(i, i + 3), rows);
        }
        var adjusted = new ArrayList<ScoreNoteEvent>(12);
        for (int i = 0; i < line.size(); i++) {
            var n = line.get(i);
            int art = n.articulations() | (borrowedDots[i] ? NoteArticulation.STACCATO : 0);
            adjusted.add(new ScoreNoteEvent(
                    n.measureIndex(), n.positionInMeasure(), n.staffStep(), n.staffIndex(), n.staffCount(),
                    n.pageY(), n.tiedFromPrevious(), 0, 1, n.writtenAccidental(), 0,
                    3, n.followingRestBeats(), art, n.clefBottomDiatonic(), n.crossStaffBeam(),
                    n.leadingRestBeats(), n.compactOpening(), n.octaveShift(), n.boundaryTies(),
                    2, n.stemDirection(), n.kind(), n.visualSize(), n.harmonicSemitones(),
                    n.openStringTouch()));
        }
        return new Proved(List.copyOf(adjusted), engravingClear);
    }

    /** A second thick rail must not be discarded when an older label counts only one. */
    private static boolean containsAdditionalRail(
            byte[] source, int width, int height, PrintedBeamShafts.Shaft a,
            PrintedBeamShafts.Shaft b, float gap) {
        int from=(a.left()+a.right())/2, to=(b.left()+b.right())/2;
        int margin=Math.round(gap*.23f);
        int total=to-from-2*margin+1;
        if(total<gap*.7f) return false;
        for (int shift=Math.round(gap*.75f); shift<=Math.round(gap*1.45f); shift++) {
            int heavy=0;
            for (int x=from+margin; x<=to-margin; x++) {
                int y=Math.round(a.endY()+(b.endY()-a.endY())*(x-from)/(float)(to-from))-a.direction()*shift;
                if (y<2 || y>=height-2) return false;
                if ((source[(y-2)*width+x]&255)<155 && (source[(y-1)*width+x]&255)<155
                        && (source[y*width+x]&255)<155 && (source[(y+1)*width+x]&255)<155
                        && (source[(y+2)*width+x]&255)<155) heavy++;
            }
            if(heavy>=total*.72f) return true;
        }
        return false;
    }

    private static boolean dotBelongsToNext(
            byte[] source, int w, int h, float x, float y, float nextX, float nextY, float gap) {
        var core = LegacyTripletDotInk.uniqueDotCore(source,w,h,x,y,nextX+gap*.92f,gap);
        if (core == null) return false;
        float cx = (core.left()+core.right())*.5f, cy = (core.top()+core.bottom())*.5f;
        float dy = cy - nextY;
        return Math.abs(cx-nextX) <= gap*.25f && dy >= gap*1.18f && dy <= gap*1.55f;
    }
}
