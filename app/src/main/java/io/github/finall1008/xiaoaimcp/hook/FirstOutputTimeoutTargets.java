package io.github.finall1008.xiaoaimcp.hook;

import java.lang.reflect.Method;

record FirstOutputTimeoutTargets(
        String mode,
        Method timeoutGetter
) {
    FirstOutputTimeoutTargets {
        timeoutGetter.setAccessible(true);
    }
}
