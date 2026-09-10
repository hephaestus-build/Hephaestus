import type { ConfigAuditEntryView } from "@/api/types.gen";
import { refLabel } from "@/components/admin/audit-shared/ref-label";
import { isRecord } from "@/lib/is-record";

type EntityType = NonNullable<ConfigAuditEntryView["entityType"]>;
type Action = NonNullable<ConfigAuditEntryView["action"]>;
type ActorKind = NonNullable<ConfigAuditEntryView["actorKind"]>;

/** The trail is append-only, so a row keeps the spelling it was written under: entity types that no
 * longer exist under these names must still read as the thing they describe. */
const RENAMED_ENTITY_TYPE_LABELS = {
	AGENT_CONFIG: "Agent config",
	AI_CONFIG_BINDING: "AI binding",
	PRACTICE_ACTIVE: "Practice review participation",
	WORKSPACE_LLM_BUDGET: "Shared-model AI budget",
	WORKSPACE_BYO_LLM_BUDGET: "Own-provider AI cap",
} satisfies Partial<Record<EntityType, string>>;

export const ENTITY_TYPE_LABELS: Record<EntityType, string> = {
	PRACTICE_REVIEW_SETTINGS: "Review settings",
	AGENT_BINDING: "AI binding",
	WORKSPACE_ACCESS_POLICY: "Workspace access policy",
	WORKSPACE_ACCESS_REQUEST: "Workspace access request",
	WORKSPACE_ROLE: "Workspace role",
	WORKSPACE_FEATURES: "Feature flags",
	WORKSPACE_STATUS: "Workspace status",
	WORKSPACE_TOKEN: "Access token",
	WORKSPACE_VISIBILITY: "Visibility",
	PRACTICE_USAGE: "Practice review participation",
	PRACTICE_DEFINITION: "Practice",
	PRACTICE_GROUP: "Practice group",
	CURATED_PRACTICE: "Catalog practice",
	CURATED_PRACTICE_GROUP: "Catalog group",
	WORKSPACE_INSTANCE_LLM_BUDGET: "Shared-model AI budget",
	WORKSPACE_OWN_PROVIDER_LLM_BUDGET: "Own-provider AI cap",
	REVIEW_BACKFILL_RUN: "Review of past work",
	REVIEW_SWEEP_SCHEDULE: "Recurring check for new work",
	WORKSPACE_LLM_CONNECTION: "Workspace AI provider",
	WORKSPACE_LLM_MODEL: "Workspace model",
	...RENAMED_ENTITY_TYPE_LABELS,
};

export const ACTION_LABELS: Record<Action, string> = {
	CREATED: "Created",
	UPDATED: "Updated",
	DELETED: "Deleted",
};

export const ACTION_BADGE: Record<Action, "default" | "secondary" | "outline"> = {
	CREATED: "default",
	UPDATED: "secondary",
	DELETED: "outline",
};

/** Widened: an append-only row can carry a spelling this build has no label for. */
const ENTITY_TYPE_LOOKUP: Record<string, string | undefined> = ENTITY_TYPE_LABELS;
const ACTION_LOOKUP: Record<string, string | undefined> = ACTION_LABELS;

export function entityTypeLabel(entityType: string | undefined): string {
	if (!entityType) return "Unknown";
	return ENTITY_TYPE_LOOKUP[entityType] ?? entityType;
}

export function actionLabel(action: string | undefined): string {
	if (!action) return "—";
	return ACTION_LOOKUP[action] ?? action;
}

export interface ActorDisplay {
	kind: ActorKind;
	primary: string;
	primaryEmail?: string;
	actingAs?: string;
	filterId?: number;
}

/**
 * Who caused the change. On an impersonated row `actingActor` is the operator and `actor` the identity
 * they assumed: swapping them attributes the operator's changes to their victim.
 */
export function actorDisplay(entry: ConfigAuditEntryView): ActorDisplay {
	const kind = entry.actorKind ?? "SYSTEM";
	if (kind === "SYSTEM") {
		return { kind, primary: "System" };
	}
	if (kind === "IMPERSONATED") {
		return {
			kind,
			primary: refLabel(entry.actingActor, entry.actingAccountId) ?? "Unknown",
			primaryEmail: entry.actingActor?.email,
			actingAs: refLabel(entry.actor, entry.actorAccountId) ?? undefined,
			filterId: entry.actingAccountId,
		};
	}
	return {
		kind,
		primary: refLabel(entry.actor, entry.actorAccountId) ?? "Unknown",
		primaryEmail: entry.actor?.email,
		filterId: entry.actorAccountId,
	};
}

export interface FieldChange {
	/** Dot-path, e.g. `volumeCaps.perPullRequest`. */
	path: string;
	before: string | null;
	after: string | null;
}

/**
 * A leaf value as display text. The server redacts credentials to a `…Set` boolean, which renders
 * masked so the boolean is never read as the secret; the suffix anchor keeps `publicKey` out of it.
 */
export function formatLeaf(value: unknown, path?: string): string {
	if (value === undefined || value === null) return "not set";
	if (typeof value === "boolean" && path && /(key|secret|token|password)set$/i.test(path)) {
		return value ? "••••••" : "not set";
	}
	if (typeof value === "boolean" || typeof value === "number") return String(value);
	if (typeof value === "string") return value.length === 0 ? '""' : value;
	return JSON.stringify(value);
}

export function fieldChanges(entry: ConfigAuditEntryView): FieldChange[] {
	const before = parseSnapshot(entry.oldValue);
	const after = parseSnapshot(entry.newValue);
	return (entry.changedKeys ?? []).map((path) => ({
		path,
		before: before ? formatLeaf(leafAt(before, path), path) : null,
		after: after ? formatLeaf(leafAt(after, path), path) : null,
	}));
}

export function subjectLabel(entry: ConfigAuditEntryView): { label: string; hint?: string } {
	const type = entityTypeLabel(entry.entityType);
	const snapshot = parseSnapshot(entry.newValue) ?? parseSnapshot(entry.oldValue);
	const name =
		snapshot && typeof snapshot.name === "string" && snapshot.name ? snapshot.name : undefined;
	const id = entry.entityId;
	if (name) {
		return { label: `${type} "${name}"`, hint: id ? `${type} ${identifier(id)}` : undefined };
	}
	return { label: id ? `${type} ${identifier(id)}` : type };
}

export function changeSummary(entry: ConfigAuditEntryView): string {
	if (entry.action === "CREATED" || entry.action === "DELETED") return "";
	const changes = fieldChanges(entry);
	if (changes.length === 0) return "";
	if (changes.length <= 2) {
		return changes.map((c) => `${c.path}: ${c.before ?? "—"} → ${c.after ?? "—"}`).join(" · ");
	}
	return `${changes.length} fields changed`;
}

function identifier(entityId: string): string {
	return /^\d+$/.test(entityId) ? `#${entityId}` : entityId;
}

function parseSnapshot(value: string | undefined): Record<string, unknown> | null {
	if (!value) return null;
	try {
		const parsed: unknown = JSON.parse(value);
		return isRecord(parsed) ? parsed : null;
	} catch {
		return null;
	}
}

function leafAt(obj: Record<string, unknown>, path: string): unknown {
	return path.split(".").reduce<unknown>((acc, segment) => {
		if (isRecord(acc)) {
			return acc[segment];
		}
		return undefined;
	}, obj);
}
