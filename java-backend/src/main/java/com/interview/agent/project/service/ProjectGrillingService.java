package com.interview.agent.project.service;

import com.interview.agent.project.dto.DimensionItem;
import com.interview.agent.project.dto.ProfileResponse;
import com.interview.agent.project.dto.ProjectListItem;
import com.interview.agent.project.dto.TopicQuestionsResponse;

import java.util.List;

/**
 * 项目拷打 — 编排服务。
 *
 * <p>S7.1 仅含 4 个读端点；后续 S7.2/S7.3/S7.4 加 attempt-start/turn/finish/detail/history。
 *
 * <ul>
 *   <li>{@link #listProjects} — 列项目（含准备度）</li>
 *   <li>{@link #profileDetail} — 取项目画像（不存在则懒创建空记录）</li>
 *   <li>{@link #dimensionsList} — 列项目的所有 L2 话题（含话题分）</li>
 *   <li>{@link #topicQuestions} — 列某 L2 话题下所有 L3 题目（含题目分）</li>
 * </ul>
 */
public interface ProjectGrillingService {

    List<ProjectListItem> listProjects();

    ProfileResponse profileDetail(long projectId);

    List<DimensionItem> dimensionsList(long projectId);

    TopicQuestionsResponse topicQuestions(long topicId);
}
