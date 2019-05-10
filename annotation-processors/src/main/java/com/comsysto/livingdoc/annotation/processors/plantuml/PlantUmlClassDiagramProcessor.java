package com.comsysto.livingdoc.annotation.processors.plantuml;

import static com.comsysto.livingdoc.annotation.processors.plantuml.PlantUmlClassDiagramProcessor.KEY_OUT_DIR;
import static com.comsysto.livingdoc.annotation.processors.plantuml.PlantUmlClassDiagramProcessor.KEY_SETTINGS_DIR;
import static java.util.Arrays.stream;
import static java.util.Collections.singleton;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import com.comsysto.livingdoc.annotation.plantuml.PlantUmlClass;
import com.comsysto.livingdoc.annotation.plantuml.PlantUmlNote;
import com.comsysto.livingdoc.annotation.plantuml.PlantUmlNotes;
import com.comsysto.livingdoc.annotation.processors.plantuml.model.ClassDiagram;
import com.comsysto.livingdoc.annotation.processors.plantuml.model.TypePart;
import com.comsysto.livingdoc.annotation.processors.plantuml.model.DiagramId;
import com.google.auto.service.AutoService;
import freemarker.cache.ClassTemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;

/**
 * The main processor class that handles top-level annotations for UMl diagrams
 * (currently only {@link PlantUmlClass}).
 */
@SuppressWarnings("unused")
@SupportedAnnotationTypes("com.comsysto.livingdoc.annotation.plantuml.PlantUmlClass")
@SupportedOptions({KEY_SETTINGS_DIR, KEY_OUT_DIR})
@SupportedSourceVersion(SourceVersion.RELEASE_8)
@AutoService(Processor.class)
@Slf4j
public class PlantUmlClassDiagramProcessor extends AbstractProcessor {
    protected static final String KEY_SETTINGS_DIR = "pumlgen.settings.dir";
    protected static final String DEF_SETTINGS_DIR = ".";
    protected static final String KEY_OUT_DIR = "pumlgen.out.dir";
    protected static final String DEF_OUT_DIR = "./out";
    private final Configuration freemarkerConfiguration = new Configuration(Configuration.VERSION_2_3_23);

    private String settingsDir;
    private String outDir;

    public PlantUmlClassDiagramProcessor() {
        freemarkerConfiguration.setTemplateLoader(new ClassTemplateLoader(this.getClass(), ""));
    }

    @Override
    public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
        log.debug("Environment: {}", processingEnv.getOptions().toString());

        settingsDir = processingEnv.getOptions().getOrDefault(KEY_SETTINGS_DIR, DEF_SETTINGS_DIR);
        outDir = processingEnv.getOptions().getOrDefault(KEY_OUT_DIR, DEF_OUT_DIR);

        if (!roundEnv.errorRaised() && !roundEnv.processingOver()) {
            annotations.forEach(annotation -> processAnnotation(annotation, roundEnv));
            return true;
        }
        return false;
    }

    /**
     * Process a specific annotation.
     *
     * @param annotation the annotation
     * @param roundEnv   the round environment
     */
    private void processAnnotation(final TypeElement annotation, final RoundEnvironment roundEnv) {
        final Set<TypeElement> annotated = roundEnv.getElementsAnnotatedWith(annotation)
            .stream()
            .map(TypeElement.class::cast)
            .collect(toSet());

        //noinspection SwitchStatementWithTooFewBranches
        switch (annotation.getQualifiedName().toString()) {
            case "com.comsysto.livingdoc.annotation.plantuml.PlantUmlClass":
                processPlantumlClassAnnotation(annotated);
                break;

            // Here we could add support for additional top-level annotations

            default:
                log.error(
                    "Unexpected annotation type: {}. Most likely there is a mismatch between the value of "
                    + "@SupportedAnnotationTypes and the annotation handling implemented in "
                    + "PlantUmlClassDiagramProcessor.processAnnotation.",
                    annotation.getQualifiedName().toString());
        }
    }

    /**
     * Process the {@link PlantUmlClass} annotation for all types that have this
     * annotation. The processor will generate one diagram file per unique
     * diagram ID found on any annotation.
     *
     * @param annotatedTypes the annotated types.
     */
    private void processPlantumlClassAnnotation(final Set<TypeElement> annotatedTypes) {
        final Map<DiagramId, List<TypePart>> generatedFiles = annotatedTypes.stream()
            .map(this::createDiagramPart)
            .flatMap(part -> part.getDiagramIds().stream()
                .map(id -> new TypePart(
                    singleton(id),
                    part.getAnnotation(),
                    part.getTypeElement(),
                    part.getNotes())))
            .collect(groupingBy(part -> part.getDiagramIds().stream().findFirst().get()));

        generatedFiles.keySet().forEach(id -> createDiagram(id, generatedFiles.get(id)));
    }

    private TypePart createDiagramPart(final TypeElement annotated) {
        final PlantUmlClass classAnnotation = annotated.getAnnotation(PlantUmlClass.class);
        log.debug("Processing PlantUmlClass annotation on type: {}", annotated);

        return new TypePart(
            stream(classAnnotation.diagramIds()).map(DiagramId::of).collect(toSet()),
            classAnnotation,
            annotated,
            getNoteAnnotations(annotated));
    }

    private List<PlantUmlNote> getNoteAnnotations(final TypeElement annotated) {
        return Optional.ofNullable(annotated.getAnnotation(PlantUmlNotes.class))
            .map(PlantUmlNotes::value)
            .map(Arrays::asList)
            .orElseGet(() -> Optional.ofNullable(annotated.getAnnotation(PlantUmlNote.class))
                .map(Collections::singletonList)
                .orElse(Collections.emptyList()));
    }

    private void createDiagram(final DiagramId diagramId, final List<TypePart> parts) {
        final File       outFile  = getOutFile(diagramId);
        final Properties settings = loadSettings(diagramId);

        log.debug("Create PlantUML diagram: {}", outFile);

        try (final BufferedWriter out = new BufferedWriter(new FileWriter(outFile))) {
            final Template template = freemarkerConfiguration.getTemplate("class-diagram.puml.ftl");
            template.process(
                new ClassDiagram(
                    settings.getProperty("title", null),
                    stream(settings.getProperty("include.files", "").split(","))
                        .filter(StringUtils::isNoneBlank)
                        .collect(toList()),
                    parts),
                out);
        } catch (IOException | TemplateException e) {
            log.error("Failed to generate diagram: " + outFile, e);
            throw new RuntimeException(e);
        }
    }

    private File getOutFile(final DiagramId diagramId) {
        final File diagramFile = new File(outDir, diagramId.getValue() + "_class.puml");

        //noinspection ResultOfMethodCallIgnored
        diagramFile.getParentFile().mkdirs();
        return diagramFile;
    }

    private Properties loadSettings(final DiagramId id) {
        final File settingsFile = new File(
            settingsDir,
            id.getValue() + "_class.properties");
        final Properties settings = new Properties();
        
        try (final FileReader in = new FileReader(settingsFile)) {
            log.debug("Settings file: {}", settingsFile.getAbsoluteFile());
            settings.load(in);
            log.debug("Settings: \n{}", settings.toString());
        } catch (IOException e) {
            log.debug("No settings file found: {}", settingsFile.getAbsoluteFile());
        }
        return settings;
    }
}