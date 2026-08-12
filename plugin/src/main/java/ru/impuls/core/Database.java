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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Database implements AutoCloseable {
    private final Connection connection;

    public Database(File file) throws SQLException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
        }
        migrate();
    }

    private void migrate() throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS profiles(uuid TEXT PRIMARY KEY,name TEXT NOT NULL,coins INTEGER NOT NULL DEFAULT 100,rank INTEGER NOT NULL DEFAULT 0,xp INTEGER NOT NULL DEFAULT 0,quests INTEGER NOT NULL DEFAULT 0,dungeons INTEGER NOT NULL DEFAULT 0,insured INTEGER NOT NULL DEFAULT 0,defender INTEGER NOT NULL DEFAULT 0,last_seen INTEGER NOT NULL,creative_snapshot TEXT,creative_active INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS guilds(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,owner_uuid TEXT NOT NULL,level INTEGER NOT NULL DEFAULT 1,treasury INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS guild_members(guild_id INTEGER NOT NULL,uuid TEXT NOT NULL UNIQUE,role TEXT NOT NULL,joined_at INTEGER NOT NULL,PRIMARY KEY(guild_id,uuid),FOREIGN KEY(guild_id) REFERENCES guilds(id) ON DELETE CASCADE)");
            s.execute("CREATE TABLE IF NOT EXISTS guild_invites(guild_id INTEGER NOT NULL,uuid TEXT NOT NULL,expires_at INTEGER NOT NULL,PRIMARY KEY(guild_id,uuid))");
            s.execute("CREATE TABLE IF NOT EXISTS claims(id INTEGER PRIMARY KEY AUTOINCREMENT,owner_uuid TEXT NOT NULL,world TEXT NOT NULL,min_x INTEGER NOT NULL,max_x INTEGER NOT NULL,min_y INTEGER NOT NULL,max_y INTEGER NOT NULL,min_z INTEGER NOT NULL,max_z INTEGER NOT NULL,kind TEXT NOT NULL,last_active INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS claim_sales(claim_id INTEGER PRIMARY KEY,seller_uuid TEXT NOT NULL,buyer_uuid TEXT NOT NULL,price INTEGER NOT NULL,expires_at INTEGER NOT NULL,FOREIGN KEY(claim_id) REFERENCES claims(id) ON DELETE CASCADE)");
            s.execute("CREATE TABLE IF NOT EXISTS container_protection(world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,owner_uuid TEXT NOT NULL,expires_at INTEGER NOT NULL,PRIMARY KEY(world,x,y,z))");
            s.execute("CREATE TABLE IF NOT EXISTS creative_blocks(world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,owner_uuid TEXT NOT NULL,PRIMARY KEY(world,x,y,z))");
            s.execute("CREATE TABLE IF NOT EXISTS cooldowns(uuid TEXT NOT NULL,kind TEXT NOT NULL,until_epoch INTEGER NOT NULL,PRIMARY KEY(uuid,kind))");
            s.execute("CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT,ts INTEGER NOT NULL,actor_uuid TEXT,action TEXT NOT NULL,details TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS player_sessions(uuid TEXT PRIMARY KEY,context TEXT NOT NULL,inventory TEXT NOT NULL,world TEXT NOT NULL,x REAL NOT NULL,y REAL NOT NULL,z REAL NOT NULL,yaw REAL NOT NULL,pitch REAL NOT NULL,gamemode TEXT NOT NULL,level INTEGER NOT NULL,exp REAL NOT NULL,created_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS guild_wars(id INTEGER PRIMARY KEY AUTOINCREMENT,guild_a INTEGER NOT NULL,guild_b INTEGER NOT NULL,state TEXT NOT NULL,created_at INTEGER NOT NULL,started_at INTEGER,ended_at INTEGER,treasury_a_locked INTEGER NOT NULL DEFAULT 0,treasury_b_locked INTEGER NOT NULL DEFAULT 0,score_a INTEGER NOT NULL DEFAULT 0,score_b INTEGER NOT NULL DEFAULT 0,winner_guild INTEGER,captured INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS guild_war_roster(war_id INTEGER NOT NULL,uuid TEXT NOT NULL,guild_id INTEGER NOT NULL,rank INTEGER NOT NULL,PRIMARY KEY(war_id,uuid),FOREIGN KEY(war_id) REFERENCES guild_wars(id) ON DELETE CASCADE)");
            s.execute("CREATE TABLE IF NOT EXISTS guild_war_cooldowns(loser_guild INTEGER PRIMARY KEY,until_epoch INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS dungeon_runs(id INTEGER PRIMARY KEY AUTOINCREMENT,instance_key TEXT NOT NULL UNIQUE,rank INTEGER NOT NULL,seed INTEGER NOT NULL,floors INTEGER NOT NULL,current_floor INTEGER NOT NULL DEFAULT 1,state TEXT NOT NULL,leader_uuid TEXT NOT NULL,started_at INTEGER NOT NULL,ended_at INTEGER)");
            s.execute("CREATE TABLE IF NOT EXISTS dungeon_members(run_id INTEGER NOT NULL,uuid TEXT NOT NULL,entry_rank INTEGER NOT NULL,alive INTEGER NOT NULL DEFAULT 1,rewarded INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(run_id,uuid),FOREIGN KEY(run_id) REFERENCES dungeon_runs(id) ON DELETE CASCADE)");
        }
        ensureColumn("profiles", "creative_snapshot", "TEXT");
        ensureColumn("profiles", "creative_active", "INTEGER NOT NULL DEFAULT 0");
    }

    private void ensureColumn(String table, String column, String ddl) throws SQLException {
        boolean found = false;
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            try (Statement s = connection.createStatement()) {
                s.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + ddl);
            }
        }
    }

    public synchronized void ensure(Player player) {
        update("INSERT INTO profiles(uuid,name,last_seen) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,last_seen=excluded.last_seen",
                player.getUniqueId().toString(), player.getName(), now());
        update("UPDATE claims SET last_active=? WHERE owner_uuid=?", now(), player.getUniqueId().toString());
    }

    public synchronized int coins(UUID uuid) { return intQuery("SELECT coins FROM profiles WHERE uuid=?", 0, uuid.toString()); }
    public synchronized int rank(UUID uuid) { return intQuery("SELECT rank FROM profiles WHERE uuid=?", 0, uuid.toString()); }
    public synchronized int defender(UUID uuid) { return intQuery("SELECT defender FROM profiles WHERE uuid=?", 0, uuid.toString()); }
    public synchronized boolean insured(UUID uuid) { return intQuery("SELECT insured FROM profiles WHERE uuid=?", 0, uuid.toString()) > 0; }
    public synchronized void setInsured(UUID uuid, boolean value) { update("UPDATE profiles SET insured=? WHERE uuid=?", value ? 1 : 0, uuid.toString()); }
    public synchronized void addDefender(UUID uuid, int amount) { update("UPDATE profiles SET defender=defender+? WHERE uuid=?", amount, uuid.toString()); }

    public synchronized boolean charge(UUID uuid, int amount, String reason) {
        if (amount <= 0 || coins(uuid) < amount) return false;
        return tx(() -> {
            if (executeUpdate("UPDATE profiles SET coins=coins-? WHERE uuid=? AND coins>=?", amount, uuid.toString(), amount) != 1) return false;
            audit(uuid, "charge", reason + ":" + amount);
            return true;
        });
    }

    public synchronized void credit(UUID uuid, int amount, String reason) {
        if (amount <= 0) return;
        update("UPDATE profiles SET coins=coins+? WHERE uuid=?", amount, uuid.toString());
        audit(uuid, "credit", reason + ":" + amount);
    }

    public synchronized Long guildId(UUID uuid) { return longNullable("SELECT guild_id FROM guild_members WHERE uuid=?", uuid.toString()); }
    public synchronized Long guildIdByName(String name) { return longNullable("SELECT id FROM guilds WHERE lower(name)=lower(?)", name); }
    public synchronized String guildName(long id) { return stringQuery("SELECT name FROM guilds WHERE id=?", id); }
    public synchronized String memberRole(UUID uuid) { return stringQuery("SELECT role FROM guild_members WHERE uuid=?", uuid.toString()); }
    public synchronized int guildTreasury(long id) { return intQuery("SELECT treasury FROM guilds WHERE id=?", 0, id); }

    public synchronized boolean createGuild(UUID owner, String name, int cost) {
        if (guildId(owner) != null || name == null || name.isBlank() || coins(owner) < cost) return false;
        return tx(() -> {
            long guildId;
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO guilds(name,owner_uuid,created_at) VALUES(?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, owner.toString());
                ps.setLong(3, now());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (!rs.next()) return false;
                    guildId = rs.getLong(1);
                }
            }
            if (executeUpdate("UPDATE profiles SET coins=coins-? WHERE uuid=? AND coins>=?", cost, owner.toString(), cost) != 1) return false;
            update("INSERT INTO guild_members(guild_id,uuid,role,joined_at) VALUES(?,?,?,?)", guildId, owner.toString(), "LEADER", now());
            audit(owner, "guild_create", name);
            return true;
        });
    }

    public synchronized boolean invite(UUID actor, UUID target) {
        Long gid = guildId(actor);
        String role = memberRole(actor);
        if (gid == null || guildId(target) != null || !("LEADER".equals(role) || "DEPUTY".equals(role))) return false;
        update("INSERT INTO guild_invites(guild_id,uuid,expires_at) VALUES(?,?,?) ON CONFLICT(guild_id,uuid) DO UPDATE SET expires_at=excluded.expires_at",
                gid, target.toString(), now() + 86400);
        return true;
    }

    public synchronized boolean acceptInvite(UUID uuid) {
        if (guildId(uuid) != null) return false;
        try (PreparedStatement ps = connection.prepareStatement("SELECT guild_id FROM guild_invites WHERE uuid=? AND expires_at>? ORDER BY expires_at DESC LIMIT 1")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, now());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                long gid = rs.getLong(1);
                update("INSERT INTO guild_members(guild_id,uuid,role,joined_at) VALUES(?,?,?,?)", gid, uuid.toString(), "MEMBER", now());
                update("DELETE FROM guild_invites WHERE uuid=?", uuid.toString());
                return true;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized boolean leaveGuild(UUID uuid) {
        Long gid = guildId(uuid);
        if (gid == null || "LEADER".equals(memberRole(uuid)) || activeWarForGuild(gid) != null) return false;
        update("DELETE FROM guild_members WHERE uuid=?", uuid.toString());
        return true;
    }

    public synchronized boolean depositGuild(UUID uuid, int amount) {
        Long gid = guildId(uuid);
        if (gid == null || amount <= 0 || coins(uuid) < amount) return false;
        return tx(() -> {
            if (executeUpdate("UPDATE profiles SET coins=coins-? WHERE uuid=? AND coins>=?", amount, uuid.toString(), amount) != 1) return false;
            update("UPDATE guilds SET treasury=treasury+? WHERE id=?", amount, gid);
            audit(uuid, "guild_deposit", gid + ":" + amount);
            return true;
        });
    }

    public synchronized boolean setGuildRole(UUID leader, UUID target, String role) {
        Long gid = guildId(leader);
        if (gid == null || !"LEADER".equals(memberRole(leader)) || !gid.equals(guildId(target))) return false;
        if (!("DEPUTY".equals(role) || "MEMBER".equals(role))) return false;
        update("UPDATE guild_members SET role=? WHERE guild_id=? AND uuid=?", role, gid, target.toString());
        return true;
    }

    public synchronized boolean transferGuildLeadership(UUID leader, UUID target) {
        Long gid = guildId(leader);
        if (gid == null || !"LEADER".equals(memberRole(leader)) || !gid.equals(guildId(target)) || activeWarForGuild(gid) != null) return false;
        return tx(() -> {
            update("UPDATE guild_members SET role='DEPUTY' WHERE uuid=?", leader.toString());
            update("UPDATE guild_members SET role='LEADER' WHERE uuid=?", target.toString());
            update("UPDATE guilds SET owner_uuid=? WHERE id=?", target.toString(), gid);
            return true;
        });
    }

    public record GuildMember(UUID uuid, int rank, String role) {}
    public synchronized List<GuildMember> guildMembers(long gid) {
        List<GuildMember> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT gm.uuid,p.rank,gm.role FROM guild_members gm JOIN profiles p ON p.uuid=gm.uuid WHERE gm.guild_id=? ORDER BY gm.joined_at")) {
            ps.setLong(1, gid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new GuildMember(UUID.fromString(rs.getString(1)), rs.getInt(2), rs.getString(3)));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public record WarInfo(long id, long guildA, long guildB, String state, int scoreA, int scoreB, long startedAt) {}

    public synchronized boolean challengeWar(UUID leader, String targetGuildName) {
        Long a = guildId(leader);
        Long b = guildIdByName(targetGuildName);
        if (a == null || b == null || a.equals(b) || !"LEADER".equals(memberRole(leader))) return false;
        if (activeWarForGuild(a) != null || activeWarForGuild(b) != null) return false;
        long until = longQuery("SELECT until_epoch FROM guild_war_cooldowns WHERE loser_guild=?", 0L, b);
        if (until > now()) return false;
        update("INSERT INTO guild_wars(guild_a,guild_b,state,created_at) VALUES(?,?,'PENDING',?)", a, b, now());
        return true;
    }

    public synchronized WarInfo activeWarForGuild(long gid) {
        return warQuery("SELECT id,guild_a,guild_b,state,score_a,score_b,COALESCE(started_at,0) FROM guild_wars WHERE (guild_a=? OR guild_b=?) AND state IN ('PENDING','ACTIVE') ORDER BY id DESC LIMIT 1", gid, gid);
    }

    public synchronized WarInfo warById(long id) {
        return warQuery("SELECT id,guild_a,guild_b,state,score_a,score_b,COALESCE(started_at,0) FROM guild_wars WHERE id=?", id);
    }

    private WarInfo pendingWarForTarget(long gid) {
        return warQuery("SELECT id,guild_a,guild_b,state,score_a,score_b,COALESCE(started_at,0) FROM guild_wars WHERE guild_b=? AND state='PENDING' ORDER BY id DESC LIMIT 1", gid);
    }

    public synchronized boolean acceptWar(UUID leader, int maxCountDiff, double maxRankDiff) {
        Long target = guildId(leader);
        if (target == null || !"LEADER".equals(memberRole(leader))) return false;
        WarInfo war = pendingWarForTarget(target);
        if (war == null) return false;
        List<GuildMember> a = guildMembers(war.guildA());
        List<GuildMember> b = guildMembers(war.guildB());
        if (a.isEmpty() || b.isEmpty() || Math.abs(a.size() - b.size()) > maxCountDiff) return false;
        double avgA = a.stream().mapToInt(GuildMember::rank).average().orElse(0d);
        double avgB = b.stream().mapToInt(GuildMember::rank).average().orElse(0d);
        if (Math.abs(avgA - avgB) > maxRankDiff) return false;
        return tx(() -> {
            update("UPDATE guild_wars SET state='ACTIVE',started_at=?,treasury_a_locked=?,treasury_b_locked=? WHERE id=? AND state='PENDING'",
                    now(), guildTreasury(war.guildA()), guildTreasury(war.guildB()), war.id());
            for (GuildMember m : a) update("INSERT INTO guild_war_roster(war_id,uuid,guild_id,rank) VALUES(?,?,?,?)", war.id(), m.uuid().toString(), war.guildA(), m.rank());
            for (GuildMember m : b) update("INSERT INTO guild_war_roster(war_id,uuid,guild_id,rank) VALUES(?,?,?,?)", war.id(), m.uuid().toString(), war.guildB(), m.rank());
            return true;
        });
    }

    public synchronized Long activeWarIdForPlayer(UUID uuid) {
        return longNullable("SELECT r.war_id FROM guild_war_roster r JOIN guild_wars w ON w.id=r.war_id WHERE r.uuid=? AND w.state='ACTIVE' ORDER BY w.id DESC LIMIT 1", uuid.toString());
    }

    public synchronized boolean recordWarKill(UUID killer, UUID victim) {
        Long warId = activeWarIdForPlayer(killer);
        if (warId == null || !warId.equals(activeWarIdForPlayer(victim))) return false;
        Long killerGuild = longNullable("SELECT guild_id FROM guild_war_roster WHERE war_id=? AND uuid=?", warId, killer.toString());
        Long victimGuild = longNullable("SELECT guild_id FROM guild_war_roster WHERE war_id=? AND uuid=?", warId, victim.toString());
        WarInfo war = warById(warId);
        if (killerGuild == null || victimGuild == null || killerGuild.equals(victimGuild) || war == null) return false;
        String column = killerGuild == war.guildA() ? "score_a" : "score_b";
        update("UPDATE guild_wars SET " + column + "=" + column + "+1 WHERE id=?", warId);
        return true;
    }

    public synchronized boolean cancelWar(long warId, UUID actor) {
        WarInfo war = warById(warId);
        if (war == null || !("PENDING".equals(war.state()) || "ACTIVE".equals(war.state()))) return false;
        update("UPDATE guild_wars SET state='CANCELLED',ended_at=? WHERE id=?", now(), warId);
        audit(actor, "war_cancel", String.valueOf(warId));
        return true;
    }

    public synchronized boolean finishWar(long warId, long winnerGuild, double fraction, long cooldownSeconds) {
        WarInfo war = warById(warId);
        if (war == null || !"ACTIVE".equals(war.state()) || (winnerGuild != war.guildA() && winnerGuild != war.guildB())) return false;
        long loser = winnerGuild == war.guildA() ? war.guildB() : war.guildA();
        int locked = winnerGuild == war.guildA()
                ? intQuery("SELECT treasury_b_locked FROM guild_wars WHERE id=?", 0, warId)
                : intQuery("SELECT treasury_a_locked FROM guild_wars WHERE id=?", 0, warId);
        int capture = Math.min(guildTreasury(loser), Math.max(0, (int) Math.floor(locked * Math.min(0.10d, Math.max(0d, fraction)))));
        return tx(() -> {
            update("UPDATE guilds SET treasury=treasury-? WHERE id=?", capture, loser);
            update("UPDATE guilds SET treasury=treasury+? WHERE id=?", capture, winnerGuild);
            update("UPDATE guild_wars SET state='FINISHED',ended_at=?,winner_guild=?,captured=? WHERE id=?", now(), winnerGuild, capture, warId);
            update("INSERT INTO guild_war_cooldowns(loser_guild,until_epoch) VALUES(?,?) ON CONFLICT(loser_guild) DO UPDATE SET until_epoch=excluded.until_epoch", loser, now() + cooldownSeconds);
            return true;
        });
    }

    public record Claim(long id, UUID owner, String world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ, String kind, long lastActive) {}

    public synchronized Claim claimAt(String world, int x, int y, int z) {
        return claimQuery("SELECT id,owner_uuid,world,min_x,max_x,min_y,max_y,min_z,max_z,kind,last_active FROM claims WHERE world=? AND ? BETWEEN min_x AND max_x AND ? BETWEEN min_y AND max_y AND ? BETWEEN min_z AND max_z ORDER BY id LIMIT 1", world, x, y, z);
    }

    public synchronized Claim claimById(long id) {
        return claimQuery("SELECT id,owner_uuid,world,min_x,max_x,min_y,max_y,min_z,max_z,kind,last_active FROM claims WHERE id=?", id);
    }

    public synchronized boolean ownsClaimAt(UUID uuid, String world, int x, int y, int z, String kind) {
        Claim c = claimAt(world, x, y, z);
        return c != null && c.owner().equals(uuid) && (kind == null || kind.equals(c.kind()));
    }

    public synchronized int claimCount(UUID uuid, String kind) {
        if (kind == null) return intQuery("SELECT COUNT(*) FROM claims WHERE owner_uuid=?", 0, uuid.toString());
        return intQuery("SELECT COUNT(*) FROM claims WHERE owner_uuid=? AND kind=?", 0, uuid.toString(), kind);
    }

    public synchronized boolean createClaim(UUID owner, String world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ, String kind, int cost) {
        if (minX > maxX || minY > maxY || minZ > maxZ || coins(owner) < cost || overlaps(0, world, minX, maxX, minY, maxY, minZ, maxZ)) return false;
        return tx(() -> {
            if (executeUpdate("UPDATE profiles SET coins=coins-? WHERE uuid=? AND coins>=?", cost, owner.toString(), cost) != 1) return false;
            update("INSERT INTO claims(owner_uuid,world,min_x,max_x,min_y,max_y,min_z,max_z,kind,last_active) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    owner.toString(), world, minX, maxX, minY, maxY, minZ, maxZ, kind, now());
            return true;
        });
    }

    public synchronized boolean expandClaim(UUID owner, long claimId, int west, int east, int north, int south, int down, int up, int cost, long maxSurface) {
        Claim c = claimById(claimId);
        if (c == null || !c.owner().equals(owner)) return false;
        int minX = c.minX() - Math.max(0, west), maxX = c.maxX() + Math.max(0, east);
        int minY = c.minY() - Math.max(0, down), maxY = c.maxY() + Math.max(0, up);
        int minZ = c.minZ() - Math.max(0, north), maxZ = c.maxZ() + Math.max(0, south);
        if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > maxSurface) return false;
        if (coins(owner) < cost || overlaps(claimId, c.world(), minX, maxX, minY, maxY, minZ, maxZ)) return false;
        return tx(() -> {
            if (executeUpdate("UPDATE profiles SET coins=coins-? WHERE uuid=? AND coins>=?", cost, owner.toString(), cost) != 1) return false;
            update("UPDATE claims SET min_x=?,max_x=?,min_y=?,max_y=?,min_z=?,max_z=?,last_active=? WHERE id=?", minX, maxX, minY, maxY, minZ, maxZ, now(), claimId);
            return true;
        });
    }

    public synchronized boolean offerClaimSale(UUID seller, long claimId, UUID buyer, int price) {
        Claim c = claimById(claimId);
        if (c == null || !c.owner().equals(seller) || seller.equals(buyer) || price <= 0) return false;
        update("INSERT INTO claim_sales(claim_id,seller_uuid,buyer_uuid,price,expires_at) VALUES(?,?,?,?,?) ON CONFLICT(claim_id) DO UPDATE SET seller_uuid=excluded.seller_uuid,buyer_uuid=excluded.buyer_uuid,price=excluded.price,expires_at=excluded.expires_at",
                claimId, seller.toString(), buyer.toString(), price, now() + 86400);
        return true;
    }

    public synchronized boolean acceptClaimSale(UUID buyer, long claimId, double feeFraction) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT seller_uuid,price,expires_at FROM claim_sales WHERE claim_id=? AND buyer_uuid=?")) {
            ps.setLong(1, claimId);
            ps.setString(2, buyer.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getLong(3) < now()) return false;
                UUID seller = UUID.fromString(rs.getString(1));
                int price = rs.getInt(2);
                if (coins(buyer) < price) return false;
                int fee = (int) Math.floor(price * Math.max(0d, feeFraction));
                return tx(() -> {
                    if (executeUpdate("UPDATE profiles SET coins=coins-? WHERE uuid=? AND coins>=?", price, buyer.toString(), price) != 1) return false;
                    update("UPDATE profiles SET coins=coins+? WHERE uuid=?", Math.max(0, price - fee), seller.toString());
                    if (executeUpdate("UPDATE claims SET owner_uuid=?,last_active=? WHERE id=? AND owner_uuid=?", buyer.toString(), now(), claimId, seller.toString()) != 1) throw new SQLException("claim owner changed");
                    update("DELETE FROM claim_sales WHERE claim_id=?", claimId);
                    return true;
                });
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public record ClaimRisk(long claimId, String kind, long secondsLeft) {}
    public synchronized List<ClaimRisk> claimsAtRisk(UUID owner, long normalSeconds, long vipSeconds, long warningSeconds) {
        List<ClaimRisk> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT id,kind,last_active FROM claims WHERE owner_uuid=? AND kind IN ('NORMAL','VIP')")) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long ttl = "VIP".equals(rs.getString(2)) ? vipSeconds : normalSeconds;
                    long left = ttl - (now() - rs.getLong(3));
                    if (left > 0 && left <= warningSeconds) result.add(new ClaimRisk(rs.getLong(1), rs.getString(2), left));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public synchronized int releaseExpiredClaims(long normalSeconds, long vipSeconds) {
        List<Long> expired = new ArrayList<>();
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("SELECT id,kind,last_active FROM claims WHERE kind IN ('NORMAL','VIP')")) {
            while (rs.next()) {
                long ttl = "VIP".equals(rs.getString(2)) ? vipSeconds : normalSeconds;
                if (now() - rs.getLong(3) >= ttl) expired.add(rs.getLong(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        for (long id : expired) update("DELETE FROM claims WHERE id=?", id);
        return expired.size();
    }

    private boolean overlaps(long excludeId, String world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM claims WHERE id<>? AND world=? AND NOT(max_x<? OR min_x>? OR max_y<? OR min_y>? OR max_z<? OR min_z>?) LIMIT 1")) {
            ps.setLong(1, excludeId); ps.setString(2, world); ps.setInt(3, minX); ps.setInt(4, maxX); ps.setInt(5, minY); ps.setInt(6, maxY); ps.setInt(7, minZ); ps.setInt(8, maxZ);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public synchronized void setCreativeSnapshot(UUID uuid, String encoded) { update("UPDATE profiles SET creative_snapshot=?,creative_active=1 WHERE uuid=?", encoded, uuid.toString()); }
    public synchronized String creativeSnapshot(UUID uuid) { return stringQuery("SELECT creative_snapshot FROM profiles WHERE uuid=? AND creative_active=1", uuid.toString()); }
    public synchronized boolean creativeActive(UUID uuid) { return intQuery("SELECT creative_active FROM profiles WHERE uuid=?", 0, uuid.toString()) > 0; }
    public synchronized void clearCreativeSnapshot(UUID uuid) { update("UPDATE profiles SET creative_snapshot=NULL,creative_active=0 WHERE uuid=?", uuid.toString()); }

    public synchronized void protectContainer(String world, int x, int y, int z, UUID owner, long until) {
        update("INSERT OR REPLACE INTO container_protection(world,x,y,z,owner_uuid,expires_at) VALUES(?,?,?,?,?,?)", world, x, y, z, owner.toString(), until);
    }

    public record ContainerLock(UUID owner, long expiresAt) {}
    public synchronized ContainerLock containerLock(String world, int x, int y, int z) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT owner_uuid,expires_at FROM container_protection WHERE world=? AND x=? AND y=? AND z=?")) {
            ps.setString(1, world); ps.setInt(2, x); ps.setInt(3, y); ps.setInt(4, z);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long expires = rs.getLong(2);
                if (expires < now()) {
                    update("DELETE FROM container_protection WHERE world=? AND x=? AND y=? AND z=?", world, x, y, z);
                    return null;
                }
                return new ContainerLock(UUID.fromString(rs.getString(1)), expires);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
    public synchronized void removeContainerLock(String world, int x, int y, int z) { update("DELETE FROM container_protection WHERE world=? AND x=? AND y=? AND z=?", world, x, y, z); }

    public synchronized void addCreativeBlock(String world, int x, int y, int z, UUID owner) { update("INSERT OR REPLACE INTO creative_blocks(world,x,y,z,owner_uuid) VALUES(?,?,?,?,?)", world, x, y, z, owner.toString()); }
    public synchronized boolean removeCreativeBlock(String world, int x, int y, int z) { return executeUpdate("DELETE FROM creative_blocks WHERE world=? AND x=? AND y=? AND z=?", world, x, y, z) > 0; }

    public record SessionSnapshot(String context, String inventory, String world, double x, double y, double z, float yaw, float pitch, String gameMode, int level, float exp) {}
    public synchronized void saveSession(UUID uuid, SessionSnapshot s) {
        update("INSERT INTO player_sessions(uuid,context,inventory,world,x,y,z,yaw,pitch,gamemode,level,exp,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET context=excluded.context,inventory=excluded.inventory,world=excluded.world,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch,gamemode=excluded.gamemode,level=excluded.level,exp=excluded.exp,created_at=excluded.created_at",
                uuid.toString(), s.context(), s.inventory(), s.world(), s.x(), s.y(), s.z(), s.yaw(), s.pitch(), s.gameMode(), s.level(), s.exp(), now());
    }
    public synchronized SessionSnapshot session(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT context,inventory,world,x,y,z,yaw,pitch,gamemode,level,exp FROM player_sessions WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new SessionSnapshot(rs.getString(1), rs.getString(2), rs.getString(3), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6), rs.getFloat(7), rs.getFloat(8), rs.getString(9), rs.getInt(10), rs.getFloat(11));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
    public synchronized void clearSession(UUID uuid) { update("DELETE FROM player_sessions WHERE uuid=?", uuid.toString()); }

    public record DungeonRun(long id, String instanceKey, int rank, long seed, int floors, int currentFloor, String state, UUID leader) {}
    public synchronized long createDungeonRun(UUID leader, int rank, long seed, int floors, List<UUID> members) {
        final long[] runId = {-1L};
        boolean ok = tx(() -> {
            String key = "dungeon:" + now() + ":" + leader + ":" + Math.abs(seed);
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO dungeon_runs(instance_key,rank,seed,floors,state,leader_uuid,started_at) VALUES(?,?,?,?,'ACTIVE',?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, key); ps.setInt(2, rank); ps.setLong(3, seed); ps.setInt(4, floors); ps.setString(5, leader.toString()); ps.setLong(6, now()); ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) { if (!rs.next()) return false; runId[0] = rs.getLong(1); }
            }
            for (UUID member : members) update("INSERT INTO dungeon_members(run_id,uuid,entry_rank) VALUES(?,?,?)", runId[0], member.toString(), rank(member));
            return true;
        });
        return ok ? runId[0] : -1L;
    }
    public synchronized DungeonRun dungeonRun(long id) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT id,instance_key,rank,seed,floors,current_floor,state,leader_uuid FROM dungeon_runs WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new DungeonRun(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4), rs.getInt(5), rs.getInt(6), rs.getString(7), UUID.fromString(rs.getString(8)));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
    public synchronized Long activeDungeonRun(UUID uuid) { return longNullable("SELECT dm.run_id FROM dungeon_members dm JOIN dungeon_runs dr ON dr.id=dm.run_id WHERE dm.uuid=? AND dr.state='ACTIVE' ORDER BY dr.id DESC LIMIT 1", uuid.toString()); }
    public synchronized List<UUID> dungeonMembers(long runId) {
        List<UUID> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT uuid FROM dungeon_members WHERE run_id=?")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(UUID.fromString(rs.getString(1))); }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return result;
    }
    public synchronized void markDungeonMemberOut(long runId, UUID uuid) { update("UPDATE dungeon_members SET alive=0 WHERE run_id=? AND uuid=?", runId, uuid.toString()); }
    public synchronized int dungeonAliveCount(long runId) { return intQuery("SELECT COUNT(*) FROM dungeon_members WHERE run_id=? AND alive=1", 0, runId); }
    public synchronized void setDungeonFloor(long runId, int floor) { update("UPDATE dungeon_runs SET current_floor=? WHERE id=? AND state='ACTIVE'", floor, runId); }
    public synchronized boolean failDungeon(long runId, long cooldownSeconds) {
        DungeonRun run = dungeonRun(runId);
        if (run == null || !"ACTIVE".equals(run.state())) return false;
        update("UPDATE dungeon_runs SET state='FAILED',ended_at=? WHERE id=?", now(), runId);
        for (UUID uuid : dungeonMembers(runId)) setCooldown(uuid, "dungeon", now() + cooldownSeconds);
        return true;
    }
    public synchronized boolean finishDungeon(long runId, int totalReward, long cooldownSeconds) {
        DungeonRun run = dungeonRun(runId);
        if (run == null || !"ACTIVE".equals(run.state())) return false;
        List<UUID> members = dungeonMembers(runId);
        if (members.isEmpty()) return false;
        int each = Math.max(0, totalReward / members.size());
        return tx(() -> {
            update("UPDATE dungeon_runs SET state='SUCCESS',ended_at=? WHERE id=?", now(), runId);
            for (UUID uuid : members) {
                update("UPDATE profiles SET coins=coins+?,dungeons=dungeons+1 WHERE uuid=?", each, uuid.toString());
                update("UPDATE dungeon_members SET rewarded=1 WHERE run_id=? AND uuid=?", runId, uuid.toString());
                setCooldown(uuid, "dungeon", now() + cooldownSeconds);
            }
            return true;
        });
    }

    public synchronized long cooldown(UUID uuid, String kind) { return longQuery("SELECT until_epoch FROM cooldowns WHERE uuid=? AND kind=?", 0L, uuid.toString(), kind); }
    public synchronized void setCooldown(UUID uuid, String kind, long until) { update("INSERT INTO cooldowns(uuid,kind,until_epoch) VALUES(?,?,?) ON CONFLICT(uuid,kind) DO UPDATE SET until_epoch=excluded.until_epoch", uuid.toString(), kind, until); }

    public synchronized void audit(UUID actor, String action, String details) {
        update("INSERT INTO audit(ts,actor_uuid,action,details) VALUES(?,?,?,?)", now(), actor == null ? null : actor.toString(), action, details == null ? "" : details);
    }

    private WarInfo warQuery(String sql, Object... args) {
        try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            return new WarInfo(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getLong(7));
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private Claim claimQuery(String sql, Object... args) {
        try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            return new Claim(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getString(10), rs.getLong(11));
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private int intQuery(String sql, int fallback, Object... args) {
        try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : fallback; }
        catch (SQLException e) { throw new RuntimeException(e); }
    }
    private long longQuery(String sql, long fallback, Object... args) {
        try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : fallback; }
        catch (SQLException e) { throw new RuntimeException(e); }
    }
    private Long longNullable(String sql, Object... args) {
        try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : null; }
        catch (SQLException e) { throw new RuntimeException(e); }
    }
    private String stringQuery(String sql, Object... args) {
        try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        catch (SQLException e) { throw new RuntimeException(e); }
    }
    private PreparedStatement prepare(String sql, Object... args) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
        return ps;
    }
    private int executeUpdate(String sql, Object... args) {
        try (PreparedStatement ps = prepare(sql, args)) { return ps.executeUpdate(); }
        catch (SQLException e) { throw new RuntimeException(e); }
    }
    private void update(String sql, Object... args) { executeUpdate(sql, args); }

    private interface TransactionBody { boolean run() throws Exception; }
    private boolean tx(TransactionBody body) {
        try {
            connection.setAutoCommit(false);
            boolean ok = body.run();
            if (ok) connection.commit(); else connection.rollback();
            connection.setAutoCommit(true);
            return ok;
        } catch (Exception e) {
            try { connection.rollback(); } catch (SQLException ignored) {}
            try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
            return false;
        }
    }

    private static long now() { return Instant.now().getEpochSecond(); }

    @Override
    public synchronized void close() {
        try { connection.close(); } catch (SQLException ignored) {}
    }
}
