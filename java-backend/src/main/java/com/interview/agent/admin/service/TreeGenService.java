package com.interview.agent.admin.service;

import com.interview.agent.admin.dto.CreateTreeFromGenerateReq;
import com.interview.agent.admin.dto.CreateTreeFromTextReq;
import com.interview.agent.admin.dto.TreeGenResp;

/**
 * 知识树生成服务（S5）。
 *
 * <p>一期实现两个入口：
 * <ul>
 *   <li>{@link #createFromText} —— 解析用户粘贴的 Markdown / 纯文本（LLM 仅做结构提取，不改写内容）</li>
 *   <li>{@link #createFromGenerate} —— 给定树名 + 需求描述，LLM 一次性生成完整树（带入用户画像）</li>
 * </ul>
 *
 * <p>后续扩展：from-mm（XML 解析）、optimize（树重构）、merge（合并）、from-image（多模态）。
 */
public interface TreeGenService {

    /** 文本/Markdown → LLM 解析 → 落库。 */
    TreeGenResp createFromText(CreateTreeFromTextReq req);

    /** LLM 一次性生成完整树并落库。 */
    TreeGenResp createFromGenerate(CreateTreeFromGenerateReq req);

    /**
     * 截图 → qwen-vl 视觉解析 → 落库。
     *
     * @param imageBase64 图片 base64（不含 data: 前缀）
     * @param mediaType   图片 MIME（如 image/png）
     */
    TreeGenResp createFromImage(String imageBase64, String mediaType);

    /**
     * FreeMind/幕布 .mm 文件 → 解析 XML → 落库（不走 LLM）。
     *
     * @param content .mm 文件原始字节
     */
    TreeGenResp createFromMm(byte[] content);
}
