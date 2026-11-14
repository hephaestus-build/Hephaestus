package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.Application;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

/** The application's own classes, for rules that read compiled classes without a Spring context. */
public final class ApplicationClasses {

    private static final URL MAIN_CLASSES =
            Application.class.getProtectionDomain().getCodeSource().getLocation();

    private ApplicationClasses() {}

    /**
     * Every main class that carries {@code annotation}, directly or as a meta-annotation, also one whose condition this
     * bare environment turns off, such as the Slack and Outline controllers. Test classes are left out.
     */
    public static List<Class<?>> annotatedWith(Class<? extends Annotation> annotation) {
        var annotated = new AnnotationTypeFilter(annotation);
        // Overridden rather than filtered: the default check also drops classes whose conditions are off here.
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(MetadataReader reader) throws IOException {
                return annotated.match(reader, getMetadataReaderFactory());
            }
        };
        return scanner.findCandidateComponents(Application.class.getPackageName()).stream()
                .<Class<?>>map(candidate ->
                        ClassUtils.resolveClassName(Objects.requireNonNull(candidate.getBeanClassName()), null))
                .filter(type -> MAIN_CLASSES.equals(
                        type.getProtectionDomain().getCodeSource().getLocation()))
                .toList();
    }
}
