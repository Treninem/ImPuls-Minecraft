package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Safe city challenges with temporary inventories, restart recovery and anti-farm cooldowns. */
public final class CityChallengeService implements Listener {
    private static final String WORLD_NAME = "impuls_challenge_arena";
    private static final int MAZE_X = 0, MAZE_Z = 0, BUILD_Z = 180, WAVE_X = 120, WAVE_Z = 0;
    private final JavaPlugin plugin;
    private final Database db;
    private final SessionService sessions;
    private final NamespacedKey waveOwnerKey;
    private final Map<UUID, Set<String>> archeryHits = new HashMap<>();
    private final Map<UUID, BuildRun> builds = new HashMap<>();
    private final Map<UUID, WaveRun> waves = new HashMap<>();
    private final Set<UUID> maze = new HashSet<>();
    private final Set<UUID> pendingSafeRespawn = new HashSet<>();

    private record BuildRun(int cx, int cz, long started, Set<String> placed, Set<Material> materials) { }
    private static final class WaveRun {
        int wave;
        int alive;
        boolean failed;
        WaveRun(int wave) { this.wave = wave; }
    }

    private CityChallengeService(JavaPlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
        this.sessions = new SessionService(db);
        this.waveOwnerKey = new NamespacedKey(plugin, "safe_wave_owner");
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
        if (!(lower.startsWith("/impuls archery") || lower.startsWith("/impuls maze")
                || lower.startsWith("/impuls buildgame") || lower.startsWith("/impuls wavegame"))) return;
        event.setCancelled(true);
        String[] args = raw.split("\\s+");
        Player player = event.getPlayer();
        String mode = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (mode) {
            case "archery" -> archery(player, args);
            case "maze" -> maze(player, args);
            case "buildgame" -> buildGame(player, args);
            case "wavegame" -> waveGame(player, args);
            default -> { }
        }
    }

    private void archery(Player player, String[] args) {
        if (args.length > 2 && "leave".equalsIgnoreCase(args[2])) {
            archeryHits.remove(player.getUniqueId());
            restoreChallenge(player, "Стрельба завершена.");
            return;
        }
        if (sessions.hasSession(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "Сначала заверши другую игровую сессию.");
            return;
        }
        if (!sessions.save(player, "ARCHERY")) return;
        archeryHits.put(player.getUniqueId(), new HashSet<>());
        player.getInventory().clear();
        player.setGameMode(GameMode.ADVENTURE);
        giveArcheryKit(player);
        player.teleport(new Location(challengeWorld(), -55.5, 66, 0.5, -90f, 0f));
        player.sendMessage(ChatColor.GOLD + "[ImPuls] Стрельба: порази 5 разных мишеней. Обычный инвентарь сохранён отдельно.");
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
        } else player.sendMessage(ChatColor.GRAY + "Тренировка завершена без повторной награды.");
        restoreChallenge(player, "Обычный инвентарь восстановлен.");
    }

    private void maze(Player player, String[] args) {
        if (args.length > 2 && "leave".equalsIgnoreCase(args[2])) {
            maze.remove(player.getUniqueId());
            restoreChallenge(player, "Лабиринт покинут.");
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
        restoreChallenge(player, "Обычное состояние восстановлено.");
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
        builds.put(player.getUniqueId(), new BuildRun(cx, cz, Instant.now().getEpochSecond(), new HashSet<>(), new HashSet<>()));
        player.getInventory().clear();
        player.setGameMode(GameMode.CREATIVE);
        giveBuildPalette(player);
        player.teleport(new Location(challengeWorld(), cx + 0.5, 66, cz + 0.5));
        player.sendMessage(ChatColor.LIGHT_PURPLE + "[ImPuls] Строительный конкурс: отдельная зона 41×41. /impuls buildgame submit. Creative-предметы наружу не попадут.");
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        BuildRun run = builds.get(event.getPlayer().getUniqueId());
        if (run == null) return;
        if (!insidePlot(event.getBlock().getLocation(), run)) { event.setCancelled(true); return; }
        run.placed.add(key(event.getBlock()));
        run.materials.add(event.getBlockPlaced().getType());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        BuildRun run = builds.get(event.getPlayer().getUniqueId());
        if (run == null) return;
        if (!insidePlot(event.getBlock().getLocation(), run)) { event.setCancelled(true); return; }
        run.placed.remove(key(event.getBlock()));
    }

    private void finishBuild(Player player, boolean submit) {
        BuildRun run = builds.remove(player.getUniqueId());
        if (run == null) { restoreChallenge(player, "Строительная сессия закрыта."); return; }
        if (submit) {
            int blocks = run.placed.size(), variety = run.materials.size();
            int score = Math.min(100, blocks / 4 + variety * 3);
            long now = Instant.now().getEpochSecond();
            if (blocks >= 30 && db.cooldown(player.getUniqueId(), "buildgame_reward") <= now) {
                int reward = 50 + score;
                db.credit(player.getUniqueId(), reward, "buildgame:" + score);
                db.setCooldown(player.getUniqueId(), "buildgame_reward", now + 86400L);
                db.audit(player.getUniqueId(), "buildgame_submit", "blocks=" + blocks + ":variety=" + variety + ":score=" + score);
                player.sendMessage(ChatColor.GREEN + "Работа принята: оценка " + score + "/100, награда " + reward + " монет.");
            } else player.sendMessage(ChatColor.YELLOW + "Работа завершена без повторной награды.");
        }
        clearPlot(run.cx, run.cz);
        restoreChallenge(player, "Обычный инвентарь восстановлен.");
    }

    private void waveGame(Player player, String[] args) {
        if (args.length > 2 && "leave".equalsIgnoreCase(args[2])) {
            failWave(player, "Волновая арена покинута.");
            return;
        }
        if (sessions.hasSession(player.getUniqueId())) { player.sendMessage(ChatColor.RED + "Сначала заверши другую игровую сессию."); return; }
        if (!sessions.save(player, "WAVEGAME")) return;
        player.getInventory().clear();
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        giveWaveKit(player);
        player.addScoreboardTag("impuls_safe_game");
        player.teleport(new Location(challengeWorld(), WAVE_X + 0.5, 66, WAVE_Z + 0.5));
        WaveRun run = new WaveRun(1);
        waves.put(player.getUniqueId(), run);
        spawnWave(player, run);
        player.sendMessage(ChatColor.GOLD + "[ImPuls] Безопасная волновая арена: 5 волн. Смерть не тратит страховку и не теряет обычный инвентарь.");
    }

    private void spawnWave(Player player, WaveRun run) {
        if (!player.isOnline() || run.failed) return;
        int count = 2 + run.wave;
        run.alive = count;
        for (int i = 0; i < count; i++) {
            EntityType type = switch (run.wave) {
                case 1 -> EntityType.ZOMBIE;
                case 2 -> i % 2 == 0 ? EntityType.SKELETON : EntityType.ZOMBIE;
                case 3 -> i == 0 ? EntityType.VINDICATOR : EntityType.HUSK;
                case 4 -> i == 0 ? EntityType.RAVAGER : EntityType.VINDICATOR;
                default -> i == 0 ? EntityType.IRON_GOLEM : EntityType.VINDICATOR;
            };
            double angle = Math.PI * 2d * i / count;
            LivingEntity mob = (LivingEntity) challengeWorld().spawnEntity(
                    new Location(challengeWorld(), WAVE_X + Math.cos(angle) * 14d, 66, WAVE_Z + Math.sin(angle) * 14d), type);
            mob.getPersistentDataContainer().set(waveOwnerKey, PersistentDataType.STRING, player.getUniqueId().toString());
            mob.addScoreboardTag("impuls_safe_game_mob");
            mob.setCustomName(ChatColor.DARK_RED + "Арена — волна " + run.wave);
        }
        player.sendMessage(ChatColor.RED + "Волна " + run.wave + "/5: противников " + count + ".");
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent event) {
        String owner = event.getEntity().getPersistentDataContainer().get(waveOwnerKey, PersistentDataType.STRING);
        if (owner == null) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        UUID uuid;
        try { uuid = UUID.fromString(owner); } catch (IllegalArgumentException e) { return; }
        WaveRun run = waves.get(uuid);
        if (run == null || run.failed) return;
        run.alive = Math.max(0, run.alive - 1);
        if (run.alive > 0) return;
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return;
        if (run.wave >= 5) {
            waves.remove(uuid);
            removeWaveMobs(uuid);
            player.removeScoreboardTag("impuls_safe_game");
            long now = Instant.now().getEpochSecond();
            if (db.cooldown(uuid, "wavegame_reward") <= now) {
                db.credit(uuid, 180, "wavegame_complete");
                db.setCooldown(uuid, "wavegame_reward", now + 7200L);
                db.audit(uuid, "wavegame_complete", "5 waves");
                player.sendMessage(ChatColor.GREEN + "Все 5 волн пройдены: +180 монет.");
            }
            restoreChallenge(player, "Обычное состояние восстановлено.");
        } else {
            run.wave++;
            Bukkit.getScheduler().runTaskLater(plugin, () -> spawnWave(player, run), 60L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!player.getScoreboardTags().contains("impuls_safe_game")) return;
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        WaveRun run = waves.get(player.getUniqueId());
        if (run != null) run.failed = true;
        pendingSafeRespawn.add(player.getUniqueId());
        removeWaveMobs(player.getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!pendingSafeRespawn.remove(player.getUniqueId())) return;
        event.setRespawnLocation(challengeWorld().getSpawnLocation());
        Bukkit.getScheduler().runTask(plugin, () -> failWave(player, "Волновая арена провалена, но обычный инвентарь сохранён."));
    }

    private void failWave(Player player, String message) {
        UUID uuid = player.getUniqueId();
        waves.remove(uuid);
        pendingSafeRespawn.remove(uuid);
        removeWaveMobs(uuid);
        player.removeScoreboardTag("impuls_safe_game");
        restoreChallenge(player, message);
    }

    private void removeWaveMobs(UUID owner) {
        String id = owner.toString();
        for (LivingEntity entity : challengeWorld().getLivingEntities()) {
            String value = entity.getPersistentDataContainer().get(waveOwnerKey, PersistentDataType.STRING);
            if (id.equals(value)) entity.remove();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        archeryHits.remove(player.getUniqueId());
        maze.remove(player.getUniqueId());
        BuildRun build = builds.remove(player.getUniqueId());
        if (build != null) clearPlot(build.cx, build.cz);
        if (waves.remove(player.getUniqueId()) != null) removeWaveMobs(player.getUniqueId());
        player.removeScoreboardTag("impuls_safe_game");
        // Session snapshot is deliberately left in SQLite; onJoin restores the original state after reconnect/restart.
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String context = sessions.context(player.getUniqueId());
        if (context != null && (context.startsWith("ARCHERY") || context.startsWith("MAZE")
                || context.startsWith("BUILDGAME") || context.startsWith("WAVEGAME"))) {
            player.removeScoreboardTag("impuls_safe_game");
            sessions.restore(player);
            player.sendMessage(ChatColor.YELLOW + "[ImPuls] Незавершённая мини-игра после выхода/рестарта закрыта; обычное состояние восстановлено.");
        }
    }

    private void restoreChallenge(Player player, String message) {
        if (sessions.restore(player)) player.sendMessage(ChatColor.YELLOW + "[ImPuls] " + message);
    }

    private void bootstrapWorld() {
        World world = challengeWorld();
        buildArchery(world);
        buildMaze(world);
        buildWaveArena(world);
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
            world.setPVP(false);
            world.setSpawnLocation(new Location(world, 0.5, 66, -60.5));
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
            boolean wall = x == 0 || z == 0 || x == size - 1 || z == size - 1
                    || ((x % 4 == 0) && (z % 7 != 2)) || ((z % 6 == 0) && (x % 9 != 3));
            for (int h = 1; h <= 3; h++) world.getBlockAt(MAZE_X + x, 64 + h, MAZE_Z + z).setType(wall ? Material.STONE_BRICKS : Material.AIR, false);
        }
        for (int h = 1; h <= 3; h++) {
            world.getBlockAt(MAZE_X + 1, 64 + h, MAZE_Z).setType(Material.AIR, false);
            world.getBlockAt(MAZE_X + 39, 64 + h, MAZE_Z + 40).setType(Material.AIR, false);
        }
        world.getBlockAt(MAZE_X + 39, 65, MAZE_Z + 39).setType(Material.EMERALD_BLOCK, false);
    }

    private void buildWaveArena(World world) {
        int r = 22;
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
            world.getBlockAt(WAVE_X + x, 64, WAVE_Z + z).setType(Material.SMOOTH_STONE, false);
            if (Math.abs(x) == r || Math.abs(z) == r) {
                for (int h = 1; h <= 5; h++) world.getBlockAt(WAVE_X + x, 64 + h, WAVE_Z + z).setType(Material.DEEPSLATE_BRICKS, false);
            } else {
                for (int h = 1; h <= 4; h++) world.getBlockAt(WAVE_X + x, 64 + h, WAVE_Z + z).setType(Material.AIR, false);
            }
        }
    }

    private void clearPlot(int cx, int cz) {
        World world = challengeWorld();
        for (int x = -20; x <= 20; x++) for (int z = -20; z <= 20; z++) {
            world.getBlockAt(cx + x, 64, cz + z).setType(Material.SMOOTH_STONE, false);
            for (int y = 65; y <= 95; y++) world.getBlockAt(cx + x, y, cz + z).setType(Material.AIR, false);
        }
    }

    private boolean insidePlot(Location location, BuildRun run) {
        return location.getWorld() == challengeWorld() && Math.abs(location.getBlockX() - run.cx) <= 20
                && Math.abs(location.getBlockZ() - run.cz) <= 20 && location.getBlockY() >= 65 && location.getBlockY() <= 95;
    }

    private String key(Block block) { return block.getX() + ":" + block.getY() + ":" + block.getZ(); }

    private void giveArcheryKit(Player player) {
        player.getInventory().addItem(new ItemStack(Material.BOW), new ItemStack(Material.ARROW, 32));
    }

    private void giveWaveKit(Player player) {
        player.getInventory().addItem(new ItemStack(Material.IRON_SWORD), new ItemStack(Material.BOW),
                new ItemStack(Material.ARROW, 24), new ItemStack(Material.COOKED_BEEF, 16), new ItemStack(Material.SHIELD));
        player.getInventory().setHelmet(new ItemStack(Material.IRON_HELMET));
        player.getInventory().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        player.getInventory().setLeggings(new ItemStack(Material.IRON_LEGGINGS));
        player.getInventory().setBoots(new ItemStack(Material.IRON_BOOTS));
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
