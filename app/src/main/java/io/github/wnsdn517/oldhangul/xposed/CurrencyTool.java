package io.github.wnsdn517.oldhangul.xposed;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Currency conversion for the tool cards: "100 usd", "$100", "5000원", "1만엔", "100달러 원",
 * "100 usd to krw", "nzd 20", "¥1000"...
 *
 * <p>Currencies are recognised by ISO code, symbol and by their names in Korean, English,
 * Japanese and Chinese. Rates are per 1 USD ({@link #setRates}); {@link CurrencyFeed} keeps them
 * fresh. Without rates nothing is suggested.
 */
final class CurrencyTool {
    private CurrencyTool() {}

    /** Units of each currency per 1 USD. */
    private static volatile Map<String, Double> rates = Collections.emptyMap();
    private static volatile boolean enabled = true;

    static void setRates(Map<String, Double> perUsd) {
        Map<String, Double> copy = new HashMap<>(perUsd);
        copy.put("USD", 1.0);
        rates = copy;
    }

    static void setEnabled(boolean on) {
        enabled = on;
    }

    static boolean hasRates() {
        return rates.size() > 5;
    }

    /** Common currencies first: the cards of a conversion follow this order. */
    private static final String[] POPULAR = {"KRW", "USD", "JPY", "EUR", "CNY", "GBP", "VND", "THB"};

    private static final Map<String, String> WORDS = new HashMap<>();
    private static final List<String> WORDS_LONGEST_FIRST = new ArrayList<>();

    private static void words(String code, String... names) {
        for (String n : names) WORDS.put(n.toLowerCase(Locale.ROOT), code);
    }

    static {
        words("USD", "$", "us$", "usd", "dollar", "dollars", "달러", "달라", "불", "미국달러", "ドル", "美元", "弗", "bucks");
        words("KRW", "₩", "krw", "won", "원", "한국원", "한화", "ウォン", "韩元", "韓元", "원화");
        words("JPY", "¥", "jpy", "yen", "엔", "일엔", "円", "엔화", "日元", "日圓", "￥");
        words("CNY", "cny", "rmb", "yuan", "위안", "인민폐", "元", "人民币", "人民幣", "위안화", "cn¥");
        words("EUR", "€", "eur", "euro", "euros", "유로", "유로화", "ユーロ", "欧元", "歐元");
        words("GBP", "£", "gbp", "파운드", "영국파운드", "ポンド", "英镑", "英鎊", "sterling");
        words("VND", "₫", "vnd", "dong", "동", "ドン", "越南盾", "베트남동");
        words("THB", "฿", "thb", "baht", "바트", "バーツ", "泰铢");
        words("INR", "₹", "inr", "rupee", "rupees", "루피", "ルピー", "卢比");
        words("IDR", "idr", "rupiah", "루피아", "ルピア");
        words("RUB", "₽", "rub", "ruble", "rubles", "루블", "ルーブル", "卢布");
        words("TRY", "₺", "try", "lira", "리라", "リラ");
        words("PHP", "₱", "php", "peso", "pesos", "페소", "ペソ", "比索");
        words("ILS", "₪", "ils", "shekel", "셰켈", "シェケル");
        words("UAH", "₴", "uah", "hryvnia", "흐리우냐");
        words("AUD", "aud", "a$", "au$", "호주달러", "豪元", "オーストラリアドル");
        words("NZD", "nzd", "nz$", "뉴질랜드달러", "新西兰元", "ニュージーランドドル");
        words("CAD", "cad", "c$", "ca$", "캐나다달러", "加元", "カナダドル");
        words("HKD", "hkd", "hk$", "홍콩달러", "港元", "香港ドル");
        words("SGD", "sgd", "s$", "싱가포르달러", "新加坡元", "シンガポールドル");
        words("TWD", "twd", "nt$", "대만달러", "新台币", "台湾ドル", "신타이완달러");
        words("CHF", "chf", "franc", "francs", "프랑", "スイスフラン", "法郎");
        words("MYR", "myr", "ringgit", "링깃", "リンギット");
        words("MXN", "mxn", "멕시코페소");
        words("BRL", "brl", "real", "헤알", "レアル");
        words("ZAR", "zar", "rand", "랜드");
        words("AED", "aed", "dirham", "디르함");
        words("SAR", "sar", "riyal", "리얄");
        words("SEK", "sek", "크로나");
        words("NOK", "nok", "노르웨이크로네");
        words("DKK", "dkk", "덴마크크로네");
        words("PLN", "pln", "zloty", "즐로티");
        words("CZK", "czk", "코루나");
        words("HUF", "huf", "forint", "포린트");
        words("MNT", "mnt", "tugrik", "투그릭");
        words("KZT", "kzt", "tenge", "텡게");
        words("PKR", "pkr");
        words("BDT", "bdt", "taka", "타카");
        words("EGP", "egp");
        words("ARS", "ars");
        words("CLP", "clp");
        words("COP", "cop");
        words("PEN", "pen");
        words("USD", "미국 달러", "미불", "달러화", "us dollar", "us dollars");
        words("JPY", "일본 엔", "일본엔");
        words("CNY", "중국 위안", "중국위안", "cnh", "렌민비");
        words("EUR", "유럽 유로", "유로존");
        words("GBP", "영국 파운드", "파운드화");
        words("HKD", "홍콩 달러");
        words("TWD", "대만 달러", "타이완달러");
        words("AUD", "호주 달러", "오스트레일리아달러");
        words("CAD", "캐나다 달러");
        words("SGD", "싱가포르 달러");
        words("THB", "태국 바트", "태국바트");
        words("VND", "베트남 동");
        words("PHP", "필리핀 페소", "필리핀페소");
        words("BTC", "btc", "bitcoin", "비트코인", "ビットコイン");
        words("ETH", "eth", "ethereum", "이더리움");
        WORDS_LONGEST_FIRST.addAll(WORDS.keySet());
        Collections.sort(WORDS_LONGEST_FIRST, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return b.length() - a.length();
            }
        });
    }

    private static final Pattern AMOUNT = Pattern.compile(
            "^([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(만|천|억|조|k|thousand|million|mil)?\\s*");
    /** "…얼마", "…환율", "…?" at the end of a question. */
    private static final Pattern FILLER = Pattern.compile(
            "\\s*(?:[은는이가]\\s*)?(?:얼마(?:야|지|예요|에요|임|인가요|나)?|몇\\s*[원달러엔]?|환율|환산|변환|계산)?\\s*[?？!.~]*$");
    private static final Pattern EXPLICIT_SEPARATOR = Pattern.compile(
            "^\\s*(?:->|=>|→|=|>|to\\b|in\\b|into\\b|로|으로|→)\\s*");
    private static final Pattern SEPARATOR = Pattern.compile(
            "^\\s*(?:->|=>|→|=|>|-|~|/|_|:|2|to\\b|in\\b|into\\b|로|으로)?\\s*");

    /** The conversion for an expression that ends the text, or null. */
    static ToolSuggester.Result parse(String expr) {
        if (!enabled || rates.isEmpty() || expr == null) return null;
        String s = expr.trim().toLowerCase(Locale.ROOT);
        s = FILLER.matcher(s).replaceFirst("").trim();
        if (s.length() < 2 || s.length() > 48) return null;

        String from;
        double amount;
        String rest;
        int[] used = new int[1];
        double korean = koreanAmount(s, used);
        Matcher m = AMOUNT.matcher(s);
        if (!Double.isNaN(korean)) {
            // "오만원", "천원", "삼만엔", "3천5백원", "이천오백 원"
            amount = korean;
            String after = s.substring(used[0]);
            from = leadingWord(after);
            if (from == null) return null;
            rest = after.substring(wordLength(after));
        } else if (m.find()) {
            amount = number(m.group(1), m.group(2));
            String after = s.substring(m.end());
            from = leadingWord(after);
            if (from == null) return null;
            rest = after.substring(wordLength(after));
        } else {
            // "usd 100", "$100", "₩50,000"
            String code = leadingWord(s);
            if (code == null) return null;
            String after = s.substring(wordLength(s)).trim();
            Matcher n = AMOUNT.matcher(after);
            if (!n.find()) {
                // "usd to krw", "달러 -> 원": one unit, but only with an explicit separator
                // (a bare "try php" in running text is not a question).
                Matcher sep = EXPLICIT_SEPARATOR.matcher(after);
                if (!sep.find() || sep.end() >= after.length()) return null;
                from = code;
                amount = 1;
                rest = after;
            } else {
                from = code;
                amount = number(n.group(1), n.group(2));
                rest = after.substring(n.end());
            }
        }
        if (Double.isNaN(amount) || amount <= 0) return null;
        Double fromRate = rates.get(from);
        if (fromRate == null || fromRate == 0) return null;

        String[] targets;
        String tail = SEPARATOR.matcher(rest).replaceFirst("").trim();
        if (!tail.isEmpty()) {
            // one or more target currencies: "usd krw", "100 usd to krw jpy", "100달러 원 엔"
            List<String> list = new ArrayList<>();
            String left = tail;
            while (!left.isEmpty()) {
                String to = leadingWord(left);
                if (to == null) return null;
                int len = wordLength(left);
                if (!to.equals(from) && !list.contains(to)) list.add(to);
                left = left.substring(len).replaceFirst("^[\\s,/·+&]+", "");
            }
            if (list.isEmpty()) return null;
            targets = list.toArray(new String[0]);
        } else if (rest.trim().isEmpty() || SEPARATOR.matcher(rest).replaceFirst("").trim().isEmpty()) {
            targets = defaultTargets(from);
        } else {
            return null;
        }

        double usd = amount / fromRate;
        StringBuilder display = new StringBuilder(money(from, amount)).append(" = ");
        List<String[]> cards = new ArrayList<>();
        for (String to : targets) {
            Double r = rates.get(to);
            if (r == null) continue;
            String one = money(to, usd * r);
            cards.add(new String[] {one, one});
            display.append(cards.size() == 1 ? "" : " · ").append(one);
        }
        if (cards.isEmpty()) return null;
        ToolSuggester.Result result = new ToolSuggester.Result(display.toString(), cards.get(0)[1]);
        result.cards.clear();
        result.cards.addAll(cards);
        return result;
    }

    /** The device's own currency, shown first so "100 usd" answers in what the user spends. */
    private static String localCurrency() {
        try {
            return java.util.Currency.getInstance(Locale.getDefault()).getCurrencyCode();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String[] defaultTargets(String from) {
        List<String> out = new ArrayList<>();
        String local = localCurrency();
        if (local != null && !local.equals(from) && rates.containsKey(local)) out.add(local);
        for (String code : POPULAR) {
            if (out.contains(code)) continue;
            if (!code.equals(from) && rates.containsKey(code)) out.add(code);
            if (out.size() == 4) break;
        }
        return out.toArray(new String[0]);
    }

    /** ISO code (3 letters) or a known name at the start of the text, as an uppercase ISO code. */
    private static String leadingWord(String s) {
        String t = s.trim();
        for (String w : WORDS_LONGEST_FIRST) {
            if (t.startsWith(w)) {
                if (endsWord(t, w.length(), w)) return WORDS.get(w);
            }
        }
        if (t.length() >= 3 && t.substring(0, 3).matches("[a-z]{3}") && endsWord(t, 3, "abc")
                && rates.containsKey(t.substring(0, 3).toUpperCase(Locale.ROOT))) {
            return t.substring(0, 3).toUpperCase(Locale.ROOT);
        }
        return null;
    }

    private static int wordLength(String s) {
        String t = s.trim();
        int lead = s.length() - s.replaceFirst("^\\s+", "").length();
        for (String w : WORDS_LONGEST_FIRST) {
            if (t.startsWith(w) && endsWord(t, w.length(), w)) return lead + w.length();
        }
        return lead + 3;
    }

    /** A Latin word must not run on ("usdx"); symbols and Korean/Japanese/Chinese names may be followed by anything. */
    private static boolean endsWord(String t, int length, String word) {
        if (length >= t.length()) return true;
        boolean latin = Character.isLetter(word.charAt(word.length() - 1))
                && word.charAt(word.length() - 1) < 128;
        if (!latin) return true;
        char next = t.charAt(length);
        return !(Character.isLetter(next) && next < 128);
    }

    private static final String NUMERAL_CHARS = "0123456789,.일이삼사오육칠팔구영공십백천만억조 ";
    private static final String UNIT_CHARS = "십백천만억조";

    /**
     * A Korean amount at the start of the text: Sino-Korean numerals, digits, or a mix
     * ("오만", "천", "이천오백", "3천5백", "1억2천만"). It must contain a unit (십 백 천 만 억 조) or
     * an Arabic digit, so that a plain "이" or "오" in a sentence is not a number. NaN when there is none;
     * {@code used[0]} is the number of characters it takes.
     */
    static double koreanAmount(String s, int[] used) {
        int end = 0;
        while (end < s.length() && NUMERAL_CHARS.indexOf(s.charAt(end)) >= 0) end++;
        String t = s.substring(0, end);
        boolean hasUnit = false;
        boolean hasKorean = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (UNIT_CHARS.indexOf(c) >= 0) hasUnit = true;
            if (c >= 0xAC00 && c <= 0xD7A3) hasKorean = true;
        }
        if (!hasKorean || !hasUnit) return Double.NaN;
        double total = 0;
        double section = 0;
        double current = 0;
        boolean digitsPending = false;
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            int d = "영공일이삼사오육칠팔구".indexOf(c);
            if (d == 0 || d == 1) d = 0;
            else if (d >= 2) d -= 1;
            if (Character.isDigit(c) || c == '.' || c == ',') {
                digits.append(c);
                digitsPending = true;
            } else if (d >= 0) {
                current = current * 10 + d;
            } else if (c == ' ') {
                continue;
            } else {
                if (digitsPending) {
                    try {
                        current = Double.parseDouble(digits.toString().replace(",", ""));
                    } catch (NumberFormatException e) {
                        return Double.NaN;
                    }
                    digits.setLength(0);
                    digitsPending = false;
                }
                double unit;
                switch (c) {
                    case '십': unit = 10; break;
                    case '백': unit = 100; break;
                    case '천': unit = 1000; break;
                    default: unit = 0; break;
                }
                if (unit > 0) {
                    section += (current == 0 ? 1 : current) * unit;
                    current = 0;
                } else {
                    double big = c == '만' ? 1e4 : c == '억' ? 1e8 : 1e12;
                    double part = section + current;
                    total += (part == 0 ? 1 : part) * big;
                    section = 0;
                    current = 0;
                }
            }
        }
        if (digitsPending) {
            try {
                current = Double.parseDouble(digits.toString().replace(",", ""));
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        total += section + current;
        used[0] = end;
        return total > 0 ? total : Double.NaN;
    }

    private static double number(String digits, String magnitude) {
        double v;
        try {
            v = Double.parseDouble(digits.replace(",", ""));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
        if (magnitude == null) return v;
        switch (magnitude) {
            case "천": case "k": case "thousand": return v * 1e3;
            case "만": return v * 1e4;
            case "억": return v * 1e8;
            case "조": return v * 1e12;
            case "million": case "mil": return v * 1e6;
            default: return v;
        }
    }

    private static final List<String> NO_DECIMALS = Arrays.asList(
            "KRW", "JPY", "VND", "IDR", "CLP", "PYG", "UGX", "XOF", "XAF", "KMF", "GNF", "RWF", "VUV", "IRR", "LAK",
            "MNT", "COP", "HUF");
    private static final Map<String, String> SYMBOLS = new HashMap<>();

    static {
        SYMBOLS.put("USD", "$");
        SYMBOLS.put("EUR", "€");
        SYMBOLS.put("GBP", "£");
        SYMBOLS.put("JPY", "¥");
        SYMBOLS.put("KRW", "₩");
        SYMBOLS.put("VND", "₫");
        SYMBOLS.put("THB", "฿");
        SYMBOLS.put("INR", "₹");
        SYMBOLS.put("RUB", "₽");
        SYMBOLS.put("TRY", "₺");
        SYMBOLS.put("PHP", "₱");
        SYMBOLS.put("ILS", "₪");
        SYMBOLS.put("UAH", "₴");
    }

    static String money(String code, double value) {
        String number;
        if (code.equals("BTC") || code.equals("ETH")) {
            number = trim(String.format(Locale.US, "%.8f", value));
        } else if (NO_DECIMALS.contains(code) || Math.abs(value) >= 1e7) {
            number = String.format(Locale.US, "%,.0f", value);
        } else if (Math.abs(value) < 0.01) {
            number = String.format(Locale.US, "%.4f", value);
        } else {
            number = String.format(Locale.US, "%,.2f", value);
        }
        String symbol = SYMBOLS.get(code);
        return symbol != null ? symbol + number : code + " " + number;
    }

    private static String trim(String s) {
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    // ------------------------------------------------------------------- rates JSON

    private static final Pattern PAIR = Pattern.compile("\"([A-Za-z0-9]{3,10})\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)");

    /**
     * Reads rates per USD out of either feed: {"rates":{"KRW":1380.5,...}} (open.er-api.com) or
     * {"usd":{"krw":1380.5,...}} (fawazahmed0/currency-api).
     */
    static Map<String, Double> parseRates(String json) {
        Map<String, Double> out = new HashMap<>();
        if (json == null) return out;
        int start = json.indexOf("\"rates\"");
        if (start < 0) start = json.indexOf("\"usd\"");
        if (start < 0) return out;
        int open = json.indexOf('{', start);
        if (open < 0) return out;
        int depth = 0;
        int end = json.length();
        for (int i = open; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) {
                end = i;
                break;
            }
        }
        Matcher m = PAIR.matcher(json.substring(open, end));
        while (m.find()) {
            try {
                double v = Double.parseDouble(m.group(2));
                if (v > 0) out.put(m.group(1).toUpperCase(Locale.ROOT), v);
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return out;
    }
}
