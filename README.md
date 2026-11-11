# Wind Charge Owner Reintroduce

Minecraft **1.21.1** / Fabric 的轻量修复 mod。

**一句话：** 当投掷物的主人已经离线（或不在当前维度）时，风弹依然能正确继承并保留主人的 UUID；箭照常命中并反弹，不会被删除，也不会穿过活板门掉下去。

## 解决的问题

原版里箭等投掷物命中风弹时，风弹会"接管"投掷物主人的身份（owner UUID），后续爆炸、连锁风弹等逻辑都依赖这个 UUID。但主人一旦离线：

- `Projectile.deflect` 只在服务端执行，客户端什么都不做，两边轨迹分叉——再配上只有 3/16 格厚的活板门，箭就有概率直接穿过去掉下来；
- 风弹拿到的 owner 无法解析成实体，依赖主人的逻辑随之失效。

本 mod 在不改变"主人在线时"原版行为的前提下修复这些情况。

## 功能

| 场景 | 原版 | 装了本 mod 之后 |
| --- | --- | --- |
| 主人离线，箭命中风弹 | 服务端反弹、客户端继续下落，箭反复命中并慢慢穿过活板门 | 第一次命中像原版一样把箭弹开（客户端补齐服务端的反弹速度）；箭再次落回风弹时直接插进后面的方块（例如活板门），不会下沉、不会穿门，也不会被删除；风弹本身不受力 |
| 风弹的 owner UUID | 只在主人可解析时转移 | 始终从投掷物持久化的 `ownerUUID` 复制，离线玩家也生效 |
| 风弹爆炸 | 附近的 TNT / 风弹不带主人 | 半径 1.2 格内的 TNT 和风弹继承 `Owner` |
| 活板门短暂打开再关闭 | 箭被"嵌"进方块，每次开关下沉一点，最终从底部掉出去 | 箭被吸回活板门表面，位置不再累加下沉 |

## 工作原理

- **`ProjectileMixin`** — `Projectile.onHit` 命中 `AbstractWindCharge` 时，把投掷物持久化的 `ownerUUID` 复制给风弹。普通风弹（`WindCharge`）生成后的前 5 tick 不响应偏转，此时保持原版行为。主人无法解析（离线/不在当前维度）时，还会在 `onHit` 结束时把风弹速度恢复成命中前的值——原版 `AIM_DEFLECT` 此时本来就是空操作，这一层保证即使其它逻辑改动了速度，离线主人的箭也绝不会给风弹施加任何动量。
- **`AbstractArrowMixin#onHitEntity`** — 箭命中"主人无法解析"的风弹时分两阶段处理：
  - **第一次命中**：完全保持原版反弹手感；服务端本来就是原版行为，客户端补上漏掉的 `REVERSE` 反弹（速度 × -0.5 后再 × 0.2，净 × -0.1），让两边看到同一根弹开的箭。
  - **第二次及以后**：离线风弹不会像在线时那样被弹走，箭反弹后会落回同一命中区域、每 tick 反复命中并逐渐下沉。此时按和 `AbstractArrow#tick` 相同的扫掠线段重新做一次方块检测，直接让箭插进后面的方块（例如活板门）。
  - 箭全程**不会被删除**，风弹本身也不会获得任何动量。
- **`WindChargeMixin`** — 风弹爆炸时把 `Owner` 传递给附近的 TNT 和风弹。
- **`AbstractArrowMixin#tick`** — 对"嵌进关闭状态活板门碰撞体"的箭做位置修正：吸回表面外 0.05 格（与原版 `onHitBlock` 落点一致），同步 `inGround` / `lastState` / 速度。只处理活板门，其它方块完全按原版。

所有修复都在 common 的 mixin 配置里，**客户端、服务端、单人游戏都生效**。

## 环境与依赖

- Minecraft **1.21.1**
- Fabric Loader **≥ 0.16.14**
- Fabric API（必需）
- Java **21**

## 安装

把 `windchargeownerreintroduce-<version>.jar` 放进 `mods/` 目录。

多人游戏时**服务端和客户端都要安装**：服务端负责实际物理和 owner 转移，客户端参与箭插住位置的同步。

## 确认 mod 已加载

启动日志里会有一行：

```
WCO loaded - windchargeownerreintroduce 1.2.8 (server-side mixins active)
```

触发修复时还会输出：

```
[WCO] owner <uuid> not resolvable (offline/other dimension); copied owner UUID to ...
[WCO][server] kept wind charge id=... motion at ... after offline-owner hit
[WCO][client] mirrored vanilla bounce for arrow id=... on wind charge id=...
[WCO][server] stuck arrow id=... into ... after offline-owner wind charge hit (wc id=...)
[WCO][client] stuck arrow id=... into ... after offline-owner wind charge hit (wc id=...)
[WCO][server] lifted arrow id=... out of closed trapdoor at ...
[WCO][client] lifted arrow id=... out of closed trapdoor at ...
```

## 构建

需要 JDK 21：

```bash
./gradlew build
```

产物在 `build/libs/`（`-sources.jar` 为源码包）。

## 已知行为 / 限制

- 活板门持续打开较久（约 10 tick 以上）时箭会正常掉落——这是"门确实开了"的合理物理行为；本 mod 只修短暂激活造成的**下陷/嵌入**。
- 只修正关闭状态活板门的嵌入问题，打开状态和其它方块保持原版。
- 依赖 Fabric API，不支持 Forge / NeoForge。

## English Summary

A small Fabric mod for Minecraft 1.21.1 that preserves wind-charge ownership when the owner is offline. The owner UUID is copied from the hitting projectile (even if the owner entity cannot be resolved). The arrow's first hit still bounces like vanilla (the client mirrors the server-side bounce), and when the arrow falls back onto the stationary charge it is stuck into the block behind it instead of repeatedly hitting and sinking through the trapdoor; the arrow is never deleted, and the charge itself gains no momentum. TNT and chained wind charges inherit the owner, and vanilla arrows no longer sink into closed trapdoors after brief activations. Requires Fabric Loader 0.16.14+, Fabric API and Java 21; install on both client and server.

## License

CC0-1.0.
