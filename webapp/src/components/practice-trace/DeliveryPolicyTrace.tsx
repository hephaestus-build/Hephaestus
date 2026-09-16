import { CheckCircle2Icon, CircleStopIcon } from "lucide-react";
import type {
	DeliveryPolicyFactsSnapshot,
	DeliveryPolicyTrace as DeliveryPolicyTraceData,
} from "@/api/types.gen";
import { RelativeTime } from "@/components/common/RelativeTime";
import { StatusBadge } from "@/components/practice-vocabulary/StatusBadge";
import {
	Accordion,
	AccordionContent,
	AccordionItem,
	AccordionTrigger,
} from "@/components/ui/accordion";
import { Badge } from "@/components/ui/badge";
import {
	DELIVERY_AUTONOMY_LABELS,
	DELIVERY_CHECK_LABELS,
	DELIVERY_CHECK_STATUS_DEFS,
	DELIVERY_PERSON_MODE_LABELS,
	DELIVERY_REASON_SENTENCES,
	DELIVERY_REPOSITORY_MODE_LABELS,
	DELIVERY_STAGE_LABELS,
	DELIVERY_STATUS_LABELS,
	DELIVERY_SUBJECT_LABELS,
	DELIVERY_SURFACE_LABELS,
	DELIVERY_TRIGGER_LABELS,
} from "./delivery-policy-vocabulary";

export interface DeliveryPolicyTraceProps {
	evaluations: DeliveryPolicyTraceData[];
}

export function DeliveryPolicyTrace({ evaluations }: DeliveryPolicyTraceProps) {
	if (evaluations.length === 0) {
		return null;
	}
	const denied = evaluations.some((evaluation) => !evaluation.allowed);

	return (
		<section className="min-w-0 rounded-lg border bg-card p-4 text-sm">
			<h4 className="font-medium">{denied ? "Why this was not sent" : "Delivery checks"}</h4>
			<p className="mt-1 text-xs text-muted-foreground">
				Checks run in order. After a denial, later checks are not reached.
			</p>
			<Accordion multiple className="mt-3">
				{evaluations.map((evaluation, index) => {
					const value = `${evaluation.reviewId}-${evaluation.evaluatedAt.toISOString()}-${index}`;
					return (
						<AccordionItem key={value} value={value}>
							<AccordionTrigger className="gap-3 no-underline hover:no-underline">
								<span className="min-w-0 flex-1">
									<span className="block break-words">
										{DELIVERY_SURFACE_LABELS[evaluation.surface]} ·{" "}
										{DELIVERY_STAGE_LABELS[evaluation.stage]}
									</span>
									<span className="block text-xs font-normal text-muted-foreground">
										<RelativeTime value={evaluation.evaluatedAt} tooltip={false} />
									</span>
								</span>
								<Badge variant={evaluation.allowed ? "success" : "warning"}>
									{evaluation.allowed ? (
										<CheckCircle2Icon aria-hidden />
									) : (
										<CircleStopIcon aria-hidden />
									)}
									{evaluation.allowed ? "Allowed" : "Stopped"}
								</Badge>
							</AccordionTrigger>
							<AccordionContent className="min-w-0 space-y-2 pb-4">
								{evaluation.decisiveReason && (
									<p className="text-xs">{DELIVERY_REASON_SENTENCES[evaluation.decisiveReason]}</p>
								)}
								<p className="min-w-0 text-xs break-words text-muted-foreground">
									{scopeSentence(evaluation.facts)}
								</p>
								<PolicyFacts facts={evaluation.facts} />
								<p className="min-w-0 text-xs break-all text-muted-foreground">
									Policy revision {evaluation.admittedRevision}
									{evaluation.evaluatedRevision == null
										? ""
										: `, evaluated as revision ${evaluation.evaluatedRevision}`}
									{" · "}Resolver {evaluation.resolverVersion}
								</p>
								<ul className="space-y-1">
									{evaluation.checks.map((check) => (
										<li
											key={check.check}
											className="flex items-center justify-between gap-3 text-xs"
										>
											<span className="min-w-0">{DELIVERY_CHECK_LABELS[check.check]}</span>
											<StatusBadge def={DELIVERY_CHECK_STATUS_DEFS[check.status]} />
										</li>
									))}
								</ul>
							</AccordionContent>
						</AccordionItem>
					);
				})}
			</Accordion>
		</section>
	);
}

function PolicyFacts({ facts }: { facts: DeliveryPolicyFactsSnapshot }) {
	const applicable = (value: boolean | undefined) =>
		value == null ? "not applicable" : value ? "yes" : "no";
	const practices = facts.contributingPractices?.map((practice) => {
		const autonomy = practice.autonomy
			? DELIVERY_AUTONOMY_LABELS[practice.autonomy]
			: "not recorded";
		return `${practice.slug ?? "unnamed practice"}: ${autonomy}`;
	});
	const rows = [
		[
			"Workspace delivery",
			facts.deliveryStatus ? DELIVERY_STATUS_LABELS[facts.deliveryStatus] : "not recorded",
		],
		["Started", facts.triggerMode ? DELIVERY_TRIGGER_LABELS[facts.triggerMode] : "not recorded"],
		["Recipient consent", applicable(facts.recipientConsent)],
		["Repository matched", applicable(facts.repositoryMatched)],
		["Branch matched", applicable(facts.branchMatched)],
		["Person matched", applicable(facts.personMatched)],
		[
			"Contributing practices",
			practices == null ? "not recorded" : practices.length === 0 ? "none" : practices.join(", "),
		],
	] as const;

	return (
		<dl className="grid min-w-0 grid-cols-[max-content_minmax(0,1fr)] gap-x-3 gap-y-1 text-xs text-muted-foreground">
			{rows.map(([label, value]) => (
				<div key={label} className="contents">
					<dt className="font-medium text-foreground">{label}</dt>
					<dd className="min-w-0 break-words">{value}</dd>
				</div>
			))}
		</dl>
	);
}

function scopeSentence(facts: DeliveryPolicyFactsSnapshot): string {
	const NOT_APPLICABLE = "not applicable";
	const where = facts.baseBranch
		? `${facts.repository ?? "no repository"} / ${facts.baseBranch}`
		: (facts.repository ?? "no repository");
	const repositories =
		facts.repositoryMode == null
			? NOT_APPLICABLE
			: DELIVERY_REPOSITORY_MODE_LABELS[facts.repositoryMode];
	const people =
		facts.personMode == null ? NOT_APPLICABLE : DELIVERY_PERSON_MODE_LABELS[facts.personMode];
	const subject = facts.subject == null ? NOT_APPLICABLE : DELIVERY_SUBJECT_LABELS[facts.subject];
	return `Scope: ${where} · Repositories: ${repositories} · People: ${people} · Subject: ${subject}`;
}
