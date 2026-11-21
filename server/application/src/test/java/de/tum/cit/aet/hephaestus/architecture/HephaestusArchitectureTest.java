package de.tum.cit.aet.hephaestus.architecture;

import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import org.junit.jupiter.api.Tag;

/**
 * Base class for all Hephaestus architecture tests.
 *
 * <p>Provides shared class loading infrastructure to avoid duplicate imports
 * across test files. All architecture tests should extend this class.
 *
 * <p><b>Why a base class?</b>
 * <ul>
 *   <li>Class import is expensive in time and heap - do it once. The architecture tier runs in one JVM, so a
 *       second import of these packages stays next to this one; filter this one instead</li>
 *   <li>Consistent import options across all tests</li>
 *   <li>Single place to add new import exclusions</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * class MyArchitectureTest extends HephaestusArchitectureTest {
 *     @Test
 *     void myRule() {
 *         // Use 'classes' for production code only
 *         myRule.check(classes);
 *
 *         // Use 'classesWithTests' when testing test architecture
 *         testRule.check(classesWithTests);
 *     }
 * }
 * }</pre>
 *
 * @see ArchitectureTestConstants for thresholds and package patterns
 */
@Tag("architecture")
public abstract class HephaestusArchitectureTest {

    /** All classes including tests. Used for test architecture rules. */
    protected static final JavaClasses classesWithTests = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages(BASE_PACKAGE);

    /**
     * Production classes only (excludes tests). Used for most architecture rules. A view of the same import, selected
     * by the location rule that {@code DO_NOT_INCLUDE_TESTS} applies. Test classes stay in the graph: a lookup that
     * leaves the checked classes, such as {@code getAllSubclasses()}, a package, or the accesses to a class, also
     * returns test classes. A rule that follows such a lookup filters it with {@link #inProduction(JavaClass)}.
     */
    protected static final JavaClasses classes = classesWithTests.that(
            DescribedPredicate.describe("production classes", HephaestusArchitectureTest::inProduction));

    /** Whether a class comes from the production output, by the location rule of {@code DO_NOT_INCLUDE_TESTS}. */
    protected static boolean inProduction(JavaClass type) {
        return type.getSource()
                .map(source -> ImportOption.Predefined.DO_NOT_INCLUDE_TESTS.includes(Location.of(source.getUri())))
                .orElse(false);
    }
}
