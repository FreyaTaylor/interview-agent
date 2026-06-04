package com.interview.agent.learn.service;

import com.interview.agent.knowledge.entity.KnowledgeNode;
import com.interview.agent.knowledge.mapper.KnowledgeNodeMapper;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Learn 模块通用 helper（轻量纯只读）。
 *
 * <p>放共用的、跨多个 Service 的小函数。当前只有 categoryPath；后续 Learn 内有新公共小工具继续加进来。
 */
@Service
public class LearnHelper {

    private static final String CATEGORY_PATH_SEP = " → ";

    private final KnowledgeNodeMapper nodeMapper;

    public LearnHelper(KnowledgeNodeMapper nodeMapper) {
        this.nodeMapper = nodeMapper;
    }

    /**
     * 算知识点的"分类路径"——从根节点到该节点的名称链，如 {@code "redis → 数据结构 → Set"}。
     *
     * <p>用途：喂给 LLM prompt 做领域约束，避免同名概念误判（Redis Set vs JS Set）。
     * 原型：{@code backend/services/learn.py::_get_category_path}。
     * <ol>
     *   <li>Step 1: 一次性把全部节点拉进内存建索引（树规模有限，O(n) 比多次回表更省 IO）</li>
     *   <li>Step 2: 沿 parent_id 上爬，名字 addFirst 自然得到根→叶顺序</li>
     * </ol>
     * @return 形如 {@code "redis → 数据结构 → Set"}；节点不存在返空串
     */
    public String categoryPath(long kpId) {
        // Step 1
        List<KnowledgeNode> all = nodeMapper.findAllOrdered();
        Map<Long, KnowledgeNode> byId = new HashMap<>(all.size() * 2);
        for (KnowledgeNode n : all) {
            byId.put(n.id(), n);
        }
        // Step 2
        LinkedList<String> path = new LinkedList<>();
        KnowledgeNode cur = byId.get(kpId);
        while (cur != null) {
            path.addFirst(cur.name());
            cur = cur.parentId() == null ? null : byId.get(cur.parentId());
        }
        return String.join(CATEGORY_PATH_SEP, path);
    }
}
