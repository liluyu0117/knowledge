package kbapp.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 应用配置，对应 .kb/app.json；字段默认值即设计文档所列。 */
public class AppConfig {

    private String sourceRoot = "";
    private List<Provider> providers = new ArrayList<>();
    private String activeChat = "";
    private String activeEmbedding = "";
    private Rag rag = new Rag();
    private IndexCfg index = new IndexCfg();
    private Guard guard = new Guard();
    private Mcp mcp = new Mcp();
    private Rerank rerank = new Rerank();
    private Api api = new Api();
    private Sync sync = new Sync();

    public static class Provider {
        private String name = "";
        private String baseUrl = "";
        private String apiKey = "";
        private String chatModel = "";
        private String embeddingModel = "";
        private int embeddingDim = 1024;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getChatModel() { return chatModel; }
        public void setChatModel(String chatModel) { this.chatModel = chatModel; }
        public String getEmbeddingModel() { return embeddingModel; }
        public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
        public int getEmbeddingDim() { return embeddingDim; }
        public void setEmbeddingDim(int embeddingDim) { this.embeddingDim = embeddingDim; }

        public Provider copy() {
            Provider p = new Provider();
            p.name = name; p.baseUrl = baseUrl; p.apiKey = apiKey;
            p.chatModel = chatModel; p.embeddingModel = embeddingModel; p.embeddingDim = embeddingDim;
            return p;
        }
    }

    public static class Rag {
        private int topK = 8;
        private int candidate = 20;
        private int rrfK = 60;
        private int maxPerDoc = 3;
        private String analyzer = "smart"; // smart | bigram
        /** rerank 分数阈值（0~1）：低于它的块直接丢弃。 */
        private double minRerankScore = 0.05;
        /** 未启用 rerank 时的相对阈值：低于「最高分 × 该比例」的块丢弃。 */
        private double minRelScore = 0.25;

        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
        public int getCandidate() { return candidate; }
        public void setCandidate(int candidate) { this.candidate = candidate; }
        public int getRrfK() { return rrfK; }
        public void setRrfK(int rrfK) { this.rrfK = rrfK; }
        public int getMaxPerDoc() { return maxPerDoc; }
        public void setMaxPerDoc(int maxPerDoc) { this.maxPerDoc = maxPerDoc; }
        public String getAnalyzer() { return analyzer; }
        public void setAnalyzer(String analyzer) { this.analyzer = analyzer; }
        public double getMinRerankScore() { return minRerankScore; }
        public void setMinRerankScore(double minRerankScore) { this.minRerankScore = minRerankScore; }
        public double getMinRelScore() { return minRelScore; }
        public void setMinRelScore(double minRelScore) { this.minRelScore = minRelScore; }
    }

    /** 对外 REST 接口（/api/v1/*），用 X-API-Key 保护。 */
    public static class Api {
        private boolean enabled = false;
        private String key = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
    }

    public static class IndexCfg {
        private int chunkChars = 500;
        /** 自动增量重扫间隔（分钟），0 = 关闭。 */
        private int autoMinutes = 0;
        private int chunkOverlapChars = 50;
        private int minChunkChars = 20;
        /** 分块模式：fixed = 滑动窗口（默认）；parent-child = 子块检索 + 父块上下文。切换后下次构建自动全量重建。 */
        private String chunkMode = "fixed";
        /** parent-child 模式：子块目标字符数（检索粒度）。 */
        private int childChars = 380;
        /** parent-child 模式：父块上限字符数（回读上下文的完整度）。 */
        private int parentChars = 2000;

        public int getChunkChars() { return chunkChars; }
        public void setChunkChars(int chunkChars) { this.chunkChars = chunkChars; }
        public int getChunkOverlapChars() { return chunkOverlapChars; }
        public void setChunkOverlapChars(int chunkOverlapChars) { this.chunkOverlapChars = chunkOverlapChars; }
        public int getMinChunkChars() { return minChunkChars; }
        public void setMinChunkChars(int minChunkChars) { this.minChunkChars = minChunkChars; }
        public int getAutoMinutes() { return autoMinutes; }
        public void setAutoMinutes(int autoMinutes) { this.autoMinutes = autoMinutes; }
        public String getChunkMode() { return chunkMode == null || chunkMode.isBlank() ? "fixed" : chunkMode; }
        public void setChunkMode(String chunkMode) { this.chunkMode = chunkMode; }
        public int getChildChars() { return childChars; }
        public void setChildChars(int childChars) { this.childChars = childChars; }
        public int getParentChars() { return parentChars; }
        public void setParentChars(int parentChars) { this.parentChars = parentChars; }
    }

    public static class Guard {
        private int embedThreshold = 200;

        public int getEmbedThreshold() { return embedThreshold; }
        public void setEmbedThreshold(int embedThreshold) { this.embedThreshold = embedThreshold; }
    }

    /** 重排序模型：Cohere 风格的 POST {baseUrl}/rerank。 */
    public static class Rerank {
        private boolean enabled = false;
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "";
        /** 送重排的候选块数（RRF 之后取前 N 条送模型）。 */
        private int candidate = 20;
        /** 重排后保留条数，0 = 用 rag.topK。 */
        private int topN = 0;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getCandidate() { return candidate; }
        public void setCandidate(int candidate) { this.candidate = candidate; }
        public int getTopN() { return topN; }
        public void setTopN(int topN) { this.topN = topN; }

        public Rerank copy() {
            Rerank r = new Rerank();
            r.enabled = enabled; r.baseUrl = baseUrl; r.apiKey = apiKey;
            r.model = model; r.candidate = candidate; r.topN = topN;
            return r;
        }
    }

    public static class Mcp {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Sync {
        private String server = "https://dav.jianguoyun.com/dav/";
        private String user = "";
        private String appPassword = "";
        private boolean autoDaily = false;

        public String getServer() { return server; }
        public void setServer(String server) { this.server = server; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getAppPassword() { return appPassword; }
        public void setAppPassword(String appPassword) { this.appPassword = appPassword; }
        public boolean isAutoDaily() { return autoDaily; }
        public void setAutoDaily(boolean autoDaily) { this.autoDaily = autoDaily; }
    }

    /** 默认预置两家厂商骨架，首次启动即可在设置页填 key。 */
    public static AppConfig defaults() {
        AppConfig c = new AppConfig();
        c.sourceRoot = "";
        Provider ds = new Provider();
        ds.name = "DeepSeek";
        ds.baseUrl = "https://api.deepseek.com/v1";
        ds.chatModel = "deepseek-chat";
        Provider zhipu = new Provider();
        zhipu.name = "智谱";
        zhipu.baseUrl = "https://open.bigmodel.cn/api/paas/v4";
        zhipu.chatModel = "glm-4-air";
        zhipu.embeddingModel = "embedding-3";
        zhipu.embeddingDim = 1024;
        c.providers.add(ds);
        c.providers.add(zhipu);
        c.activeChat = "DeepSeek";
        c.activeEmbedding = "智谱";
        return c;
    }

    public String getSourceRoot() { return sourceRoot; }
    public void setSourceRoot(String sourceRoot) { this.sourceRoot = sourceRoot; }
    public List<Provider> getProviders() { return providers; }
    public void setProviders(List<Provider> providers) { this.providers = providers; }
    public String getActiveChat() { return activeChat; }
    public void setActiveChat(String activeChat) { this.activeChat = activeChat; }
    public String getActiveEmbedding() { return activeEmbedding; }
    public void setActiveEmbedding(String activeEmbedding) { this.activeEmbedding = activeEmbedding; }
    public Rag getRag() { return rag; }
    public void setRag(Rag rag) { this.rag = rag; }
    public IndexCfg getIndex() { return index; }
    public void setIndex(IndexCfg index) { this.index = index; }
    public Guard getGuard() { return guard; }
    public void setGuard(Guard guard) { this.guard = guard; }
    public Api getApi() { return api; }
    public void setApi(Api api) { this.api = api; }
    public Rerank getRerank() { return rerank; }
    public void setRerank(Rerank rerank) { this.rerank = rerank; }
    public Mcp getMcp() { return mcp; }
    public void setMcp(Mcp mcp) { this.mcp = mcp; }
    public Sync getSync() { return sync; }
    public void setSync(Sync sync) { this.sync = sync; }

    public AppConfig copy() {
        AppConfig c = new AppConfig();
        c.sourceRoot = sourceRoot;
        List<Provider> ps = new ArrayList<>();
        for (Provider p : providers) ps.add(p.copy());
        c.providers = ps;
        c.activeChat = activeChat;
        c.activeEmbedding = activeEmbedding;
        c.rag = rag; c.index = index; c.guard = guard; c.mcp = mcp;
        c.rerank = rerank.copy();
        c.api = api;
        return c;
    }

    public Provider providerByName(String name) {
        if (name == null) return null;
        for (Provider p : providers) if (name.equals(p.getName())) return p;
        return null;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sourceRoot", sourceRoot);
        m.put("providers", providers);
        m.put("activeChat", activeChat);
        m.put("activeEmbedding", activeEmbedding);
        m.put("rag", rag);
        m.put("index", index);
        m.put("guard", guard);
        m.put("mcp", mcp);
        m.put("rerank", rerank);
        m.put("api", api);
        m.put("sync", sync);
        return m;
    }
}
