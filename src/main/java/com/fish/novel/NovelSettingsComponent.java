package com.fish.novel;

import com.intellij.openapi.options.ConfigurableUi;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.util.ui.FormBuilder;

import javax.swing.*;
import java.net.URI;

public class NovelSettingsComponent implements ConfigurableUi<NovelConfig> {

    private final JPanel panel;
    private final JTextField urlField = new JTextField();
    private final JTextField bookNameField = new JTextField();
    private final JTextField matchTextField = new JTextField();
    private final JSpinner viewportWidthSpinner = new JSpinner(new SpinnerNumberModel(450, 120, 2400, 10));
    private final JSpinner fontSizeSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 72, 1));

    public NovelSettingsComponent() {
        configureSpinners();

        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("阅读(Legado) Web 服务 URL:", urlField, 1, false)
                .addLabeledComponent("书名:", bookNameField, 1, false)
                .addLabeledComponent("匹配关键词:", matchTextField, 1, false)
                .addLabeledComponent("阅读区宽度:", viewportWidthSpinner, 1, false)
                .addLabeledComponent("字体大小(0=跟随编辑器):", fontSizeSpinner, 1, false)
                .addComponent(new JLabel("提示：修改后会自动重载阅读内容"), 1)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    private void configureSpinners() {
        configureSpinner(viewportWidthSpinner, 6);
        configureSpinner(fontSizeSpinner, 4);
    }

    private void configureSpinner(JSpinner spinner, int columns) {
        JComponent editor = spinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor defaultEditor) {
            defaultEditor.getTextField().setColumns(columns);
        }
    }

    @Override
    public JPanel getComponent() {
        return panel;
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
        return urlField;
    }

    public String getLegadoUrl() {
        return urlField.getText();
    }

    public void setLegadoUrl(String legadoUrl) {
        urlField.setText(legadoUrl == null ? "" : legadoUrl);
    }

    public String getBookName() {
        return bookNameField.getText();
    }

    public void setBookName(String bookName) {
        bookNameField.setText(bookName == null ? "" : bookName);
    }

    public String getMatchPrefix() {
        return matchTextField.getText();
    }

    public void setMatchPrefix(String matchPrefix) {
        matchTextField.setText(matchPrefix == null ? "" : matchPrefix);
    }

    public int getViewportWidth() {
        return ((Number) viewportWidthSpinner.getValue()).intValue();
    }

    public void setViewportWidth(int viewportWidth) {
        viewportWidthSpinner.setValue(Math.max(120, Math.min(2400, viewportWidth)));
    }

    public int getFontSize() {
        return ((Number) fontSizeSpinner.getValue()).intValue();
    }

    public void setFontSize(int fontSize) {
        fontSizeSpinner.setValue(Math.max(0, Math.min(72, fontSize)));
    }

    @Override
    public void reset(NovelConfig settings) {
        setLegadoUrl(settings.getLegadoUrl());
        setBookName(settings.getBookName());
        setMatchPrefix(settings.getMatchPrefix());
        setViewportWidth(settings.getViewportWidth());
        setFontSize(settings.getRendererFontSize());
    }

    @Override
    public boolean isModified(NovelConfig settings) {
        return !normalize(getLegadoUrl()).equals(settings.getLegadoUrl()) ||
                !normalize(getBookName()).equals(settings.getBookName()) ||
                !normalize(getMatchPrefix()).equals(settings.getMatchPrefix()) ||
                getViewportWidth() != settings.getViewportWidth() ||
                getFontSize() != settings.getRendererFontSize();
    }

    @Override
    public void apply(NovelConfig settings) throws ConfigurationException {
        String legadoUrl = normalize(getLegadoUrl());
        String bookName = normalize(getBookName());
        String matchPrefix = normalize(getMatchPrefix());
        int viewportWidth = getViewportWidth();
        int fontSize = getFontSize();

        validate(legadoUrl, bookName, matchPrefix, viewportWidth, fontSize);

        boolean contentSettingsChanged = !legadoUrl.equals(settings.getLegadoUrl()) ||
                !bookName.equals(settings.getBookName());
        boolean renderSettingsChanged = viewportWidth != settings.getViewportWidth() ||
                fontSize != settings.getRendererFontSize();

        settings.setLegadoUrl(legadoUrl);
        settings.setBookName(bookName);
        settings.setMatchPrefix(matchPrefix);
        settings.setViewportWidth(viewportWidth);
        settings.setRendererFontSize(fontSize);

        if (contentSettingsChanged) {
            NovelGlobalService.getInstance().reload();
        } else if (renderSettingsChanged) {
            NovelGlobalService.getInstance().requestUiRefresh();
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private void validate(String legadoUrl, String bookName, String matchPrefix, int viewportWidth, int fontSize) throws ConfigurationException {
        if (legadoUrl.isEmpty()) {
            throw new ConfigurationException("请填写 Legado Web 服务 URL");
        }
        if (bookName.isEmpty()) {
            throw new ConfigurationException("请填写书名");
        }
        if (matchPrefix.isEmpty()) {
            throw new ConfigurationException("请填写匹配关键词");
        }
        if (viewportWidth < 120 || viewportWidth > 2400) {
            throw new ConfigurationException("阅读区宽度需在 120 到 2400 之间");
        }
        if (fontSize < 0 || fontSize > 72) {
            throw new ConfigurationException("字体大小需在 0 到 72 之间，0 表示跟随编辑器");
        }

        String normalizedUrl = legadoUrl.startsWith("http://") || legadoUrl.startsWith("https://")
                ? legadoUrl
                : "http://" + legadoUrl;
        try {
            URI uri = new URI(normalizedUrl);
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new ConfigurationException("Legado Web 服务 URL 需要包含有效主机地址");
            }
        } catch (Exception e) {
            if (e instanceof ConfigurationException configurationException) {
                throw configurationException;
            }
            throw new ConfigurationException("Legado Web 服务 URL 格式不正确");
        }
    }
}
