package io.github.finall1008.xiaoaimcp.hook;

import android.content.pm.ApplicationInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dalvik.system.DexFile;

final class DexClassCatalog implements ClassCatalog {
    private final ApplicationInfo applicationInfo;

    DexClassCatalog(ApplicationInfo applicationInfo) {
        this.applicationInfo = applicationInfo;
    }

    @Override
    public List<String> classNames() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        addDex(names, applicationInfo.sourceDir);
        if (applicationInfo.splitSourceDirs != null) {
            for (String splitSourceDir : applicationInfo.splitSourceDirs) {
                addDex(names, splitSourceDir);
            }
        }
        return new ArrayList<>(names);
    }

    @SuppressWarnings("deprecation")
    private static void addDex(Set<String> names, String path) throws IOException {
        if (path == null || path.isBlank()) {
            return;
        }
        DexFile dexFile = new DexFile(path);
        try {
            Enumeration<String> entries = dexFile.entries();
            names.addAll(Collections.list(entries));
        } finally {
            dexFile.close();
        }
    }
}
