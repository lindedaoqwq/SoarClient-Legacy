package me.eldodebug.soar.injection.transformer;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import org.lwjgl.system.FunctionProvider;

/**
 * Resolves OpenGL entry points for NanoVG.
 *
 * The original client was built against LWJGL 2, where
 * {@code org.lwjgl.opengl.GLContext#getFunctionAddress(String)} exists. Some
 * mobile launchers (Amethyst/Pojav on iOS) ship LWJGL 3 instead, where that
 * method is gone, so the provider falls back to the LWJGL 3 ways of obtaining
 * function pointers (GL capabilities or GLFW).
 */
public class Lwjgl2FunctionProvider implements FunctionProvider {

    private Method getFunctionAddressMethod;
    private Object receiver;

    public Lwjgl2FunctionProvider() {
        if (tryBind("org.lwjgl.opengl.GLContext", "getFunctionAddress", String.class, null)) {
            return;
        }
        if (tryBind("org.lwjgl.opengl.GLContext", "getFunctionAddress", CharSequence.class, null)) {
            return;
        }
        if (tryBindCapabilities()) {
            return;
        }
        if (tryBind("org.lwjgl.glfw.GLFW", "glfwGetProcAddress", CharSequence.class, null)) {
            return;
        }
        throw new RuntimeException("No OpenGL function address resolver found (tried LWJGL 2/3)");
    }

    private boolean tryBind(String className, String methodName, Class<?> parameterType, Object instance) {
        try {
            Class<?> owner = Class.forName(className);
            Method method;
            try {
                method = owner.getMethod(methodName, parameterType);
            } catch (NoSuchMethodException e) {
                method = owner.getDeclaredMethod(methodName, parameterType);
            }
            method.setAccessible(true);
            this.getFunctionAddressMethod = method;
            this.receiver = instance;
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private boolean tryBindCapabilities() {
        try {
            Class<?> glClass = Class.forName("org.lwjgl.opengl.GL");
            Object capabilities = glClass.getMethod("getCapabilities").invoke(null);
            if (capabilities != null) {
                Method method = capabilities.getClass().getMethod("getFunctionAddress", CharSequence.class);
                method.setAccessible(true);
                this.getFunctionAddressMethod = method;
                this.receiver = capabilities;
                return true;
            }
        } catch (Throwable e) {
            // Fall through to the next strategy.
        }
        return false;
    }

    @Override
    public long getFunctionAddress(CharSequence functionName) {
        try {
            Object address = getFunctionAddressMethod.invoke(receiver, functionName.toString());
            return ((Long) address).longValue();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public long getFunctionAddress(ByteBuffer byteBuffer) {
        throw new UnsupportedOperationException();
    }
}
