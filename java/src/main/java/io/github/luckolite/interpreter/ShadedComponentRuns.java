// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;

/** Scratch queue for four-connected horizontal runs in shaded components. */
final class ShadedComponentRuns {
    int[] queue;

    ShadedComponentRuns(int size) {
        queue = new int[size];
    }

    int enqueue(byte[] core, int seed, int required, byte visited, int count) {
        // The empty border columns bound every horizontal run.
        int start = seed, finish = seed;
        while (core[start - 1] == required) start--;
        while (core[finish + 1] == required) finish++;
        Arrays.fill(core, start, finish + 1, visited);
        if (count + 2 > queue.length)
            queue = Arrays.copyOf(queue, count + Math.max(2, count));
        queue[count] = start;
        queue[count + 1] = finish;
        return count + 2;
    }
}
