package com.util;


import com.model.StreamConfig;
import com.model.VideoFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
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
    private static final String FFPLAY_COMMAND = "ffplay";
    private static final String TRANSCODED_DIR = "transcoded";

    private Process currentProcess;

    /**
     * Checks that FFMPEG is available to the system.
     *
     * @return true if FFMPEG is available, false if it is not
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
     * @return The directory's absolute path
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
                logger.info("Created video directory: {}", videoDir);
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
                    // TODO Continue with other configurations instead of failing completely
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
        String outputFileName = String.format("%s_%dp.%s", videoName, config.videoHeight(), config.videoFormat());
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
                config.videoFormat(),
                config.videoWidth(),
                config.videoHeight(),
                config.bitrate()
        );
        videoFile.addTranscodedVersion(version);
    }

    /**
     * Helper method to execute FFMPEG command with proper error handling
     *
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
                config.videoFormat(),
                config.videoWidth(),
                config.videoHeight(),
                config.bitrate()
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

        // Use transcoding parameters that are optimized for streaming
        command.add("-c:v");
        command.add("libx264");  // Force H.264 codec
        command.add("-preset");
        command.add("ultrafast");  // Fast encoding for real-time
        command.add("-tune");
        command.add("zerolatency");  // Optimize for low latency
        command.add("-c:a");
        command.add("aac");
        command.add("-b:v");
        command.add(config.bitrate() + "");
        command.add("-b:a");
        command.add("128k");
        command.add("-vf");
        command.add("scale=" + config.videoWidth() + ":" + config.videoHeight());
        command.add("-f");
        command.add("mpegts");  // Use MPEG-TS format for streaming
        command.add("-pkt_size");
        command.add("1316");  // Optimal packet size for UDP
        command.add("udp://127.0.0.1:" + config.streamPort() + "?pkt_size=1316");

        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            // Start of the FFMPEG Process
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            currentProcess = process;

            Thread outputThread = readOutputThread(outputConsumer, process);
            outputThread.start();

            Thread watcherThread = getWatcherThread(process, future);
            watcherThread.start();

        } catch (IOException e) {
            logger.error("Error during the start of streaming: {}", e.getMessage());
            future.completeExceptionally(e);
        }

        return future;
    }

    private static Thread getWatcherThread(Process process, CompletableFuture<Void> future) {
        Thread watcherThread = new Thread(() -> {
            int exitCode = 0;
            try {
                exitCode = process.waitFor();
                logger.info("The transmission is complete with output code: {}", exitCode);

                if (exitCode != 0) {
                    future.completeExceptionally(new RuntimeException("FFMPEG exited with code: " + exitCode));
                } else {
                    future.complete(null);
                }
            } catch (InterruptedException e) {
                logger.error("Stop the watching process: {}", e.getMessage());
                future.completeExceptionally(e);
            }
        });
        watcherThread.setDaemon(true);
        return watcherThread;
    }

    private Thread readOutputThread(Consumer<String> outputConsumer, Process process) {
        Thread outputThread = new Thread(() -> {
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
        return outputThread;
    }

    /**
     * Starts playing a stream from a remote source
     *
     * @param serverAddress
     * @param config
     * @param outputConsumer
     * @return
     */
    public CompletableFuture<Void> startPlayback(String serverAddress, StreamConfig config, Consumer<String> outputConsumer) {
        logger.info("Start streaming from {} : {} with configuration: {}", serverAddress, config.streamPort(), config);

        // Create the FFPLAY command for playback
        List<String> playCommand = new ArrayList<>();

        // First, check if ffplay is available, otherwise use ffmpeg with SDL
        String playbackCommand = FFPLAY_COMMAND;
        boolean useFFPlay = isFFPlayAvailable();

        if (useFFPlay) {
            // Use ffplay for playback
            playCommand.add(playbackCommand);
            playCommand.add("-i");
            playCommand.add("udp://" + serverAddress + ":" + config.streamPort());
            playCommand.add("-fflags");
            playCommand.add("nobuffer");  // Reduce buffering
            playCommand.add("-flags");
            playCommand.add("low_delay");  // Low latency mode
            playCommand.add("-probesize");
            playCommand.add("32");  // Minimal probe size
            playCommand.add("-sync");
            playCommand.add("ext");  // External sync
            playCommand.add("-vf");
            playCommand.add("setpts=N/FRAME_RATE/TB");  // Smooth playback
            playCommand.add("-window_title");
            playCommand.add("Streaming Video");
        } else {
            // Fallback to ffmpeg with SDL output
            logger.warn("ffplay not found, using ffmpeg with SDL output");
            playCommand.add(FFMPEG_COMMAND);
            playCommand.add("-i");
            playCommand.add("udp://" + serverAddress + ":" + config.streamPort());
            playCommand.add("-fflags");
            playCommand.add("nobuffer");
            playCommand.add("-flags");
            playCommand.add("low_delay");
            playCommand.add("-probesize");
            playCommand.add("32");
            playCommand.add("-vf");
            playCommand.add("setpts=N/FRAME_RATE/TB");
            playCommand.add("-f");
            playCommand.add("sdl");
            playCommand.add("Streaming Video");
        }

        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            logger.info("Starting playback with command: {}", String.join(" ", playCommand));

            // Start the playback process
            Process process = new ProcessBuilder(playCommand)
                    .redirectErrorStream(true)
                    .start();

            currentProcess = process;

            // Create the output reading thread
            Thread outputThread = readOutputThread(outputConsumer, process);
            outputThread.setDaemon(true);
            outputThread.start();

            // Create a Watcher thread that watches the completion of the process
            Thread watcherThread = new Thread(() -> {
                try {
                    int exitCode = process.waitFor();
                    logger.info("The playback was completed with exit code: {}", exitCode);

                    if (exitCode != 0 && exitCode != 255) {  // 255 is normal exit for ffplay when window is closed
                        future.completeExceptionally(new RuntimeException("Playback exited with code: " + exitCode));
                    } else {
                        future.complete(null);
                    }
                } catch (InterruptedException e) {
                    logger.error("Error: Stop watching the process: {}", e.getMessage());
                    future.completeExceptionally(e);
                }
            });
            watcherThread.setDaemon(true);
            watcherThread.start();

        } catch (IOException e) {
            logger.error("Error during the initiation of the playback: {}", e.getMessage());
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Checks if ffplay is available on the system
     *
     * @return true if ffplay is available, false otherwise
     */
    private boolean isFFPlayAvailable() {
        try {
            Process process = new ProcessBuilder(FFPLAY_COMMAND, "-version")
                    .redirectErrorStream(true)
                    .start();

            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            logger.debug("ffplay not available: {}", e.getMessage());
            return false;
        }
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
