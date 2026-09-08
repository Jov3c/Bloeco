package com.blocke.centraleconomy.paper;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;

/** Opens the GUI-only Bloeco economy centre. */
public final class EcoCommand implements CommandExecutor {
    private final BloecoMenu menu;

    public EcoCommand(BloecoMenu menu) {
        this.menu = Objects.requireNonNull(menu, "menu");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("只有玩家可以打开经济中心。");
            return true;
        }
        menu.open(player);
        return true;
    }
}
