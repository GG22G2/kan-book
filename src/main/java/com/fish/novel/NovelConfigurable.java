package com.fish.novel;

import com.intellij.openapi.options.Configurable;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public class NovelConfigurable implements Configurable {

    private JTextField urlField;
    private JTextField bookNameField;
    private JTextField matchTextField;
    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Novel Reader";
    }


    @Override
    public @Nullable JComponent createComponent() {
        JPanel panel = new JPanel(new GridLayout(4, 1)); // 简单布局

        JPanel urlPanel = new JPanel(new BorderLayout());
        urlPanel.add(new JLabel("阅读(Legado) Web服务URL (e.g. 192.168.1.5:1122): "), BorderLayout.WEST);
        urlField = new JTextField();
        urlPanel.add(urlField, BorderLayout.CENTER);

        JPanel bookPanel = new JPanel(new BorderLayout());
        bookPanel.add(new JLabel("书名: "), BorderLayout.WEST);
        bookNameField = new JTextField();
        bookPanel.add(bookNameField, BorderLayout.CENTER);


        JPanel matchTextPanel = new JPanel(new BorderLayout());
        matchTextPanel.add(new JLabel("匹配关键词: "), BorderLayout.WEST);
        matchTextField = new JTextField();
        matchTextPanel.add(matchTextField, BorderLayout.CENTER);


        panel.add(urlPanel);
        panel.add(bookPanel);
        panel.add(matchTextPanel);
        panel.add(new JLabel("提示：修改后需在编辑器内滚动滚轮触发重载"));

        NovelConfig config = NovelConfig.getInstance();
        urlField.setText(config.legadoUrl);
        bookNameField.setText(config.bookName);
        matchTextField.setText(config.matchPrefix);
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(panel, BorderLayout.NORTH);
        return wrapper;
    }

    @Override
    public boolean isModified() {
        NovelConfig config = NovelConfig.getInstance();
        return !urlField.getText().equals(config.legadoUrl) ||
               !bookNameField.getText().equals(config.bookName) ||
        !matchTextField.getText().equals(config.matchPrefix);
    }

    @Override
    public void apply() {
        NovelConfig config = NovelConfig.getInstance();
        config.legadoUrl = urlField.getText();
        config.bookName = bookNameField.getText();
        config.matchPrefix = matchTextField.getText();
        // 配置修改后，强制 Service 重载
        NovelGlobalService.getInstance().reload();
    }
}