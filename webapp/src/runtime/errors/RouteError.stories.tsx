import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { RouteError } from "./RouteError";

/**
 * What a route shows when its `beforeLoad`, loader or component throws. The error's status picks the
 * state, and `PageError` owns how each one looks. An error with no status has an unknown cause, so
 * Retry is offered.
 */
const meta = {
	component: RouteError,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	args: { error: new Error("render failed") },
} satisfies Meta<typeof RouteError>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		const alert = canvas.getByRole("alert");
		await expect(alert).toHaveTextContent("We could not load this page");
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
		await expect(alert).not.toHaveTextContent("render failed");
	},
};
