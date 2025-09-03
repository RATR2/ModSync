# ModSync

**ModSync** is a Minecraft mod designed to simplify playing on modded servers. Instead of manually installing modpacks, players only need to install ModSync, and it handles the rest.

## Features

- **Server Compatibility Check**: Shows a checkmark or X in the server list if the server supports ModSync.
- **Handshake on Connect**: Compares client and server mods when joining a server.
- **Automatic Mod Download**: If mods are missing or mismatched, prompts to download from the server or from provided URLs.
- **Auto-Restart**: After downloading mods, Minecraft restarts automatically so everything is ready to go.
- **Multi-Loader Support**: Works with Forge, Fabric, and Quilt from a single shared codebase.
- **Configurable**: Players can choose default download source, auto-accept downloads, and more.

## Installation

1. Install ModSync for your modloader (Forge, Fabric, or Quilt).
2. Launch Minecraft.
3. ModSync will automatically check servers and manage mods as needed.

## Contributing

This project is open! The architecture is multi-module:

- `common/` – shared, platform-independent logic
- `forge/` – Forge-specific bootstrap
- `fabric/` – Fabric-specific bootstrap
- `quilt/` – Quilt-specific bootstrap
