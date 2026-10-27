package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.io.ObjectInput;
import org.junit.jupiter.api.Test;

/**
 * No server code turns bytes into objects through Java serialization. A gadget chain reaches any class on the
 * classpath, so library classes that are safe only while nothing deserializes them stay safe.
 * {@code security/vulnerability-policy.json} names this rule as the proof behind its {@code
 * vulnerable_code_not_in_execute_path} claims for the server image: their flaws are reachable only through Java
 * deserialization.
 */
class JavaDeserializationArchTest extends HephaestusArchitectureTest {

    @Test
    void noJavaDeserialization() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .areAssignableTo(ObjectInput.class)
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.util.SerializationUtils")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.apache.commons.lang3.SerializationUtils")
                .because("Java deserialization of untrusted bytes runs any gadget the classpath holds; use JSON")
                .check(classes);
    }
}
