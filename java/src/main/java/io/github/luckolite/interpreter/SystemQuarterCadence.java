// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Finds a separately engraved short harmonic cadence after a complete written bar. */
final class SystemQuarterCadence {
    private SystemQuarterCadence() {}

    static List<MeasureRegion> recover(
            List<MeasureRegion> regions, byte[] labels, byte[] gray, int width, int height) {
        if (gray == null || gray.length != (long) width * height) return regions;
        var result = new ArrayList<MeasureRegion>();
        boolean changed = false;
        for (int i = 0; i < regions.size(); i++) {
            var region = regions.get(i);
            float bottom = region.bottom() * height, top = region.top() * height;
            float gap = (bottom - top) / 8.2f;
            boolean lastInRow = i + 1 < regions.size()
                    && Math.abs(regions.get(i + 1).top() * height - top) > gap * 5;
            var split = lastInRow ? prove(region, labels, gray, width, height) : List.<MeasureRegion>of();
            if (lastInRow && split.isEmpty()) split=proveQuarterAfterThirds(region,labels,gray,width,height);
            if (lastInRow && split.isEmpty())
                split = proveQuarterAfterHarmonicTriplets(region, labels, gray, width, height);
            if (split.isEmpty()) result.add(region);
            else {
                result.addAll(split);
                changed = true;
            }
        }
        return changed ? List.copyOf(result) : regions;
    }

    static List<MeasureRegion> prove(
            MeasureRegion region, byte[] labels, byte[] gray, int width, int height) {
        if (region.cadenceQuarterBeats() != 0) return List.of();
        float top = region.top() * height, bottom = region.bottom() * height;
        float gap = (bottom - top) / 8.2f;
        float left = region.left() * width, right = region.right() * width;
        if (gap < 6 || gap > 32 || right - left < gap * 32 || right - left > gap * 45)
            return List.of();
        float hint = bottom - 2.1f * gap;
        var rails = PrintedDirectionStaff.printedRules(gray, width, height, right - gap * 2.5f, hint, gap);
        if (rails == null) rails = PrintedDirectionStaff.printedRules(
                gray, width, height, right - gap * 4, hint, gap);
        if (rails == null) return List.of();
        var staff = new PlayingTechniqueDetector.Staff(rails[0] - rails[1] * 4, rails[0], rails[1], 0, 1);
        if (PrintedCadenceDurations.boundaryColumn(gray, width, staff, right, false, true) < 0)
            return List.of();

        var single = List.of(region);
        var raw = OmrScoreInterpreter.analyze(labels, gray, width, height, single);
        if (!raw.rests().isEmpty() || raw.notes().size() < 17 || raw.notes().size() > 34) return List.of();
        var owners = OmrScoreInterpreter.techniqueStaffs(labels, gray, width, height, single);
        var notes = ArtificialHarmonics.apply(labels, gray, width, height, single, raw.notes(), owners);
        if (notes.size() != 17) return List.of();
        notes = notes.stream().sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
        float[] x = new float[notes.size()], y = new float[notes.size()];
        for (int i = 0; i < notes.size(); i++) {
            var n = notes.get(i);
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffCount() != 1 || n.staffIndex() != 0
                    || n.octaveShift() != 2 || n.tupletDivisor() != 1 || n.augmentationDots() != 0
                    || n.crossStaffBeam() || (n.boundaryTies() & (i == 0 ? 12 : 15)) != 0
                    || n.stemDirection() == 0
                    || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0
                    || (n.articulations() & NoteOrnament.GRACE) != 0)
                return List.of();
            int beams = i == 16 ? 0 : 2;
            float duration = i == 16 ? 1 : 0;
            if (n.beamCount() != beams || n.unbeamedDurationBeats() != duration) return List.of();
            x[i] = left + n.positionInMeasure() * (right - left);
            y[i] = n.pageY() * height;
            if (i == 0 || i == 16) continue;
            if (x[i] - x[i - 1] < gap * 1.3f || x[i] - x[i - 1] > gap * 3.5f) return List.of();
            var p = notes.get(i - 1);
            boolean linked = p.stemDirection() == n.stemDirection()
                    && PrintedTupletBeamOwner.connectedHeads(
                       gray, width, height, x[i - 1], y[i - 1], x[i], y[i], gap, n.stemDirection());
            if (linked != (i % 4 != 0)) return List.of();
        }

        // The separator can only occur in the printed inter-note gap, outside both shafts.
        int start = Math.round(x[15] + gap * 1.35f);
        int end = Math.round(x[16] - gap * 1.05f);
        int first = -1, last = -1;
        for (int column = start; column <= end; column++) {
            if (!crossesSpaces(gray, width, staff, column)) continue;
            if (last >= 0 && column > last + 1) return List.of();
            if (first < 0) first = column;
            last = column;
        }
        if (first < 0 || last - first > gap * .35f) return List.of();
        int cut = (first + last) / 2;
        if (right - cut < gap * 4 || right - cut > gap * 9
                || x[16] - cut < gap * 1.2f || x[16] - cut > gap * 3.5f
                || ClosedHeadBarlineGuard.attached(gray, width, height, cut, Math.round(staff.top()),
                                                    Math.round(staff.bottom()), staff.gap())) return List.of();
        int margin = Math.max(2, Math.round(gap * .5f));
        return List.of(new MeasureRegion(region.left(), (float) (cut - margin) / width,
                                         region.top(), region.bottom()),
                       new MeasureRegion((float) (cut + margin) / width, region.right(),
                                         region.top(), region.bottom(), 1));
    }

    static List<MeasureRegion> proveQuarterAfterThirds(
            MeasureRegion region, byte[] labels, byte[] gray, int width, int height) {
        if (region.cadenceQuarterBeats()!=0) return List.of();
        float left=region.left()*width, right=region.right()*width, span=right-left;
        float bottom=region.bottom()*height, gap=(region.bottom()-region.top())*height/8.2f;
        if (gap<10 || gap>25 || span<gap*50 || span>gap*60 || right+gap>=width)
            return List.of();
        var rails=PrintedDirectionStaff.printedRules(gray,width,height,right-gap*2.5f,bottom-gap*2.1f,gap);
        if (rails==null) return List.of();
        var staff=new PlayingTechniqueDetector.Staff(rails[0]-4*rails[1],rails[0],rails[1],0,1);
        int end=PrintedCadenceDurations.boundaryColumn(gray,width,staff,right,false,true);
        if (end<0) return List.of();
        int first=-1,last=-1;
        for (int x=Math.round(right-gap*12);x<=right-gap*8;x++) {
            if (!crossesSpaces(gray,width,staff,x)) continue;
            if (last>=0 && x!=last+1) return List.of();
            if (first<0) first=x;
            last=x;
        }
        if (first<0 || last-first<1 || last-first>gap*.48f) return List.of();
        int cut=(first+last)/2;
        int staffTop=Math.round(staff.top()), staffBottom=Math.round(staff.bottom());
        if (ClosedHeadBarlineGuard.attached(gray,width,height,cut,staffTop,staffBottom,staff.gap())
                || DottedDyadMeasureSeparator.continuesBeyondStaff(
                        gray,width,height,cut,staffTop,staffBottom,staff.gap())) return List.of();

        var raw=OmrScoreInterpreter.analyze(labels,gray,width,height,List.of(region));
        if (!raw.rests().isEmpty() || (raw.notes().size()!=25 && raw.notes().size()!=26)) return List.of();
        var notes=raw.notes().stream().sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
        var columns=new ArrayList<List<ScoreNoteEvent>>();
        var anchors=new ArrayList<Float>();
        for(var n:notes) {
            if (n.kind()!=ScoreNoteEvent.Kind.PITCHED || n.staffCount()!=1 || n.staffIndex()!=0
                    || n.visualSize()!=ScoreNoteEvent.VisualSize.FULL || n.stemDirection()!=-1
                    || n.augmentationDots()!=0 || n.tupletDivisor()!=1 || n.crossStaffBeam()
                    || n.boundaryTies()!=0 || n.leadingRestBeats()!=0
                    || n.followingRestBeats()!=0 || (n.articulations() & NoteOrnament.GRACE)!=0)
                return List.of();
            float x=left+n.positionInMeasure()*span;
            if (anchors.isEmpty() || x-anchors.get(anchors.size()-1)>gap*.25f) {
                anchors.add(x);
                columns.add(new ArrayList<>(2));
            }
            columns.get(columns.size()-1).add(n);
        }
        if (columns.size()!=13) return List.of();

        for(int i=0;i<columns.size();i++) {
            var owners=columns.get(i);
            var primary=owners.stream().min(Comparator.comparingInt(ScoreNoteEvent::staffStep)).orElseThrow();
            boolean cadence=i==12;
            if ((owners.size()!=2 && (i!=0 || owners.size()!=1))
                    || owners.stream().anyMatch(n -> n.beamCount()!=(cadence?0:2)
                            || n.unbeamedDurationBeats()!=(cadence?1:0)))
                return List.of();
            if(owners.size()==2 && Math.abs(owners.get(0).staffStep()-owners.get(1).staffStep())!=2)
                return List.of();
            float x=anchors.get(i);
            if (cadence) {
                if (owners.stream().anyMatch(ScoreNoteEvent::tiedFromPrevious)
                        || x-cut<gap*3.3f || x-cut>gap*5.5f || cut-anchors.get(11)<gap*1.75f
                        || !PrintedCadenceDurations.emptyOutsideChord(gray,width,staff,
                                cut,end,x-gap*1.55,x,true)) return List.of();
                continue;
            }
            if (i>0) {
                var prior=columns.get(i-1).stream()
                        .min(Comparator.comparingInt(ScoreNoteEvent::staffStep)).orElseThrow();
                float x0=left+span*prior.positionInMeasure(), x1=left+span*primary.positionInMeasure();
                if (x1-x0<gap*2.55f || x1-x0>gap*4.7f) return List.of();
                if (i%4!=0 && !PrintedTupletBeamOwner.connectedHeads(
                        gray,width,height,x0,prior.pageY()*height,x1,primary.pageY()*height,gap,-1))
                    return List.of();
                if (i%4==0 && !wideGapBetweenThirdBeams(
                        gray,width,height,staff,x0,x1,prior.pageY()*height,primary.pageY()*height)) return List.of();
            }
        }
        int padding=Math.round(gap*.5f);
        return List.of(new MeasureRegion(region.left(),(cut-padding)/(float)width,
                            region.top(),region.bottom()),
                       new MeasureRegion((cut+padding)/(float)width,region.right(),
                            region.top(),region.bottom(),1));
    }

    /** A separate quarter harmonic can follow four fully beamed three-note groups. */
    static List<MeasureRegion> proveQuarterAfterHarmonicTriplets(
            MeasureRegion region, byte[] labels, byte[] gray, int width, int height) {
        if (region.cadenceQuarterBeats() != 0 || labels == null || gray == null
                || labels.length != gray.length || gray.length != (long) width * height)
            return List.of();
        float left = region.left() * width, right = region.right() * width;
        float top = region.top() * height, bottom = region.bottom() * height;
        float gap = (bottom - top) / 8.2f;
        if (gap < 10 || gap > 25 || right - left < gap * 26 || right - left > gap * 33
                || right + gap >= width) return List.of();
        var rails = PrintedDirectionStaff.printedRules(
                gray, width, height, right - gap * 2.5f, bottom - gap * 2.1f, gap);
        if (rails == null) return List.of();
        var staff = new PlayingTechniqueDetector.Staff(rails[0] - 4 * rails[1], rails[0], rails[1], 0, 1);
        int closingRule = PrintedCadenceDurations.boundaryColumn(gray, width, staff, right, false, true);
        if (closingRule < 0) return List.of();
        int start = Math.max(0, Math.round(right - gap * 7));
        int end = Math.min(width - 1, Math.round(right - gap * 4));
        int first = -1, last = -1;
        int staffTop = Math.round(staff.top()), staffBottom = Math.round(staff.bottom());
        for (int x = start; x <= end; x++) {
            if (!crossesSpaces(gray, width, staff, x)
                    || ClosedHeadBarlineGuard.attached(
                            gray, width, height, x, staffTop, staffBottom, staff.gap())
                    || DottedDyadMeasureSeparator.continuesBeyondStaff(
                            gray, width, height, x, staffTop, staffBottom, staff.gap())) continue;
            if (last >= 0 && x != last + 1) return List.of();
            if (first < 0) first = x;
            last = x;
        }
        if (first < 0 || last - first + 1 < 2 || last - first + 1 > gap * .35f)
            return List.of();
        int cut = (first + last) / 2;

        var single = List.of(region);
        var source = OmrScoreInterpreter.analyze(labels, gray, width, height, single);
        if (!source.rests().isEmpty() || source.notes().size() < 13 || source.notes().size() > 23)
            return List.of();
        var owners = OmrScoreInterpreter.techniqueStaffs(labels, gray, width, height, single);
        var notes = ArtificialHarmonics.apply(labels, gray, width, height, single, source.notes(), owners);
        var outlined = new ArrayList<ScoreNoteEvent>();
        int unresolved = 0;
        for (var note : notes) {
            float x = left + note.positionInMeasure() * (right - left);
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.staffCount() != 1
                    || note.staffIndex() != 0 || note.crossStaffBeam()
                    || note.tiedFromPrevious() || note.boundaryTies() != 0
                    || note.leadingRestBeats() != 0 || note.followingRestBeats() != 0
                    || (note.articulations() & NoteOrnament.GRACE) != 0) return List.of();
            if (note.stemDirection() != 0) outlined.add(note);
            else if (x >= cut - gap || ++unresolved > 1) return List.of();
        }
        if (outlined.size() != 13) return List.of();
        outlined.sort(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure));
        var budget = new PrintedBeamShafts.Budget(500_000);
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(13);
        int harmonicNotes = 0;
        for (int i = 0; i < outlined.size(); i++) {
            var note = outlined.get(i);
            float x = left + note.positionInMeasure() * (right - left), y = note.pageY() * height;
            if (!(x >= 0 && x < width && y >= 0 && y < height)) return List.of();
            if (i == 12) {
                if (x - cut < gap * 1.7f || x - cut > gap * 3.2f
                        || note.beamCount() != 0 || note.unbeamedDurationBeats() != 1
                        || note.octaveShift() != 2 || note.augmentationDots() != 0
                        || note.tupletDivisor() != 1
                        || !ArtificialHarmonics.joinedQuarterStem(gray, width, height, x, y, gap, note.stemDirection())
                        || !PrintedCadenceDurations.emptyOutsideChord(
                                gray, width, staff, cut, closingRule, x - gap * .35f, x, false)) return List.of();
            } else {
                if (x >= cut - gap || note.beamCount() < 1 || note.beamCount() > 2
                        || note.unbeamedDurationBeats() != 0) return List.of();
                if (note.octaveShift() == 2) harmonicNotes++;
                if (i % 3 == 2) {
                    if (harmonicNotes < 2) return List.of();
                    harmonicNotes = 0;
                }
            }
            var shaftsAtHead = PrintedBeamShafts.at(gray, width, height, x, y, gap, budget);
            if (shaftsAtHead.size() != 1
                    || shaftsAtHead.get(0).direction() != -note.stemDirection()) return List.of();
            var shaft = shaftsAtHead.get(0);
            if (i > 0) {
                var previous = shafts.get(i - 1);
                boolean joined = directHarmonicRail(gray, width, height, previous, shaft, gap);
                if (joined != (i % 3 != 0)) return List.of();
            }
            shafts.add(shaft);
        }
        int margin = Math.max(2, Math.round(gap * .5f));
        return List.of(new MeasureRegion(region.left(), (cut - margin) / (float) width,
                            region.top(), region.bottom()),
                       new MeasureRegion((cut + margin) / (float) width, region.right(),
                            region.top(), region.bottom(), 1));
    }

    static boolean directHarmonicRail(
            byte[] gray, int width, int height, PrintedBeamShafts.Shaft a,
            PrintedBeamShafts.Shaft b, float gap) {
        if (a.direction() != b.direction()) return false;
        int from = (a.left() + a.right()) / 2, to = (b.left() + b.right()) / 2;
        int span = to - from;
        if (span < gap || span > gap * 5) return false;
        int margin = Math.max(2, Math.round(gap * .18f));
        int total = span - margin * 2 + 1;
        for (int inset = Math.round(gap * .19f); inset <= Math.round(gap * .42f); inset++) {
            int supported = 0;
            for (int x = from + margin; x <= to - margin; x++) {
                int y = Math.round(a.endY() + (b.endY() - a.endY()) * (x - from) / (float) span)
                        - a.direction() * inset;
                if (y < 1 || y >= height - 1) return false;
                if ((gray[(y - 1) * width + x] & 255) < 155
                        && (gray[y * width + x] & 255) < 155
                        && (gray[(y + 1) * width + x] & 255) < 155) supported++;
            }
            if (supported >= total * .9f) return true;
        }
        return false;
    }

    private static boolean wideGapBetweenThirdBeams(byte[] gray,int width,int height,
            PlayingTechniqueDetector.Staff staff,float left,float right,float leftHead,float rightHead) {
        float gap=staff.gap();
        int a=Math.round(left+gap*.48f),b=Math.round(right-gap*.95f);
        if (b-a<gap*.5f) return false;
        int start=Math.max(0,Math.round(Math.min(leftHead,rightHead)+gap*1.05f));
        int end=Math.min(height-1,Math.round(staff.bottom()-gap*.75f));
        if (a<0 || b>=width || end-start<gap*2) return false;
        int clean=0;
        for (int x=a;x<=b;x++) {
            int ink=0;
            for (int y=start;y<=end;y++) {
                float line=(staff.bottom()-y)/gap;
                if (line>=-.12f && line<=4.12f && Math.abs(Math.round(line)-line)<.27f) continue;
                if ((gray[y*width+x]&255)<155) ink++;
            }
            if (ink<=Math.round(gap*.17f) && ++clean>=Math.round(gap*.42f)) return true;
            if (ink>Math.round(gap*.17f)) clean=0;
        }
        return false;
    }

    private static boolean crossesSpaces(
            byte[] gray, int width, PlayingTechniqueDetector.Staff staff, int x) {
        for (int i = 0; i < 4; i++) {
            int a = Math.round(staff.top() + (i + .2f) * staff.gap());
            int b = Math.round(staff.top() + (i + .8f) * staff.gap());
            int dark = 0;
            for (int y = a; y <= b; y++)
                if ((gray[y * width + x] & 255) < 112) dark++;
            if (dark < (b - a + 1) * .8f) return false;
        }
        return true;
    }
}
