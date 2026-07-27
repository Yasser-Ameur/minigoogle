package com.minigoogle.crawler.downloader;

import com.minigoogle.crawler.model.DownloadedPage;
import com.minigoogle.crawler.model.UrlTask;

/**
 * Responsible for downloading the content of a URL.
 */
public interface Downloader {
    
    /**
     * Downloads the page for the given task.
     *
     * @param task The task containing the URL to download.
     * @return The downloaded page, or null if the download failed (e.g., timeout, 404).
     */
    DownloadedPage download(UrlTask task);
}
