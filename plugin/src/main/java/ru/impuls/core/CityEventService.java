package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Automatic low-overhead capital events that reuse the city instead of spawning heavy temporary worlds. */
public final class CityEventService implements Listener {
    private static final int CX = -688;
    private static final int CZ = -688;
    private static final long EVENT_SECONDS = 10L * 60L;
    private static final long CYCLE_SECONDS = 30L * 60L;

    private enum Mode { COURIER, PARKOUR, HILL, TREASURE, FISHING, HORSE_RACE, BOAT_RACE }

    private final JavaPlugin plugin;
    private final Database db;
    private final Map<UUID, Integer> progress = new HashMap<>();
    private final Map<UUID, Integer> hillSeconds = new HashMap<>();
    private final Set<UUID> rewarded = new HashSet<>();
    private Mode mode;
    private long cycleStart;
    private boolean announced;

    private CityEventService(JavaPlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    public static void start(JavaPlugin plugin, Database db) {
        CityEventService service = new CityEventService(plugin, db);
        Bukkit.getPluginManager().registerEvents(service, plugin);
        service.buildEventInfrastructure();
        service.beginCycle();
        Bukkit.getScheduler().runTaskTimer(plugin, service::tick, 20L, 20L);

        // v1.3 subsystems are intentionally started from the already-active city service
        // so no second JavaPlugin entrypoint or duplicate heavy plugin is required.
        if (plugin.getConfig().getBoolean("features.progression", true)) ProgressionQuestService.start(plugin, db);
        if (plugin.getConfig().getBoolean("features.transport", true)) TransportService.start(plugin, db);
        if (plugin.getConfig().getBoolean("features.item-lifecycle", true)) ItemLifecycleService.start(plugin);
        if (plugin.getConfig().getBoolean("features.capital-expansion", true)) CapitalExpansionService.start(plugin, db);
    }

    private void beginCycle() {
        long bucket = Instant.now().getEpochSecond() / CYCLE_SECONDS;
        mode = Mode.values()[(int) Math.floorMod(bucket, Mode.values().length)];
        cycleStart = Instant.now().getEpochSecond();
        progress.clear();
        hillSeconds.clear();
        rewarded.clear();
        announced = false;
    }

    private void tick() {
        long elapsed = Instant.now().getEpochSecond() - cycleStart;
        if (elapsed >= CYCLE_SECONDS) {
            beginCycle();
            elapsed = 0;
        }
        if (elapsed >= EVENT_SECONDS) return;
        if (!announced) {
            announced = true;
            Bukkit.broadcastMessage(ChatColor.GOLD + "[ImPuls] " + seasonalPrefix() + " — городской ивент: " + title()
                    + ChatColor.GRAY + " (10 минут, /impuls event)");
        }
        if (mode == Mode.HILL) tickHill();
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!event.getMessage().trim().equalsIgnoreCase("/impuls event")) return;
        event.setCancelled(true);
        long left = Math.max(0, EVENT_SECONDS - (Instant.now().getEpochSecond() - cycleStart));
        event.getPlayer().sendMessage(ChatColor.GOLD + "Текущий ивент: " + title() + ChatColor.GRAY + " | осталось " + (left / 60) + " мин.");
        event.getPlayer().sendMessage(ChatColor.GRAY + rules());
    }

    private void tickHill() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (rewarded.contains(player.getUniqueId()) || !isCapital(player.getLocation())) continue;
            Location location = player.getLocation();
            double dx = location.getX() - CX;
            double dz = location.getZ() - CZ;
            if (dx * dx + dz * dz > 64d) continue;
            int seconds = hillSeconds.merge(player.getUniqueId(), 1, Integer::sum);
            if (seconds >= 120) reward(player, 150, "city_hill");
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || rewarded.contains(event.getPlayer().getUniqueId())) return;
        long elapsed = Instant.now().getEpochSecond() - cycleStart;
        if (elapsed < 0 || elapsed >= EVENT_SECONDS) return;
        Player player = event.getPlayer();
        if (!isCapital(event.getTo())) return;
        switch (mode) {
            case COURIER -> checkRoute(player, event.getTo(), courierCheckpoints(), false, "Курьер", 120, "city_courier");
            case PARKOUR -> checkParkour(player, event.getTo());
            case TREASURE -> checkTreasure(player, event.getTo());
            case HORSE_RACE -> {
                if (player.getVehicle() instanceof Horse) checkRoute(player, event.getTo(), horseCheckpoints(), true, "Скачки", 180, "city_horse_race");
            }
            case BOAT_RACE -> {
                if (player.getVehicle() instanceof Boat) checkRoute(player, event.getTo(), boatCheckpoints(), true, "Лодочная гонка", 180, "city_boat_race");
            }
            default -> { }
        }
    }

    @EventHandler
    public void onFish(PlayerFishEvent event) {
        if (mode != Mode.FISHING || rewarded.contains(event.getPlayer().getUniqueId())) return;
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !isCapital(event.getPlayer().getLocation())) return;
        int count = progress.merge(event.getPlayer().getUniqueId(), 1, Integer::sum);
        event.getPlayer().sendMessage(ChatColor.AQUA + "[ImPuls] Рыбалка: " + count + "/5");
        if (count >= 5) reward(event.getPlayer(), 160, "city_fishing");
    }

    private void checkRoute(Player player, Location at, int[][] checkpoints, boolean strictOrder, String label, int coins, String reason) {
        int step = progress.getOrDefault(player.getUniqueId(), 0);
        if (step >= checkpoints.length) return;
        int[] target = checkpoints[step];
        double dx = at.getX() - target[0];
        double dz = at.getZ() - target[1];
        if (dx * dx + dz * dz > (strictOrder ? 100d : 49d)) return;
        step++;
        progress.put(player.getUniqueId(), step);
        if (step >= checkpoints.length) reward(player, coins, reason);
        else player.sendMessage(ChatColor.AQUA + "[ImPuls] " + label + ": точка " + step + "/" + checkpoints.length + ".");
    }

    private void checkParkour(Player player, Location at) {
        int[][] pads = parkourPads();
        int step = progress.getOrDefault(player.getUniqueId(), 0);
        if (step >= pads.length) return;
        int[] pad = pads[step];
        if (Math.abs(at.getX() - (pad[0] + 0.5)) > 1.2
                || Math.abs(at.getY() - (pad[1] + 1.0)) > 2.0
                || Math.abs(at.getZ() - (pad[2] + 0.5)) > 1.2) return;
        step++;
        progress.put(player.getUniqueId(), step);
        if (step >= pads.length) reward(player, 140, "city_parkour");
        else player.sendMessage(ChatColor.LIGHT_PURPLE + "[ImPuls] Паркур: " + step + "/" + pads.length + ".");
    }

    private void checkTreasure(Player player, Location at) {
        int index = (int) Math.floorMod(cycleStart / CYCLE_SECONDS, treasurePoints().length);
        int[] target = treasurePoints()[index];
        double dx = at.getX() - target[0];
        double dz = at.getZ() - target[1];
        if (dx * dx + dz * dz <= 25d) reward(player, 170, "city_treasure");
    }

    private void reward(Player player, int coins, String reason) {
        if (!rewarded.add(player.getUniqueId())) return;
        db.credit(player.getUniqueId(), coins, reason);
        db.audit(player.getUniqueId(), "city_event_complete", reason + ":" + coins);
        player.sendMessage(ChatColor.GREEN + "[ImPuls] Ивент завершён: +" + coins + " монет. Повторная награда в этом цикле заблокирована.");
    }

    private boolean isCapital(Location location) {
        if (location.getWorld() == null || location.getWorld().getEnvironment() != World.Environment.NORMAL) return false;
        return Math.abs(location.getX() - CX) <= 1000 && Math.abs(location.getZ() - CZ) <= 1000;
    }

    private String title() {
        return switch (mode) {
            case COURIER -> "Курьер столицы";
            case PARKOUR -> "Крыши ImPuls";
            case HILL -> "Контроль площади";
            case TREASURE -> "Городская охота за сокровищами";
            case FISHING -> "Рыболовный турнир";
            case HORSE_RACE -> "Скачки столицы";
            case BOAT_RACE -> "Кубок каналов";
        };
    }

    private String rules() {
        return switch (mode) {
            case COURIER -> "Пройди 5 курьерских точек по городу в правильном порядке.";
            case PARKOUR -> "Пройди зелёные платформы паркура у центрального района.";
            case HILL -> "Удерживай центр площади 120 секунд.";
            case TREASURE -> "Найди отмеченную в городе тайную точку; подсказка меняется каждый цикл.";
            case FISHING -> "Поймай 5 рыб в городской воде.";
            case HORSE_RACE -> "На лошади пройди кольцо контрольных точек.";
            case BOAT_RACE -> "На лодке пройди контрольные точки портового маршрута.";
        };
    }

    private String seasonalPrefix() {
        Month month = LocalDate.now().getMonth();
        if (month == Month.DECEMBER || month == Month.JANUARY || month == Month.FEBRUARY) return "Зимний сезон";
        if (month == Month.MARCH || month == Month.APRIL || month == Month.MAY) return "Весенний сезон";
        if (month == Month.JUNE || month == Month.JULY || month == Month.AUGUST) return "Летний сезон";
        return "Осенний сезон";
    }

    private void buildEventInfrastructure() {
        World world = primaryWorld();
        if (world == null) return;
        for (int[] pad : parkourPads()) setIfReplaceable(world, pad[0], pad[1], pad[2], Material.LIME_CONCRETE);
        for (int[] p : horseCheckpoints()) marker(world, p[0], p[1]);
        for (int[] p : boatCheckpoints()) marker(world, p[0], p[1]);
    }

    private void marker(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z) + 1;
        setIfReplaceable(world, x, y, z, Material.LANTERN);
    }

    private void setIfReplaceable(World world, int x, int y, int z, Material material) {
        Material old = world.getBlockAt(x, y, z).getType();
        if (old.isAir() || old == Material.SHORT_GRASS || old == Material.TALL_GRASS || old == Material.SNOW) {
            world.getBlockAt(x, y, z).setType(material, false);
        }
    }

    private int[][] courierCheckpoints() {
        return new int[][]{{CX - 180, CZ - 120}, {CX + 170, CZ - 150}, {CX + 190, CZ + 150}, {CX - 160, CZ + 170}, {CX, CZ}};
    }

    private int[][] horseCheckpoints() {
        return new int[][]{{CX - 260, CZ + 280}, {CX, CZ + 350}, {CX + 260, CZ + 280}, {CX + 320, CZ}, {CX + 250, CZ - 250}, {CX, CZ - 320}, {CX - 250, CZ - 250}, {CX - 320, CZ}};
    }

    private int[][] boatCheckpoints() {
        return new int[][]{{CX + 170, CZ + 230}, {CX + 260, CZ + 280}, {CX + 350, CZ + 230}, {CX + 300, CZ + 130}, {CX + 210, CZ + 130}};
    }

    private int[][] treasurePoints() {
        return new int[][]{{CX - 210, CZ - 210}, {CX + 205, CZ - 170}, {CX + 280, CZ + 120}, {CX - 260, CZ + 150}, {CX + 40, CZ + 300}, {CX - 20, CZ - 310}};
    }

    private int[][] parkourPads() {
        int baseX = CX + 90;
        int baseZ = CZ + 70;
        int y = 86;
        return new int[][]{{baseX, y, baseZ}, {baseX + 4, y + 1, baseZ + 1}, {baseX + 8, y + 2, baseZ - 1}, {baseX + 12, y + 3, baseZ + 2}, {baseX + 16, y + 4, baseZ}, {baseX + 20, y + 5, baseZ + 3}, {baseX + 24, y + 6, baseZ + 1}, {baseX + 28, y + 7, baseZ + 4}};
    }

    private World primaryWorld() {
        for (World world : Bukkit.getWorlds()) if (world.getEnvironment() == World.Environment.NORMAL) return world;
        return null;
    }
}
