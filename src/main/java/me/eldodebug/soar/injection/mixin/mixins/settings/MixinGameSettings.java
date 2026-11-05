package me.eldodebug.soar.injection.mixin.mixins.settings;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.eldodebug.soar.utils.OptifineUtils;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;

@Mixin(GameSettings.class)
public class MixinGameSettings {

    @Overwrite
    public static boolean isKeyDown(KeyBinding key) {
    	
        int keyCode = key.getKeyCode();
        
        if (keyCode != 0 && keyCode < 256) {
            return keyCode < 0 ? Mouse.isButtonDown(keyCode + 100) : Keyboard.isKeyDown(keyCode);
        } else {
            return false;
        }
    }
    
    /**
     * OptiFine reads its options while GameSettings is being constructed, i.e.
     * before the first resource reload. Applying the mobile compatibility there
     * is the only way to stop the resource pack reload from decoding custom sky
     * textures (the native crash on iOS/ANGLE).
     */
    @Inject(method = "<init>(Lnet/minecraft/client/Minecraft;Ljava/io/File;)V", at = @At("RETURN"))
    private void soar$applyOptifineMobileCompatibility(CallbackInfo ci) {
    	OptifineUtils.applyMobileCompatibility((GameSettings) (Object) this);
    }
}
