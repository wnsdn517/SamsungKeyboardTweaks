package io.github.wnsdn517.oldhangul.xposed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

public class CurrencyToolTest {

    @Before
    public void rates() {
        Map<String, Double> r = new HashMap<>();
        r.put("KRW", 1400.0);
        r.put("JPY", 150.0);
        r.put("EUR", 0.9);
        r.put("CNY", 7.0);
        r.put("GBP", 0.8);
        r.put("VND", 25000.0);
        r.put("THB", 35.0);
        r.put("NZD", 1.7);
        CurrencyTool.setRates(r);
        CurrencyTool.setEnabled(true);
    }

    private static String show(String text) {
        ToolSuggester.Result r = ToolSuggester.analyze(text);
        return r == null ? null : r.display;
    }

    @Test
    public void namesInManyLanguages() {
        assertEquals("$100.00 = ₩140,000 · ¥15,000 · €90.00 · CNY 700.00", show("100 usd"));
        assertEquals("$100.00 = ₩140,000 · ¥15,000 · €90.00 · CNY 700.00", show("$100"));
        assertEquals("$100.00 = ₩140,000 · ¥15,000 · €90.00 · CNY 700.00", show("100달러"));
        assertEquals("$100.00 = ₩140,000 · ¥15,000 · €90.00 · CNY 700.00", show("100 dollars"));
        assertEquals("₩14,000 = $10.00 · ¥1,500 · €9.00 · CNY 70.00", show("14000원"));
        assertEquals("¥1,500 = ₩14,000 · $10.00 · €9.00 · CNY 70.00", show("1500엔"));
        assertEquals("₩14,000 = $10.00 · ¥1,500 · €9.00 · CNY 70.00", show("₩14,000"));
        assertNotNull(show("5000 dong"));
        assertNotNull(show("1000 yen"));
        assertNotNull(show("200 baht"));
        assertNotNull(show("nzd 20"));
    }

    @Test
    public void explicitTargetAndMagnitudes() {
        assertEquals("$100.00 = ₩140,000", show("100 usd to krw"));
        assertEquals("$100.00 = ₩140,000", show("100usd-krw"));
        assertEquals("$100.00 = ₩140,000", show("100usd2krw"));
        assertEquals("¥1,000 = ₩9,333", show("1000엔-원"));
        assertEquals("¥1,000 = ₩9,333", show("1000엔2원"));
        assertEquals("¥1,000 = ₩9,333", show("1000jpy2krw"));
        assertEquals("$100.00 = ₩140,000", show("100달러-원"));
        assertEquals("$100.00 = ₩140,000", show("100달러2원"));
        assertEquals("$100.00 = ₩140,000", show("100달러 원"));
        assertEquals("₩10,000 = $7.14", show("1만원 달러"));
        assertEquals("NZD 10.00 = ₩8,235", show("10 nzd krw"));
    }

    @Test
    public void koreanNumerals() {
        assertEquals("₩50,000 = $35.71 · ¥5,357 · €32.14 · CNY 250.00", show("오만원"));
        assertEquals("₩1,000 = $0.71 · ¥107 · €0.64 · CNY 5.00", show("천원"));
        assertEquals("₩10,000 = $7.14 · ¥1,071 · €6.43 · CNY 50.00", show("만원"));
        assertEquals("¥30,000 = ₩280,000 · $200.00 · €180.00 · CNY 1,400.00", show("삼만엔"));
        assertEquals("₩2,500 = $1.79 · ¥268 · €1.61 · CNY 12.50", show("이천오백원"));
        assertEquals("₩3,500 = $2.50 · ¥375 · €2.25 · CNY 17.50", show("3천5백원"));
        assertEquals("₩100,000,000 = $71,428.57 · ¥10,714,286 · €64,285.71 · CNY 500,000.00", show("일억원"));
        assertEquals("₩50,000 = $35.71", show("5만 원 달러"));
        assertEquals(50000.0, CurrencyTool.koreanAmount("오만원", new int[1]), 0);
        assertEquals(12000000.0, CurrencyTool.koreanAmount("1천2백만", new int[1]), 0);
        assertTrue(Double.isNaN(CurrencyTool.koreanAmount("이 사람", new int[1])));   // no unit: not a number
        assertNull(show("오만 사람"));
    }

    @Test
    public void notCurrencies() {
        assertNull(show("100 usdx"));
        assertNull(show("hello usd"));
        assertNull(show("100 usd to usd"));
    }

    @Test
    public void ratesParsedFromBothFeeds() {
        Map<String, Double> a = CurrencyTool.parseRates(
                "{\"result\":\"success\",\"base_code\":\"USD\",\"rates\":{\"USD\":1,\"KRW\":1380.5,\"VND\":25400.1}}");
        assertEquals(1380.5, a.get("KRW"), 1e-9);
        assertEquals(3, a.size());
        Map<String, Double> b = CurrencyTool.parseRates(
                "{\"date\":\"2026-10-01\",\"usd\":{\"krw\":1380.5,\"jpy\":150.25,\"btc\":1.2e-05}}");
        assertEquals(150.25, b.get("JPY"), 1e-9);
        assertEquals(1.2e-5, b.get("BTC"), 1e-12);
        assertTrue(CurrencyTool.parseRates(null).isEmpty());
    }

    @Test
    public void questionsAndSeveralTargets() {
        assertEquals("$1.00 = ₩1,400", show("usd to krw"));
        assertEquals("$1.00 = ₩1,400", show("달러 -> 원"));
        assertNull(show("try php"));
        assertEquals("$100.00 = ₩140,000", show("100 usd to krw?"));
        assertEquals("$100.00 = ₩140,000", show("100달러 원 얼마"));
        assertEquals("$100.00 = ₩140,000 · ¥15,000", show("100 usd to krw jpy"));
    }
}
