# WunderleagueSkins V1.0 (source preview)

Paper 1.21.11 / Java 21. Build: `gradle build` (Gradle 8.14+ recommended). The JAR appears in `build/libs/`.

1. Put `steve.png` and `alex.png` (64x64, full skin templates) in `plugins/WunderleagueSkins/skins/`.
2. Create a MineSkin API key at https://account.mineskin.org/keys and set `mineskin-api-key` in config.yml.
3. Restart server. Players use `/wl join`; `/wl leave` restores their previous skin; admins use `/wl on`, `/wl off`, `/wl add <player>`, `/wl remove <player>`.

## Important limitations
- This is a **source MVP, not yet compiled or server-tested**.
- Uses MineSkin's synchronous `/v2/generate` endpoint (documented but deprecated in favor of queued jobs). Requests can be rate-limited. The cache reduces duplicate requests; a future version should implement `/v2/queue` and polling.
- The server does not change Mojang account skins. The appearance changes only on this Paper server.
- Head texture UV pixels are preserved; capes and skin overlays on the body come from the Wunderleague skin.
- Requires online-mode authenticated Java players with downloadable textures from textures.minecraft.net.
- MineSkin may require a new signed texture for every unique player head. Don't run a 20-player event without testing rate limits first.
- Self-view can lag until respawn/reconnect depending on client.
