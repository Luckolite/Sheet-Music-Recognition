// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Binds two legible upper-anchored Roman string labels on an explicitly standard violin. */
final class PrintedRomanStrings {
    private static final List<String> LITERALS = List.of("I", "II", "III");
    private static final int[] OPEN_MIDI = {76, 69, 62};
    private static final int MAX_WINDOW_PIXELS = 16384;
    private static final Pattern MIXED_ROMAN_OCR = Pattern.compile("[I1V8]+");

    record Mark(int noteIndex, String literal, int number, String placement) {}
    record Box(int left, int top, int right, int bottom) {
        double x() { return (left + right) * .5; }
        double y() { return (top + bottom) * .5; }
        int width() { return right - left; }
        int height() { return bottom - top; }
    }
    private record Word(String text, double l, double t, double r, double b) {
        boolean contains(Box b) {
            return l <= b.x() && b.x() <= r && t <= b.y() && b.y() <= this.b;
        }
    }
    private record ShaftGroup(Box bounds, int shafts) {}
    private record Attack(int measure, int staff, double onset) {}
    private record Head(int owner, int midi, double x, double y, double duration) {}
    private record Pair(Head higher, Head lower, ShaftGroup above, ShaftGroup below, boolean uniquePitch) {}

    private PrintedRomanStrings() {}

    static List<Mark> capture(
            byte[] gray, int width, int height, List<SheetInterpreter.Word> words,
            ScorePageInterpretation score, List<Map<String, Object>> events,
            Supplier<List<PlayingTechniqueDetector.Staff>> staffSource) {
        if (gray == null || gray.length != (long) width * height || words.isEmpty()) {
            return List.of();
        }
        var boxes = words.stream().map(w -> new Word(
                Normalizer.normalize(w.text(), Normalizer.Form.NFKC).strip().toUpperCase(Locale.ROOT),
                (double) w.left() * width, (double) w.top() * height,
                (double) w.right() * width, (double) w.bottom() * height))
                .sorted(Comparator.comparingDouble(Word::l)).toList();
        if (boxes.stream().noneMatch(w -> LITERALS.contains(w.text()))) return List.of();
        var staffs = staffSource.get();
        if (staffs.isEmpty()) return List.of();

        var attacks = new HashMap<Attack, List<Map<String, Object>>>();
        var counts = new HashMap<Integer, Integer>();
        for (var event : events) {
            if (event.get("sourceNoteIndex") instanceof Integer owner) counts.merge(owner, 1, Integer::sum);
            if (event.get("measureIndex") instanceof Integer m
                    && event.get("staffIndex") instanceof Integer s
                    && event.get("startBeat") instanceof Number start && Double.isFinite(start.doubleValue())) {
                attacks.computeIfAbsent(new Attack(m, s, start.doubleValue()), k -> new ArrayList<>()).add(event);
            }
        }
        var pairs = new ArrayList<Pair>();
        for (var row : attacks.entrySet()) {
            var attack = row.getKey();
            if (attack.staff() != 0 || row.getValue().size() != 2 || attack.measure() < 0
                    || attack.measure() >= score.measures().size()) continue;
            var region = score.measures().get(attack.measure());
            var staff = NaturalHarmonicStaffBinding.uniqueForMeasure(staffs, 0, 1, region, height);
            if (staff == null) continue;
            var first = head(row.getValue().get(0), score.notes(), counts);
            var second = head(row.getValue().get(1), score.notes(), counts);
            if (first == null || second == null) continue;
            var higher = first.y() < second.y() ? first : second;
            var lower = first.y() < second.y() ? second : first;
            double gap = ((double) region.bottom() - region.top()) * height / 8.0;
            if (!(gap > 0) || higher.midi() - lower.midi() != 12 || higher.duration() != lower.duration()
                    || Math.abs(higher.x() - lower.x()) > .25 * gap
                    || !between(lower.y() - higher.y(), 2.6 * gap, 4.2 * gap)) continue;
            double x = (higher.x() + lower.x()) * .5, y = higher.y();
            var nearby = new ArrayList<Word>();
            for (var box : boxes) {
                if (box.l() > x + 1.6 * gap) break;
                if (box.r() >= x - 1.6 * gap && box.b() >= y - 6 * gap && box.t() < y - .2 * gap) {
                    nearby.add(box);
                }
            }
            if (nearby.stream().noneMatch(w -> LITERALS.contains(w.text()))) continue;
            var ink = glyphs(gray, width, height, x, y, gap);
            if (ink.size() != 2) continue;
            var above = ink.get(0);
            var below = ink.get(1);
            if (!stackedRows(above, below, gap)
                    || blocksOtherStaff(staffs, staff, above.bounds())
                    || blocksOtherStaff(staffs, staff, below.bounds())
                    || !qualifyOcr(above, below, nearby, gap)) continue;
            int higherFloor = OPEN_MIDI[above.shafts() - 1], lowerFloor = OPEN_MIDI[below.shafts() - 1];
            if (higher.midi() >= higherFloor && lower.midi() >= lowerFloor) {
                pairs.add(new Pair(higher, lower, above, below, lower.midi() < higherFloor));
            }
        }
        var contested = new HashSet<Integer>();
        for (int i = 0; i < pairs.size(); i++) {
            for (int j = i + 1; j < pairs.size(); j++) {
                if (sharedInk(pairs.get(i), pairs.get(j))) {
                    contested.add(i);
                    contested.add(j);
                }
            }
        }
        var marks = new ArrayList<Mark>();
        for (int i = 0; i < pairs.size(); i++) {
            var p = pairs.get(i);
            if (contested.contains(i) || !p.uniquePitch()) continue;
            marks.add(mark(p.higher(), p.above()));
            marks.add(mark(p.lower(), p.below()));
        }
        marks.sort(Comparator.comparingInt(Mark::noteIndex));
        return List.copyOf(marks);
    }

    /** A later accidental can borrow a serif from a stacked string mark above the previous head. */
    static boolean ownsAccidentalFragment(
            byte[] gray,
            int width,
            int height,
            Box fragment,
            double ownerX,
            double ownerY,
            double gap) {
        if (gray == null
                || gray.length != (long) width * height
                || gap < 10
                || gap > 25
                || Math.abs(fragment.x() - ownerX) > gap * .65
                || !between(ownerY - fragment.bottom(), gap * .85, gap * 2.3)) return false;
        var rows = glyphs(gray, width, height, ownerX, ownerY, gap);
        if (rows.size() != 2 || !stackedRows(rows.get(0), rows.get(1), gap)) return false;
        var lower = rows.get(1).bounds();
        return fragment.width() <= gap * .78
                && fragment.height() >= gap * 1.04
                && fragment.left() >= lower.left() - 1
                && fragment.right() <= lower.right() + 1
                && fragment.top() >= lower.top() - 1
                && fragment.bottom() <= lower.bottom() + 1;
    }

    private static boolean stackedRows(ShaftGroup above, ShaftGroup below, double gap) {
        return below.shafts() == above.shafts() + 1
                && Math.abs(above.bounds().x() - below.bounds().x()) <= .35 * gap
                && between(below.bounds().y() - above.bounds().y(), 1.53 * gap, 2.12 * gap);
    }

    private static Head head(Map<String, Object> event, List<ScoreNoteEvent> notes, Map<Integer, Integer> counts) {
        if (!(event.get("sourceNoteIndex") instanceof Integer index) || index < 0 || index >= notes.size()
                || counts.get(index) != 1 || !Integer.valueOf(1).equals(event.get("staffCount"))
                || !(event.get("midi") instanceof Integer midi) || !between(midi, 0, 127)
                || !(event.get("x") instanceof Number x) || !Double.isFinite(x.doubleValue())
                || !(event.get("y") instanceof Number y) || !Double.isFinite(y.doubleValue())
                || !(event.get("durationBeats") instanceof Number duration)
                || !Double.isFinite(duration.doubleValue()) || duration.doubleValue() <= 0
                || Boolean.TRUE.equals(event.get("tiedFromPrevious"))) return null;
        var note = notes.get(index);
        if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                || note.octaveShift() != 0 || note.harmonicSemitones() != 0 || note.openStringTouch() != null
                || note.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE
                || (note.articulations() & NoteOrnament.GRACE) != 0
                || !Integer.valueOf(note.measureIndex()).equals(event.get("measureIndex"))
                || !Integer.valueOf(note.staffIndex()).equals(event.get("staffIndex"))
                || !Integer.valueOf(note.staffCount()).equals(event.get("staffCount"))) return null;
        return new Head(index, midi, x.doubleValue(), y.doubleValue(), duration.doubleValue());
    }

    private static List<ShaftGroup> glyphs(byte[] gray, int width, int height, double x, double y, double gap) {
        var window = new Box(Math.max(0, (int) Math.round(x - 1.62 * gap)),
                Math.max(0, (int) Math.round(y - 6 * gap)),
                Math.min(width, (int) Math.round(x + 1.62 * gap)),
                Math.min(height, (int) Math.round(y - .18 * gap)));
        if (window.width() <= 0 || window.height() <= 0
                || (long) window.width() * window.height() > MAX_WINDOW_PIXELS) return List.of();
        var scan = RawNumeralComponents.capture(gray, width, height, window.left(), window.top(),
                window.right() - 1, window.bottom() - 1, 138, () -> {}, () -> {});
        var parts = new ArrayList<ShaftGroup>();
        for (var item : scan.components()) {
            var box = new Box(item.left, item.top, item.right + 1, item.bottom + 1);
            if (!between(box.height(), 1.04 * gap, 1.8 * gap) || !between(box.width(), .36 * gap, 2.55 * gap)
                    || item.size() < 3 * gap || box.left() == window.left() || box.top() == window.top()
                    || box.right() == window.right() || box.bottom() == window.bottom()
                    || Math.abs(box.x() - x) > .99 * gap) continue;
            int stems = shafts(item, box, width, gap);
            if (stems > 0) parts.add(new ShaftGroup(box, stems));
        }
        parts.sort(Comparator.comparingDouble(p -> p.bounds().y()));
        var bands = new ArrayList<ShaftGroup>();
        for (int first = 0; first < parts.size();) {
            int last = first + 1;
            double row = parts.get(first).bounds().y();
            while (last < parts.size() && parts.get(last).bounds().y() - row <= .24 * gap) last++;
            var group = parts.subList(first, last);
            group.sort(Comparator.comparingInt(p -> p.bounds().left()));
            var box = group.get(0).bounds();
            int total = group.get(0).shafts();
            boolean splitRow = false;
            for (var part : group.subList(1, group.size())) {
                var next = part.bounds();
                if (!between(next.left() - box.right(), 0, .5 * gap)) splitRow = true;
                box = new Box(box.left(), Math.min(box.top(), next.top()),
                        Math.max(box.right(), next.right()), Math.max(box.bottom(), next.bottom()));
                total += part.shafts();
            }
            if (!splitRow && total <= LITERALS.size() && Math.abs(box.x() - x) <= .54 * gap) {
                bands.add(new ShaftGroup(box, total));
            }
            first = last;
        }
        return bands;
    }

    private static int shafts(RawNumeralComponents.Component component, Box box, int rasterWidth, double gap) {
        int h = box.height(), w = box.width(), cap = Math.max(1, h / 5), middle = h / 3, end = 2 * h / 3;
        int[] rows = new int[h], columns = new int[w];
        boolean[] top = new boolean[w], bottom = new boolean[w];
        for (int i = 0; i < component.size(); i++) {
            int pixel = component.pixel(i), x = pixel % rasterWidth - box.left(), y = pixel / rasterWidth - box.top();
            rows[y]++;
            if (y < cap) top[x] = true;
            if (y >= h - cap) bottom[x] = true;
            if (middle <= y && y < end) columns[x]++;
        }
        int[] coreRows = Arrays.copyOfRange(rows, middle, end);
        Arrays.sort(coreRows);
        double median = .5 * (coreRows[(coreRows.length - 1) / 2] + coreRows[coreRows.length / 2]);
        if (!serif(rows, 0, cap, w, median) || !serif(rows, h - cap, h, w, median)) return 0;
        int count = 0;
        double coverage = .55 * (end - middle);
        for (int col = 0; col < w;) {
            if (columns[col] < coverage) {
                col++;
                continue;
            }
            int first = col;
            boolean cappedTop = false, cappedBottom = false;
            while (col < w && columns[col] >= coverage) {
                cappedTop |= top[col];
                cappedBottom |= bottom[col];
                col++;
            }
            if (!between(col - first, .13 * gap, .48 * gap) || !cappedTop || !cappedBottom) return 0;
            if (++count > LITERALS.size()) return 0;
        }
        return count;
    }

    private static boolean serif(int[] rows, int first, int end, int width, double coreMedian) {
        int sum = 0, max = 0;
        for (int y = first; y < end; y++) {
            sum += rows[y];
            max = Math.max(max, rows[y]);
        }
        return max >= 1.5 * coreMedian && sum >= .47 * (end - first) * width;
    }

    private static boolean qualifyOcr(ShaftGroup first, ShaftGroup second, List<Word> words, double gap) {
        String high = LITERALS.get(first.shafts() - 1), low = LITERALS.get(second.shafts() - 1);
        int exactHigh = 0, exactLow = 0, mixedHigh = 0, mixedLow = 0, merged = 0;
        for (var word : words) {
            boolean inHigh = word.contains(first.bounds()), inLow = word.contains(second.bounds());
            boolean separateBox = word.b() - word.t() <= 3 * gap;
            if (inHigh && separateBox && word.text().equals(high)) exactHigh++;
            if (inLow && separateBox && word.text().equals(low)) exactLow++;
            if (MIXED_ROMAN_OCR.matcher(word.text()).matches()) {
                if (inHigh) mixedHigh++;
                if (inLow) mixedLow++;
            }
            if (inHigh && inLow && word.text().equals(high + low)) merged++;
        }
        if (exactHigh > 1 || exactLow > 1) return false;
        return exactHigh == 1 && (exactLow == 1 || mixedLow == 1)
                || exactLow == 1 && mixedHigh == 1 || merged == 1;
    }

    private static boolean blocksOtherStaff(List<PlayingTechniqueDetector.Staff> staffs,
                                            PlayingTechniqueDetector.Staff selected, Box ink) {
        for (var staff : staffs) {
            if (staff != selected && ink.top() <= staff.bottom() && ink.bottom() >= staff.top()) return true;
        }
        return false;
    }

    private static boolean sharedInk(Pair first, Pair second) {
        for (var a : List.of(first.above().bounds(), first.below().bounds())) {
            for (var b : List.of(second.above().bounds(), second.below().bounds())) {
                int w = Math.min(a.right(), b.right()) - Math.max(a.left(), b.left());
                int h = Math.min(a.bottom(), b.bottom()) - Math.max(a.top(), b.top());
                if (w > 0 && h > 0 && w * h >= .75 * Math.min(a.width() * a.height(), b.width() * b.height())) return true;
            }
        }
        return false;
    }

    private static Mark mark(Head head, ShaftGroup ink) {
        return new Mark(head.owner(), LITERALS.get(ink.shafts() - 1), ink.shafts(), "ABOVE");
    }

    private static boolean between(double n, double lo, double hi) {
        return lo <= n && n <= hi;
    }
}
