/*
 * Copyright 2010-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.lang.resolve.java;

import com.intellij.psi.PsiClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.DeclarationDescriptor;
import org.jetbrains.jet.lang.descriptors.SourceElement;
import org.jetbrains.jet.lang.descriptors.TypeParameterDescriptor;
import org.jetbrains.jet.lang.descriptors.impl.TypeParameterDescriptorImpl;
import org.jetbrains.jet.lang.resolve.java.structure.*;
import org.jetbrains.jet.lang.resolve.java.structure.impl.JavaClassImpl;
import org.jetbrains.jet.lang.types.TypeConstructor;
import org.jetbrains.jet.lang.types.TypeProjection;
import org.jetbrains.jet.lang.types.TypeProjectionImpl;
import org.jetbrains.jet.lang.types.TypeSubstitutor;

import java.util.*;

import static org.jetbrains.jet.lang.resolve.java.JvmAnnotationNames.KOTLIN_CLASS;
import static org.jetbrains.jet.lang.resolve.java.JvmAnnotationNames.KOTLIN_PACKAGE;

public class JavaResolverPsiUtils {
    private JavaResolverPsiUtils() {
    }

    public static boolean isCompiledKotlinClass(@NotNull PsiClass psiClass) {
        JavaClass javaClass = new JavaClassImpl(psiClass);
        return javaClass.getOriginKind() == JavaClass.OriginKind.COMPILED && javaClass.findAnnotation(KOTLIN_CLASS) != null;
    }

    public static boolean isCompiledKotlinPackageClass(@NotNull PsiClass psiClass) {
        JavaClass javaClass = new JavaClassImpl(psiClass);
        return javaClass.getOriginKind() == JavaClass.OriginKind.COMPILED && javaClass.findAnnotation(KOTLIN_PACKAGE) != null;
    }

    public static boolean isCompiledKotlinClassOrPackageClass(@NotNull PsiClass psiClass) {
        return isCompiledKotlinClass(psiClass) || isCompiledKotlinPackageClass(psiClass);
    }

    /**
     * @see com.intellij.psi.util.TypeConversionUtil#erasure(com.intellij.psi.PsiType)
     */
    @Nullable
    public static JavaType erasure(@NotNull JavaType type) {
        return erasure(type, JavaTypeSubstitutor.EMPTY);
    }

    /**
     * @see com.intellij.psi.util.TypeConversionUtil#erasure(com.intellij.psi.PsiType, com.intellij.psi.PsiSubstitutor)
     */
    @Nullable
    public static JavaType erasure(@NotNull JavaType type, @NotNull JavaTypeSubstitutor substitutor) {
        if (type instanceof JavaClassifierType) {
            JavaClassifier classifier = ((JavaClassifierType) type).getClassifier();
            if (classifier instanceof JavaClass) {
                return ((JavaClass) classifier).getDefaultType();
            }
            else if (classifier instanceof JavaTypeParameter) {
                JavaTypeParameter typeParameter = (JavaTypeParameter) classifier;
                return typeParameterErasure(typeParameter, new HashSet<JavaTypeParameter>(), substitutor);
            }
            else {
                return null;
            }
        }
        else if (type instanceof JavaPrimitiveType) {
            return type;
        }
        else if (type instanceof JavaArrayType) {
            JavaType erasure = erasure(((JavaArrayType) type).getComponentType(), substitutor);
            return erasure == null ? null : erasure.createArrayType();
        }
        else if (type instanceof JavaWildcardType) {
            JavaWildcardType wildcardType = (JavaWildcardType) type;
            JavaType bound = wildcardType.getBound();
            if (bound != null && wildcardType.isExtends()) {
                return erasure(bound, substitutor);
            }
            return wildcardType.getTypeProvider().createJavaLangObjectType();
        }
        else {
            throw new IllegalStateException("Unsupported type: " + type);
        }
    }

    /**
     * @see com.intellij.psi.util.TypeConversionUtil#typeParameterErasure(com.intellij.psi.PsiTypeParameter)
     */
    @Nullable
    private static JavaType typeParameterErasure(
            @NotNull JavaTypeParameter typeParameter,
            @NotNull HashSet<JavaTypeParameter> visited,
            @NotNull JavaTypeSubstitutor substitutor
    ) {
        Collection<JavaClassifierType> upperBounds = typeParameter.getUpperBounds();
        if (!upperBounds.isEmpty()) {
            JavaClassifier classifier = upperBounds.iterator().next().getClassifier();
            if (classifier instanceof JavaTypeParameter && !visited.contains(classifier)) {
                JavaTypeParameter typeParameterBound = (JavaTypeParameter) classifier;
                visited.add(typeParameterBound);
                JavaType substitutedType = substitutor.substitute(typeParameterBound);
                if (substitutedType != null) {
                    return erasure(substitutedType);
                }
                return typeParameterErasure(typeParameterBound, visited, substitutor);
            }
            else if (classifier instanceof JavaClass) {
                return ((JavaClass) classifier).getDefaultType();
            }
        }
        return typeParameter.getTypeProvider().createJavaLangObjectType();
    }

    @NotNull
    public static Map<TypeParameterDescriptor, TypeParameterDescriptorImpl> recreateTypeParametersAndReturnMapping(
            @NotNull List<TypeParameterDescriptor> originalParameters,
            @Nullable DeclarationDescriptor newOwner
    ) {
        // LinkedHashMap to save the order of type parameters
        Map<TypeParameterDescriptor, TypeParameterDescriptorImpl> result =
                new LinkedHashMap<TypeParameterDescriptor, TypeParameterDescriptorImpl>();
        for (TypeParameterDescriptor typeParameter : originalParameters) {
            result.put(typeParameter,
                       TypeParameterDescriptorImpl.createForFurtherModification(
                               newOwner == null ? typeParameter.getContainingDeclaration() : newOwner,
                               typeParameter.getAnnotations(),
                               typeParameter.isReified(),
                               typeParameter.getVariance(),
                               typeParameter.getName(),
                               typeParameter.getIndex(),
                               SourceElement.NO_SOURCE
                       )
            );
        }
        return result;
    }

    @NotNull
    public static TypeSubstitutor createSubstitutorForTypeParameters(
            @NotNull Map<TypeParameterDescriptor, TypeParameterDescriptorImpl> originalToAltTypeParameters
    ) {
        Map<TypeConstructor, TypeProjection> typeSubstitutionContext = new HashMap<TypeConstructor, TypeProjection>();
        for (Map.Entry<TypeParameterDescriptor, TypeParameterDescriptorImpl> originalToAltTypeParameter : originalToAltTypeParameters.entrySet()) {
            typeSubstitutionContext.put(originalToAltTypeParameter.getKey().getTypeConstructor(),
                                        new TypeProjectionImpl(originalToAltTypeParameter.getValue().getDefaultType()));
        }
        return TypeSubstitutor.create(typeSubstitutionContext);
    }
}
