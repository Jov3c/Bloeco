package com.blocke.centraleconomy.market;

import com.blocke.centraleconomy.ledger.LedgerRepository;
import com.blocke.centraleconomy.ledger.SqliteLedgerRepository;
import com.blocke.centraleconomy.money.Money;
import org.bukkit.Material;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** SQLite market persistence coordinated by the authoritative ledger transaction. */
public final class SqliteMarketRepository implements MarketRepository {
    private final SqliteLedgerRepository ledger;

    public SqliteMarketRepository(SqliteLedgerRepository ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        ledger.inTransaction(transaction -> {
            createSchema(transaction.connection());
            return null;
        });
    }

    @Override
    public MarketListing createListing(UUID sellerId, Material material, int quantity, Money unitPrice) {
        Objects.requireNonNull(sellerId, "sellerId");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (quantity < 1) {
            throw new IllegalArgumentException("listing quantity must be positive");
        }
        if (unitPrice.cents() == 0) {
            throw new IllegalArgumentException("listing unit price must be positive");
        }

        MarketListing listing = new MarketListing(
                UUID.randomUUID(), sellerId, material, quantity, quantity, unitPrice,
                MarketListing.Status.ACTIVE, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        return ledger.inTransaction(transaction -> {
            insertListing(transaction.connection(), listing);
            return listing;
        });
    }

    @Override
    public MarketTrade settlePurchase(UUID listingId, UUID buyerId, int quantity, int feeRatePercent) {
        return settlePurchase(listingId, buyerId, quantity, feeRatePercent, List.of());
    }

    @Override
    public MarketTrade settlePurchase(
            UUID listingId,
            UUID buyerId,
            int quantity,
            int feeRatePercent,
            List<LedgerRepository.Posting> postings) {
        Objects.requireNonNull(listingId, "listingId");
        Objects.requireNonNull(buyerId, "buyerId");
        List<LedgerRepository.Posting> paymentPostings = List.copyOf(Objects.requireNonNull(postings, "postings"));
        if (quantity < 1) {
            throw new IllegalArgumentException("purchase quantity must be positive");
        }
        if (feeRatePercent < 0 || feeRatePercent > 20) {
            throw new IllegalArgumentException("market fee rate must be between 0 and 20 percent");
        }

        return ledger.inTransaction(transaction -> {
            MarketListing listing = activeListing(transaction.connection(), listingId)
                    .orElseThrow(() -> new IllegalStateException("listing is no longer active"));
            if (listing.sellerId().equals(buyerId)) {
                throw new IllegalArgumentException("seller cannot buy their own listing");
            }
            if (quantity > listing.remainingQuantity()) {
                throw new IllegalStateException("listing does not have enough remaining quantity");
            }

            int remaining = listing.remainingQuantity() - quantity;
            MarketListing.Status status = remaining == 0 ? MarketListing.Status.SOLD : MarketListing.Status.ACTIVE;
            updateListing(transaction.connection(), listing.id(), quantity, remaining, status);

            Money total = totalFor(listing.unitPrice(), quantity);
            Money fee = feeFor(total, feeRatePercent);
            MarketTrade trade = new MarketTrade(
                    UUID.randomUUID(), listing.id(), listing.sellerId(), buyerId, quantity, total, fee,
                    Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
            insertTrade(transaction.connection(), trade);
            transaction.applyLedgerPostings(paymentPostings);
            return trade;
        });
    }

    @Override
    public MarketListing cancelListing(UUID listingId, UUID sellerId) {
        Objects.requireNonNull(listingId, "listingId");
        Objects.requireNonNull(sellerId, "sellerId");

        return ledger.inTransaction(transaction -> {
            MarketListing listing = activeListing(transaction.connection(), listingId)
                    .orElseThrow(() -> new IllegalStateException("listing is no longer active"));
            if (!listing.sellerId().equals(sellerId)) {
                throw new IllegalArgumentException("only the seller may cancel this listing");
            }
            cancelListing(transaction.connection(), listing.id());
            return new MarketListing(
                    listing.id(), listing.sellerId(), listing.material(), listing.originalQuantity(),
                    listing.remainingQuantity(), listing.unitPrice(), MarketListing.Status.CANCELLED, listing.createdAt());
        });
    }

    @Override
    public Optional<MarketListing> findListing(UUID listingId) {
        Objects.requireNonNull(listingId, "listingId");
        return ledger.inTransaction(transaction -> findListing(transaction.connection(), listingId));
    }

    @Override
    public List<MarketListing> activeListings() {
        return ledger.inTransaction(transaction -> readActiveListings(transaction.connection()));
    }

    @Override
    public List<MarketTrade> tradesForListing(UUID listingId) {
        Objects.requireNonNull(listingId, "listingId");
        return ledger.inTransaction(transaction -> readTrades(transaction.connection(), listingId));
    }

    private static void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS market_listings (
                        id TEXT PRIMARY KEY,
                        seller_id TEXT NOT NULL,
                        material TEXT NOT NULL,
                        original_quantity INTEGER NOT NULL CHECK(original_quantity > 0),
                        remaining_quantity INTEGER NOT NULL CHECK(remaining_quantity >= 0 AND remaining_quantity <= original_quantity),
                        unit_price_cents INTEGER NOT NULL CHECK(unit_price_cents > 0),
                        status TEXT NOT NULL CHECK(status IN ('ACTIVE', 'SOLD', 'CANCELLED')),
                        created_at_epoch_ms INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS market_trades (
                        id TEXT PRIMARY KEY,
                        listing_id TEXT NOT NULL REFERENCES market_listings(id),
                        seller_id TEXT NOT NULL,
                        buyer_id TEXT NOT NULL,
                        quantity INTEGER NOT NULL CHECK(quantity > 0),
                        total_price_cents INTEGER NOT NULL CHECK(total_price_cents > 0),
                        fee_cents INTEGER NOT NULL CHECK(fee_cents >= 0 AND fee_cents <= total_price_cents),
                        created_at_epoch_ms INTEGER NOT NULL
                    )
                    """);
        }
    }

    private static void insertListing(Connection connection, MarketListing listing) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO market_listings(
                    id, seller_id, material, original_quantity, remaining_quantity, unit_price_cents, status, created_at_epoch_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, listing.id().toString());
            statement.setString(2, listing.sellerId().toString());
            statement.setString(3, listing.material().name());
            statement.setInt(4, listing.originalQuantity());
            statement.setInt(5, listing.remainingQuantity());
            statement.setLong(6, listing.unitPrice().cents());
            statement.setString(7, listing.status().name());
            statement.setLong(8, listing.createdAt().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private static Optional<MarketListing> activeListing(Connection connection, UUID listingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, seller_id, material, original_quantity, remaining_quantity, unit_price_cents, status, created_at_epoch_ms
                FROM market_listings WHERE id = ? AND status = 'ACTIVE'
                """)) {
            statement.setString(1, listingId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(toListing(result)) : Optional.empty();
            }
        }
    }

    private static Optional<MarketListing> findListing(Connection connection, UUID listingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, seller_id, material, original_quantity, remaining_quantity, unit_price_cents, status, created_at_epoch_ms
                FROM market_listings WHERE id = ?
                """)) {
            statement.setString(1, listingId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(toListing(result)) : Optional.empty();
            }
        }
    }

    private static void updateListing(
            Connection connection,
            UUID listingId,
            int quantity,
            int remaining,
            MarketListing.Status status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE market_listings SET remaining_quantity = ?, status = ?
                WHERE id = ? AND status = 'ACTIVE' AND remaining_quantity >= ?
                """)) {
            statement.setInt(1, remaining);
            statement.setString(2, status.name());
            statement.setString(3, listingId.toString());
            statement.setInt(4, quantity);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("listing is no longer active");
            }
        }
    }

    private static void cancelListing(Connection connection, UUID listingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE market_listings SET status = 'CANCELLED'
                WHERE id = ? AND status = 'ACTIVE'
                """)) {
            statement.setString(1, listingId.toString());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("listing is no longer active");
            }
        }
    }

    private static void insertTrade(Connection connection, MarketTrade trade) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO market_trades(
                    id, listing_id, seller_id, buyer_id, quantity, total_price_cents, fee_cents, created_at_epoch_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, trade.id().toString());
            statement.setString(2, trade.listingId().toString());
            statement.setString(3, trade.sellerId().toString());
            statement.setString(4, trade.buyerId().toString());
            statement.setInt(5, trade.quantity());
            statement.setLong(6, trade.totalPrice().cents());
            statement.setLong(7, trade.fee().cents());
            statement.setLong(8, trade.createdAt().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private static List<MarketTrade> readTrades(Connection connection, UUID listingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, listing_id, seller_id, buyer_id, quantity, total_price_cents, fee_cents, created_at_epoch_ms
                FROM market_trades WHERE listing_id = ? ORDER BY created_at_epoch_ms, id
                """)) {
            statement.setString(1, listingId.toString());
            try (ResultSet result = statement.executeQuery()) {
                java.util.ArrayList<MarketTrade> trades = new java.util.ArrayList<>();
                while (result.next()) {
                    trades.add(new MarketTrade(
                            UUID.fromString(result.getString("id")),
                            UUID.fromString(result.getString("listing_id")),
                            UUID.fromString(result.getString("seller_id")),
                            UUID.fromString(result.getString("buyer_id")),
                            result.getInt("quantity"),
                            Money.ofCents(result.getLong("total_price_cents")),
                            Money.ofCents(result.getLong("fee_cents")),
                            Instant.ofEpochMilli(result.getLong("created_at_epoch_ms"))));
                }
                return List.copyOf(trades);
            }
        }
    }

    private static List<MarketListing> readActiveListings(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, seller_id, material, original_quantity, remaining_quantity, unit_price_cents, status, created_at_epoch_ms
                FROM market_listings WHERE status = 'ACTIVE' ORDER BY created_at_epoch_ms, id
                """);
             ResultSet result = statement.executeQuery()) {
            java.util.ArrayList<MarketListing> listings = new java.util.ArrayList<>();
            while (result.next()) {
                listings.add(toListing(result));
            }
            return List.copyOf(listings);
        }
    }

    private static MarketListing toListing(ResultSet result) throws SQLException {
        return new MarketListing(
                UUID.fromString(result.getString("id")),
                UUID.fromString(result.getString("seller_id")),
                Material.valueOf(result.getString("material")),
                result.getInt("original_quantity"),
                result.getInt("remaining_quantity"),
                Money.ofCents(result.getLong("unit_price_cents")),
                MarketListing.Status.valueOf(result.getString("status")),
                Instant.ofEpochMilli(result.getLong("created_at_epoch_ms")));
    }

    private static Money totalFor(Money unitPrice, int quantity) {
        try {
            return Money.ofCents(Math.multiplyExact(unitPrice.cents(), (long) quantity));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("market purchase total exceeds supported range", exception);
        }
    }

    private static Money feeFor(Money total, int feeRatePercent) {
        try {
            return Money.ofCents(Math.multiplyExact(total.cents(), feeRatePercent) / 100);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("market fee exceeds supported range", exception);
        }
    }
}
