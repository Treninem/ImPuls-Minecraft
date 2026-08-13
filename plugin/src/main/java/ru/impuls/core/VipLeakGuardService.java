package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;

/** Extra server-side guards for VIP Creative item transfer vectors. */
public final class VipLeakGuardService implements Listener {
    private static final Set<Material> INVENTORY_BLOCKS = Set.of(
            Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL, Material.HOPPER,
            Material.DROPPER, Material.DISPENSER, Material.FURNACE, Material.BLAST_FURNACE,
            Material.SMOKER, Material.BREWING_STAND, Material.CHISELED_BOOKSHELF,
            Material.DECORATED_POT, Material.JUKEBOX);
    private final Database db;

    private VipLeakGuardService(Database db) { this.db = db; }

    public static void start(JavaPlugin plugin, Database db) {
        Bukkit.getPluginManager().registerEvents(new VipLeakGuardService(db), plugin);
    }

    private boolean creative(Player player) { return db.creativeActive(player.getUniqueId()); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (creative(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        if (!creative(event.getPlayer())) return;
        Material type = event.getBlockPlaced().getType();
        if (INVENTORY_BLOCKS.contains(type) || isShulker(type)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (!creative(event.getPlayer())) return;
        if (event.getRightClicked() instanceof ArmorStand || event.getRightClicked() instanceof ItemFrame) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (creative(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onCreativeInventory(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !creative(player)) return;
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        if (dangerousContainer(cursor) || dangerousContainer(current)) event.setCancelled(true);
    }

    private boolean dangerousContainer(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return false;
        return INVENTORY_BLOCKS.contains(stack.getType()) || isShulker(stack.getType());
    }

    private boolean isShulker(Material material) {
        return material.name().endsWith("SHULKER_BOX");
    }
}
