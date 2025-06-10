package com.client;

import com.model.StreamConfig;
import com.model.VideoFile;
import com.network.NetworkMessage;
import com.network.enums.MessageType;
import com.util.FFMPEGHandler;
import com.util.SpeedTester;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The Streaming Client Class
 * Handles the connection with the Server and the reception and video streaming
 */
public class StreamingClient {
    private static final Logger logger = LogManager.getLogger(StreamingClient.class);

    private final String serverAddress;
    private final int serverPort;
    private final FFMPEGHandler ffmpegHandler;

    private Socket socket;
    private ObjectOutputStream outputStream;
    private ObjectInputStream inputStream;
    private Thread receiverThread;
    private boolean isConnected;
    private double connectionSpeedMbps;
    private CompletableFuture<Void> playbackFuture;

    // Callbacks to handle events
    private Consumer<List<VideoFile>> onVideoListReceived;
    private Consumer<StreamConfig> onStreamReady;
    private Consumer<String> onStreamError;
    private Consumer<String> onFFMPEGOutput;
    private Runnable onDisconnect;

    /**
     * {@link StreamingClient} constructor
     *
     * @param serverAddress - The server address
     * @param serverPort    - The server port
     */
    public StreamingClient(String serverAddress, int serverPort) {
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
        this.ffmpegHandler = new FFMPEGHandler();
        this.isConnected = false;
    }

    /**
     * Attempts to connect to the server.
     *
     * @return - Returns true if the connection is achieved. False otherwise
     */
    public boolean attemptConnectingToServer() {
        logger.info("Connect to sever: {}:{}", serverAddress, serverPort);

        try {
            // Check that FFMPEG is installed and setup correctly.
            if (!ffmpegHandler.isFFMPEFGAvailable()) {
                logger.error("The FFMPEG is not available. Please install it and make sure that it is on the PATH");
                return false;
            }

            // Speed test
            logger.info("Performing speed test...");
            try {
                // Use the singleton instance to prevent multiple concurrent tests
                connectionSpeedMbps = SpeedTester.getInstance()
                        .measureDownloadSpeed()
                        .get(15, TimeUnit.SECONDS);

                logger.info("Connection speed: {:.2f} Mbps", connectionSpeedMbps);
            } catch (Exception e) {
                logger.warn("Speed test failed, using default speed: {}", connectionSpeedMbps);
                connectionSpeedMbps = 2.0;
            }

            // Connect to server
            socket = new Socket(serverAddress, serverPort);

            // Very Important, to create streams in the correct order
            outputStream = new ObjectOutputStream(socket.getOutputStream());
            outputStream.flush(); // Flush header
            inputStream = new ObjectInputStream(socket.getInputStream());
            isConnected = true;

            // Create and start the thread for message reception
            receiverThread = new Thread(this::receiveMessages);
            receiverThread.setDaemon(true);
            receiverThread.start();

            // Send connection speed to server
            sendConnectionSpeed();

            logger.info("Successful connection to the server");
            return true;
        } catch (Exception e) {
            logger.error("Error during the connection to the server: {}", e.getMessage());
            disconnect();
            return false;
        }
    }

    /**
     * Requests the list of available videos from the server
     */
    public void requestVideoList() {
        if (!isConnected) {
            logger.error("There is no connection with the server");
            return;
        }

        logger.info("Request for the video list from the server");
        sendMessage(new NetworkMessage(MessageType.REQUEST_VIDEO_LIST));
    }

    /**
     * Requests a video to start streaming
     */
    public void requestStreamVideo(String fileName) {
        if (!isConnected) {
            logger.error("There is no connection with the server");
            return;
        }

        logger.info("Request to stream: {}", fileName);

        // Terminate the previous playback
        if (ffmpegHandler != null) {
            ffmpegHandler.stopCurrentProcess();
            if (playbackFuture != null && !playbackFuture.isDone()) {
                playbackFuture.cancel(true);
            }
        }
        sendMessage(new NetworkMessage(MessageType.REQUEST_STREAM_VIDEO, fileName));
    }

    /**
     * Requests to terminate the running stream
     */
    public void requestStopStreaming() {
        if (!isConnected) {
            logger.error("There is no connection with the server");
            return;
        }

        logger.info("Request to terminate streaming");
        sendMessage(new NetworkMessage(MessageType.STOP_STREAMING));

        // Terminate the playback
        if (ffmpegHandler != null) {
            ffmpegHandler.stopCurrentProcess();
            if (playbackFuture != null && !playbackFuture.isDone()) {
                playbackFuture.cancel(true);
            }
        }
    }

    /**
     * Receives messages from the server.
     * This method is called on a separate thread.
     */
    private void receiveMessages() {
        try {
            while (isConnected && socket != null && !socket.isClosed()) {
                try {
                    Object object = inputStream.readObject();
                    if (object instanceof NetworkMessage) {
                        NetworkMessage message = (NetworkMessage) object;
                        handleServerMessage(message);
                    }
                } catch (ClassNotFoundException e) {
                    logger.error("Receive object of unknown type: {}", e.getMessage());
                } catch (EOFException | StreamCorruptedException e) {
                    logger.info("The server has closed the connection: {}", e.getMessage());
                    break;
                }
            }
        } catch (IOException e) {
            if (isConnected) {
                logger.error("Error during the reception of the messages: {}", e.getMessage());
            }
        } finally {
            disconnect();
        }
    }

    /**
     * Disconnecting from the server
     */
    public void disconnect() {
        if (!isConnected) {
            return;
        }

        logger.info("Disconnect from the server");

        // Send disconnect message
        try {
            if (outputStream != null) {
                sendMessage(new NetworkMessage(MessageType.DISCONNECT));
            }
        } catch (Exception e) {
            logger.error("Error during sending the disconnection message: {}", e.getMessage());
        }

        // Termination of the playback
        if (ffmpegHandler != null) {
            ffmpegHandler.stopCurrentProcess();
        }

        // Close streams and socket
        try {
            if (inputStream != null) {
                inputStream.close();
            }

            if (outputStream != null) {
                outputStream.close();
            }

            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            logger.error("Error during closing the streams: {}", e.getMessage());
        }

        // Interrupt message receiving thread
        if (receiverThread != null && receiverThread.isAlive()) {
            receiverThread.interrupt();
        }

        isConnected = false;

        // Call the disconnect callback
        if (onDisconnect != null) {
            onDisconnect.run();
        }

        logger.info("Disconnection successful");
    }

    /**
     * Method responsible for handling the messages received from server.
     *
     * @param message - The {@link NetworkMessage} from server
     */
    private void handleServerMessage(NetworkMessage message) {
        logger.debug("Received message from server: {}", message);

        switch (message.getMessageType()) {
            case VIDEO_LIST_RESPONSE:
                // Reception of the video list
                List videosList = message.getPayloadAs(List.class);
                logger.info("A video list with received with a size of {}", videosList != null
                        ? videosList.size() : 0);
                if (onVideoListReceived != null && videosList != null) {
                    onVideoListReceived.accept(videosList);
                }
                break;

            case STREAM_READY:
                StreamConfig streamConfig = message.getPayloadAs(StreamConfig.class);
                logger.info("The stream is ready: {}", streamConfig);

                if (streamConfig != null) {
                    startPlayback(streamConfig);

                    if (onStreamReady != null) {
                        onStreamReady.accept(streamConfig);
                    }
                }
                break;

            case STREAM_ERROR:
                String errorMessage = message.getPayloadAs(String.class);
                logger.error("Streaming error: {}", errorMessage);

                if (onStreamError != null && errorMessage != null) {
                    onStreamError.accept(errorMessage);
                }
                break;

            case SERVER_INFO:
                String infoMessage = message.getPayloadAs(String.class);
                logger.info("Information about the serer: {}", infoMessage);


                break;

            default:
                logger.warn("Unknown message type: {}", message.getMessageType());
                break;
        }
    }

    /**
     * Initiates the video playback
     *
     * @param config
     */
    private void startPlayback(StreamConfig config) {
        logger.info("Start streaming from {}:{}", serverAddress, config.streamPort());

        // Start streaming with FFMPEG
        playbackFuture = ffmpegHandler.startPlayback(serverAddress, config, line -> {
            logger.debug("FFMPEG: {}", line);

            if (onFFMPEGOutput != null) {
                onFFMPEGOutput.accept(line);
            }
        });

        playbackFuture.whenComplete((result, error) -> {
            if (error != null) {
                logger.error("Error during playback: {}", error.getMessage());

                if (onStreamError != null) {
                    onStreamError.accept("Error during playback: " + error.getMessage());
                }
            } else {
                logger.info("The playback has finished successfully");
            }
        });
    }

    /**
     * Sends a message to the server
     *
     * @param message
     */
    private void sendMessage(NetworkMessage message) {
        try {
            outputStream.writeObject(message);
            outputStream.flush();
            logger.debug("Message sent to server: {}", message);
        } catch (IOException e) {
            logger.error("Error during message sending: {}", e.getMessage());
            disconnect();
        }
    }

    /**
     * Sends the connection speed to the server
     */
    private void sendConnectionSpeed() {
        logger.info("Send connection speed to the server: {:.2f Mbps", connectionSpeedMbps);
        sendMessage(new NetworkMessage(MessageType.REPORT_CONNECTION_SPEED, connectionSpeedMbps));
    }

    public boolean getIsConnected() {
        return isConnected;
    }

    public double getConnectionSpeedMbps() {
        return connectionSpeedMbps;
    }

    public String getServerAddress() {
        return serverAddress;
    }

    public int getServerPort() {
        return serverPort;
    }

    public void setOnStreamReady(Consumer<StreamConfig> callback) {
        this.onStreamReady = callback;
    }

    public void setOnVideoListReceived(Consumer<List<VideoFile>> callback) {
        this.onVideoListReceived = callback;
    }

    public void setOnStreamError(Consumer<String> callback) {
        this.onStreamError = callback;
    }

    public void setOnFFMPEGOutput(Consumer<String> callback) {
        this.onFFMPEGOutput = callback;
    }

    public void setOnDisconnect(Runnable callback) {
        this.onDisconnect = callback;
    }
}
