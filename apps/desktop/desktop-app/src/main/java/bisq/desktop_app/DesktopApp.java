package bisq.desktop_app;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DesktopApp {
    public static void main(String[] args) {
        Thread.currentThread().setName("DesktopApp.main");
        System.setProperty("javafx.sg.warn", "false");
        new DesktopExecutable(args);
    }
}


