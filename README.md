# PluginsReload

Minecraft 26.2 Bukkit/Paper/Purpur plugin helper.

## Commands

- `/pl reload` - Load newly added plugin jars from the `plugins` folder.
- `/pl unload <plugin>` - Disable a loaded plugin, remove its commands, and remove it from the plugin manager lists.
- `/pl help` - Show command help.
- `/pl version` - Show the current plugin version.
- `/pl update` - Check Modrinth for updates. A 404 response is treated as pending approval while the Modrinth project is not fully listed.

`/pl unload <plugin>` requires the same command to be entered twice to prevent accidental unloads.

## Build

```powershell
gradle clean build
```

The compiled jar is created in `build/libs/`.
