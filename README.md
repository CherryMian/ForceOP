# ForceOP (Paper)

A Paper-only plugin that secures operator status. On first start it migrates current server ops into `config.json`, clears the server OP list, and then watches for unauthorized ops. It can also sync ops from `ops.json`, run commands on offenders, and includes a version check command.

## Features
- First-run migration: copies existing ops into config and clears the server op list.
- Periodic audit (async): checks `ops.json` and online/offline ops, deops unexpected operators, runs configured commands.
- Auto-start audit only when players are online; stops when empty.
- Manual `/fop sync` to pull `ops.json` into config.
- `/fop version` shows plugin version.
- Tab-complete for subcommands.

## Commands & Permissions
- `/fop version` — permission `ForceOP.command.version`
- `/fop sync` — permission `ForceOP.command.sync`

## Configuration (`config.json`)
Defaults (shipped resource):
```json
{
  "enabled": true,
  "ops": [],
  "isFirst": true,
  "intervalSeconds": 5,
  "commands": ["kick {player} Unauthorized operator"],
  "AuditLogOutput": true
}
```

- `enabled`: Master switch. When `false`, no audits run.
- `ops`: Array of allowed operator entries. Each entry is an object `{ "uuid": "...", "name": "..." }`.
- `isFirst`: Internal flag; on first start, ops are migrated from the server list, config rewritten, flag set to `false`.
- `intervalSeconds`: Audit interval (seconds). Minimum 1s. Used when players are online.
- `commands`: List of console commands executed when an unauthorized op is found. Placeholder `{player}` is replaced with the offender's name.
- `AuditLogOutput`: When `true`, logs audit results; when `false`, audits are silent (still deops and runs commands).

### Runtime config location
After first launch, the effective config lives in `plugins/ForceOP/config.json`. Edit this file to adjust settings (e.g., interval, commands, AuditLogOutput). Run `/fop sync` to refresh the `ops` list from `ops.json` while preserving other fields.

## Behavior notes
- First start: migrates current ops into `config.json`, clears server op list, sets `isFirst=false`.
- Audit loop: only runs when at least one player is online; stops when server is empty. Checks `ops.json` and current ops; deops unauthorized ops present in `ops.json`; executes `commands` and logs based on `AuditLogOutput`.
- `/fop sync`: Reads `ops.json` (uuid+name) and rewrites the `ops` array, preserving `enabled`, `isFirst`, `intervalSeconds`, `commands`, and `AuditLogOutput`.

## Build
```powershell
./gradlew build
```
The built JAR will be in `build/libs/`.

