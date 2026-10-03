package dev.reforgedfrost;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Builds the Reforged Frost pieces. Base material (leather / netherite) keeps its vanilla
 * armor values; the look comes from the mythicarmor: asset ids in the resource pack.
 */
public final class FrostItems {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    public enum Base {
        LEATHER, NETHERITE;

        public static Base parse(String s) {
            if (s == null) return null;
            try {
                return valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    public enum Piece {
        HELMET("HELMET", EquipmentSlot.HEAD, "head_piece", "Helmet"),
        CHESTPLATE("CHESTPLATE", EquipmentSlot.CHEST, "chest_piece", "Chestplate"),
        LEGGINGS("LEGGINGS", EquipmentSlot.LEGS, "legs_piece", "Leggings"),
        BOOTS("BOOTS", EquipmentSlot.FEET, "feet_piece", "Boots");

        final String materialSuffix;
        final EquipmentSlot slot;
        final String assetSuffix;
        final String display;

        Piece(String materialSuffix, EquipmentSlot slot, String assetSuffix, String display) {
            this.materialSuffix = materialSuffix;
            this.slot = slot;
            this.assetSuffix = assetSuffix;
            this.display = display;
        }

        public static Piece parse(String s) {
            if (s == null) return null;
            try {
                return valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private FrostItems() {}

    public static ItemStack create(Base base, Piece piece) {
        Material mat = Material.valueOf(base.name() + "_" + piece.materialSuffix);
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        meta.itemName(MM.deserialize("<bold><#C8C4E0>Reforged Frost <white>" + piece.display));

        List<Component> lore = new ArrayList<>();
        lore.add(MM.deserialize("<!italic><gray>Forged in endless winter,"));
        lore.add(MM.deserialize("<!italic><gray>pale, spiked, and crowned with horns,"));
        lore.add(MM.deserialize("<!italic><gray>worthy of a frost sovereign."));
        lore.add(MM.deserialize("<!italic><dark_gray>" + capitalize(base.name()) + " base"));
        lore.add(Component.empty());
        lore.add(MM.deserialize("<!italic><#C8C4E0>Any piece: <white>Speed I"));
        lore.add(MM.deserialize("<!italic><#C8C4E0>Full set: <white>Speed II, +5% max health"));
        meta.lore(lore);

        NamespacedKey asset = assetKey(piece);

        // item_model = inventory icon; equippable model = (blank) vanilla armor layer.
        // The 3D armor itself is drawn by ArmorRig with animated ItemDisplays.
        meta.setItemModel(asset);
        EquippableComponent eq = meta.getEquippable();
        eq.setSlot(piece.slot);
        eq.setModel(asset);
        meta.setEquippable(eq);

        item.setItemMeta(meta);
        return item;
    }

    public static NamespacedKey assetKey(Piece piece) {
        return NamespacedKey.fromString("mythicarmor:reforged_frost_armor_" + piece.assetSuffix);
    }

    private static String capitalize(String s) {
        return s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT);
    }
}
