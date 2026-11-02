package ru.skyrus;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.WeakHashMap;

/** Запоминает исходный (английский) заголовок экрана, чтобы при повторной инициализации не переводить перевод. */
public final class ScreenTitles {
    private static final Map<Screen, Component> ORIGINALS = new WeakHashMap<>();

    private ScreenTitles() {}

    public static Component original(Screen screen, Component current) {
        return ORIGINALS.computeIfAbsent(screen, s -> current);
    }
}
