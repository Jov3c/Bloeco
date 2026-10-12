package com.blocke.example;

import org.bukkit.plugin.java.JavaPlugin;

public final class ExamplePlugin extends JavaPlugin {
    @Override public void onEnable() {
        new ExampleEconomyHook(this).connect().thenAccept(connected -> {
            if (!connected) getLogger().severe("无法连接 Bloeco API 1.x，示例插件将不处理经济业务。");
        });
    }
}
