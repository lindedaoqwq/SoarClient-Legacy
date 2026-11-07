package me.eldodebug.soar.utils;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import me.eldodebug.soar.Soar;
import me.eldodebug.soar.logger.SoarLogger;
import me.eldodebug.soar.management.event.impl.EventText;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.client.gui.GuiScreenWorking;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/**
 * Runtime optimizations, none of which touch Minecraft/OptiFine user settings.
 *
 * The feature list is modelled after CMPack (CMClient): entity culling,
 * string width caching and memory cleanup on disconnect. Everything here is
 * purely code level:
 *
 *  - Entity culling: entities completely hidden behind opaque blocks are not
 *    submitted to the renderer. Results are cached per entity, so each entity
 *    only costs a few ray casts per second. Entities close to the camera and
 *    the player/view entity are never culled.
 *  - String width caching: FontRenderer.getStringWidth is one of the hottest
 *    calls in chat/HUD heavy scenes; widths are cached per FontRenderer and
 *    only while no EventText listener transforms the text.
 *  - Memory cleanup on disconnect: once the client has been back in the menu
 *    for a moment, the caches are dropped, world-scoped OptiFine caches are
 *    reset and the heap is collected, so the next world starts with a clean
 *    heap instead of inheriting the previous session's garbage.
 */
public class OptimizeUtils {

    /** Do not allocate ray casts for entities closer than this (blocks). */
    private static final double MIN_CULL_DISTANCE = 16.0;
    private static final double MIN_CULL_DISTANCE_SQ = MIN_CULL_DISTANCE * MIN_CULL_DISTANCE;

    /** Beyond this the entity is far enough away that vanilla culling already applies. */
    private static final double MAX_CULL_DISTANCE = 96.0;
    private static final double MAX_CULL_DISTANCE_SQ = MAX_CULL_DISTANCE * MAX_CULL_DISTANCE;

    /** Re-test an entity at most every 250 ms. */
    private static final long CULL_CACHE_MILLIS = 250L;

    /** Maximum ray cast work per 100 ms window, keeps the check cheap. */
    private static final long CULL_BUDGET_WINDOW_MILLIS = 100L;
    private static final int CULL_BUDGET_PER_WINDOW = 16;

    private static final int STRING_WIDTH_CACHE_SIZE = 4096;

    private static final Map<Integer, CullEntry> cullCache = new HashMap<Integer, CullEntry>();
    private static final Map<FontRenderer, LinkedHashMap<String, Integer>> widthCaches =
            new IdentityHashMap<FontRenderer, LinkedHashMap<String, Integer>>();

    private static long cullWindowStartMs;
    private static int cullTestsInWindow;

    private static boolean started;

    static {
        startMemoryCleanupThread();
    }

    private static class CullEntry {

        private final boolean culled;
        private final long timeMs;

        private CullEntry(boolean culled, long timeMs) {
            this.culled = culled;
            this.timeMs = timeMs;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Entity culling                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * Called before an entity is rendered. {@code x/y/z} are already relative
     * to the camera, so the world position of the camera can be derived from
     * the entity position.
     */
    public static boolean shouldCullEntity(Entity entity, double x, double y, double z) {

        Minecraft minecraft = Minecraft.getMinecraft();

        if (entity == null || minecraft == null || minecraft.theWorld == null) {
            return false;
        }

        if (entity == minecraft.thePlayer || entity == minecraft.getRenderViewEntity()) {
            return false;
        }

        // Entities that opt out of frustum checks (bosses, markers) must stay.
        if (entity.ignoreFrustumCheck || entity.isInvisible()) {
            return false;
        }

        double distanceSq = x * x + y * y + z * z;

        if (distanceSq < MIN_CULL_DISTANCE_SQ || distanceSq > MAX_CULL_DISTANCE_SQ) {
            return false;
        }

        long now = System.currentTimeMillis();
        int entityId = entity.getEntityId();

        CullEntry cached = cullCache.get(entityId);

        if (cached != null && now - cached.timeMs < CULL_CACHE_MILLIS) {
            return cached.culled;
        }

        if (!acquireCullBudget(now)) {
            return false;
        }

        boolean culled = isOccluded(entity, x, y, z);

        if (cullCache.size() > 4096) {
            cullCache.clear();
        }

        cullCache.put(entityId, new CullEntry(culled, now));
        return culled;
    }

    private static boolean isOccluded(Entity entity, double relX, double relY, double relZ) {

        try {

            Minecraft minecraft = Minecraft.getMinecraft();

            if (minecraft == null || minecraft.theWorld == null) {
                return false;
            }

            Entity viewer = minecraft.getRenderViewEntity();

            if (viewer == null) {
                return false;
            }

            double entityX = entity.posX;
            double entityY = entity.posY;
            double entityZ = entity.posZ;

            Vec3 eye = new Vec3(
                    entityX - relX,
                    entityY - relY + viewer.getEyeHeight(),
                    entityZ - relZ);

            // When the camera is inside a block every ray starts inside it and
            // would report the entity as hidden - never cull in that case.
            BlockPos eyePos = new BlockPos(eye.xCoord, eye.yCoord, eye.zCoord);

            if (minecraft.theWorld.getBlockState(eyePos).getBlock().getMaterial().isOpaque()) {
                return false;
            }

            // Test the middle and the top of the entity: if both are behind
            // opaque blocks the entity cannot be seen at all.
            Vec3 center = new Vec3(entityX, entityY + entity.height * 0.5, entityZ);
            Vec3 top = new Vec3(entityX, entityY + entity.height * 0.9, entityZ);

            return isRayOccluded(minecraft, eye, center) && isRayOccluded(minecraft, eye, top);

        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isRayOccluded(Minecraft minecraft, Vec3 from, Vec3 to) {

        try {

            MovingObjectPosition hit = minecraft.theWorld.rayTraceBlocks(from, to, false, true, false);

            if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
                return false;
            }

            double distance = from.distanceTo(to);

            if (hit.hitVec == null || hit.hitVec.distanceTo(from) >= distance - 0.5) {
                return false;
            }

            BlockPos pos = hit.getBlockPos();

            if (pos == null || minecraft.theWorld.isAirBlock(pos)) {
                return false;
            }

            Block block = minecraft.theWorld.getBlockState(pos).getBlock();

            // Glass, water, leaves and other non-opaque materials do not hide
            // entities and must never cull them.
            return block.getMaterial().isOpaque() && block.isFullBlock();

        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean acquireCullBudget(long now) {

        if (now - cullWindowStartMs > CULL_BUDGET_WINDOW_MILLIS) {
            cullWindowStartMs = now;
            cullTestsInWindow = 0;
        }

        if (cullTestsInWindow >= CULL_BUDGET_PER_WINDOW) {
            return false;
        }

        cullTestsInWindow++;
        return true;
    }

    /* ------------------------------------------------------------------ */
    /* String width caching                                                */
    /* ------------------------------------------------------------------ */

    public static Integer getCachedStringWidth(FontRenderer fontRenderer, String text) {

        if (fontRenderer == null || text == null || text.isEmpty() || text.length() > 256) {
            return null;
        }

        if (hasTextTransformers()) {
            return null;
        }

        LinkedHashMap<String, Integer> cache = getWidthCache(fontRenderer);

        synchronized (cache) {
            return cache.get(text);
        }
    }

    public static void storeStringWidth(FontRenderer fontRenderer, String text, int width) {

        if (fontRenderer == null || text == null || text.isEmpty() || text.length() > 256) {
            return;
        }

        if (hasTextTransformers()) {
            return;
        }

        LinkedHashMap<String, Integer> cache = getWidthCache(fontRenderer);

        synchronized (cache) {

            if (cache.size() >= STRING_WIDTH_CACHE_SIZE && !cache.containsKey(text)) {
                Iterator<String> iterator = cache.keySet().iterator();
                if (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }

            cache.put(text, width);
        }
    }

    private static LinkedHashMap<String, Integer> getWidthCache(FontRenderer fontRenderer) {

        synchronized (widthCaches) {

            LinkedHashMap<String, Integer> cache = widthCaches.get(fontRenderer);

            if (cache == null) {
                cache = new LinkedHashMap<String, Integer>(256, 0.75F, true);
                widthCaches.put(fontRenderer, cache);
            }

            return cache;
        }
    }

    /**
     * When a mod rewrites rendered text through EventText, the cached width of
     * the original string would be wrong, so the cache is bypassed entirely.
     */
    private static boolean hasTextTransformers() {

        try {

            Soar instance = Soar.getInstance();

            if (instance == null || instance.getEventManager() == null) {
                return false;
            }

            return instance.getEventManager().get(EventText.class) != null;

        } catch (Throwable ignored) {
            return true;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Memory cleanup                                                      */
    /* ------------------------------------------------------------------ */

    /** Drops caches that depend on the current resource pack/font. */
    public static void clearCaches() {

        cullCache.clear();

        synchronized (widthCaches) {
            widthCaches.clear();
        }
    }

    /**
     * Runs on the render thread after the client has been disconnected for a
     * moment. Frees world-scoped OptiFine data as well, because that otherwise
     * survives until the next resource reload.
     */
    public static void cleanupAfterDisconnect() {

        clearCaches();

        try {
            Class<?> dynamicLights = Class.forName("net.optifine.DynamicLights");
            dynamicLights.getMethod("clear").invoke(null);
        } catch (Throwable ignored) {
        }

        try {
            Class<?> customSky = Class.forName("net.optifine.CustomSky");
            customSky.getMethod("reset").invoke(null);
        } catch (Throwable ignored) {
        }

        try {
            System.gc();
        } catch (Throwable ignored) {
        }

        SoarLogger.info("Memory cleanup completed after disconnect");
    }

    private static void startMemoryCleanupThread() {

        if (started) {
            return;
        }

        started = true;

        Thread thread = new Thread("Soar-Optimize-MemoryCleanup") {
            @Override
            public void run() {

                boolean wasInWorld = false;
                boolean cleaned = false;
                long disconnectedAtMs = 0L;

                while (true) {

                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException e) {
                        return;
                    }

                    try {

                        Minecraft minecraft = Minecraft.getMinecraft();

                        if (minecraft == null) {
                            continue;
                        }

                        if (minecraft.theWorld != null) {
                            wasInWorld = true;
                            cleaned = false;
                            disconnectedAtMs = 0L;
                            continue;
                        }

                        if (!wasInWorld || cleaned) {
                            continue;
                        }

                        // Never collect while a new world is being loaded.
                        if (minecraft.currentScreen instanceof GuiDownloadTerrain
                                || minecraft.currentScreen instanceof GuiScreenWorking) {
                            continue;
                        }

                        if (disconnectedAtMs == 0L) {
                            disconnectedAtMs = System.currentTimeMillis();
                            continue;
                        }

                        if (System.currentTimeMillis() - disconnectedAtMs < 3000L) {
                            continue;
                        }

                        cleaned = true;
                        disconnectedAtMs = 0L;

                        minecraft.addScheduledTask(new Runnable() {
                            @Override
                            public void run() {
                                cleanupAfterDisconnect();
                            }
                        });

                    } catch (Throwable ignored) {
                    }
                }
            }
        };

        thread.setDaemon(true);
        thread.start();
    }
}
