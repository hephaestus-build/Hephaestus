import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import { Stateful } from "@/stories/stateful";
import { expectGenuinelyDisabled } from "@/test/controls";
import { expectNoPageOverflow } from "@/test/reflow";

import { ConsentPage, type ConsentPageProps, type ConsentSubmission } from "./ConsentPage";

const notice = {
	completed: false,
	noticeVersion: "2026-09-10",
	participateInResearch: false,
};
const onSubmit = fn();
const onRetry = fn();
const ready = {
	status: "ready",
	notice,
	submission: { status: "idle" },
	onSubmit,
} satisfies ConsentPageProps["state"];

const meta = {
	component: ConsentPage,
	args: { state: ready, onSignOut: fn() },
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
		await expect(screen.getByText(/One question to go/)).toBeVisible();
	},
};

export const TakingPart: Story = {
	play: async () => {
		await acceptTerms();
		await answer(/Yes, take part/);
		await expect(screen.getByText("You can change your answer later in settings.")).toBeVisible();
		await expect(screen.getByText("That's everything. Let's get to work.")).toBeVisible();
		await userEvent.click(screen.getByRole("button", { name: "Continue" }));
		await expect(onSubmit).toHaveBeenCalledWith({
			noticeVersion: "2026-09-10",
			termsAccepted: true,
			participateInResearch: true,
		});
	},
};

export const DecliningResearch: Story = {
	play: async () => {
		await acceptTerms();
		await answer(/don't take part/);
		await userEvent.click(screen.getByRole("button", { name: "Continue" }));
		await expect(onSubmit).toHaveBeenCalledWith({
			noticeVersion: "2026-09-10",
			termsAccepted: true,
			participateInResearch: false,
		});
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
	args: { state: { status: "error", error: new Error("offline"), onRetry } },
	play: async () => {
		await userEvent.click(await screen.findByRole("button", { name: "Retry" }));
		await expect(onRetry).toHaveBeenCalled();
		await expect(screen.queryByRole("checkbox")).toBeNull();
	},
};

export const CanSignOut: Story = {
	play: async ({ args }) => {
		await userEvent.click(await screen.findByRole("button", { name: "Sign out" }));
		await expect(args.onSignOut).toHaveBeenCalled();
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
