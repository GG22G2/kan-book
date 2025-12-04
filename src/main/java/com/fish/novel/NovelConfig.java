package com.fish.novel;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Service(Service.Level.APP)
@State(name = "NovelConfig", storages = @Storage("novel-reader.xml"))
public final class NovelConfig implements PersistentStateComponent<NovelConfig> {

    public String legadoUrl = "http://192.168.1.113:1122";
    public String bookName = "";

    public static NovelConfig getInstance() {
        return ((ComponentManager)ApplicationManager.getApplication()).getService(NovelConfig.class);
    }

    @Override
    public @Nullable NovelConfig getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull NovelConfig state) {
        XmlSerializerUtil.copyBean(state, this);
    }
}