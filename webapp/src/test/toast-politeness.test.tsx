import { render, screen } from "@testing-library/react";
import { toast } from "sonner";
import { describe, expect, it } from "vitest";

import { Toaster } from "@/components/ui/sonner";

/**
 * A tripwire on the dependency, not on our own code: sonner hardcodes `aria-live="polite"` on its
 * container and exposes no per-toast politeness, so an error toast cannot interrupt
 * (emilkowalski/sonner#765). This fails when upstream lands that, which is the cue to route
 * `toast.error` to an assertive region.
 */
describe("sonner toast politeness", () => {
	it("announces an error toast politely, because there is no per-toast role to set", async () => {
		render(<Toaster />);
		toast.error("We could not delete the model");

		await screen.findByText("We could not delete the model");
		const region = document.querySelector("section[aria-live]");
		expect(region?.getAttribute("aria-live")).toBe("polite");

		expect(screen.queryByRole("alert")).toBeNull();
	});
});
