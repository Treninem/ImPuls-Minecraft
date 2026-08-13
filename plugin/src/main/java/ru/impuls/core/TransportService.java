package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Low-overhead city travel: delayed free spawn return plus paid district carriages. */
public final class TransportService implements Listener {
    private static final int CX = -688;
    private static final int CZ = -688;
    private final JavaPlugin plugin;
    private final Database db;
    private final Map<UUID, Pending> pending = new HashMap<>();

    private record Point(String title, int x, int z, int cost) { }
    private record Pending(Location origin, int taskId) { }

    private final Map<String, Point> points = Map.ofEntries(
            Map.entry("spawn", new Point("Центральная площадь", CX, CZ, 0)),
            Map.entry("market", new Point("Рынок", CX + 125, CZ - 70, 12)),
            Map.entry("guild", new Point("Зал гильдии", CX - 135, CZ - 40, 12)),
            Map.entry("port", new Point("Порт", CX + 210, CZ + 245, 18)),
            Map.entry("arena", new Point("Арена", CX - 235, CZ + 170, 18)),
            Map.entry("dungeon", new Point("Зал подземелий", CX + 250, CZ - 210, 20)),
            Map.entry("north", new Point("Северные ворота", CX, CZ - 980, 24)),
            Map.entry("south", new Point("Южные ворота", CX, CZ + 980, 24)),
            Map.entry("west", new Point("Западные ворота", CX - 980, CZ, 24)),
            Map.entry("east", new Point("Восточные ворота", CX + 980, CZ, 24))
    );

    private TransportService(JavaPlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    public static void start(JavaPlugin plugin, Database db) {
        Bukkit.getPluginManager().registerEvents(new TransportService(plugin, db), plugin);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage().trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        if (!(lower.equals("/impuls spawn") || lower.startsWith("/impuls travel") || lower.startsWith("/impuls transport"))) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (lower.equals("/impuls spawn")) {
            schedule(player, points.get("spawn"), true);
            return;
        }
        String[] parts = raw.split("\\s+");
        if (parts.length < 3 || "list".equalsIgnoreCase(parts[2])) {
            player.sendMessage(ChatColor.GOLD + "Кареты ImPuls: " + ChatColor.WHITE + String.join(", ", points.keySet()));
            player.sendMessage(ChatColor.GRAY + "/impuls travel <точка>. Возврат /impuls spawn бесплатный, но с задержкой.");
            return;
        }
        Point point = points.get(parts[2].toLowerCase(Locale.ROOT));
        if (point == null) {
            player.sendMessage(ChatColor.RED + "Неизвестная точка. /impuls travel list");
            return;
        }
        schedule(player, point, false);
    }

    private void schedule(Player player, Point point, boolean freeSpawn) {
        if (blocked(player)) {
            player.sendMessage(ChatColor.RED + "Телепорт недоступен в бою, войне, подземелье или другой игровой сессии.");
            return;
        }
        if (pending.containsKey(player.getUniqueId())) {
            player.sendMessage(ChatColor.YELLOW + "Перемещение уже готовится.");
            return;
        }
        int delay = freeSpawn ? plugin.getConfig().getInt("transport.spawn-delay-seconds", 5) : plugin.getConfig().getInt("transport.carriage-delay-seconds", 3);
        int cost = freeSpawn ? 0 : point.cost;
        if (cost > 0 && db.coins(player.getUniqueId()) < cost) {
            player.sendMessage(ChatColor.RED + "Поездка стоит " + cost + " монет.");
            return;
        }
        Location origin = player.getLocation().clone();
        int task = Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            Pending p = pending.remove(player.getUniqueId());
            if (p == null || !player.isOnline() || blocked(player)) return;
            if (cost > 0 && !db.charge(player.getUniqueId(), cost, "transport:" + point.title)) {
                player.sendMessage(ChatColor.RED + "Поездка отменена: недостаточно монет.");
                return;
            }
            Location destination = destination(player.getWorld(), point);
            player.teleport(destination);
            db.audit(player.getUniqueId(), "transport", point.title + ":" + cost);
            player.sendMessage(ChatColor.AQUA + "[ImPuls] Прибытие: " + point.title + (cost > 0 ? " (" + cost + " монет)" : ""));
        }, Math.max(1, delay) * 20L);
        pending.put(player.getUniqueId(), new Pending(origin, task));
        player.sendMessage(ChatColor.YELLOW + "Не двигайся и не получай урон " + delay + " сек. Перемещение: " + point.title + ".");
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        Pending p = pending.get(event.getPlayer().getUniqueId());
        if (p == null) return;
        if (p.origin.getWorld() != event.getTo().getWorld()
                || p.origin.distanceSquared(event.getTo()) > 1.0) cancel(event.getPlayer(), "Перемещение отменено: ты сдвинулся.");
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && pending.containsKey(player.getUniqueId())) {
            cancel(player, "Перемещение отменено: получен урон.");
        }
    }

    private void cancel(Player player, String message) {
        Pending p = pending.remove(player.getUniqueId());
        if (p == null) return;
        Bukkit.getScheduler().cancelTask(p.taskId);
        player.sendMessage(ChatColor.RED + message);
    }

    private boolean blocked(Player player) {
        for (String tag : player.getScoreboardTags()) {
            if (tag.equals("impuls_combat") || tag.equals("impuls_war") || tag.startsWith("impuls_war_") || tag.startsWith("impuls_dungeon")) return true;
        }
        return false;
    }

    private Location destination(World world, Point point) {
        int y = Math.max(world.getMinHeight() + 2, world.getHighestBlockYAt(point.x, point.z) + 1);
        Location location = new Location(world, point.x + 0.5, y, point.z + 0.5);
        Material below = world.getBlockAt(point.x, y - 1, point.z).getType();
        if (below.isAir() || below == Material.WATER || below == Material.LAVA) {
            world.getBlockAt(point.x, y - 1, point.z).setType(Material.SMOOTH_STONE, false);
        }
        return location;
    }
}
