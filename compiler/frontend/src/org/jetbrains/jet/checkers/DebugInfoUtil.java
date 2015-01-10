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

package org.jetbrains.jet.checkers;

import com.google.common.collect.Maps;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.JetNodeTypes;
import org.jetbrains.jet.lang.descriptors.CallableDescriptor;
import org.jetbrains.jet.lang.descriptors.DeclarationDescriptor;
import org.jetbrains.jet.lang.descriptors.PropertyDescriptor;
import org.jetbrains.jet.lang.descriptors.VariableDescriptor;
import org.jetbrains.jet.lang.diagnostics.Diagnostic;
import org.jetbrains.jet.lang.diagnostics.DiagnosticFactory;
import org.jetbrains.jet.lang.diagnostics.Errors;
import org.jetbrains.kotlin.psi.*;
import org.jetbrains.jet.lang.resolve.BindingContext;
import org.jetbrains.jet.lang.resolve.BindingContextUtils;
import org.jetbrains.jet.lang.resolve.calls.callUtil.CallUtilPackage;
import org.jetbrains.jet.lang.resolve.calls.model.ResolvedCall;
import org.jetbrains.jet.lang.resolve.calls.tasks.TasksPackage;
import org.jetbrains.jet.lang.types.ErrorUtils;
import org.jetbrains.jet.lang.types.JetType;
import org.jetbrains.kotlin.lexer.JetTokens;
import org.jetbrains.jet.util.slicedmap.WritableSlice;

import java.util.Collection;
import java.util.Map;

import static org.jetbrains.jet.lang.resolve.BindingContext.*;
import static org.jetbrains.kotlin.lexer.JetTokens.*;

public class DebugInfoUtil {
    private static final TokenSet MAY_BE_UNRESOLVED = TokenSet.create(IN_KEYWORD, NOT_IN);
    private static final TokenSet EXCLUDED = TokenSet.create(
            COLON, AS_KEYWORD, AS_SAFE, IS_KEYWORD, NOT_IS, OROR, ANDAND, EQ, EQEQEQ, EXCLEQEQEQ, ELVIS, EXCLEXCL);

    public abstract static class DebugInfoReporter {

        public void preProcessReference(@NotNull JetReferenceExpression expression) {
            // do nothing
        }

        public abstract void reportElementWithErrorType(@NotNull JetReferenceExpression expression);

        public abstract void reportMissingUnresolved(@NotNull JetReferenceExpression expression);

        public abstract void reportUnresolvedWithTarget(@NotNull JetReferenceExpression expression, @NotNull String target);

        public void reportDynamicCall(@NotNull JetElement element, DeclarationDescriptor declarationDescriptor) { }
    }

    public static void markDebugAnnotations(
            @NotNull PsiElement root,
            @NotNull final BindingContext bindingContext,
            @NotNull final DebugInfoReporter debugInfoReporter
    ) {
        final Map<JetReferenceExpression, DiagnosticFactory<?>> markedWithErrorElements = Maps.newHashMap();
        for (Diagnostic diagnostic : bindingContext.getDiagnostics()) {
            DiagnosticFactory<?> factory = diagnostic.getFactory();
            if (Errors.UNRESOLVED_REFERENCE_DIAGNOSTICS.contains(diagnostic.getFactory())) {
                markedWithErrorElements.put((JetReferenceExpression) diagnostic.getPsiElement(), factory);
            }
            else if (factory == Errors.SUPER_IS_NOT_AN_EXPRESSION
                    || factory == Errors.SUPER_NOT_AVAILABLE) {
                JetSuperExpression superExpression = (JetSuperExpression) diagnostic.getPsiElement();
                markedWithErrorElements.put(superExpression.getInstanceReference(), factory);
            }
            else if (factory == Errors.EXPRESSION_EXPECTED_PACKAGE_FOUND) {
                markedWithErrorElements.put((JetSimpleNameExpression) diagnostic.getPsiElement(), factory);
            }
            else if (factory == Errors.UNSUPPORTED) {
                for (JetReferenceExpression reference : PsiTreeUtil.findChildrenOfType(diagnostic.getPsiElement(),
                                                                                       JetReferenceExpression.class)) {
                    markedWithErrorElements.put(reference, factory);
                }
            }
        }

        root.acceptChildren(new JetTreeVisitorVoid() {

            @Override
            public void visitForExpression(@NotNull JetForExpression expression) {
                JetExpression range = expression.getLoopRange();
                reportIfDynamicCall(range, range, LOOP_RANGE_ITERATOR_RESOLVED_CALL);
                reportIfDynamicCall(range, range, LOOP_RANGE_HAS_NEXT_RESOLVED_CALL);
                reportIfDynamicCall(range, range, LOOP_RANGE_NEXT_RESOLVED_CALL);
                super.visitForExpression(expression);
            }

            @Override
            public void visitMultiDeclaration(@NotNull JetMultiDeclaration multiDeclaration) {
                for (JetMultiDeclarationEntry entry : multiDeclaration.getEntries()) {
                    reportIfDynamicCall(entry, entry, COMPONENT_RESOLVED_CALL);
                }
                super.visitMultiDeclaration(multiDeclaration);
            }

            @Override
            public void visitProperty(@NotNull JetProperty property) {
                VariableDescriptor descriptor = bindingContext.get(VARIABLE, property);
                if (descriptor instanceof PropertyDescriptor && property.getDelegate() != null) {
                    PropertyDescriptor propertyDescriptor = (PropertyDescriptor) descriptor;
                    reportIfDynamicCall(property.getDelegate(), propertyDescriptor.getGetter(), DELEGATED_PROPERTY_RESOLVED_CALL);
                    reportIfDynamicCall(property.getDelegate(), propertyDescriptor.getSetter(), DELEGATED_PROPERTY_RESOLVED_CALL);
                    reportIfDynamicCall(property.getDelegate(), propertyDescriptor, DELEGATED_PROPERTY_PD_RESOLVED_CALL);
                }
                super.visitProperty(property);
            }

            @Override
            public void visitThisExpression(@NotNull JetThisExpression expression) {
                ResolvedCall<? extends CallableDescriptor> resolvedCall = CallUtilPackage.getResolvedCall(expression, bindingContext);
                if (resolvedCall != null) {
                    reportIfDynamic(expression, resolvedCall.getResultingDescriptor(), debugInfoReporter);
                }
                super.visitThisExpression(expression);
            }

            @Override
            public void visitReferenceExpression(@NotNull JetReferenceExpression expression) {
                super.visitReferenceExpression(expression);
                if (!BindingContextUtils.isExpressionWithValidReference(expression, bindingContext)){
                    return;
                }
                IElementType referencedNameElementType = null;
                if (expression instanceof JetSimpleNameExpression) {
                    JetSimpleNameExpression nameExpression = (JetSimpleNameExpression) expression;
                    IElementType elementType = expression.getNode().getElementType();
                    if (elementType == JetNodeTypes.OPERATION_REFERENCE) {
                        referencedNameElementType = nameExpression.getReferencedNameElementType();
                        if (EXCLUDED.contains(referencedNameElementType)) {
                            return;
                        }
                    }
                    if (elementType == JetNodeTypes.LABEL ||
                            nameExpression.getReferencedNameElementType() == JetTokens.THIS_KEYWORD) {
                        return;
                    }
                }

                debugInfoReporter.preProcessReference(expression);

                String target = null;
                DeclarationDescriptor declarationDescriptor = bindingContext.get(REFERENCE_TARGET, expression);
                if (declarationDescriptor != null) {
                    target = declarationDescriptor.toString();

                    reportIfDynamic(expression, declarationDescriptor, debugInfoReporter);
                }
                if (target == null) {
                    PsiElement labelTarget = bindingContext.get(LABEL_TARGET, expression);
                    if (labelTarget != null) {
                        target = labelTarget.getText();
                    }
                }
                if (target == null) {
                    Collection<? extends DeclarationDescriptor> declarationDescriptors =
                            bindingContext.get(AMBIGUOUS_REFERENCE_TARGET, expression);
                    if (declarationDescriptors != null) {
                        target = "[" + declarationDescriptors.size() + " descriptors]";
                    }
                }
                if (target == null) {
                    Collection<? extends PsiElement> labelTargets = bindingContext.get(AMBIGUOUS_LABEL_TARGET, expression);
                    if (labelTargets != null) {
                        target = "[" + labelTargets.size() + " elements]";
                    }
                }

                if (MAY_BE_UNRESOLVED.contains(referencedNameElementType)) {
                    return;
                }

                boolean resolved = target != null;
                boolean markedWithError = markedWithErrorElements.containsKey(expression);
                if (expression instanceof JetArrayAccessExpression &&
                    markedWithErrorElements.containsKey(((JetArrayAccessExpression) expression).getArrayExpression())) {
                    // if 'foo' in 'foo[i]' is unresolved it means 'foo[i]' is unresolved (otherwise 'foo[i]' is marked as 'missing unresolved')
                    markedWithError = true;
                }
                JetType expressionType = bindingContext.get(EXPRESSION_TYPE, expression);
                DiagnosticFactory<?> factory = markedWithErrorElements.get(expression);
                if (declarationDescriptor != null &&
                    (ErrorUtils.isError(declarationDescriptor) || ErrorUtils.containsErrorType(expressionType))) {
                    if (factory != Errors.EXPRESSION_EXPECTED_PACKAGE_FOUND) {
                        debugInfoReporter.reportElementWithErrorType(expression);
                    }
                }
                if (resolved && markedWithError) {
                    if (Errors.UNRESOLVED_REFERENCE_DIAGNOSTICS.contains(factory)) {
                        debugInfoReporter.reportUnresolvedWithTarget(expression, target);
                    }
                }
                else if (!resolved && !markedWithError) {
                    debugInfoReporter.reportMissingUnresolved(expression);
                }
            }

            private <E extends JetElement, K, D extends CallableDescriptor> boolean reportIfDynamicCall(E element, K key, WritableSlice<K, ResolvedCall<D>> slice) {
                ResolvedCall<D> resolvedCall = bindingContext.get(slice, key);
                if (resolvedCall != null) {
                    return reportIfDynamic(element, resolvedCall.getResultingDescriptor(), debugInfoReporter);
                }
                return false;
            }
        });
    }

    private static boolean reportIfDynamic(JetElement element, DeclarationDescriptor declarationDescriptor, DebugInfoReporter debugInfoReporter) {
        if (declarationDescriptor != null && TasksPackage.isDynamic(declarationDescriptor)) {
            debugInfoReporter.reportDynamicCall(element, declarationDescriptor);
            return true;
        }
        return false;
    }
}
