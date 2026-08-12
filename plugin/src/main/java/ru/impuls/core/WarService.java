package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class WarService {
    private static final String WORLD_NAME = "impuls_war_arena";
    private static final String WAR_TAG = "impuls_war";
    private final JavaPlugin plugin;
    private final Database db;
    private final SessionService sessions;
    private final Set<UUID> pendingRespawn = new HashSet<>();

    public WarService(JavaPlugin plugin, Database db, SessionService sessions) {
        this.plugin = plugin;
        this.db = db;
        this.sessions = sessions;
    }

    public void startTicker() {
        if (plugin.getConfig().getBoolean("features.city-events", true)) {
            CityEventService.start(plugin, db);
        }
        if (plugin.getConfig().getBoolean("features.world-expansion", true)) {
            WorldExpansionService.start(plugin, db);
        }
        if (plugin.getConfig().getBoolean("features.region-discovery", true)) {
            RegionDiscoveryService.start(plugin, db);
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L * 10, 20L * 10);
    }

    public void recoverOnJoin(Player player) {
        String context = sessions.context(player.getUniqueId());
        if (context == null || !context.startsWith("WAR:")) return;
        long warId;
        try { warId = Long.parseLong(context.substring(4)); }
        catch (NumberFormatException e) { sessions.restore(player); clearWarTags(player); return; }
        Database.WarInfo war = db.warById(warId);
        if (war != null && "ACTIVE".equals(war.state())) {
            Long guild = db.guildId(player.getUniqueId());
            boolean teamA = guild != null && guild == war.guildA();
            player.getInventory().clear();
            player.getInventory().setArmorContents(null);
            giveEqualKit(player);
            player.setGameMode(GameMode.SURVIVAL);
            player.addScoreboardTag(WAR_TAG);
            player.addScoreboardTag("impuls_war_" + warId);
            player.addScoreboardTag(teamA ? "impuls_war_a" : "impuls_war_b");
            player.teleport(new Location(arenaWorld(), teamA ? -24 : 24, 66, 0, teamA ? -90f : 90f, 0f));
            player.sendMessage(ChatColor.YELLOW + "[ImPuls] Ты вернулся в активную войну; обычный инвентарь всё ещё хранится отдельно.");
        } else {
            sessions.restore(player);
            clearWarTags(player);
            player.sendMessage(ChatColor.YELLOW + "[ImPuls] Военная сессия завершена; обычный инвентарь восстановлен.");
        }
    }

    public boolean acceptAndStart(Player leader) {
        int maxCountDiff = plugin.getConfig().getInt("war.max-member-difference", 1);
        double maxRankDiff = plugin.getConfig().getDouble("war.max-average-rank-difference", 1.5d);
        if (!db.acceptWar(leader.getUniqueId(), maxCountDiff, maxRankDiff)) return false;
        Long guild = db.guildId(leader.getUniqueId());
        if (guild == null) return false;
        Database.WarInfo war = db.activeWarForGuild(guild);
        if (war == null || !"ACTIVE".equals(war.state())) return false;
        deploy(war);
        return true;
    }

    private void deploy(Database.WarInfo war) {
        World world = arenaWorld();
        buildArena(world);
        List<Database.GuildMember> a = db.guildMembers(war.guildA());
        List<Database.GuildMember> b = db.guildMembers(war.guildB());
        int slot = 0;
        for (Database.GuildMember member : a) {
            Player p = Bukkit.getPlayer(member.uuid());
            if (p != null) prepare(p, war.id(), true, slot++);
        }
        slot = 0;
        for (Database.GuildMember member : b) {
            Player p = Bukkit.getPlayer(member.uuid());
            if (p != null) prepare(p, war.id(), false, slot++);
        }
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "[ImPuls] Началась согласованная война гильдий: " + db.guildName(war.guildA()) + " vs " + db.guildName(war.guildB()) + ". Казна фиксирована; максимум захвата — 10%.");
    }

    private void prepare(Player player, long warId, boolean teamA, int slot) {
        if (!sessions.save(player, "WAR:" + warId)) return;
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        giveEqualKit(player);
        player.addScoreboardTag(WAR_TAG);
        player.addScoreboardTag("impuls_war_" + warId);
        player.addScoreboardTag(teamA ? "impuls_war_a" : "impuls_war_b");
        int offset = Math.min(18, slot * 3);
        player.teleport(new Location(arenaWorld(), teamA ? -24 : 24, 66, -18 + offset, teamA ? -90f : 90f, 0f));
        player.sendMessage(ChatColor.GOLD + "[ImPuls] Война идёт в равном временном снаряжении. Выход из гильдии заблокирован до конца войны.");
    }

    public boolean handleDeath(Player victim, Player killer) {
        Long warId = db.activeWarIdForPlayer(victim.getUniqueId());
        if (warId == null) return false;
        if (killer != null) db.recordWarKill(killer.getUniqueId(), victim.getUniqueId());
        pendingRespawn.add(victim.getUniqueId());
        return true;
    }

    public Location respawnLocation(Player player) {
        if (!pendingRespawn.remove(player.getUniqueId())) return null;
        Long warId = db.activeWarIdForPlayer(player.getUniqueId());
        if (warId == null) return arenaWorld().getSpawnLocation();
        Database.WarInfo info = db.warById(warId);
        Long guild = db.guildId(player.getUniqueId());
        boolean teamA = info != null && guild != null && guild == info.guildA();
        return new Location(arenaWorld(), teamA ? -24 : 24, 66, 0, teamA ? -90f : 90f, 0f);
    }

    public void finishRespawn(Player player) {
        if (!player.getScoreboardTags().contains(WAR_TAG)) return;
        player.getInventory().clear();
        giveEqualKit(player);
        player.setHealth(Math.min(player.getMaxHealth(), 20d));
        player.setFoodLevel(20);
    }

    public void cancel(long warId, UUID actor) {
        if (!db.cancelWar(warId, actor)) return;
        restoreWar(warId, ChatColor.YELLOW + "Война отменена без захвата казны.");
    }

    private void tick() {
        Set<Long> checked = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Long warId = db.activeWarIdForPlayer(player.getUniqueId());
            if (warId == null || !checked.add(warId)) continue;
            Database.WarInfo war = db.warById(warId);
            if (war == null || !"ACTIVE".equals(war.state())) continue;
            long elapsed = Instant.now().getEpochSecond() - war.startedAt();
            int target = plugin.getConfig().getInt("war.kill-target", 20);
            long limit = plugin.getConfig().getLong("war.duration-minutes", 15) * 60L;
            if (war.scoreA() >= target || war.scoreB() >= target || elapsed >= limit) conclude(war);
        }
    }

    private void conclude(Database.WarInfo war) {
        if (war.scoreA() == war.scoreB()) {
            if (db.cancelWar(war.id(), null)) restoreWar(war.id(), ChatColor.YELLOW + "Война завершилась ничьей. Казна не захвачена.");
            return;
        }
        long winner = war.scoreA() > war.scoreB() ? war.guildA() : war.guildB();
        double capture = plugin.getConfig().getDouble("war.treasury-capture-fraction", 0.10d);
        long cooldown = plugin.getConfig().getLong("war.capture-cooldown-days", 7) * 86400L;
        if (db.finishWar(war.id(), winner, Math.min(0.10d, Math.max(0d, capture)), cooldown)) {
            restoreWar(war.id(), ChatColor.GREEN + "Победила гильдия " + db.guildName(winner) + ". Захвачено не более 10% зафиксированной денежной казны; редкие предметы не затрагиваются.");
        }
    }

    private void restoreWar(long warId, String message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            String context = sessions.context(player.getUniqueId());
            if (("WAR:" + warId).equals(context)) {
                clearWarTags(player);
                sessions.restore(player);
                player.sendMessage(message);
            }
        }
        Bukkit.broadcastMessage(ChatColor.GOLD + "[ImPuls] " + ChatColor.stripColor(message));
    }

    private void giveEqualKit(Player player) {
        player.getInventory().addItem(
                new ItemStack(Material.IRON_SWORD),
                new ItemStack(Material.BOW),
                new ItemStack(Material.ARROW, 24),
                new ItemStack(Material.COOKED_BEEF, 16),
                new ItemStack(Material.SHIELD)
        );
        player.getInventory().setHelmet(new ItemStack(Material.IRON_HELMET));
        player.getInventory().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        player.getInventory().setLeggings(new ItemStack(Material.IRON_LEGGINGS));
        player.getInventory().setBoots(new ItemStack(Material.IRON_BOOTS));
    }

    private World arenaWorld() {
        World world = Bukkit.getWorld(WORLD_NAME);
        if (world == null) {
            WorldCreator creator = new WorldCreator(WORLD_NAME);
            creator.type(WorldType.FLAT);
            creator.generateStructures(false);
            world = creator.createWorld();
            if (world == null) throw new IllegalStateException("Cannot create war arena world");
            world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
            world.setGameRule(GameRule.KEEP_INVENTORY, true);
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            world.setTime(6000L);
            world.setPVP(true);
        }
        return world;
    }

    private void buildArena(World world) {
        int y = 64;
        for (int x = -32; x <= 32; x++) {
            for (int z = -32; z <= 32; z++) {
                world.getBlockAt(x, y, z).setType(Material.SMOOTH_STONE, false);
                if (Math.abs(x) == 32 || Math.abs(z) == 32) {
                    for (int h = 1; h <= 6; h++) world.getBlockAt(x, y + h, z).setType(Material.STONE_BRICKS, false);
                } else {
                    for (int h = 1; h <= 5; h++) world.getBlockAt(x, y + h, z).setType(Material.AIR, false);
                }
            }
        }
        world.setSpawnLocation(new Location(world, 0.5, 66, 0.5));
    }

    private void clearWarTags(Player player) {
        for (String tag : new HashSet<>(player.getScoreboardTags())) {
            if (tag.equals(WAR_TAG) || tag.startsWith("impuls_war_")) player.removeScoreboardTag(tag);
        }
    }
}
