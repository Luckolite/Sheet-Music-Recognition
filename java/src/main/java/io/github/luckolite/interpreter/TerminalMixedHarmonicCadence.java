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

    private static boolean plainUpstem(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.staffCount() == 1 && n.staffIndex() == 0
                && n.clefBottomDiatonic() == ScoreNoteEvent.CLEF_TREBLE && n.stemDirection() == 1
                && n.beamCount() == 0 && n.augmentationDots() == 0 && n.tupletDivisor() == 1
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
