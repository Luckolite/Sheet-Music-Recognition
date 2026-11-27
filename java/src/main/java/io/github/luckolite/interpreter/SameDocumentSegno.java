// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** In a PDF with matching editions, one printed Segno can complete a duplicated route. */
final class SameDocumentSegno {
    record Page(String digest, int physicalPage, int physicalCount, int firstMeasure,
            List<ScorePlaybackDirection> directions, List<List<List<Attack>>> bars) {}
    record Attack(int pitch, int beam, int dots) {}
    record Mark(int page, ScorePlaybackDirection direction) {
        int boundary() { return direction.measureBoundary(); }
    }
    record Route(Mark segno, Mark trigger, Mark command, Mark coda) {}
    record Transfer(int page, int measure) {}

    private static final Attack BARLINE = new Attack(Integer.MIN_VALUE, 0, 0);

    private SameDocumentSegno() {}

    static List<ScorePlaybackDirection> validated(
            List<ScorePageInterpretation> pages, int[] counts, int[] starts,
            ScorePageTimeline.PageRange range, int[] offsets, List<ScorePlaybackDirection> directions,
            int measureCount, ScoreMeterMap meter) {
        if (!needsSegno(directions)) return List.of();
        var ranges = ScorePageTimeline.arrangements(counts, starts);
        if (ranges.size() < 2) return List.of();
        var material = pages.stream().map(SameDocumentSegno::material).toList();
        var transfer = resolve(material, ranges, range);
        if (transfer == null) return List.of();
        var recovered = new ScorePlaybackDirection(
                offsets[transfer.page()] + transfer.measure(), ScorePlaybackDirection.Kind.SEGNO);
        var combined = new ArrayList<>(directions);
        combined.add(recovered);
        return ScoreNavigationPlan.create(measureCount, combined, meter).traversal().performanceQualified()
                ? List.of(recovered) : List.of();
    }

    static boolean needsSegno(List<ScorePlaybackDirection> directions) {
        return routeShape(directions, false);
    }

    private static boolean routeShape(List<ScorePlaybackDirection> directions, boolean donor) {
        return count(directions, ScorePlaybackDirection.Kind.SEGNO) == (donor ? 1 : 0)
                && count(directions, ScorePlaybackDirection.Kind.TO_CODA) == 1
                && count(directions, ScorePlaybackDirection.Kind.DAL_SEGNO_AL_CODA) == 1
                && count(directions, ScorePlaybackDirection.Kind.CODA) == 1
                && directions.stream().noneMatch(d -> switch (d.kind()) {
                    case DA_CAPO, DAL_SEGNO, DA_CAPO_AL_FINE, DAL_SEGNO_AL_FINE, DA_CAPO_AL_CODA, FINE -> true;
                    default -> false;
                });
    }

    private static long count(
            List<ScorePlaybackDirection> directions, ScorePlaybackDirection.Kind kind) {
        return directions.stream().filter(d -> d.kind() == kind).count();
    }

    static Transfer resolve(List<Page> pages, List<ScorePageTimeline.PageRange> ranges,
            ScorePageTimeline.PageRange target) {
        var receiver = route(pages, target, false);
        if (receiver == null) return null;
        var found = new ArrayList<Transfer>();
        for (var range : ranges) {
            if (range.equals(target)) continue;
            var donor = route(pages, range, true);
            if (donor == null || !sameSource(pages, List.of(donor.segno(), donor.trigger(), donor.command(),
                            donor.coda(), receiver.trigger(), receiver.command(), receiver.coda()))
                    || !cueMatches(pages, donor.trigger(), receiver.trigger(), 2, 0, 12, true)
                    || !cueMatches(pages, donor.command(), receiver.command(), 2, 0, 8, false)
                    || !cueMatches(pages, donor.coda(), receiver.coda(), 0, 3, 8, false))
                continue;
            var transfer = segment(pages, donor.segno(), target, receiver.trigger());
            if (transfer != null) found.add(transfer);
        }
        return found.size() == 1 ? found.get(0) : null;
    }

    private static Route route(List<Page> pages, ScorePageTimeline.PageRange range, boolean donor) {
        var marks = new ArrayList<Mark>();
        for (int i = range.firstPage(); i < range.pageAfterLast(); i++) {
            var page = pages.get(i);
            if (page == null) return null;
            for (var direction : page.directions()) marks.add(new Mark(i, direction));
        }
        if (!routeShape(marks.stream().map(Mark::direction).toList(), donor)) return null;
        var segno = find(marks, ScorePlaybackDirection.Kind.SEGNO);
        var trigger = find(marks, ScorePlaybackDirection.Kind.TO_CODA);
        var command = find(marks, ScorePlaybackDirection.Kind.DAL_SEGNO_AL_CODA);
        var coda = find(marks, ScorePlaybackDirection.Kind.CODA);
        if (command == null || coda == null || (donor && segno == null)
                || (segno != null && !before(segno, trigger)) || !before(trigger, command)
                || (!before(command, coda) && (command.page() != coda.page()
                        || command.boundary() != coda.boundary()))) return null;
        return new Route(segno, trigger, command, coda);
    }

    private static Mark find(List<Mark> marks, ScorePlaybackDirection.Kind kind) {
        return marks.stream().filter(mark -> mark.direction().kind() == kind
                && mark.direction().details().quarterBeatOffset() == 0
                && mark.direction().details().targetId().isEmpty()
                && mark.direction().details().codaTargetId().isEmpty()).findFirst().orElse(null);
    }

    private static boolean before(Mark first, Mark second) {
        return first != null && second != null && (first.page() < second.page()
                || first.page() == second.page() && first.boundary() < second.boundary());
    }

    private static boolean sameSource(List<Page> pages, List<Mark> marks) {
        var origin = pages.get(marks.get(0).page());
        if (origin.digest().isEmpty()) return false;
        for (var mark : marks) {
            var page = pages.get(mark.page());
            if (!origin.digest().equals(page.digest()) || origin.physicalCount() != page.physicalCount()
                    || page.physicalPage() != mark.page() || page.physicalCount() <= mark.page())
                return false;
        }
        return true;
    }

    private static boolean cueMatches(List<Page> pages, Mark donor, Mark receiver,
            int preceding, int following, int minAttacks, boolean allowSplit) {
        var source = pages.get(donor.page());
        var target = pages.get(receiver.page());
        int numberDelta = source.firstMeasure() + donor.boundary()
                - target.firstMeasure() - receiver.boundary();
        if (numberDelta != 0 && (!allowSplit || numberDelta != -1)) return false;
        var signatures = windows(source, donor.boundary() - preceding, donor.boundary() + following, !allowSplit);
        var other = windows(target, receiver.boundary() - preceding + numberDelta,
                receiver.boundary() + following, !allowSplit);
        return signatures.stream().anyMatch(signature ->
                signature.size() - (allowSplit ? 0 : preceding + following) >= minAttacks && other.contains(signature));
    }

    private static Transfer segment(
            List<Page> pages, Mark donor, ScorePageTimeline.PageRange target, Mark trigger) {
        var source = pages.get(donor.page());
        int printed = source.firstMeasure() + donor.boundary();
        var signatures = windows(source, donor.boundary() - 1, donor.boundary() + 3, true);
        var found = new ArrayList<Transfer>();
        for (int p = target.firstPage(); p <= trigger.page(); p++) {
            var page = pages.get(p);
            if (!source.digest().equals(page.digest()) || page.physicalPage() != p
                    || source.physicalCount() != page.physicalCount()) return null;
            int end = p == trigger.page() ? trigger.boundary() - 1 : page.bars().size() - 1;
            for (int m = 1; m < end; m++) {
                var candidates = windows(page, m - 1, m + 3, true);
                if (candidates.stream().anyMatch(candidate -> candidate.size() >= 22
                        && signatures.contains(candidate))) found.add(new Transfer(p, m));
            }
        }
        if (found.size() != 1) return null;
        var result = found.get(0);
        return pages.get(result.page()).firstMeasure() + result.measure() == printed ? result : null;
    }

    private static List<List<Attack>> windows(Page page, int first, int end, boolean preserveBarlines) {
        if (page.firstMeasure() <= 0 || first < 0 || end > page.bars().size() || first >= end)
            return List.of();
        var bars = page.bars().subList(first, end);
        int staves = bars.stream().mapToInt(List::size).min().orElse(0);
        var result = new ArrayList<List<Attack>>();
        for (int staff = 0; staff < staves; staff++) {
            var attacks = new ArrayList<Attack>();
            for (var bar : bars) {
                attacks.addAll(bar.get(staff));
                if (preserveBarlines) attacks.add(BARLINE);
            }
            if (attacks.size() > (preserveBarlines ? bars.size() : 0)) result.add(attacks);
        }
        return result;
    }

    private static Page material(ScorePageInterpretation page) {
        if (page == null) return null;
        var origin = page.clockContextSource().origin();
        var bars = new ArrayList<List<List<Attack>>>();
        for (int m = 0; m < page.measures().size(); m++) bars.add(new ArrayList<>());
        page.notes().stream().filter(Objects::nonNull)
                .filter(note -> note.kind() == ScoreNoteEvent.Kind.PITCHED
                        && note.clefBottomDiatonic() != ScoreNoteEvent.CLEF_UNKNOWN
                        && note.measureIndex() >= 0 && note.measureIndex() < bars.size()
                        && note.staffIndex() >= 0 && note.staffIndex() < 64)
                .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                .forEach(note -> {
                    var bar = bars.get(note.measureIndex());
                    while (bar.size() <= note.staffIndex()) bar.add(new ArrayList<>());
                    bar.get(note.staffIndex()).add(new Attack(note.clefBottomDiatonic() + note.staffStep(),
                            note.beamCount(), note.augmentationDots()));
                });
        return new Page(origin.boundDocument() ? origin.documentSha256() : "", origin.physicalPageIndex(),
                origin.physicalPageCount(), page.firstMeasureNumber(), page.playbackDirections(), bars);
    }
}
