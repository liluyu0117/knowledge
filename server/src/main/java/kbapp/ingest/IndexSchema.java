package kbapp.ingest;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexableField;

/** Lucene 字段 schema（设计文档 5.2 字段表）。 */
public final class IndexSchema {

    public static final String DOC_PATH = "docPath";
    public static final String DOC_TITLE = "docTitle";
    public static final String EXT = "ext";
    public static final String KIND = "kind";
    public static final String FOLDER = "folder";
    public static final String SEQ = "seq";
    public static final String HEADING = "heading";
    public static final String BODY = "body";
    public static final String HEADING_TEXT = "headingText";
    public static final String CHUNK_HASH = "chunkHash";
    public static final String FILE_HASH = "fileHash";
    public static final String SIG = "sig";
    public static final String VECTOR = "vector";
    /** 向量本体（float32 二进制）。Lucene 的 KNN 字段不存储向量，必须单独存一份才能复用旧向量。 */
    public static final String VECTOR_BIN = "vectorBin";
    /** 向量维度（0/缺省 = 该块无向量）。用于低成本判断向量覆盖，避免为统计加载整个向量。 */
    public static final String VEC_DIM = "vecDim";

    private IndexSchema() {}

    public static void fill(Document d, String docPath, String docTitle, String ext, String kind,
                            String folder, int seq, String heading, String body,
                            String chunkHash, String fileHash, String sig, float[] vector) {
        d.add(new StringField(DOC_PATH, docPath, Field.Store.YES));
        d.add(new TextField(DOC_TITLE, nullToEmpty(docTitle), Field.Store.YES));
        d.add(new StoredField(EXT, nullToEmpty(ext)));
        d.add(new StoredField(KIND, nullToEmpty(kind)));
        d.add(new StringField(FOLDER, nullToEmpty(folder), Field.Store.YES));
        d.add(new StoredField(SEQ, seq));
        d.add(new StoredField(HEADING, nullToEmpty(heading)));
        d.add(new TextField(BODY, nullToEmpty(body), Field.Store.YES));
        d.add(new TextField(HEADING_TEXT, nullToEmpty(heading) + " " + nullToEmpty(docTitle), Field.Store.NO));
        d.add(new StoredField(CHUNK_HASH, nullToEmpty(chunkHash)));
        d.add(new StoredField(FILE_HASH, nullToEmpty(fileHash)));
        d.add(new StoredField(SIG, nullToEmpty(sig)));
        if (vector != null) {
            d.add(new KnnFloatVectorField(VECTOR, vector));
            d.add(new StoredField(VECTOR_BIN, toBytes(vector)));
            d.add(new StoredField(VEC_DIM, vector.length));
        } else {
            d.add(new StoredField(VEC_DIM, 0));
        }
    }

    public static String getString(Document d, String field) {
        String v = d.get(field);
        return v == null ? "" : v;
    }

    public static int getInt(Document d, String field) {
        var f = d.getField(field);
        Number n = f == null ? null : f.numericValue();
        return n == null ? 0 : n.intValue();
    }

    public static float[] getVector(Document d) {
        IndexableField bin = d.getField(VECTOR_BIN);
        if (bin != null && bin.binaryValue() != null) {
            var br = bin.binaryValue();
            byte[] raw = new byte[br.length];
            System.arraycopy(br.bytes, br.offset, raw, 0, br.length);
            var bb = java.nio.ByteBuffer.wrap(raw)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            float[] v = new float[bb.remaining()];
            bb.get(v);
            return v;
        }
        // 兼容：历史索引若 KNN 字段可读（未存储时返回 null）
        var f = d.getField(VECTOR);
        return f instanceof KnnFloatVectorField knn ? knn.vectorValue() : null;
    }

    private static byte[] toBytes(float[] v) {
        var bb = java.nio.ByteBuffer.allocate(v.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.asFloatBuffer().put(v);
        return bb.array();
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}
