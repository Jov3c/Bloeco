# Bloeco V2 存储与经济中心规范

状态：正式架构基线（2026-09-08）

这份规范是 Bloeco 经济中心及后续接入插件的共同存储契约。Bloeco 负责全服货币账本、余额、发行/回收、税费、手续费、幂等和审计；业务插件只拥有自己的业务数据，并通过 Bloeco API 提交经济结算。

## 1. 权威边界

- **MySQL 8.4（InnoDB）是唯一权威账本**。账户余额、分录、分录行、发行、税规则、手续费规则、幂等记录和审计记录都必须在 MySQL 事务中完成。
- **Redis 7 不是账本**。Redis 只保存可重建缓存、限流状态和事件流；Redis 丢失、清空或不可用时不得改变货币总量。
- Redis Stream 事件由 MySQL `outbox_events` 事务性记录后异步发布。MySQL 提交成功而 Redis 发布失败时，后台发布器重试；不会反向重放或凭空创建分录。
- MySQL 不可用时，Bloeco 进入只读/不可用保护，禁止发行、回收、转账、税费结算和余额写入；不自动回退 SQLite。

## 2. 数据库约束

- 字符集 `utf8mb4`，排序规则 `utf8mb4_0900_ai_ci`；所有时间写入 UTC，使用 `DATETIME(3)`。
- Java 金额使用分为单位的有符号 `BIGINT`，数据库约束为金额非负或按账户 `permits_negative` 规则校验。禁止使用浮点金额。
- 业务 UUID 使用标准 `CHAR(36)`；账户标识保留带命名空间的 canonical 字符串（例如 `player:<uuid>:wallet`），以兼容账户层级。外部 idempotency key 必须有明确的 `scope_key`（例如 `bloeco.system`、`bloeco-stock`），不得依赖 MySQL 对 nullable UNIQUE 的特殊行为。
- 所有余额变更必须同时写入不可变 `journal_entries`/`postings` 和物化 `account_balances`；启动和定时任务重算两者并进入只读保护。
- 结算快照（`settlement_items`）是业务请求的不可变快照；最终货币事实只认 `postings`，两者必须可通过 `settlement_id` 对账。

## 3. 核心表分层

### 3.1 货币真相层

`institutions`、`accounts`、`account_balances`、`journal_entries`、`postings`、`fund_reservations`、`idempotency_records`、`outbox_events`。

### 3.2 经济政策层

`tax_rules`、`policy_limits`、`issuance_requests`、`audit_events`。

### 3.3 结算与对账层

`settlements`、`settlement_items`、`settlement_batches`。`settlements` 记录一次业务结算，`settlement_items` 保存扣款/收款/税/手续费的请求快照，`postings` 保存实际货币变动。

所有跨表引用均设置外键，尤其是：机构、结算、结算项、目标税收账户、发行请求关联分录、批次关联机构。删除采用停用或归档，不级联删除账本记录。

## 4. 事务与并发规则

1. 读取余额时只用于展示；写入前在同一 MySQL 事务内按 `account_id` 排序 `SELECT ... FOR UPDATE`。
2. 校验账户状态、负余额策略、税费和手续费规则后，写入 journal、postings、account_balances、settlement 快照、audit 和 outbox。
3. 所有写入使用唯一幂等键；重试必须返回原结算结果，参数指纹不一致时返回冲突。
4. 发行必须经过申请→批准→执行；国库资金只能通过已批准的发行/回收流程变化。初始国库资金属于初始化配置，不是每次启动重新铸币。
5. Redis 事件消费端必须支持至少一次投递和幂等处理，业务插件不得把 Redis 消息当作余额确认。

## 5. Redis 使用规范

- Key 前缀默认 `bloeco:v2:`，余额缓存、快照缓存均设置 TTL，并带账本版本号。
- Stream 默认 `bloeco:v2:ledger-events`；事件载荷包含 `event_id`、`journal_id`、`scope_key`、`created_at` 和 schema 版本。
- 缓存失效优先于更新；任何缓存命中都必须能够从 MySQL 重新构建。
- Redis 连接失败只记录健康状态并继续保证 MySQL 账本；发布器恢复后从 `outbox_events` 的未发布位置继续。

## 6. 配置基线

生产默认：

```yaml
storage:
  type: mysql
  mysql:
    jdbc-url: jdbc:mysql://127.0.0.1:3306/bloeco?useSSL=false&serverTimezone=UTC
    username: bloeco
    password-env: BLOECO_MYSQL_PASSWORD
    maximum-pool-size: 16
redis:
  enabled: true
  uri: redis://127.0.0.1:6379/0
  key-prefix: bloeco:v2:
```

SQLite 仅用于开发、离线测试和迁移工具，必须显式设置 `storage.type: sqlite`。生产环境禁止把 SQLite 当作 MySQL 故障转移目标。

## 7. 插件接入契约

经济相关插件应声明自己的 `scope_key` 和 `institution_id`，通过 Bloeco 的异步 API 提交结算请求，提供稳定的 `idempotency_key`、业务类型、业务引用和中文 `display_memo`。插件不得直接修改 Bloeco 表、直接增发货币或把 Redis 当作余额数据库。
