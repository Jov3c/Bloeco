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
import java.util.Locale;
import java.util.Objects;

/** Player payment command backed by the CentralEconomy atomic settlement service. */
public final class PayCommand implements CommandExecutor {
    private final EconomyService economy;

    public PayCommand(EconomyService economy) {
        this.economy = Objects.requireNonNull(economy, "economy");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player payer)) {
            sender.sendMessage("Only players can make payments.");
            return true;
        }
        if (args.length != 2) {
            payer.sendMessage("Usage: /pay <online-player> <amount>");
            return true;
        }
        Player recipient = Bukkit.getPlayerExact(args[0]);
        if (recipient == null) {
            payer.sendMessage("The recipient must be online.");
            return true;
        }
        Money amount;
        try {
            amount = parseMoney(args[1]);
            var result = economy.transferPlayerFunds(payer.getUniqueId(), recipient.getUniqueId(), amount,
                    "player payment " + payer.getName() + " -> " + recipient.getName());
            payer.sendMessage("Paid " + recipient.getName() + " " + format(result.amount().cents())
                    + "; fee " + format(result.transferFee().cents())
                    + ", income tax " + format(result.incomeTax().cents())
                    + ", total debited " + format(result.senderDebit().cents()) + ".");
            recipient.sendMessage("Received " + format(result.recipientNet().cents()) + " from " + payer.getName()
                    + " after income tax " + format(result.incomeTax().cents()) + ".");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            payer.sendMessage("Payment rejected: " + exception.getMessage());
        }
        return true;
    }

    private static Money parseMoney(String source) {
        try {
            BigDecimal decimal = new BigDecimal(source);
            if (decimal.signum() <= 0 || decimal.scale() > 2) {
                throw new IllegalArgumentException("amount must be positive with at most two decimal places");
            }
            return Money.ofCents(decimal.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact());
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("amount must be positive with at most two decimal places", exception);
        }
    }

    private static String format(long cents) {
        return String.format(Locale.ROOT, "%.2f", cents / 100.0d);
    }
}
