package com.model;

import com.util.SerialNumbers;

import java.io.Serial;
import java.io.Serializable;

/**
 * Class that includes configuration for the video streaming.
 * It is utilized for the communication between Client - Server
 *
 * @param videoCodec The video codec (e.g. h264)
 * @param audioCodec The audio codec (e.g. aac)
 * @param streamPort The streaming port
 */
public record StreamConfig(int videoWidth, int videoHeight, int bitrate, String videoCodec, String audioCodec,
                           String videoFormat, Integer streamPort) implements Serializable {
    @Serial
    private static final long serialVersionUID = SerialNumbers.FOUR;

    // Constants for the predefined video resolutions with proper codec and format values
    // Using mp4 format for transcoded files (more compatible than mpegts for storage)
    public static final StreamConfig HD_1080P = new StreamConfig(1920, 1080, 5000000,
            "libx264", "aac", "mp4", null);
    public static final StreamConfig HD_720P = new StreamConfig(1280, 720, 2500000,
            "libx264", "aac", "mp4", null);
    public static final StreamConfig SD_480P = new StreamConfig(854, 480, 1000000,
            "libx264", "aac", "mp4", null);
    public static final StreamConfig SD_360P = new StreamConfig(640, 360, 500000,
            "libx264", "aac", "mp4", null);
    public static final StreamConfig LOW_240P = new StreamConfig(426, 240, 250000,
            "libx264", "aac", "mp4", null);

    // Constant defining the streaming port
    public static final Integer DEFAULT_STREAMING_PORT = 8889;

    /**
     * Full constructor of the class
     *
     * @param videoWidth
     * @param videoHeight
     * @param bitrate
     * @param videoCodec
     * @param audioCodec
     * @param videoFormat
     * @param streamPort
     */
    public StreamConfig(int videoWidth, int videoHeight, int bitrate, String videoCodec, String audioCodec,
                        String videoFormat, Integer streamPort) {
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.bitrate = bitrate;
        this.videoCodec = videoCodec != null ? videoCodec : "libx264"; // Default to H.264
        this.audioCodec = audioCodec != null ? audioCodec : "aac";     // Default to AAC
        this.videoFormat = videoFormat != null ? videoFormat : "mpegts"; // Default to MPEG-TS
        this.streamPort = streamPort != null ? streamPort : DEFAULT_STREAMING_PORT;
    }

    /**
     * Creates a new configuration with a port that is different than the default
     *
     * @param newStreamPort
     * @return
     */
    public StreamConfig withStreamPort(Integer newStreamPort) {
        return new StreamConfig(this.videoWidth, this.videoHeight, this.bitrate, this.videoCodec, this.audioCodec,
                this.videoFormat, newStreamPort);
    }

    /**
     * Chooses the best resolution for the given connection speed
     *
     * @param connectionSpeedMbps
     * @return
     */
    public static StreamConfig getBestConfigurationForSpeed(double connectionSpeedMbps) {
        long speedBps = (long) (connectionSpeedMbps * 1_000_000);

        double safetyFactor = 0.7;
        long safeBps = (long) (speedBps * safetyFactor);

        if (safeBps >= HD_1080P.bitrate()) {
            return HD_1080P.withStreamPort(DEFAULT_STREAMING_PORT);
        } else if (safeBps >= HD_720P.bitrate()) {
            return HD_720P.withStreamPort(DEFAULT_STREAMING_PORT);
        } else if (safeBps >= SD_480P.bitrate()) {
            return SD_480P.withStreamPort(DEFAULT_STREAMING_PORT);
        } else if (safeBps >= SD_360P.bitrate()) {
            return SD_360P.withStreamPort(DEFAULT_STREAMING_PORT);
        } else {
            return LOW_240P.withStreamPort(DEFAULT_STREAMING_PORT);
        }
    }

    //Getters

    public String getResolution() {
        return videoWidth + "x" + videoHeight;
    }

    public double getBitrateMbps() {
        return bitrate / 1_000_000.0;
    }

    @Override
    public String toString() {
        return String.format("%dx%d (%.2f Mbps, %s)", videoWidth, videoHeight, getBitrateMbps(), videoFormat);
    }

    public String[] getFFMPEGParameters() {
        return new String[]{
                "-c:v", videoCodec,
                "-c:a", audioCodec,
                "-b:v", bitrate + "",
                "-b:a", "128k",
                "-vf", "scale=" + videoWidth + ":" + videoHeight,
                "-f", videoFormat
        };
    }
}