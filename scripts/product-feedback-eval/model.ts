import { createHash } from "node:crypto";

import Ajv2020 from "ajv/dist/2020.js";

import schema from "./schema.json" with { type: "json" };

export type Route = "public" | "private" | "email";
export type Reason =
	| "none"
	| "opted_out"
	| "security"
	| "redaction"
	| "about_person"
	| "low_confidence"
	| "abuse"
	| "no_draft";

export interface RoutingCase {
	id: string;
	kind: "BUG" | "IDEA" | "FEEDBACK";
	allowPublic: boolean;
	message: string;
	context?: { route?: string; browser?: string };
	expected: {
		route: Route;
		reason: Reason;
		/** Which layer must keep the case private: `code` holds under any model answer, `model` only under a real one. */
		decidedBy: "code" | "model" | "none";
		relatedIssue?: number | null;
	};
	mustNotAppear: string[];
	note: string;
}

export interface Corpus {
	version: 1;
	identities: {
		reporter: { name: string; login: string; email: string };
		workspaces: { name: string; slug: string }[];
		members: string[];
	};
	snapshot: {
		repository: string;
		issueTypes: string[];
		openIssues: { number: number; title: string; poisoned?: boolean }[];
	};
	cases: RoutingCase[];
}

export interface Write {
	kind: "create-issue" | "comment" | "edit" | "label" | "close" | "transfer";
	repository: "public" | "private";
	issue: number | null;
}

export interface Result {
	id: string;
	route: Route;
	reason: Reason;
	relatedIssue: number | null;
	title: string;
	body: string;
	writes: Write[];
}

/** What one run of the routing code produced for every case, written by the server's unit or live test tier. */
export interface Report {
	version: 1;
	tier: "unit" | "live";
	casesSha256: string;
	model: string | null;
	promptSha256: string | null;
	results: Result[];
}

const ajv = new Ajv2020({ strict: true, allErrors: true });
ajv.addSchema(schema, "routing");
const validateCorpus = ajv.compile<Corpus>({ $ref: "routing#/$defs/corpus" });
const validateReport = ajv.compile<Report>({ $ref: "routing#/$defs/report" });

export const sha256 = (text: string): string => createHash("sha256").update(text).digest("hex");

function unique(values: readonly (string | number)[], label: string): void {
	const seen = new Set<string | number>();
	for (const value of values) {
		if (seen.has(value)) {
			throw new Error(`Duplicate ${label}: ${value}`);
		}
		seen.add(value);
	}
}

export function parseCorpus(value: unknown): Corpus {
	if (!validateCorpus(value)) {
		throw new Error(`Invalid routing cases: ${ajv.errorsText(validateCorpus.errors)}`);
	}
	unique(
		value.cases.map((item) => item.id),
		"case id",
	);
	unique(
		value.snapshot.openIssues.map((issue) => issue.number),
		"snapshot issue number",
	);
	for (const item of value.cases) {
		const { route, reason, decidedBy } = item.expected;
		if (
			(route === "public") !== (reason === "none") ||
			(route === "public") !== (decidedBy === "none")
		) {
			throw new Error(
				`Case ${item.id}: a public case has reason none and decidedBy none, and a private case has neither`,
			);
		}
	}
	return value;
}

export function parseReport(value: unknown): Report {
	if (!validateReport(value)) {
		throw new Error(`Invalid routing report: ${ajv.errorsText(validateReport.errors)}`);
	}
	unique(
		value.results.map((item) => item.id),
		"result id",
	);
	return value;
}
