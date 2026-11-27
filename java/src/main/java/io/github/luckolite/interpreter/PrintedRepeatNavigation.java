// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;

/** Converts proved local optical marks only when repeat ownership and pass coverage agree. */
final class PrintedRepeatNavigation {
    record Result(List<ScorePlaybackDirection> directions, List<String> unresolved) {}

    private record Region(int start, int end, String id) {}

    private PrintedRepeatNavigation() {}

    static final String CAPTION_ID = "unresolved-repeat-caption:";
    static final String CAPTION_SOURCE = "printed-repeat-caption:v1:";
    private static final java.util.regex.Pattern CAPTION_SPACES = java.util.regex.Pattern.compile("\\s+");

    static ScorePlaybackDirection.Kind captionKind(String text) {
        if (text == null) return null;
        return switch (CAPTION_SPACES.matcher(text.strip()).replaceAll(" ").toUpperCase(Locale.ROOT)) {
            case "REPEAT START" -> ScorePlaybackDirection.Kind.REPEAT_START;
            case "REPEAT END" -> ScorePlaybackDirection.Kind.REPEAT_END;
            case "ENDING" -> ScorePlaybackDirection.Kind.ENDING;
            default -> null;
        };
    }

    private record Caption(int measure,
            PlayingTechniqueDetector.Word word, PlayingTechniqueDetector.Staff staff, float inset) {}

    private static List<ScoreExpressiveEvent> captions(
            List<PlayingTechniqueDetector.Word> words, List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures, int width, int height, int page) {
        if (words == null || staffs == null || width <= 0 || height <= 0) return List.of();
        var captions = new LinkedHashMap<String, Caption>();
        for (var word : words) {
            var kind = captionKind(word.text());
            if (kind == null || word.left() < 0 || word.right() > 1 || word.top() < 0
                    || word.bottom() > 1 || word.left() >= word.right() || word.top() >= word.bottom())
                continue;
            PlayingTechniqueDetector.Staff owner = null;
            float distance = Float.POSITIVE_INFINITY;
            for (var staff : staffs) {
                float relative = (staff.top() - word.bottom() * height) / staff.gap();
                if (relative >= -.2f && relative <= 6 && Math.abs(relative) < distance) {
                    owner = staff;
                    distance = Math.abs(relative);
                }
            }
            if (owner == null || owner.index() != 0) continue;
            for (int bar = 0; bar < measures.size(); bar++) {
                var region = measures.get(bar);
                float inset = Math.abs(word.left() - region.left()) * width / owner.gap();
                if (owner.top() < region.top() * height - owner.gap() * .5f
                        || owner.top() >= region.bottom() * height || inset > 1.35f
                        || word.right() > region.right() + .25f * owner.gap() / width) continue;
                var caption = new Caption(bar, word, owner, inset);
                String key = kind.name() + ":" + bar;
                var existing = captions.get(key);
                if (existing == null || inset < existing.inset()) captions.put(key, caption);
            }
        }
        var result = new ArrayList<ScoreExpressiveEvent>();
        for (var entry : captions.entrySet()) {
            var caption = entry.getValue();
            var staff = caption.staff();
            var word = caption.word();
            String id = CAPTION_ID + page + ":" + entry.getKey();
            result.add(unresolved(id, word.text(), caption.measure(), -1, CAPTION_SOURCE,
                    evidence(id, page, word.left(), staff.index(), staff.count(), word.text())));
        }
        return List.copyOf(result);
    }

    static ScorePageInterpretation apply(
            ScorePageInterpretation score,
            List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs,
            byte[] gray,
            int width,
            int height,
            int physicalPage) {
        return apply(score, words, staffs, gray, gray, width, height, physicalPage);
    }

    static ScorePageInterpretation apply(
            ScorePageInterpretation score,
            List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs,
            byte[] gray,
            byte[] annotationGray,
            int width,
            int height,
            int physicalPage) {
        var repeats = PrintedRepeatGlyphs.detect(gray, width, height, staffs, score.measures());
        var endings = new ArrayList<>(PrintedEndingBrackets.detect(
                gray, width, height, staffs, score.measures(), words));
        int pageIndex = Math.max(0, physicalPage);
        // Unnumbered first bars use zero. A known continuation still needs its printed repeat start.
        boolean scoreBeginning = physicalPage == 0 && score.firstMeasureNumber() <= 1;
        var found = assemble(repeats, endings, pageIndex, scoreBeginning);
        // Normalization can interrupt a thin roof. Use the source raster to fill gaps in the
        // evidence while keeping already proved selector positions and their span.
        if (annotationGray != gray && !found.unresolved().isEmpty()) {
            int before = endings.size();
            for (var alternate : PrintedEndingBrackets.detect(
                    annotationGray, width, height, staffs, score.measures(), words))
                if (endings.stream().noneMatch(e -> e.start() < alternate.end() && alternate.start() < e.end()
                        && e.staffIndex() == alternate.staffIndex()
                        && e.staffCount() == alternate.staffCount())) endings.add(alternate);
            if (endings.size() != before) found = assemble(repeats, endings, pageIndex, scoreBeginning);
        }
        var captions = captions(words, staffs, score.measures(), width, height, pageIndex);
        if (found.directions().isEmpty() && found.unresolved().isEmpty() && captions.isEmpty()) return score;
        var directions = new ArrayList<>(score.playbackDirections());
        directions.addAll(found.directions());
        var expressions = new ArrayList<>(score.expressiveEvents());
        for (var caption : captions) addUnresolved(expressions, caption);
        for (var mark : repeats) {
            var kind =
                    mark.outgoing()
                            ? ScorePlaybackDirection.Kind.REPEAT_END
                            : ScorePlaybackDirection.Kind.REPEAT_START;
            if (found.directions().stream()
                    .anyMatch(d -> d.kind() == kind && d.measureBoundary() == mark.boundary()))
                continue;
            String id =
                    "unresolved-printed-repeat:"
                            + physicalPage
                            + ":"
                            + mark.boundary()
                            + ":"
                            + mark.outgoing();
            addUnresolved(
                    expressions,
                    unresolved(
                            id,
                            "Printed repeat needs document-level ownership",
                            mark.boundary(),
                            -1,
                            mark.outgoing() ? "printed-repeat-end:v1:" : "printed-repeat-start:v1:",
                            evidence(
                                    id,
                                    physicalPage,
                                    mark.x(),
                                    mark.staffIndex(),
                                    mark.staffCount(),
                                    "")));
        }
        for (var mark : endings) {
            if (found.directions().stream()
                    .anyMatch(
                            d ->
                                    d.kind() == ScorePlaybackDirection.Kind.ENDING
                                            && d.measureBoundary() == mark.start())) continue;
            String id =
                    "unresolved-printed-ending:"
                            + physicalPage
                            + ":"
                            + mark.start()
                            + ":"
                            + mark.text();
            addUnresolved(
                    expressions,
                    unresolved(
                            id,
                            "Printed ending needs unambiguous repeat ownership: " + mark.text(),
                            mark.start(),
                            mark.end(),
                            "printed-ending-span:v1:"
                                    + mark.openRight()
                                    + ":"
                                    + mark.precedingRoof()
                                    + ":",
                            evidence(
                                    id,
                                    physicalPage,
                                    mark.x(),
                                    mark.staffIndex(),
                                    mark.staffCount(),
                                    mark.text())));
        }
        return score.withPlaybackDirections(directions).withExpressiveEvents(expressions);
    }

    /** Repeated staff witnesses describe one unresolved logical sign; retain every witness. */
    static void addUnresolved(List<ScoreExpressiveEvent> events, ScoreExpressiveEvent event) {
        for (int i = 0; i < events.size(); i++) {
            var old = events.get(i);
            if (!old.eventId().equals(event.eventId())) continue;
            if (old.kind() != ScoreExpressiveEvent.Kind.UNRESOLVED_DIRECTION
                    || event.kind() != old.kind()
                    || event.scope() != old.scope()
                    || event.scope() != ScoreExpressiveEvent.Scope.UNRESOLVED
                    || !event.start().equals(old.start())
                    || !event.end().equals(old.end())
                    || !event.targetEventId().equals(old.targetEventId())
                    || event.strength() != old.strength()
                    || event.staffCount() != old.staffCount()
                    || !event.qualifierText().equals(old.qualifierText()))
                throw new IllegalArgumentException("Conflicting unresolved printed sign identity");
            var evidence = new ArrayList<>(old.evidence());
            for (var witness : event.evidence())
                if (!evidence.contains(witness)) evidence.add(witness);
            events.set(
                    i,
                    new ScoreExpressiveEvent(
                            old.eventId(),
                            old.kind(),
                            old.start(),
                            old.end(),
                            old.scope(),
                            old.staffIndex(),
                            old.staffCount(),
                            old.targetEventId(),
                            old.strength(),
                            old.qualifierText(),
                            evidence));
            return;
        }
        events.add(event);
    }

    private static ScoreExpressiveEvent unresolved(
            String id,
            String text,
            int start,
            int end,
            String sourceKind,
            ScoreExpressiveEvent.Evidence evidence) {
        return new ScoreExpressiveEvent(
                id,
                ScoreExpressiveEvent.Kind.UNRESOLVED_DIRECTION,
                Optional.of(new ScoreAnchor(start, 0)),
                end < 0 ? Optional.empty() : Optional.of(new ScoreAnchor(end, 0)),
                ScoreExpressiveEvent.Scope.UNRESOLVED,
                evidence.staffIndex(),
                evidence.staffCount(),
                Optional.empty(),
                ScoreExpressiveEvent.Strength.UNSPECIFIED,
                text,
                List.of(
                        new ScoreExpressiveEvent.Evidence(
                                sourceKind + evidence.sourceId(),
                                evidence.pageIndex(),
                                evidence.visualX(),
                                evidence.staffIndex(),
                                evidence.staffCount(),
                                evidence.printedText())));
    }

    private static ScoreExpressiveEvent.Evidence evidence(
            String id, int page, float x, int staff, int count, String text) {
        return new ScoreExpressiveEvent.Evidence(id, Math.max(0, page), x, staff, count, text);
    }

    private static boolean completeInteriorEndings(
            List<PrintedEndingBrackets.Mark> interior, int plays) {
        var group = new HashSet<Integer>();
        int previousEnd = -1;
        int count = 0;
        for (var ending : interior) {
            if (ending.start() < previousEnd || !group.isEmpty() && ending.start() != previousEnd)
                return false;
            for (int pass : ending.passes())
                if (pass < 1 || pass > plays || !group.add(pass)) return false;
            previousEnd = ending.end();
            count++;
            if (group.size() == plays) {
                if (count < 2) return false;
                group.clear();
                count = 0;
            }
        }
        return group.isEmpty();
    }

    static Result assemble(
            List<PrintedRepeatGlyphs.Mark> repeats,
            List<PrintedEndingBrackets.Mark> endings,
            int pageIndex) {
        return assemble(repeats, endings, pageIndex, false);
    }

    /** An implicit opening requires caller proof that boundary zero is the score beginning. */
    static Result assemble(
            List<PrintedRepeatGlyphs.Mark> repeats,
            List<PrintedEndingBrackets.Mark> endings,
            int pageIndex,
            boolean scoreBeginning) {
        var directions = new ArrayList<ScorePlaybackDirection>();
        var unresolved = new ArrayList<String>();
        var ordered = new ArrayList<>(repeats);
        ordered.sort(
                Comparator.comparingInt(PrintedRepeatGlyphs.Mark::boundary)
                        .thenComparing(m -> !m.outgoing()));
        var seen = new HashSet<String>();
        var starts = new ArrayDeque<PrintedRepeatGlyphs.Mark>();
        var regions = new ArrayList<Region>();
        var closingEdges = new HashMap<String, List<Integer>>();
        for (var mark : ordered) {
            if (!seen.add(mark.boundary() + ":" + mark.outgoing())) continue;
            if (!mark.outgoing()) {
                starts.push(mark);
                continue;
            }
            var continuation = new ArrayList<Region>();
            for (var region : regions) {
                var edges = closingEdges.get(region.id());
                int prior = edges.get(edges.size() - 1);
                boolean initialEnding =
                        endings.stream()
                                .anyMatch(
                                        e ->
                                                e.start() >= region.start()
                                                        && e.start() < region.end()
                                                        && e.end() == region.end());
                boolean linkedEnding =
                        endings.stream()
                                .anyMatch(e -> e.start() == prior && e.end() == mark.boundary());
                if (initialEnding
                        && linkedEnding
                        && (starts.isEmpty() || starts.peek().boundary() < region.start()))
                    continuation.add(region);
            }
            if (continuation.size() == 1) {
                closingEdges.get(continuation.get(0).id()).add(mark.boundary());
                continue;
            }
            if (continuation.size() > 1) {
                unresolved.add("Ambiguous alternate backward edge at " + mark.boundary());
                continue;
            }
            if (starts.isEmpty()) {
                if (scoreBeginning && regions.isEmpty() && mark.boundary() > 0) {
                    var region =
                            new Region(
                                    0,
                                    mark.boundary(),
                                    "printed-repeat:"
                                            + pageIndex
                                            + ":implicit:0:"
                                            + mark.boundary());
                    regions.add(region);
                    closingEdges.put(region.id(), new ArrayList<>(List.of(mark.boundary())));
                } else {
                    unresolved.add(
                            "Repeat end at " + mark.boundary() + " has no proved local start");
                }
                continue;
            }
            var start = starts.pop();
            if (start.boundary() >= mark.boundary()) {
                unresolved.add("Empty repeat at " + mark.boundary());
                continue;
            }
            var region =
                    new Region(
                            start.boundary(),
                            mark.boundary(),
                            "printed-repeat:"
                                    + pageIndex
                                    + ":"
                                    + start.boundary()
                                    + ":"
                                    + mark.boundary());
            regions.add(region);
            closingEdges.put(region.id(), new ArrayList<>(List.of(mark.boundary())));
        }
        for (var start : starts)
            unresolved.add(
                    "Repeat start at " + start.boundary() + " continues outside local proof");
        var uniqueEndings = new ArrayList<PrintedEndingBrackets.Mark>();
        for (var ending : endings)
            if (uniqueEndings.stream()
                    .noneMatch(
                            e ->
                                    e.start() == ending.start()
                                            && e.end() == ending.end()
                                            && e.passes().equals(ending.passes())))
                uniqueEndings.add(ending);
        var used = new HashSet<PrintedEndingBrackets.Mark>();
        regions.sort(Comparator.comparingInt(r -> r.end() - r.start()));
        for (var region : regions) {
            var owned = new ArrayList<PrintedEndingBrackets.Mark>();
            for (var ending : uniqueEndings)
                if (!used.contains(ending)
                        && ending.start() >= region.start()
                        && ending.start() < region.end()
                        && ending.end() == region.end()) owned.add(ending);
            if (owned.size() > 1) {
                unresolved.add("Conflicting closing endings for " + region.id());
                continue;
            }
            int plays = 2;
            if (!owned.isEmpty()) {
                int next = region.end();
                boolean conflict = false;
                while (true) {
                    final int boundary = next;
                    var following =
                            uniqueEndings.stream()
                                    .filter(e -> !used.contains(e) && e.start() == boundary)
                                    .collect(java.util.stream.Collectors.toList());
                    if (following.isEmpty()) break;
                    if (following.size() != 1) {
                        conflict = true;
                        break;
                    }
                    var ending = following.get(0);
                    owned.add(ending);
                    next = ending.end();
                }
                var passes = new HashSet<Integer>();
                for (var ending : owned)
                    for (int pass : ending.passes()) if (!passes.add(pass)) conflict = true;
                plays = passes.stream().mapToInt(Integer::intValue).max().orElse(0);
                for (int pass = 1; pass <= plays; pass++)
                    if (!passes.contains(pass)) conflict = true;
                var finalPasses = owned.get(owned.size() - 1).passes();
                if (plays < 2 || owned.size() < 2 || !finalPasses.contains(plays)
                        || Collections.min(finalPasses) != plays - finalPasses.size() + 1)
                    conflict = true;
                var edges = closingEdges.get(region.id());
                if (edges.size() != owned.size() - 1) conflict = true;
                for (int i = 0; i < owned.size(); i++) {
                    var ending = owned.get(i);
                    if (i == owned.size() - 1) {
                        if (edges.contains(ending.end()))
                            conflict = true;
                    } else if (!edges.contains(ending.end()) || ending.passes().contains(plays))
                        conflict = true;
                }
                if (conflict) {
                    unresolved.add("Incomplete or conflicting ending coverage for " + region.id());
                    continue;
                }
                // Adjacent interior alternatives must rejoin before the closing ending.
                int terminalStart = owned.get(0).start();
                var interior = uniqueEndings.stream()
                        .filter(e -> !used.contains(e) && e.start() > region.start()
                                && e.end() <= terminalStart)
                        .sorted(Comparator.comparingInt(PrintedEndingBrackets.Mark::start))
                        .toList();
                if (!completeInteriorEndings(interior, plays)) {
                    unresolved.add("Incomplete interior ending coverage for " + region.id());
                    continue;
                }
                owned.addAll(interior);
            } else if (uniqueEndings.stream()
                    .anyMatch(
                            e ->
                                    !used.contains(e)
                                            && e.start() >= region.start()
                                            && e.start() <= region.end())) {
                unresolved.add("Ending span has no proved closing edge for " + region.id());
                continue;
            }
            directions.add(
                    direction(
                            region.start(),
                            ScorePlaybackDirection.Kind.REPEAT_START,
                            region.id() + ":start",
                            region.id(),
                            plays,
                            List.of(),
                            Optional.empty(),
                            ""));
            for (int edge : closingEdges.get(region.id()))
                directions.add(
                        direction(
                                edge,
                                ScorePlaybackDirection.Kind.REPEAT_END,
                                region.id() + ":end" + (edge == region.end() ? "" : ":" + edge),
                                region.id(),
                                plays,
                                List.of(),
                                Optional.empty(),
                                ""));
            for (var ending : owned) {
                directions.add(
                        direction(
                                ending.start(),
                                ScorePlaybackDirection.Kind.ENDING,
                                region.id() + ":ending:" + ending.start(),
                                region.id(),
                                plays,
                                ending.passes(),
                                Optional.of(new ScoreAnchor(ending.end(), 0)),
                                ending.text()));
                used.add(ending);
            }
        }
        for (var ending : uniqueEndings)
            if (!used.contains(ending))
                unresolved.add(
                        "Unresolved printed ending at " + ending.start() + ": " + ending.text());
        for (int i = 0; i < directions.size(); i++) {
            var direction = directions.get(i);
            var details = direction.details();
            ScoreExpressiveEvent.Evidence proof;
            if (direction.kind() == ScorePlaybackDirection.Kind.ENDING) {
                var mark =
                        uniqueEndings.stream()
                                .filter(
                                        e ->
                                                e.start() == direction.measureBoundary()
                                                        && e.passes().equals(details.passes()))
                                .findFirst()
                                .orElseThrow();
                proof =
                        evidence(
                                details.eventId(),
                                pageIndex,
                                mark.x(),
                                mark.staffIndex(),
                                mark.staffCount(),
                                mark.text());
            } else {
                boolean outgoing = direction.kind() == ScorePlaybackDirection.Kind.REPEAT_END;
                boolean implicitStart = !outgoing && details.repeatGroupId().contains(":implicit:");
                var mark =
                        ordered.stream()
                                .filter(
                                        m ->
                                                implicitStart
                                                        ? m.outgoing()
                                                                && details.repeatGroupId()
                                                                        .endsWith(
                                                                                ":" + m.boundary())
                                                        : m.boundary()
                                                                        == direction
                                                                                .measureBoundary()
                                                                && m.outgoing() == outgoing)
                                .findFirst()
                                .orElseThrow();
                proof =
                        evidence(
                                (implicitStart ? "implicit-score-beginning:" : "")
                                        + details.eventId(),
                                pageIndex,
                                mark.x(),
                                mark.staffIndex(),
                                mark.staffCount(),
                                "");
            }
            directions.set(
                    i,
                    new ScorePlaybackDirection(
                            direction.measureBoundary(),
                            direction.kind(),
                            new ScorePlaybackDirection.Details(
                                    details.quarterBeatOffset(),
                                    details.eventId(),
                                    details.targetId(),
                                    details.codaTargetId(),
                                    details.repeatGroupId(),
                                    details.totalPlays(),
                                    details.passes(),
                                    details.end(),
                                    details.afterJumpRepeats(),
                                    details.printedText(),
                                    List.of(proof))));
        }
        directions.sort(
                Comparator.comparingInt(ScorePlaybackDirection::measureBoundary)
                        .thenComparingInt(d -> d.kind().wireId()));
        return new Result(List.copyOf(directions), List.copyOf(unresolved));
    }

    private static ScorePlaybackDirection direction(
            int boundary,
            ScorePlaybackDirection.Kind kind,
            String id,
            String group,
            int plays,
            List<Integer> passes,
            Optional<ScoreAnchor> end,
            String text) {
        return new ScorePlaybackDirection(
                boundary,
                kind,
                new ScorePlaybackDirection.Details(
                        0,
                        id,
                        "",
                        "",
                        group,
                        plays,
                        passes,
                        end,
                        ScorePlaybackDirection.AfterJumpRepeats.DEFAULT,
                        text,
                        List.of()));
    }
}
