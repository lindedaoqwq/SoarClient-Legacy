package me.eldodebug.soar.injection.transformer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import org.lwjgl.system.FunctionProvider;

/**
 * Resolves OpenGL entry points for NanoVG.
 *
 * The client was originally built against LWJGL 2, where
 * {@code org.lwjgl.opengl.GLContext#getFunctionAddress(String)} exists. Desktop
 * keeps using that path. iOS launchers (Amethyst/Pojav) run 1.8.9 on a LWJGL 3
 * stack with a LWJGL 2 compatibility layer, where that method does not exist but
 * the LWJGL 3 {@code GLCapabilities} object is still created and exposes every
 * entry point as a public {@code long} field.
 */
public class Lwjgl2FunctionProvider implements FunctionProvider {

    private Method getFunctionAddressMethod;
    private Object receiver;

    private Object capabilities;
    private boolean useCapabilityFields;

    private final StringBuilder failures = new StringBuilder();

    public Lwjgl2FunctionProvider() {
        if (bindMethod("org.lwjgl.opengl.GLContext", "getFunctionAddress", String.class, null)) {
            return;
        }
        if (bindMethod("org.lwjgl.opengl.GLContext", "getFunctionAddress", CharSequence.class, null)) {
            return;
        }

        // LWJGL 3 keeps the provider it used for GLCapabilities; some mobile builds
        // (e.g. Amethyst's patched lwjgl-opengl) expose it through GL.getFunctionProvider().
        if (bindGlFunctionProvider()) {
            return;
        }

        Object glCapabilities = resolveGlCapabilities();
        if (glCapabilities != null) {
            this.capabilities = glCapabilities;
            this.useCapabilityFields = true;
            return;
        }

        if (bindMethod("org.lwjgl.glfw.GLFW", "glfwGetProcAddress", CharSequence.class, null)) {
            return;
        }

        throw new RuntimeException("No OpenGL function address resolver found (tried LWJGL 2/3): " + failures);
    }

    private boolean bindMethod(String className, String methodName, Class<?> parameterType, Object instance) {
        try {
            Class<?> owner = Class.forName(className, false, Lwjgl2FunctionProvider.class.getClassLoader());
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
            failures.append('[').append(className).append('#').append(methodName).append(": ")
                    .append(e.getClass().getSimpleName()).append(']');
            return false;
        }
    }

    private boolean bindGlFunctionProvider() {
        try {
            Class<?> glClass = Class.forName("org.lwjgl.opengl.GL", false,
                    Lwjgl2FunctionProvider.class.getClassLoader());
            Object provider = glClass.getMethod("getFunctionProvider").invoke(null);
            if (provider == null) {
                failures.append("[GL.getFunctionProvider: null]");
                return false;
            }
            Method method;
            try {
                method = provider.getClass().getMethod("getFunctionAddress", CharSequence.class);
            } catch (NoSuchMethodException e) {
                Class<?> providerInterface = Class.forName("org.lwjgl.system.FunctionProvider", false,
                        Lwjgl2FunctionProvider.class.getClassLoader());
                method = providerInterface.getMethod("getFunctionAddress", CharSequence.class);
            }
            method.setAccessible(true);
            this.getFunctionAddressMethod = method;
            this.receiver = provider;
            return true;
        } catch (Throwable e) {
            failures.append("[GL.getFunctionProvider: ").append(e.getClass().getSimpleName()).append(']');
            return false;
        }
    }

    private Object resolveGlCapabilities() {
        // GLContext.getCapabilities() returns a ContextCapabilities wrapper whose "cap"
        // field is the real LWJGL 3 GLCapabilities instance.
        try {
            Class<?> contextClass = Class.forName("org.lwjgl.opengl.GLContext", false,
                    Lwjgl2FunctionProvider.class.getClassLoader());
            Object contextCapabilities = contextClass.getMethod("getCapabilities").invoke(null);
            if (contextCapabilities != null) {
                if (isGlCapabilities(contextCapabilities)) {
                    return contextCapabilities;
                }
                Field capabilityField = contextCapabilities.getClass().getDeclaredField("cap");
                capabilityField.setAccessible(true);
                Object value = capabilityField.get(contextCapabilities);
                if (value != null && isGlCapabilities(value)) {
                    return value;
                }
            }
        } catch (Throwable e) {
            failures.append("[GLContext.getCapabilities: ").append(e.getClass().getSimpleName()).append(']');
        }

        try {
            Class<?> glClass = Class.forName("org.lwjgl.opengl.GL", false,
                    Lwjgl2FunctionProvider.class.getClassLoader());
            Object value = glClass.getMethod("getCapabilities").invoke(null);
            if (value != null && isGlCapabilities(value)) {
                return value;
            }
        } catch (Throwable e) {
            failures.append("[GL.getCapabilities: ").append(e.getClass().getSimpleName()).append(']');
        }

        return null;
    }

    private boolean isGlCapabilities(Object value) {
        return value.getClass().getName().equals("org.lwjgl.opengl.GLCapabilities");
    }

    @Override
    public long getFunctionAddress(CharSequence functionName) {
        if (useCapabilityFields) {
            try {
                Field field = capabilities.getClass().getField(functionName.toString());
                return field.getLong(capabilities);
            } catch (NoSuchFieldException e) {
                return 0L;
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }

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
