package com.network.enums;

/**
 * Enumerator that holds the available message types that can be exchanged.
 */
public enum MessageType {

    // Message to the Server
    REQUEST_VIDEO_LIST,
    REQUEST_STREAM_VIDEO,
    REPORT_CONNECTION_SPEED,
    STOP_STREAMING,
    DISCONNECT,

    // Messages to the Client
    VIDEO_LIST_RESPONSE,
    STREAM_READY,
    STREAM_ERROR,
    SERVER_INFO
}
