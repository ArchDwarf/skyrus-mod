package ru.skyrus.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Машинный перевод EN -> RU с постоянным кэшем на диске.
 * Каждая уникальная строка переводится один раз, дальше берётся из mt_cache.json.
 */
public final class MachineTranslator implements StringTranslator.MachineSource {
    private static final int MAX_CACHE = 100_000;
    private static final int MAX_TEXT = 1000;

    private final SkyRusConfig config;
    private final Path cacheFile;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Long> failedUntil = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService pool;
    private final Object rateLock = new Object();
    private long nextSlot = 0;
    private volatile long backoffUntil = 0;
    private volatile boolean dirty = false;

    public MachineTranslator(SkyRusConfig config, Path cacheFile) {
        this.config = config;
        this.cacheFile = cacheFile;
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "SkyRus-MT-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        loadCache();
    }

    @Override
    public String cached(String text) {
        return cache.get(text);
    }

    @Override
    public CompletableFuture<String> request(String text) {
        String c = cache.get(text);
        if (c != null) return CompletableFuture.completedFuture(c);
        Long until = failedUntil.get(text);
        if (until != null && until > System.currentTimeMillis()) return CompletableFuture.completedFuture(null);
        if (text.length() > MAX_TEXT || "none".equalsIgnoreCase(config.provider)) {
            return CompletableFuture.completedFuture(null);
        }
        return inFlight.computeIfAbsent(text, t -> CompletableFuture.supplyAsync(() -> doTranslate(t), pool)
                .whenComplete((res, err) -> inFlight.remove(t)));
    }

    private String doTranslate(String text) {
        try {
            waitForSlot();
            String result = switch (config.provider.toLowerCase()) {
                case "deepl" -> deepl(text);
                case "libretranslate", "libre" -> libre(text);
                default -> google(text);
            };
            if (result == null || result.isBlank()) throw new IllegalStateException("пустой ответ");
            if (cache.size() < MAX_CACHE) {
                cache.put(text, result);
                dirty = true;
            }
            return result;
        } catch (RateLimited e) {
            backoffUntil = System.currentTimeMillis() + 30_000;
            failedUntil.put(text, System.currentTimeMillis() + 30_000);
            return null;
        } catch (Exception e) {
            failedUntil.put(text, System.currentTimeMillis() + 60_000);
            return null;
        }
    }

    private void waitForSlot() throws InterruptedException {
        long wait;
        synchronized (rateLock) {
            long now = System.currentTimeMillis();
            long interval = 1000L / Math.max(1, config.maxRequestsPerSecond);
            long start = Math.max(Math.max(now, nextSlot), backoffUntil);
            nextSlot = start + interval;
            wait = start - now;
        }
        if (wait > 0) Thread.sleep(wait);
    }

    private static final class RateLimited extends Exception {}

    private String send(HttpRequest req) throws Exception {
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 429 || resp.statusCode() == 456) throw new RateLimited();
        if (resp.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + resp.statusCode());
        return resp.body();
    }

    /** Бесплатный публичный endpoint Google Translate (без ключа, с ограничениями частоты). */
    private String google(String text) throws Exception {
        String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ru&dt=t&q="
                + URLEncoder.encode(text, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "Mozilla/5.0 SkyRus")
                .GET().build();
        JsonArray root = JsonParser.parseString(send(req)).getAsJsonArray();
        StringBuilder sb = new StringBuilder();
        for (JsonElement part : root.get(0).getAsJsonArray()) {
            JsonArray p = part.getAsJsonArray();
            if (!p.get(0).isJsonNull()) sb.append(p.get(0).getAsString());
        }
        return sb.toString();
    }

    private String deepl(String text) throws Exception {
        String key = config.deeplKey.trim();
        if (key.isEmpty()) throw new IllegalStateException("нет ключа DeepL");
        String host = key.endsWith(":fx") ? "https://api-free.deepl.com" : "https://api.deepl.com";
        JsonObject body = new JsonObject();
        JsonArray arr = new JsonArray();
        arr.add(text);
        body.add("text", arr);
        body.addProperty("source_lang", "EN");
        body.addProperty("target_lang", "RU");
        HttpRequest req = HttpRequest.newBuilder(URI.create(host + "/v2/translate"))
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "DeepL-Auth-Key " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        JsonObject root = JsonParser.parseString(send(req)).getAsJsonObject();
        return root.getAsJsonArray("translations").get(0).getAsJsonObject().get("text").getAsString();
    }

    private String libre(String text) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("q", text);
        body.addProperty("source", "en");
        body.addProperty("target", "ru");
        body.addProperty("format", "text");
        if (!config.libreKey.isBlank()) body.addProperty("api_key", config.libreKey.trim());
        HttpRequest req = HttpRequest.newBuilder(URI.create(config.libreUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        JsonObject root = JsonParser.parseString(send(req)).getAsJsonObject();
        return root.get("translatedText").getAsString();
    }

    public int cacheSize() {
        return cache.size();
    }

    public void clearCache() {
        cache.clear();
        failedUntil.clear();
        dirty = true;
        saveIfDirty();
    }

    private void loadCache() {
        if (!Files.exists(cacheFile)) return;
        try (Reader r = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8)) {
            Map<String, String> m = new Gson().fromJson(r, new TypeToken<Map<String, String>>() {}.getType());
            if (m != null) cache.putAll(m);
        } catch (Exception e) {
            System.err.println("[SkyRus] Кэш перевода повреждён, начинаю заново: " + e);
        }
    }

    public synchronized void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        try {
            Files.createDirectories(cacheFile.getParent());
            Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                new Gson().toJson(cache, w);
            }
            Files.move(tmp, cacheFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            dirty = true;
            System.err.println("[SkyRus] Не удалось сохранить кэш: " + e);
        }
    }
}
