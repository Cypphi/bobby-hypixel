package de.johni0702.minecraft.bobby.mixin.sodium06;

import de.johni0702.minecraft.bobby.Bobby;
//? if >=1.21.11
/*import net.caffeinemc.mods.sodium.client.gui.SodiumConfigBuilder;*/
//? if <1.21.11
import net.caffeinemc.mods.sodium.client.gui.SodiumGameOptionPages;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

//? if >=1.21.11 {
/*@Mixin(value = SodiumConfigBuilder.class, remap = false)
*///?} else
@Mixin(value = SodiumGameOptionPages.class, remap = false)
public abstract class SodiumGameOptionPagesMixin {
    //? if >=1.21.11 {
    /*@ModifyConstant(method = "buildGeneralPage", constant = @Constant(intValue = 32, ordinal = 0))
    private int bobbyMaxRenderDistance(int oldValue) {
        int overwrite = Bobby.getInstance().getConfig().getMaxRenderDistance();
        return Math.max(3, overwrite);
    }
    *///?} else {
    @ModifyConstant(method = "lambda$general$0", constant = @Constant(intValue = 32))
    private static int bobbyMaxRenderDistance(int oldValue) {
        int overwrite = Bobby.getInstance().getConfig().getMaxRenderDistance();
        return Math.max(3, overwrite);
    }
    //?}
}
