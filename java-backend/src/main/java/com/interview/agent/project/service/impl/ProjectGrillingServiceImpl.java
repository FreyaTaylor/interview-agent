package com.interview.agent.project.service.impl;

import com.interview.agent.project.dto.DimensionItem;
import com.interview.agent.project.dto.ProfileResponse;
import com.interview.agent.project.dto.ProjectListItem;
import com.interview.agent.project.dto.TopicQuestionsResponse;
import com.interview.agent.project.entity.Project;
import com.interview.agent.project.entity.ProjectNode;
import com.interview.agent.project.entity.ProjectUserProfile;
import com.interview.agent.project.mapper.ProjectMapper;
import com.interview.agent.project.mapper.ProjectNodeMapper;
import com.interview.agent.project.mapper.ProjectUserProfileMapper;
import com.interview.agent.project.service.ProjectGrillingService;
import com.interview.agent.project.service.ProjectScoreAggregateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * {@link ProjectGrillingService} 默认实现 —— S7.1 范围（4 个只读编排）。
 *
 * <p>模块逻辑总览：
 * <ol>
 *   <li>{@link #listProjects}：取该 user 的所有 project → 每个项目调聚合 readinessScore + countLeavesUnderRoot</li>
 *   <li>{@link #profileDetail}：findByProjectUser；不存在则 {@code ensureRowExists} 再读一次（懒创建语义）</li>
 *   <li>{@link #dimensionsList}：项目 root → L2 话题列表 → 每个话题调 topicScore</li>
 *   <li>{@link #topicQuestions}：L2 话题 → L3 题目列表 → 每题调 questionScore + questionAttemptCount</li>
 * </ol>
 *
 * <p>设计取舍：N+1 SQL 是有意的（单项目体量 ~50 题，可忽略）；走应用层循环换代码可读性。
 */
@Service
public class ProjectGrillingServiceImpl implements ProjectGrillingService {

    private static final long USER_ID = 1L;

    private final ProjectMapper projectMapper;
    private final ProjectNodeMapper nodeMapper;
    private final ProjectUserProfileMapper profileMapper;
    private final ProjectScoreAggregateService aggregateService;

    public ProjectGrillingServiceImpl(ProjectMapper projectMapper,
                                      ProjectNodeMapper nodeMapper,
                                      ProjectUserProfileMapper profileMapper,
                                      ProjectScoreAggregateService aggregateService) {
        this.projectMapper = projectMapper;
        this.nodeMapper = nodeMapper;
        this.profileMapper = profileMapper;
        this.aggregateService = aggregateService;
    }

    @Override
    public List<ProjectListItem> listProjects() {
        List<Project> projects = projectMapper.listByUser(USER_ID);
        List<ProjectListItem> result = new ArrayList<>(projects.size());
        for (Project p : projects) {
            int qCount = p.rootNodeId() == null ? 0 : nodeMapper.countLeavesUnderRoot(p.rootNodeId());
            Integer readiness = aggregateService.projectReadiness(p.id());
            result.add(new ProjectListItem(
                    p.id(),
                    p.name(),
                    p.description(),
                    p.techStack(),
                    p.role(),
                    p.highlights(),
                    qCount,
                    readiness
            ));
        }
        return result;
    }

    @Override
    @Transactional
    public ProfileResponse profileDetail(long projectId) {
        Optional<ProjectUserProfile> opt = profileMapper.findByProjectUser(projectId, USER_ID);
        if (opt.isEmpty()) {
            profileMapper.ensureRowExists(projectId, USER_ID);
            opt = profileMapper.findByProjectUser(projectId, USER_ID);
        }
        ProjectUserProfile p = opt.orElseThrow(() -> new IllegalStateException(
                "ensureRowExists 后仍读不到 project_user_profile：project_id=" + projectId));
        return new ProfileResponse(
                normalizeJsonbList(p.projectFacts()),
                p.version()
        );
    }

    @Override
    public List<DimensionItem> dimensionsList(long projectId) {
        Optional<Project> projectOpt = projectMapper.findById(projectId);
        if (projectOpt.isEmpty() || projectOpt.get().rootNodeId() == null) {
            return Collections.emptyList();
        }
        long rootId = projectOpt.get().rootNodeId();
        List<ProjectNode> topics = nodeMapper.findChildrenByLevel(rootId, (short) 2);
        List<DimensionItem> result = new ArrayList<>(topics.size());
        for (ProjectNode t : topics) {
            int qCount = nodeMapper.findChildrenByLevel(t.id(), (short) 3).size();
            ProjectScoreAggregateService.TopicScore ts = aggregateService.topicScore(t.id());
            result.add(new DimensionItem(t.id(), t.name(), qCount, ts.attemptCount(), ts.avgScore()));
        }
        return result;
    }

    @Override
    public TopicQuestionsResponse topicQuestions(long topicId) {
        ProjectNode topic = nodeMapper.findById(topicId).orElseThrow(() ->
                new IllegalArgumentException("话题不存在：topic_id=" + topicId));
        List<ProjectNode> leaves = nodeMapper.findChildrenByLevel(topicId, (short) 3);
        List<TopicQuestionsResponse.QuestionItem> questions = new ArrayList<>(leaves.size());
        for (ProjectNode q : leaves) {
            questions.add(new TopicQuestionsResponse.QuestionItem(
                    q.id(),
                    q.name(),
                    aggregateService.questionScore(q.id()),
                    aggregateService.questionAttemptCount(q.id())
            ));
        }
        return new TopicQuestionsResponse(topic.id(), topic.name(), questions);
    }

    /**
     * 兜底：DB 中历史脏数据可能是 null；统一返空列表给前端，避免 NPE。
        * project_facts 为 JSONB nullable，统一兜底为空列表。
     */
    private static Object normalizeJsonbList(Object raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        return raw;
    }
}
