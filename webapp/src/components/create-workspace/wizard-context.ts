import { createContext, useContext } from "react";

import type { GitLabGroup, GitLabPreflightResponse } from "@/api/types.gen";

import { generateSlug } from "./slug-utils";

export type WizardStep = 1 | 2 | 3;

export interface WizardState {
	step: WizardStep;
	// Step 1
	serverUrl: string;
	personalAccessToken: string;
	preflightResult: GitLabPreflightResponse | null;
	// Step 2
	groups: GitLabGroup[];
	selectedGroup: GitLabGroup | null;
	// Step 3
	displayName: string;
	workspaceSlug: string;
	slugManuallyEdited: boolean;
}

export type WizardAction =
	| { type: "SET_SERVER_URL"; value: string }
	| { type: "SET_PAT"; value: string }
	| { type: "SET_PREFLIGHT_RESULT"; result: GitLabPreflightResponse }
	| { type: "ADVANCE_TO_GROUPS"; groups: GitLabGroup[] }
	| { type: "SELECT_GROUP"; group: GitLabGroup }
	| { type: "ADVANCE_TO_CONFIGURE" }
	| { type: "SET_DISPLAY_NAME"; value: string }
	| { type: "SET_SLUG"; value: string; manual: boolean }
	| { type: "GO_BACK" }
	| { type: "RESET" };

export function createInitialWizardState(defaultServerUrl?: string): WizardState {
	return {
		step: 1,
		serverUrl: defaultServerUrl ?? "",
		personalAccessToken: "",
		preflightResult: null,
		groups: [],
		selectedGroup: null,
		displayName: "",
		workspaceSlug: "",
		slugManuallyEdited: false,
	};
}

export const initialWizardState: WizardState = createInitialWizardState();

export function wizardReducer(state: WizardState, action: WizardAction): WizardState {
	switch (action.type) {
		case "SET_SERVER_URL": {
			// Changing server URL invalidates preflight result
			return { ...state, serverUrl: action.value, preflightResult: null };
		}
		case "SET_PAT": {
			// Changing PAT invalidates preflight result
			return { ...state, personalAccessToken: action.value, preflightResult: null };
		}
		case "SET_PREFLIGHT_RESULT": {
			return { ...state, preflightResult: action.result };
		}
		case "ADVANCE_TO_GROUPS": {
			if (state.step !== 1) {
				return state;
			}
			return { ...state, step: 2, groups: action.groups };
		}
		case "SELECT_GROUP": {
			if (state.step !== 2) {
				return state;
			}
			return { ...state, selectedGroup: action.group };
		}
		case "ADVANCE_TO_CONFIGURE": {
			if (state.step !== 2 || !state.selectedGroup) {
				return state;
			}
			// Auto-populate display name and slug from group name on first entry
			const { name } = state.selectedGroup;
			return {
				...state,
				step: 3,
				displayName: state.displayName || name,
				workspaceSlug: state.workspaceSlug || generateSlug(name),
			};
		}
		case "SET_DISPLAY_NAME": {
			return { ...state, displayName: action.value };
		}
		case "SET_SLUG": {
			return {
				...state,
				workspaceSlug: action.value,
				slugManuallyEdited: action.manual,
			};
		}
		case "GO_BACK": {
			if (state.step === 1) {
				return state;
			}
			// Clear downstream state so stale values don't persist when user changes selection
			if (state.step === 2) {
				return {
					...state,
					step: 1,
					groups: [],
					selectedGroup: null,
					displayName: "",
					workspaceSlug: "",
					slugManuallyEdited: false,
				};
			}
			return {
				...state,
				step: 2,
				displayName: "",
				workspaceSlug: "",
				slugManuallyEdited: false,
			};
		}
		case "RESET": {
			return initialWizardState;
		}
		default: {
			const _exhaustive: never = action;
			return _exhaustive;
		}
	}
}

interface WizardContextValue {
	state: WizardState;
	dispatch: React.Dispatch<WizardAction>;
}

export const WizardContext = createContext<WizardContextValue | null>(null);

export function useWizard(): WizardContextValue {
	const ctx = useContext(WizardContext);
	if (!ctx) {
		throw new Error("useWizard must be used within a WizardContext.Provider");
	}
	return ctx;
}
