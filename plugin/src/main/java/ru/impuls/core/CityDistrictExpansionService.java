package ru.impuls.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/** Non-destructive staged builder for everyday capital districts. */
public final class CityDistrictExpansionService {
    private static final int CX = -688, CZ = -688, BATCH = 160;
    private record Placement(int x, int y, int z, Material material) { }
    private final JavaPlugin plugin;
    private final Database db;
    private final Deque<Placement> queue = new ArrayDeque<>();
    private final File marker;
    private int placed, skipped, taskId = -1;

    private CityDistrictExpansionService(JavaPlugin plugin, Database db) {
        this.plugin = plugin; this.db = db; this.marker = new File(plugin.getDataFolder(), "districts_v13.done");
    }

    public static void start(JavaPlugin plugin, Database db) {
        CityDistrictExpansionService s = new CityDistrictExpansionService(plugin, db);
        if (!s.marker.exists()) Bukkit.getScheduler().runTaskLater(plugin, s::begin, 20L * 50L);
    }

    private void begin() {
        World world = primary(); if (world == null || taskId != -1) return;
        roads(world); market(world); residential(world); craft(world); farms(world); stables(world); barracks(world);
        tavernForgeWarehouse(world); canals(world); loggingYard(world);
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::drain, 1L, 2L);
        plugin.getLogger().info("City district expansion queued: " + queue.size() + " placements");
    }

    private void roads(World w) {
        for (int d=-820; d<=820; d++) { roadTile(w,CX+d,CZ,d); roadTile(w,CX,CZ+d,d); }
        for (int d=-600; d<=600; d++) { roadTile(w,CX+d,CZ+300,d); roadTile(w,CX+d,CZ-300,d); roadTile(w,CX+300,CZ+d,d); roadTile(w,CX-300,CZ+d,d); }
    }
    private void roadTile(World w,int x,int z,int seed) {
        int y=w.getHighestBlockYAt(x,z); add(x,y,z,seed%7==0?Material.POLISHED_ANDESITE:Material.STONE_BRICKS);
        if(seed%24==0){add(x+2,y+1,z+2,Material.OAK_FENCE);add(x+2,y+2,z+2,Material.LANTERN);}
    }

    private void market(World w) {
        int bx=CX+80,bz=CZ-115;
        for(int row=0;row<3;row++)for(int col=0;col<6;col++){int x=bx+col*15,z=bz+row*18,y=w.getHighestBlockYAt(x,z)+1;stall(x,y,z,(row+col)%2==0?Material.RED_WOOL:Material.YELLOW_WOOL);}
    }
    private void stall(int x,int y,int z,Material awning){
        for(int dx=-4;dx<=4;dx++)for(int dz=-3;dz<=3;dz++)add(x+dx,y,z+dz,Material.SPRUCE_PLANKS);
        for(int dx:new int[]{-4,4})for(int dz:new int[]{-3,3})for(int h=1;h<=4;h++)add(x+dx,y+h,z+dz,Material.OAK_FENCE);
        for(int dx=-4;dx<=4;dx++)for(int dz=-3;dz<=3;dz++)add(x+dx,y+5,z+dz,awning); add(x,y+1,z,Material.BARREL);
    }

    private void residential(World w){
        int[][] c={{CX-420,CZ-150},{CX-500,CZ+40},{CX-380,CZ+180},{CX+420,CZ-150},{CX+480,CZ+70},{CX+390,CZ+190}};int i=0;
        for(int[] p:c)house(p[0],w.getHighestBlockYAt(p[0],p[1])+1,p[1],i++%2==0?Material.SPRUCE_PLANKS:Material.OAK_PLANKS);
    }
    private void house(int cx,int y,int cz,Material timber){
        int a=9,b=11;for(int x=-a;x<=a;x++)for(int z=-b;z<=b;z++)add(cx+x,y,cz+z,Material.COBBLESTONE);
        for(int h=1;h<=7;h++)for(int x=-a;x<=a;x++)for(int z=-b;z<=b;z++)if(Math.abs(x)==a||Math.abs(z)==b)add(cx+x,y+h,cz+z,(x+z+h)%5==0?timber:Material.WHITE_TERRACOTTA);
        for(int x=-a-1;x<=a+1;x++)for(int z=-b-1;z<=b+1;z++)add(cx+x,y+8,cz+z,Material.DARK_OAK_SLAB);
        add(cx,y+1,cz-b,Material.OAK_DOOR);add(cx+4,y+3,cz-b,Material.GLASS_PANE);add(cx-4,y+3,cz-b,Material.GLASS_PANE);
    }

    private void craft(World w){int x=CX-260,z=CZ-300,y=w.getHighestBlockYAt(x,z)+1;workshop(x,y,z,Material.SMITHING_TABLE,Material.BLAST_FURNACE);workshop(x+55,y,z,Material.CRAFTING_TABLE,Material.STONECUTTER);workshop(x+110,y,z,Material.LOOM,Material.CARTOGRAPHY_TABLE);}
    private void workshop(int cx,int y,int cz,Material a,Material b){
        for(int x=-12;x<=12;x++)for(int z=-9;z<=9;z++)add(cx+x,y,cz+z,Material.STONE_BRICKS);
        for(int h=1;h<=7;h++)for(int x=-12;x<=12;x++)for(int z=-9;z<=9;z++)if(Math.abs(x)==12||Math.abs(z)==9)add(cx+x,y+h,cz+z,h<=2?Material.COBBLED_DEEPSLATE:Material.SPRUCE_PLANKS);
        add(cx-3,y+1,cz,a);add(cx+3,y+1,cz,b);add(cx,y+1,cz+4,Material.BARREL);
    }

    private void farms(World w){
        int[][] fs={{CX-620,CZ+520},{CX-500,CZ+600},{CX+560,CZ+570}};
        for(int[] c:fs){int y=w.getHighestBlockYAt(c[0],c[1]);for(int x=-22;x<=22;x++)for(int z=-16;z<=16;z++){if(x%8==0)add(c[0]+x,y,c[1]+z,Material.WATER);else{add(c[0]+x,y,c[1]+z,Material.FARMLAND);if((x+z)%2==0)add(c[0]+x,y+1,c[1]+z,Material.WHEAT);}}
            for(int x=-24;x<=24;x++){add(c[0]+x,y+1,c[1]-18,Material.OAK_FENCE);add(c[0]+x,y+1,c[1]+18,Material.OAK_FENCE);}for(int z=-18;z<=18;z++){add(c[0]-24,y+1,c[1]+z,Material.OAK_FENCE);add(c[0]+24,y+1,c[1]+z,Material.OAK_FENCE);}}
    }

    private void stables(World w){int cx=CX-510,cz=CZ+330,y=w.getHighestBlockYAt(cx,cz)+1;for(int x=-26;x<=26;x++)for(int z=-12;z<=12;z++)add(cx+x,y,cz+z,Material.COARSE_DIRT);for(int x=-27;x<=27;x++){add(cx+x,y+1,cz-13,Material.DARK_OAK_FENCE);add(cx+x,y+1,cz+13,Material.DARK_OAK_FENCE);}for(int z=-13;z<=13;z++){add(cx-27,y+1,cz+z,Material.DARK_OAK_FENCE);add(cx+27,y+1,cz+z,Material.DARK_OAK_FENCE);}for(int x=-20;x<=20;x+=8){add(cx+x,y+1,cz,Material.HAY_BLOCK);add(cx+x,y+1,cz+4,Material.CAULDRON);}}

    private void barracks(World w){int cx=CX+520,cz=CZ-420,y=w.getHighestBlockYAt(cx,cz)+1;house(cx-20,y,cz,Material.DARK_OAK_PLANKS);house(cx+20,y,cz,Material.DARK_OAK_PLANKS);for(int x=-35;x<=35;x++)for(int z=-28;z<=28;z++)if(Math.abs(x)==35||Math.abs(z)==28)add(cx+x,y+1,cz+z,Material.STONE_BRICK_WALL);add(cx,y+1,cz,Material.BELL);}

    private void tavernForgeWarehouse(World w){
        int y=w.getHighestBlockYAt(CX+330,CZ+30)+1;house(CX+330,y,CZ+30,Material.SPRUCE_PLANKS);add(CX+330,y+1,CZ+30,Material.BREWING_STAND);add(CX+325,y+1,CZ+30,Material.BARREL);
        y=w.getHighestBlockYAt(CX+330,CZ-20)+1;workshop(CX+330,y,CZ-20,Material.ANVIL,Material.BLAST_FURNACE);
        int cx=CX+360,cz=CZ+95;y=w.getHighestBlockYAt(cx,cz)+1;for(int x=-14;x<=14;x++)for(int z=-12;z<=12;z++){add(cx+x,y,cz+z,Material.SPRUCE_PLANKS);if(Math.abs(x)==14||Math.abs(z)==12)for(int h=1;h<=7;h++)add(cx+x,y+h,cz+z,Material.STONE_BRICKS);}for(int x=-9;x<=9;x+=3)for(int z=-7;z<=7;z+=3)add(cx+x,y+1,cz+z,Material.BARREL);
    }

    private void canals(World w){
        int y=w.getHighestBlockYAt(CX+245,CZ+245)-2;for(int d=-260;d<=260;d++){canalSlice(CX+250,y,CZ+d);canalSlice(CX+d,y,CZ+250);}for(int d=-200;d<=200;d+=100){for(int x=-5;x<=5;x++)for(int z=-4;z<=4;z++)add(CX+250+x,y+2,CZ+d+z,Material.SPRUCE_PLANKS);for(int x=-4;x<=4;x++)for(int z=-5;z<=5;z++)add(CX+d+x,y+2,CZ+250+z,Material.SPRUCE_PLANKS);}
    }
    private void canalSlice(int cx,int y,int cz){for(int d=-4;d<=4;d++){add(cx+d,y,cz,Material.STONE_BRICKS);add(cx+d,y+1,cz,Math.abs(d)==4?Material.STONE_BRICKS:Material.WATER);}}

    private void loggingYard(World w){int cx=CX-650,cz=CZ-470,y=w.getHighestBlockYAt(cx,cz)+1;for(int i=0;i<16;i++){int x=cx-24+(i%8)*7,z=cz-10+(i/8)*16;for(int h=0;h<3;h++)add(x,y+h,z,i%2==0?Material.SPRUCE_LOG:Material.OAK_LOG);}add(cx,y+1,cz,Material.STONECUTTER);add(cx+4,y+1,cz,Material.CRAFTING_TABLE);}

    private void drain(){
        World w=primary();if(w==null)return;int n=0;while(n++<BATCH&&!queue.isEmpty()){Placement p=queue.pollFirst();Block b=w.getBlockAt(p.x,p.y,p.z);if(replaceable(b.getType())){b.setType(p.material,false);placed++;}else skipped++;}
        if(!queue.isEmpty())return;
        if(taskId!=-1){Bukkit.getScheduler().cancelTask(taskId);taskId=-1;}
        try{plugin.getDataFolder().mkdirs();Files.writeString(marker.toPath(),"completed "+Instant.now()+"\nplaced="+placed+" skipped="+skipped+"\n");}catch(Exception ignored){}
        db.audit(null,"city_districts_complete","placed="+placed+":skipped="+skipped);Bukkit.broadcastMessage(ChatColor.GOLD+"[ImPuls] Городские районы и инфраструктура v1.3 достроены без перезаписи существующих рукотворных блоков.");
    }

    private boolean replaceable(Material m){return m.isAir()||switch(m){case GRASS_BLOCK,DIRT,COARSE_DIRT,PODZOL,ROOTED_DIRT,STONE,DEEPSLATE,SAND,RED_SAND,GRAVEL,CLAY,SHORT_GRASS,TALL_GRASS,FERN,LARGE_FERN,DANDELION,POPPY,BLUE_ORCHID,ALLIUM,AZURE_BLUET,RED_TULIP,ORANGE_TULIP,WHITE_TULIP,PINK_TULIP,OXEYE_DAISY,CORNFLOWER,LILY_OF_THE_VALLEY,OAK_LEAVES,SPRUCE_LEAVES,BIRCH_LEAVES,JUNGLE_LEAVES,ACACIA_LEAVES,DARK_OAK_LEAVES,MANGROVE_LEAVES,CHERRY_LEAVES,PALE_OAK_LEAVES,WATER->true;default->false;};}
    private void add(int x,int y,int z,Material m){queue.addLast(new Placement(x,y,z,m));}
    private World primary(){for(World w:Bukkit.getWorlds())if(w.getEnvironment()==World.Environment.NORMAL)return w;return null;}
}
