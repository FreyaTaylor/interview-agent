package com.interview.agent.infra.db;

/**
 * pgvector 字面量工具。
 *
 * pgvector 不接受 PreparedStatement 直接绑定 float[]，需要拼成 '[v1,v2,...]' 文本，
 * 然后在 SQL 里用 {@code ?::vector} 强转：
 *
 * <pre>
 *   String literal = PgVector.toLiteral(embedding);
 *   jdbcClient.sql("UPDATE tree_node SET embedding = ?::vector WHERE id = ?")
 *             .params(literal, id)
 *             .update();
 * </pre>
 */
public final class PgVector {

    private PgVector() {}

    /** float[] -> '[0.123,-0.456,...]'，不带空格，保留全精度。 */
    public static String toLiteral(float[] vec) {
        if (vec == null || vec.length == 0) {
            throw new IllegalArgumentException("embedding 不能为空");
        }
        StringBuilder sb = new StringBuilder(vec.length * 12);
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
