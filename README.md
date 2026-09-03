# CentralEconomy

CentralEconomy is a Paper 1.21.11 economy plugin backed by one SQLite ledger.

## Vault

Vault is optional. When its plugin is enabled, CentralEconomy registers an `Economy` provider at highest priority. Player balances remain global (world parameters are ignored), deposits create `EXTERNAL_CREDIT` ledger entries, and withdrawals create `EXTERNAL_DEBIT` entries. Bank accounts are not supported.

## 与 Blockeco / BlockStock 联动

BlockStock 已通过 Vault 的 `Economy` 服务进行公司创建、IPO、股票交易、托管与分红结算；它不应直接读取本插件的 SQLite 文件。将两个插件安装到同一 Paper 1.21.11 服务器后，CentralEconomy 会在 `BlockStock` 前加载并以最高优先级注册 Vault 经济服务，因此 BlockStock 的余额操作都会进入 CentralEconomy 的唯一账本。

安装顺序：先安装 Vault，再安装 CentralEconomy，最后安装 BlockStock。不要同时启用 EssentialsX Economy 等另一货币提供者。首次启动后，执行 `/economy treasury issue <金额> <备注>` 注入发行准备金；BlockStock 的公司创建、认购和托管账户即可使用统一货币。

`loadbefore: [BlockStock]` 只保证加载顺序，不耦合两个项目的数据库；BlockStock 继续使用 Vault API，两个插件可以独立升级。

## Build

Run `./gradlew shadowJar` with Java 21. The distributable JAR is written to `build/libs/CentralEconomy-<version>.jar`; the SQLite driver is bundled while Paper and Vault remain server-provided dependencies.
