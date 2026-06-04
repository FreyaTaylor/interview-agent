package com.interview.agent.learn.dto;

import java.util.List;
import java.util.Map;

/** 单条子话题视图。followups 直接透传 List&lt;Map&gt;，前端按 {q,a,created_at} 渲染。 */
public record SubtopicView(
        Long id,
        String title,
        String bodyMd,
        int importance,
        List<Map<String, Object>> followups,
        int sortOrder,
        String source
) {
}
