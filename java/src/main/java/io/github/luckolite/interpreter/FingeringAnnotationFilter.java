// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import java.util.regex.Pattern;

/** Cross-checks above-staff false heads against independently recognized fingering text. */
final class FingeringAnnotationFilter {
    private static final Pattern SINGLE_DIGIT = Pattern.compile("[1-5]");
    private static final Pattern HAND_OR_DIGIT = Pattern.compile("[LH]?[1-5]");
    private static final Pattern HAND_SEQUENCE = Pattern.compile("[LRH]{2,3}");
    private static final Pattern DIGIT_SEQUENCE = Pattern.compile("[1-5]{2,4}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern OPEN_FINGER = Pattern.compile("\\(\\s*[1-5]");
    private static final Pattern CLOSE_FINGER = Pattern.compile("[1-5]\\s*\\)");
    private static final Pattern SPLIT_FINGERING_RUN =
            Pattern.compile("[1-5](?:\\s+[1-5]){3,7}");
    private static final Pattern SINGLE_SEGUE = Pattern.compile("segue[.]?", Pattern.CASE_INSENSITIVE);
    private static final Pattern FINGERS_AND_SEGUE =
            Pattern.compile("(?:[1-5]\\s+){4,8}segue[.]?", Pattern.CASE_INSENSITIVE);

    private FingeringAnnotationFilter() {}

    private record Candidate(
            float left, float right, float top, float bottom,
            boolean fingering, boolean digit, int sequenceLength) {}

    static List<ScoreNoteEvent> apply(
            List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            byte[] gray,
            int width,
            int height) {
        if (words == null
                || words.isEmpty()
                || notes.isEmpty()
                || staffs.isEmpty()
                || gray == null
                || width <= 0
                || height <= 0
                || gray.length != (long) width * height) return notes;
        notes = annotatedSingleLines(words, staffs, measures, notes, gray, width, height);
        notes = separatedFingeringCaptions(words, staffs, measures, notes, gray, width, height);
        notes = isolatedParentheticalSixteenths(words, staffs, measures, notes, gray, width, height);
        List<Candidate> candidates = null;
        var result = new ArrayList<ScoreNoteEvent>(notes.size());
        for (var note : notes) {
            if (note.measureIndex() < 0
                    || note.measureIndex() >= measures.size()
                    || note.staffStep() < 8 && note.staffStep() > -3
                    || (note.articulations() & NoteOrnament.GRACE) != 0) {
                result.add(note);
                continue;
            }
            if (candidates == null) {
                candidates = candidates(words, width, height);
                if (candidates.isEmpty()) return notes;
            }
            var measure = measures.get(note.measureIndex());
            float
                    x =
                            (measure.left()
                                            + note.positionInMeasure()
                                                    * (measure.right() - measure.left()))
                                    * width,
                    y = note.pageY() * height;
            boolean annotation = false;
            boolean below = note.staffStep() <= -3;
            for (var word : candidates) {
                boolean sequence = below && word.sequenceLength() != 0;
                if (!word.fingering() && !sequence) continue;
                float l = word.left(), r = word.right(), t = word.top(), b = word.bottom();
                if (x < l || x > r || y < t || y > b) continue;
                PlayingTechniqueDetector.Staff owner = null;
                float distance = Float.MAX_VALUE;
                for (var staff : staffs) {
                    if (staff.index() != note.staffIndex()
                            || staff.count() != note.staffCount()
                            || staff.gap() < 5) continue;
                    float dy =
                            below
                                    ? (t - staff.bottom()) / staff.gap()
                                    : (staff.top() - b) / staff.gap();
                    if (dy < (below ? -2.5f : -.4f)
                            || dy > (below ? 6 : 5)
                            || Math.abs(dy) >= distance) continue;
                    owner = staff;
                    distance = Math.abs(dy);
                }
                if (owner == null) continue;
                float gap = owner.gap();
                if (b - t < gap * 1.15f
                        || b - t > gap * (sequence ? 6 : 5.5f)
                        || r - l < gap * .45f
                        || r - l > gap * (sequence ? word.sequenceLength() * 3.5f : 7)) continue;
                if (sequence && (r - l) / word.sequenceLength() > gap * 3.5f) continue;
                if ((below || word.digit())
                        && printedStemBeyondWord(gray, width, height, x, y, t, b, gap)) continue;
                if ((note.staffStep() >= 10 || below)
                        && printedLedgerAcrossWord(
                                gray,
                                width,
                                height,
                                sequence ? x - gap * .75f : l,
                                sequence ? x + gap * .75f : r,
                                y,
                                gap)) continue;
                annotation = true;
                break;
            }
            if (!annotation) result.add(note);
        }
        return result.size() == notes.size() ? notes : List.copyOf(result);
    }

    /** Staff-line fragments can be notehead-shaped while belonging to an alternate fingering. */
    private static List<ScoreNoteEvent> annotatedSingleLines(
            List<PlayingTechniqueDetector.Word> words, List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures, List<ScoreNoteEvent> notes, byte[] gray, int width, int height) {
        var openings = words.stream().filter(w -> OPEN_FINGER.matcher(w.text().strip()).matches()
                || w.text().strip().equalsIgnoreCase("(a")).toList();
        var closings = words.stream().filter(w -> CLOSE_FINGER.matcher(w.text().strip()).matches()).toList();
        var captions = words.stream().filter(w -> FINGERS_AND_SEGUE.matcher(w.text().strip()).matches()).toList();
        if (captions.isEmpty() && (openings.isEmpty() || closings.isEmpty())) return notes;
        var result = new ArrayList<>(notes);
        for (int m = 0; m < measures.size(); m++) {
            int index=m;
            var local=notes.stream().filter(n -> n.measureIndex()==index).toList();
            long whole=local.stream().filter(FingeringAnnotationFilter::possiblyPrintedText).count();
            boolean isolatedOpening = local.size() == 17 && whole == 1;
            boolean parenthesized = isolatedOpening || local.size() == 19 && whole == 3;
            if (!parenthesized && (local.size()!=15 || whole!=3)) continue;
            var region=measures.get(m);
            var music=local.stream().filter(n->!possiblyPrintedText(n)).toList();
            if (music.stream().anyMatch(n -> n.kind()!=ScoreNoteEvent.Kind.PITCHED
                    || n.staffCount()!=1 || n.staffIndex()!=0 || n.stemDirection()!=-1
                    || n.beamCount()!=2 || n.unbeamedDurationBeats()!=0 || n.tupletDivisor()!=1
                    || n.augmentationDots()!=0 || n.leadingRestBeats()!=0 || n.followingRestBeats()!=0))
                continue;
            var staff=staffs.stream().filter(st -> st.count()==1 && st.index()==0
                    && st.top()>=region.top()*height && st.bottom()<=region.bottom()*height)
                    .min(Comparator.comparingDouble(st -> Math.abs((st.top()+st.bottom())*.5
                            -(region.top()+region.bottom())*height*.5))).orElse(null);
            if (staff==null) continue;
            float gap=staff.gap();
            var approved=parenthesized
                    ? isolatedOpening
                            ? isolatedOpeningBox(openings, closings, region, staff, music, width, height)
                            : parenthesizedBoxes(openings,closings,region,staff,width,height)
                    : captions.stream().filter(w -> insideStaffText(w,region,staff,width,height)
                            && (w.right()-w.left())*width>=gap*13
                            && (w.right()-w.left())*width<=gap*25).toList();
            if (approved.isEmpty()) continue;
            var falseHeads=local.stream().filter(FingeringAnnotationFilter::possiblyPrintedText).toList();
            if (falseHeads.stream().allMatch(n -> insideInk(n,region,staff,approved,gray,width,height))
                    && closedWrittenSixteenths(music)) result.removeAll(falseHeads);
        }
        return result.size()==notes.size()?notes:List.copyOf(result);
    }

    /** OCR can split the printed fingering line and its following word into separate boxes. */
    private static List<ScoreNoteEvent> separatedFingeringCaptions(
            List<PlayingTechniqueDetector.Word> words, List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures, List<ScoreNoteEvent> notes, byte[] gray, int width, int height) {
        var fingerings = words.stream().filter(w -> w.text() != null
                && SPLIT_FINGERING_RUN.matcher(w.text().strip()).matches()).toList();
        var captions = words.stream().filter(w -> w.text() != null
                && SINGLE_SEGUE.matcher(w.text().strip()).matches()).toList();
        if (fingerings.isEmpty() || captions.isEmpty()) return notes;
        var byMeasure = new HashMap<Integer, List<ScoreNoteEvent>>();
        for (var note : notes)
            byMeasure.computeIfAbsent(note.measureIndex(), ignored -> new ArrayList<>()).add(note);
        var discard = Collections.newSetFromMap(new IdentityHashMap<ScoreNoteEvent, Boolean>());
        for (var caption : captions) {
            for (var digits : fingerings) {
                if (!(digits.right() < caption.left())) continue;
                int measure = uniqueCaptionMeasure(measures, digits, caption);
                if (measure < 0) continue;
                var region = measures.get(measure);
                var staff = captionStaff(staffs, digits, caption, region, width, height);
                if (staff == null) continue;
                var local = byMeasure.getOrDefault(measure, List.of());
                var music = local.stream().filter(n -> n.stemDirection() == -1)
                        .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
                if (music.size() != 12 || local.size() < 15 || local.size() > 18) continue;
                var nonMusic = local.stream().filter(n -> n.stemDirection() != -1).toList();
                if (nonMusic.stream().anyMatch(n -> !captionHead(n)
                        || !insideInk(n, region, staff, List.of(digits, caption), gray, width, height)))
                    continue;
                long wordHeads = nonMusic.stream().filter(n -> (region.left() + n.positionInMeasure()
                        * (region.right() - region.left())) > caption.left()).count();
                if (wordHeads < 2 || wordHeads == nonMusic.size()) continue;
                if (closedCaptionSixteenths(music, region, staff, gray, width, height))
                    discard.addAll(nonMusic);
            }
        }
        if (discard.isEmpty()) return notes;
        return notes.stream().filter(n -> !discard.contains(n)).toList();
    }

    private static int uniqueCaptionMeasure(List<MeasureRegion> measures,
            PlayingTechniqueDetector.Word digits, PlayingTechniqueDetector.Word caption) {
        int selected = -1;
        for (int i = 0; i < measures.size(); i++) {
            var m = measures.get(i);
            if (m.cadenceQuarterBeats() != 0 || digits.left() < m.left() || caption.right() > m.right()
                    || digits.top() < m.top() || caption.bottom() > m.bottom()) continue;
            if (selected >= 0) return -1;
            selected = i;
        }
        return selected;
    }

    private static PlayingTechniqueDetector.Staff captionStaff(
            List<PlayingTechniqueDetector.Staff> staffs, PlayingTechniqueDetector.Word digits,
            PlayingTechniqueDetector.Word word, MeasureRegion region, int width, int height) {
        PlayingTechniqueDetector.Staff chosen = null;
        for (var staff : staffs) {
            float gap = staff.gap();
            if (staff.index() != 0 || staff.count() != 1 || staff.top() < region.top() * height
                    || staff.bottom() > region.bottom() * height || gap < 10 || gap > 25
                    || !insideStaffText(digits, region, staff, width, height)
                    || !insideStaffText(word, region, staff, width, height)
                    || (word.left() - digits.right()) * width < gap * .7f
                    || (word.left() - digits.right()) * width > gap * 2.7f
                    || (word.right() - word.left()) * width < gap * 5
                    || (word.right() - word.left()) * width > gap * 10
                    || Math.abs(word.top() - digits.top()) * height > gap * .75f
                    || Math.abs(word.bottom() - digits.bottom()) * height > gap * .85f) continue;
            if (chosen != null) return null;
            chosen = staff;
        }
        return chosen;
    }

    private static boolean captionHead(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffCount() == 1 && n.staffIndex() == 0
                && n.stemDirection() == 0 && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.staffStep() >= -2 && n.staffStep() <= 1 && !n.crossStaffBeam()
                && n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && n.augmentationDots() == 0 && n.tupletDivisor() == 1
                && (n.articulations() & (NoteOrnament.GRACE | NoteOrnament.TYPE_MASK)) == 0
                && !n.tiedFromPrevious() && n.boundaryTies() == 0
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0
                && (n.beamCount() >= 1 && n.beamCount() <= 2 && n.unbeamedDurationBeats() == 0
                        || n.beamCount() == 0 && n.unbeamedDurationBeats() == 4);
    }

    private static boolean closedCaptionSixteenths(List<ScoreNoteEvent> music, MeasureRegion region,
            PlayingTechniqueDetector.Staff staff, byte[] gray, int width, int height) {
        float gap = staff.gap();
        float left = region.left() * width, right = region.right() * width;
        if (right - left < gap * 26 || right - left > gap * 39) return false;
        var shafts = new PrintedBeamShafts.Shaft[12];
        var budget = new PrintedBeamShafts.Budget(180000);
        float previous = left;
        for (int i = 0; i < shafts.length; i++) {
            var note = music.get(i);
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.staffCount() != 1 || note.staffIndex() != 0
                    || note.beamCount() != 2 || note.unbeamedDurationBeats() != 0
                    || note.tupletDivisor() != 1 || note.augmentationDots() != 0 || note.crossStaffBeam()
                    || note.leadingRestBeats() != 0 || note.followingRestBeats() != 0
                    || note.boundaryTies() != 0
                    || (note.articulations() & NoteOrnament.GRACE) != 0) return false;
            float x = left + note.positionInMeasure() * (right - left);
            float y = note.pageY() * height;
            if (x - previous < gap * 1.65f || x - previous > gap * 3.55f) return false;
            previous = x;
            var source = PrintedBeamShafts.at(gray, width, height, x, y, gap, budget);
            if (source.size() != 1 || source.get(0).direction() != 1) return false;
            shafts[i] = source.get(0);
            if (i > 0 && pairedRails(gray, width, height, shafts[i-1], shafts[i], gap, true) != (i % 4 != 0))
                return false;
        }
        if (right - previous < gap * .9f || right - previous > gap * 2.6f) return false;
        return PrintedCadenceDurations.boundaryColumn(gray, width, staff, right, true, false) >= 0
                && closedWrittenSixteenths(music);
    }

    /** Paired below-staff fingerings can seed both a false pitch and a false triplet. */
    private static List<ScoreNoteEvent> isolatedParentheticalSixteenths(
            List<PlayingTechniqueDetector.Word> words,List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> regions,List<ScoreNoteEvent> notes,byte[] gray,int width,int height) {
        var opening=words.stream().filter(w->w.text()!=null && w.text().strip().equals("(3")).toList();
        var closing=words.stream().filter(w->w.text()!=null && w.text().strip().equals("3)")).toList();
        if(opening.isEmpty() || closing.isEmpty()) return notes;
        List<ScoreNoteEvent> output=null;
        for (int index=0;index<notes.size();index++) {
            var falseHead=notes.get(index);
            if (!possiblyPrintedText(falseHead) || falseHead.beamCount()!=0
                    || falseHead.unbeamedDurationBeats()!=4 || falseHead.staffStep()!=-1
                    || falseHead.leadingRestBeats()!=0 || falseHead.followingRestBeats()!=0)
                continue;
            int measure=falseHead.measureIndex();
            if (measure<0 || measure>=regions.size()) continue;
            var bar=regions.get(measure);
            float left=bar.left()*width, right=bar.right()*width, span=right-left;
            float falseX=left+span*falseHead.positionInMeasure(),falseY=falseHead.pageY()*height;
            var real=notes.stream().filter(n->n.measureIndex()==measure && n!=falseHead)
                    .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
            if(real.size()!=16) continue;
            for (var staff:staffs) {
                if (staff.count()!=1 || staff.index()!=0 || staff.top()<bar.top()*height
                        || staff.bottom()>bar.bottom()*height || staff.gap()<10 || staff.gap()>24)
                    continue;
                float gap=staff.gap();
                if (falseY<staff.bottom()+gap*.32f || falseY>staff.bottom()+gap*.9f) continue;
                var pair=parenthesizedBoxes(opening,closing,bar,staff,width,height);
                if (pair.isEmpty() || !insideInk(falseHead,bar,staff,pair.subList(0,1),gray,width,height))
                    continue;
                float first=left+span*real.get(0).positionInMeasure(),last=left+span*real.get(15).positionInMeasure();
                if (!WrappedMeasureBoundaries.confirmsInitialStaffBar(
                        gray,width,height,regions,measure,first,last)) continue;
                var replacement=printedSixteenthLine(real,pair.get(1),left,span,staff,gray,width,height);
                if(replacement.isEmpty()) continue;
                if(output==null) output=new ArrayList<>(notes);
                for (int j=9;j<=11;j++) {
                    int owner=notes.indexOf(real.get(j));
                    output.set(owner,replacement.get(j));
                }
                output.set(index,null);
                break;
            }
        }
        return output==null?notes:output.stream().filter(Objects::nonNull).toList();
    }

    private static List<ScoreNoteEvent> printedSixteenthLine(
            List<ScoreNoteEvent> notes,PlayingTechniqueDetector.Word closing,float left,float span,
            PlayingTechniqueDetector.Staff staff,byte[] gray,int width,int height) {
        float gap=staff.gap();
        var heads=new PrintedBeamShafts.Shaft[16];
        var x=new float[16];
        var y=new float[16];
        var budget=new PrintedBeamShafts.Budget(180000);
        for(int i=0;i<notes.size();i++) {
            var n=notes.get(i);
            int ratio=i>=9 && i<=11?3:1;
            if(n.kind()!=ScoreNoteEvent.Kind.PITCHED || n.staffCount()!=1 || n.staffIndex()!=0
                    || n.visualSize()!=ScoreNoteEvent.VisualSize.FULL || n.stemDirection()!=-1
                    || n.beamCount()!=2 || n.unbeamedDurationBeats()!=0 || n.tupletDivisor()!=ratio
                    || n.tupletNormalNotes()!=(ratio==3?2:1) || n.augmentationDots()!=0
                    || n.leadingRestBeats()!=0 || n.followingRestBeats()!=0 || n.tiedFromPrevious()
                    || n.boundaryTies()!=0 || n.crossStaffBeam()) return List.of();
            x[i]=left+span*n.positionInMeasure();
            y[i]=n.pageY()*height;
            if (i>0 && (x[i]-x[i-1]<gap*1.5f || x[i]-x[i-1]>gap*4)) return List.of();
            var axes=PrintedBeamShafts.at(gray,width,height,x[i],y[i],gap,budget);
            if (axes.size()==1 && axes.get(0).direction()==1) heads[i]=axes.get(0);
            else if (!axes.isEmpty() || i%4!=1 || i<12) return List.of();
        }
        float close=(closing.left()+closing.right())*width*.5f;
        if (Math.abs(close-x[11])>gap*.65f) return List.of();
        for(int i=0;i+1<heads.length;i++) {
            if (heads[i]==null || heads[i+1]==null) {
                int middle=heads[i]==null?i:i+1;
                if (!PrintedTupletBeamOwner.connectedHeads(gray,width,height,x[i],y[i],x[i+1],y[i+1],gap,-1)
                        || !pairedRails(gray,width,height,heads[middle-1],heads[middle+1],gap))
                    return List.of();
            } else if (i%4==3 ? pairedRails(gray,width,height,heads[i],heads[i+1],gap)
                              : !pairedRails(gray,width,height,heads[i],heads[i+1],gap)) return List.of();
        }
        var result=notes.stream().map(n -> n.tupletDivisor()==3?n.withTupletRatio(1,1):n).toList();
        return closedWrittenSixteenths(result)?result:List.of();
    }

    private static boolean pairedRails(byte[] gray,int width,int height,
            PrintedBeamShafts.Shaft a,PrintedBeamShafts.Shaft b,float gap) {
        return pairedRails(gray, width, height, a, b, gap, false);
    }

    private static boolean pairedRails(byte[] gray,int width,int height,
            PrintedBeamShafts.Shaft a,PrintedBeamShafts.Shaft b,float gap,boolean narrowSeam) {
        int x0=(a.left()+a.right())/2,x1=(b.left()+b.right())/2;
        int margin=Math.round(gap*.37f),rad=Math.max(2,Math.round(gap*.14f));
        if(x0+margin>=x1-margin) return false;
        for(int offset=-Math.round(gap*.22f);offset<=Math.round(gap*.45f);offset++)
            for(int separation=Math.round(gap*.55f);separation<=Math.round(gap*.92f);separation++) {
                int first=0,second=0,seam=0,lightSeam=0;
                for(int x=x0+margin;x<=x1-margin;x++) {
                    int axis=Math.round(a.endY()+(b.endY()-a.endY())*(x-x0)/(float)(x1-x0))-offset;
                    if (axis+rad>=height || axis-separation-rad<0) continue;
                    if(verticalInk(gray,width,x,axis,rad)>=2*rad) first++;
                    if(verticalInk(gray,width,x,axis-separation,rad)>=2*rad) second++;
                    int trough = axis - separation / 2;
                    if(verticalInk(gray,width,x,trough,1)<=1) seam++;
                    if (narrowSeam && (gray[trough * width + x] & 255) >= 185) lightSeam++;
                }
                int count=x1-x0-2*margin+1;
                boolean visibleGap = seam >= count * .28f
                        || narrowSeam && seam >= count * .18f && lightSeam >= count * .50f;
                if(first>=count*.96f && second>=count*.96f && visibleGap) return true;
            }
        return false;
    }

    private static int verticalInk(byte[] gray,int width,int x,int y,int radius) {
        int dark=0;
        for(int dy=-radius;dy<=radius;dy++) if((gray[(y+dy)*width+x]&255)<165) dark++;
        return dark;
    }

    private static boolean possiblyPrintedText(ScoreNoteEvent n) {
        return n.kind()==ScoreNoteEvent.Kind.PITCHED && n.staffCount()==1 && n.staffIndex()==0
                && n.stemDirection()==0 && n.visualSize()==ScoreNoteEvent.VisualSize.FULL
                && n.staffStep()>=-2 && n.staffStep()<=2
                && n.writtenAccidental()==ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && n.augmentationDots()==0 && n.tupletDivisor()==1 && n.articulations()==0
                && !n.tiedFromPrevious() && n.boundaryTies()==0
                && (n.beamCount()>0 || n.unbeamedDurationBeats()==4);
    }

    private static List<PlayingTechniqueDetector.Word> parenthesizedBoxes(
            List<PlayingTechniqueDetector.Word> openings, List<PlayingTechniqueDetector.Word> closings,
            MeasureRegion measure, PlayingTechniqueDetector.Staff staff, int width, int height) {
        float gap=staff.gap();
        for (var a:openings) {
            if (!OPEN_FINGER.matcher(a.text().strip()).matches()
                    || !insideStaffText(a,measure,staff,width,height)) continue;
            for (var b:closings) {
                float distance=(b.left()-a.right())*width;
                if (distance>=gap*7 && distance<=gap*13
                        && insideStaffText(b,measure,staff,width,height)
                        && Math.abs(a.top()-b.top())*height<gap*.3f
                        && (a.right()-a.left())*width<gap*3
                        && (b.right()-b.left())*width<gap*3) return List.of(a,b);
            }
        }
        return List.of();
    }

    // A lowest staff rule can cross the opening fingering while the final digit lies below it.
    private static List<PlayingTechniqueDetector.Word> isolatedOpeningBox(
            List<PlayingTechniqueDetector.Word> openings, List<PlayingTechniqueDetector.Word> closings,
            MeasureRegion measure, PlayingTechniqueDetector.Staff staff, List<ScoreNoteEvent> music,
            int width, int height) {
        float gap = staff.gap();
        float scale = (measure.right() - measure.left()) * width / gap;
        for (int i = 1; i < music.size(); i++) {
            float dx = (music.get(i).positionInMeasure() - music.get(i - 1).positionInMeasure()) * scale;
            if (dx < 1.3f || dx > 2.4f) return List.of();
        }
        for (var opening : openings) {
            if ((!opening.text().strip().equalsIgnoreCase("(a")
                            && !opening.text().strip().equals("(3"))
                    || !insideStaffText(opening, measure, staff, width, height)
                    || (opening.right() - opening.left()) * width > gap * 3) continue;
            for (var closing : closings) {
                if (!closing.text().strip().equals("3)")) continue;
                float dx = (closing.left() - opening.right()) * width / gap;
                float dy = (closing.top() - opening.top()) * height / gap;
                float h = (closing.bottom() - closing.top()) * height / gap;
                if (dx >= 8 && dx <= 11 && dy >= .8f && dy <= 1.4f
                        && closing.right() <= measure.right()
                        && closing.top() * height >= staff.bottom() - .35f * gap
                        && closing.bottom() * height <= staff.bottom() + 2.6f * gap
                        && (closing.right() - closing.left()) * width <= gap * 3
                        && h >= 1.5f && h <= 2.5f) return List.of(opening);
            }
        }
        return List.of();
    }

    private static boolean insideStaffText(
            PlayingTechniqueDetector.Word word, MeasureRegion region, PlayingTechniqueDetector.Staff staff,
            int width, int height) {
        float gap=staff.gap(), left=word.left()*width, right=word.right()*width;
        float top=word.top()*height, bottom=word.bottom()*height;
        return left>=region.left()*width && right<=region.right()*width
                && top>=staff.bottom()-gap*2.25f && top<=staff.bottom()+gap*.35f
                && bottom>=staff.bottom() && bottom<=staff.bottom()+gap*1.65f
                && bottom-top>=gap*1.15f && bottom-top<=gap*3;
    }

    private static boolean insideInk(ScoreNoteEvent n, MeasureRegion region,
            PlayingTechniqueDetector.Staff staff, List<PlayingTechniqueDetector.Word> words,
            byte[] gray, int width, int height) {
        float x=(region.left()+n.positionInMeasure()*(region.right()-region.left()))*width;
        float y=n.pageY()*height, gap=staff.gap();
        for (var word:words) {
            float t=word.top()*height,b=word.bottom()*height;
            if (x<word.left()*width || x>word.right()*width || y<t || y>b
                    || printedStemBeyondWord(gray,width,height,x,y,t,b,gap)) continue;
            int ink=0;
            for(int yy=Math.max(0,Math.round(y-gap*.33f));yy<=Math.min(height-1,Math.round(y+gap*.33f));yy++) {
                float staffLine=(staff.bottom()-yy)/gap;
                if(staffLine>=-.22f && staffLine<=4.22f
                        && Math.abs(staffLine-Math.round(staffLine))<.22f) continue;
                for(int xx=Math.max(0,Math.round(x-gap*.34f));xx<=Math.min(width-1,Math.round(x+gap*.34f));xx++)
                    if((gray[yy*width+xx]&255)<160) ink++;
            }
            if (ink>=gap*.7f) return true;
        }
        return false;
    }

    private static boolean closedWrittenSixteenths(List<ScoreNoteEvent> music) {
        int count=music.size();
        for (int i=0;i<count;i++) {
            var p=ScoreNoteTiming.writtenPlacement(music.get(i),music,count*.25f).orElse(null);
            if (p==null || p.onsetBeats()!=i*.25f || p.durationBeats()!=.25f) return false;
        }
        return true;
    }

    private static List<Candidate> candidates(
            List<PlayingTechniqueDetector.Word> words, int width, int height) {
        var result = new ArrayList<Candidate>();
        for (var word : words) {
            String text = word.text() == null ? "" : word.text().trim();
            int sequenceLength = DIGIT_SEQUENCE.matcher(text).matches() ? text.length() : 0;
            boolean fingering = isFingering(text);
            if (!fingering && sequenceLength == 0) continue;
            float l = word.left() * width, r = word.right() * width;
            float t = word.top() * height, b = word.bottom() * height;
            if (!Float.isFinite(l + r + t + b)) continue;
            result.add(new Candidate(l, r, t, b, fingering,
                    SINGLE_DIGIT.matcher(text).matches(), sequenceLength));
        }
        return result;
    }

    static boolean isFingering(String text) {
        if (text == null) return false;
        String compact = WHITESPACE.matcher(text.trim()).replaceAll("").toUpperCase(Locale.ROOT);
        return HAND_OR_DIGIT.matcher(compact).matches() || HAND_SEQUENCE.matcher(compact).matches();
    }

    /** OCR can call a notehead and its short ledger a digit. An attached shaft
     * continuing well outside that text box is independent musical evidence. */
    private static boolean printedStemBeyondWord(
            byte[] gray,
            int width,
            int height,
            float x,
            float y,
            float top,
            float bottom,
            float gap) {
        int limit = 170;
        int local =
                BeamInkThreshold.at(
                        gray,
                        width,
                        height,
                        Math.round(x),
                        Math.round(top - gap),
                        Math.round(bottom + gap),
                        gap);
        if (local < 153) limit = Math.min(limit, local + 15);
        for (int direction : new int[] {-1, 1})
            for (int offset = Math.round(gap * .35f); offset <= Math.round(gap * .9f); offset++) {
                int xx = Math.round(x) - direction * offset;
                if (xx < 0 || xx >= width) continue;
                int end =
                        Math.round(
                                direction < 0
                                        ? Math.min(y - gap * 2, top - gap * .7f)
                                        : Math.max(y + gap * 2, bottom + gap * .7f));
                if (end < 0 || end >= height || Math.abs(end - y) > gap * 5) continue;
                int ink = 0, total = 0, run = 0, longestBlank = 0;
                for (int yy = Math.round(y); direction * (end - yy) >= 0; yy += direction) {
                    total++;
                    if ((gray[yy * width + xx] & 255) < limit) {
                        ink++;
                        run = 0;
                    } else {
                        run++;
                        longestBlank = Math.max(longestBlank, run);
                    }
                }
                if (total >= gap * 1.9f
                        && ink >= total * .9f
                        && longestBlank <= Math.max(1, Math.round(gap * .15f))) return true;
            }
        return false;
    }

    private static boolean printedLedgerAcrossWord(
            byte[] gray, int width, int height, float left, float right, float y, float gap) {
        int limit = 190;
        int local =
                BeamInkThreshold.at(
                        gray,
                        width,
                        height,
                        Math.round((left + right) * .5f),
                        Math.round(y - gap),
                        Math.round(y + gap),
                        gap);
        if (local < 153) limit = Math.min(limit, local + 15);
        for (int yy = Math.max(1, Math.round(y - gap * .3f));
                yy <= Math.min(height - 2, Math.round(y + gap * .3f));
                yy++) {
            boolean both = true;
            for (int side : new int[] {-1, 1}) {
                int hits = 0, total = 0, center = Math.round(side < 0 ? left : right);
                for (int d = 1; d <= Math.round(gap * .65f); d++) {
                    int x = center + side * d;
                    if (x < 0 || x >= width) {
                        both = false;
                        break;
                    }
                    total++;
                    if ((gray[yy * width + x] & 255) < limit) hits++;
                }
                if (total < 3 || hits < total * .8f) both = false;
            }
            if (both) return true;
        }
        return false;
    }
}
