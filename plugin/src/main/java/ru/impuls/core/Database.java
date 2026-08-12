package ru.impuls.core;

import org.bukkit.entity.Player;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

public final class Database implements AutoCloseable {
    private final Connection connection;

    public Database(File file) throws SQLException {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("CREATE TABLE IF NOT EXISTS profiles(uuid TEXT PRIMARY KEY,name TEXT NOT NULL,coins INTEGER NOT NULL DEFAULT 100,rank INTEGER NOT NULL DEFAULT 0,xp INTEGER NOT NULL DEFAULT 0,quests INTEGER NOT NULL DEFAULT 0,dungeons INTEGER NOT NULL DEFAULT 0,insured INTEGER NOT NULL DEFAULT 0,defender INTEGER NOT NULL DEFAULT 0,last_seen INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS guilds(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,owner_uuid TEXT NOT NULL,level INTEGER NOT NULL DEFAULT 1,treasury INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS guild_members(guild_id INTEGER NOT NULL,uuid TEXT NOT NULL UNIQUE,role TEXT NOT NULL,joined_at INTEGER NOT NULL,PRIMARY KEY(guild_id,uuid),FOREIGN KEY(guild_id) REFERENCES guilds(id) ON DELETE CASCADE)");
            statement.execute("CREATE TABLE IF NOT EXISTS guild_invites(guild_id INTEGER NOT NULL,uuid TEXT NOT NULL,expires_at INTEGER NOT NULL,PRIMARY KEY(guild_id,uuid))");
            statement.execute("CREATE TABLE IF NOT EXISTS claims(id INTEGER PRIMARY KEY AUTOINCREMENT,owner_uuid TEXT NOT NULL,world TEXT NOT NULL,min_x INTEGER NOT NULL,max_x INTEGER NOT NULL,min_y INTEGER NOT NULL,max_y INTEGER NOT NULL,min_z INTEGER NOT NULL,max_z INTEGER NOT NULL,kind TEXT NOT NULL,last_active INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS creative_blocks(world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,owner_uuid TEXT NOT NULL,PRIMARY KEY(world,x,y,z))");
            statement.execute("CREATE TABLE IF NOT EXISTS cooldowns(uuid TEXT NOT NULL,kind TEXT NOT NULL,until_epoch INTEGER NOT NULL,PRIMARY KEY(uuid,kind))");
            statement.execute("CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT,ts INTEGER NOT NULL,actor_uuid TEXT,action TEXT NOT NULL,details TEXT NOT NULL)");
        }
    }

    public synchronized void ensure(Player player) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO profiles(uuid,name,last_seen) VALUES(?,?,?) " +
                        "ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,last_seen=excluded.last_seen")) {
            ps.setString(1, player.getUniqueId().toString());
            ps.setString(2, player.getName());
            ps.setLong(3, now());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized int coins(UUID uuid) {
        return intQuery("SELECT coins FROM profiles WHERE uuid=?", uuid, 0);
    }

    public synchronized int rank(UUID uuid) {
        return intQuery("SELECT rank FROM profiles WHERE uuid=?", uuid, 0);
    }

    public synchronized boolean insured(UUID uuid) {
        return intQuery("SELECT insured FROM profiles WHERE uuid=?", uuid, 0) > 0;
    }

    public synchronized void setInsured(UUID uuid, boolean value) {
        update("UPDATE profiles SET insured=? WHERE uuid=?", value ? 1 : 0, uuid.toString());
    }

    public synchronized boolean charge(UUID uuid, int amount, String reason) {
        if (amount <= 0) {
            return false;
        }
        try {
            connection.setAutoCommit(false);
            if (coins(uuid) < amount) {
                rollbackTransaction();
                return false;
            }
            update("UPDATE profiles SET coins=coins-? WHERE uuid=?", amount, uuid.toString());
            audit(uuid, "charge", reason + ":" + amount);
            commitTransaction();
            return true;
        } catch (Exception e) {
            rollbackQuietly();
            throw new RuntimeException(e);
        }
    }

    public synchronized void credit(UUID uuid, int amount, String reason) {
        if (amount <= 0) {
            return;
        }
        update("UPDATE profiles SET coins=coins+? WHERE uuid=?", amount, uuid.toString());
        audit(uuid, "credit", reason + ":" + amount);
    }

    public synchronized void addDefender(UUID uuid, int amount) {
        update("UPDATE profiles SET defender=defender+? WHERE uuid=?", amount, uuid.toString());
    }

    public synchronized int defender(UUID uuid) {
        return intQuery("SELECT defender FROM profiles WHERE uuid=?", uuid, 0);
    }

    public synchronized Long guildId(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT guild_id FROM guild_members WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized String guildName(long guildId) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT name FROM guilds WHERE id=?")) {
            ps.setLong(1, guildId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized boolean createGuild(UUID owner, String name, int cost) {
        if (guildId(owner) != null || name == null || name.isBlank() || cost < 0) {
            return false;
        }
        try {
            connection.setAutoCommit(false);
            if (coins(owner) < cost) {
                rollbackTransaction();
                return false;
            }
            long guildId;
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO guilds(name,owner_uuid,created_at) VALUES(?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, owner.toString());
                ps.setLong(3, now());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (!rs.next()) {
                        rollbackTransaction();
                        return false;
                    }
                    guildId = rs.getLong(1);
                }
            }
            update("INSERT INTO guild_members(guild_id,uuid,role,joined_at) VALUES(?,?,?,?)",
                    guildId, owner.toString(), "LEADER", now());
            update("UPDATE profiles SET coins=coins-? WHERE uuid=?", cost, owner.toString());
            audit(owner, "guild_create", name);
            commitTransaction();
            return true;
        } catch (Exception e) {
            rollbackQuietly();
            return false;
        }
    }

    public synchronized boolean invite(UUID leader, UUID target) {
        Long guildId = guildId(leader);
        if (guildId == null || guildId(target) != null) {
            return false;
        }
        String role = memberRole(leader);
        if (!"LEADER".equals(role) && !"DEPUTY".equals(role)) {
            return false;
        }
        update("INSERT INTO guild_invites(guild_id,uuid,expires_at) VALUES(?,?,?) " +
                        "ON CONFLICT(guild_id,uuid) DO UPDATE SET expires_at=excluded.expires_at",
                guildId, target.toString(), now() + 86400);
        return true;
    }

    public synchronized boolean acceptInvite(UUID uuid) {
        if (guildId(uuid) != null) {
            return false;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT guild_id FROM guild_invites WHERE uuid=? AND expires_at>? ORDER BY expires_at DESC LIMIT 1")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, now());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                long guildId = rs.getLong(1);
                update("INSERT INTO guild_members(guild_id,uuid,role,joined_at) VALUES(?,?,?,?)",
                        guildId, uuid.toString(), "MEMBER", now());
                update("DELETE FROM guild_invites WHERE uuid=?", uuid.toString());
                audit(uuid, "guild_join", String.valueOf(guildId));
                return true;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized boolean leaveGuild(UUID uuid) {
        Long guildId = guildId(uuid);
        if (guildId == null || "LEADER".equals(memberRole(uuid))) {
            return false;
        }
        update("DELETE FROM guild_members WHERE uuid=?", uuid.toString());
        audit(uuid, "guild_leave", String.valueOf(guildId));
        return true;
    }

    public synchronized String memberRole(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT role FROM guild_members WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized boolean depositGuild(UUID uuid, int amount) {
        Long guildId = guildId(uuid);
        if (guildId == null || amount <= 0 || coins(uuid) < amount) {
            return false;
        }
        try {
            connection.setAutoCommit(false);
            update("UPDATE profiles SET coins=coins-? WHERE uuid=?", amount, uuid.toString());
            update("UPDATE guilds SET treasury=treasury+? WHERE id=?", amount, guildId);
            audit(uuid, "guild_deposit", guildId + ":" + amount);
            commitTransaction();
            return true;
        } catch (Exception e) {
            rollbackQuietly();
            return false;
        }
    }

    public synchronized int guildTreasury(long guildId) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT treasury FROM guilds WHERE id=?")) {
            ps.setLong(1, guildId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized long cooldown(UUID uuid, String kind) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT until_epoch FROM cooldowns WHERE uuid=? AND kind=?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, kind);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void setCooldown(UUID uuid, String kind, long until) {
        update("INSERT INTO cooldowns(uuid,kind,until_epoch) VALUES(?,?,?) " +
                        "ON CONFLICT(uuid,kind) DO UPDATE SET until_epoch=excluded.until_epoch",
                uuid.toString(), kind, until);
    }

    public record Claim(long id, UUID owner, String world, int minX, int maxX, int minY, int maxY,
                        int minZ, int maxZ, String kind) {
        public boolean contains(String worldName, int x, int y, int z) {
            return world.equals(worldName)
                    && x >= minX && x <= maxX
                    && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
        }
    }

    public synchronized Claim claimAt(String world, int x, int y, int z) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id,owner_uuid,world,min_x,max_x,min_y,max_y,min_z,max_z,kind FROM claims " +
                        "WHERE world=? AND ? BETWEEN min_x AND max_x AND ? BETWEEN min_y AND max_y " +
                        "AND ? BETWEEN min_z AND max_z ORDER BY id LIMIT 1")) {
            ps.setString(1, world);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Claim(
                        rs.getLong(1),
                        UUID.fromString(rs.getString(2)),
                        rs.getString(3),
                        rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9),
                        rs.getString(10));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized boolean ownsClaimAt(UUID uuid, String world, int x, int y, int z, String kind) {
        Claim claim = claimAt(world, x, y, z);
        return claim != null && claim.owner().equals(uuid) && (kind == null || kind.equals(claim.kind()));
    }

    public synchronized int claimCount(UUID uuid, String kind) {
        String sql = kind == null
                ? "SELECT COUNT(*) FROM claims WHERE owner_uuid=?"
                : "SELECT COUNT(*) FROM claims WHERE owner_uuid=? AND kind=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            if (kind != null) {
                ps.setString(2, kind);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized boolean createClaim(UUID owner, String world,
                                            int minX, int maxX, int minY, int maxY,
                                            int minZ, int maxZ, String kind, int cost) {
        if (minX > maxX || minY > maxY || minZ > maxZ || cost < 0) {
            return false;
        }
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement overlap = connection.prepareStatement(
                    "SELECT 1 FROM claims WHERE world=? AND " +
                            "NOT(max_x<? OR min_x>? OR max_y<? OR min_y>? OR max_z<? OR min_z>?) LIMIT 1")) {
                overlap.setString(1, world);
                overlap.setInt(2, minX);
                overlap.setInt(3, maxX);
                overlap.setInt(4, minY);
                overlap.setInt(5, maxY);
                overlap.setInt(6, minZ);
                overlap.setInt(7, maxZ);
                try (ResultSet rs = overlap.executeQuery()) {
                    if (rs.next()) {
                        rollbackTransaction();
                        return false;
                    }
                }
            }
            if (coins(owner) < cost) {
                rollbackTransaction();
                return false;
            }
            update("INSERT INTO claims(owner_uuid,world,min_x,max_x,min_y,max_y,min_z,max_z,kind,last_active) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?)",
                    owner.toString(), world, minX, maxX, minY, maxY, minZ, maxZ, kind, now());
            update("UPDATE profiles SET coins=coins-? WHERE uuid=?", cost, owner.toString());
            audit(owner, "claim_buy", kind + ":" + world + ":" + minX + "," + minZ + ".." + maxX + "," + maxZ + ":" + cost);
            commitTransaction();
            return true;
        } catch (Exception e) {
            rollbackQuietly();
            return false;
        }
    }

    public synchronized void addCreativeBlock(String world, int x, int y, int z, UUID owner) {
        update("INSERT OR REPLACE INTO creative_blocks(world,x,y,z,owner_uuid) VALUES(?,?,?,?,?)",
                world, x, y, z, owner.toString());
    }

    public synchronized boolean removeCreativeBlock(String world, int x, int y, int z) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM creative_blocks WHERE world=? AND x=? AND y=? AND z=?")) {
            ps.setString(1, world);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void audit(UUID actor, String action, String details) {
        update("INSERT INTO audit(ts,actor_uuid,action,details) VALUES(?,?,?,?)",
                now(), actor == null ? null : actor.toString(), action, details == null ? "" : details);
    }

    private int intQuery(String sql, UUID uuid, int fallback) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : fallback;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void update(String sql, Object... args) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void commitTransaction() throws SQLException {
        connection.commit();
        connection.setAutoCommit(true);
    }

    private void rollbackTransaction() throws SQLException {
        connection.rollback();
        connection.setAutoCommit(true);
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
        }
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
