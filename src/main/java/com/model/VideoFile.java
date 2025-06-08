package com.model;

import com.util.SerialNumbers;

import java.io.File;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Class that represents a {@link VideoFile} and its data.
 * The class implements {@link Serializable} in order to be transmitted through network.
 */
public class VideoFile implements Serializable {

    @Serial
    private static final long serialVersionUID = SerialNumbers.ONE;
    private final String fileName;
    private final String filePath;
    private final String fileExtension;
    private final long fileSize;
    private final List<TranscodedVersion> transcodedVersionList;

    /**
     * Constructor for the creation of a {@link VideoFile} object,
     * by a {@link File} object.
     *
     * @param file The video {@link File}
     */
    public VideoFile(File file) {
        this.fileName = file.getName();
        this.filePath = file.getAbsolutePath();
        this.fileSize = file.getAbsoluteFile().length();

        int lastDotIndex = fileName.lastIndexOf('.');
        this.fileExtension = (lastDotIndex > 0) ?
                fileName.substring(lastDotIndex + 1).toLowerCase() : "";

        this.transcodedVersionList = new ArrayList<>();
    }

    // Getters

    public String getFileName() {
        return fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void addTranscodedVersion(TranscodedVersion version) {
        this.transcodedVersionList.add(version);
    }

    public List<TranscodedVersion> getTranscodedVersionList() {
        return transcodedVersionList;
    }

    @Override
    public String toString() {
        return fileName;
    }

    /**
     * Return the best transcoded version for the current connection speed
     *
     * @param connectionSpeedMbps
     * @return
     */
    public TranscodedVersion getBestVersionForSpeed(double connectionSpeedMbps) {
        // If there are no transcoded versions, the method returns null
        if (transcodedVersionList.isEmpty()) {
            return null;
        }

        // We find the best resolution, that has a bitrate smaller than the connection speed.
        TranscodedVersion bestVersion = null;
        double bestBitrate = 0;

        for (TranscodedVersion version : transcodedVersionList) {
            double bitrateMbps = version.getBitrate() / 1_000_000.0; //bps ---> Mbps

            // if the bitrate is smaller than the connection speed, and greater than the current best
            if (bitrateMbps <= connectionSpeedMbps && bitrateMbps > bestBitrate) {
                bestBitrate = bitrateMbps;
                bestVersion = version;
            }
        }

        // If there isn't any suitable versions, the method returns the version with the lowest bitrate
        if (bestVersion == null) {
            bestVersion = transcodedVersionList.stream()
                    .min((v1, v2) -> Double.compare(v1.getBitrate(), v2.getBitrate()))
                    .orElse(null);
        }

        return bestVersion;
    }

    /**
     * This class represents the transcoded video version.
     * It must be serializable, so that it can be transmitted via network.
     */
    public static class TranscodedVersion implements Serializable {
        @Serial
        private static final long serialVersionUID = SerialNumbers.TWO;
        private final String transcodedVideoPath;
        private final String transcodedVideoFormat;
        private final int videoPixelWidth;
        private final int videoPixelHeight;
        private final long bitrate;

        /**
         * Constructor for the creation of the transcoded video format.
         *
         * @param transcodedVideoPath   The path of the transcoded file, of the specific format
         * @param transcodedVideoFormat The format
         * @param videoPixelWidth       The width of the video
         * @param videoPixelHeight      The height of the video
         * @param bitrate               The bitrate in bit/second
         */
        public TranscodedVersion(String transcodedVideoPath, String transcodedVideoFormat, int videoPixelWidth,
                                 int videoPixelHeight, long bitrate) {
            this.transcodedVideoPath = transcodedVideoPath;
            this.transcodedVideoFormat = transcodedVideoFormat;
            this.videoPixelWidth = videoPixelWidth;
            this.videoPixelHeight = videoPixelHeight;
            this.bitrate = bitrate;
        }

        // Getters
        public String getTranscodedVideoPath() {
            return transcodedVideoPath;
        }

        public long getBitrate() {
            return bitrate;
        }

        @Override
        public String toString() {
            return String.format("%s (%s, %dx%d, %.2f Mbps)",
                    transcodedVideoFormat, transcodedVideoPath, videoPixelWidth, videoPixelHeight, bitrate / 1_000_000.0);
        }
    }
}
