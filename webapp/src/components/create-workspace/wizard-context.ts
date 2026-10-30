import { createContext, useContext } from "react";

import type {
	CreateWorkspaceRequest,
	GitLabGroup,
	GitLabPreflightRequest,
	GitLabPreflightResponse,
} from "@/api/types.gen";

import { generateSlug } from "./slug-utils";

export type WizardStep = 1 | 2 | 3;

/** Which of the group's projects the workspace monitors: every one, or only those chosen. */
export type ProjectScope = NonNullable<CreateWorkspaceRequest["repositorySelection"]>;

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
	projectScope: ProjectScope;
	/**
	 * The GitLab project IDs chosen for the selected group, kept across pages and searches. Kept when
	 * the scope goes back to all projects, so choosing selected projects again restores them; only
	 * selected projects send them.
	 */
	selectedProjectIds: number[];
}

/** No project chosen yet: the workspace monitors every project of the group. */
const NO_PROJECT_CHOICE = { projectScope: "ALL", selectedProjectIds: [] } satisfies Pick<
	WizardState,
	"projectScope" | "selectedProjectIds"
>;

export type WizardAction =
	| { type: "SET_PAT"; value: string }
	| {
			type: "SET_PREFLIGHT_RESULT";
			result: GitLabPreflightResponse;
			request: GitLabPreflightRequest;
	  }
	| { type: "ADVANCE_TO_GROUPS"; groups: GitLabGroup[]; request: GitLabPreflightRequest }
	| { type: "SELECT_GROUP"; group: GitLabGroup }
	| { type: "ADVANCE_TO_CONFIGURE" }
	| { type: "SET_DISPLAY_NAME"; value: string }
	| { type: "SET_SLUG"; value: string; manual: boolean }
	| { type: "SET_PROJECT_SCOPE"; scope: ProjectScope }
	| { type: "SET_PROJECT_SELECTED"; projectId: number; selected: boolean }
	| { type: "GO_BACK" };

export function createInitialWizardState(serverUrl: string): WizardState {
	return {
		step: 1,
		serverUrl,
		personalAccessToken: "",
		preflightResult: null,
		groups: [],
		selectedGroup: null,
		displayName: "",
		workspaceSlug: "",
		slugManuallyEdited: false,
		...NO_PROJECT_CHOICE,
	};
}

/** A response settles the token it was requested with; one that arrives after an edit is dropped. */
export function isForCurrentToken(state: WizardState, request: GitLabPreflightRequest): boolean {
	return request.personalAccessToken === state.personalAccessToken;
}

/**
 * A project list answers the token and group it was requested for; one for an earlier token or
 * group lends no projects to the current choice.
 */
export function isForCurrentGroup(state: WizardState, request: GitLabPreflightRequest): boolean {
	return (
		isForCurrentToken(state, request) && request.groupFullPath === state.selectedGroup?.fullPath
	);
}

/** Whether the project choice can be sent: all projects, or at least one chosen project. */
export function hasProjectChoice(state: WizardState): boolean {
	return state.projectScope === "ALL" || state.selectedProjectIds.length > 0;
}

/** The project choice as the creation request states it: IDs only with selected projects. */
export function projectSelectionOf(
	state: WizardState,
): Pick<CreateWorkspaceRequest, "repositorySelection" | "selectedProjectIds"> {
	return state.projectScope === "ALL"
		? { repositorySelection: "ALL" }
		: { repositorySelection: "SELECTED", selectedProjectIds: state.selectedProjectIds };
}

export function wizardReducer(state: WizardState, action: WizardAction): WizardState {
	switch (action.type) {
		case "SET_PAT": {
			// Changing PAT invalidates preflight result, and every project the old token could read
			return {
				...state,
				personalAccessToken: action.value,
				preflightResult: null,
				...NO_PROJECT_CHOICE,
			};
		}
		case "SET_PREFLIGHT_RESULT": {
			if (!isForCurrentToken(state, action.request)) {
				return state;
			}
			return { ...state, preflightResult: action.result };
		}
		case "ADVANCE_TO_GROUPS": {
			if (state.step !== 1 || !isForCurrentToken(state, action.request)) {
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
		case "SET_PROJECT_SCOPE": {
			if (state.step !== 3) {
				return state;
			}
			return { ...state, projectScope: action.scope };
		}
		case "SET_PROJECT_SELECTED": {
			if (state.step !== 3 || !(action.projectId > 0)) {
				return state;
			}
			const others = state.selectedProjectIds.filter((id) => id !== action.projectId);
			return {
				...state,
				selectedProjectIds: action.selected ? [...others, action.projectId] : others,
			};
		}
		case "GO_BACK": {
			if (state.step === 1) {
				return state;
			}
			// Clear downstream state so stale values don't persist when user changes selection. The
			// projects belong to the group, so leaving the step that chose them clears them too.
			if (state.step === 2) {
				return {
					...state,
					step: 1,
					groups: [],
					selectedGroup: null,
					displayName: "",
					workspaceSlug: "",
					slugManuallyEdited: false,
					...NO_PROJECT_CHOICE,
				};
			}
			return {
				...state,
				step: 2,
				displayName: "",
				workspaceSlug: "",
				slugManuallyEdited: false,
				...NO_PROJECT_CHOICE,
			};
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
