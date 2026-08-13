package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/** Persistent public rankings plus low-cost TPS/RAM/entity diagnostics for operators. */
public final class LeaderboardDiagnosticsService implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final Connection connection;

    private LeaderboardDiagnosticsService(JavaPlugin plugin) throws SQLException {
        this.plugin = plugin;
        File file = new File(plugin.getDataFolder(), plugin.getConfig().getString("database.file", "impuls.sqlite3"));
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (var s = connection.createStatement()) { s.execute("PRAGMA busy_timeout=5000"); }
    }

    public static void start(JavaPlugin plugin) {
        try {
            LeaderboardDiagnosticsService service = new LeaderboardDiagnosticsService(plugin);
            Bukkit.getPluginManager().registerEvents(service, plugin);
            Bukkit.getScheduler().runTaskTimer(plugin, service::healthWatch, 20L * 300L, 20L * 300L);
        } catch (SQLException e) {
            plugin.getLogger().severe("Leaderboard diagnostics SQLite init failed: " + e.getMessage());
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage().trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("/impuls top") || lower.startsWith("/impuls diag"))) return;
        event.setCancelled(true);
        if (lower.startsWith("/impuls diag")) {
            if (!event.getPlayer().hasPermission("impuls.admin")) {
                event.getPlayer().sendMessage(ChatColor.RED + "Требуется impuls.admin.");
                return;
            }
            diagnostics(event.getPlayer());
            return;
        }
        String[] args = raw.split("\\s+");
        String kind = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "defender";
        top(event.getPlayer(), kind);
    }

    private void top(Player player, String kind) {
        String column = switch (kind) {
            case "coins", "money" -> "coins";
            case "dungeons" -> "dungeons";
            case "quests" -> "quests";
            case "xp" -> "xp";
            case "rank" -> "rank";
            default -> "defender_score";
        };
        player.sendMessage(ChatColor.GOLD + "════ TOP ImPuls: " + column + " ════");
        String sql = "SELECT name," + column + " FROM profiles ORDER BY " + column + " DESC,name ASC LIMIT 10";
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            int pos = 0;
            while (rs.next()) {
                pos++;
                player.sendMessage(ChatColor.YELLOW + "#" + pos + ChatColor.WHITE + " " + rs.getString(1) + ChatColor.GRAY + " — " + rs.getLong(2));
            }
            if (pos == 0) player.sendMessage(ChatColor.GRAY + "Пока нет данных.");
        } catch (SQLException e) {
            player.sendMessage(ChatColor.RED + "Не удалось прочитать рейтинг.");
        }
    }

    private void diagnostics(Player player) {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1024L / 1024L;
        long maxMb = rt.maxMemory() / 1024L / 1024L;
        long freeMb = Math.max(0, maxMb - usedMb);
        int entities = 0, chunks = 0, worlds = 0;
        for (World world : Bukkit.getWorlds()) {
            worlds++;
            entities += world.getEntities().size();
            chunks += world.getLoadedChunks().length;
        }
        player.sendMessage(ChatColor.GOLD + "════ ImPuls diagnostics ════");
        player.sendMessage(ChatColor.GRAY + "RAM: " + ChatColor.WHITE + usedMb + "/" + maxMb + " MB" + ChatColor.GRAY + " | свободно ≈ " + freeMb + " MB");
        player.sendMessage(ChatColor.GRAY + "Миры: " + worlds + " | загруженные чанки: " + chunks + " | сущности: " + entities + " | игроков: " + Bukkit.getOnlinePlayers().size());
        try {
            double[] tps = Bukkit.getTPS();
            player.sendMessage(ChatColor.GRAY + String.format(Locale.US, "TPS 1m/5m/15m: %.2f / %.2f / %.2f", tps[0], tps[1], tps[2]));
        } catch (Throwable ignored) {
            player.sendMessage(ChatColor.GRAY + "TPS API недоступен в этой сборке; используй /tps Paper.");
        }
    }

    private void healthWatch() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        if (max <= 0) return;
        double ratio = used / (double) max;
        if (ratio < 0.88d) return;
        String warning = String.format(Locale.US, "ImPuls memory warning: %.0f%% JVM heap used", ratio * 100d);
        plugin.getLogger().warning(warning);
        for (Player player : Bukkit.getOnlinePlayers()) if (player.hasPermission("impuls.admin")) player.sendMessage(ChatColor.RED + "[ImPuls] " + warning);
    }

    @Override
    public void close() { try { connection.close(); } catch (SQLException ignored) { } }
}
