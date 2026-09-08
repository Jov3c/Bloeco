package com.blocke.centraleconomy.paper;

import com.blocke.centraleconomy.application.AsyncEconomyFacade;
import com.blocke.centraleconomy.application.JournalMemos;
import com.blocke.centraleconomy.application.command.PlayerPayment;
import com.blocke.centraleconomy.domain.money.Money;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;
import java.util.List;
import java.util.Locale;

/** Exact, nonblocking player-to-player payment command. */
public final class PayCommand implements CommandExecutor, TabCompleter {
    private static final List<String> AMOUNT_SUGGESTIONS = List.of("1", "10", "100", "1000");
    private final Plugin plugin;
    private final AsyncEconomyFacade economy;

    public PayCommand(Plugin plugin, AsyncEconomyFacade economy) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.economy = Objects.requireNonNull(economy, "economy");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player payer)) {
            sender.sendMessage("只有玩家可以使用转账功能。");
            return true;
        }
        if (args.length != 2) {
            payer.sendMessage("用法：/pay <在线玩家> <金额>");
            return true;
        }
        Player recipient = Bukkit.getPlayerExact(args[0]);
        if (recipient == null || !recipient.isOnline()) {
            payer.sendMessage("收款玩家不在线。");
            return true;
        }
        if (recipient.getUniqueId().equals(payer.getUniqueId())) {
            payer.sendMessage("不能给自己转账。");
            return true;
        }
        final Money amount;
        try {
            if (!args[1].matches("[1-9][0-9]*")) throw new IllegalArgumentException("not a positive integer");
            amount = Money.ofMinor(Math.multiplyExact(Long.parseLong(args[1]), 100L));
        } catch (IllegalArgumentException | ArithmeticException exception) {
            payer.sendMessage("金额必须是大于零的整数。");
            return true;
        }
        String key = "command:" + UUID.randomUUID();
        PlayerPayment payment = new PlayerPayment(payer.getUniqueId(), recipient.getUniqueId(), amount,
                JournalMemos.playerPayment(payer.getName(), recipient.getName()), key);
        economy.pay(payment).thenAccept(result -> runMain(() -> {
            if (!result.isSuccess()) {
                payer.sendMessage(MessageFormatter.error(result));
                return;
            }
            var receipt = result.value();
            payer.sendMessage("已向 " + recipient.getName() + " 转账 "
                    + MessageFormatter.money(receipt.principal()) + "；手续费 "
                    + MessageFormatter.money(receipt.fee()) + "，所得税 "
                    + MessageFormatter.money(receipt.incomeTax()) + "。");
            recipient.sendMessage("收到来自 " + payer.getName() + " 的 "
                    + MessageFormatter.money(receipt.recipientNet()) + "。");
        }));
        payer.sendMessage("转账请求已提交，正在由 Bloeco 清算。");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .filter(player -> !(sender instanceof Player payer)
                            || !player.getUniqueId().equals(payer.getUniqueId()))
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
        if (args.length == 2) {
            return AMOUNT_SUGGESTIONS.stream().filter(value -> value.startsWith(args[1])).toList();
        }
        return List.of();
    }

    private void runMain(Runnable action) {
        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, action);
    }
}
