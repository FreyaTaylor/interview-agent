package com.interview.agent.interview.exp.service;

import com.interview.agent.interview.exp.dto.InterviewExpParseResult;

/**
 * 面经解析服务 —— 文本/图片 → 规整问题清单（按知识域分类 + rewrite + 语义去重 + 计频）。
 */
public interface InterviewExpParseService {

    /**
     * 解析一篇面经纯文本并落库。
     *
     * <p>流程：来源两级去重（hash 精确 + 整篇 embedding 模糊）→ LLM 抽题+rewrite+判域 →
     * 逐题：域名匹配/新建、域内问题语义去重（命中计频 / 未命中新建）、写来源关联。
     *
     * @param text 面经原文（可多篇拼接）
     * @return 解析结果摘要（含是否整篇去重、新增/命中数）
     */
    InterviewExpParseResult parseFromText(String text);

    /**
     * 解析一张面经截图并落库：先 OCR 转录为文本，再走 {@link #parseFromText}。
     *
     * @param imageBase64 图片 base64（不含 data: 前缀）
     * @param mediaType   图片 MIME（如 image/png）
     * @return 解析结果摘要
     */
    InterviewExpParseResult parseFromImage(String imageBase64, String mediaType);
}
