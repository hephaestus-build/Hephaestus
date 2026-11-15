import { ClipboardCheckIcon, ListChecksIcon } from "lucide-react";

import { cn } from "cn";
import type { ConfigurationFact } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import {
	definitionFor,
	isKnownValue,
	type StatusDef,
	statusToneClass,
	statusValues,
	UNKNOWN_STATUS_DEF,
} from "@/components/common/status-def";
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
import type { RefreshFailure } from "@/runtime/tanstack-query/infinite-list";

import {
	CONFIGURATION_REQUIREMENT_DEFS,
	CONFIGURATION_STATUS_DEFS,
	type ConfigurationStatus,
} from "./configuration-status-defs";

export type InstanceConfigurationReadinessCardState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "empty"; refreshFailure?: RefreshFailure }
	| {
			status: "ready";
			facts: ConfigurationFact[];
			/** Set when the latest refresh failed, so `facts` are the last known ones, not current. */
			refreshFailure?: RefreshFailure;
	  };

export interface InstanceConfigurationReadinessCardProps {
	state: InstanceConfigurationReadinessCardState;
}

/** One group holds every fact whose status this build does not know. */
const UNRECOGNIZED = "UNRECOGNIZED";

/** The groups that ask something of the operator, or that this build cannot judge, start open. */
const OPEN_BY_DEFAULT: string[] = ["ACTION_REQUIRED", "NOT_CONFIGURED", UNRECOGNIZED];

const REQUIREMENT_ORDER = statusValues(CONFIGURATION_REQUIREMENT_DEFS);

/** A requirement's place in `REQUIREMENT_ORDER`; one that this build does not know sorts last. */
function requirementRank(requirement: ConfigurationFact["requirement"]): number {
	return isKnownValue(CONFIGURATION_REQUIREMENT_DEFS, requirement)
		? REQUIREMENT_ORDER.indexOf(requirement)
		: REQUIREMENT_ORDER.length;
}

function settings(count: number): string {
	return `${count} ${count === 1 ? "setting" : "settings"}`;
}

function verbOf(count: number, one: string, many: string): string {
	return count === 1 ? one : many;
}

function summaryOf(
	actionRequired: number,
	notConfigured: number,
	unrecognized: number,
): { def: StatusDef; text: string } {
	const absent = notConfigured > 0 ? ` ${settings(notConfigured)} not configured.` : "";
	const unknown =
		unrecognized > 0
			? `${settings(unrecognized)} ${verbOf(unrecognized, "has", "have")} a status that this version of Hephaestus does not recognize.`
			: "";
	if (actionRequired > 0) {
		return {
			def: CONFIGURATION_STATUS_DEFS.ACTION_REQUIRED,
			text: `${settings(actionRequired)} ${verbOf(actionRequired, "needs", "need")} action.${absent}${unknown === "" ? "" : ` ${unknown}`}`,
		};
	}
	// A status that this build cannot judge is not a pass.
	if (unrecognized > 0) {
		return { def: UNKNOWN_STATUS_DEF, text: `${unknown}${absent}` };
	}
	return {
		def: CONFIGURATION_STATUS_DEFS.SATISFIED,
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
				<>
					<RefreshNotice refreshFailure={state.refreshFailure} />
					<Empty>
						<EmptyHeader>
							<EmptyMedia variant="icon">
								<ClipboardCheckIcon aria-hidden />
							</EmptyMedia>
							<EmptyTitle>No configuration checks reported</EmptyTitle>
							<EmptyDescription>
								The server returned no checks, which is unexpected. Reload the page to ask again.
							</EmptyDescription>
						</EmptyHeader>
					</Empty>
				</>
			);
		}
		case "ready": {
			return <ReadyBody facts={state.facts} refreshFailure={state.refreshFailure} />;
		}
	}
}

function RefreshNotice({ refreshFailure }: { refreshFailure: RefreshFailure | undefined }) {
	return refreshFailure ? (
		<QueryErrorAlert
			title="We could not refresh. This card shows the last successful check."
			{...refreshFailure}
		/>
	) : null;
}

function ReadyBody({
	facts,
	refreshFailure,
}: {
	facts: ConfigurationFact[];
	refreshFailure: RefreshFailure | undefined;
}) {
	// Every status that this build does not know shares one group, so no fact goes missing.
	const groupOf = (fact: ConfigurationFact) =>
		isKnownValue(CONFIGURATION_STATUS_DEFS, fact.status) ? fact.status : UNRECOGNIZED;
	const groups = [...statusValues(CONFIGURATION_STATUS_DEFS), UNRECOGNIZED].map((status) => ({
		status,
		// `filter` hands back a copy, so sorting it in place leaves `facts` alone.
		facts: facts
			.filter((fact) => groupOf(fact) === status)
			.sort((a, b) => requirementRank(a.requirement) - requirementRank(b.requirement)),
	}));
	const countOf = (status: ConfigurationStatus) =>
		groups.find((group) => group.status === status)?.facts.length ?? 0;
	const summary = summaryOf(
		countOf("ACTION_REQUIRED"),
		countOf("NOT_CONFIGURED"),
		countOf(UNRECOGNIZED),
	);
	const { icon: SummaryIcon, badgeVariant } = summary.def;

	return (
		<>
			<RefreshNotice refreshFailure={refreshFailure} />
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

function FactGroup({ status, facts }: { status: string; facts: ConfigurationFact[] }) {
	const def =
		status === UNRECOGNIZED ? UNKNOWN_STATUS_DEF : definitionFor(CONFIGURATION_STATUS_DEFS, status);
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
					<StatusBadge def={definitionFor(CONFIGURATION_REQUIREMENT_DEFS, fact.requirement)} />
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
