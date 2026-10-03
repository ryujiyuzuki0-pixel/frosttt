package dev.reforgedfrost;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.minimessage.MiniMessage;

public final class FrostCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final List<String> SUBS = List.of("give", "pack", "view", "status", "rebuild", "reload");
    private static final List<String> OPTIONS = List.of(
            "leather", "netherite", "set", "helmet", "chestplate", "leggings", "boots");

    private final ReforgedFrostPlugin plugin;

    public FrostCommand(ReforgedFrostPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            msg(sender, "<#C8C4E0>/frost give <player> [leather|netherite] [set|helmet|chestplate|leggings|boots]\n"
                    + "<#C8C4E0>/frost pack [player] <gray>- resend resource pack\n"
                    + "<#C8C4E0>/frost view <gray>- first/third-person view of your own armor\n"
                    + "<#C8C4E0>/frost status <gray>- diagnose missing textures\n"
                    + "<#C8C4E0>/frost rebuild <gray>- run /ma reload\n"
                    + "<#C8C4E0>/frost reload");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reload();
                msg(sender, "<#C8C4E0>Reloaded.");
            }
            case "pack" -> {
                if (plugin.packHost() == null) {
                    msg(sender, "<red>Pack host is not enabled (config.yml: pack.enabled).");
                    return true;
                }
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1])
                        : (sender instanceof Player p ? p : null);
                if (target == null) {
                    msg(sender, "<red>Specify an online player.");
                    return true;
                }
                if (plugin.packHost().send(target)) {
                    msg(sender, "<#C8C4E0>Pack sent to " + target.getName() + ".");
                } else {
                    msg(sender, "<red>Pack NOT sent (no pack.zip yet, or public IP unknown). Run /frost status.");
                }
            }
            case "view", "helmet" -> {
                if (!(sender instanceof Player p) || plugin.armorRig() == null) {
                    msg(sender, "<red>Players only.");
                    return true;
                }
                boolean third = plugin.armorRig().toggleOwnView(p);
                msg(sender, third
                        ? "<#C8C4E0>Third-person view: you see your whole armor, helmet included."
                        : "<#C8C4E0>First-person view: helmet, chest and arms are hidden from you so they do not block the screen.");
            }
            case "give" -> give(sender, args);
            case "status" -> plugin.status(sender);
            case "rebuild" -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ma reload");
                msg(sender, "<#C8C4E0>Ran /ma reload. Then /frost pack to resend.");
            }
            default -> msg(sender, "<red>Unknown subcommand.");
        }
        return true;
    }

    private void give(CommandSender sender, String[] args) {
        Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1])
                : (sender instanceof Player p ? p : null);
        if (target == null) {
            msg(sender, "<red>Player not found.");
            return;
        }

        FrostItems.Base base = FrostItems.Base.parse(plugin.getConfig().getString("default-base", "netherite"));
        if (base == null) base = FrostItems.Base.NETHERITE;
        FrostItems.Piece piece = null; // null = full set

        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            FrostItems.Base b = FrostItems.Base.parse(a);
            if (b != null) {
                base = b;
            } else if (a.equalsIgnoreCase("set")) {
                piece = null;
            } else {
                FrostItems.Piece pc = FrostItems.Piece.parse(a);
                if (pc == null) {
                    msg(sender, "<red>Unknown option: " + a);
                    return;
                }
                piece = pc;
            }
        }

        List<ItemStack> items = new ArrayList<>();
        if (piece == null) {
            for (FrostItems.Piece pc : FrostItems.Piece.values()) items.add(FrostItems.create(base, pc));
        } else {
            items.add(FrostItems.create(base, piece));
        }

        for (ItemStack it : items) {
            Map<Integer, ItemStack> left = target.getInventory().addItem(it);
            for (ItemStack rest : left.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), rest);
            }
        }
        msg(sender, "<#C8C4E0>Gave " + target.getName() + " Reforged Frost ("
                + base.name().toLowerCase(Locale.ROOT) + ", "
                + (piece == null ? "full set" : piece.name().toLowerCase(Locale.ROOT)) + ").");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(SUBS, args[0]);
        if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("pack"))) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(names, args[1]);
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("give")) return filter(OPTIONS, args[args.length - 1]);
        return List.of();
    }

    private static List<String> filter(List<String> src, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : src) if (s.toLowerCase(Locale.ROOT).startsWith(p)) out.add(s);
        return out;
    }

    private static void msg(CommandSender s, String mm) {
        s.sendMessage(MM.deserialize(mm));
    }
}
