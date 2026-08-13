package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Hard server-side region rules that do not depend on a client or command convention. */
public final class CityProtectionService implements Listener {
    private static final int CX = -688;
    private static final int CZ = -688;
    private static final int CITY_RADIUS = 1000;
    private static final int SANITARY_RADIUS = 1064;
    private static final int ROYAL_RADIUS = 180;
    private static final int CASTLE_X = CX + 355;
    private static final int CASTLE_Z = CZ + 350;

    private final Database db;

    private CityProtectionService(Database db) {
        this.db = db;
    }

    public static void start(JavaPlugin plugin, Database db) {
        Bukkit.getPluginManager().registerEvents(new CityProtectionService(db), plugin);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        if (event.getPlayer().hasPermission("impuls.admin")) return;
        Block block = event.getBlockPlaced();
        if (!protectedBuildArea(block.getLocation())) return;
        if (insideOwnClaim(event.getPlayer(), block.getLocation()) && !isRoyal(block.getLocation()) && !isSanitaryOnly(block.getLocation())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(ChatColor.RED + "[ImPuls] Здесь действует защита столицы/санитарной/королевской зоны.");
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        if (event.getPlayer().hasPermission("impuls.admin")) return;
        Location at = event.getBlock().getLocation();
        if (!protectedBuildArea(at)) return;
        if (insideOwnClaim(event.getPlayer(), at) && !isRoyal(at) && !isSanitaryOnly(at)) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(ChatColor.RED + "[ImPuls] Системные территории столицы защищены.");
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (event.getPlayer().hasPermission("impuls.admin")) return;
        Material bucket = event.getBucket();
        Location at = event.getBlock().getLocation();
        if (isCity(at) && (bucket == Material.LAVA_BUCKET || bucket == Material.WATER_BUCKET)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Жидкости нельзя разливать на системной территории города.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (isCity(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> protectedBuildArea(block.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> protectedBuildArea(block.getLocation()));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Monster)) return;
        if (!isCity(event.getLocation())) return;
        // Explicit ImPuls scripted entities are allowed; ordinary hostile city spawns are not.
        if (event.getEntity().getScoreboardTags().contains("impuls_wave") || event.getEntity().getScoreboardTags().contains("impuls_rank_trial")) return;
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null || event.getPlayer().hasPermission("impuls.admin")) return;
        if (!isRoyalOrCastle(to)) return;
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL || cause == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Королевскую защиту нельзя обходить жемчугом или хорусом.");
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getPlayer().hasPermission("impuls.admin")) return;
        Player player = event.getPlayer();
        if (!nearCastle(event.getTo())) return;
        if (player.getAllowFlight() && !player.isGliding()) {
            player.setFlying(false);
            player.setAllowFlight(false);
            player.sendMessage(ChatColor.YELLOW + "VIP-полёт отключён рядом с личным королевским замком.");
        }
    }

    private boolean protectedBuildArea(Location location) {
        return isCity(location) || isSanitaryOnly(location) || isRoyal(location) || nearCastle(location);
    }

    private boolean isCity(Location location) {
        return normal(location) && chebyshev(location, CX, CZ) <= CITY_RADIUS;
    }

    private boolean isSanitaryOnly(Location location) {
        if (!normal(location)) return false;
        int d = chebyshev(location, CX, CZ);
        return d > CITY_RADIUS && d <= SANITARY_RADIUS;
    }

    private boolean isRoyal(Location location) {
        return normal(location) && chebyshev(location, CX, CZ) <= ROYAL_RADIUS;
    }

    private boolean nearCastle(Location location) {
        return normal(location) && Math.abs(location.getX() - CASTLE_X) <= 90 && Math.abs(location.getZ() - CASTLE_Z) <= 90;
    }

    private boolean isRoyalOrCastle(Location location) {
        return isRoyal(location) || nearCastle(location);
    }

    private boolean insideOwnClaim(Player player, Location location) {
        if (location.getWorld() == null) return false;
        Database.Claim claim = db.claimAt(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        return claim != null && claim.owner().equals(player.getUniqueId());
    }

    private boolean normal(Location location) {
        return location.getWorld() != null && location.getWorld().getEnvironment() == World.Environment.NORMAL;
    }

    private int chebyshev(Location location, int x, int z) {
        return (int) Math.max(Math.abs(location.getX() - x), Math.abs(location.getZ() - z));
    }
}
