package multiopener;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** 启动多开实例；记录 PID 以支持一键冻结/解冻 */
public class Launcher {
    /** 当前由本程序启动的实例（name + pid） */
    public static final List<String> INSTANCES = new ArrayList<>();

    private static final File SCRIPT_DIR = scriptsDir();

    private static File scriptsDir() {
        // app-image 运行时脚本位于程序目录 scripts/ 下；源码运行时位于工程目录下
        File cwd = new File(System.getProperty("user.dir"));
        File s = new File(cwd, "scripts");
        if (s.isDirectory()) return s;
        File s2 = new File(cwd, "windows-multiopener/scripts");
        if (s2.isDirectory()) return s2;
        return s;
    }

    /** 当前实例实际用到的多开方式（供界面日志显示） */
    public static volatile String lastMode = "direct";

    /** 启动一个实例，返回进程对象（失败抛异常）。
     *  多开方法（按 AppEntry.mode）：
     *  direct  直接启动（无单实例限制的软件）
     *  copy    复制目录 + 改名 exe 启动（绕过按进程名检测单实例：PCL、微信）
     *  profile 数据目录隔离启动（Chromium 系：Chrome/Edge 加 --user-data-dir）
     *  sandbox 沙盒多开（Sandboxie-Plus，绕互斥体级单实例：PCL2 等）
     *  auto    智能多开：自动尝试 直接启动 → 复制目录+改名 → 沙盒 */
    public static Process launch(AppEntry app, int index) throws IOException {
        String mode = app.mode;
        Process proc;
        switch (mode) {
            case "copy":
                lastMode = "copy";
                proc = launchCopy(app, index);
                break;
            case "profile":
                lastMode = "profile";
                proc = launchProfile(app, index);
                break;
            case "sandbox":
                lastMode = "sandbox";
                proc = launchSandbox(app, index);
                break;
            case "auto":
                proc = tryChain(app, index);
                break;
            default:
                lastMode = "direct";
                proc = launchDirect(app);
        }
        synchronized (INSTANCES) {
            INSTANCES.add(app.name + "|" + proc.pid());
        }
        return proc;
    }

    /** 智能多开：直接启动 → 复制目录+改名 → 沙盒，逐级尝试 */
    private static Process tryChain(AppEntry app, int index) throws IOException {
        String exeName = new File(app.path).getName();
        // 1) 无同名进程时直接启动；若 4 秒内退出（被单实例检测拦）则换下一招
        if (!isProcessRunning(exeName)) {
            try {
                Process p = launchDirect(app);
                if (aliveAfter4s(p)) { lastMode = "direct"; return p; }
            } catch (IOException ignored) { }
        }
        // 2) 复制目录 + 改名 exe（绕进程名检测）
        try {
            Process p = launchCopy(app, index);
            if (aliveAfter4s(p)) { lastMode = "copy"; return p; }
        } catch (IOException ignored) { }
        // 3) 沙盒多开（绕互斥体级单实例；需已安装 Sandboxie-Plus）
        Process p = launchSandbox(app, index);
        lastMode = "sandbox";
        return p;
    }

    /** 等 4 秒：返回 true 表示进程还活着（启动成功）；false 表示已退出（被拦截） */
    private static boolean aliveAfter4s(Process p) {
        try {
            return !p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return p.isAlive();
        }
    }

    /** 直接启动 */
    private static Process launchDirect(AppEntry app) throws IOException {
        File exe = new File(app.path);
        if (!exe.isFile()) throw new IOException("找不到程序: " + app.path);
        return new ProcessBuilder(app.path).directory(exe.getParentFile()).start();
    }

    /** 沙盒多开：每个实例独立 Sandboxie box，互斥体互相隔离 */
    private static Process launchSandbox(AppEntry app, int index) throws IOException {
        File exe = new File(app.path);
        if (!exe.isFile()) throw new IOException("找不到程序: " + app.path);
        File startExe = findSandboxieStart();
        if (startExe == null) {
            throw new IOException("未安装 Sandboxie-Plus（免费开源沙盒，https://sandboxie-plus.com）——"
                    + "互斥体级单实例软件需要它才能多开");
        }
        String safe = app.name.replaceAll("[\\\\/:*?\"<>|]", "_");
        String box = "MultiOpener_" + safe + "_" + index;
        ensureSandboxieBox(box);
        return new ProcessBuilder(startExe.getAbsolutePath(), "/box:" + box, exe.getAbsolutePath())
                .directory(exe.getParentFile()).start();
    }

    /** 查找 Sandboxie-Plus 的 Start.exe */
    private static File findSandboxieStart() {
        String sbieHome = System.getenv("SBIEHOME");
        if (sbieHome != null) {
            File f = new File(sbieHome, "Start.exe");
            if (f.isFile()) return f;
        }
        File[] paths = {
                new File("C:/Program Files/Sandboxie-Plus/Start.exe"),
                new File("C:/Program Files (x86)/Sandboxie-Plus/Start.exe"),
                new File("C:/Program Files/Sandboxie/Start.exe"),
                new File("C:/Program Files (x86)/Sandboxie/Start.exe"),
        };
        for (File f : paths) if (f.isFile()) return f;
        return null;
    }

    /** 确保 Sandboxie box 存在（往 Sandboxie.ini 追加 [box] 段） */
    private static void ensureSandboxieBox(String box) {
        String sbieHome = System.getenv("SBIEHOME");
        String programData = System.getenv("ProgramData");
        File[] inis = {
                programData == null ? null : new File(programData, "SandboxiePlus/Sandboxie.ini"),
                sbieHome == null ? null : new File(sbieHome, "Sandboxie.ini"),
                new File(System.getProperty("user.home"), "AppData/Roaming/SandboxiePlus/Sandboxie.ini"),
        };
        for (File ini : inis) {
            if (ini == null || !ini.isFile()) continue;
            try {
                java.nio.file.Path p = ini.toPath();
                byte[] data = java.nio.file.Files.readAllBytes(p);
                String content = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                if (!content.contains("[" + box + "]")) {
                    java.nio.file.Files.write(p, ("\n[" + box + "]\nEnabled=y\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            java.nio.file.StandardOpenOption.APPEND);
                }
                return; // 只要改到第一份存在的 ini 就够
            } catch (IOException ignored) { }
        }
    }

    /** 数据目录隔离启动：给 Chromium 系软件加 --user-data-dir，每次实例独立数据目录 */
    private static Process launchProfile(AppEntry app, int index) throws IOException {
        File exe = new File(app.path);
        if (!exe.isFile()) throw new IOException("找不到程序: " + app.path);
        String safe = app.name.replaceAll("[\\\\/:*?\"<>|]", "_");
        File cloneRoot = new File(Config.configFile().getParentFile(), "clones");
        File dataDir = new File(cloneRoot, safe + "-" + index + "-profile");
        if (!dataDir.isDirectory() && !dataDir.mkdirs()) throw new IOException("无法创建数据目录: " + dataDir);
        return new ProcessBuilder(app.path,
                "--user-data-dir=" + dataDir.getAbsolutePath(), "--no-first-run")
                .directory(exe.getParentFile()).start();
    }

    /** 复制目录 + 改名 exe 启动 */
    private static Process launchCopy(AppEntry app, int index) throws IOException {
        File exe = new File(app.path);
        if (!exe.isFile()) throw new IOException("找不到程序: " + app.path);
        // 复制目录启动：复制安装目录，并把 exe 改名为 "原名_多开N.exe" 再启动，
        // 绕过按进程名/窗口名检测单实例的软件（PCL、微信 4.x 等）
        String safe = app.name.replaceAll("[\\\\/:*?\"<>|]", "_");
        File cloneRoot = new File(Config.configFile().getParentFile(), "clones");
        File dstDir = new File(cloneRoot, safe + "-" + index);
        File dstExe = new File(dstDir, exe.getName());
        if (!dstExe.isFile()) {
            if (!dstDir.mkdirs() && !dstDir.isDirectory()) throw new IOException("无法创建目录: " + dstDir);
            // 复制安装目录；排除大型数据目录（如 .minecraft），用目录链接共享而不是整份复制
            Process rob = new ProcessBuilder("robocopy", exe.getParentFile().getAbsolutePath(),
                    dstDir.getAbsolutePath(), "/E", "/XD", ".minecraft", "/NFL", "/NDL", "/NJH", "/NJS", "/NP")
                    .redirectErrorStream(true).start();
            try {
                rob.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (rob.exitValue() > 7) throw new IOException("复制目录失败 (robocopy exit " + rob.exitValue() + ")");
            // 把源目录里的大型数据目录用 junction 链接到副本（零复制、共享数据）
            File srcMc = new File(exe.getParentFile(), ".minecraft");
            File dstMc = new File(dstDir, ".minecraft");
            if (srcMc.isDirectory() && !dstMc.exists()) {
                try {
                    new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                            dstMc.getAbsolutePath(), srcMc.getAbsolutePath())
                            .redirectErrorStream(true).start().waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        // 改名副本：Weixin.exe -> Weixin_多开1.exe（进程名不同 → 绕过单实例检测）
        String name = exe.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        File renamedExe = new File(dstDir, stem + "_多开" + index + ext);
        if (!renamedExe.isFile()) {
            java.nio.file.Files.copy(dstExe.toPath(), renamedExe.toPath());
        }
        return new ProcessBuilder(renamedExe.getAbsolutePath()).directory(dstDir).start();
    }

    /** 判断某个 exe 名（如 Weixin.exe）对应的进程是否已在运行 */
    private static boolean isProcessRunning(String exeName) {
        try {
            Process p = new ProcessBuilder("tasklist.exe", "/FI",
                    "IMAGENAME eq " + exeName, "/FO", "CSV", "/NH")
                    .redirectErrorStream(true).start();
            byte[] buf = p.getInputStream().readAllBytes();
            String out = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
            p.waitFor();
            // 输出含 "xxx.exe" 且不是仅标题行 → 有同名进程
            return out.toLowerCase().contains(exeName.toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    /** 挂起 / 恢复指定 pid（PowerShell + NtSuspendProcess/NtResumeProcess） */
    public static void suspendProcess(long pid, boolean suspend) {
        String script = suspend ? "suspend.ps1" : "resume.ps1";
        File s = new File(SCRIPT_DIR, script);
        if (!s.isFile()) return;
        try {
            new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", s.getAbsolutePath(), String.valueOf(pid))
                    .redirectErrorStream(true).start();
        } catch (IOException ignored) {
        }
    }

    /** 一键冻结：挂起所有已多开的实例 */
    public static synchronized void freezeAll() {
        List<String> list;
        synchronized (INSTANCES) {
            list = new ArrayList<>(INSTANCES);
        }
        for (String item : list) {
            try {
                suspendProcess(Long.parseLong(item.substring(item.indexOf('|') + 1)), true);
            } catch (Exception ignored) {
            }
        }
    }

    /** 一键解冻：恢复所有已多开的实例 */
    public static synchronized void unfreezeAll() {
        List<String> list;
        synchronized (INSTANCES) {
            list = new ArrayList<>(INSTANCES);
        }
        for (String item : list) {
            try {
                suspendProcess(Long.parseLong(item.substring(item.indexOf('|') + 1)), false);
            } catch (Exception ignored) {
            }
        }
    }
}
