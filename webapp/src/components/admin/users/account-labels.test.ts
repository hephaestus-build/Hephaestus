import { describe, expect, it } from "vitest";

import { removalReasonLabel } from "@/components/admin/research/research-labels";

import { accountStatusLabel } from "./account-labels";

describe("an account or removal value that this build does not know", () => {
	it("reads as Unknown", () => {
		expect(accountStatusLabel("SUSPENDED_BY_PROVIDER")).toBe("Unknown");
		expect(accountStatusLabel("ACTIVE")).toBe("Active");
		expect(removalReasonLabel("COURT_ORDER")).toBe("Unknown");
		expect(removalReasonLabel("ACCOUNT_ERASED")).toBe("Account erased");
	});
});
