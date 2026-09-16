import { fn } from "storybook/test";

import type { GitLabGroup } from "@/api/types.gen";

import { initialWizardState, WizardContext, type WizardState } from "./wizard-context";

export function withWizardState(overrides: Partial<WizardState>) {
	const state: WizardState = { ...initialWizardState, ...overrides };
	return function WizardDecorator(Story: React.ComponentType) {
		return (
			<WizardContext.Provider value={{ state, dispatch: fn() }}>
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
