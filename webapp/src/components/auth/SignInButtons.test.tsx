import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { SignInButtons } from "./SignInButtons";

const callbacks = { onSignIn: vi.fn() };

describe("SignInButtons", () => {
	it("offers sign-in providers but not link-only integrations", () => {
		render(
			<SignInButtons
				{...callbacks}
				options={{
					status: "ready",
					providers: [
						{ registrationId: "github", displayName: "GitHub", providerType: "GITHUB" },
						{ registrationId: "slack", displayName: "Slack", providerType: "SLACK" },
						{ registrationId: "outline", displayName: "Outline", providerType: "OUTLINE" },
					],
				}}
			/>,
		);
		expect(
			screen.getByRole<HTMLButtonElement>("button", { name: "Continue with GitHub" }).disabled,
		).toBe(false);
		expect(screen.queryByRole("button", { name: /slack|outline/i })).toBeNull();
	});
	it("never invents a provider when discovery fails", () => {
		render(<SignInButtons {...callbacks} options={{ status: "error", onRetry: vi.fn() }} />);
		expect(screen.getByRole("alert").textContent).toContain("Couldn't load");
		expect(screen.queryByRole("button", { name: /continue with/i })).toBeNull();
	});
	it("explains an empty discovery list", () => {
		render(<SignInButtons {...callbacks} options={{ status: "ready", providers: [] }} />);
		expect(screen.getByText(/No sign-in options are configured/).textContent).toContain("operator");
	});
});
