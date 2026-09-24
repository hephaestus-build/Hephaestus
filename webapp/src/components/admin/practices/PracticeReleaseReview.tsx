import { useId, useState } from "react";

import type {
	PracticeDefinition,
	PracticeReleaseField,
	PracticeReleaseProposal,
} from "@/api/types.gen";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Spinner } from "@/components/ui/spinner";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";

type Field = PracticeReleaseField["field"];
type Choice = "CURRENT" | "OFFERED";

const FIELDS = {
	NAME: { label: "Name", key: "name" },
	BINDINGS: { label: "When and what to review", key: "bindings" },
	CRITERIA: { label: "Review criteria", key: "criteria" },
	PRECOMPUTE_SCRIPT: { label: "Static analysis", key: "precomputeScript" },
	AUTOMATED_REVIEW_POLICY: { label: "Automated review settings", key: "automatedReviewPolicy" },
	WHY_IT_MATTERS: { label: "Why it matters", key: "whyItMatters" },
	WHAT_GOOD_LOOKS_LIKE: { label: "What good looks like", key: "whatGoodLooksLike" },
	GROUP_SLUG: { label: "Group", key: "groupSlug" },
	DELIVERY_BEHAVIOR: { label: "Feedback delivery", key: "deliveryBehavior" },
} satisfies Record<Field, { label: string; key: keyof PracticeDefinition }>;

const BASE_SOURCE = {
	EXACT_ADOPTION: "The exact version previously accepted",
	BUNDLED_DIGEST_MATCH: "A bundled version matched by its saved digest",
	BUNDLED_FINGERPRINT_MATCH: "A bundled version matched by its review fingerprint",
	CURRENT_DEFINITION: "The current definition; the original adopted version could not be proved",
} satisfies Record<PracticeReleaseProposal["baseSource"], string>;

function fieldText(definition: PracticeDefinition, field: Field): string {
	const value = definition[FIELDS[field].key];
	if (value === undefined) {
		return "Not set";
	}
	if (typeof value === "string") {
		return value.length > 0 ? value : "Not set";
	}
	return JSON.stringify(value, null, 2);
}

export interface PracticeReleaseReviewProps {
	proposal: PracticeReleaseProposal;
	pending: boolean;
	onAccept: (choices: Record<string, Choice>) => void;
	onDecline: () => void;
}

export function PracticeReleaseReview({
	proposal,
	pending,
	onAccept,
	onDecline,
}: PracticeReleaseReviewProps) {
	const id = useId();
	const [choices, setChoices] = useState(
		() =>
			new Map<Field, Choice>(
				proposal.fields
					.filter((field) => field.offeredChanged && !field.conflict)
					.map((field) => [field.field, "OFFERED"]),
			),
	);
	const ready = proposal.fields.every((field) => !field.offeredChanged || choices.has(field.field));
	const submit = () => {
		if (!ready) {
			return;
		}
		const selected: Record<string, Choice> = {};
		for (const [field, choice] of choices) {
			selected[field] = choice;
		}
		onAccept(selected);
	};

	return (
		<section className="space-y-5" aria-label={`Review update for ${proposal.slug}`}>
			<div className="space-y-1">
				<h2 className="text-lg font-semibold">{proposal.offered.name}</h2>
				<p className="text-sm text-muted-foreground">
					Compare each changed field. Your current practice stays in use until you accept this
					update.
				</p>
			</div>
			<Alert variant={proposal.baseSource === "CURRENT_DEFINITION" ? "warning" : "default"}>
				<AlertTitle>Comparison base</AlertTitle>
				<AlertDescription>{BASE_SOURCE[proposal.baseSource]}</AlertDescription>
			</Alert>
			<Table bordered className="min-w-220 table-fixed">
				<caption className="sr-only">
					Changed fields in the adopted, current, and offered versions
				</caption>
				<TableHeader>
					<TableRow variant="static">
						<TableHead className="w-38">Field</TableHead>
						<TableHead>Base</TableHead>
						<TableHead>Current</TableHead>
						<TableHead>Offered</TableHead>
						<TableHead className="w-42">Use</TableHead>
					</TableRow>
				</TableHeader>
				<TableBody>
					{proposal.fields.map(({ field, offeredChanged, conflict }) => (
						<TableRow key={field} variant="static">
							<TableHead scope="row" className="align-top font-medium">
								{FIELDS[field].label}
								{conflict && (
									<Badge variant="warning" className="ml-2">
										Conflict
									</Badge>
								)}
								{!offeredChanged && (
									<Badge variant="secondary" className="ml-2">
										Local edit
									</Badge>
								)}
							</TableHead>
							{([proposal.base, proposal.current, proposal.offered] as const).map(
								(definition, index) => (
									<TableCell key={index} className="align-top">
										<pre className="max-h-48 overflow-auto font-sans text-xs break-words whitespace-pre-wrap">
											{fieldText(definition, field)}
										</pre>
									</TableCell>
								),
							)}
							<TableCell className="align-top">
								{offeredChanged ? (
									<RadioGroup
										aria-label={`Use a version for ${FIELDS[field].label}`}
										value={choices.get(field) ?? ""}
										disabled={pending}
										onValueChange={(choice) => {
											if (choice === "CURRENT" || choice === "OFFERED") {
												setChoices((previous) => new Map(previous).set(field, choice));
											}
										}}
									>
										<label className="flex items-center gap-2" htmlFor={`${id}-${field}-current`}>
											<RadioGroupItem id={`${id}-${field}-current`} value="CURRENT" /> Current
										</label>
										<label className="flex items-center gap-2" htmlFor={`${id}-${field}-offered`}>
											<RadioGroupItem id={`${id}-${field}-offered`} value="OFFERED" /> Offered
										</label>
									</RadioGroup>
								) : (
									<span className="text-xs text-muted-foreground">Keep current</span>
								)}
							</TableCell>
						</TableRow>
					))}
				</TableBody>
			</Table>
			<div className="flex flex-wrap gap-2">
				<Button type="button" disabled={!ready || pending} onClick={submit}>
					{pending && <Spinner className="mr-1.5 size-4" />}
					Accept selected fields
				</Button>
				<Button type="button" variant="outline" disabled={pending} onClick={onDecline}>
					Decline this update
				</Button>
			</div>
			<p className="text-xs text-muted-foreground">
				Declining leaves this practice unchanged. A different catalogue version will be offered
				again.
			</p>
		</section>
	);
}
