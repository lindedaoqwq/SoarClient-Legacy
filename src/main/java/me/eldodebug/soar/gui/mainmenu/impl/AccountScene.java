package me.eldodebug.soar.gui.mainmenu.impl;

import java.awt.Color;
import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;

import javax.imageio.ImageIO;

import org.lwjgl.input.Keyboard;

import me.eldodebug.soar.Soar;
import me.eldodebug.soar.gui.mainmenu.GuiSoarMainMenu;
import me.eldodebug.soar.gui.mainmenu.MainMenuScene;
import me.eldodebug.soar.gui.mainmenu.impl.welcome.LastMessageScene;
import me.eldodebug.soar.injection.interfaces.IMixinMinecraft;
import me.eldodebug.soar.logger.SoarLogger;
import me.eldodebug.soar.management.account.Account;
import me.eldodebug.soar.management.account.AccountManager;
import me.eldodebug.soar.management.account.AccountType;
import me.eldodebug.soar.management.file.FileManager;
import me.eldodebug.soar.management.language.TranslateText;
import me.eldodebug.soar.management.nanovg.NanoVGManager;
import me.eldodebug.soar.management.nanovg.font.Fonts;
import me.eldodebug.soar.management.nanovg.font.Icon;
import me.eldodebug.soar.mcef.Mcef;
import me.eldodebug.soar.ui.comp.impl.field.CompMainMenuTextBox;
import me.eldodebug.soar.utils.ImageUtils;
import me.eldodebug.soar.utils.IOUtils;
import me.eldodebug.soar.utils.Multithreading;
import me.eldodebug.soar.utils.file.FileUtils;
import me.eldodebug.soar.utils.mouse.MouseUtils;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Session;

public class AccountScene extends MainMenuScene {

	public CompMainMenuTextBox textBox = new CompMainMenuTextBox();
	public CompMainMenuTextBox urlBox = new CompMainMenuTextBox();
	
	private boolean browserLogin;
	
	private String loginError;
	
	private long lastClipboardCheck;
	private String lastClipboardValue = "";
	
	private static final long CLIPBOARD_CHECK_INTERVAL = 500L;
	
	private static final String MICROSOFT_LOGIN_URL = "https://login.live.com/oauth20_authorize.srf?client_id=00000000402b5328&response_type=code&redirect_uri=https://login.live.com/oauth20_desktop.srf&scope=XboxLive.signin%20offline_access&prompt=login";
	
	private File offlineSkin;
	
	public AccountScene(GuiSoarMainMenu parent) {
		super(parent);
	}

	@Override
	public void initScene() {
		if(!Mcef.isInitialized()) {
			Mcef.getClient();
		}
	}
	
	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		
		ScaledResolution sr = new ScaledResolution(mc);
		Soar instance = Soar.getInstance();
		NanoVGManager nvg = instance.getNanoVGManager();
		
		nvg.setupAndDraw(() -> drawNanoVG(mouseX, mouseY, partialTicks, sr, instance, nvg));
	}
	
	public void drawNanoVG(int mouseX, int mouseY, float partialTicks, ScaledResolution sr, Soar instance, NanoVGManager nvg) {
		
		int acWidth = 220;
		int acHeight = 138;
		int acX = sr.getScaledWidth() / 2 - (acWidth / 2);
		int acY = sr.getScaledHeight() / 2 - (acHeight / 2);
		
		String loginMessage = TranslateText.LOGIN_MESSAGE.getText();
		String microsoftLogin = TranslateText.MICROSOFT_LOGIN.getText();
		String offlineLogin = TranslateText.OFFLINE_LOGIN.getText();
		
		nvg.drawRoundedRect(acX, acY, acWidth, acHeight, 8, this.getBackgroundColor());
		nvg.drawCenteredText(loginMessage, acX + (acWidth / 2), acY + 9, Color.WHITE, 14, Fonts.REGULAR);
		
		nvg.drawRoundedImage(new ResourceLocation("soar/mainmenu/microsoft-background.png"), acX + 10, acY + 29, 200, 30, 5);
		
		nvg.drawText(microsoftLogin, acX + 45, acY + 40, Color.WHITE, 10, Fonts.REGULAR);
		
		nvg.drawRoundedRect(acX + 18, acY + 34, 9, 9, 1, new Color(247, 78, 30));
		nvg.drawRoundedRect(acX + 18 + 11, acY + 34, 9, 9, 1, new Color(127, 186, 0));
		nvg.drawRoundedRect(acX + 18, acY + 34 + 11, 9, 9, 1, new Color(0, 164, 239));
		nvg.drawRoundedRect(acX + 18 + 11, acY + 34 + 11, 9, 9, 1, new Color(255, 185, 0));
		
		nvg.drawCenteredText(offlineLogin, acX + (acWidth / 2), acY + 67, Color.WHITE, 14, Fonts.REGULAR);
		
		nvg.drawRoundedRect(acX + acWidth - 30, acY + 86, 20, 20, 4, this.getBackgroundColor());
		nvg.drawText(Icon.USER, acX + acWidth - 25, acY + 91, Color.WHITE, 10, Fonts.ICON);
		
		textBox.setBackgroundColor(this.getBackgroundColor());
		textBox.setFontColor(Color.WHITE);
		textBox.setPosition(acX + 10, acY + 86, 175, 20);
		textBox.setEmptyText(Icon.PENCIL, TranslateText.NAME.getText());
		textBox.draw(mouseX, mouseY, partialTicks);
		
		nvg.drawRoundedRect(acX + acWidth - 96 - 10, acY + 86 + 25, 96, 20, 4, this.getBackgroundColor());
		nvg.drawCenteredText(TranslateText.LOGIN.getText(), acX + acWidth - (96 / 2) - 10, acY + 86 + 31, Color.WHITE, 10F, Fonts.REGULAR);
		
		if (browserLogin) {
			
			tickBrowserLogin();
			
			String hint = loginError != null ? loginError : TranslateText.BROWSER_LOGIN_HINT.getText();
			
			nvg.drawCenteredText(hint, sr.getScaledWidth() / 2, acY + acHeight + 10, loginError != null ? new Color(255, 85, 85) : Color.WHITE, 9, Fonts.REGULAR);
			
			urlBox.setBackgroundColor(this.getBackgroundColor());
			urlBox.setFontColor(Color.WHITE);
			urlBox.setPosition(acX, acY + acHeight + 22, 160, 20);
			urlBox.setEmptyText(Icon.PENCIL, "URL");
			urlBox.draw(mouseX, mouseY, partialTicks);
			
			nvg.drawRoundedRect(acX + 168, acY + acHeight + 22, 52, 20, 4, this.getBackgroundColor());
			nvg.drawCenteredText(TranslateText.PASTE.getText(), acX + 168 + 26, acY + acHeight + 27, Color.WHITE, 9, Fonts.REGULAR);
			
			nvg.drawRoundedRect(acX + (acWidth / 2) - 40, acY + acHeight + 48, 80, 18, 4, this.getBackgroundColor());
			nvg.drawCenteredText(TranslateText.LOGIN.getText(), acX + (acWidth / 2), acY + acHeight + 52, Color.WHITE, 10, Fonts.REGULAR);
		}
	}
	
	@Override
	public void mouseClicked(int mouseX, int mouseY, int mouseButton) {
		
		ScaledResolution sr = new ScaledResolution(mc);
		
		Soar instance = Soar.getInstance();
		AccountManager accountManager = instance.getAccountManager();
		FileManager fileManager = instance.getFileManager();
		
		int acWidth = 220;
		int acHeight = 138;
		int acX = sr.getScaledWidth() / 2 - (acWidth / 2);
		int acY = sr.getScaledHeight() / 2 - (acHeight / 2);
		
		if(mouseButton == 0) {
			
			if(MouseUtils.isInside(mouseX, mouseY, acX + acWidth - 30, acY + 86, 20, 20)) {
				Multithreading.runAsync(() -> {
					
					File selectSkin = FileUtils.selectImageFile();
					
					if(selectSkin != null) {
						
						try {
							
							File copyFile = new File(fileManager.getCacheDir(), "skin/" + selectSkin.getName());
							BufferedImage image = ImageIO.read(selectSkin);
							
							if(image.getWidth() == 64 && image.getHeight() == 64) {
								FileUtils.copyFile(selectSkin, copyFile);
								offlineSkin = copyFile;
							}
							
						} catch(Exception e) {}
					}
				});
			}
			
			if(MouseUtils.isInside(mouseX, mouseY, acX + acWidth - 96 - 10, acY + 86 + 25, 96, 20)) {
				
				File renameFile = new File(fileManager.getCacheDir(), "skin/" + textBox.getText() + ".png");
				File headDir = new File(fileManager.getCacheDir(), "head");
				
				Account acc = new Account(textBox.getText(), "0", "0", AccountType.OFFLINE);
				
				if(offlineSkin != null) {
					offlineSkin.renameTo(renameFile);
					offlineSkin = renameFile;
				}
				
				if(offlineSkin != null && offlineSkin.exists()) {
					
					acc.setSkinFile(offlineSkin);
					
					try {
						
						BufferedImage rawImage = ImageIO.read(offlineSkin);
						BufferedImage headImage = ImageUtils.scissor(rawImage, 8, 8, 8, 8);
						BufferedImage layerImage = ImageUtils.scissor(rawImage, 40, 8, 8, 8);
						BufferedImage conbineImage = ImageUtils.combine(headImage, layerImage);
						
						ImageIO.write(ImageUtils.resize(conbineImage, 128, 128), "png", new File(headDir, acc.getName() + ".png"));
					} catch(Exception e) {}
				}
				
		        ((IMixinMinecraft) mc).setSession(new Session(acc.getName(), "0", "0", "mojang"));
		        
				if(accountManager.getAccountByName(acc.getName()) == null) {
					accountManager.getAccounts().add(acc);
				}
				
		        accountManager.setCurrentAccount(acc);
		        accountManager.save();
		        
		        offlineSkin = null;
		        
		        getAfterLoginRunnable().run();
			}
			
			if(MouseUtils.isInside(mouseX, mouseY, acX + 10, acY + 29, 200, 30)) {
				if(Mcef.isInitialized()) {
					this.setCurrentScene(this.getSceneByClass(MicrosoftLoginScene.class));
				} else if (isWindows()) {
					accountManager.getAuthenticator().loginWithPopUpWindow(getAfterLoginRunnable());
				} else {
					startBrowserLogin();
				}
			}
			
			if (browserLogin) {
				
				if (MouseUtils.isInside(mouseX, mouseY, acX, acY + acHeight + 22, 160, 20)) {
					urlBox.mouseClicked(mouseX, mouseY, mouseButton);
				}
				
				if (MouseUtils.isInside(mouseX, mouseY, acX + 168, acY + acHeight + 22, 52, 20)) {
					pasteFromClipboard();
				}
				
				if (MouseUtils.isInside(mouseX, mouseY, acX + (acWidth / 2) - 40, acY + acHeight + 48, 80, 18)) {
					confirmBrowserLogin();
				}
			}
		}
		
		textBox.mouseClicked(mouseX, mouseY, mouseButton);
	}
	
	@Override
	public void keyTyped(char typedChar, int keyCode) {
		
		if (browserLogin) {
			
			urlBox.keyTyped(typedChar, keyCode);
			
			if (keyCode == Keyboard.KEY_ESCAPE) {
				browserLogin = false;
			}
			
			return;
		}
		
		textBox.keyTyped(typedChar, keyCode);
		
		if(keyCode == Keyboard.KEY_ESCAPE) {
			this.setCurrentScene(this.getSceneByClass(MainScene.class));
		}
	}
	
	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}
	
	private void startBrowserLogin() {
		
		browserLogin = true;
		lastClipboardCheck = 0;
		lastClipboardValue = MICROSOFT_LOGIN_URL;
		
		try {
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(MICROSOFT_LOGIN_URL), null);
		} catch (Throwable ignored) {
		}
		
		openInSystemBrowser(MICROSOFT_LOGIN_URL);
	}
	
	private void tickBrowserLogin() {
		
		long now = System.currentTimeMillis();
		
		if (now - lastClipboardCheck < CLIPBOARD_CHECK_INTERVAL) {
			return;
		}
		
		lastClipboardCheck = now;
		
		try {
			
			String clipboard = IOUtils.getStringFromClipboard();
			
			if (clipboard == null) {
				return;
			}
			
			String trimmed = clipboard.trim();
			
			if (!trimmed.startsWith("http") || !trimmed.contains("code=") || trimmed.equals(lastClipboardValue)) {
				return;
			}
			
			lastClipboardValue = trimmed;
			
			completeBrowserLogin(trimmed);
		} catch (Throwable e) {
			// Clipboard is not always reachable (e.g. caciocavallo on iOS); the
			// Paste/Login controls stay available as a manual fallback.
		}
	}
	
	private void pasteFromClipboard() {
		
		try {
			String clipboard = IOUtils.getStringFromClipboard();
			if (clipboard != null) {
				urlBox.setText(clipboard.trim());
			}
		} catch (Throwable e) {
			SoarLogger.error("Failed to read clipboard: " + e);
		}
	}
	
	private void confirmBrowserLogin() {
		
		String url = urlBox.getText().trim();
		
		if (!url.contains("code=")) {
			return;
		}
		
		completeBrowserLogin(url);
	}
	
	private void completeBrowserLogin(String url) {
		
		browserLogin = false;
		loginError = null;
		
		urlBox.setText(url);
		
		Multithreading.runAsync(() -> {
			
			boolean success = false;
			
			try {
				success = Soar.getInstance().getAccountManager().getAuthenticator().loginWithUrl(url);
			} catch (Throwable e) {
				SoarLogger.error("Browser login failed", new Exception(e));
			}
			
			final boolean loggedIn = success;
			
			mc.addScheduledTask(() -> {
				if (loggedIn) {
					getAfterLoginRunnable().run();
				} else {
					browserLogin = true;
					lastClipboardValue = "";
					loginError = TranslateText.LOGIN_FAILED.getText();
				}
			});
		});
	}
	
	private static void openInSystemBrowser(String url) {
		
		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(new URI(url));
				return;
			}
		} catch (Throwable ignored) {
		}
		
		try {
			
			String os = System.getProperty("os.name", "").toLowerCase();
			
			if (os.contains("mac")) {
				Runtime.getRuntime().exec(new String[] {"open", url});
			} else if (!os.contains("win")) {
				Runtime.getRuntime().exec(new String[] {"xdg-open", url});
			}
		} catch (Throwable e) {
			SoarLogger.error("Failed to open the system browser: " + e);
		}
	}
	
	private Runnable getAfterLoginRunnable() {
		
		Runnable runnable = new Runnable() {
			@Override
			public void run() {
		        if(Soar.getInstance().getApi().isFirstLogin()) {
					setCurrentScene(getSceneByClass(LastMessageScene.class));
		        } else {
					setCurrentScene(getSceneByClass(MainScene.class));
		        }
			}
		};
		
		return runnable;
	}
}
