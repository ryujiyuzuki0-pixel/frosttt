package dev.reforgedfrost;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collections;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executors;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import com.sun.net.httpserver.HttpServer;

import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Serves the MythicArmors-generated pack.zip over HTTP and pushes it to players
 * on join. Re-sends automatically whenever pack.zip changes (e.g. after /ma reload).
 */
public final class PackHost {

    /** Fixed id so our pack coexists with other server packs and only ever replaces itself. */
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("reforgedfrost:pack".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private final ReforgedFrostPlugin plugin;
    private final java.util.function.Supplier<File> fileSupplier;
    private HttpServer server;
    private BukkitTask watcher;
    private int port;

    private volatile String detectedIp;
    private boolean warnedMissing;

    private byte[] cachedHash;
    private long cachedModified = -1;
    private long lastSeenModified = -1;

    public PackHost(ReforgedFrostPlugin plugin, java.util.function.Supplier<File> fileSupplier) {
        this.plugin = plugin;
        this.fileSupplier = fileSupplier;
    }

    private File file() {
        return fileSupplier.get();
    }

    private boolean external() {
        return plugin.externalPackUrl() != null;
    }

    public void start(int port) throws IOException {
        this.port = port;
        if (external()) {
            // players download straight from the external URL (e.g. GitHub release): no HTTP server, no open port
            plugin.getLogger().info("Using external pack URL: " + plugin.externalPackUrl());
            lastSeenModified = file().isFile() ? file().lastModified() : -1;
            watcher = Bukkit.getScheduler().runTaskTimer(plugin, this::checkForUpdate, 200L, 200L);
            return;
        }
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/pack.zip", exchange -> {
            try {
                if (!file().isFile()) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] data = Files.readAllBytes(file().toPath());
                exchange.getResponseHeaders().add("Content-Type", "application/zip");
                exchange.sendResponseHeaders(200, data.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(data);
                }
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        plugin.getLogger().info("Pack host listening on port " + port + " (open this port in your firewall/host panel)");

        if (needsAutoIp()) detectPublicIp();

        lastSeenModified = file().isFile() ? file().lastModified() : -1;
        // every 10s: if pack.zip was rebuilt, push the new one to everyone online
        watcher = Bukkit.getScheduler().runTaskTimer(plugin, this::checkForUpdate, 200L, 200L);
    }

    public void stop() {
        if (watcher != null) {
            watcher.cancel();
            watcher = null;
        }
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void checkForUpdate() {
        long m = file().isFile() ? file().lastModified() : -1;
        if (m == lastSeenModified) return;
        boolean hadPack = lastSeenModified != -1;
        lastSeenModified = m;
        if (m != -1 && hadPack) {
            plugin.getLogger().info("pack.zip changed, resending to online players.");
            for (Player p : Bukkit.getOnlinePlayers()) send(p);
        }
    }

    private boolean needsAutoIp() {
        if (external()) return false;
        String url = plugin.getConfig().getString("pack.public-url", "auto");
        return url == null || url.isBlank() || url.equalsIgnoreCase("auto") || url.contains("YOUR_IP");
    }

    private void detectPublicIp() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.ipify.org"))
                        .timeout(Duration.ofSeconds(5)).GET().build();
                String ip = client.send(req, HttpResponse.BodyHandlers.ofString()).body().trim();
                detectedIp = ip;
                plugin.getLogger().info("Detected public IP " + ip + ", pack URL: http://" + ip + ":" + port + "/pack.zip");
            } catch (Exception e) {
                plugin.getLogger().warning("Could not auto-detect public IP (" + e.getMessage()
                        + "). Set pack.public-url in config.yml.");
            }
        });
    }

    private String baseUrl() {
        if (external()) return plugin.externalPackUrl();
        if (!needsAutoIp()) return plugin.getConfig().getString("pack.public-url");
        String ip = detectedIp;
        return ip == null ? null : "http://" + ip + ":" + port + "/pack.zip";
    }

    public boolean running() {
        return server != null || external();
    }

    public String describe() {
        String url = baseUrl();
        if (external()) return "external URL " + plugin.externalPackUrl();
        return "listening on port " + port + ", public URL: " + (url == null ? "NOT KNOWN YET (set pack.public-url)" : url);
    }

    private String localBase(Player player) {
        InetSocketAddress a = player.getAddress();
        if (a == null || a.getAddress() == null) return null;
        InetAddress ia = a.getAddress();
        String host = null;
        if (ia.isLoopbackAddress()) {
            host = "127.0.0.1";
        } else if (ia.isSiteLocalAddress()) {
            host = lanIp(ia);
        }
        return host == null ? null : "http://" + host + ":" + port + "/pack.zip";
    }

    /** This machine's LAN address, preferring one in the same /24 as the player. */
    private String lanIp(InetAddress player) {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || !addr.isSiteLocalAddress()) continue;
                    byte[] x = addr.getAddress(), y = player.getAddress();
                    if (y.length == 4 && x[0] == y[0] && x[1] == y[1] && x[2] == y[2]) return addr.getHostAddress();
                    if (fallback == null) fallback = addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    public boolean send(Player player) {
        if (!file().isFile()) {
            if (!warnedMissing) {
                warnedMissing = true;
                plugin.getLogger().warning("pack.zip not found at " + file().getPath()
                        + " (is MythicArmors installed and reloaded?). Will send once it exists.");
            }
            return false;
        }
        warnedMissing = false;

        // players on the same machine / LAN can't use the public IP (no hairpin NAT), so hand them a local URL
        String base = external() ? null : localBase(player);
        if (base == null) base = baseUrl();
        if (base == null) {
            plugin.getLogger().warning("Public IP not known yet; pack not sent to " + player.getName());
            return false;
        }
        byte[] hash = hash();
        if (hash == null) return false;

        // the version query makes each pack revision a new pack for the client, so updates re-download
        String sep = base.contains("?") ? "&" : "?";
        String url = base + sep + "v=" + HexFormat.of().formatHex(hash, 0, 4);

        boolean required = plugin.getConfig().getBoolean("pack.required", false);
        Component prompt = MiniMessage.miniMessage()
                .deserialize(plugin.getConfig().getString("pack.prompt", "Reforged Frost resource pack"));
        // Add (not set): setResourcePack would wipe packs from Nexo/Oraxen/ItemsAdder/server.properties.
        ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(url), HexFormat.of().formatHex(hash));
        player.removeResourcePacks(PACK_ID); // drop only our previous revision
        player.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                .packs(info)
                .prompt(prompt)
                .required(required)
                .replace(false)
                .build());
        return true;
    }

    private synchronized byte[] hash() {
        long modified = file().lastModified();
        if (cachedHash != null && modified == cachedModified) return cachedHash;
        try {
            cachedHash = MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(file().toPath()));
            cachedModified = modified;
            return cachedHash;
        } catch (Exception e) {
            plugin.getLogger().severe("Could not hash pack: " + e.getMessage());
            return null;
        }
    }
}
