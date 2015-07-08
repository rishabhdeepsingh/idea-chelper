package net.egork.chelper.codegeneration;

import com.intellij.codeInsight.actions.ReformatCodeProcessor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import net.egork.chelper.task.StreamConfiguration;
import net.egork.chelper.task.Task;
import net.egork.chelper.task.TestType;
import net.egork.chelper.task.TopCoderTask;
import net.egork.chelper.util.FileUtilities;
import net.egork.chelper.util.Utilities;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * @author egor@egork.net
 */
public class SolutionGenerator {
	private Set<String> excludedPackages;
	private MainFileTemplate template;
	private boolean allToInnerClasses;
	private PsiMethod entryPoint;

	private Set<String> classesToImport = new HashSet<String>();
	private Set<PsiElement> toInline = new HashSet<PsiElement>();
	private Queue<PsiElement> queue = new ArrayDeque<PsiElement>();
	private Set<String> resolveToFull = new HashSet<String>();
	private StringBuilder source = new StringBuilder();
	private PsiElementVisitor visitor = new PsiElementVisitor() {
		@Override
		public void visitElement(PsiElement element) {
			if (element instanceof PsiIdentifier &&
				element.getParent() instanceof PsiReference &&
				((PsiReference) element.getParent()).resolve() instanceof PsiClass) {
				PsiClass aClass = (PsiClass) ((PsiReference) element.getParent()).resolve();
				source.append(convertNameFull(aClass));
			} else {
				if (element instanceof PsiAnnotation) {
					return;
				}
				if (element.getFirstChild() == null) {
					source.append(element.getText());
				} else {
					element.acceptChildren(this);
				}
			}
		}
	};

	public SolutionGenerator(Set<String> excludedPackages, MainFileTemplate template, boolean allToInnerClasses, PsiMethod entryPoint) {
		this.excludedPackages = excludedPackages;
		this.template = template;
		this.allToInnerClasses = allToInnerClasses;
		this.entryPoint = entryPoint;
	}

	public String createInlinedSource() {
		processElement(entryPoint, toInline);
		for (PsiElement element : template.entryPoints) {
			processElement(element, toInline);
		}
		final PsiElementVisitor visitor = new PsiElementVisitor() {
			@Override
			public void visitElement(PsiElement element) {
				if (element instanceof PsiReference) {
					PsiReference reference = (PsiReference) element;
					PsiElement referenced = reference.resolve();
					if (referenced instanceof PsiField || referenced instanceof PsiMethod || referenced instanceof PsiClass) {
						processElement(referenced, toInline);
					}
				} else if (element instanceof PsiConstructorCall) {
					PsiMethod constructor = ((PsiConstructorCall) element).resolveConstructor();
					if (constructor != null) {
						processElement(constructor, toInline);
					}
				}
				element.acceptChildren(this);
			}
		};
		while (!queue.isEmpty()) {
			while (!queue.isEmpty()) {
				PsiElement element = queue.poll();
				if (element instanceof PsiField) {
					PsiField field = (PsiField) element;
					processType(field.getType().getDeepComponentType());
					PsiExpression initializer = field.getInitializer();
					if (initializer != null) {
						initializer.accept(visitor);
					}
					processElement(field.getContainingClass(), toInline);
					if (field instanceof PsiEnumConstant) {
						if (((PsiEnumConstant) field).resolveConstructor() != null) {
							processElement(((PsiEnumConstant) field).resolveConstructor(), toInline);
						}
					}
				} else if (element instanceof PsiMethod) {
					PsiMethod method = (PsiMethod) element;
					for (PsiParameter parameter : method.getParameterList().getParameters()) {
						PsiType type = parameter.getType().getDeepComponentType();
						processType(type);
					}
					PsiCodeBlock body = method.getBody();
					if (body != null) {
						body.accept(visitor);
					}
					processElement(method.getContainingClass(), toInline);
				} else if (element instanceof PsiClass) {
					PsiClass parent = ((PsiClass) element).getContainingClass();
					if (parent != null) {
						processElement(parent, toInline);
					}
					for (PsiClass superClass : ((PsiClass) element).getSupers()) {
						processElement(superClass, toInline);
					}
					for (PsiMethod constructor : ((PsiClass) element).getConstructors()) {
						processElement(constructor, toInline);
					}
					for (PsiElement initializer : ((PsiClass) element).getInitializers()) {
						initializer.accept(visitor);
					}
				}
			}
			Set<PsiElement> addOnStep = new HashSet<PsiElement>();
			for (PsiElement element : toInline) {
				if (element instanceof PsiClass) {
					for (PsiMethod method : ((PsiClass) element).getMethods()) {
						if (!toInline.contains(method)) {
							for (PsiMethod parent : method.findSuperMethods()) {
								if (toInline.contains(parent) || !shouldAddElement(parent)) {
									processElement(method, addOnStep);
									break;
								}
							}
						}
					}
				}
			}
			toInline.addAll(addOnStep);
			queue.addAll(addOnStep);
		}
		Set<String> single = new HashSet<String>();
		PsiClass entryClass = entryPoint.getContainingClass();
		single.add(entryClass.getName());
		for (PsiElement element : toInline) {
			if (element instanceof PsiClass) {
				String name = ((PsiClass) element).getName();
				if (single.contains(name)) {
					resolveToFull.add(name);
				} else {
					single.add(name);
				}
			}
		}
		toInline.remove(entryClass);
		addSource(entryClass, allToInnerClasses, true);
		for (PsiElement element : toInline) {
			if (element instanceof PsiClass && ((PsiClass) element).getContainingClass() == null && !element.equals(entryClass)) {
				addSource((PsiClass) element, allToInnerClasses, true);
			}
		}
		return template.resolve(source.toString(), entryClass.getName(), classesToImport);
	}

	private void addSource(PsiClass aClass, boolean convertToStaticInner, boolean removePublic) {
		PsiModifierList list = aClass.getModifierList();
		String modifierList = list == null ? "" : list.getText();
		if (removePublic) {
			modifierList = modifierList.replace("public ", "").replace(" public", "").replace("public", "");
		}
		if (convertToStaticInner) {
			if (modifierList.isEmpty()) {
				modifierList = "static";
			} else {
				modifierList = "static " + modifierList;
			}
		}
		source.append(modifierList);
		if (!modifierList.isEmpty()) {
			source.append(" ");
		}
		source.append(aClass.isEnum() ? "enum" : aClass.isInterface() ? "interface" : "class").append(' ');
		String className = convertName(aClass);
		aClass.getNameIdentifier().accept(visitor);
		PsiTypeParameterList parameterList = aClass.getTypeParameterList();
		if (parameterList != null) {
			parameterList.accept(visitor);
		}
		if (aClass.getExtendsList() != null) {
			source.append(' ');
			aClass.getExtendsList().accept(visitor);
		}
		if (aClass.getImplementsList() != null) {
			source.append(' ');
			aClass.getImplementsList().accept(visitor);
		}
		source.append(" {\n");
		boolean fieldAdded = false;
		boolean enumAdded = false;
		for (PsiField field : aClass.getFields()) {
			if (!toInline.contains(field)) {
				continue;
			}
			if (field instanceof PsiEnumConstant) {
				field.accept(visitor);
				source.append(",\n");
				enumAdded = true;
			}
		}
		if (enumAdded) {
			source.append(";\n");
		}
		for (PsiField field : aClass.getFields()) {
			if (!toInline.contains(field)) {
				continue;
			}
			if (!(field instanceof PsiEnumConstant)) {
				PsiModifierList fieldModifierList = field.getModifierList();
				modifierList = fieldModifierList == null ? "" : fieldModifierList.getText();
				source.append(modifierList);
				if (!modifierList.isEmpty()) {
					source.append(" ");
				}
				field.getTypeElement().accept(visitor);
				source.append(' ');
				source.append(field.getName());
				PsiExpression initializer = field.getInitializer();
				if (initializer != null) {
					source.append(" = ");
					initializer.accept(visitor);
				}
				source.append(";\n");
				fieldAdded = true;
			}
		}
		for (PsiElement element : aClass.getInitializers()) {
			element.accept(visitor);
		}
		if (fieldAdded) {
			source.append("\n");
		}
		for (PsiMethod method : aClass.getMethods()) {
			if (!toInline.contains(method)) {
				continue;
			}
			PsiModifierList fieldModifierList = method.getModifierList();
			modifierList = fieldModifierList.getText();
			modifierList = modifierList.replace("@Override", "");
			source.append(modifierList);
			if (!modifierList.isEmpty()) {
				source.append(" ");
			}
			PsiTypeParameterList pList = method.getTypeParameterList();
			if (pList != null) {
				pList.accept(visitor);
			}
			if (method.getReturnType() != null) {
				method.getReturnTypeElement().accept(visitor);
				source.append(' ');
				source.append(method.getName());
			} else {
				source.append(className);
			}
			method.getParameterList().accept(visitor);
			if (method.getBody() != null) {
				source.append(' ');
				method.getBody().accept(visitor);
			} else {
				source.append(";");
			}
			source.append("\n\n");
		}
		for (PsiClass innerClass : aClass.getInnerClasses()) {
			if (!toInline.contains(innerClass)) {
				continue;
			}
			addSource(innerClass, false, false);
			source.append("\n");
		}
		source.append("}\n");
	}

	private String convertName(PsiClass aClass) {
		if (aClass.getContainingClass() == null) {
			return convertNameFull(aClass);
		} else {
			return aClass.getName();
		}
	}

	private String convertNameFull(PsiClass aClass) {
		List<String> inner = new ArrayList<String>();
		while (aClass.getContainingClass() != null) {
			inner.add(aClass.getName());
			aClass = aClass.getContainingClass();
		}
		StringBuilder result = new StringBuilder();
		if (toInline.contains(aClass) && resolveToFull.contains(aClass.getName())) {
			result.append(aClass.getQualifiedName().replace('.', '_'));
		} else {
			result.append(aClass.getName());
		}
		for (String className : inner) {
			result.append('.').append(className);
		}
		return result.toString();
	}

	private void processElement(PsiElement element, Set<PsiElement> toInline) {
		boolean shouldAdd = shouldAddElement(element);
		if (element instanceof PsiClass && !shouldAdd) {
			String qualifiedName = ((PsiClass) element).getQualifiedName();
			if (qualifiedName != null && !qualifiedName.startsWith("_")) {
				classesToImport.add(qualifiedName);
			}
		} else if (!toInline.contains(element) && shouldAdd) {
			queue.add(element);
			toInline.add(element);
		}
	}

	private void processType(PsiType type) {
		if (type instanceof PsiClassType) {
			PsiClass aClass = ((PsiClassType) type).resolve();
			if (aClass == null) {
				//TODO
				return;
			}
			processElement(aClass, toInline);
		}
	}

	private boolean shouldAddElement(PsiElement element) {
		PsiClass containingClass = element instanceof PsiClass ? (PsiClass) element : ((PsiMember) element).getContainingClass();
		if (containingClass == null) {
			//TODO
			return false;
		}
		String qualifiedName = containingClass.getQualifiedName();
		if (qualifiedName == null || qualifiedName.startsWith("_")) {
			//TODO
			return false;
		}
		for (String aPackage : excludedPackages) {
			if (qualifiedName.startsWith(aPackage)) {
				return false;
			}
		}
		return true;
	}

	public static MainFileTemplate createMainClassTemplate(Task task, Project project) {
		StringBuilder builder = new StringBuilder();
		builder.append("%IMPORTS%\n");
		builder.append("/**\n" +
			" * Built using CHelper plug-in\n" +
			" * Actual solution is at the top\n");
		String author = Utilities.getData(project).author;
		if (!author.isEmpty()) {
			builder.append(" * @author ").append(author).append("\n");
		}
		builder.append("*/");
		builder.append("public class ").append(task.mainClass).append(" {\n");
		builder.append("\tpublic static void main(String[] args) {\n");
		if (task.includeLocale)
			builder.append("\t\tLocale.setDefault(Locale.US);\n");
		if (task.input.type == StreamConfiguration.StreamType.STANDARD)
			builder.append("\t\tInputStream inputStream = System.in;\n");
		else if (task.input.type != StreamConfiguration.StreamType.LOCAL_REGEXP) {
			builder.append("\t\tInputStream inputStream;\n");
			builder.append("\t\ttry {\n");
			builder.append("\t\t\tinputStream = new FileInputStream(\"").append(task.input.
				getFileName(task.name, ".in")).append("\");\n");
			builder.append("\t\t} catch (IOException e) {\n");
			builder.append("\t\t\tthrow new RuntimeException(e);\n");
			builder.append("\t\t}\n");
		} else {
			builder.append("\t\tInputStream inputStream;\n");
			builder.append("\t\ttry {\n");
			builder.append("\t\t\tfinal String regex = \"").append(task.input.fileName).append("\";\n");
			builder.append("\t\t\tFile directory = new File(\".\");\n" +
				"\t\t\tFile[] candidates = directory.listFiles(new FilenameFilter() {\n" +
				"\t\t\t\tpublic boolean accept(File dir, String name) {\n" +
				"\t\t\t\t\treturn name.matches(regex);\n" +
				"\t\t\t\t}\n" +
				"\t\t\t});\n" +
				"\t\t\tFile toRun = null;\n" +
				"\t\t\tfor (File candidate : candidates) {\n" +
				"\t\t\t\tif (toRun == null || candidate.lastModified() > toRun.lastModified())\n" +
				"\t\t\t\t\ttoRun = candidate;\n" +
				"\t\t\t}\n" +
				"\t\t\tinputStream = new FileInputStream(toRun);\n");
			builder.append("\t\t} catch (IOException e) {\n");
			builder.append("\t\t\tthrow new RuntimeException(e);\n");
			builder.append("\t\t}\n");
		}
		if (task.output.type == StreamConfiguration.StreamType.STANDARD)
			builder.append("\t\tOutputStream outputStream = System.out;\n");
		else {
			builder.append("\t\tOutputStream outputStream;\n");
			builder.append("\t\ttry {\n");
			builder.append("\t\t\toutputStream = new FileOutputStream(\"").append(task.output.getFileName(task.name,
				".out")).append("\");\n");
			builder.append("\t\t} catch (IOException e) {\n");
			builder.append("\t\t\tthrow new RuntimeException(e);\n");
			builder.append("\t\t}\n");
		}
		String inputClass = CodeGenerationUtilities.getSimpleName(task.inputClass);
		builder.append("\t\t").append(inputClass).append(" in = new ").append(inputClass).
			append("(inputStream);\n");
		String outputClass = CodeGenerationUtilities.getSimpleName(task.outputClass);
		builder.append("\t\t").append(outputClass).append(" out = new ").append(outputClass).
			append("(outputStream);\n");
		String className = CodeGenerationUtilities.getSimpleName(task.taskClass);
		builder.append("\t\t").append(className).append(" solver = new ").append(className).append("();\n");
		switch (task.testType) {
			case SINGLE:
				builder.append("\t\tsolver.solve(1, in, out);\n");
				builder.append("\t\tout.close();\n");
				break;
			case MULTI_EOF:
				builder.append("\t\ttry {\n");
				builder.append("\t\t\tint testNumber = 1;\n");
				builder.append("\t\t\twhile (true)\n");
				builder.append("\t\t\t\tsolver.solve(testNumber++, in, out);\n");
				builder.append("\t\t} catch (UnknownError e) {\n");
				builder.append("\t\t\tout.close();\n");
				builder.append("\t\t}\n");
				break;
			case MULTI_NUMBER:
				builder.append("\t\tint testCount = Integer.parseInt(in.next());\n");
				builder.append("\t\tfor (int i = 1; i <= testCount; i++)\n");
				builder.append("\t\t\tsolver.solve(i, in, out);\n");
				builder.append("\t\tout.close();\n");
				break;
		}
		builder.append("\t}\n");
		builder.append("%INLINED_SOURCE%");
		builder.append("}\n\n");
		List<PsiElement> entryPoints = new ArrayList<PsiElement>(Arrays.asList(MainFileTemplate.getInputConstructor(project),
			MainFileTemplate.getOutputConstructor(project)));
		entryPoints.add(MainFileTemplate.getMethod(project, Utilities.getData(project).outputClass, "close", "void"));
		if (task.testType == TestType.MULTI_NUMBER) {
			entryPoints.add(MainFileTemplate.getMethod(project, Utilities.getData(project).inputClass, "next", "java.lang.String"));
		}
		Set<String> toImport = new HashSet<String>();
		toImport.add("java.io.InputStream");
		toImport.add("java.io.OutputStream");
		toImport.add("java.io.IOException");
		if (task.input.type != StreamConfiguration.StreamType.STANDARD)
			toImport.add("java.io.FileInputStream");
		if (task.output.type != StreamConfiguration.StreamType.STANDARD)
			toImport.add("java.io.FileOutputStream");
		if (task.input.type == StreamConfiguration.StreamType.LOCAL_REGEXP) {
			toImport.add("java.io.File");
			toImport.add("java.io.FilenameFilter");
		}
		if (task.includeLocale)
			toImport.add("java.util.Locale");
		return new MainFileTemplate(builder.toString(), entryPoints, toImport);
	}

	public static void createSourceFile(final Task task, final Project project) {
		ApplicationManager.getApplication().runWriteAction(new Runnable() {
			public void run() {
				String outputDirectory = Utilities.getData(project).outputDirectory;
				VirtualFile directory = FileUtilities.createDirectoryIfMissing(project, outputDirectory);
				if (directory == null)
					return;
				for (VirtualFile file : directory.getChildren()) {
					if ("java".equals(file.getExtension())) {
						try {
							file.delete(null);
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					}
				}
				SolutionGenerator generator = new SolutionGenerator(new HashSet<String>(Arrays.asList(Utilities.getData(project).excludedPackages)),
					createMainClassTemplate(task, project), true, MainFileTemplate.getMethod(project, task.taskClass, "solve", "void", "int", Utilities.getData(project).inputClass, Utilities.getData(project).outputClass));
				String source = generator.createInlinedSource();
				final VirtualFile file = FileUtilities.writeTextFile(directory, task.mainClass + ".java", source);
				FileUtilities.synchronizeFile(file);
				ReformatCodeProcessor processor = new ReformatCodeProcessor(PsiManager.getInstance(project).findFile(file), false);
				processor.run();
				FileUtilities.synchronizeFile(file);
			}
		});
	}

	public static void createSourceFile(final Project project, final TopCoderTask task) {
		ApplicationManager.getApplication().runWriteAction(new Runnable() {
			public void run() {
				SolutionGenerator generator = new SolutionGenerator(
					new HashSet<String>(Arrays.asList(Utilities.getData(project).excludedPackages)),
					new MainFileTemplate("%IMPORTS%\npublic %INLINED_SOURCE%", Collections.<PsiElement>emptySet(),
					Collections.<String>emptySet()), false, task.getMethod(project));
				String text = generator.createInlinedSource();
				String outputDirectory = Utilities.getData(project).outputDirectory;
				VirtualFile directory = FileUtilities.createDirectoryIfMissing(project, outputDirectory);
				if (directory == null)
					return;
				for (VirtualFile file : directory.getChildren()) {
					if ("java".equals(file.getExtension())) {
						try {
							file.delete(null);
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					}
				}
				final VirtualFile file = FileUtilities.writeTextFile(directory, task.name + ".java", text);
				FileUtilities.synchronizeFile(file);
				ReformatCodeProcessor processor = new ReformatCodeProcessor(PsiManager.getInstance(project).findFile(file), false);
				processor.run();
				String source = FileUtilities.readTextFile(file);
				VirtualFile virtualFile = FileUtilities.writeTextFile(LocalFileSystem.getInstance().findFileByPath(System.getProperty("user.home")), ".java", source);
				new File(virtualFile.getCanonicalPath()).deleteOnExit();
			}
		});
	}

}
