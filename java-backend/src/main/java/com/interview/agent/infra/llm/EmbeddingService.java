package com.interview.agent.infra.llm;

import com.interview.agent.common.BizException;
import com.interview.agent.infra.db.PgVector;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.output.Response;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Embedding 服务 — 基于 LangChain4j Community DashScope 模块。
 *
 * 选择 LangChain4j 而非 Spring AI 是因为：DashScope 在 Spring AI 中尚无第一方 starter，
 * 而 LangChain4j community 模块对 {@code text-embedding-v3} 支持完整。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingProperties props;
    private QwenEmbeddingModel model;

    public EmbeddingService(EmbeddingProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        if (props.dashscopeApiKey() == null || props.dashscopeApiKey().isBlank()) {
            log.warn("[Embedding] DASHSCOPE_API_KEY 未配置，embed() 将抛异常");
            return;
        }
        this.model = QwenEmbeddingModel.builder()
                .apiKey(props.dashscopeApiKey())
                .modelName(props.model())
                .build();
        log.info("[Embedding] 初始化完成 model={} dim={}", props.model(), props.dimension());
    }

    /** 单文本 -> 向量。 */
    public float[] embed(String text) {
        if (model == null) {
            throw new BizException(50000, "Embedding 未初始化：请配置 DASHSCOPE_API_KEY");
        }
        if (text == null || text.isBlank()) {
            throw new BizException(40001, "embed 文本不能为空");
        }
        Response<Embedding> resp = model.embed(text);
        return resp.content().vector();
    }

    /** 单文本 -> pgvector 字面量（直接拼到 ?::vector 占位）。 */
    public String embedToLiteral(String text) {
        return PgVector.toLiteral(embed(text));
    }

    public int dimension() {
        return props.dimension();
    }
}
