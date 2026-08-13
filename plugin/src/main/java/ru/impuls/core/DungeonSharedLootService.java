package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Converts the legacy per-member dungeon trophy into one persistent group loot pool distributed by vote. */
public final class DungeonSharedLootService implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final Database db;
    private final Connection connection;
    private final Map<Long, Map<UUID, Integer>> baseline = new HashMap<>();

    private DungeonSharedLootService(JavaPlugin plugin, Database db) throws SQLException {
        this.plugin = plugin;
        this.db = db;
        File file = new File(plugin.getDataFolder(), plugin.getConfig().getString("database.file", "impuls.sqlite3"));
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS dungeon_loot_pools(run_id INTEGER PRIMARY KEY,leader_uuid TEXT NOT NULL,item_data TEXT NOT NULL,rank INTEGER NOT NULL,state TEXT NOT NULL DEFAULT 'VOTING',winner_uuid TEXT,created_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS dungeon_loot_votes(run_id INTEGER NOT NULL,voter_uuid TEXT NOT NULL,target_uuid TEXT NOT NULL,PRIMARY KEY(run_id,voter_uuid))");
            s.execute("CREATE TABLE IF NOT EXISTS dungeon_loot_deliveries(run_id INTEGER PRIMARY KEY,winner_uuid TEXT NOT NULL,item_data TEXT NOT NULL,claimed INTEGER NOT NULL DEFAULT 0)");
        }
    }

    public static void start(JavaPlugin plugin, Database db) {
        try {
            DungeonSharedLootService service = new DungeonSharedLootService(plugin, db);
            Bukkit.getPluginManager().registerEvents(service, plugin);
        } catch (SQLException e) {
            plugin.getLogger().severe("Dungeon shared loot SQLite init failed: " + e.getMessage());
        }
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        Long runId = runId(entity);
        if (runId == null || entity.getCustomName() == null || !ChatColor.stripColor(entity.getCustomName()).startsWith("Босс подземелья")) return;
        Database.DungeonRun run = db.dungeonRun(runId);
        if (run == null || !"ACTIVE".equals(run.state()) || run.currentFloor() < run.floors()) return;
        RankTier rank = RankTier.fromIndex(run.rank());
        String display = trophyName(rank);
        Map<UUID, Integer> before = new HashMap<>();
        for (UUID uuid : db.dungeonMembers(runId)) {
            Player player = Bukkit.getPlayer(uuid);
            before.put(uuid, player == null ? 0 : countNamed(player, display));
        }
        baseline.put(runId, before);
        Bukkit.getScheduler().runTaskLater(plugin, () -> consolidate(runId), 4L);
    }

    private void consolidate(long runId) {
        Database.DungeonRun run = db.dungeonRun(runId);
        Map<UUID, Integer> before = baseline.remove(runId);
        if (run == null || !"SUCCESS".equals(run.state()) || before == null) return;
        RankTier rank = RankTier.fromIndex(run.rank());
        String display = trophyName(rank);
        for (UUID uuid : db.dungeonMembers(runId)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            int gained = Math.max(0, countNamed(player, display) - before.getOrDefault(uuid, 0));
            if (gained > 0) removeNamed(player, display, gained);
        }
        ItemStack shared = trophy(rank);
        String data = InventoryCodec.encode(new ItemStack[]{shared});
        update("INSERT INTO dungeon_loot_pools(run_id,leader_uuid,item_data,rank,state,created_at) VALUES(?,?,?,?,'VOTING',?) ON CONFLICT(run_id) DO NOTHING",
                runId, run.leader().toString(), data, run.rank(), Instant.now().getEpochSecond());
        db.audit(run.leader(), "dungeon_shared_loot", "run=" + runId + ":rank=" + rank.display());
        for (UUID uuid : db.dungeonMembers(runId)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendMessage(ChatColor.LIGHT_PURPLE + "[ImPuls] Общий трофей подземелья " + rank.display() + " не размножен на группу. Голосуй: /impuls loot vote <игрок>. Глава завершает: /impuls loot award");
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> autoAward(runId), 20L * 120L);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage().trim();
        if (!raw.toLowerCase(Locale.ROOT).startsWith("/impuls loot")) return;
        event.setCancelled(true);
        String[] args = raw.split("\\s+");
        Player player = event.getPlayer();
        if (args.length < 3 || "status".equalsIgnoreCase(args[2])) {
            Pool pool = latestPool(player.getUniqueId());
            if (pool == null) player.sendMessage(ChatColor.YELLOW + "Нет активного голосования за трофей.");
            else player.sendMessage(ChatColor.LIGHT_PURPLE + "Трофей run #" + pool.runId + ": " + votesText(pool.runId));
            return;
        }
        if ("vote".equalsIgnoreCase(args[2])) {
            if (args.length < 4) { player.sendMessage("/impuls loot vote <player>"); return; }
            vote(player, args[3]);
            return;
        }
        if ("award".equalsIgnoreCase(args[2])) {
            Pool pool = latestPool(player.getUniqueId());
            if (pool == null || !pool.leader.equals(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "Только глава активной группы может завершить это голосование.");
                return;
            }
            award(pool.runId, null);
        }
    }

    private void vote(Player voter, String targetName) {
        Pool pool = latestPool(voter.getUniqueId());
        if (pool == null) { voter.sendMessage(ChatColor.RED + "Нет активного общего трофея."); return; }
        Player target = Bukkit.getPlayerExact(targetName);
        UUID targetId = target == null ? memberUuidByName(pool.runId, targetName) : target.getUniqueId();
        if (targetId == null || !db.dungeonMembers(pool.runId).contains(targetId)) {
            voter.sendMessage(ChatColor.RED + "Голосовать можно только за участника этого прохождения.");
            return;
        }
        update("INSERT INTO dungeon_loot_votes(run_id,voter_uuid,target_uuid) VALUES(?,?,?) ON CONFLICT(run_id,voter_uuid) DO UPDATE SET target_uuid=excluded.target_uuid", pool.runId, voter.getUniqueId().toString(), targetId.toString());
        db.audit(voter.getUniqueId(), "dungeon_loot_vote", pool.runId + ":" + targetId);
        voter.sendMessage(ChatColor.GREEN + "Голос принят. " + votesText(pool.runId));
    }

    private void autoAward(long runId) {
        Pool pool = pool(runId);
        if (pool != null && "VOTING".equals(pool.state)) award(runId, null);
    }

    private void award(long runId, UUID forced) {
        Pool pool = pool(runId);
        if (pool == null || !"VOTING".equals(pool.state)) return;
        UUID winner = forced != null ? forced : winner(runId, pool.leader);
        if (winner == null) winner = pool.leader;
        int changed = updateCount("UPDATE dungeon_loot_pools SET state='AWARDED',winner_uuid=? WHERE run_id=? AND state='VOTING'", winner.toString(), runId);
        if (changed != 1) return;
        update("INSERT INTO dungeon_loot_deliveries(run_id,winner_uuid,item_data,claimed) VALUES(?,?,?,0) ON CONFLICT(run_id) DO NOTHING", runId, winner.toString(), pool.itemData);
        db.audit(pool.leader, "dungeon_loot_award", runId + ":" + winner);
        Player online = Bukkit.getPlayer(winner);
        if (online != null) deliver(online);
        for (UUID uuid : db.dungeonMembers(runId)) {
            Player member = Bukkit.getPlayer(uuid);
            if (member != null) member.sendMessage(ChatColor.GOLD + "[ImPuls] Голосование за общий трофей завершено. Получатель: " + playerName(winner) + ".");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) { deliver(event.getPlayer()); }

    private void deliver(Player player) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT run_id,item_data FROM dungeon_loot_deliveries WHERE winner_uuid=? AND claimed=0 ORDER BY run_id")) {
            ps.setString(1, player.getUniqueId().toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long runId = rs.getLong(1);
                    ItemStack[] items = InventoryCodec.decode(rs.getString(2));
                    for (ItemStack item : items) if (item != null && !item.getType().isAir()) player.getInventory().addItem(item).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
                    update("UPDATE dungeon_loot_deliveries SET claimed=1 WHERE run_id=?", runId);
                    player.sendMessage(ChatColor.GREEN + "[ImPuls] Получен общий трофей подземелья #" + runId + ".");
                }
            }
        } catch (SQLException e) { plugin.getLogger().warning("Dungeon loot delivery failed: " + e.getMessage()); }
    }

    private UUID winner(long runId, UUID leader) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT target_uuid,COUNT(*) c FROM dungeon_loot_votes WHERE run_id=? GROUP BY target_uuid ORDER BY c DESC,target_uuid ASC")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return leader;
                String first = rs.getString(1); int max = rs.getInt(2);
                if (!rs.next() || rs.getInt(2) < max) return UUID.fromString(first);
                String leaderVote = stringQuery("SELECT target_uuid FROM dungeon_loot_votes WHERE run_id=? AND voter_uuid=?", runId, leader.toString());
                return leaderVote == null ? UUID.fromString(first) : UUID.fromString(leaderVote);
            }
        } catch (SQLException e) { return leader; }
    }

    private String votesText(long runId) {
        StringBuilder out = new StringBuilder("Голоса: ");
        try (PreparedStatement ps = connection.prepareStatement("SELECT target_uuid,COUNT(*) c FROM dungeon_loot_votes WHERE run_id=? GROUP BY target_uuid ORDER BY c DESC")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                boolean any = false;
                while (rs.next()) { if (any) out.append(", "); any = true; UUID id = UUID.fromString(rs.getString(1)); out.append(playerName(id)).append('=').append(rs.getInt(2)); }
                if (!any) out.append("пока нет");
            }
        } catch (SQLException e) { out.append("ошибка"); }
        return out.toString();
    }

    private record Pool(long runId, UUID leader, String itemData, String state) { }
    private Pool latestPool(UUID member) {
        for (long id = latestRunId(); id > Math.max(0, latestRunId() - 100); id--) {
            Pool p = pool(id);
            if (p != null && "VOTING".equals(p.state) && db.dungeonMembers(id).contains(member)) return p;
        }
        return null;
    }
    private long latestRunId() {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COALESCE(MAX(run_id),0) FROM dungeon_loot_pools"); ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
        catch (SQLException e) { return 0; }
    }
    private Pool pool(long runId) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT run_id,leader_uuid,item_data,state FROM dungeon_loot_pools WHERE run_id=?")) {
            ps.setLong(1, runId); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? new Pool(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4)) : null; }
        } catch (SQLException e) { return null; }
    }

    private Long runId(Entity entity) {
        for (String tag : entity.getScoreboardTags()) if (tag.startsWith("impuls_drun_")) try { return Long.parseLong(tag.substring("impuls_drun_".length())); } catch (NumberFormatException ignored) { return null; }
        return null;
    }
    private ItemStack trophy(RankTier rank) {
        Material material = trophyMaterial(rank); int amount = Math.min(material.getMaxStackSize(), 1 + rank.index() / 2);
        ItemStack item = new ItemStack(material, amount); ItemMeta meta = item.getItemMeta(); meta.setDisplayName(ChatColor.LIGHT_PURPLE + trophyName(rank)); item.setItemMeta(meta); return item;
    }
    private Material trophyMaterial(RankTier rank) { return switch (rank) { case H, G, F -> Material.IRON_INGOT; case E, D, C -> Material.GOLD_INGOT; case B, A -> Material.DIAMOND; case S, SS -> Material.NETHERITE_SCRAP; case SSS, SSS_PLUS -> Material.ECHO_SHARD; }; }
    private String trophyName(RankTier rank) { return "Трофей подземелья " + rank.display(); }
    private int countNamed(Player player, String display) { int c = 0; for (ItemStack item : player.getInventory().getContents()) if (named(item, display)) c += item.getAmount(); return c; }
    private void removeNamed(Player player, String display, int amount) { for (int slot = 0; slot < player.getInventory().getSize() && amount > 0; slot++) { ItemStack item = player.getInventory().getItem(slot); if (!named(item, display)) continue; int take = Math.min(amount, item.getAmount()); item.setAmount(item.getAmount() - take); amount -= take; player.getInventory().setItem(slot, item.getAmount() <= 0 ? null : item); } }
    private boolean named(ItemStack item, String display) { return item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName() && display.equals(ChatColor.stripColor(item.getItemMeta().getDisplayName())); }
    private UUID memberUuidByName(long runId, String name) { for (UUID id : db.dungeonMembers(runId)) if (playerName(id).equalsIgnoreCase(name)) return id; return null; }
    private String playerName(UUID id) { Player p = Bukkit.getPlayer(id); if (p != null) return p.getName(); String n = stringQuery("SELECT name FROM profiles WHERE uuid=?", id.toString()); return n == null ? id.toString().substring(0, 8) : n; }
    private String stringQuery(String sql, Object... args) { try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; } catch (SQLException e) { return null; } }
    private PreparedStatement prepare(String sql, Object... args) throws SQLException { PreparedStatement ps = connection.prepareStatement(sql); for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]); return ps; }
    private void update(String sql, Object... args) { updateCount(sql, args); }
    private int updateCount(String sql, Object... args) { try (PreparedStatement ps = prepare(sql, args)) { return ps.executeUpdate(); } catch (SQLException e) { throw new RuntimeException(e); } }

    @Override public void close() { try { connection.close(); } catch (SQLException ignored) { } }
}
