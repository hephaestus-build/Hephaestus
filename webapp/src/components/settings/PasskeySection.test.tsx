import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { PasskeySection } from "./PasskeySection";

const props = {
	supported: true,
	onRetry: vi.fn(),
	onRegister: vi.fn(),
	onVerify: vi.fn(),
	onProtection: vi.fn(),
	onRemove: vi.fn(),
	onCreateRecoveryCodes: vi.fn(),
	onRecover: vi.fn(),
};
const status = {
	protectionEnabled: false,
	recoveryRequired: false,
	instanceAdminRequired: true,
	workspaceAdminRequired: false,
	verified: false,
	credentials: [{ id: "one", label: "Personal device" }],
};
describe("PasskeySection", () => {
	it("keeps the last credential and requires verification for protection changes", () => {
		render(<PasskeySection {...props} status={status} />);
		expect(
			screen.getByRole<HTMLButtonElement>("button", { name: "Remove Personal device" }).disabled,
		).toBe(true);
		expect(
			screen
				.getByRole("switch", { name: "Personal admin protection" })
				.getAttribute("aria-disabled"),
		).toBe("true");
		expect(
			screen.getByRole<HTMLButtonElement>("button", { name: "Replace recovery codes" }).disabled,
		).toBe(true);
		expect(screen.getByRole<HTMLButtonElement>("button", { name: "Verify passkey" }).disabled).toBe(
			false,
		);
	});
	it("states that recovery does not restore admin access", () => {
		render(
			<PasskeySection {...props} status={{ ...status, recoveryRequired: true, credentials: [] }} />,
		);
		expect(screen.getByText(/Recovery is active/u).getAttribute("role")).toBe("alert");
	});
	it("shows a retry control when settings cannot load", () => {
		render(<PasskeySection {...props} error="Could not load passkeys." />);
		expect(screen.getByRole("alert").textContent).toContain("Could not load passkeys.");
		expect(screen.getByRole<HTMLButtonElement>("button", { name: "Retry" }).disabled).toBe(false);
	});
	it("does not permit enrollment in an unsupported browser", async () => {
		render(<PasskeySection {...props} supported={false} status={{ ...status, credentials: [] }} />);
		await userEvent.setup().type(screen.getByLabelText("Passkey name"), "Device");
		expect(
			screen.getByRole<HTMLButtonElement>("button", { name: "Register passkey" }).disabled,
		).toBe(true);
		expect(screen.getByText(/This browser cannot use passkeys/u).getAttribute("role")).toBe(
			"alert",
		);
	});
});
