package ru.skyrus;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.network.chat.Component;
import ru.skyrus.mixin.ScreenAccessor;

import java.util.List;
import java.util.regex.Pattern;

public final class SkyRusClient implements ClientModInitializer {
    /** «[MVP+] Nick: текст» / «Guild > Nick: текст» — сообщения игроков. */
    private static final Pattern PLAYER_MESSAGE =
            Pattern.compile("^(?:[A-Za-z]+ > )?(?:\\[[^\\]]+\\] ?)*[A-Za-z0-9_]{3,16}(?: \\[[^\\]]+\\])?: ");

    private int saveTimer = 0;

    @Override
    public void onInitializeClient() {
        SkyRus.init();

        // --- Чат и action bar ---
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (overlay || !SkyRus.active() || !SkyRus.config.translateChat) return true;
            if (isSkippedPlayerMessage(message)) return true;
            return ChatQueue.offer(message);
        });
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
            if (!SkyRus.active()) return message;
            if (overlay) return SkyRus.config.translateActionBar ? SkyRus.tr(message) : message;
            if (!SkyRus.config.translateChat || isSkippedPlayerMessage(message)) return message;
            return SkyRus.tr(message);
        });

        // --- Описания предметов (включая название предмета — это первая строка) ---
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            if (!SkyRus.active() || !SkyRus.config.translateTooltips) return;
            List<Component> translated = ItemLore.translate(List.copyOf(lines));
            lines.clear();
            lines.addAll(translated);
        });

        // --- Заголовки меню (сундуков/GUI) ---
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!SkyRus.active() || !SkyRus.config.translateScreenTitles) return;
            ScreenAccessor acc = (ScreenAccessor) screen;
            Component original = ScreenTitles.original(screen, acc.skyrus$getTitle());
            acc.skyrus$setTitle(SkyRus.tr(original));
            // Пока идёт машинный перевод — обновляем заголовок каждый тик
            ScreenEvents.afterTick(screen).register(s -> {
                if (SkyRus.active() && SkyRus.config.translateScreenTitles) {
                    ((ScreenAccessor) s).skyrus$setTitle(SkyRus.tr(original));
                }
            });
        });

        // --- Тики: очередь чата, ники, сохранение кэша ---
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            SkyRus.tick(mc);
            ChatQueue.tick(mc);
            if (++saveTimer >= 20 * 60) {
                saveTimer = 0;
                SkyRus.machine.saveIfDirty();
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> SkyRus.machine.saveIfDirty());

        registerCommands();
    }

    private static boolean isSkippedPlayerMessage(Component message) {
        return !SkyRus.config.translatePlayerMessages && PLAYER_MESSAGE.matcher(message.getString()).find();
    }

    private static void msg(FabricClientCommandSource src, String text) {
        src.sendFeedback(Component.literal("§6[SkyRus] §r" + text));
    }

    private static void save() {
        SkyRus.config.save(SkyRus.CONFIG_FILE);
        SkyRus.clearCaches();
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("skyrus")
                        .executes(ctx -> {
                            var c = SkyRus.config;
                            msg(ctx.getSource(), "Перевод: " + onOff(c.enabled)
                                    + "§r, автоперевод: " + onOff(c.machineTranslation) + "§r (" + c.provider + ")"
                                    + "§r, только Hypixel: " + onOff(c.onlyOnHypixel)
                                    + "\n§7Словарь: " + SkyRus.DICTIONARY.size() + " записей, предметы: " + ItemLore.size()
                                    + ", кэш автоперевода: "
                                    + SkyRus.machine.cacheSize() + " строк"
                                    + "\n§7Команды: /skyrus toggle | auto | provider <google|deepl|libretranslate|none>"
                                    + " | key <ключ DeepL> | part <chat|players|actionbar|tooltips|menus|titles|sidebar|tab|bossbar|names>"
                                    + " | everywhere | reload | clearcache");
                            return 1;
                        })
                        .then(ClientCommands.literal("toggle").executes(ctx -> {
                            SkyRus.config.enabled = !SkyRus.config.enabled;
                            save();
                            if (!SkyRus.config.enabled) ChatQueue.flush(ctx.getSource().getClient());
                            msg(ctx.getSource(), "Перевод " + onOff(SkyRus.config.enabled));
                            return 1;
                        }))
                        .then(ClientCommands.literal("auto").executes(ctx -> {
                            SkyRus.config.machineTranslation = !SkyRus.config.machineTranslation;
                            save();
                            SkyRus.applyMachineSetting();
                            msg(ctx.getSource(), "Автоматический перевод " + onOff(SkyRus.config.machineTranslation));
                            return 1;
                        }))
                        .then(ClientCommands.literal("everywhere").executes(ctx -> {
                            SkyRus.config.onlyOnHypixel = !SkyRus.config.onlyOnHypixel;
                            save();
                            msg(ctx.getSource(), "Только на Hypixel: " + onOff(SkyRus.config.onlyOnHypixel));
                            return 1;
                        }))
                        .then(ClientCommands.literal("provider")
                                .then(ClientCommands.argument("name", StringArgumentType.word()).executes(ctx -> {
                                    String p = StringArgumentType.getString(ctx, "name").toLowerCase();
                                    if (!p.matches("google|deepl|libretranslate|none")) {
                                        msg(ctx.getSource(), "§cНеизвестный переводчик: " + p);
                                        return 0;
                                    }
                                    SkyRus.config.provider = p;
                                    save();
                                    SkyRus.applyMachineSetting();
                                    msg(ctx.getSource(), "Переводчик: " + p);
                                    return 1;
                                })))
                        .then(ClientCommands.literal("key")
                                .then(ClientCommands.argument("key", StringArgumentType.greedyString()).executes(ctx -> {
                                    SkyRus.config.deeplKey = StringArgumentType.getString(ctx, "key").trim();
                                    SkyRus.config.provider = "deepl";
                                    save();
                                    SkyRus.applyMachineSetting();
                                    msg(ctx.getSource(), "Ключ DeepL сохранён, переводчик: deepl");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("part")
                                .then(ClientCommands.argument("part", StringArgumentType.word()).executes(ctx -> {
                                    String part = StringArgumentType.getString(ctx, "part").toLowerCase();
                                    Boolean v = togglePart(part);
                                    if (v == null) {
                                        msg(ctx.getSource(), "§cНеизвестная часть: " + part);
                                        return 0;
                                    }
                                    save();
                                    msg(ctx.getSource(), part + ": " + onOff(v));
                                    return 1;
                                })))
                        .then(ClientCommands.literal("reload").executes(ctx -> {
                            SkyRus.reloadDictionary();
                            msg(ctx.getSource(), "Словарь перезагружен: " + SkyRus.DICTIONARY.size() + " записей");
                            return 1;
                        }))
                        .then(ClientCommands.literal("clearcache").executes(ctx -> {
                            SkyRus.machine.clearCache();
                            SkyRus.clearCaches();
                            msg(ctx.getSource(), "Кэш автоперевода очищен");
                            return 1;
                        }))));
    }

    private static Boolean togglePart(String part) {
        var c = SkyRus.config;
        return switch (part) {
            case "chat" -> c.translateChat = !c.translateChat;
            case "players" -> c.translatePlayerMessages = !c.translatePlayerMessages;
            case "actionbar" -> c.translateActionBar = !c.translateActionBar;
            case "tooltips" -> c.translateTooltips = !c.translateTooltips;
            case "menus" -> c.translateScreenTitles = !c.translateScreenTitles;
            case "titles" -> c.translateTitles = !c.translateTitles;
            case "sidebar" -> c.translateSidebar = !c.translateSidebar;
            case "tab" -> c.translateTabList = !c.translateTabList;
            case "bossbar" -> c.translateBossBar = !c.translateBossBar;
            case "names" -> c.translateEntityNames = !c.translateEntityNames;
            default -> null;
        };
    }

    private static String onOff(boolean b) {
        return b ? "§aвкл" : "§cвыкл";
    }
}
