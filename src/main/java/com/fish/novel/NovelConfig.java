package com.fish.novel;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.BaseState;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.SimplePersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoredProperty;

@Service(Service.Level.APP)
@State(name = "NovelConfig", storages = @Storage(value = "novel-reader.xml", roamingType = RoamingType.DISABLED))
public final class NovelConfig extends SimplePersistentStateComponent<NovelConfig.ConfigState> {

    public NovelConfig() {
        super(new ConfigState());
    }

    public static NovelConfig getInstance() {
        ComponentManager application = (ComponentManager) ApplicationManager.getApplication();
        return application.getService(NovelConfig.class);
    }

    public String getLegadoUrl() {
        return getState().getLegadoUrl();
    }

    public void setLegadoUrl(String legadoUrl) {
        getState().setLegadoUrl(legadoUrl);
    }

    public String getBookName() {
        return getState().getBookName();
    }

    public void setBookName(String bookName) {
        getState().setBookName(bookName);
    }

    public String getMatchPrefix() {
        return getState().getMatchPrefix();
    }

    public void setMatchPrefix(String matchPrefix) {
        getState().setMatchPrefix(matchPrefix);
    }

    public int getViewportWidth() {
        return getState().getViewportWidth();
    }

    public void setViewportWidth(int viewportWidth) {
        getState().setViewportWidth(viewportWidth);
    }

    public int getRendererFontSize() {
        return getState().getRendererFontSize();
    }

    public void setRendererFontSize(int rendererFontSize) {
        getState().setRendererFontSize(rendererFontSize);
    }

    public int getRenderLineCount() {
        return getState().getRenderLineCount();
    }

    public void setRenderLineCount(int renderLineCount) {
        getState().setRenderLineCount(renderLineCount);
    }

    public static final class ConfigState extends BaseState {
        private final StoredProperty<String> legadoUrl = string("http://192.168.2.151:1122");
        private final StoredProperty<String> bookName = string("十日终焉");
        private final StoredProperty<String> matchPrefix = string("函数");
        private final StoredProperty<Integer> viewportWidth = property(450);
        private final StoredProperty<Integer> rendererFontSize = property(0);
        private final StoredProperty<Integer> renderLineCount = property(3);

        public String getLegadoUrl() {
            return legadoUrl.getValue(this);
        }

        public void setLegadoUrl(String value) {
            legadoUrl.setValue(this, value);
        }

        public String getBookName() {
            return bookName.getValue(this);
        }

        public void setBookName(String value) {
            bookName.setValue(this, value);
        }

        public String getMatchPrefix() {
            return matchPrefix.getValue(this);
        }

        public void setMatchPrefix(String value) {
            matchPrefix.setValue(this, value);
        }

        public int getViewportWidth() {
            return viewportWidth.getValue(this);
        }

        public void setViewportWidth(int value) {
            viewportWidth.setValue(this, value);
        }

        public int getRendererFontSize() {
            return rendererFontSize.getValue(this);
        }

        public void setRendererFontSize(int value) {
            rendererFontSize.setValue(this, value);
        }

        public int getRenderLineCount() {
            return renderLineCount.getValue(this);
        }

        public void setRenderLineCount(int value) {
            renderLineCount.setValue(this, value);
        }
    }
}
