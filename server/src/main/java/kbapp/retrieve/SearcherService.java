package kbapp.retrieve;

import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 多知识库共享 Lucene 读取器：按索引目录懒创建 NRT SearcherManager。 */
@Service
public class SearcherService {

    private final Map<String, SearcherManager> managers = new ConcurrentHashMap<>();

    /** key = 索引目录绝对路径。 */
    public SearcherManager manager(Path indexDir) throws IOException {
        String key = indexDir.toAbsolutePath().normalize().toString();
        return managers.computeIfAbsent(key, (k) -> {
            try {
                Files.createDirectories(indexDir);
                FSDirectory dir = FSDirectory.open(indexDir);
                // 仅在目录还没有索引时初始化空索引；已有索引时不要开 writer（会与构建线程的写锁冲突）
                if (!org.apache.lucene.index.DirectoryReader.indexExists(dir)) {
                    try (IndexWriter w = new IndexWriter(dir, new IndexWriterConfig()
                            .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND))) {
                    }
                }
                return new SearcherManager(dir, null);
            } catch (IOException e) {
                throw new IllegalStateException("初始化 Lucene reader 失败: " + indexDir, e);
            }
        });
    }

    public void refresh(Path indexDir) {
        try {
            manager(indexDir).maybeRefreshBlocking();
        } catch (IOException e) {
            throw new IllegalStateException("刷新 Lucene reader 失败", e);
        }
    }

    @PreDestroy
    public void close() {
        for (SearcherManager m : managers.values()) {
            try { m.close(); } catch (IOException ignored) { }
        }
    }
}
