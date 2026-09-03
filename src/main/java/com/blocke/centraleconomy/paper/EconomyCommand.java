package com.blocke.centraleconomy.paper;

import com.blocke.centraleconomy.economy.EconomyService;
import com.blocke.centraleconomy.money.Money;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Player and administrator command boundary for the central economy. */
public final class EconomyCommand implements CommandExecutor {
    private static final String ADMIN_PERMISSION = "bloeco.tax-admin";
    private static final UUID DEFAULT_BLOCKSTOCK_RESERVE_TREASURY =
            UUID.fromString("00000000-0000-0000-0000-000000000098");

    private final EconomyService economy;
    private final ProcurementMenu menu;
    private final UUID blockStockReserveTreasury;

    public EconomyCommand(EconomyService economy, ProcurementMenu menu) {
        this(economy, menu, DEFAULT_BLOCKSTOCK_RESERVE_TREASURY);
    }

    public EconomyCommand(EconomyService economy, ProcurementMenu menu, UUID blockStockReserveTreasury) {
        this.economy = Objects.requireNonNull(economy, "economy");
        this.menu = Objects.requireNonNull(menu, "menu");
        this.blockStockReserveTreasury = Objects.requireNonNull(blockStockReserveTreasury, "blockStockReserveTreasury");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can open the Bloeco menu.");
                return true;
            }
            menu.open(player);
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "balance" -> balance(sender, args);
            case "report" -> report(sender);
            case "treasury" -> treasury(sender, args);
            case "reserve" -> reserve(sender, args);
            default -> false;
        };
    }

    private boolean balance(CommandSender sender, String[] args) {
        Player player;
        if (args.length == 1 && sender instanceof Player senderPlayer) {
            player = senderPlayer;
        } else if (args.length == 2) {
            player = Bukkit.getPlayerExact(args[1]);
        } else {
        sender.sendMessage("Usage: /bloeco (GUI recommended)");
            return true;
        }
        if (player == null) {
            sender.sendMessage("That player must be online.");
            return true;
        }
        sender.sendMessage(player.getName() + " balance: " + format(economy.playerBalance(player.getUniqueId()).cents()));
        return true;
    }

    private boolean report(CommandSender sender) {
        sender.sendMessage("Treasury balance: " + format(economy.treasuryBalance().cents()));
        return true;
    }

    private boolean treasury(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage("You do not have permission to administer the treasury.");
            return true;
        }
        if (args.length < 4 || !(args[1].equalsIgnoreCase("issue") || args[1].equalsIgnoreCase("burn"))) {
            sender.sendMessage("Usage: /economy treasury <issue|burn> <amount> <memo>");
            return true;
        }
        Money amount;
        try {
            amount = parseMoney(args[2]);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("Amount must be a positive amount with at most two decimal places.");
            return true;
        }
        String memo = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim();
        if (memo.isEmpty()) {
            sender.sendMessage("A treasury memo is required.");
            return true;
        }
        try {
            if (args[1].equalsIgnoreCase("issue")) {
                economy.issueToTreasury(amount, memo);
                sender.sendMessage("Issued " + format(amount.cents()) + " to the treasury.");
            } else {
                economy.burnFromTreasury(amount, memo);
                sender.sendMessage("Burned " + format(amount.cents()) + " from the treasury.");
            }
        } catch (RuntimeException exception) {
            sender.sendMessage("Treasury operation rejected: " + exception.getMessage());
        }
        return true;
    }

    private boolean reserve(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage("You do not have permission to administer the reserve.");
            return true;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("status")) {
            sender.sendMessage("BlockStock reserve balance: "
                    + format(economy.playerBalance(blockStockReserveTreasury).cents()));
            return true;
        }
        if (args.length < 4 || !args[1].equalsIgnoreCase("fund")) {
            sender.sendMessage("Usage: /economy reserve <status|fund <amount> <memo>>");
            return true;
        }
        Money amount;
        try {
            amount = parseMoney(args[2]);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("Amount must be a positive amount with at most two decimal places.");
            return true;
        }
        String memo = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim();
        if (memo.isEmpty()) {
            sender.sendMessage("A reserve memo is required.");
            return true;
        }
        try {
            economy.fundBlockStockReserve(blockStockReserveTreasury, amount, memo);
            sender.sendMessage("Allocated " + format(amount.cents()) + " from the treasury to the BlockStock reserve.");
        } catch (RuntimeException exception) {
            sender.sendMessage("Reserve allocation rejected: " + exception.getMessage());
        }
        return true;
    }

    private static Money parseMoney(String source) {
        try {
            BigDecimal decimal = new BigDecimal(source);
            if (decimal.signum() <= 0 || decimal.scale() > 2) {
                throw new IllegalArgumentException("invalid money amount");
            }
            return Money.ofCents(decimal.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact());
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("invalid money amount", exception);
        }
    }

    private static String format(long cents) {
        return String.format(Locale.ROOT, "%.2f", cents / 100.0d);
    }
}
