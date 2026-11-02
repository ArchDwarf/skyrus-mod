package ru.skyrus.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Настройки, файл config/skyrus/config.json. */
public final class SkyRusConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public boolean enabled = true;
    /** Работать только на серверах Hypixel. */
    public boolean onlyOnHypixel = true;

    /** Машинный перевод того, чего нет в словаре. */
    public boolean machineTranslation = true;
    /** google | deepl | libretranslate | none */
    public String provider = "google";
    public String deeplKey = "";
    public String libreUrl = "https://libretranslate.com/translate";
    public String libreKey = "";
    public int maxRequestsPerSecond = 4;

    public boolean translateChat = true;
    public boolean translatePlayerMessages = true;
    public boolean translateActionBar = true;
    public boolean translateTooltips = true;
    public boolean translateScreenTitles = true;
    public boolean translateSidebar = true;
    public boolean translateTabList = true;
    public boolean translateTitles = true;
    public boolean translateBossBar = true;
    public boolean translateEntityNames = true;

    /** Сколько ждать машинный перевод сообщения чата, прежде чем показать его как есть (мс). */
    public int chatWaitMs = 1500;

    public static SkyRusConfig load(Path file) {
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                SkyRusConfig c = GSON.fromJson(r, SkyRusConfig.class);
                if (c != null) return c;
            } catch (Exception e) {
                System.err.println("[SkyRus] Не удалось прочитать конфиг: " + e);
            }
        }
        SkyRusConfig c = new SkyRusConfig();
        c.save(file);
        return c;
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(this, w);
            }
        } catch (IOException e) {
            System.err.println("[SkyRus] Не удалось сохранить конфиг: " + e);
        }
    }
}
