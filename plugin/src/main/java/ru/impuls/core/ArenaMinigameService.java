package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Safe 1v1 minigame: equal kits, saved inventories and restart recovery. */
public final class ArenaMinigameService implements Listener {
    private static final String WORLD_NAME = "impuls_duel_arena";
    private final JavaPlugin plugin;
    private final Database db;
    private final SessionService sessions;
    private final Map<UUID, UUID> challenges = new HashMap<>();
    private final Map<UUID, UUID> opponents = new HashMap<>();
    private final Map<UUID, Long> duelIds = new HashMap<>();
    private final Set<UUID> pendingRespawn = new HashSet<>();

    private ArenaMinigameService(JavaPlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
        this.sessions = new SessionService(db);
    }

    public static void start(JavaPlugin plugin, Database db) {
        ArenaMinigameService service = new ArenaMinigameService(plugin, db);
        Bukkit.getPluginManager().registerEvents(service, plugin);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage().trim();
        if (!raw.toLowerCase(Locale.ROOT).startsWith("/impuls duel")) return;
        event.setCancelled(true);
        String[] args = raw.split("\\s+");
        Player player = event.getPlayer();
        if (args.length < 3) {
            player.sendMessage("/impuls duel <player> | accept <player> | leave");
            return;
        }
        if ("leave".equalsIgnoreCase(args[2])) {
            if (!opponents.containsKey(player.getUniqueId())) player.sendMessage(ChatColor.YELLOW + "Ты не в дуэли.");
            else finish(player.getUniqueId(), null, false, "Дуэль прекращена.");
            return;
        }
        if ("accept".equalsIgnoreCase(args[2])) {
            if (args.length < 4) {
                player.sendMessage("/impuls duel accept <player>");
                return;
            }
            Player challenger = Bukkit.getPlayerExact(args[3]);
            if (challenger == null || !challenger.getUniqueId().equals(challenges.get(player.getUniqueId()))) {
                player.sendMessage(ChatColor.RED + "Нет такого активного вызова.");
                return;
            }
            challenges.remove(player.getUniqueId());
            startDuel(challenger, player);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null || target.equals(player) || busy(player) || busy(target)) {
            player.sendMessage(ChatColor.RED + "Игрок недоступен для дуэли.");
            return;
        }
        challenges.put(target.getUniqueId(), player.getUniqueId());
        player.sendMessage(ChatColor.GREEN + "Вызов отправлен " + target.getName() + ".");
        target.sendMessage(ChatColor.GOLD + player.getName() + " вызывает тебя на безопасную дуэль. /impuls duel accept " + player.getName());
        Bukkit.getScheduler().runTaskLater(plugin, () -> challenges.remove(target.getUniqueId(), player.getUniqueId()), 20L * 60L);
    }

    private void startDuel(Player a, Player b) {
        if (busy(a) || busy(b)) return;
        long id = System.currentTimeMillis();
        if (!sessions.save(a, "DUEL:" + id)) {
            a.sendMessage(ChatColor.RED + "Нельзя начать дуэль во время другой сессии.");
            return;
        }
        if (!sessions.save(b, "DUEL:" + id)) {
            sessions.restore(a);
            b.sendMessage(ChatColor.RED + "Нельзя начать дуэль во время другой сессии.");
            return;
        }
        opponents.put(a.getUniqueId(), b.getUniqueId());
        opponents.put(b.getUniqueId(), a.getUniqueId());
        duelIds.put(a.getUniqueId(), id);
        duelIds.put(b.getUniqueId(), id);
        prepare(a, true);
        prepare(b, false);
        db.audit(a.getUniqueId(), "duel_start", b.getUniqueId().toString());
        db.audit(b.getUniqueId(), "duel_start", a.getUniqueId().toString());
    }

    private void prepare(Player player, boolean left) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setHealth(Math.min(player.getMaxHealth(), 20d));
        player.setFoodLevel(20);
        giveKit(player);
        player.addScoreboardTag("impuls_duel");
        player.teleport(new Location(arena(), left ? -14.5 : 14.5, 66, 0.5, left ? -90f : 90f, 0f));
        player.sendMessage(ChatColor.GOLD + "[ImPuls] Дуэль началась. Обычный инвентарь сохранён отдельно; смерть здесь безопасна.");
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = playerDamager(event.getDamager());
        UUID opponent = opponents.get(victim.getUniqueId());
        if (opponent == null) {
            if (attacker != null && opponents.containsKey(attacker.getUniqueId())) event.setCancelled(true);
            return;
        }
        if (attacker == null || !opponent.equals(attacker.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID opponentId = opponents.get(victim.getUniqueId());
        if (opponentId == null) return;
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        pendingRespawn.add(victim.getUniqueId());
        Player winner = Bukkit.getPlayer(opponentId);
        if (winner != null) rewardPair(winner, victim);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!pendingRespawn.remove(uuid)) return;
        event.setRespawnLocation(arena().getSpawnLocation());
        UUID winner = opponents.get(uuid);
        Bukkit.getScheduler().runTask(plugin, () -> finish(uuid, winner, false, "Дуэль завершена."));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (opponents.containsKey(event.getPlayer().getUniqueId())) finish(event.getPlayer().getUniqueId(), null, false, "Соперник вышел; награда не начисляется.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        String context = sessions.context(event.getPlayer().getUniqueId());
        if (context != null && context.startsWith("DUEL:") && !opponents.containsKey(event.getPlayer().getUniqueId())) {
            sessions.restore(event.getPlayer());
            event.getPlayer().removeScoreboardTag("impuls_duel");
            event.getPlayer().sendMessage(ChatColor.YELLOW + "[ImPuls] Незавершённая дуэль после рестарта закрыта; инвентарь восстановлен.");
        }
    }

    private void rewardPair(Player winner, Player loser) {
        long now = Instant.now().getEpochSecond();
        String kind = pairCooldown(winner.getUniqueId(), loser.getUniqueId());
        if (db.cooldown(winner.getUniqueId(), kind) > now) {
            winner.sendMessage(ChatColor.GRAY + "Повторная дуэль с этим игроком не даёт награду до окончания антифарм-кулдауна.");
            return;
        }
        int coins = plugin.getConfig().getInt("duel.reward-coins", 60);
        db.credit(winner.getUniqueId(), coins, "duel_win");
        long until = now + plugin.getConfig().getLong("duel.pair-reward-cooldown-seconds", 3600L);
        db.setCooldown(winner.getUniqueId(), kind, until);
        db.setCooldown(loser.getUniqueId(), pairCooldown(loser.getUniqueId(), winner.getUniqueId()), until);
        db.audit(winner.getUniqueId(), "duel_win", loser.getUniqueId() + ":" + coins);
        winner.sendMessage(ChatColor.GREEN + "Победа в дуэли: +" + coins + " монет.");
    }

    private String pairCooldown(UUID a, UUID b) {
        return "duel_pair:" + b.toString();
    }

    private void finish(UUID any, UUID announcedWinner, boolean reward, String message) {
        UUID other = opponents.remove(any);
        if (other != null) opponents.remove(other);
        duelIds.remove(any);
        if (other != null) duelIds.remove(other);
        pendingRespawn.remove(any);
        if (other != null) pendingRespawn.remove(other);
        restoreOnline(any, message);
        if (other != null) restoreOnline(other, message);
    }

    private void restoreOnline(UUID uuid, String message) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return; // persistent session remains for next join
        player.removeScoreboardTag("impuls_duel");
        sessions.restore(player);
        player.sendMessage(ChatColor.YELLOW + "[ImPuls] " + message + " Обычное состояние восстановлено.");
    }

    private boolean busy(Player player) {
        return opponents.containsKey(player.getUniqueId()) || sessions.hasSession(player.getUniqueId()) || player.getScoreboardTags().contains("impuls_combat");
    }

    private Player playerDamager(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    private void giveKit(Player player) {
        player.getInventory().addItem(new ItemStack(Material.IRON_SWORD), new ItemStack(Material.BOW), new ItemStack(Material.ARROW, 20), new ItemStack(Material.COOKED_BEEF, 12), new ItemStack(Material.SHIELD));
        player.getInventory().setHelmet(new ItemStack(Material.IRON_HELMET));
        player.getInventory().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        player.getInventory().setLeggings(new ItemStack(Material.IRON_LEGGINGS));
        player.getInventory().setBoots(new ItemStack(Material.IRON_BOOTS));
    }

    private World arena() {
        World world = Bukkit.getWorld(WORLD_NAME);
        if (world == null) {
            WorldCreator creator = new WorldCreator(WORLD_NAME);
            creator.type(WorldType.FLAT);
            creator.generateStructures(false);
            world = creator.createWorld();
            if (world == null) throw new IllegalStateException("Cannot create duel arena");
            world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            world.setTime(6000L);
            buildArena(world);
        }
        return world;
    }

    private void buildArena(World world) {
        int y = 64;
        for (int x = -22; x <= 22; x++) for (int z = -22; z <= 22; z++) {
            world.getBlockAt(x, y, z).setType(Material.SMOOTH_STONE, false);
            if (Math.abs(x) == 22 || Math.abs(z) == 22) {
                for (int h = 1; h <= 5; h++) world.getBlockAt(x, y + h, z).setType(Material.STONE_BRICKS, false);
            } else {
                for (int h = 1; h <= 4; h++) world.getBlockAt(x, y + h, z).setType(Material.AIR, false);
            }
        }
        world.setSpawnLocation(new Location(world, 0.5, 66, 0.5));
    }
}
