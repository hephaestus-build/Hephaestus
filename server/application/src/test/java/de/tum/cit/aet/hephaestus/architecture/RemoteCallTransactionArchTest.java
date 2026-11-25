package de.tum.cit.aet.hephaestus.architecture;

import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import de.tum.cit.aet.hephaestus.agent.proxy.LlmModelProbeGateway;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGateway;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineApiClient;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodHandleEntry;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

class RemoteCallTransactionArchTest extends HephaestusArchitectureTest {

    private static final Set<String> TRANSACTION_BOUNDARIES =
            Set.of(TransactionTemplate.class.getName(), TransactionOperations.class.getName());
    private static final Set<String> HTTP_METHODS =
            Set.of("get", "post", "put", "patch", "delete", "options", "head", "method");
    private static final Set<String> GIT_NETWORK_COMMANDS =
            Set.of("cloneRepository", "lsRemoteRepository", "fetch", "push", "pull");
    private static final List<String> BASELINE = List.of();

    private static final Set<String> EGRESS_TYPES = classes.stream()
            .filter(type ->
                    type.isAnnotatedWith(OutboundEgressGateway.class) || type.isEquivalentTo(OutlineApiClient.class))
            .flatMap(type -> type.getAllClassesSelfIsAssignableTo().stream())
            .map(JavaClass::getName)
            .filter(name -> name.startsWith(BASE_PACKAGE + "."))
            .collect(Collectors.toUnmodifiableSet());

    @Test
    void shouldKeepDirectRemoteCallsOutsideTransactions() throws IOException {
        var annotations = new AnnotationTransactionAttributeSource(false);
        var violations = new ArrayList<Violation>();
        for (JavaClass type : classes) {
            var transactional = type.getMethods().stream()
                    .filter(SpringCorrectnessArchitectureTest::isEffectivelyTransactional)
                    .filter(method -> startsOrJoinsTransaction(annotations, method))
                    .map(method -> method.getName() + method.getDescriptor())
                    .collect(Collectors.toSet());
            boolean hasTemplate = type.getMethodCallsFromSelf().stream()
                    .anyMatch(call -> TRANSACTION_BOUNDARIES.contains(
                            call.getTargetOwner().getName()));
            if (transactional.isEmpty() && !hasTemplate) continue;
            try (var bytes = type.getSource().orElseThrow().getUri().toURL().openStream()) {
                checkClass(type, ClassFile.of().parse(bytes.readAllBytes()).methods(), transactional, violations);
            }
        }
        assertThat(violations.stream().map(Violation::key))
                .as("Move outbound calls outside the transaction. Call sites: %s", violations)
                .containsExactlyInAnyOrderElementsOf(BASELINE);
    }

    private static boolean startsOrJoinsTransaction(AnnotationTransactionAttributeSource source, JavaMethod method) {
        var attribute = source.getTransactionAttribute(
                method.reflect(), method.getOwner().reflect());
        if (attribute == null) return false;
        // SUPPORTS can join an existing transaction. It is checked like REQUIRED.
        return attribute.getPropagationBehavior() != TransactionDefinition.PROPAGATION_NOT_SUPPORTED
                && attribute.getPropagationBehavior() != TransactionDefinition.PROPAGATION_NEVER;
    }

    private record Violation(String key, String file, int line) {}

    private static void checkClass(
            JavaClass owner, List<MethodModel> methods, Set<String> transactional, List<Violation> violations) {
        Map<String, MethodModel> bySignature =
                methods.stream().collect(Collectors.toMap(RemoteCallTransactionArchTest::signature, method -> method));
        String outerClass = owner.getName().split("\\$", 2)[0];
        String file = "src/main/java/" + outerClass.replace('.', '/') + ".java";
        for (MethodModel method : methods) {
            String boundary = owner.getName() + "." + signature(method);
            if (transactional.contains(signature(method))) {
                checkCalls(method, boundary, file, violations);
            }
            method.code().ifPresent(code -> {
                int line = 0;
                @Nullable MethodHandleEntry callback = null;
                for (var element : code) {
                    if (element instanceof LineNumber number) {
                        line = number.line();
                    } else if (element instanceof InvokeDynamicInstruction dynamic) {
                        callback = lambdaImplementation(dynamic);
                    } else if (element instanceof Instruction) {
                        if (callback != null && element instanceof InvokeInstruction invoke && isTransaction(invoke)) {
                            var target = callback.reference();
                            if (owner.getName().equals(ownerName(target))) {
                                var implementation =
                                        bySignature.get(target.name().stringValue()
                                                + target.type().stringValue());
                                if (implementation != null)
                                    checkCalls(implementation, boundary + " callback", file, violations);
                            } else {
                                reportIfRemote(target, boundary + " callback", file, line, violations);
                            }
                        }
                        callback = null;
                    }
                }
            });
        }
    }

    // javac creates an inline callback immediately before the single-argument execute call.
    // Using its implementation handle avoids treating an after-commit lambda as part of its enclosing method.
    private static @Nullable MethodHandleEntry lambdaImplementation(InvokeDynamicInstruction dynamic) {
        var bootstrap = dynamic.bootstrapMethod();
        if (!bootstrap.owner().descriptorString().equals("Ljava/lang/invoke/LambdaMetafactory;")) return null;
        return dynamic.invokedynamic().bootstrap().arguments().get(1) instanceof MethodHandleEntry implementation
                ? implementation
                : null;
    }

    private static boolean isTransaction(InvokeInstruction invoke) {
        return TRANSACTION_BOUNDARIES.contains(ownerName(invoke.method()))
                && Set.of("execute", "executeWithoutResult")
                        .contains(invoke.name().stringValue());
    }

    private static void checkCalls(MethodModel method, String boundary, String file, List<Violation> violations) {
        method.code().ifPresent(code -> {
            int line = 0;
            for (var element : code) {
                if (element instanceof LineNumber number) {
                    line = number.line();
                } else if (element instanceof InvokeInstruction invoke) {
                    reportIfRemote(invoke.method(), boundary, file, line, violations);
                }
            }
        });
    }

    private static void reportIfRemote(
            MemberRefEntry target, String boundary, String file, int line, List<Violation> violations) {
        String owner = ownerName(target);
        String name = target.name().stringValue();
        if (isRemote(owner, name)) {
            violations.add(new Violation(
                    boundary + " -> " + owner + "." + name + target.type().stringValue(), file, line));
        }
    }

    private static boolean isRemote(String owner, String method) {
        if (method.startsWith("<")) return false;
        if (EGRESS_TYPES.contains(owner)) return true;
        return switch (owner) {
            case "org.springframework.web.client.RestClient",
                    "org.springframework.web.reactive.function.client.WebClient" -> HTTP_METHODS.contains(method);
            case "org.springframework.web.client.RestTemplate", "org.springframework.web.client.RestOperations" ->
                method.startsWith("getFor")
                        || method.startsWith("postFor")
                        || Set.of(
                                        "exchange",
                                        "execute",
                                        "put",
                                        "patchForObject",
                                        "delete",
                                        "optionsForAllow",
                                        "headForHeaders")
                                .contains(method);
            case "org.springframework.web.client.RestClient$RequestHeadersSpec",
                    "org.springframework.web.client.RestClient$RequestBodySpec",
                    "org.springframework.web.reactive.function.client.WebClient$RequestHeadersSpec",
                    "org.springframework.web.reactive.function.client.WebClient$RequestBodySpec" ->
                Set.of("retrieve", "exchange", "exchangeToMono", "exchangeToFlux")
                        .contains(method);
            case "org.springframework.graphql.client.GraphQlClient$RequestSpec" ->
                Set.of("execute", "executeSync", "retrieve", "retrieveSync").contains(method);
            case "java.net.http.HttpClient" -> Set.of("send", "sendAsync").contains(method);
            case "com.slack.api.methods.MethodsClient" -> true;
            case "org.eclipse.jgit.api.Git" -> GIT_NETWORK_COMMANDS.contains(method);
            default ->
                (owner.startsWith("org.eclipse.jgit.api.")
                                && method.equals("call")
                                && Set.of(
                                                "CloneCommand",
                                                "LsRemoteCommand",
                                                "FetchCommand",
                                                "PushCommand",
                                                "PullCommand")
                                        .contains(owner.substring(owner.lastIndexOf('.') + 1)))
                        || (owner.equals(GitRepositoryManager.class.getName())
                                && Set.of("ensureRepository", "fetchRemoteCommit")
                                        .contains(method))
                        || (owner.equals(LlmModelProbeGateway.class.getName()) && method.equals("call"));
        };
    }

    private static String signature(MethodModel method) {
        return method.methodName().stringValue() + method.methodType().stringValue();
    }

    private static String ownerName(MemberRefEntry target) {
        return target.owner().asInternalName().replace('/', '.');
    }
}
