package com.interview.agent.interview.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面试原文 → 结构化 turns —— 完全复刻 Python {@code backend/services/interview_turns.py}。
 *
 * <p>每个 turn 是一次发言（speaker 切换处切分），带全局唯一 id 与原文字符偏移。
 * 后续 LLM 引用 id 而不再复制原文片段。</p>
 *
 * <p>复刻要点（不得改动）：
 * <ul>
 *   <li>说话人正则：{@code (?:^|\n)([ \t]*)(面试官|我)\s*[:：]\s*}</li>
 *   <li>说话人前缀不进入 content；content 末尾 rstrip</li>
 *   <li>无说话人标记 → 按双换行切段，speaker 留空</li>
 *   <li>repair 续接字 / 悬挂连词常量逐字一致；短碎片阈值 8</li>
 *   <li>chunk t_len = content 长度 + 8（[tN] speaker: 前缀开销）</li>
 * </ul>
 */
public final class InterviewTurns {

    private InterviewTurns() {
    }

    public static final int DEFAULT_CHUNK_SIZE = 1200;

    /** 行首说话人前缀：面试官 / 我（兼容中英文冒号、前后空白）。 */
    private static final Pattern SPEAKER_RE = Pattern.compile("(?:^|\\n)([ \\t]*)(面试官|我)\\s*[:：]\\s*");
    /** 按双换行切段。 */
    private static final Pattern BLANK_LINE_RE = Pattern.compile("\\n\\s*\\n");

    /** 续接字：基本只能跟在另一字后面，单独开句无意义。命中即与上一 turn 合并。 */
    private static final Set<Character> CONT_START = Set.of('种', '吗', '呢', '啊', '呀', '哈', '嘛', '咯');
    /** 上一 turn 结尾的"悬挂连词"：命中说明被腰斩。 */
    private static final Set<Character> HANG_END = Set.of('那', '和', '与', '或', '及', '就', '且');
    /** 句末标点（含中英文）。 */
    private static final Set<Character> TERMINATORS = Set.of('。', '？', '！', '.', '?', '!', '；', ';');
    /** 修复时允许的"短碎片"长度。 */
    private static final int SHORT_FRAG_LEN = 8;
    /** prev 末尾裁剪字符集（与 Python rstrip 参数一致）。 */
    private static final String PREV_TRIM = "，,。.？?！!；;、 \t\n";

    // ============================================================
    // 切分
    // ============================================================

    /** 把原文切成 turns 列表（带全局 id 和原文字符偏移）。 */
    public static List<Map<String, Object>> splitIntoTurns(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        Matcher m = SPEAKER_RE.matcher(text);
        List<int[]> spans = new ArrayList<>();   // [wholeStart, wholeEnd]
        List<String> speakers = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[]{m.start(), m.end()});
            speakers.add(m.group(2));
        }
        if (spans.isEmpty()) {
            return splitByBlankLines(text);
        }

        List<Map<String, Object>> turns = new ArrayList<>();
        for (int i = 0; i < spans.size(); i++) {
            int contentStart = spans.get(i)[1];
            int contentEnd = (i + 1 < spans.size()) ? spans.get(i + 1)[0] : text.length();
            String content = rstripAll(text.substring(contentStart, contentEnd));
            if (content.isEmpty()) {
                continue;
            }
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("id", turns.size());
            turn.put("speaker", speakers.get(i));
            turn.put("content", content);
            turn.put("char_start", contentStart);
            turn.put("char_end", contentStart + content.length());
            turns.add(turn);
        }
        return turns.isEmpty() ? splitByBlankLines(text) : turns;
    }

    /** fallback：按双换行切段，speaker 留空。 */
    private static List<Map<String, Object>> splitByBlankLines(String text) {
        List<Map<String, Object>> turns = new ArrayList<>();
        int cursor = 0;
        for (String part : BLANK_LINE_RE.split(text)) {
            String stripped = part.strip();
            if (stripped.isEmpty()) {
                cursor += part.length() + 2;
                continue;
            }
            int start = text.indexOf(stripped, cursor);
            if (start < 0) {
                start = cursor;
            }
            int end = start + stripped.length();
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("id", turns.size());
            turn.put("speaker", "");
            turn.put("content", stripped);
            turn.put("char_start", start);
            turn.put("char_end", end);
            turns.add(turn);
            cursor = end;
        }
        if (turns.isEmpty()) {
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("id", 0);
            turn.put("speaker", "");
            turn.put("content", text.strip());
            turn.put("char_start", 0);
            turn.put("char_end", text.length());
            turns.add(turn);
        }
        return turns;
    }

    // ============================================================
    // ASR 切错修复（启发式合并）
    // ============================================================

    /** 合并被 ASR 切错的破碎 turn 到上一个，speaker 沿用上一个；最后重新连续编号 id。 */
    public static List<Map<String, Object>> repairTurns(List<Map<String, Object>> turns) {
        if (turns == null || turns.isEmpty()) {
            return turns;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : turns) {
            Map<String, Object> prev = out.isEmpty() ? null : out.get(out.size() - 1);
            if (shouldMergeToPrev(prev, t)) {
                String mergedContent = rstripAll(str(prev.get("content"))) + lstripAll(str(t.get("content")));
                prev.put("content", mergedContent);
                if (t.containsKey("char_end")) {
                    prev.put("char_end", t.get("char_end"));
                }
            } else {
                out.add(new LinkedHashMap<>(t));
            }
        }
        for (int i = 0; i < out.size(); i++) {
            out.get(i).put("id", i);
        }
        return out;
    }

    /** 判定当前 turn 是否应合并到上一个 turn（保守策略，与 Python 一致）。 */
    private static boolean shouldMergeToPrev(Map<String, Object> prev, Map<String, Object> cur) {
        if (prev == null) {
            return false;
        }
        String curContent = str(cur.get("content")).strip();
        if (curContent.isEmpty()) {
            return false;
        }
        String prevContent = stripTrailingChars(str(prev.get("content")), PREV_TRIM);
        if (prevContent.isEmpty()) {
            return false;
        }
        char prevLast = prevContent.charAt(prevContent.length() - 1);
        char curFirst = curContent.charAt(0);

        // 信号 1：续接字开头 + 当前短
        if (CONT_START.contains(curFirst) && curContent.length() < SHORT_FRAG_LEN) {
            return true;
        }
        // 信号 2：上句悬挂连词结尾 + 当前短 + 无句末标点
        if (HANG_END.contains(prevLast) && curContent.length() < SHORT_FRAG_LEN
                && !containsAny(curContent, TERMINATORS)) {
            return true;
        }
        return false;
    }

    // ============================================================
    // 渲染 / 分块
    // ============================================================

    /** 把 turns 渲染回带 [tN] 标记的对话，供 LLM 解析。 */
    public static String renderTurnsForLlm(List<Map<String, Object>> turns) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> t : turns) {
            String speaker = str(t.get("speaker"));
            String prefix = speaker.isEmpty() ? "" : speaker + ": ";
            lines.add("[t" + t.get("id") + "] " + prefix + str(t.get("content")));
        }
        return String.join("\n", lines);
    }

    /** 把 turns 切成多段，每段累计字符不超过 chunkSize；单 turn 超长时自成一段。 */
    public static List<List<Map<String, Object>>> chunkTurns(List<Map<String, Object>> turns, int chunkSize) {
        List<List<Map<String, Object>>> chunks = new ArrayList<>();
        if (turns == null || turns.isEmpty()) {
            return chunks;
        }
        List<Map<String, Object>> current = new ArrayList<>();
        int currentLen = 0;
        for (Map<String, Object> t : turns) {
            int tLen = str(t.get("content")).length() + 8;  // [tN] speaker: 前缀大致开销
            if (current.isEmpty() && tLen > chunkSize) {
                chunks.add(new ArrayList<>(List.of(t)));
                continue;
            }
            if (currentLen + tLen > chunkSize && !current.isEmpty()) {
                chunks.add(current);
                current = new ArrayList<>();
                current.add(t);
                currentLen = tLen;
            } else {
                current.add(t);
                currentLen += tLen;
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    // ============================================================
    // 小工具
    // ============================================================

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /** 去掉尾部所有空白（等价 Python str.rstrip()）。 */
    private static String rstripAll(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /** 去掉头部所有空白（等价 Python str.lstrip()）。 */
    private static String lstripAll(String s) {
        int start = 0;
        while (start < s.length() && Character.isWhitespace(s.charAt(start))) {
            start++;
        }
        return s.substring(start);
    }

    /** 去掉尾部指定字符集（等价 Python str.rstrip(chars)）。 */
    private static String stripTrailingChars(String s, String chars) {
        int end = s.length();
        while (end > 0 && chars.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(0, end);
    }

    private static boolean containsAny(String s, Set<Character> charset) {
        for (int i = 0; i < s.length(); i++) {
            if (charset.contains(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
