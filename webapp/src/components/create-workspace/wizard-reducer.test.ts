import { describe, expect, it } from "vitest";

import type { GitLabGroup, GitLabPreflightResponse } from "@/api/types.gen";
import {
	createInitialWizardState,
	type WizardState,
	wizardReducer,
} from "@/components/create-workspace/wizard-context";

const initialWizardState = createInitialWizardState("https://gitlab.lrz.de");
const validPreflight: GitLabPreflightResponse = { valid: true, username: "admin" };
const request = {
	serverUrl: initialWizardState.serverUrl,
	personalAccessToken: initialWizardState.personalAccessToken,
};
const sampleGroup: GitLabGroup = {
	id: 1,
	name: "Hephaestus",
	fullPath: "ls1intum/hephaestus",
	visibility: "public",
};
const sampleGroups: GitLabGroup[] = [
	sampleGroup,
	{ id: 2, name: "Artemis", fullPath: "ls1intum/artemis" },
];

function stateAt(step: 1 | 2 | 3, overrides: Partial<WizardState> = {}): WizardState {
	const base: Record<1 | 2 | 3, Partial<WizardState>> = {
		1: {},
		2: { step: 2, preflightResult: validPreflight, groups: sampleGroups },
		3: {
			step: 3,
			preflightResult: validPreflight,
			groups: sampleGroups,
			selectedGroup: sampleGroup,
		},
	};
	return { ...initialWizardState, ...base[step], ...overrides };
}

describe("wizardReducer", () => {
	describe("SET_PAT", () => {
		it("sets the PAT and clears preflight result", () => {
			const state = stateAt(1, { preflightResult: validPreflight });
			const result = wizardReducer(state, { type: "SET_PAT", value: "glpat-new" });
			expect(result.personalAccessToken).toBe("glpat-new");
			expect(result.preflightResult).toBeNull();
		});
	});

	describe("SET_PREFLIGHT_RESULT", () => {
		it("stores the preflight result", () => {
			const result = wizardReducer(initialWizardState, {
				type: "SET_PREFLIGHT_RESULT",
				result: validPreflight,
				request,
			});
			expect(result.preflightResult).toStrictEqual(validPreflight);
		});

		it("drops a result for a token edited since the request", () => {
			const state = stateAt(1, { personalAccessToken: "glpat-edited" });
			const result = wizardReducer(state, {
				type: "SET_PREFLIGHT_RESULT",
				result: validPreflight,
				request: { ...request, personalAccessToken: "glpat-sent" },
			});
			expect(result).toBe(state);
		});
	});

	describe("ADVANCE_TO_GROUPS", () => {
		it("advances from step 1 to step 2 with groups", () => {
			const result = wizardReducer(initialWizardState, {
				type: "ADVANCE_TO_GROUPS",
				groups: sampleGroups,
				request,
			});
			expect(result.step).toBe(2);
			expect(result.groups).toStrictEqual(sampleGroups);
		});

		it("drops groups listed for a token edited since the request", () => {
			const state = stateAt(1, { personalAccessToken: "glpat-edited" });
			const result = wizardReducer(state, { type: "ADVANCE_TO_GROUPS", groups: [], request });
			expect(result).toBe(state);
		});

		it("rejects advancement from step 2 (guard)", () => {
			const state = stateAt(2);
			const result = wizardReducer(state, { type: "ADVANCE_TO_GROUPS", groups: [], request });
			// unchanged reference
			expect(result).toBe(state);
		});

		it("rejects advancement from step 3 (guard)", () => {
			const state = stateAt(3);
			const result = wizardReducer(state, { type: "ADVANCE_TO_GROUPS", groups: [], request });
			expect(result).toBe(state);
		});
	});

	describe("SELECT_GROUP", () => {
		it("selects a group on step 2", () => {
			const state = stateAt(2);
			const result = wizardReducer(state, { type: "SELECT_GROUP", group: sampleGroup });
			expect(result.selectedGroup).toStrictEqual(sampleGroup);
		});

		it("rejects selection from step 1 (guard)", () => {
			const state = stateAt(1);
			const result = wizardReducer(state, { type: "SELECT_GROUP", group: sampleGroup });
			expect(result).toBe(state);
		});

		it("rejects selection from step 3 (guard)", () => {
			const state = stateAt(3);
			const result = wizardReducer(state, { type: "SELECT_GROUP", group: sampleGroup });
			expect(result).toBe(state);
		});
	});

	describe("ADVANCE_TO_CONFIGURE", () => {
		it("advances from step 2 to step 3 when group is selected", () => {
			const state = stateAt(2, { selectedGroup: sampleGroup });
			const result = wizardReducer(state, { type: "ADVANCE_TO_CONFIGURE" });
			expect(result.step).toBe(3);
		});

		it("auto-populates displayName and slug from group name", () => {
			const state = stateAt(2, { selectedGroup: sampleGroup });
			const result = wizardReducer(state, { type: "ADVANCE_TO_CONFIGURE" });
			expect(result.displayName).toBe("Hephaestus");
			expect(result.workspaceSlug).toBe("hephaestus");
		});

		it("preserves existing displayName and slug (no overwrite)", () => {
			const state = stateAt(2, {
				selectedGroup: sampleGroup,
				displayName: "Custom Name",
				workspaceSlug: "custom-slug",
			});
			const result = wizardReducer(state, { type: "ADVANCE_TO_CONFIGURE" });
			expect(result.displayName).toBe("Custom Name");
			expect(result.workspaceSlug).toBe("custom-slug");
		});

		it("rejects advancement when no group is selected (guard)", () => {
			const state = stateAt(2, { selectedGroup: null });
			const result = wizardReducer(state, { type: "ADVANCE_TO_CONFIGURE" });
			expect(result).toBe(state);
		});

		it("rejects advancement from step 1 (guard)", () => {
			const state = stateAt(1, { selectedGroup: sampleGroup });
			const result = wizardReducer(state, { type: "ADVANCE_TO_CONFIGURE" });
			expect(result).toBe(state);
		});

		it("rejects advancement from step 3 (guard)", () => {
			const state = stateAt(3);
			const result = wizardReducer(state, { type: "ADVANCE_TO_CONFIGURE" });
			expect(result).toBe(state);
		});
	});

	describe("SET_DISPLAY_NAME", () => {
		it("sets the display name", () => {
			const result = wizardReducer(stateAt(3), { type: "SET_DISPLAY_NAME", value: "My WS" });
			expect(result.displayName).toBe("My WS");
		});
	});

	describe("SET_SLUG", () => {
		it("sets the slug and manual flag", () => {
			const result = wizardReducer(stateAt(3), {
				type: "SET_SLUG",
				value: "my-ws",
				manual: true,
			});
			expect(result.workspaceSlug).toBe("my-ws");
			expect(result.slugManuallyEdited).toBe(true);
		});

		it("sets auto-generated slug with manual false", () => {
			const result = wizardReducer(stateAt(3), {
				type: "SET_SLUG",
				value: "auto-slug",
				manual: false,
			});
			expect(result.workspaceSlug).toBe("auto-slug");
			expect(result.slugManuallyEdited).toBe(false);
		});
	});

	describe("GO_BACK", () => {
		it("returns same state from step 1 (no-op)", () => {
			const state = stateAt(1);
			const result = wizardReducer(state, { type: "GO_BACK" });
			expect(result).toBe(state);
		});

		it("goes from step 2 to step 1, clearing downstream state", () => {
			const state = stateAt(2, { selectedGroup: sampleGroup });
			const result = wizardReducer(state, { type: "GO_BACK" });
			expect(result.step).toBe(1);
			expect(result.groups).toStrictEqual([]);
			expect(result.selectedGroup).toBeNull();
			expect(result.displayName).toBe("");
			expect(result.workspaceSlug).toBe("");
		});

		it("goes from step 3 to step 2, clearing step-3 state but preserving groups", () => {
			const state = stateAt(3, {
				displayName: "Test",
				workspaceSlug: "test",
				slugManuallyEdited: true,
			});
			const result = wizardReducer(state, { type: "GO_BACK" });
			expect(result.step).toBe(2);
			expect(result.displayName).toBe("");
			expect(result.workspaceSlug).toBe("");
			expect(result.slugManuallyEdited).toBe(false);
			// Groups and selectedGroup preserved
			expect(result.groups).toStrictEqual(sampleGroups);
			expect(result.selectedGroup).toStrictEqual(sampleGroup);
		});
	});

	describe("integration flows", () => {
		it("full forward flow: step 1 → 2 → 3", () => {
			let state = initialWizardState;
			state = wizardReducer(state, { type: "SET_PAT", value: "glpat-test" });
			const sent = { serverUrl: state.serverUrl, personalAccessToken: "glpat-test" };
			state = wizardReducer(state, {
				type: "SET_PREFLIGHT_RESULT",
				result: validPreflight,
				request: sent,
			});
			state = wizardReducer(state, {
				type: "ADVANCE_TO_GROUPS",
				groups: sampleGroups,
				request: sent,
			});
			expect(state.step).toBe(2);

			state = wizardReducer(state, { type: "SELECT_GROUP", group: sampleGroup });
			state = wizardReducer(state, { type: "ADVANCE_TO_CONFIGURE" });
			expect(state.step).toBe(3);
			expect(state.displayName).toBe("Hephaestus");
			expect(state.workspaceSlug).toBe("hephaestus");
		});

		it("round-trip: 1 → 2 → 3 → back → back → 1", () => {
			let state = initialWizardState;
			state = wizardReducer(state, { type: "ADVANCE_TO_GROUPS", groups: sampleGroups, request });
			state = wizardReducer(state, { type: "SELECT_GROUP", group: sampleGroup });
			state = wizardReducer(state, { type: "ADVANCE_TO_CONFIGURE" });
			expect(state.step).toBe(3);

			state = wizardReducer(state, { type: "GO_BACK" });
			expect(state.step).toBe(2);
			expect(state.displayName).toBe("");

			state = wizardReducer(state, { type: "GO_BACK" });
			expect(state.step).toBe(1);
			expect(state.groups).toStrictEqual([]);
			expect(state.selectedGroup).toBeNull();
		});

		it("back-and-forward picks up new group name", () => {
			const groupB: GitLabGroup = {
				id: 99,
				name: "New Group",
				fullPath: "org/new-group",
			};
			let state = initialWizardState;
			state = wizardReducer(state, {
				type: "ADVANCE_TO_GROUPS",
				groups: [sampleGroup, groupB],
				request,
			});
			state = wizardReducer(state, { type: "SELECT_GROUP", group: sampleGroup });
			state = wizardReducer(state, { type: "ADVANCE_TO_CONFIGURE" });
			expect(state.displayName).toBe("Hephaestus");

			// Go back, pick a different group, advance again
			state = wizardReducer(state, { type: "GO_BACK" });
			state = wizardReducer(state, { type: "SELECT_GROUP", group: groupB });
			state = wizardReducer(state, { type: "ADVANCE_TO_CONFIGURE" });
			// displayName was cleared on GO_BACK, so auto-populate uses new group
			expect(state.displayName).toBe("New Group");
			expect(state.workspaceSlug).toBe("new-group");
		});

		it("still advances on ADVANCE_TO_GROUPS with an empty array", () => {
			const state = initialWizardState;
			const result = wizardReducer(state, { type: "ADVANCE_TO_GROUPS", groups: [], request });
			expect(result.step).toBe(2);
			expect(result.groups).toStrictEqual([]);
		});
	});
});
