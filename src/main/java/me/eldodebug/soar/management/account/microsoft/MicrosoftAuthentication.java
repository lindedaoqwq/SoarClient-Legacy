package me.eldodebug.soar.management.account.microsoft;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.util.UUIDTypeAdapter;

import me.eldodebug.soar.Soar;
import me.eldodebug.soar.SoarAPI;
import me.eldodebug.soar.injection.interfaces.IMixinMinecraft;
import me.eldodebug.soar.logger.SoarLogger;
import me.eldodebug.soar.management.account.Account;
import me.eldodebug.soar.management.account.AccountManager;
import me.eldodebug.soar.management.account.AccountType;
import me.eldodebug.soar.management.account.skin.SkinDownloader;
import me.eldodebug.soar.management.cape.CapeManager;
import me.eldodebug.soar.management.file.FileManager;
import me.eldodebug.soar.management.language.TranslateText;
import me.eldodebug.soar.management.profile.mainmenu.BackgroundManager;
import me.eldodebug.soar.management.profile.mainmenu.impl.CustomBackground;
import me.eldodebug.soar.utils.network.HttpUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Session;

public class MicrosoftAuthentication {

	private static final String CLIENT_ID = "00000000402b5328";
	private static final String REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf";
	private static final String TOKEN_URL = "https://login.live.com/oauth20_token.srf";
	private static final String XBL_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
	private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
	private static final String XBOX_PROFILE_URL = "https://profile.xboxlive.com/users/me/profile/settings?settings=PublicGamerpic,Gamertag";
	private static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
	private static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
	private static final String DEMO_UUID = "00000000-0000-0000-0000-000000000000";

	private Minecraft mc = Minecraft.getMinecraft();

	private SkinDownloader skinDownloader;

	private boolean loginSucceeded;

	private volatile TranslateText loginStage;

	public TranslateText getLoginStage() {
		return loginStage;
	}

	private void setLoginStage(TranslateText stage) {
		loginStage = stage;
	}

	private volatile int loginProgress;

	public int getLoginProgress() {
		return loginProgress;
	}

	/**
	 * Human readable reason for the last failed attempt, shown by the login UI
	 * instead of a generic "login failed" message.
	 */
	private volatile String lastError;

	public String getLastError() {
		return lastError;
	}

	private void setProgress(int progress, TranslateText stage) {
		loginProgress = progress;
		loginStage = stage;
	}

	public MicrosoftAuthentication() {
		skinDownloader = new SkinDownloader();
	}

	public void loginWithRefreshToken(String refreshToken) {

		if (refreshToken == null || refreshToken.isEmpty() || refreshToken.equals("0") || refreshToken.equals("null")) {
			SoarLogger.error("Refresh token is missing for the current account, login required");
			return;
		}

		loginSucceeded = false;
		loginProgress = 0;
		loginStage = null;
		lastError = null;

		SoarLogger.info("Refreshing Microsoft token (length=" + refreshToken.length() + ")");

		try {

			JsonObject response = HttpUtils.readJson(TOKEN_URL + "?client_id=" + CLIENT_ID + "&grant_type=refresh_token&refresh_token=" + refreshToken, null);

			if (response == null) {
				SoarLogger.error("Refresh token request failed (network unavailable)");
				return;
			}

			if (!response.has("access_token")) {
				SoarLogger.error("Refresh token rejected: " + describeError(response));
				return;
			}

			SoarLogger.info("Refresh token accepted, exchanging with Xbox Live");

			getXboxLiveToken(response.get("access_token").getAsString(),
					response.has("refresh_token") ? response.get("refresh_token").getAsString() : refreshToken);
		} catch (Throwable e) {
			SoarLogger.error("Refresh token login failed", new Exception(e));
		}
	}

	public boolean loginWithUrl(String url) {

		loginSucceeded = false;
		loginProgress = 0;
		loginStage = null;
		lastError = null;

		try {
			getMicrosoftToken(new URL(url));
		} catch (MalformedURLException e) {
			SoarLogger.error("Invalid login URL: " + url);
			fail(TranslateText.LOGIN_ERROR_TOKEN);
		} catch (Throwable e) {
			SoarLogger.error("Microsoft login failed", new Exception(e));
			fail(TranslateText.LOGIN_ERROR_NETWORK);
		} finally {
			if (!loginSucceeded) {
				loginStage = null;
			}
		}

		return loginSucceeded;
	}

	public void loginWithPopUpWindow(Runnable afterLogin) {
		new MicrosoftLoginWindow(afterLogin);
	}

	private void getMicrosoftToken(URL tokenURL) {

		String code = extractAuthorizationCode(tokenURL.toString());

		if (code == null) {
			SoarLogger.error("Login URL has no code parameter: " + tokenURL);
			fail(TranslateText.LOGIN_ERROR_TOKEN);
			return;
		}

		// The browser hands out the code already URL-encoded; encoding it again
		// made login.live.com reject the request.
		SoarLogger.info("Authorization code received (length=" + code.length() + ")");

		setProgress(10, TranslateText.LOGIN_STAGE_TOKEN);

		JsonObject response = HttpUtils.readJson(TOKEN_URL + "?client_id=" + CLIENT_ID
				+ "&grant_type=authorization_code&redirect_uri=" + REDIRECT_URI + "&code=" + code, null);

		if (response == null) {
			fail(TranslateText.LOGIN_ERROR_NETWORK);
			return;
		}

		if (!response.has("access_token") || !response.has("refresh_token")) {
			SoarLogger.error("Authorization code exchange failed: " + describeError(response));
			fail(TranslateText.LOGIN_ERROR_TOKEN, describeError(response));
			return;
		}

		setProgress(20, TranslateText.LOGIN_STAGE_XBOX);

		getXboxLiveToken(response.get("access_token").getAsString(), response.get("refresh_token").getAsString());
	}

	private void getXboxLiveToken(String token, String refreshToken) {

		JsonObject properties = new JsonObject();
		properties.addProperty("AuthMethod", "RPS");
		properties.addProperty("SiteName", "user.auth.xboxlive.com");
		properties.addProperty("RpsTicket", "d=" + token);

		JsonObject request = new JsonObject();
		request.add("Properties", properties);
		request.addProperty("RelyingParty", "http://auth.xboxlive.com");
		request.addProperty("TokenType", "JWT");

		JsonObject response = HttpUtils.postJson(XBL_AUTH_URL, request);

		if (response == null) {
			fail(TranslateText.LOGIN_ERROR_NETWORK);
			return;
		}

		if (!response.has("Token")) {
			SoarLogger.error("Xbox Live authentication failed: " + describeError(response));
			fail(TranslateText.LOGIN_ERROR_XBL, describeError(response));
			return;
		}

		SoarLogger.info("Xbox Live authenticated, requesting XSTS");
		setProgress(40, TranslateText.LOGIN_STAGE_XSTS);

		String xblToken = response.get("Token").getAsString();

		// The XSTS ticket used for Minecraft is separate from the one used for
		// the Xbox profile, exactly like the reference implementation.
		XstsResult minecraftXsts = requestXsts(xblToken, "rp://api.minecraftservices.com/", true);

		if (minecraftXsts == null) {
			return;
		}

		setProgress(50, TranslateText.LOGIN_STAGE_XBOX_PROFILE);

		String gamertag = fetchGamertag(xblToken);

		if (gamertag != null) {
			SoarLogger.info("Xbox profile resolved: " + gamertag);
		}

		setProgress(60, TranslateText.LOGIN_STAGE_MINECRAFT);

		getMinecraftToken(minecraftXsts.uhs, minecraftXsts.token, refreshToken, gamertag);
	}

	private XstsResult requestXsts(String xblToken, String relyingParty, boolean reportError) {

		JsonPrimitive jsonToken = new JsonPrimitive(xblToken);
		JsonArray userTokens = new JsonArray();
		userTokens.add(jsonToken);

		JsonObject properties = new JsonObject();
		properties.addProperty("SandboxId", "RETAIL");
		properties.add("UserTokens", userTokens);

		JsonObject request = new JsonObject();
		request.add("Properties", properties);
		request.addProperty("RelyingParty", relyingParty);
		request.addProperty("TokenType", "JWT");

		JsonObject response = HttpUtils.postJson(XSTS_URL, request);

		if (response == null) {
			if (reportError) {
				fail(TranslateText.LOGIN_ERROR_NETWORK);
			}
			return null;
		}

		if (response.has("XErr")) {
			TranslateText reason = xstsError(response);

			SoarLogger.error("XSTS rejected (" + relyingParty + "): " + describeError(response));

			if (reportError) {
				fail(reason, describeError(response));
			}
			return null;
		}

		if (!response.has("Token") || !response.has("DisplayClaims")) {
			SoarLogger.error("XSTS response is incomplete (" + relyingParty + "): " + describeError(response));

			if (reportError) {
				fail(TranslateText.LOGIN_ERROR_XSTS, describeError(response));
			}
			return null;
		}

		// The reference implementation guards every level of DisplayClaims:
		// a malformed response used to throw and crash the whole login.
		if (!response.get("DisplayClaims").isJsonObject()) {
			if (reportError) {
				fail(TranslateText.LOGIN_ERROR_XSTS, "DisplayClaims missing");
			}
			return null;
		}

		JsonObject claims = response.getAsJsonObject("DisplayClaims");

		if (!claims.has("xui") || !claims.get("xui").isJsonArray()) {
			if (reportError) {
				fail(TranslateText.LOGIN_ERROR_XSTS, "DisplayClaims.xui missing");
			}
			return null;
		}

		JsonArray xui = claims.getAsJsonArray("xui");

		if (xui.size() == 0 || !xui.get(0).isJsonObject()
				|| !xui.get(0).getAsJsonObject().has("uhs")) {
			if (reportError) {
				fail(TranslateText.LOGIN_ERROR_XSTS, "DisplayClaims.xui is empty");
			}
			return null;
		}

		SoarLogger.info("XSTS authorized (" + relyingParty + ")");

		return new XstsResult(response.get("Token").getAsString(),
				xui.get(0).getAsJsonObject().get("uhs").getAsString());
	}

	private String fetchGamertag(String xblToken) {

		XstsResult xboxXsts = requestXsts(xblToken, "http://xboxlive.com", false);

		if (xboxXsts == null) {
			// The gamertag is only used as a fallback display name, so a failure
			// here must not abort an otherwise valid Minecraft login.
			return null;
		}

		Map<String, String> headers = new HashMap<>();
		headers.put("x-xbl-contract-version", "2");
		headers.put("Authorization", "XBL3.0 x=" + xboxXsts.uhs + ";" + xboxXsts.token);

		JsonObject response = HttpUtils.readJson(XBOX_PROFILE_URL, headers);

		if (response == null || !response.has("profileUsers") || !response.get("profileUsers").isJsonArray()) {
			SoarLogger.warn("Xbox profile request failed");
			return null;
		}

		try {

			JsonArray profileUsers = response.getAsJsonArray("profileUsers");

			if (profileUsers.size() == 0 || !profileUsers.get(0).isJsonObject()) {
				return null;
			}

			JsonObject firstUser = profileUsers.get(0).getAsJsonObject();

			if (!firstUser.has("settings") || !firstUser.get("settings").isJsonArray()) {
				return null;
			}

			JsonArray settings = firstUser.getAsJsonArray("settings");

			for (int i = 0; i < settings.size(); i++) {

				if (!settings.get(i).isJsonObject()) {
					continue;
				}

				JsonObject setting = settings.get(i).getAsJsonObject();

				if (setting.has("id") && "Gamertag".equals(setting.get("id").getAsString())
						&& setting.has("value")) {
					return setting.get("value").getAsString();
				}
			}
		} catch (Throwable e) {
			SoarLogger.warn("Failed to parse Xbox profile: " + e);
		}

		return null;
	}

	private void getMinecraftToken(String uhs, String token, String refreshToken, String gamertag) {

		JsonObject request = new JsonObject();
		request.addProperty("identityToken", String.format("XBL3.0 x=%s;%s", uhs, token));

		JsonObject response = HttpUtils.postJson(MC_LOGIN_URL, request);

		if (response == null) {
			fail(TranslateText.LOGIN_ERROR_NETWORK);
			return;
		}

		if (!response.has("access_token")) {
			SoarLogger.error("Minecraft token request failed: " + describeError(response));
			fail(TranslateText.LOGIN_ERROR_MC_TOKEN, describeError(response));
			return;
		}

		SoarLogger.info("Minecraft token obtained, reading profile");

		setProgress(80, TranslateText.LOGIN_STAGE_PROFILE);

		getMinecraftProfile(response.get("access_token").getAsString(), refreshToken, gamertag);
	}

	private void getMinecraftProfile(String token, String refreshToken, String gamertag) {

		Map<String, String> headers = new HashMap<>();
		headers.put("Authorization", "Bearer " + token);

		JsonObject request = HttpUtils.readJson(MC_PROFILE_URL, headers);

		if (request == null) {
			fail(TranslateText.LOGIN_ERROR_NETWORK);
			return;
		}

		if (request.has("name") && request.has("id") && !request.get("id").getAsString().isEmpty()) {

			String name = request.get("name").getAsString();
			String uuid = request.get("id").getAsString();

			SoarLogger.info("Resolved Minecraft profile: " + name + " (" + uuid + ")");

			completeLogin(name, uuid, token, refreshToken, false);
			return;
		}

		// No Java Edition profile on this account: use the Xbox gamertag as a
		// demo identity instead of failing the whole login.
		if (request.has("error") && "NOT_FOUND".equals(request.get("error").getAsString())) {

			String name = gamertag != null && !gamertag.isEmpty() ? "Demo." + gamertag : "Demo";

			SoarLogger.warn("No Minecraft profile on this account, using demo identity: " + name);

			completeLogin(name, DEMO_UUID, token, refreshToken, true);
			return;
		}

		SoarLogger.error("Minecraft profile request failed: " + describeError(request));
		fail(TranslateText.LOGIN_ERROR_PROFILE, describeError(request));
	}

	private void completeLogin(String name, String uuid, String token, String refreshToken, boolean demo) {

		Soar instance = Soar.getInstance();
		AccountManager accountManager = instance.getAccountManager();
		FileManager fileManager = instance.getFileManager();
		File headDir = new File(fileManager.getCacheDir(), "head");

		// Match the stored account by its stable profile UUID first so a name
		// change (or a second login) does not create a duplicate entry.
		Account account = demo ? null : accountManager.getAccountByUuid(uuid);

		if (account == null) {
			account = accountManager.getAccountByName(name);
		}

		if (account == null) {

			account = new Account(name, uuid, refreshToken, AccountType.MICROSOFT);
			accountManager.getAccounts().add(account);
		} else {

			account.setName(name);
			account.setUuid(uuid);
			account.setRefreshToken(refreshToken);
		}

		if (!headDir.exists()) {
			fileManager.createDir(headDir);
		}

		if (!demo) {
			skinDownloader.downloadFace(headDir, name, UUIDTypeAdapter.fromString(uuid));
		}

		((IMixinMinecraft) mc).setSession(new Session(name, uuid, token, "mojang"));

		accountManager.setCurrentAccount(account);

		// Persist the credentials right away; the check below talks to a host
		// that may be offline and must not be able to prevent saving.
		accountManager.save();

		loginSucceeded = true;
		lastError = null;
		setProgress(100, null);

		try {
			check();
		} catch (Throwable e) {
			SoarLogger.error("Post login check failed", new Exception(e));
		}
	}

	private void check() {

		Soar instance = Soar.getInstance();
		SoarAPI api = Soar.getInstance().getApi();
		CapeManager capeManager = instance.getCapeManager();
		BackgroundManager backgroundManager = instance.getProfileManager().getBackgroundManager();

		if (!api.isSpecialUser()) {

			if (capeManager.getCurrentCape().isPremium()) {
				capeManager.setCurrentCape(capeManager.getCapeByName("None"));
			}

			if (backgroundManager.getCurrentBackground() instanceof CustomBackground) {
				backgroundManager.setCurrentBackground(backgroundManager.getBackgroundById(0));
			}
		}
	}

	private TranslateText xstsError(JsonObject response) {

		String xerr = response.has("XErr") ? response.get("XErr").getAsString() : "";

		switch (xerr) {
			case "2148916233":
				return TranslateText.LOGIN_ERROR_XSTS_NO_ACCOUNT;
			case "2148916235":
				return TranslateText.LOGIN_ERROR_XSTS_REGION;
			case "2148916236":
			case "2148916237":
				return TranslateText.LOGIN_ERROR_XSTS_VERIFY;
			case "2148916238":
				return TranslateText.LOGIN_ERROR_XSTS_UNDERAGE;
			default:
				return TranslateText.LOGIN_ERROR_XSTS;
		}
	}

	private static String extractAuthorizationCode(String url) {

		if (url == null) {
			return null;
		}

		int index = url.indexOf("code=");

		if (index < 0) {
			return null;
		}

		String code = url.substring(index + 5);
		int end = code.length();

		for (int i = 0; i < code.length(); i++) {

			char c = code.charAt(i);

			if (c == '&' || c == '#' || c == '"' || c == '\'' || c == '<' || c == '>'
					|| Character.isWhitespace(c)) {
				end = i;
				break;
			}
		}

		code = code.substring(0, end).trim();

		return code.isEmpty() ? null : code;
	}

	private static String describeError(JsonObject response) {

		if (response == null) {
			return null;
		}

		String detail = null;

		if (response.has("error_description")) {
			detail = response.get("error_description").getAsString();
		} else if (response.has("Message")) {
			detail = response.get("Message").getAsString();
		} else if (response.has("errorMessage")) {
			detail = response.get("errorMessage").getAsString();
		} else if (response.has("error")) {
			detail = response.get("error").getAsString();
		} else if (response.has("XErr")) {
			detail = "XErr=" + response.get("XErr").getAsString();
		}

		if (detail == null) {
			return null;
		}

		detail = detail.trim();

		return detail.length() > 120 ? detail.substring(0, 120) + "..." : detail;
	}

	private void fail(TranslateText reason) {
		fail(reason, null);
	}

	private void fail(TranslateText reason, String detail) {

		TranslateText key = reason != null ? reason : TranslateText.LOGIN_FAILED;
		String message = key.getText();

		if (detail != null && !detail.isEmpty()) {
			message = message + " (" + detail + ")";
		}

		lastError = message;

		SoarLogger.error("Microsoft login failed: " + message);
	}

	private static class XstsResult {

		private final String token;
		private final String uhs;

		private XstsResult(String token, String uhs) {
			this.token = token;
			this.uhs = uhs;
		}
	}

	public SkinDownloader getSkinDownloader() {
		return skinDownloader;
	}
}
