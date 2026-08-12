package ru.impuls.core;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Objective;
import java.io.File;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ImPulsCorePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private Database db;
    private final Set<UUID> vipCreative = ConcurrentHashMap.newKeySet();
    private final Map<UUID,ItemStack[]> survivalInv = new ConcurrentHashMap<>();
    private final Set<UUID> paidFlight = ConcurrentHashMap.newKeySet();
    private static final Set<Material> CREATIVE_FORBIDDEN=EnumSet.of(Material.BEDROCK,Material.BARRIER,Material.COMMAND_BLOCK,Material.CHAIN_COMMAND_BLOCK,Material.REPEATING_COMMAND_BLOCK,Material.STRUCTURE_BLOCK,Material.JIGSAW,Material.TNT,Material.END_PORTAL_FRAME);

    @Override public void onEnable(){
        saveDefaultConfig();
        try { db=new Database(new File(getDataFolder(),getConfig().getString("database.file","impuls.sqlite3"))); }
        catch(Exception e){getLogger().severe("SQLite init failed: "+e.getMessage());getServer().getPluginManager().disablePlugin(this);return;}
        getServer().getPluginManager().registerEvents(this,this);
        PluginCommand c=getCommand("impuls"); if(c!=null){c.setExecutor(this);c.setTabCompleter(this);}
        Bukkit.getScheduler().runTaskTimer(this,this::flightBilling,20L*60,20L*60);
        getLogger().info("ImPulsCore 1.0.0 enabled");
    }
    @Override public void onDisable(){ if(db!=null) db.close(); }

    @EventHandler public void onJoin(PlayerJoinEvent e){db.ensure(e.getPlayer()); syncScoreboards(e.getPlayer());}
    @EventHandler public void onQuit(PlayerQuitEvent e){disableVipCreative(e.getPlayer()); paidFlight.remove(e.getPlayer().getUniqueId()); db.audit(e.getPlayer().getUniqueId(),"quit","player quit");}

    private void syncScoreboards(Player p){
        var sb=Bukkit.getScoreboardManager().getMainScoreboard();
        Objective coins=sb.getObjective("impuls_coins"); if(coins!=null) coins.getScore(p.getName()).setScore(db.coins(p.getUniqueId()));
        Objective def=sb.getObjective("impuls_defender"); if(def!=null) def.getScore(p.getName()).setScore(db.defender(p.getUniqueId()));
    }

    @EventHandler(priority=EventPriority.HIGHEST) public void onDeath(PlayerDeathEvent e){
        Player p=e.getEntity(); UUID u=p.getUniqueId();
        boolean dungeon=p.getScoreboardTags().contains("impuls_dungeon");
        if(!dungeon && db.insured(u)){
            e.setKeepInventory(true); e.getDrops().clear(); e.setKeepLevel(true); e.setDroppedExp(0); db.setInsured(u,false); db.audit(u,"insurance_used",p.getLocation().toString());
            p.sendMessage(ChatColor.AQUA+"[ImPuls] Страховка сработала и сохранит инвентарь. Полис израсходован.");
        }
    }

    @EventHandler public void onMobDeath(EntityDeathEvent e){
        if(!(e.getEntity().getKiller() instanceof Player p))return;
        if(!e.getEntity().getScoreboardTags().contains("impuls_wave"))return;
        int points=e.getEntity().getScoreboardTags().contains("impuls_wave_commander")?5:1;
        int coins=e.getEntity().getScoreboardTags().contains("impuls_wave_commander")?35:8;
        db.addDefender(p.getUniqueId(),points); db.credit(p.getUniqueId(),coins,"wall_defense"); syncScoreboards(p);
        p.sendActionBar(ComponentCompat.text("Защита города +"+points+" | "+coins+" монет"));
    }

    private boolean mayBuild(Player p, Block b){Database.Claim c=db.claimAt(b.getWorld().getName(),b.getX(),b.getY(),b.getZ());return c==null||c.owner().equals(p.getUniqueId())||p.hasPermission("impuls.admin");}
    @EventHandler(ignoreCancelled=true,priority=EventPriority.HIGHEST) public void onCreativePlace(BlockPlaceEvent e){
        Player p=e.getPlayer();Block b=e.getBlockPlaced();
        if(!mayBuild(p,b)){e.setCancelled(true);p.sendMessage(ChatColor.RED+"Этот участок принадлежит другому игроку.");return;}
        if(!vipCreative.contains(p.getUniqueId()))return;
        if(!db.ownsClaimAt(p.getUniqueId(),b.getWorld().getName(),b.getX(),b.getY(),b.getZ(),"VIP")){e.setCancelled(true);p.sendMessage(ChatColor.RED+"VIP Creative работает только внутри твоего VIP-участка.");return;}
        if(CREATIVE_FORBIDDEN.contains(b.getType())){e.setCancelled(true);p.sendMessage(ChatColor.RED+"Этот блок запрещён в VIP Creative.");return;}
        db.addCreativeBlock(b.getWorld().getName(),b.getX(),b.getY(),b.getZ(),p.getUniqueId());
    }
    @EventHandler(ignoreCancelled=true,priority=EventPriority.HIGHEST) public void onBreak(BlockBreakEvent e){
        Block b=e.getBlock();Player p=e.getPlayer();if(!mayBuild(p,b)){e.setCancelled(true);p.sendMessage(ChatColor.RED+"Этот участок защищён.");return;}
        if(vipCreative.contains(p.getUniqueId())&&!db.ownsClaimAt(p.getUniqueId(),b.getWorld().getName(),b.getX(),b.getY(),b.getZ(),"VIP")){e.setCancelled(true);return;}
        if(db.removeCreativeBlock(b.getWorld().getName(),b.getX(),b.getY(),b.getZ())) e.setDropItems(false);
    }
    @EventHandler public void onDrop(PlayerDropItemEvent e){ if(vipCreative.contains(e.getPlayer().getUniqueId())){e.setCancelled(true);e.getPlayer().sendMessage(ChatColor.RED+"Creative-предметы нельзя выбрасывать.");}}
    @EventHandler(ignoreCancelled=true,priority=EventPriority.HIGHEST) public void onContainer(InventoryOpenEvent e){
        if(!(e.getPlayer() instanceof Player p))return;
        if(vipCreative.contains(p.getUniqueId())){e.setCancelled(true);p.sendMessage(ChatColor.RED+"Контейнеры недоступны в VIP Creative.");return;}
        Location l=e.getInventory().getLocation();if(l==null)return;Database.Claim c=db.claimAt(l.getWorld().getName(),l.getBlockX(),l.getBlockY(),l.getBlockZ());if(c!=null&&!c.owner().equals(p.getUniqueId())&&!p.hasPermission("impuls.admin")){e.setCancelled(true);p.sendMessage(ChatColor.RED+"Контейнер защищён участком.");}
    }
    @EventHandler public void onMove(PlayerMoveEvent e){
        Player p=e.getPlayer();if(!vipCreative.contains(p.getUniqueId()))return;Location l=e.getTo();
        if(!p.hasPermission("impuls.vip")||!db.ownsClaimAt(p.getUniqueId(),l.getWorld().getName(),l.getBlockX(),l.getBlockY(),l.getBlockZ(),"VIP")){disableVipCreative(p);p.sendMessage(ChatColor.YELLOW+"Ты вышел за границу VIP-участка: включён Survival.");}
    }
    @EventHandler(ignoreCancelled=true) public void onEntityExplode(EntityExplodeEvent e){e.blockList().removeIf(b->db.claimAt(b.getWorld().getName(),b.getX(),b.getY(),b.getZ())!=null);}
    @EventHandler(ignoreCancelled=true) public void onBlockExplode(BlockExplodeEvent e){e.blockList().removeIf(b->db.claimAt(b.getWorld().getName(),b.getX(),b.getY(),b.getZ())!=null);}
    @EventHandler public void onDamage(EntityDamageEvent e){ if(e.getEntity() instanceof Player p && paidFlight.remove(p.getUniqueId())){p.setAllowFlight(false);p.setFlying(false);} }

    private void enableVipCreative(Player p){
        if(!p.hasPermission("impuls.vip")){p.sendMessage(ChatColor.RED+"Нет VIP-права.");return;}
        if(p.getScoreboardTags().contains("impuls_dungeon")||p.getScoreboardTags().contains("impuls_combat")){p.sendMessage(ChatColor.RED+"Creative запрещён в бою и подземельях.");return;}
        Location l=p.getLocation();if(!db.ownsClaimAt(p.getUniqueId(),l.getWorld().getName(),l.getBlockX(),l.getBlockY(),l.getBlockZ(),"VIP")){p.sendMessage(ChatColor.RED+"Включить Creative можно только на своём VIP-участке.");return;}
        if(vipCreative.add(p.getUniqueId())){survivalInv.put(p.getUniqueId(),cloneInv(p.getInventory().getContents()));p.getInventory().clear();p.setGameMode(GameMode.CREATIVE);db.audit(p.getUniqueId(),"vip_creative_on",p.getLocation().toString());}
    }
    private void disableVipCreative(Player p){
        if(!vipCreative.remove(p.getUniqueId()))return; p.getInventory().clear(); ItemStack[] old=survivalInv.remove(p.getUniqueId()); if(old!=null)p.getInventory().setContents(old);p.setGameMode(GameMode.SURVIVAL);db.audit(p.getUniqueId(),"vip_creative_off",p.getLocation().toString());
    }
    private ItemStack[] cloneInv(ItemStack[] in){ItemStack[] out=new ItemStack[in.length];for(int i=0;i<in.length;i++)out[i]=in[i]==null?null:in[i].clone();return out;}

    private void flightBilling(){
        int cost=getConfig().getInt("economy.vip-flight-cost-per-minute",2);
        for(UUID u:new HashSet<>(paidFlight)){Player p=Bukkit.getPlayer(u);if(p==null){paidFlight.remove(u);continue;}if(p.getScoreboardTags().contains("impuls_combat")||p.getScoreboardTags().contains("impuls_dungeon")||!db.charge(u,cost,"vip_flight")){paidFlight.remove(u);p.setFlying(false);p.setAllowFlight(false);p.sendMessage(ChatColor.YELLOW+"[ImPuls] VIP-полёт отключён.");}else syncScoreboards(p);}
    }

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] a){
        if(!(sender instanceof Player p)){sender.sendMessage("Player-only for this build");return true;} db.ensure(p);
        if(a.length==0||a[0].equalsIgnoreCase("help")){help(p);return true;}
        switch(a[0].toLowerCase(Locale.ROOT)){
            case "status" -> status(p);
            case "insure" -> insure(p);
            case "guild" -> guild(p,Arrays.copyOfRange(a,1,a.length));
            case "claim" -> claim(p,Arrays.copyOfRange(a,1,a.length));
            case "vip" -> vip(p,Arrays.copyOfRange(a,1,a.length));
            case "fly" -> fly(p);
            default -> help(p);
        }
        return true;
    }
    private void help(Player p){p.sendMessage(ChatColor.GOLD+"ImPulsCore: /impuls status | insure | claim ... | guild ... | vip creative | fly");}
    private void status(Player p){Long gid=db.guildId(p.getUniqueId());p.sendMessage(ChatColor.GOLD+"ImPuls §7| монеты: §f"+db.coins(p.getUniqueId())+" §7| защита: §f"+db.defender(p.getUniqueId())+" §7| страховка: §f"+(db.insured(p.getUniqueId())?"да":"нет")+" §7| гильдия: §f"+(gid==null?"—":db.guildName(gid)));}
    private void insure(Player p){if(db.insured(p.getUniqueId())){p.sendMessage(ChatColor.YELLOW+"Полис уже активен.");return;}int cost=getConfig().getInt("economy.insurance-base-cost",250);if(!db.charge(p.getUniqueId(),cost,"insurance")){p.sendMessage(ChatColor.RED+"Недостаточно монет.");return;}db.setInsured(p.getUniqueId(),true);syncScoreboards(p);p.sendMessage(ChatColor.AQUA+"Страховка на одну обычную смерть активирована.");}
    private void claim(Player p,String[] a){
        if(a.length==0){p.sendMessage("/impuls claim buy | vip | info");return;}
        Location l=p.getLocation();int cx=l.getBlockX(),cz=l.getBlockZ();String w=l.getWorld().getName();int dx=Math.abs(cx+688),dz=Math.abs(cz+688);int max=Math.max(dx,dz);
        switch(a[0].toLowerCase(Locale.ROOT)){
            case "buy" -> {if(max<=1064){p.sendMessage(ChatColor.RED+"Обычный участок можно купить только за санитарной зоной внешней стены.");return;}if(db.claimCount(p.getUniqueId(),"NORMAL")>=1){p.sendMessage(ChatColor.YELLOW+"Базовый участок уже есть; расширение будет отдельной механикой.");return;}int size=32,r=size/2,cost=getConfig().getInt("economy.claim-32-cost",500);boolean ok=db.createClaim(p.getUniqueId(),w,cx-r,cx+r-1,l.getWorld().getMinHeight(),l.getWorld().getMaxHeight()-1,cz-r,cz+r-1,"NORMAL",cost);p.sendMessage(ok?ChatColor.GREEN+"Участок 32×32 куплен и защищён.":ChatColor.RED+"Не удалось: проверь деньги и пересечение с чужим участком.");if(ok)syncScoreboards(p);}
            case "vip" -> {if(!p.hasPermission("impuls.vip")){p.sendMessage(ChatColor.RED+"Нужен VIP.");return;}if(max>=930){p.sendMessage(ChatColor.RED+"VIP-участок должен быть внутри городской стены и не вплотную к ней.");return;}if(db.claimCount(p.getUniqueId(),"VIP")>=1){p.sendMessage(ChatColor.YELLOW+"VIP-участок уже зарегистрирован.");return;}int r=48,cost=getConfig().getInt("economy.vip-claim-cost",5000);boolean ok=db.createClaim(p.getUniqueId(),w,cx-r,cx+r-1,Math.max(l.getWorld().getMinHeight(),l.getBlockY()-4),Math.min(l.getWorld().getMaxHeight()-1,l.getBlockY()+50),cz-r,cz+r-1,"VIP",cost);p.sendMessage(ok?ChatColor.GREEN+"VIP-участок 96×96 зарегистрирован.":ChatColor.RED+"Не удалось: территория занята или не хватает монет.");if(ok)syncScoreboards(p);}
            case "info" -> {Database.Claim c=db.claimAt(w,cx,l.getBlockY(),cz);p.sendMessage(c==null?"Здесь нет защищённого участка.":"Участок #"+c.id()+" | "+c.kind()+" | владелец "+c.owner());}
            default -> p.sendMessage("/impuls claim buy | vip | info");
        }
    }

    private void guild(Player p,String[] a){
        if(a.length==0){p.sendMessage("/impuls guild create <name> | invite <player> | accept | leave | deposit <amount> | info");return;}
        UUID u=p.getUniqueId();switch(a[0].toLowerCase(Locale.ROOT)){
            case "create" -> {if(a.length<2){p.sendMessage("Укажи название.");return;}String name=String.join(" ",Arrays.copyOfRange(a,1,a.length));if(name.length()<3||name.length()>24){p.sendMessage("Название 3–24 символа.");return;}int cost=getConfig().getInt("economy.guild-create-cost",1500);p.sendMessage(db.createGuild(u,name,cost)?ChatColor.GREEN+"Гильдия создана.":ChatColor.RED+"Не удалось: проверь монеты, уникальность имени и отсутствие другой гильдии.");syncScoreboards(p);}
            case "invite" -> {if(a.length<2)return;Player t=Bukkit.getPlayerExact(a[1]);p.sendMessage(t!=null&&db.invite(u,t.getUniqueId())?ChatColor.GREEN+"Приглашение отправлено.":ChatColor.RED+"Не удалось пригласить.");if(t!=null)t.sendMessage(ChatColor.GOLD+"Тебя пригласили в гильдию. /impuls guild accept");}
            case "accept" -> p.sendMessage(db.acceptInvite(u)?ChatColor.GREEN+"Ты вступил в гильдию.":ChatColor.RED+"Нет действующего приглашения.");
            case "leave" -> p.sendMessage(db.leaveGuild(u)?ChatColor.YELLOW+"Ты вышел из гильдии.":ChatColor.RED+"Глава не может выйти; сначала передай лидерство (админ-команда следующего этапа).");
            case "deposit" -> {if(a.length<2)return;try{int n=Integer.parseInt(a[1]);p.sendMessage(db.depositGuild(u,n)?ChatColor.GREEN+"Внесено в казну: "+n:ChatColor.RED+"Операция отклонена.");syncScoreboards(p);}catch(NumberFormatException ignored){}}
            case "info" -> {Long g=db.guildId(u);if(g==null)p.sendMessage("Ты не в гильдии.");else p.sendMessage(ChatColor.GOLD+db.guildName(g)+ChatColor.GRAY+" | роль "+db.memberRole(u)+" | казна "+db.guildTreasury(g));}
            default -> p.sendMessage("Неизвестная подкоманда guild.");
        }
    }
    private void vip(Player p,String[] a){if(a.length>0&&a[0].equalsIgnoreCase("creative")){if(vipCreative.contains(p.getUniqueId()))disableVipCreative(p);else enableVipCreative(p);}}
    private void fly(Player p){if(!p.hasPermission("impuls.vip")){p.sendMessage(ChatColor.RED+"Нет VIP-права.");return;}if(p.getScoreboardTags().contains("impuls_dungeon")||p.getScoreboardTags().contains("impuls_combat")){p.sendMessage(ChatColor.RED+"Полёт здесь запрещён.");return;}UUID u=p.getUniqueId();if(paidFlight.remove(u)){p.setFlying(false);p.setAllowFlight(false);p.sendMessage("Полёт выключен.");}else{paidFlight.add(u);p.setAllowFlight(true);p.sendMessage(ChatColor.AQUA+"Платный VIP-полёт включён; оплата раз в минуту.");}}

    @Override public List<String> onTabComplete(CommandSender s,Command c,String a,String[] args){if(args.length==1)return List.of("help","status","insure","claim","guild","vip","fly");if(args.length==2&&args[0].equalsIgnoreCase("guild"))return List.of("create","invite","accept","leave","deposit","info");if(args.length==2&&args[0].equalsIgnoreCase("claim"))return List.of("buy","vip","info");return List.of();}

    private static final class ComponentCompat { static net.kyori.adventure.text.Component text(String s){return net.kyori.adventure.text.Component.text(s);} }
}
