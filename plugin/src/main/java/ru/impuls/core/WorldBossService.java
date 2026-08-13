package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.List;

/** Rare anti-farm world boss event near the outside of the capital wall. */
public final class WorldBossService implements Listener {
    private static final int CX = -688;
    private static final int CZ = -688;
    private final JavaPlugin plugin;
    private final Database db;
    private final NamespacedKey bossKey;
    private long lastBucket = -1L;

    private WorldBossService(JavaPlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
        this.bossKey = new NamespacedKey(plugin, "world_boss");
    }

    public static void start(JavaPlugin plugin, Database db) {
        WorldBossService service = new WorldBossService(plugin, db);
        Bukkit.getPluginManager().registerEvents(service, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, service::tick, 20L * 90L, 20L * 300L);
    }

    private void tick() {
        World world = primaryWorld();
        if (world == null || Bukkit.getOnlinePlayers().isEmpty() || existingBoss(world) != null) return;
        long interval = Math.max(3600L, plugin.getConfig().getLong("world-boss.interval-seconds", 10800L));
        long bucket = Instant.now().getEpochSecond() / interval;
        if (bucket == lastBucket) return;
        lastBucket = bucket;
        int[][] points = {{CX, CZ - 1120}, {CX + 1120, CZ}, {CX, CZ + 1120}, {CX - 1120, CZ}};
        int[] p = points[(int) Math.floorMod(bucket, points.length)];
        int y = world.getHighestBlockYAt(p[0], p[1]) + 1;
        EntityType type = bucket % 4 == 0 ? EntityType.WARDEN : bucket % 2 == 0 ? EntityType.RAVAGER : EntityType.IRON_GOLEM;
        LivingEntity boss = (LivingEntity) world.spawnEntity(new Location(world, p[0] + 0.5, y, p[1] + 0.5), type);
        boss.getPersistentDataContainer().set(bossKey, PersistentDataType.LONG, bucket);
        boss.addScoreboardTag("impuls_world_boss");
        boss.addScoreboardTag("impuls_wave");
        boss.addScoreboardTag("impuls_wave_commander");
        boss.setCustomName(ChatColor.DARK_PURPLE + "Мировой босс ImPuls");
        boss.setCustomNameVisible(true);
        boss.setPersistent(true);
        Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "[ImPuls] У внешней стены появился мировой босс! Координаты: X=" + p[0] + " Z=" + p[1] + ". Награда ограничена одним убийством босса.");
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        Long bucket = event.getEntity().getPersistentDataContainer().get(bossKey, PersistentDataType.LONG);
        if (bucket == null) return;
        event.getDrops().clear();
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        int coins = plugin.getConfig().getInt("world-boss.reward-coins", 750);
        db.credit(killer.getUniqueId(), coins, "world_boss:" + bucket);
        db.addDefender(killer.getUniqueId(), 25);
        db.audit(killer.getUniqueId(), "world_boss_kill", "bucket=" + bucket + ":coins=" + coins);
        ItemStack trophy = new ItemStack(Material.ECHO_SHARD);
        ItemMeta meta = trophy.getItemMeta();
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Трофей мирового босса");
        meta.setLore(List.of(ChatColor.GRAY + "Редкий трофей защиты ImPuls", ChatColor.DARK_GRAY + "Не является валютой и не выдаётся Creative-системой"));
        trophy.setItemMeta(meta);
        killer.getInventory().addItem(trophy);
        Bukkit.broadcastMessage(ChatColor.GOLD + "[ImPuls] " + killer.getName() + " победил мирового босса и получил " + coins + " монет.");
    }

    private LivingEntity existingBoss(World world) {
        for (Entity entity : world.getEntities()) if (entity instanceof LivingEntity living && entity.getScoreboardTags().contains("impuls_world_boss")) return living;
        return null;
    }

    private World primaryWorld() {
        for (World world : Bukkit.getWorlds()) if (world.getEnvironment() == World.Environment.NORMAL) return world;
        return null;
    }
}
