package com.windchargeownerreintroduce.mixin;

import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Projectile 的 ownerUUID 是真正的持久化数据（箭的 NBT "Owner" 就是它），
 * 而 getOwner() 需要该实体在线才能解析出来。离线玩家只能通过 UUID 访问。
 */
@Mixin(Projectile.class)
public interface ProjectileAccessor {

    @Accessor("ownerUUID")
    @Nullable
    UUID getOwnerUUID();

    @Accessor("ownerUUID")
    void setOwnerUUID(@Nullable UUID ownerUUID);

    @Accessor("cachedOwner")
    void setCachedOwner(@Nullable Entity owner);
}
