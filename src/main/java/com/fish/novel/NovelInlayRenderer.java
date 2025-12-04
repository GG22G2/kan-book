package com.fish.novel;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.openapi.editor.markup.TextAttributes;
import org.jetbrains.annotations.NotNull;

import java.awt.*;

public class NovelInlayRenderer implements EditorCustomElementRenderer {

    private final String rawText;

    // 统一配置
    public static final int VIEWPORT_WIDTH = 600;
    public static final int PADDING_LEFT = 10;

    public NovelInlayRenderer(String text) {
        this.rawText = text;
    }

    @Override
    public int calcWidthInPixels(@NotNull Inlay inlay) {
        return VIEWPORT_WIDTH;
    }

    @Override
    public int calcHeightInPixels(@NotNull Inlay inlay) {
        return inlay.getEditor().getLineHeight();
    }

    @Override
    public void paint(@NotNull Inlay inlay, @NotNull Graphics g, @NotNull Rectangle targetRegion, @NotNull TextAttributes textAttributes) {
        Editor editor = inlay.getEditor();
        Font font = getSmartFont(editor, rawText);
        g.setFont(font);

        Color color = getContextColor(editor, inlay.getOffset());
        g.setColor(color);

        if (g instanceof Graphics2D g2d) {
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        }

        FontMetrics metrics = g.getFontMetrics(font);
        int baseline = (int) (targetRegion.y + metrics.getAscent() + (targetRegion.height - metrics.getHeight()) / 2.0);

        // 核心：计算能画多少字
        int availableWidth = VIEWPORT_WIDTH - PADDING_LEFT;
        int fitCount = calculateFittingCount(rawText, metrics, availableWidth);

        String textToDraw = (fitCount < rawText.length()) ? rawText.substring(0, fitCount) : rawText;

        g.drawString(textToDraw, targetRegion.x + PADDING_LEFT, baseline);
    }

    /**
     * 核心算法：Handler 和 Renderer 共享的真理来源
     */
    public static int calculateFittingCount(String text, FontMetrics metrics, int widthLimit) {
        if (text == null || text.isEmpty()) return 0;
        int currentWidth = 0;
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int charW = metrics.charWidth(c);
            if (currentWidth + charW > widthLimit) break;
            currentWidth += charW;
            count++;
        }
        return count;
    }

    public static Font getSmartFont(Editor editor, String sampleText) {
        Font codeFont = editor.getColorsScheme().getFont(EditorFontType.PLAIN);
        String testStr = (sampleText == null || sampleText.length() < 2) ? "测试" : sampleText;
        if (codeFont.canDisplayUpTo(testStr) == -1) {
            return codeFont;
        } else {
            return new Font("Dialog", Font.PLAIN, codeFont.getSize());
        }
    }

    private Color getContextColor(Editor editor, int offset) {
        try {
            if (editor instanceof EditorEx ex) {
                EditorHighlighter highlighter = ex.getHighlighter();
                if (offset > 0) {
                    HighlighterIterator iterator = highlighter.createIterator(offset - 1);
                    TextAttributes attr = iterator.getTextAttributes();
                    if (attr != null && attr.getForegroundColor() != null) return attr.getForegroundColor();
                }
            }
        } catch (Exception ignored) {}
        return Color.GRAY;
    }
}