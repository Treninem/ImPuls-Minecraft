package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Adds sparse handcrafted landmarks to Nether and End while keeping vanilla exploration intact.
 * It never pregenerates whole dimensions and places blocks in small batches for low-RAM hosts.
 */
public final class RealmExpansionService implements Listener {
    private static final int BATCH = 120;
    private record Placement(World world, int x, int y, int z, Material material) { }

    private final JavaPlugin plugin;
    private final Database db;
    private final Deque<Placement> queue = new ArrayDeque<>();
    private final Set<UUID> announced = new HashSet<>();
    private boolean running;

    private RealmExpansionService(JavaPlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    public static void start(JavaPlugin plugin, Database db) {
        RealmExpansionService service = new RealmExpansionService(plugin, db);
        Bukkit.getPluginManager().registerEvents(service, plugin);
        Bukkit.getScheduler().runTaskLater(plugin, service::planLoadedRealms, 20L * 40L);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (event.getWorld().getEnvironment() == World.Environment.NETHER || event.getWorld().getEnvironment() == World.Environment.THE_END) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> plan(event.getWorld()), 100L);
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        World world = event.getPlayer().getWorld();
        if (world.getEnvironment() != World.Environment.NETHER && world.getEnvironment() != World.Environment.THE_END) return;
        if (!announced.add(event.getPlayer().getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> announced.remove(event.getPlayer().getUniqueId()), 1200L);
        String title = world.getEnvironment() == World.Environment.NETHER ? "Изменённый Нижний мир" : "Поздний Край";
        event.getPlayer().sendMessage(ChatColor.DARK_PURPLE + "[ImPuls] " + title + ": ванильная логика сохранена, но ищи крепости, руины, узлы и редкие зоны.");
    }

    private void planLoadedRealms() {
        for (World world : Bukkit.getWorlds()) plan(world);
    }

    private void plan(World world) {
        if (world.getEnvironment() != World.Environment.NETHER && world.getEnvironment() != World.Environment.THE_END) return;
        File marker = new File(plugin.getDataFolder(), "realm_" + world.getUID() + "_v13.done");
        if (marker.exists()) return;
        if (world.getEnvironment() == World.Environment.NETHER) planNether(world);
        else planEnd(world);
        if (!running) {
            running = true;
            Bukkit.getScheduler().runTaskTimer(plugin, this::drain, 1L, 2L);
        }
        try {
            plugin.getDataFolder().mkdirs();
            Files.writeString(marker.toPath(), "planned " + Instant.now() + "\n");
        } catch (IOException e) {
            plugin.getLogger().warning("Realm marker write failed: " + e.getMessage());
        }
    }

    private void planNether(World world) {
        Location spawn = world.getSpawnLocation();
        int sx = spawn.getBlockX(), sz = spawn.getBlockZ();
        // Four distinct regional landmarks 220-480 blocks from spawn: fortress, ruined city, ancient mine and portal node.
        fortress(world, sx + 260, clampY(world, 72), sz, Material.NETHER_BRICKS, Material.RED_NETHER_BRICKS);
        fortress(world, sx - 330, clampY(world, 68), sz + 190, Material.BLACKSTONE, Material.POLISHED_BLACKSTONE_BRICKS);
        mine(world, sx + 180, clampY(world, 34), sz - 360);
        portalNode(world, sx - 420, clampY(world, 64), sz - 250, true);
        lavaBridge(world, sx + 440, clampY(world, 72), sz + 310);
    }

    private void planEnd(World world) {
        Location spawn = world.getSpawnLocation();
        int sx = spawn.getBlockX(), sz = spawn.getBlockZ();
        floatingRuin(world, sx + 260, 86, sz + 120, Material.PURPUR_BLOCK);
        floatingRuin(world, sx - 340, 100, sz - 180, Material.END_STONE_BRICKS);
        crystalGarden(world, sx + 170, 82, sz - 360);
        portalNode(world, sx - 430, 92, sz + 280, false);
        voidTower(world, sx + 470, 96, sz + 360);
    }

    private void fortress(World world, int cx, int y, int cz, Material wall, Material trim) {
        int r = 18;
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
            if (Math.abs(x) == r || Math.abs(z) == r) {
                for (int h = 0; h < 9; h++) add(world, cx + x, y + h, cz + z, h % 4 == 0 ? trim : wall);
            } else if (Math.abs(x) <= 2 || Math.abs(z) <= 2) add(world, cx + x, y, cz + z, trim);
        }
        for (int dx : new int[]{-r, r}) for (int dz : new int[]{-r, r}) tower(world, cx + dx, y, cz + dz, wall);
        add(world, cx, y + 1, cz, Material.LODESTONE);
        add(world, cx + 4, y + 1, cz, Material.CHEST);
        Bukkit.getScheduler().runTaskLater(plugin, () -> fillChest(world, cx + 4, y + 1, cz, true), 200L);
    }

    private void mine(World world, int cx, int y, int cz) {
        for (int d = 0; d < 55; d++) {
            for (int x = -2; x <= 2; x++) for (int h = 0; h <= 3; h++) {
                if (Math.abs(x) == 2 || h == 3) add(world, cx + x, y + h, cz + d, Material.BLACKSTONE);
            }
            if (d % 8 == 0) {
                add(world, cx - 1, y + 1, cz + d, Material.SOUL_LANTERN);
                add(world, cx + 1, y + 1, cz + d, Material.SOUL_LANTERN);
            }
        }
        add(world, cx, y + 1, cz + 50, Material.CHEST);
        Bukkit.getScheduler().runTaskLater(plugin, () -> fillChest(world, cx, y + 1, cz + 50, true), 240L);
    }

    private void lavaBridge(World world, int cx, int y, int cz) {
        for (int x = -42; x <= 42; x++) {
            for (int z = -2; z <= 2; z++) add(world, cx + x, y, cz + z, Material.POLISHED_BLACKSTONE_BRICKS);
            if (x % 8 == 0) {
                add(world, cx + x, y + 1, cz - 3, Material.NETHER_BRICK_FENCE);
                add(world, cx + x, y + 1, cz + 3, Material.NETHER_BRICK_FENCE);
            }
        }
    }

    private void floatingRuin(World world, int cx, int y, int cz, Material material) {
        for (int depth = 0; depth < 7; depth++) {
            int r = 18 - depth * 2;
            for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
                if (x * x + z * z <= r * r) add(world, cx + x, y - depth, cz + z, depth < 2 ? Material.END_STONE : material);
            }
        }
        for (int x = -12; x <= 12; x += 6) for (int z = -12; z <= 12; z += 6) {
            for (int h = 1; h <= 8; h++) add(world, cx + x, y + h, cz + z, material);
        }
        add(world, cx, y + 1, cz, Material.ENDER_CHEST);
    }

    private void crystalGarden(World world, int cx, int y, int cz) {
        for (int i = 0; i < 20; i++) {
            int dx = ((i * 17) % 31) - 15;
            int dz = ((i * 11) % 31) - 15;
            add(world, cx + dx, y, cz + dz, Material.END_STONE_BRICKS);
            for (int h = 1; h <= 2 + (i % 6); h++) add(world, cx + dx, y + h, cz + dz, i % 3 == 0 ? Material.AMETHYST_BLOCK : Material.PURPUR_PILLAR);
        }
        add(world, cx, y + 1, cz, Material.CHEST);
        Bukkit.getScheduler().runTaskLater(plugin, () -> fillChest(world, cx, y + 1, cz, false), 220L);
    }

    private void voidTower(World world, int cx, int y, int cz) {
        for (int h = 0; h < 42; h++) {
            int r = h < 34 ? 7 : Math.max(2, 7 - (h - 34));
            for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
                if (Math.abs(x) == r || Math.abs(z) == r) add(world, cx + x, y + h, cz + z, h % 6 == 0 ? Material.PURPUR_PILLAR : Material.END_STONE_BRICKS);
            }
        }
        add(world, cx, y + 2, cz, Material.LODESTONE);
    }

    private void portalNode(World world, int cx, int y, int cz, boolean nether) {
        Material base = nether ? Material.CRYING_OBSIDIAN : Material.PURPUR_BLOCK;
        for (int x = -7; x <= 7; x++) for (int z = -7; z <= 7; z++) if (x * x + z * z <= 49) add(world, cx + x, y, cz + z, base);
        for (int h = 1; h <= 7; h++) {
            add(world, cx - 5, y + h, cz, Material.OBSIDIAN);
            add(world, cx + 5, y + h, cz, Material.OBSIDIAN);
        }
        add(world, cx, y + 1, cz, Material.LODESTONE);
    }

    private void tower(World world, int cx, int y, int cz, Material material) {
        for (int h = 0; h < 14; h++) for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            if (Math.abs(x) == 4 || Math.abs(z) == 4) add(world, cx + x, y + h, cz + z, material);
        }
    }

    private void fillChest(World world, int x, int y, int z, boolean nether) {
        Block block = world.getBlockAt(x, y, z);
        if (!(block.getState() instanceof Chest chest) || !chest.getInventory().isEmpty()) return;
        if (nether) chest.getInventory().addItem(new ItemStack(Material.GOLD_INGOT, 4), new ItemStack(Material.QUARTZ, 16), new ItemStack(Material.OBSIDIAN, 4));
        else chest.getInventory().addItem(new ItemStack(Material.ENDER_PEARL, 6), new ItemStack(Material.CHORUS_FRUIT, 12), new ItemStack(Material.AMETHYST_SHARD, 4));
        chest.update(true, false);
    }

    private void drain() {
        int work = 0;
        while (work++ < BATCH && !queue.isEmpty()) {
            Placement p = queue.pollFirst();
            Block block = p.world.getBlockAt(p.x, p.y, p.z);
            if (replaceable(block.getType(), p.world.getEnvironment())) block.setType(p.material, false);
        }
        if (queue.isEmpty()) running = false;
    }

    private boolean replaceable(Material material, World.Environment env) {
        if (material.isAir()) return true;
        if (env == World.Environment.NETHER) return material == Material.NETHERRACK || material == Material.BASALT || material == Material.BLACKSTONE || material == Material.SOUL_SAND || material == Material.SOUL_SOIL || material == Material.GRAVEL || material == Material.LAVA;
        return material == Material.END_STONE || material == Material.CHORUS_PLANT || material == Material.CHORUS_FLOWER;
    }

    private void add(World world, int x, int y, int z, Material material) {
        if (y <= world.getMinHeight() || y >= world.getMaxHeight() - 1) return;
        queue.addLast(new Placement(world, x, y, z, material));
    }

    private int clampY(World world, int y) {
        return Math.max(world.getMinHeight() + 8, Math.min(world.getMaxHeight() - 50, y));
    }
}
