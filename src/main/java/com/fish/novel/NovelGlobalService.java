package com.fish.novel;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.components.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 核心服务：增加防抖保存机制 (Debounce Saving)
 */
@Service(Service.Level.APP)
public final class NovelGlobalService implements Disposable {

    // ================= 状态数据 =================
    private LegadoUtil.Book currentBook;
    private List<LegadoUtil.Chapter> chapterList;

    // UI显示的核心数据 (volatile 保证多线程可见性)
    private volatile String currentContent = "等待连接...";
    private volatile int currentChapterIndex = -1;
    private volatile int currentTextIndex = 0;

    private volatile boolean isLoading = false;
    private volatile boolean isError = false;

    // ================= 任务调度器 (核心修改) =================
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> pendingSaveTask;
    private final AtomicReference<Runnable> focusedUiListener = new AtomicReference<>();

    public static NovelGlobalService getInstance() {
        ComponentManager application = (ComponentManager) ApplicationManager.getApplication();
        return application.getService(NovelGlobalService.class);
    }

    // ================= 外部调用接口 =================

    public void ensureConnect() {
        if (currentBook == null && !isLoading) {
            reload();
        }
    }

    public void reload() {
        NovelConfig config = NovelConfig.getInstance();
        String bookName = config.getBookName();
        String url = config.getLegadoUrl();

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

                    currentChapterIndex = currentBook.durChapterIndex();
                    currentTextIndex = currentBook.durChapterPos();

                    loadChapterContent(currentChapterIndex);
                } else {
                    updateStatus("未找到书籍: " + bookName, true);
                }
            } catch (Exception e) {
                updateStatus("连接错误: " + e.getMessage(), true);
            }
        });
    }

    public String getContent() {
        return currentContent;
    }

    public int getIndex() {
        return currentTextIndex;
    }

    /**
     * 核心交互入口：处理滚动
     */
    public void setIndex(int index) {
        if (isLoading) {
            return;
        }
        if (isError) {
            reload();
            return;
        }

        if (index >= currentContent.length()) {
            if (chapterList != null && currentChapterIndex < chapterList.size() - 1) {
                forceSaveImmediately();
                currentChapterIndex++;
                currentTextIndex = 0;
                loadChapterContent(currentChapterIndex, false);
                debounceSaveProgress();
            } else {
                currentTextIndex = currentContent.length();
                notifyUI();
            }
        } else if (index < 0) {
            if (chapterList != null && currentChapterIndex > 0) {
                forceSaveImmediately();
                currentChapterIndex--;
                loadChapterContent(currentChapterIndex, true);
                debounceSaveProgress();
            } else {
                currentTextIndex = 0;
                notifyUI();
            }
        } else {
            currentTextIndex = index;
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
        if (currentBook == null || chapterList == null) {
            return;
        }
        String title = chapterIndex >= 0 && chapterIndex < chapterList.size()
                ? chapterList.get(chapterIndex).title()
                : "";

        updateStatus("正在加载: " + title + "...", false);

        scheduler.submit(() -> {
            Optional<LegadoUtil.ChapterContent> contentOpt = LegadoUtil.getBookContent(currentBook, chapterIndex);
            if (contentOpt.isPresent()) {
                String text = contentOpt.get().content();
                if (text == null) {
                    text = "本章无内容";
                }

                currentContent = text;
                isError = false;
                isLoading = false;

                if (jumpToEnd) {
                    currentTextIndex = Math.max(0, text.length() - 1);
                } else {
                    currentTextIndex = 0;
                }

                if (currentTextIndex >= text.length()) {
                    currentTextIndex = Math.max(0, text.length() - 1);
                }

                notifyUI();
            } else {
                updateStatus("加载失败，滚动重试", true);
            }
        });
    }

    private void updateStatus(String message, boolean error) {
        currentContent = message;
        isLoading = !error;
        isError = error;
        if (error) {
            currentTextIndex = 0;
        }
        notifyUI();
    }

    /**
     * 防抖保存策略：
     * 如果用户一直在滚动，不发送请求。
     * 当用户停止滚动 2 秒后，发送请求。
     */
    private void debounceSaveProgress() {
        if (currentBook == null) {
            return;
        }

        if (pendingSaveTask != null && !pendingSaveTask.isDone()) {
            pendingSaveTask.cancel(false);
        }

        pendingSaveTask = scheduler.schedule(this::doSaveNetworkRequest, 2, TimeUnit.SECONDS);
    }

    /**
     * 强制立即保存（用于切章、关闭IDE等场景）
     */
    private void forceSaveImmediately() {
        if (pendingSaveTask != null && !pendingSaveTask.isDone()) {
            pendingSaveTask.cancel(false);
        }
        scheduler.submit(this::doSaveNetworkRequest);
    }

    /**
     * 实际执行网络请求的方法
     * 注意：必须读取当前最新的状态值，不能传参(闭包问题)
     */
    private void doSaveNetworkRequest() {
        if (currentBook == null || chapterList == null) {
            return;
        }

        int chapterIndex = currentChapterIndex;
        int textIndex = currentTextIndex;
        String title = chapterIndex >= 0 && chapterIndex < chapterList.size()
                ? chapterList.get(chapterIndex).title()
                : "";

        LegadoUtil.saveProgress(currentBook, chapterIndex, textIndex, title);
    }

    // ================= UI通知 =================

    public void setFocusedUiListener(Runnable listener) {
        focusedUiListener.set(listener);
    }

    public void clearFocusedUiListener(Runnable listener) {
        focusedUiListener.compareAndSet(listener, null);
    }

    public void requestUiRefresh() {
        notifyUI();
    }

    private void notifyUI() {
        Runnable listener = focusedUiListener.get();
        if (listener != null) {
            listener.run();
        }
    }

    @Override
    public void dispose() {
        forceSaveImmediately();

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }

        focusedUiListener.set(null);
    }
}