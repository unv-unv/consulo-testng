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
import com.intellij.java.language.psi.javadoc.PsiDocComment;
import com.theoryinpractice.testng.util.TestNGUtil;
import consulo.annotation.component.ExtensionImpl;
import consulo.language.editor.inspection.LocalInspectionToolSession;
import consulo.language.editor.inspection.LocalQuickFix;
import consulo.language.editor.inspection.ProblemDescriptor;
import consulo.language.editor.inspection.ProblemsHolder;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiElementVisitor;
import consulo.language.util.IncorrectOperationException;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.util.lang.StringUtil;
import jakarta.annotation.Nonnull;

/**
 * @author Hani Suleiman
 * @since 2005-08-03
 */
@ExtensionImpl
public class ConvertAnnotationInspection extends BaseJavaLocalInspectionTool {
    private static final LocalizeValue DISPLAY_NAME = LocalizeValue.localizeTODO("Convert TestNG annotations to javadocs");

    @Nonnull
    public PsiElementVisitor buildVisitorImpl(
        @Nonnull ProblemsHolder holder,
        boolean isOnTheFly,
        LocalInspectionToolSession session,
        Object o
    ) {
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(final PsiAnnotation annotation) {
                final String qualifiedName = annotation.getQualifiedName();
                if (qualifiedName != null && qualifiedName.startsWith("org.testng.annotations")) {
                    holder.registerProblem(annotation, DISPLAY_NAME.get(), new ConvertAnnotationQuickFix());
                }
            }
        };
    }


    @Override
    public boolean isEnabledByDefault() {
        return false;
    }

    @Nonnull
    @Override
    public LocalizeValue getGroupDisplayName() {
        return TestNGUtil.TESTNG_GROUP_NAME;
    }

    @Nonnull
    @Override
    public LocalizeValue getDisplayName() {
        return DISPLAY_NAME;
    }

    @Nonnull
    public String getShortName() {
        return "ConvertAnnotations";
    }

    private static class ConvertAnnotationQuickFix implements LocalQuickFix {
        private static final Logger LOG = Logger.getInstance("#" + ConvertAnnotationQuickFix.class.getName());

        @Nonnull
        @Override
        public LocalizeValue getName() {
            return DISPLAY_NAME;
        }

        public void applyFix(@Nonnull Project project, @Nonnull ProblemDescriptor descriptor) {
            final PsiAnnotation annotation = (PsiAnnotation) descriptor.getPsiElement();
            final PsiElement parent = annotation.getParent();
            if (parent instanceof PsiModifierList) {
                try {
                    final PsiModifierListOwner element = (PsiModifierListOwner) parent.getParent();
                    final PsiElementFactory factory = JavaPsiFacade.getInstance(element.getProject()).getElementFactory();
                    PsiDocComment docComment = ((PsiDocCommentOwner) element).getDocComment();
                    if (docComment == null) {
                        docComment = factory.createDocCommentFromText("/**\n */");
                        docComment = (PsiDocComment) element.addBefore(docComment, parent);
                    }
                    StringBuilder text = new StringBuilder(convertAnnotationClassToJavadocElement(annotation.getQualifiedName()));
                    PsiAnnotationParameterList list = annotation.getParameterList();
                    for (PsiNameValuePair pair : list.getAttributes()) {
                        text.append(' ');
                        if (pair.getName() != null) {
                            text.append(pair.getName());
                        }
                        else {
                            text.append("value");
                        }
                        text.append(" = \"");

                        String parameterText = StringUtil.stripQuotesAroundValue(pair.getValue().getText());
                        if (parameterText.startsWith("{")) {
                            parameterText = parameterText.replaceAll("(\\{\\\"|\\\"\\}|\\\"\\w*\\s*\\,\\s*\\w*\\\")", " ").trim();
                        }
                        text.append(parameterText);
                        text.append('\"');
                    }
                    docComment.addAfter(factory.createDocTagFromText('@' + text.toString()), docComment.getFirstChild());
                    annotation.delete();
                }
                catch (IncorrectOperationException e) {
                    LOG.error(e);
                }
            }
        }

        private static String convertAnnotationClassToJavadocElement(String annotationFqn) {
            char[] chars = annotationFqn.replace("org.testng.annotations", "testng").toCharArray();

            boolean skippedFirst = false;
            StringBuffer sb = new StringBuffer();
            for (char aChar : chars) {
                if ((aChar >= 'A') && (aChar <= 'Z')) {
                    if (skippedFirst) {
                        sb.append('-');
                    }
                    else {
                        skippedFirst = true;
                    }
                }
                sb.append(String.valueOf(aChar));
            }

            return sb.toString().toLowerCase();
        }
    }
}
