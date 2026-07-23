package com.interview.agent.interview.exp.service;

import com.interview.agent.admin.dto.BatchSortReq;
import com.interview.agent.admin.dto.DeleteNodeReq;
import com.interview.agent.interview.exp.dto.CreateInterviewExpNodeReq;
import com.interview.agent.interview.exp.dto.InterviewExpNodeView;
import com.interview.agent.interview.exp.dto.UpdateInterviewExpNodeReq;

import java.util.List;
import java.util.Map;

/**
 * 面经树 Admin 服务 —— 节点 CRUD（供管理页 OutlinerEditor）。
 *
 * <p>与知识树 admin 平行，区别：两层 domain/question，无 interview_weight；list 带出现频率。
 */
public interface InterviewExpAdminService {

    List<InterviewExpNodeView> listAll();

    Map<String, Object> create(CreateInterviewExpNodeReq req);

    Map<String, Object> update(UpdateInterviewExpNodeReq req);

    Map<String, Object> batchSort(BatchSortReq req);

    Map<String, Object> delete(DeleteNodeReq req);

    Map<String, Object> deleteChildren(DeleteNodeReq req);
}
