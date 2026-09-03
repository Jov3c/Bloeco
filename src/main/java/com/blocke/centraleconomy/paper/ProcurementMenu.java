package com.blocke.centraleconomy.paper;

import com.blocke.centraleconomy.economy.EconomyService;
import com.blocke.centraleconomy.economy.ProcurementItem;
import com.blocke.centraleconomy.economy.ProcurementQuote;
import com.blocke.centraleconomy.economy.ProcurementResult;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** The treasury procurement inventory and its synchronous item-for-payment transaction. */
public final class ProcurementMenu implements Listener {
    private static final int MENU_SIZE = 27;

    private final EconomyService economy;
    private final Map<Material, ProcurementItem> configuredItems;

    public ProcurementMenu(Plugin plugin, EconomyService economy, Collection<ProcurementItem> items) {
        Objects.requireNonNull(plugin, "plugin");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.configuredItems = configuredItems(items);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player player) {
        Objects.requireNonNull(player, "player");
        MenuHolder holder = new MenuHolder(this);
        Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE, "Treasury Procurement");
        holder.setInventory(inventory);

        int slot = 0;
        for (Map.Entry<Material, ProcurementItem> entry : configuredItems.entrySet()) {
            if (slot >= MENU_SIZE) {
                break;
            }
            inventory.setItem(slot, displayItem(entry.getKey(), entry.getValue()));
            holder.put(slot, entry.getKey());
            slot++;
        }
        player.openInventory(inventory);
    }

    /** Performs the guarded material sale; exposed so the command/UI boundary stays small and testable. */
    public void clickConfiguredItem(Player player, Material material) {
        ProcurementItem item = configuredItems.get(material);
        if (item == null) {
            player.sendMessage("That item is not configured for procurement.");
            return;
        }

        int quantity = Math.min(countPlainMaterial(player, material), item.maxPerSale());
        if (quantity < 1) {
            player.sendMessage("No plain " + material.name() + " is available to sell.");
            return;
        }

        ProcurementQuote quote;
        try {
            quote = economy.quoteProcurement(player.getUniqueId(), item, quantity);
        } catch (RuntimeException exception) {
            player.sendMessage("Sale rejected: " + exception.getMessage());
            return;
        }

        ItemStack[] originalContents = copyContents(player.getInventory().getStorageContents());
        removePlainMaterial(player, material, quantity);
        try {
            ProcurementResult result = economy.settleProcurement(player.getUniqueId(), quote);
            player.sendMessage("Sold " + quantity + " " + material.name() + " for " + format(result.playerNet().cents()) + ".");
        } catch (RuntimeException exception) {
            player.getInventory().setStorageContents(originalContents);
            player.sendMessage("Sale rejected; your items were restored.");
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder) || holder.menu != this) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        Material material = holder.materialAt(event.getRawSlot());
        if (material != null) {
            clickConfiguredItem(player, material);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder holder && holder.menu == this) {
            event.setCancelled(true);
        }
    }

    private static Map<Material, ProcurementItem> configuredItems(Collection<ProcurementItem> items) {
        Map<Material, ProcurementItem> byMaterial = new LinkedHashMap<>();
        for (ProcurementItem item : items) {
            if (!item.enabled()) {
                continue;
            }
            Material material = materialFor(item.materialKey());
            if (material == null || !material.isItem()) {
                continue;
            }
            if (byMaterial.putIfAbsent(material, item) != null) {
                throw new IllegalArgumentException("duplicate procurement material: " + material);
            }
        }
        return Map.copyOf(byMaterial);
    }

    private static Material materialFor(String key) {
        String normalized = key.toUpperCase(Locale.ROOT);
        if (normalized.startsWith("MINECRAFT:")) {
            normalized = normalized.substring("MINECRAFT:".length());
        }
        return Material.matchMaterial(normalized);
    }

    private static ItemStack displayItem(Material material, ProcurementItem item) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName("Sell " + material.name());
        meta.setLore(List.of(
                "Gross: " + format(item.unitPrice().cents()) + " each",
                "Maximum: " + item.maxPerSale()));
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
            throw new IllegalStateException("inventory changed before sale could remove items");
        }
        player.getInventory().setStorageContents(contents);
    }

    private static boolean isPlainMaterial(ItemStack stack, Material material) {
        return stack != null && stack.isSimilar(new ItemStack(material));
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

    private static final class MenuHolder implements InventoryHolder {
        private final ProcurementMenu menu;
        private final Map<Integer, Material> materialsBySlot = new LinkedHashMap<>();
        private Inventory inventory;

        private MenuHolder(ProcurementMenu menu) {
            this.menu = menu;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        private void put(int slot, Material material) {
            materialsBySlot.put(slot, material);
        }

        private Material materialAt(int slot) {
            return materialsBySlot.get(slot);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
