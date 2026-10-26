package de.wolfsfell.uniforms;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class WunderlandUniforms extends JavaPlugin implements Listener {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<UUID, PlayerProfile> originals = new ConcurrentHashMap<>();
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean active = false;
    private BufferedImage classic, slim;
    private Path cacheDir;

    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            classic = loadTemplate("skins/steve.png"); slim = loadTemplate("skins/alex.png");
            cacheDir = getDataFolder().toPath().resolve("cache"); Files.createDirectories(cacheDir);
        } catch (Exception e) { getLogger().severe("Skin templates unavailable: " + e); Bukkit.getPluginManager().disablePlugin(this); return; }
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("WunderlandUniforms ready. /wu start to begin.");
    }
    @Override public void onDisable() {
        active = false;
        // Restore online players while plugin is still enabled.
        for (Player player : Bukkit.getOnlinePlayers()) restore(player);
        worker.shutdownNow();
    }
    private BufferedImage loadTemplate(String name) throws Exception {
        try (InputStream in = getResource(name)) {
            if (in == null) throw new IllegalStateException(name + " missing");
            BufferedImage img = ImageIO.read(in);
            if (img == null || img.getWidth() != 64 || img.getHeight() != 64) throw new IllegalStateException(name + " must be 64x64 PNG");
            return img;
        }
    }
    @EventHandler public void join(PlayerJoinEvent e) {
        if (active && getConfig().getBoolean("apply-on-join", true))
            Bukkit.getScheduler().runTaskLater(this, () -> { if (active && e.getPlayer().isOnline()) dress(e.getPlayer()); }, 20L);
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("wunderlanduniforms.admin")) { sender.sendMessage("§cKeine Berechtigung."); return true; }
        if (args.length == 0) { sender.sendMessage("§d/wu start §7| §d/wu stop §7| §d/wu reload §7| §d/wu status"); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                if (active) { sender.sendMessage("§eEvent ist bereits aktiv."); return true; }
                if (getConfig().getString("mineskin-api-key", "").isBlank()) { sender.sendMessage("§cBitte zuerst mineskin-api-key in config.yml eintragen."); return true; }
                active = true;
                sender.sendMessage("§aUniformen aktiviert. Skins werden nacheinander erstellt (API-Limits beachten).");
                for (Player p : Bukkit.getOnlinePlayers()) dress(p);
            }
            case "stop" -> {
                active = false;
                for (Player p : Bukkit.getOnlinePlayers()) restore(p);
                sender.sendMessage("§aUniformen deaktiviert. Originalskins wiederhergestellt.");
            }
            case "reload" -> { reloadConfig(); sender.sendMessage("§aKonfiguration neu geladen."); }
            case "status" -> sender.sendMessage("§dWunderlandUniforms: " + (active ? "§aAKTIV" : "§cINAKTIV") + " §7| Wartend: " + pending.size());
            default -> sender.sendMessage("§c/wu <start|stop|reload|status>");
        }
        return true;
    }
    private void dress(Player player) {
        UUID id = player.getUniqueId();
        if (!active || !pending.add(id)) return;
        PlayerProfile original = player.getPlayerProfile().clone();
        originals.putIfAbsent(id, original);
        worker.submit(() -> {
            try {
                SkinData skin = loadPlayerSkin(id);
                BufferedImage merged = merge(skin.image(), skin.slim() ? slim : classic);
                String hash = sha256(merged);
                Path cached = cacheDir.resolve(hash + ".txt");
                String value, signature;
                if (Files.isRegularFile(cached)) {
                    List<String> lines = Files.readAllLines(cached);
                    value = lines.get(0); signature = lines.get(1);
                } else {
                    String[] signed = signWithMineSkin(merged, skin.slim());
                    value = signed[0]; signature = signed[1];
                    Files.writeString(cached, value + "\n" + signature + "\n");
                }
                String finalValue = value, finalSignature = signature;
                Bukkit.getScheduler().runTask(this, () -> {
                    pending.remove(id);
                    Player online = Bukkit.getPlayer(id);
                    if (!active || online == null || !online.isOnline() || !originals.containsKey(id)) return;
                    PlayerProfile profile = online.getPlayerProfile().clone();
                    profile.setProperty(new ProfileProperty("textures", finalValue, finalSignature));
                    online.setPlayerProfile(profile);
                    online.sendMessage("§dDein Wunderland-Trackie ist jetzt aktiv!");
                });
            } catch (Exception ex) {
                getLogger().warning("Skin for " + player.getName() + " failed: " + ex.getMessage());
                Bukkit.getScheduler().runTask(this, () -> pending.remove(id));
            }
        });
    }
    private void restore(Player player) {
        PlayerProfile original = originals.remove(player.getUniqueId());
        if (original != null) player.setPlayerProfile(original);
    }
    private record SkinData(BufferedImage image, boolean slim) {}
    private SkinData loadPlayerSkin(UUID id) throws Exception {
        String uuid = id.toString().replace("-", "");
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false"))
                .header("User-Agent", "WunderlandUniforms/1.0").timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Mojang profile HTTP " + response.statusCode());
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        String textureValue = null;
        for (var prop : root.getAsJsonArray("properties")) {
            JsonObject item = prop.getAsJsonObject();
            if ("textures".equals(item.get("name").getAsString())) { textureValue = item.get("value").getAsString(); break; }
        }
        if (textureValue == null) throw new IllegalStateException("No Mojang skin texture");
        JsonObject texture = JsonParser.parseString(new String(Base64.getDecoder().decode(textureValue), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("textures").getAsJsonObject("SKIN");
        boolean isSlim = texture.has("metadata") && texture.getAsJsonObject("metadata").has("model") &&
                "slim".equalsIgnoreCase(texture.getAsJsonObject("metadata").get("model").getAsString());
        String url = texture.get("url").getAsString();
        if (!url.startsWith("http://textures.minecraft.net/texture/") && !url.startsWith("https://textures.minecraft.net/texture/"))
            throw new IllegalStateException("Unexpected skin URL host");
        HttpResponse<InputStream> skinResponse = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (skinResponse.statusCode() != 200) throw new IllegalStateException("Skin HTTP " + skinResponse.statusCode());
        try (InputStream input = skinResponse.body()) {
            BufferedImage image = ImageIO.read(input);
            if (image == null || image.getWidth() != 64 || image.getHeight() != 64) throw new IllegalStateException("Only 64x64 player skins supported");
            return new SkinData(image, isSlim);
        }
    }
    private BufferedImage merge(BufferedImage original, BufferedImage uniform) {
        BufferedImage result = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.drawImage(uniform, 0, 0, null);
        // Preserve the full original head, including second (hat/hair) layer.
        g.drawImage(original.getSubimage(0, 0, 32, 16), 0, 0, null);
        g.drawImage(original.getSubimage(32, 0, 32, 16), 32, 0, null);
        // Head overlay is UV 32..63 x 0..15; base head is UV 0..31 x 0..15.
        g.dispose(); return result;
    }
    private String sha256(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(out.toByteArray());
        return HexFormat.of().formatHex(hash);
    }
    private String[] signWithMineSkin(BufferedImage image, boolean slim) throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream(); ImageIO.write(image, "png", png);
        String boundary = "WunderlandUniform" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writePart(body, boundary, "variant", slim ? "slim" : "classic");
        writePart(body, boundary, "visibility", "unlisted");
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"uniform.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(png.toByteArray()); body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.mineskin.org/v2/generate"))
                .timeout(Duration.ofSeconds(100))
                .header("User-Agent", "WunderlandUniforms/1.0 (Minecraft Paper plugin)")
                .header("Authorization", "Bearer " + getConfig().getString("mineskin-api-key", ""))
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("MineSkin HTTP " + response.statusCode() + " (rate limit/API key?); " + response.body().substring(0, Math.min(180, response.body().length())));
        JsonObject data = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("skin").getAsJsonObject("texture").getAsJsonObject("data");
        return new String[]{data.get("value").getAsString(), data.get("signature").getAsString()};
    }
    private void writePart(ByteArrayOutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
}
