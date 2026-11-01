package me.eldodebug.soar.utils;

import java.nio.ByteBuffer;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;

public class GlUtils {

	private static Minecraft mc = Minecraft.getMinecraft();
	
    public static void bindTexture(int texture) {
    	GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }
    
    public static void getTexImage(int i, int j, int k, int l, ByteBuffer m) {
        GL11.glGetTexImage(i, j, k, l, m);
    }
    
    public static void pixelStore(int i, int j) {
        GL11.glPixelStorei(i, j);
    }
    
    public static void startScale(float x, float y, float scale) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0);
        GlStateManager.scale(scale, scale, 1);
        GlStateManager.translate(-x, -y, 0);
    }
    
    public static void startScale(float x, float y, float width, float height, float scale) {
        GlStateManager.pushMatrix();
        GlStateManager.translate((x + (x + width)) / 2, (y + (y + height)) / 2, 0);
        GlStateManager.scale(scale, scale, 1);
        GlStateManager.translate(-(x + (x + width)) / 2, -(y + (y + height)) / 2, 0);
    }

    public static void stopScale() {
        GlStateManager.popMatrix();
    }
    
    public static void startTranslate(float x, float y) {
    	GlStateManager.pushMatrix();
    	GlStateManager.translate(x, y, 0);
    }
    
    public static void stopTranslate() {
    	GlStateManager.popMatrix();
    }
    
    public static Framebuffer createFrameBuffer(Framebuffer framebuffer) {
        if (framebuffer == null || framebuffer.framebufferWidth != mc.displayWidth || framebuffer.framebufferHeight != mc.displayHeight) {
            if (framebuffer != null) {
                framebuffer.deleteFramebuffer();
            }
            return new Framebuffer(mc.displayWidth, mc.displayHeight, true);
        }
        return framebuffer;
    }

    /**
     * Detects the iOS/ANGLE + gl4es stack. There, mixing NanoVG's fixed function
     * client-array draws with Minecraft's own client-array draws corrupts the
     * vertex pointers used by the framebuffer blit (SIGSEGV inside ANGLE).
     */
    public static boolean isMobileGl() {
        try {
            String openGlLib = System.getProperty("org.lwjgl.opengl.libname", "");
            String pojavVersion = System.getProperty("pojav.lwjgl.version", "");
            String cacioScreen = System.getProperty("cacio.managed.screensize", "");
            String signals = (openGlLib + ' ' + pojavVersion + ' ' + cacioScreen).toLowerCase();
            if (signals.contains("gl4es")
                    || signals.contains("nggl4es")
                    || !pojavVersion.isEmpty()
                    || !cacioScreen.isEmpty()) {
                return true;
            }
        } catch (Throwable e) {
            // Fall through to the GL string based detection.
        }

        try {
            String version = GL11.glGetString(GL11.GL_VERSION);
            String renderer = GL11.glGetString(GL11.GL_RENDERER);
            String vendor = GL11.glGetString(GL11.GL_VENDOR);
            String info = ((version == null ? "" : version) + ' '
                    + (renderer == null ? "" : renderer) + ' '
                    + (vendor == null ? "" : vendor)).toLowerCase();
            return info.contains("opengl es")
                    || info.contains("angle")
                    || info.contains("gl4es")
                    || info.contains("krypton");
        } catch (Throwable e) {
            return false;
        }
    }
    
    public static void setAlphaLimit(float limit) {
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, (float) (limit * .01));
    }
}
