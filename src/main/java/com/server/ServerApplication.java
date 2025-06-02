package com.server;

import com.util.FFMPEGHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

/**
 * Main class for starting the Streaming Server
 */
public class ServerApplication {
    private static final Logger logger = LogManager.getLogger(ServerApplication.class);

    private static final int DEFAULT_PORT = 8888;
    private static final String DEFAULT_VIDEOS_DIRECTORY = "videos";

    /**
     * Starting point for the Server application
     */
    public static void main(String[] args) {
        logger.info("=== Start the Streaming Server ===");

        // Check for the FFMPEG
        FFMPEGHandler ffmpegHandler = new FFMPEGHandler();
        if (!ffmpegHandler.isFFMPEFGAvailable()) {
            logger.error("The FFMPEG is not available. Please make sure that you installed it, and that it is on the PATH");
            System.err.println("Error: FFMPEG is not available");
            System.err.println("Please install FFMPEG and make sure that it is located on the PATH");
            System.exit(1);
        }

        // Ascertain that the videos directory is present
        Path videosDirectory = Paths.get(DEFAULT_VIDEOS_DIRECTORY);
        if (!Files.exists(videosDirectory)) {
            try {
                Files.createDirectories(videosDirectory);
                logger.info("Created the videos directory: {}", videosDirectory.toAbsolutePath());
                System.out.println("The videos directory has been created: " + videosDirectory.toAbsolutePath());
            } catch (IOException e) {
                logger.error("Unable to create the videos directory: {}", e.getMessage());
                System.err.println("Error: Unable to create the videos directory.");
                System.exit(1);
            }
        }

        // Create the VideoManager
        VideoManager videoManager = new VideoManager(DEFAULT_VIDEOS_DIRECTORY);

        // Load the available videos
        var availableVideos = videoManager.loadAvailableVideos();

        if (availableVideos.isEmpty()) {
            logger.warn("No video files were found in the directory {}. Please add video files.", videosDirectory.toAbsolutePath());
            System.out.println("Warning: No video files were found in the directory " + videosDirectory.toAbsolutePath());
            System.out.println("Please add video files.");
        } else {
            logger.info("Loaded {} videos", availableVideos.size());
            System.out.println("Loaded " + availableVideos.size() + " videos");
            availableVideos.forEach(videoFile -> System.out.println(" - " + videoFile.getFileName()));
        }

        // Create and start the server
        StreamingServer streamingServer = new StreamingServer(DEFAULT_PORT, videoManager, ffmpegHandler);
        streamingServer.start();

        System.out.println("\nThe Streaming has started in port " + DEFAULT_PORT);
        System.out.println("Available instructions:");
        System.out.println(" - list     : Show the available videos");
        System.out.println(" - refresh  : Refresh the video list");
        System.out.println(" - exit     : Terminate the server");

        // Handle the commands from the terminal to terminate the server
        Scanner scanner = new Scanner(System.in);
        boolean isRunning = true;

        while (isRunning) {
            System.out.println("\nCommand> ");
            String command = scanner.nextLine().trim();

            switch (command.toLowerCase()) {
                case "list":
                    // Display Available video files
                    System.out.println("\nAvailable Videos: ");
                    availableVideos = videoManager.loadAvailableVideos();
                    if (availableVideos.isEmpty()) {
                        System.out.println("There were no video files available");
                    } else {
                        availableVideos.forEach(videoFile -> {
                            System.out.println(" - " + videoFile.getFileName() + " (" + formatFileSize(videoFile.getFileSize()) + ")");
                        });
                    }
                    break;

                case "refresh":
                    // Refresh the video list
                    System.out.println("Refresh the videos list");
                    availableVideos = videoManager.loadAvailableVideos();
                    System.out.println(availableVideos.size() + " video files were found.");
                    break;

                case "exit":
                    //Termination of the server
                    isRunning = false;
                    System.out.println("Terminating the server...");
                    break;

                default:
                    // Unknown command
                    if (!command.isEmpty()) {
                        System.out.println("Read the command: " + command);
                        System.out.println("Available commands: list, refresh, exit");
                    }
                    break;
            }
        }

        // Server termination
        streamingServer.stop();
        scanner.close();
        logger.info("=== Terminating Streaming Server ===");
    }

    /**
     * Formats the size of the video file to human-readable form.
     *
     * @param size  - {@link com.model.VideoFile} size in bytes
     * @return      - The size in (Kb, Mb, Gb)
     */
    public static String formatFileSize(long size) {
        if (size < 1024) {
            return size + "bytes";
        } else if (size < 1024 * 1024) {
            return String.format("%.2f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", size / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }
}
