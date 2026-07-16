package com.interview.agent.interview.support;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 定长预分块（现网默认）—— 以 turn 为原子、按字符数贪心打包到 {@link InterviewTurns#DEFAULT_CHUNK_SIZE}。
 *
 * <p>纯委托 {@link InterviewTurns#chunkTurns}，零行为变化；作为语义切分的对照支。
 */
@Component
public class FixedSizeTurnChunker implements TurnChunker {

    @Override
    public String strategy() {
        return "fixed";
    }

    @Override
    public List<List<Map<String, Object>>> chunk(List<Map<String, Object>> turns) {
        return InterviewTurns.chunkTurns(turns, InterviewTurns.DEFAULT_CHUNK_SIZE);
    }
}
