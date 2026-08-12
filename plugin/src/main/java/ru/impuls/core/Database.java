package ru.impuls.core;

import org.bukkit.entity.Player;

import java.io.File;
import java.sql.*;
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
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        migrate();
    }

    private void migrate() throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS profiles(uuid TEXT PRIMARY KEY,name TEXT NOT NULL,coins INTEGER NOT NULL DEFAULT 100,rank INTEGER NOT NULL DEFAULT 0,xp INTEGER NOT NULL DEFAULT 0,quests INTEGER NOT NULL DEFAULT 0,dungeons INTEGER NOT NULL DEFAULT 0,insured INTEGER NOT NULL DEFAULT 0,defender INTEGER NOT NULL DEFAULT 0,last_seen INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS guilds(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,owner_uuid TEXT NOT NULL,level INTEGER NOT NULL DEFAULT 1,treasury INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS guild_members(guild_id INTEGER NOT NULL,uuid TEXT NOT NULL UNIQUE,role TEXT NOT NULL,joined_at INTEGER NOT NULL,PRIMARY KEY(guild_id,uuid),FOREIGN KEY(guild_id) REFERENCES guilds(id) ON DELETE CASCADE)");
            s.execute("CREATE TABLE IF NOT EXISTS guild_invites(guild_id INTEGER NOT NULL,uuid TEXT NOT NULL,expires_at INTEGER NOT NULL,PRIMARY KEY(guild_id,uuid))");
            s.execute("CREATE TABLE IF NOT EXISTS claims(id INTEGER PRIMARY KEY AUTOINCREMENT,owner_uuid TEXT NOT NULL,world TEXT NOT NULL,min_x INTEGER NOT NULL,max_x INTEGER NOT NULL,min_y INTEGER NOT NULL,max_y INTEGER NOT NULL,min_z INTEGER NOT NULL,max_z INTEGER NOT NULL,kind TEXT NOT NULL,last_active INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS creative_blocks(world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,owner_uuid TEXT NOT NULL,PRIMARY KEY(world,x,y,z))");
            s.execute("CREATE TABLE IF NOT EXISTS cooldowns(uuid TEXT NOT NULL,kind TEXT NOT NULL,until_epoch INTEGER NOT NULL,PRIMARY KEY(uuid,kind))");
            s.execute("CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT,ts INTEGER NOT NULL,actor_uuid TEXT,action TEXT NOT NULL,details TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS transactions(idempotency_key TEXT PRIMARY KEY,uuid TEXT NOT NULL,amount INTEGER NOT NULL,reason TEXT NOT NULL,created_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS claim_sales(claim_id INTEGER PRIMARY KEY,seller_uuid TEXT NOT NULL,buyer_uuid TEXT NOT NULL,price INTEGER NOT NULL,created_at INTEGER NOT NULL,expires_at INTEGER NOT NULL,FOREIGN KEY(claim_id) REFERENCES claims(id) ON DELETE CASCADE)");
            s.execute("CREATE TABLE IF NOT EXISTS container_protection(world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,owner_uuid TEXT NOT NULL,expires_at INTEGER NOT NULL,PRIMARY KEY(world,x,y,z))");
            s.execute("CREATE TABLE IF NOT EXISTS guild_wars(id INTEGER PRIMARY KEY AUTOINCREMENT,guild_a INTEGER NOT NULL,guild_b INTEGER NOT NULL,state TEXT NOT NULL,created_at INTEGER NOT NULL,started_at INTEGER,ended_at INTEGER,treasury_a_locked INTEGER NOT NULL DEFAULT 0,treasury_b_locked INTEGER NOT NULL DEFAULT 0,score_a INTEGER NOT NULL DEFAULT 0,score_b INTEGER NOT NULL DEFAULT 0,winner_guild INTEGER,captured INTEGER NOT NULL DEFAULT 0,FOREIGN KEY(guild_a) REFERENCES guilds(id),FOREIGN KEY(guild_b) REFERENCES guilds(id))");
            s.execute("CREATE TABLE IF NOT EXISTS guild_war_roster(war_id INTEGER NOT NULL,uuid TEXT NOT NULL,guild_id INTEGER NOT NULL,rank INTEGER NOT NULL,PRIMARY KEY(war_id,uuid),FOREIGN KEY(war_id) REFERENCES guild_wars(id) ON DELETE CASCADE)");
            s.execute("CREATE TABLE IF NOT EXISTS guild_war_cooldowns(loser_guild INTEGER PRIMARY KEY,until_epoch INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS dungeon_runs(id INTEGER PRIMARY KEY AUTOINCREMENT,instance_key TEXT NOT NULL UNIQUE,rank INTEGER NOT NULL,seed INTEGER NOT NULL,floors INTEGER NOT NULL,current_floor INTEGER NOT NULL DEFAULT 1,state TEXT NOT NULL,leader_uuid TEXT NOT NULL,started_at INTEGER NOT NULL,ended_at INTEGER,reward_key TEXT UNIQUE)");
            s.execute("CREATE TABLE IF NOT EXISTS dungeon_members(run_id INTEGER NOT NULL,uuid TEXT NOT NULL,entry_rank INTEGER NOT NULL,alive INTEGER NOT NULL DEFAULT 1,rewarded INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(run_id,uuid),FOREIGN KEY(run_id) REFERENCES dungeon_runs(id) ON DELETE CASCADE)");
            s.execute("CREATE TABLE IF NOT EXISTS player_sessions(uuid TEXT PRIMARY KEY,context TEXT NOT NULL,inventory TEXT NOT NULL,world TEXT NOT NULL,x REAL NOT NULL,y REAL NOT NULL,z REAL NOT NULL,yaw REAL NOT NULL,pitch REAL NOT NULL,gamemode TEXT NOT NULL,level INTEGER NOT NULL,exp REAL NOT NULL,created_at INTEGER NOT NULL)");
        }
        ensureColumn("profiles", "creative_snapshot", "TEXT");
        ensureColumn("profiles", "creative_active", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn("profiles", "violations", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn("guilds", "emblem", "TEXT");
    }

    private void ensureColumn(String table, String column, String ddl) throws SQLException {
        boolean exists = false;
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) if (column.equalsIgnoreCase(rs.getString("name"))) { exists = true; break; }
        }
        if (!exists) try (Statement s = connection.createStatement()) { s.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + ddl); }
    }

    public synchronized void ensure(Player player) {
        update("INSERT INTO profiles(uuid,name,last_seen) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,last_seen=excluded.last_seen", player.getUniqueId().toString(), player.getName(), now());
        touchClaims(player.getUniqueId());
    }

    public synchronized int coins(UUID uuid) { return intQuery("SELECT coins FROM profiles WHERE uuid=?", uuid.toString(), 0); }
    public synchronized int rank(UUID uuid) { return intQuery("SELECT rank FROM profiles WHERE uuid=?", uuid.toString(), 0); }
    public synchronized int defender(UUID uuid) { return intQuery("SELECT defender FROM profiles WHERE uuid=?", uuid.toString(), 0); }
    public synchronized boolean insured(UUID uuid) { return intQuery("SELECT insured FROM profiles WHERE uuid=?", uuid.toString(), 0) > 0; }
    public synchronized void setInsured(UUID uuid, boolean value) { update("UPDATE profiles SET insured=? WHERE uuid=?", value ? 1 : 0, uuid.toString()); }
    public synchronized void addDefender(UUID uuid, int amount) { update("UPDATE profiles SET defender=defender+? WHERE uuid=?", amount, uuid.toString()); }
    public synchronized void addDungeonCompletion(UUID uuid) { update("UPDATE profiles SET dungeons=dungeons+1 WHERE uuid=?", uuid.toString()); }

    public synchronized boolean charge(UUID uuid, int amount, String reason) {
        if (amount <= 0) return false;
        return transaction(() -> {
            if (coins(uuid) < amount) return false;
            update("UPDATE profiles SET coins=coins-? WHERE uuid=?", amount, uuid.toString());
            audit(uuid, "charge", reason + ":" + amount);
            return true;
        }, false);
    }

    public synchronized void credit(UUID uuid, int amount, String reason) {
        if (amount <= 0) return;
        update("UPDATE profiles SET coins=coins+? WHERE uuid=?", amount, uuid.toString());
        audit(uuid, "credit", reason + ":" + amount);
    }

    public synchronized boolean creditOnce(String key, UUID uuid, int amount, String reason) {
        if (key == null || key.isBlank() || amount <= 0) return false;
        return transaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO transactions(idempotency_key,uuid,amount,reason,created_at) VALUES(?,?,?,?,?)")) {
                ps.setString(1, key); ps.setString(2, uuid.toString()); ps.setInt(3, amount); ps.setString(4, reason); ps.setLong(5, now()); ps.executeUpdate();
            } catch (SQLException duplicate) { return false; }
            update("UPDATE profiles SET coins=coins+? WHERE uuid=?", amount, uuid.toString());
            audit(uuid, "credit_once", key + ":" + reason + ":" + amount);
            return true;
        }, false);
    }

    public synchronized Long guildId(UUID uuid) { return longQueryNullable("SELECT guild_id FROM guild_members WHERE uuid=?", uuid.toString()); }
    public synchronized Long guildIdByName(String name) { return longQueryNullable("SELECT id FROM guilds WHERE lower(name)=lower(?)", name); }
    public synchronized String guildName(long id) { return stringQuery("SELECT name FROM guilds WHERE id=?", id); }
    public synchronized String memberRole(UUID uuid) { return stringQuery("SELECT role FROM guild_members WHERE uuid=?", uuid.toString()); }
    public synchronized int guildTreasury(long guildId) { return intQuery("SELECT treasury FROM guilds WHERE id=?", guildId, 0); }

    public synchronized boolean createGuild(UUID owner, String name, int cost) {
        if (guildId(owner) != null || name == null || name.isBlank() || cost < 0) return false;
        return transaction(() -> {
            if (coins(owner) < cost) return false;
            long guildId;
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO guilds(name,owner_uuid,created_at) VALUES(?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name); ps.setString(2, owner.toString()); ps.setLong(3, now()); ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) { if (!rs.next()) return false; guildId = rs.getLong(1); }
            }
            update("INSERT INTO guild_members(guild_id,uuid,role,joined_at) VALUES(?,?,?,?)", guildId, owner.toString(), "LEADER", now());
            update("UPDATE profiles SET coins=coins-? WHERE uuid=?", cost, owner.toString());
            audit(owner, "guild_create", name);
            return true;
        }, false);
    }

    public synchronized boolean invite(UUID actor, UUID target) {
        Long gid = guildId(actor);
        if (gid == null || guildId(target) != null) return false;
        String role = memberRole(actor);
        if (!"LEADER".equals(role) && !"DEPUTY".equals(role)) return false;
        update("INSERT INTO guild_invites(guild_id,uuid,expires_at) VALUES(?,?,?) ON CONFLICT(guild_id,uuid) DO UPDATE SET expires_at=excluded.expires_at", gid, target.toString(), now() + 86400);
        audit(actor, "guild_invite", target.toString());
        return true;
    }

    public synchronized boolean acceptInvite(UUID uuid) {
        if (guildId(uuid) != null) return false;
        try (PreparedStatement ps = connection.prepareStatement("SELECT guild_id FROM guild_invites WHERE uuid=? AND expires_at>? ORDER BY expires_at DESC LIMIT 1")) {
            ps.setString(1, uuid.toString()); ps.setLong(2, now());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                long gid = rs.getLong(1);
                update("INSERT INTO guild_members(guild_id,uuid,role,joined_at) VALUES(?,?,?,?)", gid, uuid.toString(), "MEMBER", now());
                update("DELETE FROM guild_invites WHERE uuid=?", uuid.toString());
                audit(uuid, "guild_join", String.valueOf(gid));
                return true;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public synchronized boolean leaveGuild(UUID uuid) {
        Long gid = guildId(uuid);
        if (gid == null || "LEADER".equals(memberRole(uuid)) || activeWarForGuild(gid) != null) return false;
        update("DELETE FROM guild_members WHERE uuid=?", uuid.toString());
        audit(uuid, "guild_leave", String.valueOf(gid));
        return true;
    }

    public synchronized boolean setGuildRole(UUID leader, UUID target, String role) {
        Long gid = guildId(leader);
        if (gid == null || !"LEADER".equals(memberRole(leader)) || !gid.equals(guildId(target))) return false;
        if (!"DEPUTY".equals(role) && !"MEMBER".equals(role)) return false;
        update("UPDATE guild_members SET role=? WHERE uuid=? AND guild_id=?", role, target.toString(), gid);
        audit(leader, "guild_role", target + ":" + role);
        return true;
    }

    public synchronized boolean transferGuildLeadership(UUID leader, UUID target) {
        Long gid = guildId(leader);
        if (gid == null || !"LEADER".equals(memberRole(leader)) || !gid.equals(guildId(target)) || activeWarForGuild(gid) != null) return false;
        return transaction(() -> {
            update("UPDATE guild_members SET role='DEPUTY' WHERE uuid=?", leader.toString());
            update("UPDATE guild_members SET role='LEADER' WHERE uuid=?", target.toString());
            update("UPDATE guilds SET owner_uuid=? WHERE id=?", target.toString(), gid);
            audit(leader, "guild_transfer", target.toString());
            return true;
        }, false);
    }

    public synchronized boolean depositGuild(UUID uuid, int amount) {
        Long gid = guildId(uuid);
        if (gid == null || amount <= 0) return false;
        return transaction(() -> {
            if (coins(uuid) < amount) return false;
            update("UPDATE profiles SET coins=coins-? WHERE uuid=?", amount, uuid.toString());
            update("UPDATE guilds SET treasury=treasury+? WHERE id=?", amount, gid);
            audit(uuid, "guild_deposit", gid + ":" + amount);
            return true;
        }, false);
    }

    public record GuildMember(UUID uuid, int rank, String role) {}
    public synchronized List<GuildMember> guildMembers(long gid) {
        List<GuildMember> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT gm.uuid,p.rank,gm.role FROM guild_members gm JOIN profiles p ON p.uuid=gm.uuid WHERE gm.guild_id=? ORDER BY gm.joined_at")) {
            ps.setLong(1, gid);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(new GuildMember(UUID.fromString(rs.getString(1)), rs.getInt(2), rs.getString(3))); }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public record GuildStats(int members, double averageRank) {}
    public synchronized GuildStats guildStats(long gid) {
        List<GuildMember> members = guildMembers(gid);
        double avg = members.stream().mapToInt(GuildMember::rank).average().orElse(0d);
        return new GuildStats(members.size(), avg);
    }

    public record WarInfo(long id, long guildA, long guildB, String state, int scoreA, int scoreB, long startedAt) {}
    public synchronized WarInfo activeWarForGuild(long gid) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT id,guild_a,guild_b,state,score_a,score_b,COALESCE(started_at,0) FROM guild_wars WHERE (guild_a=? OR guild_b=?) AND state IN ('PENDING','ACTIVE') ORDER BY id DESC LIMIT 1")) {
            ps.setLong(1, gid); ps.setLong(2, gid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new WarInfo(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getLong(7));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public synchronized boolean challengeWar(UUID leader, String targetGuildName) {
        Long a = guildId(leader); Long b = guildIdByName(targetGuildName);
        if (a == null || b == null || a.equals(b) || !"LEADER".equals(memberRole(leader))) return false;
        if (activeWarForGuild(a) != null || activeWarForGuild(b) != null) return false;
        long cooldown = longQuery("SELECT until_epoch FROM guild_war_cooldowns WHERE loser_guild=?", b, 0L);
        if (cooldown > now()) return false;
        update("INSERT INTO guild_wars(guild_a,guild_b,state,created_at) VALUES(?,?,'PENDING',?)", a, b, now());
        audit(leader, "war_challenge", a + "->" + b);
        return true;
    }

    public synchronized WarInfo pendingWarForTarget(long gid) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT id,guild_a,guild_b,state,score_a,score_b,COALESCE(started_at,0) FROM guild_wars WHERE guild_b=? AND state='PENDING' ORDER BY id DESC LIMIT 1")) {
            ps.setLong(1, gid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new WarInfo(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getString(4),rs.getInt(5),rs.getInt(6),rs.getLong(7)) : null;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public synchronized boolean acceptWar(UUID leader, int maxCountDiff, double maxRankDiff) {
        Long target = guildId(leader);
        if (target == null || !"LEADER".equals(memberRole(leader))) return false;
        WarInfo war = pendingWarForTarget(target);
        if (war == null) return false;
        GuildStats aStats = guildStats(war.guildA()); GuildStats bStats = guildStats(war.guildB());
        if (aStats.members() == 0 || bStats.members() == 0 || Math.abs(aStats.members() - bStats.members()) > maxCountDiff || Math.abs(aStats.averageRank() - bStats.averageRank()) > maxRankDiff) return false;
        return transaction(() -> {
            int ta = guildTreasury(war.guildA()); int tb = guildTreasury(war.guildB());
            update("UPDATE guild_wars SET state='ACTIVE',started_at=?,treasury_a_locked=?,treasury_b_locked=? WHERE id=? AND state='PENDING'", now(), ta, tb, war.id());
            for (GuildMember m : guildMembers(war.guildA())) update("INSERT INTO guild_war_roster(war_id,uuid,guild_id,rank) VALUES(?,?,?,?)", war.id(), m.uuid().toString(), war.guildA(), m.rank());
            for (GuildMember m : guildMembers(war.guildB())) update("INSERT INTO guild_war_roster(war_id,uuid,guild_id,rank) VALUES(?,?,?,?)", war.id(), m.uuid().toString(), war.guildB(), m.rank());
            audit(leader, "war_accept", String.valueOf(war.id()));
            return true;
        }, false);
    }

    public synchronized Long activeWarIdForPlayer(UUID uuid) { return longQueryNullable("SELECT r.war_id FROM guild_war_roster r JOIN guild_wars w ON w.id=r.war_id WHERE r.uuid=? AND w.state='ACTIVE' ORDER BY w.id DESC LIMIT 1", uuid.toString()); }

    public synchronized boolean recordWarKill(UUID killer, UUID victim) {
        Long war = activeWarIdForPlayer(killer); Long victimWar = activeWarIdForPlayer(victim);
        if (war == null || !war.equals(victimWar)) return false;
        Long killerGuild = longQueryNullable("SELECT guild_id FROM guild_war_roster WHERE war_id=? AND uuid=?", war, killer.toString());
        Long victimGuild = longQueryNullable("SELECT guild_id FROM guild_war_roster WHERE war_id=? AND uuid=?", war, victim.toString());
        if (killerGuild == null || victimGuild == null || killerGuild.equals(victimGuild)) return false;
        WarInfo info = warById(war);
        if (info == null) return false;
        String column = killerGuild == info.guildA() ? "score_a" : "score_b";
        update("UPDATE guild_wars SET " + column + "=" + column + "+1 WHERE id=?", war);
        audit(killer, "war_kill", war + ":" + victim);
        return true;
    }

    public synchronized WarInfo warById(long id) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT id,guild_a,guild_b,state,score_a,score_b,COALESCE(started_at,0) FROM guild_wars WHERE id=?")) {
            ps.setLong(1,id); try(ResultSet rs=ps.executeQuery()) { return rs.next()?new WarInfo(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getString(4),rs.getInt(5),rs.getInt(6),rs.getLong(7)):null; }
        } catch(SQLException e){throw new RuntimeException(e);}
    }

    public synchronized boolean cancelWar(long warId, UUID actor) {
        WarInfo war=warById(warId);
        if(war==null || !("PENDING".equals(war.state())||"ACTIVE".equals(war.state()))) return false;
        update("UPDATE guild_wars SET state='CANCELLED',ended_at=? WHERE id=?",now(),warId);
        audit(actor,"war_cancel",String.valueOf(warId)); return true;
    }

    public synchronized boolean finishWar(long warId, long winnerGuild, double captureFraction, long loserCooldownSeconds) {
        WarInfo war = warById(warId);
        if (war == null || !"ACTIVE".equals(war.state()) || (winnerGuild != war.guildA() && winnerGuild != war.guildB())) return false;
        long loser = winnerGuild == war.guildA() ? war.guildB() : war.guildA();
        return transaction(() -> {
            int locked = intQuery("SELECT CASE WHEN guild_a=? THEN treasury_b_locked ELSE treasury_a_locked END FROM guild_wars WHERE id=?", new Object[]{winnerGuild, warId}, 0);
            int current = guildTreasury(loser);
            int capture = Math.max(0, Math.min(current, (int)Math.floor(locked * captureFraction)));
            update("UPDATE guilds SET treasury=treasury-? WHERE id=?", capture, loser);
            update("UPDATE guilds SET treasury=treasury+? WHERE id=?", capture, winnerGuild);
            update("UPDATE guild_wars SET state='FINISHED',ended_at=?,winner_guild=?,captured=? WHERE id=?", now(), winnerGuild, capture, warId);
            update("INSERT INTO guild_war_cooldowns(loser_guild,until_epoch) VALUES(?,?) ON CONFLICT(loser_guild) DO UPDATE SET until_epoch=excluded.until_epoch", loser, now()+loserCooldownSeconds);
            audit(null, "war_finish", warId + ":winner=" + winnerGuild + ":capture=" + capture);
            return true;
        }, false);
    }

    public record Claim(long id, UUID owner, String world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ, String kind, long lastActive) {
        public boolean contains(String w,int x,int y,int z){return world.equals(w)&&x>=minX&&x<=maxX&&y>=minY&&y<=maxY&&z>=minZ&&z<=maxZ;}
        public long surfaceArea(){return (long)(maxX-minX+1)*(maxZ-minZ+1);}
    }

    public synchronized Claim claimAt(String world,int x,int y,int z){ return claimQuery("SELECT id,owner_uuid,world,min_x,max_x,min_y,max_y,min_z,max_z,kind,last_active FROM claims WHERE world=? AND ? BETWEEN min_x AND max_x AND ? BETWEEN min_y AND max_y AND ? BETWEEN min_z AND max_z ORDER BY id LIMIT 1", world,x,y,z); }
    public synchronized Claim claimById(long id){return claimQuery("SELECT id,owner_uuid,world,min_x,max_x,min_y,max_y,min_z,max_z,kind,last_active FROM claims WHERE id=?", id);}
    public synchronized boolean ownsClaimAt(UUID u,String world,int x,int y,int z,String kind){Claim c=claimAt(world,x,y,z);return c!=null&&c.owner().equals(u)&&(kind==null||kind.equals(c.kind()));}
    public synchronized int claimCount(UUID u,String kind){return intQuery(kind==null?"SELECT COUNT(*) FROM claims WHERE owner_uuid=?":"SELECT COUNT(*) FROM claims WHERE owner_uuid=? AND kind=?", kind==null?new Object[]{u.toString()}:new Object[]{u.toString(),kind},0);}

    public synchronized boolean createClaim(UUID owner,String world,int minX,int maxX,int minY,int maxY,int minZ,int maxZ,String kind,int cost){
        if(minX>maxX||minY>maxY||minZ>maxZ||cost<0)return false;
        return transaction(()->{
            if(overlapsClaim(0,world,minX,maxX,minY,maxY,minZ,maxZ)||coins(owner)<cost)return false;
            update("INSERT INTO claims(owner_uuid,world,min_x,max_x,min_y,max_y,min_z,max_z,kind,last_active) VALUES(?,?,?,?,?,?,?,?,?,?)",owner.toString(),world,minX,maxX,minY,maxY,minZ,maxZ,kind,now());
            update("UPDATE profiles SET coins=coins-? WHERE uuid=?",cost,owner.toString()); audit(owner,"claim_buy",kind+":"+world+":"+minX+","+minZ+".."+maxX+","+maxZ+":"+cost);return true;
        },false);
    }

    public synchronized boolean expandClaim(UUID owner,long claimId,int west,int east,int north,int south,int down,int up,int cost,long maxSurface){
        Claim c=claimById(claimId); if(c==null||!c.owner().equals(owner)||cost<0)return false;
        int minX=c.minX()-Math.max(0,west),maxX=c.maxX()+Math.max(0,east),minZ=c.minZ()-Math.max(0,north),maxZ=c.maxZ()+Math.max(0,south),minY=c.minY()-Math.max(0,down),maxY=c.maxY()+Math.max(0,up);
        long area=(long)(maxX-minX+1)*(maxZ-minZ+1); if(area>maxSurface)return false;
        return transaction(()->{
            if(overlapsClaim(claimId,c.world(),minX,maxX,minY,maxY,minZ,maxZ)||coins(owner)<cost)return false;
            update("UPDATE claims SET min_x=?,max_x=?,min_y=?,max_y=?,min_z=?,max_z=?,last_active=? WHERE id=?",minX,maxX,minY,maxY,minZ,maxZ,now(),claimId);
            update("UPDATE profiles SET coins=coins-? WHERE uuid=?",cost,owner.toString()); audit(owner,"claim_expand",claimId+":"+cost); return true;
        },false);
    }

    public synchronized boolean offerClaimSale(UUID seller,long claimId,UUID buyer,int price){
        Claim c=claimById(claimId);if(c==null||!c.owner().equals(seller)||price<=0||seller.equals(buyer))return false;
        update("INSERT INTO claim_sales(claim_id,seller_uuid,buyer_uuid,price,created_at,expires_at) VALUES(?,?,?,?,?,?) ON CONFLICT(claim_id) DO UPDATE SET seller_uuid=excluded.seller_uuid,buyer_uuid=excluded.buyer_uuid,price=excluded.price,created_at=excluded.created_at,expires_at=excluded.expires_at",claimId,seller.toString(),buyer.toString(),price,now(),now()+86400);
        audit(seller,"claim_sale_offer",claimId+":"+buyer+":"+price);return true;
    }

    public synchronized boolean acceptClaimSale(UUID buyer,long claimId,double feeFraction){
        try(PreparedStatement ps=connection.prepareStatement("SELECT seller_uuid,price,expires_at FROM claim_sales WHERE claim_id=? AND buyer_uuid=?")){
            ps.setLong(1,claimId);ps.setString(2,buyer.toString());try(ResultSet rs=ps.executeQuery()){
                if(!rs.next()||rs.getLong(3)<now())return false;UUID seller=UUID.fromString(rs.getString(1));int price=rs.getInt(2);
                return transaction(()->{if(coins(buyer)<price)return false;int fee=(int)Math.floor(price*Math.max(0,feeFraction));int payout=Math.max(0,price-fee);update("UPDATE profiles SET coins=coins-? WHERE uuid=?",price,buyer.toString());update("UPDATE profiles SET coins=coins+? WHERE uuid=?",payout,seller.toString());update("UPDATE claims SET owner_uuid=?,last_active=? WHERE id=? AND owner_uuid=?",buyer.toString(),now(),claimId,seller.toString());update("DELETE FROM claim_sales WHERE claim_id=?",claimId);audit(buyer,"claim_sale_complete",claimId+":"+seller+":"+price+":fee="+fee);return true;},false);
            }}
        }catch(SQLException e){throw new RuntimeException(e);}
    }

    public record ClaimRisk(long claimId,String kind,long secondsLeft){}
    public synchronized List<ClaimRisk> claimsAtRisk(UUID owner,long normalSeconds,long vipSeconds,long warningSeconds){
        List<ClaimRisk> out=new ArrayList<>();long current=now();
        try(PreparedStatement ps=connection.prepareStatement("SELECT id,kind,last_active FROM claims WHERE owner_uuid=? AND kind IN ('NORMAL','VIP')")){
            ps.setString(1,owner.toString());try(ResultSet rs=ps.executeQuery()){while(rs.next()){String kind=rs.getString(2);long ttl="VIP".equals(kind)?vipSeconds:normalSeconds;long left=ttl-(current-rs.getLong(3));if(left>0&&left<=warningSeconds)out.add(new ClaimRisk(rs.getLong(1),kind,left));}}
        }catch(SQLException e){throw new RuntimeException(e);}return out;
    }

    public synchronized int releaseExpiredClaims(long normalSeconds,long vipSeconds){
        long current=now();int count=0;
        try(PreparedStatement ps=connection.prepareStatement("SELECT id,kind,last_active FROM claims WHERE kind IN ('NORMAL','VIP')");ResultSet rs=ps.executeQuery()){
            List<Long> ids=new ArrayList<>();while(rs.next()){String kind=rs.getString(2);long age=current-rs.getLong(3);if(("VIP".equals(kind)&&age>=vipSeconds)||("NORMAL".equals(kind)&&age>=normalSeconds))ids.add(rs.getLong(1));}
            for(long id:ids){update("DELETE FROM claims WHERE id=?",id);audit(null,"claim_expired",String.valueOf(id));count++;}
        }catch(SQLException e){throw new RuntimeException(e);}return count;
    }

    private boolean overlapsClaim(long excludeId,String world,int minX,int maxX,int minY,int maxY,int minZ,int maxZ){
        try(PreparedStatement ps=connection.prepareStatement("SELECT 1 FROM claims WHERE id<>? AND world=? AND NOT(max_x<? OR min_x>? OR max_y<? OR min_y>? OR max_z<? OR min_z>?) LIMIT 1")){
            ps.setLong(1,excludeId);ps.setString(2,world);ps.setInt(3,minX);ps.setInt(4,maxX);ps.setInt(5,minY);ps.setInt(6,maxY);ps.setInt(7,minZ);ps.setInt(8,maxZ);try(ResultSet rs=ps.executeQuery()){return rs.next();}
        }catch(SQLException e){throw new RuntimeException(e);}
    }
    private void touchClaims(UUID owner){update("UPDATE claims SET last_active=? WHERE owner_uuid=?",now(),owner.toString());}

    public synchronized void setCreativeSnapshot(UUID uuid,String encoded){update("UPDATE profiles SET creative_snapshot=?,creative_active=1 WHERE uuid=?",encoded,uuid.toString());}
    public synchronized String creativeSnapshot(UUID uuid){return stringQuery("SELECT creative_snapshot FROM profiles WHERE uuid=? AND creative_active=1",uuid.toString());}
    public synchronized boolean creativeActive(UUID uuid){return intQuery("SELECT creative_active FROM profiles WHERE uuid=?",uuid.toString(),0)>0;}
    public synchronized void clearCreativeSnapshot(UUID uuid){update("UPDATE profiles SET creative_snapshot=NULL,creative_active=0 WHERE uuid=?",uuid.toString());}

    public synchronized void protectContainer(String world,int x,int y,int z,UUID owner,long until){update("INSERT OR REPLACE INTO container_protection(world,x,y,z,owner_uuid,expires_at) VALUES(?,?,?,?,?,?)",world,x,y,z,owner.toString(),until);}
    public record ContainerLock(UUID owner,long expiresAt){}
    public synchronized ContainerLock containerLock(String world,int x,int y,int z){
        try(PreparedStatement ps=connection.prepareStatement("SELECT owner_uuid,expires_at FROM container_protection WHERE world=? AND x=? AND y=? AND z=?")){ps.setString(1,world);ps.setInt(2,x);ps.setInt(3,y);ps.setInt(4,z);try(ResultSet rs=ps.executeQuery()){if(!rs.next())return null;long exp=rs.getLong(2);if(exp<now()){update("DELETE FROM container_protection WHERE world=? AND x=? AND y=? AND z=?",world,x,y,z);return null;}return new ContainerLock(UUID.fromString(rs.getString(1)),exp);}}}catch(SQLException e){throw new RuntimeException(e);}
    }
    public synchronized void removeContainerLock(String world,int x,int y,int z){update("DELETE FROM container_protection WHERE world=? AND x=? AND y=? AND z=?",world,x,y,z);}

    public record SessionSnapshot(String context,String inventory,String world,double x,double y,double z,float yaw,float pitch,String gameMode,int level,float exp){}
    public synchronized void saveSession(UUID uuid,SessionSnapshot s){update("INSERT INTO player_sessions(uuid,context,inventory,world,x,y,z,yaw,pitch,gamemode,level,exp,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET context=excluded.context,inventory=excluded.inventory,world=excluded.world,x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch,gamemode=excluded.gamemode,level=excluded.level,exp=excluded.exp,created_at=excluded.created_at",uuid.toString(),s.context(),s.inventory(),s.world(),s.x(),s.y(),s.z(),s.yaw(),s.pitch(),s.gameMode(),s.level(),s.exp(),now());}
    public synchronized SessionSnapshot session(UUID uuid){
        try(PreparedStatement ps=connection.prepareStatement("SELECT context,inventory,world,x,y,z,yaw,pitch,gamemode,level,exp FROM player_sessions WHERE uuid=?")){ps.setString(1,uuid.toString());try(ResultSet rs=ps.executeQuery()){return rs.next()?new SessionSnapshot(rs.getString(1),rs.getString(2),rs.getString(3),rs.getDouble(4),rs.getDouble(5),rs.getDouble(6),rs.getFloat(7),rs.getFloat(8),rs.getString(9),rs.getInt(10),rs.getFloat(11)):null;}}catch(SQLException e){throw new RuntimeException(e);}
    }
    public synchronized void clearSession(UUID uuid){update("DELETE FROM player_sessions WHERE uuid=?",uuid.toString());}

    public record DungeonRun(long id,String instanceKey,int rank,long seed,int floors,int currentFloor,String state,UUID leader){}
    public synchronized long createDungeonRun(UUID leader,int rank,long seed,int floors,List<UUID> members){
        return transaction(()->{
            String key="dungeon:"+now()+":"+leader+":"+Math.abs(seed);long id;
            try(PreparedStatement ps=connection.prepareStatement("INSERT INTO dungeon_runs(instance_key,rank,seed,floors,state,leader_uuid,started_at) VALUES(?,?,?,?, 'ACTIVE',?,?)",Statement.RETURN_GENERATED_KEYS)){ps.setString(1,key);ps.setInt(2,rank);ps.setLong(3,seed);ps.setInt(4,floors);ps.setString(5,leader.toString());ps.setLong(6,now());ps.executeUpdate();try(ResultSet rs=ps.getGeneratedKeys()){if(!rs.next())return -1L;id=rs.getLong(1);}}
            for(UUID u:members)update("INSERT INTO dungeon_members(run_id,uuid,entry_rank) VALUES(?,?,?)",id,u.toString(),rank(u));audit(leader,"dungeon_start",id+":"+RankTier.fromIndex(rank).display()+":"+members.size());return id;
        },-1L);
    }
    public synchronized DungeonRun dungeonRun(long id){
        try(PreparedStatement ps=connection.prepareStatement("SELECT id,instance_key,rank,seed,floors,current_floor,state,leader_uuid FROM dungeon_runs WHERE id=?")){ps.setLong(1,id);try(ResultSet rs=ps.executeQuery()){return rs.next()?new DungeonRun(rs.getLong(1),rs.getString(2),rs.getInt(3),rs.getLong(4),rs.getInt(5),rs.getInt(6),rs.getString(7),UUID.fromString(rs.getString(8))):null;}}catch(SQLException e){throw new RuntimeException(e);}
    }
    public synchronized Long activeDungeonRun(UUID uuid){return longQueryNullable("SELECT dm.run_id FROM dungeon_members dm JOIN dungeon_runs dr ON dr.id=dm.run_id WHERE dm.uuid=? AND dr.state='ACTIVE' ORDER BY dr.id DESC LIMIT 1",uuid.toString());}
    public synchronized List<UUID> dungeonMembers(long runId){List<UUID> list=new ArrayList<>();try(PreparedStatement ps=connection.prepareStatement("SELECT uuid FROM dungeon_members WHERE run_id=?")){ps.setLong(1,runId);try(ResultSet rs=ps.executeQuery()){while(rs.next())list.add(UUID.fromString(rs.getString(1)));}}catch(SQLException e){throw new RuntimeException(e);}return list;}
    public synchronized void markDungeonMemberOut(long runId,UUID uuid){update("UPDATE dungeon_members SET alive=0 WHERE run_id=? AND uuid=?",runId,uuid.toString());}
    public synchronized int dungeonAliveCount(long runId){return intQuery("SELECT COUNT(*) FROM dungeon_members WHERE run_id=? AND alive=1",runId,0);}
    public synchronized void setDungeonFloor(long runId,int floor){update("UPDATE dungeon_runs SET current_floor=? WHERE id=? AND state='ACTIVE'",floor,runId);}
    public synchronized boolean finishDungeon(long runId,int totalReward,long cooldownSeconds){
        DungeonRun run=dungeonRun(runId);if(run==null||!"ACTIVE".equals(run.state()))return false;List<UUID> members=dungeonMembers(runId);if(members.isEmpty())return false;return transaction(()->{String rewardKey="dungeon:"+runId;int each=Math.max(0,totalReward/members.size());update("UPDATE dungeon_runs SET state='SUCCESS',ended_at=?,reward_key=? WHERE id=?",now(),rewardKey,runId);for(UUID u:members){if(creditOnce(rewardKey+":"+u,u,each,"dungeon")){update("UPDATE dungeon_members SET rewarded=1 WHERE run_id=? AND uuid=?",runId,u.toString());addDungeonCompletion(u);}setCooldown(u,"dungeon",now()+cooldownSeconds);}audit(run.leader(),"dungeon_success",runId+":reward="+totalReward);return true;},false);
    }
    public synchronized boolean failDungeon(long runId,long cooldownSeconds){DungeonRun run=dungeonRun(runId);if(run==null||!"ACTIVE".equals(run.state()))return false;update("UPDATE dungeon_runs SET state='FAILED',ended_at=? WHERE id=?",now(),runId);for(UUID u:dungeonMembers(runId))setCooldown(u,"dungeon",now()+cooldownSeconds);audit(run.leader(),"dungeon_fail",String.valueOf(runId));return true;}

    public synchronized long cooldown(UUID uuid,String kind){return longQuery("SELECT until_epoch FROM cooldowns WHERE uuid=? AND kind=?",new Object[]{uuid.toString(),kind},0L);}
    public synchronized void setCooldown(UUID uuid,String kind,long until){update("INSERT INTO cooldowns(uuid,kind,until_epoch) VALUES(?,?,?) ON CONFLICT(uuid,kind) DO UPDATE SET until_epoch=excluded.until_epoch",uuid.toString(),kind,until);}

    public synchronized void addCreativeBlock(String world,int x,int y,int z,UUID owner){update("INSERT OR REPLACE INTO creative_blocks(world,x,y,z,owner_uuid) VALUES(?,?,?,?,?)",world,x,y,z,owner.toString());}
    public synchronized boolean removeCreativeBlock(String world,int x,int y,int z){return executeUpdate("DELETE FROM creative_blocks WHERE world=? AND x=? AND y=? AND z=?",world,x,y,z)>0;}
    public synchronized void audit(UUID actor,String action,String details){update("INSERT INTO audit(ts,actor_uuid,action,details) VALUES(?,?,?,?)",now(),actor==null?null:actor.toString(),action,details==null?"":details);}

    private Claim claimQuery(String sql,Object...args){try(PreparedStatement ps=prepare(sql,args);ResultSet rs=ps.executeQuery()){return rs.next()?new Claim(rs.getLong(1),UUID.fromString(rs.getString(2)),rs.getString(3),rs.getInt(4),rs.getInt(5),rs.getInt(6),rs.getInt(7),rs.getInt(8),rs.getInt(9),rs.getString(10),rs.getLong(11)):null;}catch(SQLException e){throw new RuntimeException(e);}}
    private int intQuery(String sql,Object arg,int fallback){return intQuery(sql,new Object[]{arg},fallback);}
    private int intQuery(String sql,Object[]args,int fallback){try(PreparedStatement ps=prepare(sql,args);ResultSet rs=ps.executeQuery()){return rs.next()?rs.getInt(1):fallback;}catch(SQLException e){throw new RuntimeException(e);}}
    private long longQuery(String sql,Object arg,long fallback){return longQuery(sql,new Object[]{arg},fallback);}
    private long longQuery(String sql,Object[]args,long fallback){try(PreparedStatement ps=prepare(sql,args);ResultSet rs=ps.executeQuery()){return rs.next()?rs.getLong(1):fallback;}catch(SQLException e){throw new RuntimeException(e);}}
    private Long longQueryNullable(String sql,Object...args){try(PreparedStatement ps=prepare(sql,args);ResultSet rs=ps.executeQuery()){return rs.next()?rs.getLong(1):null;}catch(SQLException e){throw new RuntimeException(e);}}
    private String stringQuery(String sql,Object...args){try(PreparedStatement ps=prepare(sql,args);ResultSet rs=ps.executeQuery()){return rs.next()?rs.getString(1):null;}catch(SQLException e){throw new RuntimeException(e);}}
    private PreparedStatement prepare(String sql,Object...args)throws SQLException{PreparedStatement ps=connection.prepareStatement(sql);for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);return ps;}
    private int executeUpdate(String sql,Object...args){try(PreparedStatement ps=prepare(sql,args)){return ps.executeUpdate();}catch(SQLException e){throw new RuntimeException(e);}}
    private void update(String sql,Object...args){executeUpdate(sql,args);}

    private interface Tx<T>{T run() throws Exception;}
    private <T>T transaction(Tx<T> action,T fallback){boolean outer;try{outer=connection.getAutoCommit();if(outer)connection.setAutoCommit(false);T result=action.run();if(outer){connection.commit();connection.setAutoCommit(true);}return result;}catch(Exception e){try{connection.rollback();connection.setAutoCommit(true);}catch(SQLException ignored){}return fallback;}}
    private static long now(){return Instant.now().getEpochSecond();}

    @Override public synchronized void close(){try{connection.close();}catch(SQLException ignored){}}
}
