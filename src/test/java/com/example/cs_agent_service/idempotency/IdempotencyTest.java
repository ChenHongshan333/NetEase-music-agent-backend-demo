package com.example.cs_agent_service.idempotency;

import com.example.cs_agent_service.repo.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IdempotencyTest.FakeStoreConfig.class)
class IdempotencyTest {

    @TestConfiguration
    static class FakeStoreConfig {
        @Bean
        @Primary
        FakeIdempotencyStore fakeIdempotencyStore() {
            return new FakeIdempotencyStore();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeIdempotencyStore store;

    @Autowired
    private KnowledgeBaseRepository repository;

    private String body(String question) {
        return """
                {"question":"%s","answer":"答案","keywords":"kw"}
                """.formatted(question);
    }

    @BeforeEach
    void resetStore() {
        // 每个用例用独立的 key，store 本身不必清空；这里只是让断言更好读
    }

    @Test
    @DisplayName("不带 Idempotency-Key → 行为与改造前完全一致，重复提交就是重复插入")
    void withoutKeyBehavesAsBefore() throws Exception {
        String q = "无key条目AAA";
        int storeSizeBefore = store.size();

        mockMvc.perform(post("/api/knowledge").contentType(MediaType.APPLICATION_JSON).content(body(q)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(IdempotencyAspect.HEADER_REPLAYED));
        mockMvc.perform(post("/api/knowledge").contentType(MediaType.APPLICATION_JSON).content(body(q)))
                .andExpect(status().isOk());

        assertThat(countByQuestion(q)).isEqualTo(2);
        assertThat(store.size())
                .as("没有 key 就不该往幂等存储里写任何东西（store 是类内共享的，只比较增量）")
                .isEqualTo(storeSizeBefore);
    }

    @Test
    @DisplayName("同 key 同 body 两次 → 第二次回放，数据库只有 1 条")
    void sameKeySameBodyReplays() throws Exception {
        String q = "幂等条目BBB";
        String key = "key-bbb";

        MvcResult first = mockMvc.perform(post("/api/knowledge")
                        .header(IdempotencyAspect.HEADER_KEY, key)
                        .contentType(MediaType.APPLICATION_JSON).content(body(q)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(IdempotencyAspect.HEADER_REPLAYED))
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/knowledge")
                        .header(IdempotencyAspect.HEADER_KEY, key)
                        .contentType(MediaType.APPLICATION_JSON).content(body(q)))
                .andExpect(status().isOk())
                .andExpect(header().string(IdempotencyAspect.HEADER_REPLAYED, "true"))
                .andReturn();

        assertThat(countByQuestion(q))
                .as("第二次必须没有真的插入")
                .isEqualTo(1);

        // 回放的是原样字节，不是"重新算一遍得到的等价结果"
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("同 key 不同 body → 422，且不执行")
    void sameKeyDifferentBodyIsRejected() throws Exception {
        String key = "key-ccc";

        mockMvc.perform(post("/api/knowledge")
                        .header(IdempotencyAspect.HEADER_KEY, key)
                        .contentType(MediaType.APPLICATION_JSON).content(body("幂等条目CCC-1")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/knowledge")
                        .header(IdempotencyAspect.HEADER_KEY, key)
                        .contentType(MediaType.APPLICATION_JSON).content(body("幂等条目CCC-2")))
                .andExpect(status().is(422));

        assertThat(countByQuestion("幂等条目CCC-2")).isZero();
    }

    @Test
    @DisplayName("请求体字段顺序变化不影响判定：hash 的是规范化后的内容，不是原始字节")
    void fieldOrderDoesNotCauseFalseMismatch() throws Exception {
        String key = "key-order";
        String q = "幂等条目ORDER";

        mockMvc.perform(post("/api/knowledge")
                        .header(IdempotencyAspect.HEADER_KEY, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"%s\",\"answer\":\"答案\",\"keywords\":\"kw\"}".formatted(q)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/knowledge")
                        .header(IdempotencyAspect.HEADER_KEY, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keywords\":\"kw\",  \"answer\":\"答案\",\n\"question\":\"%s\"}".formatted(q)))
                .andExpect(status().isOk())
                .andExpect(header().string(IdempotencyAspect.HEADER_REPLAYED, "true"));

        assertThat(countByQuestion(q)).isEqualTo(1);
    }

    @Test
    @DisplayName("同一个 key 打到不同接口互不干扰：key 里含 method 和 route")
    void sameKeyOnDifferentEndpointsDoesNotCollide() throws Exception {
        String key = "key-shared";

        mockMvc.perform(post("/api/knowledge")
                        .header(IdempotencyAspect.HEADER_KEY, key)
                        .contentType(MediaType.APPLICATION_JSON).content(body("幂等条目SHARED")))
                .andExpect(status().isOk());

        // 同一个 key，不同接口 —— 必须正常执行，而不是回放上面那个响应
        mockMvc.perform(post("/api/conversations")
                        .header(IdempotencyAspect.HEADER_KEY, key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"customerId\":\"c-1\"}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(IdempotencyAspect.HEADER_REPLAYED))
                .andExpect(jsonPath("$.customerId").value("c-1"));
    }

    @Test
    @DisplayName("业务返回 4xx → key 保留为 COMPLETED，重试回放同一个 4xx")
    void clientErrorsAreRecordedAndReplayed() throws Exception {
        String key = "key-404";

        mockMvc.perform(delete("/api/knowledge/{id}", 999_999L)
                        .header(IdempotencyAspect.HEADER_KEY, key))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/knowledge/{id}", 999_999L)
                        .header(IdempotencyAspect.HEADER_KEY, key))
                .andExpect(status().isNotFound())
                .andExpect(header().string(IdempotencyAspect.HEADER_REPLAYED, "true"));
    }

    @Test
    @DisplayName("204 无响应体的接口也能正确回放")
    void noContentResponseReplays() throws Exception {
        var saved = repository.save(newEntity("待删除条目DDD"));
        String key = "key-204";

        mockMvc.perform(delete("/api/knowledge/{id}", saved.getId())
                        .header(IdempotencyAspect.HEADER_KEY, key))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/knowledge/{id}", saved.getId())
                        .header(IdempotencyAspect.HEADER_KEY, key))
                .andExpect(status().isNoContent())
                .andExpect(header().string(IdempotencyAspect.HEADER_REPLAYED, "true"));
    }

    @Test
    @DisplayName("同 key 打到不同路径变量 → 视为不同请求，422")
    void pathVariablesArePartOfTheRequestHash() throws Exception {
        var a = repository.save(newEntity("路径变量条目E1"));
        var b = repository.save(newEntity("路径变量条目E2"));
        String key = "key-path";

        mockMvc.perform(put("/api/knowledge/{id}", a.getId())
                        .header(IdempotencyAspect.HEADER_KEY, key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"新答案\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/knowledge/{id}", b.getId())
                        .header(IdempotencyAspect.HEADER_KEY, key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"新答案\"}"))
                .andExpect(status().is(422));
    }

    @Test
    @DisplayName("空 key / 超长 key → 400")
    void malformedKeysAreRejected() throws Exception {
        mockMvc.perform(post("/api/knowledge")
                        .header(IdempotencyAspect.HEADER_KEY, "   ")
                        .contentType(MediaType.APPLICATION_JSON).content(body("不该被创建F1")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/knowledge")
                        .header(IdempotencyAspect.HEADER_KEY, "k".repeat(256))
                        .contentType(MediaType.APPLICATION_JSON).content(body("不该被创建F2")))
                .andExpect(status().isBadRequest());

        assertThat(countByQuestion("不该被创建F1")).isZero();
        assertThat(countByQuestion("不该被创建F2")).isZero();
    }

    @Test
    @DisplayName("并发同 key 同 body → 恰好执行一次，另一个拿到 409 或回放")
    void concurrentSameKeyExecutesExactlyOnce() throws Exception {
        String q = "并发条目GGG";
        String key = "key-concurrent";

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<Outcome> outcomes = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        Runnable task = () -> {
            try {
                start.await();
                MvcResult r = mockMvc.perform(post("/api/knowledge")
                        .header(IdempotencyAspect.HEADER_KEY, key)
                        .contentType(MediaType.APPLICATION_JSON).content(body(q))).andReturn();
                outcomes.add(new Outcome(r.getResponse().getStatus(),
                        r.getResponse().getHeader(IdempotencyAspect.HEADER_REPLAYED) != null));
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                done.countDown();
            }
        };

        Thread t1 = new Thread(task, "idem-1");
        Thread t2 = new Thread(task, "idem-2");
        t1.start();
        t2.start();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(countByQuestion(q))
                .as("并发下必须恰好插入一条")
                .isEqualTo(1);

        assertThat(errors).as("两个请求都必须拿到 HTTP 响应，不能有请求炸掉").isEmpty();
        assertThat(outcomes).hasSize(2);

        // 恰好一个请求真正执行：200 且没有 Replayed 头。
        assertThat(outcomes.stream().filter(Outcome::executed).count())
                .as("只能有一个请求真正执行，实际结果 %s", outcomes)
                .isEqualTo(1);

        // 另一个要么被 409 挡下（前一个还在 IN_PROGRESS），要么拿到回放的 200。
        // 两者都可接受：竞争的结果取决于第一个请求有没有来得及写完 COMPLETED。
        assertThat(outcomes).allSatisfy(o ->
                assertThat(o.status()).isIn(200, 409));
    }

    @Test
    @DisplayName("存储不可用 → 写接口 fail closed 返回 503，绝不放行")
    void storeOutageFailsClosed() throws Exception {
        String q = "存储故障条目HHH";
        store.breakStore();
        try {
            mockMvc.perform(post("/api/knowledge")
                            .header(IdempotencyAspect.HEADER_KEY, "key-outage")
                            .contentType(MediaType.APPLICATION_JSON).content(body(q)))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            // 这个 store 是共享 bean，必须还原，否则会污染同类里的其它用例
            store.repair();
        }

        assertThat(countByQuestion(q))
                .as("fail closed 意味着业务方法根本没被执行")
                .isZero();
    }

    /** 200 且没有 Replayed 头 = 这个请求真的执行了业务方法。 */
    private record Outcome(int status, boolean replayed) {
        boolean executed() {
            return status == 200 && !replayed;
        }
    }

    private long countByQuestion(String question) {
        return repository.findAll().stream()
                .filter(k -> question.equals(k.getQuestion()))
                .count();
    }

    private static com.example.cs_agent_service.entity.KnowledgeBase newEntity(String question) {
        var k = new com.example.cs_agent_service.entity.KnowledgeBase();
        k.setQuestion(question);
        k.setAnswer("答案");
        k.setActive(true);
        return k;
    }
}
