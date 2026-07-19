package com.interview.agent.admin.service;

import com.interview.agent.admin.dto.BatchSortReq;
import com.interview.agent.admin.dto.CreateProjectNodeReq;
import com.interview.agent.admin.dto.DeleteNodeReq;
import com.interview.agent.admin.dto.ProjectFromTextReq;
import com.interview.agent.admin.dto.ProjectFromTextResp;
import com.interview.agent.admin.dto.ProjectNodeView;
import com.interview.agent.admin.dto.UpdateProjectNodeReq;

import java.util.List;
import java.util.Map;

/**
 * 项目树 Admin 服务接口（S6）—— 节点 CRUD + 文本解析建项目。
 *
 * <p>分层约定：与 KnowledgeAdminService 平行；6 个 REST 端点。实现见 impl.ProjectAdminServiceImpl。
 *
 * <p>全部方法只接 DTO，不接裸 long id（java-style §3.3 “全 POST + body 传参”）。
 */
public interface ProjectAdminService {

    /** 列出全部项目节点（前端 OutlinerEditor 一次拉树）。 */
    List<ProjectNodeView> listAll();

    /** 创建节点；返回 {id, name, level}。level 由父节点 level+1 推导（项目树硬规则 ≥3 → leaf）。 */
    Map<String, Object> create(CreateProjectNodeReq req);

    /** 部分更新；movingParent=true 时同时改 parent/level/nodeType 并平移子树 level。id 从 req.id() 读。 */
    Map<String, Object> update(UpdateProjectNodeReq req);

    /** 批量改 sort_order（与 S1 BatchSortReq 共用）。 */
    Map<String, Object> batchSort(BatchSortReq req);

    /** 递归删除节点 + 子孙；删前清 interview_project_question 引用；返回 {deleted: req.id()}。 */
    Map<String, Object> delete(DeleteNodeReq req);

    /** 只删该节点的全部子孙、保留节点自身（项目树 node_type 由 level 决定，不变）；返回 {id, deleted:子孙数}。 */
    Map<String, Object> deleteChildren(DeleteNodeReq req);

    /**
     * S6 from-text：把项目描述拆为 项目→话题→问题 三层树并落库；同步建 project 元数据行。
     * @return rootId / projectId / name / leafCount
     */
    ProjectFromTextResp createFromText(ProjectFromTextReq req);
}
