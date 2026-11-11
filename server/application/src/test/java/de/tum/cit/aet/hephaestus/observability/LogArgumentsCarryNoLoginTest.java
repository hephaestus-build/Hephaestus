package de.tum.cit.aet.hephaestus.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A log record names a person by an id, never by a provider login, a username or an email address. Logs leave the
 * erasure path: a log store keeps a line until its retention removes it. {@link PersonalDataLogScan} finds email
 * addresses only in the records that a test run writes; this rule reads every log call in the source.
 */
@Tag("architecture")
class LogArgumentsCarryNoLoginTest {

    private static final Path SOURCES = Path.of("src/main/java");

    /** A logger call up to its closing {@code );}, with its arguments. */
    private static final Pattern LOG_CALL =
            Pattern.compile("\\blog\\.(?:trace|debug|info|warn|error)\\((.*?)\\);", Pattern.DOTALL);

    /** The message of a log call: its first argument, a string literal or a concatenation of them. */
    private static final Pattern MESSAGE =
            Pattern.compile("^\\s*\"(?:[^\"\\\\]|\\\\.)*\"(?:\\s*\\+\\s*\"(?:[^\"\\\\]|\\\\.)*\")*");

    /** The login of an organization, such as {@code org.getLogin()}, {@code orgDto.login()} or {@code safeOrgLogin}. */
    private static final Pattern ORGANIZATION_LOGIN =
            Pattern.compile("\\b\\w*[oO]rg\\w*(?:\\(\\))?\\.(?:getLogin|login)\\(\\)|\\b\\w*[oO]rg\\w*Login\\b");

    /**
     * An argument that reads a login, a username or an email address: an accessor such as {@code user.getLogin()}, or
     * a variable named for one, such as {@code login} or {@code safeUsername}. A name with {@code By} counts things.
     */
    private static final Pattern PERSON_ARGUMENT = Pattern.compile(
            "\\.(?:getLogin|login|getUsername|username|getEmail|email|getAuthorEmail|getCommitterEmail)\\(\\)"
                    + "|(?:^|,)\\s*(?:login|username|email|(?!\\w*By)\\w+(?:Login|Username|Email))\\s*(?=,|$)",
            Pattern.MULTILINE);

    /** Files whose logged login is not a person's, each with the reason. */
    private static final Map<String, String> NOT_A_PERSON = Map.of(
            "integration/scm/github/organization/GitHubOrganizationProcessor.java", "an organization's login",
            "contributors/ContributorService.java", "public contributors of the project itself and its bots",
            "core/auth/dev/DevLoginService.java", "the fixture accounts of the dev profile");

    @Test
    void shouldLogNoLoginUsernameOrEmail() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = SOURCES.resolve("de/tum/cit/aet/hephaestus")
                        .relativize(file)
                        .toString()
                        .replace('\\', '/');
                if (NOT_A_PERSON.containsKey(relative)) {
                    continue;
                }
                var call = LOG_CALL.matcher(Files.readString(file));
                while (call.find()) {
                    String withoutOrganizations =
                            ORGANIZATION_LOGIN.matcher(call.group(1)).replaceAll("");
                    String arguments = MESSAGE.matcher(withoutOrganizations).replaceFirst("");
                    if (PERSON_ARGUMENT.matcher(arguments).find()) {
                        found.add(relative + ": " + call.group(1).replaceAll("\\s+", " "));
                    }
                }
            }
        }
        assertThat(found)
                .as("log calls that pass a login, a username or an email address")
                .isEmpty();
    }
}
