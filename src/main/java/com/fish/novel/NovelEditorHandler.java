package com.fish.novel;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.ArrayList;
import java.util.List;

final class NovelEditorHandler implements Disposable {

    private static final int RENDER_BUFFER_SIZE = 100;

    private final Editor editor;
    private final NovelGlobalService service;
    private final CaretListener caretListener;
    private final DocumentListener documentListener;
    private final MouseWheelListener mouseWheelListener;
    private final FocusAdapter focusListener;
    private final Runnable uiRefreshCallback;

    private boolean active;
    private boolean disposed;
    private final List<Inlay<?>> currentInlays = new ArrayList<>();
    private int currentTriggerOffset = -1;

    NovelEditorHandler(Editor editor, NovelGlobalService service) {
        this.editor = editor;
        this.service = service;
        this.uiRefreshCallback = this::updateDisplay;
        this.caretListener = new CaretListener() {
            @Override
            public void caretPositionChanged(@NotNull CaretEvent event) {
                checkCaret();
            }
        };
        this.documentListener = new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                checkCaret();
            }
        };
        this.mouseWheelListener = new MouseWheelListener() {
            @Override
            public void mouseWheelMoved(MouseWheelEvent event) {
                handleMouseWheel(event);
            }
        };
        this.focusListener = new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                if (active) {
                    service.setFocusedUiListener(uiRefreshCallback);
                    updateDisplay();
                }
            }

            @Override
            public void focusLost(FocusEvent event) {
                service.clearFocusedUiListener(uiRefreshCallback);
            }
        };
    }

    void attach() {
        if (disposed || editor.isDisposed()) {
            return;
        }
        editor.getCaretModel().addCaretListener(caretListener);
        editor.getDocument().addDocumentListener(documentListener);
        editor.getContentComponent().addFocusListener(focusListener);
        checkCaret();
    }

    private void handleMouseWheel(MouseWheelEvent event) {
        if (!active) {
            return;
        }
        if (event.isControlDown() || event.isAltDown() || event.isShiftDown() || event.isMetaDown()) {
            // 按住功能键时放行，编辑器仍可正常滚动 / 缩放字体，方便临时看一眼代码
            return;
        }
        event.consume();

        String content = service.getContent();
        int currentIndex = service.getIndex();
        int rotation = event.getWheelRotation();

        int rowCount = getRowCount();
        int textWidth = NovelInlayRenderer.getTextWidth();
        String sample = content != null && content.length() > currentIndex + 10
                ? content.substring(currentIndex, currentIndex + 10)
                : "";
        Font font = NovelInlayRenderer.getSmartFont(editor, sample);
        FontMetrics metrics = editor.getContentComponent().getFontMetrics(font);

        int step;
        if (rotation > 0) {
            String segment = "";
            if (content != null && currentIndex < content.length()) {
                int end = Math.min(currentIndex + getBufferSize(rowCount), content.length());
                segment = content.substring(currentIndex, end);
            }
            step = NovelInlayRenderer.calculatePageLength(segment, metrics, textWidth, rowCount);
            if (step == 0) {
                step = 1;
            }
        } else {
            step = Math.max(1, NovelInlayRenderer.calculateBackwardLength(content, currentIndex, metrics, textWidth, rowCount));
        }

        service.setIndex(currentIndex + (rotation > 0 ? step : -step));
    }

    /**
     * 每行至少要能装下阅读区宽度的字符数，避免字体很小时缓冲不够、一行画不全
     */
    private int getBufferSize(int rowCount) {
        return Math.max(RENDER_BUFFER_SIZE, NovelInlayRenderer.getTextWidth()) * rowCount;
    }

    private int getRowCount() {
        return getRowOffsets().size();
    }

    private boolean isFolded(int offset) {
        try {
            return editor.getFoldingModel().isOffsetCollapsed(offset);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void checkCaret() {
        String trigger = NovelConfig.getInstance().getMatchPrefix();
        if (disposed || editor.isDisposed() || editor.getDocument().isInBulkUpdate()) {
            return;
        }

        int offset = editor.getCaretModel().getOffset();
        if (offset < trigger.length()) {
            disable();
            return;
        }

        try {
            String previousText = editor.getDocument().getText(new TextRange(offset - trigger.length(), offset));
            if (trigger.equals(previousText)) {
                if (active && offset == currentTriggerOffset) {
                    return;
                }
                if (active && offset != currentTriggerOffset) {
                    disable();
                }
                currentTriggerOffset = offset;
                enable();
            } else {
                disable();
            }
        } catch (Exception ignored) {
            disable();
        }
    }

    private void enable() {
        if (active) {
            return;
        }
        active = true;
        editor.getContentComponent().addMouseWheelListener(mouseWheelListener);
        if (isEditorFocused()) {
            service.setFocusedUiListener(uiRefreshCallback);
        }
        service.ensureConnect();
        updateDisplay();
    }

    private void disable() {
        if (!active) {
            return;
        }
        active = false;
        editor.getContentComponent().removeMouseWheelListener(mouseWheelListener);
        service.clearFocusedUiListener(uiRefreshCallback);
        currentTriggerOffset = -1;
        disposeInlays();
    }

    private void updateDisplay() {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            doUpdateDisplay();
            return;
        }
        ApplicationManager.getApplication().invokeLater(this::doUpdateDisplay);
    }

    private void doUpdateDisplay() {
        if (disposed || editor.isDisposed() || !active || !isEditorFocused()) {
            return;
        }

        String fullContent = service.getContent();
        int globalIndex = service.getIndex();

        if (fullContent == null) {
            fullContent = "Loading...";
        }
        if (globalIndex >= fullContent.length()) {
            globalIndex = Math.max(0, fullContent.length() - 1);
        }

        List<Integer> offsets = getRowOffsets();
        if (offsets.isEmpty()) {
            disposeInlays();
            disable();
            return;
        }

        int end = Math.min(globalIndex + getBufferSize(offsets.size()), fullContent.length());
        String snippet = globalIndex < end ? fullContent.substring(globalIndex, end) : "";

        String sample = snippet.length() > 10 ? snippet.substring(0, 10) : snippet;
        Font font = NovelInlayRenderer.getSmartFont(editor, sample);
        FontMetrics metrics = editor.getContentComponent().getFontMetrics(font);
        List<String> rows = NovelInlayRenderer.layout(snippet, metrics, NovelInlayRenderer.getTextWidth(), offsets.size()).rows();

        if (canReuseInlays(offsets, rows)) {
            for (int row = 0; row < rows.size(); row++) {
                ((NovelInlayRenderer) currentInlays.get(row).getRenderer()).setText(rows.get(row));
                currentInlays.get(row).update();
            }
            return;
        }

        disposeInlays();
        for (int row = 0; row < rows.size(); row++) {
            Inlay<?> inlay = editor.getInlayModel().addInlineElement(
                    offsets.get(row),
                    true,
                    new NovelInlayRenderer(rows.get(row))
            );
            if (inlay != null) {
                currentInlays.add(inlay);
            }
        }
    }

    /**
     * 行数和插入点都没变时复用现成的 inlay，只换文本
     */
    private boolean canReuseInlays(List<Integer> offsets, List<String> rows) {
        if (currentInlays.size() != rows.size() || currentInlays.size() != offsets.size()) {
            return false;
        }
        for (int row = 0; row < currentInlays.size(); row++) {
            Inlay<?> inlay = currentInlays.get(row);
            if (!inlay.isValid()
                    || inlay.getOffset() != offsets.get(row)
                    || !(inlay.getRenderer() instanceof NovelInlayRenderer)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 每行小说占一个代码行：首行贴光标，后面几行贴在各自前缀之后并尽量对齐光标所在列。
     * 折叠起来的行不会被绘制，所以碰到折叠就停，避免翻页跳过看不见的内容
     */
    private List<Integer> getRowOffsets() {
        List<Integer> offsets = new ArrayList<>();
        Document document = editor.getDocument();
        if (currentTriggerOffset < 0 || currentTriggerOffset > document.getTextLength()) {
            return offsets;
        }
        offsets.add(currentTriggerOffset);

        int triggerLine = document.getLineNumber(currentTriggerOffset);
        int caretColumn = currentTriggerOffset - document.getLineStartOffset(triggerLine);
        int lastLine = document.getLineCount() - 1;
        int configured = NovelInlayRenderer.getLineCount();
        int commentEnd = NovelInlayRenderer.getBlockCommentEnd(editor, currentTriggerOffset);
        for (int line = triggerLine + 1; line <= lastLine && offsets.size() < configured; line++) {
            int offset = getRowInsertOffset(document, line, caretColumn);
            if (commentEnd >= 0 && offset >= commentEnd) {
                break;
            }
            if (isFolded(offset) || isBelowVisibleArea(offset)) {
                break;
            }
            offsets.add(offset);
        }
        return offsets;
    }

    /**
     * 行在可视区域下方时就停：看不见的行不该被翻页跳过。
     * 只用可见高度换算出能显示几行（只用长度、不用坐标，避免坐标系不一致时误判），
     * 而且触发行本身一定可见，所以从它往下数行数是可靠的
     */
    private boolean isBelowVisibleArea(int offset) {
        try {
            Rectangle visibleArea = editor.getScrollingModel().getVisibleArea();
            if (visibleArea == null || visibleArea.height <= 0) {
                return false;
            }
            int visibleLines = visibleArea.height / Math.max(1, editor.getLineHeight());
            if (visibleLines <= 0) {
                return false;
            }
            Document document = editor.getDocument();
            int triggerLine = document.getLineNumber(currentTriggerOffset);
            return document.getLineNumber(offset) - triggerLine >= visibleLines;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 插入点：注释行留在注释符号（// 或 *）之后并尽量对齐光标列，代码行留在缩进之后，
     * 这样每行的前缀不会被顶走，整块文字也是齐的
     */
    private int getRowInsertOffset(Document document, int line, int caretColumn) {
        int lineStart = document.getLineStartOffset(line);
        int lineEnd = document.getLineEndOffset(line);
        CharSequence chars = document.getCharsSequence();

        int contentStart = skipWhitespace(chars, lineStart, lineEnd);
        int prefixEnd = contentStart;
        if (startsWith(chars, prefixEnd, lineEnd, "//")) {
            prefixEnd = skipWhitespace(chars, prefixEnd + 2, lineEnd);
        } else if (startsWith(chars, prefixEnd, lineEnd, "/*")) {
            prefixEnd = skipWhitespace(chars, prefixEnd + 2, lineEnd);
            if (prefixEnd < lineEnd && chars.charAt(prefixEnd) == '*') {
                prefixEnd = skipWhitespace(chars, prefixEnd + 1, lineEnd);
            }
        } else if (prefixEnd < lineEnd && chars.charAt(prefixEnd) == '*'
                && (prefixEnd + 1 >= lineEnd || Character.isWhitespace(chars.charAt(prefixEnd + 1)))) {
            prefixEnd = skipWhitespace(chars, prefixEnd + 1, lineEnd);
        }

        int column = prefixEnd == contentStart
                ? contentStart - lineStart
                : Math.max(caretColumn, prefixEnd - lineStart);
        return Math.min(lineStart + column, lineEnd);
    }

    private int skipWhitespace(CharSequence chars, int offset, int limit) {
        while (offset < limit && Character.isWhitespace(chars.charAt(offset))) {
            offset++;
        }
        return offset;
    }

    private boolean startsWith(CharSequence chars, int offset, int limit, String prefix) {
        if (offset + prefix.length() > limit) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (chars.charAt(offset + i) != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private boolean isEditorFocused() {
        Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        Component contentComponent = editor.getContentComponent();
        return focusOwner == contentComponent
                || focusOwner != null && SwingUtilities.isDescendingFrom(focusOwner, contentComponent);
    }

    private void disposeInlays() {
        for (Inlay<?> inlay : currentInlays) {
            if (inlay.isValid()) {
                inlay.dispose();
            }
        }
        currentInlays.clear();
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        active = false;
        currentTriggerOffset = -1;

        if (!editor.isDisposed()) {
            editor.getCaretModel().removeCaretListener(caretListener);
            editor.getDocument().removeDocumentListener(documentListener);
            editor.getContentComponent().removeFocusListener(focusListener);
            editor.getContentComponent().removeMouseWheelListener(mouseWheelListener);
        }

        service.clearFocusedUiListener(uiRefreshCallback);
        disposeInlays();
    }
}