package io.github.finall1008.xiaoaimcp.hook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

record ObjectConfigAdapter(
        Constructor<?> serverConstructor,
        Constructor<?> serversConstructor,
        Method getAllServers,
        Method getGatewayMode,
        Method getServerName
) {
}
