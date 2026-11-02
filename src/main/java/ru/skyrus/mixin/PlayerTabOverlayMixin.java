package ru.skyrus.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import ru.skyrus.SkyRus;

/** Список игроков по Tab: на Hypixel SkyBlock там навыки, статистика, события и т.д. */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {
    @ModifyReturnValue(method = "getNameForDisplay", at = @At("RETURN"))
    private Component skyrus$name(Component original) {
        return SkyRus.active() && SkyRus.config.translateTabList ? SkyRus.tr(original) : original;
    }

    @ModifyVariable(method = "setHeader", at = @At("HEAD"), argsOnly = true)
    private Component skyrus$header(Component header) {
        return SkyRus.active() && SkyRus.config.translateTabList ? SkyRus.tr(header) : header;
    }

    @ModifyVariable(method = "setFooter", at = @At("HEAD"), argsOnly = true)
    private Component skyrus$footer(Component footer) {
        return SkyRus.active() && SkyRus.config.translateTabList ? SkyRus.tr(footer) : footer;
    }
}
