package net.aeten.core.spi;

import java.io.*;
import java.util.*;
import javax.annotation.Generated;
import javax.annotation.processing.*;
import static javax.lang.model.SourceVersion.RELEASE_7;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import net.aeten.core.logging.LogLevel;

/**
 *
 * @author Thomas Pérennou
 */
@Provider(Processor.class)
@SupportedAnnotationTypes({"net.aeten.core.spi.Provider", "javax.annotation.Generated", "net.aeten.core.spi.Configurations", "net.aeten.core.spi.Configuration", "net.aeten.core.spi.SpiInitializer"})
@SupportedSourceVersion(RELEASE_7)
public class AnnotatedProviderProcessor extends AbstractProcessor {

	private static final Map<String, FileObject> servicesFileObjects = Collections.synchronizedMap(new HashMap<String, FileObject>());

	@Override
	public synchronized void init(ProcessingEnvironment processingEnv) {
		super.init(processingEnv);
		this.logLevel = LogLevel.DEBUG;
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		List<Element> initializers = new ArrayList<>();
		for (Element element : roundEnv.getElementsAnnotatedWith(SpiInitializer.class)) {
			initializers.add(element);
		}

		for (Element element : roundEnv.getElementsAnnotatedWith(Configurations.class)) {
			for (AnnotationMirror configurations : getAnnotationMirrors(element, Configurations.class)) {
				for (AnnotationValue v : (Iterable<AnnotationValue>) getAnnotationValue(configurations).getValue()) {
					this.configure(element, (AnnotationMirror) v.getValue(), initializers);
				}
			}
		}

		for (Element element : roundEnv.getElementsAnnotatedWith(Configuration.class)) {
			for (AnnotationMirror configuration : getAnnotationMirrors(element, Configuration.class)) {
				this.configure(element, configuration, initializers);
			}
		}

		for (Element provider : roundEnv.getElementsAnnotatedWith(Provider.class)) {
			Element initializer = null;
			for (Element element : initializers) {
				if (element.getEnclosingElement().getEnclosingElement().equals(provider)) {
					initializer = element;
				}
			}
			if (initializer == null) {
				registerProvider(provider);
			}
		}

		return true;
	}

	private void registerProvider(Element provider) {
		String providerClassName = getProperQualifiedName((TypeElement) provider);

		for (AnnotationMirror annotation : getAnnotationMirrors(provider, Provider.class)) {
			for (AnnotationValue value : findValue(annotation)) {
				String service = value.getValue().toString();

				try {
					FileObject fileObject;
					synchronized (servicesFileObjects) {
						fileObject = servicesFileObjects.get(service);
						if (fileObject == null) {
							try {
								fileObject = processingEnv.getFiler().createResource(StandardLocation.SOURCE_OUTPUT, "", "META-INF/services/" + service);
							} catch (Exception exception) {
								fileObject = processingEnv.getFiler().getResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/services/" + service);
							}
						}
						servicesFileObjects.put(service, fileObject);
					}

					StringWriter copy = new StringWriter();
					boolean alreadyRegistered = false;
					try {

						BufferedReader reader = getReader(fileObject);
						String line;

						try {
							while ((line = reader.readLine()) != null) {
								copy.write(line + "\n");
								if (line.trim().equals(providerClassName)) {
									debug("Provider " + providerClassName + " for service " + service + " is already registered");
									alreadyRegistered = true;
								}
							}
						} finally {
							reader.close();
						}
					} catch (FileNotFoundException exception) {
						fileObject = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/services/" + service);
						servicesFileObjects.put(service, fileObject);
					}
					try (PrintWriter writer = getWriter(fileObject, WriteMode.APPEND, true)) {
						writer.write(copy.toString());
						if (!alreadyRegistered) {
							writer.println(providerClassName);
							note("Add provider " + providerClassName + " for service " + service);
						}
					}
				} catch (IOException | IllegalArgumentException exception) {
					error("Fail to add provider " + providerClassName + " for service " + service, exception, provider);
				}
			}
		}

	}

	@SuppressWarnings("unchecked")
	private void configure(Element element, AnnotationMirror configuration, List<Element> initializers) {
		AnnotationValue nameAnnotationValue = getAnnotationValue(configuration, "name");
		String name = getClassName(nameAnnotationValue);
		TypeElement providerElement = toElement(getAnnotationValue(configuration, "provider"));
		List<String> services = new ArrayList<>();
		for (AnnotationMirror serviceAnnotation : getAnnotationMirrors(providerElement, Provider.class)) {
			AnnotationValue annotationValue = getAnnotationValue(serviceAnnotation);
			for (AnnotationValue value : (Iterable<AnnotationValue>) annotationValue.getValue()) {
				services.add(getProperQualifiedName(toElement(value)));
			}
		}
		String provider = providerElement.getQualifiedName().toString();
		String parser = (String) getAnnotationValue(configuration, "parser").getValue();

		debug("Configure " + name);
		String pkg = processingEnv.getElementUtils().getPackageOf(element).getQualifiedName().toString();
		try {
			FileObject fileObject = processingEnv.getFiler().createSourceFile(pkg + "." + name, element);
			try (PrintWriter writer = getWriter(fileObject, WriteMode.CREATE, false)) {

				writer.println("package " + pkg + ";");
				writer.println();
				writeImport(writer, Generated.class, Provider.class, SpiConfiguration.class);
				writer.println();
				writer.println("@Generated(\"" + AnnotatedProviderProcessor.class.getName() + "\")");
				writer.print("@" + Provider.class.getSimpleName() + ((services.size() > 1) ? "({" : "("));

				for (int i = 0; i < services.size(); i++) {
					writer.print(services.get(i) + ".class");
					if (i < (services.size() - 1)) {
						writer.write(", ");
					}
				}
				writer.println(((services.size() > 1) ? "})" : ")"));
				writer.println("public class " + name + " extends " + provider + " {");
				TypeMirror initializerType = null;
				Iterator<? extends TypeMirror> thrownTypes = null;
				for (Element enclosedElement : providerElement.getEnclosedElements()) {
					if (enclosedElement.getKind() == ElementKind.CONSTRUCTOR) {
						ExecutableElement constructor = (ExecutableElement) enclosedElement;
						if (constructor.getParameters().size() == 1 && constructor.getParameters().get(0).getAnnotation(SpiInitializer.class) != null) {
							initializerType = constructor.getParameters().get(0).asType();
							thrownTypes = constructor.getThrownTypes().iterator();
							break;
						}
					}
				}
				if (initializerType == null) {
					error("SpiInitializer not found in " + providerElement, element);
				}
				writer.print("	public " + name + " ()");
				if (thrownTypes.hasNext()) {
					writer.print(" throws ");
				}
				while (thrownTypes.hasNext()) {
					writer.print(thrownTypes.next().toString());
					if (thrownTypes.hasNext()) {
						writer.print(", ");
					}
				}
				writer.println(" {");
				writer.print("		super(new " + initializerType + "(new SpiConfiguration(");
				writer.print("\"" + pkg + "\", " + "\"" + nameAnnotationValue.getValue() + "\", " + "\"" + parser + "\", " + provider + ".class");
				writer.println(")));");
				writer.println("	}");
				writer.println("}");
				writer.flush();
			}
		} catch (IOException | IllegalArgumentException | Error exception) {
			error("Unexpected exception", exception, element);
		}
	}

	private static String getClassName(AnnotationValue configurationFileNameAnnotationValue) {
		String configurationFileName = (String) configurationFileNameAnnotationValue.getValue();
		return configurationFileName.substring(0, configurationFileName.lastIndexOf('.')).replace('.', '_');
	}
}
