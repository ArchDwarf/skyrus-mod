package ru.skyrus.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.skyrus.SkyRus;

/** Надписи над головами: голограммы, NPC, мобы («[Lv5] Zombie 100/100❤»). */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @ModifyReturnValue(method = "getCustomName", at = @At("RETURN"))
    private Component skyrus$customName(Component original) {
        if (original == null || !SkyRus.active() || !SkyRus.config.translateEntityNames) return original;
        return SkyRus.tr(original);
    }
}
