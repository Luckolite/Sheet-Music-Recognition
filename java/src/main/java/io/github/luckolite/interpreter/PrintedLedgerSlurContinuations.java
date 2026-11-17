// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Matches long above-staff slur fragments on adjacent rows of high-ledger sixteenths. */
final class PrintedLedgerSlurContinuations {
    private PrintedLedgerSlurContinuations() {}

    private record Sample(int y, boolean joint) {}

    private record Row(
            PrintedSlurPageCapture.Frame<PrintedSlurOwners.GeometryHead> frame,
            float gap, PrintedSlurOwners.GeometryHead first, PrintedSlurOwners.GeometryHead last) {}

    static ScorePageInterpretation apply(
            ScorePageInterpretation score, byte[] normalized, byte[] original, byte[] labels,
            int width, int height, int page, int initialKey) {
        var proved = find(score, normalized, original, labels, width, height, page);
        if (proved.isEmpty()) return score;
        var prior = score.slurSource();
        var spans = new ArrayList<>(prior.spans());
        var origins = new ArrayList<>(prior.noteOrigins());
        var keys = new ArrayList<ScoreKeyChange>();
        keys.add(new ScoreKeyChange(0, initialKey));
        keys.addAll(score.keyChanges());
        for (var span : proved) {
            int first = span.start().noteIndex(), last = span.end().noteIndex();
            int a = ScoreTiePitchGuard.midi(score.notes().get(first), keys);
            int b = ScoreTiePitchGuard.midi(score.notes().get(last), keys);
            if (a < 0 || b < 0 || a == b) continue;
            var start = origins.get(first).orElse(span.start());
            var stop = origins.get(last).orElse(span.end());
            if (start.pageIndex() != page || stop.pageIndex() != page
                    || origins.get(first).isEmpty() && origins.contains(java.util.Optional.of(start))
                    || origins.get(last).isEmpty() && origins.contains(java.util.Optional.of(stop)))
                continue;
            boolean owned = java.util.stream.Stream.concat(spans.stream(), prior.candidates().stream())
                    .anyMatch(s -> s.id().equals(span.id()) || s.start().equals(start) || s.end().equals(stop));
            if (owned) continue;
            origins.set(first, java.util.Optional.of(start));
            origins.set(last, java.util.Optional.of(stop));
            spans.add(new ScoreSlurPages.Span(span.id(), start, stop,
                    span.placement(), span.lineType(), span.evidence()));
        }
        return spans.equals(prior.spans()) ? score
                : score.withSlurSource(new ScoreSlurSource(spans, origins, prior.candidates()));
    }

    static List<ScoreSlurPages.Span> find(
            ScorePageInterpretation score, byte[] normalized, byte[] original, byte[] labels,
            int width, int height, int page) {
        if (page < 0 || normalized == null || original == null || labels == null
                || normalized.length != (long) width * height || original.length != normalized.length
                || labels.length != normalized.length || width < 256 || height < 128)
            return List.of();
        var measures = score.measures();
        var notes = score.notes();
        var heads = PrintedSlurContext.geometryHeads(notes, measures, width, height);
        var bars = new ArrayList<List<PrintedSlurOwners.GeometryHead>>(measures.size());
        for (int i = 0; i < measures.size(); i++) bars.add(new ArrayList<>());
        for (var head : heads) bars.get(notes.get(head.owner()).measureIndex()).add(head);
        var result = new ArrayList<ScoreSlurPages.Span>();
        int attempts = 0;
        ScoreMeterChange active = null;
        int nextMeter = 0;
        for (int m = 1; m + 2 < measures.size(); m++) {
            while (nextMeter < score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex() <= m)
                active = score.meterChanges().get(nextMeter++);
            if (active == null || active.numerator() != 3 || active.denominator() != 4
                    || nextMeter < score.meterChanges().size()
                        && score.meterChanges().get(nextMeter).measureIndex() == m + 1
                    || !range(notes, bars.get(m)) || !range(notes, bars.get(m + 1))
                    || measures.get(m).cadenceQuarterBeats() != 0
                    || measures.get(m + 1).cadenceQuarterBeats() != 0)
                continue;
            var outgoing = measures.get(m);
            var incoming = measures.get(m + 1);
            float gap = (outgoing.bottom() - outgoing.top()) * height / 8.2f;
            if (!(gap >= 10 && gap <= 24)
                    || (incoming.top() - outgoing.bottom()) * height < 3 * gap
                    || Math.abs(outgoing.top() - measures.get(m - 1).top()) * height > .4f * gap
                    || Math.abs(incoming.top() - measures.get(m + 2).top()) * height > .4f * gap)
                continue;
            if (++attempts > 6) return List.of();
            var raw = pair(score, original, labels, width, height, page, m, bars.get(m), bars.get(m + 1));
            if (raw.isEmpty()) continue;
            var corrected = pair(score, normalized, labels, width, height, page, m, bars.get(m), bars.get(m + 1));
            if (corrected.equals(raw)) result.addAll(raw);
        }
        return List.copyOf(result);
    }

    private static boolean range(
            List<ScoreNoteEvent> notes, List<PrintedSlurOwners.GeometryHead> heads) {
        if (heads.size() != 12) return false;
        for (var head : heads) {
            var n = notes.get(head.owner());
            if (n.staffIndex() != 0 || n.staffCount() != 1
                    || n.kind() != ScoreNoteEvent.Kind.PITCHED
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || n.beamCount() != 2 || n.augmentationDots() != 0
                    || n.stemDirection() != -1 || n.boundaryTies() != 0
                    || n.openStringTouch() != null || n.harmonicSemitones() != 0)
                return false;
        }
        return true;
    }

    private static List<ScoreSlurPages.Span> pair(
            ScorePageInterpretation score, byte[] gray, byte[] labels, int w, int h, int page, int measure,
            List<PrintedSlurOwners.GeometryHead> a, List<PrintedSlurOwners.GeometryHead> b) {
        var outgoing = row(gray, w, h, score.measures().get(measure), a, true);
        var incoming = row(gray, w, h, score.measures().get(measure + 1), b, false);
        if (outgoing == null || incoming == null) return List.of();
        var lead = incoming.first();
        var shaft = PrintedBeamShafts.at(gray, w, h, lead.x(), lead.y(), incoming.gap(),
                new PrintedBeamShafts.Budget(60_000));
        if (shaft.size() != 1 || shaft.get(0).direction() != 1
                || !WrappedMeasureBoundaries.confirmsShaftBoundedInitialStaffBar(
                        gray, w, h, score.measures(), measure + 1, lead.x(), shaft.get(0).left()))
            return List.of();
        var after = outgoing.frame();
        var hints = PrintedSlurOwners.headEvidence(after.heads(), gray, w, h, after.track());
        hints = new PrintedCurveMarkRoles(score.notes(), gray, w, h).wrap(hints, after.heads(), after.track());
        hints = new PrintedCurveJointRoles(score.notes(), gray, w, h).wrap(hints, after.heads(), after.track());
        var bows = PrintedSlurContours.find(gray, labels, w, h, after.left(), after.top(),
                after.right(), after.bottom(), after.track(), null, hints);
        if (bows.limitReached()) return List.of();
        var old = bows.bows().stream().filter(bow -> bow.above()
                && bow.end().x() - bow.start().x() >= outgoing.gap() * 18
                && Math.abs(bow.start().x() - outgoing.first().x()) <= .55f * outgoing.gap()
                && bow.end().x() > outgoing.last().x() + outgoing.gap()
                && !bows.shortRailBows().contains(bow)).toList();
        if (old.size() != 1) return List.of();
        var first = old.get(0);
        var free = after.track().at(first.end().x());
        float phase = (free[0] - first.end().y()) / free[1];
        if (phase < 6 || phase > 12) return List.of();
        var second = incomingBow(gray, w, h, incoming, phase);
        if (second == null) return List.of();
        var joined = PrintedSystemSlurJoins.joinLongTaperedFragments(page,
                List.of(new PrintedSystemSlurJoins.Observed(after, first),
                        new PrintedSystemSlurJoins.Observed(incoming.frame(), second)),
                List.of(after, incoming.frame()), score.notes(), gray, w, h, score.measures());
        return joined.stream().filter(s -> s.start().noteIndex() == outgoing.first().owner()
                && s.end().noteIndex() == incoming.last().owner()).toList();
    }

    private static Row row(
            byte[] gray, int w, int h, MeasureRegion bar, List<PrintedSlurOwners.GeometryHead> source,
            boolean outgoing) {
        var track = WrappedMeasureBoundaries.verifiedTripletStaff(gray, w, h, bar);
        if (track == null) return null;
        float gap = (bar.bottom() - bar.top()) * h / 8.2f;
        var heads = source.stream().sorted(Comparator.comparingDouble(PrintedSlurOwners.GeometryHead::x)).toList();
        var first = heads.get(0);
        var last = heads.get(heads.size() - 1);
        if (last.x() - first.x() < 23 * gap || last.x() - first.x() > 37 * gap) return null;
        float high = Float.POSITIVE_INFINITY, low = Float.NEGATIVE_INFINITY;
        for (var head : heads) {
            var rules = track.at(head.x());
            if (head.y() > rules[0] - 4 * rules[1] - .55f * gap) return null;
            high = Math.min(high, head.y());
            low = Math.max(low, head.y());
        }
        int left = Math.round(bar.left() * w - (outgoing ? .1f : 2.3f) * gap);
        int right = Math.round(bar.right() * w + .3f * gap);
        int top = Math.round(high - 3.7f * gap), bottom = Math.round(low + .6f * gap);
        if (left <= 1 || right >= w - 1 || top < 1 || bottom >= h - 1 || right <= left || bottom <= top)
            return null;
        return new Row(new PrintedSlurPageCapture.Frame<>(left, top, right, bottom, track, heads),
                gap, first, last);
    }

    private static PrintedSlurContours.Bow incomingBow(
            byte[] gray, int w, int h, Row row, float phase) {
        float gap = row.gap();
        var last = row.last();
        int minX = Math.round(last.x() - .48f * gap), maxX = Math.round(last.x() + .03f * gap);
        int minY = Math.round(last.y() - 1.06f * gap), maxY = Math.round(last.y() - .59f * gap);
        PrintedSlurContours.Bow result = null;
        for (int x = maxX; x >= minX; x--) {
            for (int y = minY; y <= maxY; y++) {
                var seed = sample(gray, w, h, x, y, gap);
                if (seed == null || seed.joint() || seed.y() != y) continue;
                var bow = trace(gray, w, h, row, x, y, phase);
                if (bow == null) continue;
                if (result != null && (Math.abs(result.start().x() - bow.start().x()) > 1
                        || Math.abs(result.start().y() - bow.start().y()) > 2)) return null;
                if (result == null || result.end().x() - result.start().x()
                        < bow.end().x() - bow.start().x()) result = bow;
            }
        }
        return result;
    }

    private static PrintedSlurContours.Bow trace(
            byte[] gray, int w, int h, Row row, int capX, int capY, float phase) {
        float gap = row.gap();
        int joint = 0, joins = 0;
        var cells = new ArrayList<PrintedSlurContours.ForeignRun>();
        cells.add(cell(capX, capY));
        int y = capY;
        for (int x = capX - 1; x > row.frame().left(); x--) {
            int step = Math.min(7, cells.size() - 1);
            int earlier = cells.get(cells.size() - 1 - step).members().get(0).first();
            double slope = step == 0 ? 0 : Math.max(-1.5, Math.min(1.5, (y - earlier) / (double) step));
            Sample best = null;
            double distance = Double.POSITIVE_INFINITY;
            for (int yy = y - 3; yy <= y + 3; yy++) {
                var option = sample(gray, w, h, x, yy, gap);
                if (option == null || !option.joint() && option.y() != yy) continue;
                double error = Math.abs(option.y() - (y + slope));
                if (error < distance) {
                    best = option;
                    distance = error;
                }
            }
            if (best == null) {
                var staff = row.frame().track().at(x + 1);
                if (row.first().x() - x < gap * 1.5f
                        || row.first().x() - x > gap * 4.3f
                        || cells.size() < 23 * gap || joint != 0 || joins > cells.size() * .075f
                        || Math.abs((staff[0] - y) / staff[1] - phase) > .65f
                        || !blankBeyond(gray, w, h, x, y, slope, gap)) return null;
                Collections.reverse(cells);
                var bow = PrintedSlurContours.fitPixelCells(cells, row.frame().track());
                return bow != null && bow.above() ? bow : null;
            }
            if (best.joint()) {
                joins++;
                if (++joint > Math.round(.57f * gap)) return null;
            } else joint = 0;
            y = best.y();
            if (y <= row.frame().top() || y > row.frame().bottom()) return null;
            cells.add(cell(x, y));
        }
        return null;
    }

    private static PrintedSlurContours.ForeignRun cell(int x, int y) {
        return new PrintedSlurContours.ForeignRun(x, List.of(new PrintedSlurContours.RawBand(y, y)));
    }

    private static Sample sample(byte[] gray, int w, int h, int x, int y, float gap) {
        if (y < 10 || y >= h - 10 || (gray[y * w + x] & 255) >= 162) return null;
        int top = y, bottom = y;
        while (top > y - 8 && (gray[(top - 1) * w + x] & 255) < 162) top--;
        while (bottom < y + 8 && (gray[(bottom + 1) * w + x] & 255) < 162) bottom++;
        if (bottom - top + 1 > Math.round(.43f * gap)) return new Sample(y, true);
        return new Sample((top + bottom + 1) / 2, false);
    }

    private static boolean blankBeyond(
            byte[] gray, int w, int h, int x, int y, double slope, float gap) {
        for (int dx = 1; dx <= Math.round(.4f * gap); dx++) {
            int xx = x + 1 - dx, center = (int) Math.round(y + dx * slope);
            if (xx < 0 || center < 2 || center + 2 >= h) return false;
            for (int dy = -2; dy <= 2; dy++)
                if ((gray[(center + dy) * w + xx] & 255) < 162) return false;
        }
        return true;
    }
}
