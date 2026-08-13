package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Prevents ordinary-world one-death insurance from being consumed by safe minigame deaths. */
public final class DuelInsuranceGuardService implements Listener {
    private final Database db;
    private final Set<UUID> restoreInsurance = new HashSet<>();

    private DuelInsuranceGuardService(Database db) { this.db = db; }

    public static void start(JavaPlugin plugin, Database db) {
        Bukkit.getPluginManager().registerEvents(new DuelInsuranceGuardService(db), plugin);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void beforeCoreDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!player.getScoreboardTags().contains("impuls_duel")
                && !player.getScoreboardTags().contains("impuls_safe_game")) return;
        UUID uuid = player.getUniqueId();
        if (!db.insured(uuid)) return;
        restoreInsurance.add(uuid);
        db.setInsured(uuid, false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void afterCoreDeath(PlayerDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        if (restoreInsurance.remove(uuid)) db.setInsured(uuid, true);
    }
}
