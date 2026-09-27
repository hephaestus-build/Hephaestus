package de.tum.cit.aet.hephaestus.core.auth.clientsession;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/** The request bodies are bounded to the secrets' real shapes before anything is hashed or queried. */
class ClientSessionControllerValidationTest extends BaseUnitTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    private static final String ID = "ijkajblcbajjpjbknfgdiiiljipafiko";
    private static final String CALLBACK = "https://" + ID + ".chromiumapp.org/callback";
    private static final String SECRET = "A".repeat(43);
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    private static boolean valid(Object body) {
        return VALIDATOR.validate(body).isEmpty();
    }

    @Test
    void shouldAcceptWellFormedBodiesWhenEveryFieldHasItsShape() {
        assertThat(valid(new ClientSessionController.ClientTokenRequestDTO(ID, CALLBACK, SECRET, VERIFIER)))
                .isTrue();
        assertThat(valid(new ClientSessionController.ClientTokenRequestDTO(ID, CALLBACK, SECRET, "~".repeat(128))))
                .isTrue();
        assertThat(valid(new ClientSessionController.ClientRefreshRequestDTO(SECRET)))
                .isTrue();
    }

    @Test
    void shouldRejectBodiesWhenAFieldIsOutOfBounds() {
        assertThat(valid(new ClientSessionController.ClientTokenRequestDTO(
                        ID.toUpperCase(java.util.Locale.ROOT), CALLBACK, SECRET, VERIFIER)))
                .isFalse();
        assertThat(valid(new ClientSessionController.ClientTokenRequestDTO(ID, "x".repeat(201), SECRET, VERIFIER)))
                .isFalse();
        assertThat(valid(new ClientSessionController.ClientTokenRequestDTO(ID, CALLBACK, SECRET + "A", VERIFIER)))
                .isFalse();
        assertThat(valid(new ClientSessionController.ClientTokenRequestDTO(
                        ID, CALLBACK, "A".repeat(42) + "=", VERIFIER)))
                .isFalse();
        assertThat(valid(new ClientSessionController.ClientTokenRequestDTO(ID, CALLBACK, SECRET, "a".repeat(42))))
                .isFalse();
        assertThat(valid(new ClientSessionController.ClientTokenRequestDTO(ID, CALLBACK, SECRET, "a".repeat(129))))
                .isFalse();
        assertThat(valid(new ClientSessionController.ClientRefreshRequestDTO("A".repeat(44))))
                .isFalse();
        assertThat(valid(new ClientSessionController.ClientRefreshRequestDTO("A".repeat(42) + "/")))
                .isFalse();
    }
}
