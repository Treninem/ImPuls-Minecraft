package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class WorldExpansionService implements Listener {
    private static final int CX = -688;
    private static final int CZ = -688;
    private static final double MAX_WORLD_DIAMETER = 59_999_968d;

    private final JavaPlugin plugin;
    private final Database db;
    private final Set<UUID> announced = new HashSet<>();

    private WorldExpansionService(JavaPlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    public static void start(JavaPlugin plugin, Database db) {
        WorldExpansionService service = new WorldExpansionService(plugin, db);
        Bukkit.getPluginManager().registerEvents(service, plugin);
        Bukkit.getScheduler().runTaskLater(plugin, service::bootstrapLoadedWorlds, 200L);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> bootstrap(event.getWorld()), 40L);
    }

    public void announceRegion(Player player) {
        if (!announced.add(player.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> announced.remove(player.getUniqueId()), 1200L);
        Block block = player.getLocation().getBlock();
        player.sendMessage(ChatColor.GRAY + "[ImPuls] Регион: " + block.getBiome().getKey().getKey()
                + " | X=" + block.getX() + " Z=" + block.getZ());
    }

    private void bootstrapLoadedWorlds() {
        for (World world : Bukkit.getWorlds()) bootstrap(world);
    }

    private void bootstrap(World world) {
        switch (world.getEnvironment()) {
            case NORMAL -> bootstrapOverworld(world);
            case NETHER -> bootstrapNether(world);
            case THE_END -> bootstrapEnd(world);
            default -> { }
        }
    }

    private void bootstrapOverworld(World world) {
        world.getWorldBorder().setCenter(0, 0);
        world.getWorldBorder().setSize(MAX_WORLD_DIAMETER);

        int[][] sites = {
                {CX, CZ - 1450},
                {CX + 1120, CZ - 1120},
                {CX + 1550, CZ},
                {CX + 1180, CZ + 1180},
                {CX, CZ + 1600},
                {CX - 1200, CZ + 1200},
                {CX - 1600, CZ},
                {CX - 1150, CZ - 1150},
                {CX + 2050, CZ - 450},
                {CX - 2100, CZ + 500},
                {CX + 500, CZ + 2200},
                {CX - 450, CZ - 2200}
        };
        for (int i = 0; i < sites.length; i++) {
            int x = sites[i][0];
            int z = sites[i][1];
            int y = Math.max(world.getMinHeight() + 10, world.getHighestBlockYAt(x, z));
            Material marker = i % 3 == 0 ? Material.LODESTONE : i % 3 == 1 ? Material.COPPER_BLOCK : Material.DEEPSLATE_BRICKS;
            if (world.getBlockAt(x, y + 1, z).getType() == marker) continue;
            buildResourceSite(world, x, y, z, marker, i);
        }
    }

    private void buildResourceSite(World world, int x, int y, int z, Material marker, int index) {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                world.getBlockAt(x + dx, y, z + dz).setType(Material.COBBLED_DEEPSLATE, false);
                if (Math.abs(dx) == 4 || Math.abs(dz) == 4) {
                    world.getBlockAt(x + dx, y + 1, z + dz).setType(Material.DEEPSLATE_BRICKS, false);
                }
            }
        }
        world.getBlockAt(x, y + 1, z).setType(marker, false);
        world.getBlockAt(x + 2, y + 1, z).setType(Material.CHEST, false);
        if (world.getBlockAt(x + 2, y + 1, z).getState() instanceof Chest chest) {
            chest.getInventory().clear();
            chest.getInventory().addItem(
                    new ItemStack(Material.TORCH, 24),
                    new ItemStack(Material.BREAD, 8),
                    new ItemStack(index % 2 == 0 ? Material.IRON_PICKAXE : Material.IRON_AXE),
                    new ItemStack(Material.IRON_INGOT, 2 + index % 4));
            chest.update(true, false);
        }
        for (int depth = 1; depth <= 12; depth++) {
            world.getBlockAt(x - 2, y - depth, z).setType(Material.AIR, false);
            world.getBlockAt(x - 1, y - depth, z).setType(Material.AIR, false);
            world.getBlockAt(x - 2, y - depth, z + 1).setType(Material.AIR, false);
            world.getBlockAt(x - 1, y - depth, z + 1).setType(Material.AIR, false);
            if (depth % 3 == 0) world.getBlockAt(x, y - depth, z).setType(Material.LANTERN, false);
        }
    }

    private void bootstrapNether(World world) {
        Location spawn = world.getSpawnLocation();
        int cx = spawn.getBlockX();
        int cy = Math.max(world.getMinHeight() + 5, spawn.getBlockY() - 1);
        int cz = spawn.getBlockZ();
        if (world.getBlockAt(cx, cy + 1, cz).getType() == Material.LODESTONE) return;
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d <= 10) world.getBlockAt(cx + x, cy, cz + z).setType(d > 8 ? Material.BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS, false);
            }
        }
        world.getBlockAt(cx, cy + 1, cz).setType(Material.LODESTONE, false);
        world.getBlockAt(cx + 3, cy + 1, cz).setType(Material.RESPAWN_ANCHOR, false);
        world.getBlockAt(cx - 3, cy + 1, cz).setType(Material.SOUL_LANTERN, false);
    }

    private void bootstrapEnd(World world) {
        Location spawn = world.getSpawnLocation();
        int cx = spawn.getBlockX();
        int cy = Math.max(50, spawn.getBlockY() - 1);
        int cz = spawn.getBlockZ();
        if (world.getBlockAt(cx, cy + 1, cz).getType() == Material.LODESTONE) return;
        for (int x = -9; x <= 9; x++) {
            for (int z = -9; z <= 9; z++) {
                if (x * x + z * z <= 81) world.getBlockAt(cx + x, cy, cz + z).setType(Material.END_STONE_BRICKS, false);
            }
        }
        world.getBlockAt(cx, cy + 1, cz).setType(Material.LODESTONE, false);
        world.getBlockAt(cx + 4, cy + 1, cz).setType(Material.ENDER_CHEST, false);
        world.getBlockAt(cx - 4, cy + 1, cz).setType(Material.PURPUR_PILLAR, false);
    }
}
