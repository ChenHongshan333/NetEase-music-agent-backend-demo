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

    List<KnowledgeBase> findByActiveTrue();

    /**
     * 根据关键词搜索，模糊匹配问题或关键词字段，只返回启用状态的记录
     */
    @Query("SELECT k FROM KnowledgeBase k WHERE k.active = true AND " +
           "(LOWER(k.question) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(k.keywords) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<KnowledgeBase> searchByKeyword(@Param("query") String query);

    /**
     * 用于RAG：active=true 且 question/keywords 模糊匹配，支持 limit=5（PageRequest.of(0,5)）
     */
    @Query("SELECT k FROM KnowledgeBase k WHERE k.active = true AND " +
            "(LOWER(k.question) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
            "LOWER(k.keywords) LIKE LOWER(CONCAT('%', :q, '%')))")
    List<KnowledgeBase> searchActiveTop(@Param("q") String q, Pageable pageable);

    /**
     * 根据ID查找启用状态的知识库
     */
    @Query("SELECT k FROM KnowledgeBase k WHERE k.id = :id AND k.active = true")
    KnowledgeBase findByIdAndActiveTrue(@Param("id") Long id);
}