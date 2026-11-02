package ru.skyrus;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Готовые переводы всех предметов SkyBlock (assets/skyrus/items_ru.json, собирается tools/translate_items.py).
 * Описание делится на абзацы и ищется целиком — так фраза, разорванная цветами и переносами строк,
 * переводится как одно предложение. Числа в ключах заменены на {0}, {1}..., поэтому перевод подходит
 * при любых значениях характеристик. Правила разбиения должны совпадать с tools/extract_items.py.
 */
public final class ItemLore {
    private static final Pattern CODE = Pattern.compile("§.");
    private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern NUM = Pattern.compile("[+-]?\\d[\\d,]*(?:\\.\\d+)?");
    private static final Pattern LABEL = Pattern.compile("^[^:]{1,40}:\\s");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?]$");
    private static final Pattern SOFT_END = Pattern.compile("[,;:]$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");
    private static final Pattern NOT_LETTER_TAIL = Pattern.compile("[^\\p{L})]+$");

    private static final Map<String, String> NAMES = new HashMap<>();
    private static final Map<String, String> TEXTS = new HashMap<>();

    private static final Map<List<Component>, List<Component>> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<List<Component>, List<Component>> e) {
                    return size() > 512;
                }
            });

    private ItemLore() {}

    public static void load() {
        NAMES.clear();
        TEXTS.clear();
        CACHE.clear();
        try (InputStream in = ItemLore.class.getResourceAsStream("/assets/skyrus/items_ru.json")) {
            if (in == null) return;
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("names").entrySet()) {
                    NAMES.put(e.getKey(), e.getValue().getAsString());
                }
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("texts").entrySet()) {
                    TEXTS.put(e.getKey(), e.getValue().getAsString());
                }
            }
        } catch (Exception e) {
            System.err.println("[SkyRus] Ошибка чтения items_ru.json: " + e);
        }
    }

    public static int size() {
        return NAMES.size() + TEXTS.size();
    }

    public static void clearCache() {
        CACHE.clear();
    }

    // ---------- общие функции (как в extract_items.py) ----------

    static String plain(String s) {
        return SPACES.matcher(CODE.matcher(s).replaceAll("")).replaceAll(" ").strip();
    }

    private record Key(String key, List<String> nums) {}

    static Key toKey(String text) {
        List<String> nums = new ArrayList<>();
        Matcher m = NUM.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(text, last, m.start()).append('{').append(nums.size()).append('}');
            nums.add(m.group());
            last = m.end();
        }
        sb.append(text.substring(last));
        return new Key(sb.toString(), nums);
    }

    static String fill(String template, List<String> nums) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            int i = Integer.parseInt(m.group(1));
            if (i >= nums.size()) return null;
            sb.append(template, last, m.start()).append(nums.get(i));
            last = m.end();
        }
        sb.append(template.substring(last));
        return sb.toString();
    }

    static boolean isSingle(String p) {
        if (LABEL.matcher(p).find()) return true;
        int letters = 0;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (!Character.isUpperCase(c)) return false;
            }
        }
        return letters >= 3;
    }

    static boolean endsParagraph(String prev, String next) {
        if (SENTENCE_END.matcher(prev).find()) return true;
        return prev.split(" ").length <= 4 && !SOFT_END.matcher(prev).find()
                && !next.isEmpty() && Character.isUpperCase(next.charAt(0));
    }

    /** Абзацы: списки индексов строк (как paragraphs() в Python). */
    static List<List<Integer>> paragraphs(List<String> plains, int from) {
        List<List<Integer>> out = new ArrayList<>();
        List<Integer> cur = new ArrayList<>();
        for (int i = from; i < plains.size(); i++) {
            String p = plains.get(i);
            if (!cur.isEmpty() && !p.isEmpty() && !isSingle(p) && endsParagraph(plains.get(cur.get(cur.size() - 1)), p)) {
                out.add(cur);
                cur = new ArrayList<>();
            }
            if (p.isEmpty()) {
                if (!cur.isEmpty()) { out.add(cur); cur = new ArrayList<>(); }
                out.add(List.of(i)); // пустая строка остаётся как есть
                continue;
            }
            if (isSingle(p)) {
                if (!cur.isEmpty()) { out.add(cur); cur = new ArrayList<>(); }
                out.add(List.of(i));
                continue;
            }
            cur.add(i);
        }
        if (!cur.isEmpty()) out.add(cur);
        return out;
    }

    // ---------- перевод описания ----------

    public static List<Component> translate(List<Component> lines) {
        if (lines.isEmpty()) return lines;
        List<Component> cached = CACHE.get(lines);
        if (cached != null) return cached;

        boolean complete = true;
        List<String> plains = new ArrayList<>(lines.size());
        for (Component c : lines) plains.add(plain(c.getString()));

        List<Component> out = new ArrayList<>(lines.size() + 4);

        // Название предмета
        Component name = translateName(lines.get(0), plains.get(0));
        if (name == null) {
            SkyRus.Result r = SkyRus.translate(lines.get(0), true);
            if (!r.pending().isEmpty()) complete = false;
            name = r.component();
        }
        out.add(name);

        // Описание
        for (List<Integer> group : paragraphs(plains, 1)) {
            List<Component> done = TEXTS.isEmpty() ? null : translateParagraph(lines, plains, group);
            if (done != null) {
                out.addAll(done);
                continue;
            }
            for (int i : group) {
                SkyRus.Result r = SkyRus.translate(lines.get(i), true);
                if (!r.pending().isEmpty()) complete = false;
                out.add(r.component());
            }
        }
        if (complete) CACHE.put(new ArrayList<>(lines), out);
        return out;
    }

    /** Название: целиком, либо «приставка перековки + известное название + звёзды». */
    private static Component translateName(Component line, String plain) {
        if (NAMES.isEmpty() || plain.isEmpty()) return null;
        Style style = firstLetterStyle(List.of(line));
        String tail = "";
        Matcher t = NOT_LETTER_TAIL.matcher(plain);
        String body = plain;
        if (t.find()) {
            tail = plain.substring(t.start());
            body = plain.substring(0, t.start()).strip();
        }
        String[] words = body.split(" ");
        for (int start = 0; start < words.length; start++) {
            String candidate = String.join(" ", java.util.Arrays.copyOfRange(words, start, words.length));
            Key k = toKey(candidate);
            String tpl = NAMES.get(k.key());
            if (tpl == null) continue;
            String tr = fill(tpl, k.nums());
            if (tr == null) return null;
            if (start > 0) {
                String prefix = String.join(" ", java.util.Arrays.copyOfRange(words, 0, start));
                tr = SkyRus.strings.translate(prefix, true).text + " " + tr;
            }
            String tailSpace = tail.isEmpty() || tail.startsWith(" ") ? "" : " ";
            return Component.literal(tr + (tail.isEmpty() ? "" : tailSpace + tail.strip())).setStyle(style);
        }
        return null;
    }

    private static List<Component> translateParagraph(List<Component> lines, List<String> plains, List<Integer> group) {
        if (group.size() == 1 && plains.get(group.get(0)).isEmpty()) return List.of(lines.get(group.get(0)));
        StringBuilder joined = new StringBuilder();
        int width = 0;
        for (int i : group) {
            if (joined.length() > 0) joined.append(' ');
            joined.append(plains.get(i));
            width = Math.max(width, plains.get(i).length());
        }
        Key k = toKey(joined.toString());
        String tpl = TEXTS.get(k.key());
        if (tpl == null) return null;
        String text = fill(tpl, k.nums());
        if (text == null) return null;

        List<Component> src = new ArrayList<>();
        for (int i : group) src.add(lines.get(i));
        Style base = firstLetterStyle(src);
        Map<String, Style> numStyles = numberStyles(src);

        List<Component> result = new ArrayList<>();
        int wrap = group.size() == 1 ? Integer.MAX_VALUE : Math.max(width + 4, 30);
        for (String l : wrap(text, wrap)) result.add(colorize(l, base, numStyles));
        return result;
    }

    private static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : text.split(" ")) {
            if (cur.length() > 0 && cur.length() + 1 + w.length() > width) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(w);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** Числа сохраняют свой цвет (например красное «+50%»), остальной текст — цвет начала абзаца. */
    private static Component colorize(String line, Style base, Map<String, Style> numStyles) {
        MutableComponent m = Component.empty();
        Matcher nm = NUM.matcher(line);
        int last = 0;
        while (nm.find()) {
            String tok = nm.group();
            int end = nm.end();
            if (end < line.length() && line.charAt(end) == '%') { end++; tok = tok + "%"; }
            Style s = numStyles.getOrDefault(tok, numStyles.get(nm.group()));
            if (s == null) continue;
            if (nm.start() > last) m.append(Component.literal(line.substring(last, nm.start())).setStyle(base));
            m.append(Component.literal(tok).setStyle(s));
            last = end;
        }
        if (last < line.length()) m.append(Component.literal(line.substring(last)).setStyle(base));
        return m;
    }

    private static Style firstLetterStyle(List<Component> comps) {
        Style[] found = {null};
        for (Component c : comps) {
            c.visit((style, text) -> {
                for (int i = 0; i < text.length(); i++) {
                    if (Character.isLetter(text.charAt(i))) {
                        found[0] = style;
                        return Optional.of(Boolean.TRUE);
                    }
                }
                return Optional.empty();
            }, Style.EMPTY);
            if (found[0] != null) return found[0];
        }
        return Style.EMPTY;
    }

    private static Map<String, Style> numberStyles(List<Component> comps) {
        Map<String, Style> map = new HashMap<>();
        for (Component c : comps) {
            c.visit((style, text) -> {
                Matcher m = NUM.matcher(text);
                while (m.find()) {
                    map.putIfAbsent(m.group(), style);
                    int end = m.end();
                    if (end < text.length() && text.charAt(end) == '%') map.putIfAbsent(m.group() + "%", style);
                }
                return Optional.empty();
            }, Style.EMPTY);
        }
        return map;
    }
}
