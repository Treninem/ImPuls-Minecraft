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
    private static final int CX = -688, CZ = -688, CITY_RADIUS = 1000, SANITARY_RADIUS = 1064, ROYAL_RADIUS = 180;
    private static final int CASTLE_X = CX + 355, CASTLE_Z = CZ + 350;
    private final Database db;

    private CityProtectionService(Database db) { this.db = db; }

    public static void start(JavaPlugin plugin, Database db) {
        Bukkit.getPluginManager().registerEvents(new CityProtectionService(db), plugin);
        ExtendedFeatureService.start(plugin, db);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        if (event.getPlayer().hasPermission("impuls.admin")) return;
        Block block = event.getBlockPlaced();
        if (!protectedBuildArea(block.getLocation())) return;
        if (insideOwnClaim(event.getPlayer(), block.getLocation()) && !isRoyal(block.getLocation()) && !isSanitaryOnly(block.getLocation())) return;
        if (GuildExpansionService.canBuildGuildBase(event.getPlayer(), block.getLocation()) && !isRoyal(block.getLocation()) && !isSanitaryOnly(block.getLocation())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(ChatColor.RED + "[ImPuls] Здесь действует защита столицы/санитарной/королевской зоны.");
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        if (event.getPlayer().hasPermission("impuls.admin")) return;
        Location at = event.getBlock().getLocation();
        if (!protectedBuildArea(at)) return;
        if (insideOwnClaim(event.getPlayer(), at) && !isRoyal(at) && !isSanitaryOnly(at)) return;
        if (GuildExpansionService.canBuildGuildBase(event.getPlayer(), at) && !isRoyal(at) && !isSanitaryOnly(at)) return;
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
    public void onIgnite(BlockIgniteEvent event) { if (isCity(event.getBlock().getLocation())) event.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) { event.blockList().removeIf(block -> protectedBuildArea(block.getLocation())); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) { event.blockList().removeIf(block -> protectedBuildArea(block.getLocation())); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Monster) || !isCity(event.getLocation())) return;
        if (event.getEntity().getScoreboardTags().contains("impuls_wave") || event.getEntity().getScoreboardTags().contains("impuls_rank_trial")) return;
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null || event.getPlayer().hasPermission("impuls.admin") || !isRoyalOrCastle(to)) return;
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
            player.setFlying(false); player.setAllowFlight(false);
            player.sendMessage(ChatColor.YELLOW + "VIP-полёт отключён рядом с личным королевским замком.");
        }
    }

    private boolean protectedBuildArea(Location l) { return isCity(l) || isSanitaryOnly(l) || isRoyal(l) || nearCastle(l); }
    private boolean isCity(Location l) { return normal(l) && chebyshev(l,CX,CZ) <= CITY_RADIUS; }
    private boolean isSanitaryOnly(Location l) { if(!normal(l))return false;int d=chebyshev(l,CX,CZ);return d>CITY_RADIUS&&d<=SANITARY_RADIUS; }
    private boolean isRoyal(Location l) { return normal(l)&&chebyshev(l,CX,CZ)<=ROYAL_RADIUS; }
    private boolean nearCastle(Location l) { return normal(l)&&Math.abs(l.getX()-CASTLE_X)<=90&&Math.abs(l.getZ()-CASTLE_Z)<=90; }
    private boolean isRoyalOrCastle(Location l) { return isRoyal(l)||nearCastle(l); }
    private boolean insideOwnClaim(Player p,Location l) { if(l.getWorld()==null)return false;Database.Claim c=db.claimAt(l.getWorld().getName(),l.getBlockX(),l.getBlockY(),l.getBlockZ());return c!=null&&c.owner().equals(p.getUniqueId()); }
    private boolean normal(Location l) { return l.getWorld()!=null&&l.getWorld().getEnvironment()==World.Environment.NORMAL; }
    private int chebyshev(Location l,int x,int z) { return (int)Math.max(Math.abs(l.getX()-x),Math.abs(l.getZ()-z)); }
}
