package com.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A simple, thread-safe speed tester that measures download speed
 * by downloading a small file and measuring the time taken.
 */
public class SpeedTester {
    private static final Logger logger = LogManager.getLogger(SpeedTester.class);

    // Simple, reliable test URLs with small files
    private static final String[] TEST_URLS = {
            "https://www.google.com/images/branding/googlelogo/1x/googlelogo_color_272x92dp.png",
            "https://httpbin.org/bytes/1048576", // 1MB from httpbin
            "https://via.placeholder.com/1024x1024.png" // 1MB placeholder image
    };

    private static final double DEFAULT_SPEED_MBPS = 2.0; // Conservative fallback
    private static final int TIMEOUT_SECONDS = 10;
    private static final int MAX_DOWNLOAD_SIZE = 2 * 1024 * 1024; // 2MB limit

    // Singleton pattern to prevent multiple concurrent tests
    private static volatile SpeedTester instance;
    private static final Object lock = new Object();
    private volatile boolean testInProgress = false;

    /**
     * Get the singleton instance of SpeedTester
     */
    public static SpeedTester getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new SpeedTester();
                }
            }
        }
        return instance;
    }

    /**
     * Private constructor to enforce singleton pattern
     */
    private SpeedTester() {}

    /**
     * Measures download speed in a thread-safe manner.
     * Only one test can run at a time.
     */
    public CompletableFuture<Double> measureDownloadSpeed() {
        // Prevent multiple concurrent tests
        synchronized (lock) {
            if (testInProgress) {
                logger.info("Speed test already in progress, using default speed");
                return CompletableFuture.completedFuture(DEFAULT_SPEED_MBPS);
            }
            testInProgress = true;
        }

        return CompletableFuture.supplyAsync(() -> {
                    try {
                        logger.info("Starting download speed test");

                        for (String testUrl : TEST_URLS) {
                            try {
                                double speed = performSingleTest(testUrl);
                                if (speed > 0) {
                                    logger.info("Speed test completed successfully: {:.2f} Mbps using {}", speed, testUrl);
                                    return speed;
                                }
                            } catch (Exception e) {
                                logger.warn("Speed test failed for URL {}: {}", testUrl, e.getMessage());
                                // Continue to next URL
                            }
                        }

                        // All tests failed, return default speed
                        logger.warn("All speed test URLs failed, using default speed: {} Mbps", DEFAULT_SPEED_MBPS);
                        return DEFAULT_SPEED_MBPS;

                    } finally {
                        // Always reset the test flag
                        synchronized (lock) {
                            testInProgress = false;
                        }
                    }
                }).orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(throwable -> {
                    logger.error("Speed test timed out or failed: {}", throwable.getMessage());
                    synchronized (lock) {
                        testInProgress = false;
                    }
                    return DEFAULT_SPEED_MBPS;
                });
    }

    /**
     * Performs a single speed test by downloading from the given URL
     */
    private double performSingleTest(String testUrl) throws IOException {
        logger.debug("Testing with URL: {}", testUrl);

        URL url = new URL(testUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        try {
            // Configure connection
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000); // 5 second connect timeout
            connection.setReadTimeout(10000);   // 10 second read timeout
            connection.setInstanceFollowRedirects(true);

            // Add user agent to avoid being blocked
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36");

            long startTime = System.currentTimeMillis();

            // Connect and start downloading
            connection.connect();

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + connection.getResponseCode() + ": " + connection.getResponseMessage());
            }

            // Download the content
            long bytesDownloaded = 0;
            byte[] buffer = new byte[8192]; // 8KB buffer

            try (InputStream inputStream = connection.getInputStream()) {
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1 && bytesDownloaded < MAX_DOWNLOAD_SIZE) {
                    bytesDownloaded += bytesRead;
                }
            }

            long endTime = System.currentTimeMillis();
            long durationMs = endTime - startTime;

            if (durationMs <= 0 || bytesDownloaded <= 0) {
                throw new IOException("Invalid test results: duration=" + durationMs + "ms, bytes=" + bytesDownloaded);
            }

            // Calculate speed in Mbps
            double durationSeconds = durationMs / 1000.0;
            double megabits = (bytesDownloaded * 8.0) / (1024.0 * 1024.0); // Convert bytes to megabits
            double speedMbps = megabits / durationSeconds;

            logger.debug("Downloaded {} bytes in {} ms = {:.2f} Mbps", bytesDownloaded, durationMs, speedMbps);

            // Sanity check - if speed is unreasonably high or low, it's probably wrong
            if (speedMbps < 0.1 || speedMbps > 1000) {
                logger.warn("Speed test result seems unrealistic: {:.2f} Mbps, ignoring", speedMbps);
                return 0;
            }

            return speedMbps;

        } finally {
            connection.disconnect();
        }
    }

    /**
     * Static method for quick speed measurement with proper error handling
     */
    public static double quickMeasureDownloadSpeedMbps() {
        try {
            return getInstance().measureDownloadSpeed().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("Quick speed test failed: {}", e.getMessage());
            return DEFAULT_SPEED_MBPS;
        }
    }
}