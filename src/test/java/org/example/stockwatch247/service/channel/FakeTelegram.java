package org.example.stockwatch247.service.channel;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A Bot API on this machine: queued answers per method, and every request it received. */
public final class FakeTelegram implements AutoCloseable {
    public static final String TOKEN = "123456789:AAH-test-token_with-enough-characters-x";

    public record Request(String method, String body) { }

    private final HttpServer server;
    public final List<Request> received = new CopyOnWriteArrayList<>();
    private final Deque<String[]> answers = new ArrayDeque<>();
    private final Deque<String> updates = new ArrayDeque<>();

    public FakeTelegram() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        server.createContext("/bot" + TOKEN + "/", exchange -> {
            String method = exchange.getRequestURI().getPath().substring(("/bot" + TOKEN + "/").length());
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Request(method, body));
            int status = 200;
            String answer;
            synchronized (this) {
                if (method.equals("getUpdates")) {
                    answer = "{\"ok\":true,\"result\":[" + String.join(",", updates) + "]}";
                    updates.clear();
                } else if (!answers.isEmpty()) {
                    String[] next = answers.poll();
                    status = Integer.parseInt(next[0]);
                    answer = next[1];
                } else {
                    answer = "{\"ok\":true,\"result\":{\"message_id\":" + (100 + received.size()) + "}}";
                }
            }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    public String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    /** The next non-getUpdates call answers this way. */
    public synchronized void answer(int status, String json) { answers.add(new String[]{Integer.toString(status), json}); }

    public synchronized void update(String json) { updates.add(json); }

    public List<Request> sent() {
        return received.stream().filter(request -> request.method().equals("sendMessage")).toList();
    }

    @Override
    public void close() { server.stop(0); }
}
