/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.vcs.history;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.diff.DiffProvider;
import com.intellij.openapi.vcs.diff.ItemLatestState;
import com.intellij.openapi.vcs.impl.BackgroundableActionLock;
import com.intellij.openapi.vcs.impl.VcsBackgroundableActions;
import com.intellij.openapi.vcs.impl.VcsBackgroundableComputable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Consumer;
import com.intellij.vcs.history.VcsHistoryProviderEx;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * also uses memory cache
 */
public class VcsHistoryProviderBackgroundableProxy {
  private final Project myProject;
  private final DiffProvider myDiffProvider;
  private final VcsHistoryProvider myDelegate;
  private final VcsHistoryCache myVcsHistoryCache;
  private final boolean myCachesHistory;
  private final HistoryComputerFactory myHistoryComputerFactory;
  private final VcsType myType;
  private final VcsConfiguration myConfiguration;

  public VcsHistoryProviderBackgroundableProxy(final AbstractVcs vcs, final VcsHistoryProvider delegate, DiffProvider diffProvider) {
    myDelegate = delegate;
    myProject = vcs.getProject();
    myConfiguration = VcsConfiguration.getInstance(myProject);
    myCachesHistory = myDelegate instanceof VcsCacheableHistorySessionFactory;
    myDiffProvider = diffProvider;
    myVcsHistoryCache = ProjectLevelVcsManager.getInstance(myProject).getVcsHistoryCache();
    myType = vcs.getType();
    myHistoryComputerFactory = new HistoryComputerFactory() {
      @Override
      public ThrowableComputable<VcsHistorySession, VcsException> create(FilePath filePath,
                                                                         Consumer<VcsHistorySession> consumer,
                                                                         VcsKey vcsKey) {
        if (myCachesHistory) {
          return new CachingHistoryComputer(filePath, consumer, vcsKey);
        }
        else {
          return new SimpleHistoryComputer(filePath, consumer);
        }
      }
    };
  }

  public void createSessionFor(@NotNull VcsKey vcsKey, @NotNull FilePath filePath, @NotNull Consumer<VcsHistorySession> continuation,
                               @NotNull VcsBackgroundableActions actionKey, boolean silent) {
    ThrowableComputable<VcsHistorySession, VcsException> throwableComputable = myHistoryComputerFactory.create(filePath, null, vcsKey);

    String title = VcsBundle.message("loading.file.history.progress");
    String errorTitle = silent ? null : VcsBundle.message("message.title.could.not.load.file.history");

    Object key = VcsBackgroundableActions.keyFrom(filePath);
    BackgroundableActionLock lock = BackgroundableActionLock.getLock(myProject, actionKey, key);
    if (lock.isLocked()) return;

    VcsBackgroundableComputable<VcsHistorySession> computable =
      new VcsBackgroundableComputable<>(myProject, title, errorTitle, throwableComputable, continuation, lock);
    lock.lock();
    ProgressManager.getInstance().run(computable);
  }

  public void executeAppendableSession(final VcsKey vcsKey, final FilePath filePath, final VcsAppendableHistorySessionPartner partner,
                                       boolean canUseCache, boolean canUseLastRevisionCheck) {
    doExecuteAppendableSession(vcsKey, filePath, null, partner, canUseCache, canUseLastRevisionCheck);
  }

  /**
   * @throws UnsupportedOperationException if this proxy was created for {@link VcsHistoryProvider} instance,
   *                                       that doesn't implement {@link VcsHistoryProviderEx}
   */
  public void executeAppendableSession(final VcsKey vcsKey, final FilePath filePath, @Nullable VcsRevisionNumber startRevisionNumber,
                                       final VcsAppendableHistorySessionPartner partner) {
    if (!(myDelegate instanceof VcsHistoryProviderEx)) throw new UnsupportedOperationException();
    doExecuteAppendableSession(vcsKey, filePath, startRevisionNumber, partner, false, false);
  }

  private void doExecuteAppendableSession(final VcsKey vcsKey, final FilePath filePath, @Nullable VcsRevisionNumber startRevisionNumber,
                                          final VcsAppendableHistorySessionPartner partner,
                                          boolean canUseCache, boolean canUseLastRevisionCheck) {
    if (myCachesHistory && canUseCache) {
      final VcsAbstractHistorySession session = getFullHistoryFromCache(vcsKey, filePath);
      if (session != null) {
        partner.reportCreatedEmptySession(session);
        partner.finished();
        return;
      }
    }

    BackgroundableActionLock lock =
      BackgroundableActionLock.getLock(myProject, VcsBackgroundableActions.CREATE_HISTORY_SESSION, filePath.getPath());
    if (lock.isLocked()) return;
    lock.lock();

    VcsAppendableHistorySessionPartner cachedPartner = partner;
    if (myCachesHistory && startRevisionNumber == null) {
      cachedPartner = new HistoryPartnerProxy(partner, session -> {
        if (session == null) return;
        VcsCacheableHistorySessionFactory<Serializable, VcsAbstractHistorySession> delegate =
          (VcsCacheableHistorySessionFactory<Serializable, VcsAbstractHistorySession>)myDelegate;
        FilePath correctedPath = delegate.getUsedFilePath(session);
        myVcsHistoryCache.put(filePath, correctedPath, vcsKey, (VcsAbstractHistorySession)session.copy(), delegate, true);
      });
    }

    reportHistory(filePath, startRevisionNumber, vcsKey, lock, cachedPartner, canUseLastRevisionCheck);
  }

  private VcsAbstractHistorySession getFullHistoryFromCache(VcsKey vcsKey, FilePath filePath) {
    VcsAbstractHistorySession full =
      myVcsHistoryCache.getFull(filePath, vcsKey, (VcsCacheableHistorySessionFactory<Serializable, VcsAbstractHistorySession>)myDelegate);
    if (full != null) {
      if (myConfiguration.LIMIT_HISTORY) {
        if (myConfiguration.MAXIMUM_HISTORY_ROWS < full.getRevisionList().size()) {
          final List<VcsFileRevision> list = full.getRevisionList();
          final List<VcsFileRevision> was = new ArrayList<>(list.subList(0, myConfiguration.MAXIMUM_HISTORY_ROWS));
          list.clear();
          list.addAll(was);
        }
      }
    }
    return full;
  }

  private void reportHistory(final FilePath filePath, @Nullable final VcsRevisionNumber startRevisionNumber,
                             final VcsKey vcsKey,
                             @NotNull BackgroundableActionLock lock,
                             final VcsAppendableHistorySessionPartner cachedPartner, final boolean canUseLastRevisionCheck) {
    ProgressManager.getInstance().run(new Task.Backgroundable(myProject, VcsBundle.message("loading.file.history.progress"), true) {
      public void run(@NotNull ProgressIndicator indicator) {
        indicator.setText(VcsUtil.getPathForProgressPresentation(filePath.getIOFile()));
        indicator.setIndeterminate(true);
        try {
          VcsAbstractHistorySession cachedSession;
          if (canUseLastRevisionCheck &&
              myCachesHistory &&
              (cachedSession = getSessionFromCacheWithLastRevisionCheck(filePath, vcsKey)) != null) {
            cachedPartner.reportCreatedEmptySession(cachedSession);
          }
          else if (myDelegate instanceof VcsHistoryProviderEx) {
            ((VcsHistoryProviderEx)myDelegate).reportAppendableHistory(filePath, startRevisionNumber, cachedPartner);
          }
          else {
            myDelegate.reportAppendableHistory(filePath, cachedPartner);
          }
        }
        catch (VcsException e) {
          cachedPartner.reportException(e);
        }
        finally {
          cachedPartner.finished();
          ApplicationManager.getApplication().invokeLater(lock::unlock, ModalityState.NON_MODAL);
        }
      }
    });
  }

  private static class HistoryPartnerProxy implements VcsAppendableHistorySessionPartner {
    private final VcsAppendableHistorySessionPartner myPartner;
    private final Consumer<VcsAbstractHistorySession> myFinish;
    private VcsAbstractHistorySession myCopy;

    private HistoryPartnerProxy(VcsAppendableHistorySessionPartner partner, final Consumer<VcsAbstractHistorySession> finish) {
      myPartner = partner;
      myFinish = finish;
    }

    @Override
    public void reportCreatedEmptySession(VcsAbstractHistorySession session) {
      myCopy = (VcsAbstractHistorySession)session.copy();
      myPartner.reportCreatedEmptySession(session);
    }

    @Override
    public void acceptRevision(VcsFileRevision revision) {
      myCopy.appendRevision(revision);
      myPartner.acceptRevision(revision);
    }

    @Override
    public void reportException(VcsException exception) {
      myPartner.reportException(exception);
    }

    @Override
    public void finished() {
      myPartner.finished();
      myFinish.consume(myCopy);
    }

    @Override
    public void beforeRefresh() {
      myPartner.beforeRefresh();
    }

    @Override
    public void forceRefresh() {
      myPartner.forceRefresh();
    }
  }

  private interface HistoryComputerFactory {
    ThrowableComputable<VcsHistorySession, VcsException> create(FilePath filePath, Consumer<VcsHistorySession> consumer, VcsKey vcsKey);
  }

  private class SimpleHistoryComputer implements ThrowableComputable<VcsHistorySession, VcsException> {
    private final FilePath myFilePath;
    private final Consumer<VcsHistorySession> myConsumer;

    private SimpleHistoryComputer(FilePath filePath, Consumer<VcsHistorySession> consumer) {
      myFilePath = filePath;
      myConsumer = consumer;
    }

    @Override
    public VcsHistorySession compute() throws VcsException {
      VcsHistorySession session = createSessionWithLimitCheck(myFilePath);
      if (myConsumer != null) {
        myConsumer.consume(session);
      }
      return session;
    }
  }

  private VcsAbstractHistorySession createSessionWithLimitCheck(final FilePath filePath) throws VcsException {
    final LimitHistoryCheck check = new LimitHistoryCheck(myProject, filePath.getPath());
    final VcsAppendableHistoryPartnerAdapter partner = new VcsAppendableHistoryPartnerAdapter() {
      @Override
      public void acceptRevision(VcsFileRevision revision) {
        check.checkNumber();
        super.acceptRevision(revision);
      }
    };
    try {
      myDelegate.reportAppendableHistory(filePath, partner);
    }
    catch (ProcessCanceledException e) {
      if (!check.isOver()) throw e;
    }
    return partner.getSession();
  }

  private class CachingHistoryComputer implements ThrowableComputable<VcsHistorySession, VcsException> {
    private final FilePath myFilePath;
    private final Consumer<VcsHistorySession> myConsumer;
    private final VcsKey myVcsKey;

    private CachingHistoryComputer(FilePath filePath, Consumer<VcsHistorySession> consumer, VcsKey vcsKey) {
      myFilePath = filePath;
      myConsumer = consumer;
      myVcsKey = vcsKey;
    }

    @Override
    public VcsHistorySession compute() throws VcsException {
      VcsAbstractHistorySession session;
      // we check for the last revision, since requests to this exact method at the moment only request history once, and no refresh is possible later
      session = getSessionFromCacheWithLastRevisionCheck(myFilePath, myVcsKey);
      if (session == null) {
        session = createSessionWithLimitCheck(myFilePath);
        VcsCacheableHistorySessionFactory<Serializable, VcsAbstractHistorySession> delegate =
          (VcsCacheableHistorySessionFactory<Serializable, VcsAbstractHistorySession>)myDelegate;
        FilePath correctedPath = delegate.getUsedFilePath(session);
        myVcsHistoryCache.put(myFilePath, correctedPath, myVcsKey, (VcsAbstractHistorySession)session.copy(), delegate, true);
      }
      if (myConsumer != null) {
        myConsumer.consume(session);
      }
      return session;
    }
  }

  @Nullable
  private VcsAbstractHistorySession getSessionFromCacheWithLastRevisionCheck(final FilePath filePath, final VcsKey vcsKey) {
    final ProgressIndicator pi = ProgressManager.getInstance().getProgressIndicator();
    if (pi != null) {
      pi.setText2("Checking last revision");
    }
    VcsAbstractHistorySession cached = getFullHistoryFromCache(vcsKey, filePath);
    if (cached == null) return null;
    final FilePath correctedFilePath =
      ((VcsCacheableHistorySessionFactory<Serializable, VcsAbstractHistorySession>)myDelegate).getUsedFilePath(cached);

    if (VcsType.distributed.equals(myType)) {
      final FilePath path = correctedFilePath != null ? correctedFilePath : filePath;
      VirtualFile virtualFile = path.getVirtualFile();
      if (virtualFile == null) {
        virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(path.getPath());
      }
      if (virtualFile != null) {
        final VcsRevisionNumber currentRevision = myDiffProvider.getCurrentRevision(virtualFile);
        final List<VcsFileRevision> revisionList = cached.getRevisionList();
        if (!revisionList.isEmpty() && revisionList.get(0).getRevisionNumber().equals(currentRevision)) {
          return cached;
        }
      }
    }
    else {
      final ItemLatestState lastRevision = myDiffProvider.getLastRevision(correctedFilePath != null ? correctedFilePath : filePath);
      if (lastRevision != null && !lastRevision.isDefaultHead() && lastRevision.isItemExists()) {
        final List<VcsFileRevision> revisionList = cached.getRevisionList();
        if (!revisionList.isEmpty() && revisionList.get(0).getRevisionNumber().equals(lastRevision.getNumber())) {
          return cached;
        }
      }
    }
    return null;
  }
}
