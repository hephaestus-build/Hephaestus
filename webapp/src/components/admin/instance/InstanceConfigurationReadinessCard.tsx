import { ClipboardCheckIcon, ListChecksIcon } from "lucide-react";

import { cn } from "cn";
import type { ConfigurationFact } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { statusToneClass, statusValues } from "@/components/common/status-def";
import { StatusBadge } from "@/components/common/StatusBadge";
import {
	Accordion,
	AccordionContent,
	AccordionItem,
	AccordionTrigger,
} from "@/components/ui/accordion";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";

import {
	CONFIGURATION_REQUIREMENT_DEFS,
	CONFIGURATION_STATUS_DEFS,
	type ConfigurationStatus,
} from "./configuration-status-defs";

export type InstanceConfigurationReadinessCardState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "empty" }
	| {
			status: "ready";
			facts: ConfigurationFact[];
			/** Set when the latest refresh failed, so `facts` are the last known ones, not current. */
			refreshFailure?: { error: unknown; onRetry: () => void };
	  };

export interface InstanceConfigurationReadinessCardProps {
	state: InstanceConfigurationReadinessCardState;
}

/** The groups that ask something of the operator start open; the rest start folded. */
const OPEN_BY_DEFAULT: ConfigurationStatus[] = ["ACTION_REQUIRED", "NOT_CONFIGURED"];

const REQUIREMENT_ORDER = statusValues(CONFIGURATION_REQUIREMENT_DEFS);

function settings(count: number): string {
	return `${count} ${count === 1 ? "setting" : "settings"}`;
}

function summaryOf(
	actionRequired: number,
	notConfigured: number,
): { status: ConfigurationStatus; text: string } {
	const absent = notConfigured > 0 ? ` ${settings(notConfigured)} not configured.` : "";
	if (actionRequired > 0) {
		const verb = actionRequired === 1 ? "needs" : "need";
		return {
			status: "ACTION_REQUIRED",
			text: `${settings(actionRequired)} ${verb} action.${absent}`,
		};
	}
	return {
		status: "SATISFIED",
		text: `Every check that applies to this instance passes.${absent}`,
	};
}

/**
 * The redacted facts the server reports about its own configuration, grouped by status with what
 * needs the operator first. A fact carries a setting's name and what is wrong with it, never its
 * value, so there is nothing here to redact. An optional setting that is absent reads as optional
 * and never as a failure.
 */
export function InstanceConfigurationReadinessCard({
	state,
}: InstanceConfigurationReadinessCardProps) {
	return (
		<Card>
			<CardHeader>
				<CardTitle className="flex items-center gap-2">
					<ListChecksIcon className="size-4 text-muted-foreground" aria-hidden />
					Configuration readiness
				</CardTitle>
				<CardDescription>
					Which settings this server has, checked without revealing their values.
				</CardDescription>
			</CardHeader>
			<CardContent className="space-y-4">
				<ReadinessBody state={state} />
			</CardContent>
		</Card>
	);
}

function ReadinessBody({ state }: InstanceConfigurationReadinessCardProps) {
	switch (state.status) {
		case "loading": {
			return (
				<div className="space-y-3">
					<Skeleton className="h-5 w-64" />
					<Skeleton className="h-8 w-full" />
					<Skeleton className="h-8 w-full" />
					<Skeleton className="h-8 w-full" />
				</div>
			);
		}
		case "error": {
			return (
				<QueryErrorAlert
					title="Configuration readiness is unavailable"
					error={state.error}
					onRetry={state.onRetry}
				/>
			);
		}
		case "empty": {
			return (
				<Empty>
					<EmptyHeader>
						<EmptyMedia variant="icon">
							<ClipboardCheckIcon aria-hidden />
						</EmptyMedia>
						<EmptyTitle>No configuration checks reported</EmptyTitle>
						<EmptyDescription>
							This server returned no checks, which it should never do. Reload the page to ask
							again.
						</EmptyDescription>
					</EmptyHeader>
				</Empty>
			);
		}
		case "ready": {
			return <ReadyBody facts={state.facts} refreshFailure={state.refreshFailure} />;
		}
	}
}

function ReadyBody({
	facts,
	refreshFailure,
}: {
	facts: ConfigurationFact[];
	refreshFailure: { error: unknown; onRetry: () => void } | undefined;
}) {
	const groups = statusValues(CONFIGURATION_STATUS_DEFS).map((status) => ({
		status,
		// `filter` hands back a copy, so sorting it in place leaves `facts` alone.
		facts: facts
			.filter((fact) => fact.status === status)
			.sort(
				(a, b) =>
					REQUIREMENT_ORDER.indexOf(a.requirement) - REQUIREMENT_ORDER.indexOf(b.requirement),
			),
	}));
	const countOf = (status: ConfigurationStatus) =>
		groups.find((group) => group.status === status)?.facts.length ?? 0;
	const summary = summaryOf(countOf("ACTION_REQUIRED"), countOf("NOT_CONFIGURED"));
	const { icon: SummaryIcon, badgeVariant } = CONFIGURATION_STATUS_DEFS[summary.status];

	return (
		<>
			{refreshFailure ? (
				<QueryErrorAlert
					title="Couldn't refresh. Showing the last successful check."
					error={refreshFailure.error}
					onRetry={refreshFailure.onRetry}
				/>
			) : null}
			<p className="flex items-center gap-2 text-sm font-medium">
				<SummaryIcon className={cn("size-4 shrink-0", statusToneClass(badgeVariant))} aria-hidden />
				{summary.text}
			</p>
			<Accordion multiple defaultValue={OPEN_BY_DEFAULT}>
				{groups
					.filter((group) => group.facts.length > 0)
					.map((group) => (
						<FactGroup key={group.status} status={group.status} facts={group.facts} />
					))}
			</Accordion>
		</>
	);
}

function FactGroup({ status, facts }: { status: ConfigurationStatus; facts: ConfigurationFact[] }) {
	const def = CONFIGURATION_STATUS_DEFS[status];
	return (
		<AccordionItem value={status}>
			<AccordionTrigger headingLevel={2} className="items-center no-underline hover:no-underline">
				<span className="flex items-center gap-2">
					<StatusBadge def={def} />
					<span className="tabular-nums">{facts.length}</span>
				</span>
			</AccordionTrigger>
			<AccordionContent>
				<p className="text-muted-foreground">{def.description}</p>
				<ul className="mt-2 divide-y">
					{facts.map((fact) => (
						<FactRow key={fact.id} fact={fact} />
					))}
				</ul>
			</AccordionContent>
		</AccordionItem>
	);
}

/** `REQUIRED` is the norm, so only the lesser requirements get a badge; it stays in the text for a screen reader. */
function FactRow({ fact }: { fact: ConfigurationFact }) {
	return (
		<li className="space-y-1 py-3 first:pt-0 last:pb-0">
			<div className="flex flex-wrap items-center gap-x-2 gap-y-1">
				<code className="font-mono text-sm break-words">{fact.subject}</code>
				{fact.requirement === "REQUIRED" ? (
					<span className="sr-only">{CONFIGURATION_REQUIREMENT_DEFS.REQUIRED.label}</span>
				) : (
					<StatusBadge def={CONFIGURATION_REQUIREMENT_DEFS[fact.requirement]} />
				)}
			</div>
			<p className="text-sm text-muted-foreground">{fact.explanation}</p>
			<p className="flex flex-wrap items-center gap-x-3 text-xs text-muted-foreground">
				<span>Applies to {fact.roles.map((role) => role.toLowerCase()).join(", ")}</span>
				<InlineLink href={fact.documentationUrl} external>
					Read the guide <span className="sr-only">for {fact.subject}</span>
				</InlineLink>
			</p>
		</li>
	);
}
