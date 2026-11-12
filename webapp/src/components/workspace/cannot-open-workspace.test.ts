import { assert, expect, it } from "vitest";

import { toast } from "@/lib/toast";

import { noticeCannotOpenWorkspace } from "./cannot-open-workspace";

function currentNotice() {
	const notice = toast.getToasts().find(({ id }) => id === "cannot-open-workspace");
	assert(notice && "description" in notice);
	return notice;
}

it("clears the previous workspace name and can show a new destination", () => {
	noticeCannotOpenWorkspace("missing", "Acme");
	expect(currentNotice().description).toBe("This is Acme.");

	noticeCannotOpenWorkspace("missing", undefined);
	expect(currentNotice().description).toBeNull();

	noticeCannotOpenWorkspace("missing", "Beta");
	expect(currentNotice().description).toBe("This is Beta.");
});

it("clears the name when another arrival replaces a pending dismissal", () => {
	noticeCannotOpenWorkspace("missing", "Acme");
	toast.dismiss("cannot-open-workspace");

	noticeCannotOpenWorkspace("missing", undefined);

	expect(currentNotice().description).toBeNull();
});
