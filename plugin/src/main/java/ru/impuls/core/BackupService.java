package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;

/** Small local SQLite backups supplementing host-level full-world backups. */
public final class BackupService implements Listener {
    private final JavaPlugin plugin;
    private final File database;
    private final File backupDir;

    private BackupService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.database = new File(plugin.getDataFolder(), plugin.getConfig().getString("database.file", "impuls.sqlite3"));
        this.backupDir = new File(plugin.getDataFolder(), "backups");
        backupDir.mkdirs();
    }

    public static void start(JavaPlugin plugin) {
        BackupService service = new BackupService(plugin);
        Bukkit.getPluginManager().registerEvents(service, plugin);
        long hours = Math.max(1L, plugin.getConfig().getLong("backups.interval-hours", 6));
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, service::createScheduled, 20L * 300L, 20L * 3600L * hours);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage().trim();
        if (!raw.toLowerCase(Locale.ROOT).startsWith("/impuls backup")) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.hasPermission("impuls.admin")) {
            player.sendMessage(ChatColor.RED + "Требуется impuls.admin.");
            return;
        }
        String[] args = raw.split("\\s+");
        String sub = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "create" -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                File file = createBackup();
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(file == null ? ChatColor.RED + "Backup не создан; смотри консоль." : ChatColor.GREEN + "SQLite backup создан: " + file.getName()));
            });
            case "verify" -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                File latest = latest();
                boolean ok = latest != null && verify(latest);
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(ok ? ChatColor.GREEN + "Последний SQLite backup читается и прошёл PRAGMA integrity_check." : ChatColor.RED + "Нет корректного backup."));
            });
            case "status" -> {
                File latest = latest();
                player.sendMessage(ChatColor.GOLD + "Backup ImPuls: " + ChatColor.GRAY + (latest == null ? "локальных копий ещё нет" : latest.getName() + " | " + latest.length() / 1024 + " KiB"));
                player.sendMessage(ChatColor.GRAY + "Локальная копия защищает SQLite. Полный мир резервируй штатным backup хостинга перед обновлениями.");
            }
            default -> player.sendMessage("/impuls backup status|create|verify");
        }
    }

    private void createScheduled() {
        File file = createBackup();
        if (file != null) plugin.getLogger().info("ImPuls SQLite backup: " + file.getName());
    }

    private File createBackup() {
        if (!database.exists()) return null;
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        File out = new File(backupDir, "impuls-" + stamp + ".sqlite3");
        String escaped = out.getAbsolutePath().replace("'", "''");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + database.getAbsolutePath()); Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=10000");
            s.execute("VACUUM INTO '" + escaped + "'");
        } catch (SQLException e) {
            plugin.getLogger().warning("SQLite backup failed: " + e.getMessage());
            try { Files.deleteIfExists(out.toPath()); } catch (Exception ignored) { }
            return null;
        }
        if (!verify(out)) {
            plugin.getLogger().warning("SQLite backup integrity verification failed: " + out.getName());
            try { Files.deleteIfExists(out.toPath()); } catch (Exception ignored) { }
            return null;
        }
        prune();
        return out;
    }

    private boolean verify(File file) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath()); PreparedStatement ps = c.prepareStatement("PRAGMA integrity_check"); ResultSet rs = ps.executeQuery()) {
            return rs.next() && "ok".equalsIgnoreCase(rs.getString(1));
        } catch (SQLException e) { return false; }
    }

    private File latest() {
        File[] files = backupDir.listFiles((dir, name) -> name.endsWith(".sqlite3"));
        if (files == null || files.length == 0) return null;
        return java.util.Arrays.stream(files).max(Comparator.comparingLong(File::lastModified)).orElse(null);
    }

    private void prune() {
        int keep = Math.max(2, plugin.getConfig().getInt("backups.keep", 8));
        File[] files = backupDir.listFiles((dir, name) -> name.endsWith(".sqlite3"));
        if (files == null || files.length <= keep) return;
        java.util.Arrays.stream(files).sorted(Comparator.comparingLong(File::lastModified).reversed()).skip(keep).forEach(file -> {
            try { Files.deleteIfExists(file.toPath()); } catch (Exception e) { plugin.getLogger().warning("Cannot prune backup " + file.getName()); }
        });
    }
}
