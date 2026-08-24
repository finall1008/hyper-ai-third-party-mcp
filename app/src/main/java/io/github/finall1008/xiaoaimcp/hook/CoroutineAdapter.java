package io.github.finall1008.xiaoaimcp.hook;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

final class CoroutineAdapter {
    private static final String SUSPENDED_MARKER = "COROUTINE_SUSPENDED";

    private final Class<?> continuationClass;
    private final Object emptyContext;

    private CoroutineAdapter(Class<?> continuationClass, Object emptyContext) {
        this.continuationClass = continuationClass;
        this.emptyContext = emptyContext;
    }

    static CoroutineAdapter create(Class<?> continuationClass) throws ReflectiveOperationException {
        if (continuationClass == null || !continuationClass.isInterface()) {
            throw new IllegalArgumentException("Continuation type must be an interface");
        }
        Method getContext = continuationClass.getMethod("getContext");
        if (getContext.getParameterCount() != 0 || !getContext.getReturnType().isInterface()) {
            throw new NoSuchMethodException("Continuation getContext signature is unavailable");
        }
        Class<?> contextClass = getContext.getReturnType();
        Object emptyContext = Proxy.newProxyInstance(
                contextClass.getClassLoader(),
                new Class<?>[]{contextClass},
                (proxy, method, args) -> switch (method.getName()) {
                    case "fold" -> args == null ? null : args[0];
                    case "get" -> null;
                    case "minusKey" -> proxy;
                    case "plus" -> args == null ? null : args[0];
                    case "toString" -> "XiaoAiMcpEmptyCoroutineContext";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == firstArg(args);
                    default -> throw new UnsupportedOperationException(method.toString());
                }
        );
        return new CoroutineAdapter(continuationClass, emptyContext);
    }

    Object newContinuation(Runnable completion) {
        return Proxy.newProxyInstance(
                continuationClass.getClassLoader(),
                new Class<?>[]{continuationClass},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getContext" -> emptyContext;
                    case "resumeWith" -> {
                        completion.run();
                        yield null;
                    }
                    case "toString" -> "XiaoAiMcpReloadContinuation";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == firstArg(args);
                    default -> throw new UnsupportedOperationException(method.toString());
                }
        );
    }

    static boolean isSuspended(Object result) {
        return result instanceof Enum<?> marker && SUSPENDED_MARKER.equals(marker.name());
    }

    private static Object firstArg(Object[] args) {
        return args == null || args.length == 0 ? null : args[0];
    }
}
