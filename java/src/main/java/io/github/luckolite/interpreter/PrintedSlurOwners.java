// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;

/** Binds a proved raw bow to explicit printed heads and written attacks, independently of ties. */
final class PrintedSlurOwners {
    interface Location {
        int owner();

        float x();

        float y();

        int staffIndex();

        int staffCount();

        default boolean grace() {
            return false;
        }
    }

    /** Pixel ownership only; no invented voice, attack or pitch. */
    record GeometryHead(int owner, float x, float y, int staffIndex, int staffCount, boolean grace)
            implements Location {
        GeometryHead(int owner, float x, float y, int staffIndex, int staffCount) {
            this(owner, x, y, staffIndex, staffCount, false);
        }

        GeometryHead {
            if (owner < 0
                    || !Float.isFinite(x + y)
                    || x < 0
                    || y < 0
                    || staffCount < 1
                    || staffIndex < 0
                    || staffIndex >= staffCount)
                throw new IllegalArgumentException("Invalid printed geometry ownership");
        }
    }

    record Head(
            int owner,
            float x,
            float y,
            int staffIndex,
            int staffCount,
            int voice,
            long attack,
            int graceOrder,
            int soundingPitch)
            implements Location {
        Head(
                int owner,
                float x,
                float y,
                int staffIndex,
                int staffCount,
                int voice,
                long attack,
                int soundingPitch) {
            this(owner, x, y, staffIndex, staffCount, voice, attack, 0, soundingPitch);
        }

        public boolean grace() {
            return graceOrder != 0;
        }

        Head {
            if (owner < 0
                    || !Float.isFinite(x + y)
                    || x < 0
                    || y < 0
                    || staffCount < 1
                    || staffIndex < 0
                    || staffIndex >= staffCount
                    || voice < 0
                    || attack < 0
                    || graceOrder < -1000000
                    || graceOrder > 1000000
                    || soundingPitch < -1
                    || soundingPitch > 127)
                throw new IllegalArgumentException("Invalid printed head ownership");
        }
    }

    record Tie(int startOwner, int endOwner) {
        Tie {
            if (startOwner < 0 || endOwner < 0 || startOwner == endOwner)
                throw new IllegalArgumentException("Invalid retained tie owners");
        }
    }

    enum Status {
        RESOLVED,
        GEOMETRY_OWNED,
        NO_START_OWNER,
        NO_END_OWNER,
        AMBIGUOUS_START,
        AMBIGUOUS_END,
        DIFFERENT_LANES,
        BEAM_RAIL,
        VOICE_UNRESOLVED,
        REVERSED_CLOCK,
        SAME_OWNER,
        TIE_ALREADY_RETAINED,
        TIE_PITCH_CONFLICT,
        SAME_PITCH_UNRESOLVED,
        UNPITCHED_UNRESOLVED
    }

    record Result(Status status, Optional<ScoreSlurPages.Span> span) {
        Result {
            Objects.requireNonNull(status);
            Objects.requireNonNull(span);
            if ((status == Status.RESOLVED) != span.isPresent())
                throw new IllegalArgumentException("Slur resolution status and source disagree");
        }
    }

    record Binding(Status status, Optional<ScoreSlurPages.Span> span) {
        Binding {
            Objects.requireNonNull(status);
            Objects.requireNonNull(span);
            if ((status == Status.GEOMETRY_OWNED) != span.isPresent())
                throw new IllegalArgumentException("Geometry binding status disagrees with source");
        }
    }

    private record Candidate(Location head, double distance, boolean wideChord) {
        Candidate(Location head, double distance) {
            this(head, distance, false);
        }
    }

    private PrintedSlurOwners() {}

    static Result resolve(
            String id,
            int page,
            PrintedSlurContours.Bow bow,
            List<Head> heads,
            Set<Tie> ties,
            byte[] gray,
            int width,
            int height,
            StaffPitchTrack track) {
        Objects.requireNonNull(ties);
        return resolveBinding(bind(id, page, bow, heads, gray, width, height, track), heads, ties);
    }

    static Result resolvePixelCells(
            String id,
            int page,
            PrintedSlurContours.Bow bow,
            List<Head> heads,
            Set<Tie> ties,
            byte[] gray,
            int width,
            int height,
            StaffPitchTrack track) {
        Objects.requireNonNull(ties);
        return resolveBinding(
                bindPixelCells(id, page, bow, heads, gray, width, height, track), heads, ties);
    }

    static Result resolveBinding(Binding binding, List<Head> heads, Set<Tie> ties) {
        if (binding.span().isEmpty()) return absent(binding.status());
        var arc = binding.span().orElseThrow();
        var byOwner = new HashMap<Integer, Head>();
        for (var head : heads) byOwner.put(head.owner(), head);
        Head a = byOwner.get(arc.start().noteIndex()), b = byOwner.get(arc.end().noteIndex());
        // A printed slur may connect voices within the same proved physical staff.
        // Unique raw endpoints and written order identify it without assigning a voice.
        int order = Long.compare(a.attack(), b.attack());
        if (order == 0) order = Integer.compare(a.graceOrder(), b.graceOrder());
        if (order >= 0) return absent(Status.REVERSED_CLOCK);
        if (a.soundingPitch() < 0 || b.soundingPitch() < 0)
            return absent(Status.UNPITCHED_UNRESOLVED);
        if (ties.contains(new Tie(a.owner(), b.owner())))
            return absent(
                    a.soundingPitch() == b.soundingPitch()
                            ? Status.TIE_ALREADY_RETAINED
                            : Status.TIE_PITCH_CONFLICT);
        if (a.soundingPitch() == b.soundingPitch()) return absent(Status.SAME_PITCH_UNRESOLVED);
        return new Result(Status.RESOLVED, Optional.of(arc));
    }

    static Binding bind(
            String id,
            int page,
            PrintedSlurContours.Bow bow,
            List<? extends Location> heads,
            byte[] gray,
            int width,
            int height,
            StaffPitchTrack track) {
        return binding(id, page, bow, heads, gray, width, height, track, 0);
    }

    static Binding bindPixelCells(
            String id,
            int page,
            PrintedSlurContours.Bow bow,
            List<? extends Location> heads,
            byte[] gray,
            int width,
            int height,
            StaffPitchTrack track) {
        return binding(id, page, bow, heads, gray, width, height, track, .5f);
    }

    private static Binding binding(
            String id,
            int page,
            PrintedSlurContours.Bow bow,
            List<? extends Location> heads,
            byte[] gray,
            int width,
            int height,
            StaffPitchTrack track,
            float horizontalCellRadius) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(bow);
        if (id.isBlank() || page < 0)
            throw new IllegalArgumentException("Invalid source arc identity");
        if (gray == null
                || gray.length != (long) width * height
                || width <= 0
                || height <= 0
                || track == null
                || !track.verified())
            throw new IllegalArgumentException(
                    "Printed slur ownership needs its verified source pixels");
        var ids = new HashSet<Integer>();
        for (var head : heads)
            if (!ids.add(head.owner()))
                throw new IllegalArgumentException("Duplicate printed head identity");
        var start =
                candidates(
                        bow.start(),
                        bow.above(),
                        true,
                        heads,
                        gray,
                        width,
                        height,
                        track,
                        horizontalCellRadius);
        var end =
                candidates(
                        bow.end(),
                        bow.above(),
                        false,
                        heads,
                        gray,
                        width,
                        height,
                        track,
                        horizontalCellRadius);
        if (ambiguous(start))
            start = exteriorChordCap(start, bow.start(), bow.above(), gray, width, height, track);
        if (ambiguous(end))
            end = exteriorChordCap(end, bow.end(), bow.above(), gray, width, height, track);
        float gap = track.at(bow.start().x())[1];
        if (start.isEmpty() && bow.end().x() - bow.start().x() < gap * 3.4f) {
            var a = compactShaftCap(bow.start(), bow.above(), true, heads, gray, width, height, track);
            var b = compactShaftCap(bow.end(), bow.above(), false, heads, gray, width, height, track);
            if (a != null && b != null && b.x() > a.x() + gap
                    && a.staffIndex() == b.staffIndex() && a.staffCount() == b.staffCount()
                    && (end.isEmpty() || end.stream().allMatch(c ->
                            c.head().staffIndex() == b.staffIndex()
                                    && c.head().staffCount() == b.staffCount()
                                    && Math.abs(c.head().x() - b.x()) <= gap * .18f))) {
                start = List.of(new Candidate(a, 0));
                end = List.of(new Candidate(b, 0));
            }
        }
        if ((start.isEmpty() || end.isEmpty() || ambiguous(start) || ambiguous(end))
                && bow.above() && bow.bendInGaps() >= .38f && bow.bendInGaps() <= 1.25f
                && bow.rmsInGaps() < .075f
                && bow.end().x() - bow.start().x() >= gap * 1.85f
                && bow.end().x() - bow.start().x() <= gap * 5.60f) {
            var a = rearOctaveContourOwner(bow.start(), true, heads, gray, width, height, track);
            var b = rearOctaveContourOwner(bow.end(), false, heads, gray, width, height, track);
            if (a != null && b != null && b.x() - a.x() >= gap * 1.55f
                    && b.x() - a.x() <= gap * 4.85f
                    && (start.isEmpty() || start.stream().anyMatch(x -> x.head().owner() == a.owner()))
                    && (end.isEmpty() || end.stream().anyMatch(x -> x.head().owner() == b.owner()))
                    && !followsParallelBeam(bow, gray, width, height, gap)) {
                start = List.of(new Candidate(a, 0, true));
                end = List.of(new Candidate(b, 0, true));
            }
        }
        if (start.isEmpty()) return new Binding(Status.NO_START_OWNER, Optional.empty());
        if (end.isEmpty() && !ambiguous(start) && start.get(0).head().grace())
            end =
                    gracePairEndCandidates(
                            bow.end(),
                            bow.above(),
                            start.get(0).head(),
                            heads,
                            gray,
                            width,
                            height,
                            track);
        if (end.isEmpty()) return new Binding(Status.NO_END_OWNER, Optional.empty());
        if (ambiguous(start)) return new Binding(Status.AMBIGUOUS_START, Optional.empty());
        if (ambiguous(end)) return new Binding(Status.AMBIGUOUS_END, Optional.empty());
        if ((start.get(0).wideChord() || end.get(0).wideChord())
                && followsParallelBeam(bow, gray, width, height, gap))
            return new Binding(Status.BEAM_RAIL, Optional.empty());
        Location a = start.get(0).head(), b = end.get(0).head();
        if (start.get(0).wideChord()
                && !hasFacingEndpoint(bow.end(), b, bow.above(), heads, gray, width, height, track))
            return new Binding(Status.NO_END_OWNER, Optional.empty());
        if (end.get(0).wideChord()
                && !hasFacingEndpoint(bow.start(), a, bow.above(), heads, gray, width, height, track))
            return new Binding(Status.NO_START_OWNER, Optional.empty());
        if (a.owner() == b.owner()) return new Binding(Status.SAME_OWNER, Optional.empty());
        if (a.staffIndex() != b.staffIndex() || a.staffCount() != b.staffCount())
            return new Binding(Status.DIFFERENT_LANES, Optional.empty());
        return geometryBinding(id, page, bow, a, b, width, height);
    }

    static Binding literalOctaveBinding(
            String id, int page, PrintedSlurContours.Bow bow,
            List<? extends Location> heads, byte[] gray, int width, int height, StaffPitchTrack track) {
        var existing = bind(id, page, bow, heads, gray, width, height, track);
        if (existing.span().isPresent()) return existing;
        var a = rearOctaveContourOwner(bow.start(), true, heads, gray, width, height, track);
        var b = rearOctaveContourOwner(bow.end(), false, heads, gray, width, height, track);
        if (a == null || b == null || a.x() >= b.x()
                || a.staffIndex() != b.staffIndex() || a.staffCount() != b.staffCount()
                || followsParallelBeam(bow, gray, width, height, track.at(a.x())[1]))
            return new Binding(Status.NO_START_OWNER, Optional.empty());
        return geometryBinding(id, page, bow, a, b, width, height);
    }

    static Binding geometryBinding(
            String id, int page, PrintedSlurContours.Bow bow, Location a, Location b,
            int width, int height) {
        var span =
                new ScoreSlurPages.Span(
                        id,
                        new ScoreSlurPages.Anchor(page, a.owner()),
                        new ScoreSlurPages.Anchor(page, b.owner()),
                        bow.above() ? ScoreSlurSpan.Placement.ABOVE : ScoreSlurSpan.Placement.BELOW,
                        ScoreSlurSpan.LineType.SOLID,
                        List.of(
                                new ScoreSlurSpan.ArcEvidence(
                                        page,
                                        bow.start().x() / (float) width,
                                        bow.start().y() / height,
                                        bow.end().x() / (float) width,
                                        bow.end().y() / height)));
        return new Binding(Status.GEOMETRY_OWNED, Optional.of(span));
    }

    private static Result absent(Status status) {
        return new Result(status, Optional.empty());
    }

    /** Bounded per-column head hints; no synthetic pixels or full-page mask allocation. */
    static PrintedSlurContours.HeadEvidence headEvidence(
            List<? extends Location> heads,
            byte[] gray,
            int width,
            int height,
            StaffPitchTrack track) {
        record Visible(Location head, float gap) {}
        var columns = new ArrayList<List<Visible>>(Collections.nCopies(width, null));
        var independentlyVisible = new ArrayList<Visible>();
        for (var head : heads) {
            float gap = track.at(head.x())[1];
            if (!headBody(gray, width, height, head, gap)) continue;
            var visible = new Visible(head, gap);
            independentlyVisible.add(visible);
            for (int x = Math.max(0, Math.round(head.x() - gap * .72f));
                    x < width && x <= head.x() + gap * .72f;
                    x++) {
                if (columns.get(x) == null) columns.set(x, new ArrayList<>());
                columns.get(x).add(visible);
            }
        }
        return new PrintedSlurContours.HeadEvidence() {
            private boolean inside(Visible visible, int x, int y) {
                double dx = (x - visible.head().x()) / (visible.gap() * .72);
                double dy = (y - visible.head().y()) / (visible.gap() * .55);
                return dx * dx + dy * dy <= 1;
            }

            public long returningOwnerCost() {
                return independentlyVisible.size();
            }

            public boolean ownsReturningBow(PrintedSlurContours.Bow bow) {
                Location first = null, last = null;
                for (var visible : independentlyVisible) {
                    var head = visible.head();
                    for (int side = 0; side < 2; side++) {
                        var cap = side == 0 ? bow.start() : bow.end();
                        double dx = Math.abs(cap.x() - head.x()) / visible.gap();
                        double clearance =
                                (bow.above() ? head.y() - cap.y() : cap.y() - head.y())
                                        / visible.gap();
                        if (dx > .85
                                || clearance < -.2
                                || clearance > 2
                                || (side == 0 ? cap.x() + 1 < head.x() : cap.x() - 1 > head.x()))
                            continue;
                        if (side == 0) {
                            if (first != null && first.owner() != head.owner()) return false;
                            first = head;
                        } else {
                            if (last != null && last.owner() != head.owner()) return false;
                            last = head;
                        }
                    }
                }
                return first != null
                        && last != null
                        && first.owner() != last.owner()
                        && first.staffIndex() == last.staffIndex()
                        && first.staffCount() == last.staffCount();
            }

            public boolean contains(int x, int y) {
                if (x < 0
                        || x >= width
                        || y < 0
                        || y >= height
                        || (gray[y * width + x] & 255) > 205
                        || columns.get(x) == null) return false;
                for (var visible : columns.get(x)) if (inside(visible, x, y)) return true;
                return false;
            }

            public boolean closes(PrintedSlurContours.Point cap, int x, int y, boolean first) {
                if (x < 0
                        || x >= width
                        || y < 0
                        || y >= height
                        || (gray[y * width + x] & 255) > 205
                        || columns.get(x) == null) return false;
                for (var visible : columns.get(x)) {
                    var head = visible.head();
                    boolean body = inside(visible, x, y);
                    // Raster fringe ink must touch strict dark ink of this independently
                    // proved head body. White pixels, a detached mark, and a bare rail
                    // cannot borrow the one-pixel boundary adjacency.
                    for (int dy = -1; !body && dy <= 1; dy++)
                        for (int dx = -1; !body && dx <= 1; dx++)
                            if (x + dx >= 0
                                    && x + dx < width
                                    && y + dy >= 0
                                    && y + dy < height
                                    && inside(visible, x + dx, y + dy)
                                    && (gray[(y + dy) * width + x + dx] & 255) < 165) body = true;
                    if (!body
                            || Math.abs(cap.x() - head.x()) > visible.gap() * .85f
                            || Math.abs(cap.y() - head.y()) > visible.gap() * 2) continue;
                    if (first ? cap.x() + 1 < head.x() : cap.x() - 1 > head.x()) {
                        // A returning tip can overlap the facing rim before the head's
                        // centroid. Its actual outward continuation must touch strict
                        // ink inside this independently proved head body.
                        if (!inside(visible, x, y)
                                || (gray[y * width + x] & 255) >= 165
                                || (first ? x >= cap.x() : x <= cap.x())) continue;
                    }
                    return true;
                }
                return false;
            }
        };
    }

    private static boolean ambiguous(List<Candidate> values) {
        return values.size() > 1 && values.get(1).distance() - values.get(0).distance() < .2;
    }

    /** The exterior ends of the bow face the two upper heads of independently printed octaves. */
    private static Location rearOctaveContourOwner(
            PrintedSlurContours.Point cap,
            boolean first,
            List<? extends Location> heads,
            byte[] gray,
            int width,
            int height,
            StaffPitchTrack track) {
        float gap = track.at(cap.x())[1];
        Location matched = null;
        for (var upper : heads) {
            float dx = (upper.x() - cap.x()) * (first ? 1 : -1);
            float clearance = upper.y() - cap.y();
            if (upper.grace() || upper.staffCount() != 1 || dx < -gap * .30f || dx > gap * .77f
                    || clearance < gap * .32f || clearance > gap * 1.38f
                    || !headBody(gray, width, height, upper, gap)) continue;
            int count = 0;
            for (var lower : heads) {
                float spread = lower.y() - upper.y();
                if (lower.grace() || lower.staffIndex() != upper.staffIndex()
                        || lower.staffCount() != upper.staffCount()
                        || Math.abs(lower.x() - upper.x()) > gap * .18f
                        || spread < gap * 3.15f || spread > gap * 3.85f
                        || !headBody(gray, width, height, lower, gap)
                        || (!stemReaches(gray, width, height, upper, lower, gap, -1)
                                && !sameObservedDownshaft(gray, width, height, upper, lower, gap)))
                    continue;
                count++;
            }
            if (count == 1) {
                if (matched != null) return null;
                matched = upper;
            }
        }
        return matched;
    }

    /** Compare the printed column and tip directly when the head-center offset is at its edge. */
    private static boolean sameObservedDownshaft(
            byte[] gray, int width, int height, Location upper, Location lower, float gap) {
        var budget = new PrintedBeamShafts.Budget(75_000);
        var top = PrintedBeamShafts.at(gray, width, height, upper.x(), upper.y(), gap, budget);
        var bottom = PrintedBeamShafts.at(gray, width, height, lower.x(), lower.y(), gap, budget);
        if (top.size() != 1 || bottom.size() != 1) return false;
        var a = top.get(0);
        var b = bottom.get(0);
        return a.direction() == 1 && b.direction() == 1
                && Math.max(a.left(), b.left()) <= Math.min(a.right(), b.right())
                && Math.abs(a.endY() - b.endY()) <= gap * .13f
                && Math.min(a.endY(), b.endY()) - lower.y() >= gap * 1.85f;
    }

    /** A curve outside an aligned chord owns its facing outer head. */
    private static List<Candidate> exteriorChordCap(
            List<Candidate> values, PrintedSlurContours.Point cap, boolean above,
            byte[] gray, int width, int height, StaffPitchTrack track) {
        float gap = track.at(cap.x())[1];
        var first = values.get(0).head();
        Location outer = first;
        boolean wideChord = false;
        for (var candidate : values) {
            if (candidate.distance() - values.get(0).distance() >= .2) break;
            var head = candidate.head();
            if (head.owner() == first.owner()) continue;
            float separation = Math.abs(head.y() - first.y()) / gap;
            if (head.grace() || first.grace() || head.staffIndex() != first.staffIndex()
                    || head.staffCount() != first.staffCount()
                    || Math.abs(head.x() - first.x()) > gap * .18f
                    || separation < .7f || separation > 4.75f
                    || separation > 2.5f
                            && !sharedChordStem(gray, width, height, first, head, gap))
                return values;
            wideChord |= separation > 2.5f;
            if (above ? head.y() < outer.y() : head.y() > outer.y()) outer = head;
        }
        double clearance = (above ? outer.y() - cap.y() : cap.y() - outer.y()) / gap;
        if (clearance < .3 || clearance > 4
                || !headBody(gray, width, height, outer, gap)
                || clearance > 1.65 && !shaftSupports(cap, outer, above, gray, width, height, gap,
                        values.stream().map(Candidate::head).toList())) return values;
        return List.of(new Candidate(outer, values.get(0).distance(), wideChord));
    }

    /** A chord shaft can lead a projected contour to the edge of a pair of beams. */
    static boolean followsParallelBeam(
            PrintedSlurContours.Bow bow, byte[] gray, int width, int height, float gap) {
        int margin = Math.max(2, Math.round(gap * .37f));
        int left = bow.start().x() + margin, right = bow.end().x() - margin;
        int minimumRun = Math.round(gap * 1.9f);
        if (right - left < minimumRun) return false;
        int railDepth = Math.max(4, Math.round(gap * .30f));
        int neighborDepth = Math.max(3, Math.round(gap * .24f));
        int maxDepth = Math.round(gap);
        int maxGap = Math.round(gap * .75f);
        int verticalMargin = maxDepth + maxGap + neighborDepth + 2;
        int coverage = 0, run = 0, longest = 0, last = -2;
        for (var point : bow.points()) {
            int x = point.x(), center = Math.round(point.y());
            if (x < left
                    || x > right
                    || x < 0
                    || x >= width
                    || center < verticalMargin
                    || center >= height - verticalMargin) continue;
            if ((gray[center * width + x] & 255) >= 165) {
                if ((gray[(center - 1) * width + x] & 255) < 165) center--;
                else if ((gray[(center + 1) * width + x] & 255) < 165) center++;
                else continue;
            }
            int top = offsetOfLight(gray, width, x, center, -1, maxDepth);
            int bottom = offsetOfLight(gray, width, x, center, 1, maxDepth);
            if (top + bottom - 1 < railDepth || top + bottom - 1 > maxDepth) continue;
            if (!separatedRail(gray, width, x, center - top, -1, maxGap, neighborDepth)
                    && !separatedRail(gray, width, x, center + bottom, 1, maxGap, neighborDepth))
                continue;
            coverage++;
            run = x == last + 1 ? run + 1 : 1;
            longest = Math.max(longest, run);
            last = x;
        }
        return longest >= minimumRun && coverage >= (right - left + 1) * .65f;
    }

    private static int offsetOfLight(
            byte[] gray, int width, int x, int y, int direction, int limit) {
        int offset = 1;
        while (offset <= limit && (gray[(y + direction * offset) * width + x] & 255) < 165)
            offset++;
        return offset;
    }

    private static boolean separatedRail(
            byte[] gray, int width, int x, int y, int direction, int maxGap, int depth) {
        int space = 0;
        while (space < maxGap && (gray[(y + direction * space) * width + x] & 255) >= 165) space++;
        if (space < 2 || space >= maxGap) return false;
        return offsetOfLight(gray, width, x, y + direction * space, direction, depth) >= depth;
    }

    private static boolean hasFacingEndpoint(
            PrintedSlurContours.Point cap, Location head, boolean above,
            List<? extends Location> heads, byte[] gray, int width, int height,
            StaffPitchTrack track) {
        float gap = track.at(cap.x())[1];
        float clearance = above ? head.y() - cap.y() : cap.y() - head.y();
        return clearance <= 4 * gap
                || shaftSupports(cap, head, above, gray, width, height, gap, heads);
    }

    private static boolean sharedChordStem(
            byte[] gray, int width, int height, Location a, Location b, float gap) {
        if (!headBody(gray, width, height, a, gap) || !headBody(gray, width, height, b, gap))
            return false;
        Location high = a.y() < b.y() ? a : b;
        Location low = high == a ? b : a;
        return stemReaches(gray, width, height, high, low, gap, -1)
                || stemReaches(gray, width, height, low, high, gap, 1)
                || continuousChordShaft(gray, width, height, high, low, gap);
    }

    /** The middle of a long shared chord shaft can be proved separately from its beam tip. */
    private static boolean continuousChordShaft(
            byte[] gray, int width, int height, Location high, Location low, float gap) {
        if (gap < 9
                || gap > 30
                || low.y() - high.y() < gap * 2.5f
                || Math.abs(high.x() - low.x()) > gap * .18f
                || high.staffCount() != 1) {
            return false;
        }
        int center = Math.round((high.x() + low.x()) * .5f);
        for (int side = -1; side <= 1; side += 2) {
            int first = Math.round(side < 0 ? high.y() : high.y() - gap * .75f);
            int last = Math.round(side < 0 ? low.y() + gap * .75f : low.y());
            if (first < 1 || last >= height - 1) continue;
            for (int distance = Math.round(gap * .42f); distance <= gap * .71f; distance++) {
                int column = center + side * distance;
                int partner = column + side;
                if (column <= 1
                        || partner <= 1
                        || column >= width - 2
                        || partner >= width - 2
                        || !shaftTouchesBody(gray, width, height, high, column, gap)
                        || !shaftTouchesBody(gray, width, height, low, column, gap)) {
                    continue;
                }
                int count = 0;
                int run = 0;
                int longest = 0;
                for (int y = first; y <= last; y++) {
                    if ((gray[y * width + column] & 255) < 155
                            && (gray[y * width + partner] & 255) < 165) {
                        count++;
                        run = 0;
                    } else {
                        longest = Math.max(longest, ++run);
                    }
                }
                if (count >= (last - first + 1) * .9f && longest <= Math.max(1, gap * .14f)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean shaftTouchesBody(
            byte[] gray, int width, int height, Location head, int column, float gap) {
        int center = Math.round(head.x());
        if (center < 0 || center >= width) return false;
        int x0 = Math.min(center, column);
        int x1 = Math.max(center, column);
        int y = Math.round(head.y());
        int margin = Math.max(1, Math.round(gap * .18f));
        for (int row = Math.max(0, y - margin); row <= Math.min(height - 1, y + margin); row++) {
            int ink = 0;
            for (int x = x0; x <= x1; x++) {
                if ((gray[row * width + x] & 255) < 165) ink++;
            }
            if (ink >= (x1 - x0 + 1) * .9f) return true;
        }
        return false;
    }

    private static boolean stemReaches(
            byte[] gray, int width, int height, Location from, Location to, float gap,
            int direction) {
        var stem = PairedTremoloInk.stem(gray, width, height, from.x(), from.y(), gap, direction);
        if (stem == null || direction * (to.y() - stem.endY()) < 0) return false;
        float offset = direction * (stem.x() - to.x());
        return offset >= gap * .2f && offset <= gap * .7f;
    }

    /** Both facing shaft tips must independently surround a compact returning bow. */
    static Location compactShaftCap(
            PrintedSlurContours.Point cap, boolean above, boolean first,
            List<? extends Location> heads, byte[] gray, int width, int height,
            StaffPitchTrack track) {
        float gap = track.at(cap.x())[1];
        Location owner = null;
        PairedTremoloInk.Stem owned = null;
        for (var head : heads) {
            if (head.grace() || Math.abs(head.x() - cap.x()) > gap * 1.5f
                    || !headBody(gray, width, height, head, gap)) continue;
            float side = above ? head.y() - cap.y() : cap.y() - head.y();
            if (side < gap * 1.5f || side > gap * 7) continue;
            var stem = PairedTremoloInk.stem(
                    gray, width, height, head.x(), head.y(), gap, above ? 1 : -1);
            if (stem == null || Math.abs(stem.endY() - cap.y()) > gap * .45f) continue;
            int inward = (first ? 1 : -1) * (cap.x() - stem.x());
            if (inward < 0 || inward > gap) continue;
            if (owned != null && (Math.abs(owned.x() - stem.x()) > 1
                    || Math.abs(owned.endY() - stem.endY()) > 1
                    || Math.abs(owner.x() - head.x()) > gap * .18f)) return null;
            if (owner == null || (above ? head.y() < owner.y() : head.y() > owner.y())) {
                owner = head;
                owned = stem;
            }
        }
        return owner;
    }

    static Optional<Location> displacedUnisonCap(
            PrintedSlurContours.Point cap, boolean above, List<? extends Location> heads,
            List<? extends Location> rivals, List<ScoreNoteEvent> notes,
            byte[] gray, int w, int h, float gap) {
        Location found = null;
        int direction = above ? 1 : -1;
        for (var head : heads) {
            var n = notes.get(head.owner());
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || n.stemDirection() != direction || n.beamCount() == 0 || head.grace()) continue;
            float x = cap.x() - direction * .6f * gap;
            float displacement = Math.abs(x - head.x());
            float clearance = direction * (head.y() - cap.y());
            if (x < 0 || x >= w || displacement < gap * .35f || displacement > gap * 1.4f
                    || clearance < gap * 2 || clearance > gap * 11) continue;
            boolean unison = false;
            for (int i = Math.max(0, head.owner() - 6);
                    i < Math.min(notes.size(), head.owner() + 7); i++) {
                var other = notes.get(i);
                if (other.measureIndex() == n.measureIndex() && other.staffIndex() == n.staffIndex()
                        && other.staffCount() == n.staffCount() && other.kind() == n.kind()
                        && other.positionInMeasure() == n.positionInMeasure()
                        && other.diatonicPitchIdentity() == n.diatonicPitchIdentity()
                        && other.unbeamedDurationBeats() == ScoreNoteEvent.DURATION_WHOLE
                        && other.stemDirection() == 0) unison = true;
            }
            if (!unison) continue;
            var printed = new GeometryHead(head.owner(), x, head.y(), head.staffIndex(), head.staffCount());
            if (!headBody(gray, w, h, printed, gap)
                    || !rawShaftCapSupports(cap, printed, above, gray, w, h, gap, rivals))
                continue;
            if (found != null) return Optional.empty();
            found = printed;
        }
        return Optional.ofNullable(found);
    }

    /** Reuse the unchanged endpoint rule for one observed half of a system continuation. */
    static Optional<Location> capOwner(
            PrintedSlurContours.Point cap,
            boolean above,
            boolean start,
            List<? extends Location> heads,
            byte[] gray,
            int width,
            int height,
            StaffPitchTrack track) {
        var values = candidates(cap, above, start, heads, gray, width, height, track);
        return values.isEmpty() || ambiguous(values)
                ? Optional.empty()
                : Optional.of(values.get(0).head());
    }

    private static List<Candidate> candidates(
            PrintedSlurContours.Point cap,
            boolean above,
            boolean start,
            List<? extends Location> heads,
            byte[] gray,
            int width,
            int height,
            StaffPitchTrack track) {
        return candidates(cap, above, start, heads, gray, width, height, track, 0);
    }

    private static List<Candidate> candidates(
            PrintedSlurContours.Point cap,
            boolean above,
            boolean start,
            List<? extends Location> heads,
            byte[] gray,
            int width,
            int height,
            StaffPitchTrack track,
            float horizontalCellRadius) {
        float gap = track.at(cap.x())[1];
        var result = new ArrayList<Candidate>();
        for (var head : heads) {
            if (head.x() >= width || head.y() >= height) continue;
            double dx = Math.max(0, Math.abs(head.x() - cap.x()) - horizontalCellRadius) / gap;
            double clearance = (above ? head.y() - cap.y() : cap.y() - head.y()) / gap;
            if (dx > 1.1 || clearance < -.6 || clearance > 8.5) continue;
            boolean shaft = shaftSupports(cap, head, above, gray, width, height, gap, heads);
            boolean headEdge =
                    dx <= .85
                            && (start ? cap.x() >= head.x() : cap.x() <= head.x())
                            && headBody(gray, width, height, head, gap)
                            && (clearance <= 2
                                    || headFacingEdgeSupports(
                                            cap, head, above, gray, width, height, gap));
            // A slanted notehead rim can meet a returning bow above its center.
            // Permit that overlap only with a proved adjacent head body.
            if (clearance < -.2 && !headEdge) continue;
            if (dx > .45 && !shaft && !headEdge) continue;
            // A proved shaft or adjacent head edge owns the endpoint ahead of distant heads.
            double score =
                    shaft || headEdge && clearance <= 1
                            ? -.35 + dx * .15 + clearance * .015
                            : dx + clearance * .03;
            result.add(new Candidate(head, score));
        }
        result.sort(
                Comparator.comparingDouble(Candidate::distance)
                        .thenComparingInt(c -> c.head().owner()));
        return result;
    }

    /** A proved grace-to-grace bow may finish beside the right edge of its small final head.
     * The ordinary endpoint route and all its thresholds keep precedence unchanged. */
    private static List<Candidate> gracePairEndCandidates(
            PrintedSlurContours.Point cap,
            boolean above,
            Location start,
            List<? extends Location> heads,
            byte[] gray,
            int width,
            int height,
            StaffPitchTrack track) {
        float gap = track.at(cap.x())[1];
        if (!headBody(gray, width, height, start, track.at(start.x())[1])) return List.of();
        var result = new ArrayList<Candidate>();
        for (var head : heads) {
            if (!head.grace()
                    || head.owner() == start.owner()
                    || head.staffIndex() != start.staffIndex()
                    || head.staffCount() != start.staffCount()
                    || head.x() <= start.x()
                    || cap.x() <= head.x()
                    || head.x() >= width
                    || head.y() >= height) continue;
            double dx = (cap.x() - head.x()) / gap;
            double clearance = (above ? head.y() - cap.y() : cap.y() - head.y()) / gap;
            if (dx > .85
                    || clearance < 0
                    || clearance > 2
                    || !headBody(gray, width, height, head, track.at(head.x())[1])) continue;
            double score =
                    clearance <= 1 ? -.35 + dx * .15 + clearance * .015 : dx + clearance * .03;
            result.add(new Candidate(head, score));
        }
        result.sort(
                Comparator.comparingDouble(Candidate::distance)
                        .thenComparingInt(c -> c.head().owner()));
        return result;
    }

    static boolean headBody(byte[] gray, int width, int height, Location head, float gap) {
        int rows = 0, minimum = Math.max(3, (int) Math.ceil(gap * .25f));
        for (int y = Math.max(0, Math.round(head.y() - gap * .4f));
                y < height && y <= head.y() + gap * .4f;
                y++) {
            int columns = 0;
            for (int x = Math.max(0, Math.round(head.x() - gap * .65f));
                    x < width && x <= head.x() + gap * .65f;
                    x++) if ((gray[y * width + x] & 255) < 165) columns++;
            if (columns >= minimum) rows++;
        }
        return rows >= minimum;
    }

    /** The two-gap side clearance is from observed head ink, not its centre. */
    private static boolean headFacingEdgeSupports(
            PrintedSlurContours.Point cap,
            Location head,
            boolean above,
            byte[] gray,
            int width,
            int height,
            float gap) {
        int minimum = Math.max(3, (int) Math.ceil(gap * .25f));
        int first = Math.max(0, Math.round(head.y() - gap * .4f));
        int last = Math.min(height - 1, (int) Math.floor(head.y() + gap * .4f));
        int center = Math.round(head.y());
        if (center < first || center > last) return false;
        int edge = center, rows = 0;
        for (int y = center; y >= first && y <= last; y += above ? -1 : 1) {
            int hits = 0;
            for (int x = Math.max(0, Math.round(head.x() - gap * .65f));
                    x < width && x <= head.x() + gap * .65f;
                    x++) if ((gray[y * width + x] & 255) < 165) hits++;
            // The facing edge belongs to the wide row run through the head centre.
            // A detached rail or one-pixel stem cannot move this body boundary.
            if (hits < minimum) break;
            edge = y;
            rows++;
        }
        double clearance = (above ? edge - cap.y() : cap.y() - edge) / gap;
        return rows >= Math.max(2, (minimum + 1) / 2) && clearance >= 0 && clearance <= 2;
    }

    record CapEvidence(Optional<Location> owner, Set<Integer> possibleOwners) {
        CapEvidence {
            possibleOwners = Set.copyOf(possibleOwners);
        }
    }

    /** Unchanged ordinary candidate law, including its original ambiguity ranking. */
    static CapEvidence rawCapEvidence(
            PrintedSlurContours.Point cap,
            boolean above,
            boolean start,
            List<? extends Location> heads,
            byte[] gray,
            int width,
            int height,
            StaffPitchTrack track) {
        var values = candidates(cap, above, start, heads, gray, width, height, track);
        var ids = new HashSet<Integer>();
        for (var value : values) ids.add(value.head().owner());
        return new CapEvidence(
                values.isEmpty() || ambiguous(values)
                        ? Optional.empty()
                        : Optional.of(values.get(0).head()),
                ids);
    }

    /** The same literal head-edge ink property used by ordinary endpoint candidates. */
    static boolean rawHeadCapSupports(
            PrintedSlurContours.Point cap,
            Location head,
            boolean above,
            boolean start,
            byte[] gray,
            int width,
            int height,
            float gap) {
        double dx = Math.abs(head.x() - cap.x()) / gap;
        double clearance = (above ? head.y() - cap.y() : cap.y() - head.y()) / gap;
        return dx <= .85
                && (start ? cap.x() >= head.x() : cap.x() <= head.x())
                && headBody(gray, width, height, head, gap)
                && (clearance <= 2
                        || headFacingEdgeSupports(cap, head, above, gray, width, height, gap));
    }

    /** Raw ink property only; callers still need independent complete role and ownership proof. */
    static boolean rawShaftCapSupports(
            PrintedSlurContours.Point cap,
            Location head,
            boolean above,
            byte[] gray,
            int width,
            int height,
            float gap,
            List<? extends Location> heads) {
        return shaftSupports(cap, head, above, gray, width, height, gap, heads);
    }

    private static boolean shaftSupports(
            PrintedSlurContours.Point cap,
            Location head,
            boolean above,
            byte[] gray,
            int width,
            int height,
            float gap,
            List<? extends Location> heads) {
        int direction = above ? -1 : 1;
        float edge = head.x() + (above ? .6f : -.6f) * gap;
        if (Math.abs(edge - cap.x()) > gap * .4f) return false;
        float distance = direction * (cap.y() - head.y()) - gap;
        if (distance < gap * .8f) return false;
        int first = Math.round(head.y() + direction * gap * .15f);
        int last = Math.round(head.y() + direction * distance);
        for (int x = Math.max(0, Math.round(edge - gap * .2f));
                x < width && x <= edge + gap * .2f;
                x++) {
            int total = 0, hits = 0, exposedHits = 0;
            for (int y = Math.min(first, last); y <= Math.max(first, last); y++) {
                if (y < 0 || y >= height) {
                    total = 0;
                    break;
                }
                total++;
                if ((gray[y * width + x] & 255) < 165) {
                    hits++;
                    // Stacked head rims cannot supply the exposed stem proof.
                    boolean body = false;
                    for (var other : heads)
                        if (Math.abs(x - other.x()) <= gap * .75f
                                && Math.abs(y - other.y()) <= gap * .6f) {
                            body = true;
                            break;
                        }
                    if (!body) exposedHits++;
                }
            }
            if (total >= gap * .75f && hits >= total * .9f && exposedHits >= gap * .75f)
                return true;
        }
        return false;
    }
}
