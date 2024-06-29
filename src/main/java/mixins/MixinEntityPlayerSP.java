package mixins;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraftforge.common.MinecraftForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import util.UpdatePlayerEvent;

@Mixin(value = {EntityPlayerSP.class}, priority = 9998)
public class MixinEntityPlayerSP {
    @Inject(method = "onUpdate", at = {@At(value = "HEAD")})
    private void updateHook(CallbackInfo info) {
        UpdatePlayerEvent ev = new UpdatePlayerEvent();
        MinecraftForge.EVENT_BUS.post(ev);
        if (ev.isCanceled()) info.cancel();
    }
}
