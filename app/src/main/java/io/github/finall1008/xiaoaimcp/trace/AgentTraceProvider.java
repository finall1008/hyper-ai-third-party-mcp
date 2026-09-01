package io.github.finall1008.xiaoaimcp.trace;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.finall1008.xiaoaimcp.BridgeApplication;
import io.github.finall1008.xiaoaimcp.BridgeContract;

public final class AgentTraceProvider extends ContentProvider {
    private final ExecutorService ingestExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "AgentTraceProviderIngest");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        AgentTraceDatabase.get(context).getWritableDatabase();
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        enforceTargetWriter();
        if (!AgentTraceContract.INGEST_URI.equals(uri) || !"w".equals(mode)) {
            throw new FileNotFoundException("Unsupported Agent Trace endpoint");
        }
        Context context = Objects.requireNonNull(getContext(), "context");
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            ingestExecutor.execute(() -> {
                try (ParcelFileDescriptor.AutoCloseInputStream input =
                             new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) {
                    TraceRetentionConfig retention = TraceRetentionStore.load(
                            context,
                            BridgeApplication.remotePreferences()
                    );
                    AgentTraceDatabase.get(context).ingestCompressed(input, retention);
                } catch (Throwable error) {
                    try {
                        pipe[0].closeWithError(error.getClass().getSimpleName());
                    } catch (IOException ignored) {
                        // The writer observes a closed pipe; no sensitive content is logged.
                    }
                }
            });
            return pipe[1];
        } catch (IOException error) {
            throw new FileNotFoundException("Unable to open Agent Trace ingest pipe");
        }
    }

    private void enforceTargetWriter() {
        Context context = Objects.requireNonNull(getContext(), "context");
        int callingUid = Binder.getCallingUid();
        if (callingUid == Process.myUid()) {
            return;
        }
        PackageManager manager = context.getPackageManager();
        try {
            ApplicationInfo target = manager.getApplicationInfo(BridgeContract.TARGET_PACKAGE, 0);
            String callingPackage = getCallingPackage();
            String[] packages = manager.getPackagesForUid(callingUid);
            boolean packageMatches = packages != null
                    && Arrays.asList(packages).contains(BridgeContract.TARGET_PACKAGE);
            if (target.uid != callingUid || !packageMatches
                    || (callingPackage != null
                    && !BridgeContract.TARGET_PACKAGE.equals(callingPackage))) {
                throw new SecurityException("Agent Trace writer is not the target package");
            }
        } catch (PackageManager.NameNotFoundException error) {
            throw new SecurityException("Agent Trace target package is unavailable", error);
        }
    }

    private void enforceOwner() {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw new SecurityException("Agent Trace data is private to the module app");
        }
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        enforceOwner();
        throw new UnsupportedOperationException("Use the in-process Agent Trace repository");
    }

    @Override
    public String getType(Uri uri) {
        return "application/x-ndjson+gzip";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        enforceTargetWriter();
        throw new UnsupportedOperationException("Use the streaming ingest endpoint");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        enforceOwner();
        throw new UnsupportedOperationException("Use the in-process Agent Trace repository");
    }

    @Override
    public int update(
            Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs
    ) {
        enforceTargetWriter();
        throw new UnsupportedOperationException("Use the streaming ingest endpoint");
    }
}
