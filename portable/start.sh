#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════
#  知识库一键启动（Linux / macOS / Git Bash 通用）
#  首次运行会自动：下载 JDK 17 → 下载 Maven → 构建服务 jar
#  之后每次运行直接启动，数据目录为本目录下的 .kb/
# ══════════════════════════════════════════════════════════
set -e
cd "$(dirname "$0")"

# ---- 启动前自清理：端口已被占用则先停掉旧服务（只处理占用该端口的进程）----
PORT=39217
OLD_PID=""
if command -v lsof >/dev/null 2>&1; then
    OLD_PID="$(lsof -ti tcp:$PORT 2>/dev/null | head -1)"
else
    OLD_PID="$(netstat -ano 2>/dev/null | grep -E "[:.]$PORT[[:space:]]+.*LISTENING" | awk '{print $NF}' | head -1)"
fi
if [ -n "$OLD_PID" ]; then
    echo "[0/3] 端口 $PORT 已被进程 $OLD_PID 占用，先停止旧服务..."
    if command -v tasklist >/dev/null 2>&1; then
        if tasklist //FI "PID eq $OLD_PID" 2>/dev/null | grep -qi "java"; then
            taskkill //PID "$OLD_PID" //F >/dev/null 2>&1
        else
            echo "[错误] 端口 $PORT 被非 Java 进程占用，请手动关闭后重试"; exit 1
        fi
    else
        COMM="$(ps -p "$OLD_PID" -o comm= 2>/dev/null)"
        case "$COMM" in
            *java*) kill "$OLD_PID" 2>/dev/null || true ;;
            *) echo "[错误] 端口 $PORT 被 $COMM 占用，请手动关闭后重试"; exit 1 ;;
        esac
    fi
    sleep 2
    echo "旧服务已停止"
fi

OS="$(uname -s)"; ARCH="$(uname -m)"

find_java() {
    [ -x "jdk/bin/java" ] && { echo "jdk/bin/java"; return; }
    for d in jdk/jdk-*/; do
        [ -x "$d/bin/java" ] && { echo "${d}bin/java"; return; }
        # macOS 包结构 Contents/Home
        [ -x "$d/Contents/Home/bin/java" ] && { echo "${d}Contents/Home/bin/java"; return; }
    done
    command -v java 2>/dev/null || true
}

JAVA="$(find_java)"
if [ -z "$JAVA" ]; then
    echo "[1/3] 未检测到 JDK 17，自动下载（约 190MB，只需一次，下载到本目录 jdk/）..."
    mkdir -p jdk
    # 多源依次尝试：清华 Adoptium 镜像（国内快）→ Adoptium 官方
    dl_jdk() {
        case "$OS/$ARCH" in
            Darwin/arm64)  OFFICIAL="https://api.adoptium.net/v3/binary/latest/17/ga/mac/aarch64/jdk/hotspot/normal/eclipse?project=jdk"; TUNA="" ;;
            Darwin/*)      OFFICIAL="https://api.adoptium.net/v3/binary/latest/17/ga/mac/x64/jdk/hotspot/normal/eclipse?project=jdk"; TUNA="" ;;
            Linux/*)       OFFICIAL="https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk"
                           TUNA="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/linux/OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz" ;;
            MINGW*|MSYS*|CYGWIN*) OFFICIAL="https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk"
                           TUNA="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip" ;;
            *) return 1 ;;
        esac
        for U in "$TUNA" "$OFFICIAL"; do
            [ -z "$U" ] && continue
            echo "  尝试: $U"
            if curl -fL --retry 2 "$U" -o jdk-pkg 2>/dev/null; then
                case "$U" in
                    *.zip|*windows*) unzip -qo jdk-pkg -d jdk && rm jdk-pkg ;;
                    *) tar -xzf jdk-pkg -C jdk && rm jdk-pkg ;;
                esac
                return 0
            fi
            rm -f jdk-pkg
        done
        return 1
    }
    dl_jdk || { echo "[错误] JDK 下载失败，请手动安装 JDK 17 或手动下载解压到本目录 jdk/"; exit 1; }
    JAVA="$(find_java)"
fi
[ -z "$JAVA" ] && { echo "[错误] JDK 安装失败，请手动安装 JDK 17"; exit 1; }
echo "已找到 Java: $JAVA"

if [ ! -f kb-server.jar ]; then
    echo "[2/3] 未找到 kb-server.jar，准备构建（首次约 3-5 分钟，之后无需再构建）..."
    MVN=""
    [ -x "maven/bin/mvn" ] && MVN="maven/bin/mvn"
    for d in maven/apache-maven-*/; do
        [ -x "$d/bin/mvn" ] && MVN="${d}bin/mvn"
    done
    if [ -z "$MVN" ]; then
        echo "下载 Maven（清华镜像，失败换官方源）..."
        MVN_OK=0
        for U in "https://mirrors.tuna.tsinghua.edu.cn/apache/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.tar.gz" \
                 "https://dlcdn.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.tar.gz"; do
            echo "  尝试: $U"
            curl -fL --retry 2 "$U" -o maven.tgz 2>/dev/null || continue
            mkdir -p maven
            tar -xzf maven.tgz -C maven && rm maven.tgz && MVN_OK=1 && break
        done
        [ "$MVN_OK" = "0" ] && { echo "[错误] Maven 下载失败"; exit 1; }
        for d in maven/apache-maven-*/; do
            [ -x "$d/bin/mvn" ] && MVN="${d}bin/mvn"
        done
    fi
    [ -z "$MVN" ] && { echo "[错误] Maven 下载失败"; exit 1; }
    if [ -z "$JAVA_HOME" ]; then
        export JAVA_HOME="$(cd "$(dirname "$JAVA")/.." && pwd)"
    fi
    echo "构建中..."
    MVN_ABS="$(cd "$(dirname "$MVN")" && pwd)/$(basename "$MVN")"
    # 强制用包内 JDK 构建：系统 JAVA_HOME 可能指向过旧的 Java（如 Java 8），会报 class 版本不兼容
    PORTABLE_JDK_HOME=""
    [ -x "jdk/bin/java" ] && PORTABLE_JDK_HOME="$(pwd)/jdk"
    for d in jdk/jdk-*/; do
        [ -x "$d/bin/java" ] && PORTABLE_JDK_HOME="$(cd "$d" && pwd)"
        [ -x "$d/Contents/Home/bin/java" ] && PORTABLE_JDK_HOME="$(cd "$d/Contents/Home" && pwd)"
    done
    [ -n "$PORTABLE_JDK_HOME" ] && export JAVA_HOME="$PORTABLE_JDK_HOME"
    command -v cygpath >/dev/null 2>&1 && JAVA_HOME="$(cygpath -m "$JAVA_HOME")"
    # 依赖仓库放项目内 portable/m2；Git Bash 下 $PWD 是 /e/... 形式，须转成 Windows 路径再传给 JVM
    M2_DIR="$(cd .. && pwd)/portable/m2"
    command -v cygpath >/dev/null 2>&1 && M2_DIR="$(cygpath -m "$M2_DIR")"
    (cd ../server && "$MVN_ABS" -s mvn-settings.xml -q -DskipTests \
        -Dmaven.repo.local="$M2_DIR" package)
    cp ../server/target/kb-server-0.1.0.jar kb-server.jar
fi

echo "[3/3] 启动知识库服务：http://localhost:39217（Ctrl+C 停止）"
(sleep 3
    xdg-open http://localhost:39217 2>/dev/null \
    || open http://localhost:39217 2>/dev/null \
    || explorer.exe http://localhost:39217 2>/dev/null \
    || true) &
mkdir -p tmp
exec "$JAVA" -Djava.io.tmpdir="$(pwd)/tmp" -jar kb-server.jar --server.port=$PORT
