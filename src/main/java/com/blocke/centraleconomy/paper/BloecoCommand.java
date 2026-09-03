package com.blocke.centraleconomy.paper;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;

/** Small command boundary: Bloeco's normal operation lives in the inventory UI. */
public final class BloecoCommand implements CommandExecutor {
    private final BloecoMenu menu;

    public BloecoCommand(BloecoMenu menu) {
        this.menu = Objects.requireNonNull(menu, "menu");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can open the Bloeco menu.");
            return true;
        }
        menu.open(player);
        return true;
    }
}
