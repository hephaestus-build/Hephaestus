import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { UnavailableCostAlert } from "./UnavailableCostAlert";

/** Both consoles render it. Only the closing sentence, where to set the rates, differs. */
const meta = {
	component: UnavailableCostAlert,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		uses: 42,
		children:
			"Set rates for your own models in AI models. For shared models, ask an instance admin.",
	},
} satisfies Meta<typeof UnavailableCostAlert>;

export default meta;
type Story = StoryObj<typeof meta>;

export const WorkspaceConsole: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("note")).toHaveTextContent("42 uses have unavailable cost");
		await expect(canvas.getByRole("note")).toHaveTextContent(/ask an instance admin\.$/u);
	},
};

export const InstanceConsole: Story = {
	args: {
		uses: 1234,
		children:
			"Set rates for shared models in AI models. Acme’s admins set rates for its own models.",
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("note")).toHaveTextContent("1,234 uses have unavailable cost");
		await expect(canvas.getByRole("note")).toHaveTextContent(/Acme’s admins set rates/u);
	},
};

export const SingleUse: Story = {
	args: { uses: 1 },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("1 use has unavailable cost")).toBeVisible();
	},
};
