package com.example.cs_agent_service.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在写接口的 controller 方法上，启用 {@code Idempotency-Key} 支持。
 *
 * <p>被标注的方法必须返回 {@code ResponseEntity}：回放时切面要能同时决定状态码和响应体，
 * 而返回裸 DTO 的方法只能由 Spring 决定状态码，切面无从覆盖。
 *
 * <p>GET 天然幂等，不需要标。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
}
