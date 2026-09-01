package io.github.finall1008.xiaoaimcp.hook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

record AgentSessionTraceTargets(
        String mode,
        Method execute,
        Method getAgentMeta,
        Field agentManagerField,
        Method agentManagerGet,
        List<Method> callSites
) {
    AgentSessionTraceTargets {
        callSites = List.copyOf(callSites);
        makeAccessible(execute);
        makeAccessible(getAgentMeta);
        makeAccessible(agentManagerField);
        makeAccessible(agentManagerGet);
        for (Method callSite : callSites) {
            makeAccessible(callSite);
        }
    }

    boolean available() {
        return execute != null && getAgentMeta != null;
    }

    boolean installable() {
        return available() && !callSites.isEmpty();
    }

    private static void makeAccessible(Object member) {
        if (member instanceof Method method) {
            method.setAccessible(true);
        } else if (member instanceof Field field) {
            field.setAccessible(true);
        }
    }
}
