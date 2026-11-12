import { HttpResponse, http } from "msw";

import type {
	CatalogOrigin,
	FeedbackModeAssignment,
	Practice,
	PracticeDefinition,
	PracticeGroup,
	PracticeGuidanceDto,
} from "@/api/types.gen";
import { mockDefinitionOptions } from "@/components/admin/practice-editor/fixtures";
import {
	mockPracticeOf,
	practiceGroup,
	mockAdmin,
	OWN_ORIGIN,
} from "@/components/admin/practices/fixtures";
import type { Wire } from "@/lib/dates";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { toWire } from "@/test/to-wire";

const FOLLOWS_WORKSPACE: FeedbackModeAssignment = {
	effective: "APPROVE_FIRST",
	override: null,
	source: "WORKSPACE",
};

interface WirePracticeInput {
	slug: string;
	definition: PracticeDefinition;
	kind?: string;
	groupSlug?: string | null;
	position?: number;
	versionId?: string;
	versionNumber?: number;
	catalogOrigin?: CatalogOrigin;
}

export function wirePractice({
	slug,
	definition,
	kind = "scm.pull_request",
	groupSlug = null,
	position = 0,
	versionId = "501",
	versionNumber = 1,
	catalogOrigin = OWN_ORIGIN,
}: WirePracticeInput): Wire<Practice> {
	return toWire({
		...mockPracticeOf({
			id: "50",
			slug,
			kind,
			groupSlug,
			position,
			definition,
			feedbackMode: FOLLOWS_WORKSPACE,
			catalogOrigin,
			version: {
				id: versionId,
				number: versionNumber,
				reviewDigest: `sha256:${versionId}`,
				createdAt: new Date("2026-10-01T09:00:00Z"),
				createdBy: mockAdmin,
			},
		}),
		createdAt: new Date("2026-09-01T09:00:00Z"),
		updatedAt: new Date("2026-10-01T09:00:00Z"),
	});
}

export function wireGroup(
	slug: string,
	name: string,
	overrides: Partial<Wire<PracticeGroup>> = {},
): Wire<PracticeGroup> {
	return {
		...toWire(
			practiceGroup({
				id: "60",
				slug,
				name,
				feedbackMode: FOLLOWS_WORKSPACE,
				createdAt: new Date("2026-09-01T09:00:00Z"),
			}),
		),
		...overrides,
	};
}

/** Install defaults first; tests prepend their specific overrides through server.use. */
export function practiceSetupHandlers() {
	return [
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json(ownMembership("ADMIN")),
		),
		http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
		http.get("*/workspaces/:workspaceSlug/practice-groups", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/practices", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/practices/definition-options", () =>
			HttpResponse.json(mockDefinitionOptions),
		),
		http.get("*/workspaces/:workspaceSlug/practices/releases", () => HttpResponse.json([])),
	];
}

// Register fixed practice paths before the slug handler, which can match them too.
export function practicesHandlers(practices: Wire<Practice>[]) {
	return [
		http.get("*/workspaces/:workspaceSlug/practices", () => HttpResponse.json(practices)),
		http.get("*/workspaces/:workspaceSlug/practices/definition-options", () =>
			HttpResponse.json(mockDefinitionOptions),
		),
		http.get("*/workspaces/:workspaceSlug/practices/releases", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/practices/:practiceSlug", ({ params }) => {
			const practice = practices.find(({ slug }) => slug === params.practiceSlug);
			return practice
				? HttpResponse.json(practice)
				: HttpResponse.json({ title: "Not Found", status: 404 }, { status: 404 });
		}),
	];
}

export function wireGuidance(
	practiceSlug: string,
	guide: string | null,
): Wire<PracticeGuidanceDto> {
	return {
		practiceSlug,
		visual: null,
		guide: guide === null ? null : { markdown: guide, figures: {} },
	};
}
