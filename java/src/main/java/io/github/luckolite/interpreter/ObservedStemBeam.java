// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;

/** Beams and ledger rules with literal shaft ownership cannot also issue a slur. */
final class ObservedStemBeam {
    private final List<ScoreNoteEvent> notes;
    private final byte[] gray;
    private final int w, h;
    private long work;
    private boolean refused;

    boolean refused() {
        return refused;
    }

    private record Stem(int left, int right, int end, int start) {}

    ObservedStemBeam(List<ScoreNoteEvent> notes, byte[] gray, int w, int h) {
        this(notes, gray, w, h, 8_000_000L);
    }

    ObservedStemBeam(List<ScoreNoteEvent> notes, byte[] gray, int w, int h, long budget) {
        Objects.requireNonNull(notes);
        if (gray == null
                || w < 3
                || h < 3
                || gray.length != (long) w * h
                || budget < 0
                || budget > 8_000_000L)
            throw new IllegalArgumentException("Invalid raw beam source");
        this.notes = notes;
        this.gray = gray;
        this.w = w;
        this.h = h;
        work = budget;
    }

    private boolean take(long cost) {
        if (cost < 0 || cost > work) {
            refused = true;
            return false;
        }
        work -= cost;
        return true;
    }

    private int oriented(int y, int direction) {
        return direction > 0 ? y : h - 1 - y;
    }

    private int actual(int y, int direction) {
        return direction > 0 ? y : h - 1 - y;
    }

    private int raw(int x, int y, int direction) {
        return gray[actual(y, direction) * w + x] & 255;
    }

    /** Separate a compact bow from the attached beam before applying the raw contour proof. */
    Optional<PrintedSlurContours.Bow> compactBowAbove(
            PrintedSlurOwners.Location a, PrintedSlurOwners.Location b, StaffPitchTrack track) {
        var first = notes.get(a.owner());
        var last = notes.get(b.owner());
        float gap = track.at((a.x() + b.x()) * .5f)[1];
        float span = b.x() - a.x();
        if (first.kind() != ScoreNoteEvent.Kind.PITCHED || last.kind() != ScoreNoteEvent.Kind.PITCHED
                || a.grace() || b.grace() || first.measureIndex() != last.measureIndex()
                || first.diatonicPitchIdentity() == last.diatonicPitchIdentity()
                || first.staffIndex() != last.staffIndex() || first.staffCount() != last.staffCount()
                || first.crossStaffBeam() || last.crossStaffBeam()
                || first.beamCount() < 1 || first.beamCount() != last.beamCount()
                || first.stemDirection() != 1 || last.stemDirection() != 1
                || gap < 8 || gap > 64 || span < gap * 1.65f || span > gap * 2.9f
                || !take((long) Math.ceil(gap * gap * 32 + w * 4L))) return Optional.empty();
        Stem sa = stem(a, gap, -1), sb = stem(b, gap, -1);
        if (sa == null || sb == null || !connectedShaft(sa, -1) || !connectedShaft(sb, -1))
            return Optional.empty();
        int ay = actual(sa.end(), -1), by = actual(sb.end(), -1);
        if (Math.abs(ay - by) > gap * .4f) return Optional.empty();
        int bottom = Math.round(Math.min(ay, by) - gap * .2f);
        var result = PrintedSlurContours.findAboveStemTips(gray, w, h, Math.round(a.x()),
                Math.round(bottom - gap * 1.3f), Math.round(b.x() + gap), bottom, track);
        if (result.limitReached()) return Optional.empty();
        var bows = result.bows().stream().filter(bow ->
                bow.above()
                        && Math.abs(bow.start().x() - (sa.left() + sa.right()) * .5f) <= gap * .35f
                        && Math.abs(bow.end().x() - (sb.left() + sb.right()) * .5f) <= gap * .35f
                        && ay - bow.start().y() <= gap * .8f
                        && by - bow.end().y() <= gap * .8f).toList();
        return bows.size() == 1 ? Optional.of(bows.get(0)) : Optional.empty();
    }

    /** An upper ledger can look curved where its ends merge into the outer noteheads. */
    boolean ownsLedger(
            PrintedSlurContours.Bow bow,
            ScoreSlurPages.Span span,
            List<? extends PrintedSlurOwners.Location> heads,
            StaffPitchTrack track) {
        if (bow.asymmetric()
                || !track.verified()
                || span.start().pageIndex() != span.end().pageIndex()) return false;
        if (!bow.above()) return ownsLowerLedgerContact(bow, span, heads, track);
        int left = bow.start().x(),
                right = bow.end().x(),
                ai = span.start().noteIndex(),
                bi = span.end().noteIndex();
        int length = right - left;
        float[] center = track.at((left + right) * .5f);
        float gap = center[1];
        if (!Float.isFinite(center[0] + gap)
                || gap < 8
                || gap > 64
                || length < gap * 2
                || length > gap * 3.5f
                || ai >= notes.size()
                || bi >= notes.size()) return false;
        var first = notes.get(ai);
        var last = notes.get(bi);
        int step = first.staffStep();
        if (step < 10
                || (step & 1) != 0
                || last.staffStep() != step
                || !upperLedgerVoice(first, first)
                || !upperLedgerVoice(first, last)
                || !take(heads.size() * 8L + 64)) return false;

        PrintedSlurOwners.Location a = null, b = null, inner = null;
        for (var head : heads) {
            if (head.owner() == ai) a = head;
            else if (head.owner() == bi) b = head;
            else if (head.x() >= left - gap * .25f && head.x() <= right + gap * .25f) {
                var note = notes.get(head.owner());
                if (inner != null
                        || head.grace()
                        || head.x() < left + gap * .6f
                        || head.x() > right - gap * .6f
                        || note.staffStep() < step + 2
                        || !upperLedgerVoice(first, note)) return false;
                inner = head;
            }
        }
        if (a == null
                || b == null
                || inner == null
                || a.grace()
                || b.grace()
                || left <= a.x()
                || left - a.x() > gap
                || right >= b.x()
                || b.x() - right > gap
                || Math.abs(a.y() - ledgerY(track, a.x(), step)) > gap * .2f
                || Math.abs(b.y() - ledgerY(track, b.x(), step)) > gap * .2f
                || Math.abs(bow.start().y() - a.y()) > gap * .65f
                || Math.abs(bow.end().y() - b.y()) > gap * .65f
                || !take((long) Math.ceil(gap * gap * 5 + length * 12L))) return false;
        if (Math.abs(inner.y() - ledgerY(track, inner.x(), notes.get(inner.owner()).staffStep()))
                > gap * .25f) return false;
        Stem shaft = stem(inner, gap, 1);
        if (shaft == null) return false;
        float ledger = ledgerY(track, (shaft.left() + shaft.right()) * .5f, step);
        if (ledger - shaft.start() < gap * .5f
                || shaft.end() - ledger < gap * .5f
                || !connectedShaft(shaft, 1)
                || !TrackedLedgerMarkOwnership.shortRule(gray, w, h, inner.x(), gap, step, track))
            return false;

        // Every departure from the ledger must coincide with an owned head or shaft.
        int onRule = 0;
        for (var point : bow.points()) {
            int x = point.x(), y = Math.round(point.y());
            if (x < 0 || x >= w || y < 1 || y >= h - 1) return false;
            if (Math.abs(point.y() - ledgerY(track, x, step)) <= gap * .2f) {
                if (Math.min(
                                gray[(y - 1) * w + x] & 255,
                                Math.min(gray[y * w + x] & 255, gray[(y + 1) * w + x] & 255))
                        < 185) onRule++;
            } else if (x > a.x() + gap * .9f
                    && x < b.x() - gap * .75f
                    && (x < shaft.left() - gap * .18f || x > shaft.right() + gap * .18f))
                return false;
        }
        return onRule >= (length + 1) * .75f;
    }

    /** A ledger below the first head can lead into the upper edge of the next filled head. */
    private boolean ownsLowerLedgerContact(
            PrintedSlurContours.Bow bow, ScoreSlurPages.Span span,
            List<? extends PrintedSlurOwners.Location> heads, StaffPitchTrack track) {
        int ai = span.start().noteIndex(), bi = span.end().noteIndex();
        if (ai >= notes.size() || bi >= notes.size()) return false;
        var first = notes.get(ai);
        var last = notes.get(bi);
        int ledgerStep = last.staffStep();
        if (ledgerStep < 10 || (ledgerStep & 1) != 0 || first.staffStep() != ledgerStep + 1
                || !upperLedgerVoice(first, first) || !upperLedgerVoice(first, last)
                || first.beamCount() < 1 || first.beamCount() != last.beamCount()
                || !take(heads.size() * 8L + 64)) return false;
        PrintedSlurOwners.Location a = null, b = null;
        for (var head : heads) {
            if (head.owner() == ai) a = head;
            if (head.owner() == bi) b = head;
        }
        if (a == null || b == null || a.grace() || b.grace()) return false;
        int left = bow.start().x(), right = bow.end().x();
        float gap = track.at((left + right) * .5f)[1];
        int length = right - left + 1;
        if (gap < 8 || gap > 32 || length < gap * 1.55f || length > gap * 2.6f
                || Math.abs(a.y() - ledgerY(track, a.x(), ledgerStep + 1)) > gap * .2f
                || Math.abs(b.y() - ledgerY(track, b.x(), ledgerStep)) > gap * .2f
                || Math.abs(left - a.x()) > gap * .32f
                || right < b.x() - gap * .55f || right > b.x() + gap * .15f
                || bow.end().y() > b.y() - gap * .30f
                || !take(bow.points().size() * 14L + (long) Math.ceil(gap * gap * 2))) return false;
        if (!PrintedSlurOwners.headBody(gray, w, h, a, gap)
                || !PrintedSlurOwners.headBody(gray, w, h, b, gap)) return false;

        int rule = 0, exposedRule = 0, onHead = 0;
        int flank = Math.round(gap * .30f);
        for (var point : bow.points()) {
            int x = point.x(), y = Math.round(point.y());
            if (x < 0 || x >= w || y < flank || y + flank >= h
                    || Math.min(raw(x, y - 1, 1), Math.min(raw(x, y, 1), raw(x, y + 1, 1))) >= 185)
                return false;
            if (Math.abs(point.y() - ledgerY(track, x, ledgerStep)) <= gap * .20f) {
                rule++;
                if (x > a.x() + gap * .6f && x < b.x() - gap * .6f
                        && raw(x, y - flank, 1) >= 185 && raw(x, y + flank, 1) >= 185) exposedRule++;
            } else if (x >= b.x() - gap * .68f && x <= b.x()
                    && point.y() < b.y() && b.y() - point.y() <= gap * .70f) {
                onHead++;
            } else return false;
        }
        return rule >= length * .65f && exposedRule >= gap * .20f && onHead >= 2;
    }

    private static boolean upperLedgerVoice(ScoreNoteEvent a, ScoreNoteEvent b) {
        return b.kind() == ScoreNoteEvent.Kind.PITCHED
                && b.stemDirection() == -1
                && !b.crossStaffBeam()
                && a.measureIndex() == b.measureIndex()
                && a.staffIndex() == b.staffIndex()
                && a.staffCount() == b.staffCount();
    }

    private static float ledgerY(StaffPitchTrack track, float x, int step) {
        float[] frame = track.at(x);
        return frame[0] - step * frame[1] * .5f;
    }

    boolean owns(
            PrintedSlurContours.Bow bow,
            ScoreSlurPages.Span span,
            List<? extends PrintedSlurOwners.Location> heads,
            StaffPitchTrack track) {
        if (bow == null
                || span == null
                || heads == null
                || track == null
                || !track.verified()
                || bow.points().size() < 2
                || bow.points().size() > 2048
                || span.start().pageIndex() != span.end().pageIndex()) return false;
        int ai = span.start().noteIndex(), bi = span.end().noteIndex();
        if (ai < 0
                || bi < 0
                || ai >= notes.size()
                || bi >= notes.size()
                || !take(heads.size() * 32L + 64)) return false;
        var na = notes.get(ai);
        var nb = notes.get(bi);
        int count = na.beamCount(), direction = bow.above() ? -1 : 1;
        if (count < 1
                || count > 4
                || nb.beamCount() != count
                || na.kind() != ScoreNoteEvent.Kind.PITCHED
                || nb.kind() != ScoreNoteEvent.Kind.PITCHED
                || na.crossStaffBeam()
                || nb.crossStaffBeam()
                || na.stemDirection() != -direction
                || nb.stemDirection() != -direction
                || na.measureIndex() != nb.measureIndex()
                || na.staffIndex() != nb.staffIndex()
                || na.staffCount() != nb.staffCount()) return false;
        PrintedSlurOwners.Location a = null, b = null;
        for (var head : heads) {
            if (head.owner() == ai) {
                if (a != null) return false;
                a = head;
            }
            if (head.owner() == bi) {
                if (b != null) return false;
                b = head;
            }
        }
        if (a == null
                || b == null
                || !Float.isFinite(a.x() + a.y() + b.x() + b.y())
                || a.x() < 0
                || b.x() >= w
                || a.y() < 0
                || a.y() >= h
                || b.y() < 0
                || b.y() >= h
                || a.x() >= b.x()
                || a.staffIndex() != na.staffIndex()
                || a.staffCount() != na.staffCount()
                || b.staffIndex() != nb.staffIndex()
                || b.staffCount() != nb.staffCount()) return false;
        float gap = track.at((a.x() + b.x()) * .5f)[1];
        if (!Float.isFinite(gap)
                || gap < 6
                || gap > 64
                || b.x() - a.x() < gap * 1.5
                || b.x() - a.x() > gap * 16) return false;
        if (!take((long) Math.ceil(gap * gap * 80))) return false;
        if (!PrintedSlurOwners.headBody(gray, w, h, a, gap)
                || !PrintedSlurOwners.headBody(gray, w, h, b, gap)) return false;
        Stem sa = stem(a, gap, direction), sb = stem(b, gap, direction);
        if (sa == null || sb == null || sa.right >= sb.left) return false;
        for (var head : heads) {
            if (head.owner() != ai
                    && Math.abs(head.x() - (sa.left + sa.right) * .5f) <= gap * .75f
                    && oriented(Math.round(head.y()), direction) >= sa.start - gap * .6f
                    && oriented(Math.round(head.y()), direction) <= sa.end + gap * .6f)
                return false;
            if (head.owner() != bi
                    && Math.abs(head.x() - (sb.left + sb.right) * .5f) <= gap * .75f
                    && oriented(Math.round(head.y()), direction) >= sb.start - gap * .6f
                    && oriented(Math.round(head.y()), direction) <= sb.end + gap * .6f)
                return false;
        }
        if (!connectedShaft(sa, direction) || !connectedShaft(sb, direction)) return false;
        int first = sa.right + 1, last = sb.left - 1, n = last - first + 1;
        if (n < Math.ceil(gap) || n > 256 || Math.abs(sa.end - sb.end) > gap * 1.5) return false;
        if (!take(
                (long) count * n * n * 64
                        + (long) n * (int) Math.ceil(gap * count * 32)
                        + bow.points().size() * 128L)) return false;
        int[][] top = new int[count][n], bottom = new int[count][n];
        for (int x = first; x <= last; x++) {
            double expected =
                    sa.end + (sb.end - sa.end) * (x - sa.right) / (double) (sb.left - sa.right);
            int lo = (int) Math.floor(expected - gap * (count + 1)),
                    hi = (int) Math.ceil(expected + gap * .35);
            if (lo < 1 || hi >= h - 1) return false;
            var bands = new ArrayList<int[]>();
            int start = -1;
            for (int y = lo; y <= hi + 1; y++) {
                if (y <= hi && raw(x, y, direction) < 165) {
                    if (start < 0) start = y;
                    continue;
                }
                if (start < 0) continue;
                int end = y - 1;
                if (start > lo
                        && end < hi
                        && end - start + 1 >= Math.max(3, Math.ceil(gap * .2))
                        && end - start + 1 <= gap * .8) bands.add(new int[] {start, end});
                start = -1;
            }
            int primary = -1;
            for (int i = 0; i < bands.size(); i++)
                if (Math.abs(bands.get(i)[1] - expected) <= gap * .35) {
                    if (primary >= 0) return false;
                    primary = i;
                }
            if (primary < 0 || primary < count - 1) return false;
            for (int beam = 0; beam < count; beam++) {
                var band = bands.get(primary - beam);
                top[beam][x - first] = band[0];
                bottom[beam][x - first] = band[1];
            }
        }
        var certificates = new ArrayList<FiniteParallelBeamBand>();
        var thicknesses = new ArrayList<Integer>();
        for (int beam = 0; beam < count; beam++) {
            int thick = thickness(top[beam], bottom[beam]);
            boolean regular = regular(top[beam], bottom[beam], thick);
            var proof =
                    regular ? FiniteParallelBeamBand.prove(top[beam], bottom[beam], thick) : null;
            if (proof == null && beam > 0) {
                if (regular && !take(n * n * 64L))
                    return false; // A failed old fit already spent its allowance.
                int[][] separated =
                        beamWithStaff(
                                top[beam],
                                bottom[beam],
                                first,
                                gap,
                                direction,
                                track,
                                heads,
                                ai,
                                bi);
                if (separated == null) return false;
                top[beam] = separated[0];
                bottom[beam] = separated[1];
                thick = thickness(top[beam], bottom[beam]);
                if (!regular(top[beam], bottom[beam], thick)) return false;
                proof = FiniteParallelBeamBand.prove(top[beam], bottom[beam], thick);
            }
            if (proof == null) return false;
            if (beam > 0)
                for (int i = 0; i < n; i++) {
                    int gapPixels = top[beam - 1][i] - bottom[beam][i] - 1;
                    if (gapPixels < Math.max(1, gap * .1) || gapPixels > gap) return false;
                }
            if (!contact(sa, top[beam][0], bottom[beam][0], true, direction)
                    || !contact(sb, top[beam][n - 1], bottom[beam][n - 1], false, direction))
                return false;
            for (var prior : certificates) if (!proof.parallelTo(prior)) return false;
            certificates.add(proof);
            thicknesses.add(thick);
        }
        int halo = Math.max(1, (int) Math.ceil(gap * .2));
        for (int beam = 0; beam < count; beam++) {
            boolean all = true;
            for (var point : bow.points()) {
                int x = point.x();
                float y = direction > 0 ? point.y() : h - 1 - point.y();
                int low, high;
                if (x < 0 || x >= w || !Float.isFinite(y)) {
                    all = false;
                    break;
                }
                if (x >= first && x <= last) {
                    int i = x - first;
                    low = top[beam][i];
                    high = bottom[beam][i];
                } else {
                    if (x < sa.left - 1 || x > sb.right + 1) {
                        all = false;
                        break;
                    }
                    int off = x - first;
                    high = certificates.get(beam).terminalCeiling(off);
                    low = certificates.get(beam).terminalFloor(off) - thicknesses.get(beam) + 1;
                }
                if (y < low - halo || y > high + halo) {
                    all = false;
                    break;
                }
                int yy = Math.round(y);
                if (yy < 0 || yy >= h || raw(x, yy, direction) > 205) {
                    all = false;
                    break;
                }
            }
            if (all) return true;
        }
        return false;
    }

    private static int thickness(int[] top, int[] bottom) {
        int[] values = new int[top.length];
        for (int i = 0; i < values.length; i++) values[i] = bottom[i] - top[i] + 1;
        Arrays.sort(values);
        return values[values.length / 2];
    }

    private static boolean regular(int[] top, int[] bottom, int thick) {
        for (int i = 0; i < top.length; i++)
            if (Math.abs(bottom[i] - top[i] + 1 - thick) > 1
                    || i > 0
                            && (Math.abs(top[i] - top[i - 1]) > 1
                                    || Math.abs(bottom[i] - bottom[i - 1]) > 1)) return false;
        return true;
    }

    /** A foreground partition, never a background or pixel-removal certificate. */
    private int[][] beamWithStaff(
            int[] top,
            int[] bottom,
            int first,
            float gap,
            int direction,
            StaffPitchTrack track,
            List<? extends PrintedSlurOwners.Location> heads,
            int ai,
            int bi) {
        int n = top.length, last = first + n - 1;
        if (!take((n + (long) Math.ceil(gap * 4)) * (long) Math.ceil(gap * 128))) return null;
        int[][] result = null;
        for (int line = 0; line < 5; line++) {
            int[][] carrier = staffCarrier(first, last, gap, direction, track, line, heads, ai, bi);
            if (carrier == null) continue;
            var exposed = new ArrayList<Integer>();
            int longest = 0, run = 0, joined = 0;
            for (int i = 0; i < n; i++) {
                boolean separate = bottom[i] < carrier[0][i] || top[i] > carrier[1][i];
                if (separate) {
                    exposed.add(bottom[i] - top[i] + 1);
                    longest = Math.max(longest, ++run);
                } else {
                    run = 0;
                    joined++;
                }
            }
            if (longest < Math.max(3, Math.ceil(gap * .2)) || joined < 3) continue;
            Collections.sort(exposed);
            int minimum =
                    Math.max(
                            exposed.get(exposed.size() - 1) - 1,
                            Math.max(3, (int) Math.ceil(gap * .2)));
            int maximum = Math.min(exposed.get(0) + 1, (int) Math.floor(gap * .8));
            // Quantized beam width can vary by one pixel across columns. At each column,
            // intersect EVERY literal partition admitted by the exposed width constraints.
            int[][] common = partition(top, bottom, carrier, first, direction, minimum, maximum);
            if (common == null) continue;
            int[] a = common[0], b = common[1];
            int thick = thickness(a, b);
            if (!regular(a, b, thick))
                continue; // The caller issues the mandatory exact parallel proof.
            if (result != null) return null; // Competing carrier identities stay unknown.
            result = new int[][] {a, b};
        }
        return result;
    }

    private int[][] partition(
            int[] top,
            int[] bottom,
            int[][] carrier,
            int first,
            int direction,
            int minimum,
            int maximum) {
        int[] a = top.clone(), b = bottom.clone();
        for (int i = 0; i < top.length; i++) {
            int lo = carrier[0][i], hi = carrier[1][i];
            if (bottom[i] < lo || top[i] > hi) continue;
            boolean topExposed = top[i] < lo, bottomExposed = bottom[i] > hi;
            if (!topExposed && !bottomExposed) return null;
            int commonTop = Integer.MIN_VALUE, commonBottom = Integer.MAX_VALUE;
            boolean found = false;
            for (int thick = minimum; thick <= maximum; thick++) {
                int aa = top[i], bb = bottom[i];
                if (topExposed && !bottomExposed) bb = top[i] + thick - 1;
                if (bottomExposed && !topExposed) aa = bottom[i] - thick + 1;
                if (aa < 1 || bb >= h - 1) continue;
                boolean valid = true;
                // Every assigned beam pixel remains literal ink. All residual ink in this
                // connected band must belong to the independently observed staff stripe.
                for (int y = Math.min(aa, top[i]); y <= Math.max(bb, bottom[i]); y++) {
                    if (y >= aa && y <= bb) {
                        if (raw(first + i, y, direction) >= 165) {
                            valid = false;
                            break;
                        }
                    } else if (y < lo || y > hi) {
                        valid = false;
                        break;
                    }
                }
                if (valid) {
                    found = true;
                    commonTop = Math.max(commonTop, aa);
                    commonBottom = Math.min(commonBottom, bb);
                }
            }
            if (!found || commonTop > commonBottom) return null;
            a[i] = commonTop;
            b[i] = commonBottom;
        }
        return new int[][] {a, b};
    }

    private int[][] staffCarrier(
            int first,
            int last,
            float gap,
            int direction,
            StaffPitchTrack track,
            int line,
            List<? extends PrintedSlurOwners.Location> heads,
            int ai,
            int bi) {
        int reach = (int) Math.ceil(gap * 2),
                clear = (int) Math.ceil(gap * .6),
                left = first - reach,
                right = last + reach;
        if (left < 1 || right >= w - 1) return null;
        int lowOffset = Integer.MIN_VALUE,
                highOffset = Integer.MAX_VALUE,
                minLow = Integer.MAX_VALUE,
                maxHigh = Integer.MIN_VALUE;
        for (int side = 0; side < 2; side++) {
            int from = side == 0 ? left : last + clear, to = side == 0 ? first - clear : right;
            if (to - from + 1 < Math.max(3, Math.ceil(gap))) return null;
            int[] ruleColumns = new int[5];
            int fullRun = 0, longestFullRun = 0;
            for (int x = from; x <= to; x++) {
                int[] band = staffBand(x, line, gap, direction, track);
                if (band == null) return null;
                ruleColumns[line]++;
                int corroborating = 0;
                for (int other = 0; other < 5; other++)
                    if (other != line && staffBand(x, other, gap, direction, track) != null) {
                        corroborating++;
                        ruleColumns[other]++;
                    }
                if (corroborating == 4) longestFullRun = Math.max(longestFullRun, ++fullRun);
                else fullRun = 0;
                int lo = band[0], hi = band[1];
                lowOffset = Math.max(lowOffset, lo);
                highOffset = Math.min(highOffset, hi);
                minLow = Math.min(minLow, lo);
                maxHigh = Math.max(maxHigh, hi);
            }
            // A track estimate only seeds this search. All five rules must be independently
            // observed on BOTH sides, including when the old track's sampled extent ends here.
            for (int columns : ruleColumns)
                if (columns < Math.max(3, Math.ceil(gap * .3))) return null;
            if (longestFullRun < Math.max(3, Math.ceil(gap * .3))) return null;
        }
        // This optional partition currently certifies a locally straight horizontal rule.
        // Every reference has the SAME literal edges; the old track only seeds the search
        // and never extends its sampled proof or supplies an inferred carrier boundary.
        if (lowOffset != minLow || maxHigh != highOffset) return null;
        int[][] carrier = new int[2][last - first + 1];
        for (var head : heads)
            if (head.owner() != ai && head.owner() != bi && head.x() >= left && head.x() <= right) {
                if (Math.abs(
                                oriented(Math.round(head.y()), direction)
                                        - (lowOffset + highOffset) * .5f)
                        <= gap * .8) return null;
            }
        for (int x = left; x <= right; x++) {
            int lo = lowOffset, hi = highOffset;
            if (lo < 1 || hi >= h - 1) return null;
            for (int y = lo; y <= hi; y++) if (raw(x, y, direction) >= 165) return null;
            if (x >= first && x <= last) {
                carrier[0][x - first] = lo;
                carrier[1][x - first] = hi;
            }
        }
        return carrier;
    }

    private int staffCenter(int x, int line, int direction, StaffPitchTrack track) {
        float[] f = track.at(x);
        float y = f[0] - line * f[1];
        return Math.round(direction > 0 ? y : h - 1 - y);
    }

    private int[] staffBand(int x, int line, float gap, int direction, StaffPitchTrack track) {
        int center = staffCenter(x, line, direction, track),
                radius = (int) Math.ceil(gap * .4),
                lo = center - radius,
                hi = center + radius;
        if (lo < 1 || hi >= h - 1 || raw(x, lo, direction) < 230 || raw(x, hi, direction) < 230)
            return null;
        int first = -1, last = -1, runs = 0;
        boolean prior = false;
        for (int y = lo; y <= hi; y++) {
            boolean ink = raw(x, y, direction) < 165;
            if (ink && !prior) {
                runs++;
                first = y;
            }
            if (ink) last = y;
            prior = ink;
        }
        if (runs != 1
                || first <= lo
                || last >= hi
                || last - first + 1 > Math.max(3, Math.ceil(gap * .3))
                || Math.abs((first + last) * .5f - center) > gap * .2) return null;
        return new int[] {first, last};
    }

    private Stem stem(PrintedSlurOwners.Location head, float gap, int direction) {
        int cy = oriented(Math.round(head.y()), direction),
                edge = Math.round(head.x() - direction * .6f * gap),
                left = w,
                right = -1,
                end = -1,
                start = cy + Math.round(gap * .2f);
        for (int x = Math.max(1, Math.round(edge - gap * .2f));
                x < w - 1 && x <= edge + gap * .2f;
                x++) {
            int hits = 0, total = 0, blank = 0, last = start;
            for (int y = start; y < h - 1 && y <= start + Math.ceil(gap * 7); y++) {
                if (raw(x, y, direction) < 165) {
                    hits++;
                    blank = 0;
                    last = y;
                } else if (++blank >= 2) break;
                total++;
            }
            if (last - cy < gap * 1.7f || last - cy > gap * 7 || hits < total * .9f) continue;
            if (right >= 0 && x != right + 1) return null;
            if (end >= 0 && Math.abs(last - end) > 1) return null;
            left = Math.min(left, x);
            right = x;
            end = Math.max(end, last);
        }
        if (right < left || right - left + 1 > Math.max(3, gap * .35f)) return null;
        return new Stem(left, right, end, start);
    }

    private boolean contact(Stem stem, int top, int bottom, boolean left, int direction) {
        int x = left ? stem.right : stem.left;
        for (int y = top; y <= bottom; y++)
            for (int dy = -1; dy <= 1; dy++) {
                int yy = y + dy;
                if (yy >= stem.start && yy <= stem.end && raw(x, yy, direction) < 165) return true;
            }
        return false;
    }

    private boolean connectedShaft(Stem stem, int direction) {
        int width = stem.right - stem.left + 1;
        boolean[] prior = new boolean[width];
        for (int y = stem.start; y <= stem.end; y++) {
            boolean[] next = new boolean[width];
            boolean any = false;
            for (int x = 0; x < width; x++)
                if (raw(stem.left + x, y, direction) < 165) {
                    next[x] =
                            y == stem.start
                                    || prior[x]
                                    || x > 0 && prior[x - 1]
                                    || x + 1 < width && prior[x + 1];
                    any |= next[x];
                }
            if (!any) return false;
            prior = next;
        }
        return true;
    }
}
