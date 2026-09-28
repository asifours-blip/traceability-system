package com.qhx.back.config;

import com.qhx.back.file.KuboClient;
import com.qhx.back.file.UploadPipeline;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * IPFS（kubo HTTP RPC）客户端与上传流程。启动时不连节点：节点不可用只影响文件相关接口（返回 503），不影响其他功能。
 */
@Configuration
public class IpfsConfig
{
    @Bean
    public KuboClient kuboClient(@Value("${ipfs.api-url}") String apiUrl,
                                 @Value("${ipfs.connect-timeout-ms:3000}") int connectTimeoutMs,
                                 @Value("${ipfs.read-timeout-ms:30000}") int readTimeoutMs)
    {
        return new KuboClient(apiUrl, connectTimeoutMs, readTimeoutMs);
    }

    @Bean
    public UploadPipeline uploadPipeline(KuboClient kuboClient, @Value("${file.max-bytes}") long maxBytes)
    {
        return new UploadPipeline(kuboClient, maxBytes);
    }
}
