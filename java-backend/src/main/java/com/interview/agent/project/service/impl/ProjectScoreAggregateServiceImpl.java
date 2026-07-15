package com.interview.agent.project.service.impl;

import com.interview.agent.auth.CurrentUser;
import com.interview.agent.project.entity.Project;
import com.interview.agent.project.entity.ProjectNode;
import com.interview.agent.project.mapper.ProjectAttemptMapper;
import com.interview.agent.project.mapper.ProjectMapper;
import com.interview.agent.project.mapper.ProjectNodeMapper;
import com.interview.agent.project.service.ProjectScoreAggregateService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * {@link ProjectScoreAggregateService} 实现。
 *
 * <p>三级聚合都在 Java 侧循环 + 汇总（与 Python qa_aggregate.py 同款）：
 * <ul>
 *   <li>话题分：拉该 L2 下所有 L3 → 每个 L3 调 {@link ProjectAttemptMapper#avgQuestionScore} → 只对已答题取平均
 *       （未答的题不计入分母，避免拉低话题分）</li>
 *   <li>项目准备度：拉该项目 root 下所有 L2 → 每个 L2 算话题分 → 只对有话题分的取平均</li>
 * </ul>
 *
 * <p>不做 N+1 优化：当前体量（单项目 ~10 话题 × ~5 题）查询次数可控；
 * 真要优化把整段算法迁到一条 SQL JOIN COUNT GROUP BY 即可。
 */
@Service
public class ProjectScoreAggregateServiceImpl implements ProjectScoreAggregateService {

    private final ProjectAttemptMapper attemptMapper;
    private final ProjectNodeMapper nodeMapper;
    private final ProjectMapper projectMapper;

    public ProjectScoreAggregateServiceImpl(ProjectAttemptMapper attemptMapper,
                                            ProjectNodeMapper nodeMapper,
                                            ProjectMapper projectMapper) {
        this.attemptMapper = attemptMapper;
        this.nodeMapper = nodeMapper;
        this.projectMapper = projectMapper;
    }

    @Override
    public Integer questionScore(long questionId) {
        Double avg = attemptMapper.avgQuestionScore(CurrentUser.id(), questionId, Impl.RECENT_N);
        return avg == null ? null : (int) Math.round(avg);
    }

    @Override
    public int questionAttemptCount(long questionId) {
        return attemptMapper.countFinishedAttempts(CurrentUser.id(), questionId);
    }

    @Override
    public TopicScore topicScore(long topicId) {
        // Step 1: 拉该 L2 下所有 L3 叶子
        List<ProjectNode> leaves = nodeMapper.findChildrenByLevel(topicId, (short) 3, CurrentUser.id());
        if (leaves.isEmpty()) {
            return new TopicScore(null, 0);
        }

        // Step 2: 累加每个 L3 的题目分（只计有分数的题）+ 累加 finished 次数
        int scoreSum = 0;
        int answeredCount = 0;
        int totalAttempts = 0;
        for (ProjectNode leaf : leaves) {
            Integer s = questionScore(leaf.id());
            int cnt = questionAttemptCount(leaf.id());
            totalAttempts += cnt;
            if (s != null) {
                scoreSum += s;
                answeredCount++;
            }
        }

        // Step 3: 全部未答 → avg=null（语义“暂未练习”）
        if (answeredCount == 0) {
            return new TopicScore(null, totalAttempts);
        }
        return new TopicScore((int) Math.round((double) scoreSum / answeredCount), totalAttempts);
    }

    @Override
    public Integer projectReadiness(long projectId) {
        // Step 1: 取项目 root
        Optional<Project> projectOpt = projectMapper.findById(projectId, CurrentUser.id());
        if (projectOpt.isEmpty() || projectOpt.get().rootNodeId() == null) {
            return null;
        }
        long rootId = projectOpt.get().rootNodeId();

        // Step 2: 拉所有 L2 话题
        List<ProjectNode> topics = nodeMapper.findChildrenByLevel(rootId, (short) 2, CurrentUser.id());
        if (topics.isEmpty()) {
            return null;
        }

        // Step 3: 累加话题分（只计有分数的话题）
        int scoreSum = 0;
        int answeredTopics = 0;
        for (ProjectNode t : topics) {
            Integer s = topicScore(t.id()).avgScore();
            if (s != null) {
                scoreSum += s;
                answeredTopics++;
            }
        }
        if (answeredTopics == 0) {
            return null;
        }
        return (int) Math.round((double) scoreSum / answeredTopics);
    }
}
