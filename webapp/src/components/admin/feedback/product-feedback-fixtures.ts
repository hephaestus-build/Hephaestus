import type { FeedbackItem } from "@/api/types.gen";
import { daysBefore, hoursBefore, minutesBefore } from "@/components/common/story-clock";

export const bugReport = {
	id: "f1f1f1f1-0000-0000-0000-000000000001",
	kind: "BUG",
	message:
		"The practice page jumps to the top every time I change the filter.\n\nSteps: open a practice group, pick a team, watch the scroll reset.",
	account: { id: 2, displayName: "Grace Hopper", email: "grace@example.org" },
	workspace: { id: 7, slug: "acme", displayName: "Acme" },
	pagePath: "/w/acme/practices/code-review",
	userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:131.0) Gecko/20100101 Firefox/131.0",
	appVersion: "0.71.0",
	createdAt: minutesBefore(40),
} satisfies FeedbackItem;

export const idea = {
	id: "f1f1f1f1-0000-0000-0000-000000000005",
	kind: "IDEA",
	message: "Let me pin the practices I am working on to the top of my page.",
	account: { id: 5, displayName: "Barbara Liskov", email: "barbara@example.org" },
	workspace: { id: 7, slug: "acme", displayName: "Acme" },
	appVersion: "0.71.0",
	createdAt: hoursBefore(2),
} satisfies FeedbackItem;

export const praise = {
	id: "f1f1f1f1-0000-0000-0000-000000000002",
	kind: "FEEDBACK",
	message: "Loving the summary on merged pull requests. A weekly digest would be even better.",
	account: { id: 3, displayName: "Linus Torvalds" },
	appVersion: "0.71.0",
	createdAt: hoursBefore(6),
} satisfies FeedbackItem;

/** Sent by an account that has since been erased. */
export const orphanedFeedback = {
	id: "f1f1f1f1-0000-0000-0000-000000000003",
	kind: "FEEDBACK",
	message: "Can Heph answer in German?",
	workspace: { id: 9, slug: "globex", displayName: "Globex" },
	pagePath: "/w/globex/mentor",
	appVersion: "0.70.2",
	createdAt: daysBefore(2),
} satisfies FeedbackItem;

export const resolvedBugReport = {
	id: "f1f1f1f1-0000-0000-0000-000000000004",
	kind: "BUG",
	message: "Sign-in with GitLab looped back to the login page.",
	account: { id: 4, displayName: "Margaret Hamilton", email: "margaret@example.org" },
	workspace: { id: 7, slug: "acme", displayName: "Acme" },
	pagePath: "/login",
	userAgent:
		"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36",
	appVersion: "0.69.0",
	createdAt: daysBefore(12),
	resolvedAt: daysBefore(10),
	resolvedBy: { id: 1, displayName: "Ada Lovelace", email: "ada@example.org" },
} satisfies FeedbackItem;

export const openFeedback: FeedbackItem[] = [bugReport, idea, praise, orphanedFeedback];
export const resolvedFeedback: FeedbackItem[] = [resolvedBugReport];
