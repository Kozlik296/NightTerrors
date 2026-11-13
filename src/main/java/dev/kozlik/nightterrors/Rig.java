package dev.kozlik.nightterrors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The visible body of a custom mob: a set of item displays (one per body part) that follow
 * the invisible vanilla mob and are posed every step. Part shapes come from the resource pack,
 * part positions and animation settings from rigs.json.
 */
final class Rig {

    record Part(String model, float px, float py, float pz, char axis, float swing, float offset,
                float idle, float idleSpeed, float baseYaw, boolean look, boolean glow) { }

    record Def(float scale, float stride, float bob, List<Part> parts) { }

    static Map<String, Def> load(Plugin plugin) {
        Map<String, Def> out = new HashMap<>();
        InputStream in = plugin.getResource("rigs.json");
        if (in == null) {
            return out;
        }
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                List<Part> parts = new ArrayList<>();
                for (JsonElement pe : o.getAsJsonArray("parts")) {
                    JsonObject p = pe.getAsJsonObject();
                    JsonArray piv = p.getAsJsonArray("pivot");
                    parts.add(new Part(p.get("model").getAsString(),
                            piv.get(0).getAsFloat(), piv.get(1).getAsFloat(), piv.get(2).getAsFloat(),
                            p.get("axis").getAsString().charAt(0), p.get("swing").getAsFloat(),
                            p.get("offset").getAsFloat(), p.get("idle").getAsFloat(), p.has("ispeed") ? p.get("ispeed").getAsFloat() : 0.12f, p.get("yaw").getAsFloat(),
                            p.get("look").getAsBoolean(), p.get("glow").getAsBoolean()));
                }
                out.put(e.getKey(), new Def(o.get("scale").getAsFloat(), o.get("stride").getAsFloat(),
                        o.get("bob").getAsFloat(), parts));
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not read rigs.json: " + ex);
        }
        return out;
    }

    private final Def def;
    private final float yawOffset;
    private final List<ItemDisplay> displays = new ArrayList<>();
    private double lastX;
    private double lastZ;
    private float yaw;
    private float phase;
    private float amp;
    private long time;
    private float headYaw;
    private double lastY;
    private boolean climbing;
    private float climbPose;
    private float headPitch;
    private float strike;

    Rig(Def def, Mob mob, float yawOffset) {
        this.def = def;
        this.yawOffset = yawOffset;
        Location loc = mob.getLocation();
        this.lastX = loc.getX();
        this.lastZ = loc.getZ();
        this.lastY = loc.getY();
        this.yaw = loc.getYaw();
        Location at = new Location(loc.getWorld(), loc.getX(), loc.getY(), loc.getZ(), yaw + yawOffset, 0f);
        for (Part p : def.parts()) {
            ItemDisplay d = loc.getWorld().spawn(at, ItemDisplay.class, e -> {
                e.setPersistent(false);
                ItemStack item = new ItemStack(Material.PAPER);
                ItemMeta meta = item.getItemMeta();
                meta.setItemModel(new NamespacedKey("nightterrors", p.model()));
                item.setItemMeta(meta);
                e.setItemStack(item);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                e.setTeleportDuration(3);
                e.setViewRange(2.0f);
                if (p.glow()) {
                    e.setBrightness(new Display.Brightness(15, 15));
                }
                e.setTransformation(new Transformation(new Vector3f(p.px(), p.py(), p.pz()), new Quaternionf(),
                        new Vector3f(def.scale(), def.scale(), def.scale()), new Quaternionf()));
            });
            displays.add(d);
        }
    }

    /**
     * Moves and poses all parts. {@code look} is the point the head should face, or null.
     * {@code factor} enlarges the whole body (1 = designed size); used by the growing spider.
     */
    void update(Mob mob, Location look, float factor, boolean asleep) {
        if (asleep) {
            look = null;
        }
        Location loc = mob.getLocation();
        double dx = loc.getX() - lastX;
        double dz = loc.getZ() - lastZ;
        lastX = loc.getX();
        lastZ = loc.getZ();
        double rise = Math.abs(loc.getY() - lastY);
        lastY = loc.getY();
        double moved = Math.hypot(dx, dz);
        if (moved > 4) {
            moved = 0; // teleported, not walked
        }
        boolean moving = moved > 0.012;
        float want = yaw;
        if (moved > 0.05) { // tiny shuffles must not swing the whole body around
            want = (float) Math.toDegrees(Math.atan2(-dx, dz));
        } else if (look != null) {
            want = yawTo(loc, look);
        }
        // Big bodies turn slowly; a giant that snaps around looks like it is spinning on the spot.
        float maxTurn = def.scale() >= 3f ? 5f : 30f;
        yaw += clamp(wrap(want - yaw) * 0.35f, -maxTurn, maxTurn);
        phase += (float) (moved * def.stride() / Math.max(0.1f, factor)); // bigger body, longer steps
        amp += ((moving ? 1f : 0f) - amp) * 0.5f;
        // Climbing: arms come up over the head and reach hand over hand, driven by how fast it rises.
        climbPose += ((climbing ? 1f : 0f) - climbPose) * 0.3f;
        if (climbing) {
            phase += (float) (rise * def.stride() * 3.0 / Math.max(0.1f, factor)) + 0.25f;
            amp = Math.max(amp, 0.8f);
        }
        time++;
        strike *= 0.62f; // an attack swing fades over about half a second

        float relYaw = 0f;
        float pitch = 0f;
        Location at = new Location(loc.getWorld(), loc.getX(), loc.getY(), loc.getZ(), yaw + yawOffset, 0f);
        float bob = (float) (def.bob() * Math.abs(Math.sin(phase)) * amp) * factor;
        float s = def.scale() * factor;
        // Head pose is eased toward its goal, so a goal that flips between steps cannot make the head jerk.
        float goalYaw = 0f;
        float goalPitch = asleep ? 35f : 0f;
        if (!asleep && look != null) {
            float headY = 0f;
            for (Part p : def.parts()) {
                if (p.look()) {
                    headY = p.py() * factor;
                    break;
                }
            }
            goalYaw = clamp(wrap(yawTo(loc, look) - yaw), -70f, 70f);
            double up = look.getY() - (loc.getY() + headY);
            double flat = Math.hypot(look.getX() - loc.getX(), look.getZ() - loc.getZ());
            goalPitch = clamp((float) -Math.toDegrees(Math.atan2(up, Math.max(flat, 0.01))), -40f, 40f);
        }
        headYaw += (goalYaw - headYaw) * 0.25f;
        headPitch += (goalPitch - headPitch) * 0.25f;
        for (int i = 0; i < displays.size(); i++) {
            ItemDisplay d = displays.get(i);
            if (!d.isValid()) {
                continue;
            }
            Part p = def.parts().get(i);
            Quaternionf q = new Quaternionf();
            if (p.baseYaw() != 0f) {
                q.rotateY(p.baseYaw()); // fans the legs out around the body
            }
            if (p.look()) {
                // Minecraft yaw grows clockwise, JOML's Y rotation counter-clockwise, hence the minus.
                q.rotateY((float) -Math.toRadians(headYaw)).rotateX((float) Math.toRadians(headPitch));
            }
            if (climbPose > 0.01f && p.model().contains("arm")) {
                // -2.8 rad about X swings a hanging arm up and a little forward, toward the wall.
                q.rotateX(-2.8f * climbPose);
            }
            float a = (float) (p.swing() * Math.sin(phase + p.offset()) * amp
                    + (asleep ? 0.0 : p.idle() * Math.sin(time * p.idleSpeed() + p.offset())));
            if (strike > 0.02f && p.model().contains("arm") && p.axis() == 'x') {
                q.rotateX(-1.5f * strike); // both arms thrown forward
            }
            if (a != 0f) {
                switch (p.axis()) {
                    case 'y' -> q.rotateY(a);
                    case 'z' -> q.rotateZ(a);
                    default -> q.rotateX(a);
                }
            }
            d.teleport(at);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(3);
            d.setTransformation(new Transformation(new Vector3f(p.px() * factor, p.py() * factor + bob, p.pz() * factor), q,
                    asleep && p.glow() ? new Vector3f(0.001f, 0.001f, 0.001f) : new Vector3f(s, s, s), new Quaternionf()));
        }
    }

    /** Red outline on or off; used as the "I got hit" flash, since a model cannot turn red like a vanilla mob. */
    void flash(boolean on) {
        for (ItemDisplay d : displays) {
            if (d.isValid()) {
                d.setGlowColorOverride(org.bukkit.Color.RED);
                d.setGlowing(on);
            }
        }
    }

    /** Starts the attack animation: arms swing forward and drop back. */
    void strike() {
        strike = 1f;
    }

    void setClimbing(boolean climbing) {
        this.climbing = climbing;
    }

    void remove() {
        for (ItemDisplay d : displays) {
            d.remove();
        }
        displays.clear();
    }

    private static float yawTo(Location from, Location to) {
        return (float) Math.toDegrees(Math.atan2(-(to.getX() - from.getX()), to.getZ() - from.getZ()));
    }

    private static float wrap(float deg) {
        deg %= 360f;
        if (deg > 180f) {
            deg -= 360f;
        } else if (deg < -180f) {
            deg += 360f;
        }
        return deg;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
