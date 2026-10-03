package dev.reforgedfrost;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.MainHand;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import net.kyori.adventure.text.Component;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws the armor set as one ItemDisplay per body part (head, torso, dust, arms, hips, legs, boots)
 * and animates them every tick:
 *  - arms and legs swing while the player walks / runs (opposite arm and leg together)
 *  - the main-hand arm swings when you attack or use an item
 *  - the upper body leans forward while sneaking
 *  - the dust cubes around the chest slowly orbit and bob
 * The base armor (head shell, chest, arms, belt, legs, boots) is NOT drawn here: it is baked into the vanilla armor
 * layers, which the game animates itself (every pose, inventory preview). These displays only add the 3D extras.
 * Only the parts of pieces that are actually worn are shown, so a half set looks like a half set.
 * Every part is mounted on the player as a passenger, so the client moves it together with the player model
 * (server-side teleporting always trails behind your own view). Position and rotation are therefore expressed
 * purely through the display transformation: the entity itself never rotates, so the transformation frame is the world frame.
 * Models come from the resource pack (assets/mythicarmor/items/reforged_frost_armor_part_*.json).
 */
public final class ArmorRig implements Listener {

    /** One model pixel in blocks. The player model is rendered at 0.9375, so 32 px = 1.8 blocks. */
    private static final float K = 0.9375f / 16f;
    private static final float HIP_Y = 12f;
    private static final int SWING_TICKS = 6;
    /** Vanilla sitting pose (boat, minecart, horse...): legs forward, arms slightly raised. */
    private static final float RIDING_LEG = -1.4137167f;
    private static final float RIDING_ARM = -0.62831855f;

    private enum Kind { HEAD, UPPER, DUST, ARM, LEG }

    /** x/y/z = pivot in model pixels (same numbers as tools/build_pack.py). swing = limb swing direction. */
    private enum Part {
        HEAD(FrostItems.Piece.HELMET, "head", 0, 24, 0, Kind.HEAD, 0),
        TORSO(FrostItems.Piece.CHESTPLATE, "torso", 0, 24, 0, Kind.UPPER, 0),
        DUST(FrostItems.Piece.CHESTPLATE, "dust", 0, 20, 0, Kind.DUST, 0),
        ARM_R(FrostItems.Piece.CHESTPLATE, "arm_r", 5, 22, 0, Kind.ARM, -1),
        ARM_L(FrostItems.Piece.CHESTPLATE, "arm_l", -5, 22, 0, Kind.ARM, 1),
        LEG_R(FrostItems.Piece.LEGGINGS, "leg_r", 2, 12, 0, Kind.LEG, 1),
        LEG_L(FrostItems.Piece.LEGGINGS, "leg_l", -2, 12, 0, Kind.LEG, -1),
        BOOT_R(FrostItems.Piece.BOOTS, "boot_r", 2, 12, 0, Kind.LEG, 1),
        BOOT_L(FrostItems.Piece.BOOTS, "boot_l", -2, 12, 0, Kind.LEG, -1);

        final FrostItems.Piece piece;
        final NamespacedKey model;
        final float x, y, z;
        final Kind kind;
        final int swing;

        Part(FrostItems.Piece piece, String id, float x, float y, float z, Kind kind, int swing) {
            this.piece = piece;
            this.model = NamespacedKey.fromString("mythicarmor:reforged_frost_armor_part_" + id);
            this.x = x;
            this.y = y;
            this.z = z;
            this.kind = kind;
            this.swing = swing;
        }

        boolean upper() {
            return kind == Kind.HEAD || kind == Kind.UPPER || kind == Kind.DUST || kind == Kind.ARM;
        }
    }

    private static final class State {
        final Map<Part, ItemDisplay> displays = new EnumMap<>(Part.class);
        final Map<Part, float[]> sig = new EnumMap<>(Part.class);
        Set<FrostItems.Piece> worn = EnumSet.noneOf(FrostItems.Piece.class);
        long age;
        int scan;
        long spawnedAt;
        /** Measured server-side offset of a mounted display from the player's feet: [standing, sneaking]. */
        final float[][] attach = new float[2][];
    }

    /**
     * Movement state of EVERY online player, tracked from join on (not only while armor is worn), using the same
     * formulas as the vanilla client, so the 3D extras stay in step with the vanilla armor layer.
     */
    private static final class Body {
        Location prev;
        float bodyYaw;
        /** Walk cycle position and smoothed speed, like the client's WalkAnimationState. */
        double phase;
        float amp;
        /** Horizontal blocks moved this tick. */
        double speed;
        long age;
        /** Ticks left of the attack / use arm swing (0 = none). */
        int swingTicks;
    }

    private final ReforgedFrostPlugin plugin;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, Body> bodies = new HashMap<>();
    private final Map<Part, ItemStack> stacks = new EnumMap<>(Part.class);
    private final NamespacedKey viewKey;
    private final Set<UUID> hinted = new java.util.HashSet<>();
    private BukkitTask task;

    public ArmorRig(ReforgedFrostPlugin plugin) {
        this.plugin = plugin;
        this.viewKey = new NamespacedKey(plugin, "own_view");
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (State st : states.values()) removeAll(st);
        states.clear();
        bodies.clear();
    }

    /** True = third-person view: the wearer sees every part. False = first-person view: parts near the camera are hidden. */
    private boolean ownThird(Player p) {
        Byte v = p.getPersistentDataContainer().get(viewKey, org.bukkit.persistence.PersistentDataType.BYTE);
        if (v != null) return v == 1;
        return "third".equalsIgnoreCase(plugin.getConfig().getString("animation.own-view", "first"));
    }

    /** Head, torso, dust and arms sit around the camera in first person, so they are hidden from their wearer there. */
    private static boolean nearCamera(Part part) {
        return part.kind == Kind.HEAD || part.kind == Kind.UPPER || part.kind == Kind.DUST || part.kind == Kind.ARM;
    }

    /** /frost view: switch between first-person and third-person view of your own armor. Returns true if now third-person. */
    public boolean toggleOwnView(Player p) {
        boolean third = !ownThird(p);
        p.getPersistentDataContainer().set(viewKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte) (third ? 1 : 0));
        State st = states.get(p.getUniqueId());
        if (st != null) {
            for (Map.Entry<Part, ItemDisplay> en : st.displays.entrySet()) {
                if (!nearCamera(en.getKey())) continue;
                if (third) p.showEntity(plugin, en.getValue());
                else p.hideEntity(plugin, en.getValue());
            }
        }
        return third;
    }

    /** For /frost status: the measured display offset vs what vanilla would predict. */
    public String attachInfo(Player p) {
        State st = states.get(p.getUniqueId());
        if (st == null) return "not wearing any piece";
        float[] m = st.attach[0];
        return m == null ? "not measured yet (stand still for a second)"
                : String.format("x %.2f, y %.2f, z %.2f (vanilla would give 0, 1.80, 0)", m[0], m[1], m[2]);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        bodies.computeIfAbsent(event.getPlayer().getUniqueId(), k -> new Body()).swingTicks = SWING_TICKS;
    }

    /** The client builds a new player entity on respawn / dimension change, so its walk cycle restarts at 0. */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        bodies.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        bodies.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player p = event.getPlayer();
        if (!p.isSneaking()) return;
        if (!plugin.getConfig().getBoolean("animation.sneak-f-toggle", true)) return;
        if (wornPieces(p).isEmpty()) return;
        event.setCancelled(true);
        boolean third = toggleOwnView(p);
        p.sendActionBar(Component.text(third
                ? "Third-person view: your whole armor is visible"
                : "First-person view: helmet, chest and arms hidden from you"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        bodies.remove(event.getPlayer().getUniqueId());
        State st = states.remove(event.getPlayer().getUniqueId());
        if (st != null) removeAll(st);
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Body body = bodies.computeIfAbsent(p.getUniqueId(), k -> new Body());
            trackBody(p, body);

            State st = states.get(p.getUniqueId());
            // players without armor are only scanned every 4th tick
            if (st == null && (body.age & 3) != 0) continue;
            Set<FrostItems.Piece> worn = st != null && (st.scan++ % 4 != 0) ? st.worn : wornPieces(p);
            if (st == null) {
                if (worn.isEmpty()) continue;
                st = new State();
                states.put(p.getUniqueId(), st);
                if (plugin.getConfig().getBoolean("animation.sneak-f-toggle", true) && hinted.add(p.getUniqueId())) {
                    p.sendActionBar(Component.text("Sneak + F: switch your own armor view (first / third person)"));
                }
            }
            st.worn = worn;
            if (worn.isEmpty() || !shouldShow(p)) {
                removeAll(st);
                if (worn.isEmpty()) states.remove(p.getUniqueId());
                continue;
            }
            update(p, st, body);
        }
        states.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        bodies.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    /** Walk cycle and body yaw, ported from the vanilla client (WalkAnimationState, LivingEntity#tickHeadTurn). */
    private static void trackBody(Player p, Body b) {
        b.age++;
        Location loc = p.getLocation();
        float yaw = loc.getYaw();
        double dx = 0, dz = 0;
        if (b.prev != null && b.prev.getWorld() == loc.getWorld()) {
            dx = loc.getX() - b.prev.getX();
            dz = loc.getZ() - b.prev.getZ();
        } else {
            b.bodyYaw = yaw;
            b.phase = 0;
            b.amp = 0;
        }
        b.prev = loc;
        double dist = Math.sqrt(dx * dx + dz * dz);
        b.speed = dist;

        // limb swing: speed = min(distance * 4, 1), smoothed by 0.4 each tick; the cycle advances by that speed
        float target = (float) Math.min(dist * 4.0, 1.0);
        b.amp += (target - b.amp) * 0.4f;
        b.phase += b.amp;

        // body yaw: faces the walking direction (or its reverse when walking backwards), faces the head while attacking,
        // and is dragged along when the head turns more than 50 degrees away from it
        float g = b.bodyYaw;
        if (dist * dist > 0.0025) {
            float k = (float) (Math.atan2(dz, dx) * 57.29578) - 90f;
            float l = Math.abs(wrap(yaw) - k);
            g = (95f < l && l < 265f) ? k - 180f : k;
        }
        if (b.swingTicks > 0) {
            g = yaw;
            b.swingTicks--;
        }
        b.bodyYaw += wrap(g - b.bodyYaw) * 0.3f;
        float h = wrap(yaw - b.bodyYaw);
        if (Math.abs(h) > 50f) b.bodyYaw += h - Math.signum(h) * 50f;
    }

    private static boolean shouldShow(Player p) {
        if (!p.isValid() || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return false;
        if (p.hasPotionEffect(PotionEffectType.INVISIBILITY)) return false;
        Pose pose = p.getPose();
        // swimming, gliding, sleeping... use poses the rig does not animate, so the parts would float detached
        return pose == Pose.STANDING || pose == Pose.SNEAKING;
    }

    private static Set<FrostItems.Piece> wornPieces(Player p) {
        Set<FrostItems.Piece> out = EnumSet.noneOf(FrostItems.Piece.class);
        var inv = p.getInventory();
        if (SetBonus.isPiece(inv.getHelmet(), FrostItems.Piece.HELMET)) out.add(FrostItems.Piece.HELMET);
        if (SetBonus.isPiece(inv.getChestplate(), FrostItems.Piece.CHESTPLATE)) out.add(FrostItems.Piece.CHESTPLATE);
        if (SetBonus.isPiece(inv.getLeggings(), FrostItems.Piece.LEGGINGS)) out.add(FrostItems.Piece.LEGGINGS);
        if (SetBonus.isPiece(inv.getBoots(), FrostItems.Piece.BOOTS)) out.add(FrostItems.Piece.BOOTS);
        return out;
    }

    // ------------------------------------------------------------------ per-player update

    private void update(Player p, State st, Body b) {
        st.age++;
        boolean animate = plugin.getConfig().getBoolean("animation.enabled", true);
        double scale = plugin.getConfig().getDouble("animation.swing-scale", 1.0);
        double leanDeg = plugin.getConfig().getDouble("animation.sneak-lean-degrees", 26.0);
        boolean dustAnim = animate && plugin.getConfig().getBoolean("animation.dust", true);

        Location loc = p.getLocation();
        boolean riding = p.isInsideVehicle();

        // vanilla humanoid walk animation: arm = cos * speed (about 57 deg at a sprint), leg = cos * 1.4 * speed (80 deg).
        // The right arm and the left leg swing together, opposite to the right leg and the left arm.
        float amp = animate && !riding ? b.amp : 0f;
        double c = Math.cos(b.phase * 0.6662);
        float armSwing = (float) (c * amp * scale);
        float legSwing = (float) (c * 1.4 * amp * scale);
        float speed = (float) b.speed;

        boolean sneaking = p.getPose() == Pose.SNEAKING;
        double lean = sneaking ? Math.toRadians(leanDeg) : 0.0;

        double yawR = Math.toRadians(b.bodyYaw);
        double sin = Math.sin(yawR), cos = Math.cos(yawR);
        float headYaw = loc.getYaw();

        // Where does a mounted display sit relative to the player's feet? Vanilla says (0, height, 0), but instead of
        // trusting that, measure it from the server's own display position while the player stands still.
        int pose = sneaking ? 1 : 0;
        boolean still = speed < 1e-4f && p.isOnGround();
        if (still && st.age - st.spawnedAt >= 6 && !st.displays.isEmpty()) {
            ItemDisplay any = st.displays.values().iterator().next();
            if (any.isValid() && any.getVehicle() == p) {
                Location dl = any.getLocation();
                float mx = (float) (dl.getX() - loc.getX()), my = (float) (dl.getY() - loc.getY()), mz = (float) (dl.getZ() - loc.getZ());
                if (Math.abs(mx) < 2 && Math.abs(mz) < 2 && my > -1 && my < 3) st.attach[pose] = new float[] {mx, my, mz};
            }
        }
        float[] att = st.attach[pose] != null ? st.attach[pose] : new float[] {0f, (float) p.getHeight(), 0f};
        boolean third = ownThird(p);
        float yawB = (float) Math.toRadians(b.bodyYaw);
        float yawH = (float) Math.toRadians(headYaw);
        float pitch = (float) Math.toRadians(loc.getPitch());

        // attack swing: the main-hand arm sweeps forward and back over SWING_TICKS (about 85 degrees at the peak)
        float attack = 0f;
        if (animate && b.swingTicks > 0) {
            attack = (float) (Math.sin((1.0 - b.swingTicks / (double) SWING_TICKS) * Math.PI) * Math.toRadians(85));
        }
        Part mainArm = p.getMainHand() == MainHand.RIGHT ? Part.ARM_R : Part.ARM_L;

        for (Part part : Part.values()) {
            ItemDisplay d = st.displays.get(part);
            if (!st.worn.contains(part.piece)) {
                if (d != null) {
                    d.remove();
                    st.displays.remove(part);
                    st.sig.remove(part);
                }
                continue;
            }
            if (d == null || !d.isValid() || d.getVehicle() != p) {
                if (d != null) d.remove();
                d = spawn(p, part, loc, !third && nearCamera(part));
                st.spawnedAt = st.age;
                if (d == null) {
                    st.displays.remove(part);
                    continue;
                }
                st.displays.put(part, d);
                st.sig.remove(part);
            }

            // keep first/third-person visibility correct every tick (fixes parts that stay on screen after
            // respawn, world change, chunk reload or a missed hide)
            boolean hideFromOwner = !third && nearCamera(part);
            boolean visibleToOwner = p.canSee(d);
            if (hideFromOwner && visibleToOwner) p.hideEntity(plugin, d);
            else if (!hideFromOwner && !visibleToOwner) p.showEntity(plugin, d);

            // all offsets are in world space, so the display entity itself must not be rotated
            if (Math.abs(d.getLocation().getYaw()) > 0.01f || Math.abs(d.getLocation().getPitch()) > 0.01f) {
                d.setRotation(0f, 0f);
            }

            // pivot position, leaning the upper body forward around the hips when sneaking
            double px = part.x, py = part.y, pz = part.z;
            if (lean != 0 && part.upper()) {
                double f = -pz, u = py - HIP_Y;
                double f2 = f * Math.cos(lean) + u * Math.sin(lean);
                double u2 = u * Math.cos(lean) - f * Math.sin(lean);
                pz = -f2;
                py = HIP_Y + u2;
            }
            double right = px * K, fwd = -pz * K, up = py * K;
            // offset from the player's feet in world space (right = (-cos,-sin), forward = (-sin,cos) in x/z)
            float tx = (float) (right * -cos + fwd * -sin) - att[0];
            float tz = (float) (right * -sin + fwd * cos) - att[2];
            float ty = (float) up - att[1];

            // The item display flips the model 180 deg, so inside the transformation the model front is +z:
            // rotX > 0 tips the top forward (lean) and swings a hanging limb backward.
            float rotX = 0f, spin = 0f, bob = 0f;
            switch (part.kind) {
                case ARM -> rotX = part.swing * armSwing + (float) lean - (part == mainArm ? attack : 0f)
                        + (riding ? RIDING_ARM : 0f);
                case LEG -> rotX = riding ? RIDING_LEG : part.swing * legSwing;
                case UPPER -> rotX = (float) lean;
                case DUST -> {
                    rotX = (float) lean;
                    if (dustAnim) {
                        spin = (float) (st.age * 0.02);
                        bob = (float) (Math.sin(st.age * 0.07) * 0.04);
                    }
                }
                default -> { }
            }
            ty += bob;
            float yaw = part.kind == Kind.HEAD ? yawH : yawB;
            float pit = part.kind == Kind.HEAD ? pitch : 0f;

            float[] now = {tx, ty, tz, yaw, pit, rotX, spin};
            float[] sg = st.sig.get(part);
            boolean changed = sg == null;
            if (!changed) {
                for (int i = 0; i < now.length; i++) {
                    if (Math.abs(now[i] - sg[i]) > 0.0015f) {
                        changed = true;
                        break;
                    }
                }
            }
            if (changed) {
                d.setInterpolationDuration(sg == null ? 0 : 1);
                st.sig.put(part, now);
                Quaternionf q = new Quaternionf().rotateY(-yaw).rotateX(pit + rotX).rotateY(spin);
                d.setTransformation(new Transformation(new Vector3f(tx, ty, tz), q,
                        new Vector3f(0.9375f, 0.9375f, 0.9375f), new Quaternionf()));
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private ItemDisplay spawn(Player owner, Part part, Location at, boolean hideFromOwner) {
        ItemStack stack = stacks.computeIfAbsent(part, k -> {
            ItemStack it = new ItemStack(Material.PAPER);
            ItemMeta meta = it.getItemMeta();
            meta.setItemModel(k.model);
            it.setItemMeta(meta);
            return it;
        });
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(stack);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setPersistent(false);
            e.setInvulnerable(true);
            e.setInterpolationDelay(0);
            e.setInterpolationDuration(1);
            e.setShadowRadius(0f);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(0.9375f, 0.9375f, 0.9375f), new Quaternionf()));
        });
        if (!owner.addPassenger(d)) {
            d.remove();
            return null;
        }
        // first person: these parts would sit around/in front of the camera, so the wearer does not see them
        if (hideFromOwner) owner.hideEntity(plugin, d);
        return d;
    }

    private void removeAll(State st) {
        List<ItemDisplay> list = new ArrayList<>(st.displays.values());
        for (ItemDisplay d : list) d.remove();
        st.displays.clear();
        st.sig.clear();
    }

    private static float wrap(float deg) {
        deg %= 360f;
        if (deg >= 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }
}
