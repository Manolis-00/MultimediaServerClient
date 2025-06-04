package com.util;


import com.model.StreamConfig;
import com.model.VideoFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A class that handles the FFMPEG functions for video coding, decoding and streaming.
 * It utilizes the {@link ProcessBuilder} in order to execute the FFMPEG functions.
 */
public class FFMPEGHandler {
    private static final Logger logger = LogManager.getLogger(FFMPEGHandler.class); //TODO Make the required changes on the log4j2.xml and to the pom, in order to migrate from log4j ---> slf4j in the project

    private static final String FFMPEG_COMMAND = "ffmpeg";
    private static final String FFPROBE_COMMAND = "ffprobe";
    private static final String TRANSCODED_DIR = "transcoded";

    private Process currentProcess;

    /**
     * Checks that FFMPEG is available to the system.
     * @return true if FFMPEF is available, false if it is not
     */
    public boolean isFFMPEFGAvailable() {
        try {
            Process process = new ProcessBuilder(FFMPEG_COMMAND, "-version")
                    .redirectErrorStream(true)
                    .start();

            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            logger.error("Error during the FFMPEG availability check: {}", e.getMessage());
            return false;
        }
    }

    /**
     * If the directory for the transcoded files, does not exist, it creates it.
     *
     * @return              The directory's absolute path
     * @throws IOException If there is an error during the directory creation process
     */
    private Path ensureTranscodedDirectory() throws IOException {
        Path directory = Paths.get(TRANSCODED_DIR);
        if (!Files.exists(directory)) {
                Files.createDirectories(directory);
                logger.info("The directory for the transcoded files has been created: {}", directory.toAbsolutePath());
        }
        return directory;
    }

    // TODO the method will be heavily refactored

    /**
     * Transcodes a video to different resolutions and formats
     *
     * @param videoFile
     * @return
     * @throws IOException
     */
    public VideoFile transcodeVideo(VideoFile videoFile) throws IOException {
        logger.info("Start of the transcoding process: {}", videoFile.getFileName());
        Path transcodedDirectory = ensureTranscodedDirectory();

        // Add detailed logging for each step, in order to understand the root of errors
        try {
            // 1st Step: Validate input parameters
            if (videoFile == null) {
                throw new IllegalArgumentException("VideoFile cannot be null");
            }
            if (videoFile.getFilePath() == null) {
                throw new IllegalArgumentException("VideoFile path cannot be null");
            }

            logger.info("Input validation passed for: {}", videoFile.getFilePath());

            // 2nd Step: Check if the source file exists
            Path sourceFile = Paths.get(videoFile.getFilePath());
            if (!Files.exists(sourceFile)) {
                throw new IOException("Source video file does not exist: " + sourceFile.toAbsolutePath());
            }

            logger.info("Source file exists: {}", sourceFile.toAbsolutePath());

            // 3rd Step: Ensure transcoded directory exists
            Path transcodedDir = ensureTranscodedDirectory();
            logger.info("Transcoded directory ready: {}", transcodedDir.toAbsolutePath());

            // 4th Step: Prepare video-specific directory
            String videoName = videoFile.getFileName().substring(0, videoFile.getFileName().lastIndexOf('.'));
            Path videoDir = transcodedDir.resolve(videoName);

            if (!Files.exists(videoDir)) {
                Files.createDirectories(videoDir);
                logger.info("Created video directory: {}");
            } else {
                logger.info("Video directory already exists: {}", videoDir.toAbsolutePath());
            }

            // 5th Step: Process each transcoding configuration
            List<StreamConfig> streamConfigList = List.of(
                    StreamConfig.HD_1080P,
                    StreamConfig.HD_720P,
                    StreamConfig.SD_480P,
                    StreamConfig.SD_360P,
                    StreamConfig.LOW_240P);

            logger.info("Starting transcoding for {} configurations", streamConfigList.size());

            for (int i = 0; i < streamConfigList.size(); i++) {
                StreamConfig config = streamConfigList.get(i);
                logger.info("Processing configuration {}/{}: {}", i + 1, streamConfigList.size(), config);

                try {
                    processVideoConfiguration(videoFile, config, videoDir);
                    logger.info("Successfully processed configuration: {}", config);
                } catch (Exception e) {
                    logger.error("Failed to process configuration {}: {}", config, e.getMessage(), e);
                    // Continue with other configurations instead of failing completely
                }
            }

            logger.info("Transcoding completed for video: {}", videoFile.getFileName());
            return videoFile;
        } catch (Exception e) {
            logger.error("Transcoded failed for video {}: {}", videoFile.getFileName(), e.getMessage());
            throw new IOException("Transcoded failed: " + e.getMessage(), e);
        }
    }

    /**
     * Helper method to process a single video configuration
     *
     * @param videoFile
     * @param config
     * @param videoDir
     * @throws IOException
     * @throws InterruptedException
     */
    private void processVideoConfiguration(VideoFile videoFile, StreamConfig config, Path videoDir)
        throws IOException, InterruptedException {

        // Generate output filename
        String videoName = videoFile.getFileName().substring(0, videoFile.getFileName().lastIndexOf('.'));
        String outputFileName = String.format("%s_%dp.%s", videoName, config.getVideoHeight(), config.getVideoFormat());
        Path outputPath = videoDir.resolve(outputFileName);

        // Check if already exists
        if (Files.exists(outputPath)) {
            logger.info("Transcoded file already exists: {}", outputFileName);
            addExistingTranscodedVersion(videoFile, config, outputPath);
            return;
        }

        logger.info("Creating new transcoded version: {}", outputFileName);

        // Validate FFMPEG parameters before creating the command
        String[] ffmpegParams = config.getFFMPEGParameters();
        if (ffmpegParams == null) {
            throw new IllegalStateException("FFMPEG parameters cannot be null for config: " + config);
        }

        logger.debug("FFMPEG Parameters: {}", Arrays.toString(ffmpegParams));

        // Build the complete FFMPEG command
        List<String> command = new ArrayList<>();
        command.add(FFMPEG_COMMAND);
        command.add("-i");
        command.add(videoFile.getFilePath());
        command.addAll(Arrays.asList(ffmpegParams));
        command.add(outputPath.toString());

        logger.info("Executing FFMPEG command: {}", String.join(" ", command));

        // Execute command with detailed error handling
        executeFFMPEGCommand(command, outputFileName);

        // Verify the output file was created successfully
        if (!Files.exists(outputPath) || Files.size(outputPath) == 0) {
            throw new IOException("FFMPEG failed to create output file or file is empty: " + outputPath);
        }

        logger.info("Successfully created transcoded file: {} (size: {} bytes)", outputFileName, Files.size(outputPath));

        // Add the transcoded version to the video file
        VideoFile.TranscodedVersion version = new VideoFile.TranscodedVersion(
                outputPath.toString(),
                config.getVideoFormat(),
                config.getVideoWidth(),
                config.getVideoHeight(),
                config.getBitrate()
        );
        videoFile.addTranscodedVersion(version);
    }

    /**
     * Helper method to execute FFMPEG command with proper error handling
     * @param command
     * @param outputFileName
     * @throws IOException
     * @throws InterruptedException
     */
    private void executeFFMPEGCommand(List<String> command, String outputFileName)
        throws IOException, InterruptedException {

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        logger.debug("Starting FFMPEG process for: {}", outputFileName);
        Process process = processBuilder.start();

        // Capture and Log FFMPEG output for debugging
        StringBuilder outputCapture = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.debug("FFMPEG: {}", line);
                outputCapture.append(line).append("\n");

                // Log common error indicators
                if (line.toLowerCase().contains("error") || line.toLowerCase().contains("failed")) {
                    logger.warn("Potential FFMPEG error detected: {}", line);
                }
            }
        }

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            String errorOutput = outputCapture.toString();
            logger.error("FFMPEG process failed with exit code {}", exitCode);
            logger.error("FFMPEG output: {}", errorOutput);
            throw new IOException("FFMPEG process failed with exit code " + exitCode + ". Output: " + errorOutput);
        }

        logger.info("FFMPEG process completed successfully for: {}", outputFileName);
    }

    /**
     * Helper method to add an existing transcoded version to the video file
     *
     * @param videoFile
     * @param config
     * @param outputPath
     */
    private void addExistingTranscodedVersion(VideoFile videoFile, StreamConfig config, Path outputPath) {
        VideoFile.TranscodedVersion version = new VideoFile.TranscodedVersion(
                outputPath.toString(),
                config.getVideoFormat(),
                config.getVideoWidth(),
                config.getVideoHeight(),
                config.getBitrate()
        );
        videoFile.addTranscodedVersion(version);
    }

    /**
     * Initiates the streaming of a video, with the required parameters
     *
     * @param videoPath
     * @param config
     * @param outputConsumer
     * @return
     */
    public CompletableFuture<Void> startStreaming(String videoPath, StreamConfig config, Consumer<String> outputConsumer) {
        logger.info("Start the streaming of video: {} with configuration: {}", videoPath, config);

        //Create the FFMPEG command for streaming
        List<String> command = new ArrayList<>();
        command.add(FFMPEG_COMMAND);
        command.add("-re");
        command.add("-i");
        command.add(videoPath);
        command.addAll(List.of(config.getFFMPEGParameters()));
        command.add("udp://127.0.0.1:" + config.getStreamPort());

        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            // Start of the FFMPEG Process
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            currentProcess = process;

            // Create a thread to read the output
            Thread outputThread = new Thread(() ->{
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logger.debug("FFMPEG Stream: {}", line);
                        if (outputConsumer != null) {
                            outputConsumer.accept(line);
                        }
                    }
                } catch (IOException e) {
                    logger.error("Error during the reading stage of the output of the FFMPEG: {}", e.getMessage());
                }
            });

            outputThread.setDaemon(true);
            outputThread.start();

            // Create a Thread for watching the process
            Thread watcherThread = new Thread(() -> {
                int exitCode = 0;
                try {
                    exitCode = process.waitFor();
                    logger.info("The transmission is complete with output code: {}", exitCode);
                    future.complete(null);
                } catch (InterruptedException e) {
                    logger.error("Stop the watching process: {}", e.getMessage());
                    future.completeExceptionally(e);
                }
            });
            watcherThread.setDaemon(true);
            watcherThread.start();

        } catch (IOException e) {
            logger.error("Error during the start of streaming: {}", e.getMessage());
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Starts playing a stream from a remote source
     * @param serverAddress
     * @param config
     * @param outputConsumer
     * @return
     */
    public CompletableFuture<Void> startPlayback(String serverAddress, StreamConfig config, Consumer<String> outputConsumer) {
        logger.info("Start streaming from {} : {} with configuration: {}", serverAddress, config.getStreamPort(), config);

        // Create the FFMPEG command for streaming
        List<String> streamCommand = new ArrayList<>();
        streamCommand.add(FFMPEG_COMMAND);
        streamCommand.add("udp://" + serverAddress + ":" + config.getStreamPort());
        streamCommand.add("-c:v");
        streamCommand.add("copy");
        streamCommand.add("-c:a");
        streamCommand.add("copy");
        streamCommand.add("-f");
        streamCommand.add("sdl");
        streamCommand.add("-"); // Exit to the SDL

        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            // Start the FFMPEG process
            Process process = new ProcessBuilder(streamCommand)
                    .redirectErrorStream(true)
                    .start();

            currentProcess = process;

            // Create the exit reading thread
            Thread outputThread = new Thread(() -> {
                try (BufferedReader inputStreamReader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String inputStreamLine;
                    while ((inputStreamLine = inputStreamReader.readLine()) != null) {
                        logger.debug("FFMPEG Player: {}", inputStreamLine);
                        if (outputConsumer != null) {
                            outputConsumer.accept(inputStreamLine);
                        }
                    }
                } catch (IOException e) {
                    logger.error("Error during reading the exit of the FFMPEG: {}", e.getMessage());
                }
            });
            outputThread.setDaemon(true);
            outputThread.start();

            // Create a Watcher thread that watches the completion of the process
            Thread watcherThread = new Thread(() -> {
                try {
                    int exitCode = process.waitFor();
                    logger.info("The streaming was successfully completed with exit code: {}", exitCode);
                    future.complete(null);
                } catch (InterruptedException e) {
                    logger.error("Error: Stop watching the process: {}", e.getMessage());
                    future.completeExceptionally(e);
                }
            });
            watcherThread.setDaemon(true);
            watcherThread.start();

        } catch (IOException e) {
            logger.error("Error during the initiation of the streaming: {}", e.getMessage());
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Terminates the current FFMPEG process if it exists
     */
    public void stopCurrentProcess() {
        if (currentProcess != null && currentProcess.isAlive()) {
            logger.info("Termination the current FFMPEG process");
            currentProcess.destroy();

            try {
                // Wait up to 3 seconds for the termination
                if (!currentProcess.waitFor(3, TimeUnit.SECONDS)) {
                    logger.warn("The process was not terminated smoothly. Force termination");
                    currentProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                logger.error("Interruption during waiting for termination: {}", e.getMessage());
                Thread.currentThread().interrupt();
            }
            currentProcess = null;
        }
    }
}
