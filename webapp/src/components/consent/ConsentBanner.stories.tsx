import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { ConsentBanner } from "./ConsentBanner";

/**
 * Presentational cookie-consent banner. These stories drive the pure component directly (no store),
 * so both shapes are reachable: the first-visit prompt and the re-opened edit mode with its Cancel
 * action (GDPR Art. 7(3) — withdrawing consent must be as easy as granting it).
 */
const meta = {
	component: ConsentBanner,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	args: {
		editing: false,
		onAllow: fn(),
		onDecline: fn(),
		onCancel: fn(),
		privacyPolicy: (
			<a href="/privacy" className="underline underline-offset-4 hover:text-foreground">
				Read our Privacy Policy
			</a>
		),
	},
} satisfies Meta<typeof ConsentBanner>;

export default meta;
type Story = StoryObj<typeof meta>;

/** First visit: a named region with an equal-prominence Decline / Allow pair and no Cancel. */
export const Default: Story = {
	play: async ({ canvas }) => {
		canvas.getByRole("region", { name: /your privacy/iu });
		canvas.getByRole("button", { name: "Decline" });
		canvas.getByRole("button", { name: "Allow" });
		await expect(canvas.queryByRole("button", { name: "Cancel" })).not.toBeInTheDocument();
	},
};

/** Re-opened to change an existing decision: a Cancel action appears alongside the pair. */
export const Editing: Story = {
	args: { editing: true },
	play: async ({ canvas }) => {
		canvas.getByRole("button", { name: "Cancel" });
		canvas.getByRole("button", { name: "Decline" });
		canvas.getByRole("button", { name: "Allow" });
	},
};
