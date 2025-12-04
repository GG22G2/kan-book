package com.fish.novel;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * 核心服务：增加防抖保存机制 (Debounce Saving)
 */
@Service(Service.Level.APP)
public final class NovelGlobalService implements Disposable {

    public static final Key<Disposable> HANDLER_KEY = Key.create("NovelHandler");

    // ================= 状态数据 =================
    private LegadoUtil.Book currentBook;
    private List<LegadoUtil.Chapter> chapterList;

    // UI显示的核心数据 (volatile 保证多线程可见性)
    private volatile String currentContent = "等待连接...";
    private volatile int currentChapterIndex = -1;
    private volatile int currentTextIndex = 0; // 章节内精确进度

    private volatile boolean isLoading = false;
    private volatile boolean isError = false;

    // ================= 任务调度器 (核心修改) =================
    // 单线程调度器，用于执行后台网络请求和定时任务
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    // 待执行的保存任务（用于防抖）
    private ScheduledFuture<?> pendingSaveTask;
    // 观察者列表
    private final List<Runnable> uiListeners = new CopyOnWriteArrayList<>();

    public static NovelGlobalService getInstance() {
        return ((ComponentManager)ApplicationManager.getApplication()).getService(NovelGlobalService.class);
    }

    // ================= 外部调用接口 =================

    public void ensureConnect() {
        if (currentBook == null && !isLoading) {
            reload();
        }
    }

    public void reload() {
        String bookName = NovelConfig.getInstance().bookName;
        String url = NovelConfig.getInstance().legadoUrl;

        if (bookName == null || bookName.isEmpty() || url == null || url.isEmpty()) {
            updateStatus("请在 Settings 中配置 Legado URL 和 书名", true);
            return;
        }

        updateStatus("正在连接服务器获取《" + bookName + "》...", false);

        scheduler.submit(() -> {
            try {
                Optional<LegadoUtil.Book> bookOpt = LegadoUtil.findBookByName(bookName);
                if (bookOpt.isPresent()) {
                    currentBook = bookOpt.get();
                    chapterList = LegadoUtil.getChapterList(currentBook);

                    // 1. 恢复进度 ( Legado 的 durChapterPos 就是章节内的字符偏移量 )
                    currentChapterIndex = currentBook.durChapterIndex();
                    currentTextIndex = currentBook.durChapterPos();

                    // 2. 加载内容
                    loadChapterContent(currentChapterIndex);
                } else {
                    updateStatus("未找到书籍: " + bookName, true);
                }
            } catch (Exception e) {
                updateStatus("连接错误: " + e.getMessage(), true);
            }
        });
    }

    public String getContent() { return currentContent; }
    public int getIndex() { return currentTextIndex; }

    /**
     * 核心交互入口：处理滚动
     */
    public void setIndex(int index) {
        if (isLoading) return;
        if (isError) { reload(); return; }

        if (index >= currentContent.length()) {
            // --- 下一章 ---
            if (chapterList != null && currentChapterIndex < chapterList.size() - 1) {
                forceSaveImmediately();
                currentChapterIndex++;
                currentTextIndex = 0; // 下一章从头开始
                loadChapterContent(currentChapterIndex, false); // false = 不跳到末尾
                debounceSaveProgress();
            } else {
                currentTextIndex = currentContent.length();
                notifyUI();
            }
        } else if (index < 0) {
            // --- 上一章 ---
            if (chapterList != null && currentChapterIndex > 0) {
                forceSaveImmediately();
                currentChapterIndex--;
                // ⚠️ 修复点：加载上一章，并标记加载完跳转到末尾
                loadChapterContent(currentChapterIndex, true);
                debounceSaveProgress();
            } else {
                currentTextIndex = 0;
                notifyUI();
            }
        } else {
            // --- 章节内 ---
            this.currentTextIndex = index;
            notifyUI();
            debounceSaveProgress();
        }
    }

    // ================= 内部逻辑 =================

    private void loadChapterContent(int chapterIndex) {
        loadChapterContent(chapterIndex, false);
    }

    /**
     * 加载章节内容
     * @param chapterIndex 章节索引
     * @param jumpToEnd 加载完成后是否跳转到章节末尾（用于从下一章翻回来）
     */
    private void loadChapterContent(int chapterIndex, boolean jumpToEnd) {
        if (currentBook == null || chapterList == null) return;
        String title = (chapterIndex >= 0 && chapterIndex < chapterList.size())
                ? chapterList.get(chapterIndex).title() : "";

        updateStatus("正在加载: " + title + "...", false);

        scheduler.submit(() -> {
            Optional<LegadoUtil.ChapterContent> contentOpt = LegadoUtil.getBookContent(currentBook, chapterIndex);
            if (contentOpt.isPresent()) {
                String text = contentOpt.get().content();
                if (text == null) text = "本章无内容";

                this.currentContent = text;
                this.isError = false;
                this.isLoading = false;

                // ⚠️ 修复点：根据 flag 决定定位到开头还是末尾
                if (jumpToEnd) {
                    // 跳转到末尾（为了视觉连贯，通常定位到最后能显示的一屏位置，但简单起见先指到最后）
                    // 渲染器会自动处理边界，这里设为 length 即可，或者 length - 1
                    this.currentTextIndex = Math.max(0, text.length() - 1);
                } else {
                    this.currentTextIndex = 0;
                }

                // 再次检查越界（防止 jumpToEnd 计算有误或 text 为空）
                if (currentTextIndex >= text.length()) currentTextIndex = Math.max(0, text.length() - 1);

                notifyUI();
            } else {
                updateStatus("加载失败，滚动重试", true);
            }
        });
    }

    private void updateStatus(String msg, boolean error) {
        this.currentContent = msg;
        this.isLoading = !error;
        this.isError = error;
        // status 更新时归零 index，避免渲染越界
        if (error) this.currentTextIndex = 0;
        notifyUI();
    }

    /**
     * 防抖保存策略：
     * 如果用户一直在滚动，不发送请求。
     * 当用户停止滚动 2 秒后，发送请求。
     */
    private void debounceSaveProgress() {
        if (currentBook == null) return;

        // 如果有之前没执行的任务，取消它
        if (pendingSaveTask != null && !pendingSaveTask.isDone()) {
            pendingSaveTask.cancel(false);
        }

        // 安排一个新的任务，2秒后执行
        pendingSaveTask = scheduler.schedule(this::doSaveNetworkRequest, 2, TimeUnit.SECONDS);
    }

    /**
     * 强制立即保存（用于切章、关闭IDE等场景）
     */
    private void forceSaveImmediately() {
        if (pendingSaveTask != null && !pendingSaveTask.isDone()) {
            pendingSaveTask.cancel(false);
        }
        // 提交到线程池立即执行
        scheduler.submit(this::doSaveNetworkRequest);
    }

    /**
     * 实际执行网络请求的方法
     * 注意：必须读取当前最新的状态值，不能传参(闭包问题)
     */
    private void doSaveNetworkRequest() {
        if (currentBook == null || chapterList == null) return;

        // 快照当前状态，防止发送过程中被修改
        int cIdx = currentChapterIndex;
        int tIdx = currentTextIndex;
        String title = (cIdx >= 0 && cIdx < chapterList.size()) ? chapterList.get(cIdx).title() : "";

        // Legado API: durChapterPos 对应章节内字符偏移
        LegadoUtil.saveProgress(currentBook, cIdx, tIdx, title);
    }

    // ================= UI通知 =================

    public void addUiListener(Runnable listener) { uiListeners.add(listener); }
    public void removeUiListener(Runnable listener) { uiListeners.remove(listener); }

    private void notifyUI() {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Runnable listener : uiListeners) listener.run();
        });
    }

    @Override
    public void dispose() {
        // 1. 立即强制保存
        forceSaveImmediately();

        // 2. 优雅关闭线程池
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }

        uiListeners.clear();
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
            Disposable handler = editor.getUserData(HANDLER_KEY);
            if (handler != null) {
                handler.dispose();
                editor.putUserData(HANDLER_KEY, null);
            }
        }
    }
}