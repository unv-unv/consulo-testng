/*
 * Copyright 2000-2016 JetBrains s.r.o.
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

package com.theoryinpractice.testng.configuration;

import com.intellij.java.execution.JavaExecutionUtil;
import com.intellij.java.execution.ShortenCommandLine;
import com.intellij.java.execution.impl.MethodBrowser;
import com.intellij.java.execution.impl.testDiscovery.TestDiscoveryExtension;
import com.intellij.java.execution.impl.ui.*;
import com.intellij.java.language.impl.ui.EditorTextFieldWithBrowseButton;
import com.intellij.java.language.psi.JavaCodeFragment;
import com.intellij.java.language.psi.PsiClass;
import com.intellij.java.language.psi.PsiJavaCodeReferenceElement;
import com.intellij.java.language.psi.PsiMethod;
import com.intellij.java.language.util.TreeClassChooser;
import com.intellij.java.language.util.TreeClassChooserFactory;
import com.theoryinpractice.testng.MessageInfoException;
import com.theoryinpractice.testng.configuration.browser.GroupBrowser;
import com.theoryinpractice.testng.configuration.browser.PackageBrowser;
import com.theoryinpractice.testng.configuration.browser.SuiteBrowser;
import com.theoryinpractice.testng.configuration.browser.TestClassBrowser;
import com.theoryinpractice.testng.model.*;
import com.theoryinpractice.testng.util.TestNGUtil;
import consulo.execution.ExecutionBundle;
import consulo.execution.configuration.ui.SettingsEditor;
import consulo.execution.test.TestSearchScope;
import consulo.execution.ui.awt.BrowseModuleValueActionListener;
import consulo.fileChooser.FileChooserDescriptor;
import consulo.fileChooser.FileChooserDescriptorFactory;
import consulo.language.editor.ui.awt.EditorTextField;
import consulo.language.plain.PlainTextLanguage;
import consulo.language.psi.PsiElement;
import consulo.language.psi.scope.GlobalSearchScope;
import consulo.logging.Logger;
import consulo.module.Module;
import consulo.module.ui.awt.ModuleDescriptionsComboBox;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.project.Project;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.awt.*;
import consulo.ui.ex.awt.table.TableView;
import consulo.util.lang.function.Condition;
import consulo.virtualFileSystem.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import jakarta.annotation.Nonnull;
import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.text.Document;
import javax.swing.text.PlainDocument;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Map;

public class TestNGConfigurationEditor<T extends TestNGConfiguration> extends SettingsEditor<T> implements PanelWithAnchor
{
	//private static final Logger LOGGER = Logger.getInstance("TestNG Runner");
	private final Project project;

	private JPanel panel;

	private LabeledComponent<EditorTextFieldWithBrowseButton> classField;
	private LabeledComponent<ModuleDescriptionsComboBox> moduleClasspath;
	private JrePathEditor alternateJDK;
	private final ConfigurationModuleSelector moduleSelector;
	private JComboBox<TestType> myTestKind;
	private JBLabel myTestLabel;
	private final TestNGConfigurationModel model;
	private LabeledComponent<EditorTextFieldWithBrowseButton> methodField;
	private LabeledComponent<EditorTextFieldWithBrowseButton> packageField;
	private LabeledComponent<TextFieldWithBrowseButton.NoPathCompletion> groupField;
	private LabeledComponent<TextFieldWithBrowseButton> suiteField;
	private JComponent anchor;
	private JRadioButton packagesInProject;
	private JRadioButton packagesInModule;
	private JRadioButton packagesAcrossModules;
	private JPanel packagePanel;
	private TestNGParametersTableModel propertiesTableModel;
	private LabeledComponent<TextFieldWithBrowseButton> propertiesFile;
	private LabeledComponent<TextFieldWithBrowseButton> outputDirectory;
	private TableView propertiesTableView;
	private JPanel commonParametersPanel;//temp compilation problems
	private JList myListenersList;
	private JCheckBox myUseDefaultReportersCheckBox;
	private LabeledComponent<JPanel> myPattern;
	private JPanel myPropertiesPanel;
	private JPanel myListenersPanel;
	private LabeledComponent<ShortenCommandLineModeCombo> myShortenCommandLineCombo;
	TextFieldWithBrowseButton myPatternTextField;
	private final CommonJavaParametersPanel commonJavaParameters = new CommonJavaParametersPanel();
	private final ArrayList<Map.Entry<String, String>> propertiesList = new ArrayList<>();
	private TestNGListenersTableModel listenerModel;

	private TestNGConfiguration config;

	public TestNGConfigurationEditor(Project project)
	{
		this.project = project;
		BrowseModuleValueActionListener[] browseListeners = new BrowseModuleValueActionListener[]{
				new PackageBrowser(project),
				new TestClassBrowser(project, this),
				new TestNGMethodBrowser(project),
				new GroupBrowser(project, this),
				new SuiteBrowser(project),
				new TestClassBrowser(project, this)
				{
					@Override
					protected void onClassChoosen(PsiClass psiClass)
					{
						final JTextField textField = myPatternTextField.getTextField();
						final String text = textField.getText();
						textField.setText(text + (text.length() > 0 ? "||" : "") + psiClass.getQualifiedName());
					}

					@Override
					public void actionPerformed(ActionEvent e)
					{
						showDialog();
					}
				}
		};
		model = new TestNGConfigurationModel(project);
		model.setListener(this);
		createView();
		moduleSelector = new ConfigurationModuleSelector(project, getModulesComponent());
		alternateJDK.setDefaultJreSelector(DefaultJreSelector.fromModuleDependencies(getModulesComponent(), false));
		commonJavaParameters.setModuleContext(moduleSelector.getModule());
		moduleClasspath.getComponent().addActionListener(new ActionListener()
		{
			public void actionPerformed(ActionEvent e)
			{
				commonJavaParameters.setModuleContext(moduleSelector.getModule());
			}
		});
		commonJavaParameters.setHasModuleMacro();

		final JPanel panel = myPattern.getComponent();
		panel.setLayout(new BorderLayout());
		myPatternTextField = new TextFieldWithBrowseButton();
		myPatternTextField.setButtonIcon(PlatformIconGroup.generalAdd());
		panel.add(myPatternTextField, BorderLayout.CENTER);

		ArrayList<TestType> testTypes = new ArrayList<>();

		for(TestType type : TestType.values())
		{
			if(type != TestType.SOURCE || TestDiscoveryExtension.TESTDISCOVERY_ENABLED)
			{
				testTypes.add(type);
			}
		}
		myTestKind.setModel(new CollectionComboBoxModel<>(testTypes));
		myTestKind.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				TestNGConfigurationEditor.this.model.setType((TestType) myTestKind.getSelectedItem());
			}
		});
		myTestKind.setRenderer(new ColoredListCellRenderer<TestType>()
		{
			@Override
			protected void customizeCellRenderer(@Nonnull JList<? extends TestType> jList, TestType value, int i, boolean b, boolean b1)
			{
				if(value != null)
				{
					append(value.getPresentableName());
				}
			}
		});
		registerListener(new JRadioButton[]{
				packagesInProject,
				packagesInModule,
				packagesAcrossModules
		}, null);
		packagesInProject.addChangeListener(new ChangeListener()
		{
			public void stateChanged(ChangeEvent e)
			{
				evaluateModuleClassPath();
			}
		});

		LabeledComponent[] components = new LabeledComponent[]{
				packageField,
				classField,
				methodField,
				groupField,
				suiteField,
				myPattern
		};
		for(int i = 0; i < components.length; i++)
		{
			JComponent field = components[i].getComponent();
			Object document = model.getDocument(i);
			if(field instanceof TextFieldWithBrowseButton)
			{
				((TextFieldWithBrowseButton) field).getTextField().setDocument((PlainDocument) document);
			}
			else if(field instanceof EditorTextFieldWithBrowseButton)
			{
				document = ((EditorTextFieldWithBrowseButton) field).getChildComponent().getDocument();
			}
			else
			{
				field = myPatternTextField;
				document = new PlainDocument();
				((TextFieldWithBrowseButton) field).getTextField().setDocument((Document) document);
			}

			browseListeners[i].setField((ComponentWithBrowseButton) field);
			if(browseListeners[i] instanceof MethodBrowser)
			{
				final EditorTextField childComponent = (EditorTextField) ((ComponentWithBrowseButton) field).getChildComponent();
				((MethodBrowser) browseListeners[i]).installCompletion(childComponent);
				document = childComponent.getDocument();
			}
			model.setDocument(i, document);
		}
		model.setType(TestType.CLASS);
		propertiesFile.getComponent().getTextField().setDocument(model.getPropertiesFileDocument());
		outputDirectory.getComponent().getTextField().setDocument(model.getOutputDirectoryDocument());

		commonJavaParameters.setProgramParametersLabel(ExecutionBundle.message("junit.configuration.test.runner.parameters.label"));

		myShortenCommandLineCombo.setComponent(new ShortenCommandLineModeCombo(project, alternateJDK, getModulesComponent()));
		setAnchor(outputDirectory.getLabel());
		alternateJDK.setAnchor(moduleClasspath.getLabel());
		commonJavaParameters.setAnchor(moduleClasspath.getLabel());
		myShortenCommandLineCombo.setAnchor(moduleClasspath.getLabel());
	}

	private void evaluateModuleClassPath()
	{
		final boolean allPackagesInProject = packagesInProject.isSelected() && packagePanel.isVisible();
		moduleClasspath.setEnabled(!allPackagesInProject);
		if(allPackagesInProject)
		{
			moduleClasspath.getComponent().setSelectedItem(null);
		}
	}

	private void redisplay()
	{
		final TestType testKind = (TestType) myTestKind.getSelectedItem();
		if(testKind == TestType.PACKAGE)
		{
			packagePanel.setVisible(true);
			packageField.setVisible(true);
			classField.setVisible(false);
			methodField.setVisible(false);
			groupField.setVisible(false);
			suiteField.setVisible(false);
			myPattern.setVisible(false);
		}
		else if(testKind == TestType.CLASS)
		{
			packagePanel.setVisible(false);
			classField.setVisible(true);
			methodField.setVisible(false);
			groupField.setVisible(false);
			suiteField.setVisible(false);
			myPattern.setVisible(false);
		}
		else if(testKind == TestType.METHOD || testKind == TestType.SOURCE)
		{
			packagePanel.setVisible(false);
			classField.setVisible(true);
			methodField.setVisible(true);
			groupField.setVisible(false);
			suiteField.setVisible(false);
			myPattern.setVisible(false);
		}
		else if(testKind == TestType.GROUP)
		{
			packagePanel.setVisible(true);
			classField.setVisible(false);
			methodField.setVisible(false);
			groupField.setVisible(true);
			suiteField.setVisible(false);
			myPattern.setVisible(false);
		}
		else if(testKind == TestType.SUITE)
		{
			packagePanel.setVisible(true);
			classField.setVisible(false);
			methodField.setVisible(false);
			groupField.setVisible(false);
			suiteField.setVisible(true);
			myPattern.setVisible(false);
		}
		else if(testKind == TestType.PATTERN)
		{
			packagePanel.setVisible(true);
			classField.setVisible(false);
			methodField.setVisible(false);
			groupField.setVisible(false);
			suiteField.setVisible(false);
			myPattern.setVisible(true);
		}
	}

	public String getClassName()
	{
		return classField.getComponent().getText();
	}

	public ModuleDescriptionsComboBox getModulesComponent()
	{
		return moduleClasspath.getComponent();
	}

	@Override
	protected void resetEditorFrom(@NotNull TestNGConfiguration config)
	{
		this.config = config;
		model.reset(config);
		commonJavaParameters.reset(config);
		getModuleSelector().reset(config);
		TestData data = config.getPersistantData();
		TestSearchScope scope = data.getScope();
		if(scope == TestSearchScope.SINGLE_MODULE)
		{
			packagesInModule.setSelected(true);
		}
		else if(scope == TestSearchScope.MODULE_WITH_DEPENDENCIES)
		{
			packagesAcrossModules.setSelected(true);
		}
		else
		{
			packagesInProject.setSelected(true);
		}
		evaluateModuleClassPath();
		alternateJDK.setPathOrName(config.ALTERNATIVE_JRE_PATH, config.ALTERNATIVE_JRE_PATH_ENABLED);
		propertiesList.clear();
		propertiesList.addAll(data.TEST_PROPERTIES.entrySet());
		propertiesTableModel.setParameterList(propertiesList);

		listenerModel.setListenerList(data.TEST_LISTENERS);
		myUseDefaultReportersCheckBox.setSelected(data.USE_DEFAULT_REPORTERS);
		myShortenCommandLineCombo.getComponent().setSelectedItem(config.getShortenCommandLine());
	}

	@Override
	public void applyEditorTo(@NotNull TestNGConfiguration config)
	{
		model.apply(getModuleSelector().getModule(), config);
		getModuleSelector().applyTo(config);
		TestData data = config.getPersistantData();
		final TestType testKind = (TestType) myTestKind.getSelectedItem();
		if(testKind != TestType.CLASS && testKind != TestType.METHOD && testKind != TestType.SOURCE)
		{
			if(packagesInProject.isSelected())
			{
				data.setScope(TestSearchScope.WHOLE_PROJECT);
			}
			else if(packagesInModule.isSelected())
			{
				data.setScope(TestSearchScope.SINGLE_MODULE);
			}
			else if(packagesAcrossModules.isSelected())
			{
				data.setScope(TestSearchScope.MODULE_WITH_DEPENDENCIES);
			}
		}
		else
		{
			data.setScope(TestSearchScope.MODULE_WITH_DEPENDENCIES);
		}
		commonJavaParameters.applyTo(config);
		config.ALTERNATIVE_JRE_PATH = alternateJDK.getJrePathOrName();
		config.ALTERNATIVE_JRE_PATH_ENABLED = alternateJDK.isAlternativeJreSelected();

		data.TEST_PROPERTIES.clear();
		for(Map.Entry<String, String> entry : propertiesList)
		{
			data.TEST_PROPERTIES.put(entry.getKey(), entry.getValue());
		}

		data.TEST_LISTENERS.clear();
		data.TEST_LISTENERS.addAll(listenerModel.getListenerList());

		data.USE_DEFAULT_REPORTERS = myUseDefaultReportersCheckBox.isSelected();
		config.setShortenCommandLine((ShortenCommandLine) myShortenCommandLineCombo.getComponent().getSelectedItem());
	}

	public ConfigurationModuleSelector getModuleSelector()
	{
		return moduleSelector;
	}

	@NotNull
	@Override
	protected JComponent createEditor()
	{
		return panel;
	}

	@Override
	public JComponent getAnchor()
	{
		return anchor;
	}

	@Override
	public void setAnchor(JComponent anchor)
	{
		this.anchor = anchor;
		methodField.setAnchor(anchor);
		packageField.setAnchor(anchor);
		groupField.setAnchor(anchor);
		suiteField.setAnchor(anchor);
		outputDirectory.setAnchor(anchor);
		classField.setAnchor(anchor);
		myPattern.setAnchor(anchor);
		myTestLabel.setAnchor(anchor);
	}

	private void createUIComponents()
	{
		myShortenCommandLineCombo = new LabeledComponent<>();
	}

	private static void registerListener(JRadioButton[] buttons, ChangeListener changelistener)
	{
		ButtonGroup buttongroup = new ButtonGroup();
		for(JRadioButton button : buttons)
		{
			button.getModel().addChangeListener(changelistener);
			buttongroup.add(button);
		}

		if(buttongroup.getSelection() == null)
		{
			buttongroup.setSelected(buttons[0].getModel(), true);
		}
	}

	private void createView()
	{
		commonParametersPanel.add(commonJavaParameters, BorderLayout.CENTER);
		classField.setComponent(new EditorTextFieldWithBrowseButton(project, true, new JavaCodeFragment.VisibilityChecker()
		{
			@Override
			public Visibility isDeclarationVisible(PsiElement declaration, PsiElement place)
			{
				if(declaration instanceof PsiClass && place.getParent() instanceof PsiJavaCodeReferenceElement)
				{
					return Visibility.VISIBLE;
				}
				try
				{
					if(declaration instanceof PsiClass && new TestClassBrowser(project, TestNGConfigurationEditor.this).getFilter().isAccepted((PsiClass) declaration))
					{
						return Visibility.VISIBLE;
					}
				}
				catch(MessageInfoException e)
				{
					return Visibility.NOT_VISIBLE;
				}
				return Visibility.NOT_VISIBLE;
			}
		}));

		final EditorTextFieldWithBrowseButton methodEditorTextField = new EditorTextFieldWithBrowseButton(project, true, JavaCodeFragment.VisibilityChecker.EVERYTHING_VISIBLE, PlainTextLanguage
				.INSTANCE.getAssociatedFileType());
		methodField.setComponent(methodEditorTextField);

		groupField.setComponent(new TextFieldWithBrowseButton.NoPathCompletion());
		suiteField.setComponent(new TextFieldWithBrowseButton());
		packageField.setVisible(true);
		packageField.setEnabled(true);
		packageField.setComponent(new EditorTextFieldWithBrowseButton(project, false));


		TextFieldWithBrowseButton outputDirectoryButton = new TextFieldWithBrowseButton();
		outputDirectory.setComponent(outputDirectoryButton);
		outputDirectoryButton.addBrowseFolderListener("TestNG", "Select test output directory", project, FileChooserDescriptorFactory.createSingleFolderDescriptor());
		moduleClasspath.setEnabled(true);

		propertiesTableModel = new TestNGParametersTableModel();
		listenerModel = new TestNGListenersTableModel();

		TextFieldWithBrowseButton textFieldWithBrowseButton = new TextFieldWithBrowseButton();
		propertiesFile.setComponent(textFieldWithBrowseButton);

		FileChooserDescriptor propertiesFileDescriptor = new FileChooserDescriptor(true, false, false, false, false, false)
		{
			@Override
			public boolean isFileVisible(VirtualFile virtualFile, boolean showHidden)
			{
				if(!showHidden && virtualFile.getName().charAt(0) == '.')
				{
					return false;
				}
				return virtualFile.isDirectory() || "properties".equals(virtualFile.getExtension());
			}
		};

		textFieldWithBrowseButton.addBrowseFolderListener("TestNG", "Select .properties file for test properties", project, propertiesFileDescriptor);

		propertiesTableView = new TableView(propertiesTableModel);

		myPropertiesPanel.add(ToolbarDecorator.createDecorator(propertiesTableView).setAddAction(new AnActionButtonRunnable()
		{
			@Override
			public void run(AnActionButton button)
			{
				propertiesTableModel.addParameter();
				int index = propertiesTableModel.getRowCount() - 1;
				propertiesTableView.setRowSelectionInterval(index, index);
			}
		}).setRemoveAction(new AnActionButtonRunnable()
		{
			@Override
			public void run(AnActionButton button)
			{
				int idx = propertiesTableView.getSelectedRow() - 1;
				for(int row : propertiesTableView.getSelectedRows())
				{
					propertiesTableModel.removeProperty(row);
				}
				if(idx > -1)
				{
					propertiesTableView.setRowSelectionInterval(idx, idx);
				}
			}
		}).disableUpDownActions().createPanel(), BorderLayout.CENTER);

		myListenersList = new JBList(listenerModel);
		myListenersPanel.add(ToolbarDecorator.createDecorator(myListenersList).setAddAction(new AddActionButtonRunnable()).setRemoveAction(new AnActionButtonRunnable()
		{
			@Override
			public void run(AnActionButton button)
			{
				int idx = myListenersList.getSelectedIndex() - 1;
				for(int row : myListenersList.getSelectedIndices())
				{
					listenerModel.removeListener(row);
				}
				if(idx > -1)
				{
					myListenersList.setSelectedIndex(idx);
				}
			}
		}).setAddActionUpdater(new AnActionButtonUpdater()
		{
			@Override
			public boolean isEnabled(AnActionEvent e)
			{
				return !project.isDefault();
			}
		}).disableUpDownActions().createPanel(), BorderLayout.CENTER);
	}

	public void onTypeChanged(TestType type)
	{
		//LOGGER.info("onTypeChanged with " + type);
		myTestKind.setSelectedItem(type);
		if(type == TestType.PACKAGE)
		{
			packageField.setEnabled(true);
			classField.setEnabled(false);
			methodField.setEnabled(false);
			groupField.setEnabled(false);
			suiteField.setEnabled(false);
			myPattern.setEnabled(false);
		}
		else if(type == TestType.CLASS)
		{
			packageField.setEnabled(false);
			classField.setEnabled(true);
			methodField.setEnabled(false);
			groupField.setEnabled(false);
			suiteField.setEnabled(false);
			myPattern.setEnabled(false);
		}
		else if(type == TestType.METHOD || type == TestType.SOURCE)
		{
			packageField.setEnabled(false);
			classField.setEnabled(true);
			methodField.setEnabled(true);
			groupField.setEnabled(false);
			suiteField.setEnabled(false);
			myPattern.setEnabled(false);
		}
		else if(type == TestType.GROUP)
		{
			groupField.setEnabled(true);
			packageField.setVisible(false);
			classField.setEnabled(false);
			methodField.setEnabled(false);
			suiteField.setEnabled(false);
			myPattern.setEnabled(false);
		}
		else if(type == TestType.SUITE)
		{
			suiteField.setEnabled(true);
			packageField.setVisible(false);
			classField.setEnabled(false);
			methodField.setEnabled(false);
			groupField.setEnabled(false);
			myPattern.setEnabled(false);
		}
		else if(type == TestType.PATTERN)
		{
			myPattern.setEnabled(true);
			suiteField.setEnabled(false);
			packageField.setVisible(false);
			classField.setEnabled(false);
			methodField.setEnabled(false);
			groupField.setEnabled(false);
		}
		redisplay();
		evaluateModuleClassPath();
	}

	private class AddActionButtonRunnable implements AnActionButtonRunnable
	{
		private final Logger LOGGER = Logger.getInstance("TestNG Runner");

		@Nullable
		protected GlobalSearchScope getSearchScope(Module[] modules)
		{
			if(modules == null || modules.length == 0)
			{
				return null;
			}
			GlobalSearchScope scope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(modules[0]);
			for(int i = 1; i < modules.length; i++)
			{
				scope.uniteWith(GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(modules[i]));
			}
			return scope;
		}

		@Nullable
		protected String selectListenerClass()
		{
			GlobalSearchScope searchScope = getSearchScope(config.getModules());
			if(searchScope == null)
			{
				searchScope = GlobalSearchScope.allScope(project);
			}
			final TestListenerFilter filter = new TestListenerFilter(searchScope, project);

			TreeClassChooser chooser = TreeClassChooserFactory.getInstance(project).createWithInnerClassesScopeChooser("Choose Listener Class", filter.getScope(), filter, null);
			chooser.showDialog();
			PsiClass psiclass = chooser.getSelected();
			if(psiclass == null)
			{
				return null;
			}
			else
			{
				return JavaExecutionUtil.getRuntimeQualifiedName(psiclass);
			}
		}

		@Override
		public void run(AnActionButton button)
		{
			final String className = selectListenerClass();
			if(className != null)
			{
				listenerModel.addListener(className);
				LOGGER.info("Adding listener " + className + " to configuration.");
			}
		}
	}

	private class TestNGMethodBrowser extends MethodBrowser
	{
		public TestNGMethodBrowser(Project project)
		{
			super(project);
		}

		protected Condition<PsiMethod> getFilter(PsiClass testClass)
		{
			return method -> TestNGUtil.hasTest(method);
		}

		@Override
		protected String getClassName()
		{
			return TestNGConfigurationEditor.this.getClassName();
		}

		@Override
		protected ConfigurationModuleSelector getModuleSelector()
		{
			return moduleSelector;
		}
	}
}
