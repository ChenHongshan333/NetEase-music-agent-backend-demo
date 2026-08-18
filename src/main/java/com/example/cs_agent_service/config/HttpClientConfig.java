package com.example.cs_agent_service.config;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * OkHttpClient 必须单例复用：它内部持有连接池和线程池，
 * 每次请求 new 一个会导致连接无法复用、线程数无上限增长。
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public OkHttpClient okHttpClient(LlmTimeoutProperties timeouts) {
        return new OkHttpClient.Builder()
                .connectTimeout(timeouts.getConnectMs(), TimeUnit.MILLISECONDS)
                .readTimeout(timeouts.getReadMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(timeouts.getWriteMs(), TimeUnit.MILLISECONDS)
                // callTimeout 是唯一覆盖"整次调用"的上限（含 DNS、连接、重定向）。
                // 不设它的话，一个不断慢慢吐字节的上游可以无限期地拖住一个线程，
                // 因为每一段读取都没超过 readTimeout。
                .callTimeout(timeouts.getCallMs(), TimeUnit.MILLISECONDS)
                .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
                .retryOnConnectionFailure(false)
                .build();
    }
}
