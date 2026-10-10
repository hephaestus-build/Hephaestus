/**
 * **Format only, never sum.** Totals, remaining budget and cap verdicts are exact decimals computed
 * server-side and shipped as their own fields; re-deriving one from binary64 rows here can only
 * disagree with the figure printed beside it.
 */
const USD = new Intl.NumberFormat("en-US", {
	style: "currency",
	currency: "USD",
	minimumFractionDigits: 2,
	maximumFractionDigits: 2,
});

const USD_WHOLE = new Intl.NumberFormat("en-US", {
	style: "currency",
	currency: "USD",
	minimumFractionDigits: 0,
	maximumFractionDigits: 0,
});

const USD_RATE = new Intl.NumberFormat("en-US", {
	style: "currency",
	currency: "USD",
	minimumFractionDigits: 2,
	maximumFractionDigits: 4,
});

export function formatCostUsd(value: number | undefined): string {
	if (value == null) {
		return "—";
	}
	if (value > 0 && value < 0.005) {
		return "<$0.01";
	}
	return USD.format(value);
}

/** A cap someone typed, rendered the way they typed it: `$50`, not `$50.00`. */
export function formatCapUsd(value: number | undefined): string {
	if (value == null) {
		return "—";
	}
	// Two formatters, not one with `maximumFractionDigits: 2`: that one emits "$49.5" for a
	// half-dollar cap, and a single decimal reads as a typo in a column of money.
	return Number.isInteger(value) ? USD_WHOLE.format(value) : USD.format(value);
}

/** The decimals an average spend can show. */
export type AverageDigits = 2 | 3 | 4;

function usdWith(fractionDigits: AverageDigits): Intl.NumberFormat {
	return new Intl.NumberFormat("en-US", {
		style: "currency",
		currency: "USD",
		minimumFractionDigits: fractionDigits,
		maximumFractionDigits: fractionDigits,
	});
}

const USD_AVERAGE: Record<AverageDigits, Intl.NumberFormat> = {
	2: USD,
	3: usdWith(3),
	4: usdWith(4),
};

/** Cents from a cent and for nothing, then three decimals, then four. */
function averageDigitsOf(value: number): AverageDigits {
	if (value === 0 || value >= 0.01) {
		return 2;
	}
	return value >= 0.001 ? 3 : 4;
}

/**
 * The decimals of every average in one column, its total included: the most that any of them needs,
 * so that the decimal points line up. `null` is a cell with nothing to average.
 */
export function averageFractionDigits(values: readonly (number | null)[]): AverageDigits {
	let most: AverageDigits = 2;
	for (const value of values) {
		const digits = value === null ? 2 : averageDigitsOf(value);
		if (digits > most) {
			most = digits;
		}
	}
	return most;
}

/**
 * An average spend, with the decimals of its column ({@link averageFractionDigits}). Unit costs below
 * a cent are the point of an average, so it never collapses them to "<$0.01" as a total does. A real
 * cost too small for the decimals reads "<$0.0001", never "$0.0000".
 */
export function formatAverageUsd(value: number, fractionDigits: AverageDigits): string {
	const format = USD_AVERAGE[fractionDigits];
	const smallest = 10 ** -fractionDigits;
	if (value > 0 && value < smallest / 2) {
		return `<${format.format(smallest)}`;
	}
	return format.format(value);
}

/** A published price keeps its decimals, because an admin checks it against a price list. */
export function formatRateUsd(value: number | undefined): string {
	return value == null ? "—" : USD_RATE.format(value);
}
