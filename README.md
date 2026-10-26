# WunderlandUniforms 1.0 (Paper 1.21.11)

An independent HorrorOlympics uniform plugin. Each player retains the head from their own official Minecraft skin (including hair/hat layer), while the entire body, arms, hands, legs and shoes are replaced with the Wunderland template. Steve and Alex are handled separately.

## Setup
1. Use Java 21 and Paper 1.21.11.
2. Build on GitHub Actions (`Actions` → `Build Plugin` → `Run workflow`) or run `gradle build` locally.
3. Install `build/libs/WunderlandUniforms-1.0.0.jar` in `plugins/`, then start the server once.
4. Get a MineSkin API key from https://account.mineskin.org/keys and put it in `plugins/WunderlandUniforms/config.yml` under `mineskin-api-key` (never commit the key to GitHub).
5. Restart the server. Run `/wu start` to activate and `/wu stop` to restore. `/wu reload` and `/wu status` also available. OP permission required.

## Important limitations
- **Requires an internet connection** for Mojang's official skin service and MineSkin. MineSkin signs each unique merged texture. Generation is queued sequentially and can be rate-limited; with 20+ players the change may take a while.
- **Not tested on a live Paper server.** This is a first source build, not a guaranteed working release.
- Online-mode Mojang accounts with 64x64 skins only. Legacy 64x32 skins are not supported.
- If a player disconnects while active, original appearance is restored naturally by the Minecraft account skin on reconnect; if they reconnect while the event remains active, the uniform is reapplied.
- If the server crashes or restarts, the event defaults to off. Player skin changes are temporary and are not written to Mojang accounts.
- Skins and signed textures are cached in `plugins/WunderlandUniforms/cache` to avoid repeat MineSkin uploads for unchanged original skins.
- Do not share MineSkin API keys or upload config.yml to a public repository.
- For reliable changes to already-connected player models, a packet-based skin refresh may be needed on some clients. The plugin uses Paper's Player#setPlayerProfile, but full client behavior is unverified.

## Original skin templates
- `src/main/resources/skins/steve.png`
- `src/main/resources/skins/alex.png`
