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
import me.eldodebug.soar.management.account.microsoft.MicrosoftAuthentication;
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
	
	private boolean loginInProgress;
	
	private static final String MICROSOFT_LOGIN_URL = "https://login.live.com/oauth20_authorize.srf?client_id=00000000402b5328&response_type=code&redirect_uri=https://login.live.com/oauth20_desktop.srf&scope=XboxLive.signin%20offline_access&prompt=login";
	
	private File offlineSkin;
	
	public AccountScene(GuiSoarMainMenu parent) {
		super(parent);
		
		// The Microsoft redirect URL carries a long "code" parameter; the
		// default 256 character limit truncated it and made the code exchange
		// fail with an unreachable/broken token.
		urlBox.setMaxStringLength(8192);
	}
	
	public boolean isLoginInProgress() {
		return loginInProgress;
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
		
		if (loginInProgress) {
			drawLoginProgress(sr, instance, nvg);
			return;
		}
		
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
	
	private void drawLoginProgress(ScaledResolution sr, Soar instance, NanoVGManager nvg) {
		
		MicrosoftAuthentication authenticator = instance.getAccountManager().getAuthenticator();
		int progress = authenticator.getLoginProgress();
		TranslateText stageKey = authenticator.getLoginStage();
		
		int width = sr.getScaledWidth();
		int height = sr.getScaledHeight();
		
		nvg.drawRect(0, 0, width, height, new Color(0, 0, 0, 150));
		
		int panelWidth = 240;
		int panelHeight = 86;
		int panelX = width / 2 - panelWidth / 2;
		int panelY = height / 2 - panelHeight / 2;
		
		nvg.drawRoundedRect(panelX, panelY, panelWidth, panelHeight, 8, this.getBackgroundColor());
		
		nvg.drawCenteredText(TranslateText.LOGIN_IN_PROGRESS.getText(), panelX + panelWidth / 2, panelY + 16, Color.WHITE, 14, Fonts.REGULAR);
		
		if (stageKey != null) {
			nvg.drawCenteredText(stageKey.getText(), panelX + panelWidth / 2, panelY + 42, new Color(210, 210, 210), 9, Fonts.REGULAR);
		}
		
		int barX = panelX + 20;
		int barY = panelY + 60;
		int barWidth = panelWidth - 40;
		
		nvg.drawRoundedRect(barX, barY, barWidth, 6, 3, new Color(255, 255, 255, 40));
		nvg.drawRoundedRect(barX, barY, Math.max(3, barWidth * progress / 100), 6, 3, instance.getColorManager().getCurrentColor().getInterpolateColor());
		
		nvg.drawCenteredText(progress + "%", panelX + panelWidth / 2, panelY + 70, Color.WHITE, 8, Fonts.REGULAR);
	}
	
	@Override
	public void mouseClicked(int mouseX, int mouseY, int mouseButton) {
		
		if (loginInProgress) {
			return;
		}
		
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
		
		if (loginInProgress) {
			return;
		}
		
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
		loginError = null;
		
		try {
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(MICROSOFT_LOGIN_URL), null);
		} catch (Throwable ignored) {
		}
		
		openInSystemBrowser(MICROSOFT_LOGIN_URL);
	}
	
	private void pasteFromClipboard() {
		
		try {
			
			String clipboard = extractUrl(IOUtils.getStringFromClipboard());
			
			if (clipboard == null || clipboard.isEmpty()) {
				loginError = TranslateText.CLIPBOARD_EMPTY.getText();
				return;
			}
			
			loginError = null;
			urlBox.setText(clipboard);
		} catch (Throwable e) {
			SoarLogger.error("Failed to read clipboard: " + e);
			loginError = TranslateText.CLIPBOARD_EMPTY.getText();
		}
	}
	
	private void confirmBrowserLogin() {
		
		String url = extractUrl(urlBox.getText());
		
		// Validate the pasted redirect URL before starting the login, so an
		// incomplete/truncated link reports a clear error instead of looking
		// like the token request itself failed.
		if (url == null || !(url.startsWith("https://") || url.startsWith("http://")) || !url.contains("code=")) {
			loginError = TranslateText.INVALID_URL.getText();
			return;
		}
		
		browserLogin = false;
		loginError = null;
		loginInProgress = true;
		
		// The link contains a one-time authorization code: drop it from both
		// the clipboard and the input box as soon as the login starts.
		urlBox.setText("");
		clearClipboard();
		
		MicrosoftAuthentication authenticator = Soar.getInstance().getAccountManager().getAuthenticator();
		
		Multithreading.runAsync(() -> {
			
			boolean success = authenticator.loginWithUrl(url);
			
			mc.addScheduledTask(() -> {
				loginInProgress = false;
				
				if (success) {
					getAfterLoginRunnable().run();
				} else {
					browserLogin = true;
					
					// Prefer the step-specific reason (XSTS/region/ownership...)
					// over the generic "login failed" text.
					String reason = authenticator.getLastError();
					
					loginError = reason != null && !reason.isEmpty() ? reason : TranslateText.LOGIN_FAILED.getText();
				}
			});
		});
	}
	
	/**
	 * Users copy the whole browser address bar, sometimes with surrounding
	 * text. Keep only the plain http(s) URL so the authorization code can be
	 * parsed reliably.
	 */
	private static String extractUrl(String raw) {
		
		if (raw == null) {
			return null;
		}
		
		String text = raw.trim();
		int start = text.indexOf("https://");
		
		if (start < 0) {
			start = text.indexOf("http://");
		}
		
		if (start < 0) {
			return text;
		}
		
		text = text.substring(start);
		int end = text.length();
		
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (Character.isWhitespace(c) || c == '"' || c == '\'' || c == '<' || c == '>') {
				end = i;
				break;
			}
		}
		
		return text.substring(0, end).trim();
	}
	
	private static void clearClipboard() {
		try {
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(""), null);
		} catch (Throwable ignored) {
		}
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
