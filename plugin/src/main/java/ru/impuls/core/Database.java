package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.io.File;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class Database implements AutoCloseable {
    private final Connection c;
    public Database(File file) throws SQLException {
        file.getParentFile().mkdirs();
        c = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("CREATE TABLE IF NOT EXISTS profiles(uuid TEXT PRIMARY KEY,name TEXT NOT NULL,coins INTEGER NOT NULL DEFAULT 100,rank INTEGER NOT NULL DEFAULT 0,xp INTEGER NOT NULL DEFAULT 0,quests INTEGER NOT NULL DEFAULT 0,dungeons INTEGER NOT NULL DEFAULT 0,insured INTEGER NOT NULL DEFAULT 0,defender INTEGER NOT NULL DEFAULT 0,last_seen INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS guilds(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,owner_uuid TEXT NOT NULL,level INTEGER NOT NULL DEFAULT 1,treasury INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS guild_members(guild_id INTEGER NOT NULL,uuid TEXT NOT NULL UNIQUE,role TEXT NOT NULL,joined_at INTEGER NOT NULL,PRIMARY KEY(guild_id,uuid),FOREIGN KEY(guild_id) REFERENCES guilds(id) ON DELETE CASCADE)");
            s.execute("CREATE TABLE IF NOT EXISTS guild_invites(guild_id INTEGER NOT NULL,uuid TEXT NOT NULL,expires_at INTEGER NOT NULL,PRIMARY KEY(guild_id,uuid))");
            s.execute("CREATE TABLE IF NOT EXISTS claims(id INTEGER PRIMARY KEY AUTOINCREMENT,owner_uuid TEXT NOT NULL,world TEXT NOT NULL,min_x INTEGER NOT NULL,max_x INTEGER NOT NULL,min_y INTEGER NOT NULL,max_y INTEGER NOT NULL,min_z INTEGER NOT NULL,max_z INTEGER NOT NULL,kind TEXT NOT NULL,last_active INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS creative_blocks(world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,owner_uuid TEXT NOT NULL,PRIMARY KEY(world,x,y,z))");
            s.execute("CREATE TABLE IF NOT EXISTS cooldowns(uuid TEXT NOT NULL,kind TEXT NOT NULL,until_epoch INTEGER NOT NULL,PRIMARY KEY(uuid,kind))");
            s.execute("CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT,ts INTEGER NOT NULL,actor_uuid TEXT,action TEXT NOT NULL,details TEXT NOT NULL)");
        }
    }
    public synchronized void ensure(Player p) {
        try (PreparedStatement ps=c.prepareStatement("INSERT INTO profiles(uuid,name,last_seen) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,last_seen=excluded.last_seen")) {
            ps.setString(1,p.getUniqueId().toString()); ps.setString(2,p.getName()); ps.setLong(3,Instant.now().getEpochSecond()); ps.executeUpdate();
        } catch(SQLException e){throw new RuntimeException(e);} }
    public synchronized int coins(UUID u){ return intQuery("SELECT coins FROM profiles WHERE uuid=?",u,0); }
    public synchronized int rank(UUID u){ return intQuery("SELECT rank FROM profiles WHERE uuid=?",u,0); }
    public synchronized boolean insured(UUID u){ return intQuery("SELECT insured FROM profiles WHERE uuid=?",u,0)>0; }
    public synchronized void setInsured(UUID u, boolean v){ update("UPDATE profiles SET insured=? WHERE uuid=?", v?1:0,u.toString()); }
    public synchronized boolean charge(UUID u,int amount,String reason){
        try { c.setAutoCommit(false); int have=coins(u); if(have<amount){c.rollback();c.setAutoCommit(true);return false;} update("UPDATE profiles SET coins=coins-? WHERE uuid=?",amount,u.toString()); audit(u,"charge",reason+":"+amount); c.commit(); c.setAutoCommit(true); return true; }
        catch(Exception e){ try{c.rollback();c.setAutoCommit(true);}catch(Exception ignored){} throw new RuntimeException(e); }
    }
    public synchronized void credit(UUID u,int amount,String reason){ update("UPDATE profiles SET coins=coins+? WHERE uuid=?",amount,u.toString()); audit(u,"credit",reason+":"+amount); }
    public synchronized void addDefender(UUID u,int n){ update("UPDATE profiles SET defender=defender+? WHERE uuid=?",n,u.toString()); }
    public synchronized int defender(UUID u){ return intQuery("SELECT defender FROM profiles WHERE uuid=?",u,0); }
    public synchronized Long guildId(UUID u){ try(PreparedStatement ps=c.prepareStatement("SELECT guild_id FROM guild_members WHERE uuid=?")){ps.setString(1,u.toString());try(ResultSet r=ps.executeQuery()){return r.next()?r.getLong(1):null;}}catch(SQLException e){throw new RuntimeException(e);} }
    public synchronized String guildName(long id){ try(PreparedStatement ps=c.prepareStatement("SELECT name FROM guilds WHERE id=?")){ps.setLong(1,id);try(ResultSet r=ps.executeQuery()){return r.next()?r.getString(1):null;}}catch(SQLException e){throw new RuntimeException(e);} }
    public synchronized boolean createGuild(UUID owner,String name,int cost){
        if(guildId(owner)!=null) return false;
        try { c.setAutoCommit(false); if(coins(owner)<cost){c.rollback();c.setAutoCommit(true);return false;}
            try(PreparedStatement ps=c.prepareStatement("INSERT INTO guilds(name,owner_uuid,created_at) VALUES(?,?,?)",Statement.RETURN_GENERATED_KEYS)){ps.setString(1,name);ps.setString(2,owner.toString());ps.setLong(3,Instant.now().getEpochSecond());ps.executeUpdate();try(ResultSet r=ps.getGeneratedKeys()){r.next();long id=r.getLong(1);update("INSERT INTO guild_members(guild_id,uuid,role,joined_at) VALUES(?,?,?,?)",id,owner.toString(),"LEADER",Instant.now().getEpochSecond());}}
            update("UPDATE profiles SET coins=coins-? WHERE uuid=?",cost,owner.toString()); audit(owner,"guild_create",name); c.commit(); c.setAutoCommit(true); return true;
        } catch(Exception e){try{c.rollback();c.setAutoCommit(true);}catch(Exception ignored){} return false;}
    }
    public synchronized boolean invite(UUID leader,UUID target){ Long gid=guildId(leader); if(gid==null||guildId(target)!=null)return false; String role=memberRole(leader); if(!"LEADER".equals(role)&&!"DEPUTY".equals(role))return false; update("INSERT INTO guild_invites(guild_id,uuid,expires_at) VALUES(?,?,?) ON CONFLICT(guild_id,uuid) DO UPDATE SET expires_at=excluded.expires_at",gid,target.toString(),Instant.now().getEpochSecond()+86400); return true; }
    public synchronized boolean acceptInvite(UUID u){
        if(guildId(u)!=null)return false; try(PreparedStatement ps=c.prepareStatement("SELECT guild_id FROM guild_invites WHERE uuid=? AND expires_at>? ORDER BY expires_at DESC LIMIT 1")){ps.setString(1,u.toString());ps.setLong(2,Instant.now().getEpochSecond());try(ResultSet r=ps.executeQuery()){if(!r.next())return false;long gid=r.getLong(1);update("INSERT INTO guild_members(guild_id,uuid,role,joined_at) VALUES(?,?,?,?)",gid,u.toString(),"MEMBER",Instant.now().getEpochSecond());update("DELETE FROM guild_invites WHERE uuid=?",u.toString());audit(u,"guild_join",String.valueOf(gid));return true;}}}catch(SQLException e){throw new RuntimeException(e);} }
    public synchronized boolean leaveGuild(UUID u){ Long gid=guildId(u); if(gid==null)return false; if("LEADER".equals(memberRole(u)))return false; update("DELETE FROM guild_members WHERE uuid=?",u.toString()); audit(u,"guild_leave",String.valueOf(gid)); return true; }
    public synchronized String memberRole(UUID u){try(PreparedStatement ps=c.prepareStatement("SELECT role FROM guild_members WHERE uuid=?")){ps.setString(1,u.toString());try(ResultSet r=ps.executeQuery()){return r.next()?r.getString(1):null;}}catch(SQLException e){throw new RuntimeException(e);} }
    public synchronized boolean depositGuild(UUID u,int amount){ Long gid=guildId(u); if(gid==null||amount<=0||coins(u)<amount)return false; try{c.setAutoCommit(false);update("UPDATE profiles SET coins=coins-? WHERE uuid=?",amount,u.toString());update("UPDATE guilds SET treasury=treasury+? WHERE id=?",amount,gid);audit(u,"guild_deposit",gid+":"+amount);c.commit();c.setAutoCommit(true);return true;}catch(Exception e){try{c.rollback();c.setAutoCommit(true);}catch(Exception ignored){}return false;} }
    public synchronized int guildTreasury(long gid){try(PreparedStatement ps=c.prepareStatement("SELECT treasury FROM guilds WHERE id=?")){ps.setLong(1,gid);try(ResultSet r=ps.executeQuery()){return r.next()?r.getInt(1):0;}}catch(SQLException e){throw new RuntimeException(e);} }
    public synchronized long cooldown(UUID u,String kind){try(PreparedStatement ps=c.prepareStatement("SELECT until_epoch FROM cooldowns WHERE uuid=? AND kind=?")){ps.setString(1,u.toString());ps.setString(2,kind);try(ResultSet r=ps.executeQuery()){return r.next()?r.getLong(1):0L;}}catch(SQLException e){throw new RuntimeException(e);} }
    public synchronized void setCooldown(UUID u,String kind,long until){update("INSERT INTO cooldowns(uuid,kind,until_epoch) VALUES(?,?,?) ON CONFLICT(uuid,kind) DO UPDATE SET until_epoch=excluded.until_epoch",u.toString(),kind,until);}

    public record Claim(long id, UUID owner, String world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ, String kind) {
        public boolean contains(String w,int x,int y,int z){return world.equals(w)&&x>=minX&&x<=maxX&&y>=minY&&y<=maxY&&z>=minZ&&z<=maxZ;}
    }
    public synchronized Claim claimAt(String world,int x,int y,int z){
        try(PreparedStatement ps=c.prepareStatement("SELECT id,owner_uuid,world,min_x,max_x,min_y,max_y,min_z,max_z,kind FROM claims WHERE world=? AND ? BETWEEN min_x AND max_x AND ? BETWEEN min_y AND max_y AND ? BETWEEN min_z AND max_z ORDER BY id LIMIT 1")){
            ps.setString(1,world);ps.setInt(2,x);ps.setInt(3,y);ps.setInt(4,z);try(ResultSet r=ps.executeQuery()){return r.next()?new Claim(r.getLong(1),UUID.fromString(r.getString(2)),r.getString(3),r.getInt(4),r.getInt(5),r.getInt(6),r.getInt(7),r.getInt(8),r.getInt(9),r.getString(10)):null;}
        }catch(SQLException e){throw new RuntimeException(e);}
    }
    public synchronized boolean ownsClaimAt(UUID u,String world,int x,int y,int z,String kind){Claim q=claimAt(world,x,y,z);return q!=null&&q.owner().equals(u)&&(kind==null||kind.equals(q.kind()));}
    public synchronized int claimCount(UUID u,String kind){
        String sql=kind==null?"SELECT COUNT(*) FROM claims WHERE owner_uuid=?":"SELECT COUNT(*) FROM claims WHERE owner_uuid=? AND kind=?";
        try(PreparedStatement ps=c.prepareStatement(sql)){ps.setString(1,u.toString());if(kind!=null)ps.setString(2,kind);try(ResultSet r=ps.executeQuery()){return r.next()?r.getInt(1):0;}}catch(SQLException e){throw new RuntimeException(e);}
    }
    public synchronized boolean createClaim(UUID owner,String world,int minX,int maxX,int minY,int maxY,int minZ,int maxZ,String kind,int cost){
        try{c.setAutoCommit(false);
            try(PreparedStatement ov=c.prepareStatement("SELECT 1 FROM claims WHERE world=? AND NOT(max_x<? OR min_x>? OR max_y<? OR min_y>? OR max_z<? OR min_z>?) LIMIT 1")){
                ov.setString(1,world);ov.setInt(2,minX);ov.setInt(3,maxX);ov.setInt(4,minY);ov.setInt(5,maxY);ov.setInt(6,minZ);ov.setInt(7,maxZ);try(ResultSet r=ov.executeQuery()){if(r.next()){c.rollback();c.setAutoCommit(true);return false;}}
            }
            if(coins(owner)<cost){c.rollback();c.setAutoCommit(true);return false;}
            update("INSERT INTO claims(owner_uuid,world,min_x,max_x,min_y,max_y,min_z,max_z,kind,last_active) VALUES(?,?,?,?,?,?,?,?,?,?)",owner.toString(),world,minX,maxX,minY,maxY,minZ,maxZ,kind,Instant.now().getEpochSecond());
            update("UPDATE profiles SET coins=coins-? WHERE uuid=?",cost,owner.toString()); audit(owner,"claim_buy",kind+":"+world+":"+minX+","+minZ+".."+maxX+","+maxZ+":"+cost);c.commit();c.setAutoCommit(true);return true;
        }catch(Exception e){try{c.rollback();c.setAutoCommit(true);}catch(Exception ignored){}return false;}
    }
    public synchronized void addCreativeBlock(String world,int x,int y,int z,UUID owner){update("INSERT OR REPLACE INTO creative_blocks(world,x,y,z,owner_uuid) VALUES(?,?,?,?,?)",world,x,y,z,owner.toString());}
    public synchronized boolean removeCreativeBlock(String world,int x,int y,int z){try(PreparedStatement ps=c.prepareStatement("DELETE FROM creative_blocks WHERE world=? AND x=? AND y=? AND z=?")){ps.setString(1,world);ps.setInt(2,x);ps.setInt(3,y);ps.setInt(4,z);return ps.executeUpdate()>0;}catch(SQLException e){throw new RuntimeException(e);} }
    public synchronized void audit(UUID actor,String action,String details){update("INSERT INTO audit(ts,actor_uuid,action,details) VALUES(?,?,?,?)",Instant.now().getEpochSecond(),actor==null?null:actor.toString(),action,details);}
    private int intQuery(String sql,UUID u,int def){try(PreparedStatement ps=c.prepareStatement(sql)){ps.setString(1,u.toString());try(ResultSet r=ps.executeQuery()){return r.next()?r.getInt(1):def;}}catch(SQLException e){throw new RuntimeException(e);} }
    private void update(String sql,Object... args){try(PreparedStatement ps=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);ps.executeUpdate();}catch(SQLException e){throw new RuntimeException(e);} }
    public synchronized void close(){try{c.close();}catch(SQLException ignored){}}
}
