import { expect, it } from "vite-plus/test";

import { aboutLabel } from "./about-role-labels";

it("displays a neutral label for a role from a newer server", () => {
	expect(aboutLabel("APPROVER")).toBe("Other role");
	expect(aboutLabel("constructor")).toBe("Other role");
	expect(aboutLabel(null)).toBe("The work");
});
