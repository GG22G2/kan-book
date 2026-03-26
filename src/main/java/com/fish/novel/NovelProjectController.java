package com.fish.novel;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

@Service(Service.Level.PROJECT)
public final class NovelProjectController implements Disposable {

    private final Project project;

    private Editor currentEditor;
    private NovelEditorHandler currentHandler;

    public NovelProjectController(Project project) {
        this.project = project;
    }

    public static NovelProjectController getInstance(Project project) {
        return project.getService(NovelProjectController.class);
    }

    public void syncSelectedEditor() {
        if (project.isDisposed()) {
            detachCurrentHandler();
            return;
        }
        syncSelectedEditor(FileEditorManager.getInstance(project).getSelectedTextEditor());
    }

    public void syncSelectedEditor(@Nullable Editor editor) {
        Editor targetEditor = isSupportedEditor(editor) ? editor : null;
        if (currentEditor == targetEditor && currentHandler != null) {
            return;
        }

        detachCurrentHandler();
        if (targetEditor == null) {
            return;
        }

        NovelEditorHandler handler = new NovelEditorHandler(targetEditor, NovelGlobalService.getInstance());
        currentEditor = targetEditor;
        currentHandler = handler;
        Disposer.register(this, handler);
        handler.attach();
    }

    private boolean isSupportedEditor(@Nullable Editor editor) {
        if (project.isDisposed() || editor == null || editor.isDisposed()) {
            return false;
        }
        VirtualFile virtualFile = editor.getVirtualFile();
        return virtualFile != null && virtualFile.getName().endsWith(".java");
    }

    private void detachCurrentHandler() {
        if (currentHandler != null) {
            NovelEditorHandler handler = currentHandler;
            currentHandler = null;
            currentEditor = null;
            Disposer.dispose(handler);
        } else {
            currentEditor = null;
        }
    }

    @Override
    public void dispose() {
        detachCurrentHandler();
    }
}