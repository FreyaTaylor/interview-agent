package com.interview.agent.admin.service;

import com.interview.agent.admin.dto.BatchSortReq;
import com.interview.agent.admin.dto.CreateKnowledgeNodeReq;
import com.interview.agent.admin.dto.KnowledgeNodeView;
import com.interview.agent.admin.dto.UpdateKnowledgeNodeReq;

import java.util.List;
import java.util.Map;

/**
 * 知识树 Admin 服务接口 —— 节点 CRUD（S1）。
 *
 * <p>分层约定：本接口对应 5 个 REST 端点，实现见 {@code impl.KnowledgeAdminServiceImpl}。
 * 接口只声明能力，方法语义 / 业务规则 / 异常码统一写在实现类里，避免 Javadoc 双份维护。
 */
public interface KnowledgeAdminService {

    /** 列出全部节点（一次拉整棵树，由前端按 parent 组装）。 */
    List<KnowledgeNodeView> listAll();

    /** 创建节点；返回 {id, name, level}。 */
    Map<String, Object> create(CreateKnowledgeNodeReq req);

    /** 部分更新节点；movingParent=true 时同时改 parent / level / nodeType。 */
    Map<String, Object> update(long id, UpdateKnowledgeNodeReq req);

    /** 批量改 sort_order（前端任何兄弟重排操作都会调）。 */
    Map<String, Object> batchSort(BatchSortReq req);

    /** 递归删除节点及全部子孙；返回 {deleted: 传入根 id}。 */
    Map<String, Object> delete(long id);
}
