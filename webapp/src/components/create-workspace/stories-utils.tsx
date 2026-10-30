import { useReducer } from "react";
import { fn } from "storybook/test";

import type { GitLabGroup } from "@/api/types.gen";

import {
	createInitialWizardState,
	type WizardAction,
	WizardContext,
	type WizardState,
	wizardReducer,
} from "./wizard-context";

export function withWizardState(overrides: Partial<WizardState>) {
	const state: WizardState = { ...createInitialWizardState("https://gitlab.com"), ...overrides };
	return function WizardDecorator(Story: React.ComponentType) {
		return (
			<WizardContext.Provider value={{ state, dispatch: fn<React.Dispatch<WizardAction>>() }}>
				<Story />
			</WizardContext.Provider>
		);
	};
}

/** The same state behind the wizard's own reducer, for a story that types into the step. */
export function withWizardReducer(overrides: Partial<WizardState>) {
	const initial: WizardState = { ...createInitialWizardState("https://gitlab.com"), ...overrides };
	return function WizardReducerDecorator(Story: React.ComponentType) {
		const [state, dispatch] = useReducer(wizardReducer, initial);
		return (
			<WizardContext.Provider value={{ state, dispatch }}>
				<Story />
			</WizardContext.Provider>
		);
	};
}

export const makeGroup = (
	id: number,
	name: string,
	fullPath: string,
	opts: Partial<GitLabGroup> = {},
): GitLabGroup => ({
	id,
	name,
	fullPath,
	avatarUrl: `https://gitlab.com/uploads/-/system/group/avatar/${id}/avatar.png`,
	visibility: "private",
	...opts,
});
