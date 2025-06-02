package com.util;

import fr.bmartel.speedtest.SpeedTestReport;
import fr.bmartel.speedtest.SpeedTestSocket;
import fr.bmartel.speedtest.inter.ISpeedTestListener;
import fr.bmartel.speedtest.model.SpeedTestError;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A utility class that measures the network speed by using JSpeedTest
 */
public class SpeedTester {
    private static final Logger logger = LogManager.getLogger(SpeedTester.class);

    private static final String[] TEST_URLS = {
            "https://speed.cloudflare.com/__down?bytes=1048576", // Cloudflare (1MB)
            "https://www.google.com/images/branding/googlelogo/1x/googlelogo_color_272x92dp.png", // Google logo (small file)
            "https://proof.ovh.net/files/1Mb.dat" // OVH test file (1MB)
    };

    // Default speed in MBps if test fails
    private static final double DEFAULT_SPEED = 2.0;
    private static final int TEST_DURATION_SECONDS = 5;

    /**
     * Executes a speed test
     *
     * @return - Returns the speed in Mbps
     */
    public CompletableFuture<Double> measureDownloadSpeed() {
        logger.info("Initiate speed test");

        CompletableFuture<Double> future = new CompletableFuture<>();
        SpeedTestSocket speedTestSocket = new SpeedTestSocket();

        // Tracks that the future is completed
        final AtomicReference<Boolean> completed = new AtomicReference<>(false);

        // References to the last measured speed
        AtomicReference<Double> lastSpeed = new AtomicReference<>(DEFAULT_SPEED);

        // Add listener for test results
        speedTestSocket.addSpeedTestListener(new ISpeedTestListener() {
            @Override
            public void onCompletion(SpeedTestReport speedTestReport) {
                double speedMbps = speedTestReport.getTransferRateBit().doubleValue() / 1_000_000.0;
                logger.info("Speed test completed: {} Mbps", speedMbps);

                if (!completed.getAndSet(true)) {
                    future.complete(speedMbps);
                }
            }

            @Override
            public void onProgress(float percent, SpeedTestReport speedTestReport) {
                double speedMbps = speedTestReport.getTransferRateBit().doubleValue() / 1_000_000.0;
                lastSpeed.set(speedMbps);
                logger.debug("Spped test progress: {}%, Speed: {} Mbps", percent, speedMbps);
            }

            @Override
            public void onError(SpeedTestError speedTestError, String errorMessage) {
                logger.error("Error during speed test: {}", errorMessage);
            }
        });

        tryNextUrl(speedTestSocket, future, completed, lastSpeed, 0);

        // Set timeout so the program wont hang
        new Thread(() -> {
            try {
                Thread.sleep(TEST_DURATION_SECONDS * 1000);
                if (!completed.getAndSet(true)) {
                    logger.info("Speed test timed out, using default or last measured speed");
                    future.complete(lastSpeed.get());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();

        return future;
    }

    private void tryNextUrl(SpeedTestSocket speedTestSocket, CompletableFuture<Double> future,
                            AtomicReference<Boolean> completed, AtomicReference<Double> lastSpeed, int urlIndex) {
        if (urlIndex >= TEST_URLS.length) {
            if (!completed.getAndSet(true)) {
                logger.warn("All speed test URLs failed, using default speed: {} Mbps", DEFAULT_SPEED);
                future.complete(lastSpeed.get());
            }
            return;
        }

        String testUrl = TEST_URLS[urlIndex];
        logger.info("Trying speed test with URL: {}", testUrl);

        try {
            // Force stop any previous tests
            speedTestSocket.forceStopTask();

            // One time listener for specific test
            speedTestSocket.addSpeedTestListener(new ISpeedTestListener() {
                @Override
                public void onCompletion(SpeedTestReport speedTestReport) {

                }

                @Override
                public void onProgress(float v, SpeedTestReport speedTestReport) {

                }

                @Override
                public void onError(SpeedTestError speedTestError, String errorMessage) {
                    logger.error("Error during measuring speed with {}:{}", testUrl, errorMessage);

                    // Try next URL after short delay
                    new Thread(() -> {
                        try {
                            Thread.sleep(500);
                            tryNextUrl(speedTestSocket, future, completed, lastSpeed, urlIndex);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                }
            });

            speedTestSocket.startDownload(testUrl);
        }catch (Exception e) {
            logger.error("Exception during speed test setup: {}", e.getMessage());
            tryNextUrl(speedTestSocket, future, completed, lastSpeed, urlIndex + 1);
        }
    }

    public CompletableFuture<Double> measureDownloadSpeed(String testUrl) {
        logger.info("Initiate speed test");

        CompletableFuture<Double> future = new CompletableFuture<>();
        SpeedTestSocket speedTestSocket = new SpeedTestSocket();

        AtomicReference<Double> lastSpeedMeasured = new AtomicReference<>(0.0);

        speedTestSocket.addSpeedTestListener(new ISpeedTestListener() {
            @Override
            public void onCompletion(SpeedTestReport report) {
                // bit/s ---> Mbit/s
                double speedMpbs = report.getTransferRateBit().doubleValue() / 1_000_000.0;
                logger.info("Speed measuring success: {:.2f} Mbps", speedMpbs);
                future.complete(lastSpeedMeasured.get());
            }

            @Override
            public void onProgress(float percent, SpeedTestReport report) {
                //bit/s --> Mbit/s
                double speedMbps = report.getTransferRateBit().doubleValue() / 1_000_000.0;
                lastSpeedMeasured.set(speedMbps);
                logger.debug("Progress: {}%, Speed: {:.2f} Mbps", percent, speedMbps);
            }

            @Override
            public void onError(SpeedTestError speedTestError, String errorMessage) {
                logger.error("Error during measuring speed: {}", errorMessage);
                future.completeExceptionally(new RuntimeException(errorMessage));
            }
        });

        //Start downloading
        speedTestSocket.startDownload(testUrl);

        // Create a new thread that will stop the test after the designated time period.
        new Thread(() -> {
            try {
                Thread.sleep(TEST_DURATION_SECONDS * 1000);
                speedTestSocket.forceStopTask();
                if (!future.isDone()) {
                    logger.info("Finish the measurement due to time limit");
                    future.complete(lastSpeedMeasured.get());
                }
            } catch (InterruptedException e) {
                logger.error("Terminate the measuring thread: {}", e.getMessage());
                Thread.currentThread().interrupt();
            }
        }).start();

        return future;
    }

    /**
     * A simple static method for the fast measurement of download speed in Mbps.
     * This method will block the running thread, until the measurement is complete.
     *
     * @return The measured speed. It will be -1 if there is an error.
     */
    public static double quickMeasureDownloadSpeedMbps() {
        try {
            return new SpeedTester().measureDownloadSpeed().get();
        } catch (Exception e) {
            logger.error("Error during the 'Quick Speed Measurement': {}", e.getMessage());
            return -1;
        }
    }
}
