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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 本地 HTTP 替身，模拟 WeBASE-Front v1.5.5 的 POST /trans/handle 与 GET /{groupId}/web3/transactionReceipt/{hash}。
 * 响应结构严格按本地真实链核验结果（docs/webase-front-contract.md）构造，不连真实链。
 * 记录每次请求的 user（签名地址）、funcName、funcParam。
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

    /** 一种响应行为 */
    public interface Reply {
        void write(HttpExchange exchange) throws IOException;
    }

    private static final String ZERO = "0x0000000000000000000000000000000000000000";

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<String> receiptQueries = new CopyOnWriteArrayList<>();
    private final Map<String, Function<Request, Reply>> handlers = new ConcurrentHashMap<>();
    private final AtomicLong block = new AtomicLong(1);
    private volatile Function<String, Reply> receiptHandler = hash -> receiptNotFound();
    private volatile Consumer<Request> onArrival = r -> { };
    // 可选：按合约 v2 规则模拟三阶段写入与读回（阶段顺序、每阶段只写一次、getStageActors），默认关闭
    private volatile ContractSim sim;

    public FakeWeBaseFront() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/WeBASE-Front/trans/handle", this::handleTrans);
        server.createContext("/WeBASE-Front/1/web3/transactionReceipt/", this::handleReceipt);
        // 延迟响应的用例不能阻塞后续请求
        server.setExecutor(executor);
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

    public List<String> receiptQueries() {
        return new ArrayList<>(receiptQueries);
    }

    /** 指定某个函数的响应；覆盖默认行为 */
    public void on(String funcName, Function<Request, Reply> handler) {
        handlers.put(funcName, handler);
    }

    public void onReceipt(Function<String, Reply> handler) {
        receiptHandler = handler;
    }

    /** 请求到达、响应之前的回调，用来观察「发请求那一刻」服务端的状态 */
    public void onArrival(Consumer<Request> hook) {
        onArrival = hook;
    }

    /** 打开合约模拟（每次调用都换一份空的链上状态）：未单独指定响应的三阶段写入按 v2 规则落到内存，读函数从内存返回 */
    public void simulateContract() {
        sim = new ContractSim();
    }

    /** 清掉请求记录与自定义响应；合约模拟的链上状态保留 */
    public void reset() {
        requests.clear();
        receiptQueries.clear();
        handlers.clear();
        receiptHandler = hash -> receiptNotFound();
        onArrival = r -> { };
    }

    private void handleTrans(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        JSONObject json = JSONUtil.parseObj(body);
        String funcName = json.getStr("funcName");
        Request request = new Request(json.getStr("user"), funcName, json.getJSONArray("funcParam"));
        requests.add(request);
        onArrival.accept(request);

        Function<Request, Reply> handler = handlers.get(funcName);
        Reply reply = handler != null ? handler.apply(request) : defaultReply(request);
        reply.write(exchange);
    }

    private void handleReceipt(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String hash = path.substring(path.lastIndexOf('/') + 1);
        receiptQueries.add(hash);
        receiptHandler.apply(hash).write(exchange);
    }

    private Reply defaultReply(Request request) {
        String funcName = request.funcName;
        ContractSim contract = sim;
        if (contract != null) {
            Reply simulated = contract.handle(this, request);
            if (simulated != null) {
                return simulated;
            }
        }
        if (funcName.startsWith("is")) {
            return json(200, "[true]");
        }
        if ("getSystemInfo".equals(funcName)) {
            return json(200, "[\"溯源系统\",\"1.0\",\"测试\"]");
        }
        if (funcName.startsWith("get")) {
            return callRevert("Trace: traceNumber does not exist");
        }
        return receiptSuccess(request.user, nextHash(), nextBlock());
    }

    // ---------- 按实测结构构造的响应 ----------

    public static Reply json(int status, String body) {
        return exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        };
    }

    /** 成功回执：HTTP 200，status=0x0，statusOK=true，blockNumber 为十进制字符串 */
    public static Reply receiptSuccess(String from, String hash, long blockNumber) {
        return json(200, receipt(from, hash, blockNumber, "0x0", "None", "0x", "Success", true).toString());
    }

    /** revert 回执：HTTP 200，status=0x16，statusMsg=RevertInstruction，output 为 Error(string)，message 为解码后的文本 */
    public static Reply receiptRevert(String from, String hash, long blockNumber, String reason) {
        return json(200, receipt(from, hash, blockNumber, "0x16", "RevertInstruction",
                encodeError(reason), reason, false).toString());
    }

    /** 共识停滞时 WeBASE 等满 transMaxWait 后的响应：HTTP 200，transactionHash=null，status=50001 */
    public static Reply receiptTimeout() {
        // 逐字取自本地真实链停掉 2/4 节点后的响应
        return json(200, "{\"transactionHash\":null,\"transactionIndex\":null,\"root\":null,\"blockNumber\":\"0\","
                + "\"blockHash\":null,\"from\":null,\"to\":null,\"gasUsed\":\"0\",\"remainGas\":null,"
                + "\"contractAddress\":null,\"logs\":null,\"logsBloom\":null,\"status\":\"50001\",\"statusMsg\":null,"
                + "\"input\":null,\"output\":null,\"txProof\":null,\"receiptProof\":null,"
                + "\"message\":\"Transaction receipt timeout\",\"statusOK\":false}");
    }

    /** WeBASE-Front 的 FrontException：HTTP 422 {code,data,errorMessage} */
    public static Reply frontError(int code, String message) {
        return json(422, "{\"code\":" + code + ",\"data\":null,\"errorMessage\":" + JSONUtil.quote(message) + "}");
    }

    /** 查不到回执：v1.5.5 内部空指针，HTTP 500 {"code":500,"errorMessage":null} */
    public static Reply receiptNotFound() {
        return json(500, "{\"code\":500,\"errorMessage\":null}");
    }

    /** 只读调用 revert：HTTP 200 ["Call contract return error: ..."] */
    public static Reply callRevert(String reason) {
        return json(200, new JSONArray().put("Call contract return error: " + reason).toString());
    }

    /** 只读调用返回值：实测所有返回值（含 uint）都是 JSON 字符串 */
    public static Reply callResult(Object... values) {
        JSONArray arr = new JSONArray();
        for (Object v : values) {
            arr.add(String.valueOf(v));
        }
        return json(200, arr.toString());
    }

    public static Reply delayed(long millis, Reply then) {
        return exchange -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                then.write(exchange);
            } catch (IOException ignored) {
                // 客户端已超时断开
            }
        };
    }

    public static JSONObject receipt(String from, String hash, long blockNumber, String status, String statusMsg,
                                     String output, String message, boolean statusOK) {
        JSONObject r = new JSONObject();
        r.set("transactionHash", hash);
        r.set("transactionIndex", "0x0");
        r.set("root", "0x" + "0".repeat(64));
        r.set("blockNumber", String.valueOf(blockNumber));
        r.set("blockHash", "0x" + "b".repeat(64));
        r.set("from", from);
        r.set("to", "0x3d37f47620091952443a1df9c6b23a443e746beb");
        r.set("gasUsed", "45797");
        r.set("contractAddress", ZERO);
        r.set("logs", new JSONArray());
        r.set("status", status);
        r.set("statusMsg", statusMsg);
        r.set("input", "0x");
        r.set("output", output);
        r.set("message", message);
        r.set("statusOK", statusOK);
        return r;
    }

    /** 按 Solidity Error(string) 的 ABI 编码 revert 文本 */
    public static String encodeError(String reason) {
        byte[] bytes = reason.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder("0x08c379a0");
        sb.append(String.format("%064x", 32));
        sb.append(String.format("%064x", bytes.length));
        StringBuilder data = new StringBuilder();
        for (byte b : bytes) {
            data.append(String.format("%02x", b));
        }
        while (data.length() % 64 != 0) {
            data.append('0');
        }
        return sb.append(data).toString();
    }

    public String nextHash() {
        return String.format("0x%064x", System.nanoTime());
    }

    public long nextBlock() {
        return block.incrementAndGet();
    }

    /**
     * 按合约 v2 的规则模拟 Trace 的三阶段写入：生产必须是新溯源号，分销必须在生产之后，零售必须在分销之后，每阶段只写一次。
     * revert 文本与合约一致；读函数的返回结构与真实链一致（全部是字符串，未写入的阶段为空串和 0）。不校验角色。
     */
    static final class ContractSim {
        private static final String[] WRITES = {"newAgroFood", "addTraceInfoByDistributor", "addTraceInfoByRetailer"};
        private static final String[] READS = {"getAgroFoodInfo", "getAgroFoodInfoByDistributor", "getAgroFoodInfoByRetailer"};
        private static final int[] WIDTH = {7, 8, 6};

        private static final class Item {
            final String[] actors = {ZERO, ZERO, ZERO};
            final List<List<String>> data = new ArrayList<>(List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
            final long[] timestamps = new long[3];
            int stage;
        }

        private final Map<String, Item> items = new ConcurrentHashMap<>();

        synchronized Reply handle(FakeWeBaseFront fake, Request request) {
            String func = request.funcName;
            List<String> params = request.params == null ? new ArrayList<>() : request.params.toList(String.class);
            String tn = params.isEmpty() ? "" : params.get(0);
            for (int i = 0; i < 3; i++) {
                if (WRITES[i].equals(func)) {
                    Item item = items.get(tn);
                    if (i == 0 && item != null) {
                        return receiptRevert(request.user, fake.nextHash(), fake.nextBlock(), "Trace: traceNumber already exists");
                    }
                    if (i > 0 && item == null) {
                        return receiptRevert(request.user, fake.nextHash(), fake.nextBlock(), "Trace: traceNumber does not exist");
                    }
                    if (i == 1 && item.stage >= 2) {
                        return receiptRevert(request.user, fake.nextHash(), fake.nextBlock(), "Trace: distribution already recorded");
                    }
                    if (i == 2 && item.stage < 2) {
                        return receiptRevert(request.user, fake.nextHash(), fake.nextBlock(), "Trace: distribution not recorded yet");
                    }
                    if (i == 2 && item.stage >= 3) {
                        return receiptRevert(request.user, fake.nextHash(), fake.nextBlock(), "Trace: retail already recorded");
                    }
                    if (item == null) {
                        item = new Item();
                        items.put(tn, item);
                    }
                    item.actors[i] = request.user;
                    item.data.set(i, new ArrayList<>(params.subList(1, params.size())));
                    item.timestamps[i] = System.currentTimeMillis();
                    item.stage = i + 1;
                    return receiptSuccess(request.user, fake.nextHash(), fake.nextBlock());
                }
                if (READS[i].equals(func)) {
                    Item item = items.get(tn);
                    if (item == null) {
                        return callRevert("Trace: traceNumber does not exist");
                    }
                    JSONArray arr = new JSONArray();
                    if (item.timestamps[i] == 0) {
                        for (int k = 0; k < WIDTH[i]; k++) {
                            arr.add(isUint(i, k) ? "0" : "");
                        }
                        arr.add("0");
                    } else {
                        item.data.get(i).forEach(arr::add);
                        arr.add(String.valueOf(item.timestamps[i]));
                    }
                    return json(200, arr.toString());
                }
            }
            if ("getStageActors".equals(func)) {
                Item item = items.get(tn);
                if (item == null) {
                    return callRevert("Trace: traceNumber does not exist");
                }
                return callResult((Object[]) item.actors);
            }
            if ("getAgroFoodList".equals(func)) {
                JSONArray list = new JSONArray();
                list.add(new JSONArray(items.keySet()));
                return json(200, list.toString());
            }
            return null;
        }

        private static boolean isUint(int stage, int index) {
            return (stage == 1 && (index == 5 || index == 6)) || (stage == 2 && index >= 1 && index <= 3);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
