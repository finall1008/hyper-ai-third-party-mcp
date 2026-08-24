package io.github.finall1008.xiaoaimcp.hook;

import java.util.List;

final class CachingClassCatalog implements ClassCatalog {
    private final ClassCatalog delegate;
    private volatile List<String> cached;

    CachingClassCatalog(ClassCatalog delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<String> classNames() throws Exception {
        List<String> current = cached;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (cached == null) {
                cached = List.copyOf(delegate.classNames());
            }
            return cached;
        }
    }
}
