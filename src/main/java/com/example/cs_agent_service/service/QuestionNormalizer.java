package com.example.cs_agent_service.service;

import java.util.regex.Pattern;

/**
 * 问题归一化：把用户的口语化问法削成接近知识库 question/keywords 字面的形式，
 * 用于第一次 LIKE 检索 0 命中时的二次检索。
 *
 * <p>纯函数、无状态、无 Spring 依赖，便于单元测试。
 */
public final class QuestionNormalizer {

    private static final Pattern PUNCTUATION = Pattern.compile(
            "[\\s\\p{Punct}，。！？、；：“”‘’（）()【】\\[\\]{}<>《》]+");

    private static final Pattern FILLER = Pattern.compile(
            "(请问|麻烦|帮我|我想问|想问|请|怎么|如何|怎样|要|想|能|可以|我想知道)+");

    private static final Pattern TAIL_PARTICLE = Pattern.compile("(呢|呀|吗|啊|嘛)+$");

    private static final Pattern TAIL_PRICE = Pattern.compile(
            "(要多少钱|多少钱|多少|价格是多少|价钱是多少|是多少)$");

    /** 定点迭代上限。正常输入 1~2 轮即收敛，设上限只为防御性地杜绝死循环。 */
    private static final int MAX_PASSES = 5;

    private QuestionNormalizer() {
    }

    /**
     * @param q 原始问题，允许为 null
     * @return 归一化结果；null / 空白 / 纯标点输入一律返回空字符串，绝不抛异常
     */
    public static String normalize(String q) {
        if (q == null) {
            return "";
        }

        String s = PUNCTUATION.matcher(q.trim()).replaceAll("");

        // 迭代到不动点：单次 replaceAll 只能消掉"原本相邻"的填充词，
        // 删除动作本身会让新的填充词相邻（如 "怎请么" 删 "请" 后才出现 "怎么"）。
        // 迭代保证 normalize(normalize(x)) == normalize(x)，这条性质有测试守着。
        for (int i = 0; i < MAX_PASSES; i++) {
            String before = s;
            s = FILLER.matcher(s).replaceAll("");
            s = TAIL_PARTICLE.matcher(s).replaceAll("");
            s = TAIL_PRICE.matcher(s).replaceAll("");
            if (s.equals(before)) {
                break;
            }
        }
        return s;
    }
}
