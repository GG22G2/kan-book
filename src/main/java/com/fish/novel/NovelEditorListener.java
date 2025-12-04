package com.fish.novel;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;

public class NovelEditorListener implements EditorFactoryListener {

    @Override
    public void editorCreated(@NotNull EditorFactoryEvent event) {
        initEditor(event.getEditor());
    }

    @Override
    public void editorReleased(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        Disposable handler = editor.getUserData(NovelGlobalService.HANDLER_KEY);
        if (handler != null) {
            handler.dispose();
            editor.putUserData(NovelGlobalService.HANDLER_KEY, null);
        }
    }

    public void initEditor(Editor editor) {
        VirtualFile virtualFile = editor.getVirtualFile();
        if (virtualFile == null || !virtualFile.getName().endsWith(".java")) return; // 仅限Java，可自行去掉限制
        if (editor.getUserData(NovelGlobalService.HANDLER_KEY) != null) return;

        NovelHandler handler = new NovelHandler(editor);
        editor.putUserData(NovelGlobalService.HANDLER_KEY, handler);
    }

    private static class NovelHandler implements Disposable {
        private final Editor editor;
        private final CaretListener caretListener;
        private final DocumentListener documentListener;
        private final MouseWheelListener mouseWheelListener;
        private final Runnable uiRefreshCallback;

        private boolean isActive = false;
        private Inlay<?> currentInlay = null;
        private int currentTriggerOffset = -1;

        //private static final String TRIGGER = "假如";
        private static final int RENDER_BUFFER_SIZE = 100; // 预读长度

        public NovelHandler(Editor editor) {
            this.editor = editor;

            // 1. Service 通知回调
            this.uiRefreshCallback = () -> {
                if (isActive && !editor.isDisposed()) updateDisplay();
            };
            NovelGlobalService.getInstance().addUiListener(this.uiRefreshCallback);

            // 2. 监听器
            this.caretListener = new CaretListener() {
                @Override
                public void caretPositionChanged(@NotNull CaretEvent e) { checkCaret(); }
            };
            this.documentListener = new DocumentListener() {
                @Override
                public void documentChanged(@NotNull DocumentEvent event) { checkCaret(); }
            };
            this.mouseWheelListener = new MouseWheelListener() {
                @Override
                public void mouseWheelMoved(MouseWheelEvent e) { handleMouseWheel(e); }
            };

            editor.getCaretModel().addCaretListener(caretListener);
            editor.getDocument().addDocumentListener(documentListener, this);
        }

        private void handleMouseWheel(MouseWheelEvent e) {
            if (!isActive) return;
            e.consume();

            NovelGlobalService service = NovelGlobalService.getInstance();
            String content = service.getContent();
            int currentIndex = service.getIndex();
            int rot = e.getWheelRotation();

            // 准备计算环境
            int availableWidth = NovelInlayRenderer.VIEWPORT_WIDTH - NovelInlayRenderer.PADDING_LEFT;
            String sample = (content != null && content.length() > currentIndex + 10)
                    ? content.substring(currentIndex, currentIndex + 10) : "";
            Font font = NovelInlayRenderer.getSmartFont(editor, sample);
            FontMetrics metrics = editor.getContentComponent().getFontMetrics(font);

            int step = 0;
            if (rot > 0) {
                // 下翻：计算从当前位置往后，多少字能填满一行
                String seg = "";
                if (content != null && currentIndex < content.length()) {
                    int end = Math.min(currentIndex + RENDER_BUFFER_SIZE, content.length());
                    seg = content.substring(currentIndex, end);
                }
                // 使用核心算法计算步长
                step = NovelInlayRenderer.calculateFittingCount(seg, metrics, availableWidth);
                if (step == 0) step = 1; // 防止死循环
            } else {
                // 上翻：倒序查找上一行起点
                if (currentIndex > 0) {
                    int currentW = 0;
                    int count = 0;
                    for (int i = currentIndex - 1; i >= 0; i--) {
                        if (content == null) break;
                        char c = content.charAt(i);
                        int charW = metrics.charWidth(c);
                        if (currentW + charW > availableWidth) break;
                        currentW += charW;
                        count++;
                        if (count > RENDER_BUFFER_SIZE) break;
                    }
                    step = Math.max(1, count);
                } else {
                    // ⚠️ 修复点：已经在开头了，强制步长为 1
                    // 这样 newIndex 就会变成 0 - 1 = -1，触发 Service 的“上一章”逻辑
                    step = 1;
                }
            }

            service.setIndex(currentIndex + (rot > 0 ? step : -step));
        }

        private void checkCaret() {
            String TRIGGER = NovelConfig.getInstance().matchPrefix;
            if (editor.isDisposed() || editor.getDocument().isInBulkUpdate()) return;
            int offset = editor.getCaretModel().getOffset();
            if (offset < TRIGGER.length()) { disable(); return; }

            try {
                String prevText = editor.getDocument().getText(new TextRange(offset - TRIGGER.length(), offset));
                if (TRIGGER.equals(prevText)) {
                    if (isActive && offset == currentTriggerOffset) return;
                    if (isActive && offset != currentTriggerOffset) disable();
                    currentTriggerOffset = offset;
                    enable();
                } else {
                    disable();
                }
            } catch (Exception e) { disable(); }
        }

        private void enable() {
            if (!isActive) {
                isActive = true;
                editor.getContentComponent().addMouseWheelListener(mouseWheelListener);
                NovelGlobalService.getInstance().ensureConnect(); // 懒加载触发点
                updateDisplay();
            }
        }

        private void disable() {
            if (isActive) {
                isActive = false;
                editor.getContentComponent().removeMouseWheelListener(mouseWheelListener);
                currentTriggerOffset = -1;
                disposeInlay();
            }
        }

        private void updateDisplay() {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (editor.isDisposed() || !isActive) return;

                NovelGlobalService service = NovelGlobalService.getInstance();
                String full = service.getContent();
                int globalIndex = service.getIndex();

                if (full == null) full = "Loading...";
                if (globalIndex >= full.length()) globalIndex = Math.max(0, full.length() - 1);

                int end = Math.min(globalIndex + RENDER_BUFFER_SIZE, full.length());
                String snippet = (globalIndex < end) ? full.substring(globalIndex, end) : "";

                WriteCommandAction.runWriteCommandAction(editor.getProject(), () -> {
                    disposeInlay();
                    // 再次校验 offset 依然有效
                    if (currentTriggerOffset != -1 && currentTriggerOffset <= editor.getDocument().getTextLength()) {
                        Inlay<?> inlay = editor.getInlayModel().addInlineElement(
                                currentTriggerOffset,
                                true,
                                new NovelInlayRenderer(snippet) // Renderer 会根据宽度自动截断
                        );
                        currentInlay = inlay;
                    } else {
                        disable();
                    }
                });
            });
        }

        private void disposeInlay() {
            if (currentInlay != null) {
                if (currentInlay.isValid()) currentInlay.dispose();
                currentInlay = null;
            }
        }

        @Override
        public void dispose() {
            editor.getCaretModel().removeCaretListener(caretListener);
            editor.getDocument().removeDocumentListener(documentListener);
            editor.getContentComponent().removeMouseWheelListener(mouseWheelListener);
            NovelGlobalService.getInstance().removeUiListener(this.uiRefreshCallback);
            disposeInlay();
        }
    }
}