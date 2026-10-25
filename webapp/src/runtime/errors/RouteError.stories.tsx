import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { RouteError } from "./RouteError";

/** What the generated client throws for a response with that status. */
function refused(status: number): Error {
	return Object.assign(new Error("refused"), { status });
}

/**
 * What a route shows when its `beforeLoad`, loader or component throws. The error's status picks the
 * state; `PageError` owns how each one looks. The route test owns that Retry runs the route again.
 */
const meta = {
	component: RouteError,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	args: { error: new Error("render failed") },
} satisfies Meta<typeof RouteError>;

export default meta;
type Story = StoryObj<typeof meta>;

/** An error with no status, such as a component that threw: running the route again can help. */
export const Default: Story = {
	play: async ({ canvas }) => {
		const alert = canvas.getByRole("alert");
		await expect(alert).toHaveTextContent("We could not load this page");
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
		await expect(alert).not.toHaveTextContent("render failed");
	},
};

export const NotFound: Story = {
	args: { error: refused(404) },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent("We could not find this page");
		await expect(canvas.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
	},
};

export const NoAccess: Story = {
	args: { error: refused(403) },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent(
			"You do not have access to this page",
		);
		await expect(canvas.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
	},
};

/** A request the server refused would be refused again, so there is no Retry. */
export const Refused: Story = {
	args: { error: refused(400) },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent("We could not load this page");
		await expect(canvas.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
	},
};
