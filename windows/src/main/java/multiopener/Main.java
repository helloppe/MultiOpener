package multiopener;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/**
 * 通用多开器（Windows 版）
 * 与安卓版同仓库（MultiOpener），功能对应：
 *   多开 / 一键冻结(挂起) / 一键解冻(恢复) / 定时冻结 / 检查更新 / 深色主题 / 实例改名
 */
public class Main extends JFrame {
    private static final String VERSION = "1.0.3";
    private static final String[] COLUMNS = {"软件", "程序路径", "启动方式", "实例数"};

    private Config cfg;
    private DefaultTableModel model;
    private JTextArea log;
    private JComboBox<String> modeBox = new JComboBox<>(new String[]{"直接启动", "复制目录启动", "数据目录隔离", "互斥体绕过", "沙盒多开", "智能多开"});
    private Timer scheduler;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(Main::new);
    }

    private Main() {
        cfg = Config.load();
        applyTheme(cfg.darkTheme);
        buildUi();
        scheduleLoop();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (scheduler != null) scheduler.cancel();
                System.exit(0);
            }
        });
    }

    // ---------- 界面 ----------
    private void buildUi() {
        setTitle("通用多开器（Windows 版）v" + VERSION);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(900, 580);
        setMinimumSize(new Dimension(780, 500));
        setLocationRelativeTo(null);

        // 顶栏
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        JLabel title = new JLabel("通用多开器（Windows 版）");
        title.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 16));
        top.add(title);
        top.add(new JLabel("v" + VERSION + " · Java 版 · 完全开源"));
        JButton help = new JButton("帮助");
        help.addActionListener(e -> showHelp());
        top.add(help);
        JButton theme = new JButton(cfg.darkTheme ? "浅色主题" : "深色主题");
        theme.addActionListener(e -> {
            cfg.darkTheme = !cfg.darkTheme;
            cfg.save();
            applyTheme(cfg.darkTheme);
            SwingUtilities.updateComponentTreeUI(this);
            theme.setText(cfg.darkTheme ? "浅色主题" : "深色主题");
            logLine("已切换为" + (cfg.darkTheme ? "深色" : "浅色") + "主题");
        });
        top.add(theme);
        add(top, BorderLayout.NORTH);

        // 工具栏
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        addButton(bar, "添加软件", this::addApp);
        addButton(bar, "删除所选", this::removeApps);
        addButton(bar, "多开所选", () -> runMulti(false));
        addButton(bar, "全部多开", () -> runMulti(true));
        addButton(bar, "一键冻结", Launcher::freezeAll);
        addButton(bar, "一键解冻", Launcher::unfreezeAll);
        addButton(bar, "定时冻结", this::showScheduleDialog);
        addButton(bar, "检查更新", this::checkUpdate);

        // 表格
        model = new DefaultTableModel(new Object[][]{}, COLUMNS) {
            @Override public boolean isCellEditable(int row, int col) {
                return col == 0 || col == 2 || col == 3;
            }
        };
        JTable table = new JTable(model);
        table.getColumnModel().getColumn(0).setPreferredWidth(110);
        table.getColumnModel().getColumn(1).setPreferredWidth(380);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        table.getColumnModel().getColumn(3).setPreferredWidth(60);
        modeBox.addActionListener(e -> { /* editor 由表格持有 */ });
        table.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(modeBox));
        table.setRowHeight(26);

        JPanel center = new JPanel(new BorderLayout());
        center.add(bar, BorderLayout.NORTH);
        center.add(new JScrollPane(table), BorderLayout.CENTER);

        // 日志
        log = new JTextArea(7, 80);
        log.setEditable(false);
        log.setFont(new Font("Consolas", Font.PLAIN, 12));
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(new JLabel("运行日志："), BorderLayout.NORTH);
        bottom.add(new JScrollPane(log), BorderLayout.CENTER);

        add(center, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        // 自动检测常见软件
        detectCommonApps();
        refreshTable();
        logLine("欢迎使用通用多开器（Windows 版）v" + VERSION + "，已加载 " + cfg.apps.size() + " 个软件。");
        logLine("提示：微信等单实例软件选「复制目录启动」；一键冻结=挂起全部多开实例。");
        setVisible(true);
    }

    private void addButton(JPanel bar, String text, Runnable action) {
        JButton b = new JButton(text);
        b.addActionListener(e -> action.run());
        bar.add(b);
    }

    private void refreshTable() {
        model.setRowCount(0);
        for (AppEntry a : cfg.apps) {
            model.addRow(new Object[]{a.name, a.path, modeLabel(a.mode), a.count});
        }
    }

    private String modeLabel(String mode) {
        switch (mode) {
            case "copy": return "复制目录+改名";
            case "profile": return "数据目录隔离";
            case "sandbox": return "沙盒多开";
            case "mutex": return "互斥体绕过";
            case "auto": return "智能多开";
            default: return "直接启动";
        }
    }

    private String modeOf(String label) {
        switch (label) {
            case "复制目录+改名": return "copy";
            case "数据目录隔离": return "profile";
            case "沙盒多开": return "sandbox";
            case "互斥体绕过": return "mutex";
            case "智能多开": return "auto";
            default: return "direct";
        }
    }

    private void saveFromTable() {
        for (int i = 0; i < model.getRowCount() && i < cfg.apps.size(); i++) {
            AppEntry a = cfg.apps.get(i);
            Object n = model.getValueAt(i, 0);
            Object p = model.getValueAt(i, 1);
            Object m = model.getValueAt(i, 2);
            Object c = model.getValueAt(i, 3);
            if (n != null) a.name = n.toString();
            if (p != null) a.path = p.toString();
            if (m != null) a.mode = modeOf(m.toString());
            if (c != null) {
                try { a.count = Math.max(1, Integer.parseInt(c.toString())); }
                catch (Exception ignored) { }
            }
        }
        cfg.save();
    }

    // ---------- 操作 ----------
    private void addApp() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("程序 (*.exe)", "exe"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            String name = f.getName();
            if (name.toLowerCase().endsWith(".exe")) name = name.substring(0, name.length() - 4);
            cfg.apps.add(new AppEntry(name, f.getAbsolutePath(), "direct", 2));
            cfg.save();
            refreshTable();
            logLine("已添加：" + name + " → " + f.getAbsolutePath());
        }
    }

    private void removeApps() {
        JTable table = findTable();
        int[] rows = table == null ? new int[0] : table.getSelectedRows();
        if (rows.length == 0) {
            JOptionPane.showMessageDialog(this, "请先在列表里选中要删除的软件。");
            return;
        }
        saveFromTable();
        List<AppEntry> keep = new ArrayList<>();
        for (int i = 0; i < cfg.apps.size(); i++) {
            boolean del = false;
            for (int r : rows) if (r == i) { del = true; break; }
            if (!del) keep.add(cfg.apps.get(i));
        }
        cfg.apps = keep;
        cfg.save();
        refreshTable();
        logLine("已删除所选软件。");
    }

    private int[] getSelectedRows() {
        JTable table = findTable();
        return table == null ? new int[0] : table.getSelectedRows();
    }

    private void runMulti(boolean all) {
        saveFromTable();
        final List<Integer> rows = new ArrayList<>();
        if (all) {
            for (int i = 0; i < cfg.apps.size(); i++) rows.add(i);
        } else {
            JTable table = findTable();
            if (table != null) {
                for (int r : table.getSelectedRows()) rows.add(r);
            }
        }
        if (rows.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请先在列表里选中要多开的软件。");
            return;
        }
        new Thread(() -> {
            for (int i : rows) {
                AppEntry a = cfg.apps.get(i);
                if (!new File(a.path).isFile()) {
                    logLine("✗ " + a.name + "：找不到程序 " + a.path);
                    continue;
                }
                logLine("▶ " + a.name + "：" + modeLabel(a.mode) + " × " + a.count);
                for (int k = 1; k <= a.count; k++) {
                    try {
                        Process proc = Launcher.launch(a, k);
                        String used = Launcher.lastMode;
                        logLine("  ✓ 第 " + k + " 个实例已启动（" + modeLabel(used) + "）");
                        // 手动选「复制目录+改名」时：副本 3 秒内退出 → 提示该软件是互斥体级
                        if ("copy".equals(a.mode)) {
                            sleep(3000);
                            if (!proc.isAlive()) {
                                logLine("  ⚠ 第 " + k + " 个实例退出了——该软件可能是「互斥体级」单实例（如 PCL2），");
                                logLine("    请把启动方式改成「沙盒多开」（需安装 Sandboxie-Plus）或「智能多开」。");
                            }
                        }
                    } catch (Exception e) {
                        logLine("  ✗ 第 " + k + " 个实例启动失败：" + e.getMessage());
                    }
                    sleep(800);
                }
            }
        }, "multi-launch").start();
    }

    private JTable findTable() {
        for (java.awt.Component c : getContentPane().getComponents()) {
            JTable t = findTableIn(c);
            if (t != null) return t;
        }
        return null;
    }

    private JTable findTableIn(java.awt.Component c) {
        if (c instanceof JTable) return (JTable) c;
        if (c instanceof java.awt.Container) {
            for (java.awt.Component ch : ((java.awt.Container) c).getComponents()) {
                JTable t = findTableIn(ch);
                if (t != null) return t;
            }
        }
        return null;
    }

    // ---------- 定时冻结 ----------
    private void scheduleLoop() {
        scheduler = new Timer("scheduler", true);
        scheduleNext();
    }

    private void scheduleNext() {
        if (!cfg.scheduledEnabled) return;
        long now = System.currentTimeMillis();
        long freeze = cfg.freezeTimeMin >= 0 ? nextTime(cfg.freezeTimeMin, now) : -1;
        long unfreeze = cfg.unfreezeTimeMin >= 0 ? nextTime(cfg.unfreezeTimeMin, now) : -1;
        long target = -1;
        boolean freezeJob = true;
        if (freeze > 0 && (target < 0 || freeze < target)) { target = freeze; freezeJob = true; }
        if (unfreeze > 0 && (target < 0 || unfreeze < target)) { target = unfreeze; freezeJob = false; }
        if (target < 0) return;
        final boolean doFreeze = freezeJob;
        try {
            scheduler.schedule(new TimerTask() {
                @Override public void run() {
                    if (doFreeze) {
                        Launcher.freezeAll();
                        logLine("⏰ 定时冻结：已挂起全部多开实例");
                    } else {
                        Launcher.unfreezeAll();
                        logLine("⏰ 定时解冻：已恢复全部多开实例");
                    }
                    scheduleNext();
                }
            }, target - now);
        } catch (IllegalStateException ignored) {
        }
    }

    private long nextTime(int minuteOfDay, long now) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        cal.set(Calendar.MINUTE, minuteOfDay % 60);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= now) cal.add(Calendar.DAY_OF_YEAR, 1);
        return cal.getTimeInMillis();
    }

    private void showScheduleDialog() {
        JDialog dlg = new JDialog(this, "定时冻结", true);
        dlg.setLayout(new BorderLayout());
        JPanel body = new JPanel(new java.awt.GridLayout(4, 1, 4, 6));
        body.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
        JCheckBox enable = new JCheckBox("启用定时冻结", cfg.scheduledEnabled);
        JSpinner freezeSp = new JSpinner(new SpinnerDateModel());
        freezeSp.setEditor(new JSpinner.DateEditor(freezeSp, "HH:mm"));
        JSpinner unfreezeSp = new JSpinner(new SpinnerDateModel());
        unfreezeSp.setEditor(new JSpinner.DateEditor(unfreezeSp, "HH:mm"));
        JPanel p1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        p1.add(new JLabel("每日冻结时间："));
        p1.add(freezeSp);
        JPanel p2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        p2.add(new JLabel("每日解冻时间："));
        p2.add(unfreezeSp);
        body.add(enable);
        body.add(p1);
        body.add(p2);
        body.add(new JLabel("说明：到点自动挂起 / 恢复全部多开实例（相当于安卓版的定时冻结）。"));
        dlg.add(body, BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton ok = new JButton("保存");
        ok.addActionListener(e -> {
            java.util.Date f = (java.util.Date) freezeSp.getValue();
            java.util.Date u = (java.util.Date) unfreezeSp.getValue();
            cfg.scheduledEnabled = enable.isSelected();
            cfg.freezeTimeMin = f.getHours() * 60 + f.getMinutes();
            cfg.unfreezeTimeMin = u.getHours() * 60 + u.getMinutes();
            cfg.save();
            if (scheduler != null) scheduler.cancel();
            scheduler = new Timer("scheduler", true);
            scheduleNext();
            logLine(cfg.scheduledEnabled ? "定时冻结已启用：每天 " + fmtTime(cfg.freezeTimeMin)
                    + " 冻结 / " + fmtTime(cfg.unfreezeTimeMin) + " 解冻"
                    : "定时冻结已关闭");
            dlg.dispose();
        });
        JButton cancel = new JButton("取消");
        cancel.addActionListener(e -> dlg.dispose());
        btns.add(ok);
        btns.add(cancel);
        dlg.add(btns, BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    private static String fmtTime(int minuteOfDay) {
        return String.format("%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }

    // ---------- 检查更新 ----------
    private void checkUpdate() {
        logLine("正在检查更新……");
        new Thread(() -> {
            String latest = Updater.fetchLatestTag();
            SwingUtilities.invokeLater(() -> {
                if (latest == null) {
                    logLine("✗ 检查更新失败，请检查网络");
                    JOptionPane.showMessageDialog(this, "检查更新失败，请检查网络。");
                    return;
                }
                if (("v" + VERSION).equalsIgnoreCase(latest)) {
                    logLine("✓ 已是最新版本 v" + VERSION);
                    JOptionPane.showMessageDialog(this, "已是最新版本 v" + VERSION + "。");
                } else {
                    logLine("→ 发现新版本 " + latest);
                    int r = JOptionPane.showConfirmDialog(this,
                            "发现新版本 " + latest + "（当前 v" + VERSION + "），是否前往下载？",
                            "发现新版本", JOptionPane.YES_NO_OPTION);
                    if (r == JOptionPane.YES_OPTION) {
                        try {
                            java.awt.Desktop.getDesktop().browse(
                                    java.net.URI.create(Updater.releaseUrl()));
                        } catch (Exception ex) {
                            logLine("✗ 无法打开浏览器：" + ex.getMessage());
                        }
                    }
                }
            });
        }, "check-update").start();
    }

    // ---------- 主题 ----------
    private void applyTheme(boolean dark) {
        if (dark) {
            UIManager.put("Panel.background", new Color(0x1E1E1E));
            UIManager.put("Panel.foreground", new Color(0xE0E0E0));
            UIManager.put("Button.background", new Color(0x333333));
            UIManager.put("Button.foreground", new Color(0xE0E0E0));
            UIManager.put("Label.foreground", new Color(0xE0E0E0));
            UIManager.put("Table.background", new Color(0x252525));
            UIManager.put("Table.foreground", new Color(0xE0E0E0));
            UIManager.put("TableHeader.background", new Color(0x2D2D2D));
            UIManager.put("TableHeader.foreground", new Color(0xE0E0E0));
            UIManager.put("TextArea.background", new Color(0x1A1A1A));
            UIManager.put("TextArea.foreground", new Color(0x9CDCFE));
            UIManager.put("TextField.background", new Color(0x333333));
            UIManager.put("TextField.foreground", new Color(0xE0E0E0));
            UIManager.put("ComboBox.background", new Color(0x333333));
            UIManager.put("ComboBox.foreground", new Color(0xE0E0E0));
            UIManager.put("ScrollPane.background", new Color(0x1E1E1E));
        } else {
            UIManager.put("Panel.background", new Color(0xF0F0F0));
            UIManager.put("Panel.foreground", new Color(0x000000));
            UIManager.put("Button.background", new Color(0xE0E0E0));
            UIManager.put("Button.foreground", new Color(0x000000));
            UIManager.put("Label.foreground", new Color(0x000000));
            UIManager.put("Table.background", Color.WHITE);
            UIManager.put("Table.foreground", new Color(0x000000));
            UIManager.put("TableHeader.background", new Color(0xE0E0E0));
            UIManager.put("TableHeader.foreground", new Color(0x000000));
            UIManager.put("TextArea.background", Color.WHITE);
            UIManager.put("TextArea.foreground", new Color(0x000000));
            UIManager.put("TextField.background", Color.WHITE);
            UIManager.put("TextField.foreground", new Color(0x000000));
            UIManager.put("ComboBox.background", Color.WHITE);
            UIManager.put("ComboBox.foreground", new Color(0x000000));
            UIManager.put("ScrollPane.background", Color.WHITE);
        }
    }

    // ---------- 工具 ----------
    private void detectCommonApps() {
        String localAppData = System.getenv("LOCALAPPDATA");
        String windir = System.getenv("WINDIR");
        // 每个候选: 路径 + 名称 + 默认多开方式
        // copy=复制目录+改名（绕进程名检测，适合 PCL/微信）
        // profile=数据目录隔离（适合 Chrome/Edge 等 Chromium 软件）
        // direct=直接启动（无单实例限制的软件）
        String[][] candidates = {
                {"D:/Weixin/Weixin.exe", "微信", "copy"},
                {"D:/QQ/QQ.exe", "QQ", "direct"},
                {"C:/Program Files/Google/Chrome/Application/chrome.exe", "Chrome", "profile"},
                {"C:/Program Files (x86)/Google/Chrome/Application/chrome.exe", "Chrome", "profile"},
                {"C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe", "Edge", "profile"},
                {"C:/Program Files/Microsoft/Edge/Application/msedge.exe", "Edge", "profile"},
                {windir + "/notepad.exe", "记事本", "direct"},
                {"D:/Plain Craft Launcher 2/Plain Craft Launcher.exe", "PCL", "copy"},
                {"D:/PCL/Plain Craft Launcher.exe", "PCL", "copy"},
                {"D:/PCL2/Plain Craft Launcher.exe", "PCL", "copy"},
                {"C:/Program Files (x86)/Plain Craft Launcher 2/Plain Craft Launcher.exe", "PCL", "copy"},
        };
        java.util.Set<String> added = new java.util.HashSet<>();
        if (localAppData != null) {
            for (String[] cand : new String[][]{
                    {localAppData + "/Google/Chrome/Application/chrome.exe", "Chrome", "profile"},
                    {localAppData + "/Programs/WeChat/WeChat.exe", "微信", "copy"}}) {
                File f = new File(cand[0]);
                if (f.isFile()) {
                    boolean exists = cfg.apps.stream().anyMatch(a -> a.path.equalsIgnoreCase(f.getAbsolutePath()));
                    if (!exists) {
                        cfg.apps.add(new AppEntry(cand[1], f.getAbsolutePath(), cand[2], 2));
                        added.add(f.getAbsolutePath().toLowerCase());
                    }
                }
            }
        }
        for (String[] cand : candidates) {
            File f = new File(cand[0]);
            if (!f.isFile()) continue;
            if (added.contains(f.getAbsolutePath().toLowerCase())) continue;
            boolean exists = cfg.apps.stream().anyMatch(a -> a.path.equalsIgnoreCase(f.getAbsolutePath()));
            if (!exists) {
                cfg.apps.add(new AppEntry(cand[1], f.getAbsolutePath(), cand[2], 2));
            }
        }
    }

    private void showHelp() {
        JOptionPane.showMessageDialog(this,
                "通用多开器（Windows 版）v" + VERSION + "\n\n"
                + "【怎么用】\n"
                + "1. 列表自动检测微信、QQ、Chrome、Edge、记事本、PCL；\n"
                + "   没有的用「添加软件」自己选（任意 .exe）。\n"
                + "2. 双击单元格可改软件名、多开方式、实例数。\n"
                + "3. 点「多开所选」或「全部多开」启动多实例。\n\n"
                + "【多开方式（一种不行就换另一种）】\n"
                + "· 直接启动：普通启动，适合无单实例限制的软件\n"
                + "· 复制目录+改名：复制安装目录并把 exe 改名后启动，\n"
                + "  绕过按进程名检测单实例的软件（微信等）\n"
                + "· 数据目录隔离：给 Chrome/Edge 加 --user-data-dir，\n"
                + "  每个实例独立数据目录（Chromium 系软件专用）\n"
                + "· 互斥体绕过（内置）：挂起启动 → 注入自研组件 → 给互斥体\n"
                + "  改名，连互斥体级单实例（PCL2 等）都能多开，\n"
                + "  无需安装任何外部软件\n"
                + "· 沙盒多开：每个实例放独立 Sandboxie 沙盒，\n"
                + "  需要时自选（免费开源，sandboxie-plus.com）\n"
                + "· 智能多开：自动逐级尝试 直接启动 → 复制改名 → 互斥体绕过，\n"
                + "  这个不行就自动换下一个，几乎覆盖所有软件\n\n"
                + "【功能对照（安卓版 → Windows 版）】\n"
                + "· 一键冻结/解冻 → 挂起/恢复全部多开实例\n"
                + "· 定时冻结 → 每日到点自动挂起/恢复\n"
                + "· 克隆改名 → 实例自动编号（软件-1、软件-2）\n"
                + "· 检查更新 → 读取 GitHub Release 对比版本\n"
                + "· 深色主题 → 一键切换\n\n"
                + "【注意】\n"
                + "· 微信用「复制目录+改名」；Chrome/Edge 用「数据目录隔离」；\n"
                + "· PCL2 等互斥体级单实例软件用「互斥体绕过」（内置组件）；\n"
                + "· 个别安全软件可能拦截注入，出现拦截时把程序目录加入白名单；\n"
                + "· 复制目录时 .minecraft 等大目录会用链接共享，不整份复制。\n"
                + "· 「智能多开」会自动试完所有招数，最适合懒得选的情况。\n"
                + "· 多开账号请遵守软件官方使用条款。\n\n"
                + "完全开源（GPL-3.0）：https://github.com/helloppe/MultiOpener",
                "帮助", JOptionPane.INFORMATION_MESSAGE);
    }

    private void logLine(String msg) {
        SwingUtilities.invokeLater(() -> {
            String ts = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            log.append("[" + ts + "] " + msg + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        });
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
