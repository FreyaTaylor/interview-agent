package com.interview.agent.interview.support;

import java.util.List;
import java.util.Map;

/**
 * 预分块策略 —— 把 turns 切成<b>不相交</b>的段，供并发 LLM 解析。
 *
 * <p>不变量（所有实现都必须守）：
 * <ul>
 *   <li>turn 是原子单位，绝不跨段拆一个 turn；</li>
 *   <li>各段互不相交、且并起来 == 全部 turns（partition）；</li>
 *   <li>顺序保持（段内 / 段间都按 turn 原顺序）。</li>
 * </ul>
 * 这三条保证了 {@code InterviewParserServiceImpl} 的「不重叠」下游逻辑（turn_ids 裁剪、边界合并）继续成立。
 */
public interface TurnChunker {

    /** 策略名（用于日志 / 配置选择）。 */
    String strategy();

    /** 把 turns 切成多段。 */
    List<List<Map<String, Object>>> chunk(List<Map<String, Object>> turns);
}
