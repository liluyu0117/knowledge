package kbapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KbServerApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(KbServerApplication.class);
        // 本应用固定跑在用户桌面机上，关闭无头模式以支持 Desktop.open 调用系统默认程序
        app.setHeadless(false);
        app.addInitializers(new kbapp.config.McpSwitchInitializer());
        app.run(args);
    }
}
