package com.qhx.back.task;

import com.qhx.back.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 定时清理孤儿文件：UPLOADED 超过 file.orphan.ttl-hours 仍未被确认的交易引用 → ORPHANED，并从本地 IPFS 取消 pin。
 * 策略见 docs/files.md「清理策略」。
 */
@Slf4j
@Component
public class FileOrphanTask
{
    @Autowired
    private FileService fileService;

    @Value("${file.orphan.cleanup-enabled:true}")
    private boolean enabled;

    @Scheduled(initialDelayString = "${file.orphan.cleanup-interval-ms:3600000}",
            fixedDelayString = "${file.orphan.cleanup-interval-ms:3600000}")
    public void run()
    {
        if (!enabled) {
            return;
        }
        try {
            fileService.cleanupOrphans(new Date());
        } catch (RuntimeException e) {
            // 数据库或 IPFS 暂时不可用：下一轮再试
            log.error("孤儿文件清理失败", e);
        }
    }
}
