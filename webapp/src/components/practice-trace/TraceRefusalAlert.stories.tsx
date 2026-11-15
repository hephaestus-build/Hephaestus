import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import { RefusalFixLink } from "./RefusalFixLink";
import { TraceRefusalAlert } from "./TraceRefusalAlert";

// A refused domain request can be a successful HTTP read; retain its reason on the page.
const meta = {
	component: TraceRefusalAlert,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		refusal: {
			status: "REFUSED",
			reason: "REVIEW_MODEL_UNBOUND",
		},
		fix: <RefusalFixLink workspaceSlug="demo" reason="REVIEW_MODEL_UNBOUND" />,
	},
	argTypes: { fix: { control: false } },
} satisfies Meta<typeof TraceRefusalAlert>;

export default meta;
type Story = StoryObj<typeof meta>;

export const OffersTheFixToAnAdmin: Story = {
	play: async ({ canvas }) => {
		const alert = within(canvas.getByRole("alert"));
		await expect(alert.getByText("No AI model is assigned to practice review.")).toBeVisible();
		await expect(alert.getByRole("link", { name: "Set up a review model" })).toHaveAttribute(
			"href",
			"/w/demo/admin/models",
		);
	},
};

// A start switch turned off is a setting, so the alert says so and an admin gets the settings page.
export const WithheldByTheWorkspaceSettings: Story = {
	args: {
		refusal: { status: "REFUSED", reason: "GATE_WITHHELD" },
		fix: <RefusalFixLink workspaceSlug="demo" reason="GATE_WITHHELD" />,
	},
	play: async ({ canvas }) => {
		const alert = within(canvas.getByRole("alert"));
		await expect(
			alert.getByText("The workspace’s settings did not allow a review to start."),
		).toBeVisible();
		await expect(
			alert.getByRole("link", { name: "Open Review: Automated review" }),
		).toHaveAttribute("href", "/w/demo/admin/practices/review?section=automated-review");
	},
};

// Members cannot use admin fix links; an alert must not send them to an inaccessible page.
export const WithoutAFix: Story = {
	args: { fix: undefined },
	play: async ({ canvas }) => {
		const alert = within(canvas.getByRole("alert"));
		await expect(alert.getByText("No AI model is assigned to practice review.")).toBeVisible();
		await expect(alert.queryByRole("link")).not.toBeInTheDocument();
	},
};

export const WithoutAReason: Story = {
	args: {
		refusal: { status: "REFUSED" },
		fix: undefined,
	},
	play: async ({ canvas }) => {
		const alert = within(canvas.getByRole("alert"));
		await expect(alert.getByText("No reason was given.")).toBeVisible();
		await expect(alert.queryByRole("link")).not.toBeInTheDocument();
	},
};
