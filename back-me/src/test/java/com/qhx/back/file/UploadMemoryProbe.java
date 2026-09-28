package com.qhx.back.file;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 在独立的小堆 JVM（UploadMemoryBoundTest 用 -Xmx48m 启动）里跑一遍完整上传流程：检查 → 流式写入 kubo → 读回核对。
 * 文件比堆大好几倍，只要有任何一步把文件整块读进内存就会 OutOfMemoryError、进程非 0 退出。
 * 输出一行 RESULT，供父进程断言。
 */
public final class UploadMemoryProbe {

    private UploadMemoryProbe() {
    }

    public static void main(String[] args) throws Exception {
        String apiUrl = args[0];
        long size = Long.parseLong(args[1]);
        long seed = Long.parseLong(args[2]);

        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            pool.resetPeakUsage();
        }
        AtomicLong peak = new AtomicLong();
        Thread sampler = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max);
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.setDaemon(true);
        sampler.start();

        UploadPipeline pipeline = new UploadPipeline(new KuboClient(apiUrl, 3000, 300000), size);
        UploadPipeline.Stored stored = pipeline.store(() -> new GeneratedPng(size, seed), "big.png");
        sampler.interrupt();

        long poolPeak = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                poolPeak += pool.getPeakUsage().getUsed();
            }
        }
        System.out.println("RESULT cid=" + stored.cid + " sha256=" + stored.sha256 + " size=" + stored.size
                + " maxHeap=" + Runtime.getRuntime().maxMemory() + " sampledPeakHeap=" + peak.get() + " poolPeakSum=" + poolPeak);
    }
}
