// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Carries engraved duplet ownership into compound-meter timing. */
final class BracketedDuplets {
    private BracketedDuplets() {}

    private record Voice(int measure, int staff, int staffCount) {}
    private record Arm(int first, int last) {}

    static List<ScoreNoteEvent> capture(
            List<ScoreNoteEvent> notes, List<MeasureRegion> measures, byte[] gray, int width, int height) {
        if (gray == null || gray.length != (long) width * height || notes.isEmpty()
                || notes.stream().noneMatch(n -> n.tupletDivisor() == 1 && n.beamCount() == 0
                        && n.unbeamedDurationBeats() == 1 && n.augmentationDots() == 1)) return notes;
        var lanes = lanes(notes);
        List<RawStaffLineDetector.StaffLines> tracks = null;
        ArrayList<ScoreNoteEvent> changed = null;
        for (var lane : lanes.values()) {
            for (int i = 1; i < lane.size(); i++) {
                int first = lane.get(i - 1), second = lane.get(i);
                var a = notes.get(first);
                var b = notes.get(second);
                if (!mixedPair(a,b) || a.tupletDivisor() != 1 || b.tupletDivisor() != 1
                        || a.measureIndex() >= measures.size()) continue;
                if (changed != null && changed.get(first).tupletDivisor() != 1) continue;
                if (tracks == null) tracks = RawStaffLineDetector.detect(gray, width, height);
                var bar = measures.get(a.measureIndex());
                var staves = tracks.stream().filter(s -> s.center() >= bar.top() * height
                        && s.center() <= bar.bottom() * height).toList();
                if (staves.size() != a.staffCount()) continue;
                var staff = staves.get(a.staffIndex());
                float x1 = (bar.left() + a.positionInMeasure() * (bar.right() - bar.left())) * width;
                float x2 = (bar.left() + b.positionInMeasure() * (bar.right() - bar.left())) * width;
                if (!printedBracket(gray,width,height,x1,x2,staff,a.stemDirection())) continue;
                if (changed == null) changed = new ArrayList<>(notes);
                changed.set(first,a.withTupletRatio(2,2));
                changed.set(second,b.withTupletRatio(2,2));
            }
        }
        return changed == null ? notes : List.copyOf(changed);
    }

    /** Two in the time of three applies only when both glyphs close the same compound-meter lane. */
    static List<ScoreNoteEvent> resolve(
            List<ScoreNoteEvent> notes, List<ScoreMeterChange> meters, boolean openingPickup) {
        if (notes.isEmpty() || meters == null || meters.isEmpty()
                || notes.stream().noneMatch(n -> n.tupletDivisor() == 2 && n.tupletNormalNotes() == 2))
            return notes;
        ArrayList<ScoreNoteEvent> changed = null;
        for (var entry : lanes(notes).entrySet()) {
            var lane = entry.getValue();
            int measure = entry.getKey().measure();
            if (measure == 0 && openingPickup) continue;
            var meter = activeMeter(meters,measure);
            if (meter == null || meter.denominator() != 8 || meter.numerator() < 6
                    || meter.numerator() % 3 != 0) continue;
            var pairs = new ArrayList<Integer>();
            float prior = -1;
            double written = 0, gain = 0;
            boolean complete = true;
            for (int at = 0; at < lane.size(); at++) {
                var n = notes.get(lane.get(at));
                double duration = ScoreNoteTiming.writtenDurationBeats(n);
                if (n.positionInMeasure() - prior < .012f || n.kind() != ScoreNoteEvent.Kind.PITCHED
                        || n.crossStaffBeam() || (n.articulations() & NoteOrnament.GRACE) != 0
                        || duration <= 0 || !Double.isFinite(duration)) {
                    complete = false;
                    break;
                }
                written += duration + n.leadingRestBeats() + n.followingRestBeats();
                prior = n.positionInMeasure();
                if (!unresolved(n)) continue;
                if ((pairs.size() & 1) == 0) {
                    if (at + 1 >= lane.size()) {
                        complete = false;
                        break;
                    }
                    var b = notes.get(lane.get(at + 1));
                    if (!unresolved(b) || !mixedPair(n,b) && !equalPair(n,b)) {
                        complete = false;
                        break;
                    }
                    gain += (duration + ScoreNoteTiming.writtenDurationBeats(b)) * .5;
                }
                pairs.add(lane.get(at));
            }
            if (!complete || pairs.isEmpty() || (pairs.size() & 1) != 0
                    || Math.abs(written + gain - meter.quarterBeats()) > .001) continue;
            if (changed == null) changed = new ArrayList<>(notes);
            for (int at : pairs) changed.set(at, notes.get(at).withTupletRatio(2,3));
        }
        return changed == null ? notes : List.copyOf(changed);
    }

    private static LinkedHashMap<Voice,List<Integer>> lanes(List<ScoreNoteEvent> notes) {
        var out = new LinkedHashMap<Voice,List<Integer>>();
        for (int i = 0; i < notes.size(); i++) {
            var n = notes.get(i);
            out.computeIfAbsent(new Voice(n.measureIndex(),n.staffIndex(),n.staffCount()),
                    ignored -> new ArrayList<>()).add(i);
        }
        for (var lane : out.values())
            lane.sort(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure()));
        return out;
    }

    private static ScoreMeterChange activeMeter(List<ScoreMeterChange> meters, int measure) {
        ScoreMeterChange latest = null;
        for (var meter : meters)
            if (meter.measureIndex() <= measure
                    && (latest == null || meter.measureIndex() > latest.measureIndex())) latest = meter;
        if (latest != null)
            for (var meter : meters)
                if (meter.measureIndex() == latest.measureIndex() && !meter.equals(latest)) return null;
        return latest;
    }

    private static boolean unresolved(ScoreNoteEvent note) {
        return note.tupletDivisor() == 2 && note.tupletNormalNotes() == 2;
    }

    private static boolean equalPair(ScoreNoteEvent a, ScoreNoteEvent b) {
        return a.beamCount() == 1 && b.beamCount() == 1
                && a.augmentationDots() == 0 && b.augmentationDots() == 0
                && a.stemDirection() != 0 && a.stemDirection() == b.stemDirection()
                && a.followingRestBeats() == 0 && b.leadingRestBeats() == 0;
    }

    private static boolean mixedPair(ScoreNoteEvent a, ScoreNoteEvent b) {
        return a.kind() == ScoreNoteEvent.Kind.PITCHED && b.kind() == ScoreNoteEvent.Kind.PITCHED
                && a.beamCount() == 0 && a.unbeamedDurationBeats() == 1
                && a.augmentationDots() == 1 && b.beamCount() == 1 && b.augmentationDots() == 0
                && a.stemDirection() != 0 && a.stemDirection() == b.stemDirection()
                && a.followingRestBeats() == 0 && b.leadingRestBeats() == 0
                && !a.crossStaffBeam() && !b.crossStaffBeam()
                && ((a.articulations() | b.articulations()) & NoteOrnament.GRACE) == 0;
    }

    private static boolean printedBracket(
            byte[] gray, int width, int height, float x1, float x2,
            RawStaffLineDetector.StaffLines staff, int stem) {
        float gap = staff.gap();
        if (gap < 7 || x2 - x1 < gap * 2.6f || x2 - x1 > gap * 13) return false;
        float center = (x1 + x2) * .5f;
        int start = Math.round(center - gap * 1.10f), end = Math.round(center + gap * 1.10f);
        int edge = stem < 0 ? staff.bottom() : staff.top();
        int top = Math.round(edge + (stem < 0 ? .32f : -2.7f) * gap);
        int bottom = Math.round(edge + (stem < 0 ? 2.7f : -.32f) * gap);
        if (start < 0 || end >= width || top < 0 || bottom >= height) return false;
        var components = RawNumeralComponents.captureLongWindow(gray,width,height,
                start,top,end,bottom,154);
        boolean found = false;
        for (var glyph : components.components()) {
            float h = glyph.bottom - glyph.top + 1, w = glyph.right - glyph.left + 1;
            if (h < gap * .65f || h > gap * 1.65f || w < gap * .5f || w > gap * 1.5f
                    || glyph.left <= start || glyph.right >= end
                    || !TripletRhythmDetector.fingerTwo(gray,width,glyph)) continue;
            if (!ownsArms(gray,width,height,x1,x2,gap,stem,glyph)) continue;
            if (found) return false;
            found = true;
        }
        return found;
    }

    private static boolean ownsArms(
            byte[] gray, int width, int height, float x1, float x2, float gap, int stem,
            RawNumeralComponents.Component glyph) {
        int left = Math.max(0, (int)Math.floor(x1 - gap * 1.85f));
        int right = Math.min(width - 1, (int)Math.ceil(x2 + gap * 1.85f));
        int top = Math.round(glyph.top + (glyph.bottom - glyph.top) * .24f);
        int bottom = Math.round(glyph.bottom - (glyph.bottom - glyph.top) * .22f);
        for (int y = top; y <= bottom; y++) {
            var a = arm(gray,width,y,left,glyph.left - 1,gap);
            var b = arm(gray,width,y,glyph.right + 1,right,gap);
            if (a == null || b == null || a.first() > x1 + gap * .35f || b.last() < x2 - gap * .35f
                    || glyph.left - a.last() < gap * .12f || glyph.left - a.last() > gap * .85f
                    || b.first() - glyph.right < gap * .12f || b.first() - glyph.right > gap * .85f)
                continue;
            int towardStaff = stem < 0 ? -1 : 1;
            if (hook(gray,width,height,a.first(),y,towardStaff,gap)
                    && hook(gray,width,height,b.last(),y,towardStaff,gap)) return true;
        }
        return false;
    }

    private static Arm arm(byte[] gray,int width,int y,int left,int right,float gap) {
        int best = 0;
        Arm arm = null;
        for (int x = left; x <= right; ) {
            if ((gray[y * width + x] & 255) > 154) {
                x++;
                continue;
            }
            int first = x++;
            while (x <= right && (gray[y * width + x] & 255) <= 154) x++;
            if (x - first > best) {
                best = x - first;
                arm = new Arm(first,x - 1);
            }
        }
        return best >= gap * 1.3f ? arm : null;
    }

    private static boolean hook(
            byte[] gray,int width,int height,int x,int armY,int sign,float gap) {
        int steps = Math.round(gap * .78f), covered = 0;
        for (int i = 2; i <= steps; i++) {
            int y = armY + sign * i;
            if (y < 0 || y >= height) return false;
            for (int xx = Math.max(0,x - 2); xx <= Math.min(width - 1,x + 2); xx++) {
                if ((gray[y * width + xx] & 255) > 154) continue;
                covered++;
                break;
            }
        }
        return covered >= (steps - 1) * .84f;
    }
}
