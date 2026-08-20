package com.example.cs_agent_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;


@SpringBootApplication
@ConfigurationPropertiesScan
public class CsAgentServiceApplication {


	@Bean
	public ObjectMapper objectMapper() {
		return JsonMapper.builder()
				.findAndAddModules()
				.build();
	}

	/**
	 * 时间从容器里注入而不是直接调 System.currentTimeMillis()，
	 * 这样熔断器的状态机测试可以手动推进时钟，不必 Thread.sleep 30 秒。
	 */
	@Bean
	public java.time.Clock clock() {
		return java.time.Clock.systemDefaultZone();
	}

	public static void main(String[] args) {
		SpringApplication.run(CsAgentServiceApplication.class, args);
	}

}
