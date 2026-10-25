package multiopener;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** 配置持久化（Properties 文件，免第三方依赖） */
public class Config {
    private static final String FILE_NAME = "config.properties";
    private static final String SEP = "\u0001";

    public boolean scheduledEnabled = false;
    public int freezeTimeMin = -1;    // 每日冻结时间（0..1439，-1=未设置）
    public int unfreezeTimeMin = -1;  // 每日解冻时间
    public boolean darkTheme = false;

    public List<AppEntry> apps = new ArrayList<>();

    public static File configFile() {
        String appdata = System.getenv("APPDATA");
        File dir = new File(appdata == null ? System.getProperty("user.home")
                : appdata, "MultiOpenerWin");
        return new File(dir, FILE_NAME);
    }

    public static Config load() {
        Config c = new Config();
        Properties p = new Properties();
        File f = configFile();
        if (f.isFile()) {
            try (FileInputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (IOException ignored) {
            }
            c.scheduledEnabled = Boolean.parseBoolean(p.getProperty("scheduledEnabled", "false"));
            c.freezeTimeMin = Integer.parseInt(p.getProperty("freezeTimeMin", "-1"));
            c.unfreezeTimeMin = Integer.parseInt(p.getProperty("unfreezeTimeMin", "-1"));
            c.darkTheme = Boolean.parseBoolean(p.getProperty("darkTheme", "false"));
            String list = p.getProperty("apps", "");
            if (!list.isEmpty()) {
                for (String item : list.split(";")) {
                    String[] parts = item.split(SEP);
                    if (parts.length >= 3) {
                        int count = 2;
                        try { count = Integer.parseInt(parts[3]); } catch (Exception ignored) {}
                        c.apps.add(new AppEntry(parts[0], parts[1], parts[2], count));
                    }
                }
            }
        }
        return c;
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("scheduledEnabled", String.valueOf(scheduledEnabled));
        p.setProperty("freezeTimeMin", String.valueOf(freezeTimeMin));
        p.setProperty("unfreezeTimeMin", String.valueOf(unfreezeTimeMin));
        p.setProperty("darkTheme", String.valueOf(darkTheme));
        StringBuilder sb = new StringBuilder();
        for (AppEntry a : apps) {
            if (sb.length() > 0) sb.append(";");
            sb.append(a.name).append(SEP).append(a.path).append(SEP).append(a.mode).append(SEP).append(a.count);
        }
        p.setProperty("apps", sb.toString());
        File f = configFile();
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
        try (FileOutputStream out = new FileOutputStream(f)) {
            p.store(out, "MultiOpener Win config");
        } catch (IOException ignored) {
        }
    }
}
