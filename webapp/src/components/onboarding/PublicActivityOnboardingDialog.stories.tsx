import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import { withProvider, withStandardPage } from "@/stories/decorators";
import { expectSettledVisible } from "@/stories/overlay";

import { PublicActivityOnboardingDialog } from "./PublicActivityOnboardingDialog";

const meta = {
	component: PublicActivityOnboardingDialog,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		open: true,
		workspaceName: "Hephaestus",
		providerType: "GITHUB",
		answer: { status: "idle" },
		onAnswer: fn(),
	},
} satisfies Meta<typeof PublicActivityOnboardingDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The step, once it has arrived: a dialog fades in, and what is inside it reads as transparent until then. */
async function dialog() {
	const step = await screen.findByRole("alertdialog");
	await expectSettledVisible(step);
	return within(step);
}

/** Both answers are the same size and neither is focused, so neither is the default. */
export const Default: Story = {
	play: async ({ args, userEvent }) => {
		const step = await dialog();
		await expect(
			step.getByRole("heading", { name: "Hephaestus has a public activity page" }),
		).toBeVisible();
		const show = step.getByRole("button", { name: "Show me" });
		const hide = step.getByRole("button", { name: "Hide me" });
		await expect(show).not.toHaveFocus();
		await expect(hide).not.toHaveFocus();
		await expect(show.getBoundingClientRect().width).toBeCloseTo(
			hide.getBoundingClientRect().width,
			0,
		);
		await expect(show.className).toBe(hide.className);
		await userEvent.click(hide);
		await expect(args.onAnswer).toHaveBeenCalledWith(false);
	},
};

export const ShowMe: Story = {
	play: async ({ args, userEvent }) => {
		const step = await dialog();
		await userEvent.click(step.getByRole("button", { name: "Show me" }));
		await expect(args.onAnswer).toHaveBeenCalledWith(true);
	},
};

/** Only an answer closes the step: Escape leaves it open, and the person is asked again next time. */
export const CannotBeDismissed: Story = {
	play: async ({ userEvent }) => {
		await dialog();
		await userEvent.keyboard("{Escape}");
		await expect(screen.getByRole("alertdialog")).toBeVisible();
	},
};

export const Saving: Story = {
	args: { answer: { status: "saving", visible: false } },
	play: async () => {
		const step = await dialog();
		await expect(step.getByRole("button", { name: "Show me" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
		await expect(step.getByRole("button", { name: "Hide me" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
	},
};

export const Failed: Story = {
	args: { answer: { status: "error", message: "We could not save your choice. Try again." } },
	play: async () => {
		const step = await dialog();
		await expect(step.getByRole("alert")).toHaveTextContent(
			"We could not save your choice. Try again.",
		);
	},
};

/** GitLab's words: merge requests, projects. */
export const GitLab: Story = {
	decorators: [withProvider("GITLAB")],
	args: { providerType: "GITLAB" },
	play: async () => {
		const step = await dialog();
		await expect(step.getByText(/For public projects only/u)).toBeVisible();
		await expect(step.getByText(/the merge requests you opened and reviewed/u)).toBeVisible();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

export const Closed: Story = {
	args: { open: false },
	play: async () => {
		await expect(screen.queryByRole("alertdialog")).toBeNull();
	},
};
