package com.server;

import com.model.StreamConfig;
import com.model.VideoFile;
import com.network.NetworkMessage;
import com.network.enums.MessageType;
import com.util.FFMPEGHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


/**
 * The main class that handles the Server Streaming.
 * It handles the connections with the clients and the video streaming
 */
public class StreamingServer {
    private static final Logger logger = LogManager.getLogger(StreamingServer.class);

    private final int port;
    private final VideoManager videoManager;
    private final FFMPEGHandler ffmpegHandler;
    private final ExecutorService executorService;

    private ServerSocket serverSocket;
    private boolean isRunning;
    private Socket clientSocket;
    private CompletableFuture<Void> streamingFuture;

    private double clientConnectionSpeedMbps = 2.0;

    /**
     * Constructor method
     *
     * @param port
     * @param videoManager
     * @param ffmpegHandler
     */
    public StreamingServer(int port, VideoManager videoManager, FFMPEGHandler ffmpegHandler) {
        this.port = port;
        this.videoManager = videoManager;
        this.ffmpegHandler = ffmpegHandler;
        this.executorService = Executors.newCachedThreadPool();
        this.isRunning = false;
    }

    public void start() {
        if (isRunning) {
            logger.warn("The server is already running");
            return;
        }

        try {
            serverSocket = new ServerSocket(port);
            isRunning = true;
            logger.info("The streaming server has started in port {}", port);

            // Create thread for the connections acceptance
            executorService.submit(this::acceptConnections);
        } catch (IOException e) {
            logger.error("Error during the start up of the server: {}", e.getMessage());
            stop();
        }
    }

    /**
     * Stops the server and closes all the connections
     */
    public void stop() {
        isRunning = false;

        // Terminate the running stream
        if (ffmpegHandler != null) {
            ffmpegHandler.stopCurrentProcess();
        }

        // Close the connection with the client
        if (clientSocket != null && !clientSocket.isClosed()) {
            try {
                clientSocket.close();
                logger.info("Connection to the client is closed");
            } catch (IOException e) {
                logger.error("Error during the closing of the connection with the client: {}", e.getMessage());
            }
        }

        // Close the server socket
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
                logger.info("The server socket is closed");
            } catch (IOException e) {
                logger.error("Error during the closing of the server socket: {}", e.getMessage());
            }
        }

        // Termination of the executor
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("The executor service was not shut-down gracefully within the designated time period");
            }
        } catch (InterruptedException e) {
            logger.error("Interrupted while waiting for the termination of executorService: {}", e.getMessage());
            Thread.currentThread().interrupt();
        }

        logger.info("The Streaming Server is terminated");
    }

    /**
     * Method that handles the acceptance of new connections from clients
     */
    private void acceptConnections() {
        logger.info("Waiting for connections...");

        while (isRunning) {
            try {
                // Accept a new connection
                clientSocket = serverSocket.accept();
                String clientAddress = clientSocket.getInetAddress().getHostAddress();
                logger.info("New connection from client: {}", clientAddress);

                // Connection management
                handleClientConnection(clientSocket);
            } catch (IOException e) {
                if (isRunning) {
                    logger.error("Error during the connection acceptance: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Manages the connection with a client
     *
     * @param socket The connection socket
     */
    private void handleClientConnection(Socket socket) {
        executorService.submit(() -> {
            try (
                    ObjectOutputStream outputStream = new ObjectOutputStream(socket.getOutputStream());
                    ObjectInputStream inputStream = new ObjectInputStream(socket.getInputStream());
            ) {
                // Send the welcome message
                sendMessage(outputStream, new NetworkMessage(
                        MessageType.SERVER_INFO, "Connection Successful. Welcome to the Streaming Server."
                ));

                // Handle the messages from the client
                while (isRunning && !socket.isClosed()) {
                    try {
                        Object object = inputStream.readObject();
                        if (object instanceof NetworkMessage) {
                            NetworkMessage message = (NetworkMessage) object;
                            handleClientMessage(message, socket, outputStream);
                        }
                    } catch (ClassNotFoundException e) {
                        logger.error("Received item of unknown type: {}", e.getMessage());
                    } catch (EOFException | StreamCorruptedException e) {
                        logger.info("Client disconnected: {}", e.getMessage());
                        break;
                    }
                }
            } catch (IOException e) {
                if (isRunning) {
                    logger.error("Error in the connection management: {}", e.getMessage());
                }
            } finally {
                try {
                    socket.close();
                    logger.info("The connection to the client is closed");
                } catch (IOException e) {
                    logger.error("Error during the closing of the socket: {}", e.getMessage());
                }
            }
        });
    }

    /**
     * Handles a message from the client
     *
     * @param message       - The message from the client
     * @param socket        - The connection socket
     * @param outputStream  - The outputstream for sending replies
     */
    private void handleClientMessage(NetworkMessage message, Socket socket, ObjectOutputStream outputStream) {
        logger.info("Solved message from client: {}", message);

        switch (message.getMessageType()) {
            case REQUEST_VIDEO_LIST:
                handleVideoListRequest(outputStream);
                break;
            case REQUEST_STREAM_VIDEO:
                handleStreamRequest(message, socket, outputStream);
                break;
            case REPORT_CONNECTION_SPEED:
                handleConnectionSpeedReport(message);
                break;
            case STOP_STREAMING:
                handleStopStreaming();
                break;
            case DISCONNECT:
                handleDisconnect(socket);
                break;
            default:
                logger.warn("Unknown type of message: {}", message.getMessageType());
                break;
        }
    }

    /**
     * Handles requests for available videos
     *
     * @param outputStream - The {@link ObjectOutputStream} to send the answer
     */
    private void handleVideoListRequest(ObjectOutputStream outputStream) {
        logger.info("Handling requests for video lists");

        // Load all the available videos
        List<VideoFile> videoFiles = videoManager.loadAvailableVideos();

        // Send the list to the client
        sendMessage(outputStream, new NetworkMessage(MessageType.VIDEO_LIST_RESPONSE, videoFiles));
    }

    /**
     * Handles video streaming requests
     *
     * @param message       - The {@link NetworkMessage} that contains the request
     * @param socket        - The {@link Socket} of the connection
     * @param outputStream  - The {@link ObjectOutputStream} where the reply will be relayed to.
     */
    private void handleStreamRequest(NetworkMessage message, Socket socket, ObjectOutputStream outputStream) {
        String fileName = message.getPayloadAs(String.class);

        // Detailed logging added
        logger.info("=== STREAM REQUEST DEBUG START ===");
        logger.info("Received stream request for file: {}", fileName);

        if (fileName == null) {
            logger.error("Invalid streaming request: No file name was provided");
            sendErrorMessage(outputStream, "No file name was provided");
            return;
        }

        logger.info("Handling of the video streaming request: {}", fileName);

        // Search for the video file and check if it exists
        VideoFile videoFile = videoManager.getVideoByFileName(fileName);
        if (videoFile == null) {
            logger.error("The video {} was not found", fileName);
            logger.info("Available videos: {}",
                    videoManager.loadAvailableVideos().stream()
                            .map(VideoFile::getFileName)
                            .collect(Collectors.toList()));
            sendErrorMessage(outputStream, "The video was not found: " + fileName);
            return;
        }

        logger.info("Video file found successfully: {}", videoFile.getFileName());

        // Check if transcoded versions already exist
        if (!videoFile.getTranscodedVersionList().isEmpty()) {
            logger.info("Transcoded versions already available, proceeding directly to streaming");
            proceedWithStreaming(videoFile, outputStream);
            return;
        }

        // If need to transcode, inform the client immediately and do it asynchronously
        logger.info("Video needs transcoding, starting asynchronous preparation");

        // Send immediate response to client saying "processing in progress"
        try {
            sendMessage(outputStream, new NetworkMessage(MessageType.SERVER_INFO,
                    "Video is being prepared for streaming. This may take a few minutes..."));
        } catch (Exception e) {
            logger.error("Failed to send processing message to client: {}", e.getMessage());
            return;
        }

        // Asynchronous transcoding.
        CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Starting asynchronous video preparation");
                return videoManager.prepareVideoForStreaming(videoFile);
            } catch (Exception e) {
                logger.error("Error during video preparation: {}", e.getMessage());
                throw new RuntimeException(e);
            }
        }).thenAccept(preparedVideo -> {
            // Runs when transcoded is complete
            logger.info("Video preparation completed successfully");
            proceedWithStreaming(preparedVideo, outputStream);
        }).exceptionally(throwable -> {
            // Runs if transcoding fails
            logger.error("Video preparation failed: {}", throwable.getMessage());
            sendMessage(outputStream, new NetworkMessage(MessageType.STREAM_ERROR, "Failed to prepare video: " + throwable.getMessage()));
            return null;
        });

        logger.info("Asynchronous transcoding initiated, keeping connection alive");
    }

    /**
     * Helper method to handle the actual streaming once video is ready
     * @param videoFile
     * @param outputStream
     */
    private void proceedWithStreaming(VideoFile videoFile, ObjectOutputStream outputStream) {
        try {
            logger.info("Using client connection speed: {} Mbps", clientConnectionSpeedMbps);

            // Select appropriate stream configuration
            StreamConfig config = StreamConfig.getBestConfigurationForSpeed(clientConnectionSpeedMbps);
            logger.info("Selected streaming configuration: {}", config);

            // Find the best version for streaming
            VideoFile.TranscodedVersion version = videoFile.getBestVersionForSpeed(config.getBitrateMbps());
            if (version == null) {
                logger.error("No suitable transcoded version found");
                logger.error("Available transcoded versions: {}", videoFile.getTranscodedVersionList());
                sendErrorMessage(outputStream, "No suitable version found for streaming");
                return;
            }

            logger.info("Selected transcoded version: {}", version);

            // Stop any previous streaming
            if (streamingFuture != null && !streamingFuture.isDone()) {
                logger.info("Stopping previous streaming session");
                ffmpegHandler.stopCurrentProcess();
                streamingFuture.cancel(true);
            }

            // Notify client that stream is ready
            sendMessage(outputStream, new NetworkMessage(MessageType.STREAM_READY, config));
            logger.info("Starting FFMPEG streaming process");

            // Start the actual streaming
            String videoPath = version.getTranscodedVideoPath();
            streamingFuture = ffmpegHandler.startStreaming(videoPath, config, line -> {
                logger.debug("FFMPEG output: {}", line);
            });

            // Monitor streaming completion
            streamingFuture.whenComplete((result, error) -> {
                if (error != null) {
                    logger.error("Streaming completed with error: {}", error.getMessage());
                    sendErrorMessage(outputStream, "Streaming error: " + error.getMessage());
                } else {
                    logger.info("Streaming completed successfully");
                }
            });
        } catch (Exception e) {
            logger.error("Exception during streaming setup: {}", e.getMessage(), e);
            sendErrorMessage(outputStream, "Server error during streaming setup: " + e.getMessage());
        }
    }

    /**
     * Handles the speed report from the client
     * @param message
     */
    private void handleConnectionSpeedReport(NetworkMessage message) {
        Double speedMbps = message.getPayloadAs(Double.class);
        if (speedMbps != null) {
            logger.info("The client reported connection speed of {:.2f} Mbps", speedMbps);
            this.clientConnectionSpeedMbps = speedMbps;
        } else {
            logger.warn("Invalid connection speed");
        }
    }

    /**
     * Handles the request to stop streaming
     */
    private void handleStopStreaming() {
        logger.info("Handle the request to stop streaming");

        if (streamingFuture != null && !streamingFuture.isDone()) {
            ffmpegHandler.stopCurrentProcess();
            streamingFuture.cancel(true);
            logger.info("Streaming is terminated");
        } else {
            logger.info("There is no active streaming do be terminated");
        }
    }

    /**
     * Handles disconnect requests
     *
     * @param socket - The connection socket
     */
    private void handleDisconnect(Socket socket) {
        logger.info("Handling of the disconnection request");

        handleStopStreaming();

        // Close the connection
        try {
            socket.close();
            logger.info("Connection with the client was killed, following the disconnect request");
        } catch (IOException e) {
            logger.error("Error during the closing of the socket: {}", e.getMessage());
        }
    }

    /**
     * Sends a message to the client
     *
     * @param outputStream The {@link ObjectOutputStream} responsible for sending the message
     * @param message      The {@link NetworkMessage} to be sent
     */
    private void sendMessage(ObjectOutputStream outputStream, NetworkMessage message) {
        try {
            outputStream.writeObject(message);
            outputStream.flush();
            logger.debug("Send message to client: {}", message);
        } catch (IOException e) {
            logger.error("Error during sending the message: {}", e.getMessage());
        }
    }

    /**
     * Sends an errorMessage to the client
     *
     * @param outputStream - The {@link ObjectOutputStream} for relaying the message
     * @param errorMessage - The message that contains the error
     */
    private void sendErrorMessage(ObjectOutputStream outputStream, String errorMessage) {
        sendMessage(outputStream, new NetworkMessage(MessageType.STREAM_ERROR, errorMessage));
    }
}
