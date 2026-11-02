import { Link } from "@tanstack/react-router";

import { InlineLink } from "@/components/common/InlineLink";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldLabel,
	FieldLegend,
	FieldSet,
	FieldTitle,
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
	workspaceSlug?: string;
}

/** Whether this report may become a public GitHub issue. It loads with the first composer that asks. */
export function ProductFeedbackSharingField({
	id,
	status,
	share,
	onShareChange,
	workspaceSlug,
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
					<FieldDescription id={`${id}-share-detail`}>
						{SHARING_COPY.switchDetail} ·{" "}
						<InlineLink
							className="underline"
							render={
								<Link
									to="/settings"
									hash="product-feedback"
									search={{ chromeWorkspace: workspaceSlug }}
								/>
							}
						>
							{SHARING_COPY.changeDefault}
						</InlineLink>
					</FieldDescription>
				</FieldContent>
			</Field>
		);
	}
	const options = [
		{ value: "share", copy: SHARING_COPY.share },
		{ value: "private", copy: SHARING_COPY.keepPrivate },
	] as const;
	let picked: (typeof options)[number]["value"] | null = null;
	if (share !== undefined) {
		picked = share ? "share" : "private";
	}
	return (
		<FieldSet className="gap-2">
			<FieldLegend variant="label">{SHARING_COPY.question}</FieldLegend>
			<RadioGroup
				aria-label={SHARING_COPY.question}
				aria-describedby={`${id}-share-footnote`}
				value={picked}
				onValueChange={(value) => onShareChange(value === "share")}
				className="gap-2"
			>
				{options.map(({ value, copy }) => (
					<FieldLabel key={value} htmlFor={`${id}-share-${value}`}>
						<Field orientation="horizontal">
							<RadioGroupItem
								id={`${id}-share-${value}`}
								value={value}
								aria-labelledby={`${id}-share-${value}-title`}
								aria-describedby={`${id}-share-${value}-detail`}
							/>
							<FieldContent>
								<FieldTitle id={`${id}-share-${value}-title`}>{copy.title}</FieldTitle>
								<FieldDescription id={`${id}-share-${value}-detail`}>
									{copy.detail}
								</FieldDescription>
							</FieldContent>
						</Field>
					</FieldLabel>
				))}
			</RadioGroup>
			<FieldDescription id={`${id}-share-footnote`}>{SHARING_COPY.footnote}</FieldDescription>
		</FieldSet>
	);
}
