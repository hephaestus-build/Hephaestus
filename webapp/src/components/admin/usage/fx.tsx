import type { FxRateInfo } from "@/api/types.gen";
import { asDate, type DateLike } from "@/lib/dates";

/**
 * Display-only conversion for the AI-usage surfaces. USD stays authoritative — spend is metered,
 * capped and enforced in USD — so a converted figure is only ever an estimate beside it:
 * `$4.50 (≈ €3.96)`. Absent `fx` is the norm, not an error.
 */

/** Below this, `formatCostUsd` renders the bound `<$0.01`, which is not a figure to convert. */
const SUB_CENT_USD = 0.005;

const SPEND_DIGITS = 2;
/** Whole units: `€43.95` beside a round `$50` claims a precision the estimate does not have. */
const CAP_DIGITS = 0;
const RATE_DIGITS = 3;

/** `source` is wider than the generated literal: a newer server can name a publisher this build has never heard of. */
export type Fx = (Omit<FxRateInfo, "source"> & { source: string }) | null | undefined;

type CurrencyDisplay = "symbol" | "code" | "name";

/** A cached `null` is a currency code the platform rejected, so `undefined` means "not tried yet". */
const formatters = new Map<string, Intl.NumberFormat | null>();

/** `en-US`, not the viewer's locale: `de-DE`'s `3,96 €` beside `$4.50` is two number grammars. */
function formatter(
	currencyCode: string,
	digits: number,
	currencyDisplay: CurrencyDisplay,
): Intl.NumberFormat | null {
	const key = `${currencyCode}|${digits}|${currencyDisplay}`;
	const cached = formatters.get(key);
	if (cached !== undefined) {
		return cached;
	}
	let built: Intl.NumberFormat | null;
	try {
		built = new Intl.NumberFormat("en-US", {
			style: "currency",
			currency: currencyCode,
			currencyDisplay,
			minimumFractionDigits: digits,
			maximumFractionDigits: digits,
		});
	} catch {
		// An unknown ISO code throws. Presentation, not accounting — fall back to USD alone.
		built = null;
	}
	formatters.set(key, built);
	return built;
}

const usesIsoCode = new Map<string, boolean>();

/** A `$`-bearing symbol beside the USD figure would read as dollars: `$50 (≈ CAD 44)` instead. */
function displayFor(currencyCode: string): CurrencyDisplay {
	let ambiguous = usesIsoCode.get(currencyCode);
	if (ambiguous === undefined) {
		const symbol =
			formatter(currencyCode, 2, "symbol")
				?.formatToParts(1)
				.find((part) => part.type === "currency")?.value ?? "";
		ambiguous = currencyCode !== "USD" && symbol.includes("$");
		usesIsoCode.set(currencyCode, ambiguous);
	}
	return ambiguous ? "code" : "symbol";
}

/**
 * Below one unit of the requested precision there is no figure rather than a misleading one: at
 * {@link CAP_DIGITS} a $0.40 cap would round to `≈ €0`, which is free.
 */
function amountIn(
	usd: number | null | undefined,
	fx: Fx,
	digits: number,
): { written: string; spoken: string } | null {
	if (fx == null || usd == null || !Number.isFinite(usd) || usd < SUB_CENT_USD) {
		return null;
	}
	if (!Number.isFinite(fx.ratePerUsd) || fx.ratePerUsd <= 0) {
		return null;
	}
	const value = usd * fx.ratePerUsd;
	if (value < 10 ** -digits) {
		return null;
	}
	const written = formatter(fx.currencyCode, digits, displayFor(fx.currencyCode))?.format(value);
	const spoken = formatter(fx.currencyCode, digits, "name")?.format(value);
	return written != null && spoken != null ? { written, spoken } : null;
}

export interface FxConversion {
	/** As it renders, without surrounding punctuation: `≈ €3.96`. */
	text: string;
	/** As it is announced: `approximately 3.96 euros`. */
	label: string;
}

function conversionOf(amount: { written: string; spoken: string } | null): FxConversion | null {
	return amount == null
		? null
		: { text: `≈ ${amount.written}`, label: `approximately ${amount.spoken}` };
}

export function spendConversion(usd: number | null | undefined, fx: Fx): FxConversion | null {
	return conversionOf(amountIn(usd, fx, SPEND_DIGITS));
}

export function capConversion(usd: number | null | undefined, fx: Fx): FxConversion | null {
	return conversionOf(amountIn(usd, fx, CAP_DIGITS));
}

/** `≈ €38.59 of €44` — all or nothing, because half a parenthetical reads as a mistake. */
export function spendOfCapConversion(
	spendUsd: number | null | undefined,
	capUsd: number | null | undefined,
	fx: Fx,
): FxConversion | null {
	const spend = amountIn(spendUsd, fx, SPEND_DIGITS);
	const cap = amountIn(capUsd, fx, CAP_DIGITS);
	if (spend == null || cap == null) {
		return null;
	}
	return {
		text: `≈ ${spend.written} of ${cap.written}`,
		label: `approximately ${spend.spoken} of ${cap.spoken}`,
	};
}

function formatRateDate(value: DateLike): string {
	const date = asDate(value);
	if (!date) {
		return "–";
	}
	return date.toLocaleDateString(undefined, {
		month: "short",
		day: "numeric",
		year: "numeric",
		timeZone: "UTC",
	});
}

const SOURCE_NAMES: Record<string, string | undefined> = {
	ECB: "European Central Bank",
} satisfies Record<FxRateInfo["source"], string>;

/** A newer server can send a source this spec does not know; unattributed beats the wrong bank. */
function publisherOf(source: string): string | undefined {
	return SOURCE_NAMES[source];
}

interface DisclosureParts {
	lead: string;
	/** The rate itself: `1 USD ≈ €0.879`. */
	rate: FxConversion;
	tail: string;
}

function disclosureParts(fx: Fx, isCurrentMonth: boolean): DisclosureParts | null {
	if (fx == null) {
		return null;
	}
	const written = formatter(fx.currencyCode, RATE_DIGITS, displayFor(fx.currencyCode))?.format(
		fx.ratePerUsd,
	);
	const spoken = formatter(fx.currencyCode, RATE_DIGITS, "name")?.format(fx.ratePerUsd);
	if (written == null || spoken == null) {
		return null;
	}
	const publisher = publisherOf(fx.source);
	const attribution = publisher == null ? "" : `${publisher} `;
	return {
		lead: `${fx.currencyCode} amounts are estimates at the ${attribution}reference rate published on ${formatRateDate(fx.rateDate)}`,
		rate: {
			text: `1 USD ≈ ${written}`,
			label: `1 US dollar is approximately ${spoken}`,
		},
		tail: isCurrentMonth
			? ". Spend is metered and enforced in USD."
			: ". That is the last rate published in the month shown, so these figures no longer change.",
	};
}

export interface FxCapHint {
	conversion: FxConversion;
	/** The rest of the sentence, leading space included. */
	tail: string;
}

/** Re-checks `isCurrentMonth` rather than trusting the caller: an open dialog outlives a month step. */
export function fxCapHint(
	valueUsd: number | null | undefined,
	fx: Fx,
	isCurrentMonth: boolean,
): FxCapHint | null {
	const conversion = capConversion(valueUsd, fx);
	if (conversion == null || !isCurrentMonth) {
		return null;
	}
	return {
		conversion,
		tail: " at today's rate.",
	};
}

export interface FxApproxProps {
	conversion: FxConversion;
}

/**
 * `role="img"` is load-bearing: a bare `<span>`'s `aria-label` is dropped, and `≈` read literally is
 * announced as "tilde operator".
 */
export function FxApprox({ conversion }: FxApproxProps) {
	return (
		<span role="img" aria-label={conversion.label} className="whitespace-nowrap">
			{conversion.text}
		</span>
	);
}

export interface FxAmountProps {
	conversion: FxConversion | null;
}

/** The parenthetical that trails a USD figure: ` (≈ €3.96)`, separating space included. */
export function FxAmount({ conversion }: FxAmountProps) {
	if (conversion == null) {
		return null;
	}
	return (
		<>
			{" "}
			<span role="img" aria-label={conversion.label} className="whitespace-nowrap">
				({conversion.text})
			</span>
		</>
	);
}

export interface FxSpendProps {
	usd: number | null | undefined;
	fx: Fx;
}

/** Its own line, never an inline suffix: a variable-width suffix breaks the money column's right edge. */
export function FxSpendLine({ usd, fx }: FxSpendProps) {
	const conversion = spendConversion(usd, fx);
	if (conversion == null) {
		return null;
	}
	return (
		<div className="text-xs font-normal text-muted-foreground tabular-nums">
			<FxApprox conversion={conversion} />
		</div>
	);
}

export interface FxDisclosureProps {
	fx: Fx;
	isCurrentMonth: boolean;
}

export function FxDisclosure({ fx, isCurrentMonth }: FxDisclosureProps) {
	const parts = disclosureParts(fx, isCurrentMonth);
	if (parts == null) {
		return null;
	}
	return (
		<p className="text-sm text-muted-foreground">
			{parts.lead}
			{" ("}
			<FxApprox conversion={parts.rate} />){parts.tail}
		</p>
	);
}
