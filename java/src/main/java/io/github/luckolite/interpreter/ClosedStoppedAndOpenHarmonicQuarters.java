package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

final class ClosedStoppedAndOpenHarmonicQuarters {
    private ClosedStoppedAndOpenHarmonicQuarters() {}

    record Proof(PrintedOpenHarmonicPair.Evidence open, int lowTouch, int highTouch,
            HarmonicFingeringPair.Fingers lowerStopped, HarmonicFingeringPair.Fingers upperStopped,
            HarmonicFingeringPair.Fingers lowerOpen, HarmonicFingeringPair.Fingers upperOpen) {}

    static ScorePageInterpretation recover(ScorePageInterpretation score, List<Map<String,Object>> events,
            byte[] labels, byte[] raw, byte[] normalized, int width, int height, float[] beats, int[] keys,
            double[] starts) {
        if (labels == null || raw == null || normalized == null || raw.length != (long) width * height
                || normalized.length != raw.length || labels.length != raw.length
                || score.notes().size() != events.size()) return score;
        var measures=score.measures(); var notes=score.notes();
        var updated=new ArrayList<>(notes);
        var owners=new ArrayList<>(IntStream.range(0,notes.size()).boxed().toList());
        var byBar = new ArrayList<List<Integer>>(measures.size());
        for (int i = 0; i < measures.size(); i++) byBar.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) byBar.get(notes.get(i).measureIndex()).add(i);
        for(int bar=1;bar+1<measures.size();bar++) {
            int i=bar;
            if(beats[bar]!=2 || score.rests().stream().anyMatch(rest->rest.measureIndex()==i)) continue;
            var local = byBar.get(bar);
            if (local.size() != 3) continue;
            local.sort(java.util.Comparator.comparingDouble(j -> notes.get(j).positionInMeasure()));
            int a=local.get(0),b=local.get(1),touch=local.get(2);
            int lower=notes.get(a).staffStep()<notes.get(b).staffStep()?a:b, upper=lower==a?b:a;
            var lo=notes.get(lower);var hi=notes.get(upper);var half=notes.get(touch);
            if(!plain(lo)||!plain(hi)||!plain(half)||lo.stemDirection()!=-1||hi.stemDirection()!=-1
                    ||lo.beamCount()!=1||hi.beamCount()!=1||lo.octaveShift()!=2||hi.octaveShift()!=2
                    ||half.stemDirection()!=1||half.beamCount()!=0||half.octaveShift()!=0
                    ||half.unbeamedDurationBeats()!=2||hi.staffStep()-lo.staffStep()!=4
                    ||Math.abs(hi.positionInMeasure()-lo.positionInMeasure())>.015f
                    ||half.positionInMeasure()-lo.positionInMeasure()<.35f
                    ||half.positionInMeasure()-lo.positionInMeasure()>.54f) continue;
            double onset = starts[bar];
            if (!expectedEvent(events.get(lower), lower, onset, .5)
                    || !expectedEvent(events.get(upper), upper, onset, .5)
                    || !expectedEvent(events.get(touch), touch, onset + .5, 2)) continue;
            var region=measures.get(bar);
            var source=prove(labels,raw,width,height,measures.get(bar-1),region,measures.get(bar+1),lo,hi,half,keys[bar]);
            if(source==null) continue;
            var other=prove(labels,normalized,width,height,measures.get(bar-1),region,measures.get(bar+1),lo,hi,half,keys[bar]);
            if(!source.equals(other)) continue;
            var open=source.open();
            var low=ClosedOpenAndStoppedHarmonicQuarters.natural(open.lower(),open.lowerY(),
                    pitch(open.lower(),keys[bar]),source.lowerOpen().touch());
            var high=ClosedOpenAndStoppedHarmonicQuarters.natural(open.upper(),open.upperY(),
                    pitch(open.upper(),keys[bar]),source.upperOpen().touch());
            if(low==null||high==null||low.open()==high.open()||half.staffStep()!=open.lower()) continue;
            boolean written = events.get(touch).containsKey("writtenPlacement");
            applyStopped(updated,events,lower,source.lowTouch(),28,source.lowerStopped(),"below",starts[bar]);
            applyStopped(updated,events,upper,source.highTouch(),24,source.upperStopped(),"above",starts[bar]);
            // The lower written touch is already represented by a false half note. Reuse its index.
            var replacements=new ArrayList<ScoreNoteEvent>();
            var sound=new ArrayList<Map<String,Object>>();
            var replacementOrigins=new ArrayList<Integer>();
            ClosedOpenAndStoppedHarmonicQuarters.append(replacements,sound,replacementOrigins,region,
                    bar,open.headX(),low,width,height,starts[bar]+1,"below",1);
            updated.set(touch,replacements.get(0));
            var lowerEvent=sound.get(0);lowerEvent.put("sourceNoteIndex",touch);events.set(touch,lowerEvent);
            ClosedOpenAndStoppedHarmonicQuarters.append(updated,events,owners,region,
                    bar,open.headX(),high,width,height,starts[bar]+1,"above",1);
            if (written) {
                var first = new ScoreNoteTiming.WrittenPlacement(0, 1, -1, -1, false, 0);
                var second = new ScoreNoteTiming.WrittenPlacement(1, 1, -1, -1, false, 0);
                events.get(lower).put("writtenPlacement", first);
                events.get(upper).put("writtenPlacement", first);
                events.get(touch).put("writtenPlacement", second);
                events.get(events.size() - 1).put("writtenPlacement", second);
            }
        }
        return updated.equals(notes)?score:score.withNotes(updated,owners).withClockContextSource(
                score.clockContextSource().withNoteCount(updated.size()));
    }

    private static boolean expectedEvent(Map<String, Object> event, int index,
                                         double onset, double duration) {
        return event.get("sourceNoteIndex") instanceof Number owner && owner.intValue() == index
                && event.get("startBeat") instanceof Number start && start.doubleValue() == onset
                && event.get("durationBeats") instanceof Number length && length.doubleValue() == duration
                && !Boolean.TRUE.equals(event.get("durationFallback"))
                && !Boolean.TRUE.equals(event.get("tiedFromPrevious"));
    }

    private static boolean plain(ScoreNoteEvent n) {
        return n.kind()==ScoreNoteEvent.Kind.PITCHED&&n.staffIndex()==0&&n.staffCount()==1
                &&n.clefBottomDiatonic()==ScoreNoteEvent.CLEF_TREBLE&&n.visualSize()==ScoreNoteEvent.VisualSize.FULL
                &&n.writtenAccidental()==ScoreNoteEvent.ACCIDENTAL_FROM_KEY&&n.augmentationDots()==0
                &&n.tupletDivisor()==1&&n.articulations()==0&&!n.tiedFromPrevious()&&!n.crossStaffBeam()
                &&n.boundaryTies()==0&&n.harmonicSemitones()==0&&n.openStringTouch()==null
                &&n.leadingRestBeats()==0&&n.followingRestBeats()==0;
    }

    static Proof prove(byte[] labels,byte[] ink,int w,int h,MeasureRegion previous,MeasureRegion bar,
            MeasureRegion following,ScoreNoteEvent lo,ScoreNoteEvent hi,ScoreNoteEvent half,int key) {
        float gap=(bar.bottom()-bar.top())*h/8.2f;
        if(gap<10||gap>25||Math.abs(bar.bottom()-previous.bottom())*h>.3f*gap
                ||Math.abs(bar.bottom()-following.bottom())*h>.3f*gap)return null;
        float x=x(lo,bar,w),second=x(half,bar,w);
        var frame=PrintedDirectionStaff.printedRules(ink,w,h,x,bar.bottom()*h-2.1f*gap,gap);
        var next=PrintedDirectionStaff.printedRules(ink,w,h,second,bar.bottom()*h-2.1f*gap,gap);
        if(frame==null||next==null)return null;
        gap=frame[1];
        var staff=new PlayingTechniqueDetector.Staff(frame[0]-4*gap,frame[0],gap,0,1);
        var shaft=ClosedDoubleHarmonicQuarters.stoppedQuarterShaft(ink,w,h,lo,hi,x,x(hi,bar,w),staff);
        if(shaft==null)return null;
        var open=PrintedOpenHarmonicPair.find(labels,ink,w,h,second-.32f*gap,second+.32f*gap,next[0],next[1],key);
        if(open==null||second-x<3.2f*gap||second-x>4.3f*gap)return null;
        int left=PrintedCadenceDurations.boundaryColumn(ink,w,staff,(previous.right()+bar.left())*w*.5f,true,false);
        int right=PrintedCadenceDurations.boundaryColumn(ink,w,staff,(bar.right()+following.left())*w*.5f,true,false);
        if(left<0||right<0||!PrintedCadenceDurations.emptyOutsideChord(ink,w,staff,left,right,x,second,true))
            return null;
        float lowY=frame[0]-(lo.staffStep()+2)*gap*.5f, highY=frame[0]-(hi.staffStep()+3)*gap*.5f;
        int lowTouch=pitch(lo.staffStep()+2,key),highTouch=pitch(hi.staffStep()+3,key);
        if(lowTouch-pitch(lo.staffStep(),key)!=4||highTouch-pitch(hi.staffStep(),key)!=5
                ||!ArtificialHarmonics.symbolDiamond(labels,ink,w,h,x,lowY,gap)
                ||!ArtificialHarmonics.shaftCrossedDiamond(ink,w,h,x,highY,gap,shaft.column(),-1,true))return null;
        float tail=shaft.end();
        var lower=HarmonicFingeringPair.read(ink,w,h,x,tail+.12f*gap,tail+3.1f*gap,gap,true);
        var confirm=HarmonicFingeringPair.read(ink,w,h,x,tail+.23f*gap,tail+3.1f*gap,gap,true);
        var upper=HarmonicFingeringPair.read(ink,w,h,x,highY-4.1f*gap,highY-.53f*gap,gap,false);
        var lowerOpen=HarmonicFingeringPair.read(ink,w,h,open.headX(),open.lowerZeroY()-2.12f*gap,
                open.lowerZeroY()+.8f*gap,gap,false);
        var upperOpen=HarmonicFingeringPair.read(ink,w,h,open.headX(),open.upperZeroY()-2.02f*gap,
                open.upperZeroY()+.66f*gap,gap,false);
        return lower!=null&&lower.equals(confirm)&&upper!=null&&lowerOpen!=null&&upperOpen!=null
                &&lower.base()>0&&upper.base()>0&&lowerOpen.base()==0&&upperOpen.base()==0
                ?new Proof(open,lowTouch,highTouch,lower,upper,lowerOpen,upperOpen):null;
    }

    private static void applyStopped(List<ScoreNoteEvent> notes,List<Map<String,Object>> events,int i,
            int touch,int partial,HarmonicFingeringPair.Fingers fingers,String side,double beat) {
        notes.set(i,ClosedDoubleHarmonicQuarters.withQuarter(notes.get(i),-1));
        ClosedOpenAndStoppedHarmonicQuarters.stopped(notes,events,i,touch,partial,
                ""+fingers.touch()+fingers.base(),side,beat);
    }

    private static int pitch(int step,int key) {
        return ScoreTiePitchGuard.writtenMidi(ScoreNoteEvent.CLEF_TREBLE+step,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY,key);
    }

    private static float x(ScoreNoteEvent n,MeasureRegion bar,int width) {
        return (bar.left()+(bar.right()-bar.left())*n.positionInMeasure())*width;
    }
}
