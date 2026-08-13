package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Persistent hunter-guild progression: medallion, ranked quests and promotion trials. */
public final class ProgressionQuestService implements Listener, AutoCloseable {
    private static final int[] XP_REQUIRED = {0, 250, 700, 1400, 2400, 3800, 5600, 8000, 11200, 15500, 21000, 28000, 37000};
    private static final int[] QUESTS_REQUIRED = {0, 2, 5, 9, 14, 20, 28, 38, 50, 65, 83, 104, 128};
    private static final Set<Material> MINE_BLOCKS = Set.of(
            Material.STONE, Material.DEEPSLATE, Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE, Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE, Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE);

    private final JavaPlugin plugin;
    private final Database db;
    private final Connection connection;
    private final NamespacedKey medallionKey;
    private final NamespacedKey trialOwnerKey;
    private final Map<UUID, String> lastExploreChunk = new HashMap<>();
    private final Map<UUID, Long> nextExploreCredit = new HashMap<>();

    private ProgressionQuestService(JavaPlugin plugin, Database db) throws SQLException {
        this.plugin = plugin;
        this.db = db;
        this.medallionKey = new NamespacedKey(plugin, "hunter_medallion");
        this.trialOwnerKey = new NamespacedKey(plugin, "rank_trial_owner");
        File file = new File(plugin.getDataFolder(), plugin.getConfig().getString("database.file", "impuls.sqlite3"));
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS hunter_progress(uuid TEXT PRIMARY KEY,active_type TEXT,active_goal INTEGER NOT NULL DEFAULT 0,active_progress INTEGER NOT NULL DEFAULT 0,active_target TEXT,active_expires INTEGER NOT NULL DEFAULT 0,trial_rank INTEGER NOT NULL DEFAULT -1,medallion_issued INTEGER NOT NULL DEFAULT 0)");
        }
    }

    public static void start(JavaPlugin plugin, Database db) {
        try {
            ProgressionQuestService service = new ProgressionQuestService(plugin, db);
            Bukkit.getPluginManager().registerEvents(service, plugin);
            plugin.getLogger().info("Hunter progression service enabled");
        } catch (SQLException e) {
            plugin.getLogger().severe("Hunter progression SQLite init failed: " + e.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        ensure(event.getPlayer().getUniqueId());
        if (!medallionIssued(event.getPlayer().getUniqueId())) issueMedallion(event.getPlayer(), false);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage().trim();
        if (!raw.toLowerCase(Locale.ROOT).startsWith("/impuls ")) return;
        String[] args = raw.substring(8).trim().split("\\s+");
        if (args.length == 0) return;
        String root = args[0].toLowerCase(Locale.ROOT);
        if (!(root.equals("medal") || root.equals("medallion") || root.equals("quest") || root.equals("rank"))) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        ensure(player.getUniqueId());
        switch (root) {
            case "medal", "medallion" -> handleMedallion(player, args);
            case "quest" -> handleQuest(player, args);
            case "rank" -> handleRank(player, args);
            default -> { }
        }
    }

    @EventHandler
    public void onMedallionUse(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) return;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(medallionKey, PersistentDataType.BYTE);
        if (marker == null || marker != (byte) 1) return;
        event.setCancelled(true);
        showProfile(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (MINE_BLOCKS.contains(event.getBlock().getType())) progress(event.getPlayer(), "MINE", 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (event.getWhoClicked() instanceof Player player) progress(player, "CRAFT", 1);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getTo().getWorld() == null) return;
        if (event.getFrom().getChunk().equals(event.getTo().getChunk())) return;
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        if (nextExploreCredit.getOrDefault(player.getUniqueId(), 0L) > now) return;
        String key = event.getTo().getWorld().getName() + ":" + event.getTo().getChunk().getX() + ":" + event.getTo().getChunk().getZ();
        if (key.equals(lastExploreChunk.put(player.getUniqueId(), key))) return;
        if (Math.abs(event.getTo().getX() + 688) < 1100 && Math.abs(event.getTo().getZ() + 688) < 1100) return;
        nextExploreCredit.put(player.getUniqueId(), now + 15000L);
        progress(player, "EXPLORE", 1);
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null && event.getEntity() instanceof Monster) progress(killer, "HUNT", 1);

        String owner = event.getEntity().getPersistentDataContainer().get(trialOwnerKey, PersistentDataType.STRING);
        if (owner == null || killer == null || !owner.equals(killer.getUniqueId().toString())) return;
        int next = trialRank(killer.getUniqueId());
        if (next <= db.rank(killer.getUniqueId()) || next >= RankTier.values().length) return;
        updateProfileRank(killer.getUniqueId(), next);
        setTrialRank(killer.getUniqueId(), -1);
        int reward = 250 + next * 125;
        db.credit(killer.getUniqueId(), reward, "rank_promotion:" + next);
        db.audit(killer.getUniqueId(), "rank_promoted", RankTier.fromIndex(next).display());
        killer.sendTitle(ChatColor.GOLD + "Ранг повышен", ChatColor.YELLOW + RankTier.fromIndex(next).display(), 10, 70, 20);
        killer.sendMessage(ChatColor.GREEN + "[ImPuls] Испытание пройдено. Новый ранг: " + RankTier.fromIndex(next).display() + ", награда " + reward + " монет.");
    }

    private void handleMedallion(Player player, String[] args) {
        if (args.length > 1 && "recover".equalsIgnoreCase(args[1])) {
            if (hasMedallion(player)) {
                player.sendMessage(ChatColor.YELLOW + "Медальон уже находится в твоём инвентаре.");
                return;
            }
            int cost = plugin.getConfig().getInt("progression.medallion-recovery-cost", 25);
            if (!db.charge(player.getUniqueId(), cost, "medallion_recover")) {
                player.sendMessage(ChatColor.RED + "Нужно " + cost + " монет для восстановления медальона.");
                return;
            }
            issueMedallion(player, true);
            return;
        }
        showProfile(player);
    }

    private void showProfile(Player player) {
        UUID uuid = player.getUniqueId();
        int rank = db.rank(uuid);
        int xp = profileInt(uuid, "xp");
        int done = profileInt(uuid, "quests");
        Quest quest = quest(uuid);
        player.sendMessage(ChatColor.GOLD + "════ Медальон искателя ════");
        player.sendMessage(ChatColor.YELLOW + player.getName() + ChatColor.GRAY + " | ранг " + ChatColor.WHITE + RankTier.fromIndex(rank).display());
        player.sendMessage(ChatColor.GRAY + "Опыт гильдии: " + ChatColor.WHITE + xp + ChatColor.GRAY + " | заданий: " + ChatColor.WHITE + done + ChatColor.GRAY + " | подземелий: " + ChatColor.WHITE + profileInt(uuid, "dungeons"));
        Long guild = db.guildId(uuid);
        player.sendMessage(ChatColor.GRAY + "Гильдия: " + ChatColor.WHITE + (guild == null ? "—" : db.guildName(guild)) + ChatColor.GRAY + " | роль: " + ChatColor.WHITE + (guild == null ? "—" : db.memberRole(uuid)));
        player.sendMessage(ChatColor.GRAY + "Активное задание: " + ChatColor.WHITE + (quest == null ? "нет" : quest.type + " " + quest.progress + "/" + quest.goal));
        if (rank + 1 < RankTier.values().length) {
            player.sendMessage(ChatColor.GRAY + "До " + RankTier.fromIndex(rank + 1).display() + ": XP " + xp + "/" + XP_REQUIRED[rank + 1] + ", задания " + done + "/" + QUESTS_REQUIRED[rank + 1]);
        }
    }

    private void handleQuest(Player player, String[] args) {
        if (args.length == 1 || "status".equalsIgnoreCase(args[1])) {
            Quest q = quest(player.getUniqueId());
            if (q == null) player.sendMessage(ChatColor.YELLOW + "Нет активного задания. /impuls quest take mine|hunt|craft|gather|explore");
            else player.sendMessage(ChatColor.AQUA + "Задание " + q.type + ": " + q.progress + "/" + q.goal + (q.target == null ? "" : " | цель " + q.target));
            return;
        }
        if ("abandon".equalsIgnoreCase(args[1])) {
            clearQuest(player.getUniqueId());
            player.sendMessage(ChatColor.YELLOW + "Задание отменено без награды.");
            return;
        }
        if ("submit".equalsIgnoreCase(args[1])) {
            submitGather(player);
            return;
        }
        if (!"take".equalsIgnoreCase(args[1]) || args.length < 3) {
            player.sendMessage("/impuls quest take mine|hunt|craft|gather|explore | status | submit | abandon");
            return;
        }
        if (quest(player.getUniqueId()) != null) {
            player.sendMessage(ChatColor.RED + "Сначала заверши или отмени текущее задание.");
            return;
        }
        String type = args[2].toUpperCase(Locale.ROOT);
        if (!Set.of("MINE", "HUNT", "CRAFT", "GATHER", "EXPLORE").contains(type)) {
            player.sendMessage(ChatColor.RED + "Тип: mine, hunt, craft, gather, explore.");
            return;
        }
        int rank = db.rank(player.getUniqueId());
        int goal = switch (type) {
            case "MINE" -> 24 + rank * 8;
            case "HUNT" -> 8 + rank * 3;
            case "CRAFT" -> 6 + rank * 2;
            case "EXPLORE" -> 5 + rank;
            case "GATHER" -> 12 + rank * 4;
            default -> 10;
        };
        String target = null;
        if ("GATHER".equals(type)) {
            Material[] materials = {Material.COAL, Material.IRON_INGOT, Material.COPPER_INGOT, Material.WHEAT, Material.OAK_LOG};
            target = materials[Math.floorMod(player.getUniqueId().hashCode() + (int) (Instant.now().getEpochSecond() / 3600), materials.length)].name();
        }
        setQuest(player.getUniqueId(), type, goal, target, Instant.now().getEpochSecond() + 86400L);
        player.sendMessage(ChatColor.GREEN + "[ImPuls] Задание принято: " + type + " ×" + goal + (target == null ? "" : " (" + target + ")") + ". Срок 24 часа.");
    }

    private void handleRank(Player player, String[] args) {
        int current = db.rank(player.getUniqueId());
        if (current + 1 >= RankTier.values().length) {
            player.sendMessage(ChatColor.GOLD + "У тебя максимальный ранг SSS+.");
            return;
        }
        if (args.length == 1 || "status".equalsIgnoreCase(args[1])) {
            int next = current + 1;
            player.sendMessage(ChatColor.AQUA + "Повышение до " + RankTier.fromIndex(next).display() + ": XP " + profileInt(player.getUniqueId(), "xp") + "/" + XP_REQUIRED[next] + ", задания " + profileInt(player.getUniqueId(), "quests") + "/" + QUESTS_REQUIRED[next] + ". Затем /impuls rank trial");
            return;
        }
        if (!"trial".equalsIgnoreCase(args[1])) return;
        int next = current + 1;
        if (profileInt(player.getUniqueId(), "xp") < XP_REQUIRED[next] || profileInt(player.getUniqueId(), "quests") < QUESTS_REQUIRED[next]) {
            player.sendMessage(ChatColor.RED + "Требования повышения ещё не выполнены.");
            return;
        }
        if (trialRank(player.getUniqueId()) == next) {
            player.sendMessage(ChatColor.YELLOW + "Испытание уже активно. Победи призванного противника самостоятельно.");
            return;
        }
        LivingEntity mob = (LivingEntity) player.getWorld().spawnEntity(player.getLocation().add(8, 0, 0), trialMob(next));
        mob.setCustomName(ChatColor.DARK_RED + "Испытание ранга " + RankTier.fromIndex(next).display());
        mob.setCustomNameVisible(true);
        mob.getPersistentDataContainer().set(trialOwnerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        mob.addScoreboardTag("impuls_rank_trial");
        setTrialRank(player.getUniqueId(), next);
        player.sendMessage(ChatColor.RED + "[ImPuls] Испытание началось. Победи противника без помощи другого игрока.");
    }

    private EntityType trialMob(int rank) {
        if (rank >= 11) return EntityType.WARDEN;
        if (rank >= 9) return EntityType.RAVAGER;
        if (rank >= 7) return EntityType.IRON_GOLEM;
        if (rank >= 5) return EntityType.VINDICATOR;
        if (rank >= 3) return EntityType.HUSK;
        return EntityType.ZOMBIE;
    }

    private void submitGather(Player player) {
        Quest q = quest(player.getUniqueId());
        if (q == null || !"GATHER".equals(q.type) || q.target == null) {
            player.sendMessage(ChatColor.RED + "Нет активного задания на сдачу ресурсов.");
            return;
        }
        Material material;
        try { material = Material.valueOf(q.target); } catch (IllegalArgumentException e) { clearQuest(player.getUniqueId()); return; }
        if (!player.getInventory().containsAtLeast(new ItemStack(material), q.goal)) {
            player.sendMessage(ChatColor.YELLOW + "Нужно принести " + q.goal + " × " + material.name() + ".");
            return;
        }
        player.getInventory().removeItem(new ItemStack(material, q.goal));
        completeQuest(player, q);
    }

    private void progress(Player player, String type, int amount) {
        Quest q = quest(player.getUniqueId());
        if (q == null || !type.equals(q.type) || q.expires < Instant.now().getEpochSecond()) return;
        int value = Math.min(q.goal, q.progress + amount);
        update("UPDATE hunter_progress SET active_progress=? WHERE uuid=?", value, player.getUniqueId().toString());
        if (value >= q.goal) completeQuest(player, new Quest(q.type, q.goal, value, q.target, q.expires));
    }

    private void completeQuest(Player player, Quest q) {
        UUID uuid = player.getUniqueId();
        int rank = db.rank(uuid);
        int xp = 80 + rank * 35 + q.goal * 2;
        int coins = 45 + rank * 20 + q.goal;
        boolean ok = transaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement("UPDATE hunter_progress SET active_type=NULL,active_goal=0,active_progress=0,active_target=NULL,active_expires=0 WHERE uuid=? AND active_type=?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, q.type);
                if (ps.executeUpdate() != 1) return false;
            }
            try (PreparedStatement ps = connection.prepareStatement("UPDATE profiles SET xp=xp+?,quests=quests+1 WHERE uuid=?")) {
                ps.setInt(1, xp);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
            return true;
        });
        if (!ok) return;
        db.credit(uuid, coins, "quest:" + q.type);
        db.audit(uuid, "quest_complete", q.type + ":xp=" + xp + ":coins=" + coins);
        player.sendMessage(ChatColor.GREEN + "[ImPuls] Задание выполнено: +" + xp + " XP гильдии, +" + coins + " монет.");
    }

    private void issueMedallion(Player player, boolean recovered) {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Медальон искателя");
        meta.setLore(java.util.List.of(ChatColor.GRAY + "Персональный профиль ImPuls", ChatColor.DARK_GRAY + "ПКМ — открыть медальон"));
        meta.getPersistentDataContainer().set(medallionKey, PersistentDataType.BYTE, (byte) 1);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        player.getInventory().addItem(item);
        update("UPDATE hunter_progress SET medallion_issued=1 WHERE uuid=?", player.getUniqueId().toString());
        player.sendMessage(ChatColor.LIGHT_PURPLE + (recovered ? "Медальон восстановлен." : "Ты получил персональный медальон искателя."));
    }

    private boolean hasMedallion(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !item.hasItemMeta()) continue;
            Byte value = item.getItemMeta().getPersistentDataContainer().get(medallionKey, PersistentDataType.BYTE);
            if (value != null && value == (byte) 1) return true;
        }
        return false;
    }

    private void ensure(UUID uuid) {
        update("INSERT INTO hunter_progress(uuid) VALUES(?) ON CONFLICT(uuid) DO NOTHING", uuid.toString());
    }

    private boolean medallionIssued(UUID uuid) { return intQuery("SELECT medallion_issued FROM hunter_progress WHERE uuid=?", uuid.toString()) > 0; }
    private int trialRank(UUID uuid) { return intQuery("SELECT trial_rank FROM hunter_progress WHERE uuid=?", uuid.toString()); }
    private void setTrialRank(UUID uuid, int rank) { update("UPDATE hunter_progress SET trial_rank=? WHERE uuid=?", rank, uuid.toString()); }
    private int profileInt(UUID uuid, String column) {
        if (!Set.of("xp", "quests", "dungeons").contains(column)) return 0;
        return intQuery("SELECT " + column + " FROM profiles WHERE uuid=?", uuid.toString());
    }
    private void updateProfileRank(UUID uuid, int rank) { update("UPDATE profiles SET rank=? WHERE uuid=?", rank, uuid.toString()); }

    private record Quest(String type, int goal, int progress, String target, long expires) { }
    private Quest quest(UUID uuid) {
        ensure(uuid);
        try (PreparedStatement ps = connection.prepareStatement("SELECT active_type,active_goal,active_progress,active_target,active_expires FROM hunter_progress WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getString(1) == null) return null;
                if (rs.getLong(5) > 0 && rs.getLong(5) < Instant.now().getEpochSecond()) {
                    clearQuest(uuid);
                    return null;
                }
                return new Quest(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getLong(5));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private void setQuest(UUID uuid, String type, int goal, String target, long expires) {
        ensure(uuid);
        update("UPDATE hunter_progress SET active_type=?,active_goal=?,active_progress=0,active_target=?,active_expires=? WHERE uuid=?", type, goal, target, expires, uuid.toString());
    }
    private void clearQuest(UUID uuid) { update("UPDATE hunter_progress SET active_type=NULL,active_goal=0,active_progress=0,active_target=NULL,active_expires=0 WHERE uuid=?", uuid.toString()); }

    private int intQuery(String sql, Object... args) {
        try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        catch (SQLException e) { throw new RuntimeException(e); }
    }
    private PreparedStatement prepare(String sql, Object... args) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
        return ps;
    }
    private void update(String sql, Object... args) {
        try (PreparedStatement ps = prepare(sql, args)) { ps.executeUpdate(); }
        catch (SQLException e) { throw new RuntimeException(e); }
    }
    private interface TxBody { boolean run() throws Exception; }
    private boolean transaction(TxBody body) {
        try {
            connection.setAutoCommit(false);
            boolean ok = body.run();
            if (ok) connection.commit(); else connection.rollback();
            connection.setAutoCommit(true);
            return ok;
        } catch (Exception e) {
            try { connection.rollback(); } catch (SQLException ignored) { }
            try { connection.setAutoCommit(true); } catch (SQLException ignored) { }
            return false;
        }
    }

    @Override
    public void close() {
        try { connection.close(); } catch (SQLException ignored) { }
    }
}
