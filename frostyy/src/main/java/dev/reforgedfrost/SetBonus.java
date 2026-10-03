package dev.reforgedfrost;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Set bonuses:
 *  - any piece worn      -> Speed I  (config: partial-speed-amplifier)
 *  - all 4 pieces worn   -> Speed II (config: full-speed-amplifier) + max health bonus (config: full-health-bonus)
 */
public final class SetBonus implements Listener {

    /** Effect duration in ticks; refreshed every REFRESH ticks so it never visibly runs out. */
    private static final int DURATION = 40;
    private static final int REFRESH = 10;

    private final ReforgedFrostPlugin plugin;
    private final Set<UUID> buffed = new HashSet<>();
    private final NamespacedKey healthKey;

    public SetBonus(ReforgedFrostPlugin plugin) {
        this.plugin = plugin;
        this.healthKey = new NamespacedKey(plugin, "set_health");
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, REFRESH);
    }

    public void clearAll() {
        for (UUID id : new HashSet<>(buffed)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) removeOurSpeed(p);
        }
        buffed.clear();
        for (Player p : Bukkit.getOnlinePlayers()) setHealthBonus(p, 0);
    }

    private void tick() {
        int partialAmp = plugin.getConfig().getInt("set-bonus.partial-speed-amplifier", 0);
        int fullAmp = plugin.getConfig().getInt("set-bonus.full-speed-amplifier", 1);
        double healthBonus = plugin.getConfig().getDouble("set-bonus.full-health-bonus", 0.05);

        for (Player p : Bukkit.getOnlinePlayers()) {
            int count = countPieces(p);
            setHealthBonus(p, count == 4 ? healthBonus : 0);
            if (count == 0) {
                if (buffed.remove(p.getUniqueId())) removeOurSpeed(p);
                continue;
            }
            int amp = count == 4 ? fullAmp : partialAmp;
            applySpeed(p, amp);
            buffed.add(p.getUniqueId());
        }
    }

    private void applySpeed(Player p, int amp) {
        PotionEffect existing = p.getPotionEffect(PotionEffectType.SPEED);
        // don't downgrade a stronger/longer effect from a potion or beacon
        if (existing != null && (existing.getAmplifier() > amp
                || (existing.getAmplifier() == amp && existing.getDuration() > DURATION))) {
            return;
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, DURATION, amp, true, false, true));
    }

    private void removeOurSpeed(Player p) {
        PotionEffect e = p.getPotionEffect(PotionEffectType.SPEED);
        if (e != null && e.getDuration() <= DURATION) p.removePotionEffect(PotionEffectType.SPEED);
    }

    /** Full set: +fraction of max health (0.05 = +5%). 0 removes the bonus. Transient, so it never sticks to the player data. */
    private void setHealthBonus(Player p, double fraction) {
        AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;
        AttributeModifier current = null;
        for (AttributeModifier m : attr.getModifiers()) {
            if (healthKey.equals(m.getKey())) {
                current = m;
                break;
            }
        }
        if (current != null && fraction > 0 && Math.abs(current.getAmount() - fraction) < 1e-9) return;
        if (current != null) attr.removeModifier(current);
        if (fraction > 0) {
            attr.addTransientModifier(new AttributeModifier(healthKey, fraction,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (buffed.remove(event.getPlayer().getUniqueId())) removeOurSpeed(event.getPlayer());
        setHealthBonus(event.getPlayer(), 0);
    }

    public static int countPieces(Player p) {
        var inv = p.getInventory();
        int n = 0;
        if (isPiece(inv.getHelmet(), FrostItems.Piece.HELMET)) n++;
        if (isPiece(inv.getChestplate(), FrostItems.Piece.CHESTPLATE)) n++;
        if (isPiece(inv.getLeggings(), FrostItems.Piece.LEGGINGS)) n++;
        if (isPiece(inv.getBoots(), FrostItems.Piece.BOOTS)) n++;
        return n;
    }

    /** Matches by asset id, so items made by Nexo/Oraxen/ItemsAdder configs count too. */
    public static boolean isPiece(ItemStack item, FrostItems.Piece piece) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        NamespacedKey want = FrostItems.assetKey(piece);
        if (meta.hasItemModel() && want.equals(meta.getItemModel())) return true;
        return meta.hasEquippable() && want.equals(meta.getEquippable().getModel());
    }
}
