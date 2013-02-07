/*
   Copyright 2009-2013 Attila Szegedi

   Licensed under either the Apache License, Version 2.0 (the "Apache
   License") or the 3-clause BSD License (the "BSD License"), with licensee
   being free to choose either of the two at their discretion.

   You may not use this file except in compliance with either the Apache
   License or the BSD License.

   A copy of the BSD License is available in the root directory of the
   source distribution of the project under the file name
   "LICENSE-BSD.txt".

   A copy of the Apache License is available in the root directory of the
   source distribution of the project under the file name
   "LICENSE-Apache-2.0.txt". Alternatively, you may obtain a copy of the
   Apache License at <http://www.apache.org/licenses/LICENSE-2.0>

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See your chosen License for the specific language governing permissions
   and limitations under that License.
*/

package org.dynalang.dynalink.beans;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;

import org.dynalang.dynalink.linker.ConversionComparator.Comparison;
import org.dynalang.dynalink.linker.GuardedInvocation;
import org.dynalang.dynalink.linker.LinkRequest;
import org.dynalang.dynalink.linker.LinkerServices;
import org.dynalang.dynalink.support.Lookup;

import junit.framework.TestCase;

/**
 * Tests for the {@link SimpleDynamicMethod}.
 *
 * @author Attila Szegedi
 */
public class TestSimpleDynamicMethod extends TestCase {
    /**
     * Test that it returns null when tried to link with a call site that has less arguments than the method has fixed
     * arguments.
     */
    public void testLessArgsOnFixArgs() {
        assertNull(new SimpleDynamicMethod(getTest1XMethod()).getInvocation(MethodType.methodType(Void.TYPE,
                Object.class, int.class), null));
    }

    public void testMoreArgsOnFixArgs() {
        assertNull(new SimpleDynamicMethod(getTest1XMethod()).getInvocation(MethodType.methodType(Void.TYPE,
                Object.class, int.class, int.class, int.class), null));
    }

    private static MethodHandle getTest1XMethod() {
        return Lookup.PUBLIC.findVirtual(Test1.class, "x", MethodType.methodType(int.class, int.class, int.class));
    }

    public class Test1 {
        public int x(int y, int z) {
            return y + z;
        }

        public int xv(int y, int... z) {
            for(int zz: z) {
                y += zz;
            }
            return y;
        }

        public String sv(String y, String... z) {
            for(String zz: z) {
                y += zz;
            }
            return y;
        }
    }

    private abstract static class MockLinkerServices implements LinkerServices {
        @Override
        public boolean canConvert(Class<?> from, Class<?> to) {
            fail(); // Not supposed to be called
            return false;
        }

        @Override
        public GuardedInvocation getGuardedInvocation(LinkRequest lreq) throws Exception {
            fail(); // Not supposed to be called
            return null;
        }

        @Override
        public Comparison compareConversion(Class<?> sourceType, Class<?> targetType1, Class<?> targetType2) {
            fail(); // Not supposed to be called
            return null;
        }

        @Override
        public MethodHandle getTypeConverter(Class<?> sourceType, Class<?> targetType) {
            fail(); // Not supposed to be called
            return null;
        }
    }

    public void testExactArgsOnFixArgs() {
        final MethodHandle mh = getTest1XMethod();
        final MethodType type = MethodType.methodType(int.class, Test1.class, int.class, int.class);

        final boolean[] converterInvoked = new boolean[1];

        LinkerServices ls = new MockLinkerServices() {
            @Override
            public MethodHandle asType(MethodHandle handle, MethodType fromType) {
                assertSame(handle, mh);
                assertEquals(type, fromType);
                converterInvoked[0] = true;
                return handle;
            }
        };
        // Make sure it didn't interfere - just returned the same method handle
        assertSame(mh, new SimpleDynamicMethod(mh).getInvocation(type, ls));
        assertTrue(converterInvoked[0]);
    }

    public void testVarArgsWithoutConversion() {
        final MethodHandle mh = getTest1XvMethod();
        final MethodType type = MethodType.methodType(int.class, Test1.class, int.class, int[].class);

        final boolean[] converterInvoked = new boolean[1];

        LinkerServices ls = new MockLinkerServices() {
            @Override
            public MethodHandle asType(MethodHandle handle, MethodType fromType) {
                assertEqualHandle(handle, mh.asFixedArity());
                assertEquals(type, fromType);
                converterInvoked[0] = true;
                return handle;
            }
        };
        // Make sure it didn't interfere - just returned the same method handle
        assertEqualHandle(mh.asFixedArity(),
                new SimpleDynamicMethod(mh).getInvocation(type, ls));
        assertTrue(converterInvoked[0]);
    }

    private static void assertEqualHandle(MethodHandle m1, MethodHandle m2) {
        assertEquals(m1.type(), m2.type());
        assertEquals(m1.isVarargsCollector(), m2.isVarargsCollector());
    }

    private static MethodHandle getTest1XvMethod() {
        return Lookup.PUBLIC.findVirtual(Test1.class, "xv", MethodType.methodType(int.class, int.class, int[].class));
    }

    private static MethodHandle getTest1SvMethod() {
        return Lookup.PUBLIC.findVirtual(Test1.class, "sv",
                MethodType.methodType(String.class, String.class, String[].class));
    }

    public void testVarArgsWithFixArgsOnly() throws Throwable {
        final MethodHandle mh = getTest1XvMethod();
        final MethodType callSiteType = MethodType.methodType(int.class, Object.class, int.class);

        final boolean[] converterInvoked = new boolean[1];

        LinkerServices ls = new MockLinkerServices() {
            @Override
            public MethodHandle asType(MethodHandle handle, MethodType fromType) {
                assertNotSame(handle, mh);
                assertEquals(MethodType.methodType(int.class, Test1.class, int.class), handle.type());
                assertEquals(callSiteType, fromType);
                converterInvoked[0] = true;
                return handle;
            }
        };
        MethodHandle newHandle = new SimpleDynamicMethod(mh).getInvocation(callSiteType, ls);
        assertNotSame(newHandle, mh);
        assertTrue(converterInvoked[0]);
        assertEquals(1, newHandle.invokeWithArguments(new Test1(), 1));
    }

    public void testVarArgsWithPrimitiveConversion() throws Throwable {
        final MethodHandle mh = getTest1XvMethod();
        final MethodType callSiteType = MethodType.methodType(int.class, Object.class, int.class, int.class, int.class);
        final MethodType declaredType = MethodType.methodType(int.class, Test1.class, int.class, int.class, int.class);

        final boolean[] converterInvoked = new boolean[1];

        LinkerServices ls = new MockLinkerServices() {
            @Override
            public MethodHandle asType(MethodHandle handle, MethodType fromType) {
                assertNotSame(handle, mh);
                assertEquals(declaredType, handle.type());
                assertEquals(callSiteType, fromType);
                converterInvoked[0] = true;
                return handle;
            }
        };
        MethodHandle newHandle = new SimpleDynamicMethod(mh).getInvocation(callSiteType, ls);
        assertNotSame(newHandle, mh);
        assertTrue(converterInvoked[0]);
        assertEquals(6, newHandle.invokeWithArguments(new Test1(), 1, 2, 3));
    }

    public void testVarArgsWithSinglePrimitiveArgConversion() throws Throwable {
        final MethodHandle mh = getTest1XvMethod();
        final MethodType declaredType = MethodType.methodType(int.class, Test1.class, int.class, int.class);
        final MethodType callSiteType = MethodType.methodType(int.class, Object.class, int.class, int.class);

        final boolean[] converterInvoked = new boolean[1];

        LinkerServices ls = new MockLinkerServices() {
            @Override
            public boolean canConvert(Class<?> from, Class<?> to) {
                assertSame(int.class, from);
                assertSame(int[].class, to);
                return false;
            }

            @Override
            public MethodHandle asType(MethodHandle handle, MethodType fromType) {
                assertNotSame(handle, mh);
                assertEquals(declaredType, handle.type());
                assertEquals(callSiteType, fromType);
                converterInvoked[0] = true;
                return handle;
            }
        };
        MethodHandle newHandle = new SimpleDynamicMethod(mh).getInvocation(callSiteType, ls);
        assertNotSame(newHandle, mh);
        assertTrue(converterInvoked[0]);
        assertEquals(3, newHandle.invokeWithArguments(new Test1(), 1, 2));
    }

    public void testVarArgsWithSingleStringArgConversion() throws Throwable {
        final MethodHandle mh = getTest1SvMethod();
        final MethodType callSiteType = MethodType.methodType(String.class, Object.class, String.class, String.class);

        final boolean[] converterInvoked = new boolean[1];

        LinkerServices ls = new MockLinkerServices() {
            @Override
            public boolean canConvert(Class<?> from, Class<?> to) {
                assertSame(String.class, from);
                assertSame(String[].class, to);
                return false;
            }

            @Override
            public MethodHandle asType(MethodHandle handle, MethodType fromType) {
                assertNotSame(handle, mh);
                assertEquals(MethodType.methodType(String.class, Test1.class, String.class, String.class),
                        handle.type());
                assertEquals(callSiteType, fromType);
                converterInvoked[0] = true;
                return handle;
            }
        };
        MethodHandle newHandle = new SimpleDynamicMethod(mh).getInvocation(callSiteType, ls);
        assertNotSame(newHandle, mh);
        assertTrue(converterInvoked[0]);
        assertEquals("ab", newHandle.invokeWithArguments(new Test1(), "a", "b"));
    }

    public void testVarArgsWithStringConversion() throws Throwable {
        final MethodHandle mh = getTest1SvMethod();
        final MethodType callSiteType =
                MethodType.methodType(String.class, Object.class, String.class, String.class, String.class);

        final boolean[] converterInvoked = new boolean[1];

        LinkerServices ls = new MockLinkerServices() {
            @Override
            public MethodHandle asType(MethodHandle handle, MethodType fromType) {
                assertNotSame(handle, mh);
                assertEquals(
                        MethodType.methodType(String.class, Test1.class, String.class, String.class, String.class),
                        handle.type());
                assertEquals(callSiteType, fromType);
                converterInvoked[0] = true;
                return handle;
            }
        };
        MethodHandle newHandle = new SimpleDynamicMethod(mh).getInvocation(callSiteType, ls);
        assertNotSame(newHandle, mh);
        assertTrue(converterInvoked[0]);
        assertEquals("abc", newHandle.invokeWithArguments(new Test1(), "a", "b", "c"));

    }

    public void testVarArgsWithSinglePrimitiveArgRuntimeConversion() throws Throwable {
        final MethodHandle mh = getTest1XvMethod();
        final MethodType callSiteType = MethodType.methodType(int.class, Object.class, Object.class, Object.class);

        final int[] converterInvoked = new int[1];

        LinkerServices ls = new MockLinkerServices() {
            @Override
            public boolean canConvert(Class<?> from, Class<?> to) {
                assertSame(Object.class, from);
                assertSame(int[].class, to);
                return true;
            }

            @Override
            public MethodHandle asType(MethodHandle handle, MethodType fromType) {
                assertEquals(callSiteType, fromType);
                int c = ++converterInvoked[0];
                switch(c) {
                    case 1: {
                        assertEqualHandle(handle, mh.asFixedArity());
                        break;
                    }
                    case 2: {
                        assertNotSame(handle, mh);
                        assertEquals(MethodType.methodType(int.class, Test1.class, int.class, int.class), handle.type());
                        break;
                    }
                    default: {
                        fail();
                        break;
                    }
                }
                return handle.asType(fromType);
            }
        };
        MethodHandle newHandle = new SimpleDynamicMethod(mh).getInvocation(callSiteType, ls);
        assertNotSame(newHandle, mh);
        assertEquals(2, converterInvoked[0]);
        assertEquals(3, newHandle.invokeWithArguments(new Test1(), 1, 2));
        assertEquals(6, newHandle.invokeWithArguments(new Test1(), 1, new int[] { 2, 3 }));
    }

    public void testVarArgsWithSingleStringArgRuntimeConversion() throws Throwable {
        final MethodHandle mh = getTest1SvMethod();
        final MethodType callSiteType = MethodType.methodType(String.class, Object.class, Object.class, Object.class);

        final int[] converterInvoked = new int[1];

        LinkerServices ls = new MockLinkerServices() {
            @Override
            public boolean canConvert(Class<?> from, Class<?> to) {
                assertSame(Object.class, from);
                assertSame(String[].class, to);
                return true;
            }

            @Override
            public MethodHandle asType(MethodHandle handle, MethodType fromType) {
                assertEquals(callSiteType, fromType);
                int c = ++converterInvoked[0];
                switch(c) {
                    case 1: {
                        assertEqualHandle(handle, mh.asFixedArity());
                        break;
                    }
                    case 2: {
                        assertNotSame(handle, mh);
                        assertEquals(MethodType.methodType(String.class, Test1.class, String.class, String.class),
                                handle.type());
                        break;
                    }
                    default: {
                        fail();
                        break;
                    }
                }
                return handle.asType(fromType);
            }
        };
        MethodHandle newHandle = new SimpleDynamicMethod(mh).getInvocation(callSiteType, ls);
        assertNotSame(newHandle, mh);
        assertEquals(2, converterInvoked[0]);
        assertEquals("ab", newHandle.invokeWithArguments(new Test1(), "a", "b"));
        assertEquals("abc", newHandle.invokeWithArguments(new Test1(), "a", new String[] { "b", "c" }));
    }
}