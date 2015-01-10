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

package org.jetbrains.jet.plugin.decompiler.navigation

import org.jetbrains.kotlin.psi.KotlinDeclarationNavigationPolicy
import org.jetbrains.kotlin.psi.JetElement
import org.jetbrains.kotlin.psi.JetDeclaration
import com.intellij.openapi.project.DumbService

public class KotlinDeclarationNavigationPolicyImpl : KotlinDeclarationNavigationPolicy {
    override fun getNavigationElement(declaration: JetDeclaration): JetElement? {
        if (DumbService.isDumb(declaration.getProject())) {
            return null
        }
        if (declaration.getContainingJetFile().isCompiled()) {
            return JetSourceNavigationHelper.replaceBySourceDeclarationIfPresent(declaration)
        }
        return null
    }
}
