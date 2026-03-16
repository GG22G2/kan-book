package com.fish.novel;

import com.intellij.openapi.options.Configurable;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class NovelConfigurable implements Configurable {

    private NovelSettingsComponent settingsComponent;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Novel Reader";
    }

    @Override
    public @Nullable JComponent createComponent() {
        if (settingsComponent == null) {
            settingsComponent = new NovelSettingsComponent();
        }
        reset();
        return settingsComponent.getPanel();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return settingsComponent == null ? null : settingsComponent.getPreferredFocusedComponent();
    }

    @Override
    public boolean isModified() {
        if (settingsComponent == null) {
            return false;
        }
        NovelConfig config = NovelConfig.getInstance();
        return !settingsComponent.getLegadoUrl().equals(config.getLegadoUrl()) ||
               !settingsComponent.getBookName().equals(config.getBookName()) ||
               !settingsComponent.getMatchPrefix().equals(config.getMatchPrefix());
    }

    @Override
    public void apply() {
        if (settingsComponent == null) {
            return;
        }
        NovelConfig config = NovelConfig.getInstance();
        config.setLegadoUrl(settingsComponent.getLegadoUrl());
        config.setBookName(settingsComponent.getBookName());
        config.setMatchPrefix(settingsComponent.getMatchPrefix());
        // 配置修改后，强制 Service 重载
        NovelGlobalService.getInstance().reload();
    }

    @Override
    public void reset() {
        if (settingsComponent == null) {
            return;
        }
        NovelConfig config = NovelConfig.getInstance();
        settingsComponent.setLegadoUrl(config.getLegadoUrl());
        settingsComponent.setBookName(config.getBookName());
        settingsComponent.setMatchPrefix(config.getMatchPrefix());
    }

    @Override
    public void disposeUIResources() {
        settingsComponent = null;
    }
}
