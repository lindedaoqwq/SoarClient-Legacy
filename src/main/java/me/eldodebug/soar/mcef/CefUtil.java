package me.eldodebug.soar.mcef;

import java.io.File;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;

import me.eldodebug.soar.Soar;
import me.eldodebug.soar.logger.SoarLogger;
import me.eldodebug.soar.management.file.FileManager;

public final class CefUtil {
	
    private CefUtil() {
    }

    private static boolean init;
    private static CefApp cefAppInstance;
    private static CefClient cefClientInstance;

    static boolean init() {
    	
    	if (init) {
    		return true;
    	}
    	
    	try {
    		FileManager fileManager = Soar.getInstance().getFileManager();
    		File cacheDir = new File(fileManager.getCacheDir(), "mcef");
    		
    		if(!cacheDir.exists()) {
    			fileManager.createDir(cacheDir);
    		}
    		
    		String[] cefSwitches = new String[]{
    				"--autoplay-policy=no-user-gesture-required",
    				"--disable-web-security"
    		};

    		if (!CefApp.startup(cefSwitches)) {
    			return false;
    		}

    		CefSettings cefSettings = new CefSettings();
    		cefSettings.windowless_rendering_enabled = true;
    		cefSettings.background_color = cefSettings.new ColorType(0, 255, 255, 255);
    		cefSettings.cache_path = cacheDir.getAbsolutePath();
    		
    		cefAppInstance = CefApp.getInstance(cefSwitches, cefSettings);
    		cefClientInstance = cefAppInstance.createClient();
    		
    		return init = true;
    	} catch (Throwable e) {
    		// CEF ships desktop natives only; on iOS (and other unsupported
    		// platforms) loading libjcef fails with UnsatisfiedLinkError. Treat it
    		// as "browser unavailable" instead of taking the whole client down.
    		SoarLogger.error("CEF is unavailable on this platform: " + e);
    		init = false;
    		return false;
    	}
    }

    static void shutdown() {
        if (isInit()) {
            init = false;
            cefClientInstance.dispose();
            cefAppInstance.dispose();
        }
    }

    static boolean isInit() {
        return init;
    }

    static CefApp getCefApp() {
        return cefAppInstance;
    }

    static CefClient getCefClient() {
        return cefClientInstance;
    }
}