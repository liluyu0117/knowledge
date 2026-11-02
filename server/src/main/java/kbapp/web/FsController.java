package kbapp.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 本地目录浏览：供新建知识库的目录选择器逐级选择（只列目录，不列文件）。 */
@RestController
@RequestMapping("/api/fs")
public class FsController {

    /** path 为空 → 返回盘符列表；否则返回该目录下的子目录。 */
    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(required = false) String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> dirs = new ArrayList<>();
        if (path == null || path.isBlank()) {
            m.put("current", "");
            for (File root : File.listRoots()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("name", root.getPath().replace("\\", ""));
                d.put("path", root.getPath());
                dirs.add(d);
            }
        } else {
            File dir = new File(path);
            if (!dir.exists() || !dir.isDirectory()) {
                m.put("current", path);
                m.put("error", "目录不存在");
                m.put("dirs", dirs);
                return m;
            }
            m.put("current", dir.getAbsolutePath());
            File[] children = dir.listFiles(File::isDirectory);
            if (children != null) {
                for (File c : children) {
                    if (c.isHidden()) continue;
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("name", c.getName());
                    d.put("path", c.getAbsolutePath());
                    dirs.add(d);
                }
            }
        }
        m.put("dirs", dirs);
        return m;
    }
}
