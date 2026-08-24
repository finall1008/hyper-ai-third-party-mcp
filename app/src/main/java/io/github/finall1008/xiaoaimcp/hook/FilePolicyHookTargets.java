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

    boolean hasAllCapabilities() {
        return hasMutationPolicy() && hasLockscreenPolicy() && hasConfirmationPolicy();
    }

    FilePolicyHookTargets withFallback(FilePolicyHookTargets fallback) {
        boolean mutationFromPrimary = hasMutationPolicy();
        boolean lockscreenFromPrimary = hasLockscreenPolicy();
        boolean confirmationFromPrimary = hasConfirmationPolicy();
        String mergedMode = mode;
        if ((!mutationFromPrimary && fallback.hasMutationPolicy())
                || (!lockscreenFromPrimary && fallback.hasLockscreenPolicy())
                || (!confirmationFromPrimary && fallback.hasConfirmationPolicy())) {
            mergedMode += "+" + fallback.mode;
        }
        return new FilePolicyHookTargets(
                mergedMode,
                mutationFromPrimary ? uriResolve : fallback.uriResolve,
                mutationFromPrimary ? externalUserAssetCheck : fallback.externalUserAssetCheck,
                mutationFromPrimary ? uriCallSites : fallback.uriCallSites,
                lockscreenFromPrimary ? lockscreenToolAllowed : fallback.lockscreenToolAllowed,
                lockscreenFromPrimary ? lockscreenCliMatchers : fallback.lockscreenCliMatchers,
                lockscreenFromPrimary ? toolCallGetName : fallback.toolCallGetName,
                lockscreenFromPrimary ? toolCallGetArguments : fallback.toolCallGetArguments,
                confirmationFromPrimary ? riskFileExemption : fallback.riskFileExemption,
                confirmationFromPrimary ? riskContextGetAgentId : fallback.riskContextGetAgentId,
                confirmationFromPrimary
                        ? riskContextGetSharedState : fallback.riskContextGetSharedState,
                confirmationFromPrimary ? riskContextGetSessionId : fallback.riskContextGetSessionId,
                confirmationFromPrimary ? riskMoveGetFirst : fallback.riskMoveGetFirst,
                confirmationFromPrimary ? riskMoveGetSecond : fallback.riskMoveGetSecond
        );
    }
}
