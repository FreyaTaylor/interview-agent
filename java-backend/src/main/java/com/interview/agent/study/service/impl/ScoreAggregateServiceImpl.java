package com.interview.agent.study.service.impl;

import com.interview.agent.auth.CurrentUser;
import com.interview.agent.knowledge.mapper.KnowledgeNodeMapper;
import com.interview.agent.learn.mapper.KnowledgeSubtopicMapper;
import com.interview.agent.study.mapper.QuestionAttemptMapper;
import com.interview.agent.study.service.ScoreAggregateService;
import org.springframework.stereotype.Service;

/**
 * {@link ScoreAggregateService} 实现。
 *
 * <p>聚合算法全部下沉到 SQL（{@code QuestionAttemptMapper.avgQuestionScore} / {@code avgKpMastery}），
 * Java 侧只做四舍五入 + 写回。
 */
@Service
public class ScoreAggregateServiceImpl implements ScoreAggregateService {

    private final QuestionAttemptMapper attemptMapper;
    private final KnowledgeNodeMapper nodeMapper;
    private final KnowledgeSubtopicMapper subtopicMapper;

    public ScoreAggregateServiceImpl(QuestionAttemptMapper attemptMapper,
                                     KnowledgeNodeMapper nodeMapper,
                                     KnowledgeSubtopicMapper subtopicMapper) {
        this.attemptMapper = attemptMapper;
        this.nodeMapper = nodeMapper;
        this.subtopicMapper = subtopicMapper;
    }

    @Override
    public Integer questionScore(long questionId) {
        Double avg = attemptMapper.avgQuestionScore(CurrentUser.id(), questionId, Impl.RECENT_N);
        return avg == null ? null : (int) Math.round(avg);
    }

    @Override
    public Integer kpMastery(long kpId) {
        Double avg = attemptMapper.avgKpMastery(CurrentUser.id(), kpId, Impl.RECENT_N);
        return avg == null ? null : (int) Math.round(avg);
    }

    @Override
    public Integer refreshKpMastery(long kpId) {
        // Step 1: 算最新掌握度
        Integer mastery = kpMastery(kpId);
        // Step 2: 写回（mastery 为 null 也允许，前端按 null 渲染"未学过"）
        nodeMapper.updateMastery(kpId, CurrentUser.id(), mastery);
        return mastery;
    }

    @Override
    public Integer subtopicMastery(long subtopicId) {
        Double avg = attemptMapper.avgSubtopicMastery(CurrentUser.id(), subtopicId, Impl.RECENT_N);
        return avg == null ? null : (int) Math.round(avg);
    }

    @Override
    public Integer refreshSubtopicMastery(long subtopicId) {
        Integer mastery = subtopicMastery(subtopicId);
        subtopicMapper.updateMastery(subtopicId, CurrentUser.id(), mastery);
        return mastery;
    }
}
