package me.eldodebug.soar.utils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import me.eldodebug.soar.injection.mixin.SoarTweaker;
import me.eldodebug.soar.logger.SoarLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.client.settings.GameSettings;

/**
 * OptiFine is written for desktop OpenGL. On the iOS/ANGLE + gl4es stack its
 * optional content loaders crash natively (no Java stack trace), so every
 * OptiFine feature that decodes extra data from a resource pack or touches the
 * entity renderer is switched off on that stack.
 *
 * There is a second, purely OptiFine internal problem: with "Render Regions"
 * enabled (default) every 256x256 block region the player visits is cached in
 * {@code ViewFrustum.mapVboRegions} together with a shared VBO per block layer.
 * OptiFine only clears that map when the whole ViewFrustum is rebuilt (world
 * load / render distance change), so exploring a world or a server keeps
 * allocating GPU/direct memory for regions that are far behind the player until
 * the native memory runs out and the game dies. {@link #trimRenderRegions}
 * releases the regions that no loaded render chunk uses anymore.
 */
public class OptifineUtils {

    private static Field gameSettings_ofFastRender;

    private static boolean optifineChecked;
    private static boolean compatibilityLogged;

    private static Field renderGlobalField;
    private static Field viewFrustumField;
    private static Field mapVboRegionsField;
    private static Method getVertexBufferByLayerMethod;
    private static Method getVboRegionMethod;
    private static Method deleteGlBuffersMethod;

    private static boolean regionCleanupStarted;

    static {
        init();
    }

    private static void init() {
        if (optifineChecked) {
            return;
        }
        optifineChecked = true;

        try {
            Class.forName("Config");
            gameSettings_ofFastRender = GameSettings.class.getDeclaredField("ofFastRender");
            gameSettings_ofFastRender.setAccessible(true);
        } catch (Throwable ignored) {
            // OptiFine is not installed, or its field names differ.
        }

        try {
            renderGlobalField = findFieldByType(Minecraft.class, RenderGlobal.class);
            viewFrustumField = findFieldByType(RenderGlobal.class, ViewFrustum.class);
            mapVboRegionsField = findFieldByName(ViewFrustum.class, "mapVboRegions");

            getVertexBufferByLayerMethod = RenderChunk.class.getMethod("getVertexBufferByLayer", int.class);
            getVboRegionMethod = VertexBuffer.class.getMethod("getVboRegion");

            Class<?> vboRegionClass = Class.forName("net.optifine.render.VboRegion");
            deleteGlBuffersMethod = vboRegionClass.getMethod("deleteGlBuffers");
        } catch (Throwable ignored) {
            // Plain Minecraft, or an OptiFine build without render regions.
        }
    }

    private static Field findFieldByType(Class<?> owner, Class<?> type) {
        for (Field field : owner.getDeclaredFields()) {
            if (field.getType() == type) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    private static Field findFieldByName(Class<?> owner, String name) {
        for (Field field : owner.getDeclaredFields()) {
            if (field.getName().equals(name)) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    public static void disableFastRender() {

        Minecraft mc = Minecraft.getMinecraft();

        if (mc == null || mc.gameSettings == null) {
            return;
        }

        if (SoarTweaker.hasOptifine && gameSettings_ofFastRender != null) {
            try {
                gameSettings_ofFastRender.set(mc.gameSettings, false);
            } catch (Throwable ignored) {
            }
        }

        mc.gameSettings.useVbo = true;
        mc.gameSettings.fboEnable = true;
    }

    public static void applyMobileCompatibility() {

        Minecraft mc = Minecraft.getMinecraft();

        if (mc == null || mc.gameSettings == null) {
            return;
        }

        applyMobileCompatibility(mc.gameSettings);
    }

    public static void applyMobileCompatibility(GameSettings settings) {

        if (settings == null) {
            return;
        }

        if (!SoarTweaker.hasOptifine) {
            logOnce("OptiFine not installed, OptiFine compatibility skipped");
            return;
        }

        if (!isMobileEnvironment()) {
            logOnce("Desktop OpenGL detected, OptiFine compatibility skipped");
            return;
        }

        StringBuilder applied = new StringBuilder();

        // Resource pack reload crash: this runs while the texture atlas is
        // rebuilt and every one of these decodes extra pack data (mcpatcher
        // sky/ctm/emissive/animation properties). They are the last thing in
        // the log before the native crash.
        setOption(settings, "ofCustomSky", false, applied);
        setOption(settings, "ofEmissiveTextures", false, applied);
        setOption(settings, "ofAnimatedTextures", false, applied);
        setOption(settings, "ofNaturalTextures", false, applied);
        setOption(settings, "ofConnectedTextures", 0, applied);

        // In-world mob hit crash/stutter: variant textures, custom entity
        // models and dynamic light updates all allocate/upload while the
        // entity renderer holds GL state.
        setOption(settings, "ofRandomEntities", false, applied);
        setOption(settings, "ofCustomEntityModels", false, applied);
        setOption(settings, "ofDynamicLights", 0, applied);

        // Frame smoothing/timers fight the fixed-step loop on iOS and show up
        // as stutter spikes when the game renders damage particles.
        setOption(settings, "ofSmoothFps", false, applied);
        setOption(settings, "ofSmoothWorld", false, applied);

        // MSAA/FXAA resolve on gl4es is slow and can corrupt the framebuffer
        // after an entity is damaged.
        setOption(settings, "ofAaLevel", 0, applied);

        SoarLogger.info("OptiFine mobile compatibility applied: "
                + (applied.length() == 0 ? "none (unknown option names)" : applied.toString().trim()));

        startRegionCleanup();

        logOnce("OptiFine render-region cleanup " + (deleteGlBuffersMethod != null ? "active" : "unavailable"));
    }

    /**
     * Runs on the render thread and releases the render-region VBOs that no
     * loaded render chunk references anymore. This is OptiFine's own leak, not
     * an option: the map is only cleared when the ViewFrustum is rebuilt.
     */
    public static void trimRenderRegions() {

        Minecraft mc = Minecraft.getMinecraft();

        if (mc == null || mc.theWorld == null || mc.thePlayer == null || mc.currentScreen != null) {
            return;
        }

        if (renderGlobalField == null || viewFrustumField == null || mapVboRegionsField == null
                || getVertexBufferByLayerMethod == null || getVboRegionMethod == null
                || deleteGlBuffersMethod == null) {
            return;
        }

        try {

            RenderGlobal renderGlobal = (RenderGlobal) renderGlobalField.get(mc);

            if (renderGlobal == null) {
                return;
            }

            ViewFrustum viewFrustum = (ViewFrustum) viewFrustumField.get(renderGlobal);

            if (viewFrustum == null) {
                return;
            }

            RenderChunk[] renderChunks = viewFrustum.renderChunks;

            if (renderChunks == null || renderChunks.length == 0) {
                return;
            }

            Map<?, ?> regionsByPosition = (Map<?, ?>) mapVboRegionsField.get(viewFrustum);

            if (regionsByPosition == null || regionsByPosition.isEmpty()) {
                return;
            }

            Set<Object> usedRegions = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            collectUsedRegions(renderChunks, usedRegions);

            int released = 0;
            Iterator<?> iterator = regionsByPosition.entrySet().iterator();

            while (iterator.hasNext()) {

                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) iterator.next();
                Object[] vboRegions = (Object[]) entry.getValue();

                if (isAnyRegionUsed(vboRegions, usedRegions)) {
                    continue;
                }

                for (Object vboRegion : vboRegions) {
                    if (vboRegion != null) {
                        try {
                            deleteGlBuffersMethod.invoke(vboRegion);
                        } catch (Throwable ignored) {
                        }
                    }
                }

                iterator.remove();
                released++;
            }

            if (released > 0) {
                SoarLogger.info("OptiFine render regions released: " + released
                        + " (remaining " + regionsByPosition.size() + ")");
            }

        } catch (Throwable ignored) {
        }
    }

    private static void collectUsedRegions(RenderChunk[] renderChunks, Set<Object> usedRegions) {

        if (renderChunks == null) {
            return;
        }

        for (RenderChunk renderChunk : renderChunks) {

            if (renderChunk == null) {
                continue;
            }

            for (int layer = 0; layer < 8; layer++) {

                VertexBuffer vertexBuffer;

                try {
                    vertexBuffer = renderChunk.getVertexBufferByLayer(layer);
                } catch (Throwable endOfLayers) {
                    break;
                }

                if (vertexBuffer == null) {
                    continue;
                }

                try {
                    Object vboRegion = getVboRegionMethod.invoke(vertexBuffer);

                    if (vboRegion != null) {
                        usedRegions.add(vboRegion);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean isAnyRegionUsed(Object[] vboRegions, Set<Object> usedRegions) {

        if (vboRegions == null) {
            return false;
        }

        for (Object vboRegion : vboRegions) {
            if (vboRegion != null && usedRegions.contains(vboRegion)) {
                return true;
            }
        }

        return false;
    }

    /**
     * The cleanup needs the render thread (glDeleteBuffers), so a daemon thread
     * only schedules the work back onto Minecraft's task queue.
     */
    private static void startRegionCleanup() {

        if (regionCleanupStarted) {
            return;
        }

        regionCleanupStarted = true;

        Thread thread = new Thread("Soar-OptiFine-RegionCleanup") {
            @Override
            public void run() {
                while (true) {

                    try {
                        Thread.sleep(30000L);
                    } catch (InterruptedException e) {
                        return;
                    }

                    try {

                        Minecraft mc = Minecraft.getMinecraft();

                        if (mc != null) {
                            mc.addScheduledTask(new Runnable() {
                                @Override
                                public void run() {
                                    trimRenderRegions();
                                }
                            });
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        };

        thread.setDaemon(true);
        thread.start();
    }

    private static boolean isMobileEnvironment() {

        // System properties are available before the GL context is created,
        // which matters because GameSettings is built before the first
        // resource reload.
        try {
            String libName = System.getProperty("org.lwjgl.opengl.libname", "").toLowerCase();
            String pojav = System.getProperty("pojav.lwjgl.version", "");
            String cacio = System.getProperty("cacio.managed.screensize", "");
            String osName = System.getProperty("os.name", "").toLowerCase();

            if (libName.contains("gl4es")
                    || libName.contains("nggl4es")
                    || !pojav.isEmpty()
                    || !cacio.isEmpty()
                    || osName.contains("ios")) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        // Only query GL strings once a context exists.
        try {
            if (!org.lwjgl.opengl.Display.isCreated()) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }

        return GlUtils.isMobileGl();
    }

    private static void logOnce(String message) {
        if (compatibilityLogged) {
            return;
        }
        compatibilityLogged = true;
        SoarLogger.info(message);
    }

    private static void setOption(GameSettings settings, String fieldName, Object value, StringBuilder applied) {

        boolean success = false;

        try {
            Field field = GameSettings.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            success = writeValue(field, settings, value);
        } catch (Throwable ignored) {
            // Option name differs between OptiFine versions; leave it untouched.
        }

        if (success) {
            applied.append(fieldName).append(' ');
        }

        // OptiFine keeps a copy of these options in static fields of Config;
        // writing only the GameSettings field does not always affect rendering.
        String configFieldName = fieldName.startsWith("of")
                ? Character.toLowerCase(fieldName.charAt(2)) + fieldName.substring(3)
                : fieldName;

        for (String name : new String[] {configFieldName, fieldName}) {
            try {
                Class<?> configClass = Class.forName("Config");
                Field configField = configClass.getDeclaredField(name);

                if (writeValue(configField, null, value)) {
                    applied.append("Config.").append(name).append(' ');
                }
                break;
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean writeValue(Field field, Object target, Object value) {

        try {
            Class<?> type = field.getType();

            if (type == boolean.class) {
                field.setBoolean(target, value instanceof Boolean ? (Boolean) value : ((Integer) value) != 0);
                return true;
            }

            if (type == int.class) {
                field.setInt(target, value instanceof Integer ? (Integer) value : ((Boolean) value) ? 1 : 0);
                return true;
            }
        } catch (Throwable ignored) {
        }

        return false;
    }
}
