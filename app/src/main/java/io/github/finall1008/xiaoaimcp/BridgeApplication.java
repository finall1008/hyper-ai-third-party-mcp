package io.github.finall1008.xiaoaimcp;

import android.app.Application;
import android.content.SharedPreferences;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class BridgeApplication extends Application
        implements XposedServiceHelper.OnServiceListener {
    private static final Set<ServiceStateListener> LISTENERS = new CopyOnWriteArraySet<>();
    private static volatile XposedService service;

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
    }

    public static XposedService service() {
        return service;
    }

    public static SharedPreferences remotePreferences() {
        XposedService current = service;
        return current == null ? null : current.getRemotePreferences(BridgeContract.PREF_GROUP);
    }

    public static void addServiceStateListener(ServiceStateListener listener, boolean notifyNow) {
        LISTENERS.add(listener);
        if (notifyNow) {
            listener.onServiceStateChanged(service);
        }
    }

    public static void removeServiceStateListener(ServiceStateListener listener) {
        LISTENERS.remove(listener);
    }

    private static void notifyServiceStateChanged() {
        for (ServiceStateListener listener : LISTENERS) {
            listener.onServiceStateChanged(service);
        }
    }

    @Override
    public void onServiceBind(XposedService boundService) {
        service = boundService;
        notifyServiceStateChanged();
    }

    @Override
    public void onServiceDied(XposedService deadService) {
        if (service == deadService) {
            service = null;
        }
        notifyServiceStateChanged();
    }

    public interface ServiceStateListener {
        void onServiceStateChanged(XposedService service);
    }
}
