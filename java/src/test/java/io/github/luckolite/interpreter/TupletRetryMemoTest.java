// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class TupletRetryMemoTest {
    @Test
    public void equalQueriesMatchOnlyForTheCurrentReadAndRaster() {
        byte[] page = new byte[64], otherPage = page.clone();
        assertNull(TupletNumeralInk.activeRetryMemo(page));
        try (var scope = TupletNumeralInk.memoizeRetries()) {
            assertSame(scope, TupletNumeralInk.activeRetryMemo(page));
            scope.record(key(Float.NaN, false), false);
            assertEquals(TupletNumeralInk.RetryMiss.UNCHANGED_EDGES,
                    scope.find(key(Float.intBitsToFloat(0x7fc00001), false)));
            assertNull(scope.find(key(1, false)));
            assertNull(scope.find(key(Float.NaN, true)));
            try (var nested = TupletNumeralInk.memoizeRetries()) {
                assertSame(nested, TupletNumeralInk.activeRetryMemo(page));
                assertNull(nested.find(key(Float.NaN, false)));
            }
            assertSame(scope, TupletNumeralInk.activeRetryMemo(page));
            assertEquals(TupletNumeralInk.RetryMiss.UNCHANGED_EDGES,
                    scope.find(key(Float.NaN, false)));
            assertNull(TupletNumeralInk.activeRetryMemo(otherPage).find(key(Float.NaN, false)));
        }
        assertNull(TupletNumeralInk.activeRetryMemo(page));
    }

    private static TupletNumeralInk.RetryKey key(float headX, boolean edges) {
        return new TupletNumeralInk.RetryKey(
                new TupletNumeralInk.RetryGeometry(8, 0, 0, 8, 8, 1f, 5f, 1f, 5f, 3f,
                        headX, Float.NaN, true, 3, false),
                165, edges);
    }
}
