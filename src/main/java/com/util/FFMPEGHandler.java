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

        // Create a list of the possible resolutions, that the video file will be transcoded to.
        List<StreamConfig> streamConfigList = List.of(
                StreamConfig.HD_1080P,
                StreamConfig.HD_720P,
                StreamConfig.SD_480P,
                StreamConfig.SD_360P,
                StreamConfig.LOW_240P);

        // Create the subdirectory for the video at hand
        String videoName = videoFile.getFileName().substring(0, videoFile.getFileName().lastIndexOf('.'));
        Path videoDirectory = transcodedDirectory.resolve(videoName);
        if (!Files.exists(videoDirectory)) {
            Files.createDirectories(videoDirectory);
        }

        for (StreamConfig config : streamConfigList) {

            String outputFileName = String.format("%s_%dp.%s", videoName, config.getVideoHeight(), config.getVideoFormat());
            Path outputPath = videoDirectory.resolve(outputFileName);

            if (Files.exists(outputPath)) {
                logger.info("The version {} already exists", outputFileName);

                VideoFile.TranscodedVersion version = new VideoFile.TranscodedVersion(
                        outputPath.toString(),
                        config.getVideoFormat(),
                        config.getVideoWidth(),
                        (config.getVideoHeight()),
                        config.getBitrate()
                );
                videoFile.addTranscodedVersion(version);
                continue;
            }

            List<String> command = new ArrayList<>();
            command.add(FFMPEG_COMMAND);
            command.add("-i");
            command.add(videoFile.getFilePath());
            command.addAll(List.of(config.getFFMPEGParameters()));
            command.add(outputPath.toString());

            logger.info("ΕΚΤΕΛΕΣΗ ΜΕΤΑΤΡΟΠΗΣ ΓΙΑ ΔΙΑΜΟΡΦΩΣΗ: {}", config);

            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    logger.debug("FFMPEG: {}", line);
                }
            }

            try {
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    logger.info("Succesful transcoding to {}", outputFileName);

                    VideoFile.TranscodedVersion version = new VideoFile.TranscodedVersion(
                            outputPath.toString(),
                            config.getVideoFormat(),
                            config.getVideoWidth(),
                            config.getVideoHeight(),
                            config.getBitrate()
                    );
                    videoFile.addTranscodedVersion(version);
                } else {
                    logger.error("Failure to transcode to {}, exit code {}", outputFileName, exitCode);
                }
            } catch (InterruptedException e) {
                logger.error("Error during the transcoding the video: {}", e.getMessage());
            }
        }
        logger.info("The transcoding process of the video is complete: {}", videoFile.getFileName());
        return videoFile;
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
