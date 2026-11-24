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
