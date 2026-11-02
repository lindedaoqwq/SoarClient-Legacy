package me.eldodebug.soar.injection.mixin;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;

import me.eldodebug.soar.injection.transformer.LwjglTransformer;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

public class SoarTweaker implements ITweaker {

    private final List<String> launchArguments = new ArrayList<>();

	public static boolean hasOptifine = false;
	
    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
    	
    	try {
			Class.forName("optifine.Patcher");
			hasOptifine = true;
		}
		catch(ClassNotFoundException e) {
		}
		
        // Under FML, FMLTweaker already forwards every launch argument (including options
        // Launch cannot parse, such as --userProperties), so copying them here duplicates
        // them and Minecraft's Main fails with MultipleArgumentsForOptionException.
        if (!isForgeEnvironment()) {
            this.launchArguments.addAll(args);
        }

        if (profile != null && !hasArgument(args, "--version")) {
            launchArguments.add("--version");
            launchArguments.add(profile);
        }

        if (assetsDir != null && !hasArgument(args, "--assetsDir")) {
            launchArguments.add("--assetsDir");
            launchArguments.add(assetsDir.getAbsolutePath());
        }

        if (gameDir != null && !hasArgument(args, "--gameDir")) {
            launchArguments.add("--gameDir");
            launchArguments.add(gameDir.getAbsolutePath());
        }
    }

    private static boolean hasArgument(List<String> args, String option) {
        for (String arg : args) {
            if (arg.equals(option) || arg.startsWith(option + "=")) {
                return true;
            }
        }

        Object knownArguments = Launch.blackboard.get("launchArgs");
        if (knownArguments instanceof Map) {
            for (Object key : ((Map<?, ?>) knownArguments).keySet()) {
                if (String.valueOf(key).equals(option)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isForgeEnvironment() {
        try {
            Class.forName("net.minecraftforge.fml.common.Loader", false, SoarTweaker.class.getClassLoader());
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {

    	classLoader.registerTransformer(LwjglTransformer.class.getName());
    	
        MixinBootstrap.init();

        MixinEnvironment env = MixinEnvironment.getDefaultEnvironment();
        Mixins.addConfiguration("mixins.soar.json");

        // Forge overrides GuiIngame#renderGameOverlay, so the HUD hook lives in a
        // forge-only config that is shipped with the Forge jar.
        if (isForgeEnvironment()) {
            Mixins.addConfiguration("mixins.soar.forge.json");
        }

        if (env.getObfuscationContext() == null) {
        	env.setObfuscationContext(isForgeEnvironment() ? "searge" : "notch");
        }

        env.setSide(MixinEnvironment.Side.CLIENT);

        this.unlockLwjgl();
    }

    @Override
    public String getLaunchTarget() {
        return "net.minecraft.client.main.Main";
    }

    @Override
    public String[] getLaunchArguments() {
        return launchArguments.toArray(new String[0]);
    }
    
    @SuppressWarnings("unchecked")
    private void unlockLwjgl() {
        try {
            Field transformerExceptions = LaunchClassLoader.class.getDeclaredField("classLoaderExceptions");
            transformerExceptions.setAccessible(true);
            Object o = transformerExceptions.get(Launch.classLoader);
            ((Set<String>) o).remove("org.lwjgl.");
        } catch (NoSuchFieldException | IllegalAccessException e) {}
    }
}