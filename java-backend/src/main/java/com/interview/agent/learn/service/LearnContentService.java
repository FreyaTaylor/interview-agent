package com.interview.agent.learn.service;

import com.interview.agent.learn.dto.ContentView;
import com.interview.agent.learn.dto.LearnAssetRequest;

/**
 * Learn 模块 · 讲解（content）业务接口。
 *
 * <p>职责：知识点讲解 Markdown 的取/生成/重生；与题目、对话完全无关。
 * <p>用户：一期写死 user_id=1（CONVENTIONS §1）。
 */
public interface LearnContentService {

    /** 总入口：根据 {@code action} 分发 fetch / regenerate。 */
    ContentView resolveContent(LearnAssetRequest req);

    /** 幂等保证：若讲解不存在则生成并落库（供 S3 串联调用）。 */
    void ensureContent(long kpId);

    /** 删除单个子话题（需同时传 kpId 防越权）；记录不存在 → 抛 40400。 */
    void deleteSubtopic(long kpId, long subtopicId);

    /**
     * 设置/清除知识点自评掌握度（与答题派生掌握度独立）。
     * @param selfMastery null=清除；非 null 时 clamp 到 [0,100]
     * @return 落库后的自评值（null=已清除）
     */
    Integer setSelfMastery(long kpId, Integer selfMastery);
}
