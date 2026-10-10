import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { WelcomeCard } from "~/components/options/WelcomeCard";
import { expectNoHorizontalOverflow } from "~/stories/reflow";

const meta = {
	component: WelcomeCard,
	tags: ["autodocs"],
	args: {
		hostedHost: "hephaestus.build",
		developmentBuild: false,
		state: { status: "idle" },
		onConnectHosted: fn(),
		onConnectApex: fn(),
		onConnectCustom: fn(),
		privacyUrl: "https://docs.hephaestus.build/user/browser-extension-privacy",
		docsOrigin: "https://docs.hephaestus.build",
	},
} satisfies Meta<typeof WelcomeCard>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The hosted service is one click; the address form stays out of the way until asked for. */
export const Default: Story = {
	play: async ({ canvas, userEvent, args }) => {
		await expect(
			canvas.getByRole("heading", { name: "Practice reviews, in context" }),
		).toBeVisible();
		await expect(canvas.getByText("hephaestus.build")).toBeVisible();
		await expect(canvas.getByLabelText("Hephaestus address")).not.toBeVisible();
		await expect(
			canvas.getByRole("link", { name: /How the extension handles data/u }),
		).toHaveAttribute("href", "https://docs.hephaestus.build/user/browser-extension-privacy");
		await userEvent.click(canvas.getByRole("button", { name: "Connect to Hephaestus" }));
		await expect(args.onConnectHosted).toHaveBeenCalledOnce();
	},
};

export const SelfHosted: Story = {
	play: async ({ canvas, userEvent, args }) => {
		await userEvent.click(canvas.getByText("Use a self-hosted instance"));
		await userEvent.type(canvas.getByLabelText("Hephaestus address"), "https://heph.example.test");
		await userEvent.click(canvas.getByRole("button", { name: "Connect instance" }));
		await expect(args.onConnectCustom).toHaveBeenCalledWith({
			origin: "https://heph.example.test",
			webAppOrigin: undefined,
		});
		await expect(args.onConnectHosted).not.toHaveBeenCalled();
	},
};

export const SelfHostedDevelopmentBuild: Story = {
	args: { developmentBuild: true },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByText("Use a self-hosted instance"));
		await expect(canvas.getByLabelText(/Web app address/u)).toBeVisible();
	},
};

export const Connecting: Story = {
	args: { state: { status: "pending", target: "hosted" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Connecting…" })).toBeDisabled();
	},
};

export const PermissionRefused: Story = {
	args: {
		state: {
			status: "error",
			target: "hosted",
			message:
				"Chrome did not allow the extension to reach hephaestus.build, so nothing changed. Try again to see Chrome’s prompt.",
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent(/Chrome did not allow/u);
		await expect(canvas.getByRole("button", { name: "Connect to Hephaestus" })).toBeEnabled();
		await expect(
			canvas.getByRole("button", { name: "Connect to Hephaestus" }),
		).toHaveAccessibleDescription(/Chrome did not allow/u);
	},
};

/** A failed self-hosted address keeps its form open with the reason beside the field. */
export const SelfHostedFailed: Story = {
	args: {
		state: {
			status: "error",
			target: "custom",
			message:
				"No Hephaestus instance answered at that address. Check the address and that you allowed access in Chrome.",
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByLabelText("Hephaestus address")).toBeVisible();
		await expect(canvas.getByRole("alert")).toHaveTextContent(/No Hephaestus instance answered/u);
	},
};

export const Narrow: Story = {
	parameters: { reflow: true },
	play: async ({ canvasElement }) => {
		await expectNoHorizontalOverflow(canvasElement);
	},
};

export const Dark: Story = {
	globals: { theme: "dark" },
	play: async ({ canvas }) => {
		await expect(document.documentElement).toHaveClass("dark");
		await expect(canvas.getByRole("button", { name: "Connect to Hephaestus" })).toBeVisible();
	},
};

export const WorkspaceAddress: Story = {
	args: { state: { status: "confirm-apex", origin: "https://heph.example.test" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status")).toHaveTextContent(
			"This workspace uses heph.example.test for sign-in and the API.",
		);
		await expect(
			canvas.getByRole("button", { name: "Connect to heph.example.test" }),
		).toBeVisible();
	},
};
