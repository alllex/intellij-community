package com.intellij.ide.hierarchy.actions;

import com.intellij.ide.IdeBundle;
import com.intellij.ide.hierarchy.HierarchyBrowserManager;
import com.intellij.ide.hierarchy.call.CallHierarchyBrowser;
import com.intellij.ide.hierarchy.call.CallerMethodsTreeStructure;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;

import java.awt.*;

public final class BrowseCallHierarchyAction extends AnAction {
  public final void actionPerformed(final AnActionEvent e) {
    final DataContext dataContext = e.getDataContext();
    final Project project = PlatformDataKeys.PROJECT.getData(dataContext);
    if (project == null) return;

    PsiDocumentManager.getInstance(project).commitAllDocuments(); // prevents problems with smart pointers creation

    final PsiMethod method = getMethod(dataContext);
    if (method == null) return;
    final CallHierarchyBrowser hierarchyBrowser = new CallHierarchyBrowser(project, method);

    final Content content;

    final HierarchyBrowserManager hierarchyBrowserManager = HierarchyBrowserManager.getInstance(project);

    final ContentManager contentManager = hierarchyBrowserManager.getContentManager();
    final Content selectedContent = contentManager.getSelectedContent();
    if (selectedContent != null && !selectedContent.isPinned()) {
      content = selectedContent;
      final Component component = content.getComponent();
      if (component instanceof Disposable) {
        Disposer.dispose((Disposable)component);
      }
      content.setComponent(hierarchyBrowser);
    }
    else {
      content = ContentFactory.SERVICE.getInstance().createContent(hierarchyBrowser, null, true);
      contentManager.addContent(content);
    }
    contentManager.setSelectedContent(content);
    hierarchyBrowser.setContent(content);

    final String name = method.getName();
    content.setDisplayName(name);

    final Runnable runnable = new Runnable() {
      public void run() {
        hierarchyBrowser.changeView(CallerMethodsTreeStructure.TYPE);
      }
    };
    ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.HIERARCHY).activate(runnable);
  }

  public final void update(final AnActionEvent event){
    final Presentation presentation = event.getPresentation();
    if (!ActionPlaces.MAIN_MENU.equals(event.getPlace())) {
      presentation.setText(IdeBundle.message("action.browse.call.hierarchy"));
    }

    final DataContext dataContext = event.getDataContext();
    final PsiMethod method = getMethod(dataContext);
    presentation.setEnabled(method != null);
  }

  private static PsiMethod getMethod(final DataContext dataContext) {
    final Project project = PlatformDataKeys.PROJECT.getData(dataContext);
    if (project == null) return null;

    final PsiElement element = LangDataKeys.PSI_ELEMENT.getData(dataContext);
    return PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
  }
}