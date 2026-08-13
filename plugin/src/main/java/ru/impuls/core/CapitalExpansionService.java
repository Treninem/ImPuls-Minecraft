package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Builds missing royal-capital landmarks in small batches. Existing crafted blocks are never overwritten.
 * The builder is idempotent and restarts safely after interruption.
 */
public final class CapitalExpansionService implements Listener {
    private static final int CX = -688;
    private static final int CZ = -688;
    private static final int BATCH = 180;

    private record Placement(int x, int y, int z, Material material) { }

    private final JavaPlugin plugin;
    private final Database db;
    private final Deque<Placement> queue = new ArrayDeque<>();
    private final File doneMarker;
    private boolean running;
    private int total;
    private int placed;
    private int skipped;

    private CapitalExpansionService(JavaPlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
        this.doneMarker = new File(plugin.getDataFolder(), "capital_v13.done");
    }

    public static void start(JavaPlugin plugin, Database db) {
        CapitalExpansionService service = new CapitalExpansionService(plugin, db);
        Bukkit.getPluginManager().registerEvents(service, plugin);
        if (!service.doneMarker.exists()) Bukkit.getScheduler().runTaskLater(plugin, service::begin, 20L * 20L);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage().trim();
        if (!raw.toLowerCase(Locale.ROOT).startsWith("/impuls capital")) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.hasPermission("impuls.admin")) {
            player.sendMessage(ChatColor.RED + "Требуется impuls.admin.");
            return;
        }
        String[] parts = raw.split("\\s+");
        String sub = parts.length > 2 ? parts[2].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "status" -> player.sendMessage(ChatColor.GOLD + "Capital builder: " + (running ? "RUNNING" : doneMarker.exists() ? "DONE" : "READY") + ChatColor.GRAY + " | " + placed + "/" + total + " | skipped=" + skipped);
            case "build" -> begin();
            case "rebuild" -> {
                if (doneMarker.exists()) doneMarker.delete();
                begin();
            }
            default -> player.sendMessage("/impuls capital status|build|rebuild");
        }
    }

    private void begin() {
        if (running) return;
        World world = primaryWorld();
        if (world == null) return;
        queue.clear();
        placed = 0;
        skipped = 0;
        planRoyalWall(world);
        planTownHall(world);
        planGuildHall(world);
        planDungeonHall(world);
        planArena(world);
        planPort(world);
        planFloatingCastle(world);
        total = queue.size();
        running = true;
        plugin.getLogger().info("Capital expansion queued: " + total + " safe placements");
        Bukkit.getScheduler().runTaskTimer(plugin, this::drain, 1L, 2L);
    }

    private void drain() {
        if (!running) return;
        World world = primaryWorld();
        if (world == null) return;
        int work = 0;
        while (work++ < BATCH && !queue.isEmpty()) {
            Placement p = queue.pollFirst();
            Block block = world.getBlockAt(p.x, p.y, p.z);
            if (canReplace(block.getType())) {
                block.setType(p.material, false);
                placed++;
            } else {
                skipped++;
            }
        }
        if (!queue.isEmpty()) return;
        running = false;
        try {
            plugin.getDataFolder().mkdirs();
            Files.writeString(doneMarker.toPath(), "ImPuls capital v1.3 completed " + Instant.now() + "\nplaced=" + placed + " skipped=" + skipped + "\n");
        } catch (IOException e) {
            plugin.getLogger().warning("Cannot write capital marker: " + e.getMessage());
        }
        db.audit(null, "capital_build_complete", "placed=" + placed + ":skipped=" + skipped);
        Bukkit.broadcastMessage(ChatColor.GOLD + "[ImPuls] Королевская зона и ключевые городские объекты подготовлены без перезаписи существующих построек.");
    }

    private boolean canReplace(Material material) {
        return material.isAir() || switch (material) {
            case GRASS_BLOCK, DIRT, COARSE_DIRT, PODZOL, ROOTED_DIRT, STONE, DEEPSLATE, SAND, RED_SAND,
                    GRAVEL, CLAY, SNOW, SNOW_BLOCK, SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN,
                    DANDELION, POPPY, BLUE_ORCHID, ALLIUM, AZURE_BLUET, RED_TULIP, ORANGE_TULIP,
                    WHITE_TULIP, PINK_TULIP, OXEYE_DAISY, CORNFLOWER, LILY_OF_THE_VALLEY, BROWN_MUSHROOM,
                    RED_MUSHROOM, OAK_LEAVES, BIRCH_LEAVES, SPRUCE_LEAVES, JUNGLE_LEAVES, ACACIA_LEAVES,
                    DARK_OAK_LEAVES, MANGROVE_LEAVES, CHERRY_LEAVES, PALE_OAK_LEAVES, WATER -> true;
            default -> false;
        };
    }

    private void planRoyalWall(World world) {
        int r = 170;
        for (int n = -r; n <= r; n += 2) {
            wallColumn(world, CX + n, CZ - r, gate(n, r));
            wallColumn(world, CX + n, CZ + r, gate(n, r));
            wallColumn(world, CX - r, CZ + n, gate(n, r));
            wallColumn(world, CX + r, CZ + n, gate(n, r));
        }
        // Corner towers.
        tower(world, CX - r, CZ - r, 5, 12);
        tower(world, CX + r, CZ - r, 5, 12);
        tower(world, CX - r, CZ + r, 5, 12);
        tower(world, CX + r, CZ + r, 5, 12);
    }

    private boolean gate(int offset, int radius) {
        return Math.abs(offset) <= 6 || Math.abs(Math.abs(offset) - radius / 2) <= 3;
    }

    private void wallColumn(World world, int x, int z, boolean gate) {
        if (gate) return;
        int y = world.getHighestBlockYAt(x, z) + 1;
        for (int h = 0; h < 7; h++) add(x, y + h, z, h == 6 ? Material.POLISHED_ANDESITE : Material.STONE_BRICKS);
        if ((x + z) % 12 == 0) add(x, y + 7, z, Material.LANTERN);
    }

    private void planTownHall(World world) {
        int x0 = CX - 28, z0 = CZ - 138;
        int y = world.getHighestBlockYAt(CX, CZ - 118) + 1;
        buildingShell(x0, y, z0, 56, 40, 13, Material.STONE_BRICKS, Material.DARK_OAK_PLANKS);
        // Grand entry and administration hall.
        for (int x = CX - 5; x <= CX + 5; x++) for (int z = z0 - 4; z <= z0; z++) add(x, y, z, Material.POLISHED_ANDESITE);
        for (int h = 1; h <= 6; h++) {
            add(CX - 7, y + h, z0, Material.STONE_BRICKS);
            add(CX + 7, y + h, z0, Material.STONE_BRICKS);
        }
        add(CX, y + 1, z0 + 25, Material.LECTERN);
        add(CX - 4, y + 1, z0 + 25, Material.BOOKSHELF);
        add(CX + 4, y + 1, z0 + 25, Material.BOOKSHELF);
        add(CX, y + 1, z0 + 32, Material.ENDER_CHEST);
    }

    private void planGuildHall(World world) {
        int cx = CX - 190, cz = CZ - 85;
        int y = world.getHighestBlockYAt(cx, cz) + 1;
        buildingShell(cx - 22, y, cz - 18, 44, 36, 11, Material.COBBLED_DEEPSLATE, Material.SPRUCE_PLANKS);
        add(cx, y + 1, cz + 10, Material.BELL);
        add(cx - 3, y + 1, cz + 10, Material.LECTERN);
        add(cx + 3, y + 1, cz + 10, Material.LECTERN);
    }

    private void planDungeonHall(World world) {
        int cx = CX + 205, cz = CZ - 105;
        int y = world.getHighestBlockYAt(cx, cz) + 1;
        buildingShell(cx - 26, y, cz - 22, 52, 44, 15, Material.DEEPSLATE_BRICKS, Material.POLISHED_BLACKSTONE_BRICKS);
        // Portal dais; intentionally no active Nether portal to avoid accidental dimension bypass.
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) {
            if (x * x + z * z <= 36) add(cx + x, y + 1, cz + z, Material.POLISHED_DEEPSLATE);
        }
        add(cx, y + 2, cz, Material.LODESTONE);
    }

    private void planArena(World world) {
        int cx = CX - 260, cz = CZ + 210;
        int y = world.getHighestBlockYAt(cx, cz) + 1;
        int r = 34;
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
            int d = x * x + z * z;
            if (d <= r * r && d >= (r - 3) * (r - 3)) {
                for (int h = 0; h < 5; h++) add(cx + x, y + h, cz + z, h < 2 ? Material.STONE_BRICKS : Material.OAK_FENCE);
            } else if (d < (r - 5) * (r - 5)) add(cx + x, y, cz + z, Material.SMOOTH_STONE);
        }
    }

    private void planPort(World world) {
        int cx = CX + 250, cz = CZ + 260;
        int y = world.getHighestBlockYAt(cx, cz) + 1;
        for (int z = 0; z < 48; z++) for (int x = -6; x <= 6; x++) add(cx + x, y, cz + z, Material.DARK_OAK_PLANKS);
        for (int z = 0; z < 48; z += 8) {
            add(cx - 7, y + 1, cz + z, Material.DARK_OAK_FENCE);
            add(cx + 7, y + 1, cz + z, Material.DARK_OAK_FENCE);
            add(cx - 7, y + 2, cz + z, Material.LANTERN);
            add(cx + 7, y + 2, cz + z, Material.LANTERN);
        }
    }

    private void planFloatingCastle(World world) {
        int cx = CX + 355, cz = CZ + 350;
        int terrain = world.getHighestBlockYAt(cx, cz);
        int islandY = Math.max(145, terrain + 42);
        // Small mountain/viewpoint below, deliberately leaving a clear air gap to the island.
        for (int level = 0; level < 22; level++) {
            int radius = Math.max(5, 22 - level);
            int y = terrain + 1 + level;
            for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
                if (x * x + z * z <= radius * radius) add(cx + x, y, cz + z, level > 14 ? Material.STONE : Material.GRASS_BLOCK);
            }
        }
        // Floating island, no blocks between mountain and island.
        for (int depth = 0; depth < 8; depth++) {
            int radius = 25 - depth * 2;
            for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
                if (x * x + z * z <= radius * radius) add(cx + x, islandY - depth, cz + z, depth < 2 ? Material.STONE_BRICKS : Material.DEEPSLATE);
            }
        }
        buildingShell(cx - 18, islandY + 1, cz - 16, 36, 32, 17, Material.POLISHED_BLACKSTONE_BRICKS, Material.DARK_OAK_PLANKS);
        towerAtFixedY(cx - 18, islandY + 1, cz - 16, 5, 24);
        towerAtFixedY(cx + 18, islandY + 1, cz - 16, 5, 24);
        towerAtFixedY(cx - 18, islandY + 1, cz + 16, 5, 24);
        towerAtFixedY(cx + 18, islandY + 1, cz + 16, 5, 24);
        // Throne, library, portal tower marker and treasury.
        add(cx, islandY + 2, cz + 8, Material.GOLD_BLOCK);
        add(cx, islandY + 3, cz + 8, Material.DARK_OAK_STAIRS);
        for (int i = -8; i <= 8; i += 2) {
            add(cx - 12, islandY + 2, cz + i, Material.BOOKSHELF);
            add(cx + 12, islandY + 2, cz + i, Material.BOOKSHELF);
        }
        add(cx, islandY + 2, cz - 10, Material.ENDER_CHEST);
        add(cx, islandY + 2, cz - 12, Material.LODESTONE);
        // Separated decorative basins on opposite sides of the mountain. No direct contact.
        basin(cx - 34, terrain + 2, cz, Material.WATER);
        basin(cx + 34, terrain + 2, cz, Material.LAVA);
    }

    private void basin(int cx, int y, int cz, Material liquid) {
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            boolean rim = Math.abs(x) == 4 || Math.abs(z) == 4;
            add(cx + x, y, cz + z, rim ? Material.STONE_BRICKS : liquid);
        }
    }

    private void buildingShell(int x0, int y, int z0, int width, int depth, int height, Material wall, Material floor) {
        for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) add(x0 + x, y, z0 + z, floor);
        for (int h = 1; h <= height; h++) {
            for (int x = 0; x < width; x++) {
                add(x0 + x, y + h, z0, wall);
                add(x0 + x, y + h, z0 + depth - 1, wall);
            }
            for (int z = 0; z < depth; z++) {
                add(x0, y + h, z0 + z, wall);
                add(x0 + width - 1, y + h, z0 + z, wall);
            }
        }
        for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) add(x0 + x, y + height + 1, z0 + z, Material.DARK_OAK_SLAB);
    }

    private void tower(World world, int cx, int cz, int radius, int height) {
        int y = world.getHighestBlockYAt(cx, cz) + 1;
        towerAtFixedY(cx, y, cz, radius, height);
    }

    private void towerAtFixedY(int cx, int y, int cz, int radius, int height) {
        for (int h = 0; h < height; h++) for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            if (Math.abs(x) == radius || Math.abs(z) == radius) add(cx + x, y + h, cz + z, Material.STONE_BRICKS);
        }
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) add(cx + x, y + height, cz + z, Material.DEEPSLATE_TILE_SLAB);
    }

    private void add(int x, int y, int z, Material material) {
        queue.addLast(new Placement(x, y, z, material));
    }

    private World primaryWorld() {
        for (World world : Bukkit.getWorlds()) if (world.getEnvironment() == World.Environment.NORMAL) return world;
        return null;
    }
}
