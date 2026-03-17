package com.fish.novel;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import javax.swing.SwingUtilities;

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
            Disposer.dispose(handler);
        }
    }

    public void initEditor(Editor editor) {
        VirtualFile virtualFile = editor.getVirtualFile();
        if (virtualFile == null || !virtualFile.getName().endsWith(".java")) return; // 仅限Java，可自行去掉限制
        if (editor.getUserData(NovelGlobalService.HANDLER_KEY) != null) return;

        NovelGlobalService service = NovelGlobalService.getInstance();
        NovelHandler handler = new NovelHandler(editor, service);
        Disposer.register(service, handler);
        handler.attach();
        editor.putUserData(NovelGlobalService.HANDLER_KEY, handler);
    }

    private static class NovelHandler implements Disposable {
        private final Editor editor;
        private final NovelGlobalService service;
        private final CaretListener caretListener;
        private final DocumentListener documentListener;
        private final MouseWheelListener mouseWheelListener;
        private final FocusAdapter focusListener;
        private final Runnable uiRefreshCallback;

        private boolean isActive = false;
        private boolean disposed = false;
        private Inlay<?> currentInlay = null;
        private int currentTriggerOffset = -1;

        //private static final String TRIGGER = "假如";
        private static final int RENDER_BUFFER_SIZE = 100; // 预读长度

        public NovelHandler(Editor editor, NovelGlobalService service) {
            this.editor = editor;
            this.service = service;

            this.uiRefreshCallback = this::updateDisplay;

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
            this.focusListener = new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    if (isActive) {
                        service.setFocusedUiListener(uiRefreshCallback);
                        updateDisplay();
                    }
                }

                @Override
                public void focusLost(FocusEvent e) {
                    service.clearFocusedUiListener(uiRefreshCallback);
                }
            };
        }

        private void attach() {
            editor.getCaretModel().addCaretListener(caretListener);
            editor.getDocument().addDocumentListener(documentListener);
            editor.getContentComponent().addFocusListener(focusListener);
        }

        private void handleMouseWheel(MouseWheelEvent e) {
            if (!isActive) return;
            e.consume();

            String content = service.getContent();
            int currentIndex = service.getIndex();
            int rot = e.getWheelRotation();

            // 准备计算环境
            int availableWidth = NovelInlayRenderer.getViewportWidth() - NovelInlayRenderer.PADDING_LEFT;
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
            String trigger = NovelConfig.getInstance().getMatchPrefix();
            if (editor.isDisposed() || editor.getDocument().isInBulkUpdate()) return;
            int offset = editor.getCaretModel().getOffset();
            if (offset < trigger.length()) { disable(); return; }

            try {
                String prevText = editor.getDocument().getText(new TextRange(offset - trigger.length(), offset));
                if (trigger.equals(prevText)) {
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
                if (isEditorFocused()) {
                    service.setFocusedUiListener(uiRefreshCallback);
                }
                service.ensureConnect();
                updateDisplay();
            }
        }

        private void disable() {
            if (isActive) {
                isActive = false;
                editor.getContentComponent().removeMouseWheelListener(mouseWheelListener);
                service.clearFocusedUiListener(uiRefreshCallback);
                currentTriggerOffset = -1;
                disposeInlay();
            }
        }

        private void updateDisplay() {
            if (ApplicationManager.getApplication().isDispatchThread()) {
                doUpdateDisplay();
            } else {
                ApplicationManager.getApplication().invokeLater(this::doUpdateDisplay);
            }
        }

        private void doUpdateDisplay() {
            if (editor.isDisposed() || !isActive || !isEditorFocused()) return;

            String full = service.getContent();
            int globalIndex = service.getIndex();

            if (full == null) full = "Loading...";
            if (globalIndex >= full.length()) globalIndex = Math.max(0, full.length() - 1);

            int end = Math.min(globalIndex + RENDER_BUFFER_SIZE, full.length());
            String snippet = (globalIndex < end) ? full.substring(globalIndex, end) : "";

            disposeInlay();
            if (currentTriggerOffset != -1 && currentTriggerOffset <= editor.getDocument().getTextLength()) {
                Inlay<?> inlay = editor.getInlayModel().addInlineElement(
                        currentTriggerOffset,
                        true,
                        new NovelInlayRenderer(snippet)
                );
                currentInlay = inlay;
            } else {
                disable();
            }
        }

        private boolean isEditorFocused() {
            Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            Component contentComponent = editor.getContentComponent();
            return focusOwner == contentComponent || (focusOwner != null && SwingUtilities.isDescendingFrom(focusOwner, contentComponent));
        }

        private void disposeInlay() {
            if (currentInlay != null) {
                if (currentInlay.isValid()) currentInlay.dispose();
                currentInlay = null;
            }
        }

        @Override
        public void dispose() {
            if (disposed) return;
            disposed = true;
            isActive = false;
            currentTriggerOffset = -1;
            editor.getCaretModel().removeCaretListener(caretListener);
            editor.getDocument().removeDocumentListener(documentListener);
            editor.getContentComponent().removeFocusListener(focusListener);
            editor.getContentComponent().removeMouseWheelListener(mouseWheelListener);
            service.clearFocusedUiListener(this.uiRefreshCallback);
            disposeInlay();
            if (!editor.isDisposed()) {
                editor.putUserData(NovelGlobalService.HANDLER_KEY, null);
            }
        }
    }
}
