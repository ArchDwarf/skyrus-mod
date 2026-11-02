package ru.skyrus.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.skyrus.SkyRus;

/** Полоса босса сверху экрана. */
@Mixin(BossEvent.class)
public abstract class BossEventMixin {
    @ModifyReturnValue(method = "getName", at = @At("RETURN"))
    private Component skyrus$name(Component original) {
        return SkyRus.active() && SkyRus.config.translateBossBar ? SkyRus.tr(original) : original;
    }
}
