package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/** Overrides the legacy short help text with the current v1.3 command overview. */
public final class HelpCommandService implements Listener {
    private HelpCommandService() { }

    public static void start(JavaPlugin plugin) {
        Bukkit.getPluginManager().registerEvents(new HelpCommandService(), plugin);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage().trim().toLowerCase(Locale.ROOT);
        if (!(raw.equals("/impuls help") || raw.equals("/impuls commands") || raw.equals("/imp help") || raw.equals("/imp commands"))) return;
        event.setCancelled(true);
        Player p = event.getPlayer();
        p.sendMessage(ChatColor.GOLD + "════ ImPulsCore v1.3.0 ════");
        p.sendMessage(ChatColor.YELLOW + "Профиль/RPG: " + ChatColor.WHITE + "/impuls status | medal | quest | rank | insure");
        p.sendMessage(ChatColor.YELLOW + "Земля/VIP: " + ChatColor.WHITE + "/impuls claim ... | vip creative | fly");
        p.sendMessage(ChatColor.YELLOW + "Гильдия: " + ChatColor.WHITE + "/impuls guild ... | war ... | guild base ... | guild alliance ...");
        p.sendMessage(ChatColor.YELLOW + "Подземелья: " + ChatColor.WHITE + "/impuls dungeon ... | loot status|vote|award");
        p.sendMessage(ChatColor.YELLOW + "Экономика: " + ChatColor.WHITE + "/impuls work | sellserver | market ...");
        p.sendMessage(ChatColor.YELLOW + "Город: " + ChatColor.WHITE + "/impuls spawn | travel | event | royal ...");
        p.sendMessage(ChatColor.YELLOW + "Игры: " + ChatColor.WHITE + "/impuls duel | archery | maze | wavegame | buildgame");
        p.sendMessage(ChatColor.YELLOW + "Рейтинги: " + ChatColor.WHITE + "/impuls top defender|coins|rank|xp|quests|dungeons");
        if (p.hasPermission("impuls.admin")) {
            p.sendMessage(ChatColor.RED + "Админ: " + ChatColor.WHITE + "/impuls diag | backup | capital | royal grant/revoke | war cancel");
        }
        p.sendMessage(ChatColor.GRAY + "Подробности: docs/COMMANDS_RU.md в репозитории ImPuls-Minecraft.");
    }
}
