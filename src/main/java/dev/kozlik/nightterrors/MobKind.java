package dev.kozlik.nightterrors;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

/** One custom mob definition, read from config.yml. */
final class MobKind {

    enum Behavior { STALKER, GROWER, LEAPER, WATCHER, HUNTER, BURROWER, MIMIC, FLYER, NONE }

    final String id;
    boolean enabled;
    EntityType entity;
    final Set<EntityType> replaces = EnumSet.noneOf(EntityType.class);
    double chancePercent;
    final Set<String> worlds = new HashSet<>();
    boolean onlyAtNight;
    boolean surfaceOnly;
    int maxAlive;
    double health;
    double damage;
    double speed;
    double scale;
    double followRange;
    boolean silent;
    boolean clearEquipment;
    double knockback;
    String model;
    float modelYawOffset;
    boolean persistent;
    boolean sleepsByDay;
    double wakeRange;
    int groupSize;
    int xp;
    boolean soundOnlyMoving;
    double dodgeChance;
    boolean steam;
    double modelScale;
    boolean diesInDaylight;

    String soundKey;
    double soundRadius;
    int soundInterval;
    float soundVolume;
    float soundPitch;

    Behavior behavior = Behavior.NONE;
    // STALKER
    double range;
    double approachSpeed;
    double fleeSpeed;
    double fleeDistance;
    double attackReach;
    // GROWER
    double maxScale;
    double growSeconds;
    double speedBonus;
    double damageBonus;
    // LEAPER
    double leapMin;
    double leapMax;
    int leapCooldown;
    double leapHorizontal;
    double leapVertical;
    int faceBlindTicks;
    // WATCHER
    double vanishDistance;
    double stareSeconds;
    double stareDamage;
    double effectRange;
    String stareSound;
    String stareScream;
    // HUNTER
    boolean throughWalls;
    int attackCooldown;
    boolean breaksBlocks;
    final Set<String> extraBreakable = new HashSet<>();
    // BURROWER
    double triggerRange;
    int activeTicks;
    int cooldownTicks;
    double depth;
    double launch;
    String eruptSound;
    double tunnelSpeed;
    // MIMIC
    String revealModel;
    boolean climbs;
    // FLYER
    double flySpeed;
    int liftLevel;
    double liftHeight;

    private MobKind(String id) {
        this.id = id;
    }

    static MobKind load(String id, ConfigurationSection s, Logger log) {
        MobKind k = new MobKind(id);
        k.enabled = s.getBoolean("enabled", true);
        k.entity = parseType(s.getString("entity", "ZOMBIE"));
        if (k.entity == null) {
            log.warning("Mob '" + id + "': unknown entity '" + s.getString("entity") + "', mob disabled.");
            k.enabled = false;
            k.entity = EntityType.ZOMBIE;
        }
        for (String name : s.getStringList("replaces")) {
            EntityType t = parseType(name);
            if (t == null) {
                log.warning("Mob '" + id + "': unknown entity in replaces: '" + name + "'");
            } else {
                k.replaces.add(t);
            }
        }
        k.chancePercent = s.getDouble("chance-percent", 1.0);
        k.worlds.addAll(s.getStringList("worlds"));
        k.onlyAtNight = s.getBoolean("only-at-night", true);
        k.surfaceOnly = s.getBoolean("surface-only", false);
        k.maxAlive = s.getInt("max-alive", 4);
        k.health = s.getDouble("health", 20);
        k.damage = s.getDouble("damage", 4);
        k.speed = s.getDouble("speed", 0.25);
        k.scale = s.getDouble("scale", 1.0);
        k.followRange = s.getDouble("follow-range", 32);
        k.silent = s.getBoolean("silent", true);
        k.clearEquipment = s.getBoolean("clear-equipment", false);
        k.knockback = s.getDouble("knockback", 0);
        k.model = s.getString("model", "");
        k.modelYawOffset = (float) s.getDouble("model-yaw-offset", 0);

        k.persistent = s.getBoolean("persistent", false);
        k.sleepsByDay = s.getBoolean("sleeps-by-day", false);
        k.wakeRange = s.getDouble("wake-range", 14);
        k.groupSize = Math.max(1, s.getInt("group-size", 1));
        k.xp = s.getInt("xp", -1);
        k.dodgeChance = s.getDouble("dodge-projectiles", 0.0);
        k.steam = s.getBoolean("steam", false);
        k.modelScale = s.getDouble("model-scale", 1.0);
        k.diesInDaylight = s.getBoolean("dies-in-daylight", false);
        k.soundOnlyMoving = s.getBoolean("sound.only-moving", false);

        k.soundKey = s.getString("sound.key", "");
        k.soundRadius = s.getDouble("sound.radius", 16);
        k.soundInterval = Math.max(2, s.getInt("sound.interval-ticks", 60));
        k.soundVolume = (float) s.getDouble("sound.volume", 1.0);
        k.soundPitch = (float) s.getDouble("sound.pitch", 1.0);

        String type = s.getString("behavior.type", "NONE");
        try {
            k.behavior = Behavior.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            log.warning("Mob '" + id + "': unknown behavior.type '" + type + "', using NONE.");
        }
        k.range = s.getDouble("behavior.range", 48);
        k.approachSpeed = s.getDouble("behavior.approach-speed", 1.6);
        k.fleeSpeed = s.getDouble("behavior.flee-speed", 2.5);
        k.fleeDistance = s.getDouble("behavior.flee-distance", 9);
        k.attackReach = s.getDouble("behavior.attack-reach", 2.5);
        k.maxScale = s.getDouble("behavior.max-scale", 4.0);
        k.growSeconds = Math.max(1, s.getDouble("behavior.grow-seconds", 25));
        k.speedBonus = s.getDouble("behavior.speed-bonus", 0.5);
        k.damageBonus = s.getDouble("behavior.damage-bonus", 1.0);
        k.leapMin = s.getDouble("behavior.min-distance", 3);
        k.leapMax = s.getDouble("behavior.max-distance", 8);
        k.leapCooldown = s.getInt("behavior.cooldown-ticks", 60);
        k.leapHorizontal = s.getDouble("behavior.horizontal", 1.0);
        k.leapVertical = s.getDouble("behavior.vertical", 0.35);
        k.faceBlindTicks = s.getInt("behavior.face-blind-ticks", 30);
        k.effectRange = s.getDouble("behavior.effect-range", 24);
        k.stareDamage = s.getDouble("behavior.stare-damage", 6);
        k.stareSound = s.getString("behavior.stare-sound", "nightterrors:static");
        k.stareScream = s.getString("behavior.stare-scream", "nightterrors:scream");
        k.vanishDistance = s.getDouble("behavior.vanish-distance", 7);
        k.stareSeconds = s.getDouble("behavior.stare-seconds", 10);
        k.throughWalls = s.getBoolean("behavior.through-walls", false);
        k.attackCooldown = Math.max(2, s.getInt("behavior.attack-cooldown-ticks", 20));
        k.breaksBlocks = s.getBoolean("behavior.breaks-blocks", false);
        for (String name : s.getStringList("behavior.extra-breakable")) {
            k.extraBreakable.add(name.trim().toUpperCase(Locale.ROOT));
        }
        k.triggerRange = s.getDouble("behavior.trigger-range", 3.2);
        k.activeTicks = (int) (s.getDouble("behavior.active-seconds", 7) * 20);
        k.cooldownTicks = (int) (s.getDouble("behavior.cooldown-seconds", 12) * 20);
        k.depth = s.getDouble("behavior.depth", 2.4);
        k.launch = s.getDouble("behavior.launch", 0.75);
        k.eruptSound = s.getString("behavior.erupt-sound", "");
        k.revealModel = s.getString("behavior.reveal-model", "");
        k.tunnelSpeed = s.getDouble("behavior.tunnel-speed", 0.0);
        k.climbs = s.getBoolean("behavior.climbs", false);
        k.flySpeed = s.getDouble("behavior.fly-speed", 0.8);
        k.liftLevel = Math.max(0, s.getInt("behavior.lift-level", 1));
        k.liftHeight = s.getDouble("behavior.lift-height", 40);
        return k;
    }

    private static EntityType parseType(String name) {
        if (name == null) {
            return null;
        }
        try {
            return EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
