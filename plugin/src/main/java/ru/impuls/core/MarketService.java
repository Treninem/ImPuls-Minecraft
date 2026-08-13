package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Transactional item market; payment and durable item delivery survive restarts. */
public final class MarketService implements Listener, AutoCloseable {
    private static final Set<Material> FORBIDDEN = Set.of(
            Material.AIR, Material.BEDROCK, Material.BARRIER, Material.COMMAND_BLOCK,
            Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK, Material.STRUCTURE_BLOCK,
            Material.JIGSAW, Material.END_PORTAL_FRAME, Material.DEBUG_STICK, Material.STRUCTURE_VOID);

    private final JavaPlugin plugin;
    private final Database db;
    private final Connection connection;

    private MarketService(JavaPlugin plugin, Database db) throws SQLException {
        this.plugin = plugin;
        this.db = db;
        File file = new File(plugin.getDataFolder(), plugin.getConfig().getString("database.file", "impuls.sqlite3"));
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS market_listings(id INTEGER PRIMARY KEY AUTOINCREMENT,seller_uuid TEXT NOT NULL,item_data TEXT NOT NULL,item_name TEXT NOT NULL,amount INTEGER NOT NULL,price INTEGER NOT NULL,fee INTEGER NOT NULL,state TEXT NOT NULL DEFAULT 'ACTIVE',created_at INTEGER NOT NULL,buyer_uuid TEXT,sold_at INTEGER)");
            s.execute("CREATE TABLE IF NOT EXISTS market_deliveries(id INTEGER PRIMARY KEY AUTOINCREMENT,buyer_uuid TEXT NOT NULL,item_data TEXT NOT NULL,listing_id INTEGER NOT NULL,claimed INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_market_active ON market_listings(state,created_at)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_market_delivery ON market_deliveries(buyer_uuid,claimed)");
        }
    }

    public static void start(JavaPlugin plugin, Database db) {
        try {
            MarketService service = new MarketService(plugin, db);
            Bukkit.getPluginManager().registerEvents(service, plugin);
        } catch (SQLException e) {
            plugin.getLogger().severe("Market SQLite init failed: " + e.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        deliverPending(event.getPlayer());
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage().trim();
        if (!raw.toLowerCase(Locale.ROOT).startsWith("/impuls market")) return;
        event.setCancelled(true);
        String[] args = raw.split("\\s+");
        Player player = event.getPlayer();
        if (args.length < 3 || "list".equalsIgnoreCase(args[2])) {
            list(player);
            return;
        }
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "sell" -> sell(player, args);
            case "buy" -> buy(player, args);
            case "cancel" -> cancel(player, args);
            case "deliver" -> deliverPending(player);
            default -> player.sendMessage("/impuls market list | sell <amount> <price> | buy <id> | cancel <id> | deliver");
        }
    }

    private void sell(Player player, String[] args) {
        if (args.length < 5) {
            player.sendMessage("/impuls market sell <amount> <price>");
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE) {
            player.sendMessage(ChatColor.RED + "Из Creative продавать нельзя.");
            return;
        }
        int amount, price;
        try {
            amount = Integer.parseInt(args[3]);
            price = Integer.parseInt(args[4]);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "Количество и цена должны быть числами.");
            return;
        }
        if (amount <= 0 || price <= 0 || price > plugin.getConfig().getInt("market.max-price", 1_000_000)) {
            player.sendMessage(ChatColor.RED + "Некорректная цена или количество.");
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (FORBIDDEN.contains(hand.getType()) || hand.getAmount() < amount) {
            player.sendMessage(ChatColor.RED + "В основной руке недостаточно разрешённых предметов.");
            return;
        }
        long day = Instant.now().getEpochSecond() / 86400L;
        int countToday = intQuery("SELECT COUNT(*) FROM market_listings WHERE seller_uuid=? AND created_at>=?", player.getUniqueId().toString(), day * 86400L);
        int dailyLimit = plugin.getConfig().getInt("market.daily-listing-limit", 20);
        if (countToday >= dailyLimit) {
            player.sendMessage(ChatColor.RED + "Дневной лимит объявлений: " + dailyLimit);
            return;
        }
        double feeRate = Math.max(0d, Math.min(0.25d, plugin.getConfig().getDouble("market.listing-fee", 0.05d)));
        int fee = Math.max(1, (int) Math.ceil(price * feeRate));
        if (db.coins(player.getUniqueId()) < fee) {
            player.sendMessage(ChatColor.RED + "Комиссия размещения: " + fee + " монет.");
            return;
        }
        ItemStack listed = hand.clone();
        listed.setAmount(amount);
        String data = InventoryCodec.encode(new ItemStack[]{listed});
        String name = listed.hasItemMeta() && listed.getItemMeta().hasDisplayName() ? ChatColor.stripColor(listed.getItemMeta().getDisplayName()) : listed.getType().name();
        boolean ok = tx(() -> {
            if (execute("UPDATE profiles SET coins=coins-? WHERE uuid=? AND coins>=?", fee, player.getUniqueId().toString(), fee) != 1) return false;
            execute("INSERT INTO market_listings(seller_uuid,item_data,item_name,amount,price,fee,state,created_at) VALUES(?,?,?,?,?,?,'ACTIVE',?)",
                    player.getUniqueId().toString(), data, name, amount, price, fee, Instant.now().getEpochSecond());
            return true;
        });
        if (!ok) {
            player.sendMessage(ChatColor.RED + "Не удалось создать объявление.");
            return;
        }
        hand.setAmount(hand.getAmount() - amount);
        player.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? new ItemStack(Material.AIR) : hand);
        db.audit(player.getUniqueId(), "market_sell", name + ":amount=" + amount + ":price=" + price + ":fee=" + fee);
        player.sendMessage(ChatColor.GREEN + "Товар выставлен за " + price + " монет. Комиссия " + fee + ".");
    }

    private void buy(Player buyer, String[] args) {
        if (args.length < 4) {
            buyer.sendMessage("/impuls market buy <id>");
            return;
        }
        long id;
        try { id = Long.parseLong(args[3]); } catch (NumberFormatException e) { buyer.sendMessage(ChatColor.RED + "Неверный ID."); return; }
        Listing listing = listing(id);
        if (listing == null || !"ACTIVE".equals(listing.state)) {
            buyer.sendMessage(ChatColor.RED + "Объявление уже недоступно.");
            return;
        }
        if (listing.seller.equals(buyer.getUniqueId())) {
            buyer.sendMessage(ChatColor.RED + "Нельзя покупать собственный товар.");
            return;
        }
        boolean ok = tx(() -> {
            try (PreparedStatement lock = connection.prepareStatement("SELECT seller_uuid,item_data,price,state FROM market_listings WHERE id=?")) {
                lock.setLong(1, id);
                try (ResultSet rs = lock.executeQuery()) {
                    if (!rs.next() || !"ACTIVE".equals(rs.getString("state"))) return false;
                    String seller = rs.getString("seller_uuid");
                    String data = rs.getString("item_data");
                    int price = rs.getInt("price");
                    if (execute("UPDATE profiles SET coins=coins-? WHERE uuid=? AND coins>=?", price, buyer.getUniqueId().toString(), price) != 1) return false;
                    execute("UPDATE profiles SET coins=coins+? WHERE uuid=?", price, seller);
                    if (execute("UPDATE market_listings SET state='SOLD',buyer_uuid=?,sold_at=? WHERE id=? AND state='ACTIVE'", buyer.getUniqueId().toString(), Instant.now().getEpochSecond(), id) != 1) return false;
                    execute("INSERT INTO market_deliveries(buyer_uuid,item_data,listing_id,created_at) VALUES(?,?,?,?)", buyer.getUniqueId().toString(), data, id, Instant.now().getEpochSecond());
                    return true;
                }
            }
        });
        if (!ok) {
            buyer.sendMessage(ChatColor.RED + "Покупка не выполнена: товар уже куплен или не хватает монет.");
            return;
        }
        db.audit(buyer.getUniqueId(), "market_buy", "listing=" + id + ":price=" + listing.price);
        deliverPending(buyer);
    }

    private void cancel(Player seller, String[] args) {
        if (args.length < 4) {
            seller.sendMessage("/impuls market cancel <id>");
            return;
        }
        long id;
        try { id = Long.parseLong(args[3]); } catch (NumberFormatException e) { return; }
        Listing listing = listing(id);
        if (listing == null || !listing.seller.equals(seller.getUniqueId()) || !"ACTIVE".equals(listing.state)) {
            seller.sendMessage(ChatColor.RED + "Нельзя отменить это объявление.");
            return;
        }
        boolean ok = tx(() -> {
            if (execute("UPDATE market_listings SET state='CANCELLED' WHERE id=? AND seller_uuid=? AND state='ACTIVE'", id, seller.getUniqueId().toString()) != 1) return false;
            execute("INSERT INTO market_deliveries(buyer_uuid,item_data,listing_id,created_at) VALUES(?,?,?,?)", seller.getUniqueId().toString(), listing.itemData, id, Instant.now().getEpochSecond());
            return true;
        });
        if (ok) {
            seller.sendMessage(ChatColor.YELLOW + "Объявление отменено. Комиссия не возвращается; предмет возвращён через доставку.");
            deliverPending(seller);
        }
    }

    private void list(Player player) {
        player.sendMessage(ChatColor.GOLD + "════ Рынок ImPuls ════");
        try (PreparedStatement ps = connection.prepareStatement("SELECT id,item_name,amount,price FROM market_listings WHERE state='ACTIVE' ORDER BY id DESC LIMIT 12"); ResultSet rs = ps.executeQuery()) {
            int rows = 0;
            while (rs.next()) {
                rows++;
                player.sendMessage(ChatColor.YELLOW + "#" + rs.getLong(1) + ChatColor.WHITE + " " + rs.getString(2) + " ×" + rs.getInt(3) + ChatColor.GRAY + " — " + rs.getInt(4) + " монет");
            }
            if (rows == 0) player.sendMessage(ChatColor.GRAY + "Активных объявлений нет.");
        } catch (SQLException e) {
            player.sendMessage(ChatColor.RED + "Ошибка чтения рынка.");
        }
    }

    private void deliverPending(Player player) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT id,item_data,listing_id FROM market_deliveries WHERE buyer_uuid=? AND claimed=0 ORDER BY id")) {
            ps.setString(1, player.getUniqueId().toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long deliveryId = rs.getLong(1);
                    ItemStack[] decoded = InventoryCodec.decode(rs.getString(2));
                    ItemStack item = decoded.length == 0 ? null : decoded[0];
                    if (item == null || item.getType().isAir()) {
                        execute("UPDATE market_deliveries SET claimed=1 WHERE id=?", deliveryId);
                        continue;
                    }
                    var overflow = player.getInventory().addItem(item);
                    for (ItemStack extra : overflow.values()) player.getWorld().dropItemNaturally(player.getLocation(), extra);
                    execute("UPDATE market_deliveries SET claimed=1 WHERE id=?", deliveryId);
                    player.sendMessage(ChatColor.GREEN + "[ImPuls] Получена рыночная доставка #" + rs.getLong(3) + ".");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Market delivery failed for " + player.getName() + ": " + e.getMessage());
        }
    }

    private record Listing(long id, UUID seller, String itemData, int price, String state) { }
    private Listing listing(long id) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT seller_uuid,item_data,price,state FROM market_listings WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Listing(id, UUID.fromString(rs.getString(1)), rs.getString(2), rs.getInt(3), rs.getString(4));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private int intQuery(String sql, Object... args) {
        try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        catch (SQLException e) { throw new RuntimeException(e); }
    }
    private PreparedStatement prepare(String sql, Object... args) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
        return ps;
    }
    private int execute(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = prepare(sql, args)) { return ps.executeUpdate(); }
    }
    private interface TxBody { boolean run() throws Exception; }
    private boolean tx(TxBody body) {
        try {
            connection.setAutoCommit(false);
            boolean ok = body.run();
            if (ok) connection.commit(); else connection.rollback();
            connection.setAutoCommit(true);
            return ok;
        } catch (Exception e) {
            try { connection.rollback(); } catch (SQLException ignored) { }
            try { connection.setAutoCommit(true); } catch (SQLException ignored) { }
            return false;
        }
    }

    @Override
    public void close() {
        try { connection.close(); } catch (SQLException ignored) { }
    }
}
