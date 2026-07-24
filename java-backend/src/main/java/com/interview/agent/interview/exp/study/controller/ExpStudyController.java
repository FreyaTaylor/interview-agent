package com.interview.agent.interview.exp.study.controller;

import com.interview.agent.interview.exp.study.dto.ExpContentRequest;
import com.interview.agent.interview.exp.study.dto.ExpQuestionView;
import com.interview.agent.interview.exp.study.dto.ExpSelfMasteryRequest;
import com.interview.agent.interview.exp.study.dto.ExpStudyTreeNode;
import com.interview.agent.interview.exp.study.service.ExpStudyService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 「看看面经」学习页 API（用户侧查看）。
 *
 * <p>路径前缀：/api/interview-exp（区别于 admin 的 /api/admin/interview-exp-*）。
 * 全 POST + body。
 * <ul>
 *   <li>POST /tree — 面经树（域→问题，含自评/频率/内容状态）</li>
 *   <li>POST /content — 问题内容懒生成/读取（讲解+rubric+推荐答案）</li>
 *   <li>POST /self-mastery — 设置问题自评掌握度</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/interview-exp")
public class ExpStudyController {

    private final ExpStudyService service;

    public ExpStudyController(ExpStudyService service) {
        this.service = service;
    }

    @PostMapping("/tree")
    public List<ExpStudyTreeNode> tree() {
        return service.getTree();
    }

    @PostMapping("/content")
    public ExpQuestionView content(@RequestBody ExpContentRequest req) {
        return service.resolveContent(req);
    }

    @PostMapping("/self-mastery")
    public Integer selfMastery(@RequestBody ExpSelfMasteryRequest req) {
        return service.setSelfMastery(req.questionId(), req.selfMastery());
    }
}
