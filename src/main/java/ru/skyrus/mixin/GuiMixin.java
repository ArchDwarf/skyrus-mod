package ru.skyrus.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import ru.skyrus.SkyRus;

/** Большие заголовки и подзаголовки в центре экрана (/title). */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @ModifyVariable(method = "setTitle", at = @At("HEAD"), argsOnly = true)
    private Component skyrus$title(Component title) {
        return SkyRus.active() && SkyRus.config.translateTitles ? SkyRus.tr(title) : title;
    }

    @ModifyVariable(method = "setSubtitle", at = @At("HEAD"), argsOnly = true)
    private Component skyrus$subtitle(Component subtitle) {
        return SkyRus.active() && SkyRus.config.translateTitles ? SkyRus.tr(subtitle) : subtitle;
    }
}
