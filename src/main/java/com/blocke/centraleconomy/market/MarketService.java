package com.blocke.centraleconomy.market;

import com.blocke.centraleconomy.ledger.AccountId;
import com.blocke.centraleconomy.ledger.LedgerRepository;
import com.blocke.centraleconomy.ledger.TransactionType;
import com.blocke.centraleconomy.economy.TaxPolicy;
import com.blocke.centraleconomy.economy.TaxType;
import com.blocke.centraleconomy.money.Money;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Market use cases that atomically settle listing quantity and ledger postings. */
public final class MarketService {
    private static final int DEFAULT_CONSUMPTION_TAX_RATE_PERCENT = 3;
    private final LedgerRepository ledger;
    private final MarketRepository marketRepository;
    private final int feeRatePercent;
    private final TaxPolicy taxPolicy;

    public MarketService(LedgerRepository ledger, MarketRepository marketRepository, int feeRatePercent) {
        this(ledger, marketRepository, feeRatePercent, new TaxPolicy(5, 1, 5, DEFAULT_CONSUMPTION_TAX_RATE_PERCENT));
    }

    public MarketService(
            LedgerRepository ledger,
            MarketRepository marketRepository,
            int feeRatePercent,
            int consumptionTaxRatePercent) {
        this(ledger, marketRepository, feeRatePercent,
                new TaxPolicy(5, 1, 5, consumptionTaxRatePercent));
    }

    public MarketService(
            LedgerRepository ledger,
            MarketRepository marketRepository,
            int feeRatePercent,
            TaxPolicy taxPolicy) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.marketRepository = Objects.requireNonNull(marketRepository, "marketRepository");
        if (feeRatePercent < 0 || feeRatePercent > 20) {
            throw new IllegalArgumentException("market fee rate must be between 0 and 20 percent");
        }
        this.feeRatePercent = feeRatePercent;
        this.taxPolicy = Objects.requireNonNull(taxPolicy, "taxPolicy");
    }

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
        return marketRepository.createListing(sellerId, material, quantity, unitPrice);
    }

    public MarketPurchase buy(UUID buyerId, UUID listingId, int quantity) {
        Objects.requireNonNull(buyerId, "buyerId");
        Objects.requireNonNull(listingId, "listingId");
        if (quantity < 1) {
            throw new IllegalArgumentException("purchase quantity must be positive");
        }

        MarketListing listing = marketRepository.findListing(listingId)
                .orElseThrow(() -> new IllegalStateException("listing does not exist"));
        if (listing.sellerId().equals(buyerId)) {
            throw new IllegalArgumentException("seller cannot buy their own listing");
        }

        MarketCharge charge = quoteBuyerCharge(listing.unitPrice(), quantity);
        Money total = charge.itemTotal();
        Money fee = feeFor(total);
        Money consumptionTax = charge.consumptionTax();
        Money sellerNet = Money.ofCents(total.cents() - fee.cents());
        List<LedgerRepository.Posting> postings = new ArrayList<>();
        postings.add(new LedgerRepository.Posting(
                AccountId.player(buyerId), AccountId.player(listing.sellerId()), sellerNet,
                TransactionType.TRANSFER, "market purchase " + listingId));
        if (fee.cents() > 0) {
            postings.add(new LedgerRepository.Posting(
                    AccountId.player(buyerId), AccountId.treasury(), fee,
                    TransactionType.TRANSFER_FEE, "market fee " + listingId));
        }
        if (consumptionTax.cents() > 0) {
            postings.add(new LedgerRepository.Posting(
                    AccountId.player(buyerId), AccountId.treasury(), consumptionTax,
                    TransactionType.CONSUMPTION_TAX, "market consumption tax " + listingId));
        }

        MarketTrade trade = marketRepository.settlePurchase(listingId, buyerId, quantity, feeRatePercent, postings);
        return new MarketPurchase(trade, sellerNet, consumptionTax, charge.buyerTotal());
    }

    /** Quotes the tax-inclusive buyer cost without reserving money or inventory. */
    public MarketCharge quoteBuyerCharge(Money unitPrice, int quantity) {
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (quantity < 1) {
            throw new IllegalArgumentException("purchase quantity must be positive");
        }
        Money total = totalFor(unitPrice, quantity);
        Money consumptionTax = consumptionTaxFor(total);
        return new MarketCharge(total, consumptionTax, add(total, consumptionTax));
    }

    public MarketResult cancel(UUID sellerId, UUID listingId) {
        Objects.requireNonNull(sellerId, "sellerId");
        Objects.requireNonNull(listingId, "listingId");
        return new MarketResult(marketRepository.cancelListing(listingId, sellerId));
    }

    public List<MarketListing> browse() {
        return marketRepository.activeListings();
    }

    private static Money totalFor(Money unitPrice, int quantity) {
        try {
            return Money.ofCents(Math.multiplyExact(unitPrice.cents(), (long) quantity));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("market purchase total exceeds supported range", exception);
        }
    }

    private Money feeFor(Money total) {
        return percentageOf(total, feeRatePercent, "market fee");
    }

    private Money consumptionTaxFor(Money total) {
        return percentageOf(total, taxPolicy.rate(TaxType.MARKET_CONSUMPTION), "market consumption tax");
    }

    private static Money percentageOf(Money total, int ratePercent, String label) {
        try {
            return Money.ofCents(Math.multiplyExact(total.cents(), ratePercent) / 100);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(label + " exceeds supported range", exception);
        }
    }

    private static Money add(Money first, Money second) {
        try {
            return Money.ofCents(Math.addExact(first.cents(), second.cents()));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("market buyer total exceeds supported range", exception);
        }
    }
}
