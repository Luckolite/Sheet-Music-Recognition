// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/** Source-owned above-staff slurs across an ascending and descending triplet pair. */
final class PrintedTripletTransitionSlurs {
    private PrintedTripletTransitionSlurs() {}

    static ScorePageInterpretation apply(ScorePageInterpretation score, int page, byte[] raw, byte[] gray,
            byte[] labels, int width, int height) {
        long area = (long) width * height;
        if (page < 0 || raw == null || gray == null || labels == null
                || raw.length != area || gray.length != area || labels.length != area
                || score.beamSource().groups().isEmpty()) return score;
        var notes = score.notes();
        var measures = score.measures();
        var slurs = score.slurSource();
        var groups = new HashMap<Integer, List<List<Integer>>>();
        for (var group : score.beamSource().resolve().groups()) {
            if (group.source().tupletMark() != ScoreBeamSource.TupletMark.UNNUMBERED
                    || !group.source().bridges().equals(List.of(1, 1)) || !group.source().hooks().isEmpty()
                    || group.columns().stream().anyMatch(c -> !c.usable() || c.owners().size() != 1
                            || c.source().members().size() != 1)) continue;
            var owners = group.columns().stream().map(c -> c.owners().get(0)).toList();
            var first = notes.get(owners.get(0));
            int measure = first.measureIndex();
            if (Math.abs(first.stemDirection()) != 1 || owners.stream().map(notes::get).anyMatch(n ->
                    n.measureIndex() != measure || n.stemDirection() != first.stemDirection()
                    || n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffCount() != 1
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL || n.crossStaffBeam()
                    || n.beamCount() != 1 || n.tupletDivisor() != 3 || n.tupletNormalNotes() != 2
                    || (n.articulations() & NoteOrnament.GRACE) != 0)) continue;
            groups.computeIfAbsent(measure, key -> new ArrayList<>()).add(owners);
        }
        if (groups.isEmpty()) return score;
        var heads = PrintedSlurContext.geometryHeads(notes, measures, width, height);
        var spans = new ArrayList<>(slurs.spans());
        var candidates = new ArrayList<>(slurs.candidates());
        for (int measure = 0; measure < measures.size(); measure++) {
            var here = groups.get(measure);
            if (here == null || here.size() < 2) continue;
            final int m = measure;
            if (score.rests().stream().anyMatch(rest -> rest.measureIndex() == m)) continue;
            var bar = measures.get(measure);
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (gap < 10 || gap > 25) continue;
            here.sort(Comparator.comparingDouble(group -> notes.get(group.get(0)).positionInMeasure()));
            var row = heads.stream()
                    .filter(head -> notes.get(head.owner()).measureIndex() == m)
                    .sorted(Comparator.comparingDouble(PrintedSlurOwners.GeometryHead::x)).toList();
            if (row.size() < 6 || row.size() > 32) continue;
            var byOwner = new HashMap<Integer, PrintedSlurOwners.GeometryHead>();
            for (var head : row) byOwner.put(head.owner(), head);
            var originalStaff = WrappedMeasureBoundaries.verifiedStaffPockets(raw, width, height, bar, true);
            var correctedStaff = WrappedMeasureBoundaries.verifiedStaffPockets(gray, width, height, bar, true);
            if (originalStaff == null || correctedStaff == null) continue;
            for (int i = 1; i < here.size(); i++) {
                var up = here.get(i - 1);
                var down = here.get(i);
                if (notes.get(up.get(0)).stemDirection() != 1 || notes.get(down.get(0)).stemDirection() != -1)
                    continue;
                var a = byOwner.get(up.get(1));
                var cap = byOwner.get(up.get(2));
                var next = byOwner.get(down.get(0));
                var b = byOwner.get(down.get(1));
                if (a == null || cap == null || next == null || b == null || b.x() - a.x() < 6.5f * gap
                        || b.x() - a.x() > 9.4f * gap || next.x() - cap.x() < 1.65f * gap
                        || next.x() - cap.x() > 3.6f * gap || row.stream().anyMatch(h ->
                            h.x() > a.x() && h.x() < b.x() && h.owner() != cap.owner()
                                    && h.owner() != next.owner())) continue;
                var start = slurs.noteOrigins().get(a.owner()).orElse(null);
                var end = slurs.noteOrigins().get(b.owner()).orElse(null);
                if (start == null || end == null || start.pageIndex() != page || end.pageIndex() != page
                        || spans.stream().anyMatch(s -> s.start().equals(start) && s.end().equals(end))) continue;
                var rawBow = prove(raw, labels, width, height, a, cap, b, gap, originalStaff, row);
                var grayBow = prove(gray, labels, width, height, a, cap, b, gap, correctedStaff, row);
                if (!agrees(rawBow, grayBow)) continue;
                var pending = candidates.stream().filter(s -> s.start().equals(start) && s.end().equals(end))
                        .toList();
                if (pending.size() > 1 || !pending.isEmpty()
                        && (pending.get(0).placement() != ScoreSlurSpan.Placement.ABOVE
                                || pending.get(0).lineType() != ScoreSlurSpan.LineType.SOLID)) continue;
                String id = pending.isEmpty() ? "printed-triplet-transition:" + page + ":" + start.noteIndex()
                        : pending.get(0).id();
                var evidence = new ScoreSlurSpan.ArcEvidence(page, rawBow.start().x() / (float) width,
                        rawBow.start().y() / height, rawBow.end().x() / (float) width, rawBow.end().y() / height);
                spans.add(new ScoreSlurPages.Span(id, start, end, ScoreSlurSpan.Placement.ABOVE,
                        ScoreSlurSpan.LineType.SOLID, List.of(evidence)));
                candidates.removeAll(pending);
            }
        }
        return spans.size() == slurs.spans().size() ? score
                : score.withSlurSource(new ScoreSlurSource(spans, slurs.noteOrigins(), candidates));
    }

    /** Retain a complete thin phrase arc spanning four independently owned triplet groups. */
    static ScorePageInterpretation recoverLongPhrases(ScorePageInterpretation score,
            byte[] raw, byte[] normalized, int width, int height) {
        if (raw == null || normalized == null || raw.length != (long) width * height
                || normalized.length != raw.length) return score;
        var source = score.slurSource();
        var notes = score.notes();
        if (source.noteOrigins().size() != notes.size()) return score;
        var bars = score.measures();
        var byBar = new HashMap<Integer, List<List<Integer>>>();
        for (var group : score.beamSource().resolve().groups()) {
            if (!group.source().bridges().equals(List.of(1, 1)) || !group.source().hooks().isEmpty()
                    || group.columns().stream().anyMatch(c -> !c.usable() || c.owners().size() != 1)) continue;
            var owners = group.columns().stream().map(c -> c.owners().get(0)).toList();
            int measure = notes.get(owners.get(0)).measureIndex();
            if (owners.stream().map(notes::get).anyMatch(n -> n.measureIndex() != measure
                    || n.stemDirection() != -1 || n.beamCount() != 1 || n.tupletDivisor() != 3
                    || n.tupletNormalNotes() != 2 || n.augmentationDots() != 0
                    || !ClosedMonophonicSixteenths.regularBeamHead(n, 1))) continue;
            byBar.computeIfAbsent(measure, m -> new ArrayList<>()).add(owners);
        }
        var notesPerBar = new int[bars.size()];
        for (var note : notes) notesPerBar[note.measureIndex()]++;
        var spans = new ArrayList<>(source.spans());
        var candidates = new ArrayList<>(source.candidates());
        for (int measure = 0; measure < bars.size(); measure++) {
            var parts = byBar.get(measure);
            if (parts == null || parts.size() != 4 || notesPerBar[measure] != 12) continue;
            var row = parts.stream().flatMap(List::stream).distinct()
                    .sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure())).toList();
            if (row.size() != 12) continue;
            var start = source.noteOrigins().get(row.get(0)).orElse(null);
            var end = source.noteOrigins().get(row.get(11)).orElse(null);
            if (start == null || end == null || start.pageIndex() != end.pageIndex()
                    || start.noteIndex() >= end.noteIndex()
                    || spans.stream().anyMatch(s -> s.start().pageIndex() == start.pageIndex()
                            && s.end().pageIndex() == end.pageIndex() && s.start().noteIndex() <= end.noteIndex()
                            && s.end().noteIndex() >= start.noteIndex())) continue;
            var pending = candidates.stream().filter(s -> s.start().equals(start) && s.end().equals(end)).toList();
            if (pending.size() > 1 || !pending.isEmpty() && (pending.get(0).placement() != ScoreSlurSpan.Placement.ABOVE
                    || pending.get(0).lineType() != ScoreSlurSpan.LineType.SOLID)) continue;
            var bar = bars.get(measure);
            var a = longPhrase(raw, width, height, bar, bars, notes.get(row.get(0)), notes.get(row.get(11)));
            if (a == null) continue;
            var b = raw == normalized ? a
                    : longPhrase(normalized, width, height, bar, bars, notes.get(row.get(0)), notes.get(row.get(11)));
            if (!agrees(a, b)) continue;
            int page = start.pageIndex();
            var evidence = new ScoreSlurSpan.ArcEvidence(page, a.start().x() / (float) width,
                    a.start().y() / height, a.end().x() / (float) width, a.end().y() / height);
            String id = pending.isEmpty() ? "printed-long-triplet-phrase:" + page + ":" + start.noteIndex()
                    : pending.get(0).id();
            spans.add(new ScoreSlurPages.Span(id, start, end, ScoreSlurSpan.Placement.ABOVE,
                    ScoreSlurSpan.LineType.SOLID, List.of(evidence)));
            candidates.removeAll(pending);
        }
        return spans.size() == source.spans().size() ? score
                : score.withSlurSource(new ScoreSlurSource(spans, source.noteOrigins(), candidates))
                        .withClockContextSource(score.clockContextSource());
    }

    private static PrintedSlurContours.Bow longPhrase(byte[] ink, int width, int height,
            MeasureRegion bar, List<MeasureRegion> page, ScoreNoteEvent first, ScoreNoteEvent last) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float span = (bar.right() - bar.left()) * width;
        float ax = bar.left() * width + first.positionInMeasure() * span;
        float bx = bar.left() * width + last.positionInMeasure() * span;
        float ay = first.pageY() * height, by = last.pageY() * height;
        if (gap < 10 || gap > 25 || bx - ax < 19 * gap || bx - ax > 38 * gap) return null;
        var staff = WrappedMeasureBoundaries.verifiedStaffPockets(ink, width, height, bar, true);
        if (staff == null) return null;
        int left = Math.round(ax - gap), right = Math.round(bx + gap);
        int top = Math.round(Math.min(ay, by) - 8 * gap), bottom = Math.round(Math.max(ay, by) - .75f * gap);
        for (var other : page)
            if (other.bottom() < bar.top() && other.left() * width <= right && other.right() * width >= left)
                top = Math.max(top, Math.round(other.bottom() * height + .12f * gap));
        if (left < 0 || right >= width || top < 0 || bottom >= height || bottom - top < 2 * gap) return null;
        int w = right - left + 1, h = bottom - top + 1;
        var patch = new byte[w * h];
        for (int row = 0; row < h; row++) System.arraycopy(ink, (top + row) * width + left, patch, row * w, w);
        for (int x = 0; x < w; x++) {
            var frame = staff.at(left + x);
            for (int i = 0; i < 5; i++) {
                int y = Math.round(frame[0] - i * frame[1]) - top;
                for (int row = Math.max(0, y - 2); row <= Math.min(h - 1, y + 2); row++)
                    patch[row * w + x] = (byte) 255;
            }
        }
        PrintedSlurContours.Bow result = null;
        for (var component : RawNumeralComponents.captureLongWindow(patch, w, h, 0, 0, w - 1, h - 1, 165).components()) {
            if (component.top <= 0 || component.bottom >= h - 1 || component.left <= 0 || component.right >= w - 1
                    || Math.abs(left + component.left - ax) > gap * .78f
                    || Math.abs(left + component.right - bx) > gap * .78f
                    || component.bottom - component.top < 1.65f * gap) continue;
            var bow = fitLongPhrase(component, patch, w, left, top, staff, gap);
            if (bow == null || ay - bow.start().y() < .8f * gap || by - bow.end().y() < .8f * gap
                    || ay - bow.start().y() > 3.5f * gap || by - bow.end().y() > 3.5f * gap) continue;
            if (result != null) return null;
            result = bow;
        }
        return result;
    }

    private static PrintedSlurContours.Bow fitLongPhrase(RawNumeralComponents.Component component,
            byte[] patch, int width, int left, int top, StaffPitchTrack staff, float gap) {
        int count = component.right - component.left + 1;
        var upper = new int[count];
        var lower = new int[count];
        java.util.Arrays.fill(upper, Integer.MAX_VALUE);
        for (int i = 0; i < component.size(); i++) {
            int at = component.pixel(i), x = at % width - component.left, y = at / width;
            upper[x] = Math.min(upper[x], y);
            lower[x] = Math.max(lower[x], y);
        }
        var columns = new ArrayList<PrintedSlurContours.ForeignRun>(count);
        for (int i = 0; i < count; i++) {
            if (upper[i] == Integer.MAX_VALUE) return null;
            int x = component.left + i, first = upper[i], last = first;
            while (last < lower[i] && (patch[(last + 1) * width + x] & 255) <= 165) last++;
            if (last - first + 1 > .43f * gap) {
                if (i > .08f * count && i < .92f * count) return null;
                continue;
            }
            columns.add(new PrintedSlurContours.ForeignRun(left + x,
                    List.of(new PrintedSlurContours.RawBand(top + first, top + last))));
        }
        if (columns.size() < .91f * count) return null;
        int trim = Math.round(.55f * gap);
        var core = PrintedSlurContours.fitPixelCells(columns.subList(trim, columns.size() - trim), staff);
        if (core == null || !core.above() || core.bendInGaps() < 1.6f || core.rmsInGaps() > .09f) return null;
        return core;
    }


    private static boolean agrees(PrintedSlurContours.Bow a, PrintedSlurContours.Bow b) {
        return a != null && b != null && Math.abs(a.start().x() - b.start().x()) <= 2
                && Math.abs(a.end().x() - b.end().x()) <= 2
                && Math.abs(a.start().y() - b.start().y()) <= 2
                && Math.abs(a.end().y() - b.end().y()) <= 2;
    }

    private static PrintedSlurContours.Bow prove(byte[] ink, byte[] labels, int width, int height,
            PrintedSlurOwners.GeometryHead a, PrintedSlurOwners.GeometryHead cap,
            PrintedSlurOwners.GeometryHead b, float gap, StaffPitchTrack track,
            List<PrintedSlurOwners.GeometryHead> heads) {
        int left = Math.round(a.x() - .65f * gap), right = Math.round(b.x() + .86f * gap);
        int top = Math.round(b.y() - 5 * gap), bottom = Math.round(b.y() + .10f * gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height || top >= bottom) return null;
        var hints = PrintedSlurOwners.headEvidence(heads, ink, width, height, track);
        var read = PrintedSlurContours.find(ink, labels, width, height, left, top, right, bottom, track, null, hints);
        if (read.limitReached()) return null;
        var candidates = read.bows().stream().filter(bow -> owns(bow, a, b, gap)).toList();
        if (!candidates.isEmpty()) return candidates.size() == 1 ? candidates.get(0) : null;
        var shaft = new PrintedBeamShafts.Budget(100_000);
        var start = PrintedBeamShafts.at(ink, width, height, a.x(), a.y(), gap, shaft);
        var crossed = PrintedBeamShafts.at(ink, width, height, cap.x(), cap.y(), gap, shaft);
        if (shaft.refused() || start.size() != 1 || crossed.size() != 1
                || start.get(0).direction() != -1 || crossed.get(0).direction() != -1) return null;
        return sharedShaftBow(ink, width, left, right, top, bottom, a, b, gap, track, start.get(0), crossed.get(0));
    }

    private static boolean owns(PrintedSlurContours.Bow bow, PrintedSlurOwners.GeometryHead a,
            PrintedSlurOwners.GeometryHead b, float gap) {
        return bow != null && bow.above() && bow.bendInGaps() >= .9f && bow.bendInGaps() <= 2.4f
                && bow.rmsInGaps() <= .078f && Math.abs(bow.start().x() - a.x()) <= .58f * gap
                && Math.abs(bow.end().x() - b.x()) <= .62f * gap
                && a.y() - bow.start().y() >= 2.5f * gap && a.y() - bow.start().y() <= 4.6f * gap
                && b.y() - bow.end().y() >= .70f * gap && b.y() - bow.end().y() <= 1.55f * gap;
    }

    /** A thin contour may cover the top of an independently traced beam shaft. */
    private static PrintedSlurContours.Bow sharedShaftBow(byte[] ink, int width, int left, int right,
            int top, int bottom, PrintedSlurOwners.GeometryHead a, PrintedSlurOwners.GeometryHead b,
            float gap, StaffPitchTrack track, PrintedBeamShafts.Shaft start, PrintedBeamShafts.Shaft crossed) {
        var columns = new ArrayList<PrintedSlurContours.ForeignRun>();
        int overlaps = 0, tail = 0, thin = Math.round(gap * .39f);
        int previousTop = -1;
        boolean reachedStaff = false;
        for (int x = left; x <= right; x++) {
            int y = top;
            while (y <= bottom && (ink[y * width + x] & 255) >= 165) y++;
            if (columns.isEmpty() && y > start.endY() - gap * .18f) continue;
            if (y > bottom || previousTop >= 0 && Math.abs(y - previousTop) > gap * .30f) return null;
            var staff = track.at(x);
            float topRule = staff[0] - 4 * staff[1];
            boolean endPocket = x >= b.x() - gap * .20f && x <= b.x() + gap * .82f;
            if (y >= topRule - .1f * gap) {
                reachedStaff = endPocket;
                break;
            }
            int endY = y;
            while (endY < bottom && (ink[(endY + 1) * width + x] & 255) < 165) endY++;
            if (endY - y + 1 > thin) {
                if (endPocket && y >= topRule - .50f * gap && endY >= topRule - .10f * gap) {
                    reachedStaff = true;
                    break;
                }
                if (x < crossed.left() - gap * .16f || x > crossed.right() + gap * .16f
                        || Math.abs(y - crossed.endY()) > gap * .26f
                        || ++overlaps > gap * .38f || columns.size() < gap * 1.65f) return null;
                endY = y + Math.round(gap * .23f);
            } else if (overlaps > 0) tail++;
            columns.add(new PrintedSlurContours.ForeignRun(x,
                    List.of(new PrintedSlurContours.RawBand(y, endY))));
            previousTop = y;
        }
        if (!reachedStaff || overlaps == 0 || tail < gap * 2.5f) return null;
        var bow = PrintedSlurContours.fitPixelCells(columns, track);
        return owns(bow, a, b, gap) ? bow : null;
    }
}
