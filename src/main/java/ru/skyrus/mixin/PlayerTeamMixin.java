package ru.skyrus.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.skyrus.SkyRus;

/**
 * Боковое табло (sidebar). Hypixel пишет текст строк в префикс/суффикс команды фиктивного «игрока» вида §0§r,
 * поэтому переводим результат formatNameForTeam, но только для таких фиктивных записей — ники игроков не трогаем.
 */
@Mixin(PlayerTeam.class)
public abstract class PlayerTeamMixin {
    @Inject(method = "formatNameForTeam", at = @At("RETURN"), cancellable = true)
    private static void skyrus$sidebar(Team team, Component name, CallbackInfoReturnable<MutableComponent> cir) {
        if (!SkyRus.active() || !SkyRus.config.translateSidebar || team == null) return;
        if (!SkyRus.looksLikeSidebarEntry(name)) return;
        MutableComponent original = cir.getReturnValue();
        Component t = SkyRus.tr(original);
        if (t != original) cir.setReturnValue(t.copy());
    }
}
