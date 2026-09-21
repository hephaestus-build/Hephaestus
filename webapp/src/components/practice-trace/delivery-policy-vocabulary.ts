import { CircleCheckIcon, CircleDashedIcon, CircleSlashIcon, OctagonXIcon } from "lucide-react";
import type {
	DeliveryPolicyFactsSnapshot,
	DeliveryPolicyTrace,
	DeliveryPolicyTraceCheck,
} from "@/api/types.gen";
import type { StatusDefs } from "@/components/common/status-def";
import { WITHHOLDING_REASON_DEFS } from "@/components/practice-vocabulary/withholding-defs";
import { WORKSPACE_DELIVERY_STATUS_DEFS } from "@/components/practice-vocabulary/workspace-delivery-status-defs";

type Check = DeliveryPolicyTraceCheck["check"];
type Status = DeliveryPolicyTraceCheck["status"];
type Surface = DeliveryPolicyTrace["surface"];
type Stage = DeliveryPolicyTrace["stage"];
type Reason = NonNullable<DeliveryPolicyTrace["decisiveReason"]>;
type RepositoryMode = NonNullable<DeliveryPolicyFactsSnapshot["repositoryMode"]>;
type PersonMode = NonNullable<DeliveryPolicyFactsSnapshot["personMode"]>;
type Subject = NonNullable<DeliveryPolicyFactsSnapshot["subject"]>;
type DeliveryStatus = NonNullable<DeliveryPolicyFactsSnapshot["deliveryStatus"]>;
type TriggerMode = NonNullable<DeliveryPolicyFactsSnapshot["triggerMode"]>;
type Autonomy = NonNullable<
	NonNullable<DeliveryPolicyFactsSnapshot["contributingPractices"]>[number]["autonomy"]
>;

export const DELIVERY_CHECK_LABELS = {
	INSTANCE_SILENT_MODE: "Instance Silent Mode",
	WORKSPACE_ENABLED: "Workspace enabled",
	ROLLOUT_REVISION: "Current rollout revision",
	WORKSPACE_DELIVERY: "Workspace delivery",
	CURRENT_COVERAGE: "Current review coverage",
	PRACTICE_AUTHORITY: "Practice authority",
	RECIPIENT_CONSENT: "Recipient preference",
	ARTIFACT_ELIGIBILITY: "Work eligibility",
} satisfies Record<Check, string>;

export const DELIVERY_CHECK_STATUS_DEFS: StatusDefs<Status> = {
	PASSED: {
		label: "Passed",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "The check allowed evaluation to continue.",
	},
	DENIED: {
		label: "Denied",
		icon: OctagonXIcon,
		badgeVariant: "destructive",
		description: "The check stopped delivery.",
	},
	NOT_APPLICABLE: {
		label: "Not applicable",
		icon: CircleSlashIcon,
		badgeVariant: "outline",
		description: "The check did not apply to this surface or stage.",
	},
	NOT_REACHED: {
		label: "Not reached",
		icon: CircleDashedIcon,
		badgeVariant: "secondary",
		description: "An earlier denial stopped evaluation before this check.",
	},
};

export const DELIVERY_SURFACE_LABELS = {
	ARTIFACT: "In-context feedback",
	IN_APP: "In-app feedback",
	CONVERSATION: "Conversation feedback",
} satisfies Record<Surface, string>;

export const DELIVERY_STAGE_LABELS = {
	COMPOSITION: "Composition",
	AUTOMATIC: "Automatic authorization",
	APPROVED: "Approved authorization",
	EGRESS: "Final delivery",
} satisfies Record<Stage, string>;

export const DELIVERY_REPOSITORY_MODE_LABELS = {
	ALL_MONITORED: "all monitored",
	SELECTED: "selected",
} satisfies Record<RepositoryMode, string>;

export const DELIVERY_PERSON_MODE_LABELS = {
	ALL_ELIGIBLE: "all eligible",
	SELECTED: "selected",
} satisfies Record<PersonMode, string>;

export const DELIVERY_SUBJECT_LABELS = {
	RESOLVED_LINKED_HUMAN: "author is a workspace member",
	MISSING: "no author identified",
	NON_HUMAN: "author is a bot",
	UNLINKED: "author is not a workspace member",
} satisfies Record<Subject, string>;

export const DELIVERY_STATUS_LABELS = {
	ACTIVE: WORKSPACE_DELIVERY_STATUS_DEFS.ACTIVE.label,
	PAUSED: WORKSPACE_DELIVERY_STATUS_DEFS.PAUSED.label,
} satisfies Record<DeliveryStatus, string>;

export const DELIVERY_TRIGGER_LABELS = {
	AUTO: "started automatically",
	MANUAL: "requested by a person",
} satisfies Record<TriggerMode, string>;

export const DELIVERY_AUTONOMY_LABELS = {
	OFF: "off",
	HUMAN_APPROVAL: "review before sending",
	AUTOMATIC: "send automatically",
} satisfies Record<Autonomy, string>;

export const DELIVERY_REASON_SENTENCES = WITHHOLDING_REASON_DEFS satisfies Record<Reason, string>;
