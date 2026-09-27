import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { InstanceSetupForm } from "~/components/options/InstanceSetupForm";

const meta = {
	component: InstanceSetupForm,
	tags: ["autodocs"],
	args: { developmentBuild: false, state: { status: "idle" }, onConnect: fn() },
} satisfies Meta<typeof InstanceSetupForm>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas, userEvent, args }) => {
		await expect(canvas.queryByLabelText(/Web app address/u)).toBeNull();
		await userEvent.type(canvas.getByLabelText("Hephaestus address"), "https://heph.example.test");
		await userEvent.click(canvas.getByRole("button", { name: "Connect" }));
		await expect(args.onConnect).toHaveBeenCalledWith({
			origin: "https://heph.example.test",
			webAppOrigin: undefined,
		});
	},
};

export const DevelopmentBuild: Story = {
	args: { developmentBuild: true },
	play: async ({ canvas, userEvent, args }) => {
		await userEvent.type(canvas.getByLabelText("Hephaestus address"), "http://localhost:18480");
		await userEvent.type(canvas.getByLabelText(/Web app address/u), "http://localhost:14280");
		await userEvent.click(canvas.getByRole("button", { name: "Connect" }));
		await expect(args.onConnect).toHaveBeenCalledWith({
			origin: "http://localhost:18480",
			webAppOrigin: "http://localhost:14280",
		});
	},
};

export const DisabledWhileAnotherConnects: Story = {
	args: { disabled: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Connect" })).toBeDisabled();
	},
};

export const Pending: Story = {
	args: { state: { status: "pending" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Connecting…" })).toBeDisabled();
	},
};

export const Invalid: Story = {
	args: { state: { status: "error", message: "The address must start with https://." } },
	play: async ({ canvas }) => {
		const field = canvas.getByLabelText("Hephaestus address");
		await expect(field).toHaveAttribute("aria-invalid", "true");
		await expect(field).toHaveAccessibleDescription(/The address must start with https:\/\/\./u);
		await expect(canvas.getByRole("alert")).toHaveTextContent(
			"The address must start with https://.",
		);
	},
};
