package com.windchargeownerreintroduce.mixin;

import com.windchargeownerreintroduce.WindChargeOwnerReintroduce;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.projectile.windcharge.AbstractWindCharge;
import net.minecraft.world.entity.projectile.windcharge.WindCharge;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 箭命中风弹时，原版服务端和客户端的行为是不一致的：
 * <ul>
 *   <li>服务端：AbstractArrow#onHitEntity 在风弹（hurt() 恒为 false）上走失败分支，
 *       先 deflect(REVERSE)（速度 * -0.5，另有装饰性的随机 yaw），再 * 0.2，
 *       净效果是 <b>速度 * -0.1</b> —— 箭被弹回去；</li>
 *   <li>客户端：Projectile#deflect 内部有 {@code if (!isClientSide)}，REVERSE 整个不执行，
 *       只执行最后的 * 0.2 —— 箭继续朝原方向慢慢往下掉。</li>
 * </ul>
 * 主人在线时风弹会被 AIM_DEFLECT 弹走，箭的落点差异不明显；但主人离线时风弹原地不动：
 * <ol>
 *   <li>客户端那支“慢慢往下掉”的箭会和反弹后的箭轨迹分叉；</li>
 *   <li>反弹后的箭会再次落回风弹的命中区域，于是每 tick 再次命中，
 *       速度被反复 * -0.1，箭几乎停在原地并一点点往下沉，最后穿进活板门
 *       （MC-305092 的 margin-assisted hit 会持续覆盖方块命中）。</li>
 * </ol>
 * <p>
 * 处理方式：
 * <ul>
 *   <li>第一次命中：完全不拦截，保持原版“箭撞上风弹并反弹”的手感；
 *       客户端补上服务端已经做过的 REVERSE 反弹，保证两边看到同一根弹开的箭。</li>
 *   <li>第二次及以后：既然离线风弹不会像在线时那样被弹走，就按和 AbstractArrow#tick
 *       相同的扫掠线段重新做一次方块检测，直接把箭插进后面的方块（例如活板门）。
 *       箭不会被删除，也不会再从活板门里漏下去。</li>
 * </ul>
 * 风弹本身的速度完全不动：主人离线时原版 AIM_DEFLECT 拿不到实体，本来就是 no-op。
 * <p>
 * 主人在线、或风弹本来就没有主人（例如发射器射出的箭）时完全不处理，保持原版。
 */
@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin {

    /** 这根箭是否已经因为“离线主人的风弹”反弹过一次；用于第二次命中时改为插住。 */
    @Unique
    private boolean windchargeownerreintroduce$offlineBounceDone;

    @Inject(method = "onHitEntity", at = @At("HEAD"), cancellable = true)
    private void windchargeownerreintroduce$handleOfflineOwnerWindChargeHit(
            EntityHitResult entityHitResult, CallbackInfo ci) {
        if (!(entityHitResult.getEntity() instanceof AbstractWindCharge windCharge)) {
            return;
        }

        AbstractArrow self = (AbstractArrow) (Object) this;

        // 普通风弹生成后的前 5 tick 不响应偏转，原版此时也不会转移 owner，
        // 服务端 ProjectileMixin 也会跳过，这里保持一致。
        if (windCharge instanceof WindCharge normalWindCharge
                && ((WindChargeAccessor) normalWindCharge).getNoDeflectTicks() > 0) {
            return;
        }

        if (!windchargeownerreintroduce$ownerUnresolvable(self, windCharge)) {
            return;
        }

        boolean clientSide = self.level().isClientSide;

        // 第一次命中：保持原版反弹（服务端本来就是原版行为；客户端把 REVERSE 补上）。
        if (!this.windchargeownerreintroduce$offlineBounceDone) {
            this.windchargeownerreintroduce$offlineBounceDone = true;
            if (clientSide) {
                windchargeownerreintroduce$mirrorVanillaBounce(self, windCharge);
                ci.cancel();
            }
            return;
        }

        // 第二次命中：离线风弹不会被弹走，箭反弹后会落回同一命中区域；继续走原版
        // 反弹只会让箭在风弹上每 tick 反复命中、逐渐下沉并穿过活板门。
        // 这里改用和 AbstractArrow#tick 相同的扫掠线段检测方块，直接让箭插住。
        Vec3 from = self.position();
        Vec3 to = from.add(self.getDeltaMovement());
        BlockHitResult blockHit = self.level().clip(
                new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self));

        if (blockHit.getType() == HitResult.Type.BLOCK
                && from.distanceToSqr(blockHit.getLocation())
                        <= from.distanceToSqr(entityHitResult.getLocation())) {
            ((AbstractArrowInvoker) self).windchargeownerreintroduce$invokeOnHitBlock(blockHit);
            ci.cancel();

            WindChargeOwnerReintroduce.LOGGER.info(
                    "[WCO][{}] stuck arrow id={} into {} at {} after offline-owner wind charge hit (wc id={})",
                    clientSide ? "client" : "server",
                    self.getId(),
                    self.level().getBlockState(blockHit.getBlockPos()).getBlock(),
                    blockHit.getBlockPos(),
                    windCharge.getId());
            return;
        }

        // 这一 tick 的移动还够不到方块：客户端继续补齐反弹，让下一次命中再尝试插住。
        if (clientSide) {
            windchargeownerreintroduce$mirrorVanillaBounce(self, windCharge);
            ci.cancel();
        }
    }

    /**
     * 客户端补齐服务端 {@code AbstractArrow#onHitEntity} 已经做过的反弹：
     * <pre>
     *   deflect(REVERSE)  // 服务端：速度 * -0.5（随机 yaw 只是视觉，随后 tick 会按实际速度重算朝向）
     *   setDeltaMovement(getDeltaMovement().scale(0.2))
     * </pre>
     * 原版客户端因为 {@code Projectile#deflect} 里的 {@code if (!isClientSide)} 跳过了 REVERSE，
     * 只做了 * 0.2，于是箭会继续慢慢往下掉——这正是两边轨迹分叉、箭穿过活板门的原因。
     */
    private static void windchargeownerreintroduce$mirrorVanillaBounce(
            AbstractArrow self, AbstractWindCharge windCharge) {
        ProjectileDeflection.REVERSE.deflect(self, windCharge, self.getRandom());
        self.setDeltaMovement(self.getDeltaMovement().scale(0.2));

        WindChargeOwnerReintroduce.LOGGER.info(
                "[WCO][client] mirrored vanilla bounce for arrow id={} on wind charge id={} (owner offline/unresolvable)",
                self.getId(), windCharge.getId());
    }

    /**
     * 原版 AbstractArrow#tick 开头的“嵌在方块碰撞体里就 inGround=true”分支只改状态、
     * 不修正位置，也不更新 lastState。箭被激活的活板门短暂打开再关上后，位置会落进
     * 活板门的碰撞体内部，于是每开关一次就卡得更深一点，最终从方块底部掉出去。
     * <p>
     * 这里在 tick 最前面（原版检查之前）把“已经嵌进关闭状态活板门碰撞体”的箭顶回
     * 表面外 0.05 格——和 AbstractArrow#onHitBlock 留下的落点一致——并同步 inGround /
     * lastState / 速度，让它稳定停在活板门上面。客户端和服务端都执行，避免两边位置分叉。
     * <p>
     * 只处理关闭状态的活板门：打开时箭本来就该掉下去；活板门以外方块的行为保持原版。
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void windchargeownerreintroduce$liftArrowOutOfClosedTrapdoor(CallbackInfo ci) {
        AbstractArrow self = (AbstractArrow) (Object) this;
        if (self.isNoPhysics()) {
            return;
        }

        BlockPos blockPos = self.blockPosition();
        BlockState blockState = self.level().getBlockState(blockPos);
        if (!(blockState.getBlock() instanceof TrapDoorBlock)
                || blockState.getValue(TrapDoorBlock.OPEN)) {
            return;
        }

        VoxelShape shape = blockState.getCollisionShape(self.level(), blockPos);
        if (shape.isEmpty()) {
            return;
        }

        Vec3 pos = self.position();
        for (AABB localBox : shape.toAabbs()) {
            AABB worldBox = localBox.move(blockPos);
            if (!worldBox.contains(pos)) {
                continue;
            }

            // 往下掉的箭按顶面回收；向上射进上半天花板的箭按底面回收。
            double surfaceY = self.getDeltaMovement().y > 0.0
                    ? worldBox.minY - 0.05
                    : worldBox.maxY + 0.05;
            self.setPos(pos.x, surfaceY, pos.z);
            self.setDeltaMovement(Vec3.ZERO);
            ((AbstractArrowAccessor) self).windchargeownerreintroduce$setInGround(true);
            ((AbstractArrowAccessor) self).windchargeownerreintroduce$setLastState(blockState);

            WindChargeOwnerReintroduce.LOGGER.info(
                    "[WCO][{}] lifted arrow id={} out of closed trapdoor at {} (y {} -> {})",
                    self.level().isClientSide ? "client" : "server",
                    self.getId(),
                    blockPos,
                    pos.y,
                    surfaceY);
            return;
        }
    }

    /**
     * 判定“这根箭的主人已经无法解析”（离线/换维度/离开追踪范围）。
     * <p>
     * 客户端：风弹身上被转移的 owner UUID 只在服务端存在，不会同步给客户端；但触发这次
     * 命中的箭的主人就是同一个玩家——客户端生成箭时会按生成包里的 owner 实体 id 缓存
     * 这个玩家（Projectile#recreateFromPacket -> setOwner）。主人实体被移除后缓存失效，
     * getOwner() 返回 null，而 ownerUUID 仍然保留，此时就按“主人已不可解析”处理。
     * <p>
     * 服务端：此时 ProjectileMixin 已在 Projectile.onHit 的 HEAD 把 owner UUID 转移到
     * 风弹上；有 owner UUID 但解析不到实体，就是离线/不在当前维度。
     */
    private static boolean windchargeownerreintroduce$ownerUnresolvable(
            AbstractArrow self, AbstractWindCharge windCharge) {
        if (self.level().isClientSide) {
            UUID arrowOwner = ((ProjectileAccessor) self).getOwnerUUID();
            return arrowOwner != null && self.getOwner() == null;
        }

        UUID ownerUuid = ((ProjectileAccessor) windCharge).getOwnerUUID();
        return ownerUuid != null && windCharge.getOwner() == null;
    }
}
