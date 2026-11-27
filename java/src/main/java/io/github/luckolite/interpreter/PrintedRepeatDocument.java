// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import java.util.function.IntPredicate;

/** Joins optical navigation evidence across proved consecutive page numbers. */
final class PrintedRepeatDocument {
    record Result(List<ScorePlaybackDirection> directions, Set<String> resolvedIds) {}

    private record Witness(ScoreExpressiveEvent event, int page, int pageStart, int pageEnd,
            boolean sharesPrintedOpening) {}

    /** Recover document witnesses when playback has the same contiguous page offsets. */
    static Result validatedPlayback(
            List<ScorePageInterpretation> pages,
            int[] offsets,
            List<ScorePlaybackDirection> existing,
            int count,
            ScoreMeterMap meter) {
        if (pages.size() != offsets.length) return new Result(List.of(), Set.of());
        int offset = 0;
        for (int i = 0; i < pages.size(); i++) {
            var page = pages.get(i);
            if (page == null || page.measures().isEmpty() || offsets[i] != offset)
                return new Result(List.of(), Set.of());
            offset += page.measures().size();
        }
        if (offset != count) return new Result(List.of(), Set.of());
        return validated(pages, existing, count, meter);
    }

    /** Keep authored/local navigation intact; admit each recovered repeat only after joint traversal. */
    static Result validated(
            List<ScorePageInterpretation> pages,
            List<ScorePlaybackDirection> existing,
            int count,
            ScoreMeterMap meter) {
        return validated(pages, existing, count, meter, ScorePageTimeline.physicalAdjacency(pages, null));
    }

    static Result validated(
            List<ScorePageInterpretation> pages,
            List<ScorePlaybackDirection> existing,
            int count,
            ScoreMeterMap meter,
            IntPredicate adjacent) {
        return validated(pages, existing, count, meter, adjacent, page -> false);
    }

    static Result validated(List<ScorePageInterpretation> pages, List<ScorePlaybackDirection> existing,
            int count, ScoreMeterMap meter, IntPredicate adjacent, IntPredicate boundAdjacent) {
        return validatedTransport(pages, existing, count, meter, adjacent, boundAdjacent, false);
    }

    static Result validatedTransport(
            List<ScorePageInterpretation> pages, List<ScorePlaybackDirection> existing,
            int count, ScoreMeterMap meter, IntPredicate adjacent, IntPredicate boundAdjacent,
            boolean legacySharedContinuity) {
        return validateCandidates(
                assemble(pages, adjacent, boundAdjacent, legacySharedContinuity), existing, count, meter);
    }

    private static Result validateCandidates(
            Result candidate, List<ScorePlaybackDirection> existing, int count, ScoreMeterMap meter) {
        var groups = new LinkedHashMap<String, List<ScorePlaybackDirection>>();
        for (var direction : candidate.directions())
            groups.computeIfAbsent(
                            direction.details().repeatGroupId(), ignored -> new ArrayList<>())
                    .add(direction);
        var accepted = new ArrayList<ScorePlaybackDirection>();
        var ids = new HashSet<String>();
        var combined = new ArrayList<>(existing);
        var prior = ScoreNavigationTraversal.traverse(count, meter, combined);
        for (var group : groups.values()) {
            var current = combined;
            if (group.stream()
                    .anyMatch(
                            n ->
                                    current.stream()
                                            .anyMatch(
                                                    old ->
                                                            old.kind() == n.kind()
                                                                    && old.anchor()
                                                                            .equals(n.anchor()))))
                continue;
            var attempt = new ArrayList<>(combined);
            attempt.addAll(group);
            var checked = ScoreNavigationTraversal.traverse(count, meter, attempt);
            if (!checked.complete() || !prior.diagnostics().containsAll(checked.diagnostics()))
                continue;
            accepted.addAll(group);
            combined = attempt;
            prior = checked;
            for (var direction : group)
                for (var proof : direction.details().evidence())
                    for (String id : candidate.resolvedIds())
                        if (proof.sourceId().endsWith(id)) ids.add(id);
        }
        return new Result(List.copyOf(accepted), Set.copyOf(ids));
    }


    private record CaptionRoute(
            ScorePlaybackDirection start, ScorePlaybackDirection close,
            ScorePlaybackDirection first, ScorePlaybackDirection last) {}

    /** The captions supply anchors; only a matching numbered engraving supplies the passes. */
    static Map<Integer, List<ScorePlaybackDirection>> resolveCaptionRoutes(
            List<ScorePageInterpretation> pages, List<ScorePageTimeline.PageRange> arrangements) {
        var pitches = new HashMap<Integer, List<List<Integer>>>();
        return resolveCaptionRoutes(pages, arrangements,
                index -> pitches.computeIfAbsent(index, i -> pitchBars(pages.get(i))));
    }

    static Map<Integer, List<ScorePlaybackDirection>> resolveCaptionRoutes(
            List<ScorePageInterpretation> pages, List<ScorePageTimeline.PageRange> arrangements,
            java.util.function.IntFunction<List<List<Integer>>> pitches) {
        var resolved = new LinkedHashMap<Integer, List<ScorePlaybackDirection>>();
        if (arrangements.size() < 2) return resolved;
        for (var range : arrangements) {
            for (int p = range.firstPage(); p < range.pageAfterLast(); p++) {
                var page = pages.get(p);
                if (page == null) continue;
                var captions = page.expressiveEvents().stream()
                        .filter(PrintedRepeatDocument::isRepeatCaption).toList();
                for (var opening : captions) {
                    if (PrintedRepeatNavigation.captionKind(opening.qualifierText())
                            != ScorePlaybackDirection.Kind.REPEAT_START) continue;
                    int anchor = opening.start().orElseThrow().measureIndex();
                    var matches = new ArrayList<List<ScorePlaybackDirection>>();
                    for (var other : arrangements) {
                        if (other.equals(range)) continue;
                        for (int d = other.firstPage(); d < other.pageAfterLast(); d++) {
                            var donor = pages.get(d);
                            if (donor == null || !sameSourceDocument(page, donor)) continue;
                            for (var route : numberedCaptionRoutes(donor)) {
                                int shift = anchor - route.start().measureBoundary();
                                var projected = matchCaptionRoute(p, captions, opening, route, shift, page,
                                        pitches.apply(p), pitches.apply(d));
                                if (!projected.isEmpty()) matches.add(projected);
                            }
                        }
                    }
                    if (matches.size() == 1)
                        resolved.computeIfAbsent(p, ignored -> new ArrayList<>()).addAll(matches.get(0));
                }
            }
        }
        resolved.replaceAll((page, directions) -> List.copyOf(directions));
        return Map.copyOf(resolved);
    }

    static Result validatedCaptionPlayback(
            List<ScorePageInterpretation> pages, int[] counts, int[] starts,
            ScorePageTimeline.PageRange range, int[] offsets,
            List<ScorePlaybackDirection> existing, int count, ScoreMeterMap meter) {
        return validatedCaptionPlayback(resolveCaptionRoutes(pages, ScorePageTimeline.arrangements(counts, starts)),
                range, offsets, existing, count, meter);
    }

    static Result validatedCaptionPlayback(
            Map<Integer, List<ScorePlaybackDirection>> byPage, ScorePageTimeline.PageRange range,
            int[] offsets, List<ScorePlaybackDirection> existing, int count, ScoreMeterMap meter) {
        var directions = new ArrayList<ScorePlaybackDirection>();
        var ids = new HashSet<String>();
        for (int page = range.firstPage(); page < range.pageAfterLast(); page++) {
            for (var row : byPage.getOrDefault(page, List.of())) {
                directions.add(row.offset(offsets[page]));
                String source = row.details().evidence().get(0).sourceId();
                ids.add(source.substring(PrintedRepeatNavigation.CAPTION_SOURCE.length()));
            }
        }
        return directions.isEmpty() ? new Result(List.of(), Set.of())
                : validateCandidates(new Result(directions, ids), existing, count, meter);
    }

    private static boolean isRepeatCaption(ScoreExpressiveEvent event) {
        return event.kind() == ScoreExpressiveEvent.Kind.UNRESOLVED_DIRECTION
                && event.eventId().startsWith(PrintedRepeatNavigation.CAPTION_ID)
                && event.start().filter(anchor -> anchor.quarterBeatOffset() == 0).isPresent()
                && event.evidence().size() == 1
                && event.evidence().get(0).sourceId().equals(
                        PrintedRepeatNavigation.CAPTION_SOURCE + event.eventId())
                && event.evidence().get(0).printedText().equals(event.qualifierText());
    }

    private static boolean sameSourceDocument(ScorePageInterpretation a, ScorePageInterpretation b) {
        var left = a.clockContextSource().origin().documentSha256();
        var right = b.clockContextSource().origin().documentSha256();
        return left.isEmpty() || right.isEmpty() || left.equals(right);
    }

    private static List<CaptionRoute> numberedCaptionRoutes(ScorePageInterpretation donor) {
        var groups = new LinkedHashMap<String, List<ScorePlaybackDirection>>();
        for (var direction : donor.playbackDirections()) {
            var id = direction.details().repeatGroupId();
            if (id.startsWith("printed-repeat:") && direction.details().quarterBeatOffset() == 0
                    && !direction.details().evidence().isEmpty())
                groups.computeIfAbsent(id, ignored -> new ArrayList<>()).add(direction);
        }
        var result = new ArrayList<CaptionRoute>();
        for (var group : groups.values()) {
            var start = uniqueDirection(group, ScorePlaybackDirection.Kind.REPEAT_START);
            var close = uniqueDirection(group, ScorePlaybackDirection.Kind.REPEAT_END);
            var endings = group.stream().filter(d -> d.kind() == ScorePlaybackDirection.Kind.ENDING)
                    .sorted(Comparator.comparingInt(ScorePlaybackDirection::measureBoundary)).toList();
            if (group.size() != 4 || start == null || close == null || endings.size() != 2) continue;
            var first = endings.get(0);
            var last = endings.get(1);
            int a = start.measureBoundary(), b = first.measureBoundary(), c = close.measureBoundary();
            int plays = start.details().totalPlays();
            if (a < 0 || b <= a || b >= c || c != last.measureBoundary()
                    || !first.details().end().equals(Optional.of(new ScoreAnchor(c, 0)))
                    || last.details().end().filter(end -> end.measureIndex() > c
                            && end.measureIndex() <= donor.measures().size()
                            && end.quarterBeatOffset() == 0).isEmpty()
                    || plays < 2 || group.stream().anyMatch(d -> d.details().totalPlays() != plays)
                    || first.details().printedText().isBlank() || last.details().printedText().isBlank())
                continue;
            var passes = new ArrayList<>(first.details().passes());
            passes.addAll(last.details().passes());
            if (passes.size() != plays || passes.stream().distinct().count() != plays
                    || passes.stream().anyMatch(pass -> pass < 1 || pass > plays)
                    || first.details().passes().isEmpty() || last.details().passes().isEmpty()) continue;
            result.add(new CaptionRoute(start, close, first, last));
        }
        return result;
    }

    private static ScorePlaybackDirection uniqueDirection(
            List<ScorePlaybackDirection> group, ScorePlaybackDirection.Kind kind) {
        var matches = group.stream().filter(direction -> direction.kind() == kind).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static List<List<Integer>> pitchBars(ScorePageInterpretation page) {
        var bars = new ArrayList<List<Integer>>(page.measures().size());
        for (int m = 0; m < page.measures().size(); m++) bars.add(new ArrayList<>());
        for (var note : page.notes()) {
            int m = note.measureIndex();
            if (m >= 0 && m < bars.size() && note.kind() == ScoreNoteEvent.Kind.PITCHED
                    && note.clefBottomDiatonic() != ScoreNoteEvent.CLEF_UNKNOWN)
                bars.get(m).add(note.clefBottomDiatonic() + note.staffStep());
        }
        return bars;
    }

    private static List<ScorePlaybackDirection> matchCaptionRoute(
            int pageIndex, List<ScoreExpressiveEvent> captions, ScoreExpressiveEvent opening,
            CaptionRoute route, int shift, ScorePageInterpretation page,
            List<List<Integer>> target, List<List<Integer>> donor) {
        int begin = route.start().measureBoundary();
        int end = route.last().details().end().orElseThrow().measureIndex();
        int start = begin + shift, finish = end + shift;
        if (start < 0 || finish > target.size() || end - begin < 3) return List.of();
        var fingerprint = donor.subList(begin, end);
        if (fingerprint.stream().anyMatch(List::isEmpty)
                || fingerprint.stream().mapToInt(List::size).sum() < 8
                || !fingerprint.equals(target.subList(start, finish))
                || !uniquePassage(donor, fingerprint) || !uniquePassage(target, fingerprint))
            return List.of();
        if (page.playbackDirections().stream().anyMatch(d ->
                d.measureBoundary() >= start && d.measureBoundary() <= finish
                        && (d.kind() == ScorePlaybackDirection.Kind.REPEAT_START
                                || d.kind() == ScorePlaybackDirection.Kind.REPEAT_END
                                || d.kind() == ScorePlaybackDirection.Kind.ENDING)))
            return List.of();
        var marks = captions.stream().filter(event -> {
            int at = event.start().orElseThrow().measureIndex();
            return at >= start && at < finish;
        }).toList();
        if (marks.size() != 4) return List.of();
        String id = "caption-repeat:" + pageIndex + ":" + start + ":" + finish;
        var result = new ArrayList<ScorePlaybackDirection>();
        for (var source : List.of(route.start(), route.close(), route.first(), route.last())) {
            int at = source.measureBoundary() + shift;
            var matching = marks.stream().filter(event -> event.start().orElseThrow().measureIndex() == at
                    && PrintedRepeatNavigation.captionKind(event.qualifierText()) == source.kind()).toList();
            if (matching.size() != 1) return List.of();
            var caption = matching.get(0);
            if (source == route.start() && !caption.equals(opening)) return List.of();
            var detail = source.details();
            if (detail.evidence().size() >= 64) return List.of();
            var evidence = new ArrayList<>(caption.evidence());
            evidence.addAll(detail.evidence());
            result.add(new ScorePlaybackDirection(at, source.kind(), new ScorePlaybackDirection.Details(
                    0, id + ":" + source.kind() + ":" + at, "", "", id, detail.totalPlays(),
                    detail.passes(), detail.end().map(last -> new ScoreAnchor(last.measureIndex() + shift, 0)),
                    detail.afterJumpRepeats(), caption.qualifierText(), evidence)));
        }
        return List.copyOf(result);
    }

    private static boolean uniquePassage(List<List<Integer>> bars, List<List<Integer>> fingerprint) {
        int matches = 0;
        for (int i = 0; i <= bars.size() - fingerprint.size(); i++)
            if (bars.get(i).equals(fingerprint.get(0))
                    && bars.subList(i, i + fingerprint.size()).equals(fingerprint) && ++matches > 1)
                return false;
        return matches == 1;
    }

    static Result assemble(List<ScorePageInterpretation> pages) {
        return assemble(pages, ScorePageTimeline.physicalAdjacency(pages, null), page -> false, false);
    }

    private static Result assemble(List<ScorePageInterpretation> pages, IntPredicate adjacent,
            IntPredicate boundAdjacent, boolean legacySharedContinuity) {
        var directions = new ArrayList<ScorePlaybackDirection>();
        var resolved = new HashSet<String>();
        int offset = 0;
        var group = new ArrayList<Witness>();
        ScorePageInterpretation previous = null;
        boolean runStartsDocumentMusic = false;
        for (int page = 0; page < pages.size(); page++) {
            var score = pages.get(page);
            boolean transmittedOpening = legacySharedContinuity && runStartsDocumentMusic
                    && previous != null && previous.firstMeasureNumber() == 0
                    && score.firstMeasureNumber() == 0 && group.isEmpty()
                    && score.expressiveEvents().stream().anyMatch(
                            event -> sharesPrintedOpening(event, score.playbackDirections()));
            if (previous != null && (!adjacent.test(page)
                    || !continues(previous, score, boundAdjacent.test(page) || transmittedOpening))) {
                flush(group, directions, resolved, runStartsDocumentMusic);
                group.clear();
                runStartsDocumentMusic = false;
            }
            if (offset == 0 && !score.measures().isEmpty() && score.firstMeasureNumber() <= 1)
                runStartsDocumentMusic = true;
            for (var event : score.expressiveEvents()) {
                if (event.kind() == ScoreExpressiveEvent.Kind.UNRESOLVED_DIRECTION
                        && (event.eventId().startsWith("unresolved-printed-repeat:")
                                || event.eventId().startsWith("unresolved-printed-ending:")))
                    group.add(
                            new Witness(
                                    event.offset(offset),
                                    page,
                                    offset,
                                    offset + score.measures().size(),
                                    sharesPrintedOpening(event, score.playbackDirections())));
            }
            offset += score.measures().size();
            previous = score;
        }
        flush(group, directions, resolved, runStartsDocumentMusic);
        return new Result(List.copyOf(directions), Set.copyOf(resolved));
    }

    private static boolean sharesPrintedOpening(
            ScoreExpressiveEvent event, List<ScorePlaybackDirection> directions) {
        if (event.start().isEmpty() || event.start().get().quarterBeatOffset() != 0
                || event.evidence().size() < 2
                || event.evidence().stream().anyMatch(
                        e -> !e.sourceId().startsWith("printed-repeat-end:v1:"))) return false;
        var start = event.start().get();
        return directions.stream().anyMatch(direction ->
                direction.kind() == ScorePlaybackDirection.Kind.REPEAT_START
                        && direction.anchor().equals(start)
                        && direction.details().repeatGroupId().startsWith("printed-repeat:")
                        && direction.details().evidence().stream().anyMatch(open ->
                                event.evidence().stream().anyMatch(close ->
                                        close.pageIndex() == open.pageIndex()
                                                && Math.abs(close.visualX() - open.visualX()) <= .003f)));
    }

    private static boolean continues(ScorePageInterpretation previous, ScorePageInterpretation next,
            boolean boundAdjacent) {
        if (previous.measures().isEmpty() || next.measures().isEmpty()) return false;
        int previousNumber = previous.firstMeasureNumber();
        int nextNumber = next.firstMeasureNumber();
        long numberedAdvance = (long) nextNumber - previousNumber;
        int detectedBars = previous.measures().size();
        if ((previousNumber != 0 || nextNumber != 0) && numberedAdvance == detectedBars)
            return true;
        var first = previous.clockContextSource().origin();
        var second = next.clockContextSource().origin();
        if (!boundAdjacent && (!first.boundDocument() || !second.boundDocument()
                || !first.documentSha256().equals(second.documentSha256())
                || first.physicalPageCount() != second.physicalPageCount()
                || first.physicalPageIndex() + 1 != second.physicalPageIndex())) return false;
        if (previousNumber == 0 && nextNumber == 0) return true;
        // Split bars and a separately engraved volta can change the count of source regions.
        // Require source-identical adjacent pages and a forward printed number before bridging.
        return previousNumber > 0 && numberedAdvance > 0
                && Math.abs(numberedAdvance - detectedBars) <= 2;
    }

    private static boolean validEnding(Witness w) {
        return w.event().evidence().stream()
                .allMatch(
                        e ->
                                e.sourceId()
                                        .matches(
                                                "printed-ending-span:v1:(true|false):(true|false):unresolved-printed-ending:.*"));
    }

    private static boolean source(Witness w, String prefix) {
        return w.event().evidence().stream().allMatch(e -> e.sourceId().startsWith(prefix));
    }

    private static void flush(
            List<Witness> group, List<ScorePlaybackDirection> directions,
            Set<String> resolved, boolean runStartsDocumentMusic) {
        if (group.isEmpty()) return;
        if (runStartsDocumentMusic && appendImplicitOpening(group, directions, resolved)) {
            group = group.subList(1, group.size());
            if (group.isEmpty()) return;
        }
        var repeats = new ArrayList<PrintedRepeatGlyphs.Mark>();
        var endings = new ArrayList<PrintedEndingBrackets.Mark>();
        for (var w : group) {
            var event = w.event();
            if (event.start().isEmpty() || event.start().get().quarterBeatOffset() != 0) return;
            int start = event.start().get().measureIndex();
            if (start < w.pageStart()
                    || start > w.pageEnd()
                    || event.staffCount() != group.get(0).event().staffCount()) return;
            var proof = event.evidence().get(0);
            if (source(w, "printed-repeat-start:v1:") || source(w, "printed-repeat-end:v1:")) {
                repeats.add(
                        new PrintedRepeatGlyphs.Mark(
                                start,
                                source(w, "printed-repeat-end:v1:"),
                                proof.visualX(),
                                proof.staffIndex(),
                                proof.staffCount()));
            } else if (source(w, "printed-ending-span:v1:")) {
                if (!validEnding(w)) return;
                if (event.end().isEmpty() || event.end().get().quarterBeatOffset() != 0) return;
                int end = event.end().get().measureIndex();
                if (end <= start || end > w.pageEnd()) return;
                var passes = PrintedEndingBrackets.passes(proof.printedText());
                if (passes.isEmpty()) return;
                if (source(w, "printed-ending-span:v1:true:")
                        && end == w.pageEnd()
                        && passes.equals(List.of(1))) {
                    var next =
                            group.stream()
                                    .filter(
                                            n ->
                                                    n.page() == w.page() + 1
                                                            && validEnding(n)
                                                            && n.event()
                                                                    .evidence()
                                                                    .get(0)
                                                                    .sourceId()
                                                                    .split(":", 6)[3]
                                                                    .equals("true")
                                                            && PrintedEndingBrackets.passes(
                                                                            n.event()
                                                                                    .evidence()
                                                                                    .get(0)
                                                                                    .printedText())
                                                                    .equals(List.of(2))
                                                            && n.event().start().isPresent()
                                                            && n.event()
                                                                            .start()
                                                                            .get()
                                                                            .measureIndex()
                                                                    >= w.pageEnd())
                                    .toList();
                    if (next.size() != 1) return;
                    var following = next.get(0);
                    if (following.event().staffCount() != event.staffCount()) return;
                    end = following.event().start().get().measureIndex();
                    final int closing = end;
                    long outgoing =
                            group.stream()
                                    .filter(
                                            n ->
                                                    source(n, "printed-repeat-end:v1:")
                                                            && n.event().start().isPresent()
                                                            && n.event()
                                                                            .start()
                                                                            .get()
                                                                            .measureIndex()
                                                                    == closing)
                                    .count();
                    if (outgoing != 1) return;
                }
                endings.add(
                        new PrintedEndingBrackets.Mark(
                                start,
                                end,
                                passes,
                                proof.printedText(),
                                proof.visualX(),
                                proof.staffIndex(),
                                proof.staffCount()));
            } else return; // Old caches have no span proof; keep their unresolved evidence intact.
        }
        var found = PrintedRepeatNavigation.assemble(repeats, endings, group.get(0).page());
        if (!found.unresolved().isEmpty()) return;
        appendProvedDirections(found.directions(), group, directions, resolved);
    }

    /** A proved transition between the opening repeat and its successor stands on its own. */
    private static boolean appendImplicitOpening(List<Witness> group,
            List<ScorePlaybackDirection> directions, Set<String> resolved) {
        var closing = group.get(0);
        if (closing.pageStart() <= 0 || !closing.sharesPrintedOpening()) return false;
        int boundary = closing.event().start().orElseThrow().measureIndex();
        if (boundary <= closing.pageStart() || boundary >= closing.pageEnd()
                || group.stream().skip(1).anyMatch(w -> w.event().start().isEmpty()
                        || w.event().start().get().measureIndex() <= boundary)) return false;
        var proof = closing.event().evidence().get(0);
        var mark = new PrintedRepeatGlyphs.Mark(
                boundary, true, proof.visualX(), proof.staffIndex(), proof.staffCount());
        var first = PrintedRepeatNavigation.assemble(
                List.of(mark), List.of(), proof.pageIndex(), true);
        if (!first.unresolved().isEmpty() || first.directions().size() != 2) return false;
        appendProvedDirections(first.directions(), List.of(closing), directions, resolved);
        return true;
    }

    private static void appendProvedDirections(List<ScorePlaybackDirection> proved, List<Witness> group,
            List<ScorePlaybackDirection> directions, Set<String> resolved) {
        for (var direction : proved) {
            var proof = new ArrayList<ScoreExpressiveEvent.Evidence>();
            for (var w : group) {
                if (w.event().start().get().measureIndex() != direction.measureBoundary()) continue;
                boolean match =
                        switch (direction.kind()) {
                            case REPEAT_START -> source(w, "printed-repeat-start:v1:");
                            case REPEAT_END -> source(w, "printed-repeat-end:v1:");
                            case ENDING -> source(w, "printed-ending-span:v1:");
                            default -> false;
                        };
                if (match) {
                    proof.addAll(w.event().evidence());
                    resolved.add(w.event().eventId());
                }
            }
            var d = direction.details();
            if (proof.isEmpty() && d.repeatGroupId().contains(":implicit:")) proof.addAll(d.evidence());
            directions.add(
                    new ScorePlaybackDirection(
                            direction.measureBoundary(),
                            direction.kind(),
                            new ScorePlaybackDirection.Details(
                                    d.quarterBeatOffset(),
                                    "document:" + d.eventId(),
                                    d.targetId(),
                                    d.codaTargetId(),
                                    "document:" + d.repeatGroupId(),
                                    d.totalPlays(),
                                    d.passes(),
                                    d.end(),
                                    d.afterJumpRepeats(),
                                    d.printedText(),
                                    proof)));
        }
    }
}
