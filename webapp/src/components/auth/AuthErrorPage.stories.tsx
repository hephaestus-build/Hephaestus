import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { expectNoPageOverflow } from "@/stories/reflow";

import { AuthErrorPage } from "./AuthErrorPage";

/**
 * Where the server sends a sign-in or account connection that it refused. The code picks the words;
 * a code the page does not know, including one named like a built-in property, gets the generic
 * message.
 */
const meta = {
	component: AuthErrorPage,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	args: { code: "oauth_failure", next: { kind: "sign-in" } },
} satisfies Meta<typeof AuthErrorPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		const alert = canvas.getByRole("alert");
		await expect(
			canvas.getByRole("heading", { level: 1, name: "Sign-in did not complete" }),
		).toBeVisible();
		await expect(alert).toHaveTextContent(
			"We could not finish signing you in with that provider. Try again from the sign-in page.",
		);
		await expect(canvas.getByRole("link", { name: "Back to sign in" })).toHaveAttribute(
			"href",
			"/login",
		);
	},
};

export const ProviderUnavailable: Story = {
	args: { code: "idp_unavailable" },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { level: 1, name: "Provider unavailable" }),
		).toBeVisible();
	},
};

/** `toString` is on every object's prototype: the page must not read it as a known code. */
export const UnknownCode: Story = {
	args: { code: "toString" },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { level: 1, name: "We could not sign you in" }),
		).toBeVisible();
		await expect(canvas.getByRole("alert")).toHaveTextContent(
			"Try again. If it keeps failing, contact your instance operator.",
		);
	},
};

export const NoCode: Story = {
	args: { code: undefined },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { level: 1, name: "We could not sign you in" }),
		).toBeVisible();
	},
};

/** Signed out since the refusal: sign in again, and come back to where connecting was headed. */
export const StepUpSignedOut: Story = {
	args: { code: "step_up_required", next: { kind: "sign-in", returnTo: "/settings" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: "Back to sign in" })).toHaveAttribute(
			"href",
			"/login?returnTo=/settings",
		);
	},
};

const onConfirm = fn();

/** Still signed in, only too long ago: confirm access here rather than bounce through sign-in. */
export const StepUpSignedIn: Story = {
	args: { code: "step_up_required", next: { kind: "confirm-access", onConfirm } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", {
				level: 1,
				name: "Confirm access before connecting that account",
			}),
		).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Confirm access" }));
		await expect(onConfirm).toHaveBeenCalledOnce();
		await expect(canvas.queryByRole("link", { name: "Back to sign in" })).not.toBeInTheDocument();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

/** The longest description, at the narrowest supported width. */
export const OnAPhone: Story = {
	args: { code: "client_not_registered" },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};
