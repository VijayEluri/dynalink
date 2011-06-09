package org.dynalang.dynalink.beans;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import junit.framework.TestCase;

import org.dynalang.dynalink.CallSiteDescriptor;
import org.dynalang.dynalink.DynamicLinker;
import org.dynalang.dynalink.DynamicLinkerFactory;
import org.dynalang.dynalink.GuardedInvocation;

public class TestLengthGetter extends TestCase
{
    public void testEarlyBoundArrayLengthGetter() throws Throwable {
        testEarlyBoundArrayLengthGetter(byte[].class);
        testEarlyBoundArrayLengthGetter(short[].class);
        testEarlyBoundArrayLengthGetter(char[].class);
        testEarlyBoundArrayLengthGetter(int[].class);
        testEarlyBoundArrayLengthGetter(long[].class);
        testEarlyBoundArrayLengthGetter(float[].class);
        testEarlyBoundArrayLengthGetter(double[].class);
        testEarlyBoundArrayLengthGetter(Object[].class);
        testEarlyBoundArrayLengthGetter(String[].class);
    }

    private void testEarlyBoundArrayLengthGetter(Class<?> arrayClass) throws Throwable {
        final BeansLinker bl = new BeansLinker();
        final CallSiteDescriptor csd = new CallSiteDescriptor("dyn:getLength",
                MethodType.methodType(int.class, arrayClass));
        final Object array = Array.newInstance(arrayClass.getComponentType(), 2);
        final GuardedInvocation inv = bl.getGuardedInvocation(csd, null, array);
        // early bound, as call site guarantees we'll pass an array
        assertNull(inv.getGuard());
        final MethodHandle mh = inv.getInvocation();
        assertNotNull(mh);
        assertEquals(csd.getMethodType(), mh.type());
        assertEquals(2, mh.invokeWithArguments(array));
    }

    public void testEarlyBoundCollectionLengthGetter() throws Throwable {
        final BeansLinker bl = new BeansLinker();
        final CallSiteDescriptor csd = new CallSiteDescriptor("dyn:getLength",
                MethodType.methodType(int.class, List.class));
        final GuardedInvocation inv = bl.getGuardedInvocation(csd, null, Collections.EMPTY_LIST);
        assertNull(inv.getGuard());
        final MethodHandle mh = inv.getInvocation();
        assertNotNull(mh);
        assertEquals(csd.getMethodType(), mh.type());
        assertEquals(0, mh.invokeWithArguments(new Object[] { Collections.EMPTY_LIST }));
        assertEquals(2, mh.invokeWithArguments(new Object[] { Arrays.asList(new Object[] { "a", "b" })}));
    }

    public void testEarlyBoundMapLengthGetter() throws Throwable {
        final BeansLinker bl = new BeansLinker();
        final CallSiteDescriptor csd = new CallSiteDescriptor("dyn:getLength",
                MethodType.methodType(int.class, Map.class));
        final GuardedInvocation inv = bl.getGuardedInvocation(csd, null, Collections.EMPTY_MAP);
        assertNull(inv.getGuard());
        final MethodHandle mh = inv.getInvocation();
        assertNotNull(mh);
        assertEquals(csd.getMethodType(), mh.type());
        assertEquals(0, mh.invokeWithArguments(Collections.EMPTY_MAP));
        assertEquals(1, mh.invokeWithArguments(Collections.singletonMap("a", "b")));
    }

    public void testLateBoundLengthGetter() throws Throwable {
        final DynamicLinker linker = new DynamicLinkerFactory().createLinker();
        final RelinkCountingCallSite callSite = new RelinkCountingCallSite(
                "dyn:getLength", MethodType.methodType(int.class, Object.class));

        linker.link(callSite);
        assertEquals(0, callSite.getRelinkCount());
        MethodHandle callSiteInvoker = callSite.dynamicInvoker();
        assertEquals(2, callSiteInvoker.invokeWithArguments(new int[2]));
        assertEquals(1, callSite.getRelinkCount());
        assertEquals(3, callSiteInvoker.invokeWithArguments(new Object[] { new Object[3] }));
        // No relink - length getter applies to all array classes
        assertEquals(1, callSite.getRelinkCount());
        assertEquals(4, callSiteInvoker.invokeWithArguments(new long[4]));
        // Still no relink
        assertEquals(1, callSite.getRelinkCount());

        assertEquals(5, callSiteInvoker.invokeWithArguments(new Object[] { Arrays.asList(new Object[5])}));
        // Relinked for collections
        assertEquals(2, callSite.getRelinkCount());
        assertEquals(0, callSiteInvoker.invokeWithArguments(new HashSet()));
        // No relink for various collection types
        assertEquals(2, callSite.getRelinkCount());

        assertEquals(1, callSiteInvoker.invokeWithArguments(Collections.singletonMap("1", "2")));
        // Relinked for maps
        assertEquals(3, callSite.getRelinkCount());
        assertEquals(0, callSiteInvoker.invokeWithArguments(new HashMap()));
        // No relink for various map types
        assertEquals(3, callSite.getRelinkCount());

        assertEquals(6, callSiteInvoker.invokeWithArguments(new long[6]));
        // Relinked again for arrays
        assertEquals(4, callSite.getRelinkCount());
    }
}