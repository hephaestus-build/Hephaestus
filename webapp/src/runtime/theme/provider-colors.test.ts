import { renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { ProviderType } from "@/lib/provider/provider-terms";

import { useProviderColors } from "./provider-colors";

describe("useProviderColors", () => {
	it("paints the provider from the document root, which portals inherit, and clears it", () => {
		const { rerender, unmount } = renderHook(
			({ provider }: { provider: ProviderType }) => useProviderColors(provider),
			{
				initialProps: { provider: "GITLAB" },
			},
		);
		expect(document.documentElement.dataset.provider).toBe("gitlab");

		rerender({ provider: "GITHUB" });
		expect(document.documentElement.dataset.provider).toBe("github");

		unmount();
		expect(document.documentElement.dataset.provider).toBeUndefined();
	});
});
