package com.fish.novel;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.markup.TextAttributes;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

public class NovelInlayRenderer implements EditorCustomElementRenderer {

    private String rawText;

    // 统一配置
    public static final int PADDING_LEFT = 10;

    // 向上翻页时每行最多回看的字符数
    private static final int SCAN_LIMIT_PER_ROW = 100;

    // 注释的高亮 key：插入点落在这些 token 里就认为小说处在注释中
    private static final TextAttributesKey[] COMMENT_KEYS = {
            DefaultLanguageHighlighterColors.LINE_COMMENT,
            DefaultLanguageHighlighterColors.BLOCK_COMMENT,
            DefaultLanguageHighlighterColors.DOC_COMMENT
    };

    public NovelInlayRenderer(String text) {
        this.rawText = text;
    }

    /**
     * 翻页时复用同一个 inlay，只换文本，避免销毁重建
     */
    public void setText(String text) {
        this.rawText = text;
    }

    @Override
    public int calcWidthInPixels(@NotNull Inlay inlay) {
        return getViewportWidth();
    }

    @Override
    public int calcHeightInPixels(@NotNull Inlay inlay) {
        return inlay.getEditor().getLineHeight();
    }

    @Override
    public void paint(@NotNull Inlay inlay, @NotNull Graphics2D g, @NotNull Rectangle2D targetRegion, @NotNull TextAttributes textAttributes) {
        String text = rawText;
        Editor editor = inlay.getEditor();
        Font font = getSmartFont(editor, text);
        g.setFont(font);

        Color color = getContextColor(editor, inlay.getOffset());
        g.setColor(color);

        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        FontMetrics metrics = g.getFontMetrics(font);
        // 用浮点坐标，高 DPI 缩放下基线不会因为取整而抖
        float baseline = (float) (targetRegion.getY() + metrics.getAscent()
                + (targetRegion.getHeight() - metrics.getHeight()) / 2.0);

        // 核心：按段落排版，取第一行
        List<String> rows = layout(text, metrics, getTextWidth(), 1).rows();
        if (!rows.isEmpty()) {
            g.drawString(rows.get(0), (float) targetRegion.getX() + PADDING_LEFT, baseline);
        }
    }

    /**
     * 一屏排版结果：rows 是最多 maxRows 行的内容，consumedChars 是这些行用掉了源文本的前多少个字符
     * （换行符不画出来，但偏移必须算进去，否则翻页会错位）
     */
    public record PageLayout(List<String> rows, int consumedChars) {}

    /**
     * 核心算法：Handler 和 Renderer 共享的真理来源。
     * 按段落排版：每个段落都从新的一行开始，段内再按阅读区宽度贪心折行，
     * 段落之间不接力，所以段尾不满的那一行不会被下一段填进来
     */
    public static PageLayout layout(String text, FontMetrics metrics, int widthLimit, int maxRows) {
        List<String> rows = new ArrayList<>();
        int length = text == null ? 0 : text.length();
        if (length == 0 || maxRows <= 0) {
            return new PageLayout(rows, 0);
        }

        int index = 0;
        int consumed = 0;
        while (index < length && rows.size() < maxRows) {
            int paragraphEnd = index;
            while (paragraphEnd < length && !isLineBreak(text.charAt(paragraphEnd))) {
                paragraphEnd++;
            }

            int rowStart = index;
            while (rowStart < paragraphEnd && rows.size() < maxRows) {
                int width = 0;
                int rowEnd = rowStart;
                while (rowEnd < paragraphEnd) {
                    int charWidth = metrics.charWidth(text.charAt(rowEnd));
                    if (rowEnd > rowStart && width + charWidth > widthLimit) {
                        break;
                    }
                    width += charWidth;
                    rowEnd++;
                }
                rows.add(text.substring(rowStart, rowEnd));
                rowStart = rowEnd;
            }
            consumed = rowStart;

            if (rowStart < paragraphEnd) {
                break;                      // 这一屏装不下整段，剩下的下一屏接着排
            }
            index = paragraphEnd;
            while (index < length && isLineBreak(text.charAt(index))) {
                index++;                    // 换行和空行不占行，但要算进偏移
            }
            consumed = index;
        }
        return new PageLayout(rows, consumed);
    }

    /**
     * 一整页（maxRows 行）能显示的字符数，滚轮向下翻页使用
     */
    public static int calculatePageLength(String text, FontMetrics metrics, int widthLimit, int maxRows) {
        return layout(text, metrics, widthLimit, maxRows).consumedChars();
    }

    private static boolean isLineBreak(char c) {
        return c == '\n' || c == '\r';
    }

    /**
     * 滚轮向上翻页的字符数：从 index 向前凑满 maxRows 行
     */
    public static int calculateBackwardLength(String text, int index, FontMetrics metrics, int widthLimit, int maxRows) {
        if (text == null || index <= 0 || maxRows <= 0) {
            return 0;
        }

        int rows = 1;
        int rowWidth = 0;
        int length = 0;
        // 每行最多回看这么多字符：按阅读区宽度取上限，字体很小时一行能放的字也可能超过 100
        int scanLimit = maxRows * Math.max(SCAN_LIMIT_PER_ROW, widthLimit);
        for (int i = index - 1; i >= 0 && length < scanLimit; i--) {
            char c = text.charAt(i);
            if (isLineBreak(c)) {
                // 段落边界：当前行到此为止，往上换新的一行，和往下翻的切分保持一致
                if (rowWidth > 0) {
                    rows++;
                    if (rows > maxRows) {
                        break;
                    }
                    rowWidth = 0;
                }
                length++;
                continue;
            }

            int charWidth = metrics.charWidth(c);
            if (rowWidth > 0 && rowWidth + charWidth > widthLimit) {
                rows++;
                if (rows > maxRows) {
                    break;
                }
                rowWidth = 0;
            }
            rowWidth += charWidth;
            length++;
        }
        return length;
    }

    public static Font getSmartFont(Editor editor, String sampleText) {
        Font codeFont = editor.getColorsScheme().getFont(EditorFontType.PLAIN);
        int configuredSize = NovelConfig.getInstance().getRendererFontSize();
        int fontSize = configuredSize > 0 ? configuredSize : codeFont.getSize();
        Font preferredFont = codeFont.deriveFont((float) fontSize);
        String testStr = (sampleText == null || sampleText.length() < 2) ? "测试" : sampleText;
        if (preferredFont.canDisplayUpTo(testStr) == -1) {
            return preferredFont;
        } else {
            return new Font("Dialog", Font.PLAIN, fontSize);
        }
    }

    public static int getViewportWidth() {
        return Math.max(PADDING_LEFT + 20, NovelConfig.getInstance().getViewportWidth());
    }

    public static int getTextWidth() {
        return getViewportWidth() - PADDING_LEFT;
    }

    public static int getLineCount() {
        return Math.max(1, NovelConfig.getInstance().getRenderLineCount());
    }

    /**
     * 插入点落在注释里就用注释色（整块看起来还是注释），否则用编辑器默认前景色。
     * 不能直接拿前一个字符的颜色：空行/行首会继承上一行末尾 token 的颜色（上一行是字符串就会变绿）
     */
    private Color getContextColor(Editor editor, int offset) {
        try {
            CharSequence chars = editor.getDocument().getCharsSequence();
            if (editor instanceof EditorEx ex && !chars.isEmpty()) {
                int probe = Math.min(Math.max(offset, 0), chars.length() - 1);
                char probeChar = chars.charAt(probe);
                if (probe > 0 && (probeChar == '\n' || probeChar == '\r')) {
                    // 插入点在行尾或空行：退一格看前一个字符，块注释里的换行本身就属于注释
                    probe--;
                }
                TextAttributes attr = ex.getHighlighter().createIterator(probe).getTextAttributes();
                if (attr != null && attr.getForegroundColor() != null && isCommentToken(editor, attr)) {
                    return attr.getForegroundColor();
                }
            }
            Color defaultForeground = editor.getColorsScheme().getDefaultForeground();
            if (defaultForeground != null) {
                return defaultForeground;
            }
        } catch (Exception ignored) {}
        return Color.GRAY;
    }

    private static boolean isCommentToken(Editor editor, TextAttributes attr) {
        EditorColorsScheme scheme = editor.getColorsScheme();
        for (TextAttributesKey key : COMMENT_KEYS) {
            TextAttributes commentAttributes = scheme.getAttributes(key);
            if (commentAttributes != null && commentAttributes.equals(attr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 触发点落在跨行的块注释里时返回注释结束的偏移，用于把行数限制在注释内。
     * 只有行注释、代码、以及只占一行的块注释都返回 -1（不限制，避免只显示一行）。
     * 判断按 token 文本做（真块注释一定以斜杠星号开头、以星号斜杠结尾），
     * 不能按高亮属性判断 —— 很多配色里行注释和块注释的属性完全相同
     */
    public static int getBlockCommentEnd(Editor editor, int offset) {
        try {
            if (!(editor instanceof EditorEx ex)) {
                return -1;
            }
            CharSequence chars = editor.getDocument().getCharsSequence();
            if (offset < 0 || offset >= chars.length()) {
                return -1;
            }
            var tokens = ex.getHighlighter().createIterator(offset);
            int start = tokens.getStart();
            int end = tokens.getEnd();
            if (!isBlockCommentText(chars, start, end)) {
                return -1;
            }
            if (editor.getDocument().getLineNumber(end - 1) <= editor.getDocument().getLineNumber(offset)) {
                return -1;
            }
            return end;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static boolean isBlockCommentText(CharSequence chars, int start, int end) {
        return end - start >= 4
                && chars.charAt(start) == '/' && chars.charAt(start + 1) == '*'
                && chars.charAt(end - 2) == '*' && chars.charAt(end - 1) == '/';
    }
}
