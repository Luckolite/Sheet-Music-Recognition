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
        var openings = words.stream().filter(w -> OPEN_FINGER.matcher(w.text().strip()).matches()).toList();
        var closings = words.stream().filter(w -> CLOSE_FINGER.matcher(w.text().strip()).matches()).toList();
        var captions = words.stream().filter(w -> FINGERS_AND_SEGUE.matcher(w.text().strip()).matches()).toList();
        if (captions.isEmpty() && (openings.isEmpty() || closings.isEmpty())) return notes;
        var result = new ArrayList<>(notes);
        for (int m = 0; m < measures.size(); m++) {
            int index=m;
            var local=notes.stream().filter(n -> n.measureIndex()==index).toList();
            long whole=local.stream().filter(FingeringAnnotationFilter::possiblyPrintedText).count();
            boolean parenthesized=local.size()==19 && whole==3;
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
                    ? parenthesizedBoxes(openings,closings,region,staff,width,height)
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
            if (!insideStaffText(a,measure,staff,width,height)) continue;
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
