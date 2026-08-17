package com.example.cs_agent_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 上下文装配冒烟测试。test profile 保证零外部依赖：H2 + 缓存关闭 + LLM stub。
 */
@SpringBootTest
@ActiveProfiles("test")
class CsAgentServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
