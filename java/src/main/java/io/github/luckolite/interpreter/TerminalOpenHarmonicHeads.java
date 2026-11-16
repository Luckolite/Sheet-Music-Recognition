// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Literal open-string double stops on a proved short single-staff exercise. */
final class TerminalOpenHarmonicHeads {
    private TerminalOpenHarmonicHeads() {}

    record Head(int staffStep, int writtenMidi, float x, float y, Integer soundingMidi, Integer sourceNoteIndex) {}
    record Chord(int measureIndex, int quarterBeatOffset, List<Head> heads) {}

    static List<Chord> capture(
            ScorePageInterpretation score, byte[] labels, byte[] normalized, byte[] raw,
            int width, int height, float[] beats, List<PlayingTechniqueDetector.Word> words,
            int initialKey, boolean violin) {
        if (labels == null || normalized == null || raw == null || raw.length != (long) width * height
                || labels.length != raw.length || normalized.length != raw.length
                || score.measures().size() != beats.length || beats.length < 4) return List.of();
        var bars = score.measures();
        var last = bars.get(bars.size() - 1);
        float gap = (last.bottom() - last.top()) * height / 8.2f;
        if (gap < 10 || gap > 25) return List.of();
        int first = bars.size() - 1;
        while (first > 0 && sameStaff(last, bars.get(first - 1), gap, height)) first--;
        if (bars.size() - first < 4) return List.of();
        int opening = first;
        var right = bars.size() - 1;
        var clefs = score.notes().stream().filter(n -> n.measureIndex() >= opening)
                .map(ScoreNoteEvent::clefBottomDiatonic).distinct().toList();
        if (!clefs.equals(List.of(ScoreNoteEvent.CLEF_TREBLE))) return List.of();
        int firstKey = initialKey;
        for (var change : score.keyChanges())
            if (change.measureIndex() <= first) firstKey = change.fifths();
        int lastKey = firstKey;
        for (var change : score.keyChanges())
            if (change.measureIndex() > first && change.measureIndex() <= right) lastKey = change.fifths();
        var out = new ArrayList<Chord>(3);
        if (beats[first] == 2 && vacant(score, first)) {
            var proof = TerminalHollowHarmonicBarline.writtenOpening(
                    labels,normalized,width,height,bars.get(first),bars.get(first+1));
            var original = TerminalHollowHarmonicBarline.writtenOpening(
                    labels,raw,width,height,bars.get(first),bars.get(first+1));
            append(out, first, firstKey, violin, width,height, normalized,raw, words, proof, original);
        }
        if (beats[right] == 1 && vacant(score,right)) {
            var proof = TerminalHollowHarmonicBarline.writtenEnding(
                    labels,normalized,width,height,bars.get(right-1),bars.get(right));
            var original = TerminalHollowHarmonicBarline.writtenEnding(
                    labels,raw,width,height,bars.get(right-1),bars.get(right));
            append(out, right, lastKey, violin, width,height, normalized,raw, words, proof, original);
        }
        return List.copyOf(out);
    }

    private static boolean sameStaff(MeasureRegion a,MeasureRegion b,float gap,int h) {
        return Math.abs(a.top()-b.top())*h<=gap*.28f && Math.abs(a.bottom()-b.bottom())*h<=gap*.28f;
    }

    private static boolean vacant(ScorePageInterpretation score, int bar) {
        return score.notes().stream().noneMatch(n -> n.measureIndex() == bar)
                && score.rests().stream().noneMatch(r -> r.measureIndex() == bar);
    }

    private static void append(
            List<Chord> output, int bar, int key, boolean violin, int width, int height,
            byte[] normalized, byte[] original, List<PlayingTechniqueDetector.Word> words,
            List<TerminalHollowHarmonicBarline.HeadPair> geometry,
            List<TerminalHollowHarmonicBarline.HeadPair> source) {
        if (geometry.size()!=source.size()) return;
        var captured=new ArrayList<Chord>();
        for (int i=0;i<geometry.size();i++) {
            var a=geometry.get(i); var b=source.get(i);
            float gap=b.gap();
            if (Math.abs(a.x()-b.x())>1.5f || Math.abs(a.lowerY()-b.lowerY())>1.2f
                    || Math.abs(a.upperY()-b.upperY())>1.2f
                    || a.lower()!=b.lower() || a.upper()!=b.upper()
                    || !hasOpenMark(words,b,true,original,normalized,width,height)
                    || !hasOpenMark(words,b,false,original,normalized,width,height)) return;
            var heads=new ArrayList<Head>(2);
            int[] steps={b.lower(),b.upper()};
            float[] ys={b.lowerY(),b.upperY()};
            for (int h=0;h<2;h++) {
                int touch=ScoreTiePitchGuard.writtenMidi(ScoreNoteEvent.CLEF_TREBLE+steps[h],
                        ScoreNoteEvent.ACCIDENTAL_FROM_KEY,key);
                if (touch<0 || touch>127) return;
                int resolved=violin ? OpenStringHarmonics.uniqueSoundingMidi(touch) : -1;
                if (violin && resolved<0) return;
                heads.add(new Head(steps[h],touch,b.x(),ys[h],resolved<0?null:resolved,null));
            }
            captured.add(new Chord(bar,i,List.copyOf(heads)));
        }
        output.addAll(captured);
    }

    private static boolean hasOpenMark(List<PlayingTechniqueDetector.Word> words,
            TerminalHollowHarmonicBarline.HeadPair pair, boolean above,
            byte[] raw, byte[] normalized, int w, int h) {
        float gap=pair.gap();
        float expected=above?pair.upperY():pair.lowerY();
        for (var word:words) {
            if (!OpenStringHarmonics.isFingering(word.text())) continue;
            int x=Math.round((word.left()+word.right())*w*.5f);
            int low=Math.round(word.top()*h), high=Math.round(word.bottom()*h);
            if (Math.abs(x-pair.x())>gap*.48f || (word.right()-word.left())*w>gap*2.2f
                    || high-low>gap*4) continue;
            int first=Math.max(low+Math.round(gap*.22f), high-Math.round(gap*1.24f));
            int last=high-Math.round(gap*.42f);
            for (int y=last;y>=first;y--) {
                float dy=(y-expected)*(above?-1:1);
                if (dy<gap*1.45f || dy>gap*4.85f) continue;
                if (ArtificialHarmonics.enclosedCounter(raw,w,h,x,y,gap*.82f,gap*.13f)
                        && ArtificialHarmonics.enclosedCounter(normalized,w,h,x,y,gap*.82f,gap*.13f))
                    return true;
            }
        }
        return false;
    }
}
