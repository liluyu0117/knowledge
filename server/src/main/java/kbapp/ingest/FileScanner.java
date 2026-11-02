package kbapp.ingest;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** 按设计文档 5.1 规则扫描数据源目录，返回稳定排序的文件绝对路径列表。 */
@Component
public class FileScanner {

    public List<Path> scan(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("数据源目录不存在: " + root);
        }
        List<Path> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> !isUnderExcludedDir(root, p))
                  .filter(p -> ScanFilter.accept(p.getFileName().toString()))
                  .filter(p -> !ScanFilter.isEmptyFile(p))
                  .forEach(out::add);
        }
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    private boolean isUnderExcludedDir(Path root, Path file) {
        Path rel = root.relativize(file);
        for (int i = 0; i < rel.getNameCount() - 1; i++) {
            if (ScanFilter.isExcludedDir(rel.getName(i).toString())) return true;
        }
        return false;
    }

    public static Path normalizeRoot(String sourceRoot) {
        Path p = Paths.get(sourceRoot == null ? "" : sourceRoot.trim()).toAbsolutePath().normalize();
        if (!java.nio.file.Files.isDirectory(p)) {
            throw new IllegalArgumentException("数据源目录不存在: " + p);
        }
        return p;
    }
}
