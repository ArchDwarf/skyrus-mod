package ru.skyrus;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Сообщения чата, для которых ещё нет машинного перевода, придерживаются здесь,
 * пока перевод не придёт (или не истечёт chatWaitMs), и выводятся строго по порядку.
 */
public final class ChatQueue {
    private record Entry(Component original, List<CompletableFuture<String>> pending, long deadline) {}

    private static final ArrayDeque<Entry> QUEUE = new ArrayDeque<>();

    private ChatQueue() {}

    /** @return true — сообщение можно показать сразу (будет переведено в MODIFY_GAME), false — оно отложено. */
    public static boolean offer(Component message) {
        SkyRus.Result r = SkyRus.translate(message, true);
        if (r.pending().isEmpty() && QUEUE.isEmpty()) return true;
        QUEUE.addLast(new Entry(message, r.pending(), System.currentTimeMillis() + SkyRus.config.chatWaitMs));
        return false;
    }

    public static void tick(Minecraft mc) {
        if (QUEUE.isEmpty()) return;
        long now = System.currentTimeMillis();
        while (!QUEUE.isEmpty()) {
            Entry e = QUEUE.peekFirst();
            boolean ready = e.pending().stream().allMatch(CompletableFuture::isDone);
            if (!ready && now < e.deadline()) break;
            QUEUE.pollFirst();
            Component out = SkyRus.translate(e.original(), true).component();
            if (mc.gui != null) mc.gui.hud.getChat().addClientSystemMessage(out);
        }
    }

    public static void flush(Minecraft mc) {
        while (!QUEUE.isEmpty()) {
            Entry e = QUEUE.pollFirst();
            if (mc.gui != null) mc.gui.hud.getChat().addClientSystemMessage(SkyRus.translate(e.original(), false).component());
        }
    }
}
