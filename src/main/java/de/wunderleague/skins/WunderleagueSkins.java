package de.wunderleague.skins;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class WunderleagueSkins extends JavaPlugin implements Listener {
    private final Set<UUID> participants = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PlayerProfile> originals = new ConcurrentHashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
    private Path skinsDir, cacheDir, participantsFile;
    private volatile boolean active;

    @Override public void onEnable() {
        saveDefaultConfig();
        active = getConfig().getBoolean("enabled", true);
        skinsDir = getDataFolder().toPath().resolve("skins");
        cacheDir = getDataFolder().toPath().resolve("cache");
        participantsFile = getDataFolder().toPath().resolve("participants.txt");
        try {
            Files.createDirectories(skinsDir); Files.createDirectories(cacheDir);
            if (Files.exists(participantsFile)) for (String line : Files.readAllLines(participantsFile)) {
                try { participants.add(UUID.fromString(line.trim())); } catch (IllegalArgumentException ignored) {}
            }
        } catch (IOException ex) { getLogger().severe("Storage initialization failed: " + ex); }
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskLater(this, () -> { if (active) Bukkit.getOnlinePlayers().forEach(p -> { { participants.add(p.getUniqueId()); apply(p); } }); }, 20L);
    }
    @Override public void onDisable() {
        Bukkit.getOnlinePlayers().forEach(this::restore);
        persist();
    }
    private void persist() {
        try { Files.write(participantsFile, participants.stream().map(UUID::toString).sorted().toList()); }
        catch (IOException ex) { getLogger().warning("Could not save participants: " + ex.getMessage()); }
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        participants.add(p.getUniqueId()); persist();
        if (active) Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) apply(p); }, 30L);
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        // Server-local profile changes are not Mojang account skin changes.
        originals.remove(event.getPlayer().getUniqueId()); busy.remove(event.getPlayer().getUniqueId());
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { sender.sendMessage("§d/wl status §7| Admin: on, off, all, reload, add <player>, remove <player>"); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("status")) { sender.sendMessage("§dWunderleague: §f" + (active ? "aktiv" : "aus") + " §7| Teilnehmer: " + participants.size()); return true; }
        if (sub.equals("join") || sub.equals("leave")) { sender.sendMessage("§cNur Event-Admins können Wunderleague-Skins verwalten."); return true; }
        if (!sender.hasPermission("wunderleague.admin")) { sender.sendMessage("§cKeine Berechtigung."); return true; }
        switch (sub) {
            case "on" -> { active = true; getConfig().set("enabled", true); saveConfig(); Bukkit.getOnlinePlayers().forEach(p -> { { participants.add(p.getUniqueId()); apply(p); } }); sender.sendMessage("§aWunderleague aktiviert."); }
            case "off" -> { active = false; getConfig().set("enabled", false); saveConfig(); Bukkit.getOnlinePlayers().forEach(this::restore); sender.sendMessage("§eWunderleague deaktiviert."); }
            case "all" -> { Bukkit.getOnlinePlayers().forEach(p -> { participants.add(p.getUniqueId()); if (active) apply(p); }); persist(); sender.sendMessage("§aAlle Online-Spieler für Wunderleague aktiviert."); }
            case "reload" -> { reloadConfig(); active = getConfig().getBoolean("enabled", true); sender.sendMessage("§aKonfiguration geladen."); }
            case "add", "remove" -> {
                if (args.length < 2) { sender.sendMessage("§c/wl " + sub + " <online-player>"); break; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { sender.sendMessage("§cSpieler muss online sein."); break; }
                if (sub.equals("add")) { participants.add(target.getUniqueId()); persist(); if (active) apply(target); }
                else { participants.remove(target.getUniqueId()); persist(); restore(target); sender.sendMessage("§eAchtung: Beim nächsten Join wird der Spieler automatisch wieder angemeldet."); }
                sender.sendMessage("§aTeilnehmerliste aktualisiert.");
            }
            default -> sender.sendMessage("§cUnbekannter Befehl.");
        }
        return true;
    }
    private void restore(Player p) {
        PlayerProfile old = originals.remove(p.getUniqueId());
        if (old != null && p.isOnline()) p.setPlayerProfile(old);
    }
    private void apply(Player p) {
        UUID id = p.getUniqueId();
        if (!active || !participants.contains(id) || !busy.add(id)) return;
        PlayerProfile source = originals.computeIfAbsent(id, k -> p.getPlayerProfile().clone());
        String key = getConfig().getString("mineskin-api-key", "").trim();
        if (key.isEmpty()) { p.sendMessage("§eWunderleague: MineSkin API-Key fehlt in config.yml."); busy.remove(id); return; }
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                URL skinUrl = source.getTextures().getSkin();
                if (skinUrl == null || !skinUrl.getHost().equalsIgnoreCase("textures.minecraft.net")) throw new IOException("No supported original skin URL");
                boolean slim = source.getTextures().getSkinModel() == org.bukkit.profile.PlayerTextures.SkinModel.SLIM;
                Path uniform = skinsDir.resolve(slim ? "alex.png" : "steve.png");
                if (!Files.isRegularFile(uniform)) throw new IOException("Missing " + uniform.getFileName());
                BufferedImage original = ImageIO.read(skinUrl);
                BufferedImage outfit = ImageIO.read(uniform.toFile());
                if (original == null || original.getWidth() != 64 || original.getHeight() != 64 || outfit == null || outfit.getWidth() != 64 || outfit.getHeight() != 64) throw new IOException("Both skins must be 64x64 PNG");
                BufferedImage merged = new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = merged.createGraphics(); graphics.drawImage(outfit,0,0,null);
                // Preserve head base and overlay (hat/hair) exactly as original.
                graphics.drawImage(original.getSubimage(0,0,32,16),0,0,null);
                graphics.drawImage(original.getSubimage(32,0,32,16),32,0,null);
                graphics.dispose();
                ByteArrayOutputStream png = new ByteArrayOutputStream(); ImageIO.write(merged,"png",png);
                byte[] data = png.toByteArray(); String hash = hex(MessageDigest.getInstance("SHA-256").digest(data));
                Path cached = cacheDir.resolve(hash + ".json");
                String value, signature;
                if (Files.exists(cached)) {
                    JsonObject saved = JsonParser.parseString(Files.readString(cached)).getAsJsonObject();
                    value = saved.get("value").getAsString(); signature = saved.get("signature").getAsString();
                } else {
                    JsonObject result = generate(data, slim, key);
                    JsonObject texture = result.getAsJsonObject("skin").getAsJsonObject("texture").getAsJsonObject("data");
                    value = texture.get("value").getAsString(); signature = texture.get("signature").getAsString();
                    JsonObject saved = new JsonObject(); saved.addProperty("value",value); saved.addProperty("signature",signature);
                    Files.writeString(cached, saved.toString());
                }
                String finalValue = value, finalSignature = signature;
                Bukkit.getScheduler().runTask(this, () -> {
                    try {
                        if (!active || !participants.contains(id) || !p.isOnline() || originals.get(id) != source) return;
                        PlayerProfile profile = source.clone();
                        profile.setProperty(new ProfileProperty("textures", finalValue, finalSignature));
                        p.setPlayerProfile(profile);
                        p.sendMessage("§aWunderleague-Skin angelegt!");
                    } finally { busy.remove(id); }
                });
            } catch (Exception ex) {
                getLogger().warning("Skin generation for " + p.getName() + " failed: " + ex);
                Bukkit.getScheduler().runTask(this, () -> { busy.remove(id); if (p.isOnline()) p.sendMessage("§cSkin fehlgeschlagen: " + ex.getMessage()); });
            }
        });
    }
    private JsonObject generate(byte[] png, boolean slim, String key) throws Exception {
        String boundary = "WL" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String variant = slim ? "slim" : "classic";
        for (String[] field : new String[][]{{"variant",variant},{"visibility","unlisted"}}) {
            out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+field[0]+"\"\r\n\r\n"+field[1]+"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"uniform.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.write(png); out.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.mineskin.org/v2/generate"))
                .timeout(Duration.ofSeconds(90)).header("Authorization","Bearer " + key)
                .header("User-Agent","WunderleagueSkins/1.0 (Minecraft Paper plugin)")
                .header("Content-Type","multipart/form-data; boundary="+boundary)
                .header("Accept","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("MineSkin HTTP " + response.statusCode() + " (rate limits or API error)");
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!json.has("skin") || json.get("skin").isJsonNull()) throw new IOException("MineSkin returned no skin");
        return json;
    }
    private static String hex(byte[] bytes) { StringBuilder sb = new StringBuilder(); for (byte b : bytes) sb.append(String.format("%02x", b & 255)); return sb.toString(); }
}
