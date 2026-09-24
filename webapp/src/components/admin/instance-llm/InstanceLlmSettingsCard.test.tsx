import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { InstanceLlmSettings } from "@/api/types.gen";

import {
	InstanceLlmSettingsCard,
	type InstanceLlmSettingsCardProps,
} from "./InstanceLlmSettingsCard";

const saved: InstanceLlmSettings = {
	allowedEgressHosts: "api.openai.com",
	allowWorkspaceConnections: true,
};

const hostsField = () => screen.getByLabelText<HTMLTextAreaElement>("Allowed provider hosts");
const ownProviderSwitch = () =>
	screen.getByRole("switch", { name: /Let workspaces add providers and models/u });
const saveButton = () => screen.getByRole<HTMLButtonElement>("button", { name: "Save settings" });

function renderCard(settings: InstanceLlmSettings = saved) {
	const onSave = vi.fn<InstanceLlmSettingsCardProps["onSave"]>();
	const view = render(
		<InstanceLlmSettingsCard
			settings={settings}
			isLoading={false}
			isSubmitting={false}
			onSave={onSave}
		/>,
	);
	return { onSave, ...view };
}

describe("InstanceLlmSettingsCard", () => {
	it("offers nothing to save until something differs from the settings on record", () => {
		renderCard();

		expect(saveButton().disabled).toBe(true);

		fireEvent.change(hostsField(), { target: { value: "llm.example.com" } });
		expect(saveButton().disabled).toBe(false);

		fireEvent.change(hostsField(), { target: { value: "api.openai.com" } });
		expect(saveButton().disabled).toBe(true);
	});

	it("sends an explicit empty host list so an admin can clear the allowlist", () => {
		const { onSave } = renderCard();

		fireEvent.change(hostsField(), { target: { value: "" } });

		expect(hostsField().value).toBe("");
		expect(saveButton().disabled).toBe(false);

		fireEvent.click(saveButton());

		expect(onSave).toHaveBeenCalledWith(expect.objectContaining({ allowedEgressHosts: "" }));
	});

	it("saves the workspace-provider toggle the admin actually flipped", () => {
		const { onSave } = renderCard({ ...saved, allowWorkspaceConnections: false });

		expect(ownProviderSwitch().getAttribute("aria-checked")).toBe("false");

		fireEvent.click(ownProviderSwitch());

		expect(ownProviderSwitch().getAttribute("aria-checked")).toBe("true");
		expect(saveButton().disabled).toBe(false);

		fireEvent.click(saveButton());

		expect(onSave).toHaveBeenCalledWith(
			expect.objectContaining({
				allowedEgressHosts: "api.openai.com",
				allowWorkspaceConnections: true,
			}),
		);
	});

	it("keeps an unsaved edit when the settings are refetched underneath it", () => {
		const { rerender } = renderCard();

		fireEvent.change(hostsField(), { target: { value: "llm.example.com" } });

		rerender(
			<InstanceLlmSettingsCard
				settings={{ allowedEgressHosts: "api.openai.com", allowWorkspaceConnections: false }}
				isLoading={false}
				isSubmitting={false}
				onSave={vi.fn()}
			/>,
		);

		expect(hostsField().value).toBe("llm.example.com");
		expect(saveButton().disabled).toBe(false);
	});
});
