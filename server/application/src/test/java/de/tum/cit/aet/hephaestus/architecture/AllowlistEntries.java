package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps an allow-list from going stale: after a rename or a deletion, an entry that names no class would exempt nothing
 * and hide that the rule's subject changed.
 */
final class AllowlistEntries {

    private AllowlistEntries() {}

    /**
     * Fails unless every entry names a class among {@code classes}. An entry is a fully qualified name, a simple name, or
     * {@code Class.member}, whose class part must exist.
     */
    static void assertEachNamesAClass(String allowlist, Collection<String> entries, JavaClasses classes) {
        Set<String> names = new HashSet<>();
        for (JavaClass javaClass : classes) {
            names.add(javaClass.getName());
            names.add(javaClass.getSimpleName());
        }
        List<String> stale = entries.stream()
                .filter(entry -> !names.contains(entry)
                        && !(entry.contains(".") && names.contains(entry.substring(0, entry.lastIndexOf('.')))))
                .sorted()
                .toList();

        assertThat(stale)
                .as("%s names classes that do not exist; remove or rename these entries", allowlist)
                .isEmpty();
    }
}
