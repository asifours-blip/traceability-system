package com.qhx.back.support;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 本地 HTTP 替身，模拟 WeBASE-Front 的 POST /trans/handle。
 * 记录每次请求的 user（签名地址）、funcName、funcParam，不连真实链。
 */
public class FakeWeBaseFront implements AutoCloseable {

    public static class Request {
        public final String user;
        public final String funcName;
        public final JSONArray params;

        Request(String user, String funcName, JSONArray params) {
            this.user = user;
            this.funcName = funcName;
            this.params = params;
        }

        @Override
        public String toString() {
            return funcName + "(user=" + user + ", params=" + params + ")";
        }
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    public FakeWeBaseFront() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/WeBASE-Front/trans/handle", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/WeBASE-Front";
    }

    public List<Request> requests() {
        return new ArrayList<>(requests);
    }

    public List<Request> requestsFor(String funcName) {
        return requests.stream().filter(r -> r.funcName.equals(funcName)).collect(Collectors.toList());
    }

    public void reset() {
        requests.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        JSONObject json = JSONUtil.parseObj(body);
        String funcName = json.getStr("funcName");
        requests.add(new Request(json.getStr("user"), funcName, json.getJSONArray("funcParam")));

        String response;
        if (funcName.startsWith("is")) {
            response = "[true]";
        } else if ("getSystemInfo".equals(funcName)) {
            response = "[\"溯源系统\",\"1.0\",\"测试\"]";
        } else if (funcName.startsWith("get")) {
            response = "[\"not exists\"]";
        } else {
            response = "{\"statusOK\":true}";
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
