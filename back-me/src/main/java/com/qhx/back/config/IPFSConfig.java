package com.qhx.back.config;

import io.ipfs.api.IPFS;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

@Configuration
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class IPFSConfig
{

    @Value("${ipfs.host}")
    private String host;

    @Value("${ipfs.port}")
    private int port;


    @Bean
    public IPFS getIPFS()
    {
        return new IPFS(host, port);
    }
}
