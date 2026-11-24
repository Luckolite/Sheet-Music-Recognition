// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Finds two adjacent upstem columns separated by local paper across the staff. */
final class InterruptedUpstaffShaft {
    private InterruptedUpstaffShaft() {}

    private record Column(int x, int tip, int narrow, int ink) {}

    static PrintedBeamShafts.Shaft measure(byte[] gray, int width, int height, float x, float y,
            float gap, StaffPitchTrack track) {
        if (gap < 12 || gap > 24 || track == null || !track.verifiedAt(x)) return null;
        int first = Math.round(x + gap * .49f), last = Math.round(x + gap * .80f);
        int distal = Math.round(y - gap * 4.45f), proximal = Math.round(y - gap * .20f);
        int flank = Math.round(gap * .24f);
        if (first - flank < 0 || last + flank >= width || distal < 0 || proximal >= height) return null;
        var frame = track.at(x);
        var candidates = new ArrayList<Column>();
        for (int col = first; col <= last; col++) {
            int tip = -1, run = -1;
            for (int row = distal; row <= proximal; row++) {
                if (!ink(gray, width, col, row)) {
                    run = -1;
                } else {
                    if (run < 0) run = row;
                    if (row - run >= gap * .52f) {
                        tip = run;
                        break;
                    }
                }
            }
            if (tip < 0 || y - tip < gap * 2.7f || y - tip > gap * 4.3f) continue;
            int body = 0, narrow = 0, blank = 0, longest = 0, attachment = 0;
            for (int row = tip; row <= proximal; row++) {
                if (!ink(gray, width, col, row)) {
                    longest = Math.max(longest, ++blank);
                    continue;
                }
                blank = 0;
                body++;
                if (row >= y - gap * .74f) attachment++;
                float lane = (frame[0] - row) / frame[1];
                if (row - tip > gap * 1.08f && Math.abs(lane - Math.round(lane)) * frame[1] > gap * .19f
                        && !ink(gray, width, col - flank, row) && !ink(gray, width, col + flank, row))
                    narrow++;
            }
            if (body < (proximal - tip + 1) * .64f || narrow < gap * .25f
                    || longest > gap * .80f || attachment < gap * .23f) continue;
            candidates.add(new Column(col, tip, narrow, body));
        }
        Column a = null, b = null;
        int best = -1;
        for (int i = 1; i < candidates.size(); i++) {
            var left = candidates.get(i - 1);
            var right = candidates.get(i);
            if (right.x() - left.x() != 1 || Math.abs(left.tip() - right.tip()) > 2) continue;
            int score = (left.narrow() + right.narrow()) * 3 + left.ink() + right.ink();
            if (score > best) {
                best = score;
                a = left;
                b = right;
            }
        }
        if (a == null) return null;
        return new PrintedBeamShafts.Shaft(a.x(), b.x(), proximal, Math.max(a.tip(), b.tip()), -1,
                List.of(new PrintedBeamShafts.Column(a.x(), a.tip()), new PrintedBeamShafts.Column(b.x(), b.tip())));
    }

    private static boolean ink(byte[] gray, int width, int col, int row) {
        return (gray[row * width + col] & 255) < 165;
    }
}
