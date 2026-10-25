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
     *  copy    复制目录 + 改名 exe 启动（绕过按进程名检测单实例的软件）
     *  profile 数据目录隔离启动（浏览器类软件加 --user-data-dir）
     *  sandbox 沙盒多开（Sandboxie-Plus，绕互斥体级单实例）
     *  mutex   互斥体绕过（自研内置组件：挂起启动→注入 hook→改互斥体名）
     *  auto    智能多开：自动尝试 直接启动 → 复制目录+改名 → 互斥体绕过 */
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
            case "mutex":
                lastMode = "mutex";
                proc = launchMutex(app, index);
                break;
            case "auto":
                proc = tryChain(app, index);
                break;
            default:
                lastMode = "direct";
                proc = launchDirect(app);
        }
        synchronized (INSTANCES) {
            // mutex 模式：真实目标是注入器启动的那个进程
            long pid = "mutex".equals(mode) ? lastMutexPid : proc.pid();
            if (pid > 0) INSTANCES.add(app.name + "|" + pid);
        }
        return proc;
    }

    /** 智能多开：直接启动 → 复制目录+改名 → 互斥体绕过，逐级尝试 */
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
            Main.logLine("  copy 启动后 8 秒内退出（可能被单实例检测拦截），换下一招");
        } catch (IOException e) {
            Main.logLine("  copy 失败: " + e.getMessage() + "，换下一招");
        }
        // 3) 互斥体绕过（注入原版，最后兜底；对会自检注入的软件慎用）
        Process p = launchMutex(app, index);
        lastMode = "mutex";
        return p;
    }

    /** 等 8 秒：返回 true 表示进程还活着（启动成功）；false 表示已退出（被拦截）。
     *  8 秒覆盖 .NET/Java 软件的冷启动时间，避免慢启动误判 */
    private static boolean aliveAfter4s(Process p) {
        try {
            return !p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
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

    /** 互斥体绕过：注入器挂起启动目标 → 注入 hook.dll（改互斥体名）→ 恢复。
     *  任何按固定互斥体名检测单实例的软件都能多开，无需安装任何外部软件。 */
    private static long lastMutexPid = -1;

    private static Process launchMutex(AppEntry app, int index) throws IOException {
        File exe = new File(app.path);
        if (!exe.isFile()) throw new IOException("找不到程序: " + app.path);
        File injector = findNativeTool("injector.exe");
        File hook = findNativeTool("hook.dll");
        if (injector == null || hook == null) {
            throw new IOException("缺少内置组件 injector.exe / hook.dll（互斥体绕过）——请下载完整发布包");
        }
        Process p = new ProcessBuilder(injector.getAbsolutePath(),
                exe.getAbsolutePath(), hook.getAbsolutePath(), "@150")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).trim();
        try {
            p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("互斥体绕过启动被中断");
        }
        if (p.exitValue() != 0) {
            throw new IOException("互斥体绕过启动失败: " + (out.isEmpty() ? "exit " + p.exitValue() : out));
        }
        int nl = out.indexOf('\n');
        String line = (nl > 0 ? out.substring(0, nl) : out).trim();
        try {
            lastMutexPid = Long.parseLong(line);
        } catch (NumberFormatException e) {
            throw new IOException("互斥体绕过启动失败（无法识别进程）: " + out);
        }
        if (lastMutexPid <= 0) throw new IOException("互斥体绕过启动失败: " + out);
        return p;
    }

    /** 互斥体绕过 + 改名副本：建副本（进程名不同）后注入 hook（互斥体改名）。
     *  双保险组合，对付"固定互斥体 + 进程名级"双重单实例检测的软件 */
    private static Process launchMutexClone(AppEntry app, int index) throws IOException {
        File renamedExe = createClone(app, index);
        File injector = findNativeTool("injector.exe");
        File hook = findNativeTool("hook.dll");
        if (injector == null || hook == null) {
            throw new IOException("缺少内置组件 injector.exe / hook.dll（互斥体绕过）——请下载完整发布包");
        }
        Process p = new ProcessBuilder(injector.getAbsolutePath(),
                renamedExe.getAbsolutePath(), hook.getAbsolutePath(), "@150")
                .directory(renamedExe.getParentFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).trim();
        try {
            p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("互斥体绕过启动被中断");
        }
        if (p.exitValue() != 0) {
            throw new IOException("互斥体绕过启动失败: " + (out.isEmpty() ? "exit " + p.exitValue() : out));
        }
        int nl = out.indexOf('\n');
        String line = (nl > 0 ? out.substring(0, nl) : out).trim();
        try {
            lastMutexPid = Long.parseLong(line);
        } catch (NumberFormatException e) {
            throw new IOException("互斥体绕过启动失败（无法识别进程）: " + out);
        }
        if (lastMutexPid <= 0) throw new IOException("互斥体绕过启动失败: " + out);
        return p;
    }

    /** 在程序目录里找自研内置组件（app-image 时在 app/ 下，源码运行时在工程目录下） */
    /** 内置组件所在目录：优先 jar 所在目录（jpackage 的 app/ 目录，组件就放在这里），
     *  不依赖启动时的工作目录，从任何位置运行都能找到 */
    private static File nativeDir() {
        try {
            java.net.URL url = Launcher.class.getProtectionDomain().getCodeSource().getLocation();
            File jar = new File(url.toURI());
            if (jar.isFile()) return jar.getParentFile();
        } catch (Exception ignored) {
        }
        return new File(System.getProperty("user.dir"));
    }

    private static File findNativeTool(String name) {
        File dir = nativeDir();
        File[] cands = {
                new File(dir, name),                                  // app/ 内
                new File(dir.getParentFile(), "app/" + name),         // 程序根/app
                new File(System.getProperty("user.dir"), "app/" + name),
                new File(System.getProperty("user.dir"), name),
        };
        for (File f : cands) if (f.isFile()) return f;
        return null;
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

    /** 数据目录隔离启动：给浏览器类软件加 --user-data-dir，每次实例独立数据目录 */
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
        File renamedExe = createClone(app, index);
        File dstDir = renamedExe.getParentFile();
        return new ProcessBuilder(renamedExe.getAbsolutePath()).directory(dstDir).start();
    }

    /** 复制目录 + 改名 exe，返回改名后的副本 exe（进程名不同 → 绕过进程名级单实例检测） */
    private static File createClone(AppEntry app, int index) throws IOException {
        File exe = new File(app.path);
        if (!exe.isFile()) throw new IOException("找不到程序: " + app.path);
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
        String name = exe.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        File renamedExe = new File(dstDir, stem + "_多开" + index + ext);
        if (!renamedExe.isFile()) {
            java.nio.file.Files.copy(dstExe.toPath(), renamedExe.toPath());
        }
        // 对副本做"互斥体名补丁"：把 <名字>SingletonMutex 的互斥体名按实例编号
        // 改造成互不相同的名字（等长替换第 2 个字符）。固定互斥体级单实例软件
        // （游戏启动器等）从此可以无限多开，无需注入任何 DLL。
        patchSingletonMutex(renamedExe, index);
        return renamedExe;
    }

    /** 二进制补丁：把 UTF-16LE 的 "<前缀>SingletonMutex" 字符串第 2 个字符替换成实例号。
     *  等长替换（不改变文件长度），对无强签名的 .NET 程序安全有效；未命中的程序原样返回。 */
    private static void patchSingletonMutex(File exe, int index) throws IOException {
        byte[] data = java.nio.file.Files.readAllBytes(exe.toPath());
        byte[] marker = "SingletonMutex".getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        int changed = 0;
        int searchFrom = 0;
        while (true) {
            int pos = indexOf(data, marker, searchFrom);
            if (pos < 0) break;
            // 回看前缀：字母数字下划线（UTF-16LE 每字符 2 字节）
            int start = pos;
            while (start >= 2 && isWordChar16(data[start - 2]) && isWordChar16(data[start - 1])) start -= 2;
            int prefixChars = (pos - start) / 2;
            if (prefixChars >= 1 && prefixChars <= 24) {
                // 第 2 个字符替换为实例号（等长）
                int idx = index % 36;
                char c = (char) (idx < 10 ? '0' + idx : 'A' + idx - 10);
                byte[] ch = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
                data[start + 2] = ch[0];
                data[start + 3] = ch[1];
                changed++;
            }
            searchFrom = pos + 2;
        }
        if (changed > 0) {
            java.nio.file.Files.write(exe.toPath(), data);
        }
    }

    private static boolean isWordChar16(byte b) {
        return (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9') || b == '_';
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = from; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
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
