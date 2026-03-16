package com.fish.novel;

import com.intellij.util.ui.FormBuilder;

import javax.swing.*;

public class NovelSettingsComponent {

    private final JPanel panel;
    private final JTextField urlField = new JTextField();
    private final JTextField bookNameField = new JTextField();
    private final JTextField matchTextField = new JTextField();

    public NovelSettingsComponent() {
        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("阅读(Legado) Web 服务 URL:", urlField, 1, false)
                .addLabeledComponent("书名:", bookNameField, 1, false)
                .addLabeledComponent("匹配关键词:", matchTextField, 1, false)
                .addComponent(new JLabel("提示：修改后会自动重载阅读内容"), 1)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    public JPanel getPanel() {
        return panel;
    }

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
}
