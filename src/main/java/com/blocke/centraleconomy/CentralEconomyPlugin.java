package com.blocke.centraleconomy;

import org.bukkit.plugin.java.JavaPlugin;

public class CentralEconomyPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("procurement.yml", false);
    }
}
