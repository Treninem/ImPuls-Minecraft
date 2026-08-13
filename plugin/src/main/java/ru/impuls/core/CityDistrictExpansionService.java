package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/** Non-destructive staged builder for the missing everyday capital districts. */
public final class CityDistrictExpansionService {
    private static final int CX = -688;
    private static final int CZ = -688;
    private static final int BATCH = 160;
    private record Placement(int x, int y, int z, Material material) { }

    private final JavaPlugin plugin;
    private final Database db;
    private final Deque<Placement> queue = new ArrayDeque<>();
    private final File marker;
    private int placed;
    private int skipped;

    private CityDistrictExpansionService(JavaPlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
        this.marker = new File(plugin.getDataFolder(), "districts_v13.done");
    }

    public static void start(JavaPlugin plugin, Database db) {
        CityDistrictExpansionService service = new CityDistrictExpansionService(plugin, db);
        if (!service.marker.exists()) Bukkit.getScheduler().runTaskLater(plugin, service::begin, 20L * 50L);
    }

    private void begin() {
        World world = primary();
        if (world == null) return;
        roads(world);
        market(world);
        residential(world);
        craft(world);
        farms(world);
        stables(world);
        barracks(world);
        tavernForgeWarehouse(world);
        canals(world);
        loggingYard(world);
        Bukkit.getScheduler().runTaskTimer(plugin, this::drain, 1L, 2L);
        plugin.getLogger().info("City district expansion queued: " + queue.size() + " placements");
    }

    private void roads(World world) {
        for (int d = -820; d <= 820; d++) {
            roadTile(world, CX + d, CZ, d);
            roadTile(world, CX, CZ + d, d);
        }
        for (int d = -600; d <= 600; d++) {
            roadTile(world, CX + d, CZ + 300, d);
            roadTile(world, CX + d, CZ - 300, d);
            roadTile(world, CX + 300, CZ + d, d);
            roadTile(world, CX - 300, CZ + d, d);
        }
    }

    private void roadTile(World world, int x, int z, int seed) {
        int y = world.getHighestBlockYAt(x, z);
        Material m = seed % 7 == 0 ? Material.POLISHED_ANDESITE : Material.STONE_BRICKS;
        add(x, y, z, m);
        if (seed % 24 == 0) {
            add(x + 2, y + 1, z + 2, Material.OAK_FENCE);
            add(x + 2, y + 2, z + 2, Material.LANTERN);
        }
    }

    private void market(World world) {
        int bx = CX + 80, bz = CZ - 115;
        for (int row = 0; row < 3; row++) for (int col = 0; col < 6; col++) {
            int x = bx + col * 15, z = bz + row * 18;
            int y = world.getHighestBlockYAt(x, z) + 1;
            stall(x, y, z, (row + col) % 2 == 0 ? Material.RED_WOOL : Material.YELLOW_WOOL);
        }
    }

    private void stall(int x, int y, int z, Material awning) {
        for (int dx = -4; dx <= 4; dx++) for (int dz = -3; dz <= 3; dz++) add(x + dx, y, z + dz, Material.SPRUCE_PLANKS);
        for (int dx : new int[]{-4, 4}) for (int dz : new int[]{-3, 3}) for (int h = 1; h <= 4; h++) add(x + dx, y + h, z + dz, Material.OAK_FENCE);
        for (int dx = -4; dx <= 4; dx++) for (int dz = -3; dz <= 3; dz++) add(x + dx, y + 5, z + dz, awning);
        add(x, y + 1, z, Material.BARREL);
    }

    private void residential(World world) {
        int[][] centers = {{CX - 420, CZ - 150}, {CX - 500, CZ + 40}, {CX - 380, CZ + 180}, {CX + 420, CZ - 150}, {CX + 480, CZ + 70}, {CX + 390, CZ + 190}};
        int i = 0;
        for (int[] c : centers) {
            int y = world.getHighestBlockYAt(c[0], c[1]) + 1;
            house(c[0], y, c[1], i++ % 2 == 0 ? Material.SPRUCE_PLANKS : Material.OAK_PLANKS);
        }
    }

    private void house(int cx, int y, int cz, Material timber) {
        int w = 9, d = 11;
        for (int x = -w; x <= w; x++) for (int z = -d; z <= d; z++) add(cx + x, y, cz + z, Material.COBBLESTONE);
        for (int h = 1; h <= 7; h++) for (int x = -w; x <= w; x++) for (int z = -d; z <= d; z++) {
            if (Math.abs(x) == w || Math.abs(z) == d) add(cx + x, y + h, cz + z, (x + z + h) % 5 == 0 ? timber : Material.WHITE_TERRACOTTA);
        }
        for (int x = -w - 1; x <= w + 1; x++) for (int z = -d - 1; z <= d + 1; z++) add(cx + x, y + 8, cz + z, Material.DARK_OAK_SLAB);
        add(cx, y + 1, cz - d, Material.OAK_DOOR);
        add(cx + 4, y + 3, cz - d, Material.GLASS_PANE);
        add(cx - 4, y + 3, cz - d, Material.GLASS_PANE);
    }

    private void craft(World world) {
        int cx = CX - 260, cz = CZ - 300;
        int y = world.getHighestBlockYAt(cx, cz) + 1;
        workshop(cx, y, cz, Material.SMITHING_TABLE, Material.BLAST_FURNACE);
        workshop(cx + 55, y, cz, Material.CRAFTING_TABLE, Material.STONECUTTER);
        workshop(cx + 110, y, cz, Material.LOOM, Material.CARTOGRAPHY_TABLE);
    }

    private void workshop(int cx, int y, int cz, Material stationA, Material stationB) {
        for (int x = -12; x <= 12; x++) for (int z = -9; z <= 9; z++) add(cx + x, y, cz + z, Material.STONE_BRICKS);
        for (int h = 1; h <= 7; h++) for (int x = -12; x <= 12; x++) for (int z = -9; z <= 9; z++) if (Math.abs(x) == 12 || Math.abs(z) == 9) add(cx + x, y + h, cz + z, h <= 2 ? Material.COBBLED_DEEPSLATE : Material.SPRUCE_PLANKS);
        add(cx - 3, y + 1, cz, stationA); add(cx + 3, y + 1, cz, stationB); add(cx, y + 1, cz + 4, Material.BARREL);
    }

    private void farms(World world) {
        int[][] farms = {{CX - 620, CZ + 520}, {CX - 500, CZ + 600}, {CX + 560, CZ + 570}};
        for (int[] c : farms) {
            int y = world.getHighestBlockYAt(c[0], c[1]);
            for (int x = -22; x <= 22; x++) for (int z = -16; z <= 16; z++) {
                if (x % 8 == 0) add(c[0] + x, y, c[1] + z, Material.WATER);
                else add(c[0] + x, y, c[1] + z, Material.FARMLAND);
                if (x % 8 != 0 && (x + z) % 2 == 0) add(c[0] + x, y + 1, c[1] + z, Material.WHEAT);
            }
            for (int x = -24; x <= 24; x++) { add(c[0] + x, y + 1, c[1] - 18, Material.OAK_FENCE); add(c[0] + x, y + 1, c[1] + 18, Material.OAK_FENCE); }
            for (int z = -18; z <= 18; z++) { add(c[0] - 24, y + 1, c[1] + z, Material.OAK_FENCE); add(c[0] + 24, y + 1, c[1] + z, Material.OAK_FENCE); }
        }
    }

    private void stables(World world) {
        int cx = CX - 510, cz = CZ + 330, y = world.getHighestBlockYAt(cx, cz) + 1;
        for (int x = -26; x <= 26; x++) for (int z = -12; z <= 12; z++) add(cx + x, y, cz + z, Material.COARSE_DIRT);
        for (int x = -27; x <= 27; x++) { add(cx + x, y + 1, cz - 13, Material.DARK_OAK_FENCE); add(cx + x, y + 1, cz + 13, Material.DARK_OAK_FENCE); }
        for (int z = -13; z <= 13; z++) { add(cx - 27, y + 1, cz + z, Material.DARK_OAK_FENCE); add(cx + 27, y + 1, cz + z, Material.DARK_OAK_FENCE); }
        for (int x = -20; x <= 20; x += 8) { add(cx + x, y + 1, cz, Material.HAY_BLOCK); add(cx + x, y + 1, cz + 4, Material.CAULDRON); }
    }

    private void barracks(World world) {
        int cx = CX + 520, cz = CZ - 420, y = world.getHighestBlockYAt(cx, cz) + 1;
        for (int side = -1; side <= 1; side += 2) house(cx + side * 20, y, cz, Material.DARK_OAK_PLANKS);
        for (int x = -35; x <= 35; x++) for (int z = -28; z <= 28; z++) if (Math.abs(x) == 35 || Math.abs(z) == 28) add(cx + x, y + 1, cz + z, Material.STONE_BRICK_WALL);
        add(cx, y + 1, cz, Material.BELL);
    }

    private void tavernForgeWarehouse(World world) {
        int y1 = world.getHighestBlockYAt(CX + 330, CZ + 30) + 1;
        house(CX + 330, y1, CZ + 30, Material.SPRUCE_PLANKS);
        add(CX + 330, y1 + 1, CZ + 30, Material.BREWING_STAND);
        add(CX + 325, y1 + 1, CZ + 30, Material.BARREL);
        int y2 = world.getHighestBlockYAt(CX + 330, CZ - 20) + 1;
        workshop(CX + 330, y2, CZ - 20, Material.ANVIL, Material.BLAST_FURNACE);
        int y3 = world.getHighestBlockYAt(CX + 360, CZ + 95) + 1;
        for (int x = -14; x <= 14; x++) for (int z = -12; z <= 12; z++) {
            add(CX + 360 + x, y3, CZ + 95 + z, Material.SPRUCE_PLANKS);
            if (Math.abs(x) == 14 || Math.abs(z) == 12) for (int h = 1; h <= 7; h++) add(CX + 360 + x, y3 + h, CZ + 95 + z, Material.STONE_BRICKS);
        }
        for (int x = -9; x <= 9; x += 3) for (int z = -7; z <= 7; z += 3) add(CX + 360 + x, y3 + 1, CZ + 95 + z, Material.BARREL);
    }

    private void canals(World world) {
        int baseY = world.getHighestBlockYAt(CX + 245, CZ + 245) - 2;
        for (int d = -260; d <= 260; d++) {
            canalSlice(CX + 250, baseY, CZ + d);
            canalSlice(CX + d, baseY, CZ + 250);
        }
        // Bridges every ~100 blocks.
        for (int d = -200; d <= 200; d += 100) {
            for (int x = -5; x <= 5; x++) for (int z = -4; z <= 4; z++) add(CX + 250 + x, baseY + 2, CZ + d + z, Material.SPRUCE_PLANKS);
            for (int x = -4; x <= 4; x++) for (int z = -5; z <= 5; z++) add(CX + d + x, baseY + 2, CZ + 250 + z, Material.SPRUCE_PLANKS);
        }
    }

    private void canalSlice(int cx, int y, int cz) {
        for (int w = -4; w <= 4; w++) {
            add(cx + w, y, cz, Material.STONE_BRICKS);
            add(cx + w, y + 1, cz, Math.abs(w) == 4 ? Material.STONE_BRICKS : Material.WATER);
        }
    }

    private void loggingYard(World world) {
        int cx = CX - 650, cz = CZ - 470, y = world.getHighestBlockYAt(cx, cz) + 1;
        for (int i = 0; i < 16; i++) {
            int x = cx - 24 + (i % 8) * 7, z = cz - 10 + (i / 8) * 16;
            for (int h = 0; h < 3; h++) add(x, y + h, z, i % 2 == 0 ? Material.SPRUCE_LOG : Material.OAK_LOG);
        }
        add(cx, y + 1, cz, Material.STONECUTTER); add(cx + 4, y + 1, cz, Material.CRAFTING_TABLE);
    }

    private void drain() {
        World world = primary();
        if (world == null) return;
        int n = 0;
        while (n++ < BATCH && !queue.isEmpty()) {
            Placement p = queue.pollFirst();
            Block block = world.getBlockAt(p.x, p.y, p.z);
            if (replaceable(block.getType())) { block.setType(p.material, false); placed++; } else skipped++;
        }
        if (!queue.isEmpty()) return;
        try { plugin.getDataFolder().mkdirs(); Files.writeString(marker.toPath(), "completed " + Instant.now() + "\nplaced=" + placed + " skipped=" + skipped + "\n"); } catch (Exception ignored) {}
        db.audit(null, "city_districts_complete", "placed=" + placed + ":skipped=" + skipped);
        Bukkit.broadcastMessage(ChatColor.GOLD + "[ImPuls] Городские районы и инфраструктура v1.3 достроены с сохранением существующих рукотворных блоков.");
        Bukkit.getScheduler().cancelTasks(plugin); // all delayed bootstrap work has already started; plugin remains functional through event listeners
    }

    private boolean replaceable(Material m) {
        return m.isAir() || switch (m) {
            case GRASS_BLOCK, DIRT, COARSE_DIRT, PODZOL, ROOTED_DIRT, STONE, DEEPSLATE, SAND, RED_SAND, GRAVEL, CLAY,
                    SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN, DANDELION, POPPY, BLUE_ORCHID, ALLIUM, AZURE_BLUET,
                    RED_TULIP, ORANGE_TULIP, WHITE_TULIP, PINK_TULIP, OXEYE_DAISY, CORNFLOWER, LILY_OF_THE_VALLEY,
                    OAK_LEAVES, SPRUCE_LEAVES, BIRCH_LEAVES, JUNGLE_LEAVES, ACACIA_LEAVES, DARK_OAK_LEAVES,
                    MANGROVE_LEAVES, CHERRY_LEAVES, PALE_OAK_LEAVES, WATER -> true;
            default -> false;
        };
    }

    private void add(int x, int y, int z, Material material) { queue.addLast(new Placement(x, y, z, material)); }
    private World primary() { for (World world : Bukkit.getWorlds()) if (world.getEnvironment() == World.Environment.NORMAL) return world; return null; }
}
