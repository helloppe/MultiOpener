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

    /** 启动一个实例，返回进程对象（失败抛异常） */
    public static Process launch(AppEntry app, int index) throws IOException {
        boolean copy = "copy".equals(app.mode);
        File exe = new File(app.path);
        if (!exe.isFile()) throw new IOException("找不到程序: " + app.path);

        Process proc;
        if (!copy) {
            proc = new ProcessBuilder(app.path).directory(exe.getParentFile()).start();
        } else {
            // 复制目录启动：先把安装目录复制到独立文件夹，再启动副本
            String safe = app.name.replaceAll("[\\\\/:*?\"<>|]", "_");
            File cloneRoot = new File(Config.configFile().getParentFile(), "clones");
            File dstDir = new File(cloneRoot, safe + "-" + index);
            File dstExe = new File(dstDir, exe.getName());
            if (!dstExe.isFile()) {
                if (!dstDir.mkdirs() && !dstDir.isDirectory()) throw new IOException("无法创建目录: " + dstDir);
                Process rob = new ProcessBuilder("robocopy", exe.getParentFile().getAbsolutePath(),
                        dstDir.getAbsolutePath(), "/E", "/NFL", "/NDL", "/NJH", "/NJS", "/NP")
                        .redirectErrorStream(true).start();
                rob.onExit().thenRun(() -> { });
                try {
                    rob.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (rob.exitValue() > 7) throw new IOException("复制目录失败 (robocopy exit " + rob.exitValue() + ")");
            }
            proc = new ProcessBuilder(dstExe.getAbsolutePath()).directory(dstDir).start();
        }
        synchronized (INSTANCES) {
            INSTANCES.add(app.name + "|" + proc.pid());
        }
        return proc;
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
