# ADR 0083: Each purpose uses one governed model within the member's AI choice, and every model call is counted

**Status:** Accepted
**Date:** 2026-11-10
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0026](0026-per-purpose-agent-bindings-and-llm-governance.md)

**Builds on:** [ADR 0079](0079-one-agent-system.md), [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md), [ADR 0070](0070-1-0-is-a-cutover-release.md)

**Relates to:** [ADR 0046](0046-heph-follows-its-model-not-a-workspace-switch.md), [ADR 0055](0055-practice-scripts-use-one-result-contract.md), [ADR 0027](0027-dialog-lifetime-and-where-a-write-outcome-lands.md)

## Context

ADR 0026 decided how a workspace gets a model and how Hephaestus counts what the model costs.
It then collected six updates and two banners.
Its section on the runtime boundary still named workers after its last update moved the keys to the server.
Main has a protocol that the ADR does not list, and 1.0 removes the models for practice scripts.

This ADR states model access at 1.0 as rules.
[ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) and [ADR 0079](0079-one-agent-system.md) own where the proxy runs and which process holds keys.
The admin guide owns the budget arithmetic and the price fields.

1.0 is a cutover ([ADR 0070](0070-1-0-is-a-cutover-release.md)).
A rule that main does not satisfy yet says "1.0 target" and names the pull request that delivers it.

## Decision drivers

- The object that an admin configures decides the behavior. A purpose has one model and no implicit fan-out.
- Ownership follows the blast radius. Who edits a base URL or a price can redirect a key or move a cap.
- A developer's AI choice is a ceiling. No admin change widens it.
- Two parties' money never mixes. An exhausted host budget does not stop work that a workspace pays for.
- A cap holds while a run is in flight, not only when the run starts.
- A table boundary or a database CHECK is better than a WHERE clause that someone must remember.

## Considered options

1. **Keep named execution profiles (`agent_config`), and fix the fan-out.** This is the smallest change. The key, base URL and price stay with the workspace, and two objects hold one fact.
2. **One catalog table with a nullable `workspace_id`.** This has fewer tables. One forgotten predicate leaks instance keys, and the schema hides the blast radius.
3. **Two catalogs in separate tables, and one binding row for each purpose and tier (chosen).**
4. **A cap only at submission and claim.** This is the simplest gate. One admitted run can then spend without limit.
5. **An AI choice for each workspace membership.** It asks the same question in each workspace. Who operates a model does not change from workspace to workspace.

## Decision

1. **Two catalogs, two tables.** The instance catalog (`llm_connection`, `llm_model`, prices, grants) is global, and only instance admins edit it. A workspace catalog (`workspace_llm_connection`, `workspace_llm_model`) is workspace-scoped. A workspace admin adds to it only while the instance setting allows it.
   Enforced by: `DataIsolationArchitectureTest`, `LlmModelResolverTest#shouldRejectWorkspaceModelOwnedByAnotherWorkspace` and `WorkspaceLlmConnectionControllerIntegrationTest#creatingAConnectionIsForbiddenWhenTheInstanceHasDisabledWorkspaceByoConnections`.
2. **Only the owning scope sets a base URL, a key and a price.** The API never returns a stored key. It shows `hasApiKey` and the last four characters. Each base URL passes the egress policy when an admin saves it and when the proxy connects. The proxy follows no redirect.
   Enforced by: `apiKeyIsNeverExposedInAnyResponse` in both connection controller tests, `EgressPolicyTest` and `LlmProxyWebClientConfigTest#shouldNotFollowARedirectFromTheProvider`. `ProductFeedbackModelAdapter` still posts with a plain client. [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) decision 5 tracks that call. No pull request removes it yet.
3. **One resolver decides availability.** `LlmModelResolver` alone decides if a model can serve. The model and its connection must be enabled, and the protocol supported. The model is the workspace's own, public, or granted.
   Enforced by: `LlmModelResolverTest` and `WorkspaceLlmModelControllerIntegrationTest#availableModelsHidesAGrantedInstanceModelNotGrantedToThisWorkspace`.
4. **A protocol is a wire shape, not a vendor.** 1.0 has three chat protocols: `anthropic-messages`, `openai-completions` and `openai-responses`. The proxy accepts only the path of the bound model's protocol. It sets the model in the body, removes `service_tier` and sends one upstream credential header. It refuses a query string. The one exception is the fixed `beta=true` hint of the Anthropic SDK on `/internal/llm/v1/messages`, which it does not forward.
   1.0 target: main also has `openai-decisions`, `openai-embeddings` and `cohere-rerank`. [#4075](https://github.com/hephaestus-build/Hephaestus/pull/4075) removes them.
   Enforced by: `LlmProxyServiceTest#shouldRejectProtocolPathMismatchBeforeCredentialResolution`, `#shouldRejectQueryBeforeCredentialResolution`, `#shouldInjectNativeAnthropicHeadersWithoutForwardingCallerKeysOrBetas` and `ProxyRequestBodyTest#shouldForceAuthoritativeModel`.
5. **A model serves only after its cost is declared.** A model starts disabled. It activates only when its connection is active and its price is `PRICED` or `NO_CHARGE` (**No metered API cost**). An enabled model cannot become unpriced. A bound model cannot be deleted.
   Enforced by: `LlmModelServiceTest#refusesToCreateAModelThatIsAlreadyActive`, `#metadataUpdateRevalidatesAnEnabledModel`, `#enabledModelCannotBeRepricedToUnpricedAndExistingPriceStaysOpen` and `#deletingAModelStillBoundToAnAgentBindingIsRejected`.
6. **Two purposes.** `PRACTICE_REVIEW` serves pull-request, issue and conversation reviews. `MENTOR` serves conversations with Hephaestus. Both need a chat model. A need for another model is a new purpose value, not a schema change.
   1.0 target: main also has `PRACTICE_DECISION`, `PRACTICE_EMBEDDING` and `PRACTICE_RERANKING`. [#4075](https://github.com/hephaestus-build/Hephaestus/pull/4075) removes them, because a stored practice script calls no model directly ([ADR 0055](0055-practice-scripts-use-one-result-contract.md), update of 2026-11-10).
   Enforced by: `AgentBindingServiceTest#upsertRejectsAModelWhenItsKindDoesNotFitThePurpose`.
7. **One binding names one model.** A `workspace_agent_binding` row binds one purpose and tier of a workspace to exactly one catalog model. No row, or a disabled row, turns the purpose off for that tier. Nothing fans out, and nothing falls back to another model.
   Enforced by: `uk_workspace_agent_binding_purpose_tier`, `ck_workspace_agent_binding_single_model` and `sfk_workspace_agent_binding_workspace_model_scope` in the baseline, and `AgentBindingServiceTest#upsertRejectsWhenNotExactlyOneModelIsProvided`.
8. **Conversations follow their model.** Hephaestus answers where a `MENTOR` binding is ready, as [ADR 0046](0046-heph-follows-its-model-not-a-workspace-switch.md) decides.
9. **The tier comes from one declared fact.** An admin declares **Operated by** for each model. "Your organization" gives `IN_HOUSE`, and "A provider" gives `CLOUD`. Without a declaration, a model is `UNDECLARED` and outside every ceiling. Hephaestus never infers the tier from a name or a URL. A tier row holds only a model of that tier.
   Enforced by: `DataHandlingFactsTest`, `AgentBindingServiceTest#upsertRejectsAModelWhoseDeclaredTierDiffersFromTheSlot` and `LlmModelResolverTest#shouldRefuseAStricterModelInALooserSlotBecauseTheSlotRuleIsExact`.
10. **The AI choice is a ceiling on the account.** A member chooses `NO_AI`, `IN_HOUSE_ONLY` or `CLOUD` once, for every workspace on the instance. A workspace serves the loosest ready binding within the ceiling, never a looser one. `NO_AI` gets no model. The `UNDECLARED` row serves only members who have not chosen, where the choice is optional. A developer without a resolved account has not chosen.
    Enforced by: `ck_account_ai_choice`, and `MemberAiRoutingAdapterTest#shouldPickTheLoosestBindingWithinTheChosenCeiling`, `#shouldNotFallBackToALooserOrUndeclaredBinding`, `#shouldNeverResolveAModelWhenMemberChoosesNoAi` and `#shouldServeTheUndeclaredSlotOnlyToMembersWhoNeverHadToChoose`.
11. **The ceiling is checked on each model request.** A model whose declaration loosened stops serving until an admin assigns it again. A new or removed model never asks members again. A change to what a tier means ships with a migration that clears the saved choices.
    Enforced by: `ProxyRequestPolicyTest#shouldRecheckTheCurrentChoiceOnEveryModelRequest` and `MemberAiRoutingAdapterTest#shouldAllowAModelWithinTheCeilingAndRejectOneThatLoosenedAfterTheChoice`. The migration rule is not enforced.
12. **Admission freezes the price.** The admitted work keeps the model's declared rate for each token class (`LlmPriceSnapshot`). A new price adds a price row and never changes a recorded cost. Hephaestus never scrapes a price, never estimates tokens, and never counts a missing price as zero. [AI providers § Pricing a model](../admin/ai-providers.mdx#4-pricing-a-model) owns the rate fields.
    Enforced by: `LlmUsageLedgerIntegrationTest#recordAppendsOneLedgerRowUsingTheAdmissionPriceSnapshot`, `LlmModelServiceTest#repricingClosesThePriorOpenRowAndInsertsANewOne` and `LlmPriceSnapshotTest#anUnpricedModelYieldsNoCostAtAllRatherThanAZero`.
13. **One ledger row for each model call.** `llm_usage_event` is the one ledger. The proxy writes the row of a call, with its run and epoch, before the call, and completes it after. An open row is unverifiable and is never billed as zero ([ADR 0079](0079-one-agent-system.md) § Usage).
    Decided, not shipped: main writes one ledger row for each attempt or turn, from usage tables for each kind. No pull request delivers the row for each call yet.
    Enforced by (the rule on main): `LlmUsageLedgerIntegrationTest#sourceAttemptIsTheIdempotencyBoundary` and `#unverifiableUsageRetainsAdmissionProvenanceWithoutInventingACost`.
14. **Two purses, never summed.** The shared-model budget caps a workspace's spend on instance models. The provider cap caps its spend on its own connections. Each cap pauses only the work that its purse funds. Spend with an unknown purse counts against both.
    Enforced by: `LlmUsageLedgerIntegrationTest#workspaceFundedSpendNeverCountsTowardTheInstanceBudget`, `LlmBudgetServiceTest#anExhaustedCapBlocksOnlyItsOwnFundingSource` and `#unknownFundingSourceIsFailSafe`.
15. **A cap that cannot be verified pauses.** A capped purse pauses when its month is exhausted or unverifiable. An uncapped purse never pauses. No setting changes this.
    Enforced by: `LlmBudgetServiceTest#theInstancePurseIsBlockedOnlyWhenExhaustedOrUnverifiable` and `#theWorkspaceFundedPurseIsBlockedOnlyWhenExhaustedOrUnverifiable`.
16. **Three gates.** Submission and claim read the ledger and decide if work may start. A review over the cap waits. It is cancelled if it still waits 7 days after submission. The proxy judges each call before it waits for capacity and again before dispatch. It adds the settled spend of the workspace's running work to the ledger, for each purse. It caches the ledger read for at most 5 seconds. No call reserves money ahead. [Workspace budgets and alerting](../admin/production-setup.mdx#workspace-budgets-and-alerting) states the overshoot bound and what it excludes.
    Enforced by: `LlmUsageLedgerIntegrationTest#submitIsBlockedForAnExhaustedWorkspace`, `ReviewJobExecutorTest#shouldCancelOldBudgetHeldWorkEvenAfterItsAvailabilityWasMoved`, `ProxyBudgetGateTest#theRunningSpendIsFreshEvenWhenTheLedgerVerdictIsCached`, and `LlmProxyIntegrationTest#shouldRefuseACallThatTheCapOvertookWhileItWaitedForCapacity` and `#shouldCountWhatRunningAttemptsSpentOncePerPurseUntilTheyEnd`.
17. **Model calls wait in bands, with a fair share for each workspace,** as [ADR 0079](0079-one-agent-system.md) § Permits decides.
    Enforced by: `LlmCallPermitsIntegrationTest`.
18. **Where the proxy runs and which process holds keys:** [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) decision 5.

## What changed, and why

- **Three chat protocols and two purposes.** ADR 0026 listed five protocols and five purposes. #3437 added `anthropic-messages`. The script purposes and their protocols lost their only caller when stored scripts stopped calling models ([ADR 0055](0055-practice-scripts-use-one-result-contract.md)).
- **The server resolves keys and admits work.** ADR 0026 § Current runtime boundary named workers. 1.0 has no worker ([ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md)).
- **One usage row for each call** replaces the ledger write at the end of an attempt. No reaper bills a lost turn ([ADR 0079](0079-one-agent-system.md) § Usage).
- **One shared headroom** replaces the bound for each execution (ADR 0026, update of 2026-11-06). The arithmetic now has one home, the admin guide.

## Consequences

- Good: a workspace admin never sees or redirects an instance key. A workspace query cannot select one.
- Good: a new price never changes the cost of work that was already admitted.
- Good: a member's choice holds in every workspace, and no admin change widens it.
- Good: an exhausted purse never stops work that the other purse funds.
- Bad: each catalog field exists twice. The resolver absorbs the reads, but the write paths stay doubled.
- Bad: one model for each purpose and tier. A cheaper model for issue reviews needs a new purpose.
- Bad: the calls in flight when a purse reaches its cap can pass the cap.
- Bad: a call without a usage block counts nothing. The provider invoice can then be higher than the ledger.
- Bad: one unpriced call pauses a capped purse for the rest of the month.
- Bad: a member whose ceiling has no ready binding gets no reviews and no answers in that workspace.

## Confirmation

Each rule above names its test or database constraint.
The migration rule of decision 11 is not enforced. Review of a change to `DataHandlingTier` catches it.
Decision 13 has no test until the row for each call ships.

## Revisit trigger

- A third purse appears, for example a team budget. "Never summed" must then cover more than two purses.
- A purpose needs more than one model, for example a fallback chain. Then `uk_workspace_agent_binding_purpose_tier` must change.
- A provider protocol cannot be pinned to one path, one credential header and a usage block.

## Sources

- OWASP, [SSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html): "Deny-lists are bypass-prone. Prefer allow-lists." It also says "Disable the support for the following of the redirection". This is why only the owning admin sets a base URL, and the proxy follows no redirect (decision 2).
- Anthropic, [Prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching): `input_tokens` counts only tokens "after the last cache breakpoint". Cache reads and writes have their own prices. So a price is a rate for each token class, never one input rate (decision 12).
- OpenAI, [Create chat completion](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create): `include_usage` adds a final chunk that "shows the token usage statistics for the entire request". `service_tier` "Specifies the processing type used for serving the request." So the proxy asks for usage and removes `service_tier`, which can change the price (decision 4).

## Already priced and declined

Each line links the section that priced the option.

- [ADR 0026](0026-per-purpose-agent-bindings-and-llm-governance.md#considered-options): keep `agent_config` and fix the fan-out.
- [ADR 0026](0026-per-purpose-agent-bindings-and-llm-governance.md#considered-options): one catalog table with a nullable `workspace_id`.
- [ADR 0026](0026-per-purpose-agent-bindings-and-llm-governance.md#considered-options): a cap only at submission and claim.
- [ADR 0026](0026-per-purpose-agent-bindings-and-llm-governance.md#decision): cancel queued work over the cap, and a hold with no limit.
- [ADR 0026](0026-per-purpose-agent-bindings-and-llm-governance.md#decision): a `WARN`/`BLOCK` setting for usage without a price.
- [ADR 0026](0026-per-purpose-agent-bindings-and-llm-governance.md) (amendment of 2026-09-22): an AI choice for each membership, and a narrow-only override for each workspace.
- [ADR 0026](0026-per-purpose-agent-bindings-and-llm-governance.md) (second amendment of 2026-09-22): tiers that split providers by retention (`kept_after_reply`).
- [ADR 0026](0026-per-purpose-agent-bindings-and-llm-governance.md#update--2026-11-06-running-work-shares-one-headroom): a reservation for each call.
- [ADR 0026](0026-per-purpose-agent-bindings-and-llm-governance.md#update--2026-11-03-no-per-location-limit-on-running-reviews): an operator limit on running reviews for each location.
- [ADR 0006](0006-llm-proxy-on-coordinator-trust-model.md#considered-and-rejected-for-this-update): keys in worker or sandbox configuration, and a catch-all provider proxy.
- [ADR 0006](0006-llm-proxy-on-coordinator-trust-model.md#considered-and-rejected-for-this-update): a price lookup at completion, and a dollar cost from the provider.
- [ADR 0006](0006-llm-proxy-on-coordinator-trust-model.md#considered-and-rejected-for-this-update): Azure-specific protocol and `api-version` columns.
- [ADR 0006](0006-llm-proxy-on-coordinator-trust-model.md#considered-and-rejected-for-this-update): an accounting outbox, a hard reservation, and accounting in an external gateway.
