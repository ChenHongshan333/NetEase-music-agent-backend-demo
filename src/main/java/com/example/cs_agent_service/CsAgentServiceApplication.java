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
