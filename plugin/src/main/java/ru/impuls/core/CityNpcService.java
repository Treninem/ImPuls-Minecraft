package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;

/** Lightweight built-in NPC layer with day/night work locations and no pathfinding load. */
public final class CityNpcService implements Listener {
    private static final int CX = -688;
    private static final int CZ = -688;
    private static final String TAG = "impuls_city_npc";

    private record NpcDef(String id, String name, Villager.Profession profession, int workX, int workZ, int homeX, int homeZ, String hint) { }

    private final JavaPlugin plugin;
    private final Database db;
    private final Map<String, NpcDef> defs = new HashMap<>();

    private CityNpcService(JavaPlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
        add(new NpcDef("registrar", "Регистратор земель", Villager.Profession.CARTOGRAPHER, CX, CZ - 112, CX - 35, CZ - 65, "/impuls claim info — земля и участки"));
        add(new NpcDef("insurer", "Страховой распорядитель", Villager.Profession.LIBRARIAN, CX + 8, CZ - 112, CX - 25, CZ - 65, "/impuls insure — полис на одну обычную смерть"));
        add(new NpcDef("guildmaster", "Мастер Гильдии искателей", Villager.Profession.WEAPONSMITH, CX - 190, CZ - 75, CX - 210, CZ - 48, "/impuls medal, /impuls quest, /impuls rank"));
        add(new NpcDef("dungeon", "Управляющий подземельями", Villager.Profession.CLERIC, CX + 205, CZ - 94, CX + 178, CZ - 54, "/impuls dungeon enter <ранг> — вход по среднему рангу группы"));
        add(new NpcDef("merchant", "Городской торговец", Villager.Profession.TOOLSMITH, CX + 125, CZ - 70, CX + 105, CZ - 42, "Рынок работает днём. Игровая экономика не принимает Creative-предметы."));
        add(new NpcDef("ferryman", "Портовый перевозчик", Villager.Profession.FISHERMAN, CX + 250, CZ + 260, CX + 220, CZ + 220, "/impuls travel list — кареты и транспорт"));
        add(new NpcDef("event", "Распорядитель ивентов", Villager.Profession.SHEPHERD, CX + 22, CZ + 12, CX + 55, CZ + 35, "/impuls event — текущий городской ивент"));
    }

    private void add(NpcDef def) { defs.put(def.id, def); }

    public static void start(JavaPlugin plugin, Database db) {
        CityNpcService service = new CityNpcService(plugin, db);
        Bukkit.getPluginManager().registerEvents(service, plugin);
        Bukkit.getScheduler().runTaskLater(plugin, service::ensureAll, 20L * 30L);
        Bukkit.getScheduler().runTaskTimer(plugin, service::updateSchedule, 20L * 60L, 20L * 60L);
    }

    private void ensureAll() {
        World world = primaryWorld();
        if (world == null) return;
        for (NpcDef def : defs.values()) if (find(world, def.id) == null) spawn(world, def);
    }

    private void updateSchedule() {
        World world = primaryWorld();
        if (world == null) return;
        ensureAll();
        boolean day = world.getTime() < 12500L || world.getTime() > 23500L;
        for (NpcDef def : defs.values()) {
            Villager npc = find(world, def.id);
            if (npc == null) continue;
            Location target = safe(world, day ? def.workX : def.homeX, day ? def.workZ : def.homeZ);
            if (npc.getLocation().distanceSquared(target) > 9d) npc.teleport(target);
            npc.setCustomName(day ? ChatColor.GOLD + def.name : ChatColor.GRAY + def.name + " [ночь]");
        }
    }

    private void spawn(World world, NpcDef def) {
        Villager npc = world.spawn(safe(world, def.workX, def.workZ), Villager.class);
        npc.addScoreboardTag(TAG);
        npc.addScoreboardTag("impuls_npc_" + def.id);
        npc.setProfession(def.profession);
        npc.setCustomName(ChatColor.GOLD + def.name);
        npc.setCustomNameVisible(true);
        npc.setAI(false);
        npc.setInvulnerable(true);
        npc.setSilent(true);
        npc.setPersistent(true);
        npc.setRemoveWhenFarAway(false);
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager npc) || !npc.getScoreboardTags().contains(TAG)) return;
        event.setCancelled(true);
        for (NpcDef def : defs.values()) {
            if (!npc.getScoreboardTags().contains("impuls_npc_" + def.id)) continue;
            Player player = event.getPlayer();
            player.sendMessage(ChatColor.GOLD + "[" + def.name + "] " + ChatColor.WHITE + def.hint);
            if ("guildmaster".equals(def.id)) {
                player.sendMessage(ChatColor.GRAY + "Твой ранг: " + RankTier.fromIndex(db.rank(player.getUniqueId())).display());
            }
            if ("dungeon".equals(def.id)) {
                Long guild = db.guildId(player.getUniqueId());
                player.sendMessage(ChatColor.GRAY + "Группа определяется по ближайшим членам твоей гильдии. Гильдия: " + (guild == null ? "нет" : db.guildName(guild)));
            }
            return;
        }
    }

    private Villager find(World world, String id) {
        for (Entity entity : world.getEntities()) {
            if (entity instanceof Villager villager && villager.getScoreboardTags().contains("impuls_npc_" + id)) return villager;
        }
        return null;
    }

    private Location safe(World world, int x, int z) {
        int y = Math.max(world.getMinHeight() + 2, world.getHighestBlockYAt(x, z) + 1);
        return new Location(world, x + 0.5, y, z + 0.5);
    }

    private World primaryWorld() {
        for (World world : Bukkit.getWorlds()) if (world.getEnvironment() == World.Environment.NORMAL) return world;
        return null;
    }
}
