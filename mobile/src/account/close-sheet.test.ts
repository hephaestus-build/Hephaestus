import { describe, expect, it, vi } from "vitest";

import { closeSheet } from "./close-sheet";

describe("closeSheet", () => {
	it("goes back to what the sheet was opened over", () => {
		const below = { canGoBack: () => true, goBack: vi.fn<() => void>() };
		const goHome = vi.fn<() => void>();
		closeSheet(below, goHome);
		expect(below.goBack).toHaveBeenCalledOnce();
		expect(goHome).not.toHaveBeenCalled();
	});

	it("goes home when a link opened the sheet with nothing beneath it", () => {
		const goHome = vi.fn<() => void>();
		closeSheet({ canGoBack: () => false, goBack: vi.fn<() => void>() }, goHome);
		closeSheet(undefined, goHome);
		expect(goHome).toHaveBeenCalledTimes(2);
	});
});
