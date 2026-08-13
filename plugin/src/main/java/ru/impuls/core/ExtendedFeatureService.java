package ru.impuls.core;

import org.bukkit.plugin.java.JavaPlugin;

/** Single bootstrap point for optional v1.3 subsystems so compiled features cannot remain dormant. */
public final class ExtendedFeatureService {
    private ExtendedFeatureService() { }

    public static void start(JavaPlugin plugin, Database db) {
        if (plugin.getConfig().getBoolean("features.duel-insurance-guard", true)) DuelInsuranceGuardService.start(plugin, db);
        if (plugin.getConfig().getBoolean("features.city-challenges", true)) CityChallengeService.start(plugin, db);
        if (plugin.getConfig().getBoolean("features.leaderboards", true)) LeaderboardDiagnosticsService.start(plugin);
        if (plugin.getConfig().getBoolean("features.dungeon-shared-loot", true)) DungeonSharedLootService.start(plugin, db);
        if (plugin.getConfig().getBoolean("features.backups", true)) BackupService.start(plugin);
        if (plugin.getConfig().getBoolean("features.city-districts", true)) CityDistrictExpansionService.start(plugin, db);
        if (plugin.getConfig().getBoolean("features.vip-leak-guard", true)) VipLeakGuardService.start(plugin, db);
        if (plugin.getConfig().getBoolean("features.royal-estate", true)) RoyalEstateService.start(plugin, db);
    }
}
