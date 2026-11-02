package kbapp.llm;

import java.util.List;

/** Embedding 客户端：单条与批量。 */
public interface EmbeddingClient {

    float[] embed(String text);

    default List<float[]> embedAll(List<String> texts) {
        List<float[]> out = new java.util.ArrayList<>(texts.size());
        for (String t : texts) out.add(embed(t));
        return out;
    }
}
