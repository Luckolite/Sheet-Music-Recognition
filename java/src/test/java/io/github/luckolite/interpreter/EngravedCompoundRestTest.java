package io.github.luckolite.interpreter;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class EngravedCompoundRestTest {
    @Test
    public void twoDifferentPlatesBindThreeBarsToThePrintedCount() {
        var page = scene();
        var count = count(page);
        assertEquals(List.of(count), detect(page, count));
    }

    @Test
    public void aNoteOrMissingPlatePreventsClaimingTheOpening() {
        var page = scene();
        page.note(184, 120, 6, 5, false);
        assertTrue(detect(page, count(page)).isEmpty());
        page = scene();
        for (int y = 97; y <= 103; y++)
            for (int x = 185; x <= 197; x++) page.gray[y * page.w + x] = (byte) 255;
        assertTrue(detect(page, count(page)).isEmpty());
    }

    private static HeaderSymbolNormalizationTest.Page scene() {
        var page = new HeaderSymbolNormalizationTest.Page(false, true);
        for (int y = 96; y <= 112; y++)
            for (int x = 158; x <= 170; x++) page.gray[y * page.w + x] = 0;
        for (int y = 96; y <= 103; y++)
            for (int x = 185; x <= 197; x++) page.gray[y * page.w + x] = 0;
        return page;
    }

    private static MeasureNumberReconciler.NumberToken count(HeaderSymbolNormalizationTest.Page page) {
        return new MeasureNumberReconciler.NumberToken(
                3, 174f / page.w, 48f / page.h, 186f / page.w, 76f / page.h);
    }

    private static List<MeasureNumberReconciler.NumberToken> detect(
            HeaderSymbolNormalizationTest.Page page, MeasureNumberReconciler.NumberToken count) {
        var bar = new MeasureRegion(98f / page.w, 207f / page.w, 48f / page.h, 176f / page.h);
        return MultiMeasureRestDetector.detect(page.labels, page.gray, page.w, page.h,
                List.of(bar), List.of(count));
    }
}
