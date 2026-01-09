package space.yumian.forceOP;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class ForceOP extends JavaPlugin implements Listener {

    private final Gson gson = new Gson();
    private int auditTaskId = -1;
    private File configFile;

    @Override
    public void onEnable() {
        final File dataFolder = getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            getLogger().warning("Could not create plugin data folder; config.json may not persist.");
            return;
        }

        this.configFile = new File(dataFolder, "config.json");
        if (!configFile.exists()) {
            saveResource("config.json", false);
            getLogger().info("Created default config.json in plugin data folder.");
        } else {
            getLogger().info("config.json found; using existing configuration.");
        }

        final JsonObject config = readConfig(configFile);
        final boolean isFirst = !config.has("isFirst") || config.get("isFirst").getAsBoolean();
        if (isFirst) {
            getServer().getScheduler().runTaskAsynchronously(this, () -> migrateOperatorsAsync(configFile, config));
        }

        getServer().getPluginManager().registerEvents(this, this);

        registerFopCommand();
        startAuditTask();
        getLogger().info("ForceOP enabled. plugin by Yumian (https://blog.yumian.space).");
    }

    private void startAuditTask() {
        if (auditTaskId != -1) {
            getServer().getScheduler().cancelTask(auditTaskId);
        }
        if (getServer().getOnlinePlayers().isEmpty()) {
            getLogger().info("No players online; audit checker disabled.");
            return;
        }
        final JsonObject config = readConfig(configFile);
        final boolean enabled = !config.has("enabled") || config.get("enabled").getAsBoolean();
        if (!enabled) {
            return;
        }
        final int intervalSeconds = config.has("intervalSeconds") ? Math.max(1, config.get("intervalSeconds").getAsInt()) : 5;
        getLogger().info("Players online; starting audit checker every " + intervalSeconds + "s.");
        auditTaskId = getServer().getScheduler().runTaskTimerAsynchronously(
                this,
                () -> auditOperators(configFile),
                intervalSeconds * 20L,
                intervalSeconds * 20L
        ).getTaskId();
    }

    private void auditOperators(File configFile) {
        final JsonObject config = readConfig(configFile);
        final boolean auditLogOutput = !config.has("AuditLogOutput") || config.get("AuditLogOutput").getAsBoolean();
        final JsonArray configuredOps = config.has("ops") && config.get("ops").isJsonArray()
                ? config.getAsJsonArray("ops")
                : new JsonArray();
        final Set<String> allowedNames = new HashSet<>();
        final Set<UUID> allowedUuids = new HashSet<>();
        for (JsonElement el : configuredOps) {
            if (!el.isJsonObject()) {
                continue;
            }
            final JsonObject opObj = el.getAsJsonObject();
            if (opObj.has("name")) {
                allowedNames.add(opObj.get("name").getAsString().toLowerCase(Locale.ROOT));
            }
            if (opObj.has("uuid")) {
                try {
                    allowedUuids.add(UUID.fromString(opObj.get("uuid").getAsString()));
                } catch (IllegalArgumentException ignored) {
                    // skip invalid uuid
                }
            }
        }

        final File opsJson = new File(Bukkit.getWorldContainer(), "ops.json");
        final Set<String> serverOps = new HashSet<>();
        final Set<UUID> serverOpUuids = new HashSet<>();
        try (FileReader reader = new FileReader(opsJson)) {
            final JsonArray opsFileArray = gson.fromJson(reader, JsonArray.class);
            if (opsFileArray != null) {
                for (JsonElement element : opsFileArray) {
                    if (!element.isJsonObject()) continue;
                    final JsonObject opObj = element.getAsJsonObject();
                    if (opObj.has("name")) {
                        serverOps.add(opObj.get("name").getAsString());
                    }
                    if (opObj.has("uuid")) {
                        try {
                            serverOpUuids.add(UUID.fromString(opObj.get("uuid").getAsString()));
                        } catch (IllegalArgumentException ignored) {
                            // skip invalid uuid
                        }
                    }
                }
            }
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Failed to read ops.json during audit", ex);
            return;
        }

        boolean unauthorizedFound = false;
        for (OfflinePlayer op : Bukkit.getOperators()) {
            final String name = op.getName();
            final UUID uuid = op.getUniqueId();
            final boolean allowed = (name != null && allowedNames.contains(name.toLowerCase(Locale.ROOT)))
                    || (uuid != null && allowedUuids.contains(uuid));
            if (allowed) {
                continue;
            }
            final boolean existsInOpsJson = (name != null && serverOps.contains(name))
                    || (uuid != null && serverOpUuids.contains(uuid));
            if (!existsInOpsJson) {
                continue;
            }
            unauthorizedFound = true;
            Bukkit.getScheduler().runTask(this, () -> {
                if (auditLogOutput && name != null) {
                    getLogger().warning("Unauthorized operator found; permissions revoked: " + name);
                }
                op.setOp(false);
                executeConfiguredCommands(config, name);
            });
        }
        if (!unauthorizedFound && auditLogOutput) {
            getLogger().info("Audit complete; no risky players found.");
        }
    }

    private void executeConfiguredCommands(JsonObject config, String playerName) {
        if (playerName == null) {
            return;
        }
        final ConsoleCommandSender console = Bukkit.getServer().getConsoleSender();
        final JsonArray commands = config.has("commands") && config.get("commands").isJsonArray()
                ? config.getAsJsonArray("commands")
                : new JsonArray();
        for (JsonElement el : commands) {
            if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
                continue;
            }
            final String raw = el.getAsString();
            final String cmd = raw.replace("{player}", playerName);
            Bukkit.dispatchCommand(console, cmd);
        }
    }

    private void registerFopCommand() {
        try {
            final Method getCommandMapMethod = Bukkit.getServer().getClass().getMethod("getCommandMap");
            final CommandMap commandMap = (CommandMap) getCommandMapMethod.invoke(Bukkit.getServer());
            final Command fop = new Command("fop", "ForceOP commands", "/fop version", Collections.emptyList()) {
                @Override
                public boolean execute(@NonNull CommandSender sender, @NonNull String label, String @NonNull [] args) {
                    if (args.length == 1 && args[0].equalsIgnoreCase("version")) {
                        if (!sender.hasPermission("ForceOP.command.version")) {
                            sender.sendMessage("You do not have permission to use this command.");
                            return true;
                        }
                        sender.sendMessage("ForceOP version: " + ForceOP.this.getDescription().getVersion());
                        return true;
                    }
                    if (args.length == 1 && args[0].equalsIgnoreCase("sync")) {
                        if (!sender.hasPermission("ForceOP.command.sync")) {
                            sender.sendMessage("You do not have permission to use this command.");
                            return true;
                        }
                        syncOpsFromOpsJson(sender);
                        return true;
                    }
                    sender.sendMessage("Usage: /fop version|sync");
                    return true;
                }

                @Override
                public @NonNull List<String> tabComplete(@NonNull CommandSender sender, @NonNull String alias, String @NonNull [] args) {
                    if (args.length == 1) {
                        final String prefix = args[0].toLowerCase(Locale.ROOT);
                        final JsonArray suggestions = new JsonArray();
                        if (sender.hasPermission("ForceOP.command.version") && "version".startsWith(prefix)) {
                            suggestions.add("version");
                        }
                        if (sender.hasPermission("ForceOP.command.sync") && "sync".startsWith(prefix)) {
                            suggestions.add("sync");
                        }
                        return suggestions.asList().stream().map(JsonElement::getAsString).toList();
                    }
                    return Collections.emptyList();
                }
            };
            fop.setPermission("ForceOP.command.version");
            commandMap.register("forceop", fop);
            getLogger().info("Registered /fop command programmatically for Paper.");
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Failed to register /fop command", ex);
        }
    }

    private JsonObject readConfig(File configFile) {
        try (FileReader reader = new FileReader(configFile)) {
            final JsonObject parsed = gson.fromJson(reader, JsonObject.class);
            return parsed != null ? parsed : new JsonObject();
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Failed to read config.json; using defaults.", ex);
            return new JsonObject();
        }
    }

    private void writeConfig(File configFile, JsonObject content) {
        try (FileWriter writer = new FileWriter(configFile)) {
            gson.toJson(content, writer);
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Failed to write config.json.", ex);
        }
    }

    private void syncOpsFromOpsJson(CommandSender sender) {
        final File configFile = new File(getDataFolder(), "config.json");
        final File opsJson = new File(Bukkit.getWorldContainer(), "ops.json");
        if (!opsJson.exists()) {
            sender.sendMessage("ops.json not found.");
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            final JsonObject config = readConfig(configFile);
            final JsonArray newOps = new JsonArray();
            try (FileReader reader = new FileReader(opsJson)) {
                final JsonArray opsFileArray = gson.fromJson(reader, JsonArray.class);
                if (opsFileArray != null) {
                    for (JsonElement element : opsFileArray) {
                        if (!element.isJsonObject()) {
                            continue;
                        }
                        final JsonObject opObj = element.getAsJsonObject();
                        final JsonObject target = new JsonObject();
                        if (opObj.has("uuid") && opObj.has("name")) {
                            target.addProperty("uuid", opObj.get("uuid").getAsString());
                            target.addProperty("name", opObj.get("name").getAsString());
                            newOps.add(target);
                        }
                    }
                }
            } catch (IOException ex) {
                getLogger().log(Level.WARNING, "Failed to read ops.json", ex);
                Bukkit.getScheduler().runTask(this, () -> sender.sendMessage("Failed to read ops.json: " + ex.getMessage()));
                return;
            }

            final JsonObject newConfig = new JsonObject();
            if (config.has("enabled")) {
                newConfig.add("enabled", config.get("enabled"));
            }
            if (config.has("isFirst")) {
                newConfig.add("isFirst", config.get("isFirst"));
            }
            if (config.has("intervalSeconds")) {
                newConfig.add("intervalSeconds", config.get("intervalSeconds"));
            }
            if (config.has("commands")) {
                newConfig.add("commands", config.get("commands"));
            }
            if (config.has("AuditLogOutput")) {
                newConfig.add("AuditLogOutput", config.get("AuditLogOutput"));
            } else {
                newConfig.addProperty("AuditLogOutput", true);
            }
            newConfig.add("ops", newOps);

            writeConfig(configFile, newConfig);
            Bukkit.getScheduler().runTask(this, () -> sender.sendMessage("Synced ops.json into config.json."));
        });
    }

    private void migrateOperatorsAsync(File configFile, JsonObject existingConfig) {
        final JsonObject newConfig = new JsonObject();
        final JsonArray opsArray = new JsonArray();

        // Clear existing config and rebuild with enabled flag (if it was present) plus merged ops.
        if (existingConfig.has("enabled")) {
            newConfig.addProperty("enabled", existingConfig.get("enabled").getAsBoolean());
        } else {
            newConfig.addProperty("enabled", true);
        }

        for (OfflinePlayer op : Bukkit.getOperators()) {
            if (op.getName() == null) {
                continue;
            }
            final JsonObject entry = new JsonObject();
            entry.addProperty("uuid", op.getUniqueId().toString());
            entry.addProperty("name", op.getName());
            opsArray.add(entry);
        }

        // Preserve interval and commands if present
        if (existingConfig.has("intervalSeconds")) {
            newConfig.add("intervalSeconds", existingConfig.get("intervalSeconds"));
        }
        if (existingConfig.has("commands")) {
            newConfig.add("commands", existingConfig.get("commands"));
        }
        if (existingConfig.has("AuditLogOutput")) {
            newConfig.add("AuditLogOutput", existingConfig.get("AuditLogOutput"));
        } else {
            newConfig.addProperty("AuditLogOutput", true);
        }

        newConfig.add("ops", opsArray);
        newConfig.addProperty("isFirst", false);

        writeConfig(configFile, newConfig);

        for (OfflinePlayer op : Bukkit.getOperators()) {
            op.setOp(false);
        }
        getLogger().info("Merged server operators into config.json, cleared server op list, and set isFirst to false.");
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (auditTaskId == -1) {
            startAuditTask();
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (getServer().getOnlinePlayers().isEmpty() && auditTaskId != -1) {
            getServer().getScheduler().cancelTask(auditTaskId);
            auditTaskId = -1;
            getLogger().info("No players online; audit checker disabled.");
        }
    }
}
