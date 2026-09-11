import { describe, expect, it } from "vitest";

import type { ConfigAuditEntryView } from "@/api/types.gen";

import {
	actionLabel,
	actorDisplay,
	changeSummary,
	entityTypeLabel,
	fieldChanges,
	formatLeaf,
	subjectLabel,
} from "./config-audit-format";

const SNAPSHOT = '{"name":"Primary","enabled":true}';

function entry(over: Partial<ConfigAuditEntryView>): ConfigAuditEntryView {
	return {
		id: 1,
		occurredAt: new Date("2026-07-10T10:00:00Z"),
		actorKind: "USER",
		elevatedViaInstanceAdmin: false,
		action: "UPDATED",
		entityType: "PRACTICE_REVIEW_SETTINGS",
		entityId: "5",
		changedKeys: [],
		...over,
	};
}

describe("label mapping", () => {
	it("maps known types and actions to human labels", () => {
		expect(entityTypeLabel("AGENT_CONFIG")).toBe("Agent config");
		expect(entityTypeLabel("PRACTICE_DEFINITION")).toBe("Practice");
		expect(actionLabel("CREATED")).toBe("Created");
	});
	it("names which purse a budget row is about, under either spelling", () => {
		expect(entityTypeLabel("WORKSPACE_INSTANCE_LLM_BUDGET")).toBe("Shared-model AI budget");
		expect(entityTypeLabel("WORKSPACE_LLM_BUDGET")).toBe("Shared-model AI budget");
		expect(entityTypeLabel("WORKSPACE_OWN_PROVIDER_LLM_BUDGET")).toBe("Own-provider AI cap");
		expect(entityTypeLabel("WORKSPACE_BYO_LLM_BUDGET")).toBe("Own-provider AI cap");
	});

	it("falls back to the raw value for anything unknown", () => {
		expect(entityTypeLabel("FUTURE_TYPE")).toBe("FUTURE_TYPE");
		expect(entityTypeLabel(undefined)).toBe("Unknown");
		expect(actionLabel(undefined)).toBe("—");
	});
});

describe("fieldChanges", () => {
	it("resolves before/after leaves for each changed key", () => {
		const changes = fieldChanges(
			entry({
				changedKeys: ["cooldownMinutes"],
				oldValue: '{"cooldownMinutes":30,"skipDrafts":true}',
				newValue: '{"cooldownMinutes":10,"skipDrafts":true}',
			}),
		);
		expect(changes).toStrictEqual([{ path: "cooldownMinutes", before: "30", after: "10" }]);
	});

	it("renders a cleared override as 'not set', not an absent field", () => {
		const [change] = fieldChanges(
			entry({
				changedKeys: ["cooldownMinutes"],
				oldValue: '{"cooldownMinutes":30}',
				newValue: '{"cooldownMinutes":null}',
			}),
		);
		expect(change).toStrictEqual({ path: "cooldownMinutes", before: "30", after: "not set" });
	});

	it("resolves nested dot-paths to the leaf, not the container", () => {
		const [change] = fieldChanges(
			entry({
				changedKeys: ["volumeCaps.perPullRequest"],
				oldValue: '{"volumeCaps":{"perPullRequest":5}}',
				newValue: '{"volumeCaps":{"perPullRequest":3}}',
			}),
		);
		expect(change).toStrictEqual({ path: "volumeCaps.perPullRequest", before: "5", after: "3" });
	});

	it("masks a credential boolean end-to-end through fieldChanges, not just in formatLeaf isolation", () => {
		expect(
			fieldChanges(
				entry({
					entityType: "AGENT_CONFIG",
					changedKeys: ["llmApiKeySet"],
					oldValue: '{"llmApiKeySet":false}',
					newValue: '{"llmApiKeySet":true}',
				}),
			),
		).toStrictEqual([{ path: "llmApiKeySet", before: "not set", after: "••••••" }]);
		expect(
			fieldChanges(
				entry({
					changedKeys: ["publicKey"],
					oldValue: '{"publicKey":false}',
					newValue: '{"publicKey":true}',
				}),
			),
		).toStrictEqual([{ path: "publicKey", before: "false", after: "true" }]);
	});

	it.each<
		["CREATED" | "DELETED", "before" | "after", Pick<ConfigAuditEntryView, "oldValue" | "newValue">]
	>([
		["CREATED", "before", { newValue: SNAPSHOT }],
		["DELETED", "after", { oldValue: SNAPSHOT }],
	])("gives a %s row no %s side", (action, absentSide, snapshot) => {
		const changes = fieldChanges(entry({ action, changedKeys: ["name", "enabled"], ...snapshot }));
		expect(changes.every((change) => change[absentSide] === null)).toBe(true);
		expect(changes.map((change) => change.path).sort()).toStrictEqual(["enabled", "name"]);
	});
});

describe("formatLeaf", () => {
	it("masks a credential-shaped boolean so it is never mistaken for the secret", () => {
		expect(formatLeaf(true, "llmApiKeySet")).toBe("••••••");
		expect(formatLeaf(false, "llmApiKeySet")).toBe("not set");
	});
	it.each<[unknown, string | undefined, string]>([
		[true, "enabled", "true"],
		[42, "timeoutSeconds", "42"],
		["gpt-5", "modelName", "gpt-5"],
		[null, undefined, "not set"],
		[undefined, undefined, "not set"],
	])("renders %s as %s", (value, key, expected) => {
		expect(formatLeaf(value, key)).toBe(expected);
	});
});

describe("changeSummary", () => {
	it("leaves the summary empty for created/deleted, where the Action badge already says it", () => {
		expect(changeSummary(entry({ action: "CREATED", newValue: "{}" }))).toBe("");
		expect(changeSummary(entry({ action: "DELETED", oldValue: "{}" }))).toBe("");
	});
	it("spells out one or two field changes inline", () => {
		expect(
			changeSummary(
				entry({
					changedKeys: ["cooldownMinutes"],
					oldValue: '{"cooldownMinutes":30}',
					newValue: '{"cooldownMinutes":10}',
				}),
			),
		).toBe("cooldownMinutes: 30 → 10");
	});
	it("collapses many field changes to a count, leaving the field names to the detail sheet", () => {
		expect(
			changeSummary(
				entry({
					changedKeys: ["a", "b", "c"],
					oldValue: '{"a":1,"b":1,"c":1}',
					newValue: '{"a":2,"b":2,"c":2}',
				}),
			),
		).toBe("3 fields changed");
	});
});

describe("subjectLabel", () => {
	it("enriches the subject with a name from the snapshot when present", () => {
		expect(
			subjectLabel(
				entry({
					entityType: "AGENT_CONFIG",
					entityId: "42",
					newValue: '{"name":"GPT-5 reviewer"}',
				}),
			),
		).toStrictEqual({ label: 'Agent config "GPT-5 reviewer"', hint: "Agent config #42" });
	});
	it("falls back to type + identifier without inventing a name", () => {
		expect(
			subjectLabel(entry({ entityType: "AGENT_CONFIG", entityId: "42", newValue: "{}" })),
		).toStrictEqual({
			label: "Agent config #42",
		});
	});
	it("renders a slug identifier as-is", () => {
		expect(
			subjectLabel(
				entry({ entityType: "AGENT_BINDING", entityId: "practice-config", newValue: "{}" }),
			),
		).toStrictEqual({ label: "AI binding practice-config" });
	});
});

describe("actorDisplay", () => {
	it("shows a signed-in user by name", () => {
		expect(
			actorDisplay(
				entry({ actorKind: "USER", actorAccountId: 7, actor: { id: 7, displayName: "Grace" } }),
			),
		).toStrictEqual({ kind: "USER", primary: "Grace", primaryEmail: undefined, filterId: 7 });
	});
	it("labels a background actor 'System'", () => {
		expect(actorDisplay(entry({ actorKind: "SYSTEM", actor: undefined }))).toStrictEqual({
			kind: "SYSTEM",
			primary: "System",
		});
	});
	it("attributes impersonation to the operator, showing the assumed identity", () => {
		expect(
			actorDisplay(
				entry({
					actorKind: "IMPERSONATED",
					actorAccountId: 42,
					actor: { id: 42, displayName: "Ada" },
					actingAccountId: 7,
					actingActor: { id: 7, displayName: "Grace" },
				}),
			),
		).toStrictEqual({
			kind: "IMPERSONATED",
			primary: "Grace",
			primaryEmail: undefined,
			actingAs: "Ada",
			filterId: 7,
		});
	});
});

describe("degrading on unusable data", () => {
	it("treats an unparseable snapshot as absent rather than blanking the row", () => {
		const broken = entry({ action: "UPDATED", oldValue: "{not json", newValue: "{also not json" });
		expect(fieldChanges(broken)).toStrictEqual([]);
		expect(changeSummary(broken)).toBe("");
	});
});
