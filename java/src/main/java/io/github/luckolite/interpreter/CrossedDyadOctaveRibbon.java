// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Read a closed dotted ottava when a crossing stroke splits the numeral's lower counter. */
final class CrossedDyadOctaveRibbon {
    private CrossedDyadOctaveRibbon() {}

    record Ribbon(PlayingTechniqueDetector.Staff staff, float from, float to, boolean recognizedLeadingDigit,
            float numeralX, float ruleY) {
        Ribbon(PlayingTechniqueDetector.Staff staff, float from, float to, boolean recognizedLeadingDigit) {
            this(staff, from, to, recognizedLeadingDigit, Float.NaN, Float.NaN);
        }
    }

    private enum RibbonScan { SINGLE, CROSS_BAR, COMPACT, SOLO, OUTGOING, REPEATED, SOLO_CROSS, SOLO_OUTGOING, SOLO_REPEAT }

    record SoloWrap(Ribbon outgoing, Ribbon incoming) {}

    private record Dot(int first, int last) {
        float center() { return (first + last) * .5f; }
    }

    private record Evidence(int row, int numeralX, int hookX, int count, boolean recognizedLeadingDigit) {}

    static List<Ribbon> read(byte[] raw, byte[] normalized, int width, int height,
            List<PlayingTechniqueDetector.Staff> staffs, List<MeasureRegion> bars, List<ScoreNoteEvent> notes, List<PlayingTechniqueDetector.Word> words) {
        if (normalized == null || raw == null || raw.length != (long) width * height
                || normalized.length != raw.length) return List.of();
        var byBar = new ArrayList<List<ScoreNoteEvent>>(bars.size());
        for (var ignored : bars) byBar.add(new ArrayList<>());
        for (var note : notes)
            if (note.measureIndex() >= 0 && note.measureIndex() < bars.size())
                byBar.get(note.measureIndex()).add(note);
        var result = new ArrayList<Ribbon>();
        for (int i = 0; i < bars.size(); i++) {
            if (byBar.get(i).size() != 24) continue;
            var bar = bars.get(i);
            var heads = byBar.get(i).stream().sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (gap < 12 || gap > 23) continue;
            var staff = staffs.stream().filter(st -> st.count() == 1 && st.top() >= bar.top() * height
                    && st.bottom() <= bar.bottom() * height && Math.abs(st.gap() - gap) < .12f * gap)
                    .findFirst().orElse(null);
            if (staff == null) continue;
            float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
            var x = new float[12];
            boolean dyads = true;
            for (int d = 0; d < x.length; d++) {
                var a = heads.get(d * 2);
                var b = heads.get(d * 2 + 1);
                if (a.kind() != ScoreNoteEvent.Kind.PITCHED || b.kind() != ScoreNoteEvent.Kind.PITCHED || a.staffCount() != 1 || b.staffCount() != 1
                        || a.clefBottomDiatonic() != b.clefBottomDiatonic()
                        || Math.abs(a.staffStep() - b.staffStep()) != 7
                        || Math.abs(a.positionInMeasure() - b.positionInMeasure()) * span > .2f * gap) {
                    dyads = false;
                    break;
                }
                x[d] = left + span * (a.positionInMeasure() + b.positionInMeasure()) * .5f;
            }
            if (!dyads) continue;
            var first = evidence(raw, width, height, staff, x, gap, words);
            var second = evidence(normalized, width, height, staff, x, gap, words);
            if (first == null || second == null || Math.abs(first.numeralX - second.numeralX) > gap * .20f
                    || Math.abs(first.hookX - second.hookX) > gap * .20f
                    || Math.abs(first.row - second.row) > gap * .18f) continue;
            int start = ownerAfter(x, Math.max(first.numeralX, second.numeralX));
            int stop = ownerAfter(x, Math.min(first.hookX, second.hookX));
            if (start < 1 || stop >= x.length || stop - start < 4 || stop - start > 8) continue;
            result.add(new Ribbon(staff, (x[start - 1] + x[start]) * .5f, (x[stop - 1] + x[stop]) * .5f,
                    first.recognizedLeadingDigit && second.recognizedLeadingDigit));
        }
        return result;
    }

    /** A bounded two-bar span whose two lobes, dot train and return hook are all printed. */
    static List<Ribbon> readAcrossBars(byte[] raw, byte[] normalized, int width, int height,
            List<PlayingTechniqueDetector.Staff> staffs, List<MeasureRegion> bars, List<ScoreNoteEvent> notes) {
        if (raw == null || normalized == null || raw.length != (long) width * height
                || raw.length != normalized.length) return List.of();
        var byBar = new ArrayList<List<ScoreNoteEvent>>(bars.size());
        for (var ignored : bars) byBar.add(new ArrayList<>());
        for (var n : notes) if (n.measureIndex() >= 0 && n.measureIndex() < bars.size())
            byBar.get(n.measureIndex()).add(n);
        var result = new ArrayList<Ribbon>();
        for (int i = 1; i < bars.size(); i++) {
            var first = bars.get(i - 1);
            var last = bars.get(i);
            if (Math.abs(first.top() - last.top()) * height > 5 || first.right() >= last.right()
                    || first.left() >= last.left() || byBar.get(i - 1).size() < 12 || byBar.get(i).size() < 12)
                continue;
            float gap = (first.bottom() - first.top()) * height / 8.2f;
            if (gap < 12 || gap > 24 || Math.abs(first.right() - last.left()) * width > 1.45f * gap) continue;
            var staff = staffs.stream().filter(st -> st.count() == 1 && st.top() >= first.top() * height
                    && st.bottom() <= first.bottom() * height && Math.abs(st.gap() - gap) < .12f * gap)
                    .findFirst().orElse(null);
            if (staff == null) continue;
            var heads = new ArrayList<LocatedHead>();
            locate(byBar.get(i - 1), first, width, heads);
            locate(byBar.get(i), last, width, heads);
            var columns = chordColumns(heads, gap);
            if (columns.size() < 12 || columns.stream().filter(c -> c.size() == 2).count() < columns.size() * .70f)
                continue;
            float line = (first.right() + last.left()) * width * .5f;
            int left = Math.round(Math.max(first.left() * width, line - 36 * gap));
            int right = Math.round(Math.min(last.right() * width, line + 34 * gap));
            var a = evidence(raw, width, height, staff, left, right, gap, List.of(), RibbonScan.CROSS_BAR, line);
            var b = evidence(normalized, width, height, staff, left, right, gap, List.of(), RibbonScan.CROSS_BAR, line);
            if (a == null || b == null || Math.abs(a.row - b.row) > 1
                    || Math.abs(a.numeralX - b.numeralX) > 2 || Math.abs(a.hookX - b.hookX) > 2) continue;
            int start = closestOwner(columns, (a.numeralX + b.numeralX) * .5f, gap);
            int end = 0;
            float hook = Math.min(a.hookX, b.hookX);
            while (end < columns.size() && columnX(columns.get(end)) < hook) end++;
            if (start < 1 || end >= columns.size() || end - start < 7 || end - start > 15) continue;
            float opened = columnX(columns.get(start)), closed = columnX(columns.get(end - 1));
            float following = columnX(columns.get(end)), before = columnX(columns.get(start - 1));
            if (opened >= line || closed <= line || hook - closed < .30f * gap
                    || following - hook < .53f * gap || hook - closed > 1.55f * gap
                    || following - hook > 2.0f * gap || opened - before < 1.55f * gap) continue;
            if (!octaveColumns(columns.subList(start, end), a.row, height, gap)) continue;
            result.add(new Ribbon(staff, (before + opened) * .5f, (closed + following) * .5f, true));
        }
        return result;
    }

    static List<Ribbon> readCompactBars(byte[] raw, byte[] normalized, int width, int height,
            List<PlayingTechniqueDetector.Staff> staffs, List<MeasureRegion> bars, List<ScoreNoteEvent> notes, List<PlayingTechniqueDetector.Word> words) {
        if (raw == null || normalized == null || raw.length != (long) width * height
                || raw.length != normalized.length) return List.of();
        var byBar = new ArrayList<List<ScoreNoteEvent>>(bars.size());
        for (var ignored : bars) byBar.add(new ArrayList<>());
        for (var n : notes) if (n.measureIndex() >= 0 && n.measureIndex() < bars.size())
            byBar.get(n.measureIndex()).add(n);
        var result = new ArrayList<Ribbon>();
        for (int index = 0; index < bars.size(); index++) {
            var bar = bars.get(index);
            if (byBar.get(index).size() < 15) continue;
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (gap < 12 || gap > 24) continue;
            var staff = staffs.stream().filter(st -> st.count() == 1 && st.top() >= bar.top() * height
                    && st.bottom() <= bar.bottom() * height && Math.abs(st.gap() - gap) < .12f * gap)
                    .findFirst().orElse(null);
            if (staff == null) continue;
            var heads = new ArrayList<LocatedHead>();
            locate(byBar.get(index), bar, width, heads);
            var columns = chordColumns(heads, gap);
            if (columns.size() < 8 || columns.stream().filter(col -> col.size() == 2).count() < columns.size() * .82f)
                continue;
            int left = Math.round(bar.left() * width - 1.0f * gap);
            int right = Math.round(bar.right() * width - .8f * gap);
            var a = evidence(raw, width, height, staff, left, right, gap, words, RibbonScan.COMPACT, 0);
            var b = evidence(normalized, width, height, staff, left, right, gap, words, RibbonScan.COMPACT, 0);
            if (a == null || b == null || Math.abs(a.row - b.row) > 1
                    || Math.abs(a.numeralX - b.numeralX) > 2 || Math.abs(a.hookX - b.hookX) > 2) continue;
            int start = closestOwner(columns, (a.numeralX + b.numeralX) * .5f, gap);
            float numeral = (a.numeralX + b.numeralX) * .5f;
            if (start < 0 && columnX(columns.get(0)) - numeral >= .75f * gap
                    && columnX(columns.get(0)) - numeral <= 2.3f * gap) start = 0;
            if (start < 0) continue;
            int end = start;
            float hook = Math.min(a.hookX, b.hookX);
            while (end < columns.size() && columnX(columns.get(end)) < hook) end++;
            if (end - start < 3 || end - start > 5 || end >= columns.size()) continue;
            float opened = columnX(columns.get(start)), last = columnX(columns.get(end - 1));
            float following = columnX(columns.get(end));
            if (hook - last < .28f * gap || hook - last > 1.55f * gap
                    || following - hook < .45f * gap || following - hook > 2.10f * gap
                    || !octaveColumns(columns.subList(start, end), a.row, height, gap)) continue;
            float before = start == 0 ? opened - .60f * gap : columnX(columns.get(start - 1));
            result.add(new Ribbon(staff, (before + opened) * .5f, (last + following) * .5f, true));
        }
        return result;
    }

    static List<Ribbon> readSoloReturns(byte[] raw, byte[] normalized, int width, int height,
            List<PlayingTechniqueDetector.Staff> staffs, List<MeasureRegion> bars, List<ScoreNoteEvent> notes) {
        if (raw == null || normalized == null || raw.length != (long) width * height
                || raw.length != normalized.length) return List.of();
        var byBar = new ArrayList<List<ScoreNoteEvent>>(bars.size());
        for (var ignored : bars) byBar.add(new ArrayList<>());
        for (var n : notes) if (n.measureIndex() >= 0 && n.measureIndex() < bars.size())
            byBar.get(n.measureIndex()).add(n);
        var result = new ArrayList<Ribbon>();
        for (int i = 0; i < bars.size(); i++) {
            var bar = bars.get(i);
            if (byBar.get(i).size() < 12) continue;
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (gap < 12 || gap > 23) continue;
            var staff = staffOf(staffs, bar, height, gap);
            if (staff == null) continue;
            var heads = new ArrayList<LocatedHead>();
            locate(byBar.get(i), bar, width, heads);
            var columns = chordColumns(heads, gap);
            if (columns.stream().filter(col -> col.size() == 1).count() < 12) continue;
            if (i > 0 && (bar.top() - bars.get(i - 1).bottom()) * height >= 4.2f * gap
                    && (bars.get(i - 1).left() - bar.left()) * width >= 15 * gap) {
                var entry = closedSoloEntry(raw, normalized, width, height, staff, bar, columns, gap);
                if (entry != null) {
                    result.add(entry);
                    continue;
                }
            }
            int l = Math.round(bar.left() * width + 1.1f * gap);
            int r = Math.round(bar.right() * width - 1.1f * gap);
            var a = evidence(raw, width, height, staff, l, r, gap, List.of(), RibbonScan.SOLO, 0);
            var b = evidence(normalized, width, height, staff, l, r, gap, List.of(), RibbonScan.SOLO, 0);
            if (a == null || b == null || Math.abs(a.row - b.row) > 1
                    || Math.abs(a.numeralX - b.numeralX) > 2 || Math.abs(a.hookX - b.hookX) > 2) continue;
            int start = soloStartOwner(columns, (a.numeralX + b.numeralX) * .5f, gap);
            if (start < 1) continue;
            float hook = Math.min(a.hookX, b.hookX);
            int end = start;
            while (end < columns.size() && columnX(columns.get(end)) < hook) end++;
            if (end - start < 6 || end - start > 9 || end >= columns.size()) continue;
            float opened = columnX(columns.get(start)), last = columnX(columns.get(end - 1));
            float following = columnX(columns.get(end));
            if (hook - last < .28f * gap || hook - last > 1.4f * gap
                    || following - hook < .72f * gap || following - hook > 2.15f * gap
                    || !closedSoloNoteRun(columns.subList(start, end), a.row, height, gap)) continue;
            result.add(new Ribbon(staff, (columnX(columns.get(start - 1)) + opened) * .5f,
                    (last + following) * .5f, true, (a.numeralX + b.numeralX) * .5f, (a.row + b.row) * .5f));
        }
        return result;
    }

    private static int soloStartOwner(List<List<LocatedHead>> columns, float numeral, float gap) {
        for (int i = 1; i < columns.size(); i++) {
            float head = columnX(columns.get(i));
            if (head < numeral - .25f * gap) continue;
            float preceding = columnX(columns.get(i - 1));
            return head - numeral <= 1.24f * gap && numeral - preceding >= .48f * gap ? i : -1;
        }
        return -1;
    }

    private static boolean closedSoloNoteRun(List<List<LocatedHead>> columns, int row, int height, float gap) {
        var first = columns.get(0).get(0).note;
        float x = Float.NEGATIVE_INFINITY;
        int unknownStem = 0;
        int previousBeamCount = -1;
        for (var entry : columns) {
            if (entry.size() != 1) return false;
            var head = entry.get(0);
            var n = head.note;
            float clearance = n.pageY() * height - row;
            if (n.stemDirection() > 0 || n.stemDirection() == 0 && ++unknownStem > 1
                    || previousBeamCount != -1 && n.beamCount() != previousBeamCount
                    || n.clefBottomDiatonic() != first.clefBottomDiatonic()
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL || n.openStringTouch() != null
                    || n.harmonicSemitones() != 0 || n.beamCount() < 1 || n.beamCount() > 2
                    || clearance < .82f * gap || clearance > 7.8f * gap
                    || head.x - x < 1.25f * gap || Float.isFinite(x) && head.x - x > 4.0f * gap) return false;
            x = head.x;
            previousBeamCount = n.beamCount();
        }
        return true;
    }

    static List<Ribbon> readSoloAcrossBars(byte[] raw, byte[] normalized, int width, int height,
            List<PlayingTechniqueDetector.Staff> staffs, List<MeasureRegion> bars, List<ScoreNoteEvent> notes) {
        if (!hasTwoPlanes(raw, normalized, width, height)) return List.of();
        var result = new ArrayList<Ribbon>();
        for (int index = 1; index < bars.size(); index++) {
            var first = bars.get(index - 1);
            var second = bars.get(index);
            float gap = (first.bottom() - first.top()) * height / 8.2f;
            if (gap < 12 || gap > 23 || Math.abs(first.top() - second.top()) * height > 5
                    || second.left() <= first.left() || second.right() <= first.right()
                    || Math.abs(first.right() - second.left()) * width > 1.45f * gap) continue;
            var staff = staffOf(staffs, first, height, gap);
            if (staff == null) continue;
            var leftColumns = soloColumns(notes, first, index - 1, width, gap);
            var rightColumns = soloColumns(notes, second, index, width, gap);
            if (leftColumns.size() < 8 || rightColumns.size() < 6) continue;
            float barline = (first.right() + second.left()) * width * .5f;
            int left = Math.round(first.left() * width + gap);
            int right = Math.round(second.right() * width - gap);
            var ink = agreedEvidence(raw, normalized, width, height, staff, left, right, gap,
                    RibbonScan.SOLO_CROSS, barline);
            if (ink == null) continue;
            var columns = new ArrayList<>(leftColumns);
            columns.addAll(rightColumns);
            int start = soloStartOwner(columns, ink.numeralX, gap);
            int stop = firstAfterHook(columns, ink.hookX);
            if (start < 1 || start >= leftColumns.size() - 2 || stop <= leftColumns.size() + 1
                    || stop - start < 6 || stop - start > 16
                    || !confirmedSoloClose(columns, start, stop, ink, height, gap)) continue;
            result.add(closedRibbon(staff, columns, start, stop));
        }
        return result;
    }

    static List<SoloWrap> readSoloWraps(byte[] raw, byte[] normalized, int width, int height,
            List<PlayingTechniqueDetector.Staff> staffs, List<MeasureRegion> bars, List<ScoreNoteEvent> notes) {
        if (!hasTwoPlanes(raw, normalized, width, height)) return List.of();
        var result = new ArrayList<SoloWrap>();
        for (int index = 1; index < bars.size(); index++) {
            var first = bars.get(index - 1);
            var second = bars.get(index);
            float gap = (first.bottom() - first.top()) * height / 8.2f;
            if (gap < 12 || gap > 23 || (second.top() - first.bottom()) * height < 4.2f * gap
                    || (first.left() - second.left()) * width < 15 * gap) continue;
            var outgoing = staffOf(staffs, first, height, gap);
            var incoming = staffOf(staffs, second, height, gap);
            if (outgoing == null || incoming == null || incoming.top() <= outgoing.bottom()) continue;
            var left = soloColumns(notes, first, index - 1, width, gap);
            var right = soloColumns(notes, second, index, width, gap);
            if (left.size() < 8 || right.size() < 7) continue;
            float edge = first.right() * width;
            var startInk = agreedEvidence(raw, normalized, width, height, outgoing,
                    Math.round(first.left() * width + gap), Math.round(edge + .56f * gap), gap,
                    RibbonScan.SOLO_OUTGOING, edge);
            if (startInk == null) continue;
            int start = soloStartOwner(left, startInk.numeralX, gap);
            if (start < 1 || left.size() - start < 3 || left.size() - start > 12
                    || edge - columnX(left.get(left.size() - 1)) > 3.4f * gap
                    || !closedSoloNoteRun(left.subList(start, left.size()), startInk.row, height, gap)) continue;
            var entry = closedSoloEntry(raw, normalized, width, height, incoming, second, right, gap);
            if (entry == null) continue;
            float opened = (columnX(left.get(start - 1)) + columnX(left.get(start))) * .5f;
            result.add(new SoloWrap(new Ribbon(outgoing, opened, edge, true), entry));
        }
        return result;
    }

    private static Ribbon closedSoloEntry(byte[] raw, byte[] normalized, int width, int height,
            PlayingTechniqueDetector.Staff staff, MeasureRegion bar,
            List<List<LocatedHead>> columns, float gap) {
        if (columns.size() < 7 || columns.stream().anyMatch(column -> column.size() != 1)) return null;
        float first = columnX(columns.get(0));
        var ink = agreedEvidence(raw, normalized, width, height, staff,
                Math.round(first - 4.2f * gap), Math.round(bar.right() * width - gap),
                gap, RibbonScan.SOLO_REPEAT, 0);
        if (ink == null || first - ink.numeralX < .75f * gap || first - ink.numeralX > 2.6f * gap)
            return null;
        int stop = firstAfterHook(columns, ink.hookX);
        if (stop < 3 || stop > 12 || !confirmedSoloClose(columns, 0, stop, ink, height, gap)) return null;
        float end = (columnX(columns.get(stop - 1)) + columnX(columns.get(stop))) * .5f;
        return new Ribbon(staff, bar.left() * width, end, true, ink.numeralX, ink.row);
    }

    private static boolean hasTwoPlanes(byte[] raw, byte[] normalized, int width, int height) {
        return raw != null && normalized != null && raw.length == (long) width * height
                && normalized.length == raw.length;
    }

    private static List<List<LocatedHead>> soloColumns(
            List<ScoreNoteEvent> notes, MeasureRegion bar, int index, int width, float gap) {
        var heads = new ArrayList<LocatedHead>();
        locate(notes.stream().filter(note -> note.measureIndex() == index).toList(), bar, width, heads);
        var columns = chordColumns(heads, gap);
        return columns.stream().allMatch(column -> column.size() == 1) ? columns : List.of();
    }

    private static Evidence agreedEvidence(byte[] raw, byte[] normalized, int width, int height,
            PlayingTechniqueDetector.Staff staff, int left, int right, float gap, RibbonScan mode, float boundary) {
        var a = evidence(raw, width, height, staff, left, right, gap, List.of(), mode, boundary);
        var b = evidence(normalized, width, height, staff, left, right, gap, List.of(), mode, boundary);
        if (a == null || b == null || Math.abs(a.row - b.row) > 1
                || Math.abs(a.numeralX - b.numeralX) > 2 || Math.abs(a.hookX - b.hookX) > 2) return null;
        return a;
    }

    private static int firstAfterHook(List<List<LocatedHead>> columns, float hook) {
        int stop = 0;
        while (stop < columns.size() && columnX(columns.get(stop)) < hook) stop++;
        return stop;
    }

    private static boolean confirmedSoloClose(List<List<LocatedHead>> columns, int start, int stop,
            Evidence ink, int height, float gap) {
        if (stop <= start || stop >= columns.size()) return false;
        float afterLast = ink.hookX - columnX(columns.get(stop - 1));
        float beforeNext = columnX(columns.get(stop)) - ink.hookX;
        return afterLast >= .26f * gap && afterLast <= 1.55f * gap
                && beforeNext >= .52f * gap && beforeNext <= 2.5f * gap
                && closedSoloNoteRun(columns.subList(start, stop), ink.row, height, gap);
    }

    private static Ribbon closedRibbon(PlayingTechniqueDetector.Staff staff,
            List<List<LocatedHead>> columns, int start, int stop) {
        return new Ribbon(staff, (columnX(columns.get(start - 1)) + columnX(columns.get(start))) * .5f,
                (columnX(columns.get(stop - 1)) + columnX(columns.get(stop))) * .5f, true);
    }

    static List<Ribbon> readWrappedStarts(byte[] raw, byte[] normalized, int w, int h,
            List<PlayingTechniqueDetector.Staff> staffs, List<MeasureRegion> bars, List<ScoreNoteEvent> notes, List<PlayingTechniqueDetector.Word> words) {
        if (raw == null || normalized == null || raw.length != (long) w * h
                || raw.length != normalized.length) return List.of();
        var result = new ArrayList<Ribbon>();
        for (int i = 1; i < bars.size(); i++) {
            var last = bars.get(i - 1);
            var next = bars.get(i);
            float gap = (last.bottom() - last.top()) * h / 8.2f;
            float edge = last.right() * w;
            if (gap < 12 || gap > 24 || (next.top() - last.bottom()) * h < 4.2f * gap
                    || (last.left() - next.left()) * w < 15 * gap) continue;
            var outgoing = staffOf(staffs, last, h, gap);
            var incoming = staffOf(staffs, next, h, gap);
            if (outgoing == null || incoming == null || incoming.top() <= outgoing.bottom()) continue;
            var leftHeads = new ArrayList<LocatedHead>();
            var rightHeads = new ArrayList<LocatedHead>();
            int leftIndex = i - 1, rightIndex = i;
            locate(notes.stream().filter(n -> n.measureIndex() == leftIndex).toList(), last, w, leftHeads);
            locate(notes.stream().filter(n -> n.measureIndex() == rightIndex).toList(), next, w, rightHeads);
            var leftColumns = chordColumns(leftHeads, gap);
            var rightColumns = chordColumns(rightHeads, gap);
            if (leftColumns.size() < 8 || rightColumns.size() < 9) continue;
            int left = Math.round(last.left() * w);
            int right = Math.min(w - 1, Math.round(edge + .68f * gap));
            var a = evidence(raw, w, h, outgoing, left, right, gap, List.of(), RibbonScan.OUTGOING, edge);
            var b = evidence(normalized, w, h, outgoing, left, right, gap, List.of(), RibbonScan.OUTGOING, edge);
            if (a == null || b == null || Math.abs(a.row - b.row) > 1
                    || Math.abs(a.numeralX - b.numeralX) > 2) continue;
            int start = closestOwner(leftColumns, (a.numeralX + b.numeralX) * .5f, gap);
            if (start < 1 || leftColumns.size() - start < 7
                    || !octaveColumns(leftColumns.subList(start, leftColumns.size()), a.row, h, gap)
                    || edge - columnX(leftColumns.get(leftColumns.size() - 1)) > 3.8f * gap) continue;
            float nextX = columnX(rightColumns.get(0));
            int nextLeft = Math.round(nextX - 4.2f * gap);
            int nextRight = Math.round(next.right() * w - .5f * gap);
            var c = evidence(raw, w, h, incoming, nextLeft, nextRight, gap, words, RibbonScan.REPEATED, 0);
            var d = evidence(normalized, w, h, incoming, nextLeft, nextRight, gap, words, RibbonScan.REPEATED, 0);
            if (c == null || d == null || Math.abs(c.numeralX - d.numeralX) > 2
                    || Math.abs(c.hookX - d.hookX) > 2 || Math.abs(c.row - d.row) > 1
                    || nextX - c.numeralX < .55f * gap || nextX - c.numeralX > 2.5f * gap) continue;
            int stop = 0;
            while (stop < rightColumns.size() && columnX(rightColumns.get(stop)) < c.hookX) stop++;
            if (stop < 7 || stop >= rightColumns.size()
                    || !octaveColumns(rightColumns.subList(0, stop), c.row, h, gap)) continue;
            result.add(new Ribbon(outgoing,
                    (columnX(leftColumns.get(start - 1)) + columnX(leftColumns.get(start))) * .5f,
                    edge, true));
        }
        return result;
    }

    private static PlayingTechniqueDetector.Staff staffOf(List<PlayingTechniqueDetector.Staff> staffs,
            MeasureRegion bar, int height, float gap) {
        return staffs.stream().filter(st -> st.count() == 1 && st.top() >= bar.top() * height
                && st.bottom() <= bar.bottom() * height && Math.abs(st.gap() - gap) < .12f * gap)
                .findFirst().orElse(null);
    }

    private static List<List<LocatedHead>> chordColumns(List<LocatedHead> heads, float gap) {
        heads.sort(Comparator.comparingDouble(LocatedHead::x));
        var columns = new ArrayList<List<LocatedHead>>();
        for (int j = 0; j < heads.size();) {
            int end = j + 1;
            while (end < heads.size() && heads.get(end).x - heads.get(j).x <= .27f * gap) end++;
            columns.add(heads.subList(j, end));
            j = end;
        }
        return columns;
    }

    private record LocatedHead(float x, ScoreNoteEvent note) {}

    private static void locate(List<ScoreNoteEvent> notes, MeasureRegion bar, int width, List<LocatedHead> heads) {
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        for (var n : notes) {
            if (n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffCount() == 1)
                heads.add(new LocatedHead(left + span * n.positionInMeasure(), n));
        }
    }

    private static float columnX(List<LocatedHead> column) {
        return (column.get(0).x + column.get(column.size() - 1).x) * .5f;
    }

    private static int closestOwner(List<List<LocatedHead>> columns, float x, float gap) {
        int found = -1;
        for (int i = 0; i < columns.size(); i++) {
            if (Math.abs(columnX(columns.get(i)) - x) > .90f * gap) continue;
            if (found != -1) return -1;
            found = i;
        }
        return found;
    }

    private static boolean octaveColumns(List<List<LocatedHead>> columns, int row, int height, float gap) {
        int exact = 0;
        for (var column : columns) {
            if (column.size() != 2) return false;
            var a = column.get(0).note;
            var b = column.get(1).note;
            int interval = Math.abs(a.staffStep() - b.staffStep());
            float separation = Math.abs(a.pageY() - b.pageY()) * height;
            if (interval < 6 || interval > 8 || a.clefBottomDiatonic() != b.clefBottomDiatonic()
                    || Math.min(a.pageY(), b.pageY()) * height < row + .85f * gap
                    || Math.abs(separation - 3.5f * gap) > .45f * gap) return false;
            if (interval == 7) exact++;
        }
        return exact >= columns.size() * .75f;
    }

    private static int ownerAfter(float[] columns, int edge) {
        int owner = 0;
        while (owner < columns.length && columns[owner] <= edge) owner++;
        return owner;
    }

    private static boolean soloMode(RibbonScan mode) {
        return mode == RibbonScan.SOLO || mode == RibbonScan.SOLO_CROSS
                || mode == RibbonScan.SOLO_OUTGOING || mode == RibbonScan.SOLO_REPEAT;
    }

    private static Evidence evidence(byte[] image, int width, int height,
            PlayingTechniqueDetector.Staff staff, float[] owners, float gap, List<PlayingTechniqueDetector.Word> words) {
        int left = Math.round(owners[0] + gap);
        int right = Math.round(owners[11] - gap);
        return evidence(image, width, height, staff, left, right, gap, words, RibbonScan.SINGLE, 0);
    }

    private static Evidence evidence(byte[] image, int width, int height,
            PlayingTechniqueDetector.Staff staff, int left, int right, float gap,
            List<PlayingTechniqueDetector.Word> words, RibbonScan mode, float boundary) {
        var ordinary = scanRibbon(image, width, height, staff, left, right, gap, words, mode, boundary, 165);
        if (ordinary != null || mode != RibbonScan.SOLO_REPEAT) return ordinary;
        // A lower threshold separates neighboring dots joined by faint ink.
        return scanRibbon(image, width, height, staff, left, right, gap, words, mode, boundary, 153);
    }

    private static Evidence scanRibbon(byte[] image, int width, int height,
            PlayingTechniqueDetector.Staff staff, int left, int right, float gap,
            List<PlayingTechniqueDetector.Word> words, RibbonScan mode, float boundary, int threshold) {
        int from = Math.max(0, Math.round(staff.top() - gap * (soloMode(mode) ? 7.9f : 6.6f)));
        int through = Math.min(height - 1, Math.round(staff.top() - gap * 2.8f));
        if (left < 0 || right >= width || through <= from) return null;
        Evidence best = null;
        boolean compact = mode == RibbonScan.COMPACT;
        from = Math.max(compact ? 1 : 0, from);
        through = Math.min(height - (compact ? 2 : 1), through);
        for (int y = from; y <= through; y++) {
            var series = new ArrayList<Dot>();
            for (int x = left; x <= right;) {
                if (!dashInk(image, width, x, y, compact, threshold)) {
                    x++;
                    continue;
                }
                int start = x++;
                while (x <= right && dashInk(image, width, x, y, compact, threshold)) x++;
                var dot = new Dot(start, x - 1);
                int breadth = x - start;
                if (breadth < gap * .16f || breadth > gap * (compact || soloMode(mode) ? .57f : .49f)) continue;
                float step = series.isEmpty() ? 0 : dot.center() - series.get(series.size() - 1).center();
                // In the crossed arm one dash can join the slur and be lost from this row.
                if (!series.isEmpty() && !(step >= .43f * gap && step <= .67f * gap
                        || step >= .88f * gap && step <= (soloMode(mode) ? 2.82f : 1.85f) * gap)) {
                    best = best(best, (mode != RibbonScan.SINGLE ? finishMeasuredRibbon(image, width, height, y, gap, series, mode, boundary, words)
                            : finish(image, width, height, y, gap, series, words)));
                    series.clear();
                }
                series.add(dot);
            }
            best = best(best, (mode != RibbonScan.SINGLE ? finishMeasuredRibbon(image, width, height, y, gap, series, mode, boundary, words)
                            : finish(image, width, height, y, gap, series, words)));
        }
        return best;
    }

    private static boolean dashInk(byte[] plane, int w, int x, int y, boolean compact, int threshold) {
        return (plane[y * w + x] & 255) < threshold || compact
                && dark(plane, w, x, y - 1) && dark(plane, w, x, y + 1);
    }

    private static Evidence best(Evidence first, Evidence second) {
        return second != null && (first == null || second.count > first.count) ? second : first;
    }

    private static Evidence finishMeasuredRibbon(byte[] image, int width, int height, int row,
            float gap, List<Dot> series, RibbonScan mode, float boundary,
            List<PlayingTechniqueDetector.Word> words) {
        boolean compact = mode == RibbonScan.COMPACT;
        boolean solo = soloMode(mode);
        boolean staffEdge = mode == RibbonScan.OUTGOING || mode == RibbonScan.SOLO_OUTGOING;
        boolean nextStaff = mode == RibbonScan.REPEATED;
        int min = switch (mode) {
            case SOLO_OUTGOING, SOLO_REPEAT -> 9;
            case COMPACT -> 10;
            case SOLO -> 16;
            case SOLO_CROSS -> 24;
            default -> 29;
        };
        int max = switch (mode) {
            case COMPACT -> 25;
            case SOLO -> 36;
            case SOLO_CROSS, SOLO_OUTGOING, SOLO_REPEAT -> 64;
            default -> Integer.MAX_VALUE;
        };
        if (series.size() < min || series.size() > max) return null;
        if ((mode == RibbonScan.CROSS_BAR || mode == RibbonScan.SOLO_CROSS) && series.get(0).center() > boundary - 3 * gap) return null;
        var last = series.get(series.size() - 1);
        if ((mode == RibbonScan.CROSS_BAR || mode == RibbonScan.SOLO_CROSS) && last.center() < boundary + 3 * gap) return null;
        if (staffEdge && Math.abs(last.last - boundary) > .6f * gap) return null;
        int ordinarySteps = 0;
        for (int i = 1; i < series.size(); i++) {
            float step = series.get(i).center() - series.get(i - 1).center();
            if (step >= .43f * gap && step <= .67f * gap) ordinarySteps++;
        }
        if (!compact && !solo && ordinarySteps < (series.size() - 1) * .91f) return null;
        int numeral = -1;
        int tries = compact || solo || staffEdge || nextStaff ? Math.min(3, series.size()) : 1;
        for (int i = 0; i < tries && numeral < 0; i++) {
            int first = series.get(i).first;
            numeral = acrossBarEight(image, width, height, first, row, gap, compact || solo);
            if ((compact || solo || staffEdge || nextStaff) && numeral < 0) {
                for (int d = -Math.round(.44f * gap); d < 0 && numeral < 0; d++)
                    numeral = acrossBarEight(image, width, height, first + d, row, gap, true);
            }
        }
        if ((compact || nextStaff) && numeral < 0) numeral = openEightWithText(image, width, height,
                series.get(0).first, row, gap, words, nextStaff);
        if (numeral < 0) return null;
        if (staffEdge) {
            if (solo && !compactCadence(series, series.size() - 1, gap, true)) return null;
            return new Evidence(row, numeral, last.last, series.size(), true);
        }
        int lastIndex = series.size() - 1;
        int candidates = solo ? Math.min(6, series.size() - min + 1) : compact ? Math.min(4, series.size() - 9) : 1;
        for (int offset = 0; offset < candidates; offset++) {
            int i = solo ? lastIndex - candidates + 1 + offset : lastIndex - offset;
            if ((compact || solo) && !compactCadence(series, i, gap, solo)) continue;
            if (solo && series.get(i).center() - series.get(i - 1).center() > .78f * gap) continue;
            var tail = series.get(i);
            for (int x = tail.first; x <= tail.last + .44f * gap && x + 1 < width; x++) {
                if (dottedReturn(image, width, height, x, row, gap, compact || nextStaff, solo)
                        || solo && interruptedSoloReturn(image, width, height, x, row, gap))
                    return new Evidence(row, numeral, x, i + 1, true);
            }
        }
        return null;
    }

    private static boolean compactCadence(List<Dot> series, int end, float gap, boolean solo) {
        int steps = 0;
        for (int i = 1; i <= end; i++) {
            float distance = series.get(i).center() - series.get(i - 1).center();
            if (distance >= .41f * gap && distance <= .69f * gap) steps++;
        }
        return steps >= end * (solo ? .79f : .85f);
    }

    private static boolean dottedReturn(
            byte[] image, int width, int height, int x, int row, float gap, boolean compact, boolean solo) {
        int end = row + Math.round(2.22f * gap);
        if (end >= height) return false;
        int first = -1, last = -1, ink = 0, runs = 0;
        boolean previous = false;
        for (int y = row; y <= end; y++) {
            boolean present = dark(image, width, x, y) && dark(image, width, x + 1, y);
            if (present) {
                if (first < 0) first = y;
                if (!previous) runs++;
                last = y;
                ink++;
            } else if (last >= 0 && y - last > .41f * gap) break;
            previous = present;
        }
        return first >= row && first - row <= (solo ? .56f : compact ? .44f : .40f) * gap
                && last - row >= (compact ? .65f : 1.07f) * gap
                && ink >= (solo ? .70f : compact ? .43f : .91f) * gap && runs >= (compact || solo ? 2 : 3) && runs <= 6;
    }

    private static boolean interruptedSoloReturn(
            byte[] image, int width, int height, int x, int row, float gap) {
        int left = x - Math.round(.30f * gap), right = x + Math.round(.30f * gap);
        int top = row + Math.round(.25f * gap), bottom = row + Math.round(1.88f * gap);
        if (left < 0 || right >= width || bottom >= height) return false;
        int lastCenter = -1;
        for (int y = top; y <= bottom;) {
            int first = y;
            while (y <= bottom && narrowHookInk(image, width, y, x, left, right)) y++;
            int count = y - first;
            if (count >= 2 && count <= Math.round(.37f * gap)) {
                int center = (first + y - 1) / 2;
                if (lastCenter >= 0 && center - row >= .96f * gap) {
                    float spacing = (center - lastCenter) / gap;
                    if (spacing >= .36f && spacing <= 1.12f) return true;
                }
                lastCenter = center;
            }
            y++;
        }
        return false;
    }

    private static boolean narrowHookInk(byte[] image, int width, int row, int x, int left, int right) {
        if (dark(image, width, left, row) || dark(image, width, right, row)) return false;
        return dark(image, width, x, row) || dark(image, width, x + 1, row);
    }

    private static int openEightWithText(byte[] image, int width, int height, int firstDot, int row,
            float gap, List<PlayingTechniqueDetector.Word> words, boolean nextStaff) {
        if (words == null) return -1;
        int owned = -1;
        for (var word : words) {
            if (!word.text().strip().equals("8")) continue;
            int l = Math.round(word.left() * width), r = Math.round(word.right() * width);
            int t = Math.round(word.top() * height), b = Math.round(word.bottom() * height);
            if (l < 0 || r >= width || t < 0 || b >= height
                    || firstDot - l < 1.15f * gap || firstDot - l > 2.75f * gap
                    || r < firstDot - .5f * gap || r > firstDot + (nextStaff ? 1.2f : .55f) * gap
                    || row - t < .42f * gap || row - t > 1.15f * gap
                    || b - row < 1.30f * gap || b - row > 2.18f * gap) continue;
            var counters = whiteCounters(image, width, l, t, r, b, 205).stream()
                    .filter(c -> c.area >= .09f * gap * gap).toList();
            if (counters.size() != 1 || counters.get(0).y < row + .65f * gap
                    || counters.get(0).y > row + 1.25f * gap) continue;
            if (owned >= 0) return -1;
            owned = firstDot - Math.round(.87f * gap);
        }
        return owned;
    }

    private static int acrossBarEight(byte[] image, int width, int height, int firstDot, int row, float gap, boolean compact) {
        int left = firstDot - Math.round((compact ? 1.85f : 1.61f) * gap);
        int right = firstDot + Math.round(.24f * gap);
        int top = row - Math.round(.63f * gap), bottom = row + Math.round((compact ? 1.82f : 1.66f) * gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return -1;
        var counters = whiteCounters(image, width, left, top, right, bottom, 205).stream()
                .filter(counter -> counter.area >= .08f * gap * gap).toList();
        if (counters.size() != 2) return -1;
        var sorted = counters.stream().sorted(Comparator.comparingDouble(Counter::y)).toList();
        var upper = sorted.get(0);
        var lower = sorted.get(1);
        if (upper.y < row - .20f * gap || upper.y > row + .36f * gap
                || lower.y < row + .60f * gap || lower.y > row + 1.37f * gap
                || upper.area < .10f * gap * gap || lower.area < .10f * gap * gap) return -1;
        return firstDot - Math.round(.62f * gap);
    }

    private static Evidence finish(byte[] image, int width, int height, int row, float gap, List<Dot> series, List<PlayingTechniqueDetector.Word> words) {
        if (series.size() < 15) return null;
        int regular = 0;
        for (int i = 1; i < series.size(); i++)
            if (series.get(i).center() - series.get(i - 1).center() <= gap * .67f) regular++;
        if (regular < (series.size() - 1) * .82f) return null;
        var last = series.get(series.size() - 1);
        for (int hook = last.first; hook <= last.last + .53f * gap && hook + 1 < width; hook++) {
            if (!downHook(image, width, height, hook, row, gap)
                    || !downHook(image, width, height, hook + 1, row, gap)) continue;
            // The long vertical return may itself be read as the last horizontal dot.
            int first = series.get(0).first;
            int numeral = numeral(image, width, height, first, row, gap);
            if (numeral < 0 && series.get(1).first - first < .66f * gap)
                numeral = numeral(image, width, height, series.get(1).first, row, gap);
            if (numeral < 0) continue;
            if (numeral >= 0) return new Evidence(row, numeral, hook, series.size(), false);
        }
        if (series.size() >= 19) {
            int numeral = recognizedSeriesStart(image, width, height, row, gap, series, words);
            if (numeral >= 0) {
                for (int hook = last.first; hook <= last.last + .53f * gap && hook + 1 < width; hook++) {
                    for (int offset = 0; offset <= Math.round(.18f * gap); offset++) {
                        if (downHook(image, width, height, hook, row + offset, gap)
                                && downHook(image, width, height, hook + 1, row + offset, gap))
                            return new Evidence(row, numeral, hook, series.size(), true);
                    }
                }
            }
        }
        return null;
    }

    private static int recognizedSeriesStart(byte[] image, int width, int height, int row,
            float gap, List<Dot> series, List<PlayingTechniqueDetector.Word> words) {
        var first = series.get(0);
        int numeral = recognizedLeadingEight(image, width, height, first.first, row, gap, words);
        if (numeral >= 0) return numeral;
        // A faint leading dot can start a couple of rows below the continuous series.
        int left = Math.max(0, first.first - Math.round(1.03f * gap));
        int right = first.first - Math.round(.22f * gap);
        for (int y = row + 1; y <= row + Math.round(.16f * gap) && y < height; y++) {
            for (int x = left; x <= right;) {
                if (!dark(image, width, x, y)) {
                    x++;
                    continue;
                }
                int start = x++;
                while (x <= right && dark(image, width, x, y)) x++;
                float cadence = first.center() - (x + start - 1) * .5f;
                if (start == left || x > right || x - start < .15f * gap || x - start > .50f * gap
                        || cadence < .43f * gap || cadence > .67f * gap) continue;
                numeral = recognizedLeadingEight(image, width, height, start, y, gap, words);
                if (numeral >= 0) return numeral;
            }
        }
        return -1;
    }

    private static boolean downHook(byte[] image, int width, int height, int x, int row, float gap) {
        if (row < 0 || row + gap * 2.2f >= height || !dark(image, width, x, row)) return false;
        int count = 0, last = row, missing = 0;
        for (int y = row; y <= row + gap * 2.2f; y++) {
            if (dark(image, width, x, y)) {
                count++;
                last = y;
                missing = 0;
            } else if (++missing > gap * .38f) break;
        }
        return count >= gap && last - row >= gap * 1.08f;
    }

    private static int numeral(byte[] image, int width, int height, int firstDot, int row, float gap) {
        for (int right = firstDot - 1; right >= firstDot - gap * .47f; right--) {
            int top = Math.round(row - gap * .44f), bottom = Math.round(row + gap * 1.46f);
            int left = Math.round(right - gap * 1.38f);
            if (left < 0 || right >= width || top < 0 || bottom >= height) return -1;
            var counters = whiteCounters(image, width, left, top, right, bottom);
            int upper = 0, lower = 0;
            for (var c : counters) {
                if (c.area >= gap * gap * .10f && c.y <= row + gap * .40f) upper++;
                if (c.area >= gap * gap * .045f && c.y >= row + gap * .85f) lower++;
            }
            if (upper == 1 && lower == 1) return (left + right) / 2;
        }
        return -1;
    }

    /** Confirm an engraved eight whose open counters defeat the closed loop reading. */
    static int recognizedLeadingEight(byte[] image, int width, int height, int firstDot, int row,
            float gap, List<PlayingTechniqueDetector.Word> words) {
        if (words == null || words.isEmpty()) return -1;
        PlayingTechniqueDetector.Word owner = null;
        for (var word : words) {
            boolean segue = isSegueEight(word);
            if (!segue && !"8".equals(word.text())
                    || word.left() * width > firstDot - (segue ? 5f : 1f) * gap
                    || word.left() * width < firstDot - (segue ? 9f : 2.65f) * gap
                    || word.right() * width < firstDot - gap * .12f
                    || word.right() * width > firstDot + .65f * gap
                    || word.top() * height > row || word.bottom() * height < row + .62f * gap
                    || (word.bottom() - word.top()) * height > (segue ? 3.6f : 2.8f) * gap) continue;
            if (owner != null) return -1;
            owner = word;
        }
        if (owner == null) return -1;
        int left = Math.round(firstDot - 1.84f * gap);
        int right = firstDot - 1;
        int top = Math.round(row - .76f * gap), bottom = Math.round(row + 1.35f * gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return -1;
        var numerals = RawNumeralComponents.captureLongWindow(image, width, height, left, top, right, bottom);
        int center = -1;
        for (var part : numerals.components()) {
            float breadth = part.right - part.left + 1, depth = part.bottom - part.top + 1;
            if (part.left <= left || part.top <= top || part.bottom >= bottom
                    || firstDot - part.right > .43f * gap || breadth < 1.15f * gap || breadth > 1.65f * gap
                    || depth < 1.32f * gap || depth > 1.85f * gap) continue;
            var fit = JoinedDigitTemplates.inspect(image, width, height, new java.util.BitSet(),
                    part.left, part.top, part.right, part.bottom, () -> {});
            if (fit == null) continue;
            float eight = fit.scores()[8];
            boolean normal = fit.digit() == 8 && eight >= .58f && eight - fit.runner() >= .105f;
            if (!normal && (!isSegueEight(owner) || eight < .62f
                    || !hasPrintedUpperCounter(image, width, part, gap))) continue;
            if (!normal) {
                boolean competing = false;
                for (int digit = 0; digit < fit.scores().length; digit++)
                    if (digit != 8 && eight - fit.scores()[digit] < .038f) competing = true;
                if (competing) continue;
            }
            if (center >= 0) return -1;
            center = (part.left + part.right) / 2;
        }
        return center;
    }

    private static boolean hasPrintedUpperCounter(byte[] image, int width,
            RawNumeralComponents.Component numeral, float gap) {
        float center = (numeral.top + numeral.bottom) * .5f;
        return whiteCounters(image, width, numeral.left, numeral.top, numeral.right, numeral.bottom)
                .stream().filter(c -> c.y < center && c.area >= gap * gap * .065f).count() == 1;
    }

    static boolean isSegueEight(PlayingTechniqueDetector.Word word) {
        return "segue 8".equalsIgnoreCase(word.text().strip());
    }

    private record Counter(int area, float y) {}

    private static List<Counter> whiteCounters(byte[] image, int stride, int left, int top, int right, int bottom) {
        return whiteCounters(image, stride, left, top, right, bottom, 165);
    }

    private static List<Counter> whiteCounters(
            byte[] image, int stride, int left, int top, int right, int bottom, int cutoff) {
        int width = right - left + 1, height = bottom - top + 1;
        boolean[] seen = new boolean[width * height];
        int[] queue = new int[seen.length];
        var result = new ArrayList<Counter>();
        for (int p = 0; p < seen.length; p++) {
            if (seen[p] || (image[(top + p / width) * stride + left + p % width] & 255) < cutoff) continue;
            seen[p] = true;
            queue[0] = p;
            int size = 1, sum = 0;
            boolean boundary = false;
            for (int i = 0; i < size; i++) {
                int x = queue[i] % width, y = queue[i] / width;
                sum += y + top;
                boundary |= x == 0 || x == width - 1 || y == 0 || y == height - 1;
                if (x > 0) size = visit(image, stride, seen, queue, size, x - 1, y, width, left, top, cutoff);
                if (x + 1 < width) size = visit(image, stride, seen, queue, size, x + 1, y, width, left, top, cutoff);
                if (y > 0) size = visit(image, stride, seen, queue, size, x, y - 1, width, left, top, cutoff);
                if (y + 1 < height) size = visit(image, stride, seen, queue, size, x, y + 1, width, left, top, cutoff);
            }
            if (!boundary && size > 1) result.add(new Counter(size, (float) sum / size));
        }
        return result;
    }

    private static int visit(byte[] image, int stride, boolean[] seen, int[] queue, int size,
            int x, int y, int width, int left, int top, int cutoff) {
        int id = y * width + x;
        if (seen[id] || (image[(top + y) * stride + left + x] & 255) < cutoff) return size;
        seen[id] = true;
        queue[size] = id;
        return size + 1;
    }

    private static boolean dark(byte[] image, int width, int x, int y) {
        return (image[y * width + x] & 255) < 165;
    }
}
