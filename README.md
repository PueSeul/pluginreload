# PluginsReload

Minecraft 26.2 Bukkit/Paper/Purpur plugin helper.

## Commands

- `/pl reload` - Load newly added plugin jars from the `plugins` folder.
- `/pl unload <plugin>` - Disable a loaded plugin, remove its commands, and remove it from the plugin manager lists.
- `/pl help` - Show command help.
- `/pl version` - Show the current plugin version.
- `/pl update` - Check GitHub Releases and stage a new version for the next server restart.

`/pl unload <plugin>` requires the same command to be entered twice to prevent accidental unloads.
`/pl update` also requires a second confirmation when a new version is available. The verified JAR is saved in `plugins/update` and applied on the next full server restart.

## Build

```powershell
.\gradlew.bat clean build
```

The compiled jar is created as `build/libs/PluginsReload.jar`. The file name stays the same across plugin versions.
