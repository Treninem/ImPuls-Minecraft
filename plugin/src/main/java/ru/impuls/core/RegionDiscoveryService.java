package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class RegionDiscoveryService implements Listener {
    private final Database db;
    private final Map<UUID, Long> nextCheck = new HashMap<>();

    private RegionDiscoveryService(Database db) {
        this.db = db;
    }

    public static void start(JavaPlugin plugin, Database db) {
        Bukkit.getPluginManager().registerEvents(new RegionDiscoveryService(db), plugin);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        check(event.getPlayer(), true);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        check(event.getPlayer(), true);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;
        check(event.getPlayer(), false);
    }

    private void check(Player player, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && nextCheck.getOrDefault(player.getUniqueId(), 0L) > now) return;
        nextCheck.put(player.getUniqueId(), now + 5000L);

        Region region = classify(player.getLocation());
        if (region == null) return;
        String tag = "impuls_region_" + region.id;
        if (!player.addScoreboardTag(tag)) return;

        db.credit(player.getUniqueId(), region.reward, "region_discovery:" + region.id);
        db.audit(player.getUniqueId(), "region_discovery", region.id + ":" + player.getLocation());
        player.sendTitle(ChatColor.GOLD + "Открыт регион", ChatColor.YELLOW + region.title, 10, 50, 15);
        player.sendMessage(ChatColor.GREEN + "[ImPuls] Исследование: " + region.title + " — +" + region.reward + " монет.");
    }

    private Region classify(Location location) {
        World world = location.getWorld();
        if (world == null) return null;
        if (world.getEnvironment() == World.Environment.NETHER) return new Region("nether", "Изменённый Нижний мир", 180);
        if (world.getEnvironment() == World.Environment.THE_END) return new Region("end", "Изменённый Край", 220);
        if (world.getEnvironment() != World.Environment.NORMAL) return null;

        if (location.getBlockY() <= 20) return new Region("deep_caves", "Глубинные пещеры", 120);
        Biome biome = world.getBiome(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        String key = biome.getKey().getKey().toLowerCase(Locale.ROOT);

        if (containsAny(key, "ocean", "river", "beach")) return new Region("ocean", "Морской и подводный регион", 140);
        if (containsAny(key, "snow", "frozen", "ice", "grove")) return new Region("winter", "Северный зимний регион", 140);
        if (containsAny(key, "jungle", "bamboo", "mangrove")) return new Region("tropical", "Тропический регион", 150);
        if (containsAny(key, "peak", "windswept", "mountain", "stony")) return new Region("mountains", "Горный регион", 140);
        if (containsAny(key, "desert", "badlands", "savanna")) return new Region("drylands", "Пустоши и жаркие земли", 130);
        if (containsAny(key, "swamp")) return new Region("swamp", "Болотный регион", 120);
        if (containsAny(key, "forest", "taiga", "woods")) return new Region("forest", "Лесной регион", 110);
        if (containsAny(key, "plains", "meadow")) return new Region("plains", "Поля и луга", 100);
        return null;
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private record Region(String id, String title, int reward) {}
}
