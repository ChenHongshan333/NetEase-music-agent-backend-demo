package com.example.cs_agent_service.repo;

import com.example.cs_agent_service.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    /** LIKE 转义字符。选 '!' 而不是反斜杠，避免 JPQL 字符串字面量里的转义歧义。 */
    String LIKE_ESCAPE = "!";

    List<KnowledgeBase> findByActiveTrue();

    /**
     * 根据关键词搜索，模糊匹配问题或关键词字段，只返回启用状态的记录。
     * 入参会被转义，见 {@link #escapeLike(String)}。
     */
    default List<KnowledgeBase> searchByKeyword(String query) {
        return searchByKeywordEscaped(escapeLike(query));
    }

    /**
     * 用于 RAG：active=true 且 question/keywords 模糊匹配，limit 由 Pageable 控制。
     * 入参会被转义，见 {@link #escapeLike(String)}。
     */
    default List<KnowledgeBase> searchActiveTop(String q, Pageable pageable) {
        return searchActiveTopEscaped(escapeLike(q), pageable);
    }

    /**
     * 把用户输入中的 LIKE 元字符转成字面量。
     *
     * <p>不转义的话，用户输入一个 {@code %} 就会让 {@code LIKE '%%%'} 匹配全表：
     * 检索"命中"了 5 条毫不相关的记录，于是拒答闸门失效，我们拿着无关上下文去调 LLM，
     * 用户收到一个自信的幻觉回答。这既是正确性 bug 也是成本 bug。
     *
     * <p>转义字符自身必须先处理，否则会把后面补上的转义符再转义一遍。
     */
    static String escapeLike(String s) {
        if (s == null) {
            return null;
        }
        return s.replace(LIKE_ESCAPE, LIKE_ESCAPE + LIKE_ESCAPE)
                .replace("%", LIKE_ESCAPE + "%")
                .replace("_", LIKE_ESCAPE + "_");
    }

    @Query("SELECT k FROM KnowledgeBase k WHERE k.active = true AND " +
           "(LOWER(k.question) LIKE LOWER(CONCAT('%', :query, '%')) ESCAPE '!' OR " +
           "LOWER(k.keywords) LIKE LOWER(CONCAT('%', :query, '%')) ESCAPE '!')")
    List<KnowledgeBase> searchByKeywordEscaped(@Param("query") String query);

    @Query("SELECT k FROM KnowledgeBase k WHERE k.active = true AND " +
            "(LOWER(k.question) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '!' OR " +
            "LOWER(k.keywords) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '!')")
    List<KnowledgeBase> searchActiveTopEscaped(@Param("q") String q, Pageable pageable);

    /**
     * 根据ID查找启用状态的知识库
     */
    @Query("SELECT k FROM KnowledgeBase k WHERE k.id = :id AND k.active = true")
    KnowledgeBase findByIdAndActiveTrue(@Param("id") Long id);
}
