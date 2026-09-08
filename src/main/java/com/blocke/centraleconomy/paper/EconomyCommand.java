package com.blocke.centraleconomy.paper;

import com.blocke.centraleconomy.application.AsyncEconomyFacade;
import com.blocke.centraleconomy.application.result.Result;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.centraleconomy.domain.tax.TaxCategory;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Console-capable recovery and administration boundary using the same central-bank services as the GUI. */
public final class EconomyCommand implements CommandExecutor {
    private final Plugin plugin;
    private final AsyncEconomyFacade economy;
    private final BloecoMenu menu;
    private final RoleAccess roles;

    public EconomyCommand(Plugin plugin, AsyncEconomyFacade economy, BloecoMenu menu, RoleAccess roles) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.menu = Objects.requireNonNull(menu, "menu");
        this.roles = Objects.requireNonNull(roles, "roles");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) menu.open(player);
            else usage(sender);
            return true;
        }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "health" -> health(sender);
                case "verify" -> verify(sender);
                case "balance" -> balance(sender, args);
                case "issue" -> issue(sender, args);
                case "retire" -> retire(sender, args);
                case "player" -> player(sender, args);
                case "tax" -> tax(sender, args);
                default -> usage(sender);
            }
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("参数错误：" + exception.getMessage());
        }
        return true;
    }

    private void health(CommandSender sender) {
        require(sender, RoleAccess.OPERATOR);
        economy.monetaryTotals().thenAccept(result -> dispatch(sender, result, totals ->
                "Bloeco 正常；货币供给 " + MessageFormatter.moneyMinor(totals.netSupplyMinor()) + "。"));
    }

    private void verify(CommandSender sender) {
        if (!roles.allows(sender, RoleAccess.OPERATOR) && !roles.allows(sender, RoleAccess.AUDITOR)) {
            throw new IllegalArgumentException("缺少总账校验权限");
        }
        economy.verifyIntegrity().thenAccept(result -> dispatch(sender, result,
                report -> report.valid() ? "中央总账校验通过。" : "中央总账存在不一致：" + report.violations()));
    }

    private void balance(CommandSender sender, String[] args) {
        require(sender, RoleAccess.AUDITOR);
        if (args.length != 2) throw new IllegalArgumentException("用法：/bloeco balance <在线玩家>");
        Player player = requireOnline(args[1]);
        economy.playerBalance(player.getUniqueId()).thenAccept(result -> dispatch(sender, result,
                value -> player.getName() + " 的余额为 " + MessageFormatter.moneyMinor(value) + "。"));
    }

    private void issue(CommandSender sender, String[] args) {
        require(sender, RoleAccess.MONETARY);
        if (args.length < 3) throw new IllegalArgumentException(
                "用法：/bloeco issue request <金额> <原因> | approve <申请UUID> | execute <申请UUID>");
        String actor = actor(sender);
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "request" -> {
                if (args.length < 4) throw new IllegalArgumentException("发行申请必须填写原因");
                Money amount = positiveMoney(args[2]);
                economy.requestIssuance(amount, actor, joined(args, 3)).thenAccept(result -> dispatch(sender, result,
                        id -> "发行申请已创建：" + id));
            }
            case "approve" -> {
                UUID requestId = UUID.fromString(args[2]);
                economy.approveIssuance(requestId, actor).thenAccept(result -> dispatch(sender, result,
                        record -> "发行申请已审批：" + record.requestId()));
            }
            case "execute" -> {
                UUID requestId = UUID.fromString(args[2]);
                economy.executeIssuance(requestId, actor, "issuance:" + requestId).thenAccept(result ->
                        dispatch(sender, result, receipt -> "发行已入账，凭证：" + receipt.entryId()));
            }
            default -> throw new IllegalArgumentException("未知发行操作");
        }
    }

    private void retire(CommandSender sender, String[] args) {
        require(sender, RoleAccess.MONETARY);
        if (args.length < 3) throw new IllegalArgumentException("用法：/bloeco retire <金额> <原因>");
        Money amount = positiveMoney(args[1]);
        economy.retire(amount, actor(sender), joined(args, 2), "retire:" + UUID.randomUUID())
                .thenAccept(result -> dispatch(sender, result, receipt -> "回收已入账，凭证：" + receipt.entryId()));
    }

    private void player(CommandSender sender, String[] args) {
        require(sender, RoleAccess.TREASURER);
        if (args.length < 5 || !"set".equalsIgnoreCase(args[1])) {
            throw new IllegalArgumentException("用法：/bloeco player set <在线玩家> <目标余额> <原因>");
        }
        Player target = requireOnline(args[2]);
        Money targetBalance = Money.parse(args[3]);
        economy.adjustPlayerBalance(target.getUniqueId(), targetBalance, actor(sender), joined(args, 4),
                        "balance:" + UUID.randomUUID())
                .thenAccept(result -> dispatch(sender, result, receipt -> "余额调整已入账，凭证：" + receipt.entryId()));
    }

    private void tax(CommandSender sender, String[] args) {
        require(sender, RoleAccess.TAX);
        if (args.length < 6 || !"set".equalsIgnoreCase(args[1])) {
            throw new IllegalArgumentException("用法：/bloeco tax set <fee|income> <基点> <固定最小单位> <原因>");
        }
        TaxCategory category = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "fee" -> TaxCategory.PLAYER_TRANSFER_FEE;
            case "income" -> TaxCategory.PLAYER_TRANSFER_INCOME;
            default -> throw new IllegalArgumentException("税种只能是 fee 或 income");
        };
        int basisPoints = Integer.parseInt(args[3]);
        long fixedMinor = Long.parseLong(args[4]);
        economy.changeTaxRule(category, basisPoints, fixedMinor, actor(sender), joined(args, 5))
                .thenAccept(result -> dispatch(sender, result,
                        rule -> "财政规则已更新，版本：" + rule.versionId()));
    }

    private <T> void dispatch(CommandSender sender, Result<T> result, java.util.function.Function<T, String> success) {
        runMain(() -> sender.sendMessage(result.isSuccess() ? success.apply(result.value())
                : MessageFormatter.error(result)));
    }

    private void require(CommandSender sender, String permission) {
        if (!roles.allows(sender, permission)) throw new IllegalArgumentException("缺少权限：" + permission);
    }

    private static Player requireOnline(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null || !player.isOnline()) throw new IllegalArgumentException("玩家不在线：" + name);
        return player;
    }

    private static Money positiveMoney(String value) {
        Money money = Money.parse(value);
        if (money.minor() == 0) throw new IllegalArgumentException("金额必须大于零");
        return money;
    }

    private static String joined(String[] args, int start) {
        String text = String.join(" ", Arrays.copyOfRange(args, start, args.length)).trim();
        if (text.isBlank() || text.length() > 256) throw new IllegalArgumentException("原因必须为 1 至 256 个字符");
        return text;
    }

    private static String actor(CommandSender sender) {
        return sender instanceof Player player ? "player:" + player.getUniqueId() : "console:" + sender.getName();
    }

    private void runMain(Runnable action) {
        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, action);
    }

    private static void usage(CommandSender sender) {
        sender.sendMessage("Bloeco：/bloeco health|verify|balance|issue|retire|player|tax");
    }
}
