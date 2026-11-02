package ru.skyrus.mixin;

import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import ru.skyrus.SkyRus;

/** Большие заголовки и подзаголовки в центре экрана (/title). */
@Mixin(Hud.class)
public abstract class HudMixin {
    @ModifyVariable(method = "setTitle", at = @At("HEAD"), argsOnly = true)
    private Component skyrus$title(Component title) {
        return SkyRus.active() && SkyRus.config.translateTitles ? SkyRus.tr(title) : title;
    }

    @ModifyVariable(method = "setSubtitle", at = @At("HEAD"), argsOnly = true)
    private Component skyrus$subtitle(Component subtitle) {
        return SkyRus.active() && SkyRus.config.translateTitles ? SkyRus.tr(subtitle) : subtitle;
    }
}
