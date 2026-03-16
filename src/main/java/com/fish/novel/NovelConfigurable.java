package com.fish.novel;

import com.intellij.openapi.options.ConfigurableBase;

public class NovelConfigurable extends ConfigurableBase<NovelSettingsComponent, NovelConfig> {

    public NovelConfigurable() {
        super("com.fish.novel.NovelConfigurable", "Novel Reader", null);
    }

    @Override
    protected NovelConfig getSettings() {
        return NovelConfig.getInstance();
    }

    @Override
    protected NovelSettingsComponent createUi() {
        return new NovelSettingsComponent();
    }
}
