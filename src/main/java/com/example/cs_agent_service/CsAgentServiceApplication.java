package com.example.cs_agent_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import com.example.cs_agent_service.config.CacheProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;


@SpringBootApplication
@EnableConfigurationProperties(CacheProperties.class)
public class CsAgentServiceApplication {

	/**
	 * 兜底提供 Spring 管理的 ObjectMapper（用于 DashScopeClient 严格复用 Jackson 解析）。
	 * 你的依赖当前使用 spring-boot-starter-webmvc，可能不会自动装配 ObjectMapper。
	 */
	@Bean
	public ObjectMapper objectMapper() {
		return JsonMapper.builder()
				.findAndAddModules()
				.build();
	}

	public static void main(String[] args) {
		SpringApplication.run(CsAgentServiceApplication.class, args);
	}

}
