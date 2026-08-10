package com.leantpm.opscontrol.notification;

@FunctionalInterface
public interface PushPlusTransport {
    PushPlusTransportResult send(PushPlusDeliveryRequest request);
}
