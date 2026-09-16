import {
	Field,
	FieldDescription,
	FieldError,
	FieldGroup,
	FieldLabel,
	FieldLegend,
	FieldSet,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { type PricingMode, priceLabel } from "@/lib/llm-pricing";
import { hasText } from "@/lib/text";

export interface PriceModeValue {
	pricingMode: PricingMode;
	per1mInputUsd?: number;
	per1mOutputUsd?: number;
	per1mCacheReadUsd?: number;
	per1mCacheWriteUsd?: number;
	note?: string;
}

export interface PriceModeEditorProps {
	audience: "instance" | "workspace";
	value: PriceModeValue;
	onChange: (value: PriceModeValue) => void;
	errors?: { per1mInputUsd?: string; per1mOutputUsd?: string; note?: string };
	idPrefix: string;
}

/** The "no rate" options are worded by {@link priceLabel}, so the radio and the label the tables
 * print cannot drift apart. */
export function PriceModeEditor({
	audience,
	value,
	onChange,
	errors,
	idPrefix,
}: PriceModeEditorProps) {
	const set = <K extends keyof PriceModeValue>(key: K, next: PriceModeValue[K]) => {
		onChange({ ...value, [key]: next });
	};

	const noChargeLabel = priceLabel({ pricingMode: "NO_CHARGE" }, audience);
	const unpricedLabel = priceLabel({ pricingMode: "UNPRICED" }, audience);

	const numberField = (
		key: "per1mInputUsd" | "per1mOutputUsd" | "per1mCacheReadUsd" | "per1mCacheWriteUsd",
		label: string,
		required: boolean,
	) => {
		const id = `${idPrefix}-${key}`;
		const errorId = `${id}-error`;
		const error =
			key === "per1mInputUsd"
				? errors?.per1mInputUsd
				: key === "per1mOutputUsd"
					? errors?.per1mOutputUsd
					: undefined;
		return (
			<Field data-invalid={Boolean(error)} key={key}>
				<FieldLabel htmlFor={id}>
					{label}
					{required && (
						<span className="text-destructive" aria-hidden="true">
							{" *"}
						</span>
					)}
				</FieldLabel>
				<Input
					id={id}
					type="number"
					min={0}
					step="0.01"
					inputMode="decimal"
					value={value[key] == null ? "" : String(value[key])}
					onChange={(e) => {
						const raw = e.target.value;
						set(key, raw === "" ? undefined : Number(raw));
					}}
					aria-required={required}
					aria-invalid={Boolean(error)}
					aria-describedby={hasText(error) ? errorId : undefined}
				/>
				{hasText(error) && <FieldError id={errorId}>{error}</FieldError>}
			</Field>
		);
	};

	const modes: { value: PricingMode; id: string; label: string }[] = [
		{ value: "PRICED", id: `${idPrefix}-mode-priced`, label: "Price per 1M tokens" },
		{ value: "NO_CHARGE", id: `${idPrefix}-mode-no-charge`, label: noChargeLabel },
		{ value: "UNPRICED", id: `${idPrefix}-mode-unpriced`, label: unpricedLabel },
	];

	return (
		<FieldSet>
			<FieldLegend variant="label">Price</FieldLegend>
			<FieldDescription>
				Enter the rate billed by the provider. OpenAI-compatible usage responses report tokens, not
				your account's dollar charge.
			</FieldDescription>
			<RadioGroup
				value={value.pricingMode}
				onValueChange={(next) => set("pricingMode", next)}
				aria-label="Price"
			>
				{modes.map((mode) => (
					<Field key={mode.value} orientation="horizontal">
						<RadioGroupItem value={mode.value} id={mode.id} />
						<FieldLabel htmlFor={mode.id} className="font-normal">
							{mode.label}
						</FieldLabel>
					</Field>
				))}
			</RadioGroup>

			{value.pricingMode === "PRICED" && (
				<FieldGroup className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
					{numberField("per1mInputUsd", "Input (USD)", true)}
					{numberField("per1mOutputUsd", "Output (USD)", true)}
					{numberField("per1mCacheReadUsd", "Cache read (USD)", false)}
					{numberField("per1mCacheWriteUsd", "Cache write (USD)", false)}
					<FieldDescription className="sm:col-span-2">
						Reasoning tokens are included in output tokens and are not priced a second time.
					</FieldDescription>
				</FieldGroup>
			)}

			{value.pricingMode === "NO_CHARGE" && (
				<Field data-invalid={Boolean(errors?.note)} className="mt-3">
					<FieldLabel htmlFor={`${idPrefix}-note`}>
						Note
						<span className="text-destructive" aria-hidden="true">
							{" *"}
						</span>
					</FieldLabel>
					<Input
						id={`${idPrefix}-note`}
						value={value.note ?? ""}
						onChange={(e) => set("note", e.target.value)}
						placeholder="e.g. internal endpoint; infrastructure billed separately"
						aria-required="true"
						aria-invalid={Boolean(errors?.note)}
						aria-describedby={`${idPrefix}-note-hint${hasText(errors?.note) ? ` ${idPrefix}-note-error` : ""}`}
					/>
					<FieldDescription id={`${idPrefix}-note-hint`}>
						Explain why no per-token API rate applies. Infrastructure cost may still apply.
					</FieldDescription>
					{hasText(errors?.note) && (
						<FieldError id={`${idPrefix}-note-error`}>{errors.note}</FieldError>
					)}
				</Field>
			)}
		</FieldSet>
	);
}
