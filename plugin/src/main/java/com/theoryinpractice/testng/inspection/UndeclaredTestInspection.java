/*
 * Copyright 2000-2012 JetBrains s.r.o.
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

/*
 * User: anna
 * Date: 18-Jun-2007
 */
package com.theoryinpractice.testng.inspection;

import com.intellij.java.analysis.impl.codeInspection.BaseJavaLocalInspectionTool;
import com.intellij.java.language.psi.PsiClass;
import com.intellij.java.language.psi.PsiIdentifier;
import com.intellij.java.language.psi.util.PsiClassUtil;
import com.theoryinpractice.testng.configuration.browser.SuiteBrowser;
import com.theoryinpractice.testng.util.TestNGUtil;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.Result;
import consulo.fileChooser.FileChooserDescriptorFactory;
import consulo.fileChooser.IdeaFileChooser;
import consulo.language.editor.WriteCommandAction;
import consulo.language.editor.inspection.LocalQuickFix;
import consulo.language.editor.inspection.ProblemDescriptor;
import consulo.language.editor.inspection.ProblemHighlightType;
import consulo.language.editor.inspection.scheme.InspectionManager;
import consulo.language.psi.PsiDirectory;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiFileFactory;
import consulo.language.psi.PsiManager;
import consulo.language.psi.search.PsiNonJavaFileReferenceProcessor;
import consulo.language.psi.search.PsiSearchHelper;
import consulo.language.psi.util.PsiTreeUtil;
import consulo.language.util.IncorrectOperationException;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import consulo.xml.ide.highlighter.XmlFileType;
import consulo.xml.psi.xml.XmlAttribute;
import consulo.xml.psi.xml.XmlFile;
import consulo.xml.psi.xml.XmlTag;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@ExtensionImpl
public class UndeclaredTestInspection extends BaseJavaLocalInspectionTool {
    private static final Logger LOG = Logger.getInstance(UndeclaredTestInspection.class);

    @Nonnull
    @Override
    public LocalizeValue getGroupDisplayName() {
        return TestNGUtil.TESTNG_GROUP_NAME;
    }

    @Override
    public boolean isEnabledByDefault() {
        return false;
    }

    @Nonnull
    @Override
    public LocalizeValue getDisplayName() {
        return LocalizeValue.localizeTODO("Undeclared test");
    }

    @Nonnull
    @Override
    public String getShortName() {
        return "UndeclaredTests";
    }

    @Nullable
    @Override
    public ProblemDescriptor[] checkClass(
        @Nonnull final PsiClass aClass,
        @Nonnull final InspectionManager manager,
        final boolean isOnTheFly,
        Object state
    ) {
        if (TestNGUtil.hasTest(aClass) && PsiClassUtil.isRunnableClass(aClass, true)) {
            final Project project = aClass.getProject();
            final String qName = aClass.getQualifiedName();
            if (qName == null) {
                return null;
            }

            final List<String> names = new ArrayList<String>();
            for (int i = 0; i < qName.length(); i++) {
                if (qName.charAt(i) == '.') {
                    names.add(qName.substring(0, i));
                }
            }
            names.add(qName);
            Collections.reverse(names);

            for (final String name : names) {
                final boolean isFullName = qName.equals(name);
                final boolean[] found = new boolean[]{false};
                PsiSearchHelper.SERVICE.getInstance(project).processUsagesInNonJavaFiles(name, new PsiNonJavaFileReferenceProcessor() {
                    @Override
                    public boolean process(final PsiFile file, final int startOffset, final int endOffset) {
                        if (file.findReferenceAt(startOffset) != null) {
                            if (!isFullName) { //special package tag required
                                final XmlTag tag = PsiTreeUtil.getParentOfType(file.findElementAt(startOffset), XmlTag.class);
                                if (tag == null || !tag.getName().equals("package")) {
                                    return true;
                                }
                                final XmlAttribute attribute = tag.getAttribute("name");
                                if (attribute == null) {
                                    return true;
                                }
                                final String value = attribute.getValue();
                                if (value == null) {
                                    return true;
                                }
                                if (!value.endsWith(".*")) {
                                    return true;
                                }
                            }
                            found[0] = true;
                            return false;
                        }
                        return true;
                    }
                }, new TestNGSearchScope(project));
                if (found[0]) {
                    return null;
                }
            }
            final PsiIdentifier nameIdentifier = aClass.getNameIdentifier();
            LOG.assertTrue(nameIdentifier != null);
            return new ProblemDescriptor[]{
                manager.createProblemDescriptor(
                    nameIdentifier,
                    "Undeclared test \'" + aClass.getName() + "\'",
                    isOnTheFly,
                    new LocalQuickFix[]{
                        new RegisterClassFix(aClass),
                        new CreateTestngFix()
                    },
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                )
            };
        }
        return null;
    }

    private static class RegisterClassFix implements LocalQuickFix {
        private final PsiClass myClass;

        public RegisterClassFix(final PsiClass aClass) {
            myClass = aClass;
        }

        @Override
        @Nonnull
        public LocalizeValue getName() {
            return LocalizeValue.localizeTODO("Register \'" + myClass.getName() + "\'");
        }

        @Override
        public void applyFix(@Nonnull final Project project, @Nonnull ProblemDescriptor descriptor) {
            final PsiClass psiClass = PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiClass.class);
            LOG.assertTrue(psiClass != null);
            SwingUtilities.invokeLater(new Runnable() { //need to show dialog

                @Override
                public void run() {
                    final String testngXmlPath = new SuiteBrowser(project).showDialog();
                    if (testngXmlPath == null) {
                        return;
                    }
                    final VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(testngXmlPath);
                    LOG.assertTrue(virtualFile != null);
                    final PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
                    LOG.assertTrue(psiFile instanceof XmlFile);
                    final XmlFile testngXML = (XmlFile) psiFile;
                    new WriteCommandAction(project, getName().get(), testngXML) {
                        @Override
                        protected void run(final Result result) throws Throwable {
                            patchTestngXml(testngXML, psiClass);
                        }
                    }.execute();
                }
            });
        }
    }

    //make public for tests only
    public static void patchTestngXml(final XmlFile testngXML, final PsiClass psiClass) {
        final XmlTag rootTag = testngXML.getDocument().getRootTag();
        if (rootTag != null && rootTag.getName().equals("suite")) {
            try {
                XmlTag testTag = rootTag.findFirstSubTag("test");
                if (testTag == null) {
                    testTag = (XmlTag) rootTag.add(rootTag.createChildTag("test", rootTag.getNamespace(), null, false));
                    testTag.setAttribute("name", psiClass.getName());
                }
                XmlTag classesTag = testTag.findFirstSubTag("classes");
                if (classesTag == null) {
                    classesTag = (XmlTag) testTag.add(testTag.createChildTag("classes", testTag.getNamespace(), null, false));
                }
                final XmlTag classTag = (XmlTag) classesTag.add(classesTag.createChildTag("class", classesTag.getNamespace(), null, false));
                final String qualifiedName = psiClass.getQualifiedName();
                LOG.assertTrue(qualifiedName != null);
                classTag.setAttribute("name", qualifiedName);
            }
            catch (IncorrectOperationException e) {
                LOG.error(e);
            }
        }
    }

    private static class CreateTestngFix implements LocalQuickFix {
        @Nonnull
        @Override
        public LocalizeValue getName() {
            return LocalizeValue.localizeTODO("Create suite");
        }

        @Override
        public void applyFix(@Nonnull final Project project, @Nonnull final ProblemDescriptor descriptor) {
            final PsiClass psiClass = PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiClass.class);
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    final VirtualFile file =
                        IdeaFileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor(), project, null);
                    if (file != null) {
                        final PsiManager psiManager = PsiManager.getInstance(project);
                        final PsiDirectory directory = psiManager.findDirectory(file);
                        LOG.assertTrue(directory != null);
                        new WriteCommandAction(project, getName().get(), null) {
                            @Override
                            protected void run(final Result result) throws Throwable {
                                XmlFile testngXml =
                                    (XmlFile) PsiFileFactory.getInstance(psiManager.getProject()).createFileFromText(
                                        "testng.xml",
                                        XmlFileType.INSTANCE,
                                        "<!DOCTYPE suite SYSTEM \"http://testng.org/testng-1.0.dtd\">\n<suite></suite>"
                                    );
                                try {
                                    testngXml = (XmlFile) directory.add(testngXml);
                                }
                                catch (IncorrectOperationException e) {
                                    //todo suggest new name
                                    return;
                                }
                                patchTestngXml(testngXml, psiClass);
                            }
                        }.execute();
                    }
                }
            });
        }
    }
}
