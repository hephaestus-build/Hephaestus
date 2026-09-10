import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import { Stateful } from "@/stories/stateful";
import { expectGenuinelyDisabled } from "@/test/controls";
import { expectNoPageOverflow } from "@/test/reflow";

import {
	ConsentPage,
	type ConsentPageProps,
	type ConsentSubmission,
	WORDING_VERSION,
} from "./ConsentPage";

const notice = {
	completed: false,
	noticeVersion: `${WORDING_VERSION}+abcd1234`,
	wordingVersion: WORDING_VERSION,
	participateInResearch: false,
	researchOrganization: "the Technical University of Munich (AET)",
};
const ready = {
	status: "ready",
	notice,
	submission: { status: "idle" },
	onSubmit: fn(),
} satisfies ConsentPageProps["state"];

const meta = {
	component: ConsentPage,
	args: { state: ready, onSignOut: fn(), onReload: fn() },
	parameters: { layout: "fullscreen" },
} satisfies Meta<typeof ConsentPage>;
export default meta;
type Story = StoryObj<typeof meta>;

async function acceptTerms() {
	await userEvent.click(await screen.findByRole("checkbox", { name: /terms of use/i }));
}

async function answer(name: RegExp) {
	await userEvent.click(screen.getByRole("radio", { name }));
}

/**
 * Renders the idle page and moves to `outcome` on submit. A static `saving` state would leave
 * Continue disabled by the unanswered question rather than by the write, so the disabled assertions
 * would pass on a component that never disables anything.
 */
function AfterSubmit({ outcome, ...args }: ConsentPageProps & { outcome: ConsentSubmission }) {
	const { state } = args;
	if (state.status !== "ready") return <ConsentPage {...args} />;
	return (
		<Stateful<ConsentSubmission> initial={{ status: "idle" }}>
			{(submission, setSubmission) => (
				<ConsentPage
					{...args}
					state={{
						...state,
						submission,
						onSubmit: (choice) => {
							state.onSubmit(choice);
							setSubmission(outcome);
						},
					}}
				/>
			)}
		</Stateful>
	);
}

export const Default: Story = {
	play: async () => {
		await expectGenuinelyDisabled(await screen.findByRole("button", { name: "Continue" }));
		await expect(screen.getByRole("radio", { name: /Yes, take part/ })).not.toBeChecked();
		await expect(screen.getByRole("radio", { name: /don't take part/ })).not.toBeChecked();
		await expect(screen.getByText(/Two things first/)).toBeVisible();
	},
};

export const TermsAcceptedOnly: Story = {
	play: async () => {
		await acceptTerms();
		await expectGenuinelyDisabled(screen.getByRole("button", { name: "Continue" }));
		await expect(screen.getByText("Answer the research question to continue.")).toBeVisible();
	},
};

export const BothAnswered: Story = {
	play: async () => {
		await acceptTerms();
		await answer(/Yes, take part/);
		await expect(screen.getByRole("button", { name: "Continue" })).toBeEnabled();
		await expect(screen.getByText("You can change your answer later in settings.")).toBeVisible();
		await expect(screen.getByText("That's everything. Let's get to work.")).toBeVisible();
	},
};

/**
 * A deployment can land while this page is open. The wording comes from this bundle, so a version the
 * server has moved past must not be answered — accepting it would record terms nobody was shown.
 */
export const NoticeChangedUnderneath: Story = {
	args: { state: { ...ready, notice: { ...notice, wordingVersion: "2027-01-01" } } },
	play: async () => {
		await expect(await screen.findByRole("button", { name: "Reload" })).toBeVisible();
		await expect(screen.queryByRole("checkbox")).toBeNull();
		await expect(screen.queryByRole("button", { name: "Continue" })).toBeNull();
	},
};

/** No study on this deployment, so the question is not asked and the terms alone complete setup. */
export const NoResearchProgramme: Story = {
	args: {
		state: { ...ready, notice: { ...notice, researchOrganization: undefined } },
	},
	play: async () => {
		await expectGenuinelyDisabled(await screen.findByRole("button", { name: "Continue" }));
		await expect(screen.queryByRole("radiogroup")).toBeNull();
		await acceptTerms();
		await expect(screen.getByRole("button", { name: "Continue" })).toBeEnabled();
	},
};

export const Submitting: Story = {
	render: (args) => <AfterSubmit {...args} outcome={{ status: "saving" }} />,
	play: async () => {
		await acceptTerms();
		await answer(/Yes, take part/);
		await userEvent.click(screen.getByRole("button", { name: "Continue" }));
		await expectGenuinelyDisabled(screen.getByRole("button", { name: "Saving…" }));
		await expectGenuinelyDisabled(screen.getByRole("button", { name: "Sign out" }));
	},
};

export const SubmitFailed: Story = {
	render: (args) => <AfterSubmit {...args} outcome={{ status: "error" }} />,
	play: async () => {
		await acceptTerms();
		await answer(/don't take part/);
		await userEvent.click(screen.getByRole("button", { name: "Continue" }));
		await expect(screen.getByRole("alert")).toHaveTextContent(/weren't saved/i);
		await expect(screen.getByRole("button", { name: "Continue" })).toBeEnabled();
	},
};

export const Loading: Story = { args: { state: { status: "loading" } } };

export const FailedToLoad: Story = {
	args: { state: { status: "error", error: new Error("offline"), onRetry: fn() } },
	play: async () => {
		await expect(await screen.findByRole("button", { name: "Retry" })).toBeVisible();
		await expect(screen.queryByRole("checkbox")).toBeNull();
	},
};

export const Narrow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await acceptTerms();
		await answer(/Yes, take part/);
		await expectNoPageOverflow();
	},
};

export const Dark: Story = {
	globals: { theme: "dark" },
	play: async () => {
		await acceptTerms();
		await answer(/Yes, take part/);
		await expect(screen.getByRole("radio", { name: /Yes, take part/ })).toBeChecked();
	},
};
