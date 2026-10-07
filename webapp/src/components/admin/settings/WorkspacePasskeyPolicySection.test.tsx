import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { WorkspacePasskeyPolicySection } from "./WorkspacePasskeyPolicySection";

describe("WorkspacePasskeyPolicySection", () => {
	it("lets an owner change an optional workspace policy", async () => {
		const onChange = vi.fn();
		render(
			<WorkspacePasskeyPolicySection
				owner
				required={false}
				onRetry={vi.fn()}
				onChange={onChange}
			/>,
		);
		await userEvent.setup().click(screen.getByRole("switch", { name: "Require admin passkeys" }));
		expect(onChange).toHaveBeenCalledWith(true);
	});

	it.each([
		{ owner: false, instanceRequired: false },
		{ owner: true, instanceRequired: true },
	])(
		"prevents a policy change for $owner with instance requirement $instanceRequired",
		({ owner, instanceRequired }) => {
			render(
				<WorkspacePasskeyPolicySection
					owner={owner}
					required={false}
					instanceRequired={instanceRequired}
					onRetry={vi.fn()}
					onChange={vi.fn()}
				/>,
			);
			const control = screen.getByRole("switch", {
				name: "Require admin passkeys",
			});
			expect(control.getAttribute("aria-disabled")).toBe("true");
			expect(control.getAttribute("aria-checked")).toBe(String(instanceRequired));
		},
	);

	it("uses distinct label associations when sections share a document", () => {
		render(
			<>
				<WorkspacePasskeyPolicySection
					owner
					required={false}
					onRetry={vi.fn()}
					onChange={vi.fn()}
				/>
				<WorkspacePasskeyPolicySection owner required onRetry={vi.fn()} onChange={vi.fn()} />
			</>,
		);
		const controls = screen.getAllByRole("switch", { name: "Require admin passkeys" });
		expect(new Set(controls.map((control) => control.id)).size).toBe(2);
	});
});
