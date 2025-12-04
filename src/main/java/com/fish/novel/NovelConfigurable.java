package com.fish.novel;

import com.intellij.openapi.options.Configurable;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public class NovelConfigurable implements Configurable {

    private JTextField urlField;
    private JTextField bookNameField;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Novel Reader";
    }

    @Override
    public @Nullable JComponent createComponent() {
        JPanel panel = new JPanel(new GridLayout(3, 1)); // 简单布局

        JPanel urlPanel = new JPanel(new BorderLayout());
        urlPanel.add(new JLabel("Legado URL (e.g. 192.168.1.5:1122): "), BorderLayout.WEST);
        urlField = new JTextField();
        urlPanel.add(urlField, BorderLayout.CENTER);

        JPanel bookPanel = new JPanel(new BorderLayout());
        bookPanel.add(new JLabel("Book Name: "), BorderLayout.WEST);
        bookNameField = new JTextField();
        bookPanel.add(bookNameField, BorderLayout.CENTER);

        panel.add(urlPanel);
        panel.add(bookPanel);
        panel.add(new JLabel("提示：修改后需在编辑器内滚动滚轮触发重载"));

        NovelConfig config = NovelConfig.getInstance();
        urlField.setText(config.legadoUrl);
        bookNameField.setText(config.bookName);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(panel, BorderLayout.NORTH);
        return wrapper;
    }

    @Override
    public boolean isModified() {
        NovelConfig config = NovelConfig.getInstance();
        return !urlField.getText().equals(config.legadoUrl) ||
               !bookNameField.getText().equals(config.bookName);
    }

    @Override
    public void apply() {
        NovelConfig config = NovelConfig.getInstance();
        config.legadoUrl = urlField.getText();
        config.bookName = bookNameField.getText();

        // 配置修改后，强制 Service 重载
        NovelGlobalService.getInstance().reload();
    }
}