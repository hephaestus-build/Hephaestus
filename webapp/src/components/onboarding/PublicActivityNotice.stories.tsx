import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, waitFor } from "storybook/test";

import { withProvider, withStandardPage } from "@/stories/decorators";
import { Stateful } from "@/stories/stateful";

import { PublicActivityNotice } from "./PublicActivityNotice";

/**
 * A region in the page rather than a dialog, so it never takes focus when the page loads. A modal
 * on load interrupts the task the member came for, and they learn to dismiss it without reading.
 */
const meta = {
	component: PublicActivityNotice,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		open: true,
		workspaceName: "Hephaestus",
		providerType: "GITHUB",
		currentlyVisible: false,
		answer: { status: "idle" },
		onAnswer: fn(),
		onDefer: fn(),
	},
} satisfies Meta<typeof PublicActivityNotice>;

export default meta;
type Story = StoryObj<typeof meta>;

const NOTICE = { name: /has a public activity page$/u };

/** A member who has not answered: public pages hide them until they do. */
export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const notice = canvas.getByRole("region", { name: "Hephaestus has a public activity page" });
		await expect(canvas.queryByRole("alertdialog")).toBeNull();
		await expect(notice).toHaveTextContent("Right now, public pages hide you.");
		const show = canvas.getByRole("button", { name: "Show me" });
		const hide = canvas.getByRole("button", { name: "Hide me" });
		// Both answers look the same and neither has focus, so neither is the default.
		await expect(show).not.toHaveFocus();
		await expect(hide).not.toHaveFocus();
		await expect(show.className).toBe(hide.className);
		await userEvent.click(hide);
		await expect(args.onAnswer).toHaveBeenCalledWith(false);
	},
};

export const ShowMe: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Show me" }));
		await expect(args.onAnswer).toHaveBeenCalledWith(true);
	},
};

/** Decide later leaves the notice for this visit, without an answer. */
export const Deferred: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Decide later" }));
		await expect(args.onDefer).toHaveBeenCalledOnce();
		await expect(args.onAnswer).not.toHaveBeenCalled();
	},
};

export const SavingShow: Story = {
	args: { answer: { status: "saving", visible: true } },
	play: async ({ canvas }) => {
		const show = canvas.getByRole("button", { name: "Show me" });
		const hide = canvas.getByRole("button", { name: "Hide me" });
		await expect(show).toHaveAttribute("aria-disabled", "true");
		await expect(hide).toHaveAttribute("aria-disabled", "true");
		await expect(show.querySelector("svg.animate-spin")).not.toBeNull();
		await expect(hide.querySelector("svg.animate-spin")).toBeNull();
		// A save that never answers cannot hold the member in the notice.
		await expect(canvas.getByRole("button", { name: "Decide later" })).toBeEnabled();
	},
};

export const SavingHide: Story = {
	args: { answer: { status: "saving", visible: false } },
	play: async ({ canvas }) => {
		const show = canvas.getByRole("button", { name: "Show me" });
		const hide = canvas.getByRole("button", { name: "Hide me" });
		await expect(show).toHaveAttribute("aria-disabled", "true");
		await expect(hide).toHaveAttribute("aria-disabled", "true");
		await expect(hide.querySelector("svg.animate-spin")).not.toBeNull();
		await expect(show.querySelector("svg.animate-spin")).toBeNull();
	},
};

/** A failing save says so in the notice, and the member can answer again or decide later. */
export const Failed: Story = {
	args: { answer: { status: "error", message: "We could not save your choice. Try again." } },
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent(
			"We could not save your choice. Try again.",
		);
		await userEvent.click(canvas.getByRole("button", { name: "Hide me" }));
		await expect(args.onAnswer).toHaveBeenCalledWith(false);
	},
};

/** A member who chose Show in User settings or in another workspace is told what they are now. */
export const AlreadyShown: Story = {
	args: { currentlyVisible: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("region", NOTICE)).toHaveTextContent(
			"Right now, public pages show you.",
		);
	},
};

/** GitLab’s words: merge requests, projects. */
export const GitLab: Story = {
	decorators: [withProvider("GITLAB")],
	args: { providerType: "GITLAB" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/For public projects only/u)).toBeVisible();
		await expect(canvas.getByText(/the merge requests you opened and reviewed/u)).toBeVisible();
	},
};

/** At 320 px a long workspace name wraps inside the notice, and nothing scrolls sideways. */
export const LongWorkspaceName: Story = {
	args: { workspaceName: "Technical University of Munich Applied Education Technologies Lab" },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		const notice = canvas.getByRole("region", {
			name: "Technical University of Munich Applied Education Technologies Lab has a public activity page",
		});
		await expect(notice.scrollWidth).toBeLessThanOrEqual(notice.clientWidth);
		await expect(canvas.getByRole("button", { name: "Hide me" })).toBeVisible();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

export const Closed: Story = {
	args: { open: false },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("region", NOTICE)).toBeNull();
	},
};

/**
 * An answer closes the notice. Focus was on the pressed button, so it moves to the page heading
 * instead of being lost with the button.
 */
export const Answered: Story = {
	render: (args) => (
		<Stateful initial={args.open}>
			{(open, setOpen) => (
				<main>
					<PublicActivityNotice
						{...args}
						open={open}
						onAnswer={(visible) => {
							args.onAnswer(visible);
							setOpen(false);
						}}
					/>
					<h1 className="text-xl font-semibold">Practice profile</h1>
				</main>
			)}
		</Stateful>
	),
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Show me" }));
		await expect(args.onAnswer).toHaveBeenCalledWith(true);
		await waitFor(async () => expect(canvas.queryByRole("region", NOTICE)).toBeNull());
		await expect(canvas.getByRole("heading", { level: 1, name: "Practice profile" })).toHaveFocus();
	},
};
