import { LinkIcon } from "lucide-react";
import { describe, expect, it } from "vitest";

import { GitHubIcon, GitLabIcon, OutlineIcon, SlackIcon } from "@/components/icons/brand";
import { getProviderIcon } from "@/components/icons/integration-provider-icons";

describe("getProviderIcon", () => {
	it.each([
		["github", GitHubIcon],
		["GitLab", GitLabIcon],
		["SLACK", SlackIcon],
		["Outline", OutlineIcon],
	])("draws %s with its own mark, whatever the case of its type", (providerType, icon) => {
		expect(getProviderIcon(providerType)).toBe(icon);
	});

	it("draws an unknown or missing provider with the generic link", () => {
		expect(getProviderIcon("KEYCLOAK")).toBe(LinkIcon);
		expect(getProviderIcon("")).toBe(LinkIcon);
		expect(getProviderIcon(undefined)).toBe(LinkIcon);
	});
});
