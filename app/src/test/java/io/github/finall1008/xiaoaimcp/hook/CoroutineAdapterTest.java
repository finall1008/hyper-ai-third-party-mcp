package io.github.finall1008.xiaoaimcp.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public final class CoroutineAdapterTest {
    @Test
    public void createsContinuationWithEmptyContextSemantics() throws Exception {
        CoroutineAdapter adapter = CoroutineAdapter.create(TestContinuation.class);
        AtomicInteger completions = new AtomicInteger();
        TestContinuation continuation = (TestContinuation) adapter.newContinuation(
                completions::incrementAndGet);

        TestContext context = continuation.getContext();
        Object initial = new Object();
        TestContext other = new TestContext() {
            @Override
            public Object fold(Object value, Object operation) {
                return null;
            }

            @Override
            public Object get(Object key) {
                return null;
            }

            @Override
            public TestContext minusKey(Object key) {
                return this;
            }

            @Override
            public TestContext plus(TestContext context) {
                return context;
            }
        };

        assertSame(initial, context.fold(initial, new Object()));
        assertNull(context.get(new Object()));
        assertSame(context, context.minusKey(new Object()));
        assertSame(other, context.plus(other));

        continuation.resumeWith(new Object());
        assertEquals(1, completions.get());
    }

    @Test
    public void recognizesOnlyCoroutineSuspendedEnumMarker() {
        assertTrue(CoroutineAdapter.isSuspended(TestCoroutineSingletons.COROUTINE_SUSPENDED));
        assertTrue(!CoroutineAdapter.isSuspended(TestCoroutineSingletons.UNDECIDED));
        assertTrue(!CoroutineAdapter.isSuspended("COROUTINE_SUSPENDED"));
    }

    public interface TestContinuation {
        TestContext getContext();

        void resumeWith(Object result);
    }

    public interface TestContext {
        Object fold(Object initial, Object operation);

        Object get(Object key);

        TestContext minusKey(Object key);

        TestContext plus(TestContext context);
    }

    private enum TestCoroutineSingletons {
        COROUTINE_SUSPENDED,
        UNDECIDED
    }
}
