package com.example.cs_agent_service.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯单元测试：不加载 Spring 容器。
 */
class NormalizeQuestionTest {

    @Test
    @DisplayName("去除标点：全角问号被剥掉")
    void stripsPunctuation() {
        String out = QuestionNormalizer.normalize("会员多少钱？");
        assertThat(out).doesNotContain("？");
        assertThat(out).isEqualTo("会员");
    }

    @Test
    @DisplayName("去除口语词：请问 / 怎么 不出现在结果里")
    void stripsFillerWords() {
        String out = QuestionNormalizer.normalize("请问会员怎么取消续费");
        assertThat(out).doesNotContain("请问").doesNotContain("怎么");
        assertThat(out).isEqualTo("会员取消续费");
    }

    @Test
    @DisplayName("空字符串输入返回空字符串，不抛异常")
    void emptyInput() {
        assertThat(QuestionNormalizer.normalize("")).isEmpty();
        assertThat(QuestionNormalizer.normalize("   ")).isEmpty();
    }

    @Test
    @DisplayName("null 输入返回空字符串（约定行为：宽容不抛）")
    void nullInput() {
        // 这是二次检索路径上的降级工具，宁可返回空串让调用方走"无命中"，
        // 也不要在主链路上抛异常。
        assertThat(QuestionNormalizer.normalize(null)).isEmpty();
    }

    @Test
    @DisplayName("纯标点输入返回空字符串")
    void punctuationOnly() {
        assertThat(QuestionNormalizer.normalize("？？？")).isEmpty();
        assertThat(QuestionNormalizer.normalize("，。！【】()")).isEmpty();
    }

    @Test
    @DisplayName("中英混合 + 全角半角标点混合")
    void mixedScriptAndWidth() {
        // "价格是多少" 整体命中尾部价格规则，所以剩下的是 "VIP" 而不是 "VIP价格"
        assertThat(QuestionNormalizer.normalize("请问 VIP 价格是多少?")).isEqualTo("VIP");
        assertThat(QuestionNormalizer.normalize("APP闪退，怎么办呀！")).isEqualTo("APP闪退办");
    }

    @ParameterizedTest
    @DisplayName("幂等：normalize(normalize(x)) == normalize(x)")
    @ValueSource(strings = {
            "会员多少钱？",
            "请问会员怎么取消续费",
            "怎请么办呢",
            "我想知道云贝能干什么呀？",
            "请问 VIP 价格是多少?",
            "黑胶VIP",
            "？？？",
            "",
            "   ",
            "麻烦帮我看看学生认证如何开通"
    })
    void idempotent(String input) {
        String once = QuestionNormalizer.normalize(input);
        assertThat(QuestionNormalizer.normalize(once)).isEqualTo(once);
    }

    @Test
    @DisplayName("删除动作制造出的新相邻口语词也会被消掉（定点迭代）")
    void reachesFixedPointInOnePass() {
        // "怎请么" 删掉中间的 "请" 之后才出现 "怎么"；单次 replaceAll 会漏掉它。
        assertThat(QuestionNormalizer.normalize("怎请么办")).isEqualTo("办");
    }
}
