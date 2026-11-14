// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Restores an isolated third rail only on its own two-head rhythm column. */
final class OwnedTertiaryBeamlets {
    private OwnedTertiaryBeamlets() {}

    record Head(int index, ScoreNoteEvent note, float x, float y, float gap) {}

    private record Column(Head first, Head second) {
        float x() {
            return (first.x + second.x) * .5f;
        }
        float gap() {
            return (first.gap + second.gap) * .5f;
        }
    }

    private record Axis(int x, int tip, int direction) {}

    static List<Integer> recover(byte[] gray, int w, int h, List<Head> heads, List<MeasureRegion> measures) {
        if (gray == null || gray.length != (long) w * h || heads.size() < 4) return List.of();
        var result = new ArrayList<Integer>();
        var columns = new ArrayList<Column>();
        var budget = new PrintedBeamShafts.Budget(2_000_000);
        for (int i = 0; i < heads.size(); ) {
            int j = i + 1;
            Head first = heads.get(i);
            while (j < heads.size() && sameColumn(first, heads.get(j))) j++;
            if (j - i == 2)
                columns.add(new Column(first, heads.get(i + 1)));
            else
                columns.add(null); // A monophonic or ambiguous attack blocks borrowing across it.
            i = j;
        }
        int last = columns.size() - 1;
        if (last < 8 || !closingHalfChord(columns.get(last), measures.size() - 1)) return result;
        Column closing = columns.get(last);
        // The preceding run alternates dotted sixteenths and independently owned
        // third hooks. The final half chord and double rule identify its end.
        for (int i = last - 8; i < last; i++) {
            var column = columns.get(i);
            boolean dotted = (last - i) % 2 == 0;
            if (!usable(column, false) || !sameStaff(closing.first, column.first)
                    || column.first.note.augmentationDots() != (dotted ? 1 : 0)
                    || dotted && column.first.note.beamCount() != 2) return result;
        }
        if (last >= 9 && columns.get(last - 9) != null
                && sameStaff(closing.first, columns.get(last - 9).first)) return result;
        var printedStaffs = RawStaffLineDetector.detect(gray, w, h);
        var printed = staff(printedStaffs, closing);
        if (printed == null || !doubleClose(gray, w, h, printed, closing,
                measures.get(closing.first.note.measureIndex()))) return result;
        for (int i = last - 7; i < last; i += 2) {
            var own = columns.get(i);
            var peer = columns.get(i - 1);
            if (staff(printedStaffs, own) != printed || staff(printedStaffs, peer) != printed)
                return List.of();
            float gap = printed.gap();
            if (!proveSide(gray, w, h, axes(gray, w, h, own, gap, budget),
                    axes(gray, w, h, peer, gap, budget), gap, -1)) return List.of();
            if (own.first.note.beamCount() == 2) {
                result.add(own.first.index);
                result.add(own.second.index);
            }
        }
        return result;
    }

    private static boolean closingHalfChord(Column column, int finalMeasure) {
        if (column == null || column.first.note.measureIndex() != finalMeasure
                || column.first.note.staffCount() != 1) return false;
        for (Head h : List.of(column.first, column.second)) {
            var n = h.note;
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.beamCount() != 0
                    || n.unbeamedDurationBeats() != 2 || n.augmentationDots() != 0
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || n.tupletDivisor() != 1 || n.tiedFromPrevious()
                    || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0
                    || n.crossStaffBeam() || (n.articulations() & NoteOrnament.GRACE) != 0) return false;
        }
        return true;
    }

    private static boolean doubleClose(
            byte[] gray, int w, int h, RawStaffLineDetector.StaffLines printed, Column closing,
            MeasureRegion measure) {
        float x = measure.right() * w;
        float gap = printed.gap();
        if (x - closing.x() < gap * 2 || x - closing.x() > gap * 6) return false;
        float floor = (closing.first.y + closing.first.note.staffStep() * gap * .5f
                + closing.second.y + closing.second.note.staffStep() * gap * .5f) * .5f;
        float[] rails = TerminalDirectionRails.read(gray, w, h, x - 2 * gap, floor, gap,
                measure.left(), measure.right());
        if (rails != null) floor = rails[0];
        for (int offset : new int[] {0, -1, 1}) {
            float bottom = floor + offset;
            if (bottom - 4 * gap < 0 || bottom >= h) continue;
            var local = new PlayingTechniqueDetector.Staff(bottom - 4 * gap, bottom, gap, 0, 1);
            if (PrintedCadenceDurations.boundaryColumn(gray, w, local, x, false, true) >= 0)
                return true;
        }
        return false;
    }

    private static boolean sameStaff(Head a, Head b) {
        return a.note.measureIndex() == b.note.measureIndex()
                && a.note.staffIndex() == b.note.staffIndex()
                && a.note.staffCount() == b.note.staffCount();
    }

    private static boolean sameColumn(Head a, Head b) {
        return sameStaff(a, b) && Math.abs(a.x - b.x) <= Math.min(a.gap,b.gap) * .15f;
    }

    private static boolean usable(Column column, boolean owner) {
        if (column == null || column.gap() < 8) return false;
        var a = column.first.note;
        var b = column.second.note;
        if (a.staffCount() != 1
                || a.beamCount() != b.beamCount() || a.beamCount() < 2 || a.beamCount() > 3
                || a.augmentationDots() != b.augmentationDots()
                || a.clefBottomDiatonic() != b.clefBottomDiatonic()
                || a.staffStep() == b.staffStep()) return false;
        for (Head h : List.of(column.first, column.second)) {
            var n = h.note;
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || n.crossStaffBeam() || n.tiedFromPrevious() || n.boundaryTies() != 0
                    || n.unbeamedDurationBeats() != 0 || n.tupletDivisor() != 1
                    || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0
                    || (n.articulations() & NoteOrnament.GRACE) != 0) return false;
        }
        return !owner || (a.beamCount() == 2 && a.augmentationDots() == 0);
    }

    private static RawStaffLineDetector.StaffLines staff(
            List<RawStaffLineDetector.StaffLines> printed, Column column) {
        float gap = column.gap();
        // A coarse detector may report an integral gap even when the raw five rows
        // independently resolve fractional spacing. Compare head phases in each
        // candidate staff's own measured units before accepting its unique match.
        RawStaffLineDetector.StaffLines found = null;
        for (var staff : printed) {
            if (Math.abs(staff.gap() - gap) > gap * .1f) continue;
            float bottomA = column.first.y + column.first.note.staffStep() * staff.gap() * .5f;
            float bottomB = column.second.y + column.second.note.staffStep() * staff.gap() * .5f;
            float floor = (bottomA + bottomB) * .5f;
            if (Math.abs(bottomA - bottomB) > staff.gap() * .18f
                    || Math.abs(staff.bottom() - floor) > staff.gap() * .60f) continue;
            if (found != null) return null;
            found = staff;
        }
        return found;
    }

    private static List<Axis> axes(
            byte[] gray, int w, int h, Column c, float gap, PrintedBeamShafts.Budget budget) {
        var a = PrintedBeamShafts.at(gray,w,h,c.first.x,c.first.y,gap,budget);
        var b = PrintedBeamShafts.at(gray,w,h,c.second.x,c.second.y,gap,budget);
        if (a.size() != 1 || b.size() != 1) return List.of();
        var sa = a.get(0);
        var sb = b.get(0);
        int direction=sa.direction();
        if (direction != sb.direction()
                || Math.abs(sa.endY()-sb.endY()) > gap * .22f
                || c.first.note.stemDirection() != 0 && c.first.note.stemDirection() != -direction
                || c.second.note.stemDirection() != 0 && c.second.note.stemDirection() != -direction)
            return List.of();
        var result = new ArrayList<Axis>();
        for (int x = Math.max(sa.left(), sb.left()); x <= Math.min(sa.right(),sb.right()); x++) {
            result.add(new Axis(x,sa.endY(),direction));
            if (sa.endY() != sb.endY()) result.add(new Axis(x,sb.endY(),direction));
        }
        return result;
    }

    private static boolean proveSide(
            byte[] gray, int w, int h, List<Axis> owners, List<Axis> peers, float gap,int side) {
        for (var own : owners)
            for (var peer : peers) {
                float slope = (peer.tip-own.tip)/(float)(peer.x-own.x);
                if (own.direction != peer.direction || (peer.x-own.x)*side < gap * 1.7f
                        || (peer.x-own.x)*side > gap * 4.2f || Math.abs(slope) > .4f
                        || !doubleRails(gray,w,h,own,peer,slope,gap)) continue;
                if (hook(gray,w,h,own,gap,side)) return true;
            }
        return false;
    }

    private static boolean doubleRails(
            byte[] gray,int w,int h,Axis own,Axis peer,float slope,float gap) {
        int margin = Math.max(2,Math.round(gap*.28f));
        int start=Math.min(own.x,peer.x)+margin,end=Math.max(own.x,peer.x)-margin;
        for (int a=Math.round(gap*.18f); a<=Math.round(gap*.4f); a++) {
            if (!rail(gray,w,h,own,slope,start,end,a)) continue;
            for (int b=Math.round(gap*.94f); b<=Math.round(gap*1.3f); b++) {
                if (!rail(gray,w,h,own,slope,start,end,b)) continue;
                int exposed=0,seam=(a+b)/2;
                for (int x=start; x<=end; x++)
                    if (!ink(gray,w,h,x,center(own,slope,x,seam))) exposed++;
                if (exposed>=(end-start+1)*.28f) return true;
            }
        }
        return false;
    }

    private static boolean rail(byte[] gray,int w,int h,Axis own,float slope,int a,int b,int depth) {
        int blanks=0;
        for (int x=a; x<=b; x++) {
            int center=center(own,slope,x,depth);
            boolean dark=true;
            for (int row=center-2; row<=center+2; row++) dark &= ink(gray,w,h,x,row);
            if (!dark && ++blanks > 1) return false;
        }
        return true;
    }

    private static int center(Axis own,float slope,int x,int depth) {
        return Math.round(own.tip+(x-own.x)*slope)-own.direction*depth;
    }

    private static boolean hook(byte[] gray,int w,int h,Axis own,float gap,int side) {
        int edge=Math.round(gap*.8f),near=Math.round(gap*.27f),radius=Math.round(gap*.36f);
        for (int d=Math.round(gap*1.65f); d<=Math.round(gap*2.24f); d++) {
            int bands=0, exposed=0;
            for (int dx=near; dx<=edge; dx++) {
                int x=own.x+side*dx;
                if (vertical(gray,w,h,own,x,d,radius)<gap*.44f
                        || vertical(gray,w,h,own,x,Math.round(gap*.32f),Math.round(gap*.43f))<gap*.35f
                        || vertical(gray,w,h,own,x,Math.round(gap*1.03f),Math.round(gap*.38f))<gap*.28f)
                    continue;
                bands++;
                if (!ink(gray,w,h,x,own.tip-own.direction*(d-Math.round(gap*.5f)))) exposed++;
            }
            if (bands<4 || exposed==0
                    || vertical(gray,w,h,own,own.x+side*(edge+Math.round(gap*.35f)),d,radius)>=gap*.31f)
                continue;
            if (vertical(gray,w,h,own,own.x+side,d,radius)>=gap*.55f) return true;
        }
        return false;
    }

    private static int vertical(byte[] gray,int w,int h,Axis shaft,int x,int d,int radius) {
        int count=0;
        for (int j=d-radius; j<=d+radius; j++)
            if (ink(gray,w,h,x,shaft.tip-shaft.direction*j)) count++;
        return count;
    }

    private static boolean ink(byte[] gray,int w,int h,int x,int y) {
        return x>=0 && x<w && y>=0 && y<h && (gray[y*w+x]&255)<155;
    }
}
