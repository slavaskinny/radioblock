package dev.radioblock;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class RadioPlugin extends JavaPlugin implements Listener {

    private RadioManager manager;
    private NamespacedKey itemKey;
    private NamespacedKey recipeKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "radio_item");
        recipeKey = new NamespacedKey(this, "radio");

        manager = new RadioManager(new java.io.File(getDataFolder(), "radios.yml"));
        applyConfig();
        manager.load();

        BukkitVoicechatService service = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            getLogger().severe("Simple Voice Chat не найден! Установите плагин voicechat.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        service.registerPlugin(new RadioVoicePlugin(manager));

        Bukkit.getPluginManager().registerEvents(this, this);
        registerRecipe();

        // Позиции игроков для голосового потока - каждые 4 тика
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Location l = p.getLocation();
                if (l.getWorld() == null) continue;
                manager.positions.put(p.getUniqueId(),
                        new RadioManager.PlayerPos(l.getWorld().getUID(), l.getX(), l.getY() + 1.0, l.getZ()));
            }
        }, 20L, 4L);

        // Антенна и частота (редстоун) - каждые 5 тиков
        Bukkit.getScheduler().runTaskTimer(this, manager::tick, 20L, 5L);

        getLogger().info("RadioBlock включён. Рации загружено: " + manager.radios.size());
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.save();
        if (recipeKey != null) Bukkit.removeRecipe(recipeKey);
    }

    private void applyConfig() {
        reloadConfig();
        manager.transmitRange = getConfig().getDouble("transmit-range", 3.0);
        manager.listenRange = getConfig().getDouble("listen-range", 12.0);
        manager.requireAntenna = getConfig().getBoolean("require-antenna", true);
    }

    // ------------------------------------------------------------------ предмет и рецепт

    public ItemStack createRadioItem(int amount) {
        ItemStack item = new ItemStack(Material.COPPER_BLOCK, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Рация");
        meta.setLore(List.of(
                ChatColor.GRAY + "Поставьте громоотвод сверху - это антенна.",
                ChatColor.GRAY + "Волна = сила сигнала редстоуна (1-15).",
                ChatColor.GRAY + "ПКМ пустой рукой - слушать / передавать."));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isRadioItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    private void registerRecipe() {
        Bukkit.removeRecipe(recipeKey);
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createRadioItem(1));
        recipe.shape(" I ", "RCR", " N ");
        recipe.setIngredient('I', Material.IRON_INGOT);
        recipe.setIngredient('R', Material.REDSTONE);
        recipe.setIngredient('C', Material.COPPER_BLOCK);
        recipe.setIngredient('N', Material.NOTE_BLOCK);
        Bukkit.addRecipe(recipe);
    }

    // Рация не должна использоваться как обычный медный блок в чужих рецептах
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (e.getRecipe() == null) return;
        if (e.getRecipe() instanceof ShapedRecipe sr && sr.getKey().equals(recipeKey)) return;
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (isRadioItem(it)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ блок

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (!isRadioItem(e.getItemInHand())) return;
        manager.register(e.getBlockPlaced());
        actionBar(e.getPlayer(), ChatColor.GOLD + "Рация установлена. " + ChatColor.GRAY
                + "Поставьте громоотвод сверху и подайте редстоун-сигнал.");
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (manager.get(b) == null) return;
        manager.unregister(b);
        e.setDropItems(false);
        if (e.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE) {
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), createRadioItem(1));
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> manager.get(b) != null);
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> manager.get(b) != null);
    }

    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (manager.get(b) != null) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (manager.get(b) != null) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        manager.positions.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        RadioManager.Radio r = manager.get(b);
        if (r == null) return;

        Player p = e.getPlayer();
        // Если в руке предмет - не мешаем обычному взаимодействию (например, поставить громоотвод)
        if (e.getItem() != null && e.getItem().getType() != Material.AIR) return;
        if (!p.hasPermission("radio.use")) return;

        e.setCancelled(true);

        if (!p.isSneaking()) {
            r.mode = (r.mode == RadioManager.Mode.LISTEN) ? RadioManager.Mode.TRANSMIT : RadioManager.Mode.LISTEN;
            manager.save();
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, r.mode == RadioManager.Mode.TRANSMIT ? 1.6f : 1.0f);
        }
        actionBar(p, statusText(r));
    }

    private String statusText(RadioManager.Radio r) {
        String mode = (r.mode == RadioManager.Mode.TRANSMIT)
                ? ChatColor.RED + "ПЕРЕДАЧА" : ChatColor.GREEN + "ПРИЁМ";
        String wave = r.frequency > 0 ? ChatColor.AQUA + "" + r.frequency
                : ChatColor.DARK_GRAY + "нет сигнала";
        String ant = r.antenna ? ChatColor.GREEN + "есть" : ChatColor.RED + "нет";
        return ChatColor.GOLD + "Рация: " + mode + ChatColor.GRAY + " | Волна: " + wave
                + ChatColor.GRAY + " | Антенна: " + ant;
    }

    private void actionBar(Player p, String text) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text));
    }

    // ------------------------------------------------------------------ команды

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("radio.admin")) {
            sender.sendMessage(ChatColor.RED + "Нет прав.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "/radio give [игрок] [кол-во] | /radio reload");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                applyConfig();
                sender.sendMessage(ChatColor.GREEN + "Конфиг перезагружен.");
            }
            case "give" -> {
                Player target = null;
                if (args.length >= 2) target = Bukkit.getPlayerExact(args[1]);
                else if (sender instanceof Player pl) target = pl;
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Игрок не найден.");
                    return true;
                }
                int amount = 1;
                if (args.length >= 3) {
                    try {
                        amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(ChatColor.RED + "Неверное количество.");
                        return true;
                    }
                }
                target.getInventory().addItem(createRadioItem(amount))
                        .values().forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
                sender.sendMessage(ChatColor.GREEN + "Выдано: рация x" + amount + " -> " + target.getName());
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "/radio give [игрок] [кол-во] | /radio reload");
        }
        return true;
    }
}
