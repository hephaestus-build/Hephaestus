import { BanIcon, CheckIcon, ClockIcon, type LucideIcon, PauseIcon } from "lucide-react";

import { cn } from "cn";
import type { SlackMonitoredChannel } from "@/api/types.gen";
import { Badge } from "@/components/ui/badge";

/** The consent lifecycle states, sourced from the generated DTO so they never drift. */
export type SlackConsentState = SlackMonitoredChannel["consentState"];

interface ConsentTerm {
	/** The word an admin reads. The wire enum (PENDING/ACTIVE/…) is never shown. */
	label: string;
	Icon: LucideIcon;
	variant: "outline" | "secondary" | "destructive" | "success";
}

/**
 * The single place the consent lifecycle is put into words. Every surface — the channel row,
 * the audit trail in the history sheet — reads from this map, so a state can never be spelled
 * "Monitoring" in one place and "ACTIVE" in another.
 *
 * Word + icon for every state (never color-only), so the status survives WCAG 1.4.1.
 */
const CONSENT_TERMS: Record<SlackConsentState, ConsentTerm> = {
	PENDING: { label: "Not started", Icon: ClockIcon, variant: "secondary" },
	ACTIVE: { label: "Monitoring", Icon: CheckIcon, variant: "success" },
	PAUSED: { label: "Paused", Icon: PauseIcon, variant: "outline" },
	REVOKED: { label: "Revoked", Icon: BanIcon, variant: "destructive" },
};

export function consentTerm(state: SlackConsentState): ConsentTerm {
	return CONSENT_TERMS[state];
}

export interface ConsentStateBadgeProps {
	state: SlackConsentState;
	className?: string;
}

/** The one rendering of a consent state. */
export function ConsentStateBadge({ state, className }: ConsentStateBadgeProps) {
	const term = consentTerm(state);
	return (
		<Badge variant={term.variant} className={cn("gap-1", className)}>
			<term.Icon className="size-3" aria-hidden />
			{term.label}
		</Badge>
	);
}
