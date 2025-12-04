package com.fish.novel;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class NovelStartup implements ProjectActivity {
    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        EditorFactory factory = EditorFactory.getInstance();
        NovelEditorListener listener = new NovelEditorListener();
        NovelGlobalService globalService = NovelGlobalService.getInstance();

        // 绑定生命周期
        factory.addEditorFactoryListener(listener, globalService);

        // 初始化已有编辑器
        for (Editor editor : factory.getAllEditors()) {
            listener.initEditor(editor);
        }

        return Unit.INSTANCE;
    }
}