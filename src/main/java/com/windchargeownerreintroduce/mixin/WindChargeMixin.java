package com.windchargeownerreintroduce.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.windcharge.WindCharge;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(WindCharge.class)
public abstract class WindChargeMixin {

    @Inject(method = "explode", at = @At("HEAD"))
    private void onExplode(Vec3 vec3, CallbackInfo ci) {
        WindCharge self = (WindCharge) (Object) this;
        Level level = self.level();

        // 只在服务端执行，防止客户端和服务端重复处理
        if (level.isClientSide) {
            return;
        }

        // 【关键修改】直接从风弹自身的 NBT 读取 Owner，不调用 getOwner()
        CompoundTag selfNbt = new CompoundTag();
        self.saveWithoutId(selfNbt);

        // 如果 NBT 中没有 Owner 标签，或者格式不对，直接返回
        if (!selfNbt.contains("Owner") || !(selfNbt.get("Owner") instanceof IntArrayTag ownerTag)) {
            return;
        }

        // 爆炸影响范围，原代码中为 1.2
        double radius = 1.2F;
        List<Entity> affectedEntities = level.getEntitiesOfClass(
                Entity.class,
                self.getBoundingBox().inflate(radius),
                entity -> entity != self && !entity.isSpectator()
        );

        for (Entity entity : affectedEntities) {
            String typeName = entity.getType().toString();
            // 检查是否是 TNT 或其他风弹
            if (typeName.contains("tnt") || typeName.contains("wind_charge")) {
                CompoundTag targetNbt = new CompoundTag();
                entity.saveWithoutId(targetNbt);
                // 将读取到的 Owner 标签复制并写入目标实体
                targetNbt.put("Owner", ownerTag.copy());
                entity.load(targetNbt);
            }
        }
    }
}