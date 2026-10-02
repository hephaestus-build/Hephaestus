package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Pins the delivered-feedback ledger co-ownership seam (ADR 0021): {@code practices} owns the
 * {@code feedback} schema (entities + repositories), but the write-orchestration + transaction
 * boundary deliberately live in {@code agent} ({@code agent.handler.FeedbackLedgerRecorder} and the
 * reaction-aware delivery filters). The recorder cannot move into {@code practices} because it depends
 * on {@code agent.job.AgentJob} and {@code agent.handler.ReviewResultParser} — relocating it
 * would invert the dependency and close a {@code practices -> agent} Modulith cycle.
 *
 * <p><b>Why this matters.</b> The {@code feedback} package is an exposed {@code NamedInterface}, and
 * {@code agent} is an open module with no {@code allowedDependencies}, so today ANY module could legally
 * inject {@code FeedbackRepository} and write the ledger — Modulith verification would stay green. The
 * recorder's idempotency guard, REQUIRES_NEW best-effort transaction, and supersession invariant assume a
 * single write-orchestrator. This test makes that assumption load-bearing: only {@code agent} (the writer)
 * and {@code practices} (the owner) may depend on {@code practices.feedback}; the seam cannot silently
 * widen to a third module by accident.
 */
class FeedbackLedgerOwnershipTest extends HephaestusArchitectureTest {

    private static final String FEEDBACK_PACKAGE = "..practices.feedback..";
    private static final String PRACTICES_PACKAGE = "..practices..";
    private static final String AGENT_PACKAGE = "..agent..";

    @Test
    void onlyAgentAndPracticesDependOnTheFeedbackLedger() {
        ArchRule rule = noClasses()
                .that()
                .resideOutsideOfPackage(PRACTICES_PACKAGE)
                .and()
                .resideOutsideOfPackage(AGENT_PACKAGE)
                .should()
                .dependOnClassesThat()
                .resideInAPackage(FEEDBACK_PACKAGE)
                .because("the delivered-feedback ledger is a deliberate co-ownership seam (ADR 0021): practices owns "
                        + "the schema, agent owns write-orchestration via FeedbackLedgerRecorder, and the contract is "
                        + "repository-level by necessity (the recorder depends on agent.job/agent.handler types, so it "
                        + "cannot move into practices without closing a practices->agent cycle). The feedback named "
                        + "interface is open, so without this rule any module could silently start writing the ledger "
                        + "and break the single-writer idempotency/transaction invariant the recorder depends on.");
        rule.check(classes);
    }
}
