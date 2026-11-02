package me.eldodebug.soar.injection.mixin.mixins.gui;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.eldodebug.soar.gui.GuiEditHUD;
import me.eldodebug.soar.gui.modmenu.GuiModMenu;
import me.eldodebug.soar.logger.SoarLogger;
import me.eldodebug.soar.management.event.impl.EventRender2D;
import me.eldodebug.soar.management.event.impl.EventRenderNotification;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.GuiIngameForge;

/**
 * Forge replaces GuiIngame#renderGameOverlay with its own implementation, so the
 * injection in MixinGuiIngame never runs on a Forge client and HUD mods stayed
 * invisible in game while still rendering in the HUD editor.
 */
@Mixin(GuiIngameForge.class)
public class MixinGuiIngameForge {

	private static boolean soarHudHookLogged;

	@Inject(method = "renderGameOverlay", at = @At("RETURN"))
	public void postRenderGameOverlay(float partialTicks, CallbackInfo ci) {

		Minecraft mc = Minecraft.getMinecraft();

		if(!soarHudHookLogged) {
			soarHudHookLogged = true;
			
			int hudMods = 0;
			for(me.eldodebug.soar.management.mods.Mod mod : me.eldodebug.soar.Soar.getInstance().getModManager().getMods()) {
				if(mod instanceof me.eldodebug.soar.management.mods.HUDMod && mod.isToggled()) {
					hudMods++;
				}
			}
			
			SoarLogger.info("[HUD] Forge renderGameOverlay hook active, currentScreen=" + (mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getName()) + ", toggled HUD mods=" + hudMods);
		}

		if(!(mc.currentScreen instanceof GuiEditHUD)) {
			new EventRender2D(partialTicks).call();

			if(!(mc.currentScreen instanceof GuiModMenu)) {
				new EventRenderNotification().call();
			}
		}
	}
}
