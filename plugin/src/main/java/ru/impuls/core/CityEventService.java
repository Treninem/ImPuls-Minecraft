package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CityEventService implements Listener {
    private static final int CX = -688;
    private static final int CZ = -688;
    private static final long EVENT_SECONDS = 10L * 60L;
    private static final long CYCLE_SECONDS = 30L * 60L;

    private enum Mode { COURIER, PARKOUR, HILL }

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
        service.buildParkourCourse();
        service.beginCycle();
        Bukkit.getScheduler().runTaskTimer(plugin, service::tick, 20L, 20L);
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
            Bukkit.broadcastMessage(ChatColor.GOLD + "[ImPuls] Городской ивент: " + title()
                    + ChatColor.GRAY + " — активен 10 минут в столице.");
        }
        if (mode == Mode.HILL) tickHill();
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
        if (mode == Mode.COURIER) checkCourier(player, event.getTo());
        if (mode == Mode.PARKOUR) checkParkour(player, event.getTo());
    }

    private void checkCourier(Player player, Location at) {
        int[][] checkpoints = {
                {CX - 180, CZ - 120},
                {CX + 170, CZ - 150},
                {CX + 190, CZ + 150},
                {CX - 160, CZ + 170},
                {CX, CZ}
        };
        int step = progress.getOrDefault(player.getUniqueId(), 0);
        if (step >= checkpoints.length) return;
        int[] target = checkpoints[step];
        double dx = at.getX() - target[0];
        double dz = at.getZ() - target[1];
        if (dx * dx + dz * dz > 49d) return;
        step++;
        progress.put(player.getUniqueId(), step);
        if (step >= checkpoints.length) reward(player, 120, "city_courier");
        else player.sendMessage(ChatColor.AQUA + "[ImPuls] Курьер: точка " + step + "/" + checkpoints.length + ".");
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

    private void reward(Player player, int coins, String reason) {
        if (!rewarded.add(player.getUniqueId())) return;
        db.credit(player.getUniqueId(), coins, reason);
        db.audit(player.getUniqueId(), "city_event_complete", reason + ":" + coins);
        player.sendMessage(ChatColor.GREEN + "[ImPuls] Ивент завершён: +" + coins + " монет.");
    }

    private boolean isCapital(Location location) {
        if (location.getWorld() == null || location.getWorld().getEnvironment() != World.Environment.NORMAL) return false;
        return Math.abs(location.getX() - CX) <= 850 && Math.abs(location.getZ() - CZ) <= 850;
    }

    private String title() {
        return switch (mode) {
            case COURIER -> "Курьер столицы";
            case PARKOUR -> "Крыши ImPuls";
            case HILL -> "Контроль площади";
        };
    }

    private void buildParkourCourse() {
        World world = primaryWorld();
        if (world == null) return;
        for (int[] pad : parkourPads()) {
            world.getBlockAt(pad[0], pad[1], pad[2]).setType(Material.LIME_CONCRETE, false);
        }
        int[] first = parkourPads()[0];
        world.getBlockAt(first[0], first[1] - 1, first[2]).setType(Material.SMOOTH_STONE, false);
    }

    private int[][] parkourPads() {
        int baseX = CX + 90;
        int baseZ = CZ + 70;
        int y = 86;
        return new int[][]{
                {baseX, y, baseZ},
                {baseX + 4, y + 1, baseZ + 1},
                {baseX + 8, y + 2, baseZ - 1},
                {baseX + 12, y + 3, baseZ + 2},
                {baseX + 16, y + 4, baseZ},
                {baseX + 20, y + 5, baseZ + 3},
                {baseX + 24, y + 6, baseZ + 1},
                {baseX + 28, y + 7, baseZ + 4}
        };
    }

    private World primaryWorld() {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == World.Environment.NORMAL) return world;
        }
        return null;
    }
}
