import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import type { WorkspaceOnboardingLink, WorkspaceOnboardingSettings } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { Stateful } from "@/stories/stateful";
import { expectGenuinelyDisabled, expectUnavailable } from "@/test/controls";
import { expectNoPageOverflow } from "@/test/reflow";

import {
	type SettingsSubmission,
	WorkspaceOnboardingSettingsPage,
	type WorkspaceOnboardingSettingsPageProps,
} from "./WorkspaceOnboardingSettingsPage";

type State = WorkspaceOnboardingSettingsPageProps["state"];
type ReadyState = Extract<State, { status: "ready" }>;

const settings = {
	enabled: false,
	revision: 0,
	welcomeMarkdown: "",
	requiredConnectionIds: [],
} satisfies WorkspaceOnboardingSettings;
const configured = {
	...settings,
	enabled: true,
	revision: 1,
	welcomeMarkdown: "Welcome to our team!",
	requiredConnectionIds: [1],
} satisfies WorkspaceOnboardingSettings;
const slack = {
	connectionId: 1,
	displayName: "Slack",
	providerType: "SLACK",
	teamName: "Engineering",
	required: true,
	available: true,
	linked: false,
} satisfies WorkspaceOnboardingLink;
const outline = {
	connectionId: 2,
	displayName: "Outline",
	providerType: "OUTLINE",
	required: false,
	available: true,
	linked: false,
} satisfies WorkspaceOnboardingLink;
const ready = {
	status: "ready",
	settings,
	links: [],
	submission: { status: "idle" },
	onSave: fn(() => new Promise<never>(() => {})),
} satisfies ReadyState;

function readyState(state: State): ReadyState {
	if (state.status !== "ready") throw new Error("This story is not in the ready state.");
	return state;
}

/**
 * Renders the idle form and moves to `outcome` on save. A static `saving` state would leave Save
 * disabled by the pristine form rather than by the write, so the disabled assertions would pass on a
 * component that never disables anything. `saved` plays the route's part: the payload comes back
 * as the next `settings` while the promise resolves.
 */
function AfterSave({
	outcome,
	...args
}: WorkspaceOnboardingSettingsPageProps & { outcome: SettingsSubmission | { status: "saved" } }) {
	const { state } = args;
	if (state.status !== "ready") return <WorkspaceOnboardingSettingsPage {...args} />;
	return (
		<Stateful<Pick<ReadyState, "settings" | "submission">>
			initial={{ settings: state.settings, submission: { status: "idle" } }}
		>
			{(value, setValue) => (
				<WorkspaceOnboardingSettingsPage
					{...args}
					state={{
						...state,
						...value,
						onSave: (payload) => {
							void state.onSave(payload);
							if (outcome.status === "saved") {
								setValue({
									settings: { ...payload, revision: payload.revision + 1 },
									submission: { status: "idle" },
								});
								return Promise.resolve();
							}
							setValue({ ...value, submission: outcome });
							return outcome.status === "error"
								? Promise.reject(new Error(outcome.message))
								: new Promise<never>(() => {});
						},
					}}
				/>
			)}
		</Stateful>
	);
}

/** Another owner's save, reaching the form as new `settings` under a draft in progress. */
function ChangedElsewhere(args: WorkspaceOnboardingSettingsPageProps) {
	const { state } = args;
	if (state.status !== "ready") return <WorkspaceOnboardingSettingsPage {...args} />;
	return (
		<Stateful initial={state.settings}>
			{(current, setCurrent) => (
				<>
					<WorkspaceOnboardingSettingsPage {...args} state={{ ...state, settings: current }} />
					<Button
						type="button"
						variant="outline"
						className="m-6"
						onClick={() =>
							setCurrent({
								...current,
								revision: current.revision + 1,
								welcomeMarkdown: "Updated elsewhere",
							})
						}
					>
						Change settings elsewhere
					</Button>
				</>
			)}
		</Stateful>
	);
}

/**
 * The owner's page: a welcome switch, the team's Markdown and which account links a member must
 * connect. A requirement on an unavailable link stays clearable, so an owner is never locked into a
 * broken integration; only requiring a broken one is out of reach.
 *
 * "Configure AI models" sits beside the welcome although it edits nothing here: the answers a member
 * can take on the welcome are the processing locations this workspace's models are classified
 * under, so a workspace whose models are all unclassified offers only No AI. Classifying them is
 * the one action that changes that, and it happens on the models page.
 */
const meta = {
	title: "Workspace admin/Member onboarding",
	component: WorkspaceOnboardingSettingsPage,
	tags: ["autodocs"],
	parameters: { layout: "fullscreen" },
	args: { workspaceSlug: "engineering", state: ready },
	// `state` is a union carrying callbacks; the JSON control docgen infers for it edits nothing.
	argTypes: { state: { control: false } },
} satisfies Meta<typeof WorkspaceOnboardingSettingsPage>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Empty: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No integrations to require")).toBeVisible();
		// "Members connect these…" would point at cards that are not on screen.
		await expect(canvas.queryByText(/Members connect these/)).toBeNull();
		await expect(canvas.getByRole("link", { name: "Integrations" })).toHaveAttribute(
			"href",
			"/w/engineering/admin/integrations",
		);
	},
};
export const Loading: Story = { args: { state: { status: "loading" } } };
export const LoadFailed: Story = {
	args: { state: { status: "error", error: new Error("Unavailable"), onRetry: fn() } },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		const { state } = args;
		if (state.status !== "error") throw new Error("This story is not in the error state.");
		await expect(state.onRetry).toHaveBeenCalledTimes(1);
	},
};
export const Configured: Story = {
	args: { state: { ...ready, settings: configured, links: [slack, outline] } },
};
export const EditAndDiscard: Story = {
	play: async ({ canvas, userEvent }) => {
		const save = canvas.getByRole("button", { name: "Save onboarding settings" });
		const welcome = canvas.getByRole("switch", { name: "Show a welcome on first visit" });
		await expect(welcome).toHaveAccessibleDescription(/see the welcome once/);
		await expectGenuinelyDisabled(save);
		await userEvent.click(welcome);
		await expect(save).toBeEnabled();
		await userEvent.click(canvas.getByRole("button", { name: "Discard changes" }));
		await expect(welcome).not.toBeChecked();
		await expectGenuinelyDisabled(save);
	},
};
export const SaveRequiredLink: Story = {
	...Configured,
	render: (args) => <AfterSave {...args} outcome={{ status: "saved" }} />,
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("checkbox", { name: "Outline" }));
		await userEvent.click(canvas.getByRole("button", { name: "Save onboarding settings" }));
		await expect(readyState(args.state).onSave).toHaveBeenCalledWith({
			enabled: true,
			revision: 1,
			welcomeMarkdown: "Welcome to our team!",
			requiredConnectionIds: [1, 2],
		});
		// The saved payload came back as the settings, so the form is pristine again and still shows it.
		await expect(canvas.getByRole("checkbox", { name: "Outline" })).toBeChecked();
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Save onboarding settings" }));
	},
};
export const Saving: Story = {
	render: (args) => <AfterSave {...args} outcome={{ status: "saving" }} />,
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("switch", { name: "Show a welcome on first visit" }));
		await userEvent.click(canvas.getByRole("button", { name: "Save onboarding settings" }));
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Saving…" }));
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Discard changes" }));
		await expectUnavailable(canvas.getByRole("switch", { name: "Show a welcome on first visit" }));
	},
};
export const SaveFailed: Story = {
	render: (args) => (
		<AfterSave
			{...args}
			outcome={{ status: "error", message: "Onboarding settings changed. Reload before saving." }}
		/>
	),
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("switch", { name: "Show a welcome on first visit" }));
		await userEvent.click(canvas.getByRole("button", { name: "Save onboarding settings" }));
		const alert = canvas.getByRole("alert");
		await expect(alert).toHaveTextContent("Couldn't save onboarding settings");
		await expect(alert).toHaveTextContent("Onboarding settings changed. Reload before saving.");
		// The draft survives the failure, so the reader can retry without redoing it.
		await expect(
			canvas.getByRole("switch", { name: "Show a welcome on first visit" }),
		).toBeChecked();
		await expect(canvas.getByRole("button", { name: "Save onboarding settings" })).toBeEnabled();
		// The failure was about that draft; discarding it takes the alert with it.
		await userEvent.click(canvas.getByRole("button", { name: "Discard changes" }));
		await expect(canvas.queryByRole("alert")).toBeNull();
	},
};
export const Conflicted: Story = {
	...Configured,
	render: (args) => <ChangedElsewhere {...args} />,
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("switch", { name: "Show a welcome on first visit" }));
		await userEvent.click(canvas.getByRole("button", { name: "Change settings elsewhere" }));
		await expect(canvas.getByRole("alert")).toHaveTextContent(
			"Someone changed these settings while you were editing",
		);
		const save = canvas.getByRole("button", { name: "Save onboarding settings" });
		await expectGenuinelyDisabled(save);
		// Out of the tab order, so the reason has to reach a reader through the description.
		await expect(save).toHaveAccessibleDescription(/Someone changed these settings/);
		await userEvent.click(canvas.getByRole("button", { name: "Load current settings" }));
		await expect(canvas.getByRole("textbox", { name: "Welcome from your team" })).toHaveValue(
			"Updated elsewhere",
		);
		await expect(canvas.queryByRole("alert")).toBeNull();
		const welcome = canvas.getByRole("switch", { name: "Show a welcome on first visit" });
		await expect(welcome).toBeChecked();
		// The button just pressed left with its alert; focus went to the form, not the body.
		await expect(document.activeElement).toBe(welcome);
	},
};
export const UnavailableRequirement: Story = {
	args: {
		state: {
			...ready,
			settings: { ...settings, requiredConnectionIds: [1] },
			links: [
				{ ...slack, teamName: undefined, available: false },
				{ ...outline, available: false },
			],
		},
	},
	play: async ({ canvas }) => {
		const required = canvas.getByRole("checkbox", { name: "Slack" });
		await expect(required).toBeChecked();
		await expect(required).toBeEnabled();
		await expect(required).toHaveAccessibleDescription(
			"Unavailable — repair it under Integrations or clear this requirement.",
		);
		const unrequired = canvas.getByRole("checkbox", { name: "Outline" });
		await expectUnavailable(unrequired);
		await expect(unrequired).toHaveAccessibleDescription(
			"Unavailable — repair it under Integrations before requiring it.",
		);
	},
};
export const NarrowViewport: Story = {
	args: {
		state: {
			...ready,
			settings: configured,
			links: [
				{ ...slack, teamName: "Platform engineering and developer experience guild, EMEA region" },
				outline,
			],
		},
	},
	globals: { viewport: { value: "reflow", isRotated: false } },
	parameters: { chromatic: { viewports: [320] } },
	play: async () => {
		// A viewport global that stops applying leaves the overflow check passing at full width.
		await expect(window.innerWidth).toBe(320);
		await expectNoPageOverflow();
	},
};
export const Dark: Story = { ...Configured, globals: { theme: "dark" } };
