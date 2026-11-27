// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Decoded-data bridge to the same navigation engine, without pixels or inference. */
public final class NavigationBridge {
    private NavigationBridge() {}

    static ScoreNavigationPlan read(DataInputStream input) throws IOException {
        int version = input.readInt();
        boolean exact = version == 0x4e415632;
        if (!exact && version != 0x4e415631) throw new IOException("Not a navigation request");
        int count = input.readInt();
        if (count < 0 || count > 100000) throw new IOException("Invalid source measure count");
        var durations = new ArrayList<Double>(count);
        for (int m = 0; m < count; m++) {
            double beats = exact ? input.readDouble() : input.readFloat();
            if (!Double.isFinite(beats) || beats < .125 || beats > 128)
                throw new IOException("Invalid measure beats");
            durations.add(beats);
        }
        var directions = ScoreSemanticWire.readDirections(input, count);
        if (!ScoreSemanticWire.readExpressions(input, count).isEmpty())
            throw new IOException("Navigation request contains expressive realization");
        if (input.read() != -1) throw new IOException("Trailing navigation request bytes");
        return ScoreNavigationPlan.create(
                count, directions, ScoreMeterMap.fromExactPerformedDurations(durations));
    }

    record DocumentRoute(ScoreNavigationTraversal.Result route, List<ScorePlaybackDirection> recoveredDirections) {}

    static DocumentRoute readDocument(DataInputStream input) throws IOException {
        int version = input.readInt();
        boolean sourceProof = version == 0x4e415634;
        if (!sourceProof && version != 0x4e415633) throw new IOException("Not a NAV3 or NAV4 request");
        int count = input.readInt();
        if (count < 0 || count > 100000) throw new IOException("Invalid source measure count");
        var durations = new ArrayList<Double>(count);
        for (int m = 0; m < count; m++) {
            double beats = input.readDouble();
            if (!Double.isFinite(beats) || beats < .125 || beats > 128)
                throw new IOException("Invalid measure beats");
            durations.add(beats);
        }
        var directions = ScoreSemanticWire.readDirections(input, count);
        int pageCount = input.readInt();
        if (pageCount < 1 || pageCount > 100000) throw new IOException("Invalid source page count");
        var pages = new ArrayList<ScorePageInterpretation>(pageCount);
        var adjacency = new BitSet(pageCount);
        var boundAdjacency = new BitSet(pageCount);
        var placeholder = new MeasureRegion(0, 1, 0, 1);
        int remaining = count;
        for (int p = 0; p < pageCount; p++) {
            int pageMeasures = input.readInt(), first = input.readInt();
            if (pageMeasures < 0 || pageMeasures > remaining || first < 0)
                throw new IOException("Invalid source page mapping");
            int offset = count - remaining;
            remaining -= pageMeasures;
            if (input.readBoolean()) adjacency.set(p);
            if (sourceProof && input.readBoolean()) {
                if (p == 0 || !adjacency.get(p)) throw new IOException("Invalid bound page adjacency");
                boundAdjacency.set(p);
            }
            var expressions = ScoreSemanticWire.readExpressions(input, pageMeasures);
            var localStarts = expressions.stream().flatMap(event -> event.start().stream())
                    .filter(anchor -> anchor.measureIndex() > 0 && anchor.measureIndex() < pageMeasures)
                    .flatMap(anchor -> directions.stream().filter(direction ->
                            direction.kind() == ScorePlaybackDirection.Kind.REPEAT_START
                                    && direction.measureBoundary() == offset + anchor.measureIndex()))
                    .distinct().map(direction -> direction.offset(-offset)).toList();
            pages.add(new ScorePageInterpretation(Collections.nCopies(pageMeasures, placeholder),
                    List.of(), first, List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), localStarts).withExpressiveEvents(expressions));
        }
        if (remaining != 0 || input.read() != -1) throw new IOException("Invalid navigation page extent");
        var meter = ScoreMeterMap.fromExactPerformedDurations(durations);
        var recovered = PrintedRepeatDocument.validatedTransport(
                pages, directions, count, meter, adjacency::get, boundAdjacency::get, !sourceProof);
        var combined = new ArrayList<>(directions);
        combined.addAll(recovered.directions());
        var route = ScoreNavigationPlan.create(count, combined, meter).traversal();
        route.requirePerformanceQualified();
        return new DocumentRoute(route, recovered.directions());
    }


    static Map<Integer, List<ScorePlaybackDirection>> readRepeatCaptions(DataInputStream input)
            throws IOException {
        if (input.readInt() != 0x4e415234) throw new IOException("Not a NAR4 request");
        int size = input.readInt();
        if (size < 2 || size > 100000) throw new IOException("Invalid caption page count");
        int[] counts = new int[size], starts = new int[size];
        var pages = new ArrayList<ScorePageInterpretation>(size);
        var pitchPages = new ArrayList<List<List<Integer>>>(size);
        var durationPages = new ArrayList<List<Double>>(size);
        var adjacency = new BitSet(size);
        var placeholder = new MeasureRegion(0, 1, 0, 1);
        int total = 0;
        for (int page = 0; page < size; page++) {
            counts[page] = input.readInt();
            starts[page] = input.readInt();
            if (counts[page] < 0 || counts[page] > 100000 - total || starts[page] < 0)
                throw new IOException("Invalid caption page extent");
            total += counts[page];
            if (input.readBoolean()) adjacency.set(page);
            var durations = new ArrayList<Double>(counts[page]);
            var pitchBars = new ArrayList<List<Integer>>(counts[page]);
            for (int bar = 0; bar < counts[page]; bar++) {
                double beats = input.readDouble();
                if (!Double.isFinite(beats) || beats < .125 || beats > 128)
                    throw new IOException("Invalid measure beats");
                durations.add(beats);
                int attacks = input.readInt();
                if (attacks < 0 || attacks > 16384) throw new IOException("Invalid caption attack count");
                var pitches = new ArrayList<Integer>(attacks);
                for (int i = 0; i < attacks; i++) pitches.add(input.readInt());
                pitchBars.add(pitches);
            }
            var directions = ScoreSemanticWire.readDirections(input, counts[page]);
            var expressions = ScoreSemanticWire.readExpressions(input, counts[page]);
            pages.add(new ScorePageInterpretation(Collections.nCopies(counts[page], placeholder),
                    List.of(), starts[page], List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), directions).withExpressiveEvents(expressions));
            pitchPages.add(pitchBars);
            durationPages.add(durations);
        }
        if (input.read() != -1) throw new IOException("Trailing repeat caption request bytes");
        var arrangements = ScorePageTimeline.arrangements(counts, starts);
        var candidates = PrintedRepeatDocument.resolveCaptionRoutes(pages, arrangements, pitchPages::get);
        var result = new LinkedHashMap<Integer, List<ScorePlaybackDirection>>();
        var offsets = new int[size];
        for (var range : arrangements) {
            var durations = new ArrayList<Double>();
            var directions = new ArrayList<ScorePlaybackDirection>();
            for (int page = range.firstPage(); page < range.pageAfterLast(); page++) {
                offsets[page] = durations.size();
                durations.addAll(durationPages.get(page));
                for (var direction : pages.get(page).playbackDirections())
                    directions.add(direction.offset(offsets[page]));
            }
            if (candidates.keySet().stream().noneMatch(page ->
                    page >= range.firstPage() && page < range.pageAfterLast())) continue;
            var meter = ScoreMeterMap.fromExactPerformedDurations(durations);
            int count = durations.size();
            directions.addAll(PrintedRepeatDocument.validated(
                    pages.subList(range.firstPage(), range.pageAfterLast()), directions, count, meter,
                    page -> adjacency.get(page + range.firstPage())).directions());
            var accepted = PrintedRepeatDocument.validatedCaptionPlayback(
                    candidates, range, offsets, directions, count, meter).directions();
            var ids = new HashSet<String>();
            for (var direction : accepted) ids.add(direction.details().eventId());
            for (int page = range.firstPage(); page < range.pageAfterLast(); page++) {
                var additions = candidates.getOrDefault(page, List.of()).stream()
                        .filter(direction -> ids.contains(direction.details().eventId())).toList();
                if (!additions.isEmpty()) result.put(page, additions);
            }
        }
        return result;
    }

    static Map<Integer, ScorePlaybackDirection> readSameDocumentSegno(DataInputStream input)
            throws IOException {
        if (input.readInt() != 0x4e415235) throw new IOException("Not a NAR5 request");
        int size = input.readInt();
        if (size < 2 || size > 10000) throw new IOException("Invalid edition page count");
        int[] counts = new int[size], starts = new int[size];
        var pages = new ArrayList<SameDocumentSegno.Page>(size);
        var durationPages = new ArrayList<List<Double>>(size);
        int total = 0;
        for (int p = 0; p < size; p++) {
            counts[p] = input.readInt();
            starts[p] = input.readInt();
            int physical = input.readInt(), physicalCount = input.readInt();
            String digest = input.readUTF();
            if (counts[p] < 0 || counts[p] > 100000 - total || starts[p] < 0
                    || !digest.isEmpty() && !digest.matches("[0-9a-f]{64}"))
                throw new IOException("Invalid edition page identity");
            total += counts[p];
            var durations = new ArrayList<Double>(counts[p]);
            var bars = new ArrayList<List<List<SameDocumentSegno.Attack>>>(counts[p]);
            for (int m = 0; m < counts[p]; m++) {
                double beats = input.readDouble();
                int staves = input.readInt();
                if (!Double.isFinite(beats) || beats < .125 || beats > 128 || staves < 0 || staves > 64)
                    throw new IOException("Invalid edition bar");
                durations.add(beats);
                var bar = new ArrayList<List<SameDocumentSegno.Attack>>(staves);
                for (int staff = 0; staff < staves; staff++) {
                    int count = input.readInt();
                    if (count < 0 || count > 16384) throw new IOException("Invalid edition note count");
                    var attacks = new ArrayList<SameDocumentSegno.Attack>(count);
                    for (int n = 0; n < count; n++)
                        attacks.add(new SameDocumentSegno.Attack(input.readInt(), input.readInt(), input.readInt()));
                    bar.add(attacks);
                }
                bars.add(bar);
            }
            var directions = ScoreSemanticWire.readDirections(input, counts[p]);
            if (!ScoreSemanticWire.readExpressions(input, counts[p]).isEmpty())
                throw new IOException("Unexpected edition expressions");
            pages.add(new SameDocumentSegno.Page(digest, physical, physicalCount, starts[p], directions, bars));
            durationPages.add(durations);
        }
        if (input.read() != -1) throw new IOException("Trailing edition request bytes");
        var ranges = ScorePageTimeline.arrangements(counts, starts);
        var result = new LinkedHashMap<Integer, ScorePlaybackDirection>();
        for (var range : ranges) {
            var transfer = SameDocumentSegno.resolve(pages, ranges, range);
            if (transfer == null) continue;
            var offsets = ScorePageTimeline.playbackOffsets(counts, starts, range);
            var durations = new ArrayList<Double>();
            var directions = new ArrayList<ScorePlaybackDirection>();
            for (int p = range.firstPage(); p < range.pageAfterLast(); p++) {
                durations.addAll(durationPages.get(p));
                for (var direction : pages.get(p).directions())
                    directions.add(direction.offset(offsets[p]));
            }
            var recovered = new ScorePlaybackDirection(transfer.measure(), ScorePlaybackDirection.Kind.SEGNO);
            directions.add(recovered.offset(offsets[transfer.page()]));
            var meter = ScoreMeterMap.fromExactPerformedDurations(durations);
            if (ScoreNavigationPlan.create(durations.size(), directions, meter).traversal().performanceQualified())
                result.put(transfer.page(), recovered);
        }
        return result;
    }

    static Object readArrangements(DataInputStream input) throws IOException {
        if (input.readInt() != 0x4e415031) throw new IOException("Not a NAP1 request");
        int pages = input.readInt();
        if (pages < 0 || pages > 100000) throw new IOException("Invalid source page count");
        int[] counts = new int[pages], starts = new int[pages];
        long total = 0;
        for (int p = 0; p < pages; p++) {
            counts[p] = input.readInt();
            starts[p] = input.readInt();
            total += counts[p];
            if (counts[p] < 0 || total > 100000 || starts[p] < 0)
                throw new IOException("Invalid source page mapping");
        }
        if (input.read() != -1) throw new IOException("Trailing page mapping bytes");
        return ScorePageTimeline.arrangements(counts, starts);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2)
            throw new IllegalArgumentException("Usage: NavigationBridge request.nav output.json");
        var path = Path.of(args[0]);
        if (Files.size(path) > 64L * 1024 * 1024)
            throw new IOException("Navigation request too large");
        Object result;
        try (var input = new DataInputStream(Files.newInputStream(path))) {
            int magic = input.readInt();
            if (magic == 0x4e415031) {
                try (var mappingInput = new DataInputStream(Files.newInputStream(path))) {
                    result = readArrangements(mappingInput);
                }
            } else {
                try (var routeInput = new DataInputStream(Files.newInputStream(path))) {
                    if (magic == 0x4e415633 || magic == 0x4e415634) result = readDocument(routeInput);
                    else if (magic == 0x4e415234) result = readRepeatCaptions(routeInput);
                    else if (magic == 0x4e415235) result = readSameDocumentSegno(routeInput);
                    else {
                        var route = read(routeInput).traversal();
                        route.requirePerformanceQualified();
                        result = route;
                    }
                }
            }
        }
        Files.writeString(Path.of(args[1]), Main.json(result) + "\n");
    }
}
