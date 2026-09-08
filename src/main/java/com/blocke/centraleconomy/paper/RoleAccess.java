package com.blocke.centraleconomy.paper;

import org.bukkit.command.CommandSender;

/** Central role check delegated to the server's permission system. */
public final class RoleAccess {
    public static final String OPERATOR = "bloeco.role.operator";
    public static final String TREASURER = "bloeco.role.treasurer";
    public static final String TAX = "bloeco.role.tax";
    public static final String MONETARY = "bloeco.role.monetary";
    public static final String AUDITOR = "bloeco.role.auditor";
    public static final String BANKER = "bloeco.role.banker";

    public boolean allows(CommandSender sender, String permission) {
        return sender.hasPermission(permission);
    }

    public boolean anyAdministration(CommandSender sender) {
        return allows(sender, OPERATOR) || allows(sender, TREASURER) || allows(sender, TAX)
                || allows(sender, MONETARY) || allows(sender, AUDITOR) || allows(sender, BANKER);
    }
}
