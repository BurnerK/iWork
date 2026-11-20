package io.iwork.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.model.transport.HttpRequest;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProviderTransportTest {
    @Test
    void providerTransportDoesNotFollowRedirects() throws Exception {
        var received = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var authority = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext(
                "/source",
                exchange -> {
                    try (exchange) {
                        exchange.getResponseHeaders().set("Location", authority + "/target");
                        exchange.sendResponseHeaders(307, -1);
                    }
                });
        server.createContext(
                "/target",
                exchange -> {
                    try (exchange) {
                        received.incrementAndGet();
                        exchange.sendResponseHeaders(200, -1);
                    }
                });
        server.start();
        var transport = new GovernedModel.TransportConfiguration().providerTransport();
        try {
            var request = HttpRequest.builder().url(authority + "/source").method("GET").build();
            assertThatThrownBy(() -> transport.stream(request).blockLast(Duration.ofSeconds(5)))
                    .hasMessageContaining("status 307");
            assertThat(received.get()).isZero();
        } finally {
            transport.close();
            server.stop(0);
        }
    }
}
