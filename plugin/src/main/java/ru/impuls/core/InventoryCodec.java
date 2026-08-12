package ru.impuls.core;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import java.io.*;
import java.util.Base64;
public final class InventoryCodec {
    private InventoryCodec(){}
    public static String encode(ItemStack[] items){try{ByteArrayOutputStream b=new ByteArrayOutputStream();try(BukkitObjectOutputStream o=new BukkitObjectOutputStream(b)){o.writeInt(items.length);for(ItemStack i:items)o.writeObject(i);}return Base64.getEncoder().encodeToString(b.toByteArray());}catch(IOException e){throw new RuntimeException(e);}}
    public static ItemStack[] decode(String data){try{byte[] raw=Base64.getDecoder().decode(data);try(BukkitObjectInputStream in=new BukkitObjectInputStream(new ByteArrayInputStream(raw))){int n=in.readInt();ItemStack[] a=new ItemStack[n];for(int i=0;i<n;i++)a[i]=(ItemStack)in.readObject();return a;}}catch(Exception e){throw new RuntimeException(e);}}
}
