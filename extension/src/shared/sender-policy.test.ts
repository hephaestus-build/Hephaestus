import { describe, expect, it } from "vitest";

import { requestSchema } from "~/shared/rpc";
import { authorize, classifySender } from "~/shared/sender-policy";

const ID = "ijkajblcbajjpjbknfgdiiiljipafiko";
const options = { id: ID, url: `chrome-extension://${ID}/options.html`, frameId: 0 };
const sidepanel = { id: ID, url: `chrome-extension://${ID}/sidepanel.html` };
const action = {
	id: ID,
	url: `chrome-extension://${ID}/action.html#intent`,
	frameId: 0,
	tab: { id: 7 },
};
const INTENT = "abcdefghijklmnopqrstuvwxyz012345";
const JOB = "0b7c1a52-0d3e-4c55-8a0b-1d2e3f4a5b6c";
const inline = {
	id: ID,
	url: "chrome-extension://4a0b7c8e-5d9f-4b3e-9a61-0c1d2e3f4a5b/inline.html",
	frameId: 3,
	tab: { id: 42 },
};

describe("classifySender", () => {
	it("recognises only settings and the inline frame", () => {
		expect(classifySender(options, ID)).toBe("options");
		expect(classifySender(sidepanel, ID)).toBeUndefined();
		expect(classifySender(inline, ID)).toBe("inline");
	});

	it("refuses another extension, a content script and a web page", () => {
		expect(
			classifySender({ ...options, id: "otherextensionidaaaaaaaaaaaaaaaa" }, ID),
		).toBeUndefined();
		expect(
			classifySender(
				{ id: ID, url: "https://github.com/o/r/pull/1", frameId: 0, tab: { id: 1 } },
				ID,
			),
		).toBeUndefined();
		expect(classifySender({ url: `chrome-extension://${ID}/options.html` }, ID)).toBeUndefined();
	});

	it("refuses the inline page when it is not a frame inside a tab", () => {
		expect(classifySender({ ...inline, frameId: 0 }, ID)).toBeUndefined();
		expect(classifySender({ id: ID, url: inline.url, frameId: 2 }, ID)).toBeUndefined();
	});

	it("recognises the confirmation window only as a top-level page of this extension", () => {
		expect(classifySender(action, ID)).toBe("action");
		expect(classifySender({ ...action, frameId: 2 }, ID)).toBeUndefined();
		expect(
			classifySender({ ...action, url: "chrome-extension://dynamic-guid/action.html" }, ID),
		).toBeUndefined();
	});

	it("refuses a privileged page served from another host", () => {
		expect(
			classifySender({ id: ID, url: "chrome-extension://dynamic-guid/options.html" }, ID),
		).toBeUndefined();
	});
});

function request(value: unknown) {
	return requestSchema.parse(value);
}

describe("authorize", () => {
	it("lets the inline frame read about its own tab only", () => {
		expect(authorize(request({ type: "get-context" }), inline, ID)).toStrictEqual({
			allowed: true,
			surface: "inline",
			tabId: 42,
		});
		expect(authorize(request({ type: "get-context" }), options, ID).allowed).toBe(false);
	});

	it.each([
		{ type: "sign-out" },
		{ type: "clear-instance" },
		{ type: "sign-in", registrationId: "github" },
		{ type: "sign-in-dev", username: "developer", admin: false },
		{ type: "list-site-access" },
		{ type: "configure-instance", origin: "https://evil.example" },
	])("refuses $type from the inline frame", (value) => {
		expect(authorize(request(value), { ...inline, tab: { id: 42 } }, ID).allowed).toBe(false);
	});

	it("keeps instance setup and sign-in to the options page", () => {
		expect(
			authorize(request({ type: "sign-in", registrationId: "github" }), sidepanel, ID).allowed,
		).toBe(false);
		expect(
			authorize(request({ type: "sign-in", registrationId: "github" }), options, ID).allowed,
		).toBe(true);
	});

	it("lets the inline frame ask for the confirmation window, but never confirm", () => {
		const open = request({
			type: "open-action",
			workspaceSlug: "team",
			action: { kind: "request-review" },
		});
		expect(authorize(open, inline, ID)).toStrictEqual({
			allowed: true,
			surface: "inline",
			tabId: 42,
		});
		for (const type of ["get-action", "confirm-action", "discard-action"]) {
			expect(authorize(request({ type, intent: INTENT }), inline, ID).allowed).toBe(false);
			expect(authorize(request({ type, intent: INTENT }), options, ID).allowed).toBe(false);
			expect(authorize(request({ type, intent: INTENT }), action, ID).allowed).toBe(true);
		}
		expect(authorize(open, action, ID).allowed).toBe(false);
	});

	it.each([
		{ type: "list-observations", workspaceSlug: "team" },
		{ type: "get-work-feedback", workspaceSlug: "team" },
	])("keeps $type to the inline frame", (value) => {
		expect(authorize(request(value), inline, ID).allowed).toBe(true);
		expect(authorize(request(value), options, ID).allowed).toBe(false);
		expect(authorize(request(value), action, ID).allowed).toBe(false);
	});

	it("gives an old side-panel document no access even to account reads", () => {
		expect(authorize(request({ type: "get-state" }), sidepanel, ID)).toStrictEqual({
			allowed: false,
		});
	});
});

describe("requestSchema", () => {
	it.each([
		{ type: "fetch", url: "https://evil.example" },
		{ type: "get-context", tabId: -1 },
		{ type: "get-context", tabId: 42 },
		{ type: "get-review-details", workspaceSlug: "team", tabId: 42 },
		{ type: "get-context", workspaceSlug: "../admin" },
		{ type: "request-review", tabId: 42, workspaceSlug: "team", workId: "9" },
		{ type: "set-preferred-workspace", workspaceSlug: "team" },
		{ type: "retry-result-processing", tabId: 42, workspaceSlug: "team", jobId: "job" },
		{ type: "cancel-review", tabId: 1, workspaceSlug: "team", jobId: "not-a-uuid" },
		{ type: "sign-in", registrationId: "a b" },
		{ type: "sign-in-dev", username: "x;drop", admin: false },
		{ type: "list-observations", workspaceSlug: "team", artifactId: 9 },
		// Every developer's records, one observation's detail, feedback bodies and run changes are
		// the web app's: the extension has no command for them.
		{ type: "list-observations", workspaceSlug: "team", scope: "workspace" },
		{ type: "get-observation", workspaceSlug: "team", observationId: JOB },
		{ type: "get-feedback", workspaceSlug: "team", feedbackId: JOB },
		{ type: "get-review-details", workspaceSlug: "team" },
		{ type: "open-action", workspaceSlug: "team", action: { kind: "cancel-review", jobId: JOB } },
		{ type: "open-action", workspaceSlug: "team", action: { kind: "retry-delivery", jobId: JOB } },
		{ type: "open-action", workspaceSlug: "team", action: { kind: "request-review", url: "x" } },
		{ type: "open-action", workspaceSlug: "team", action: { kind: "decide-feedback" } },
		{ type: "open-action", workspaceSlug: "team", action: { kind: "cancel-review", jobId: "1" } },
		{ type: "confirm-action", intent: "short" },
		{ type: "confirm-action", intent: INTENT, action: { kind: "request-review" } },
	])("refuses %o", (value) => {
		expect(requestSchema.safeParse(value).success).toBe(false);
	});
});

describe("the subject of a read", () => {
	const ROW = { kind: "list-row", url: "https://github.com/octo/app/pull/12" };

	it.each([
		{ type: "get-context", subject: ROW },
		{ type: "get-work-feedback", workspaceSlug: "team", subject: ROW },
		{ type: "get-context", subject: { kind: "page" } },
	])("accepts %o", (value) => {
		expect(requestSchema.safeParse(value).success).toBe(true);
	});

	it.each([
		// oxlint-disable-next-line no-script-url -- The schema must refuse one.
		{ type: "get-context", subject: { kind: "list-row", url: "javascript:alert(1)" } },
		{ type: "get-context", subject: { kind: "list-row", url: "chrome-extension://x/inline.html" } },
		{ type: "get-context", subject: { kind: "list-row" } },
		{ type: "get-context", subject: { kind: "tab", id: 4 } },
		{ type: "get-context", subject: { ...ROW, tabId: 4 } },
		// A list row's preview reads only the line and the reader's comments.
		{ type: "list-observations", workspaceSlug: "team", subject: ROW },
		// Changes and remembered choices are the page's own: a list row cannot name them.
		{
			type: "open-action",
			workspaceSlug: "team",
			action: { kind: "request-review" },
			subject: ROW,
		},
		{ type: "set-report-view", generation: 1, expanded: true, subject: ROW },
		{ type: "get-report-view", subject: ROW },
	])("refuses %o", (value) => {
		expect(requestSchema.safeParse(value).success).toBe(false);
	});
});
