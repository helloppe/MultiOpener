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

    /** 启动一个实例，返回进程对象（失败抛异常）。
     *  多开方法（按 AppEntry.mode）：
     *  direct  直接启动（无单实例限制的软件）
     *  copy    复制目录 + 改名 exe 启动（绕过按进程名检测单实例：PCL、微信）
     *  profile 数据目录隔离启动（Chromium 系：Chrome/Edge 加 --user-data-dir）
     *  auto    智能多开：先直接启动；发现同名实例已在运行 → 自动改用复制目录+改名 */
    public static Process launch(AppEntry app, int index) throws IOException {
        String mode = app.mode;
        Process proc;
        switch (mode) {
            case "copy":
                proc = launchCopy(app, index);
                break;
            case "profile":
                proc = launchProfile(app, index);
                break;
            case "auto": {
                if (isProcessRunning(new File(app.path).getName())) {
                    proc = launchCopy(app, index);
                } else {
                    proc = launchDirect(app);
                }
                break;
            }
            default:
                proc = launchDirect(app);
        }
        synchronized (INSTANCES) {
            INSTANCES.add(app.name + "|" + proc.pid());
        }
        return proc;
    }

    /** 直接启动 */
    private static Process launchDirect(AppEntry app) throws IOException {
        File exe = new File(app.path);
        if (!exe.isFile()) throw new IOException("找不到程序: " + app.path);
        return new ProcessBuilder(app.path).directory(exe.getParentFile()).start();
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
