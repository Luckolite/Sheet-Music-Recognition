// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Recovers a hollow open-string touch sharing the final filled artificial-harmonic quarter shaft. */
final class TerminalMixedHarmonicCadence {
    private TerminalMixedHarmonicCadence() {}

    static ScorePageInterpretation capture(ScorePageInterpretation score, byte[] labels,
            byte[] source, byte[] decoder, int width, int height) {
        int last = score.measures().size() - 1;
        if (last < 1 || score.rests().stream().anyMatch(rest -> rest.measureIndex() == last))
            return score;
        var owners = new ArrayList<Integer>(2);
        for (int i = 0; i < score.notes().size(); i++) {
            if (score.notes().get(i).measureIndex() != last) continue;
            owners.add(i);
            if (owners.size() > 2) return score;
        }
        if (owners.size() != 2) return score;
        owners.sort(java.util.Comparator.comparingInt(i -> score.notes().get(i).staffStep()));
        int lo = owners.get(0), hi = owners.get(1);
        var lower = score.notes().get(lo);
        var upper = score.notes().get(hi);
        if (!plainUpstem(lower) || !plainUpstem(upper)
                || lower.unbeamedDurationBeats() != ScoreNoteEvent.DURATION_HALF
                || upper.unbeamedDurationBeats() != ScoreNoteEvent.DURATION_QUARTER
                || lower.octaveShift() != 0 || upper.octaveShift() != 2
                || upper.staffStep() - lower.staffStep() != 2) return score;

        var bar = score.measures().get(last);
        if (bar.cadenceQuarterBeats() != 0) return score;
        ScoreKeyChange key = null;
        for (var change : score.keyChanges())
            if (change.measureIndex() <= last) key = change;
        if (key == null) return score;
        int touch = writtenMidi(lower, key.fifths()), stop = writtenMidi(upper, key.fifths());
        // In this lower-position double stop the upper attack uses the D string.
        // A lower touch below open D therefore has only the G string available.
        if (touch >= 62 || stop < 62 || stop >= 69) return score;
        int sound = OpenStringHarmonics.uniqueSoundingMidi(touch);
        if (sound != touch + 24) return score;

        int raw = prove(labels, source, width, height, bar, lower, upper);
        if (raw < 0 || prove(labels, decoder, width, height, bar, lower, upper) != raw)
            return score;

        var notes = new ArrayList<>(score.notes());
        notes.set(lo, new ScoreNoteEvent(lower.measureIndex(), lower.positionInMeasure(),
                lower.staffStep(), lower.staffIndex(), lower.staffCount(), lower.pageY(),
                false, 0, 0, lower.writtenAccidental(), ScoreNoteEvent.DURATION_QUARTER, 1,
                0, lower.articulations(), lower.clefBottomDiatonic(), false, 0,
                lower.compactOpening(), 2, 0, 1, lower.stemDirection(),
                lower.kind(), lower.visualSize(), 0, null));
        var regions = new ArrayList<>(score.measures());
        regions.set(last,new MeasureRegion(bar.left(),bar.right(),bar.top(),bar.bottom(),1));
        return new ScorePageInterpretation(regions, notes, score.firstMeasureNumber(),
                score.keyChanges(),score.tempoChanges(),score.meterChanges(),score.rests(),
                score.techniqueChanges(),score.dynamicChanges(),score.playbackDirections(),
                score.expressiveEvents(),score.slurSource(),score.openingPickup(),score.tremoloSource(),
                score.multiRestGroups(),score.beamSource(),score.clockContextSource());
    }

    /** One stopped third harmonic and a separate open-string touch share a quarter stem. */
    static ScorePageInterpretation captureCompound(ScorePageInterpretation score,
            byte[] original, byte[] decoder, int width, int height) {
        int last = score.measures().size() - 1;
        if (last < 1 || score.rests().stream().anyMatch(rest -> rest.measureIndex() == last))
            return score;
        var owners = new ArrayList<Integer>(3);
        for (int i = 0; i < score.notes().size(); i++) {
            if (score.notes().get(i).measureIndex() != last) continue;
            owners.add(i);
            if (owners.size() > 3) return score;
        }
        if (owners.size() != 3) return score;
        owners.sort(java.util.Comparator.comparingInt(i -> score.notes().get(i).staffStep()));
        var bar = score.measures().get(last);
        if (bar.cadenceQuarterBeats() != 0) return score;
        var stopped = score.notes().get(owners.get(0));
        var third = score.notes().get(owners.get(1));
        var open = score.notes().get(owners.get(2));
        if (!beamAlias(stopped, 1) || !beamAlias(third, 1) || !beamAlias(open, 2)
                || third.staffStep() - stopped.staffStep() != 2
                || open.staffStep() - third.staffStep() < 4) return score;
        var meter = score.meterChanges().stream()
                .filter(change -> change.measureIndex() <= last).reduce((a, b) -> b).orElse(null);
        var key = score.keyChanges().stream()
                .filter(change -> change.measureIndex() <= last).reduce((a, b) -> b).orElse(null);
        if (meter == null || meter.numerator() != 2 || meter.denominator() != 4 || key == null)
            return score;
        int base = writtenMidi(stopped, key.fifths());
        int touch = writtenMidi(third, key.fifths());
        int natural = OpenStringHarmonics.uniqueSoundingMidi(writtenMidi(open, key.fifths()));
        if (touch - base != 4 || base + 28 > 127 || natural < 0 || natural == base + 28)
            return score;
        int raw = compoundShaft(original, width, height, bar, stopped, third, open);
        if (raw < 0 || compoundShaft(decoder, width, height, bar, stopped, third, open) != raw)
            return score;

        var quarter = new ScoreNoteEvent(last, stopped.positionInMeasure(), stopped.staffStep(),
                stopped.staffIndex(), stopped.staffCount(), stopped.pageY(), false, 0, 0,
                stopped.writtenAccidental(), ScoreNoteEvent.DURATION_QUARTER, 1, 0, 0,
                stopped.clefBottomDiatonic(), false, 0, false, 0, 0, 1, 1)
                .withHarmonicSemitones(28)
                .withOpenStringTouch(new ScoreNoteEvent.OpenStringTouch(
                        open.staffStep(), open.pageY(), open.writtenAccidental()));
        var notes = new ArrayList<ScoreNoteEvent>();
        var origins = new ArrayList<Integer>();
        for (int i = 0; i < score.notes().size(); i++) {
            if (i == owners.get(1) || i == owners.get(2)) continue;
            notes.add(i == owners.get(0) ? quarter : score.notes().get(i));
            origins.add(i);
        }
        var edited = score.withNotes(notes, origins);
        var measures = new ArrayList<>(score.measures());
        measures.set(last, new MeasureRegion(bar.left(), bar.right(), bar.top(), bar.bottom(), 1));
        return new ScorePageInterpretation(measures, edited.notes(), edited.firstMeasureNumber(),
                edited.keyChanges(), edited.tempoChanges(), edited.meterChanges(), edited.rests(),
                edited.techniqueChanges(), edited.dynamicChanges(), edited.playbackDirections(),
                edited.expressiveEvents(), edited.slurSource(), edited.openingPickup(), edited.tremoloSource(),
                edited.multiRestGroups(), edited.beamSource());
    }

    private static boolean beamAlias(ScoreNoteEvent n, int beams) {
        return clearUpstem(n) && n.beamCount() == beams && n.unbeamedDurationBeats() == 0
                && n.octaveShift() == 0 && n.articulations() == 0
                && n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
    }

    static int compoundShaft(byte[] gray, int width, int height, MeasureRegion bar,
            ScoreNoteEvent stopped, ScoreNoteEvent third, ScoreNoteEvent open) {
        if (gray == null || gray.length != (long) width * height) return -1;
        float nominal = (bar.bottom() - bar.top()) * height / 8.2f;
        float left = bar.left() * width, right = bar.right() * width;
        float span = right - left;
        if (nominal < 10 || nominal > 26 || span < 4.5f * nominal || span > 8.5f * nominal)
            return -1;
        var rails = PrintedDirectionStaff.printedRules(gray, width, height, right - 2.6f * nominal,
                bar.bottom() * height - 2.1f * nominal, nominal);
        if (rails == null) return -1;
        float gap = rails[1];
        var staff = new PlayingTechniqueDetector.Staff(rails[0] - 4 * gap, rails[0], gap, 0, 1);
        float x = left + stopped.positionInMeasure() * span, y = stopped.pageY() * height;
        float tx = left + third.positionInMeasure() * span, ty = third.pageY() * height;
        float ox = left + open.positionInMeasure() * span, oy = open.pageY() * height;
        if (Math.abs(x - ox) > gap * .26f || Math.abs(x - tx) > gap * .2f
                || Math.abs(y - (rails[0] - stopped.staffStep() * gap * .5f)) > gap * .3f
                || Math.abs(ty - (rails[0] - third.staffStep() * gap * .5f)) > gap * .22f
                || Math.abs(oy - (rails[0] - open.staffStep() * gap * .5f)) > gap * .16f
                || ty <= rails[0] + gap * .55f || !filled(gray, width, height, x, y, gap)) return -1;
        int leftRule = PrintedCadenceDurations.boundaryColumn(gray, width, staff, left - gap * .5f, true, false);
        int rightRule = PrintedCadenceDurations.boundaryColumn(gray, width, staff, right, false, true);
        if (leftRule < 0 || rightRule < 0 || !PrintedCadenceDurations.emptyOutsideChord(
                gray, width, staff, left, rightRule, Math.min(ox, x), Math.max(ox, x), false)) return -1;
        var budget = new PrintedBeamShafts.Budget(100_000);
        var baseShafts = PrintedBeamShafts.at(gray, width, height, x, y, gap, budget);
        var thirdShafts = PrintedBeamShafts.at(gray, width, height, tx, ty, gap, budget);
        var openShafts = PrintedBeamShafts.at(gray, width, height, ox, oy, gap, budget);
        if (baseShafts.size() != 1 || thirdShafts.size() != 1 || openShafts.size() != 1)
            return -1;
        var shaft = baseShafts.get(0);
        var middle = thirdShafts.get(0);
        var upper = openShafts.get(0);
        if (shaft.direction() != -1 || middle.direction() != -1 || upper.direction() != -1
                || shaft.endY() != middle.endY() || shaft.endY() != upper.endY()) return -1;
        int first = Math.max(shaft.left(), Math.max(middle.left(), upper.left()));
        int last = Math.min(shaft.right(), Math.min(middle.right(), upper.right()));
        for (int stem = first; stem <= last; stem++) {
            if (ArtificialHarmonics.unflaggedQuarterTip(
                        gray, width, height, stem, oy, y, shaft.endY(), 1, staff)
                    && ArtificialHarmonics.shaftCrossedDiamond(gray, width, height, ox, oy, gap, stem, 1, true)
                    && ledgerDiamond(gray, width, height, tx, ty, gap, stem)) return stem;
        }
        return -1;
    }

    private static boolean ledgerDiamond(byte[] gray, int w, int h, float x, float y, float gap, int shaft) {
        int center = Math.round(x);
        int spread = Math.round(gap * .85f);
        int first = center - spread, end = shaft - 2;
        int outerLeft = center - Math.round(.83f * gap), outerRight = shaft + Math.round(.30f * gap);
        int ledger = Math.round(y);
        if (!dark(gray, w, h, outerLeft, ledger) || !dark(gray, w, h, outerRight, ledger)
                || rowWidth(gray, w, h, outerLeft, outerRight, ledger) < (outerRight - outerLeft + 1) * .9f)
            return false;
        // The ledger interrupts the two flanks. Measure the upper tip and the lower taper on paper.
        for (int row = Math.round(y - .76f * gap); row <= Math.round(y - .53f * gap); row++) {
            int tip = rowWidth(gray, w, h, first, end, row);
            if (tip < 2 || tip > .35f * gap || !dark(gray, w, h, center, row)
                    || rowWidth(gray, w, h, first, end, row - 2) > 1) continue;
            int rising = rowWidth(gray, w, h, first, end, row + Math.round(.13f * gap));
            int shoulder = rowWidth(gray, w, h, first, end, row + Math.round(.26f * gap));
            int falling = rowWidth(gray, w, h, first, end, Math.round(y + .22f * gap));
            int foot = rowWidth(gray, w, h, first, end, Math.round(y + .47f * gap));
            if (rising - tip >= .17f * gap && shoulder - rising >= .17f * gap
                    && falling >= .65f * gap && falling - foot >= .24f * gap) return true;
        }
        return false;
    }

    private static int rowWidth(byte[] gray, int w, int h, int left, int right, int row) {
        int ink = 0;
        for (int col = left; col <= right; col++)
            if (dark(gray, w, h, col, row)) ink++;
        return ink;
    }

    private static boolean plainUpstem(ScoreNoteEvent n) {
        return clearUpstem(n) && n.beamCount() == 0;
    }

    private static boolean clearUpstem(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.staffCount() == 1 && n.staffIndex() == 0
                && n.clefBottomDiatonic() == ScoreNoteEvent.CLEF_TREBLE && n.stemDirection() == 1
                && n.augmentationDots() == 0 && n.tupletDivisor() == 1
                && !n.tiedFromPrevious() && !n.crossStaffBeam() && n.boundaryTies() == 0
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0
                && (n.articulations() & NoteOrnament.GRACE) == 0
                && n.harmonicSemitones() == 0 && n.openStringTouch() == null;
    }

    private static int writtenMidi(ScoreNoteEvent n, int fifths) {
        return ScoreTiePitchGuard.writtenMidi(
                n.clefBottomDiatonic() + n.staffStep(),n.writtenAccidental(),fifths);
    }

    private static int prove(byte[] labels,byte[] gray,int w,int h,MeasureRegion bar,
            ScoreNoteEvent lower,ScoreNoteEvent upper) {
        if (gray == null || labels == null || gray.length != (long)w*h || labels.length != gray.length)
            return -1;
        float left=bar.left()*w, right=bar.right()*w, bottom=bar.bottom()*h;
        float guess=(bar.bottom()-bar.top())*h/8.2f;
        if (guess<9 || guess>26 || right-left<4f*guess || right-left>8.5f*guess) return -1;
        var rails=PrintedDirectionStaff.printedRules(gray,w,h,left+1.5f*guess,bottom-2.1f*guess,guess);
        if (rails==null) return -1;
        float gap=rails[1];
        var staff=new PlayingTechniqueDetector.Staff(rails[0]-4*gap,rails[0],gap,0,1);
        float x1=left+(right-left)*lower.positionInMeasure(),x2=left+(right-left)*upper.positionInMeasure();
        float y1=lower.pageY()*h,y2=upper.pageY()*h;
        if (Math.abs(x1-x2)>gap*.18f || y1-y2<gap*.80f || y1-y2>gap*1.16f
                || Math.abs(y1-(rails[0]-lower.staffStep()*gap*.5f))>gap*.16f
                || Math.abs(y2-(rails[0]-upper.staffStep()*gap*.5f))>gap*.16f) return -1;
        int opening=PrintedCadenceDurations.boundaryColumn(gray,w,staff,left-gap*.5f,true,false);
        int closing=PrintedCadenceDurations.boundaryColumn(gray,w,staff,right,false,true);
        if (opening<0 || closing<0
                || !PrintedCadenceDurations.emptyOutsideChord(
                        gray,w,staff,left,Math.min(right,closing+.45f*gap),
                        Math.min(x1,x2),Math.max(x1,x2),false)) return -1;

        var budget=new PrintedBeamShafts.Budget(300_000);
        var a=PrintedBeamShafts.at(gray,w,h,x1,y1,gap,budget);
        var b=PrintedBeamShafts.at(gray,w,h,x2,y2,gap,budget);
        if (a.size()!=1 || b.size()!=1) return -1;
        var lowerShaft=a.get(0);
        var upperShaft=b.get(0);
        if (lowerShaft.direction()!=-1 || upperShaft.direction()!=-1
                || lowerShaft.left()!=upperShaft.left() || lowerShaft.right()!=upperShaft.right()
                || lowerShaft.endY()!=upperShaft.endY()
                || !unflagged(gray,w,h,lowerShaft,staff.top(),gap)) return -1;
        int shaft=(lowerShaft.left()+lowerShaft.right())/2;
        for (int y=lowerShaft.endY()+1; y<=Math.round(y1); y++)
            if (!dark(gray,w,h,shaft,y)) return -1;
        if (!filled(gray,w,h,x2,y2,gap)
                || !ArtificialHarmonics.quarterTouchDiamond(labels,gray,w,h,x2,y2,gap)
                || !ArtificialHarmonics.shaftCrossedDiamond(gray,w,h,x1,y1,gap,shaft,1,true)
                || !lowerDiamondTip(gray,w,h,x1,y1,gap)) return -1;
        return shaft;
    }

    private static boolean filled(byte[] gray,int w,int h,float x,float y,float gap) {
        int across=Math.round(gap*.28f), down=Math.round(gap*.16f);
        int black=0;
        for(int row=Math.round(y)-down;row<=Math.round(y)+down;row++)
            for(int col=Math.round(x)-across;col<=Math.round(x)+across;col++)
                if(dark(gray,w,h,col,row))black++;
        return black>=.85f*(2*across+1)*(2*down+1);
    }

    private static boolean lowerDiamondTip(byte[] gray,int w,int h,float x,float y,float gap) {
        int row=Math.round(y+gap*.7f), middle=Math.round(x), span=Math.round(gap*.6f);
        int black=0;
        for(int col=middle-span;col<=middle+span;col++)
            if(dark(gray,w,h,col,row))black++;
        return black>=gap*.18f && black<=gap*.65f;
    }

    private static boolean unflagged(byte[] gray,int w,int h,PrintedBeamShafts.Shaft shaft,
            float staffTop,float gap) {
        int tip=shaft.endY(), edge=Math.round(staffTop-gap*.17f);
        if (tip<staffTop-gap*.9f || tip>staffTop-gap*.24f) return false;
        int protrusion=Math.max(3,Math.round(gap*.22f));
        for(int row=tip;row<=edge;row++) {
            if(dark(gray,w,h,shaft.left()-protrusion,row)
                    || dark(gray,w,h,shaft.right()+protrusion,row)) return false;
        }
        return true;
    }

    private static boolean dark(byte[] gray,int w,int h,int x,int y) {
        return x>=0 && x<w && y>=0 && y<h && (gray[y*w+x]&255)<155;
    }
}
