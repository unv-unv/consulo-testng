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
import com.intellij.java.language.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.java.language.psi.javadoc.PsiDocComment;
import com.intellij.java.language.psi.javadoc.PsiDocTag;
import com.intellij.java.language.psi.javadoc.PsiDocToken;
import com.theoryinpractice.testng.util.TestNGUtil;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.CommonBundle;
import consulo.language.editor.inspection.LocalInspectionToolSession;
import consulo.language.editor.inspection.LocalQuickFix;
import consulo.language.editor.inspection.ProblemDescriptor;
import consulo.language.editor.inspection.ProblemsHolder;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiElementVisitor;
import consulo.language.psi.PsiWhiteSpace;
import consulo.language.psi.util.PsiTreeUtil;
import consulo.language.util.IncorrectOperationException;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.ui.ex.awt.Messages;
import consulo.util.lang.StringUtil;
import jakarta.annotation.Nonnull;

/**
 * @author Hani Suleiman
 * @since 2005-08-03
 */
@ExtensionImpl
public class ConvertJavadocInspection extends BaseJavaLocalInspectionTool {
    private static final String TESTNG_PREFIX = "testng.";
    private static final LocalizeValue DISPLAY_NAME = LocalizeValue.localizeTODO("Convert TestNG Javadoc to 1.5 annotations");

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
    @Override
    public String getShortName() {
        return "ConvertJavadoc";
    }

    @Nonnull
    public PsiElementVisitor buildVisitorImpl(
        @Nonnull ProblemsHolder holder,
        boolean isOnTheFly,
        LocalInspectionToolSession session,
        Object o
    ) {
        return new JavaElementVisitor() {
            @Override
            public void visitDocTag(final PsiDocTag tag) {
                if (tag.getName().startsWith(TESTNG_PREFIX)) {
                    holder.registerProblem(tag, DISPLAY_NAME.get(), new ConvertJavadocQuickfix());
                }
            }
        };
    }

    private static class ConvertJavadocQuickfix implements LocalQuickFix {
        private static final Logger LOG = Logger.getInstance("#" + ConvertJavadocQuickfix.class.getName());

        @Nonnull
        @Override
        public LocalizeValue getName() {
            return DISPLAY_NAME;
        }

        public void applyFix(@Nonnull Project project, @Nonnull ProblemDescriptor descriptor) {
            final PsiDocTag tag = (PsiDocTag) descriptor.getPsiElement();
            if (!TestNGUtil.checkTestNGInClasspath(tag)) {
                return;
            }
            final PsiMember member = PsiTreeUtil.getParentOfType(tag, PsiMember.class);
            LOG.assertTrue(member != null);
            String annotationName = StringUtil.capitalize(tag.getName().substring(TESTNG_PREFIX.length()));
            int dash = annotationName.indexOf('-');
            if (dash > -1) {
                annotationName =
                    annotationName.substring(0, dash) + Character.toUpperCase(annotationName.charAt(dash + 1)) + annotationName.substring(
                        dash + 2);
            }
            annotationName = "org.testng.annotations." + annotationName;
            final StringBuffer annotationText = new StringBuffer("@");
            annotationText.append(annotationName);
            final PsiClass annotationClass =
                JavaPsiFacade.getInstance(member.getProject()).findClass(annotationName, member.getResolveScope());
            PsiElement[] dataElements = tag.getDataElements();
            if (dataElements.length > 1) {
                annotationText.append('(');
            }
            if (annotationClass != null) {
                for (PsiMethod attribute : annotationClass.getMethods()) {
                    boolean stripQuotes = false;
                    PsiType returnType = attribute.getReturnType();
                    if (returnType instanceof PsiPrimitiveType) {
                        stripQuotes = true;
                    }
                    for (int i = 0; i < dataElements.length; i++) {
                        String text = dataElements[i].getText();
                        int equals = text.indexOf('=');
                        String value;
                        final String key = equals == -1 ? text : text.substring(0, equals).trim();
                        if (!key.equals(attribute.getName())) {
                            continue;
                        }
                        annotationText.append(key).append(" = ");
                        if (equals == -1) {
                            //no equals, so we look in the next token
                            String next = dataElements[++i].getText().trim();
                            //it's an equals by itself
                            if (next.length() == 1) {
                                value = dataElements[++i].getText().trim();
                            }
                            else {
                                //otherwise, it's foo =bar, so we strip equals
                                value = next.substring(1, next.length()).trim();
                            }
                        }
                        else {
                            //check if the value is in the first bit too
                            if (equals < text.length() - 1) {
                                //we have stuff after equals, great
                                value = text.substring(equals + 1, text.length()).trim();
                            }
                            else {
                                //nothing after equals, so we just get the next element
                                value = dataElements[++i].getText().trim();
                            }
                        }
                        if (stripQuotes && value.charAt(0) == '\"') {
                            value = value.substring(1, value.length() - 1);
                        }
                        annotationText.append(value);
                    }
                }
            }

            if (dataElements.length > 1) {
                annotationText.append(')');
            }

            try {
                final PsiElement inserted = member.getModifierList().addBefore(
                    JavaPsiFacade.getInstance(tag.getProject())
                        .getElementFactory()
                        .createAnnotationFromText(annotationText.toString(), member),
                    member.getModifierList().getFirstChild()
                );
                JavaCodeStyleManager.getInstance(project).shortenClassReferences(inserted);


                final PsiDocComment docComment = PsiTreeUtil.getParentOfType(tag, PsiDocComment.class);
                LOG.assertTrue(docComment != null);
                //cleanup
                tag.delete();
                for (PsiElement element : docComment.getChildren()) {
                    //if it's anything other than a doc token, then it must stay
                    if (element instanceof PsiWhiteSpace) {
                        continue;
                    }
                    if (!(element instanceof PsiDocToken)) {
                        return;
                    }
                    PsiDocToken docToken = (PsiDocToken) element;
                    if (docToken.getTokenType() == JavaDocTokenType.DOC_COMMENT_DATA && docToken.getText().trim().length() > 0) {
                        return;
                    }
                }
                //at this point, our doc don't have non-empty comments, nor any tags, so we can delete it.
                docComment.delete();
            }
            catch (IncorrectOperationException e) {
                Messages.showErrorDialog(project, e.getMessage(), CommonBundle.getErrorTitle());
            }
        }
    }

}
