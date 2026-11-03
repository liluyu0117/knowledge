# 知识库便携启动包

新终端 / 新机器上，拿到本仓库后只需三步：

```
git clone https://github.com/liluyu0117/knowledge.git
cd knowledge/portable
双击 start.bat        （Windows：cmd / PowerShell / 双击均可）
bash start.sh         （Linux / macOS / Git Bash）
```

首次运行脚本会自动完成（只需一次，需联网）：

1. 下载 JDK 17（Adoptium，约 190MB）
2. 下载 Maven（清华镜像）
3. 构建服务 jar（依赖走阿里镜像，约 3-5 分钟）
4. 启动服务并打开浏览器 http://localhost:8080

之后每次运行直接启动（秒开）。

## 数据说明

- 服务的数据目录是本目录下的 `.kb/`（配置、索引、会话）
- 仓库**不包含**知识库数据；用坚果云把 `.kb/` 同步到新机器对应位置即可
- 首次启动没有 `.kb/` 时会自动创建空数据目录，或从坚果云同步后再启动

## 目录内容

| 文件/目录 | 说明 | 是否进 git |
|---|---|---|
| start.bat | Windows 启动脚本 | ✅ |
| start.sh | Linux/macOS/Git Bash 启动脚本 | ✅ |
| kb-server.jar | 构建产物（首次自动生成） | ❌ |
| jdk/ | 自动下载的 JDK | ❌ |
| maven/ | 自动下载的 Maven | ❌ |
| .kb/ | 服务数据（坚果云同步） | ❌ |

## 常见问题

- **8080 被占用**：改启动命令端口，如 `start.bat` 里 `java -jar kb-server.jar --server.port=8081`
- **下载 JDK 慢**：可手动从 https://adoptium.net 下载 JDK 17，解压到本目录 `jdk\`（Windows）/ `jdk/` 下
- **更新服务版本**：`git pull` 后删掉本目录的 `kb-server.jar`，再运行脚本会自动重新构建
