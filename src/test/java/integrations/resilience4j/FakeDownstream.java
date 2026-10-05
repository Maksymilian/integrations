package integrations.resilience4j;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real HTTP server on a loopback ephemeral port, scripted to misbehave: it answers with the
 * statuses queued by {@link #thenRespond}, then with {@link #respondWith}'s status forever, each
 * after an optional delay. It counts every request that reaches it, which is how the tests tell a
 * call that was made from one a resilience decorator stopped before it left the client.
 */
final class FakeDownstream implements AutoCloseable {

    private final HttpServer server;
    private final Queue<Integer> scripted = new ConcurrentLinkedQueue<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int steadyStatus = 200;
    private volatile Duration delay = Duration.ZERO;

    private FakeDownstream(HttpServer server) {
        this.server = server;
    }

    static FakeDownstream start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        FakeDownstream downstream = new FakeDownstream(server);
        server.createContext("/", downstream::handle);
        // virtual threads, so a delayed response never holds up the next request or stop()
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return downstream;
    }

    /** Answer the next requests with these statuses, in order, before falling back to the steady one. */
    FakeDownstream thenRespond(int... statuses) {
        for (int status : statuses) {
            scripted.add(status);
        }
        return this;
    }

    FakeDownstream respondWith(int status) {
        steadyStatus = status;
        return this;
    }

    FakeDownstream delayBy(Duration delay) {
        this.delay = delay;
        return this;
    }

    URI uri() {
        return URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/");
    }

    int requests() {
        return requests.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        try (exchange) {
            if (!delay.isZero()) {
                Thread.sleep(delay);
            }
            Integer next = scripted.poll();
            int status = next != null ? next : steadyStatus;
            byte[] body = ("status " + status).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
