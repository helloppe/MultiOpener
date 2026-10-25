package multiopener;

/** 一条要管理的软件条目 */
public class AppEntry {
    public String name;
    public String path;
    /** direct=直接启动, copy=复制目录启动 */
    public String mode;
    public int count = 2;

    public AppEntry(String name, String path, String mode, int count) {
        this.name = name;
        this.path = path;
        this.mode = mode;
        this.count = count;
    }
}
