package me.eldodebug.soar.management.download;

import java.io.File;
import java.util.ArrayList;

import me.eldodebug.soar.Soar;
import me.eldodebug.soar.logger.SoarLogger;
import me.eldodebug.soar.management.download.file.DownloadFile;
import me.eldodebug.soar.management.download.file.DownloadZipFile;
import me.eldodebug.soar.management.file.FileManager;
import me.eldodebug.soar.utils.Multithreading;
import me.eldodebug.soar.utils.file.FileUtils;
import me.eldodebug.soar.utils.network.HttpUtils;

public class DownloadManager {

	private ArrayList<DownloadFile> downloadFiles = new ArrayList<DownloadFile>();
	
	private boolean downloaded;
	
	public DownloadManager() {
		
		FileManager fileManager = Soar.getInstance().getFileManager();
		
		downloaded = false;
		
		downloadFiles.add(new DownloadFile("https://files.soarclient.com/ytdlp/ytdlp.exe",
				"ytdlp.exe", new File(fileManager.getExternalDir(), "ytdlp"), 13947126));
		downloadFiles.add(new DownloadZipFile("https://files.soarclient.com/ytdlp/ffmpeg.zip",
				"ffmpeg.zip", new File(fileManager.getExternalDir(), "ffmpeg"), 51986763, 147600195));
		downloadFiles.add(new DownloadZipFile("https://files.soarclient.com/v1/data/cef/windows.zip",
				"cef.zip", new File(fileManager.getExternalDir(), "cef"), 115822583, 265676507));
		
		Multithreading.runAsync(() -> startDownloads());
	}
	
	private void startDownloads() {
		
		try {
			// These files are Windows only (ytdlp.exe / ffmpeg / cef windows build)
			// and the host is often unreachable (HTTP/SSL failures). Skipping them
			// must never block the welcome scene, which waits on isDownloaded().
			if (!isWindows()) {
				return;
			}
			
			for(DownloadFile df : downloadFiles) {
				
				if(!df.getOutputDir().exists()) {
					df.getOutputDir().mkdirs();
				}
				
				if(df instanceof DownloadZipFile) {
					
					DownloadZipFile dzf = (DownloadZipFile) df;
					
					if(FileUtils.getDirectorySize(dzf.getOutputDir()) != dzf.getUnzippedSize()) {
						
						File outputFile = new File(dzf.getOutputDir(), dzf.getFileName());
						
						HttpUtils.downloadFile(dzf.getUrl(), outputFile);
						FileUtils.unzip(outputFile, dzf.getOutputDir());
						outputFile.delete();
					}
				} else {
					
					File outputFile = new File(df.getOutputDir(), df.getFileName());
					
					if(outputFile.length() != df.getSize()) {
						HttpUtils.downloadFile(df.getUrl(), outputFile);
					}
				}
			}
			
			checkFiles();
		} catch (Exception e) {
			SoarLogger.error("Failed to download client data", e);
		} finally {
			downloaded = true;
		}
	}
	
	private static boolean isWindows() {
		String os = System.getProperty("os.name", "");
		return os.toLowerCase().contains("win");
	}
	
	private void checkFiles() {
		
		for(DownloadFile df : downloadFiles) {
			
			if(df instanceof DownloadZipFile) {
				
				DownloadZipFile dzf = (DownloadZipFile) df;
				
				if(FileUtils.getDirectorySize(dzf.getOutputDir()) != dzf.getUnzippedSize()) {
					SoarLogger.error("Missing downloaded data: " + dzf.getOutputDir().getName());
				}
			} else {
				
				File outputFile = new File(df.getOutputDir(), df.getFileName());
				
				if(outputFile.length() != df.getSize()) {
					SoarLogger.error("Missing downloaded data: " + outputFile.getName());
				}
			}
		}
		
		downloaded = true;
	}

	public boolean isDownloaded() {
		return downloaded;
	}
}