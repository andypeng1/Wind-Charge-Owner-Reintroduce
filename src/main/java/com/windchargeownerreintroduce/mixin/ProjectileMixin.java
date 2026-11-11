package com.windchargeownerreintroduce.mixin;

import com.windchargeownerreintroduce.WindChargeOwnerReintroduce;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.windcharge.AbstractWindCharge;
import net.minecraft.world.entity.projectile.windcharge.WindCharge;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.1 中，箭（或其他弹射物）命中风弹时，原版 Projectile.onHit 会调用：
 * <pre>
 *   windCharge.deflect(AIM_DEFLECT, this.getOwner(), this.getOwner(), true);
 * </pre>
 * 而 deflect 内部通过 setOwner(Entity) 设置 owner。getOwner() 是按 UUID
 * 在 ServerLevel 里查找实体的，玩家离线时返回 null，于是 owner 丢失。
 * <p>
 * Owner 的 UUID 本身一直保存在箭的 NBT("Owner") 里，所以这里直接读取 UUID 写回风弹，
 * 这样即使箭的主人已经离线，Owner 也能正确转移。
 * <p>
 * 注意：这里只转移 owner UUID，不修改风弹速度。主人离线时原版 AIM_DEFLECT 拿不到
 * 实体，本身就是 no-op，因此风弹不会因为这次撞击获得任何动量（保持原有速度）。
 * <p>
 * 为了把"离线主人的箭不给风弹施加任何速度"变成硬保证，这里在 onHit 开头记下风弹
 * 命中前的速度，在 onHit 结束（原版 AIM_DEFLECT 已执行完）后恢复。即使将来原版逻辑
 * 或别的 mod 在这条路径上改动了风弹速度，离线主人命中时风弹也一定保持原速。
 */
@Mixin(Projectile.class)
public abstract class ProjectileMixin {

    /** 本次命中前风弹的速度；只有"主人无法解析"的命中才会被记录，命中结束后恢复。 */
    @Unique
    private Vec3 windchargeownerreintroduce$windChargeMotionBeforeHit;

    @Inject(method = "onHit", at = @At("HEAD"))
    private void windchargeownerreintroduce$transferOwnerToWindCharge(HitResult hitResult, CallbackInfo ci) {
        this.windchargeownerreintroduce$windChargeMotionBeforeHit = null;

        if (!(hitResult instanceof EntityHitResult entityHitResult)) {
            return;
        }

        if (!(entityHitResult.getEntity() instanceof AbstractWindCharge windCharge)) {
            return;
        }

        Projectile self = (Projectile) (Object) this;
        if (self.level().isClientSide) {
            return;
        }

        // 普通风弹生成后的前 5 tick 不会响应偏转，此时原版也不会转移 owner
        if (windCharge instanceof WindCharge normalWindCharge
                && ((WindChargeAccessor) normalWindCharge).getNoDeflectTicks() > 0) {
            return;
        }

        // 关键：使用持久化的 owner UUID，而不是 getOwner() 实体引用
        UUID ownerUuid = ((ProjectileAccessor) self).getOwnerUUID();
        if (ownerUuid == null) {
            return;
        }

        ProjectileAccessor targetAccessor = (ProjectileAccessor) windCharge;
        targetAccessor.setOwnerUUID(ownerUuid);
        targetAccessor.setCachedOwner(null);

        // 只为排查问题保留的诊断日志：箭的主人离线/不在当前维度时输出。
        // 服务器 latest.log 里看到这一行，就说明本 mixin 在服务端确实执行了。
        Entity resolvedOwner = self.getOwner();
        if (resolvedOwner == null) {
            // 记住命中前的速度，onHit 结束时恢复：离线主人的箭不给风弹施加任何速度。
            this.windchargeownerreintroduce$windChargeMotionBeforeHit = windCharge.getDeltaMovement();

            WindChargeOwnerReintroduce.LOGGER.info(
                    "[WCO] owner {} not resolvable (offline/other dimension); copied owner UUID to {} id={}",
                    ownerUuid, windCharge.getType(), windCharge.getId());
        } else {
            WindChargeOwnerReintroduce.LOGGER.debug(
                    "[WCO] copied owner UUID {} from {} to {} id={}",
                    ownerUuid, self.getType(), windCharge.getType(), windCharge.getId());
        }
    }

    /**
     * 原版 onHit 内部会调用 windCharge.deflect(AIM_DEFLECT, arrow.getOwner(), ...)。
     * 主人离线时 getOwner() 为 null，AIM_DEFLECT 本来就不改速度；这里在 onHit 结束后
     * 再把速度恢复成命中前的值，确保风弹不会因为离线主人的箭获得任何动量。
     * 主人在线时不会记录，保持原版 AIM_DEFLECT 的行为。
     */
    @Inject(method = "onHit", at = @At("TAIL"))
    private void windchargeownerreintroduce$restoreWindChargeMotion(HitResult hitResult, CallbackInfo ci) {
        Vec3 motionBeforeHit = this.windchargeownerreintroduce$windChargeMotionBeforeHit;
        if (motionBeforeHit == null) {
            return;
        }
        this.windchargeownerreintroduce$windChargeMotionBeforeHit = null;

        if (!(hitResult instanceof EntityHitResult entityHitResult)
                || !(entityHitResult.getEntity() instanceof AbstractWindCharge windCharge)) {
            return;
        }

        windCharge.setDeltaMovement(motionBeforeHit);
        WindChargeOwnerReintroduce.LOGGER.info(
                "[WCO][server] kept wind charge id={} motion at {} after offline-owner hit",
                windCharge.getId(), motionBeforeHit);
    }
}
