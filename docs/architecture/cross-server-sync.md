# Bloeco 跨服同步设计（1.6 已实现）

状态：Bloeco 1.6 目标实现基线

## 1. 目标与边界

跨服同步的目标是让多个 Paper 服务器共享同一套 Bloeco 经济事实，并让玩家感知到跨服付款、到账通知和余额刷新。它不是把账本复制到 Redis，也不是让每台服务器各自维护一份余额。

权威边界固定如下：

| 数据 | 权威来源 | Redis 作用 |
| --- | --- | --- |
| 钱包、税费、手续费、银行和账单 | 共享 MySQL 复式总账 | 不存余额事实 |
| 玩家 UUID 与最近名称 | MySQL `player_directory` | 加速在线定位 |
| 服务器节点租约 | Redis TTL + MySQL 注册记录 | 实时租约与发现 |
| 跨服通知、缓存失效 | MySQL Outbox → Redis Stream | 至少一次投递，消费端幂等 |

SQLite 仍是单服开发/导入模式；使用 SQLite 的服务器不参与跨服集群。所有跨服服务器必须使用同一个 MySQL 数据库、同一个 Redis 集群、同一个 `cluster-id`，并配置唯一的 `server-id`。

## 2. 事件拓扑

每个服务器向同一条 Stream 写入事件，但每个服务器使用独立消费组：

```text
服务器 A ─┐                         ┌─ group: cluster:A ─► A 本地缓存/玩家通知
服务器 B ─┼─► Redis Stream ◄─ Outbox ┼─ group: cluster:B ─► B 本地缓存/玩家通知
服务器 C ─┘                         └─ group: cluster:C ─► C 本地缓存/玩家通知
                    ▲
                    └── MySQL 事务提交后才进入 Outbox
```

独立消费组保证每个服务器都能收到同一条事件；同一服务器内使用稳定 `consumer-name`，重复事件按 `event_id` 去重后确认。Redis 发布失败时 Outbox 保留未发布状态，恢复后自动续发。

事件分为两类：

- `JOURNAL_COMMITTED`：资金事务提交后的通用账本事件，用于余额/账单读模型失效。
- `PLAYER_NOTIFICATION`：发送给目标玩家所在服务器的短消息，例如跨服转账到账；消息本身不包含可改变余额的指令。

## 3. 节点与玩家目录

每个 Paper 实例启动时注册 `server_id`，每 10 秒刷新 Redis 租约，TTL 默认 30 秒。停止或租约过期后，该节点不再被视为在线。Redis 节点信息只用于发现和路由，不参与资金结算。

玩家加入时写入 `player_directory(player_uuid, last_known_name, server_id, online, last_seen_epoch_ms)`；退出时标记离线。名称只用于命令补全和展示，UUID 才是付款目标的永久身份。跨服 `/pay` 先解析本服在线玩家，再异步查询 MySQL 玩家目录；Redis 在线目录用于节点存活与实时路由，不作为付款正确性的前置条件。解析不到就安全失败。

## 4. 跨服 `/pay` 流程

```text
/pay 玩家 金额
    │
    ├─ 解析目标 UUID（本服 → Redis → MySQL）
    ├─ 用 sender UUID + recipient UUID + 幂等键提交 MySQL 原子转账
    ├─ 结算本金、手续费、所得税并写 JOURNAL_COMMITTED
    └─ 若目标在其他服，发布 PLAYER_NOTIFICATION；目标服消费后在主线程提示玩家
```

付款成功不依赖目标服在线；目标离线时，账单仍然提交，玩家下次加入时由本服查询最近账单并显示未读通知。通知丢失不能撤销或重做已提交的资金事务。

## 5. 缓存与一致性

跨服事件消费只做两件事：删除本服 Redis 钱包/账单缓存，并把目标玩家的通知排入本服 Paper 主线程。余额读取在缓存未命中或缓存版本落后时回到 MySQL；Redis 不可用时仍可完成 MySQL 结算，只是跨服通知和缓存加速暂时降级。

事件消费必须保存最近处理过的 `event_id`（Redis 去重键带 TTL，MySQL 账单查询可作为恢复依据），处理成功后再 `XACK`。消费异常不确认消息，重启后继续处理；通知可以重复到达，但不能重复扣款。

## 6. 配置

```yaml
cluster:
  enabled: true
  cluster-id: "production"
  server-id: "survival-01"
  heartbeat-seconds: 10
  lease-seconds: 30
  consumer-group-prefix: "bloeco:v2:cluster:"
```

`cluster-id` 由整个网络共享；`server-id` 在网络内必须唯一且只能包含小写字母、数字、`.`、`_`、`-`。`cluster.enabled: false` 时插件仍可单服运行，但不会注册节点、消费跨服事件或接受跨服目标。

## 7. 失败语义与安全约束

- MySQL 结算成功、Redis 发布失败：资金保持成功，Outbox 重试，不能回滚或再次扣款。
- Redis 消费失败：不 `XACK`，重启后重放；处理器按 `event_id` 幂等。
- 节点租约过期：不再向该节点发送实时通知，但不影响离线账单和资金事实。
- 玩家名称冲突：拒绝模糊匹配，要求精确名称或 UUID；永远不靠名称直接入账。
- 同一 `/pay` 请求重试：复用原幂等键返回原凭证；更换金额或收款人返回冲突。
- 任何跨服事件都不能包含“增发、改余额、执行 SQL”的命令；事件只描述已经提交的事实或展示通知。

## 8. 验收标准

1. 两个 Paper 实例共享 MySQL 时，对同一钱包的并发付款不会双花，余额和账单一致。
2. 每个实例都能消费同一 `JOURNAL_COMMITTED`，重复投递不会重复提示或重复失效。
3. 跨服目标在线时收到一次到账提示；目标离线时资金仍结算，并在下次加入时可查询账单。
4. Redis 停机时 MySQL 付款仍可用；Redis 恢复后 Outbox 和通知自动追平。
5. 节点重启、租约过期、消费者重平衡和结果未知都不会产生第二笔资金分录。
6. 单服 SQLite 模式保持现有行为，并明确拒绝跨服配置。
