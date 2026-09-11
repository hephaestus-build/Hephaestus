import type { AdminListAuthEventsData } from "@/api/types.gen";
import { humanizeToken } from "@/lib/humanize";
import { isRecord } from "@/lib/is-record";
export type AuditSeverity = "error" | "warning" | "info";

const HIGH_RISK_EVENTS = new Set([
	"IMPERSONATION_BEGIN",
	"WORKSPACE_ELEVATION",
	"APP_ROLE_CHANGED",
	"ACCOUNT_DELETED",
	"JWT_REVOKED",
	"IDENTITY_UNLINKED",
]);

export function eventSeverity(eventType: string, result: string): AuditSeverity {
	if (result === "FAILURE") return "error";
	if (HIGH_RISK_EVENTS.has(eventType)) return "warning";
	return "info";
}

export type AuthEventType = NonNullable<
	NonNullable<AdminListAuthEventsData["query"]>["eventType"]
>[number];

export const EVENT_TYPE_LABELS: Record<AuthEventType, string> = {
	LOGIN: "Sign-in",
	LOGIN_FAILED: "Failed sign-in",
	LOGOUT: "Sign-out",
	TOKEN_REFRESH: "Token refresh",
	JWT_REVOKED: "Sessions revoked",
	IDENTITY_LINKED: "Identity linked",
	IDENTITY_UNLINKED: "Identity unlinked",
	IMPERSONATION_BEGIN: "Impersonation started",
	IMPERSONATION_END: "Impersonation ended",
	ACCOUNT_DELETED: "Account deleted",
	EXPORT_REQUESTED: "Data export requested",
	APP_ROLE_CHANGED: "Instance role changed",
	RESEARCH_CONSENT_REVOKED: "Research consent revoked",
	WORKSPACE_ELEVATION: "Workspace reached as instance admin",
	LLM_CONNECTION_CREATED: "Provider connected",
	LLM_CONNECTION_UPDATED: "Provider updated",
	LLM_CONNECTION_DELETED: "Provider removed",
	LLM_MODEL_CREATED: "Model added",
	LLM_MODEL_UPDATED: "Model updated",
	LLM_MODEL_DELETED: "Model removed",
	LLM_MODEL_PRICE_CHANGED: "Model price changed",
	LLM_MODEL_SHARING_CHANGED: "Model sharing changed",
	LLM_SETTINGS_CHANGED: "AI settings changed",
	LOGIN_PROVIDER_CREATED: "Login provider added",
	LOGIN_PROVIDER_UPDATED: "Login provider updated",
	LOGIN_PROVIDER_DELETED: "Login provider removed",
	SILENT_MODE_CHANGED: "Silent mode changed",
};

export function eventLabel(eventType: string): string {
	const known = (EVENT_TYPE_LABELS as Record<string, string | undefined>)[eventType];
	if (known) return known;
	return humanizeToken(eventType);
}

export function resultLabel(result: string): string {
	return result === "FAILURE" ? "Failure" : "Success";
}

export function humanizeDetails(details: string | undefined): string | null {
	if (!details) return null;
	let parsed: unknown;
	try {
		parsed = JSON.parse(details);
	} catch {
		return details;
	}
	if (!isRecord(parsed)) return String(parsed);
	if ("from" in parsed || "to" in parsed) {
		return `${stringify(parsed.from)} → ${stringify(parsed.to)}`;
	}
	const entries = Object.entries(parsed);
	if (entries.length === 0) return null;
	return entries.map(([k, v]) => `${k}: ${stringify(v)}`).join(", ");
}

function stringify(value: unknown): string {
	if (value === null || value === undefined) return "—";
	if (typeof value === "string") return value;
	if (typeof value === "number" || typeof value === "boolean") return String(value);
	// Objects and arrays: `String` would give "[object Object]" or a bare comma-joined run.
	return JSON.stringify(value);
}

const SEVERITY_DOT: Record<AuditSeverity, string> = {
	error: "bg-destructive",
	warning: "bg-warning",
	info: "bg-muted-foreground/40",
};

/** Import this rather than re-deriving the colours; a second copy drifts silently. */
export function severityDotClass(severity: AuditSeverity): string {
	return SEVERITY_DOT[severity];
}

/** The dot's hue is the only visual marker of a high-risk event, so screen readers hear it. */
export function severityScreenReaderPrefix(severity: AuditSeverity): string | null {
	return severity === "warning" ? "High-risk event: " : null;
}
