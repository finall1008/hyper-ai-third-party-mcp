package io.github.finall1008.xiaoaimcp.hook;

import java.lang.reflect.Method;
import java.util.List;

record FilePolicyHookTargets(
        String mode,
        Method uriResolve,
        Method externalUserAssetCheck,
        List<Method> uriCallSites,
        Method lockscreenToolAllowed,
        List<Method> lockscreenCliMatchers,
        Method toolCallGetName,
        Method toolCallGetArguments,
        Method riskFileExemption,
        Method riskContextGetAgentId,
        Method riskContextGetSharedState,
        Method riskContextGetSessionId,
        Method riskMoveGetFirst,
        Method riskMoveGetSecond
) {
    boolean hasMutationPolicy() {
        return uriResolve != null && externalUserAssetCheck != null;
    }

    boolean hasLockscreenPolicy() {
        return lockscreenToolAllowed != null
                && !lockscreenCliMatchers.isEmpty()
                && toolCallGetName != null
                && toolCallGetArguments != null;
    }

    boolean hasConfirmationPolicy() {
        return riskFileExemption != null
                && riskContextGetAgentId != null
                && riskContextGetSharedState != null
                && riskContextGetSessionId != null
                && riskMoveGetFirst != null
                && riskMoveGetSecond != null;
    }
}
