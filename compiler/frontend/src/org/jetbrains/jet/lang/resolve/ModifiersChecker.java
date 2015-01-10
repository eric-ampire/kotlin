/*
 * Copyright 2010-2014 JetBrains s.r.o.
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

package org.jetbrains.jet.lang.resolve;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.descriptors.annotations.AnnotationDescriptor;
import org.jetbrains.jet.lang.diagnostics.Errors;
import org.jetbrains.kotlin.psi.*;
import org.jetbrains.jet.lang.resolve.constants.CompileTimeConstant;
import org.jetbrains.jet.lang.resolve.constants.StringValue;
import org.jetbrains.jet.lang.resolve.name.FqName;
import org.jetbrains.jet.lang.resolve.name.Name;
import org.jetbrains.kotlin.lexer.JetModifierKeywordToken;
import org.jetbrains.kotlin.lexer.JetTokens;

import java.util.*;

import static org.jetbrains.jet.lang.diagnostics.Errors.*;
import static org.jetbrains.kotlin.lexer.JetTokens.*;

public class ModifiersChecker {
    private static final Collection<JetModifierKeywordToken> MODALITY_MODIFIERS =
            Lists.newArrayList(ABSTRACT_KEYWORD, OPEN_KEYWORD, FINAL_KEYWORD, OVERRIDE_KEYWORD);

    private static final Collection<JetModifierKeywordToken> VISIBILITY_MODIFIERS =
            Lists.newArrayList(PRIVATE_KEYWORD, PROTECTED_KEYWORD, PUBLIC_KEYWORD, INTERNAL_KEYWORD);

    public static void reportIllegalModifiers(
            @Nullable JetModifierList modifierList,
            @NotNull Collection<JetModifierKeywordToken> illegalModifiers,
            @NotNull BindingTrace trace
    ) {
        if (modifierList == null) return;

        for (JetModifierKeywordToken modifierToken : illegalModifiers) {
            if (modifierList.hasModifier(modifierToken)) {
                PsiElement modifierPsi = modifierList.getModifier(modifierToken);
                assert modifierPsi != null;
                trace.report(ILLEGAL_MODIFIER.on(modifierPsi, modifierToken));
            }
        }
    }

    public static void checkIncompatibleModifiers(
            @Nullable JetModifierList modifierList,
            @NotNull BindingTrace trace,
            @NotNull Collection<JetModifierKeywordToken> availableModifiers,
            @NotNull Collection<JetModifierKeywordToken>... availableCombinations
    ) {
        if (modifierList == null) return;
        Collection<JetModifierKeywordToken> presentModifiers = Sets.newLinkedHashSet();
        for (JetModifierKeywordToken modifier : availableModifiers) {
            if (modifierList.hasModifier(modifier)) {
                presentModifiers.add(modifier);
            }
        }
        checkRepeatedModifiers(modifierList, trace, availableModifiers);

        if (presentModifiers.size() == 1) {
            return;
        }
        for (Collection<JetModifierKeywordToken> combination : availableCombinations) {
            if (presentModifiers.containsAll(combination) && combination.containsAll(presentModifiers)) {
                return;
            }
        }
        for (JetModifierKeywordToken token : presentModifiers) {
            trace.report(Errors.INCOMPATIBLE_MODIFIERS.on(modifierList.getModifierNode(token).getPsi(), presentModifiers));
        }
    }

    private static void checkRepeatedModifiers(
            @NotNull JetModifierList modifierList,
            @NotNull BindingTrace trace,
            @NotNull Collection<JetModifierKeywordToken> availableModifiers
    ) {
        for (JetModifierKeywordToken token : availableModifiers) {
            if (!modifierList.hasModifier(token)) continue;

            List<ASTNode> nodesOfRepeatedTokens = Lists.newArrayList();
            ASTNode node = modifierList.getNode().getFirstChildNode();
            while (node != null) {
                if (node.getElementType() == token) {
                    nodesOfRepeatedTokens.add(node);
                }
                node = node.getTreeNext();
            }
            if (nodesOfRepeatedTokens.size() > 1) {
                for (ASTNode repeatedToken : nodesOfRepeatedTokens) {
                    trace.report(REPEATED_MODIFIER.on(repeatedToken.getPsi(), token));
                }
            }
        }
    }

    public static void checkIncompatibleVarianceModifiers(@Nullable JetModifierList modifierList, @NotNull BindingTrace trace) {
        checkIncompatibleModifiers(modifierList, trace, Arrays.asList(JetTokens.IN_KEYWORD, JetTokens.OUT_KEYWORD));
    }

    @NotNull
    private final BindingTrace trace;
    @NotNull
    private final AdditionalCheckerProvider additionalCheckerProvider;

    public ModifiersChecker(@NotNull BindingTrace trace, @NotNull AdditionalCheckerProvider additionalCheckerProvider) {
        this.trace = trace;
        this.additionalCheckerProvider = additionalCheckerProvider;
    }

    public static ModifiersChecker create(@NotNull BindingTrace trace, @NotNull AdditionalCheckerProvider provider) {
        return new ModifiersChecker(trace, provider);
    }

    public void checkModifiersForDeclaration(@NotNull JetDeclaration modifierListOwner, @NotNull MemberDescriptor descriptor) {
        if (modifierListOwner instanceof JetEnumEntry) {
            checkIllegalInThisContextModifiers(modifierListOwner, Arrays.asList(MODIFIER_KEYWORDS_ARRAY));
        }
        else {
            checkInnerModifier(modifierListOwner, descriptor);
            checkModalityModifiers(modifierListOwner);
            checkVisibilityModifiers(modifierListOwner, descriptor);
            checkVarianceModifiersOfTypeParameters(modifierListOwner);
        }
        checkPlatformNameApplicability(descriptor);
        runAnnotationCheckers(modifierListOwner, descriptor);
    }

    public void checkModifiersForLocalDeclaration(@NotNull JetDeclaration modifierListOwner, @NotNull DeclarationDescriptor descriptor) {
        checkIllegalModalityModifiers(modifierListOwner);
        checkIllegalVisibilityModifiers(modifierListOwner);
        checkPlatformNameApplicability(descriptor);
        runAnnotationCheckers(modifierListOwner, descriptor);
    }

    public void checkIllegalModalityModifiers(@NotNull JetModifierListOwner modifierListOwner) {
        checkIllegalInThisContextModifiers(modifierListOwner, MODALITY_MODIFIERS);
    }

    public void checkIllegalVisibilityModifiers(@NotNull JetModifierListOwner modifierListOwner) {
        checkIllegalInThisContextModifiers(modifierListOwner, VISIBILITY_MODIFIERS);
    }

    private void checkModalityModifiers(@NotNull JetModifierListOwner modifierListOwner) {
        JetModifierList modifierList = modifierListOwner.getModifierList();
        if (modifierList == null) return;

        checkRedundantModifier(modifierList, Pair.create(OPEN_KEYWORD, ABSTRACT_KEYWORD), Pair.create(OPEN_KEYWORD, OVERRIDE_KEYWORD));

        checkCompatibility(modifierList, Arrays.asList(ABSTRACT_KEYWORD, OPEN_KEYWORD, FINAL_KEYWORD),
                           Arrays.asList(ABSTRACT_KEYWORD, OPEN_KEYWORD));

        if (modifierListOwner.getParent() instanceof JetClassObject || modifierListOwner instanceof JetObjectDeclaration) {
            checkIllegalModalityModifiers(modifierListOwner);
        }
        else if (modifierListOwner instanceof JetClassOrObject) {
            checkIllegalInThisContextModifiers(modifierListOwner, Collections.singletonList(OVERRIDE_KEYWORD));
        }
    }

    private void checkVisibilityModifiers(@NotNull JetModifierListOwner modifierListOwner, @NotNull DeclarationDescriptor descriptor) {
        JetModifierList modifierList = modifierListOwner.getModifierList();
        if (modifierList == null) return;

        DeclarationDescriptor containingDeclaration = descriptor.getContainingDeclaration();
        if (containingDeclaration instanceof PackageFragmentDescriptor) {
            if (modifierList.hasModifier(PROTECTED_KEYWORD)) {
                trace.report(Errors.PACKAGE_MEMBER_CANNOT_BE_PROTECTED.on(modifierListOwner));
            }
        }

        checkCompatibility(modifierList, VISIBILITY_MODIFIERS);
    }

    private void checkInnerModifier(@NotNull JetModifierListOwner modifierListOwner, @NotNull DeclarationDescriptor descriptor) {
        if (modifierListOwner.hasModifier(INNER_KEYWORD)) {
            if (isIllegalInner(descriptor)) {
                checkIllegalInThisContextModifiers(modifierListOwner, Collections.singletonList(INNER_KEYWORD));
            }
            return;
        }
        if (modifierListOwner instanceof JetClass && !(modifierListOwner instanceof JetEnumEntry)) {
            JetClass aClass = (JetClass) modifierListOwner;
            boolean localEnumError = aClass.isLocal() && aClass.isEnum();
            if (!localEnumError && isIllegalNestedClass(descriptor)) {
                trace.report(NESTED_CLASS_NOT_ALLOWED.on(aClass));
            }
        }
    }

    public static boolean isIllegalInner(@NotNull DeclarationDescriptor descriptor) {
        if (!(descriptor instanceof ClassDescriptor)) return true;
        ClassDescriptor classDescriptor = (ClassDescriptor) descriptor;
        if (classDescriptor.getKind() != ClassKind.CLASS) return true;
        DeclarationDescriptor containingDeclaration = classDescriptor.getContainingDeclaration();
        if (!(containingDeclaration instanceof ClassDescriptor)) return true;
        return ((ClassDescriptor) containingDeclaration).getKind() == ClassKind.TRAIT;
    }

    private static boolean isIllegalNestedClass(@NotNull DeclarationDescriptor descriptor) {
        if (!(descriptor instanceof ClassDescriptor)) return false;
        DeclarationDescriptor containingDeclaration = descriptor.getContainingDeclaration();
        if (!(containingDeclaration instanceof ClassDescriptor)) return false;
        ClassDescriptor containingClass = (ClassDescriptor) containingDeclaration;
        return containingClass.isInner() || containingClass.getContainingDeclaration() instanceof FunctionDescriptor;
    }

    private void checkPlatformNameApplicability(@NotNull DeclarationDescriptor descriptor) {
        AnnotationDescriptor annotation = descriptor.getAnnotations().findAnnotation(new FqName("kotlin.platform.platformName"));
        if (annotation == null) return;

        JetAnnotationEntry annotationEntry = trace.get(BindingContext.ANNOTATION_DESCRIPTOR_TO_PSI_ELEMENT, annotation);
        if (annotationEntry == null) return;

        if (!DescriptorUtils.isTopLevelDeclaration(descriptor) || !(descriptor instanceof FunctionDescriptor)) {
            trace.report(INAPPLICABLE_ANNOTATION.on(annotationEntry));
        }

        Collection<CompileTimeConstant<?>> values = annotation.getAllValueArguments().values();
        if (!values.isEmpty()) {
            CompileTimeConstant<?> name = values.iterator().next();
            if (name instanceof StringValue) {
                String value = ((StringValue) name).getValue();
                if (value == null || !Name.isValidIdentifier(value)) {
                    trace.report(ILLEGAL_PLATFORM_NAME.on(annotationEntry, String.valueOf(value)));
                }
            }
        }
    }

    private void checkCompatibility(@Nullable JetModifierList modifierList, Collection<JetModifierKeywordToken> availableModifiers, Collection<JetModifierKeywordToken>... availableCombinations) {
        checkIncompatibleModifiers(modifierList, trace, availableModifiers, availableCombinations);
    }

    private void checkRedundantModifier(@NotNull JetModifierList modifierList, Pair<JetModifierKeywordToken, JetModifierKeywordToken>... redundantBundles) {
        for (Pair<JetModifierKeywordToken, JetModifierKeywordToken> tokenPair : redundantBundles) {
            JetModifierKeywordToken redundantModifier = tokenPair.getFirst();
            JetModifierKeywordToken sufficientModifier = tokenPair.getSecond();
            if (modifierList.hasModifier(redundantModifier) && modifierList.hasModifier(sufficientModifier)) {
                trace.report(Errors.REDUNDANT_MODIFIER.on(modifierList.getModifierNode(redundantModifier).getPsi(), redundantModifier, sufficientModifier));
            }
        }
    }

    public void checkIllegalInThisContextModifiers(
            @NotNull JetModifierListOwner modifierListOwner,
            @NotNull Collection<JetModifierKeywordToken> illegalModifiers
    ) {
        reportIllegalModifiers(modifierListOwner.getModifierList(), illegalModifiers, trace);
    }

    @NotNull
    public static Map<JetModifierKeywordToken, ASTNode> getNodesCorrespondingToModifiers(@NotNull JetModifierList modifierList, @NotNull Collection<JetModifierKeywordToken> possibleModifiers) {
        Map<JetModifierKeywordToken, ASTNode> nodes = Maps.newHashMap();
        for (JetModifierKeywordToken modifier : possibleModifiers) {
            if (modifierList.hasModifier(modifier)) {
                nodes.put(modifier, modifierList.getModifierNode(modifier));
            }
        }
        return nodes;
    }

    @NotNull
    public static Modality resolveModalityFromModifiers(@NotNull JetModifierListOwner modifierListOwner, @NotNull Modality defaultModality) {
        return resolveModalityFromModifiers(modifierListOwner.getModifierList(), defaultModality);
    }

    public static Modality resolveModalityFromModifiers(@Nullable JetModifierList modifierList, @NotNull Modality defaultModality) {
        if (modifierList == null) return defaultModality;
        boolean hasAbstractModifier = modifierList.hasModifier(ABSTRACT_KEYWORD);
        boolean hasOverrideModifier = modifierList.hasModifier(OVERRIDE_KEYWORD);

        if (modifierList.hasModifier(OPEN_KEYWORD)) {
            if (hasAbstractModifier || defaultModality == Modality.ABSTRACT) {
                return Modality.ABSTRACT;
            }
            return Modality.OPEN;
        }
        if (hasAbstractModifier) {
            return Modality.ABSTRACT;
        }
        boolean hasFinalModifier = modifierList.hasModifier(FINAL_KEYWORD);
        if (hasOverrideModifier && !hasFinalModifier && !(defaultModality == Modality.ABSTRACT)) {
            return Modality.OPEN;
        }
        if (hasFinalModifier) {
            return Modality.FINAL;
        }
        return defaultModality;
    }

    @NotNull
    public static Visibility resolveVisibilityFromModifiers(@NotNull JetModifierListOwner modifierListOwner, @NotNull Visibility defaultVisibility) {
        return resolveVisibilityFromModifiers(modifierListOwner.getModifierList(), defaultVisibility);
    }

    public static Visibility resolveVisibilityFromModifiers(@Nullable JetModifierList modifierList, @NotNull Visibility defaultVisibility) {
        if (modifierList == null) return defaultVisibility;
        if (modifierList.hasModifier(PRIVATE_KEYWORD)) return Visibilities.PRIVATE;
        if (modifierList.hasModifier(PUBLIC_KEYWORD)) return Visibilities.PUBLIC;
        if (modifierList.hasModifier(PROTECTED_KEYWORD)) return Visibilities.PROTECTED;
        if (modifierList.hasModifier(INTERNAL_KEYWORD)) return Visibilities.INTERNAL;
        return defaultVisibility;
    }

    public static boolean isInnerClass(@Nullable JetModifierList modifierList) {
        return modifierList != null && modifierList.hasModifier(INNER_KEYWORD);
    }

    @NotNull
    public static Visibility getDefaultClassVisibility(@NotNull ClassDescriptor descriptor) {
        ClassKind kind = descriptor.getKind();
        if (kind == ClassKind.ENUM_ENTRY) {
            return Visibilities.PUBLIC;
        }
        if (kind == ClassKind.CLASS_OBJECT) {
            return ((ClassDescriptor) descriptor.getContainingDeclaration()).getVisibility();
        }
        return Visibilities.INTERNAL;
    }

    private void runAnnotationCheckers(@NotNull JetDeclaration declaration, @NotNull DeclarationDescriptor descriptor) {
        for (AnnotationChecker checker : additionalCheckerProvider.getAnnotationCheckers()) {
            checker.check(declaration, descriptor, trace);
        }
    }

    public void checkVarianceModifiersOfTypeParameters(@NotNull JetModifierListOwner modifierListOwner) {
        if (!(modifierListOwner instanceof JetTypeParameterListOwner)) return;
        List<JetTypeParameter> typeParameters = ((JetTypeParameterListOwner) modifierListOwner).getTypeParameters();
        for (JetTypeParameter typeParameter : typeParameters) {
            JetModifierList modifierList = typeParameter.getModifierList();
            checkIncompatibleVarianceModifiers(modifierList, trace);
        }
    }
}
