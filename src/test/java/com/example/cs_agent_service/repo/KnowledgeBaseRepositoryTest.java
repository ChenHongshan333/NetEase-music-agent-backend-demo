package com.example.cs_agent_service.repo;

import com.example.cs_agent_service.entity.KnowledgeBase;
import com.example.cs_agent_service.service.KnowledgeBaseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跑真实 H2，验证检索的两条正确性：软删除必须生效，用户输入不能穿透成 LIKE 元字符。
 */
@DataJpaTest
@Import(KnowledgeBaseService.class)
class KnowledgeBaseRepositoryTest {

    @Autowired
    private KnowledgeBaseRepository repository;

    @Autowired
    private KnowledgeBaseService service;

    @Test
    @DisplayName("data.sql 的种子数据被 LIKE 命中")
    void matchesSeedData() {
        List<KnowledgeBase> hits = service.searchTop5("黑胶VIP");

        assertThat(hits).isNotEmpty();
        assertThat(hits).allSatisfy(k -> assertThat(k.getActive()).isTrue());
        assertThat(hits.size()).isLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("Top-5 上限被遵守")
    void respectsTopKLimit() {
        // "会员" 在种子数据里出现次数远多于 5 条
        assertThat(service.searchTop5("会员").size()).isLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("软删除：active=false 的记录不出现在 searchTop5 结果中")
    void softDeletedRowsAreExcluded() {
        String marker = "唯一标记词ZZQQ";

        KnowledgeBase active = new KnowledgeBase();
        active.setQuestion(marker + "启用条目");
        active.setAnswer("启用的答案");
        active.setActive(true);
        repository.save(active);

        KnowledgeBase inactive = new KnowledgeBase();
        inactive.setQuestion(marker + "停用条目");
        inactive.setAnswer("停用的答案");
        inactive.setActive(false);
        repository.save(inactive);

        List<KnowledgeBase> hits = service.searchTop5(marker);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getQuestion()).isEqualTo(marker + "启用条目");
    }

    @Test
    @DisplayName("软删除后再检索：deactivate 过的条目立刻从检索结果消失")
    void deactivateRemovesFromRetrieval() {
        String marker = "另一个唯一标记YYWW";

        KnowledgeBase kb = new KnowledgeBase();
        kb.setQuestion(marker);
        kb.setAnswer("答案");
        kb.setActive(true);
        KnowledgeBase saved = repository.save(kb);

        assertThat(service.searchTop5(marker)).hasSize(1);

        service.deactivate(saved.getId());

        assertThat(service.searchTop5(marker)).isEmpty();
    }

    /**
     * 通配符注入。转义之前 searchTop5("%") 会命中全表 —— 检索"有命中"于是拒答闸门失效，
     * 我们拿着 5 条随机知识去调 LLM。这是被这个测试逼出来的真实 bug。
     */
    @ParameterizedTest
    @DisplayName("LIKE 元字符不穿透：% 和 _ 被当作字面量，不会匹配全表")
    @ValueSource(strings = {"%", "_", "%%", "%_%", "!", "!%"})
    void likeWildcardsAreEscaped(String injection) {
        // 种子数据里没有任何 question/keywords 含这些字面字符
        assertThat(service.searchTop5(injection))
                .as("输入 '%s' 不应命中任何记录", injection)
                .isEmpty();
    }

    /**
     * 反证：直接把未转义的 "%" 喂给底层查询，确认它真的会匹配全表。
     * 这条测试的存在是为了证明 {@code escapeLike} 修的是一个真 bug，而不是防御性洁癖。
     */
    @Test
    @DisplayName("反证：绕过转义直接查询时，'%' 确实命中全表")
    void unescapedPercentWouldMatchEverything() {
        List<KnowledgeBase> leaked = repository.searchActiveTopEscaped(
                "%", org.springframework.data.domain.PageRequest.of(0, 5));

        assertThat(leaked)
                .as("未转义的 %% 会被当成通配符，撑满整个 Top-5")
                .hasSize(5);
    }

    @Test
    @DisplayName("转义后仍能匹配真的含 % 的内容")
    void escapingStillMatchesLiteralPercent() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setQuestion("会员折扣是 80% 吗");
        kb.setAnswer("是的");
        kb.setActive(true);
        repository.save(kb);

        assertThat(service.searchTop5("80%")).hasSize(1);
    }

    @Test
    @DisplayName("list(q) 走的同一条 LIKE 路径，同样不被通配符穿透")
    void listIsAlsoEscaped() {
        int all = service.list(null).size();
        assertThat(all).isGreaterThan(5);
        assertThat(service.list("%")).isEmpty();
    }
}
