package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Controlled professions and resource buyback with persistent anti-loop protection and daily caps. */
public final class ProfessionEconomyService implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final Database db;
    private final Connection connection;

    private static final Map<Material, Integer> BREAK_REWARD = Map.ofEntries(
            Map.entry(Material.COAL_ORE, 1), Map.entry(Material.DEEPSLATE_COAL_ORE, 1),
            Map.entry(Material.COPPER_ORE, 1), Map.entry(Material.DEEPSLATE_COPPER_ORE, 1),
            Map.entry(Material.IRON_ORE, 2), Map.entry(Material.DEEPSLATE_IRON_ORE, 2),
            Map.entry(Material.GOLD_ORE, 3), Map.entry(Material.DEEPSLATE_GOLD_ORE, 3),
            Map.entry(Material.REDSTONE_ORE, 2), Map.entry(Material.DEEPSLATE_REDSTONE_ORE, 2),
            Map.entry(Material.LAPIS_ORE, 3), Map.entry(Material.DEEPSLATE_LAPIS_ORE, 3),
            Map.entry(Material.DIAMOND_ORE, 8), Map.entry(Material.DEEPSLATE_DIAMOND_ORE, 8),
            Map.entry(Material.EMERALD_ORE, 10), Map.entry(Material.DEEPSLATE_EMERALD_ORE, 10),
            Map.entry(Material.OAK_LOG, 1), Map.entry(Material.SPRUCE_LOG, 1), Map.entry(Material.BIRCH_LOG, 1),
            Map.entry(Material.JUNGLE_LOG, 1), Map.entry(Material.ACACIA_LOG, 1), Map.entry(Material.DARK_OAK_LOG, 1),
            Map.entry(Material.MANGROVE_LOG, 1), Map.entry(Material.CHERRY_LOG, 1), Map.entry(Material.PALE_OAK_LOG, 1));

    private static final Map<Material, Integer> BUYBACK = Map.ofEntries(
            Map.entry(Material.COBBLESTONE, 1), Map.entry(Material.COBBLED_DEEPSLATE, 1),
            Map.entry(Material.OAK_LOG, 2), Map.entry(Material.SPRUCE_LOG, 2), Map.entry(Material.BIRCH_LOG, 2),
            Map.entry(Material.WHEAT, 1), Map.entry(Material.CARROT, 1), Map.entry(Material.POTATO, 1),
            Map.entry(Material.COAL, 2), Map.entry(Material.COPPER_INGOT, 3), Map.entry(Material.IRON_INGOT, 5),
            Map.entry(Material.GOLD_INGOT, 8), Map.entry(Material.REDSTONE, 2), Map.entry(Material.LAPIS_LAZULI, 3));

    private ProfessionEconomyService(JavaPlugin plugin, Database db) throws SQLException {
        this.plugin = plugin;
        this.db = db;
        File file = new File(plugin.getDataFolder(), plugin.getConfig().getString("database.file", "impuls.sqlite3"));
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS profession_daily(uuid TEXT NOT NULL,day INTEGER NOT NULL,earned INTEGER NOT NULL DEFAULT 0,sold INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(uuid,day))");
            s.execute("CREATE TABLE IF NOT EXISTS profession_placed_blocks(world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,placed_by TEXT NOT NULL,placed_at INTEGER NOT NULL,PRIMARY KEY(world,x,y,z))");
        }
    }

    public static void start(JavaPlugin plugin, Database db) {
        try {
            ProfessionEconomyService service = new ProfessionEconomyService(plugin, db);
            Bukkit.getPluginManager().registerEvents(service, plugin);
        } catch (SQLException e) {
            plugin.getLogger().severe("Profession economy SQLite init failed: " + e.getMessage());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent event) {
        if (!BREAK_REWARD.containsKey(event.getBlockPlaced().getType())) return;
        Block b = event.getBlockPlaced();
        update("INSERT INTO profession_placed_blocks(world,x,y,z,placed_by,placed_at) VALUES(?,?,?,?,?,?) ON CONFLICT(world,x,y,z) DO UPDATE SET placed_by=excluded.placed_by,placed_at=excluded.placed_at",
                b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), event.getPlayer().getUniqueId().toString(), Instant.now().getEpochSecond());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) {
        if (event.getPlayer().getGameMode() != org.bukkit.GameMode.SURVIVAL) return;
        Integer reward = BREAK_REWARD.get(event.getBlock().getType());
        if (reward == null) return;
        if (consumePlayerPlaced(event.getBlock())) return;
        rewardActivity(event.getPlayer(), reward, "profession_mining");
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || !(event.getEntity() instanceof Monster) || killer.getGameMode() != org.bukkit.GameMode.SURVIVAL) return;
        if (event.getEntity().getScoreboardTags().stream().anyMatch(t -> t.startsWith("impuls_"))) return;
        rewardActivity(killer, 2, "profession_hunting");
    }

    @EventHandler
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || event.getPlayer().getGameMode() != org.bukkit.GameMode.SURVIVAL) return;
        rewardActivity(event.getPlayer(), 3, "profession_fishing");
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage().trim();
        if (!raw.toLowerCase(Locale.ROOT).startsWith("/impuls work") && !raw.toLowerCase(Locale.ROOT).startsWith("/impuls sellserver")) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        String[] args = raw.split("\\s+");
        if (args[1].equalsIgnoreCase("work")) {
            Daily daily = daily(player.getUniqueId());
            int cap = plugin.getConfig().getInt("professions.daily-activity-cap", 500);
            int saleCap = plugin.getConfig().getInt("professions.daily-buyback-cap", 1000);
            player.sendMessage(ChatColor.GOLD + "Работы ImPuls: " + ChatColor.GRAY + "сегодня заработано за активность " + daily.earned + "/" + cap + ", продажа серверу " + daily.sold + "/" + saleCap + ".");
            player.sendMessage(ChatColor.GRAY + "Профессии: шахтёр/лесоруб, охотник и рыбак. Поставленные игроками руды/брёвна повторной награды не дают.");
            return;
        }
        if (args.length < 3) {
            player.sendMessage("/impuls sellserver <amount>");
            return;
        }
        sellServer(player, args[2]);
    }

    private boolean consumePlayerPlaced(Block block) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM profession_placed_blocks WHERE world=? AND x=? AND y=? AND z=?")) {
            ps.setString(1, block.getWorld().getName());
            ps.setInt(2, block.getX());
            ps.setInt(3, block.getY());
            ps.setInt(4, block.getZ());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("Profession placed-block check failed: " + e.getMessage());
            return true; // fail closed: no money if anti-loop persistence is unavailable
        }
    }

    private void rewardActivity(Player player, int amount, String reason) {
        int cap = plugin.getConfig().getInt("professions.daily-activity-cap", 500);
        Daily daily = daily(player.getUniqueId());
        if (daily.earned >= cap) return;
        int actual = Math.min(amount, cap - daily.earned);
        if (actual <= 0) return;
        ensureDay(player.getUniqueId());
        update("UPDATE profession_daily SET earned=earned+? WHERE uuid=? AND day=?", actual, player.getUniqueId().toString(), day());
        db.credit(player.getUniqueId(), actual, reason);
    }

    private void sellServer(Player player, String amountRaw) {
        if (player.getGameMode() != org.bukkit.GameMode.SURVIVAL) {
            player.sendMessage(ChatColor.RED + "Продажа серверу разрешена только из Survival.");
            return;
        }
        int amount;
        try { amount = Integer.parseInt(amountRaw); } catch (NumberFormatException e) { return; }
        ItemStack hand = player.getInventory().getItemInMainHand();
        Integer unit = BUYBACK.get(hand.getType());
        if (unit == null || amount <= 0 || hand.getAmount() < amount) {
            player.sendMessage(ChatColor.RED + "Этот ресурс сервер не покупает или количества недостаточно.");
            return;
        }
        int cap = plugin.getConfig().getInt("professions.daily-buyback-cap", 1000);
        Daily daily = daily(player.getUniqueId());
        int rawValue = amount * unit;
        int payout = Math.min(rawValue, Math.max(0, cap - daily.sold));
        if (payout <= 0) {
            player.sendMessage(ChatColor.YELLOW + "Дневной лимит продажи серверу исчерпан.");
            return;
        }
        int accepted = Math.max(1, Math.min(amount, payout / unit));
        payout = accepted * unit;
        Material soldType = hand.getType();
        hand.setAmount(hand.getAmount() - accepted);
        player.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? new ItemStack(Material.AIR) : hand);
        ensureDay(player.getUniqueId());
        update("UPDATE profession_daily SET sold=sold+? WHERE uuid=? AND day=?", payout, player.getUniqueId().toString(), day());
        db.credit(player.getUniqueId(), payout, "server_buyback:" + soldType.name());
        db.audit(player.getUniqueId(), "server_buyback", soldType.name() + ":" + accepted + ":" + payout);
        player.sendMessage(ChatColor.GREEN + "Сервер купил " + accepted + " × " + soldType.name() + " за " + payout + " монет.");
    }

    private record Daily(int earned, int sold) { }
    private Daily daily(UUID uuid) {
        ensureDay(uuid);
        try (PreparedStatement ps = connection.prepareStatement("SELECT earned,sold FROM profession_daily WHERE uuid=? AND day=?")) {
            ps.setString(1, uuid.toString()); ps.setLong(2, day());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? new Daily(rs.getInt(1), rs.getInt(2)) : new Daily(0, 0); }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
    private void ensureDay(UUID uuid) { update("INSERT INTO profession_daily(uuid,day) VALUES(?,?) ON CONFLICT(uuid,day) DO NOTHING", uuid.toString(), day()); }
    private long day() { return LocalDate.now(ZoneOffset.UTC).toEpochDay(); }
    private void update(String sql, Object... args) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public void close() { try { connection.close(); } catch (SQLException ignored) { } }
}
