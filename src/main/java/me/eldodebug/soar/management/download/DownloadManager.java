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

public class DownloadManager {

	private ArrayList<DownloadFile> downloadFiles = new ArrayList<DownloadFile>();
	
	private boolean downloaded;
	
	public DownloadManager() {
		
		FileManager fileManager = Soar.getInstance().getFileManager();
		
		// files.soarclient.com is shut down. The welcome scene waits on
		// isDownloaded(), so never let remote downloads decide whether the client
		// can start; only verify what is already on disk.
		downloaded = true;
		
		downloadFiles.add(new DownloadFile("https://files.soarclient.com/ytdlp/ytdlp.exe",
				"ytdlp.exe", new File(fileManager.getExternalDir(), "ytdlp"), 13947126));
		downloadFiles.add(new DownloadZipFile("https://files.soarclient.com/ytdlp/ffmpeg.zip",
				"ffmpeg.zip", new File(fileManager.getExternalDir(), "ffmpeg"), 51986763, 147600195));
		downloadFiles.add(new DownloadZipFile("https://files.soarclient.com/v1/data/cef/windows.zip",
				"cef.zip", new File(fileManager.getExternalDir(), "cef"), 115822583, 265676507));
		
		Multithreading.runAsync(() -> startDownloads());
	}
	
	private void startDownloads() {
		
		// The CDN is offline, so downloading would only stall the startup gate on
		// HTTP timeouts. Log the state of any files that are already present.
		try {
			checkFiles();
		} catch (Exception e) {
			SoarLogger.error("Failed to download client data", e);
		} finally {
			downloaded = true;
		}
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