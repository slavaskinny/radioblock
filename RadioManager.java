package dev.radioblock;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Хранит все рации и снимок позиций игроков (безопасно читается из потока голосового чата). */
public final class RadioManager {

    public enum Mode { LISTEN, TRANSMIT }

    public record Key(UUID world, int x, int y, int z) {}

    public record PlayerPos(UUID world, double x, double y, double z) {}

    public static final class Radio {
        public final Key key;
        public volatile Mode mode;
        public volatile int frequency;
        public volatile boolean antenna;

        Radio(Key key, Mode mode) {
            this.key = key;
            this.mode = mode;
        }
    }

    private static final Set<Material> COPPER = new HashSet<>();

    static {
        for (String n : List.of("COPPER_BLOCK", "EXPOSED_COPPER", "WEATHERED_COPPER", "OXIDIZED_COPPER",
                "WAXED_COPPER_BLOCK", "WAXED_EXPOSED_COPPER", "WAXED_WEATHERED_COPPER", "WAXED_OXIDIZED_COPPER")) {
            Material m = Material.matchMaterial(n);
            if (m != null) COPPER.add(m);
        }
    }

    public static boolean isCopperBlock(Material m) {
        return COPPER.contains(m);
    }

    public static boolean isRod(Material m) {
        return m.name().endsWith("LIGHTNING_ROD");
    }

    private final File file;
    public final Map<Key, Radio> radios = new ConcurrentHashMap<>();
    public final Map<UUID, PlayerPos> positions = new ConcurrentHashMap<>();

    public volatile boolean requireAntenna = true;
    public volatile double transmitRange = 3.0;
    public volatile double listenRange = 12.0;

    public RadioManager(File file) {
        this.file = file;
    }

    public static Key keyOf(Block b) {
        return new Key(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
    }

    public Radio get(Block b) {
        return radios.get(keyOf(b));
    }

    public Radio register(Block b) {
        Radio r = new Radio(keyOf(b), Mode.LISTEN);
        radios.put(r.key, r);
        refresh(r, b);
        save();
        return r;
    }

    public void unregister(Block b) {
        if (radios.remove(keyOf(b)) != null) save();
    }

    public boolean isActive(Radio r) {
        return r.frequency > 0 && (!requireAntenna || r.antenna);
    }

    /** Обновить антенну и частоту рации (только из основного потока). */
    private void refresh(Radio r, Block b) {
        r.antenna = isRod(b.getRelative(BlockFace.UP).getType());
        int power = 0;
        // Верх исключаем: громоотвод при ударе молнии сам даёт сигнал 15, это не должно менять волну.
        BlockFace[] faces = {BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (BlockFace f : faces) {
            power = Math.max(power, b.getBlockPower(f));
        }
        r.frequency = Math.min(15, power);
    }

    /** Вызывается из планировщика каждые несколько тиков. */
    public void tick() {
        boolean changed = false;
        Iterator<Radio> it = radios.values().iterator();
        while (it.hasNext()) {
            Radio r = it.next();
            World w = Bukkit.getWorld(r.key.world());
            if (w == null) continue;
            if (!w.isChunkLoaded(r.key.x() >> 4, r.key.z() >> 4)) continue;
            Block b = w.getBlockAt(r.key.x(), r.key.y(), r.key.z());
            if (!isCopperBlock(b.getType())) {
                radios.remove(r.key);
                changed = true;
                continue;
            }
            refresh(r, b);
        }
        if (changed) save();
    }

    private static double dist2(PlayerPos p, Key k) {
        double dx = p.x() - (k.x() + 0.5);
        double dy = p.y() - (k.y() + 0.5);
        double dz = p.z() - (k.z() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    /** Ближайшая активная рация в режиме ПЕРЕДАЧА рядом с игроком. */
    public Radio nearestTransmitter(PlayerPos p) {
        Radio best = null;
        double bestD = transmitRange * transmitRange;
        for (Radio r : radios.values()) {
            if (r.mode != Mode.TRANSMIT || !isActive(r) || !r.key.world().equals(p.world())) continue;
            double d = dist2(p, r.key);
            if (d <= bestD) {
                bestD = d;
                best = r;
            }
        }
        return best;
    }

    /** Ближайшая активная рация в режиме СЛУШАТЬ на нужной частоте рядом с игроком. */
    public Radio nearestListener(PlayerPos p, int frequency) {
        Radio best = null;
        double bestD = listenRange * listenRange;
        for (Radio r : radios.values()) {
            if (r.mode != Mode.LISTEN || !isActive(r) || r.frequency != frequency
                    || !r.key.world().equals(p.world())) continue;
            double d = dist2(p, r.key);
            if (d <= bestD) {
                bestD = d;
                best = r;
            }
        }
        return best;
    }

    public void load() {
        radios.clear();
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String s : yml.getStringList("radios")) {
            try {
                String[] p = s.split(";");
                Key k = new Key(UUID.fromString(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                        Integer.parseInt(p[3]));
                radios.put(k, new Radio(k, Mode.valueOf(p[4])));
            } catch (Exception ignored) {
                // битая строка - пропускаем
            }
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        List<String> out = new ArrayList<>();
        for (Radio r : radios.values()) {
            out.add(r.key.world() + ";" + r.key.x() + ";" + r.key.y() + ";" + r.key.z() + ";" + r.mode.name());
        }
        yml.set("radios", out);
        try {
            file.getParentFile().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            Bukkit.getLogger().warning("[RadioBlock] Не удалось сохранить radios.yml: " + e.getMessage());
        }
    }
}
