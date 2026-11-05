package kbapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.awt.Desktop;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
@EnableScheduling
public class KbServerApplication {

    /** 默认端口；与 application.yml 保持一致。 */
    private static final int DEFAULT_PORT = 39217;

    public static void main(String[] args) {
        int port = resolvePort(args);
        boolean quiet = isQuiet(args);
        boolean openBrowser = !quiet && Boolean.parseBoolean(System.getProperty("kb.openBrowser", "false"));

        // 单实例：同一端口上已有本应用在响应时，不再重复启动
        // （桌面版双击两次、或 Web 版已在跑时再点图标，都走这里）
        if (alreadyRunning(port)) {
            System.out.println("[kb] 服务已在运行: http://127.0.0.1:" + port);
            if (openBrowser) openBrowser(port);
            return;
        }

        SpringApplication app = new SpringApplication(KbServerApplication.class);
        // 本应用固定跑在用户桌面机上，关闭无头模式以支持 Desktop.open 调用系统默认程序
        app.setHeadless(false);
        app.addInitializers(new kbapp.config.McpSwitchInitializer());
        app.run(args);

        if (openBrowser) openBrowser(port);
    }

    /** 静默模式：桌面壳内启动时不弹浏览器、不打印启动横幅。 */
    private static boolean isQuiet(String[] args) {
        if (Boolean.parseBoolean(System.getProperty("kb.quiet", "false"))) return true;
        for (String a : args) {
            if ("--kb.quiet".equals(a) || "--kb.quiet=true".equals(a)) return true;
        }
        return false;
    }

    /** 解析 --server.port=NNNN（命令行与 jpackage/Electron 传参都支持覆盖）。 */
    private static int resolvePort(String[] args) {
        for (String a : args) {
            if (a != null && a.startsWith("--server.port=")) {
                try { return Integer.parseInt(a.substring("--server.port=".length()).trim()); }
                catch (NumberFormatException ignored) { }
            }
        }
        String p = System.getProperty("server.port");
        if (p != null) {
            try { return Integer.parseInt(p.trim()); } catch (NumberFormatException ignored) { }
        }
        return DEFAULT_PORT;
    }

    /** 探测端口上是否已有本应用在响应（看 /api/health，不只看端口占用，避免误判）。 */
    private static boolean alreadyRunning(int port) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/api/health")
                    .toURL().openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(1200);
            c.setRequestMethod("GET");
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void openBrowser(int port) {
        try {
            TimeUnit.MILLISECONDS.sleep(1200);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create("http://localhost:" + port));
            }
        } catch (Exception e) {
            System.out.println("[kb] 自动打开浏览器失败，请手动访问 http://localhost:" + port);
        }
    }
}
