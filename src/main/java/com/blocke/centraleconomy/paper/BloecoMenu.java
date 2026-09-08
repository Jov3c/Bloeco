package com.blocke.centraleconomy.paper;

import com.blocke.centraleconomy.economy.EconomyService;
import com.blocke.centraleconomy.economy.TaxPolicy;
import com.blocke.centraleconomy.economy.TaxType;
import com.blocke.centraleconomy.money.Money;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;

/** GUI-first monetary controls; merchandise and store state deliberately live outside Bloeco. */
public final class BloecoMenu implements Listener {
    private static final int SIZE = 27;
    private static final List<Money> AMOUNTS = List.of(
            Money.ofCents(10_000), Money.ofCents(100_000), Money.ofCents(1_000_000));

    private final EconomyService economy;
    private final TaxPolicy taxPolicy;
    private final TaxAdministratorAccess taxAccess;
    private final BiConsumer<TaxType, Integer> persistTaxRate;

    public BloecoMenu(Plugin plugin, EconomyService economy, TaxPolicy taxPolicy,
                      TaxAdministratorAccess taxAccess,
                      BiConsumer<TaxType, Integer> persistTaxRate) {
        Objects.requireNonNull(plugin, "plugin");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.taxPolicy = Objects.requireNonNull(taxPolicy, "taxPolicy");
        this.taxAccess = Objects.requireNonNull(taxAccess, "taxAccess");
        this.persistTaxRate = Objects.requireNonNull(persistTaxRate, "persistTaxRate");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player player) {
        HubHolder holder = new HubHolder(this);
        Inventory inventory = inventory(holder, "Bloeco 经济中心");
        inventory.setItem(4, item(Material.GOLD_INGOT, "我的余额", List.of(
                "余额：" + format(economy.playerBalance(player.getUniqueId())))));
        inventory.setItem(14, item(Material.EMERALD, "转账", List.of(
                "选择在线玩家和金额", "手续费和个人所得税自动结算")));
        if (taxAccess.allows(player)) {
            inventory.setItem(16, item(Material.NETHER_STAR, "税务与国库管理", List.of("税务管理员专用面板")));
        }
        player.openInventory(inventory);
    }

    private void openRecipients(Player sender) {
        RecipientHolder holder = new RecipientHolder(this);
        Inventory inventory = inventory(holder, "Bloeco 转账 - 选择玩家");
        int slot = 0;
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (candidate.equals(sender) || slot >= SIZE) continue;
            holder.put(slot, candidate.getUniqueId());
            inventory.setItem(slot++, item(Material.PLAYER_HEAD, candidate.getName(), List.of("点击选择收款人")));
        }
        if (slot == 0) sender.sendMessage("当前没有其他在线玩家可以收款。");
        else sender.openInventory(inventory);
    }

    private void openPayment(Player sender, Player recipient) {
        PaymentHolder holder = new PaymentHolder(this, recipient.getUniqueId());
        Inventory inventory = inventory(holder, "Bloeco 转账 - " + recipient.getName());
        for (int index = 0; index < AMOUNTS.size(); index++) {
            Money amount = AMOUNTS.get(index);
            int slot = 10 + index * 2;
            holder.put(slot, amount);
            inventory.setItem(slot, item(Material.EMERALD, "支付 " + format(amount), List.of(
                    "收款人：" + recipient.getName(), "点击确认转账", "手续费和所得税将自动扣除")));
        }
        inventory.setItem(22, item(Material.ARROW, "返回", List.of("返回收款人列表")));
        sender.openInventory(inventory);
    }

    private void openAdministration(Player player) {
        AdminHolder holder = new AdminHolder(this);
        Inventory inventory = inventory(holder, "Bloeco 税务与国库");
        inventory.setItem(4, item(Material.GOLD_BLOCK, "国库余额", List.of(format(economy.treasuryBalance()))));
        inventory.setItem(10, item(Material.BOOK, "税率设置", List.of("左键 +1%，右键 -1%", "Shift 点击每次 5%")));
        inventory.setItem(12, item(Material.EMERALD_BLOCK, "发行货币", List.of("左键 100，右键 1,000，Shift 左键 10,000")));
        inventory.setItem(14, item(Material.COAL_BLOCK, "回收货币", List.of("左键 100，右键 1,000，Shift 左键 10,000")));
        player.openInventory(inventory);
    }

    private void openTaxes(Player player) {
        TaxHolder holder = new TaxHolder(this);
        Inventory inventory = inventory(holder, "Bloeco 税率设置");
        int slot = 11;
        for (TaxType type : TaxType.values()) {
            holder.put(slot, type);
            inventory.setItem(slot, item(Material.PAPER, type.displayName(), List.of(
                    "当前：" + taxPolicy.rate(type) + "%", "左键 +1%，右键 -1%", "Shift 点击每次 5%")));
            slot += 4;
        }
        inventory.setItem(22, item(Material.ARROW, "返回", List.of("返回税务与国库面板")));
        player.openInventory(inventory);
    }

    private void openAdminConfirmation(Player player, AdministrationAction action, Money amount) {
        AdminConfirmHolder holder = new AdminConfirmHolder(this, action, amount);
        Inventory inventory = inventory(holder, "Bloeco 确认管理操作");
        inventory.setItem(11, item(Material.LIME_WOOL, "确认：" + action.displayName, List.of(
                "金额：" + format(amount), "此操作将写入不可变账本")));
        inventory.setItem(15, item(Material.BARRIER, "取消", List.of("不执行任何资金操作")));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder) || holder.menu != this) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= SIZE) return;
        try {
            if (holder instanceof HubHolder) {
                if (event.getRawSlot() == 14) openRecipients(player);
                else if (event.getRawSlot() == 16 && taxAccess.allows(player)) openAdministration(player);
            } else if (holder instanceof RecipientHolder recipients) {
                Player recipient = recipients.recipientAt(event.getRawSlot()) == null ? null
                        : Bukkit.getPlayer(recipients.recipientAt(event.getRawSlot()));
                if (recipient == null) player.sendMessage("该收款人已离线，请重新选择。");
                else openPayment(player, recipient);
            } else if (holder instanceof PaymentHolder payment) {
                if (event.getRawSlot() == 22) openRecipients(player);
                else settlePayment(player, payment, event.getRawSlot());
            } else if (holder instanceof AdminHolder && taxAccess.allows(player)) {
                switch (event.getRawSlot()) {
                    case 10 -> openTaxes(player);
                    case 12 -> openAdminConfirmation(player, AdministrationAction.ISSUE, amountFor(event.getClick()));
                    case 14 -> openAdminConfirmation(player, AdministrationAction.BURN, amountFor(event.getClick()));
                    default -> { }
                }
            } else if (holder instanceof AdminConfirmHolder confirmation && taxAccess.allows(player)) {
                if (event.getRawSlot() == 11) performAdministration(player, confirmation);
                else if (event.getRawSlot() == 15) openAdministration(player);
            } else if (holder instanceof TaxHolder taxes && taxAccess.allows(player)) {
                if (event.getRawSlot() == 22) openAdministration(player);
                else if (taxes.typeAt(event.getRawSlot()) != null) changeTax(player, taxes.typeAt(event.getRawSlot()), event.getClick());
            }
        } catch (RuntimeException exception) {
            player.sendMessage("Bloeco 操作被拒绝：" + exception.getMessage());
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder holder && holder.menu == this) event.setCancelled(true);
    }

    private void settlePayment(Player sender, PaymentHolder payment, int slot) {
        Money amount = payment.amountAt(slot);
        Player recipient = Bukkit.getPlayer(payment.recipientId);
        if (amount == null || recipient == null) throw new IllegalStateException("该收款人已离线，转账未执行");
        var result = economy.transferPlayerFunds(sender.getUniqueId(), recipient.getUniqueId(), amount,
                "Bloeco GUI payment " + sender.getName() + " -> " + recipient.getName());
        sender.sendMessage("已向 " + recipient.getName() + " 支付 " + format(result.amount()) + "；手续费 "
                + format(result.transferFee()) + "，所得税 " + format(result.incomeTax()) + "，共扣除 " + format(result.senderDebit()) + "。");
        recipient.sendMessage("收到来自 " + sender.getName() + " 的 " + format(result.recipientNet()) + "（已扣所得税 " + format(result.incomeTax()) + "）。");
        open(sender);
    }

    private void performAdministration(Player player, AdminConfirmHolder confirmation) {
        switch (confirmation.action) {
            case ISSUE -> economy.issueToTreasury(confirmation.amount, "GUI currency issue by " + player.getName());
            case BURN -> economy.burnFromTreasury(confirmation.amount, "GUI currency burn by " + player.getName());
        }
        player.sendMessage(confirmation.action.displayName + "已执行：" + format(confirmation.amount));
        openAdministration(player);
    }

    private void changeTax(Player player, TaxType type, ClickType click) {
        int adjustment = click.isShiftClick() ? 5 : 1;
        if (click.isRightClick()) adjustment = -adjustment;
        int next = Math.max(0, Math.min(100, taxPolicy.rate(type) + adjustment));
        taxPolicy.setRate(type, next);
        persistTaxRate.accept(type, next);
        player.sendMessage(type.displayName() + " 已调整为 " + next + "% 并立即生效。");
        openTaxes(player);
    }

    private static Money amountFor(ClickType click) {
        if (click.isShiftClick()) return AMOUNTS.get(2);
        return click.isRightClick() ? AMOUNTS.get(1) : AMOUNTS.get(0);
    }
    private static Inventory inventory(Holder holder, String title) {
        Inventory inventory = Bukkit.createInventory(holder, SIZE, title); holder.setInventory(inventory); return inventory;
    }
    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material); ItemMeta meta = stack.getItemMeta(); meta.setDisplayName(name); meta.setLore(lore); stack.setItemMeta(meta); return stack;
    }
    private static String format(Money money) { return String.format(Locale.ROOT, "%.2f", money.cents() / 100.0d); }

    private abstract static class Holder implements InventoryHolder {
        private final BloecoMenu menu; private Inventory inventory;
        private Holder(BloecoMenu menu) { this.menu = menu; }
        private void setInventory(Inventory inventory) { this.inventory = inventory; }
        @Override public Inventory getInventory() { return inventory; }
    }
    private static final class HubHolder extends Holder { private HubHolder(BloecoMenu menu) { super(menu); } }
    private static final class AdminHolder extends Holder { private AdminHolder(BloecoMenu menu) { super(menu); } }
    private static final class RecipientHolder extends Holder {
        private final Map<Integer, UUID> recipients = new LinkedHashMap<>(); private RecipientHolder(BloecoMenu menu) { super(menu); }
        private void put(int slot, UUID id) { recipients.put(slot, id); } private UUID recipientAt(int slot) { return recipients.get(slot); }
    }
    private static final class PaymentHolder extends Holder {
        private final UUID recipientId; private final Map<Integer, Money> amounts = new LinkedHashMap<>();
        private PaymentHolder(BloecoMenu menu, UUID recipientId) { super(menu); this.recipientId = recipientId; }
        private void put(int slot, Money amount) { amounts.put(slot, amount); } private Money amountAt(int slot) { return amounts.get(slot); }
    }
    private static final class TaxHolder extends Holder {
        private final Map<Integer, TaxType> types = new LinkedHashMap<>(); private TaxHolder(BloecoMenu menu) { super(menu); }
        private void put(int slot, TaxType type) { types.put(slot, type); } private TaxType typeAt(int slot) { return types.get(slot); }
    }
    private static final class AdminConfirmHolder extends Holder {
        private final AdministrationAction action; private final Money amount;
        private AdminConfirmHolder(BloecoMenu menu, AdministrationAction action, Money amount) { super(menu); this.action = action; this.amount = amount; }
    }
    private enum AdministrationAction {
        ISSUE("发行货币"), BURN("回收货币");
        private final String displayName; AdministrationAction(String displayName) { this.displayName = displayName; }
    }
}
