package com.network;

import com.network.enums.MessageType;
import com.util.SerialNumbers;

import java.io.Serial;
import java.io.Serializable;

/**
 * Class that represents a network message Between Client and Server
 * Used for the communication between the two entities
 */
public class NetworkMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = SerialNumbers.THREE;
    private final MessageType messageType;
    private final Object messagePayload;

    /**
     * Constructor for the creation of a {@link NetworkMessage}
     * @param messageType       The type of the message
     * @param messagePayload    The contents of the message (MUST be {@link Serializable}
     */
    public NetworkMessage(MessageType messageType, Object messagePayload) {
        this.messageType = messageType;
        this.messagePayload = messagePayload;
    }

    public NetworkMessage(MessageType messageType) {
        this(messageType, null);
    }

    /**
     * Returns the {@link MessageType}
     * @return The type of the message.
     */
    public MessageType getMessageType() {
        return messageType;
    }

    public <T> T getPayloadAs(Class<T> clazz) {
        if (messagePayload != null && clazz.isAssignableFrom(messagePayload.getClass())) {
            return (T) messagePayload;
        }
        return null;
    }

    @Override
    public String toString() {
        return "NetworkMessage{" +
                "type=" + messageType +
                ", payload=" + (messagePayload != null ? messagePayload.getClass().getSimpleName(): "null") +
                '}';
    }
}
