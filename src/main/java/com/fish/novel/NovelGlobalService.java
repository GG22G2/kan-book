package com.fish.novel;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.components.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    // 章节缓存大小：当前章 + 前后各一章，留一个余量
    private static final int CHAPTER_CACHE_SIZE = 4;

    // ================= 状态数据 =================
    private LegadoUtil.Book currentBook;
    private List<LegadoUtil.Chapter> chapterList;

    // UI显示的核心数据 (volatile 保证多线程可见性)
    // currentContent 是当前章正文，currentTextIndex 是它内部的偏移
    private volatile String currentContent = "等待连接...";
    private volatile int currentChapterIndex = -1;
    private volatile int currentTextIndex = 0;

    // 章末无缝拼接：下一章的「分隔 + 标题」和正文，接在 displayContent 后面
    private volatile String nextChapterHeader = "";
    private volatile String nextChapterBody = "";
    private volatile int nextChapterIndex = -1;
    // 渲染实际读取的内容 = currentContent + 拼接块
    private volatile String displayContent = "等待连接...";

    private volatile boolean isLoading = false;
    private volatile boolean isError = false;

    // 最近几章的正文，来回翻章节时不重复请求（LRU，按访问顺序淘汰）
    private final Map<Integer, String> chapterCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, String> eldest) {
                    return size() > CHAPTER_CACHE_SIZE;
                }
            });
    // 正在预取中的章节号，避免滚轮连续触发时重复请求
    private final Set<Integer> prefetching = ConcurrentHashMap.newKeySet();

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
            // 换书/重连时丢掉旧缓存。所有任务都在同一个线程上串行，所以清空一定排在旧预取之后
            chapterCache.clear();
            prefetching.clear();
            try {
                Optional<LegadoUtil.Book> bookOpt = LegadoUtil.findBookByName(bookName);
                if (bookOpt.isPresent()) {
                    currentBook = bookOpt.get();
                    chapterList = LegadoUtil.getChapterList(currentBook);

                    currentChapterIndex = currentBook.durChapterIndex();
                    currentTextIndex = currentBook.durChapterPos();

                    loadChapterContent(currentChapterIndex, currentBook.durChapterPos());
                } else {
                    updateStatus("未找到书籍: " + bookName, true);
                }
            } catch (Exception e) {
                updateStatus("连接错误: " + e.getMessage(), true);
            }
        });
    }

    /**
     * 渲染实际读取的内容：当前章 + 已经拼好的下一章（章末不会半屏空白）
     */
    public String getContent() {
        return displayContent;
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
            if (chapterList == null || currentChapterIndex >= chapterList.size() - 1) {
                currentTextIndex = currentContent.length();
                notifyUI();
                return;
            }
            if (nextChapterIndex == currentChapterIndex + 1 && !nextChapterBody.isEmpty()) {
                switchToNextChapter(index);
                return;
            }
            // 下一章正文还没备好：退回原来的异步加载（会先跳到下一章开头）
            forceSaveImmediately();
            currentChapterIndex++;
            currentTextIndex = 0;
            loadChapterContent(currentChapterIndex, false);
            debounceSaveProgress();
        } else if (index < 0) {
            if (chapterList == null || currentChapterIndex <= 0) {
                currentTextIndex = 0;
                notifyUI();
                return;
            }
            if (switchToPreviousChapter(index)) {
                return;
            }
            forceSaveImmediately();
            currentChapterIndex--;
            loadChapterContent(currentChapterIndex, true);
            debounceSaveProgress();
        } else {
            currentTextIndex = index;
            notifyUI();
            debounceSaveProgress();
        }
    }

    /**
     * 无缝翻到下一章：index 是越过本章末尾的偏移，扣掉标题块长度就是下一章内部的位置，
     * 屏幕上的文字正好接上，不重复也不丢字
     */
    private void switchToNextChapter(int index) {
        int spill = index - currentContent.length() - nextChapterHeader.length();
        forceSaveImmediately();

        currentChapterIndex = nextChapterIndex;
        currentContent = nextChapterBody;
        currentTextIndex = Math.max(0, spill);
        chapterCache.put(currentChapterIndex, currentContent);

        isError = false;
        isLoading = false;
        refreshNextChapterBlock();
        notifyUI();
        debounceSaveProgress();
        prefetchChapter(currentChapterIndex - 1);
    }

    /**
     * 无缝翻回上一章（上一章正文得在缓存里）：index 是负数，表示从章首又往上翻了多少，
     * 落点就是「上一章长度 - 这个值」
     */
    private boolean switchToPreviousChapter(int index) {
        String previousContent = chapterCache.get(currentChapterIndex - 1);
        if (previousContent == null) {
            return false;
        }

        int overshoot = -index;
        forceSaveImmediately();

        currentChapterIndex--;
        currentContent = previousContent;
        currentTextIndex = Math.max(0, previousContent.length() - overshoot);

        isError = false;
        isLoading = false;
        refreshNextChapterBlock();
        notifyUI();
        debounceSaveProgress();
        prefetchChapter(currentChapterIndex - 1);
        return true;
    }

    // ================= 内部逻辑 =================

    // startOffset 传这个值表示「落到章末」（用于从下一章往回翻）
    private static final int JUMP_TO_END = -1;

    /**
     * 加载章节内容：缓存里有就直接用，不发请求
     * @param chapterIndex 章节索引
     * @param startOffset 加载完成后停在章内的哪个字符位置
     */
    private void loadChapterContent(int chapterIndex, int startOffset) {
        if (currentBook == null || chapterList == null) {
            return;
        }
        String title = chapterIndex >= 0 && chapterIndex < chapterList.size()
                ? chapterList.get(chapterIndex).title()
                : "";

        if (!chapterCache.containsKey(chapterIndex)) {
            updateStatus("正在加载: " + title + "...", false);
        }

        LegadoUtil.Book book = currentBook;
        scheduler.submit(() -> {
            String cached = chapterCache.get(chapterIndex);
            if (cached != null) {
                applyChapterContent(chapterIndex, cached, startOffset);
                return;
            }

            Optional<LegadoUtil.ChapterContent> contentOpt = LegadoUtil.getBookContent(book, chapterIndex);
            if (contentOpt.isPresent()) {
                String text = contentOpt.get().content();
                if (text != null) {
                    chapterCache.put(chapterIndex, text);
                }
                applyChapterContent(chapterIndex, text, startOffset);
            } else {
                updateStatus("加载失败，滚动重试", true);
            }
        });
    }

    private void loadChapterContent(int chapterIndex, boolean jumpToEnd) {
        loadChapterContent(chapterIndex, jumpToEnd ? JUMP_TO_END : 0);
    }

    private void applyChapterContent(int chapterIndex, String text, int startOffset) {
        if (text == null) {
            text = "本章无内容";
        }

        currentContent = text;
        isError = false;
        isLoading = false;

        if (startOffset == JUMP_TO_END) {
            currentTextIndex = Math.max(0, text.length() - 1);
        } else {
            currentTextIndex = Math.min(Math.max(0, startOffset), Math.max(0, text.length() - 1));
        }

        refreshNextChapterBlock();
        notifyUI();
        // 当前章就位后前后各预取一章：下一章用于章末无缝拼接，上一章用于往回翻
        prefetchChapter(chapterIndex - 1);
        prefetchChapter(chapterIndex + 1);
    }

    /**
     * 预取一章进缓存：已缓存、越界、正在拉取中的都直接跳过
     */
    private void prefetchChapter(int chapterIndex) {
        LegadoUtil.Book book = currentBook;
        if (book == null || chapterList == null) {
            return;
        }
        if (chapterIndex < 0 || chapterIndex >= chapterList.size()) {
            return;
        }
        if (chapterCache.containsKey(chapterIndex) || !prefetching.add(chapterIndex)) {
            return;
        }

        scheduler.submit(() -> {
            try {
                LegadoUtil.getBookContent(book, chapterIndex).ifPresent(content -> {
                    String text = content.content();
                    if (text != null) {
                        chapterCache.put(chapterIndex, text);
                        if (chapterIndex == currentChapterIndex + 1) {
                            // 正是要拼在章末的下一章：拼上并刷新一次
                            refreshNextChapterBlock();
                            notifyUI();
                        }
                    }
                });
            } finally {
                prefetching.remove(chapterIndex);
            }
        });
    }

    /**
     * 重建「下一章」拼接块：当前章 + 标题 + 下一章正文，让章末和正常翻页一样满。
     * 下一章正文还没到就先不拼，等预取回来再补（预取完成时会再调一次）
     */
    private void refreshNextChapterBlock() {
        if (chapterList == null || currentChapterIndex < 0 || currentChapterIndex >= chapterList.size() - 1) {
            clearNextChapterBlock();
            return;
        }

        int nextIndex = currentChapterIndex + 1;
        String nextContent = chapterCache.get(nextIndex);
        if (nextContent == null) {
            clearNextChapterBlock();
            prefetchChapter(nextIndex);
            return;
        }

        nextChapterIndex = nextIndex;
        nextChapterHeader = buildChapterHeader(chapterList.get(nextIndex).title());
        nextChapterBody = nextContent;
        composeDisplay();
    }

    private void clearNextChapterBlock() {
        nextChapterIndex = -1;
        nextChapterHeader = "";
        nextChapterBody = "";
        composeDisplay();
    }

    /**
     * 标题单独占一段：前后各留空行，段内用破折号把标题和正文分开
     */
    private static String buildChapterHeader(String title) {
        String name = (title == null || title.isBlank()) ? "下一章" : title.trim();
        return "\n\n　　—— " + name + " ——\n\n";
    }

    private void composeDisplay() {
        StringBuilder builder = new StringBuilder(currentContent);
        if (nextChapterIndex == currentChapterIndex + 1 && !nextChapterBody.isEmpty()) {
            builder.append(nextChapterHeader).append(nextChapterBody);
        }
        displayContent = builder.toString();
    }

    private void updateStatus(String message, boolean error) {
        currentContent = message;
        clearNextChapterBlock();
        isLoading = !error;
        isError = error;
        if (error) {
            currentTextIndex = 0;
        }
        notifyUI();
    }

    /**
     * 防抖保存策略：
     * 翻页只是重置计时器（上一个待执行任务会被取消），
     * 停止滚动 30 秒后才真正发送请求。
     * 切章和退出时会走 forceSaveImmediately() 立即落盘
     */
    private void debounceSaveProgress() {
        if (currentBook == null) {
            return;
        }

        if (pendingSaveTask != null && !pendingSaveTask.isDone()) {
            pendingSaveTask.cancel(false);
        }

        pendingSaveTask = scheduler.schedule(this::doSaveNetworkRequest, 30, TimeUnit.SECONDS);
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