// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Recovers unflagged harmonic columns when the second column's touch was decoded as a half note. */
final class PenultimateHarmonicQuarterAlias {
    private PenultimateHarmonicQuarterAlias() {}

    private record Owner(int index, ScoreNoteEvent note, float x, float y) {}

    static ScorePageInterpretation capture(ScorePageInterpretation score, byte[] labels,
            byte[] source, byte[] decoder, int width, int height) {
        int barIndex = score.measures().size() - 2;
        if (barIndex < 1 || score.rests().stream().anyMatch(r -> r.measureIndex() == barIndex))
            return score;
        ScoreMeterChange meter = null;
        for (var change : score.meterChanges())
            if (change.measureIndex() <= barIndex) meter = change;
        if (meter == null || meter.numerator() != 2 || meter.denominator() != 4) return score;
        var bar = score.measures().get(barIndex);
        var next = score.measures().get(barIndex + 1);
        if (bar.cadenceQuarterBeats() != 0) return score;
        var detached = removeTouchHalf(score, source, decoder, width, height, barIndex);
        if (detached != score) return detached;
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        var owners = new ArrayList<Owner>(4);
        for (int i = 0; i < score.notes().size(); i++) {
            var note = score.notes().get(i);
            if (note.measureIndex() != barIndex) continue;
            if (!plain(note) || owners.size() == 4 || hasBeamOwner(score, i)) return score;
            owners.add(new Owner(i, note, left + note.positionInMeasure() * span, note.pageY() * height));
        }
        if (owners.size() != 4) return score;
        owners.sort(Comparator.comparingDouble(Owner::x));
        var first = owners.get(0);
        var touch = owners.get(1);
        var stops = owners.subList(2, 4);
        var upper = stops.get(0).y() < stops.get(1).y() ? stops.get(0) : stops.get(1);
        var lower = upper == stops.get(0) ? stops.get(1) : stops.get(0);
        if (first.note().beamCount() != 1 || first.note().unbeamedDurationBeats() != 0
                || first.note().stemDirection() != -1 || first.note().octaveShift() != 2
                || touch.note().beamCount() != 0 || touch.note().unbeamedDurationBeats() != 2
                || touch.note().stemDirection() != 1 || touch.note().octaveShift() != 0
                || !quarter(upper.note()) || !quarter(lower.note())
                || lower.note().stemDirection() != 1
                || upper.note().stemDirection() != 0 && upper.note().stemDirection() != 1
                || upper.note().staffStep() - lower.note().staffStep() != 4
                || touch.note().staffStep() - lower.note().staffStep() != 2) return score;

        if (!prove(labels, source, width, height, bar, next, first, touch, upper, lower)
                || !prove(labels, decoder, width, height, bar, next, first, touch, upper, lower))
            return score;
        var notes = new ArrayList<ScoreNoteEvent>(score.notes().size() - 1);
        var sourceOwners = new ArrayList<Integer>(score.notes().size() - 1);
        for (int i = 0; i < score.notes().size(); i++) {
            if (i == touch.index()) continue;
            notes.add(i == first.index() ? withQuarter(first.note()) : score.notes().get(i));
            sourceOwners.add(i);
        }
        return score.withNotes(notes, sourceOwners);
    }

    private static ScorePageInterpretation removeTouchHalf(
            ScorePageInterpretation score, byte[] source, byte[] decoder, int width, int height, int index) {
        var bars = score.measures();
        var bar = bars.get(index);
        var previous = bars.get(index - 1);
        var next = bars.get(index + 1);
        float nominal = (bar.bottom() - bar.top()) * height / 8.2f;
        float left = bar.left() * width, span = bar.right() * width - left;
        if (nominal < 9 || nominal > 26 || span < nominal * 8.5f || span > nominal * 11.9f
                || Math.abs(bar.top() - next.top()) * height > nominal * .24f
                || Math.abs(bar.top() - previous.top()) * height > nominal * .24f) return score;
        var heads = new ArrayList<Owner>(6);
        for (int i = 0; i < score.notes().size(); i++) {
            var n = score.notes().get(i);
            if (n.measureIndex() != index) continue;
            if (heads.size() == 6) return score;
            heads.add(new Owner(i, n, left + n.positionInMeasure() * span, n.pageY() * height));
        }
        if (heads.size() != 6) return score;
        heads.sort(Comparator.comparingDouble(Owner::x));
        if (heads.get(2).x() - heads.get(0).x() > nominal * .22f
                || heads.get(5).x() - heads.get(3).x() > nominal * .22f
                || heads.get(3).x() - heads.get(2).x() < nominal * 3.5f) return score;
        var right = new ArrayList<>(heads.subList(3, 6));
        right.sort(Comparator.comparingDouble(Owner::y));
        var upper = right.get(0);
        var alias = right.get(1);
        var lower = right.get(2);
        if (!quarterStop(upper.note()) || !quarterStop(lower.note()) || !plain(alias.note())
                || alias.note().unbeamedDurationBeats() != 2 || alias.note().beamCount() != 0
                || alias.note().stemDirection() != 1 || alias.note().octaveShift() != 0
                || upper.note().stemDirection() != 1 || lower.note().stemDirection() != 1
                || upper.note().staffStep() - lower.note().staffStep() != 5
                || alias.note().staffStep() - lower.note().staffStep() != 3
                || heads.subList(0, 3).stream().filter(o -> quarter(o.note())).count() != 2
                || hasBeamOwner(score, alias.index())) return score;
        if (!provedRightTouch(source, width, height, previous, bar, next, upper, alias, lower, nominal)
                || !provedRightTouch(decoder, width, height, previous, bar, next, upper, alias, lower, nominal))
            return score;
        var retained = new ArrayList<ScoreNoteEvent>(score.notes().size() - 1);
        var sourceOwners = new ArrayList<Integer>(score.notes().size() - 1);
        for (int i = 0; i < score.notes().size(); i++) {
            if (i == alias.index()) continue;
            retained.add(score.notes().get(i));
            sourceOwners.add(i);
        }
        return score.withNotes(retained, sourceOwners);
    }

    private static boolean quarterStop(ScoreNoteEvent n) {
        return quarter(n) && n.kind() == ScoreNoteEvent.Kind.PITCHED
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.staffIndex() == 0 && n.staffCount() == 1
                && n.clefBottomDiatonic() == ScoreNoteEvent.CLEF_TREBLE
                && n.augmentationDots() == 0 && n.tupletDivisor() == 1 && !n.crossStaffBeam()
                && n.boundaryTies() == 0 && n.openStringTouch() == null
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0
                && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static boolean provedRightTouch(
            byte[] gray, int width, int height, MeasureRegion previous, MeasureRegion bar,
            MeasureRegion next, Owner upper, Owner alias, Owner lower, float nominal) {
        if (gray == null || gray.length != (long) width * height) return false;
        var rails = PrintedDirectionStaff.printedRules(
                gray, width, height, (bar.left() + bar.right()) * width * .5f,
                bar.bottom() * height - nominal * 2.1f, nominal);
        if (rails == null) return false;
        float gap = rails[1];
        var staff = new PlayingTechniqueDetector.Staff(rails[0] - 4 * gap, rails[0], gap, 0, 1);
        if (Math.abs(upper.y() - (rails[0] - upper.note().staffStep() * gap * .5f)) > gap * .35f
                || Math.abs(lower.y() - (rails[0] - lower.note().staffStep() * gap * .5f)) > gap * .35f
                || Math.abs((lower.y() - alias.y()) - 1.5f * gap) > gap * .32f
                || !filled(gray, width, height, upper, gap) || !filled(gray, width, height, lower, gap)
                || !openCounter(gray, width, alias, gap)
                || !ClosedSixteenthPairBeforeHalf.ownedSeparator(gray,width,height,staff,
                        (previous.right() + bar.left()) * width * .5f)
                || !ClosedSixteenthPairBeforeHalf.ownedSeparator(gray,width,height,staff,
                        (bar.right() + next.left()) * width * .5f)) return false;
        var budget = new PrintedBeamShafts.Budget(72_000);
        var u = shaft(gray, width, height, upper, gap, budget);
        var l = shaft(gray, width, height, lower, gap, budget);
        if (u == null || l == null || u.direction() != -1 || l.direction() != -1) return false;
        for (int stem = Math.max(u.left(), l.left()); stem <= Math.min(u.right(), l.right()); stem++) {
            int end = Math.max(u.endY(), l.endY());
            if (Math.abs(u.endY() - l.endY()) > gap * .15f
                    || !solidShaft(gray, width, height, stem, end, lower.y(), gap)
                    || !ArtificialHarmonics.unflaggedQuarterTip(
                            gray, width, height, stem, upper.y() - 1.5f * gap, upper.y(), end, 1, staff)
                    || !ArtificialHarmonics.shaftCrossedDiamond(
                            gray, width, height, upper.x(), upper.y() - 1.5f * gap, gap, stem, 1, true))
                continue;
            if (ArtificialHarmonics.shaftCrossedDiamond(
                    gray, width, height, alias.x(), alias.y(), gap, stem, 1, true)) return true;
        }
        return false;
    }

    private static boolean hasBeamOwner(ScorePageInterpretation score, int slot) {
        var beams = score.beamSource();
        var origin = beams.noteOrigins().get(slot);
        return origin.isPresent() && beams.groups().stream().flatMap(g -> g.columns().stream())
                .anyMatch(c -> c.members().contains(origin.get()));
    }

    private static boolean quarter(ScoreNoteEvent n) {
        return n.beamCount() == 0 && n.unbeamedDurationBeats() == 1;
    }

    private static boolean plain(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.staffCount() == 1 && n.staffIndex() == 0
                && n.clefBottomDiatonic() == ScoreNoteEvent.CLEF_TREBLE
                && n.augmentationDots() == 0 && n.tupletDivisor() == 1
                && !n.tiedFromPrevious() && !n.crossStaffBeam() && n.boundaryTies() == 0
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0
                && (n.articulations() & NoteOrnament.GRACE) == 0 && n.openStringTouch() == null;
    }

    private static boolean prove(byte[] labels, byte[] gray, int w, int h, MeasureRegion bar,
            MeasureRegion next, Owner first, Owner touch, Owner upper, Owner lower) {
        if (gray == null || labels == null || gray.length != (long) w * h || labels.length != gray.length)
            return false;
        float left = bar.left() * w, right = bar.right() * w, bottom = bar.bottom() * h;
        float nominal = (bar.bottom() - bar.top()) * h / 8.2f;
        if (nominal < 9 || nominal > 26 || right - left < 5.5f * nominal
                || right - left > 9f * nominal || Math.abs(bar.top() - next.top()) * h > .25f * nominal)
            return false;
        var rail = PrintedDirectionStaff.printedRules(
                gray, w, h, first.x(), bottom - 2.1f * nominal, nominal);
        if (rail == null) return false;
        float gap = rail[1];
        var staff = new PlayingTechniqueDetector.Staff(rail[0] - 4 * gap, rail[0], gap, 0, 1);
        if (upper.x() - first.x() < 2.55f * gap || upper.x() - first.x() > 3.55f * gap
                || Math.abs(upper.x() - lower.x()) > .20f * gap
                || Math.abs(touch.x() - lower.x()) > .30f * gap
                || Math.abs(lower.y() - touch.y() - gap) > .18f * gap
                || Math.abs(lower.y() - upper.y() - 2 * gap) > .20f * gap
                || !ClosedSixteenthPairBeforeHalf.ownedSeparator(gray,w,h,staff,(right + next.left()*w)*.5f)
                || !ClosedSixteenthPairBeforeHalf.ownedSeparator(gray,w,h,staff,left)) return false;
        for (var stop : List.of(first, upper, lower))
            if (!filled(gray,w,h,stop,gap)
                    || Math.abs(stop.y() - (rail[0] - stop.note().staffStep() * gap * .5f)) > gap * .38f)
                return false;

        var budget = new PrintedBeamShafts.Budget(300_000);
        var leftShaft = shaft(gray,w,h,first,gap,budget);
        var upperShaft = shaft(gray,w,h,upper,gap,budget);
        var lowerShaft = shaft(gray,w,h,lower,gap,budget);
        if (leftShaft == null || upperShaft == null || lowerShaft == null
                || leftShaft.direction() != 1 || upperShaft.direction() != -1
                || lowerShaft.direction() != -1
                || upperShaft.endY() != lowerShaft.endY())
            return false;
        int leftStem = (leftShaft.left() + leftShaft.right()) / 2;
        int min = Math.max(upperShaft.left(), lowerShaft.left());
        int max = Math.min(upperShaft.right(), lowerShaft.right());
        if (min + 1 > max) return false;
        int rightStem = (min + max) / 2;
        float firstDiamond = first.y() - 1.5f * gap;
        float upperDiamond = upper.y() - 1.5f * gap;
        return ArtificialHarmonics.unflaggedQuarterTip(gray,w,h,leftStem,firstDiamond,first.y(),
                        leftShaft.endY(),-1,staff)
                && clearTip(gray,w,h,leftStem,leftShaft.endY(),1,gap)
                && clearTip(gray,w,h,rightStem,upperShaft.endY(),-1,gap)
                && solidShaft(gray,w,h,leftStem,firstDiamond,leftShaft.endY(),gap)
                && solidShaft(gray,w,h,rightStem,upperShaft.endY(),lower.y(),gap)
                && ArtificialHarmonics.shaftCrossedDiamond(
                        gray,w,h,first.x(),firstDiamond,gap,leftStem,-1,true)
                && ArtificialHarmonics.shaftCrossedDiamond(
                        gray,w,h,upper.x(),upperDiamond,gap,rightStem,1,true)
                && openCounter(gray,w,touch,gap)
                && ArtificialHarmonics.symbolDiamond(labels,gray,w,h,touch.x(),touch.y(),gap)
                && ArtificialHarmonics.shaftCrossedDiamond(
                        gray,w,h,touch.x(),touch.y(),gap,rightStem,1,true);
    }

    private static PrintedBeamShafts.Shaft shaft(byte[] g, int w, int h, Owner o, float gap,
            PrintedBeamShafts.Budget budget) {
        var matches = PrintedBeamShafts.at(g,w,h,o.x(),o.y(),gap,budget);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static boolean filled(byte[] g,int w,int h,Owner head,float gap) {
        int center = Math.round(head.x()), middle = Math.round(head.y());
        int across = Math.round(gap * .23f), down = Math.round(gap * .14f), black = 0;
        for (int y = middle - down; y <= middle + down; y++)
            for (int x = center - across; x <= center + across; x++)
                if (dark(g,w,h,x,y)) black++;
        return black >= .76f * (2*across+1)*(2*down+1);
    }

    private static boolean openCounter(byte[] gray, int width, Owner touch, float gap) {
        int center = Math.round(touch.x()), middle = Math.round(touch.y());
        int across = Math.round(gap * .22f), down = Math.round(gap * .15f), paper = 0;
        for (int y = middle - down; y <= middle + down; y++)
            for (int x = center - across; x <= center + across; x++)
                if ((gray[y * width + x] & 255) >= 210) paper++;
        return paper >= (2 * down + 1) * across / 2;
    }

    private static boolean solidShaft(byte[] g,int w,int h,int x,float top,float bottom,float gap) {
        int total = 0, ink = 0;
        for (int y = Math.round(top + gap*.08f); y <= Math.round(bottom - gap*.08f); y++) {
            total++;
            if (dark(g,w,h,x,y)) ink++;
        }
        return total >= gap && ink >= total * .95f;
    }

    private static boolean clearTip(byte[] g,int w,int h,int stem,int tip,int outward,float gap) {
        int side = Math.max(4,Math.round(gap*.38f));
        for (int offset = 0; offset <= Math.round(gap*.32f); offset++) {
            int y = tip - outward*offset;
            int left = stem;
            while (left > stem - side && dark(g,w,h,left-1,y)) left--;
            int right = stem;
            while (right < stem + side && dark(g,w,h,right+1,y)) right++;
            if (left == stem - side || right == stem + side) return false;
        }
        return true;
    }

    private static boolean dark(byte[] g,int w,int h,int x,int y) {
        return x >= 0 && x < w && y >= 0 && y < h && (g[y*w+x] & 255) < 155;
    }

    private static ScoreNoteEvent withQuarter(ScoreNoteEvent n) {
        return new ScoreNoteEvent(n.measureIndex(),n.positionInMeasure(),n.staffStep(),n.staffIndex(),
                n.staffCount(),n.pageY(),n.tiedFromPrevious(),n.augmentationDots(),0,
                n.writtenAccidental(),1,n.tupletDivisor(),n.followingRestBeats(),n.articulations(),
                n.clefBottomDiatonic(),n.crossStaffBeam(),n.leadingRestBeats(),n.compactOpening(),
                n.octaveShift(),n.boundaryTies(),n.tupletNormalNotes(),n.stemDirection(),n.kind(),
                n.visualSize(),n.harmonicSemitones(),n.openStringTouch());
    }
}
