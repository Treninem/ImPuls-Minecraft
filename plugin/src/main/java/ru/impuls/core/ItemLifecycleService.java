package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.Set;

/** Implements ImPuls drop lifetime: ordinary drops survive up to 24h, valuable drops do not auto-despawn. */
public final class ItemLifecycleService implements Listener {
    private static final Set<Material> RARE = Set.of(
            Material.NETHER_STAR, Material.DRAGON_EGG, Material.ELYTRA, Material.TRIDENT,
            Material.NETHERITE_INGOT, Material.NETHERITE_BLOCK, Material.NETHERITE_SCRAP,
            Material.DIAMOND_BLOCK, Material.EMERALD_BLOCK, Material.HEART_OF_THE_SEA,
            Material.TOTEM_OF_UNDYING, Material.MACE, Material.HEAVY_CORE);

    private final JavaPlugin plugin;
    private final long ordinaryLifetimeSeconds;
    private final NamespacedKey droppedAtKey;
    private final NamespacedKey rareKey;

    private ItemLifecycleService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.ordinaryLifetimeSeconds = Math.max(300L, plugin.getConfig().getLong("items.ordinary-drop-lifetime-seconds", 86400L));
        this.droppedAtKey = new NamespacedKey(plugin, "drop_epoch");
        this.rareKey = new NamespacedKey(plugin, "rare_drop");
    }

    public static void start(JavaPlugin plugin) {
        ItemLifecycleService service = new ItemLifecycleService(plugin);
        Bukkit.getPluginManager().registerEvents(service, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, service::cleanup, 20L * 60L, 20L * 60L);
    }

    @EventHandler
    public void onSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        boolean rare = isRare(item.getItemStack());
        item.setUnlimitedLifetime(true);
        item.getPersistentDataContainer().set(droppedAtKey, PersistentDataType.LONG, Instant.now().getEpochSecond());
        item.getPersistentDataContainer().set(rareKey, PersistentDataType.BYTE, rare ? (byte) 1 : (byte) 0);
    }

    private void cleanup() {
        long now = Instant.now().getEpochSecond();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Item item)) continue;
                Byte rare = item.getPersistentDataContainer().get(rareKey, PersistentDataType.BYTE);
                if (rare != null && rare == (byte) 1) {
                    item.setUnlimitedLifetime(true);
                    continue;
                }
                Long dropped = item.getPersistentDataContainer().get(droppedAtKey, PersistentDataType.LONG);
                if (dropped == null) {
                    dropped = now;
                    item.getPersistentDataContainer().set(droppedAtKey, PersistentDataType.LONG, dropped);
                    item.setUnlimitedLifetime(true);
                }
                if (now - dropped >= ordinaryLifetimeSeconds) item.remove();
            }
        }
    }

    private boolean isRare(ItemStack stack) {
        if (RARE.contains(stack.getType())) return true;
        if (stack.hasItemMeta() && stack.getItemMeta().hasEnchants()) return true;
        return stack.hasItemMeta() && (stack.getItemMeta().hasCustomModelData() || stack.getItemMeta().hasDisplayName());
    }
}
