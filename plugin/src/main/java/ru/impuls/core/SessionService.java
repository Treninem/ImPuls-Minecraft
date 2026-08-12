package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public final class SessionService {
    private final Database db;

    public SessionService(Database db) {
        this.db = db;
    }

    public boolean hasSession(UUID uuid) {
        return db.session(uuid) != null;
    }

    public boolean save(Player player, String context) {
        if (hasSession(player.getUniqueId())) return false;
        Location location = player.getLocation();
        db.saveSession(player.getUniqueId(), new Database.SessionSnapshot(
                context,
                InventoryCodec.encode(player.getInventory().getContents()),
                location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(),
                player.getGameMode().name(), player.getLevel(), player.getExp()
        ));
        return true;
    }

    public boolean restore(Player player) {
        Database.SessionSnapshot snapshot = db.session(player.getUniqueId());
        if (snapshot == null) return false;
        player.getInventory().clear();
        ItemStack[] items = InventoryCodec.decode(snapshot.inventory());
        player.getInventory().setContents(items);
        try {
            player.setGameMode(GameMode.valueOf(snapshot.gameMode()));
        } catch (IllegalArgumentException ignored) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        player.setLevel(Math.max(0, snapshot.level()));
        player.setExp(Math.max(0f, Math.min(1f, snapshot.exp())));
        player.setAllowFlight(false);
        player.setFlying(false);
        World world = Bukkit.getWorld(snapshot.world());
        Location destination = world == null
                ? player.getWorld().getSpawnLocation()
                : new Location(world, snapshot.x(), snapshot.y(), snapshot.z(), snapshot.yaw(), snapshot.pitch());
        player.teleport(destination);
        db.clearSession(player.getUniqueId());
        return true;
    }

    public String context(UUID uuid) {
        Database.SessionSnapshot snapshot = db.session(uuid);
        return snapshot == null ? null : snapshot.context();
    }
}
