package ru.skyrus;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import ru.skyrus.core.Dictionary;
import ru.skyrus.core.MachineTranslator;
import ru.skyrus.core.SkyRusConfig;
import ru.skyrus.core.StringTranslator;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Общее состояние мода и перевод компонентов Minecraft (Component) с сохранением цветов и стилей. */
public final class SkyRus {
    public static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("skyrus");
    public static final Path CONFIG_FILE = DIR.resolve("config.json");
    public static final Path USER_DICT = DIR.resolve("dictionary.json");
    public static final Path MT_CACHE = DIR.resolve("mt_cache.json");

    public static SkyRusConfig config;
    public static final Dictionary DICTIONARY = new Dictionary();
    public static StringTranslator strings;
    public static MachineTranslator machine;

    private static volatile boolean onHypixel = false;

    public record Result(Component component, List<CompletableFuture<String>> pending) {}

    /** Готовые переводы компонентов (только полностью готовые, без ожидающих запросов). */
    private static final Map<Component, Component> DONE = Collections.synchronizedMap(
            new LinkedHashMap<>(2048, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Component, Component> e) {
                    return size() > 8192;
                }
            });

    private SkyRus() {}

    public static void init() {
        config = SkyRusConfig.load(CONFIG_FILE);
        strings = new StringTranslator(DICTIONARY);
        machine = new MachineTranslator(config, MT_CACHE);
        reloadDictionary();
    }

    public static void reloadDictionary() {
        DICTIONARY.clear();
        try (InputStream in = SkyRus.class.getResourceAsStream("/assets/skyrus/dictionary.json")) {
            if (in != null) {
                try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    DICTIONARY.load(r);
                }
            }
        } catch (Exception e) {
            System.err.println("[SkyRus] Ошибка встроенного словаря: " + e);
        }
        try {
            if (!Files.exists(USER_DICT)) {
                Files.createDirectories(DIR);
                Files.writeString(USER_DICT, """
                        {
                          "_readme": "Свой словарь. Записи отсюда главнее встроенных. После правки: /skyrus reload",
                          "exact": {
                            "Example full line!": "Пример целой строки!"
                          },
                          "terms": {
                            "Example Term": "Пример термина"
                          },
                          "patterns": [],
                          "keep": []
                        }
                        """, StandardCharsets.UTF_8);
            }
            try (Reader r = Files.newBufferedReader(USER_DICT, StandardCharsets.UTF_8)) {
                DICTIONARY.load(r);
            }
        } catch (Exception e) {
            System.err.println("[SkyRus] Ошибка пользовательского словаря: " + e);
        }
        ItemLore.load();
        applyMachineSetting();
        clearCaches();
    }

    public static void applyMachineSetting() {
        strings.setMachine(config.machineTranslation && !"none".equalsIgnoreCase(config.provider) ? machine : null);
        DONE.clear();
        ItemLore.clearCache();
    }

    public static void clearCaches() {
        DONE.clear();
        strings.clearCache();
        ItemLore.clearCache();
    }

    public static boolean active() {
        return config != null && config.enabled && (!config.onlyOnHypixel || onHypixel);
    }

    /** Вызывается раз в тик: определяем сервер и обновляем список ников, которые нельзя переводить. */
    public static void tick(Minecraft mc) {
        ServerData server = mc.getCurrentServer();
        onHypixel = server != null && server.ip != null && server.ip.toLowerCase().contains("hypixel");
        if (mc.level != null && mc.level.getGameTime() % 100 == 0) {
            ClientPacketListener conn = mc.getConnection();
            if (conn != null) {
                Set<String> names = new HashSet<>();
                for (PlayerInfo info : conn.getOnlinePlayers()) {
                    String n = info.getProfile().name();
                    if (n != null && n.length() >= 3) names.add(n);
                }
                if (mc.player != null) names.add(mc.player.getGameProfile().name());
                strings.setProtectedNames(names);
            }
        }
    }

    public static Component tr(Component c) {
        if (c == null || !active()) return c;
        return translate(c, true).component();
    }

    /** Переводит компонент кусок за куском, сохраняя стиль (цвет, жирность, click/hover-события) каждого куска. */
    public static Result translate(Component c, boolean allowMachine) {
        if (c == null) return new Result(null, List.of());
        Component done = DONE.get(c);
        if (done != null) return new Result(done, List.of());

        List<Style> styles = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        c.visit((style, text) -> {
            styles.add(style);
            texts.add(text);
            return Optional.empty();
        }, Style.EMPTY);

        boolean changed = false;
        List<CompletableFuture<String>> pending = new ArrayList<>(0);
        List<String> out = new ArrayList<>(texts.size());
        for (String t : texts) {
            StringTranslator.Result r = strings.translate(t, allowMachine);
            pending.addAll(r.pending);
            if (!r.text.equals(t)) changed = true;
            out.add(r.text);
        }
        Component result = c;
        if (changed) {
            MutableComponent m = Component.empty();
            for (int i = 0; i < out.size(); i++) {
                m.append(Component.literal(out.get(i)).setStyle(styles.get(i)));
            }
            result = m;
        }
        if (pending.isEmpty()) DONE.put(c, result);
        return new Result(result, pending);
    }

    /** Строки вида «§0§r» — фиктивные записи табло Hypixel (настоящий текст в префиксе/суффиксе команды). */
    public static boolean looksLikeSidebarEntry(Component name) {
        String s = name.getString();
        return s.isEmpty() || s.replaceAll("§.", "").isBlank();
    }
}
