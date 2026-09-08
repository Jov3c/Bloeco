package com.blocke.centraleconomy.paper;

import com.blocke.centraleconomy.application.AsyncEconomyFacade;
import com.blocke.centraleconomy.application.banking.AsyncBankingFacade;
import com.blocke.centraleconomy.application.result.Result;
import com.blocke.centraleconomy.application.JournalMemos;
import com.blocke.centraleconomy.application.command.PlayerPayment;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.centraleconomy.domain.banking.BankingPolicy;
import com.blocke.centraleconomy.domain.tax.TaxCategory;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.time.ZoneId;

/** GUI-first access to balances, player clearing, fiscal policy, and monetary controls. */
public final class BloecoMenu implements Listener {
    private static final int SIZE = 27;
    private static final List<Money> AMOUNTS = List.of(
            Money.ofMinor(10_000), Money.ofMinor(100_000), Money.ofMinor(1_000_000));

    private final Plugin plugin;
    private final AsyncEconomyFacade economy;
    private final AsyncBankingFacade banking;
    private final RoleAccess roles;

    public BloecoMenu(Plugin plugin, AsyncEconomyFacade economy, RoleAccess roles) {
        this(plugin, economy, null, roles);
    }

    public BloecoMenu(Plugin plugin, AsyncEconomyFacade economy,
                      AsyncBankingFacade banking, RoleAccess roles) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.banking = banking;
        this.roles = Objects.requireNonNull(roles, "roles");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player player) {
        HubHolder holder = new HubHolder(this);
        Inventory inventory = inventory(holder, "Bloeco 经济中心");
        inventory.setItem(4, item(Material.GOLD_INGOT, "我的余额", List.of("正在读取中央账本…")));
        if (banking != null) inventory.setItem(12, item(Material.IRON_DOOR, "国有银行",
                List.of("存款、取款、贷款与还款")));
        inventory.setItem(14, item(Material.EMERALD, "玩家转账", List.of("本金、手续费与所得税统一清算")));
        if (roles.anyAdministration(player)) {
            inventory.setItem(16, item(Material.NETHER_STAR, "中央银行管理", List.of("按权限显示可用功能")));
        }
        inventory.setItem(22, item(Material.BARRIER, "关闭", List.of("关闭 Bloeco 菜单")));
        player.openInventory(inventory);
        economy.playerBalance(player.getUniqueId()).thenAccept(result -> runMain(() -> {
            if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof HubHolder current)
                    || !current.belongsTo(this)) return;
            String line = result.isSuccess() ? "余额：" + MessageFormatter.moneyMinor(result.value())
                    : MessageFormatter.error(result);
            player.getOpenInventory().getTopInventory().setItem(4,
                    item(Material.GOLD_INGOT, "我的余额", List.of(line)));
        }));
    }

    private void openRecipients(Player sender) {
        RecipientHolder holder = new RecipientHolder(this);
        Inventory inventory = inventory(holder, "Bloeco 转账 - 选择玩家");
        int slot = 0;
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (candidate.equals(sender) || slot >= 21) continue;
            holder.recipients.put(slot, candidate.getUniqueId());
            inventory.setItem(slot++, item(Material.PLAYER_HEAD, candidate.getName(), List.of("点击选择收款人")));
        }
        if (slot == 0) inventory.setItem(13, item(Material.PAPER, "暂无收款人", List.of("当前没有其他在线玩家")));
        addNavigation(inventory);
        sender.openInventory(inventory);
    }

    private void openPayment(Player sender, Player recipient) {
        PaymentHolder holder = new PaymentHolder(this, recipient.getUniqueId());
        Inventory inventory = inventory(holder, "Bloeco 转账 - " + recipient.getName());
        for (int index = 0; index < AMOUNTS.size(); index++) {
            int slot = 10 + index * 2;
            Money amount = AMOUNTS.get(index);
            holder.amounts.put(slot, amount);
            inventory.setItem(slot, item(Material.EMERALD, "支付 " + MessageFormatter.money(amount),
                    List.of("收款人：" + recipient.getName(), "点击提交中央清算")));
        }
        addNavigation(inventory);
        sender.openInventory(inventory);
    }

    private void openAdministration(Player player) {
        AdminHolder holder = new AdminHolder(this);
        Inventory inventory = inventory(holder, "Bloeco 中央银行");
        if (roles.allows(player, RoleAccess.TAX)) {
            inventory.setItem(10, item(Material.BOOK, "税收与手续费", List.of("版本化财政规则")));
        }
        if (roles.allows(player, RoleAccess.MONETARY)) {
            inventory.setItem(12, item(Material.EMERALD_BLOCK, "申请发行", List.of("仅创建申请，不会立即增发")));
            inventory.setItem(14, item(Material.COAL_BLOCK, "回收货币", List.of("从国库永久退出流通")));
        }
        if (roles.allows(player, RoleAccess.AUDITOR) || roles.allows(player, RoleAccess.OPERATOR)) {
            inventory.setItem(16, item(Material.COMPARATOR, "校验总账", List.of("重算分录与账户余额")));
            inventory.setItem(18, item(Material.MAP, "经济总览", List.of("货币供给、流通、国库与税费")));
        }
        if (banking != null && (roles.allows(player, RoleAccess.BANKER)
                || roles.allows(player, RoleAccess.OPERATOR))) {
            inventory.setItem(20, item(Material.IRON_BLOCK, "银行管理", List.of("利率、准备金与放贷开关")));
        }
        addNavigation(inventory);
        player.openInventory(inventory);
    }

    private void openTaxes(Player player) {
        TaxHolder holder = new TaxHolder(this);
        Inventory inventory = inventory(holder, "Bloeco 财政规则");
        holder.categories.put(11, TaxCategory.PLAYER_TRANSFER_FEE);
        holder.categories.put(15, TaxCategory.PLAYER_TRANSFER_INCOME);
        inventory.setItem(11, item(Material.PAPER, "转账手续费", List.of("正在读取…", "左键 +1%，右键 -1%")));
        inventory.setItem(15, item(Material.PAPER, "个人所得税", List.of("正在读取…", "左键 +1%，右键 -1%")));
        addNavigation(inventory);
        player.openInventory(inventory);
        refreshTaxSlot(player, holder, 11, TaxCategory.PLAYER_TRANSFER_FEE, "转账手续费");
        refreshTaxSlot(player, holder, 15, TaxCategory.PLAYER_TRANSFER_INCOME, "个人所得税");
    }

    private void refreshTaxSlot(Player player, TaxHolder holder, int slot, TaxCategory category, String title) {
        economy.currentTaxRule(category).thenAccept(result -> runMain(() -> {
            if (player.getOpenInventory().getTopInventory().getHolder() != holder) return;
            if (result.isSuccess()) {
                holder.rates.put(category, result.value().basisPoints());
                player.getOpenInventory().getTopInventory().setItem(slot, item(Material.PAPER, title,
                        List.of("当前：" + basisPoints(result.value().basisPoints()), "左键 +1%，右键 -1%")));
            }
        }));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder) || holder.menu != this) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= SIZE) return;
        int slot = event.getRawSlot();
        if (handleNavigation(player, holder, slot)) return;
        if (holder instanceof HubHolder) {
            if (slot == 4) openJournal(player);
            else if (slot == 12 && banking != null) openBank(player);
            else if (slot == 14) openRecipients(player);
            else if (slot == 16 && roles.anyAdministration(player)) openAdministration(player);
        } else if (holder instanceof RecipientHolder recipients) {
            UUID recipientId = recipients.recipients.get(slot);
            Player recipient = recipientId == null ? null : Bukkit.getPlayer(recipientId);
            if (recipient == null) player.sendMessage("该收款人已离线，请重新选择。");
            else openPayment(player, recipient);
        } else if (holder instanceof PaymentHolder payment) {
            settlePayment(player, payment, slot);
        } else if (holder instanceof AdminHolder) {
            if (slot == 10 && roles.allows(player, RoleAccess.TAX)) openTaxes(player);
            else if (slot == 12 && roles.allows(player, RoleAccess.MONETARY))
                openAmountSelection(player, AdminAction.REQUEST_ISSUE);
            else if (slot == 14 && roles.allows(player, RoleAccess.MONETARY))
                openAmountSelection(player, AdminAction.RETIRE);
            else if (slot == 16 && (roles.allows(player, RoleAccess.AUDITOR)
                    || roles.allows(player, RoleAccess.OPERATOR))) verify(player);
            else if (slot == 18 && (roles.allows(player, RoleAccess.AUDITOR)
                    || roles.allows(player, RoleAccess.OPERATOR))) openOverview(player);
            else if (slot == 20 && banking != null && (roles.allows(player, RoleAccess.BANKER)
                    || roles.allows(player, RoleAccess.OPERATOR))) openBankAdministration(player);
        } else if (holder instanceof AmountHolder amounts && roles.allows(player, RoleAccess.MONETARY)) {
            Money amount = amounts.amounts.get(slot);
            if (amount != null) openConfirmation(player, amounts.action, amount);
        } else if (holder instanceof ConfirmHolder confirmation) {
            if (slot == 11 && roles.allows(player, RoleAccess.MONETARY)) performAdmin(player, confirmation);
            else if (slot == 15) openAdministration(player);
        } else if (holder instanceof TaxHolder taxes && roles.allows(player, RoleAccess.TAX)) {
            TaxCategory category = taxes.categories.get(slot);
            Integer current = category == null ? null : taxes.rates.get(category);
            if (current != null) changeTax(player, category, current, event.getClick());
        } else if (holder instanceof BankHolder bank) {
            if (slot == 10) openBankAmounts(player, BankAction.DEPOSIT, null, null);
            else if (slot == 12) openBankAmounts(player, BankAction.WITHDRAW, null, null);
            else if (slot == 14) openBankAmounts(player, BankAction.BORROW, null, null);
            else if (slot == 16 && bank.nextLoanId == null) player.sendMessage("当前没有需要偿还的贷款。");
            else if (slot == 16) openBankAmounts(player, BankAction.REPAY, bank.nextLoanId, bank.nextLoanDue);
        } else if (holder instanceof BankAmountHolder amounts) {
            Money amount = amounts.amounts.get(slot);
            if (amount != null) performBanking(player, amounts.action, amounts.loanId, amount);
        } else if (holder instanceof BankAdminHolder admin && admin.policy != null
                && (roles.allows(player, RoleAccess.BANKER) || roles.allows(player, RoleAccess.OPERATOR))) {
            changeBankPolicy(player, admin.policy, slot, event.getClick());
        }
    }

    private void openBank(Player player) {
        BankHolder holder = new BankHolder(this);
        Inventory inventory = inventory(holder, "Bloeco 国有银行");
        inventory.setItem(4, item(Material.CLOCK, "正在读取银行账户…", List.of()));
        inventory.setItem(10, item(Material.HOPPER, "存入银行", List.of("选择金额存入银行")));
        inventory.setItem(12, item(Material.DROPPER, "取出存款", List.of("选择金额返回钱包")));
        inventory.setItem(14, item(Material.GOLD_INGOT, "申请贷款", List.of("固定利息，不创造新货币")));
        inventory.setItem(16, item(Material.PAPER, "偿还贷款", List.of("优先偿还最早到期贷款")));
        addNavigation(inventory);
        player.openInventory(inventory);
        banking.playerSnapshot(player.getUniqueId()).thenAccept(result -> runMain(() -> {
            if (player.getOpenInventory().getTopInventory().getHolder() != holder) return;
            if (!result.isSuccess()) {
                inventory.setItem(4, item(Material.BARRIER, "银行不可用", List.of(MessageFormatter.error(result))));
                return;
            }
            var snapshot = result.value();
            holder.nextLoanId = snapshot.nextLoanId();
            holder.nextLoanDue = snapshot.nextLoanDue();
            inventory.setItem(4, item(Material.IRON_INGOT, "我的银行账户", List.of(
                    "钱包：" + MessageFormatter.money(snapshot.wallet()),
                    "存款：" + MessageFormatter.money(snapshot.deposit()),
                    "待还：" + MessageFormatter.money(snapshot.loanDebt()),
                    "信用等级：" + snapshot.creditGrade(),
                    snapshot.hasOverdueLoan() ? "状态：存在逾期" : "状态：正常")));
        }));
    }

    private void openBankAmounts(Player player, BankAction action, UUID loanId, Money exactDue) {
        BankAmountHolder holder = new BankAmountHolder(this, action, loanId);
        Inventory inventory = inventory(holder, "Bloeco 银行 - " + action.title);
        for (int index = 0; index < AMOUNTS.size(); index++) {
            int slot = 10 + index * 2;
            Money amount = AMOUNTS.get(index);
            holder.amounts.put(slot, amount);
            inventory.setItem(slot, item(action.material, action.title + " " + MessageFormatter.money(amount),
                    List.of("点击提交，所有资金进入中央总账")));
        }
        if (action == BankAction.REPAY && exactDue != null) {
            holder.amounts.put(16, exactDue);
            inventory.setItem(16, item(Material.NETHER_STAR, "全部偿还 " + MessageFormatter.money(exactDue),
                    List.of("精确结清最早到期贷款")));
        }
        addNavigation(inventory);
        player.openInventory(inventory);
    }

    private void performBanking(Player player, BankAction action, UUID loanId, Money amount) {
        String key = "bank-gui:" + UUID.randomUUID();
        java.util.concurrent.CompletionStage<? extends Result<?>> stage = switch (action) {
            case DEPOSIT -> banking.deposit(player.getUniqueId(), amount, key);
            case WITHDRAW -> banking.withdraw(player.getUniqueId(), amount, key);
            case BORROW -> banking.borrow(player.getUniqueId(), amount, key);
            case REPAY -> banking.repay(player.getUniqueId(), loanId, amount, key);
        };
        player.closeInventory();
        player.sendMessage("银行请求已提交，正在记入中央总账。");
        stage.thenAccept(result -> runMain(() -> {
            player.sendMessage(result.isSuccess() ? action.title + "完成。" : MessageFormatter.error(result));
            openBank(player);
        }));
    }

    private void openBankAdministration(Player player) {
        BankAdminHolder holder = new BankAdminHolder(this);
        Inventory inventory = inventory(holder, "Bloeco 银行管理");
        inventory.setItem(4, item(Material.CLOCK, "正在读取银行资产负债表…", List.of()));
        addNavigation(inventory);
        player.openInventory(inventory);
        banking.bankSnapshot().thenAccept(result -> runMain(() -> {
            if (player.getOpenInventory().getTopInventory().getHolder() != holder) return;
            if (!result.isSuccess()) {
                inventory.setItem(4, item(Material.BARRIER, "银行不可用", List.of(MessageFormatter.error(result))));
                return;
            }
            var snapshot = result.value();
            holder.policy = snapshot.policy();
            inventory.setItem(4, item(Material.IRON_BLOCK, "银行资产负债表", List.of(
                    "现金：" + MessageFormatter.money(snapshot.cash()),
                    "存款负债：" + MessageFormatter.money(snapshot.depositLiabilities()),
                    "贷款资产：" + MessageFormatter.money(snapshot.loanAssets()),
                    "贷款数：" + snapshot.activeLoans() + "，逾期：" + snapshot.overdueLoans())));
            inventory.setItem(10, policyItem("存款年利率", snapshot.policy().depositRateBasisPoints()));
            inventory.setItem(12, policyItem("贷款固定利率", snapshot.policy().loanRateBasisPoints()));
            inventory.setItem(14, policyItem("最低准备金率", snapshot.policy().reserveRatioBasisPoints()));
            inventory.setItem(16, item(snapshot.policy().lendingEnabled() ? Material.LIME_WOOL : Material.RED_WOOL,
                    snapshot.policy().lendingEnabled() ? "放贷：已开启" : "放贷：已暂停", List.of("点击切换")));
            inventory.setItem(18, item(Material.GOLD_BLOCK, "单人贷款上限", List.of(
                    MessageFormatter.money(snapshot.policy().maximumLoan()), "左键 +1000，右键 -1000")));
        }));
    }

    private static ItemStack policyItem(String name, int basisPoints) {
        return item(Material.COMPARATOR, name, List.of("当前：" + basisPoints(basisPoints),
                "左键 +1%，右键 -1%", "Shift 点击调整 5%"));
    }

    private void changeBankPolicy(Player player, BankingPolicy current, int slot, ClickType click) {
        int delta = click.isShiftClick() ? 500 : 100;
        if (click.isRightClick()) delta = -delta;
        BankingPolicy next;
        try {
            next = switch (slot) {
                case 10 -> new BankingPolicy(clampRate(current.depositRateBasisPoints() + delta),
                        current.loanRateBasisPoints(), current.reserveRatioBasisPoints(), current.maximumLoan(),
                        current.lendingEnabled(), current.loanTermDays());
                case 12 -> new BankingPolicy(current.depositRateBasisPoints(),
                        clampRate(current.loanRateBasisPoints() + delta), current.reserveRatioBasisPoints(),
                        current.maximumLoan(), current.lendingEnabled(), current.loanTermDays());
                case 14 -> new BankingPolicy(current.depositRateBasisPoints(), current.loanRateBasisPoints(),
                        clampRate(current.reserveRatioBasisPoints() + delta), current.maximumLoan(),
                        current.lendingEnabled(), current.loanTermDays());
                case 16 -> new BankingPolicy(current.depositRateBasisPoints(), current.loanRateBasisPoints(),
                        current.reserveRatioBasisPoints(), current.maximumLoan(), !current.lendingEnabled(),
                        current.loanTermDays());
                case 18 -> new BankingPolicy(current.depositRateBasisPoints(), current.loanRateBasisPoints(),
                        current.reserveRatioBasisPoints(), Money.ofMinor(Math.max(100,
                        current.maximumLoan().minor() + (click.isRightClick() ? -100_000 : 100_000))),
                        current.lendingEnabled(), current.loanTermDays());
                default -> null;
            };
        } catch (IllegalArgumentException exception) {
            player.sendMessage("该银行参数无效。");
            return;
        }
        if (next == null) return;
        banking.updatePolicy(next, "player:" + player.getUniqueId()).thenAccept(result -> runMain(() -> {
            player.sendMessage(result.isSuccess() ? "银行政策已更新。" : MessageFormatter.error(result));
            openBankAdministration(player);
        }));
    }

    private static int clampRate(int value) { return Math.max(0, Math.min(10_000, value)); }

    private void openJournal(Player player) {
        JournalHolder holder = new JournalHolder(this);
        Inventory inventory = inventory(holder, "Bloeco 我的账单");
        inventory.setItem(13, item(Material.CLOCK, "正在读取中央账本…", List.of()));
        addNavigation(inventory);
        player.openInventory(inventory);
        var playerAccount = com.blocke.centraleconomy.domain.account.AccountId.player(player.getUniqueId());
        economy.recentJournal(playerAccount, 21)
                .thenAccept(result -> runMain(() -> {
                    if (player.getOpenInventory().getTopInventory().getHolder() != holder) return;
                    inventory.clear();
                    addNavigation(inventory);
                    if (!result.isSuccess()) {
                        inventory.setItem(13, item(Material.BARRIER, "读取失败", List.of(MessageFormatter.error(result))));
                        return;
                    }
                    if (result.value().isEmpty()) {
                        inventory.setItem(13, item(Material.PAPER, "暂无账单", List.of("完成交易后会显示在这里")));
                        return;
                    }
                    int slot = 0;
                    for (var entry : result.value()) {
                        JournalDisplay.View display = JournalDisplay.forAccount(entry, playerAccount,
                                ZoneId.systemDefault());
                        inventory.setItem(slot++, item(Material.PAPER, display.title(), display.lore()));
                    }
                }));
    }

    private void openOverview(Player player) {
        OverviewHolder holder = new OverviewHolder(this);
        Inventory inventory = inventory(holder, "Bloeco 经济总览");
        inventory.setItem(13, item(Material.CLOCK, "正在汇总中央账本…", List.of()));
        addNavigation(inventory);
        player.openInventory(inventory);
        economy.snapshot().thenAccept(result -> runMain(() -> {
            if (player.getOpenInventory().getTopInventory().getHolder() != holder) return;
            inventory.clear();
            addNavigation(inventory);
            if (!result.isSuccess()) {
                inventory.setItem(13, item(Material.BARRIER, "汇总失败", List.of(MessageFormatter.error(result))));
                return;
            }
            var snapshot = result.value();
            inventory.setItem(10, item(Material.EMERALD_BLOCK, "货币供给", List.of(
                    "累计发行：" + MessageFormatter.moneyMinor(snapshot.monetaryTotals().issuedMinor()),
                    "累计回收：" + MessageFormatter.moneyMinor(snapshot.monetaryTotals().retiredMinor()),
                    "净供给：" + MessageFormatter.moneyMinor(snapshot.monetaryTotals().netSupplyMinor()))));
            inventory.setItem(12, item(Material.PLAYER_HEAD, "玩家流通量",
                    List.of(MessageFormatter.moneyMinor(snapshot.playerCirculationMinor()))));
            inventory.setItem(14, item(Material.CHEST, "国库",
                    List.of(MessageFormatter.moneyMinor(snapshot.treasuryMinor()))));
            inventory.setItem(16, item(Material.PAPER, "财政收入", List.of(
                    "税收：" + MessageFormatter.moneyMinor(snapshot.taxRevenueMinor()),
                    "手续费：" + MessageFormatter.moneyMinor(snapshot.feeRevenueMinor()))));
            inventory.setItem(20, item(economy.isReadOnly() ? Material.REDSTONE_BLOCK : Material.LIME_WOOL,
                    economy.isReadOnly() ? "只读保护中" : "账本可写", List.of("完整性状态")));
        }));
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder holder && holder.menu == this) {
            event.setCancelled(true);
        }
    }

    private void settlePayment(Player sender, PaymentHolder holder, int slot) {
        Money amount = holder.amounts.get(slot);
        Player recipient = Bukkit.getPlayer(holder.recipientId);
        if (amount == null || recipient == null) return;
        String key = "gui:" + UUID.randomUUID();
        economy.pay(new PlayerPayment(sender.getUniqueId(), recipient.getUniqueId(), amount,
                JournalMemos.playerPayment(sender.getName(), recipient.getName()), key))
                .thenAccept(result -> runMain(() -> {
                    if (!result.isSuccess()) sender.sendMessage(MessageFormatter.error(result));
                    else {
                        sender.sendMessage("转账完成；实际扣款 " + MessageFormatter.money(result.value().senderDebit()) + "。");
                        recipient.sendMessage("收到 " + MessageFormatter.money(result.value().recipientNet()) + "。");
                    }
                    open(sender);
                }));
        sender.closeInventory();
        sender.sendMessage("转账请求已提交，正在由 Bloeco 清算。");
    }

    private void openConfirmation(Player player, AdminAction action, Money amount) {
        ConfirmHolder holder = new ConfirmHolder(this, action, amount);
        Inventory inventory = inventory(holder, "Bloeco 确认央行操作");
        inventory.setItem(11, item(Material.LIME_WOOL, "确认：" + action.title,
                List.of("金额：" + MessageFormatter.money(amount), "操作将进入永久审计记录")));
        inventory.setItem(15, item(Material.BARRIER, "取消", List.of("不执行任何操作")));
        addNavigation(inventory);
        player.openInventory(inventory);
    }

    private void openAmountSelection(Player player, AdminAction action) {
        AmountHolder holder = new AmountHolder(this, action);
        Inventory inventory = inventory(holder, "Bloeco 选择" + action.amountTitle + "金额");
        for (int index = 0; index < AMOUNTS.size(); index++) {
            int slot = 10 + index * 2;
            Money amount = AMOUNTS.get(index);
            holder.amounts.put(slot, amount);
            inventory.setItem(slot, item(action.material, action.title + " "
                    + MessageFormatter.money(amount) + " 金币", List.of("点击进入确认页面")));
        }
        addNavigation(inventory);
        player.openInventory(inventory);
    }

    private void performAdmin(Player player, ConfirmHolder holder) {
        String actor = "player:" + player.getUniqueId();
        if (holder.action == AdminAction.REQUEST_ISSUE) {
            economy.requestIssuance(holder.amount, actor, JournalMemos.issuanceRequest(player.getName()))
                    .thenAccept(result -> runMain(() -> {
                        player.sendMessage(result.isSuccess()
                                ? "发行申请已创建：" + result.value() + "，需由另一名货币管理员审批。"
                                : MessageFormatter.error(result));
                        openAdministration(player);
                    }));
        } else {
            economy.retire(holder.amount, actor, JournalMemos.retirement(player.getName()),
                            "gui-retire:" + UUID.randomUUID())
                    .thenAccept(result -> runMain(() -> {
                        player.sendMessage(result.isSuccess() ? "货币回收已入账。" : MessageFormatter.error(result));
                        openAdministration(player);
                    }));
        }
        player.closeInventory();
    }

    private void changeTax(Player player, TaxCategory category, int current, ClickType click) {
        int delta = click.isShiftClick() ? 500 : 100;
        if (click.isRightClick()) delta = -delta;
        int next = Math.max(0, Math.min(10_000, current + delta));
        economy.changeTaxRule(category, next, 0, "player:" + player.getUniqueId(),
                        JournalMemos.fiscalRuleChange(player.getName()))
                .thenAccept(result -> runMain(() -> {
                    player.sendMessage(result.isSuccess()
                            ? "财政规则已更新为 " + basisPoints(next) + "。" : MessageFormatter.error(result));
                    openTaxes(player);
                }));
    }

    private void verify(Player player) {
        economy.verifyIntegrity().thenAccept(result -> runMain(() -> player.sendMessage(
                result.isSuccess() && result.value().valid() ? "中央总账校验通过。" : "中央总账校验失败。")));
    }

    private void runMain(Runnable action) {
        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, action);
    }

    private boolean handleNavigation(Player player, Holder holder, int slot) {
        if (holder instanceof HubHolder) {
            if (slot == 22) {
                player.closeInventory();
                return true;
            }
            return false;
        }
        if (slot == 22) {
            open(player);
            return true;
        }
        if (slot != 21) return false;
        if (holder instanceof PaymentHolder) openRecipients(player);
        else if (holder instanceof ConfirmHolder confirmation) {
            openAmountSelection(player, confirmation.action);
        } else if (holder instanceof BankAmountHolder) {
            openBank(player);
        } else if (holder instanceof BankHolder) {
            open(player);
        } else if (holder instanceof BankAdminHolder) {
            openAdministration(player);
        } else if (holder instanceof TaxHolder || holder instanceof AmountHolder || holder instanceof OverviewHolder) {
            openAdministration(player);
        } else {
            open(player);
        }
        return true;
    }

    private static void addNavigation(Inventory inventory) {
        inventory.setItem(21, item(Material.ARROW, "返回上一级", List.of("返回上一层菜单")));
        inventory.setItem(22, item(Material.COMPASS, "主菜单", List.of("返回 Bloeco 经济中心")));
    }

    private static String basisPoints(int value) {
        return java.math.BigDecimal.valueOf(value, 2).stripTrailingZeros().toPlainString() + "%";
    }

    private static Inventory inventory(Holder holder, String title) {
        Inventory inventory = Bukkit.createInventory(holder, SIZE, title);
        holder.inventory = inventory;
        return inventory;
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private abstract static class Holder implements InventoryHolder {
        private final BloecoMenu menu;
        private Inventory inventory;
        private Holder(BloecoMenu menu) { this.menu = menu; }
        final boolean belongsTo(BloecoMenu candidate) { return menu == candidate; }
        @Override public Inventory getInventory() { return inventory; }
    }
    private static final class HubHolder extends Holder { private HubHolder(BloecoMenu menu) { super(menu); } }
    private static final class AdminHolder extends Holder { private AdminHolder(BloecoMenu menu) { super(menu); } }
    private static final class JournalHolder extends Holder { private JournalHolder(BloecoMenu menu) { super(menu); } }
    private static final class OverviewHolder extends Holder { private OverviewHolder(BloecoMenu menu) { super(menu); } }
    private static final class BankHolder extends Holder {
        private UUID nextLoanId;
        private Money nextLoanDue;
        private BankHolder(BloecoMenu menu) { super(menu); }
    }
    private static final class BankAdminHolder extends Holder {
        private BankingPolicy policy;
        private BankAdminHolder(BloecoMenu menu) { super(menu); }
    }
    private static final class BankAmountHolder extends Holder {
        private final BankAction action;
        private final UUID loanId;
        private final Map<Integer, Money> amounts = new LinkedHashMap<>();
        private BankAmountHolder(BloecoMenu menu, BankAction action, UUID loanId) {
            super(menu); this.action = action; this.loanId = loanId;
        }
    }
    private static final class RecipientHolder extends Holder {
        private final Map<Integer, UUID> recipients = new LinkedHashMap<>();
        private RecipientHolder(BloecoMenu menu) { super(menu); }
    }
    private static final class PaymentHolder extends Holder {
        private final UUID recipientId;
        private final Map<Integer, Money> amounts = new LinkedHashMap<>();
        private PaymentHolder(BloecoMenu menu, UUID recipientId) { super(menu); this.recipientId = recipientId; }
    }
    private static final class TaxHolder extends Holder {
        private final Map<Integer, TaxCategory> categories = new LinkedHashMap<>();
        private final Map<TaxCategory, Integer> rates = new LinkedHashMap<>();
        private TaxHolder(BloecoMenu menu) { super(menu); }
    }
    private static final class AmountHolder extends Holder {
        private final AdminAction action;
        private final Map<Integer, Money> amounts = new LinkedHashMap<>();
        private AmountHolder(BloecoMenu menu, AdminAction action) {
            super(menu);
            this.action = action;
        }
    }
    private static final class ConfirmHolder extends Holder {
        private final AdminAction action;
        private final Money amount;
        private ConfirmHolder(BloecoMenu menu, AdminAction action, Money amount) {
            super(menu); this.action = action; this.amount = amount;
        }
    }
    private enum AdminAction {
        REQUEST_ISSUE("发行", "发行", Material.EMERALD_BLOCK),
        RETIRE("回收", "回收", Material.COAL_BLOCK);
        private final String title;
        private final String amountTitle;
        private final Material material;
        AdminAction(String title, String amountTitle, Material material) {
            this.title = title;
            this.amountTitle = amountTitle;
            this.material = material;
        }
    }
    private enum BankAction {
        DEPOSIT("存款", Material.HOPPER),
        WITHDRAW("取款", Material.DROPPER),
        BORROW("贷款", Material.GOLD_INGOT),
        REPAY("还款", Material.PAPER);
        private final String title;
        private final Material material;
        BankAction(String title, Material material) { this.title = title; this.material = material; }
    }
}
