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
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;

import org.dynalang.dynalink.linker.LinkerServices;
import org.dynalang.dynalink.support.Guards;

/**
 * A dynamic method bound to exactly one, non-overloaded Java method. Handles varargs.
 *
 * @author Attila Szegedi
 */
class SimpleDynamicMethod extends DynamicMethod {
    private final MethodHandle target;

    /**
     * Creates a simple dynamic method with no name.
     * @param target the target method handle
     */
    SimpleDynamicMethod(MethodHandle target) {
        this(target, null);
    }

    /**
     * Creates a new simple dynamic method, with a name constructed from the class name, method name, and handle
     * signature.
     *
     * @param target the target method handle
     * @param clazz the class declaring the method
     * @param name the simple name of the method
     */
    SimpleDynamicMethod(MethodHandle target, Class<?> clazz, String name) {
        this(target, getName(target, clazz, name));
    }

    SimpleDynamicMethod(MethodHandle target, String name) {
        super(name);
        this.target = target;
    }

    private static String getName(MethodHandle target, Class<?> clazz, String name) {
        return getMethodNameWithSignature(target, getClassAndMethodName(clazz, name));
    }

    static String getMethodNameWithSignature(MethodHandle target, String methodName) {
        final String typeStr = target.type().toString();
        final int retTypeIndex = typeStr.lastIndexOf(')') + 1;
        int secondParamIndex = typeStr.indexOf(',') + 1;
        if(secondParamIndex == 0) {
            secondParamIndex = retTypeIndex - 1;
        }
        return typeStr.substring(retTypeIndex) + " " + methodName + "(" + typeStr.substring(secondParamIndex, retTypeIndex);
    }

    /**
     * Returns the target of this dynamic method
     *
     * @return the target of this dynamic method
     */
    public MethodHandle getTarget() {
        return target;
    }

    @Override
    SimpleDynamicMethod getMethodForExactParamTypes(String paramTypes) {
        return typeMatchesDescription(paramTypes, target.type()) ? this : null;
    }

    @Override
    MethodHandle getInvocation(MethodType callSiteType, LinkerServices linkerServices) {
        final MethodType methodType = target.type();
        final int paramsLen = methodType.parameterCount();
        final boolean varArgs = target.isVarargsCollector();
        final MethodHandle fixTarget = varArgs ? target.asFixedArity() : target;
        final int fixParamsLen = varArgs ? paramsLen - 1 : paramsLen;
        final int argsLen = callSiteType.parameterCount();
        if(argsLen < fixParamsLen) {
            // Less actual arguments than number of fixed declared arguments; can't invoke.
            return null;
        }
        // Method handle of the same number of arguments as the call site type
        if(argsLen == fixParamsLen) {
            // Method handle that matches the number of actual arguments as the number of fixed arguments
            final MethodHandle matchedMethod;
            if(varArgs) {
                // If vararg, add a zero-length array of the expected type as the last argument to signify no variable
                // arguments.
                // TODO: check whether collectArguments() would handle this too.
                matchedMethod = MethodHandles.insertArguments(fixTarget, fixParamsLen, Array.newInstance(
                        methodType.parameterType(fixParamsLen).getComponentType(), 0));
            } else {
                // Otherwise, just use the method
                matchedMethod = fixTarget;
            }
            return createConvertingInvocation(matchedMethod, linkerServices, callSiteType);
        }

        // What's below only works for varargs
        if(!varArgs) {
            return null;
        }

        final Class<?> varArgType = methodType.parameterType(fixParamsLen);
        // Handle a somewhat sinister corner case: caller passes exactly one argument in the vararg position, and we
        // must handle both a prepacked vararg array as well as a genuine 1-long vararg sequence.
        if(argsLen == paramsLen) {
            final Class<?> callSiteLastArgType = callSiteType.parameterType(fixParamsLen);
            if(varArgType.isAssignableFrom(callSiteLastArgType)) {
                // Call site signature guarantees we'll always be passed a single compatible array; just link directly
                // to the method.
                return createConvertingInvocation(fixTarget, linkerServices, callSiteType);
            } else if(!linkerServices.canConvert(callSiteLastArgType, varArgType)) {
                // Call site signature guarantees the argument can definitely not be an array (i.e. it is primitive);
                // link immediately to a vararg-packing method handle.
                return createConvertingInvocation(collectArguments(fixTarget, argsLen), linkerServices, callSiteType);
            } else {
                // Call site signature makes no guarantees that the single argument in the vararg position will be
                // compatible across all invocations. Need to insert an appropriate guard and fall back to generic
                // vararg method when it is not.
                return MethodHandles.guardWithTest(Guards.isInstance(varArgType, fixParamsLen, callSiteType),
                        createConvertingInvocation(fixTarget, linkerServices, callSiteType),
                        createConvertingInvocation(collectArguments(fixTarget, argsLen), linkerServices, callSiteType));
            }
        } else {
            // Remaining case: more than one vararg.
            return createConvertingInvocation(collectArguments(fixTarget, argsLen), linkerServices, callSiteType);
        }
    }

    @Override
    public boolean contains(MethodHandle mh) {
        return target.type().parameterList().equals(mh.type().parameterList());
    }

    /**
     * Creates a method handle out of the original target that will collect the varargs for the exact component type of
     * the varArg array. Note that this will nicely trigger language-specific type converters for exactly those varargs
     * for which it is necessary when later passed to linkerServices.convertArguments().
     *
     * @param target the original method handle
     * @param parameterCount the total number of arguments in the new method handle
     * @return a collecting method handle
     */
    static MethodHandle collectArguments(MethodHandle target, final int parameterCount) {
        final MethodType methodType = target.type();
        final int fixParamsLen = methodType.parameterCount() - 1;
        final Class<?> arrayType = methodType.parameterType(fixParamsLen);
        return target.asCollector(arrayType, parameterCount - fixParamsLen);
    }

    private static MethodHandle createConvertingInvocation(final MethodHandle sizedMethod,
            final LinkerServices linkerServices, final MethodType callSiteType) {
        return linkerServices.asType(sizedMethod, callSiteType);
    }
}