package com.windchargeownerreintroduce.mixin;

import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * AbstractArrow#inGround / #lastState 是 private 字段，这里开出来，
 * 供“把箭从活板门里顶出来”的逻辑同步原版的落地状态。
 */
@Mixin(AbstractArrow.class)
public interface AbstractArrowAccessor {

    @Accessor("inGround")
    void windchargeownerreintroduce$setInGround(boolean inGround);

    @Accessor("lastState")
    void windchargeownerreintroduce$setLastState(BlockState lastState);
}
