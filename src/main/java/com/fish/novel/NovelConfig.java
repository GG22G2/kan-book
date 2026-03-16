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

    public static final class ConfigState extends BaseState {
        private final StoredProperty<String> legadoUrl = string("http://192.168.0.178:1122");
        private final StoredProperty<String> bookName = string("");
        private final StoredProperty<String> matchPrefix = string("函数");

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
    }
}
