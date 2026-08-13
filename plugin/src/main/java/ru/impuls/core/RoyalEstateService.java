package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.UUID;

/** Protected owner estate plus explicit temporary/permanent visitor access and castle portal command. */
public final class RoyalEstateService implements Listener, AutoCloseable {
    private static final int CX=-688, CZ=-688, EX=CX+95, EZ=CZ+85, CASTLE_X=CX+355, CASTLE_Z=CZ+350, BATCH=120;
    private record P(int x,int y,int z,Material m){}
    private final JavaPlugin plugin;
    private final Database db;
    private final Connection connection;
    private final Deque<P> queue=new ArrayDeque<>();
    private final File marker;
    private int task=-1;

    private RoyalEstateService(JavaPlugin plugin,Database db)throws SQLException{
        this.plugin=plugin;this.db=db;marker=new File(plugin.getDataFolder(),"royal_estate_v13.done");
        File file=new File(plugin.getDataFolder(),plugin.getConfig().getString("database.file","impuls.sqlite3"));
        connection=DriverManager.getConnection("jdbc:sqlite:"+file.getAbsolutePath());
        try(Statement s=connection.createStatement()){
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS royal_access(uuid TEXT PRIMARY KEY,granted_by TEXT NOT NULL,expires_at INTEGER NOT NULL,created_at INTEGER NOT NULL)");
        }
    }

    public static void start(JavaPlugin plugin,Database db){
        try{
            RoyalEstateService s=new RoyalEstateService(plugin,db);Bukkit.getPluginManager().registerEvents(s,plugin);
            if(!s.marker.exists())Bukkit.getScheduler().runTaskLater(plugin,s::plan,20L*70L);
        }catch(SQLException e){plugin.getLogger().severe("Royal estate SQLite init failed: "+e.getMessage());}
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event){
        String raw=event.getMessage().trim();if(!raw.toLowerCase(Locale.ROOT).startsWith("/impuls royal"))return;event.setCancelled(true);
        Player p=event.getPlayer();String[] a=raw.split("\\s+");String sub=a.length>2?a[2].toLowerCase(Locale.ROOT):"visit";
        switch(sub){
            case "visit" -> {if(!allowed(p)){p.sendMessage(ChatColor.RED+"Нет доступа в личные королевские владения.");return;}p.teleport(safe(primary(),EX,EZ));p.sendMessage(ChatColor.GOLD+"[ImPuls] Королевская усадьба.");}
            case "castle" -> {if(!allowed(p)){p.sendMessage(ChatColor.RED+"Нет доступа к порталу замка.");return;}World w=primary();int y=Math.max(146,w.getHighestBlockYAt(CASTLE_X,CASTLE_Z)+2);p.teleport(new Location(w,CASTLE_X+0.5,y,CASTLE_Z+0.5));}
            case "grant" -> grant(p,a);
            case "revoke" -> revoke(p,a);
            case "status" -> p.sendMessage(ChatColor.GOLD+"Royal access: "+(p.hasPermission("impuls.admin")?"OWNER/ADMIN":allowed(p)?"GRANTED":"NONE"));
            default -> p.sendMessage("/impuls royal visit|castle|status | grant <player> <minutes|permanent> | revoke <player>");
        }
    }

    private void grant(Player admin,String[] a){
        if(!admin.hasPermission("impuls.admin")){admin.sendMessage(ChatColor.RED+"Требуется impuls.admin.");return;}if(a.length<5){admin.sendMessage("/impuls royal grant <player> <minutes|permanent>");return;}
        Player target=Bukkit.getPlayerExact(a[3]);if(target==null){admin.sendMessage(ChatColor.RED+"Игрок должен быть онлайн для первой выдачи доступа.");return;}
        long expires;if("permanent".equalsIgnoreCase(a[4]))expires=Long.MAX_VALUE;else try{expires=Instant.now().getEpochSecond()+Math.max(1,Long.parseLong(a[4]))*60L;}catch(NumberFormatException e){return;}
        update("INSERT INTO royal_access(uuid,granted_by,expires_at,created_at) VALUES(?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET granted_by=excluded.granted_by,expires_at=excluded.expires_at",target.getUniqueId().toString(),admin.getUniqueId().toString(),expires,Instant.now().getEpochSecond());
        db.audit(admin.getUniqueId(),"royal_access_grant",target.getUniqueId()+":"+expires);admin.sendMessage(ChatColor.GREEN+"Доступ выдан "+target.getName()+".");target.sendMessage(ChatColor.GOLD+"Тебе выдан доступ к королевской усадьбе: /impuls royal visit");
    }
    private void revoke(Player admin,String[] a){
        if(!admin.hasPermission("impuls.admin")||a.length<4)return;Player target=Bukkit.getPlayerExact(a[3]);UUID id=target==null?uuidByName(a[3]):target.getUniqueId();if(id==null)return;
        update("DELETE FROM royal_access WHERE uuid=?",id.toString());db.audit(admin.getUniqueId(),"royal_access_revoke",id.toString());admin.sendMessage(ChatColor.YELLOW+"Королевский доступ отозван.");
    }
    private boolean allowed(Player p){if(p.hasPermission("impuls.admin"))return true;long exp=longQuery("SELECT expires_at FROM royal_access WHERE uuid=?",p.getUniqueId().toString());if(exp<=Instant.now().getEpochSecond()){update("DELETE FROM royal_access WHERE uuid=?",p.getUniqueId().toString());return false;}return true;}

    private void plan(){
        World w=primary();if(w==null||task!=-1)return;int y=w.getHighestBlockYAt(EX,EZ)+1;
        manor(EX,y,EZ);garden(EX-48,y,EZ);stable(EX+48,y,EZ);prison(EX,y-12,EZ+48);tunnel(EX,y-9,EZ,EX+70,EZ+70);secretRoom(EX+70,y-9,EZ+70);portalDais(EX,y,EZ+42);
        task=Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin,this::drain,1L,2L);plugin.getLogger().info("Royal estate queued: "+queue.size());
    }

    private void manor(int cx,int y,int cz){
        for(int x=-22;x<=22;x++)for(int z=-16;z<=16;z++)add(cx+x,y,cz+z,Material.POLISHED_ANDESITE);
        for(int h=1;h<=13;h++)for(int x=-22;x<=22;x++)for(int z=-16;z<=16;z++)if(Math.abs(x)==22||Math.abs(z)==16)add(cx+x,y+h,cz+z,h<3?Material.STONE_BRICKS:Material.DARK_OAK_PLANKS);
        for(int x=-24;x<=24;x++)for(int z=-18;z<=18;z++)add(cx+x,y+14,cz+z,Material.DEEPSLATE_TILE_SLAB);
        add(cx,y+1,cz-16,Material.DARK_OAK_DOOR);add(cx,y+1,cz+8,Material.ENDER_CHEST);add(cx-8,y+1,cz+7,Material.BOOKSHELF);add(cx+8,y+1,cz+7,Material.BOOKSHELF);
    }
    private void garden(int cx,int y,int cz){
        for(int x=-25;x<=25;x++)for(int z=-22;z<=22;z++){if((Math.abs(x)<=2)||(Math.abs(z)<=2))add(cx+x,y,cz+z,Material.MOSS_BLOCK);else if((x+z)%11==0)add(cx+x,y+1,cz+z,Material.ROSE_BUSH);}
        for(int x=-7;x<=7;x++)for(int z=-7;z<=7;z++){int d=x*x+z*z;if(d<=49)add(cx+x,y,cz+z,d>36?Material.QUARTZ_BLOCK:Material.WATER);}add(cx,y+1,cz,Material.SEA_LANTERN);
    }
    private void stable(int cx,int y,int cz){for(int x=-18;x<=18;x++)for(int z=-12;z<=12;z++)add(cx+x,y,cz+z,Material.COARSE_DIRT);for(int x=-19;x<=19;x++){add(cx+x,y+1,cz-13,Material.DARK_OAK_FENCE);add(cx+x,y+1,cz+13,Material.DARK_OAK_FENCE);}for(int z=-13;z<=13;z++){add(cx-19,y+1,cz+z,Material.DARK_OAK_FENCE);add(cx+19,y+1,cz+z,Material.DARK_OAK_FENCE);}for(int x=-12;x<=12;x+=8)add(cx+x,y+1,cz,Material.HAY_BLOCK);}
    private void prison(int cx,int y,int cz){
        for(int x=-16;x<=16;x++)for(int z=-12;z<=12;z++){add(cx+x,y,cz+z,Material.DEEPSLATE_BRICKS);add(cx+x,y+6,cz+z,Material.DEEPSLATE_BRICKS);if(Math.abs(x)==16||Math.abs(z)==12)for(int h=1;h<=5;h++)add(cx+x,y+h,cz+z,Material.DEEPSLATE_BRICKS);}
        for(int x=-12;x<=12;x+=8)for(int z=-8;z<=8;z++)add(cx+x,y+2,cz+z,Material.IRON_BARS);
    }
    private void tunnel(int x1,int y,int z1,int x2,int z2){int steps=Math.max(Math.abs(x2-x1),Math.abs(z2-z1));for(int i=0;i<=steps;i++){int x=x1+(x2-x1)*i/steps,z=z1+(z2-z1)*i/steps;for(int dx=-2;dx<=2;dx++)for(int h=0;h<=4;h++)add(x+dx,y+h,z,Material.AIR);if(i%12==0)add(x,y+3,z,Material.LANTERN);}}
    private void secretRoom(int cx,int y,int cz){for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++){add(cx+x,y,cz+z,Material.POLISHED_DEEPSLATE);add(cx+x,y+6,cz+z,Material.POLISHED_DEEPSLATE);if(Math.abs(x)==8||Math.abs(z)==8)for(int h=1;h<=5;h++)add(cx+x,y+h,cz+z,Material.POLISHED_DEEPSLATE);}add(cx,y+1,cz,Material.ENDER_CHEST);}
    private void portalDais(int cx,int y,int cz){for(int x=-6;x<=6;x++)for(int z=-6;z<=6;z++)if(x*x+z*z<=36)add(cx+x,y,cz+z,Material.POLISHED_BLACKSTONE_BRICKS);add(cx,y+1,cz,Material.LODESTONE);}

    private void drain(){World w=primary();if(w==null)return;int n=0;while(n++<BATCH&&!queue.isEmpty()){P p=queue.pollFirst();Block b=w.getBlockAt(p.x,p.y,p.z);if(replaceable(b.getType(),p.m))b.setType(p.m,false);}if(!queue.isEmpty())return;if(task!=-1){Bukkit.getScheduler().cancelTask(task);task=-1;}try{Files.writeString(marker.toPath(),"completed "+Instant.now()+"\n");}catch(Exception ignored){}db.audit(null,"royal_estate_complete","v1.3");Bukkit.broadcastMessage(ChatColor.GOLD+"[ImPuls] Королевская усадьба, сад, тюрьма и секретные тоннели подготовлены.");}

    private boolean replaceable(Material old,Material target){
        if(target==Material.AIR)return naturalCarvable(old);
        return old.isAir()||naturalCarvable(old);
    }
    private boolean naturalCarvable(Material m){return switch(m){
        case GRASS_BLOCK,DIRT,COARSE_DIRT,PODZOL,ROOTED_DIRT,STONE,DEEPSLATE,TUFF,ANDESITE,DIORITE,GRANITE,
                GRAVEL,SAND,RED_SAND,CLAY,SHORT_GRASS,TALL_GRASS,FERN,LARGE_FERN,OAK_LEAVES,BIRCH_LEAVES,
                SPRUCE_LEAVES,JUNGLE_LEAVES,ACACIA_LEAVES,DARK_OAK_LEAVES,MANGROVE_LEAVES,CHERRY_LEAVES,
                PALE_OAK_LEAVES,WATER -> true;
        default -> false;
    };}
    private void add(int x,int y,int z,Material m){queue.addLast(new P(x,y,z,m));}
    private Location safe(World w,int x,int z){int y=Math.max(w.getMinHeight()+2,w.getHighestBlockYAt(x,z)+1);return new Location(w,x+0.5,y,z+0.5);}
    private World primary(){for(World w:Bukkit.getWorlds())if(w.getEnvironment()==World.Environment.NORMAL)return w;return null;}
    private void update(String sql,Object...a){try(PreparedStatement p=connection.prepareStatement(sql)){for(int i=0;i<a.length;i++)p.setObject(i+1,a[i]);p.executeUpdate();}catch(SQLException e){throw new RuntimeException(e);}}
    private long longQuery(String sql,Object...a){try(PreparedStatement p=connection.prepareStatement(sql)){for(int i=0;i<a.length;i++)p.setObject(i+1,a[i]);try(ResultSet r=p.executeQuery()){return r.next()?r.getLong(1):0;}}catch(SQLException e){return 0;}}
    private UUID uuidByName(String name){try(PreparedStatement p=connection.prepareStatement("SELECT uuid FROM profiles WHERE lower(name)=lower(?) LIMIT 1")){p.setString(1,name);try(ResultSet r=p.executeQuery()){return r.next()?UUID.fromString(r.getString(1)):null;}}catch(SQLException e){return null;}}
    @Override public void close(){try{connection.close();}catch(SQLException ignored){}}
}
