package dev.kozlik.nightterrors;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zoglin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

public final class NightTerrors extends JavaPlugin implements Listener {

    /** How often the behaviour loop runs, in ticks. */
    private static final int STEP = 2;

    /** Runtime state of one living custom mob. */
    private static final class Tracked {
        final Mob mob;
        Rig rig;
        MobKind kind;
        double growth;
        long lastSound;
        long lastAttack;
        long lastLeap;
        long fleeUntil;
        boolean leapHit = true;

        Tracked(Mob mob, MobKind kind) {
            this.mob = mob;
            this.kind = kind;
        }
    }

    private final Map<String, MobKind> kinds = new LinkedHashMap<>();
    private final Map<UUID, Tracked> tracked = new LinkedHashMap<>();
    /** Per player: how far the watcher's stare effect has built up, 0 to 1. */
    private final Map<UUID, Double> stare = new HashMap<>();
    private final Set<UUID> staringNow = new HashSet<>();
    private NamespacedKey kindKey;
    private Map<String, Rig.Def> rigs = new HashMap<>();
    private boolean despawnAtDay;
    private String packUrl = "";
    private byte[] packHash;
    private double viewCos;
    private long tick;

    @Override
    public void onEnable() {
        kindKey = new NamespacedKey(this, "kind");
        saveDefaultConfig();
        rigs = Rig.load(this);
        loadKinds();
        Bukkit.getPluginManager().registerEvents(this, this);
        for (World w : Bukkit.getWorlds()) {
            for (Mob mob : w.getEntitiesByClass(Mob.class)) {
                adopt(mob);
            }
        }
        Bukkit.getScheduler().runTaskTimer(this, this::step, 20L, STEP);
        getLogger().info("Loaded " + kinds.size() + " mob types.");
    }

    private void loadKinds() {
        reloadConfig();
        kinds.clear();
        despawnAtDay = getConfig().getBoolean("settings.despawn-at-day", true);
        packUrl = getConfig().getBoolean("resource-pack.enabled", true) ? getConfig().getString("resource-pack.url", "") : "";
        packHash = parseHex(getConfig().getString("resource-pack.sha1", ""));
        viewCos = Math.cos(Math.toRadians(getConfig().getDouble("settings.view-angle", 65)));
        ConfigurationSection mobs = getConfig().getConfigurationSection("mobs");
        if (mobs != null) {
            for (String id : mobs.getKeys(false)) {
                ConfigurationSection s = mobs.getConfigurationSection(id);
                if (s != null) {
                    kinds.put(id.toLowerCase(Locale.ROOT), MobKind.load(id.toLowerCase(Locale.ROOT), s, getLogger()));
                }
            }
        }
        // Mobs already alive follow the new settings; mobs whose type was deleted stay as they are.
        for (Tracked t : tracked.values()) {
            MobKind k = kinds.get(t.kind.id);
            if (k != null) {
                t.kind = k;
            }
        }
    }

    // ------------------------------------------------------------------ spawning

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onNaturalSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }
        Location loc = e.getLocation();
        World w = loc.getWorld();
        if (w == null) {
            return;
        }
        for (MobKind k : kinds.values()) {
            if (!k.enabled || !k.replaces.contains(e.getEntityType())) {
                continue;
            }
            if (!k.worlds.isEmpty() && !k.worlds.contains(w.getName())) {
                continue;
            }
            if (k.onlyAtNight && w.isDayTime()) {
                continue;
            }
            if (k.surfaceOnly && w.getHighestBlockYAt(loc, HeightMap.MOTION_BLOCKING_NO_LEAVES) > loc.getBlockY()) {
                continue;
            }
            if (ThreadLocalRandom.current().nextDouble() * 100.0 >= k.chancePercent) {
                continue;
            }
            if (countAlive(k, w) >= k.maxAlive) {
                continue;
            }
            e.setCancelled(true);
            Location at = loc.clone();
            Bukkit.getScheduler().runTask(this, () -> spawn(k, at));
            return;
        }
    }

    private int countAlive(MobKind k, World w) {
        int n = 0;
        for (Tracked t : tracked.values()) {
            if (t.kind.id.equals(k.id) && t.mob.isValid() && t.mob.getWorld().equals(w)) {
                n++;
            }
        }
        return n;
    }

    private Mob spawn(MobKind k, Location loc) {
        World w = loc.getWorld();
        if (w == null) {
            return null;
        }
        Entity ent = w.spawnEntity(loc, k.entity, CreatureSpawnEvent.SpawnReason.CUSTOM);
        if (!(ent instanceof Mob mob)) {
            ent.remove();
            getLogger().warning("Mob '" + k.id + "': entity " + k.entity + " is not a mob.");
            return null;
        }
        for (Entity rider : mob.getPassengers()) {
            rider.remove();
        }
        mob.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, k.id);
        if (mob instanceof Ageable a) {
            a.setAdult();
        }
        if (mob instanceof Zoglin z) {
            z.setBaby(false);
        }
        if (k.clearEquipment) {
            EntityEquipment eq = mob.getEquipment();
            if (eq != null) {
                eq.clear();
            }
        }
        setAttr(mob, Attribute.MAX_HEALTH, k.health);
        mob.setHealth(k.health);
        Tracked t = new Tracked(mob, k);
        tracked.put(mob.getUniqueId(), t);
        prepare(t);
        return mob;
    }

    /** Applies everything that has to be redone when a mob is loaded again from disk. */
    private void prepare(Tracked t) {
        Mob mob = t.mob;
        MobKind k = t.kind;
        setAttr(mob, Attribute.MOVEMENT_SPEED, k.speed);
        setAttr(mob, Attribute.ATTACK_DAMAGE, k.damage);
        setAttr(mob, Attribute.FOLLOW_RANGE, k.followRange);
        setAttr(mob, Attribute.SCALE, k.scale);
        mob.setSilent(k.silent);
        Rig.Def rigDef = k.model == null ? null : rigs.get(k.model);
        if (rigDef != null) {
            // The vanilla body only does the walking and fighting; what players see is the rig.
            mob.setInvisible(true);
            EntityEquipment eq = mob.getEquipment();
            if (eq != null) {
                eq.clear();
            }
            if (t.rig != null) {
                t.rig.remove();
            }
            t.rig = new Rig(rigDef, mob, k.modelYawOffset);
        }
        if (k.behavior == MobKind.Behavior.STALKER || k.behavior == MobKind.Behavior.WATCHER) {
            // These two are driven only by this plugin, not by the vanilla brain.
            Bukkit.getMobGoals().removeAllGoals(mob);
            mob.setTarget(null);
        }
    }

    private static void setAttr(Mob mob, Attribute attribute, double value) {
        AttributeInstance inst = mob.getAttribute(attribute);
        if (inst == null) {
            mob.registerAttribute(attribute);
            inst = mob.getAttribute(attribute);
        }
        if (inst != null) {
            inst.setBaseValue(value);
        }
    }

    private void untrack(UUID id) {
        Tracked t = tracked.remove(id);
        if (t != null && t.rig != null) {
            t.rig.remove();
            t.rig = null;
        }
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(tracked.keySet())) {
            untrack(id);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Tracked t = tracked.get(e.getEntity().getUniqueId());
        if (t != null && t.rig != null) {
            // The invisible body would play its death animation for a second; hide the model in smoke instead.
            Location at = t.mob.getLocation().add(0, t.mob.getHeight() / 2.0, 0);
            t.mob.getWorld().spawnParticle(Particle.LARGE_SMOKE, at, 30, 0.4, t.mob.getHeight() / 3.0, 0.4, 0.02);
            untrack(t.mob.getUniqueId());
        }
    }

    private Location lookPoint(Mob mob) {
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isValid() || !target.getWorld().equals(mob.getWorld())) {
            target = nearestPlayer(mob, 40);
        }
        return target == null ? null : target.getEyeLocation();
    }

    private void adopt(Entity entity) {
        if (!(entity instanceof Mob mob) || tracked.containsKey(mob.getUniqueId())) {
            return;
        }
        String id = mob.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
        if (id == null) {
            return;
        }
        MobKind k = kinds.get(id);
        if (k == null) {
            return;
        }
        Tracked t = new Tracked(mob, k);
        tracked.put(mob.getUniqueId(), t);
        prepare(t);
    }

    @EventHandler
    public void onAdd(EntityAddToWorldEvent e) {
        Entity entity = e.getEntity();
        // One tick later the entity is fully in the world and safe to modify.
        Bukkit.getScheduler().runTask(this, () -> {
            if (entity.isValid()) {
                adopt(entity);
            }
        });
    }

    @EventHandler
    public void onRemove(EntityRemoveFromWorldEvent e) {
        untrack(e.getEntity().getUniqueId());
    }

    // ------------------------------------------------------------------ behaviour loop

    private void step() {
        tick += STEP;
        List<Tracked> snapshot = new ArrayList<>(tracked.values());
        for (Tracked t : snapshot) {
            Mob mob = t.mob;
            if (!mob.isValid() || mob.isDead()) {
                untrack(mob.getUniqueId());
                continue;
            }
            if (despawnAtDay && t.kind.onlyAtNight && mob.getWorld().isDayTime()) {
                vanish(mob);
                continue;
            }
            switch (t.kind.behavior) {
                case STALKER -> stalker(t);
                case GROWER -> grower(t);
                case LEAPER -> leaper(t);
                case WATCHER -> watcher(t);
                default -> { }
            }
            if (mob.isValid()) {
                sound(t);
                if (t.rig != null) {
                    t.rig.update(mob, lookPoint(mob));
                }
            }
        }
        for (Tracked t : new ArrayList<>(tracked.values())) {
            if (!t.mob.isValid()) {
                untrack(t.mob.getUniqueId());
            }
        }
        // Players who looked away calm down again.
        for (Iterator<Map.Entry<UUID, Double>> it = stare.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, Double> en = it.next();
            if (!staringNow.contains(en.getKey())) {
                double v = en.getValue() - 0.02;
                if (v <= 0) {
                    it.remove();
                } else {
                    en.setValue(v);
                }
            }
        }
        staringNow.clear();
    }

    // ------------------------------------------------------------------ damage rules

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        // Rain and water must not kill a mob that only borrows the enderman body.
        if (e.getCause() == EntityDamageEvent.DamageCause.DROWNING
                && e.getEntity() instanceof Enderman
                && tracked.containsKey(e.getEntity().getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (e instanceof EntityDamageByEntityEvent by && e.getEntity() instanceof Player victim) {
            Tracked t = tracked.get(by.getDamager().getUniqueId());
            if (t != null && t.kind.knockback > 0) {
                Vector push = victim.getLocation().toVector().subtract(t.mob.getLocation().toVector()).setY(0);
                if (push.lengthSquared() < 0.01) {
                    push = t.mob.getLocation().getDirection().setY(0);
                }
                if (push.lengthSquared() < 0.01) {
                    return;
                }
                Vector v = push.normalize().multiply(0.6 * t.kind.knockback).setY(0.3 + 0.1 * t.kind.knockback);
                // Next tick, so it is added on top of the vanilla knockback instead of being overwritten.
                Bukkit.getScheduler().runTask(this, () -> {
                    if (victim.isValid()) {
                        victim.setVelocity(victim.getVelocity().add(v));
                    }
                });
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(EntityTeleportEvent e) {
        // Stops the enderman body from blinking away when wet or hit.
        if (tracked.containsKey(e.getEntity().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    private void sound(Tracked t) {
        MobKind k = t.kind;
        if (k.soundKey == null || k.soundKey.isEmpty() || tick - t.lastSound < k.soundInterval) {
            return;
        }
        t.lastSound = tick;
        Location at = t.mob.getLocation();
        double r2 = k.soundRadius * k.soundRadius;
        for (Player p : t.mob.getWorld().getPlayers()) {
            double d2 = p.getLocation().distanceSquared(at);
            if (d2 > r2) {
                continue;
            }
            double closeness = 1.0 - Math.sqrt(d2) / k.soundRadius;
            float volume = (float) (k.soundVolume * (0.35 + 0.65 * closeness));
            // Played at the listener so the volume follows the distance we computed.
            p.playSound(p.getLocation(), k.soundKey, SoundCategory.HOSTILE, volume, k.soundPitch);
        }
    }

    private void stalker(Tracked t) {
        Mob mob = t.mob;
        MobKind k = t.kind;
        Pathfinder pf = mob.getPathfinder();
        Player target = nearestPlayer(mob, k.range);
        if (target == null) {
            pf.stopPathfinding();
            return;
        }
        mob.lookAt(target);
        double dist = mob.getLocation().distance(target.getLocation());
        if (isWatched(mob)) {
            if (dist < k.fleeDistance) {
                if (tick >= t.fleeUntil) {
                    t.fleeUntil = tick + 10;
                    flee(mob, target, k.fleeSpeed);
                }
            } else if (tick >= t.fleeUntil) {
                pf.stopPathfinding();
            }
            return;
        }
        t.fleeUntil = 0;
        if (dist > k.attackReach) {
            pf.moveTo(target, k.approachSpeed);
        } else {
            pf.stopPathfinding();
            if (tick - t.lastAttack >= 20) {
                t.lastAttack = tick;
                mob.swingMainHand();
                mob.attack(target);
            }
        }
    }

    private void flee(Mob mob, Player from, double speed) {
        Location here = mob.getLocation();
        Vector away = here.toVector().subtract(from.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 0.01) {
            away = new Vector(1, 0, 0);
        }
        away.normalize();
        World w = mob.getWorld();
        // Straight away first, then increasingly sideways. If nothing works the mob is cornered.
        double[] angles = {0, 40, -40, 80, -80};
        for (double angle : angles) {
            Vector dir = away.clone().rotateAroundY(Math.toRadians(angle)).multiply(12);
            Location dest = here.clone().add(dir);
            int top = w.getHighestBlockYAt(dest, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            if (Math.abs(top - here.getY()) <= 8) {
                dest.setY(top);
            }
            if (mob.getPathfinder().moveTo(dest, speed)) {
                return;
            }
        }
    }

    private void grower(Tracked t) {
        if (tick % 10 != 0) {
            return;
        }
        Mob mob = t.mob;
        MobKind k = t.kind;
        boolean chasing = mob.getTarget() instanceof Player;
        double perStep = (10.0 / 20.0) / k.growSeconds;
        t.growth += chasing ? perStep : -perStep / 2.0;
        t.growth = Math.max(0.0, Math.min(1.0, t.growth));
        double scale = k.scale + (k.maxScale - k.scale) * t.growth;
        setAttr(mob, Attribute.SCALE, scale);
        // A big body needs to step over blocks, otherwise it keeps getting stuck on terrain.
        setAttr(mob, Attribute.STEP_HEIGHT, Math.max(0.6, Math.min(3.0, 0.6 * scale)));
        setAttr(mob, Attribute.MOVEMENT_SPEED, k.speed * (1.0 + k.speedBonus * t.growth));
        setAttr(mob, Attribute.ATTACK_DAMAGE, k.damage * (1.0 + k.damageBonus * t.growth));
    }

    private void leaper(Tracked t) {
        Mob mob = t.mob;
        MobKind k = t.kind;
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isValid() || !target.getWorld().equals(mob.getWorld())) {
            return;
        }
        Location face = target.getEyeLocation();
        // Mid-air: the moment it reaches the face it bites and blinds for a second.
        if (!t.leapHit && tick - t.lastLeap <= 20) {
            if (mob.getLocation().add(0, mob.getHeight() / 2.0, 0).distanceSquared(face) <= 1.8 * 1.8) {
                t.leapHit = true;
                mob.attack(target);
                if (k.faceBlindTicks > 0) {
                    target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, k.faceBlindTicks, 0, false, false, false));
                }
            }
            return;
        }
        if (tick - t.lastLeap < k.leapCooldown || !mob.isOnGround()) {
            return;
        }
        Vector dir = face.toVector().subtract(mob.getLocation().toVector());
        double dy = dir.getY();
        dir.setY(0);
        double dist = dir.length();
        if (dist < k.leapMin || dist > k.leapMax || dist < 0.1 || !mob.hasLineOfSight(target)) {
            return;
        }
        t.lastLeap = tick;
        t.leapHit = false;
        // Throw arc: flight time from the horizontal speed, then the upward speed that
        // reaches eye height in that time under gravity (0.08 per tick, half of it per tick squared).
        double h = Math.max(0.3, k.leapHorizontal);
        double time = dist / (h * 0.8);
        double vy = (dy + 0.04 * time * time) / time;
        vy = Math.max(k.leapVertical, Math.min(1.1, vy));
        mob.setVelocity(dir.normalize().multiply(h).setY(vy));
    }

    private void watcher(Tracked t) {
        Mob mob = t.mob;
        MobKind k = t.kind;
        mob.getPathfinder().stopPathfinding();
        Player target = nearestPlayer(mob, k.range);
        if (target == null) {
            return;
        }
        mob.lookAt(target);
        if (mob.getLocation().distance(target.getLocation()) < k.vanishDistance) {
            vanish(mob);
            return;
        }
        if (k.stareSeconds <= 0) {
            return;
        }
        // The effect hits the player the watcher is staring at, whether or not they look back.
        // Breaking line of sight (a wall) or leaving the effect range stops it.
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (Player p : List.of(target)) {
            if (p.getLocation().distanceSquared(mob.getLocation()) > k.effectRange * k.effectRange
                    || !mob.hasLineOfSight(p)) {
                continue;
            }
            UUID id = p.getUniqueId();
            if (!staringNow.add(id)) {
                continue; // already handled by another watcher this step
            }
            double level = Math.min(1.0, stare.getOrDefault(id, 0.0) + STEP / (k.stareSeconds * 20.0));
            stare.put(id, level);
            // Screen flicker: short blindness flashes, more often and longer as the level rises.
            if (rnd.nextDouble() < 0.08 + 0.45 * level) {
                int ticks = 4 + (int) (14 * level);
                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, ticks, 0, false, false, false));
            }
            if (tick % 10 == 0 && k.stareSound != null && !k.stareSound.isEmpty()) {
                p.playSound(p.getLocation(), k.stareSound, SoundCategory.HOSTILE,
                        (float) (0.1 + 1.4 * level), (float) (0.5 + 0.7 * level));
            }
            if (level >= 1.0) {
                stare.remove(id);
                if (k.stareScream != null && !k.stareScream.isEmpty()) {
                    p.playSound(p.getLocation(), k.stareScream, SoundCategory.HOSTILE, 1.5f, 1.0f);
                }
                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 50, 0, false, false, false));
                if (k.stareDamage > 0 && p.getGameMode() != GameMode.CREATIVE) {
                    p.damage(k.stareDamage);
                }
                vanish(mob);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ resource pack

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (packUrl == null || packUrl.isEmpty()) {
            return;
        }
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) {
                return;
            }
            if (packHash != null) {
                p.setResourcePack(packUrl, packHash);
            } else {
                p.setResourcePack(packUrl);
            }
        }, 20L);
    }

    /** Turns a 40-character SHA-1 text into bytes; anything else means "no hash". */
    private static byte[] parseHex(String hex) {
        if (hex == null || !hex.matches("[0-9a-fA-F]{40}")) {
            return null;
        }
        byte[] out = new byte[20];
        for (int i = 0; i < 20; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean counts(Player p) {
        return p.isValid() && !p.isDead() && p.getGameMode() != GameMode.SPECTATOR;
    }

    private Player nearestPlayer(Mob mob, double range) {
        Player best = null;
        double bestD2 = range * range;
        Location at = mob.getLocation();
        for (Player p : mob.getWorld().getPlayers()) {
            if (!counts(p)) {
                continue;
            }
            double d2 = p.getLocation().distanceSquared(at);
            if (d2 <= bestD2) {
                bestD2 = d2;
                best = p;
            }
        }
        return best;
    }

    /** True when the player has the mob inside their view cone with a clear line of sight. */
    private boolean sees(Player p, Mob mob) {
        Location eye = p.getEyeLocation();
        Vector to = mob.getEyeLocation().toVector().subtract(eye.toVector());
        double d2 = to.lengthSquared();
        if (d2 > 80 * 80) {
            return false;
        }
        if (d2 < 0.01) {
            return true;
        }
        return eye.getDirection().dot(to.normalize()) > viewCos && p.hasLineOfSight(mob);
    }

    /** True when at least one player sees the mob. */
    private boolean isWatched(Mob mob) {
        for (Player p : mob.getWorld().getPlayers()) {
            if (counts(p) && sees(p, mob)) {
                return true;
            }
        }
        return false;
    }

    private void vanish(Mob mob) {
        Location at = mob.getLocation().add(0, mob.getHeight() / 2.0, 0);
        World w = mob.getWorld();
        w.spawnParticle(Particle.LARGE_SMOKE, at, 40, 0.4, mob.getHeight() / 3.0, 0.4, 0.02);
        w.playSound(at, "minecraft:entity.enderman.teleport", 0.7f, 0.5f);
        untrack(mob.getUniqueId());
        mob.remove();
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("/nt spawn <mob> | kill <mob> | list | killall | reload");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                loadKinds();
                sender.sendMessage("NightTerrors: config reloaded, " + kinds.size() + " mob types.");
            }
            case "list" -> {
                sender.sendMessage("Mob types: " + String.join(", ", kinds.keySet()));
                sender.sendMessage("Alive now: " + tracked.size());
            }
            case "killall" -> {
                int n = 0;
                for (Tracked t : new ArrayList<>(tracked.values())) {
                    untrack(t.mob.getUniqueId());
                    t.mob.remove();
                    n++;
                }
                sender.sendMessage("NightTerrors: removed " + n + " mobs.");
            }
            case "kill" -> {
                String id = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
                int n = 0;
                for (Tracked t : new ArrayList<>(tracked.values())) {
                    if (t.kind.id.equals(id)) {
                        untrack(t.mob.getUniqueId());
                        t.mob.remove();
                        n++;
                    }
                }
                sender.sendMessage("NightTerrors: removed " + n + " x " + id + ".");
            }
            case "spawn" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Only a player can use this.");
                    return true;
                }
                MobKind k = args.length > 1 ? kinds.get(args[1].toLowerCase(Locale.ROOT)) : null;
                if (k == null) {
                    sender.sendMessage("Unknown mob. Available: " + String.join(", ", kinds.keySet()));
                    return true;
                }
                // Ten blocks in front of the player, on the ground.
                Vector dir = p.getLocation().getDirection().setY(0);
                if (dir.lengthSquared() < 0.01) {
                    dir = new Vector(1, 0, 0);
                }
                Location loc = p.getLocation().add(dir.normalize().multiply(10));
                int top = p.getWorld().getHighestBlockYAt(loc, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
                if (Math.abs(top - loc.getY()) <= 10) {
                    loc.setY(top);
                }
                Mob mob = spawn(k, loc);
                sender.sendMessage(mob != null ? "Spawned " + k.id + "." : "Spawn failed, see console.");
            }
            default -> sender.sendMessage("/nt spawn <mob> | kill <mob> | list | killall | reload");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("spawn", "kill", "list", "killall", "reload")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("spawn") || args[0].equalsIgnoreCase("kill"))) {
            for (String s : kinds.keySet()) {
                if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
