package com.interview.agent.interview.exp.study.service;

import com.interview.agent.interview.exp.study.dto.ExpContentRequest;
import com.interview.agent.interview.exp.study.dto.ExpQuestionView;
import com.interview.agent.interview.exp.study.dto.ExpStudyTreeNode;

import java.util.List;

/**
 * 「看看面经」学习页服务 —— 面经树查看 + 问题内容懒生成 + 自评掌握度。
 *
 * <p>本期只做「查看学习」，不接答题/评分。内容落独立表 {@code interview_exp_question_detail}。
 */
public interface ExpStudyService {

    /** 面经树（域→问题）供侧栏，问题带自评/频率/内容状态。 */
    List<ExpStudyTreeNode> getTree();

    /**
     * 解析问题内容：fetch（pending 才生、ready 直读）/ regenerate（强制重生）。
     * 生成流程：rubric+推荐答案 → 讲解正文（用采分点约束）→ 落库置 ready。
     */
    ExpQuestionView resolveContent(ExpContentRequest req);

    /**
     * 设置问题自评掌握度（写 tree_node.self_mastery）。
     * @param selfMastery 0-100（越界 clamp）；null 表示清除
     * @return 落库后的值（清除返 null）
     */
    Integer setSelfMastery(long questionId, Integer selfMastery);
}
