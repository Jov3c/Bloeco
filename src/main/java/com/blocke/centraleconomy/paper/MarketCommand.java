package com.blocke.centraleconomy.paper;

import com.blocke.centraleconomy.market.MarketListing;
import com.blocke.centraleconomy.money.Money;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;

/** Command boundary for the player-to-player material market. */
public final class MarketCommand implements CommandExecutor {
    private final MarketMenu menu;

    public MarketCommand(MarketMenu menu) {
        this.menu = Objects.requireNonNull(menu, "menu");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use the market.");
            return true;
        }
        if (args.length == 0) {
            menu.open(player);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("mine")) {
            menu.openMine(player);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("sell")) {
            try {
                Money unitPrice = parseMoney(args[1]);
                MarketListing listing = menu.createHeldListing(player, unitPrice);
                player.sendMessage("Listed " + listing.remainingQuantity() + " " + listing.material().name()
                        + " at " + format(listing.unitPrice().cents()) + " each.");
            } catch (RuntimeException exception) {
                player.sendMessage("Listing rejected: " + exception.getMessage());
            }
            return true;
        }
        sender.sendMessage("Usage: /market [sell <price>|mine]");
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
            throw new IllegalArgumentException("price must be positive with at most two decimal places", exception);
        }
    }

    private static String format(long cents) {
        return String.format(Locale.ROOT, "%.2f", cents / 100.0d);
    }
}
