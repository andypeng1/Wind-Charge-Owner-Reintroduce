package com.windchargeownerreintroduce.mixin;

import net.minecraft.world.entity.projectile.windcharge.WindCharge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WindCharge.class)
public interface WindChargeAccessor {

    /** 风弹刚生成的前 5 tick 不会响应偏转（WindCharge.deflect 的守卫）。 */
    @Accessor("noDeflectTicks")
    int getNoDeflectTicks();
}
