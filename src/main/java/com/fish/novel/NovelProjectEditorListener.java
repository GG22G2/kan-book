package com.fish.novel;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

public final class NovelProjectEditorListener implements FileEditorManagerListener {

    private final Project project;
    private final NovelProjectController controller;

    public NovelProjectEditorListener(Project project) {
        this.project = project;
        this.controller = NovelProjectController.getInstance(project);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                controller.syncSelectedEditor();
            }
        });
    }

    @Override
    public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        syncSelectedEditor();
    }

    @Override
    public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        syncSelectedEditor();
    }

    @Override
    public void selectionChanged(@NotNull FileEditorManagerEvent event) {
        syncSelectedEditor();
    }

    private void syncSelectedEditor() {
        if (!project.isDisposed()) {
            controller.syncSelectedEditor();
        }
    }
}