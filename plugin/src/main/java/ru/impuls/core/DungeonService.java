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
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class DungeonService {
    private static final String WORLD_NAME = "impuls_dungeon";
    private static final String TAG = "impuls_dungeon";
    private final JavaPlugin plugin;
    private final Database db;
    private final SessionService sessions;
    private final Set<UUID> pendingRestore = new HashSet<>();

    public DungeonService(JavaPlugin plugin, Database db, SessionService sessions) {
        this.plugin = plugin;
        this.db = db;
        this.sessions = sessions;
    }

    public void recoverOnJoin(Player player) {
        String context = sessions.context(player.getUniqueId());
        if (context != null && context.startsWith("DUNGEON:")) {
            sessions.restore(player);
            player.removeScoreboardTag(TAG);
            player.sendMessage(ChatColor.YELLOW + "[ImPuls] Незавершённая dungeon-сессия безопасно восстановлена после перезапуска/выхода.");
        }
    }

    public void start(Player leader, RankTier requested) {
        UUID leaderId = leader.getUniqueId();
        if (sessions.hasSession(leaderId) || db.activeDungeonRun(leaderId) != null) {
            leader.sendMessage(ChatColor.RED + "У тебя уже есть активная игровая сессия.");
            return;
        }
        long now = Instant.now().getEpochSecond();
        if (db.cooldown(leaderId, "dungeon") > now) {
            leader.sendMessage(ChatColor.RED + "Подземелье ещё на перезарядке.");
            return;
        }

        List<Player> party = nearbyParty(leader);
        double average = party.stream().mapToInt(p -> db.rank(p.getUniqueId())).average().orElse(db.rank(leaderId));
        if (requested.index() > Math.floor(average)) {
            leader.sendMessage(ChatColor.RED + "Ранг подземелья выше среднего ранга группы (" + RankTier.fromIndex((int)Math.floor(average)).display() + ").");
            return;
        }
        for (Player member : party) {
            if (sessions.hasSession(member.getUniqueId()) || db.activeDungeonRun(member.getUniqueId()) != null || db.cooldown(member.getUniqueId(), "dungeon") > now) {
                leader.sendMessage(ChatColor.RED + "Участник " + member.getName() + " занят или имеет cooldown.");
                return;
            }
        }

        int floors = Math.min(10, 5 + requested.index() / 2);
        long seed = new Random().nextLong();
        List<UUID> ids = party.stream().map(Player::getUniqueId).toList();
        long runId = db.createDungeonRun(leaderId, requested.index(), seed, floors, ids);
        if (runId <= 0) {
            leader.sendMessage(ChatColor.RED + "Не удалось зарегистрировать подземелье.");
            return;
        }
        World world = dungeonWorld();
        generateFloor(world, runId, 1, requested, seed, floors == 1);
        Location entry = floorCenter(world, runId, 1);
        for (Player member : party) {
            if (!sessions.save(member, "DUNGEON:" + runId)) continue;
            member.getInventory().clear();
            giveDungeonKit(member, requested);
            member.setGameMode(GameMode.SURVIVAL);
            member.setAllowFlight(false);
            member.setFlying(false);
            member.addScoreboardTag(TAG);
            member.addScoreboardTag(runTag(runId));
            member.teleport(entry);
            member.sendMessage(ChatColor.GOLD + "[ImPuls] Подземелье " + requested.display() + ", этаж 1/" + floors + ". Внешний инвентарь сохранён отдельно.");
        }
    }

    public void nextFloor(Player actor) {
        Long runId = db.activeDungeonRun(actor.getUniqueId());
        if (runId == null) {
            actor.sendMessage(ChatColor.RED + "Ты не в активном подземелье.");
            return;
        }
        Database.DungeonRun run = db.dungeonRun(runId);
        if (run == null) return;
        World world = dungeonWorld();
        if (hasDungeonMobs(world, runId)) {
            actor.sendMessage(ChatColor.RED + "Сначала зачистите текущий этаж.");
            return;
        }
        if (run.currentFloor() >= run.floors()) {
            complete(runId);
            return;
        }
        int next = run.currentFloor() + 1;
        db.setDungeonFloor(runId, next);
        RankTier rank = RankTier.fromIndex(run.rank());
        generateFloor(world, runId, next, rank, run.seed() + next * 7919L, next == run.floors());
        Location destination = floorCenter(world, runId, next);
        for (UUID uuid : db.dungeonMembers(runId)) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.getScoreboardTags().contains(runTag(runId))) {
                p.teleport(destination);
                p.sendTitle(ChatColor.GOLD + "Этаж " + next, ChatColor.GRAY + "Подземелье " + rank.display(), 10, 40, 10);
            }
        }
    }

    public void leave(Player player) {
        Long runId = db.activeDungeonRun(player.getUniqueId());
        if (runId == null) return;
        db.markDungeonMemberOut(runId, player.getUniqueId());
        clearDungeonTags(player, runId);
        sessions.restore(player);
        player.sendMessage(ChatColor.YELLOW + "Ты покинул подземелье; добыча текущего прохождения потеряна.");
        if (db.dungeonAliveCount(runId) <= 0) db.failDungeon(runId, cooldownSeconds());
    }

    public boolean handlePlayerDeath(Player player) {
        Long runId = db.activeDungeonRun(player.getUniqueId());
        if (runId == null) return false;
        db.markDungeonMemberOut(runId, player.getUniqueId());
        pendingRestore.add(player.getUniqueId());
        clearDungeonTags(player, runId);
        if (db.dungeonAliveCount(runId) <= 0) db.failDungeon(runId, cooldownSeconds());
        return true;
    }

    public Location respawnLocation(Player player) {
        if (!pendingRestore.remove(player.getUniqueId())) return null;
        Database.SessionSnapshot snapshot = db.session(player.getUniqueId());
        if (snapshot == null) return player.getWorld().getSpawnLocation();
        World world = Bukkit.getWorld(snapshot.world());
        return world == null ? player.getWorld().getSpawnLocation() : new Location(world, snapshot.x(), snapshot.y(), snapshot.z(), snapshot.yaw(), snapshot.pitch());
    }

    public void finishDeathRestore(Player player) {
        sessions.restore(player);
        player.sendMessage(ChatColor.RED + "Ты погиб в подземелье. Страховка обычного мира не сработала; dungeon-добыча потеряна.");
    }

    public void onMobDeath(Entity entity) {
        Long run = runIdFromTags(entity);
        if (run == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Database.DungeonRun info = db.dungeonRun(run);
            if (info != null && "ACTIVE".equals(info.state()) && info.currentFloor() >= info.floors() && !hasDungeonMobs(dungeonWorld(), run)) {
                complete(run);
            }
        });
    }

    public void onQuit(Player player) {
        Long runId = db.activeDungeonRun(player.getUniqueId());
        if (runId == null) return;
        db.markDungeonMemberOut(runId, player.getUniqueId());
        clearDungeonTags(player, runId);
        if (db.dungeonAliveCount(runId) <= 0) db.failDungeon(runId, cooldownSeconds());
    }

    private void complete(long runId) {
        Database.DungeonRun run = db.dungeonRun(runId);
        if (run == null || !"ACTIVE".equals(run.state())) return;
        RankTier rank = RankTier.fromIndex(run.rank());
        int total = 250 * (run.rank() + 1) * run.floors();
        if (!db.finishDungeon(runId, total, cooldownSeconds())) return;
        for (UUID uuid : db.dungeonMembers(runId)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            clearDungeonTags(player, runId);
            sessions.restore(player);
            giveCompletionItem(player, rank);
            player.sendTitle(ChatColor.GREEN + "Подземелье пройдено", ChatColor.GOLD + rank.display(), 10, 60, 15);
            player.sendMessage(ChatColor.GREEN + "Награда валютой разделена между группой. Первый предмет награды выдан после восстановления обычного инвентаря.");
        }
    }

    private void giveCompletionItem(Player player, RankTier rank) {
        Material material = switch (rank) {
            case H, G, F -> Material.IRON_INGOT;
            case E, D, C -> Material.GOLD_INGOT;
            case B, A -> Material.DIAMOND;
            case S, SS -> Material.NETHERITE_SCRAP;
            case SSS, SSS_PLUS -> Material.ECHO_SHARD;
        };
        int amount = Math.min(material.getMaxStackSize(), 1 + rank.index() / 2);
        ItemStack reward = new ItemStack(material, amount);
        ItemMeta meta = reward.getItemMeta();
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Трофей подземелья " + rank.display());
        reward.setItemMeta(meta);
        player.getInventory().addItem(reward).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }

    private List<Player> nearbyParty(Player leader) {
        List<Player> result = new ArrayList<>();
        Long guild = db.guildId(leader.getUniqueId());
        for (Player candidate : leader.getWorld().getPlayers()) {
            if (candidate.getLocation().distanceSquared(leader.getLocation()) > 12 * 12) continue;
            if (guild == null) {
                if (candidate.equals(leader)) result.add(candidate);
            } else if (guild.equals(db.guildId(candidate.getUniqueId()))) {
                result.add(candidate);
            }
        }
        if (!result.contains(leader)) result.add(leader);
        return result;
    }

    private World dungeonWorld() {
        World world = Bukkit.getWorld(WORLD_NAME);
        if (world == null) {
            WorldCreator creator = new WorldCreator(WORLD_NAME);
            creator.environment(World.Environment.NORMAL);
            creator.type(WorldType.FLAT);
            creator.generateStructures(false);
            world = creator.createWorld();
            if (world == null) throw new IllegalStateException("Cannot create " + WORLD_NAME);
            world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
            world.setGameRule(GameRule.KEEP_INVENTORY, true);
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            world.setTime(18000L);
            world.setPVP(true);
        }
        return world;
    }

    private Location floorCenter(World world, long runId, int floor) {
        int baseX = 256 + (int)((runId % 100) * 96);
        int baseZ = 256 + (int)(((runId / 100) % 100) * 96);
        int y = 72 + (floor - 1) * 14;
        return new Location(world, baseX + 0.5, y + 1, baseZ + 0.5, 0f, 0f);
    }

    private void generateFloor(World world, long runId, int floor, RankTier rank, long seed, boolean bossFloor) {
        Location center = floorCenter(world, runId, floor);
        int cx = center.getBlockX(), cy = center.getBlockY() - 1, cz = center.getBlockZ();
        Random random = new Random(seed);
        int radius = 15;
        Material floorMat = floor % 2 == 0 ? Material.POLISHED_DEEPSLATE : Material.DEEPSLATE_TILES;
        Material wallMat = rank.index() >= RankTier.S.index() ? Material.BLACKSTONE : Material.STONE_BRICKS;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                world.getBlockAt(x, cy, z).setType(floorMat, false);
                boolean edge = x == cx - radius || x == cx + radius || z == cz - radius || z == cz + radius;
                if (edge) for (int y = cy + 1; y <= cy + 6; y++) world.getBlockAt(x, y, z).setType(wallMat, false);
                else for (int y = cy + 1; y <= cy + 5; y++) world.getBlockAt(x, y, z).setType(Material.AIR, false);
            }
        }
        for (int i = 0; i < 12; i++) {
            int x = cx - 10 + random.nextInt(21), z = cz - 10 + random.nextInt(21);
            world.getBlockAt(x, cy, z).setType(i % 4 == 0 ? Material.MAGMA_BLOCK : Material.CRACKED_DEEPSLATE_TILES, false);
        }
        int count = bossFloor ? 1 : Math.min(14, 4 + rank.index() / 2 + floor);
        for (int i = 0; i < count; i++) {
            EntityType type = bossFloor ? bossType(rank) : mobType(rank, random);
            Location at = new Location(world, cx - 9 + random.nextInt(19) + 0.5, cy + 1, cz - 9 + random.nextInt(19) + 0.5);
            Entity spawned = world.spawnEntity(at, type);
            spawned.addScoreboardTag(TAG);
            spawned.addScoreboardTag(runTag(runId));
            if (spawned instanceof LivingEntity living) {
                double scale = 1d + rank.index() * 0.08d + floor * 0.03d;
                var attr = living.getAttribute(Attribute.MAX_HEALTH);
                if (attr != null) {
                    attr.setBaseValue(Math.min(2048d, attr.getBaseValue() * scale));
                    living.setHealth(attr.getValue());
                }
                living.setPersistent(true);
                if (bossFloor) {
                    living.setCustomName(ChatColor.DARK_RED + "Босс подземелья " + rank.display());
                    living.setCustomNameVisible(true);
                }
            }
        }
    }

    private EntityType bossType(RankTier rank) {
        if (rank.index() >= RankTier.SSS.index()) return EntityType.WARDEN;
        if (rank.index() >= RankTier.S.index()) return EntityType.RAVAGER;
        if (rank.index() >= RankTier.B.index()) return EntityType.IRON_GOLEM;
        return EntityType.VINDICATOR;
    }

    private EntityType mobType(RankTier rank, Random random) {
        EntityType[] low = {EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER};
        EntityType[] mid = {EntityType.HUSK, EntityType.STRAY, EntityType.PILLAGER, EntityType.VINDICATOR};
        EntityType[] high = {EntityType.WITHER_SKELETON, EntityType.BREEZE, EntityType.ENDERMAN, EntityType.EVOKER};
        EntityType[] pool = rank.index() >= RankTier.S.index() ? high : rank.index() >= RankTier.C.index() ? mid : low;
        return pool[random.nextInt(pool.length)];
    }

    private boolean hasDungeonMobs(World world, long runId) {
        String tag = runTag(runId);
        return world.getEntities().stream().anyMatch(e -> e.isValid() && e.getScoreboardTags().contains(tag) && e instanceof LivingEntity && !(e instanceof Player));
    }

    private Long runIdFromTags(Entity entity) {
        for (String tag : entity.getScoreboardTags()) {
            if (tag.startsWith("impuls_drun_")) {
                try { return Long.parseLong(tag.substring("impuls_drun_".length())); }
                catch (NumberFormatException ignored) { return null; }
            }
        }
        return null;
    }

    private String runTag(long runId) { return "impuls_drun_" + runId; }

    private void clearDungeonTags(Player player, long runId) {
        player.removeScoreboardTag(TAG);
        player.removeScoreboardTag(runTag(runId));
    }

    private void giveDungeonKit(Player player, RankTier rank) {
        Material sword = rank.index() >= RankTier.B.index() ? Material.DIAMOND_SWORD : rank.index() >= RankTier.D.index() ? Material.IRON_SWORD : Material.STONE_SWORD;
        player.getInventory().addItem(new ItemStack(sword), new ItemStack(Material.COOKED_BEEF, 16), new ItemStack(Material.SHIELD));
    }

    private long cooldownSeconds() {
        return Math.max(1, plugin.getConfig().getLong("dungeon.cooldown-hours", 4)) * 3600L;
    }
}
