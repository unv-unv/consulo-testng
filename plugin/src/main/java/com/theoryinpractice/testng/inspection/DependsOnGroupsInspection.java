/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package com.theoryinpractice.testng.inspection;

import com.intellij.java.analysis.impl.codeInspection.BaseJavaLocalInspectionTool;
import com.intellij.java.language.psi.*;
import com.theoryinpractice.testng.util.TestNGUtil;
import consulo.annotation.component.ExtensionImpl;
import consulo.language.editor.inspection.InspectionToolState;
import consulo.language.editor.inspection.LocalQuickFix;
import consulo.language.editor.inspection.ProblemDescriptor;
import consulo.language.editor.inspection.ProblemHighlightType;
import consulo.language.editor.inspection.scheme.InspectionManager;
import consulo.language.editor.inspection.scheme.InspectionProfile;
import consulo.language.editor.inspection.scheme.InspectionProjectProfileManager;
import consulo.language.psi.PsiElement;
import consulo.language.psi.SmartPointerManager;
import consulo.language.psi.SmartPsiElementPointer;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Hani Suleiman
 * @since 2005-08-03
 */
@ExtensionImpl
public class DependsOnGroupsInspection extends BaseJavaLocalInspectionTool<DependsOnGroupsInspectionState> {
    private static final Logger LOGGER = Logger.getInstance("TestNG Runner");
    private static final Pattern PATTERN = Pattern.compile("\"([a-zA-Z0-9_\\-\\(\\)]*)\"");
    private static final ProblemDescriptor[] EMPTY = new ProblemDescriptor[0];

    public static String SHORT_NAME = "groupsTestNG";

    @Nonnull
    @Override
    public InspectionToolState<? extends DependsOnGroupsInspectionState> createStateProvider() {
        return new DependsOnGroupsInspectionState();
    }

    @Nonnull
    @Override
    public LocalizeValue getGroupDisplayName() {
        return LocalizeValue.localizeTODO("TestNG");
    }

    @Nonnull
    @Override
    public LocalizeValue getDisplayName() {
        return LocalizeValue.localizeTODO("Groups problem");
    }

    @Nonnull
    @Override
    public String getShortName() {
        return SHORT_NAME;
    }

    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    @Nullable
    public ProblemDescriptor[] checkClass(
        @Nonnull PsiClass psiClass,
        @Nonnull InspectionManager manager,
        boolean isOnTheFly,
        DependsOnGroupsInspectionState state
    ) {
        if (!psiClass.getContainingFile().isWritable()) {
            return null;
        }

        PsiAnnotation[] annotations = TestNGUtil.getTestNGAnnotations(psiClass);
        if (annotations.length == 0) {
            return EMPTY;
        }

        List<ProblemDescriptor> problemDescriptors = new ArrayList<ProblemDescriptor>();
        for (PsiAnnotation annotation : annotations) {

            PsiNameValuePair dep = null;
            PsiNameValuePair[] params = annotation.getParameterList().getAttributes();
            for (PsiNameValuePair param : params) {
                if (param.getName() != null && param.getName().matches("(groups|dependsOnGroups)")) {
                    dep = param;
                    break;
                }
            }

            if (dep != null) {
                final PsiAnnotationMemberValue value = dep.getValue();
                if (value != null) {
                    LOGGER.info("Found " + dep.getName() + " with: " + value.getText());
                    String text = value.getText();
                    if (value instanceof PsiReferenceExpression) {
                        final PsiElement resolve = ((PsiReferenceExpression) value).resolve();
                        if (resolve instanceof PsiField &&
                            ((PsiField) resolve).hasModifierProperty(PsiModifier.STATIC) &&
                            ((PsiField) resolve).hasModifierProperty(PsiModifier.FINAL)) {
                            final PsiExpression initializer = ((PsiField) resolve).getInitializer();
                            if (initializer != null) {
                                text = initializer.getText();
                            }
                        }
                    }
                    Matcher matcher = PATTERN.matcher(text);
                    while (matcher.find()) {
                        String methodName = matcher.group(1);
                        if (!state.groups.contains(methodName)) {
                            LOGGER.info("group doesn't exist:" + methodName);
                            ProblemDescriptor descriptor =
                                manager.createProblemDescriptor(annotation, "Group '" + methodName + "' is undefined.",
                                    new GroupNameQuickFix(methodName, psiClass),
                                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly
                                );
                            problemDescriptors.add(descriptor);

                        }
                    }
                }
            }
        }
        return problemDescriptors.toArray(new ProblemDescriptor[]{});
    }

    private class GroupNameQuickFix implements LocalQuickFix {
        String myGroupName;
        private SmartPsiElementPointer<PsiClass> myPointer;

        public GroupNameQuickFix(@Nonnull String groupName, PsiClass psiClass) {
            myGroupName = groupName;
            myPointer = SmartPointerManager.createPointer(psiClass);
        }

        @Nonnull
        @Override
        public LocalizeValue getName() {
            return LocalizeValue.localizeTODO("Add '" + myGroupName + "' as a defined test group.");
        }

        public void applyFix(@Nonnull Project project, @Nonnull ProblemDescriptor problemDescriptor) {
            PsiClass element = myPointer.getElement();
            if (element == null) {
                return;
            }
            final InspectionProfile inspectionProfile =
                InspectionProjectProfileManager.getInstance(project).getInspectionProfile();
            inspectionProfile.<DependsOnGroupsInspection, DependsOnGroupsInspectionState>modifyToolSettings(
                SHORT_NAME,
                element,
                (inspectionTool, state) ->
                    state.groups.add(myGroupName)
            );
        }
    }
}
