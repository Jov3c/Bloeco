# Bloeco 文档索引

Bloeco 的文档分为“对外接入合同”和“内部实现设计”两类。开发商店、领地、任务或证券插件时，只需要遵守对外合同；实现插件本身或修改 Bloeco 资金流时，再阅读内部设计。

## 对外必读

| 文档 | 适用对象 | 说明 |
| --- | --- | --- |
| [第三方经济接入规范](third-party-economy-integration.md) | Bloeco 系列插件作者 | API 发现、机构注册、收付款、税费、退款、预授权、错误处理和幂等恢复。 |
| [示例 Consumer](../examples/bloeco-api-consumer) | 接入开发者 | 一个不复制、不 shade API 类的最小 Paper 插件接入示例。 |
| [Agent 指南](../AGENTS.md) | 代码 Agent 和维护者 | 源码布局、资金不变量、线程规则、测试和提交边界。 |

## 架构设计

| 文档 | 主题 |
| --- | --- |
| [中央银行设计](architecture/central-bank-design.md) | 货币供给、账户分级、税费、发行/回收和反通胀边界。 |
| [银行基础架构](architecture/banking-foundation-design.md) | 存款、贷款、利息、准备金、信用等级和银行 GUI。 |
| [Native API 设计](architecture/native-api-design.md) | 类似 Vault 的稳定接口为什么内嵌在 Bloeco 主插件，以及 Provider/Consumer 责任。 |
| [MySQL/Redis 存储规范](architecture/storage-v2-mysql-redis.md) | MySQL 事务权威、SQLite 开发模式、Redis 降级与 Outbox 事件。 |
| [Native API 实施记录](architecture/native-api-implementation-plan.md) | API 1.0 的实现范围、验收关注点和代码映射；不是运行时兼容合同。 |

## 阅读顺序

1. 先看仓库根目录的 [README](../README.md)，了解产品边界和部署方式。
2. 开发外部插件时只以[第三方接入规范](third-party-economy-integration.md)为准。
3. 修改资金模型、数据库或线程边界前，阅读[中央银行设计](architecture/central-bank-design.md)、[存储规范](architecture/storage-v2-mysql-redis.md)和 [AGENTS.md](../AGENTS.md)。

## 文档维护规则

- API 方法、错误码和结果语义以 `src/main/java/com/blocke/bloeco/api` 为源代码事实；改动 API 时必须同步更新接入规范、示例和兼容性测试。
- 数据库表、迁移编号和 Redis 主题属于内部实现，不得写进第三方插件的兼容逻辑。
- 文档中的金额统一使用最小货币单位示例（`10000` 表示 `100.00`），不使用浮点数示例。
- 任何新增业务都必须说明资金来源、税费归属、幂等键、失败恢复和是否改变货币总供给。
