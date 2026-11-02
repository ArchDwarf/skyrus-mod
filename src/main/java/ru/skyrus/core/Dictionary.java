package ru.skyrus.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Словарь терминов. Три уровня:
 *  exact    — полная строка целиком ("Click to view!" -> "Нажмите, чтобы посмотреть!")
 *  patterns — регулярные выражения (порядковые числительные, секунды и т.п.)
 *  terms    — слова и словосочетания, заменяются внутри строки по самому длинному совпадению.
 *  keep     — слова, которые никогда не переводятся (SkyBlock, Hypixel, MVP...).
 * Без зависимостей от Minecraft, чтобы логику можно было тестировать отдельно.
 */
public final class Dictionary {
    private static final Locale RU = Locale.forLanguageTag("ru");
    private static final Pattern WORD = Pattern.compile("[\\p{L}](?:[\\p{L}'’\\-]*[\\p{L}])?");
    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z][A-Za-z'’\\-]*[A-Za-z]");
    private static final Pattern ROMAN = Pattern.compile("^[IVXLCDM]+$");

    private final Map<String, String> exact = new HashMap<>();
    private final Map<String, String> exactLower = new HashMap<>();
    private final Map<String, String> terms = new HashMap<>();
    private final List<Pattern> patternRegex = new ArrayList<>();
    private final List<String> patternReplace = new ArrayList<>();
    private final Set<String> keepLower = new HashSet<>();
    private final List<String> keepOriginal = new ArrayList<>();
    private int maxTermWords = 1;

    public void clear() {
        exact.clear(); exactLower.clear(); terms.clear();
        patternRegex.clear(); patternReplace.clear();
        keepLower.clear(); keepOriginal.clear();
        maxTermWords = 1;
    }

    /** Загружает JSON и добавляет записи поверх уже загруженных (последний файл главнее). */
    public void load(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        if (root.has("exact")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("exact").entrySet()) {
                String k = e.getKey().trim();
                exact.put(k, e.getValue().getAsString());
                exactLower.put(k.toLowerCase(Locale.ROOT), e.getValue().getAsString());
            }
        }
        if (root.has("terms")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("terms").entrySet()) {
                String key = normalizeKey(e.getKey());
                if (key.isEmpty()) continue;
                terms.put(key, e.getValue().getAsString());
                maxTermWords = Math.max(maxTermWords, key.split(" ").length);
            }
        }
        if (root.has("patterns")) {
            JsonArray arr = root.getAsJsonArray("patterns");
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                patternRegex.add(Pattern.compile(o.get("regex").getAsString()));
                patternReplace.add(o.get("replace").getAsString());
            }
        }
        if (root.has("keep")) {
            for (JsonElement el : root.getAsJsonArray("keep")) {
                String s = el.getAsString();
                if (keepLower.add(s.toLowerCase(Locale.ROOT))) keepOriginal.add(s);
            }
        }
    }

    public int size() {
        return exact.size() + terms.size() + patternRegex.size();
    }

    private static String normalizeKey(String s) {
        return s.trim().toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("\\s+", " ");
    }

    /** Точное совпадение всей строки (без учёта пробелов по краям). */
    public String exact(String core) {
        String r = exact.get(core);
        if (r != null) return r;
        r = exactLower.get(core.toLowerCase(Locale.ROOT));
        if (r != null) return adaptCase(core, r);
        return null;
    }

    /** Шаблоны + термины. */
    public String apply(String core) {
        String s = core;
        for (int i = 0; i < patternRegex.size(); i++) {
            s = patternRegex.get(i).matcher(s).replaceAll(patternReplace.get(i));
        }
        return applyTerms(s);
    }

    private String applyTerms(String s) {
        Matcher m = WORD.matcher(s);
        List<int[]> words = new ArrayList<>();
        while (m.find()) words.add(new int[]{m.start(), m.end()});
        if (words.isEmpty()) return s;

        StringBuilder out = new StringBuilder(s.length() + 16);
        int prevEnd = 0;
        int i = 0;
        while (i < words.size()) {
            boolean matched = false;
            int maxLen = Math.min(maxTermWords, words.size() - i);
            for (int len = maxLen; len >= 1; len--) {
                // Слова фразы должны идти через обычные пробелы
                boolean ok = true;
                StringBuilder key = new StringBuilder();
                for (int k = 0; k < len; k++) {
                    int[] w = words.get(i + k);
                    if (k > 0) {
                        String gap = s.substring(words.get(i + k - 1)[1], w[0]);
                        if (gap.isEmpty() || !gap.isBlank()) { ok = false; break; }
                        key.append(' ');
                    }
                    key.append(s, w[0], w[1]);
                }
                if (!ok) continue;
                String tr = terms.get(normalizeKey(key.toString()));
                if (tr != null) {
                    int start = words.get(i)[0];
                    int end = words.get(i + len - 1)[1];
                    out.append(s, prevEnd, start).append(adaptCase(s.substring(start, end), tr));
                    prevEnd = end;
                    i += len;
                    matched = true;
                    break;
                }
            }
            if (!matched) i++;
        }
        out.append(s, prevEnd, s.length());
        return out.toString();
    }

    /** Подгоняет регистр перевода под оригинал: CAPS -> КАПС, Title -> Заглавная, lower -> строчная. */
    static String adaptCase(String orig, String tr) {
        if (tr.isEmpty()) return tr;
        int letters = 0; boolean allUpper = true;
        for (int i = 0; i < orig.length(); i++) {
            char c = orig.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (!Character.isUpperCase(c)) allUpper = false;
            }
        }
        if (letters >= 2 && allUpper) return tr.toUpperCase(RU);
        char first = firstLetter(orig);
        char trFirst = tr.charAt(0);
        if (Character.isUpperCase(first) && Character.isLowerCase(trFirst)) {
            return Character.toUpperCase(trFirst) + tr.substring(1);
        }
        if (Character.isLowerCase(first) && Character.isUpperCase(trFirst)
                && !(tr.length() > 1 && Character.isUpperCase(tr.charAt(1)))) {
            return Character.toLowerCase(trFirst) + tr.substring(1);
        }
        return tr;
    }

    private static char firstLetter(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) return s.charAt(i);
        return 'a';
    }

    public boolean isKeep(String word) {
        return keepLower.contains(word.toLowerCase(Locale.ROOT));
    }

    public List<String> keepWords() {
        return keepOriginal;
    }

    /** Остались ли в строке английские слова, которые стоит отдать машинному переводу. */
    public boolean hasUntranslatedEnglish(String s, Set<String> protectedNames) {
        Matcher m = LATIN_WORD.matcher(s);
        while (m.find()) {
            String w = m.group();
            if (isKeep(w)) continue;
            if (ROMAN.matcher(w).matches()) continue;
            if (protectedNames.contains(w)) continue;
            return true;
        }
        return false;
    }
}
