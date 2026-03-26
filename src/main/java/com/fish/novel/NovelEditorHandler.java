package com.fish.novel;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
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
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;

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
    private Inlay<?> currentInlay;
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
        event.consume();

        String content = service.getContent();
        int currentIndex = service.getIndex();
        int rotation = event.getWheelRotation();

        int availableWidth = NovelInlayRenderer.getViewportWidth() - NovelInlayRenderer.PADDING_LEFT;
        String sample = content != null && content.length() > currentIndex + 10
                ? content.substring(currentIndex, currentIndex + 10)
                : "";
        Font font = NovelInlayRenderer.getSmartFont(editor, sample);
        FontMetrics metrics = editor.getContentComponent().getFontMetrics(font);

        int step;
        if (rotation > 0) {
            String segment = "";
            if (content != null && currentIndex < content.length()) {
                int end = Math.min(currentIndex + RENDER_BUFFER_SIZE, content.length());
                segment = content.substring(currentIndex, end);
            }
            step = NovelInlayRenderer.calculateFittingCount(segment, metrics, availableWidth);
            if (step == 0) {
                step = 1;
            }
        } else {
            if (currentIndex > 0) {
                int currentWidth = 0;
                int count = 0;
                for (int index = currentIndex - 1; index >= 0; index--) {
                    if (content == null) {
                        break;
                    }
                    char currentChar = content.charAt(index);
                    int charWidth = metrics.charWidth(currentChar);
                    if (currentWidth + charWidth > availableWidth) {
                        break;
                    }
                    currentWidth += charWidth;
                    count++;
                    if (count > RENDER_BUFFER_SIZE) {
                        break;
                    }
                }
                step = Math.max(1, count);
            } else {
                step = 1;
            }
        }

        service.setIndex(currentIndex + (rotation > 0 ? step : -step));
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
        disposeInlay();
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

        int end = Math.min(globalIndex + RENDER_BUFFER_SIZE, fullContent.length());
        String snippet = globalIndex < end ? fullContent.substring(globalIndex, end) : "";

        disposeInlay();
        if (currentTriggerOffset != -1 && currentTriggerOffset <= editor.getDocument().getTextLength()) {
            currentInlay = editor.getInlayModel().addInlineElement(
                    currentTriggerOffset,
                    true,
                    new NovelInlayRenderer(snippet)
            );
        } else {
            disable();
        }
    }

    private boolean isEditorFocused() {
        Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        Component contentComponent = editor.getContentComponent();
        return focusOwner == contentComponent
                || focusOwner != null && SwingUtilities.isDescendingFrom(focusOwner, contentComponent);
    }

    private void disposeInlay() {
        if (currentInlay != null) {
            if (currentInlay.isValid()) {
                currentInlay.dispose();
            }
            currentInlay = null;
        }
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
        disposeInlay();
    }
}