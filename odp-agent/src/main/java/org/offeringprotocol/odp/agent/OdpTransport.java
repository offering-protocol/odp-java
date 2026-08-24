package org.offeringprotocol.odp.agent;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@FunctionalInterface
public interface OdpTransport {
    HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException;
}
