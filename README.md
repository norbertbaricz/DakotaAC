# DakotaAC 0.19

Minecraft anti-cheat by **MaxUltimat3**. This update retains the original check classes, module names, detection formulas, and default thresholds while moving version-sensitive packet handling into a shared compatibility layer.

## Install

1. Use a supported Paper version with its required Java version: the baseline is **1.21.11**, and the newer API verification target is **26.3**.
2. Install an official ProtocolLib build that explicitly supports your Minecraft version.
3. Put `DakotaAC-0.19.jar` in `plugins/` and start the server.
4. Edit `plugins/DakotaAC/config.yml` and `plugins/DakotaAC/lang.yml`.
5. Run `/dac reload`, then `/dac status` to inspect enabled checks and recorded errors.

The source distribution also includes a compiled plugin in `dist/`.

### Choose the correct ProtocolLib artifact

- **Paper 1.21.11:** use a ProtocolLib build that supports 1.21.11 and can load on that server. The official development release provides a legacy Bukkit/Spigot artifact named `ProtocolLib-Spigot.jar`, which also loads on Paper.
- **Paper 26.2 and newer:** the modern Paper artifact is named `ProtocolLib.jar`; use a build containing the mappings/fixes for your exact Minecraft release.
- The supplied reference JAR is `5.5.0-SNAPSHOT-67ce937` and its `paper-plugin.yml` declares `api-version: "26.2"`. That exact artifact cannot normally load on Paper 1.21.11. The working 1.21.11 server therefore needs a compatible artifact.
- ProtocolLib **5.4.0** is the compile-time API baseline, not a promise that its server plugin supports every newer Minecraft release. Never bundle ProtocolLib inside DakotaAC.

Official downloads and release notes: [ProtocolLib releases](https://github.com/dmulloy2/ProtocolLib/releases). Paper documents the minimum-version behavior of `api-version` in its [plugin descriptor reference](https://docs.papermc.io/paper/dev/plugin-yml/).

## Configuration

Existing `Settings.*` and `Modules.<Check>.enabled` paths are preserved. Missing settings are added on load without replacing existing values. Annotated defaults at the top of each check correspond directly to that check's configuration keys.

| Module setting | Behavior |
| --- | --- |
| `enabled` | Register or completely stop the check, including its packet listeners and tasks. |
| `cancel-events` | Cancel offending packets or Bukkit events where the check uses cancellation. |
| `setbacks` | Teleport suspicious movement to the original safe position. |
| `enforce-actions` | Apply extra actions, such as resetting sprint, restoring fall damage, hiding entities, or direct protective disconnects. |
| `flag-enabled` | Record detections and send alerts. |
| `flag-weight` | Violation points per detection; `1` retains the original behavior. |
| `punish` | Permit detections from this check to trigger global kick/ban thresholds. |
| `bypass-permission` | Exempt this permission from the check; empty means no permission bypass. |

Each check also exposes its own cooldowns, buffers, speeds, distances, sampling intervals, and relevant lists. Numeric values must be finite and within the bounds shown by that field's `@Option` annotation. Invalid values use the bundled default and produce a diagnostic. Protocol indexes, packet IDs, unit conversions, and fixed Minecraft inventory slot numbers are implementation details, not tuning options.

To observe a check while tuning it, leave `flag-enabled: true` and set its `cancel-events`, `setbacks`, `enforce-actions`, and `punish` to `false`. Client-side decoys still operate because they are detection probes. Disable the module to remove those probes. `ServerCrasher.kick-on-flood` and `AntiVPN.kick-on-detection` control their direct protective disconnects; those are separate from the global automatic kick threshold.

Global sections cover alert permissions/cooldowns, lifecycle sounds, report settings, diagnostics, and the console ban command. Settings in milliseconds and ticks retain their original units; a normal server tick is 50 ms. The old `Spammer.min-delay-between-messages` and `repeat-message-cooldown` settings are also milliseconds.

### Messages

All plugin-authored chat messages, console templates, disconnect reasons, report-book text, decoy display names, flag names, and flag details are in `lang.yml`. Use `&` color codes and the named `{placeholders}` included in each default template. `{prefix}` inserts the shared prefix. Technical exception text and messages emitted by Paper, Bukkit, or other plugins are outside this file.

Changing a check's display name does not change its configuration/module identifier or punishment policy. `/dac reload` reloads messages, thresholds, listeners, plugin channels, and repeating tasks together. Pending probes are cleaned up first, and XRay restores current world block data rather than stale snapshots.

## Commands and permissions

| Command | Purpose |
| --- | --- |
| `/dac status` | Show environment versions, active/unavailable checks, and recorded runtime errors. |
| `/dac reload` | Reload configuration and language files. |
| `/dac check <ModuleName>` | Toggle a check and save its state. |
| `/dac kick` / `/dac ban` | Toggle automatic punishments. |
| `/dac flag <KickLimit> <BanLimit>` | Set punishment thresholds. |
| `/dac notify` | Toggle staff violation alerts. |
| `/report <player>` | Report an online player. |
| `/report info` | Open the persistent report database as a book. |

Administrators use `dakotaac.admin` by default. Alerts use `dakotaac.notification`; `dakotaac.alerts` remains an alias. Report counts stay in the first world's persistent data container. Violation totals remain in memory across disconnects and `/dac reload`, until the plugin's classloader/server is restarted, matching the original session-based design.

## Compatibility changes

- Attack listeners support both legacy `USE_ENTITY` attacks and the dedicated `ATTACK` packet. Interaction-only packets do not initialize obsolete attack wrappers on split protocols.
- Swing listeners resolve `ARM_ANIMATION` or the newer `PUNCH` packet by capability.
- Movement listeners read ground-only packets through `GROUND` when the obsolete `FLYING` name is unavailable.
- Required movement fields are read through one shape-based adapter, including vector positions and packed ground flags. Missing layouts and non-finite values produce diagnostics instead of null-unboxing or fake zero coordinates.
- Virtual players use ProtocolLib's named action API, not action enum ordinals. Spawn and teleport packets use the supported runtime shape instead of directly linking to a wrapper present only in newer builds.
- AutoArmor uses the player-inventory packet where available, avoiding incorrect container state IDs. A failed packet does not create a false decoy session.
- Packet listeners request server-thread execution before accessing Bukkit world/player state. Existing main-thread callbacks run immediately when already on that thread. Timing checks therefore use server-thread dispatch time, not a claim of raw socket arrival time.
- ViaVersion remains optional. Brand messages decode the actual VarInt-prefixed UTF-8 string instead of stripping arbitrary payload bytes.
- Module startup failures roll back listeners/tasks for that module; `/dac status` exposes unavailable modules and runtime errors. Startup no longer claims unconditional complete protection.
- The Regen packet counter now starts its time window on the first packet. Criticals applies its existing vanilla exemptions before cancelling a hit. Phase uses the player's current pose dimensions with the original standing insets.
- The Linux filename mismatch `violation.java` / `Violation` is corrected. CI uses Java 21 for the baseline and Java 25 for the newer Paper API.

## Build and verification

Use **JDK 21+** and Maven 3.9+:

```sh
mvn clean verify
```

The plugin is `target/DakotaAC-0.19.jar`. Paper and ProtocolLib are provided dependencies. ViaVersion is not required to compile or run the plugin.

For the newer Paper API, use **JDK 25**:

```sh
mvn clean verify -Dpaper.version=26.3.build.218-stable
```

The regression tests cover packet field layouts, malformed brands, message substitution, punishment boundaries, typed settings, and complete configuration/language resource coverage. See [VALIDATION.md](VALIDATION.md) for exact verified versions and the scope of testing.

Compilation and regression tests do not establish identical gameplay behavior across every Minecraft, ProtocolLib, ViaVersion, or client-mod combination. The original checks remain heuristics. Changed vanilla mechanics, custom attributes/items, server lag, and other plugins can affect their assumptions. Test your selected server/client combination before enabling automatic punishments for players. Unknown future protocol layouts require an update to the shared compatibility layer rather than silently disabling checks.
