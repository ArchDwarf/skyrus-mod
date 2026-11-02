package ru.skyrus.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Переводит одну строку (один «кусок» текста с одинаковым стилем).
 * Порядок: точное совпадение -> шаблоны и термины -> (если остался английский) машинный перевод.
 * Цветовые коды § сохраняются, переводится только текст между ними.
 */
public final class StringTranslator {
    private static final Pattern LEGACY_CODE = Pattern.compile("§[0-9a-fk-orA-FK-OR]");
    private static final Pattern HAS_LATIN = Pattern.compile("[A-Za-z]{2,}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\s*#\\s*(\\d+)\\s*}");

    /** Источник машинного перевода. Возвращает готовый перевод из кэша или null и ставит запрос в очередь. */
    public interface MachineSource {
        String cached(String text);
        CompletableFuture<String> request(String text);
    }

    public static final class Result {
        public final String text;
        public final List<CompletableFuture<String>> pending;
        Result(String text, List<CompletableFuture<String>> pending) {
            this.text = text;
            this.pending = pending;
        }
    }

    private final Dictionary dictionary;
    private volatile MachineSource machine;
    private volatile Set<String> protectedNames = Collections.emptySet();

    private final Map<String, String> dictCache = Collections.synchronizedMap(new LinkedHashMap<>(4096, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 20000;
        }
    });

    public StringTranslator(Dictionary dictionary) {
        this.dictionary = dictionary;
    }

    public void setMachine(MachineSource machine) {
        this.machine = machine;
    }

    public void setProtectedNames(Set<String> names) {
        this.protectedNames = names;
    }

    public void clearCache() {
        dictCache.clear();
    }

    public Result translate(String s, boolean allowMachine) {
        if (s == null || s.isEmpty() || !HAS_LATIN.matcher(s).find()) return new Result(s, List.of());
        List<CompletableFuture<String>> pending = new ArrayList<>(0);
        StringBuilder out = new StringBuilder(s.length() + 16);
        Matcher m = LEGACY_CODE.matcher(s);
        int last = 0;
        while (m.find()) {
            out.append(translatePiece(s.substring(last, m.start()), allowMachine, pending));
            out.append(m.group());
            last = m.end();
        }
        out.append(translatePiece(s.substring(last), allowMachine, pending));
        return new Result(out.toString(), pending);
    }

    private String translatePiece(String piece, boolean allowMachine, List<CompletableFuture<String>> pending) {
        if (piece.isEmpty() || !HAS_LATIN.matcher(piece).find()) return piece;
        int a = 0, b = piece.length();
        while (a < b && Character.isWhitespace(piece.charAt(a))) a++;
        while (b > a && Character.isWhitespace(piece.charAt(b - 1))) b--;
        String lead = piece.substring(0, a), core = piece.substring(a, b), trail = piece.substring(b);

        String exact = dictionary.exact(core);
        if (exact != null) return lead + exact + trail;

        String dict = dictCache.get(core);
        if (dict == null) {
            dict = dictionary.apply(core);
            dictCache.put(core, dict);
        }

        MachineSource mt = machine;
        Set<String> names = protectedNames;
        if (allowMachine && mt != null && dictionary.hasUntranslatedEnglish(dict, names)) {
            Protected p = protect(core, names);
            String cached = mt.cached(p.text);
            if (cached != null) {
                String restored = restore(cached, p.values);
                if (restored != null) return lead + restored + trail;
            } else {
                pending.add(mt.request(p.text));
            }
        }
        return lead + dict + trail;
    }

    private record Protected(String text, List<String> values) {}

    /** Заменяет ники игроков и «неприкасаемые» слова на {#0}, {#1}..., чтобы переводчик их не испортил. */
    private Protected protect(String core, Set<String> names) {
        List<String> values = new ArrayList<>();
        String text = core;
        for (String keep : dictionary.keepWords()) text = replaceWord(text, keep, values);
        if (!names.isEmpty()) {
            Matcher m = Pattern.compile("[A-Za-z0-9_]{3,16}").matcher(text);
            StringBuilder sb = new StringBuilder();
            int last = 0;
            while (m.find()) {
                if (names.contains(m.group())) {
                    sb.append(text, last, m.start()).append("{#").append(values.size()).append('}');
                    values.add(m.group());
                    last = m.end();
                }
            }
            sb.append(text.substring(last));
            text = sb.toString();
        }
        return new Protected(text, values);
    }

    private static String replaceWord(String text, String word, List<String> values) {
        Matcher m = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE).matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        boolean any = false;
        while (m.find()) {
            any = true;
            sb.append(text, last, m.start()).append("{#").append(values.size()).append('}');
            values.add(m.group());
            last = m.end();
        }
        if (!any) return text;
        sb.append(text.substring(last));
        return sb.toString();
    }

    /** Возвращает null, если переводчик потерял какой-то плейсхолдер. */
    static String restore(String translated, List<String> values) {
        if (values.isEmpty()) return translated;
        boolean[] seen = new boolean[values.size()];
        Matcher m = PLACEHOLDER.matcher(translated);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            if (idx < 0 || idx >= values.size()) return null;
            seen[idx] = true;
            sb.append(translated, last, m.start()).append(values.get(idx));
            last = m.end();
        }
        sb.append(translated.substring(last));
        for (boolean b : seen) if (!b) return null;
        return sb.toString();
    }
}
