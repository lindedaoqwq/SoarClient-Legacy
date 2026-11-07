package me.eldodebug.soar.injection.mixin.mixins.gui;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.eldodebug.soar.Soar;
import me.eldodebug.soar.management.event.impl.EventText;
import me.eldodebug.soar.utils.OptimizeUtils;
import net.minecraft.client.gui.FontRenderer;

@Mixin(FontRenderer.class)
public abstract class MixinFontRenderer {

    @Shadow 
    protected abstract void resetStyles();

    @ModifyVariable(method = "renderString", at = @At("HEAD"), ordinal = 0)
    private String renderString(String text) {
    	
    	if(text == null || Soar.getInstance().getEventManager() == null) {
    		return text;
    	}
		
    	EventText event = new EventText(text);
    	event.call();
    	
    	return event.getOutputText();
    }
    
    @ModifyVariable(method = "getStringWidth", at = @At("HEAD"), ordinal = 0)
    private String getStringWidth(String text) {
    	
    	if(text == null || Soar.getInstance().getEventManager() == null) {
    		return text;
    	}
    	
    	EventText event = new EventText(text);
    	event.call();
    	
    	return event.getOutputText();
    }
    
    @Inject(method = "drawString(Ljava/lang/String;FFIZ)I",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/FontRenderer;renderString(Ljava/lang/String;FFIZ)I",
            ordinal = 0, shift = At.Shift.AFTER))
    private void resetStyle(CallbackInfoReturnable<Integer> ci) {
        this.resetStyles();
    }
    
    /**
     * String width caching: getStringWidth is called for every HUD/chat entry
     * every frame. The cache lives in OptimizeUtils and is bypassed when an
     * EventText listener rewrites the text.
     */
    @Inject(method = "getStringWidth(Ljava/lang/String;)I", at = @At("HEAD"), cancellable = true)
    private void cachedStringWidth(String text, CallbackInfoReturnable<Integer> cir) {
    	
    	Integer width = OptimizeUtils.getCachedStringWidth((FontRenderer) (Object) this, text);
    	
    	if (width != null) {
    		cir.setReturnValue(width);
    	}
    }
    
    @Inject(method = "getStringWidth(Ljava/lang/String;)I", at = @At("RETURN"), cancellable = true)
    private void storeStringWidth(String text, CallbackInfoReturnable<Integer> cir) {
    	OptimizeUtils.storeStringWidth((FontRenderer) (Object) this, text, cir.getReturnValue());
    }
}
