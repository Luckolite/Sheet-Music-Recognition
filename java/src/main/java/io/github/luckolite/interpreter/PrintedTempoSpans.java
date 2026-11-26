// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import static io.github.luckolite.interpreter.ScoreExpressiveEvent.*;

/** Connects proved text, extension ink, and numeric endpoints without converting pixels to beats. */
final class PrintedTempoSpans {
    private static final float[] STAFF_PROBE_FRACTIONS = {.125f, .25f, .375f, .5f, .625f, .75f, .875f};
    private static final java.util.regex.Pattern FASTER = java.util.regex.Pattern.compile("(?i)\\s*(?:[mpf]{1,3}\\s+)?faster\\s*[-.!]*\\s*");
    private static final String SOURCE = "printed-tempo-dash-span";
    static final String OPEN_HINT = "printed-tempo-dash-page-edge-v1:";
    static final String END_HINT = "printed-tempo-dash-end-v1:";

    private static final java.util.regex.Pattern COUNTED_ACCELERATION =
            java.util.regex.Pattern.compile(
                    "(?i)^accell?erando\\s+[^=0-9]*=\\s*([0-9]{2,3})[.)]?$");

    private static ScorePageInterpretation countedRestAcceleration(
            ScorePageInterpretation score, List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs, int height) {
        if (score.multiRestGroups().isEmpty() || score.tempoChanges().isEmpty() || height <= 0) return score;
        var events = new ArrayList<>(score.expressiveEvents());
        for (var word : words) {
            if (word == null || word.text() == null) continue;
            var equation = COUNTED_ACCELERATION.matcher(word.text().strip());
            if (!equation.matches()) continue;
            int rate = Integer.parseInt(equation.group(1));
            for (var rest : score.multiRestGroups()) {
                int start = rest.firstMeasureIndex(), end = start + rest.measureCount();
                if (rest.staffCount() != 1 || end >= score.measures().size()) continue;
                var startBar = score.measures().get(start);
                var endBar = score.measures().get(end);
                if (Math.abs(startBar.top() - endBar.top()) > .003f
                        || word.left() < startBar.left() || word.left() > startBar.right()
                        || word.right() <= endBar.left() || word.right() > endBar.right()
                        || score.tempoChanges().stream().noneMatch(tempo -> tempo.measureIndex() == end
                                && tempo.positionInMeasure() == 0
                                && Math.abs(tempo.bpm() / tempo.beatUnit() - rate) < 1e-7)) continue;
                boolean above = staffs.stream().anyMatch(staff -> staff.index() == rest.staffIndex()
                        && staff.count() == 1 && (staff.top() + staff.bottom()) * .5f / height >= startBar.top()
                        && (staff.top() + staff.bottom()) * .5f / height <= startBar.bottom()
                        && word.bottom() * height <= staff.top()
                        && staff.top() - word.bottom() * height <= staff.gap() * 4);
                if (!above) continue;
                String id = "printed-counted-rest-acceleration:" + start;
                if (events.stream().anyMatch(e -> e.eventId().equals(id))) continue;
                events.add(new ScoreExpressiveEvent(id, Kind.ACCELERANDO,
                        Optional.of(new ScoreAnchor(start, 0)), Optional.of(new ScoreAnchor(end, 0)),
                        Scope.SCORE, rest.staffIndex(), rest.staffCount(), Optional.empty(),
                        Strength.UNSPECIFIED, "", List.of(new Evidence("printed-expression-word",
                                0, word.left(), rest.staffIndex(), rest.staffCount(), word.text()))));
            }
        }
        return events.size() == score.expressiveEvents().size() ? score : score.withExpressiveEvents(events);
    }

    static ScorePageInterpretation apply(
            ScorePageInterpretation input,
            List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs,
            byte[] gray,
            int width,
            int height) {
        var score = countedRestAcceleration(input, words, staffs, height);
        // Multipart ownership and open page continuations need document-level evidence.
        if (staffs.isEmpty()
                || staffs.stream().anyMatch(s -> s.count() != 1)
                || width <= 0
                || height <= 0) return score;
        boolean eligible =
                score.expressiveEvents().stream()
                        .anyMatch(e -> (gradual(e.kind()) || bareFaster(e)) && e.end().isEmpty());
        if (!eligible) return score;
        var sizes = staffs.stream().map(PlayingTechniqueDetector.Staff::gap).sorted().toList();
        float gap = sizes.get(sizes.size() / 2), gx = gap / width, gy = gap / height;
        var runs = PrintedDashChains.detect(gray, width, height, gap);
        if (runs.isEmpty()) return score;
        var systems = new ArrayList<PrintedDashSpan.SystemBand>();
        int[] row = new int[score.measures().size()];
        for (int i = 0; i < score.measures().size(); i++) {
            var m = score.measures().get(i);
            if (systems.isEmpty()
                    || Math.abs(m.top() - systems.get(systems.size() - 1).top()) > gy * 2)
                systems.add(
                        new PrintedDashSpan.SystemBand(
                                m.left(), m.right(), m.top(), m.bottom(), Float.NaN));
            else {
                var s = systems.remove(systems.size() - 1);
                systems.add(
                        new PrintedDashSpan.SystemBand(
                                Math.min(s.left(), m.left()),
                                Math.max(s.right(), m.right()),
                                Math.min(s.top(), m.top()),
                                Math.max(s.bottom(), m.bottom()),
                                s.lastColumn()));
            }
            row[i] = systems.size() - 1;
        }
        float[] lastColumns = new float[systems.size()];
        Arrays.fill(lastColumns, Float.NaN);
        for (var n : score.notes()) {
            var m = score.measures().get(n.measureIndex());
            int at = row[n.measureIndex()];
            float x = m.left() + n.positionInMeasure() * (m.right() - m.left());
            float prior = lastColumns[at];
            lastColumns[at] = Float.isFinite(prior) ? Math.max(prior, x) : x;
        }
        for (int i = 0; i < systems.size(); i++) {
            var system = systems.get(i);
            systems.set(i, new PrintedDashSpan.SystemBand(system.left(), system.right(),
                    system.top(), system.bottom(), lastColumns[i]));
        }
        var ownershipSystems = printedOwnershipSystems(systems, staffs, gray, width, height);
        var spanCache = new HashMap<SpanQuery, Optional<PrintedDashSpan.Span>>();
        java.util.function.Function<SpanQuery, Optional<PrintedDashSpan.Span>> follow =
                query -> spanCache.computeIfAbsent(query, key ->
                        PrintedDashSpan.follow(runs, systems, key.word(), gx, gy,
                                ownershipSystems == systems ? List.of() : ownershipSystems,
                                key.system()));
        var competingEvidence = new IdentityHashMap<ScoreExpressiveEvent, Claim>();
        var events = new ArrayList<ScoreExpressiveEvent>(score.expressiveEvents().size());
        for (var event : score.expressiveEvents()) {
            boolean faster = bareFaster(event);
            if ((!gradual(event.kind()) && !faster) || event.end().isPresent()) {
                events.add(event);
                continue;
            }
            var measure = ScoreExpressionDetector.measureOf(event);
            if (measure.isEmpty()) {
                events.add(event);
                continue;
            }
            // This zero is an ordering sentinel only; unresolved starts remain absent in output.
            ScoreAnchor start = event.start().orElse(new ScoreAnchor(measure.getAsInt(), 0));
            ScoreAnchor endpoint = null;
            boolean conflict = false;
            float endpointX = Float.NaN;
            boolean open = false;
            var sourceTexts = new HashSet<String>();
            for (var evidence : event.evidence())
                if (evidence.sourceId().equals("printed-expression-word"))
                    sourceTexts.add(evidence.printedText());
            for (var word : words) {
                if (!sourceTexts.contains(word.text())) continue;
                var span = follow.apply(query(word, row[measure.getAsInt()]));
                if (span.isPresent()
                        && ownershipSystems != systems
                        && competingClaim(
                                event,
                                span.get(),
                                score,
                                words,
                                row,
                                follow,
                                competingEvidence)) continue;
                if (span.isEmpty()
                        || start.measureIndex() >= row.length
                        || row[start.measureIndex()] != span.get().firstSystem()) continue;
                if (span.get().end() == PrintedDashSpan.End.PAGE_EDGE) {
                    open = true;
                    endpointX = span.get().runs().get(span.get().runs().size() - 1).right();
                    continue;
                }
                var end =
                        PrintedDashEndpoint.numeric(
                                span.get(),
                                systems,
                                score.measures(),
                                score.tempoChanges(),
                                start,
                                gx,
                                gy);
                if (end.isEmpty()) continue;
                if (endpoint != null && !endpoint.equals(end.get())) {
                    conflict = true;
                    break;
                }
                endpoint = end.get();
                endpointX = span.get().runs().get(span.get().runs().size() - 1).right();
            }
            if (open && endpoint == null && !conflict) {
                var evidence = new ArrayList<>(event.evidence());
                var first = evidence.get(0);
                evidence.add(
                        new Evidence(
                                OPEN_HINT + (score.measures().size() - measure.getAsInt()),
                                first.pageIndex(),
                                endpointX,
                                event.staffIndex(),
                                event.staffCount(),
                                first.printedText()));
                events.add(
                        new ScoreExpressiveEvent(
                                event.eventId(),
                                event.kind(),
                                event.start(),
                                Optional.empty(),
                                Scope.UNRESOLVED,
                                event.staffIndex(),
                                event.staffCount(),
                                event.targetEventId(),
                                event.strength(),
                                event.qualifierText(),
                                evidence));
                continue;
            }
            if (endpoint == null || conflict || open || faster) {
                events.add(event);
                continue;
            }
            var evidence = new ArrayList<>(event.evidence());
            var first = evidence.get(0);
            evidence.add(
                    new Evidence(
                            SOURCE,
                            first.pageIndex(),
                            endpointX,
                            event.staffIndex(),
                            event.staffCount(),
                            first.printedText()));
            // Versioned, relative evidence survives page offsets without inventing an onset.
            evidence.add(
                    new Evidence(
                            END_HINT + (endpoint.measureIndex() - measure.getAsInt()),
                            first.pageIndex(),
                            endpointX,
                            event.staffIndex(),
                            event.staffCount(),
                            first.printedText()));
            events.add(
                    new ScoreExpressiveEvent(
                            event.eventId(),
                            event.kind(),
                            event.start(),
                            event.start().isPresent() ? Optional.of(endpoint) : Optional.empty(),
                            event.scope(),
                            event.staffIndex(),
                            event.staffCount(),
                            event.targetEventId(),
                            event.strength(),
                            event.qualifierText(),
                            evidence));
        }
        return score.withExpressiveEvents(events);
    }

    static Optional<ScoreAnchor> preserveEnd(
            ScoreExpressiveEvent event,
            ScoreAnchor start,
            ScoreMeterMap meter,
            int measures,
            List<ScoreTempoChange> tempos) {
        if (!gradual(event.kind())
                || event.start().filter(a -> !a.equals(start)).isPresent()
                || event.evidence().stream().noneMatch(e -> e.sourceId().equals(SOURCE)))
            return Optional.empty();
        try {
            if (event.end().isPresent())
                return event.end()
                        .map(e -> e.canonical(meter, measures))
                        .filter(e -> e.compareTo(start) > 0);
            Integer delta = null;
            for (var evidence : event.evidence()) {
                if (!evidence.sourceId().startsWith(END_HINT)) continue;
                String value = evidence.sourceId().substring(END_HINT.length());
                if (!value.matches("[1-9][0-9]{0,3}")) return Optional.empty();
                int parsed = Integer.parseInt(value);
                if (delta != null && delta != parsed) return Optional.empty();
                delta = parsed;
            }
            if (delta == null) return Optional.empty();
            int target = Math.addExact(start.measureIndex(), delta);
            if (target >= measures) return Optional.empty();
            var marks =
                    tempos.stream()
                            .filter(t -> t.measureIndex() == target && t.positionInMeasure() == 0)
                            .toList();
            if (marks.isEmpty()
                    || marks.stream().map(ScoreTempoChange::bpm).distinct().count() != 1)
                return Optional.empty();
            return Optional.of(new ScoreAnchor(target, 0).canonical(meter, measures));
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            return Optional.empty();
        }
    }

    static boolean hasOpen(ScoreExpressiveEvent event) {
        return event.evidence().stream().anyMatch(e -> e.sourceId().startsWith(OPEN_HINT));
    }

    static boolean bareFaster(ScoreExpressiveEvent event) {
        return event.kind() == Kind.UNRESOLVED_DIRECTION
                && event.evidence().stream()
                        .anyMatch(
                                e ->
                                        e.sourceId().equals("printed-expression-word")
                                                && FASTER.matcher(e.printedText()).matches());
    }

    private static boolean gradual(Kind kind) {
        return kind == Kind.ACCELERANDO || kind == Kind.RITARDANDO || kind == Kind.RALLENTANDO;
    }

    private record SpanQuery(PrintedDashSpan.WordBox word, int system) {}

    private static SpanQuery query(PlayingTechniqueDetector.Word word, int system) {
        return new SpanQuery(
                new PrintedDashSpan.WordBox(word.left(), word.right(), word.top(), word.bottom()),
                system);
    }

    private static boolean competingClaim(
            ScoreExpressiveEvent source,
            PrintedDashSpan.Span span,
            ScorePageInterpretation score,
            List<PlayingTechniqueDetector.Word> words,
            int[] row,
            java.util.function.Function<SpanQuery, Optional<PrintedDashSpan.Span>> follow,
            Map<ScoreExpressiveEvent, Claim> competingEvidence) {
        for (var event : score.expressiveEvents()) {
            if (event == source) continue;
            Claim claim = competingEvidence.get(event);
            if (claim == null && !competingEvidence.containsKey(event)) {
                claim = claim(event, row);
                competingEvidence.put(event, claim);
            }
            if (claim == null) continue;
            for (var word : words) {
                if (!claim.texts().contains(word.text())) continue;
                var competing = follow.apply(query(word, claim.system()));
                if (competing.isPresent()
                        && competing.get().runs().stream().anyMatch(span.runs()::contains))
                    return true;
            }
        }
        return false;
    }

    private record Claim(int system, Set<String> texts) {}

    private static Claim claim(ScoreExpressiveEvent event, int[] row) {
        if (!gradual(event.kind()) && !bareFaster(event)) return null;
        var measure = ScoreExpressionDetector.measureOf(event);
        if (measure.isEmpty() || measure.getAsInt() >= row.length) return null;
        var sourceTexts = new HashSet<String>();
        for (var evidence : event.evidence())
            if (evidence.sourceId().equals("printed-expression-word"))
                sourceTexts.add(evidence.printedText());
        if (sourceTexts.isEmpty()) return null;
        return new Claim(row[measure.getAsInt()], sourceTexts);
    }

    // Measure rectangles include engraving gutters. Only complete independently
    // proved staff rails may narrow ownership; numeric endpoints keep logical bands.
    private static List<PrintedDashSpan.SystemBand> printedOwnershipSystems(
            List<PrintedDashSpan.SystemBand> systems,
            List<PlayingTechniqueDetector.Staff> staffs,
            byte[] gray,
            int width,
            int height) {
        var proved = new ArrayList<PrintedDashSpan.SystemBand>();
        for (var system : systems) {
            PlayingTechniqueDetector.Staff owner = null;
            for (var staff : staffs) {
                float center = (staff.top() + staff.bottom()) * .5f / height;
                if (center < system.top() || center > system.bottom()) continue;
                if (owner != null || staff.index() != 0 || staff.count() != 1) return systems;
                owner = staff;
            }
            if (owner == null) return systems;
            float gap = owner.gap();
            float[] firstWitness = null;
            float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
            float bottoms = 0, gaps = 0;
            int witnesses = 0;
            for (float part : STAFF_PROBE_FRACTIONS) {
                float x = (system.left() + (system.right() - system.left()) * part) * width;
                var found =
                        PrintedDirectionStaff.printedRules(
                                gray, width, height, x, owner.bottom(), gap);
                if (found == null) continue;
                if (firstWitness != null
                        && (Math.abs(
                                                (firstWitness[0] - 4 * firstWitness[1])
                                                        - (found[0] - 4 * found[1]))
                                        > gap * .2f
                                || Math.abs(firstWitness[1] - found[1]) > gap * .08f))
                    return systems;
                if (firstWitness == null) firstWitness = found;
                witnesses++;
                bottoms += found[0];
                gaps += found[1];
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
            }
            if (witnesses < 2 || maxX - minX < gap * 6) return systems;
            float bottom = bottoms / witnesses, spacing = gaps / witnesses;
            float top = bottom - 4 * spacing;
            if (Math.abs(top - owner.top()) > gap * .35f
                    || Math.abs(spacing - gap) > gap * .12f
                    || top / height < system.top() - gap / height * .3f
                    || bottom / height > system.bottom() + gap / height * .3f) return systems;
            proved.add(
                    new PrintedDashSpan.SystemBand(
                            system.left(),
                            system.right(),
                            top / height,
                            bottom / height,
                            system.lastColumn()));
        }
        return List.copyOf(proved);
    }
}
