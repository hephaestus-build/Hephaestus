import {
	Field,
	FieldContent,
	FieldDescription,
	FieldError,
	FieldLabel,
	FieldLegend,
	FieldSet,
} from "@/components/ui/field";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Switch } from "@/components/ui/switch";

import { SHARING_COPY } from "./sharing-copy";

export interface ProductFeedbackSharingFieldProps {
	id: string;
	/** `ask` offers the two options with neither picked; `remembered` offers one switch from the choice. */
	status: "ask" | "remembered";
	share: boolean | undefined;
	onShareChange: (share: boolean) => void;
	invalid?: boolean;
}

/** Whether this report may become a public GitHub issue. It loads with the first composer that asks. */
export function ProductFeedbackSharingField({
	id,
	status,
	share,
	onShareChange,
	invalid = false,
}: ProductFeedbackSharingFieldProps) {
	if (status === "remembered") {
		return (
			<Field orientation="horizontal">
				<Switch
					id={`${id}-share`}
					checked={share === true}
					onCheckedChange={onShareChange}
					aria-describedby={`${id}-share-detail`}
				/>
				<FieldContent>
					<FieldLabel htmlFor={`${id}-share`}>{SHARING_COPY.switchLabel}</FieldLabel>
					<FieldDescription id={`${id}-share-detail`}>{SHARING_COPY.switchDetail}</FieldDescription>
				</FieldContent>
			</Field>
		);
	}
	const options = [
		{ value: "private", title: SHARING_COPY.keepPrivate.title },
		{ value: "share", title: SHARING_COPY.share.title },
	] as const;
	let picked: (typeof options)[number]["value"] | null = null;
	if (share !== undefined) {
		picked = share ? "share" : "private";
	}
	return (
		<FieldSet className="gap-3">
			<FieldLegend variant="label">{SHARING_COPY.question}</FieldLegend>
			<FieldDescription id={`${id}-share-detail`}>
				AI drafts the issue either way. Public issues are visible to anyone. Personal details can be
				missed, and public copies can remain.
			</FieldDescription>
			<RadioGroup
				aria-label={SHARING_COPY.question}
				aria-describedby={`${id}-share-detail ${id}-share-footnote${invalid ? ` ${id}-share-error` : ""}`}
				aria-invalid={invalid || undefined}
				value={picked}
				onValueChange={(value) => onShareChange(value === "share")}
				className="flex flex-wrap gap-x-5 gap-y-2"
			>
				{options.map(({ value, title }) => (
					<Field key={value} orientation="horizontal" className="w-auto">
						<RadioGroupItem id={`${id}-share-${value}`} value={value} />
						<FieldLabel htmlFor={`${id}-share-${value}`} className="min-h-8">
							{title}
						</FieldLabel>
					</Field>
				))}
			</RadioGroup>
			<FieldDescription id={`${id}-share-footnote`}>{SHARING_COPY.footnote}</FieldDescription>
			{invalid && (
				<FieldError id={`${id}-share-error`}>
					Choose whether your report can become public.
				</FieldError>
			)}
		</FieldSet>
	);
}
