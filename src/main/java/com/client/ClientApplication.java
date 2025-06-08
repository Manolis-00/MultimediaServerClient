package com.client;

import com.model.VideoFile;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.LocalTime;

import static com.server.ServerApplication.formatFileSize;

/**
 * Main class for the Client application with a GUI
 */
public class ClientApplication extends Application {
    private static final Logger logger = LogManager.getLogger(ClientApplication.class);

    private static final String DEFAULT_SERVER_ADDRESS = "localhost";
    private static final int DEFAULT_SERVER_PORT = 8888;

    // GUI fields
    private Stage primaryStage;
    private ListView<VideoFile> videoFileListView;
    private ObservableList<VideoFile> videoList;
    private TextArea logTextArea;
    private Label statusLabel;
    private Label connectionSpeedLabel;
    private Button connectButton;
    private Button refreshButton;
    private Button streamButton;
    private Button stopButton;

    private StreamingClient client;
    private boolean isStreaming = false;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        primaryStage.setTitle("Streaming Client");

        client = new StreamingClient(DEFAULT_SERVER_ADDRESS, DEFAULT_SERVER_PORT);
        setupClientCallbacks();
        createGUI();

        primaryStage.setOnCloseRequest(e -> {
            stopStreaming();
            disconnect();
        });
        primaryStage.show();

        appendToLog("Welcome to the Streaming Client Application!");
        appendToLog("Connect to the server to see the available videos");
    }

    /**
     * GUI
     */
    private void createGUI() {
        // Create layout
        VBox mainLayout = new VBox(10);
        mainLayout.setPadding(new Insets(10));
        mainLayout.setFillWidth(true);

        // Title
        Label titleLabel = new Label("Streaming Client");
        titleLabel.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 20));

        // Connection panel
        HBox connectionPanel = createConnectionPanel();
        HBox statusPanel = createStatusPanel();
        VBox videoListPanel = createVideoListPanel();
        VBox logPanel = createLogPanel();

        mainLayout.getChildren().addAll(titleLabel, connectionPanel, statusPanel, videoListPanel, logPanel);

        Scene scene = new Scene(mainLayout, 600, 700);
        primaryStage.setScene(scene);
    }

    /**
     * Creates the connection panel
     * @return - Returns the HBox that will hold the connection panel
     */
    private HBox createConnectionPanel() {
        HBox connectionPanel = new HBox(10);
        connectionPanel.setAlignment(Pos.CENTER_LEFT);

        // Fields for the address and the port
        Label addressLabel = new Label(("Server Address:"));
        TextField addressField = new TextField(DEFAULT_SERVER_ADDRESS);
        addressField.setPrefWidth(150);

        Label portLabel = new Label("Port:");
        TextField portField = new TextField(String.valueOf(DEFAULT_SERVER_PORT));
        portField.setPrefWidth(70);

        // Connection Button
        connectButton = new Button("Connection");
        connectButton.setOnAction(e -> {
            if (client.getIsConnected()) {
                disconnect();
            } else {
                String address = addressField.getText();
                int port;
                try {
                    port = Integer.parseInt(portField.getText());
                } catch (NumberFormatException exception) {
                    showError("The port must be a number");
                    return;
                }

                // Create new client with the new details
                client = new StreamingClient(address, port);
                setupClientCallbacks();

                connect();
            }
        });

        connectionPanel.getChildren().addAll(addressLabel, addressField, portLabel, portField, connectButton);

        return connectionPanel;
    }

    /**
     * Creates the status panel
     *
     * @return - Returns the HBox that will hold the connection status
     */
    private HBox createStatusPanel() {
        HBox statusPanel = new HBox(10);
        statusPanel.setAlignment(Pos.CENTER_LEFT);

        Label statusTitleLabel = new Label("Status: ");
        statusLabel = new Label("Disconnected");
        statusLabel.setTextFill(Color.RED);

        Label speedTitleLabel = new Label("Connection speed:");
        connectionSpeedLabel = new Label("--");

        statusPanel.getChildren().addAll(statusTitleLabel, statusLabel, speedTitleLabel, connectionSpeedLabel);
        return statusPanel;
    }

    /**
     * Creates the panel for the video list.
     *
     * @return - Returns the VBox that will hold the list of the available videos
     */
    private VBox createVideoListPanel() {
        VBox videoListPanel = new VBox(10);

        Label titleLabel = new Label("Available Videos");
        titleLabel.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 14));

        videoList = FXCollections.observableArrayList();
        videoFileListView = new ListView<>(videoList);
        videoFileListView.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(VideoFile item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.getFileName() + " (" + formatFileSize(item.getFileSize()) + ")");
                }
            }
        });
        videoFileListView.setPrefHeight(200);

        HBox buttonPanel = new HBox(10);
        buttonPanel.setAlignment(Pos.CENTER);

        refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> refreshVideoList());
        refreshButton.setDisable(true);

        streamButton = new Button("Start Streaming");
        streamButton.setOnAction(e -> startStreaming());
        streamButton.setDisable(true);

        stopButton = new Button("Termination");
        stopButton.setOnAction(e -> stopStreaming());
        stopButton.setDisable(true);

        buttonPanel.getChildren().addAll(refreshButton, streamButton, stopButton);
        videoListPanel.getChildren().addAll(titleLabel, videoFileListView, buttonPanel);
        return videoListPanel;
    }

    /**
     * Creates the log panel
     *
     * @return - Returns the VBox that will hold the logs
     */
    private VBox createLogPanel() {
        VBox logPanel = new VBox(10);

        Label logPanelTitleLabel = new Label("Logging");
        logPanelTitleLabel.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 14));

        logTextArea = new TextArea();
        logTextArea.setEditable(false);
        logTextArea.setWrapText(true);
        logTextArea.setPrefHeight(200);

        logPanel.getChildren().addAll(logPanelTitleLabel, logTextArea);
        return logPanel;
    }

    /**
     * Configures the callbacks of the client
     */
    private void setupClientCallbacks() {
        // Callback for the reception of the video list
        client.setOnVideoListReceived(videoFiles -> Platform.runLater(() -> {
            videoList.clear();
            videoList.addAll(videoFiles);
            appendToLog("Received " + videoFiles.size() + " videos from the server");

            streamButton.setDisable(videoFiles.isEmpty());
        }));

        // Callback for the stream readiness
        client.setOnStreamReady(config -> Platform.runLater(() -> {
            isStreaming = true;
            streamButton.setDisable(true);
            stopButton.setDisable(false);

            appendToLog("Start streaming with resolution " + config.getResolution() + " and bitrate " +
                    String.format("%.2f Mbps", config.getBitrateMbps()));
        }));

        // Callback for errors during streaming
        client.setOnStreamError(error -> Platform.runLater(() -> {
            isStreaming = false;
            streamButton.setDisable(false);
            stopButton.setDisable(true);

            appendToLog("Error: " + error);
            showError("Error during streaming: " + error);
        }));

        // Callback for the FFMPEG exit
        client.setOnFFMPEGOutput(output -> {
            // Log the important
            if (output.contains("error") || output.contains("warning") || output.contains("frame=") || output.contains("stream")) {
                Platform.runLater(() -> appendToLog("FFMPEG: " + output));
            }
        });

        // Callback for disconnect
        client.setOnDisconnect(() -> Platform.runLater(() -> {
            isStreaming = false;
            updateConnectionStatus(false);

            appendToLog("Disconnected from the server");
        }));
    }

    /**
     * Connects to the server
     */
    private void connect() {
        appendToLog("Connect to the server: " + client.getServerAddress() + ":" + client.getServerPort());
        connectButton.setDisable(true);

        // Perform the connection to a different Thread
        new Thread(() -> {
            boolean success = client.attemptConnectingToServer();

            Platform.runLater(() -> {
                if (success) {
                    updateConnectionStatus(true);
                    connectionSpeedLabel.setText(String.format("%.2f Mbps", client.getConnectionSpeedMbps()));
                    refreshVideoList();
                    appendToLog("Successfully connected to the server.");
                } else {
                    updateConnectionStatus(false);
                    showError("Failure to connect to server");
                    appendToLog("Failure to connect to server");
                }
                connectButton.setDisable(false);
            });
        }).start();
    }

    /**
     * Disconnects from the server
     */
    private void disconnect() {
        if (client.getIsConnected()) {
            appendToLog("Disconnecting from the server....");
            stopStreaming();
            client.disconnect();
            updateConnectionStatus(false);
        }
    }

    /**
     * Refreshes the video list from the server
     */
    private void refreshVideoList() {
        if (client.getIsConnected()) {
            appendToLog("Get list of videos from the server...");
            refreshButton.setDisable(true);
            client.requestVideoList();

            new Thread(() -> {
                try {
                    Thread.sleep(1000);
                    Platform.runLater(() -> refreshButton.setDisable(false));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
    }

    /**
     * Start streaming the selected video
     */
    private void startStreaming() {
        VideoFile selectedVideo = videoFileListView.getSelectionModel().getSelectedItem();
        if (selectedVideo == null) {
            showError("No video was selected");
            return;
        }

        if (client.getIsConnected()) {
            appendToLog("Request to stream the video: " + selectedVideo.getFileName());
            streamButton.setDisable(true);
            client.requestStreamVideo(selectedVideo.getFileName());
        }
    }

    /**
     * Terminates the current stream
     */
    private void stopStreaming() {
        logger.info("Strop Streaming button clicked");

        // Always update the GUI state first, regardless of connection status
        Platform.runLater(() -> {
            isStreaming = false;
            streamButton.setDisable(false);
            stopButton.setDisable(true);
            appendToLog("Stopping streaming...");
        });

        // Try to send the stop message if still connected
        if (client != null && client.getIsConnected()) {
            try {
                logger.info("Sending stop streaming request to server");
                client.requestStopStreaming();
                appendToLog("Stop request sent to server");
            } catch (Exception e) {
                logger.error("Error sending stop request: {}", e.getMessage());
                appendToLog("Error sending stop request: " + e.getMessage());
            }
        } else {
            logger.info("Client not connected, only updating local state");
            appendToLog("Streaming stopped (client not connected)");
        }
    }

    /**
     * Adds a message to the log
     * @param message - the message to be appended
     */
    private void appendToLog(String message) {
        String timestamp = String.format("[%tT] ", LocalTime.now());
        logTextArea.appendText(timestamp + message + "\n");
        logTextArea.setScrollTop(Double.MAX_VALUE);
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    /**
     * Updates the state of the connection to the GUI
     * @param connected - is there a connection or not
     */
    private void updateConnectionStatus(boolean connected) {
        statusLabel.setText(connected ? "Connected" : "Disconnected");
        statusLabel.setTextFill(connected ? Color.GREEN : Color.RED);

        // Update the connection button
        connectButton.setText(connected ? "Disconnect" : "Connect");

        // Update the other buttons
        refreshButton.setDisable(!connected);
        streamButton.setDisable(!connected || videoList.isEmpty());
        stopButton.setDisable(!connected || isStreaming);

        // Clean the videos list if disconnected
        if (!connected) {
            videoList.clear();
            connectionSpeedLabel.setText("--");
        }
    }
}
