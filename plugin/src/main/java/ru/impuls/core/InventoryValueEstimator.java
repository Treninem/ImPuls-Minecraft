package ru.impuls.core;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.Set;

public final class InventoryValueEstimator {
    private static final Set<Material> PREMIUM = Set.of(
            Material.NETHERITE_SWORD, Material.NETHERITE_AXE, Material.NETHERITE_PICKAXE,
            Material.NETHERITE_SHOVEL, Material.NETHERITE_HOE, Material.NETHERITE_HELMET,
            Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS,
            Material.ELYTRA, Material.NETHER_STAR, Material.TOTEM_OF_UNDYING, Material.DRAGON_EGG,
            Material.HEAVY_CORE, Material.MACE
    );
    private static final Set<Material> HIGH = Set.of(
            Material.DIAMOND, Material.DIAMOND_BLOCK, Material.EMERALD_BLOCK, Material.ANCIENT_DEBRIS,
            Material.NETHERITE_SCRAP, Material.NETHERITE_INGOT, Material.ENCHANTED_GOLDEN_APPLE,
            Material.DIAMOND_SWORD, Material.DIAMOND_AXE, Material.DIAMOND_PICKAXE,
            Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS
    );

    private InventoryValueEstimator() {}

    public static int estimate(ItemStack[] items) {
        long total = 0;
        if (items == null) return 0;
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            total += estimateItem(item);
            if (total > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) total;
    }

    private static long estimateItem(ItemStack item) {
        Material type = item.getType();
        long unit;
        if (PREMIUM.contains(type)) unit = 1500;
        else if (HIGH.contains(type)) unit = 350;
        else if (type.name().contains("DIAMOND")) unit = 220;
        else if (type.name().contains("GOLD")) unit = 70;
        else if (type.name().contains("IRON")) unit = 35;
        else if (type.name().contains("COPPER")) unit = 15;
        else unit = Math.max(2, type.getMaxStackSize() == 1 ? 45 : 4);
        long value = unit * Math.max(1, item.getAmount());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            Map<Enchantment, Integer> enchants = meta.getEnchants();
            for (int level : enchants.values()) value += 180L * Math.max(1, level);
            if (meta.hasCustomModelData()) value += 250;
            if (meta.hasDisplayName()) value += 25;
        }
        return value;
    }

    public static int insurancePrice(ItemStack[] items, int base, int min, int max) {
        int value = estimate(items);
        long dynamic = base + Math.round(value * 0.12d);
        return (int) Math.max(min, Math.min(max, dynamic));
    }
}
