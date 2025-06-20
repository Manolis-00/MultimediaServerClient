package com.server;


import com.model.StreamConfig;
import com.model.VideoFile;
import com.util.FFMPEGHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * This class is responsible for managing the video files on the Server.
 * It handles the methods of finding, loading and converting the video to different resolutions
 */
public class VideoManager {

    private static final Logger logger = LogManager.getLogger(VideoManager.class);

    /**
     * The types of the supported files
     */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("mp4", "avi", "mkv", "mov", "webm");

    /**
     * Directory with the initial videos
     */
    private final String videosDirectory;

    /**
     * Cache for the loaded videos
     */
    private final Map<String, VideoFile> videoCache;

    /**
     * The FFMPEG Handler
     */
    private final FFMPEGHandler ffmpegHandler;

    /**
     * Constructor that initializes the {@link VideoManager} with a videos directory
     *
     * @param videosDirectory - The directory that contains the video files
     */
    public VideoManager(String videosDirectory) {
        this.videosDirectory = videosDirectory;
        this.videoCache = new ConcurrentHashMap<>();
        this.ffmpegHandler = new FFMPEGHandler();

        // Create the directory if it does not exist
        Path videosDirectoryPath = Paths.get(videosDirectory);
        if (!Files.exists(videosDirectoryPath)) {
            try {
                Files.createDirectories(videosDirectoryPath);
                logger.info("The videos directory has been created: {}", videosDirectoryPath.toAbsolutePath());
            } catch (IOException e) {
                logger.error("Unable to create the videos directory: {}", e.getMessage());
            }
        }
    }

    /**
     * Loads the available video files from the videos directory
     *
     * @return - A {@link List} with the available video files
     */
    public List<VideoFile> loadAvailableVideos() {
        logger.info("Loading the available video files from the directory: {}", videosDirectory);

        try {
            Path videosDirectoryPath = Paths.get(videosDirectory);
            List<File> videoFilesList = Files.list(videosDirectoryPath)
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(this::isSupportedVideoFile)
                    .collect(Collectors.toList());

            for (File file : videoFilesList) {
                // Check if a video is in the cache. If not, create it and add it to the cache.
                if (!videoCache.containsKey(file.getName())) {
                    VideoFile videoFile = new VideoFile(file);

                    loadExistingTranscodedVersions(videoFile);

                    videoCache.put(file.getName(), videoFile);
                    logger.info("Added new video: {} (with {} transcoded versions)", file.getName(),
                            videoFile.getTranscodedVersionList().size());
                }
            }

            // Remove from the cache videos that no longer exist in the directory
            List<String> existingFileNames = videoFilesList.stream()
                    .map(File::getName)
                    .collect(Collectors.toList());

            List<String> filesToBeRemovedFromTheCache = videoCache.keySet().stream()
                    .filter(fileName -> !existingFileNames.contains(fileName))
                    .collect(Collectors.toList());

            for (String fileNameToBeRemovedFromTheCache : filesToBeRemovedFromTheCache) {
                videoCache.remove(fileNameToBeRemovedFromTheCache);
                logger.info("Removed non-existing video from the cache: {}", fileNameToBeRemovedFromTheCache);
            }

            return new ArrayList<>(videoCache.values());
        } catch (IOException e) {
            logger.error("Error during the loading of the videos: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * This method checks the transcoded directory for the existence of transcoded videos
     * @param videoFile
     */
    private void loadExistingTranscodedVersions(VideoFile videoFile) {
        String videoName = videoFile.getFileName().substring(0, videoFile.getFileName().lastIndexOf('.'));
        Path transcodedDir = Paths.get("transcoded", videoName);

        if (!Files.exists(transcodedDir)) {
            logger.debug("No transcoded directory found for: {}", videoName);
            return;
        }

        logger.info("Checking for existing transcoded versions in: {}", transcodedDir);

        // Check for each possible resolution
        List<StreamConfig> configs = List.of(
                StreamConfig.HD_1080P,
                StreamConfig.HD_720P,
                StreamConfig.SD_480P,
                StreamConfig.SD_360P,
                StreamConfig.LOW_240P
        );

        for (StreamConfig config : configs) {
            String expectedFileName = String.format("%s_%dp.mp4", videoName, config.videoHeight());
            Path transcodedFilePath = transcodedDir.resolve(expectedFileName);

            if (Files.exists(transcodedFilePath)) {
                try {
                    long fileSize = Files.size(transcodedFilePath);
                    if (fileSize > 0) {
                        logger.info("Found existing transcoded file: {} (size: {} bytes)", expectedFileName, fileSize);

                        VideoFile.TranscodedVersion version = new VideoFile.TranscodedVersion(
                                transcodedFilePath.toString(),
                                "mp4",
                                config.videoWidth(),
                                config.videoHeight(),
                                config.bitrate()
                        );
                        videoFile.addTranscodedVersion(version);
                    } else {
                        logger.warn("Transcoded file exists but is empty: {}", expectedFileName);
                    }
                } catch (IOException e) {
                    logger.error("Error checking transcoded file size: {}", e.getMessage());
                }
            }
        }

        if (!videoFile.getTranscodedVersionList().isEmpty()) {
            logger.info("Loaded {} existing transcoded versions for: {}",
                    videoFile.getTranscodedVersionList().size(), videoFile.getFileName());
        }
    }

    /**
     * Checks whether a file is a video file supported by the application
     *
     * @param file - The {@link File} to be checked
     * @return - True if the file is a supported video file, false otherwise
     */
    private boolean isSupportedVideoFile(File file) {
        if (!file.isFile()) {
            return false;
        }

        String fileName = file.getName();
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return false;
        }

        String extension = fileName.substring(lastDotIndex + 1).toLowerCase();
        return SUPPORTED_EXTENSIONS.contains(extension);
    }

    /**
     * Finds and returns a {@link VideoFile} based on the {@link File#getName()}
     *
     * @param fileName - The name of the {@link File}
     * @return - The {@link VideoFile} if it exists. Null otherwise.
     */
    public VideoFile getVideoByFileName(String fileName) {
        return videoCache.get(fileName);
    }

    /**
     * Prepares a video for streaming, by creating the required versions.
     *
     * @param videoFile - The {@link VideoFile} to be prepared for streaming
     * @return - The aforementioned {@link VideoFile}
     * @throws IOException - If there is an error during the preparation
     */
    public VideoFile prepareVideoForStreaming(VideoFile videoFile) throws IOException {
        logger.info("Prepare the video to be streamed: {}", videoFile.getFileName());

        if (!videoFile.getTranscodedVersionList().isEmpty()) {
            logger.info("The video already has {} transcoded versions", videoFile.getTranscodedVersionList().size());
            return videoFile;
        }

        loadExistingTranscodedVersions(videoFile);

        if (!videoFile.getTranscodedVersionList().isEmpty()) {
            logger.info("Found existing transcoded versions on disk");

            videoCache.put(videoFile.getFileName(), videoFile);
            return videoFile;
        }

        logger.info("No existing transcoded versions found, starting transcoding process");
        VideoFile updatedVideo = ffmpegHandler.transcodeVideo(videoFile);

        videoCache.put(videoFile.getFileName(), updatedVideo);

        return updatedVideo;
    }
}
