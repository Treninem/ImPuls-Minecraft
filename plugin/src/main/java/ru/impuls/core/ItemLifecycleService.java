package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;

/** Implements ImPuls drop lifetime: ordinary drops survive up to 24h, valuable drops do not auto-despawn. */
public final class ItemLifecycleService implements Listener {
    private static final Set<Material> RARE = Set.of(
            Material.NETHER_STAR, Material.DRAGON_EGG, Material.ELYTRA, Material.TRIDENT,
            Material.NETHERITE_INGOT, Material.NETHERITE_BLOCK, Material.NETHERITE_SCRAP,
            Material.DIAMOND_BLOCK, Material.EMERALD_BLOCK, Material.HEART_OF_THE_SEA,
            Material.TOTEM_OF_UNDYING, Material.MACE, Material.HEAVY_CORE);

    private final int ordinaryLifetimeTicks;

    private ItemLifecycleService(JavaPlugin plugin) {
        long seconds = plugin.getConfig().getLong("items.ordinary-drop-lifetime-seconds", 86400L);
        ordinaryLifetimeTicks = (int) Math.min(Integer.MAX_VALUE, Math.max(6000L, seconds * 20L));
    }

    public static void start(JavaPlugin plugin) {
        Bukkit.getPluginManager().registerEvents(new ItemLifecycleService(plugin), plugin);
    }

    @EventHandler
    public void onSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        ItemStack stack = item.getItemStack();
        if (isRare(stack)) {
            item.setUnlimitedLifetime(true);
        } else {
            item.setUnlimitedLifetime(false);
            item.setTicksLived(Math.max(0, 6000 - ordinaryLifetimeTicks));
        }
    }

    private boolean isRare(ItemStack stack) {
        if (RARE.contains(stack.getType())) return true;
        if (stack.hasItemMeta() && stack.getItemMeta().hasEnchants()) return true;
        for (Enchantment enchantment : stack.getEnchantments().keySet()) {
            if (enchantment != null) return true;
        }
        return stack.hasItemMeta() && (stack.getItemMeta().hasCustomModelData() || stack.getItemMeta().hasDisplayName());
    }
}
