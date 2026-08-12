package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Objective;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ImPulsCoreV11Plugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final Set<Material> CREATIVE_FORBIDDEN = Set.of(
            Material.BEDROCK, Material.BARRIER, Material.COMMAND_BLOCK,
            Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK,
            Material.STRUCTURE_BLOCK, Material.JIGSAW, Material.TNT, Material.END_PORTAL_FRAME);

    private Database db;
    private SessionService sessions;
    private WarService wars;
    private DungeonService dungeons;
    private final Set<UUID> vipCreative = ConcurrentHashMap.newKeySet();
    private final Set<UUID> paidFlight = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            db = new Database(new File(getDataFolder(), getConfig().getString("database.file", "impuls.sqlite3")));
        } catch (Exception e) {
            getLogger().severe("SQLite init failed: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        sessions = new SessionService(db);
        wars = new WarService(this, db, sessions);
        dungeons = new DungeonService(this, db, sessions);
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand command = getCommand("impuls");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        wars.startTicker();
        Bukkit.getScheduler().runTaskTimer(this, this::flightBilling, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(this, this::cleanupExpiredClaims, 20L * 300L, 20L * 3600L);
        getLogger().info("ImPulsCore 1.1.0 enabled");
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (vipCreative.contains(player.getUniqueId())) disableVipCreative(player);
        }
        if (db != null) db.close();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        db.ensure(player);
        recoverVipCreative(player);
        dungeons.recoverOnJoin(player);
        wars.recoverOnJoin(player);
        syncScoreboards(player);
        warnClaimExpiry(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (vipCreative.contains(player.getUniqueId())) disableVipCreative(player);
        dungeons.onQuit(player);
        paidFlight.remove(player.getUniqueId());
        db.audit(player.getUniqueId(), "quit", "player quit");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Player killer = player.getKiller();
        if (wars.handleDeath(player, killer)) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
            return;
        }
        if (dungeons.handlePlayerDeath(player)) {
            event.setKeepInventory(false);
            event.getDrops().clear();
            event.setKeepLevel(false);
            event.setDroppedExp(0);
            return;
        }
        UUID uuid = player.getUniqueId();
        if (db.insured(uuid)) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
            db.setInsured(uuid, false);
            db.audit(uuid, "insurance_used", player.getLocation().toString());
            player.sendMessage(ChatColor.AQUA + "[ImPuls] Страховка сохранила инвентарь и израсходована.");
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Location warLocation = wars.respawnLocation(player);
        if (warLocation != null) {
            event.setRespawnLocation(warLocation);
            Bukkit.getScheduler().runTask(this, () -> wars.finishRespawn(player));
            return;
        }
        Location dungeonLocation = dungeons.respawnLocation(player);
        if (dungeonLocation != null) {
            event.setRespawnLocation(dungeonLocation);
            Bukkit.getScheduler().runTask(this, () -> dungeons.finishDeathRestore(player));
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        dungeons.onMobDeath(event.getEntity());
        Player killer = event.getEntity().getKiller();
        if (killer == null || !event.getEntity().getScoreboardTags().contains("impuls_wave")) return;
        int points = event.getEntity().getScoreboardTags().contains("impuls_wave_commander") ? 5 : 1;
        int coins = event.getEntity().getScoreboardTags().contains("impuls_wave_commander") ? 35 : 8;
        db.addDefender(killer.getUniqueId(), points);
        db.credit(killer.getUniqueId(), coins, "wall_defense");
        syncScoreboards(killer);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();
        if (!mayBuild(player, block)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "Этот участок принадлежит другому игроку.");
            return;
        }
        if (vipCreative.contains(player.getUniqueId())) {
            if (!insideOwnVip(player, block.getLocation())) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "VIP Creative работает только внутри твоего VIP-участка.");
                return;
            }
            if (CREATIVE_FORBIDDEN.contains(block.getType())) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "Этот блок запрещён в VIP Creative.");
                return;
            }
            db.addCreativeBlock(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), player.getUniqueId());
            return;
        }
        if (block.getState() instanceof InventoryHolder && db.claimAt(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()) == null) {
            db.protectContainer(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), player.getUniqueId(), Instant.now().getEpochSecond() + 86400L);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        if (!mayBuild(player, block) || lockedForOther(player, block.getLocation())) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "Этот блок защищён.");
            return;
        }
        if (vipCreative.contains(player.getUniqueId()) && !insideOwnVip(player, block.getLocation())) {
            event.setCancelled(true);
            return;
        }
        if (db.removeCreativeBlock(block.getWorld().getName(), block.getX(), block.getY(), block.getZ())) event.setDropItems(false);
        db.removeContainerLock(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onContainerOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Location location = event.getInventory().getLocation();
        if (vipCreative.contains(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "Контейнеры недоступны в VIP Creative.");
            return;
        }
        if (location == null || location.getWorld() == null) return;
        Database.Claim claim = db.claimAt(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        if (claim != null && !claim.owner().equals(player.getUniqueId()) && !player.hasPermission("impuls.admin")) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "Контейнер защищён участком.");
            return;
        }
        Database.ContainerLock lock = db.containerLock(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        if (lock == null && claim == null) {
            db.protectContainer(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), player.getUniqueId(), Instant.now().getEpochSecond() + 86400L);
        } else if (lock != null && !lock.owner().equals(player.getUniqueId()) && !player.hasPermission("impuls.admin")) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "Контейнер временно защищён владельцем на 24 часа.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && vipCreative.contains(player.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::protectedFromExplosion);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::protectedFromExplosion);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!vipCreative.contains(player.getUniqueId()) || event.getTo() == null) return;
        if (!player.hasPermission("impuls.vip") || !insideOwnVip(player, event.getTo())) {
            disableVipCreative(player);
            player.sendMessage(ChatColor.YELLOW + "Ты вышел за VIP-участок: включён Survival.");
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (paidFlight.remove(player.getUniqueId())) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    private boolean mayBuild(Player player, Block block) {
        Database.Claim claim = db.claimAt(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        return claim == null || claim.owner().equals(player.getUniqueId()) || player.hasPermission("impuls.admin");
    }

    private boolean lockedForOther(Player player, Location location) {
        if (location.getWorld() == null || player.hasPermission("impuls.admin")) return false;
        Database.ContainerLock lock = db.containerLock(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        return lock != null && !lock.owner().equals(player.getUniqueId());
    }

    private boolean protectedFromExplosion(Block block) {
        if (db.claimAt(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()) != null) return true;
        return db.containerLock(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()) != null;
    }

    private boolean insideOwnVip(Player player, Location location) {
        if (location.getWorld() == null) return false;
        return db.ownsClaimAt(player.getUniqueId(), location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), "VIP");
    }

    private void enableVipCreative(Player player) {
        if (!player.hasPermission("impuls.vip")) {
            player.sendMessage(ChatColor.RED + "Нет VIP-права.");
            return;
        }
        if (sessions.hasSession(player.getUniqueId()) || player.getScoreboardTags().contains("impuls_combat") || !insideOwnVip(player, player.getLocation())) {
            player.sendMessage(ChatColor.RED + "Creative доступен только на своём VIP-участке вне боя/сессий.");
            return;
        }
        if (!vipCreative.add(player.getUniqueId())) return;
        db.setCreativeSnapshot(player.getUniqueId(), InventoryCodec.encode(player.getInventory().getContents()));
        player.getInventory().clear();
        player.setGameMode(GameMode.CREATIVE);
        db.audit(player.getUniqueId(), "vip_creative_on", player.getLocation().toString());
    }

    private void disableVipCreative(Player player) {
        UUID uuid = player.getUniqueId();
        if (!vipCreative.remove(uuid) && !db.creativeActive(uuid)) return;
        player.getInventory().clear();
        String encoded = db.creativeSnapshot(uuid);
        if (encoded != null) player.getInventory().setContents(InventoryCodec.decode(encoded));
        db.clearCreativeSnapshot(uuid);
        player.setGameMode(GameMode.SURVIVAL);
        db.audit(uuid, "vip_creative_off", player.getLocation().toString());
    }

    private void recoverVipCreative(Player player) {
        if (!db.creativeActive(player.getUniqueId())) return;
        player.sendMessage(ChatColor.YELLOW + "[ImPuls] Восстанавливаю обычный инвентарь после незавершённого VIP Creative.");
        disableVipCreative(player);
    }

    private void flightBilling() {
        int cost = getConfig().getInt("economy.vip-flight-cost-per-minute", 2);
        for (UUID uuid : new HashSet<>(paidFlight)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                paidFlight.remove(uuid);
                continue;
            }
            if (sessions.hasSession(uuid) || player.getScoreboardTags().contains("impuls_combat") || !db.charge(uuid, cost, "vip_flight")) {
                paidFlight.remove(uuid);
                player.setFlying(false);
                player.setAllowFlight(false);
                player.sendMessage(ChatColor.YELLOW + "[ImPuls] VIP-полёт отключён.");
            } else {
                syncScoreboards(player);
            }
        }
    }

    private void cleanupExpiredClaims() {
        long normal = getConfig().getLong("claims.normal-inactivity-days", 60L) * 86400L;
        long vip = getConfig().getLong("claims.vip-inactivity-days", 120L) * 86400L;
        int released = db.releaseExpiredClaims(normal, vip);
        if (released > 0) getLogger().info("Released inactive claims: " + released);
    }

    private void warnClaimExpiry(Player player) {
        long normal = getConfig().getLong("claims.normal-inactivity-days", 60L) * 86400L;
        long vip = getConfig().getLong("claims.vip-inactivity-days", 120L) * 86400L;
        for (Database.ClaimRisk risk : db.claimsAtRisk(player.getUniqueId(), normal, vip, 7L * 86400L)) {
            long days = Math.max(1L, risk.secondsLeft() / 86400L);
            player.sendMessage(ChatColor.YELLOW + "[ImPuls] Участок #" + risk.claimId() + " может освободиться через ~" + days + " дн. неактивности.");
        }
    }

    private void syncScoreboards(Player player) {
        var board = Bukkit.getScoreboardManager().getMainScoreboard();
        Objective coins = board.getObjective("impuls_coins");
        if (coins != null) coins.getScore(player.getName()).setScore(db.coins(player.getUniqueId()));
        Objective defender = board.getObjective("impuls_defender");
        if (defender != null) defender.getScore(player.getName()).setScore(db.defender(player.getUniqueId()));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Player-only command");
            return true;
        }
        db.ensure(player);
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            help(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(player);
            case "insure" -> insure(player);
            case "claim" -> claim(player, Arrays.copyOfRange(args, 1, args.length));
            case "guild" -> guild(player, Arrays.copyOfRange(args, 1, args.length));
            case "war" -> war(player, Arrays.copyOfRange(args, 1, args.length));
            case "dungeon" -> dungeon(player, Arrays.copyOfRange(args, 1, args.length));
            case "vip" -> vip(player, Arrays.copyOfRange(args, 1, args.length));
            case "fly" -> fly(player);
            default -> help(player);
        }
        return true;
    }

    private void help(Player player) {
        player.sendMessage(ChatColor.GOLD + "ImPulsCore 1.1: /impuls status | insure | claim | guild | war | dungeon | vip creative | fly");
    }

    private void status(Player player) {
        Long guild = db.guildId(player.getUniqueId());
        player.sendMessage(ChatColor.GOLD + "ImPuls §7| монеты: §f" + db.coins(player.getUniqueId())
                + " §7| ранг: §f" + RankTier.fromIndex(db.rank(player.getUniqueId())).display()
                + " §7| защита: §f" + db.defender(player.getUniqueId())
                + " §7| страховка: §f" + (db.insured(player.getUniqueId()) ? "да" : "нет")
                + " §7| гильдия: §f" + (guild == null ? "—" : db.guildName(guild)));
    }

    private void insure(Player player) {
        UUID uuid = player.getUniqueId();
        if (db.insured(uuid)) {
            player.sendMessage(ChatColor.YELLOW + "Полис уже активен.");
            return;
        }
        int price = InventoryValueEstimator.insurancePrice(
                player.getInventory().getContents(),
                getConfig().getInt("economy.insurance-base-cost", 120),
                getConfig().getInt("economy.insurance-min-cost", 100),
                getConfig().getInt("economy.insurance-max-cost", 10000));
        if (!db.charge(uuid, price, "insurance")) {
            player.sendMessage(ChatColor.RED + "Недостаточно монет. Цена полиса: " + price);
            return;
        }
        db.setInsured(uuid, true);
        syncScoreboards(player);
        player.sendMessage(ChatColor.AQUA + "Страховка на одну обычную смерть активирована за " + price + " монет.");
    }

    private void claim(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage("/impuls claim buy|vip|info|expand|sell|accept");
            return;
        }
        Location location = player.getLocation();
        String world = location.getWorld().getName();
        int x = location.getBlockX(), y = location.getBlockY(), z = location.getBlockZ();
        int maxFromCapital = Math.max(Math.abs(x + 688), Math.abs(z + 688));
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "buy" -> {
                if (maxFromCapital <= 1064) {
                    player.sendMessage(ChatColor.RED + "Обычная земля продаётся только за санитарной зоной внешней стены.");
                    return;
                }
                if (db.claimCount(player.getUniqueId(), "NORMAL") >= 1) {
                    player.sendMessage(ChatColor.YELLOW + "Базовый участок уже есть; используй expand.");
                    return;
                }
                int r = 16;
                int cost = getConfig().getInt("economy.claim-32-cost", 500);
                boolean ok = db.createClaim(player.getUniqueId(), world, x - r, x + r - 1,
                        location.getWorld().getMinHeight(), location.getWorld().getMaxHeight() - 1,
                        z - r, z + r - 1, "NORMAL", cost);
                player.sendMessage(ok ? ChatColor.GREEN + "Участок 32×32 куплен." : ChatColor.RED + "Не удалось купить участок.");
                if (ok) syncScoreboards(player);
            }
            case "vip" -> {
                if (!player.hasPermission("impuls.vip")) {
                    player.sendMessage(ChatColor.RED + "Нужен VIP.");
                    return;
                }
                if (maxFromCapital >= 930 || db.claimCount(player.getUniqueId(), "VIP") >= 1) {
                    player.sendMessage(ChatColor.RED + "VIP-участок здесь недоступен или уже есть.");
                    return;
                }
                int r = 48;
                int cost = getConfig().getInt("economy.vip-claim-cost", 5000);
                boolean ok = db.createClaim(player.getUniqueId(), world, x - r, x + r - 1,
                        Math.max(location.getWorld().getMinHeight(), y - 4), Math.min(location.getWorld().getMaxHeight() - 1, y + 50),
                        z - r, z + r - 1, "VIP", cost);
                player.sendMessage(ok ? ChatColor.GREEN + "VIP-участок 96×96 зарегистрирован." : ChatColor.RED + "Не удалось зарегистрировать VIP-участок.");
                if (ok) syncScoreboards(player);
            }
            case "info" -> {
                Database.Claim c = db.claimAt(world, x, y, z);
                player.sendMessage(c == null ? "Здесь нет участка." : "Участок #" + c.id() + " | " + c.kind() + " | " + c.owner());
            }
            case "expand" -> expandClaim(player, args);
            case "sell" -> sellClaim(player, args);
            case "accept" -> acceptClaim(player, args);
            default -> player.sendMessage("/impuls claim buy|vip|info|expand|sell|accept");
        }
    }

    private void expandClaim(Player player, String[] args) {
        if (args.length < 4) {
            player.sendMessage("/impuls claim expand <id> <west|east|north|south> <blocks>");
            return;
        }
        try {
            long id = Long.parseLong(args[1]);
            int blocks = Math.max(1, Math.min(128, Integer.parseInt(args[3])));
            Database.Claim c = db.claimById(id);
            if (c == null || !c.owner().equals(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "Это не твой участок.");
                return;
            }
            long maxSurface = 1024L * (1L + Math.max(0, db.rank(player.getUniqueId())));
            int strip = blocks * Math.max(1, "west".equals(args[2]) || "east".equals(args[2]) ? c.maxZ() - c.minZ() + 1 : c.maxX() - c.minX() + 1);
            int cost = strip * getConfig().getInt("economy.claim-expand-cost-per-strip-block", 20);
            int west = 0, east = 0, north = 0, south = 0;
            switch (args[2].toLowerCase(Locale.ROOT)) {
                case "west" -> west = blocks;
                case "east" -> east = blocks;
                case "north" -> north = blocks;
                case "south" -> south = blocks;
                default -> { player.sendMessage("Направление: west|east|north|south"); return; }
            }
            boolean ok = db.expandClaim(player.getUniqueId(), id, west, east, north, south, 0, 0, cost, maxSurface);
            player.sendMessage(ok ? ChatColor.GREEN + "Участок расширен за " + cost + " монет." : ChatColor.RED + "Расширение отклонено: пересечение, лимит ранга или монеты.");
            if (ok) syncScoreboards(player);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "ID и размер должны быть числами.");
        }
    }

    private void sellClaim(Player player, String[] args) {
        if (args.length < 4) {
            player.sendMessage("/impuls claim sell <id> <player> <price>");
            return;
        }
        try {
            long id = Long.parseLong(args[1]);
            int price = Integer.parseInt(args[3]);
            Player buyer = Bukkit.getPlayerExact(args[2]);
            if (buyer == null) {
                player.sendMessage(ChatColor.RED + "Покупатель должен быть онлайн.");
                return;
            }
            boolean ok = db.offerClaimSale(player.getUniqueId(), id, buyer.getUniqueId(), price);
            player.sendMessage(ok ? ChatColor.GREEN + "Предложение создано на 24 часа." : ChatColor.RED + "Не удалось создать предложение.");
            if (ok) buyer.sendMessage(ChatColor.GOLD + "Тебе предложен участок #" + id + " за " + price + ". /impuls claim accept " + id);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "Неверный ID или цена.");
        }
    }

    private void acceptClaim(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("/impuls claim accept <id>");
            return;
        }
        try {
            long id = Long.parseLong(args[1]);
            boolean ok = db.acceptClaimSale(player.getUniqueId(), id, getConfig().getDouble("economy.claim-sale-fee", 0.05d));
            player.sendMessage(ok ? ChatColor.GREEN + "Сделка проведена атомарно; участок твой." : ChatColor.RED + "Сделка не выполнена.");
            if (ok) syncScoreboards(player);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "Неверный ID.");
        }
    }

    private void guild(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage("/impuls guild create|invite|accept|leave|deposit|info|role|transfer");
            return;
        }
        UUID uuid = player.getUniqueId();
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (args.length < 2) return;
                String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                int cost = getConfig().getInt("economy.guild-create-cost", 1500);
                boolean ok = db.createGuild(uuid, name, cost);
                player.sendMessage(ok ? ChatColor.GREEN + "Гильдия создана." : ChatColor.RED + "Не удалось создать гильдию.");
                if (ok) syncScoreboards(player);
            }
            case "invite" -> {
                if (args.length < 2) return;
                Player target = Bukkit.getPlayerExact(args[1]);
                boolean ok = target != null && db.invite(uuid, target.getUniqueId());
                player.sendMessage(ok ? ChatColor.GREEN + "Приглашение отправлено." : ChatColor.RED + "Не удалось пригласить.");
                if (ok) target.sendMessage(ChatColor.GOLD + "Тебя пригласили в гильдию. /impuls guild accept");
            }
            case "accept" -> player.sendMessage(db.acceptInvite(uuid) ? ChatColor.GREEN + "Ты вступил в гильдию." : ChatColor.RED + "Нет действующего приглашения.");
            case "leave" -> player.sendMessage(db.leaveGuild(uuid) ? ChatColor.YELLOW + "Ты вышел из гильдии." : ChatColor.RED + "Нельзя выйти: глава или идёт война.");
            case "deposit" -> {
                if (args.length < 2) return;
                try {
                    int amount = Integer.parseInt(args[1]);
                    boolean ok = db.depositGuild(uuid, amount);
                    player.sendMessage(ok ? ChatColor.GREEN + "Внесено: " + amount : ChatColor.RED + "Операция отклонена.");
                    if (ok) syncScoreboards(player);
                } catch (NumberFormatException ignored) {}
            }
            case "info" -> {
                Long gid = db.guildId(uuid);
                player.sendMessage(gid == null ? "Ты не в гильдии." : ChatColor.GOLD + db.guildName(gid) + ChatColor.GRAY + " | роль " + db.memberRole(uuid) + " | казна " + db.guildTreasury(gid));
            }
            case "role" -> {
                if (args.length < 3) return;
                Player target = Bukkit.getPlayerExact(args[1]);
                String role = "deputy".equalsIgnoreCase(args[2]) ? "DEPUTY" : "member".equalsIgnoreCase(args[2]) ? "MEMBER" : "";
                boolean ok = target != null && !role.isEmpty() && db.setGuildRole(uuid, target.getUniqueId(), role);
                player.sendMessage(ok ? ChatColor.GREEN + "Роль изменена." : ChatColor.RED + "Не удалось изменить роль.");
            }
            case "transfer" -> {
                if (args.length < 2) return;
                Player target = Bukkit.getPlayerExact(args[1]);
                boolean ok = target != null && db.transferGuildLeadership(uuid, target.getUniqueId());
                player.sendMessage(ok ? ChatColor.GREEN + "Лидерство передано." : ChatColor.RED + "Передача отклонена.");
            }
            default -> player.sendMessage("/impuls guild create|invite|accept|leave|deposit|info|role|transfer");
        }
    }

    private void war(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage("/impuls war challenge <guild> | accept | status | cancel <id>");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "challenge" -> {
                if (args.length < 2) return;
                player.sendMessage(db.challengeWar(player.getUniqueId(), String.join(" ", Arrays.copyOfRange(args, 1, args.length)))
                        ? ChatColor.GREEN + "Вызов отправлен. Война начнётся только после принятия и проверки баланса."
                        : ChatColor.RED + "Вызов отклонён.");
            }
            case "accept" -> player.sendMessage(wars.acceptAndStart(player) ? ChatColor.GREEN + "Война принята и запущена." : ChatColor.RED + "Нельзя принять: баланс, права или состояние гильдий.");
            case "status" -> {
                Long gid = db.guildId(player.getUniqueId());
                Database.WarInfo info = gid == null ? null : db.activeWarForGuild(gid);
                player.sendMessage(info == null ? "Активной войны нет." : "Война #" + info.id() + " | " + info.state() + " | " + info.scoreA() + ":" + info.scoreB());
            }
            case "cancel" -> {
                if (!player.hasPermission("impuls.admin") || args.length < 2) return;
                try { wars.cancel(Long.parseLong(args[1]), player.getUniqueId()); } catch (NumberFormatException ignored) {}
            }
            default -> player.sendMessage("/impuls war challenge <guild> | accept | status | cancel <id>");
        }
    }

    private void dungeon(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage("/impuls dungeon enter <H..SSS+> | next | leave");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "enter" -> {
                if (args.length < 2) return;
                try { dungeons.start(player, RankTier.parse(args[1])); }
                catch (IllegalArgumentException e) { player.sendMessage(ChatColor.RED + "Ранг: H,G,F,E,D,C,B,A,S,SS,SSS,SSS+"); }
            }
            case "next" -> dungeons.nextFloor(player);
            case "leave" -> dungeons.leave(player);
            default -> player.sendMessage("/impuls dungeon enter <H..SSS+> | next | leave");
        }
    }

    private void vip(Player player, String[] args) {
        if (args.length > 0 && "creative".equalsIgnoreCase(args[0])) {
            if (vipCreative.contains(player.getUniqueId())) disableVipCreative(player); else enableVipCreative(player);
        }
    }

    private void fly(Player player) {
        if (!player.hasPermission("impuls.vip")) {
            player.sendMessage(ChatColor.RED + "Нет VIP-права.");
            return;
        }
        UUID uuid = player.getUniqueId();
        if (sessions.hasSession(uuid) || player.getScoreboardTags().contains("impuls_combat")) {
            player.sendMessage(ChatColor.RED + "Полёт запрещён в бою, войне и подземельях.");
            return;
        }
        if (paidFlight.remove(uuid)) {
            player.setFlying(false);
            player.setAllowFlight(false);
            player.sendMessage("Полёт выключен.");
        } else {
            paidFlight.add(uuid);
            player.setAllowFlight(true);
            player.sendMessage(ChatColor.AQUA + "Платный VIP-полёт включён; списание раз в минуту.");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("help", "status", "insure", "claim", "guild", "war", "dungeon", "vip", "fly");
        if (args.length == 2 && "claim".equalsIgnoreCase(args[0])) return List.of("buy", "vip", "info", "expand", "sell", "accept");
        if (args.length == 2 && "guild".equalsIgnoreCase(args[0])) return List.of("create", "invite", "accept", "leave", "deposit", "info", "role", "transfer");
        if (args.length == 2 && "war".equalsIgnoreCase(args[0])) return List.of("challenge", "accept", "status", "cancel");
        if (args.length == 2 && "dungeon".equalsIgnoreCase(args[0])) return List.of("enter", "next", "leave");
        return new ArrayList<>();
    }
}
