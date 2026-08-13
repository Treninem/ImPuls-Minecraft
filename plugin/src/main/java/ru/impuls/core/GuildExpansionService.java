package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Guild 128x128 bases, scalable boundaries and bilateral alliances. */
public final class GuildExpansionService implements Listener, AutoCloseable {
    private static volatile GuildExpansionService INSTANCE;
    private static final int CX = -688;
    private static final int CZ = -688;

    private final JavaPlugin plugin;
    private final Database db;
    private final Connection connection;

    private GuildExpansionService(JavaPlugin plugin, Database db) throws SQLException {
        this.plugin = plugin;
        this.db = db;
        File file = new File(plugin.getDataFolder(), plugin.getConfig().getString("database.file", "impuls.sqlite3"));
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS guild_bases(guild_id INTEGER PRIMARY KEY,world TEXT NOT NULL,min_x INTEGER NOT NULL,max_x INTEGER NOT NULL,min_y INTEGER NOT NULL,max_y INTEGER NOT NULL,min_z INTEGER NOT NULL,max_z INTEGER NOT NULL,size INTEGER NOT NULL DEFAULT 128,created_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS guild_alliance_invites(from_guild INTEGER NOT NULL,to_guild INTEGER NOT NULL,expires_at INTEGER NOT NULL,PRIMARY KEY(from_guild,to_guild))");
            s.execute("CREATE TABLE IF NOT EXISTS guild_alliances(guild_a INTEGER NOT NULL,guild_b INTEGER NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(guild_a,guild_b))");
        }
    }

    public static void start(JavaPlugin plugin, Database db) {
        try {
            GuildExpansionService service = new GuildExpansionService(plugin, db);
            INSTANCE = service;
            Bukkit.getPluginManager().registerEvents(service, plugin);
        } catch (SQLException e) {
            plugin.getLogger().severe("Guild expansion SQLite init failed: " + e.getMessage());
        }
    }

    public static boolean canBuildGuildBase(Player player, Location location) {
        GuildExpansionService service = INSTANCE;
        return service != null && service.canBuild(player, location);
    }

    public static boolean isGuildBase(Location location) {
        GuildExpansionService service = INSTANCE;
        return service != null && service.baseAt(location) != null;
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage().trim();
        if (!raw.toLowerCase(Locale.ROOT).startsWith("/impuls guild base") && !raw.toLowerCase(Locale.ROOT).startsWith("/impuls guild alliance")) return;
        event.setCancelled(true);
        String[] args = raw.split("\\s+");
        Player player = event.getPlayer();
        if (args.length < 4) {
            player.sendMessage("/impuls guild base buy|info|expand <blocks> | /impuls guild alliance invite|accept|remove <guild>");
            return;
        }
        if ("base".equalsIgnoreCase(args[2])) handleBase(player, args);
        else handleAlliance(player, args);
    }

    private void handleBase(Player player, String[] args) {
        Long gid = db.guildId(player.getUniqueId());
        if (gid == null) { player.sendMessage(ChatColor.RED + "Сначала вступи в гильдию."); return; }
        String sub = args[3].toLowerCase(Locale.ROOT);
        if ("info".equals(sub)) {
            Base base = base(gid);
            player.sendMessage(base == null ? ChatColor.YELLOW + "У гильдии нет базы." : ChatColor.GOLD + "База гильдии: " + base.size + "×" + base.size + " | X " + base.minX + ".." + base.maxX + " Z " + base.minZ + ".." + base.maxZ);
            return;
        }
        if (!"LEADER".equals(db.memberRole(player.getUniqueId()))) {
            player.sendMessage(ChatColor.RED + "Базой управляет глава гильдии.");
            return;
        }
        if ("buy".equals(sub)) {
            buyBase(player, gid);
            return;
        }
        if ("expand".equals(sub) && args.length >= 5) {
            expandBase(player, gid, args[4]);
            return;
        }
        player.sendMessage("/impuls guild base buy|info|expand <blocks>");
    }

    private void buyBase(Player player, long gid) {
        if (base(gid) != null) { player.sendMessage(ChatColor.YELLOW + "База уже существует."); return; }
        Location at = player.getLocation();
        int d = (int) Math.max(Math.abs(at.getX() - CX), Math.abs(at.getZ() - CZ));
        if (d < 300 || d > 850) {
            player.sendMessage(ChatColor.RED + "Гильдейские базы регистрируются внутри внешнего города, но вне королевского центра (радиус 300–850 блоков от центра). ");
            return;
        }
        int size = 128, r = size / 2;
        int minX = at.getBlockX() - r, maxX = minX + size - 1;
        int minZ = at.getBlockZ() - r, maxZ = minZ + size - 1;
        if (overlaps(at.getWorld().getName(), minX, maxX, minZ, maxZ)) {
            player.sendMessage(ChatColor.RED + "Эта зона пересекается с другой гильдейской базой.");
            return;
        }
        int cost = plugin.getConfig().getInt("guild-base.base-cost", 10000);
        boolean ok = tx(() -> {
            if (execute("UPDATE profiles SET coins=coins-? WHERE uuid=? AND coins>=?", cost, player.getUniqueId().toString(), cost) != 1) return false;
            execute("INSERT INTO guild_bases(guild_id,world,min_x,max_x,min_y,max_y,min_z,max_z,size,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    gid, at.getWorld().getName(), minX, maxX, Math.max(at.getWorld().getMinHeight(), at.getBlockY() - 4), Math.min(at.getWorld().getMaxHeight() - 1, at.getBlockY() + 50), minZ, maxZ, size, Instant.now().getEpochSecond());
            return true;
        });
        if (ok) {
            db.audit(player.getUniqueId(), "guild_base_buy", gid + ":" + size);
            player.sendMessage(ChatColor.GREEN + "Гильдейская база 128×128 зарегистрирована за " + cost + " монет.");
        } else player.sendMessage(ChatColor.RED + "Не удалось купить базу.");
    }

    private void expandBase(Player player, long gid, String amountRaw) {
        int blocks;
        try { blocks = Integer.parseInt(amountRaw); } catch (NumberFormatException e) { return; }
        blocks = Math.max(8, Math.min(64, blocks));
        Base base = base(gid);
        if (base == null) { player.sendMessage(ChatColor.RED + "Сначала купи базу."); return; }
        int maxSize = 128 + Math.max(0, guildLevel(gid) - 1) * 32;
        int newSize = base.size + blocks;
        if (newSize > maxSize) {
            player.sendMessage(ChatColor.RED + "Текущий уровень гильдии разрешает максимум " + maxSize + "×" + maxSize + ".");
            return;
        }
        int growLeft = blocks / 2, growRight = blocks - growLeft;
        int minX = base.minX - growLeft, maxX = base.maxX + growRight;
        int minZ = base.minZ - growLeft, maxZ = base.maxZ + growRight;
        if (overlapsExcept(base.world, minX, maxX, minZ, maxZ, gid)) {
            player.sendMessage(ChatColor.RED + "Расширение пересечёт другую базу.");
            return;
        }
        int cost = blocks * plugin.getConfig().getInt("guild-base.expand-cost-per-block", 250);
        boolean ok = tx(() -> {
            if (execute("UPDATE profiles SET coins=coins-? WHERE uuid=? AND coins>=?", cost, player.getUniqueId().toString(), cost) != 1) return false;
            execute("UPDATE guild_bases SET min_x=?,max_x=?,min_z=?,max_z=?,size=? WHERE guild_id=?", minX, maxX, minZ, maxZ, newSize, gid);
            return true;
        });
        player.sendMessage(ok ? ChatColor.GREEN + "База расширена до " + newSize + "×" + newSize + "." : ChatColor.RED + "Недостаточно монет или операция не выполнена.");
    }

    private void handleAlliance(Player player, String[] args) {
        Long gid = db.guildId(player.getUniqueId());
        if (gid == null || !"LEADER".equals(db.memberRole(player.getUniqueId()))) { player.sendMessage(ChatColor.RED + "Требуется глава гильдии."); return; }
        if (args.length < 5) { player.sendMessage("/impuls guild alliance invite|accept|remove <guild>"); return; }
        Long other = db.guildIdByName(String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length)));
        if (other == null || other.equals(gid)) { player.sendMessage(ChatColor.RED + "Гильдия не найдена."); return; }
        String sub = args[3].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "invite" -> {
                update("INSERT INTO guild_alliance_invites(from_guild,to_guild,expires_at) VALUES(?,?,?) ON CONFLICT(from_guild,to_guild) DO UPDATE SET expires_at=excluded.expires_at", gid, other, Instant.now().getEpochSecond() + 86400L);
                player.sendMessage(ChatColor.GREEN + "Предложение союза отправлено гильдии " + db.guildName(other) + ".");
            }
            case "accept" -> {
                int found = intQuery("SELECT COUNT(*) FROM guild_alliance_invites WHERE from_guild=? AND to_guild=? AND expires_at>?", other, gid, Instant.now().getEpochSecond());
                if (found == 0) { player.sendMessage(ChatColor.RED + "Нет действующего предложения."); return; }
                long a = Math.min(gid, other), b = Math.max(gid, other);
                update("INSERT INTO guild_alliances(guild_a,guild_b,created_at) VALUES(?,?,?) ON CONFLICT(guild_a,guild_b) DO NOTHING", a, b, Instant.now().getEpochSecond());
                update("DELETE FROM guild_alliance_invites WHERE (from_guild=? AND to_guild=?) OR (from_guild=? AND to_guild=?)", other, gid, gid, other);
                db.audit(player.getUniqueId(), "guild_alliance", a + ":" + b);
                player.sendMessage(ChatColor.GREEN + "Союз заключён с " + db.guildName(other) + ".");
            }
            case "remove" -> {
                long a = Math.min(gid, other), b = Math.max(gid, other);
                update("DELETE FROM guild_alliances WHERE guild_a=? AND guild_b=?", a, b);
                db.audit(player.getUniqueId(), "guild_alliance_remove", a + ":" + b);
                player.sendMessage(ChatColor.YELLOW + "Союз расторгнут.");
            }
            default -> player.sendMessage("/impuls guild alliance invite|accept|remove <guild>");
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        Base base = baseAt(event.getBlock().getLocation());
        if (base != null && !memberOf(event.getPlayer(), base.guildId)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Территория принадлежит другой гильдии.");
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        Base base = baseAt(event.getBlock().getLocation());
        if (base != null && !memberOf(event.getPlayer(), base.guildId)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Территория принадлежит другой гильдии.");
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onContainer(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Location location = event.getInventory().getLocation();
        if (location == null) return;
        Base base = baseAt(location);
        if (base != null && !memberOf(player, base.guildId)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "Хранилище гильдейской базы защищено.");
        }
    }

    private boolean canBuild(Player player, Location location) {
        Base base = baseAt(location);
        return base != null && memberOf(player, base.guildId);
    }

    private boolean memberOf(Player player, long gid) {
        Long own = db.guildId(player.getUniqueId());
        return player.hasPermission("impuls.admin") || (own != null && own == gid);
    }

    private record Base(long guildId, String world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ, int size) { }
    private Base base(long gid) { return baseQuery("SELECT guild_id,world,min_x,max_x,min_y,max_y,min_z,max_z,size FROM guild_bases WHERE guild_id=?", gid); }
    private Base baseAt(Location l) {
        if (l.getWorld() == null) return null;
        return baseQuery("SELECT guild_id,world,min_x,max_x,min_y,max_y,min_z,max_z,size FROM guild_bases WHERE world=? AND ? BETWEEN min_x AND max_x AND ? BETWEEN min_y AND max_y AND ? BETWEEN min_z AND max_z LIMIT 1", l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }
    private Base baseQuery(String sql, Object... args) {
        try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            return new Base(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9));
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
    private int guildLevel(long gid) { return intQuery("SELECT level FROM guilds WHERE id=?", gid); }
    private boolean overlaps(String world, int minX, int maxX, int minZ, int maxZ) { return intQuery("SELECT COUNT(*) FROM guild_bases WHERE world=? AND NOT(max_x<? OR min_x>? OR max_z<? OR min_z>?)", world, minX, maxX, minZ, maxZ) > 0; }
    private boolean overlapsExcept(String world, int minX, int maxX, int minZ, int maxZ, long gid) { return intQuery("SELECT COUNT(*) FROM guild_bases WHERE guild_id<>? AND world=? AND NOT(max_x<? OR min_x>? OR max_z<? OR min_z>?)", gid, world, minX, maxX, minZ, maxZ) > 0; }
    private int intQuery(String sql, Object... args) {
        try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        catch (SQLException e) { throw new RuntimeException(e); }
    }
    private PreparedStatement prepare(String sql, Object... args) throws SQLException { PreparedStatement ps = connection.prepareStatement(sql); for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]); return ps; }
    private void update(String sql, Object... args) { try (PreparedStatement ps = prepare(sql, args)) { ps.executeUpdate(); } catch (SQLException e) { throw new RuntimeException(e); } }
    private int execute(String sql, Object... args) throws SQLException { try (PreparedStatement ps = prepare(sql, args)) { return ps.executeUpdate(); } }
    private interface TxBody { boolean run() throws Exception; }
    private boolean tx(TxBody body) {
        try { connection.setAutoCommit(false); boolean ok = body.run(); if (ok) connection.commit(); else connection.rollback(); connection.setAutoCommit(true); return ok; }
        catch (Exception e) { try { connection.rollback(); } catch (SQLException ignored) {} try { connection.setAutoCommit(true); } catch (SQLException ignored) {} return false; }
    }

    @Override public void close() { try { connection.close(); } catch (SQLException ignored) {} }
}
