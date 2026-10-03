import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { noSessions, sessionsError, sessionsPending } from "@/mocks/handlers";

import { SessionsSection } from "./SessionsSection";

const meta = {
	component: SessionsSection,
	parameters: {
		layout: "centered",
		// One MSW worker answers a whole Docs page, so each story gets its own frame.
		docs: { story: { inline: false, height: "600px" } },
	},
	tags: ["autodocs"],
} satisfies Meta<typeof SessionsSection>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The current device, two other browsers and a browser extension. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(
			await canvas.findByRole("listitem", { name: "Browser extension in Chrome on macOS" }),
		).toBeVisible();
	},
};

export const Loading: Story = {
	parameters: { msw: { handlers: [sessionsPending] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("list", { name: "Loading sessions" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
		await expect(canvas.queryByText("No active sessions found.")).toBeNull();
		await expect(canvas.queryByRole("button")).toBeNull();
	},
};

export const Empty: Story = {
	parameters: { msw: { handlers: [noSessions] } },
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("No active sessions found.")).toBeVisible();
	},
};

export const ErrorState: Story = {
	parameters: { msw: { handlers: [sessionsError] } },
	play: async ({ canvas }) => {
		await expect(await canvas.findByText(/Failed to load sessions/iu)).toBeVisible();
	},
};
