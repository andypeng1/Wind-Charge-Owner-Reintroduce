package com.windchargeownerreintroduce.mixin;

import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * AbstractArrow#onHitBlock 是 protected 的，这里开一个 Invoker 供 mixin 调用，
 * 以便复刻原版“箭插进方块”的完整流程（lastState / inGround / 音效 / 位置修正等）。
 */
@Mixin(AbstractArrow.class)
public interface AbstractArrowInvoker {

    @Invoker("onHitBlock")
    void windchargeownerreintroduce$invokeOnHitBlock(BlockHitResult blockHitResult);
}
