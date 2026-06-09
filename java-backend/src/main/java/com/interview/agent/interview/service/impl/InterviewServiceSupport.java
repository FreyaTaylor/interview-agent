package com.interview.agent.interview.service.impl;

import com.interview.agent.common.BizException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Interview 复盘服务公共工具（纯函数，无状态、无 IO）。
 *
 * <p>拆分背景：原 {@code InterviewCrudServiceImpl} 既做解析编排又做历史 CRUD，工具方法混在一起。
 * 按职责拆为 {@code InterviewParseServiceImpl}（前/后/全）与 {@code InterviewBasicServiceImpl}（其余）后，
 * 两边都需要的「turns/groups 归一化、raw_text 拼接、JSON 取值、hash」等<b>纯函数</b>集中到这里，
 * 避免重复实现导致两边逻辑漂移。
 *
 * <p>所有方法均为 {@code static} 且不持有任何依赖；需要 mapper 的逻辑（如删除校验）留在各自 impl 内。
 */
final class InterviewServiceSupport {

    private InterviewServiceSupport() {
    }

    // ============================================================
    // turns/groups 归一化（finalize 落库前的必经一步）
    // ============================================================

    /**
     * 归一化前端校对后的 groups：补默认值、把统一字段 {@code tag} 映射回下游兼容字段、
     * 并依据 turns 回填 {@code original_dialogue / questions / user_answer}。
     *
     * <p>忠实对齐 Python {@code interview_crud.finalize_interview} 的预处理段：
     * <ul>
     *   <li>{@code id / type / tag} 缺省补值（type 默认 other、tag 默认「未分类」）</li>
     *   <li>knowledge 且 {@code knowledge_point} 为空 → 取 tag（再空兜底「未命名」），否则节点匹配拿不到 kp</li>
     *   <li>project 且 {@code topic} 为空 → 取 tag（前端展示 + 评分都依赖）</li>
     *   <li>{@code turn_ids} 为空 → 默认关联全部 turn，避免评分端丢上下文</li>
     *   <li>{@code original_dialogue / questions / user_answer} 缺失时按 turn 归属重建</li>
     * </ul>
     *
     * @param turns  source of truth 的对话轮次（含 id/speaker/content）
     * @param groups 前端校对后的分组（可能缺字段）
     * @return 补全后的 groups 副本（不修改入参）
     */
    static List<Map<String, Object>> normalizeGroups(List<Map<String, Object>> turns,
                                                     List<Map<String, Object>> groups) {
        Map<Integer, Map<String, Object>> turnById = new LinkedHashMap<>();
        for (Map<String, Object> t : turns) {
            int id = asInt(t.get("id"));
            if (id > 0) {
                turnById.put(id, t);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>(groups.size());
        int groupSeq = 1;
        for (Map<String, Object> g : groups) {
            Map<String, Object> copy = new LinkedHashMap<>(g);
            copy.putIfAbsent("id", groupSeq++);
            copy.putIfAbsent("type", "other");
            copy.putIfAbsent("tag", "未分类");

            // 把统一字段 tag 映射回下游兼容字段（对齐 Python interview_crud）：
            // - knowledge：knowledge_point 为空时取 tag（兜底「未命名」）—— 否则节点匹配/占位叶子拿不到 kp
            // - project  ：topic 为空时取 tag（前端展示 + 评分都依赖）
            String groupType = asString(copy.get("type"));
            if ("knowledge".equals(groupType) && isBlank(copy.get("knowledge_point"))) {
                String tag = asString(copy.get("tag"));
                copy.put("knowledge_point", tag.isBlank() ? "未命名" : tag);
            }
            if ("project".equals(groupType) && isBlank(copy.get("topic"))) {
                copy.put("topic", asString(copy.get("tag")));
            }

            List<Integer> turnIds = asIntList(copy.get("turn_ids"));
            if (turnIds.isEmpty()) {
                // 如果前端未传 turn_ids，默认关联全部 turn，避免评分端拿不到上下文。
                turnIds = new ArrayList<>(turnById.keySet());
                copy.put("turn_ids", turnIds);
            }

            if (isBlank(copy.get("original_dialogue"))) {
                copy.put("original_dialogue", rebuildDialogue(turnIds, turnById));
            }
            if (!(copy.get("questions") instanceof List<?>)) {
                copy.put("questions", collectQuestions(turnIds, turnById));
            }
            if (isBlank(copy.get("user_answer"))) {
                copy.put("user_answer", collectAnswer(turnIds, turnById));
            }
            out.add(copy);
        }
        return out;
    }

    /** 按 turn 归属顺序重拼 {@code speaker：content} 形式的对话片段。 */
    static String rebuildDialogue(List<Integer> turnIds, Map<Integer, Map<String, Object>> turnById) {
        StringBuilder sb = new StringBuilder();
        for (Integer tid : turnIds) {
            Map<String, Object> t = turnById.get(tid);
            if (t == null) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append(asString(t.get("speaker"))).append("：").append(asString(t.get("content")));
        }
        return sb.toString();
    }

    /** 从 turn 抽取「面试官」侧问题列表（speaker != 我）。 */
    static List<String> collectQuestions(List<Integer> turnIds, Map<Integer, Map<String, Object>> turnById) {
        List<String> out = new ArrayList<>();
        for (Integer tid : turnIds) {
            Map<String, Object> t = turnById.get(tid);
            if (t == null) {
                continue;
            }
            if (!"我".equals(asString(t.get("speaker")))) {
                out.add(asString(t.get("content")));
            }
        }
        return out;
    }

    /** 从 turn 抽取「我」侧回答并按行拼接。 */
    static String collectAnswer(List<Integer> turnIds, Map<Integer, Map<String, Object>> turnById) {
        List<String> out = new ArrayList<>();
        for (Integer tid : turnIds) {
            Map<String, Object> t = turnById.get(tid);
            if (t == null) {
                continue;
            }
            if ("我".equals(asString(t.get("speaker")))) {
                out.add(asString(t.get("content")));
            }
        }
        return String.join("\n", out);
    }

    // ============================================================
    // 统计 / 文本 / 取值
    // ============================================================

    /** 统计各类型 group 数量（knowledge/algorithm/hr/project/other），用于详情页与提交返回。 */
    static Map<String, Integer> buildStats(List<Map<String, Object>> groups) {
        Map<String, Integer> stats = new LinkedHashMap<>();
        stats.put("knowledge", 0);
        stats.put("algorithm", 0);
        stats.put("hr", 0);
        stats.put("project", 0);
        stats.put("other", 0);
        for (Map<String, Object> g : groups) {
            String type = asString(g.get("type"));
            stats.put(type, stats.getOrDefault(type, 0) + 1);
        }
        return stats;
    }

    /** 由 turns 拼出归档用 raw_text（{@code speaker：content} 逐行），同时是 text_hash 的输入。 */
    static String buildRawText(List<Map<String, Object>> turns) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> t : turns) {
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            String speaker = asString(t.get("speaker"));
            if (!speaker.isBlank()) {
                sb.append(speaker).append("：");
            }
            sb.append(asString(t.get("content")));
        }
        return sb.toString();
    }

    /** 取 group 首个问题文本；无 questions 时退化为 original_dialogue。 */
    static String firstQuestion(Map<String, Object> group) {
        Object q = group.get("questions");
        if (q instanceof List<?> list && !list.isEmpty() && list.get(0) != null) {
            return list.get(0).toString();
        }
        return asString(group.get("original_dialogue"));
    }

    /** group 的 tag（空兜底「未分类」），子表落库用。 */
    static String defaultTag(Map<String, Object> group) {
        String tag = asString(group.get("tag"));
        return tag.isBlank() ? "未分类" : tag;
    }

    /** 判断 parsed_questions 是否已含 groups（区分草稿态 vs 已解析态）。 */
    static boolean hasParsedGroups(Object parsedQuestions) {
        Map<String, Object> parsed = asMap(parsedQuestions);
        List<Map<String, Object>> groups = asMapList(parsed.get("groups"));
        return !groups.isEmpty();
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    static boolean isBlank(Object o) {
        return o == null || o.toString().isBlank();
    }

    static String asString(Object o) {
        return o == null ? "" : o.toString();
    }

    static int asInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }

    static Long asLongOrNull(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> asMapList(Object o) {
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    static List<Integer> asIntList(Object o) {
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>(list.size());
        for (Object item : list) {
            int v = asInt(item);
            if (v > 0) {
                out.add(v);
            }
        }
        return out;
    }

    /** 计算文本 SHA-256 十六进制串（text_hash 去重键）。 */
    static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BizException(50000, "计算文本 hash 失败", e);
        }
    }
}
