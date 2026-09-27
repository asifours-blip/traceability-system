package com.qhx.back.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 原始 socket 级别的 HTTP 替身：读完请求后原样写出给定字节，然后直接关闭连接。
 * 用来模拟「响应头已到、响应体只到一半连接就断了」——JDK HttpServer 做不到在响应中途断开。
 */
public class RawHttpStub implements AutoCloseable {

    private final ServerSocket server;
    private final byte[] response;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final Thread acceptor;

    public RawHttpStub(String rawResponse) throws IOException {
        this.response = rawResponse.getBytes(StandardCharsets.UTF_8);
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.acceptor = new Thread(this::serve, "raw-http-stub");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** 声明 Content-Length 为 declaredLength，但只写出 partialBody 就断开 */
    public static RawHttpStub truncatedJson(String partialBody, int declaredLength) throws IOException {
        return new RawHttpStub("HTTP/1.1 200 OK\r\nContent-Type: application/json;charset=UTF-8\r\n"
                + "Content-Length: " + declaredLength + "\r\n\r\n" + partialBody);
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getLocalPort() + "/WeBASE-Front";
    }

    public int requestCount() {
        return requestCount.get();
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                readRequest(socket.getInputStream());
                requestCount.incrementAndGet();
                OutputStream out = socket.getOutputStream();
                out.write(response);
                out.flush();
                // try-with-resources 关闭 socket：响应体没写完连接就断了
            } catch (IOException e) {
                // 关闭 stub 时 accept 抛出，退出循环
            }
        }
    }

    // 读到请求头结束，再按 Content-Length 读完请求体
    private static void readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) {
                return;
            }
            head.write(b);
            state = (b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
        }
        int length = 0;
        for (String line : head.toString(StandardCharsets.ISO_8859_1).split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(15).trim());
            }
        }
        in.readNBytes(length);
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
