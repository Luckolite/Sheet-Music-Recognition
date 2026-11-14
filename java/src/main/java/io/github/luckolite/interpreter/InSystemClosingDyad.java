// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Recovers alternating short values in a beamed dyad cadence bounded by ordinary barlines. */
final class InSystemClosingDyad {
    private InSystemClosingDyad() {}

    private record Head(int index, ScoreNoteEvent note, float x, float y) {}
    private record Column(Head first, Head second) {
        float x() { return (first.x + second.x) * .5f; }
    }
    private record Axis(int x, int tip, int direction) {}

    static ScorePageInterpretation apply(ScorePageInterpretation score, byte[] gray, int width, int height) {
        if (gray == null || gray.length != (long) width * height || score.measures().size() < 3) return score;
        var regions = score.measures();
        var byMeasure = new ArrayList<List<Head>>();
        for (int i = 0; i < regions.size(); i++) byMeasure.add(new ArrayList<>());
        for (int i = 0; i < score.notes().size(); i++) {
            var note = score.notes().get(i);
            var m = regions.get(note.measureIndex());
            byMeasure.get(note.measureIndex()).add(new Head(i, note,
                    (m.left() + note.positionInMeasure() * (m.right() - m.left())) * width,
                    note.pageY() * height));
        }

        var beamEdits = new HashMap<Integer, ScoreNoteEvent>();
        var drops = new java.util.HashSet<Integer>();
        var budget = new PrintedBeamShafts.Budget(2_000_000);
        var meters = score.meterChanges();
        ScoreMeterChange meter = null;
        int nextMeter = 0;
        for (int m = 1; m + 1 < regions.size(); m++) {
            while (nextMeter < meters.size() && meters.get(nextMeter).measureIndex() <= m)
                meter = meters.get(nextMeter++);
            int measure = m;
            var members = byMeasure.get(m);
            if (meter == null || meter.numerator() != 4 || meter.denominator() != 4
                    || members.size() < 18 || members.size() > 19
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == measure)
                    || (nextMeter < meters.size() && meters.get(nextMeter).measureIndex() == m + 1))
                continue;
            qualify(gray, width, height, regions.get(m - 1), regions.get(m), regions.get(m + 1),
                    members, budget, beamEdits, drops);
        }
        if (budget.refused() || beamEdits.isEmpty() && drops.isEmpty()) return score;
        var notes = new ArrayList<ScoreNoteEvent>(score.notes().size() - drops.size());
        var owners = new ArrayList<Integer>(notes.size());
        for (int i = 0; i < score.notes().size(); i++) {
            if (drops.contains(i)) continue;
            notes.add(beamEdits.getOrDefault(i, score.notes().get(i)));
            owners.add(i);
        }
        return score.withNotes(notes, owners);
    }

    private static void qualify(byte[] gray, int w, int h, MeasureRegion previous, MeasureRegion box,
            MeasureRegion following, List<Head> heads, PrintedBeamShafts.Budget budget,
            Map<Integer, ScoreNoteEvent> edits, java.util.Set<Integer> drops) {
        float gap = (box.bottom() - box.top()) * h / 8.2f;
        if (gap < 10 || gap > 27 || box.cadenceQuarterBeats() != 0
                || !sameSystem(previous, box, h, gap) || !sameSystem(box, following, h, gap)) return;
        heads.sort(Comparator.comparingDouble(Head::x));
        var pairs = new ArrayList<Column>(9);
        Head rival = null;
        for (int i = 0; i < heads.size();) {
            var a = heads.get(i++);
            if (!plain(a.note)) return;
            if (i < heads.size() && heads.get(i).x - a.x <= gap * .18f) {
                var b = heads.get(i++);
                if (!plain(b.note) || a.note.clefBottomDiatonic() != b.note.clefBottomDiatonic()
                        || a.note.staffStep() == b.note.staffStep()) return;
                pairs.add(new Column(a, b));
            } else {
                if (rival != null || pairs.size() > 7 || pairs.isEmpty()) return;
                rival = a;
            }
        }
        if (pairs.size() != 9) return;
        var terminal = pairs.get(8);
        if (!half(terminal.first.note) || !half(terminal.second.note)
                || box.right() * w - terminal.x() < gap * 2
                || box.right() * w - terminal.x() > gap * 5
                || !ordinarySeam(gray, w, h, previous, box, gap)
                || !ordinarySeam(gray, w, h, box, following, gap)) return;
        var axes = pairs.stream().map(pair -> axes(gray, w, h, pair, gap, budget)).toList();
        if (axes.stream().anyMatch(List::isEmpty)) return;

        for (int i = 0; i < 8; i++) {
            var pair = pairs.get(i);
            int dots = i % 2 == 0 ? 1 : 0;
            if (!beamed(pair.first.note, dots) || !beamed(pair.second.note, dots)
                    || pair.first.note.beamCount() != pair.second.note.beamCount()) return;
            if (i > 0) {
                boolean attached = onTwoRails(gray, w, h, axes.get(i), axes.get(i - 1), gap);
                if (attached != (i != 4)) return;
            }
            if (dots == 1 && axes.get(i).stream().anyMatch(a -> hook(gray, w, h, a, gap, -1))) return;
            if (dots == 0 && !proveSide(gray, w, h, axes.get(i), axes.get(i - 1), gap, -1)) return;
        }
        if (onTwoRails(gray, w, h, axes.get(8), axes.get(7), gap)
                || !openOval(gray, w, h, terminal.first, gap) || !openOval(gray, w, h, terminal.second, gap)) return;
        if (rival != null) {
            boolean borrowed = false;
            for (int i = 1; i < 8; i += 2)
                if (rival.x > pairs.get(i - 1).x() && rival.x < pairs.get(i).x()) {
                    if (!borrowedHook(gray, w, h, rival, axes.get(i), gap, budget)) return;
                    borrowed = true;
                    break;
                }
            if (!borrowed) return;
            drops.add(rival.index);
        }
        for (int i = 1; i < 8; i += 2) {
            var pair = pairs.get(i);
            if (pair.first.note.beamCount() == 3) continue;
            edits.put(pair.first.index, tertiary(pair.first.note));
            edits.put(pair.second.index, tertiary(pair.second.note));
        }
    }

    private static boolean plain(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffIndex() == 0 && n.staffCount() == 1
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL && !n.crossStaffBeam()
                && !n.tiedFromPrevious() && n.boundaryTies() == 0 && n.tupletDivisor() == 1
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0 && n.octaveShift() == 0
                && n.harmonicSemitones() == 0 && n.openStringTouch() == null
                && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static boolean half(ScoreNoteEvent n) {
        return n.unbeamedDurationBeats() == 2 && n.beamCount() == 0 && n.augmentationDots() == 0;
    }

    private static boolean beamed(ScoreNoteEvent n, int dots) {
        return n.stemDirection() == -1 && n.unbeamedDurationBeats() == 0 && n.augmentationDots() == dots
                && n.beamCount() >= 2 && n.beamCount() <= (dots == 1 ? 2 : 3);
    }

    private static boolean sameSystem(MeasureRegion a, MeasureRegion b, int h, float gap) {
        return Math.abs(a.top() - b.top()) * h < gap * .24f
                && Math.abs(a.bottom() - b.bottom()) * h < gap * .24f;
    }

    private static boolean ordinarySeam(byte[] gray, int w, int h, MeasureRegion a, MeasureRegion b, float gap) {
        float left = a.right() * w, right = b.left() * w, x = (left + right) * .5f;
        if (right - left < gap * .2f || right - left > gap * 1.6f) return false;
        float hint = (a.bottom() + b.bottom()) * .5f * h - gap * 2.1f;
        float[] rules = PrintedDirectionStaff.printedRules(gray, w, h, x, hint, gap);
        if (rules == null) {
            // Source geometry can miss the true bottom line by a few pixels.
            var rows = RawStaffLineDetector.detect(gray, w, h).stream()
                    .filter(staff -> Math.abs(staff.bottom() - hint) < gap * .23f
                            && Math.abs(staff.gap() - gap) < gap * .12f)
                    .toList();
            if (rows.size() != 1) return false;
            var staff = rows.get(0);
            rules = new float[]{staff.bottom(), staff.gap()};
            // Reconfirm every engraved row immediately across this exact barline
            // before borrowing the page staff-fit coordinates.
            for (int y : staff.rows()) for (int side : new int[]{-1, 1}) {
                int hits = 0, total = 0;
                for (int offset = Math.round(gap * .6f); offset <= Math.round(gap * 1.5f); offset++) {
                    int at = Math.round(x) + offset * side;
                    for (int dy = -2; dy <= 2; dy++) {
                        if (ink(gray, w, h, at, y + dy)) {
                            hits++;
                            break;
                        }
                    }
                    total++;
                }
                if (hits < total * .6f) return false;
            }
        }
        if (rules == null) return false;
        int columns = 0, prior = -2;
        for (int k = Math.round(x - gap * .75f); k <= Math.round(x + gap * .75f); k++) {
            int samples = 0, dark = 0;
            for (int space = 0; space < 4; space++)
                for (int y = Math.round(rules[0] - (space + .75f) * rules[1]);
                        y <= Math.round(rules[0] - (space + .25f) * rules[1]); y++) {
                    samples++;
                    if (ink(gray, w, h, k, y)) dark++;
                }
            if (dark < samples * .7f) continue;
            if (columns > 0 && k != prior + 1) return false;
            prior = k;
            columns++;
        }
        return columns >= 2 && columns <= gap * .5f;
    }

    private static boolean openOval(byte[] gray, int w, int h, Head note, float gap) {
        // A ledger can split the central paper pocket. Use a short, vertically enclosed
        // paper run near the center so a rail crossing cannot establish a hollow oval.
        int x = Math.round(note.x), y = Math.round(note.y);
        int radius = Math.round(gap * .35f);
        int minimum = Math.round(gap * .15f);
        int enclosed = 0;
        for (int column = x - radius; column <= x + radius; column++) {
            boolean previousInk = false;
            int run = 0;
            for (int row = y - radius; row <= y + radius; row++) {
                if (ink(gray, w, h, column, row)) {
                    if (previousInk && run >= minimum) {
                        enclosed++;
                        break;
                    }
                    previousInk = true;
                    run = 0;
                } else if (previousInk) run++;
            }
        }
        return enclosed >= minimum;
    }

    private static boolean borrowedHook(byte[] gray, int w, int h, Head rival, List<Axis> owners, float gap,
            PrintedBeamShafts.Budget budget) {
        var n = rival.note;
        if (n.stemDirection() != 1 || n.beamCount() != 0 || n.unbeamedDurationBeats() != 1
                || n.augmentationDots() != 0 || n.articulations() != 0
                || n.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) return false;
        var shafts = PrintedBeamShafts.at(gray, w, h, rival.x, rival.y, gap, budget);
        if (shafts.size() != 1 || shafts.get(0).direction() != -1) return false;
        var shaft = shafts.get(0);
        for (var c : shaft.columns())
            if (owners.stream().noneMatch(a -> a.x == c.x() && a.direction == 1
                    && a.x - rival.x >= gap * .24f && a.x - rival.x <= gap * .66f
                    && a.tip - rival.y >= gap * 1.35f && a.tip - rival.y <= gap * 1.95f)) return false;
        int x = Math.round(rival.x), y = Math.round(rival.y), span = Math.round(gap * .2f);
        for (int c = x - span; c <= x + span; c++)
            if (!ink(gray, w, h, c, y - 1) || !ink(gray, w, h, c, y + 1)) return false;
        return true;
    }

    private static boolean onTwoRails(byte[] gray, int w, int h, List<Axis> first, List<Axis> second, float gap) {
        for (var a : first) for (var b : second) {
            float slope = (b.tip - a.tip) / (float) (b.x - a.x);
            if (Math.abs(b.x - a.x) >= gap * 1.4f && Math.abs(slope) < .4f
                    && a.direction == b.direction && doubleRails(gray, w, h, a, b, slope, gap)) return true;
        }
        return false;
    }

    private static ScoreNoteEvent tertiary(ScoreNoteEvent n) {
        return new ScoreNoteEvent(n.measureIndex(), n.positionInMeasure(), n.staffStep(), n.staffIndex(),
                n.staffCount(), n.pageY(), n.tiedFromPrevious(), n.augmentationDots(), 3, n.writtenAccidental(),
                n.unbeamedDurationBeats(), n.tupletDivisor(), n.followingRestBeats(), n.articulations(),
                n.clefBottomDiatonic(), n.crossStaffBeam(), n.leadingRestBeats(), n.compactOpening(),
                n.octaveShift(), n.boundaryTies(), n.tupletNormalNotes(), n.stemDirection(), n.kind(),
                n.visualSize(), n.harmonicSemitones(), n.openStringTouch());
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
