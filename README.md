# CentralEconomy

CentralEconomy is a Paper 1.21.11 economy plugin backed by one SQLite ledger.

## Vault

Vault is optional. When its plugin is enabled, CentralEconomy registers an `Economy` provider at highest priority. Player balances remain global (world parameters are ignored), deposits create `EXTERNAL_CREDIT` ledger entries, and withdrawals create `EXTERNAL_DEBIT` entries. Bank accounts are not supported.

## Market

`/market` browses active player listings. Click a listing and confirm to buy one plain Material item; the menu checks inventory capacity before it settles the purchase and delivers the item only after settlement. `/market sell <price>` lists the plain stack in your main hand at the supplied per-item price, while `/market mine` shows your active listings and lets you cancel them to return the remaining items. The default 2% fee is configured in `market.yml` and is credited to the treasury.

## 与 Blockeco / BlockStock 联动

BlockStock 已通过 Vault 的 `Economy` 服务进行公司创建、IPO、股票交易、托管与分红结算；它不应直接读取本插件的 SQLite 文件。将两个插件安装到同一 Paper 1.21.11 服务器后，CentralEconomy 会在 `BlockStock` 前加载并以最高优先级注册 Vault 经济服务，因此 BlockStock 的余额操作都会进入 CentralEconomy 的唯一账本。

安装顺序：先安装 Vault，再安装 CentralEconomy，最后安装 BlockStock。不要同时启用 EssentialsX Economy 等另一货币提供者。首次启动后，管理员可用 `/economy treasury issue <金额> <备注>` 进行明确、可审计的货币发行。

BlockStock 蓝筹开市前，先以 `/economy reserve fund <金额> <备注>` 将**已经存在于中央国库**的资金划入受限蓝筹准备金（默认 UUID 末尾为 `.98`）。此操作只改变资金所在账户、不会增发；余额不足时 BlockStock 不会开市。准备金金额必须覆盖 BlockStock 的 `market.bluechip-reserve-total`（默认 `1,150,000.00`）。

`loadbefore: [BlockStock]` 只保证加载顺序，不耦合两个项目的数据库；BlockStock 继续使用 Vault API，两个插件可以独立升级。

## Build

Run `./gradlew shadowJar` with Java 21. The distributable JAR is written to `build/libs/CentralEconomy-<version>.jar`; the SQLite driver is bundled while Paper and Vault remain server-provided dependencies.
