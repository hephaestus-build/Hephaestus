import { describe, expect, it } from "vitest";

import {
	AccountStatusSchema,
	ConfigurationRequirementSchema,
	ConfigurationStatusSchema,
	CurrentnessSchema,
	FeedbackCopyKindSchema,
	FeedbackDestinationSchema,
	FeedbackResolutionSchema,
	FeedbackStateSchema,
	FeedbackUsefulnessSchema,
	IntegrationKindSchema,
	ConversationEntryKindSchema,
	ReleaseCheckFailureSchema,
	ReleaseCheckStatusSchema,
	ResearchDisclosureSubjectRemovalReasonSchema,
	ReviewKindSchema,
	ReviewOriginSchema,
	ReviewRequestReasonSchema,
	ReviewStatusSchema,
	ReviewStopReasonSchema,
	SyncJobStatusSchema,
	SyncJobTriggerSchema,
	SyncJobTypeSchema,
	WorkspaceStatusSchema,
} from "@/api/schemas.gen";
import {
	CONFIGURATION_REQUIREMENT_DEFS,
	CONFIGURATION_STATUS_DEFS,
} from "@/components/admin/instance/configuration-status-defs";
import {
	RELEASE_CHECK_FAILURE_LABELS,
	RELEASE_CHECK_STATUS_DEFS,
} from "@/components/admin/instance/release-check-status-defs";
import {
	JOB_STATUS_LABEL,
	JOB_TRIGGER_LABEL,
	JOB_TYPE_LABEL,
} from "@/components/admin/integrations/sync-format";
import {
	DETAIL_ROUTE,
	KIND_ICON,
} from "@/components/admin/integrations/WorkspaceIntegrationOverviewCard";
import { STATUS_VARIANT } from "@/components/admin/integrations/WorkspaceSyncJobsTable";
import {
	WITHDRAW_DESCRIPTIONS,
	WITHDRAWN_NOW,
} from "@/components/admin/practice-reviews/FeedbackLevel";
import { ASSESSED_NOTHING } from "@/components/admin/practice-reviews/lineage-story";
import { REVIEW_COUNT } from "@/components/admin/practice-reviews/review-outcomes";
import { READY_FOR } from "@/components/admin/practice-reviews/trace-verdict";
import { PROVIDER_NAMES } from "@/components/admin/practice-reviews/TraceStepFacts";
import { REMOVAL_REASON_LABELS } from "@/components/admin/research/research-labels";
import { ACCOUNT_STATUS_LABELS } from "@/components/admin/users/account-labels";
import { WORKSPACE_STATUS_LABELS } from "@/components/admin/workspaces/AdminWorkspacesTable";
import { AUTHORS } from "@/components/conversation/timeline-rows";
import { PROVIDER_ICONS } from "@/components/icons/integration-provider-icons";
import { CURRENTNESS_VALUES } from "@/components/practice-vocabulary/claim-currentness";
import { DELIVERY_STATE_DEFS } from "@/components/practice-vocabulary/delivery-state-defs";
import {
	FEEDBACK_COPY_KIND_DEFS,
	KIND_NOUNS,
} from "@/components/practice-vocabulary/feedback-copy-defs";
import { FEEDBACK_DESTINATION_DEFS } from "@/components/practice-vocabulary/feedback-destination-defs";
import { FEEDBACK_RESOLUTION_DEFS } from "@/components/practice-vocabulary/feedback-resolution-defs";
import { FEEDBACK_USEFULNESS_DEFS } from "@/components/practice-vocabulary/feedback-usefulness-defs";
import { ITEM_EVENT_REASON_SENTENCES } from "@/components/practice-vocabulary/item-event-defs";
import { REVIEW_ORIGIN_DEFS } from "@/components/practice-vocabulary/review-origin-defs";
import {
	OWN_REVIEW_STATUS_DEFS,
	PARTIAL_REVIEW_SCOPE,
	REVIEW_STATUS_DEFS,
	REVIEW_STOP_REASON_SENTENCES,
} from "@/components/practice-vocabulary/review-status-defs";

/**
 * The API types an open enum as a plain string, so the compiler no longer holds a registry to every
 * value. This test does: each registry, and each other map that must name every value, names exactly
 * the values that the spec lists as `examples`. A map that names only some values on purpose is not
 * here.
 */
const REGISTRIES: [
	name: string,
	keys: readonly string[],
	schema: { examples: readonly string[] },
][] = [
	["account status", Object.keys(ACCOUNT_STATUS_LABELS), AccountStatusSchema],
	[
		"configuration requirement",
		Object.keys(CONFIGURATION_REQUIREMENT_DEFS),
		ConfigurationRequirementSchema,
	],
	["configuration status", Object.keys(CONFIGURATION_STATUS_DEFS), ConfigurationStatusSchema],
	["currentness", CURRENTNESS_VALUES, CurrentnessSchema],
	["feedback copy kind", Object.keys(FEEDBACK_COPY_KIND_DEFS), FeedbackCopyKindSchema],
	["feedback destination", Object.keys(FEEDBACK_DESTINATION_DEFS), FeedbackDestinationSchema],
	["feedback resolution", Object.keys(FEEDBACK_RESOLUTION_DEFS), FeedbackResolutionSchema],
	["feedback state", Object.keys(DELIVERY_STATE_DEFS), FeedbackStateSchema],
	["feedback usefulness", Object.keys(FEEDBACK_USEFULNESS_DEFS), FeedbackUsefulnessSchema],
	["integration kind", Object.keys(PROVIDER_ICONS), IntegrationKindSchema],
	["release check failure", Object.keys(RELEASE_CHECK_FAILURE_LABELS), ReleaseCheckFailureSchema],
	["release check status", Object.keys(RELEASE_CHECK_STATUS_DEFS), ReleaseCheckStatusSchema],
	[
		"removal reason",
		Object.keys(REMOVAL_REASON_LABELS),
		ResearchDisclosureSubjectRemovalReasonSchema,
	],
	["review kind", Object.keys(PARTIAL_REVIEW_SCOPE), ReviewKindSchema],
	["review origin", Object.keys(REVIEW_ORIGIN_DEFS), ReviewOriginSchema],
	["review request reason", Object.keys(ITEM_EVENT_REASON_SENTENCES), ReviewRequestReasonSchema],
	["review status", Object.keys(REVIEW_STATUS_DEFS), ReviewStatusSchema],
	["own review status", Object.keys(OWN_REVIEW_STATUS_DEFS), ReviewStatusSchema],
	["review stop reason", Object.keys(REVIEW_STOP_REASON_SENTENCES), ReviewStopReasonSchema],
	["sync job status", Object.keys(JOB_STATUS_LABEL), SyncJobStatusSchema],
	["sync job trigger", Object.keys(JOB_TRIGGER_LABEL), SyncJobTriggerSchema],
	["sync job type", Object.keys(JOB_TYPE_LABEL), SyncJobTypeSchema],
	["sync job status variant", Object.keys(STATUS_VARIANT), SyncJobStatusSchema],
	["feedback copy kind noun", Object.keys(KIND_NOUNS), FeedbackCopyKindSchema],
	["withdrawn feedback", Object.keys(WITHDRAWN_NOW), FeedbackDestinationSchema],
	["withdraw description", Object.keys(WITHDRAW_DESCRIPTIONS), FeedbackDestinationSchema],
	["ready feedback destination", Object.keys(READY_FOR), FeedbackDestinationSchema],
	["integration detail route", Object.keys(DETAIL_ROUTE), IntegrationKindSchema],
	["integration icon", Object.keys(KIND_ICON), IntegrationKindSchema],
	["integration name", Object.keys(PROVIDER_NAMES), IntegrationKindSchema],
	["review step", Object.keys(ASSESSED_NOTHING), ReviewStatusSchema],
	["review count", Object.keys(REVIEW_COUNT), ReviewStatusSchema],
	["conversation author", Object.keys(AUTHORS), ConversationEntryKindSchema],
	["workspace status", Object.keys(WORKSPACE_STATUS_LABELS), WorkspaceStatusSchema],
];

describe("open enum registries", () => {
	it.each(REGISTRIES)("the %s words name every value that the API lists", (_name, keys, schema) => {
		expect([...keys].sort()).toStrictEqual([...schema.examples].sort());
	});
});
