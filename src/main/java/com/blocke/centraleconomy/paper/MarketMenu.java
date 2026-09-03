package com.blocke.centraleconomy.paper;

import com.blocke.centraleconomy.market.MarketListing;
import com.blocke.centraleconomy.market.MarketPurchase;
import com.blocke.centraleconomy.market.MarketResult;
import com.blocke.centraleconomy.market.MarketService;
import com.blocke.centraleconomy.money.Money;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
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

/** Player market inventories. All market item movement is handled synchronously by the service boundary. */
public final class MarketMenu implements Listener {
    private static final int BROWSE_SIZE = 54;
    private static final int DETAIL_SIZE = 27;

    private final MarketService market;

    public MarketMenu(Plugin plugin, MarketService market) {
        Objects.requireNonNull(plugin, "plugin");
        this.market = Objects.requireNonNull(market, "market");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player player) {
        Objects.requireNonNull(player, "player");
        BrowseHolder holder = new BrowseHolder(this);
        Inventory inventory = createInventory(holder, BROWSE_SIZE, "Central Market");
        int slot = 0;
        for (MarketListing listing : market.browse()) {
            if (slot >= BROWSE_SIZE) {
                break;
            }
            holder.put(slot, listing);
            inventory.setItem(slot, listingDisplay(listing));
            slot++;
        }
        player.openInventory(inventory);
    }

    public void openMine(Player player) {
        Objects.requireNonNull(player, "player");
        MineHolder holder = new MineHolder(this);
        Inventory inventory = createInventory(holder, BROWSE_SIZE, "My Market Listings");
        int slot = 0;
        for (MarketListing listing : market.browse()) {
            if (!listing.sellerId().equals(player.getUniqueId())) {
                continue;
            }
            if (slot >= BROWSE_SIZE) {
                break;
            }
            holder.put(slot, listing);
            inventory.setItem(slot, mineDisplay(listing));
            slot++;
        }
        player.openInventory(inventory);
    }

    /** Escrows exactly {@code quantity} plain material units, restoring the full storage snapshot on failure. */
    public MarketListing createListing(Player player, Material material, int quantity, Money unitPrice) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (!material.isItem()) {
            throw new IllegalArgumentException("market listings must be item materials");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("listing quantity must be positive");
        }
        if (countPlainMaterial(player, material) < quantity) {
            throw new IllegalArgumentException("not enough plain " + material.name() + " to list");
        }

        ItemStack[] originalContents = copyContents(player.getInventory().getStorageContents());
        removePlainMaterial(player, material, quantity);
        try {
            return market.createListing(player.getUniqueId(), material, quantity, unitPrice);
        } catch (RuntimeException exception) {
            player.getInventory().setStorageContents(originalContents);
            throw exception;
        }
    }

    /** Lists the entire plain stack in the player's main hand. */
    public MarketListing createHeldListing(Player player, Money unitPrice) {
        Objects.requireNonNull(player, "player");
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!isPlainMaterial(held, held.getType())) {
            throw new IllegalArgumentException("hold a plain material stack to list it");
        }
        return createListing(player, held.getType(), held.getAmount(), unitPrice);
    }

    private void openConfirm(Player player, MarketListing listing) {
        ConfirmHolder holder = new ConfirmHolder(this, listing.id(), 1);
        Inventory inventory = createInventory(holder, DETAIL_SIZE, "Confirm Market Purchase");
        inventory.setItem(11, actionItem(Material.LIME_WOOL, "Buy 1", List.of(
                listing.material().name(), "Total: " + format(listing.unitPrice().cents()))));
        inventory.setItem(13, listingDisplay(listing));
        inventory.setItem(15, actionItem(Material.BARRIER, "Cancel", List.of()));
        player.openInventory(inventory);
    }

    private void buy(Player player, UUID listingId, int quantity) {
        MarketListing listing = market.browse().stream()
                .filter(candidate -> candidate.id().equals(listingId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("listing is no longer active"));
        if (!hasCapacityFor(player, listing.material(), quantity)) {
            throw new IllegalStateException("not enough inventory space for this purchase");
        }

        MarketPurchase purchase = market.buy(player.getUniqueId(), listingId, quantity);
        givePlainMaterial(player, listing.material(), quantity);
        player.sendMessage("Bought " + quantity + " " + listing.material().name() + " for "
                + format(purchase.trade().totalPrice().cents()) + ".");
        open(player);
    }

    private void cancel(Player player, MarketListing listing) {
        if (!hasCapacityFor(player, listing.material(), listing.remainingQuantity())) {
            throw new IllegalStateException("not enough inventory space to return this listing");
        }
        MarketResult result = market.cancel(player.getUniqueId(), listing.id());
        givePlainMaterial(player, result.listing().material(), result.listing().remainingQuantity());
        player.sendMessage("Cancelled listing; returned " + result.listing().remainingQuantity() + " "
                + result.listing().material().name() + ".");
        openMine(player);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MarketHolder holder) || holder.menu != this) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }

        try {
            if (holder instanceof BrowseHolder browse) {
                MarketListing listing = browse.listingAt(event.getRawSlot());
                if (listing != null) {
                    openConfirm(player, listing);
                }
            } else if (holder instanceof ConfirmHolder confirm && event.getRawSlot() == 11) {
                buy(player, confirm.listingId, confirm.quantity);
            } else if (holder instanceof ConfirmHolder && event.getRawSlot() == 15) {
                open(player);
            } else if (holder instanceof MineHolder mine) {
                MarketListing listing = mine.listingAt(event.getRawSlot());
                if (listing != null) {
                    cancel(player, listing);
                }
            }
        } catch (RuntimeException exception) {
            player.sendMessage("Market action rejected: " + exception.getMessage());
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MarketHolder holder && holder.menu == this) {
            event.setCancelled(true);
        }
    }

    private static Inventory createInventory(MarketHolder holder, int size, String title) {
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inventory);
        return inventory;
    }

    private static ItemStack listingDisplay(MarketListing listing) {
        return actionItem(listing.material(), listing.material().name(), List.of(
                "Available: " + listing.remainingQuantity(),
                "Unit price: " + format(listing.unitPrice().cents()),
                "Click to buy one"));
    }

    private static ItemStack mineDisplay(MarketListing listing) {
        return actionItem(listing.material(), listing.material().name(), List.of(
                "Available: " + listing.remainingQuantity(),
                "Unit price: " + format(listing.unitPrice().cents()),
                "Click to cancel and return items"));
    }

    private static ItemStack actionItem(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private static int countPlainMaterial(Player player, Material material) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (isPlainMaterial(stack, material)) {
                total = Math.addExact(total, stack.getAmount());
            }
        }
        return total;
    }

    private static void removePlainMaterial(Player player, Material material, int quantity) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        int remaining = quantity;
        for (int index = 0; index < contents.length && remaining > 0; index++) {
            ItemStack stack = contents[index];
            if (!isPlainMaterial(stack, material)) {
                continue;
            }
            int removed = Math.min(stack.getAmount(), remaining);
            if (removed == stack.getAmount()) {
                contents[index] = null;
            } else {
                stack.setAmount(stack.getAmount() - removed);
            }
            remaining -= removed;
        }
        if (remaining != 0) {
            throw new IllegalStateException("inventory changed before listing could be escrowed");
        }
        player.getInventory().setStorageContents(contents);
    }

    private static boolean hasCapacityFor(Player player, Material material, int quantity) {
        int capacity = 0;
        ItemStack plain = new ItemStack(material);
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                capacity = Math.addExact(capacity, material.getMaxStackSize());
            } else if (stack.isSimilar(plain)) {
                capacity = Math.addExact(capacity, stack.getMaxStackSize() - stack.getAmount());
            }
            if (capacity >= quantity) {
                return true;
            }
        }
        return false;
    }

    private static void givePlainMaterial(Player player, Material material, int quantity) {
        int remaining = quantity;
        while (remaining > 0) {
            int stackSize = Math.min(remaining, material.getMaxStackSize());
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(new ItemStack(material, stackSize));
            if (!leftovers.isEmpty()) {
                throw new IllegalStateException("inventory capacity changed before delivery");
            }
            remaining -= stackSize;
        }
    }

    private static boolean isPlainMaterial(ItemStack stack, Material material) {
        return stack != null && material.isItem() && stack.isSimilar(new ItemStack(material));
    }

    private static ItemStack[] copyContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            copy[index] = contents[index] == null ? null : contents[index].clone();
        }
        return copy;
    }

    private static String format(long cents) {
        return String.format(Locale.ROOT, "%.2f", cents / 100.0d);
    }

    /** Base holder used to identify and fully contain all central-market inventories. */
    public abstract static class MarketHolder implements InventoryHolder {
        private final MarketMenu menu;
        private Inventory inventory;

        private MarketHolder(MarketMenu menu) {
            this.menu = menu;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class BrowseHolder extends MarketHolder {
        private final Map<Integer, MarketListing> listingsBySlot = new LinkedHashMap<>();

        private BrowseHolder(MarketMenu menu) {
            super(menu);
        }

        private void put(int slot, MarketListing listing) {
            listingsBySlot.put(slot, listing);
        }

        private MarketListing listingAt(int slot) {
            return listingsBySlot.get(slot);
        }
    }

    private static final class MineHolder extends MarketHolder {
        private final Map<Integer, MarketListing> listingsBySlot = new LinkedHashMap<>();

        private MineHolder(MarketMenu menu) {
            super(menu);
        }

        private void put(int slot, MarketListing listing) {
            listingsBySlot.put(slot, listing);
        }

        private MarketListing listingAt(int slot) {
            return listingsBySlot.get(slot);
        }
    }

    private static final class ConfirmHolder extends MarketHolder {
        private final UUID listingId;
        private final int quantity;

        private ConfirmHolder(MarketMenu menu, UUID listingId, int quantity) {
            super(menu);
            this.listingId = listingId;
            this.quantity = quantity;
        }
    }
}
