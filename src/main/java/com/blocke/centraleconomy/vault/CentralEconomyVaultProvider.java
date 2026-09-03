package com.blocke.centraleconomy.vault;

import com.blocke.centraleconomy.ledger.AccountId;
import com.blocke.centraleconomy.ledger.LedgerRepository;
import com.blocke.centraleconomy.ledger.TransactionType;
import com.blocke.centraleconomy.money.Money;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Vault boundary backed only by the authoritative Bloeco ledger. */
public final class CentralEconomyVaultProvider implements Economy {
    private static final String PROVIDER_NAME = "Bloeco";

    private final LedgerRepository ledger;
    private final String currencyNameSingular;
    private final String currencyNamePlural;

    public CentralEconomyVaultProvider(
            LedgerRepository ledger, String currencyNameSingular, String currencyNamePlural) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.currencyNameSingular = requireCurrencyName(currencyNameSingular, "currencyNameSingular");
        this.currencyNamePlural = requireCurrencyName(currencyNamePlural, "currencyNamePlural");
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return 2;
    }

    @Override
    public String format(double amount) {
        try {
            Money money = Money.fromVault(amount);
            return String.format(Locale.ROOT, "%.2f %s", toVault(money), currencyNamePlural);
        } catch (IllegalArgumentException exception) {
            return "invalid amount";
        }
    }

    @Override
    public String currencyNamePlural() {
        return currencyNamePlural;
    }

    @Override
    public String currencyNameSingular() {
        return currencyNameSingular;
    }

    @Override
    public boolean hasAccount(String playerName) {
        return offlinePlayer(playerName) != null;
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return player != null;
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public double getBalance(String playerName) {
        OfflinePlayer player = offlinePlayer(playerName);
        return player == null ? 0.0d : getBalance(player);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return player == null ? 0.0d : toVault(balance(player));
    }

    @Override
    public double getBalance(String playerName, String worldName) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String worldName) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        OfflinePlayer player = offlinePlayer(playerName);
        return player != null && has(player, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        try {
            return player != null && balance(player).cents() >= Money.fromVault(amount).cents();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        OfflinePlayer player = offlinePlayer(playerName);
        return player == null ? invalidPlayerResponse(amount) : withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        if (player == null) {
            return invalidPlayerResponse(amount);
        }
        Money money;
        try {
            money = Money.fromVault(amount);
        } catch (IllegalArgumentException exception) {
            return failure(0.0d, getBalance(player), exception.getMessage());
        }

        Money current = balance(player);
        if (current.cents() < money.cents()) {
            return failure(0.0d, toVault(current), "insufficient funds");
        }
        if (money.cents() != 0) {
            try {
                ledger.transfer(account(player), AccountId.externalDebit(), money, TransactionType.EXTERNAL_DEBIT,
                        "Vault withdrawal");
            } catch (IllegalArgumentException | IllegalStateException exception) {
                return failure(0.0d, getBalance(player), exception.getMessage());
            }
        }
        return success(toVault(money), getBalance(player));
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        OfflinePlayer player = offlinePlayer(playerName);
        return player == null ? invalidPlayerResponse(amount) : depositPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (player == null) {
            return invalidPlayerResponse(amount);
        }
        Money money;
        try {
            money = Money.fromVault(amount);
        } catch (IllegalArgumentException exception) {
            return failure(0.0d, getBalance(player), exception.getMessage());
        }

        if (money.cents() != 0) {
            try {
                ledger.transfer(AccountId.externalCredit(), account(player), money, TransactionType.EXTERNAL_CREDIT,
                        "Vault deposit");
            } catch (IllegalArgumentException | IllegalStateException exception) {
                return failure(0.0d, getBalance(player), exception.getMessage());
            }
        }
        return success(toVault(money), getBalance(player));
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankMember(String name, String player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return notImplemented();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return offlinePlayer(playerName) != null;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return player != null;
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    private Money balance(OfflinePlayer player) {
        return ledger.balance(account(player));
    }

    private static AccountId account(OfflinePlayer player) {
        return AccountId.player(player.getUniqueId());
    }

    private static OfflinePlayer offlinePlayer(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }
        return Bukkit.getOfflinePlayer(playerName);
    }

    private static double toVault(Money money) {
        return money.cents() / 100.0d;
    }

    private static String requireCurrencyName(String name, String label) {
        String normalized = Objects.requireNonNull(name, label).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized;
    }

    private static EconomyResponse success(double amount, double balance) {
        return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, null);
    }

    private static EconomyResponse failure(double amount, double balance, String message) {
        return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.FAILURE, message);
    }

    private static EconomyResponse invalidPlayerResponse(double amount) {
        return failure(0.0d, 0.0d, "unknown player");
    }

    private static EconomyResponse notImplemented() {
        return new EconomyResponse(0.0d, 0.0d, EconomyResponse.ResponseType.NOT_IMPLEMENTED,
                "bank accounts are not supported");
    }
}
