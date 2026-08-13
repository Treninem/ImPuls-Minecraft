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
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Archery, maze and isolated build challenge with persistent inventory recovery and anti-farm cooldowns. */
public final class CityChallengeService implements Listener {
    private static final String WORLD_NAME = "impuls_challenge_arena";
    private static final int MAZE_X = 0;
    private static final int MAZE_Z = 0;
    private static final int BUILD_Z = 180;

    private final JavaPlugin plugin;
    private final Database db;
    private final SessionService sessions;
    private final Map<UUID, Set<String>> archeryHits = new HashMap<>();
    private final Map<UUID, BuildRun> builds = new HashMap<>();
    private final Set<UUID> maze = new HashSet<>();

    private record BuildRun(int cx, int cz, long started, Set<String> placed, Set<Material> materials) { }

    private CityChallengeService(JavaPlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
        this.sessions = new SessionService(db);
    }

    public static void start(JavaPlugin plugin, Database db) {
        CityChallengeService service = new CityChallengeService(plugin, db);
        Bukkit.getPluginManager().registerEvents(service, plugin);
        Bukkit.getScheduler().runTaskLater(plugin, service::bootstrapWorld, 100L);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage().trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("/impuls archery") || lower.startsWith("/impuls maze") || lower.startsWith("/impuls buildgame"))) return;
        event.setCancelled(true);
        String[] args = raw.split("\\s+");
        Player player = event.getPlayer();
        String mode = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        if ("archery".equals(mode)) archery(player, args);
        else if ("maze".equals(mode)) maze(player, args);
        else buildGame(player, args);
    }

    private void archery(Player player, String[] args) {
        if (args.length > 2 && "leave".equalsIgnoreCase(args[2])) {
            archeryHits.remove(player.getUniqueId());
            player.sendMessage(ChatColor.YELLOW + "Стрельба завершена.");
            return;
        }
        long until = db.cooldown(player.getUniqueId(), "archery_reward");
        if (until > Instant.now().getEpochSecond()) {
            player.sendMessage(ChatColor.YELLOW + "Наградный заход доступен позже; тренироваться можно без награды.");
        }
        archeryHits.put(player.getUniqueId(), new HashSet<>());
        giveArcheryKit(player);
        player.teleport(new Location(challengeWorld(), -55.5, 66, 0.5, -90f, 0f));
        player.sendMessage(ChatColor.GOLD + "[ImPuls] Стрельба: порази 5 разных мишеней. Обычный инвентарь не очищается — выдаётся только лук и стрелы.");
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player) || !archeryHits.containsKey(player.getUniqueId())) return;
        Block hit = event.getHitBlock();
        if (hit == null || hit.getType() != Material.TARGET || hit.getWorld() != challengeWorld()) return;
        String key = hit.getX() + ":" + hit.getY() + ":" + hit.getZ();
        Set<String> hits = archeryHits.get(player.getUniqueId());
        if (!hits.add(key)) return;
        player.sendMessage(ChatColor.AQUA + "Мишени: " + hits.size() + "/5");
        if (hits.size() < 5) return;
        archeryHits.remove(player.getUniqueId());
        long now = Instant.now().getEpochSecond();
        if (db.cooldown(player.getUniqueId(), "archery_reward") <= now) {
            db.credit(player.getUniqueId(), 100, "archery_complete");
            db.setCooldown(player.getUniqueId(), "archery_reward", now + 3600L);
            db.audit(player.getUniqueId(), "archery_complete", "5 targets");
            player.sendMessage(ChatColor.GREEN + "Стрельба завершена: +100 монет. Повторная награда через час.");
        }
    }

    private void maze(Player player, String[] args) {
        if (args.length > 2 && "leave".equalsIgnoreCase(args[2])) {
            leaveSession(player, "Лабиринт покинут.");
            return;
        }
        if (sessions.hasSession(player.getUniqueId())) { player.sendMessage(ChatColor.RED + "Сначала заверши другую игровую сессию."); return; }
        if (!sessions.save(player, "MAZE")) return;
        maze.add(player.getUniqueId());
        player.getInventory().clear();
        player.setGameMode(GameMode.ADVENTURE);
        player.teleport(new Location(challengeWorld(), MAZE_X + 1.5, 66, MAZE_Z + 1.5));
        player.sendMessage(ChatColor.GOLD + "[ImPuls] Лабиринт: найди выход. Обычный инвентарь сохранён отдельно.");
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || !maze.contains(event.getPlayer().getUniqueId())) return;
        if (event.getTo().getWorld() != challengeWorld()) return;
        if (event.getTo().distanceSquared(new Location(challengeWorld(), MAZE_X + 39.5, 66, MAZE_Z + 39.5)) > 9d) return;
        Player player = event.getPlayer();
        maze.remove(player.getUniqueId());
        long now = Instant.now().getEpochSecond();
        if (db.cooldown(player.getUniqueId(), "maze_reward") <= now) {
            db.credit(player.getUniqueId(), 130, "maze_complete");
            db.setCooldown(player.getUniqueId(), "maze_reward", now + 7200L);
            db.audit(player.getUniqueId(), "maze_complete", "challenge arena");
            player.sendMessage(ChatColor.GREEN + "Лабиринт пройден: +130 монет.");
        }
        sessions.restore(player);
    }

    private void buildGame(Player player, String[] args) {
        String sub = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "enter";
        if ("leave".equals(sub)) { finishBuild(player, false); return; }
        if ("submit".equals(sub)) { finishBuild(player, true); return; }
        if (sessions.hasSession(player.getUniqueId())) { player.sendMessage(ChatColor.RED + "Сначала заверши другую игровую сессию."); return; }
        if (!sessions.save(player, "BUILDGAME")) return;
        int slot = Math.floorMod(player.getUniqueId().hashCode(), 12);
        int cx = -220 + (slot % 4) * 70;
        int cz = BUILD_Z + (slot / 4) * 70;
        clearPlot(cx, cz);
        BuildRun run = new BuildRun(cx, cz, Instant.now().getEpochSecond(), new HashSet<>(), new HashSet<>());
        builds.put(player.getUniqueId(), run);
        player.getInventory().clear();
        player.setGameMode(GameMode.CREATIVE);
        giveBuildPalette(player);
        player.teleport(new Location(challengeWorld(), cx + 0.5, 66, cz + 0.5));
        player.sendMessage(ChatColor.LIGHT_PURPLE + "[ImPuls] Строительный конкурс: отдельная зона 41×41. Построй работу и /impuls buildgame submit. Предметы отсюда не попадут в Survival.");
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        BuildRun run = builds.get(event.getPlayer().getUniqueId());
        if (run == null) return;
        if (!insidePlot(event.getBlock().getLocation(), run)) {
            event.setCancelled(true);
            return;
        }
        run.placed.add(key(event.getBlock()));
        run.materials.add(event.getBlockPlaced().getType());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        BuildRun run = builds.get(event.getPlayer().getUniqueId());
        if (run == null) return;
        if (!insidePlot(event.getBlock().getLocation(), run)) {
            event.setCancelled(true);
            return;
        }
        run.placed.remove(key(event.getBlock()));
    }

    private void finishBuild(Player player, boolean submit) {
        BuildRun run = builds.remove(player.getUniqueId());
        if (run == null) {
            if (sessions.context(player.getUniqueId()) != null && sessions.context(player.getUniqueId()).startsWith("BUILDGAME")) sessions.restore(player);
            return;
        }
        if (submit) {
            int blocks = run.placed.size();
            int variety = run.materials.size();
            int score = Math.min(100, blocks / 4 + variety * 3);
            long now = Instant.now().getEpochSecond();
            if (blocks >= 30 && db.cooldown(player.getUniqueId(), "buildgame_reward") <= now) {
                int reward = 50 + score;
                db.credit(player.getUniqueId(), reward, "buildgame:" + score);
                db.setCooldown(player.getUniqueId(), "buildgame_reward", now + 86400L);
                db.audit(player.getUniqueId(), "buildgame_submit", "blocks=" + blocks + ":variety=" + variety + ":score=" + score);
                player.sendMessage(ChatColor.GREEN + "Работа принята: оценка " + score + "/100, награда " + reward + " монет. Следующая награда завтра.");
            } else player.sendMessage(ChatColor.YELLOW + "Работа сохранена в журнале без повторной награды (нужно ≥30 блоков, награда раз в сутки). ");
        }
        clearPlot(run.cx, run.cz);
        sessions.restore(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        archeryHits.remove(event.getPlayer().getUniqueId());
        if (maze.remove(event.getPlayer().getUniqueId())) return; // persistent SessionService restores on next join
        builds.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        String context = sessions.context(event.getPlayer().getUniqueId());
        if (context != null && (context.startsWith("MAZE") || context.startsWith("BUILDGAME"))) {
            sessions.restore(event.getPlayer());
            event.getPlayer().sendMessage(ChatColor.YELLOW + "[ImPuls] Незавершённая мини-игра после выхода/рестарта закрыта; обычное состояние восстановлено.");
        }
    }

    private void leaveSession(Player player, String message) {
        maze.remove(player.getUniqueId());
        builds.remove(player.getUniqueId());
        if (sessions.restore(player)) player.sendMessage(ChatColor.YELLOW + message);
    }

    private void bootstrapWorld() {
        World world = challengeWorld();
        buildArchery(world);
        buildMaze(world);
    }

    private World challengeWorld() {
        World world = Bukkit.getWorld(WORLD_NAME);
        if (world == null) {
            WorldCreator creator = new WorldCreator(WORLD_NAME);
            creator.type(WorldType.FLAT);
            creator.generateStructures(false);
            world = creator.createWorld();
            if (world == null) throw new IllegalStateException("Cannot create challenge world");
            world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            world.setTime(6000L);
        }
        return world;
    }

    private void buildArchery(World world) {
        for (int i = 0; i < 5; i++) {
            int x = -30 + i * 8;
            int y = 66 + (i % 3) * 2;
            int z = -8 + i * 4;
            world.getBlockAt(x, y, z).setType(Material.TARGET, false);
            for (int h = 0; h < y - 65; h++) world.getBlockAt(x, 65 + h, z).setType(Material.OAK_FENCE, false);
        }
    }

    private void buildMaze(World world) {
        int size = 41;
        for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) {
            world.getBlockAt(MAZE_X + x, 64, MAZE_Z + z).setType(Material.SMOOTH_STONE, false);
            boolean wall = x == 0 || z == 0 || x == size - 1 || z == size - 1 || ((x % 4 == 0) && (z % 7 != 2)) || ((z % 6 == 0) && (x % 9 != 3));
            for (int h = 1; h <= 3; h++) world.getBlockAt(MAZE_X + x, 64 + h, MAZE_Z + z).setType(wall ? Material.STONE_BRICKS : Material.AIR, false);
        }
        for (int h = 1; h <= 3; h++) {
            world.getBlockAt(MAZE_X + 1, 64 + h, MAZE_Z).setType(Material.AIR, false);
            world.getBlockAt(MAZE_X + 39, 64 + h, MAZE_Z + 40).setType(Material.AIR, false);
        }
        world.getBlockAt(MAZE_X + 39, 65, MAZE_Z + 39).setType(Material.EMERALD_BLOCK, false);
    }

    private void clearPlot(int cx, int cz) {
        World world = challengeWorld();
        for (int x = -20; x <= 20; x++) for (int z = -20; z <= 20; z++) {
            world.getBlockAt(cx + x, 64, cz + z).setType(Material.SMOOTH_STONE, false);
            for (int y = 65; y <= 95; y++) world.getBlockAt(cx + x, y, cz + z).setType(Material.AIR, false);
        }
    }

    private boolean insidePlot(Location location, BuildRun run) {
        return location.getWorld() == challengeWorld() && Math.abs(location.getBlockX() - run.cx) <= 20 && Math.abs(location.getBlockZ() - run.cz) <= 20 && location.getBlockY() >= 65 && location.getBlockY() <= 95;
    }

    private String key(Block block) { return block.getX() + ":" + block.getY() + ":" + block.getZ(); }

    private void giveArcheryKit(Player player) {
        if (!player.getInventory().contains(Material.BOW)) player.getInventory().addItem(new ItemStack(Material.BOW));
        player.getInventory().addItem(new ItemStack(Material.ARROW, 32));
    }

    private void giveBuildPalette(Player player) {
        player.getInventory().addItem(
                new ItemStack(Material.STONE_BRICKS, 64), new ItemStack(Material.OAK_PLANKS, 64),
                new ItemStack(Material.SPRUCE_PLANKS, 64), new ItemStack(Material.DARK_OAK_PLANKS, 64),
                new ItemStack(Material.COBBLED_DEEPSLATE, 64), new ItemStack(Material.GLASS, 64),
                new ItemStack(Material.WHITE_WOOL, 64), new ItemStack(Material.RED_WOOL, 64),
                new ItemStack(Material.LANTERN, 32), new ItemStack(Material.OAK_STAIRS, 64));
    }
}
