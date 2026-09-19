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
	if (value === 0) {
		return "$0";
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

/** A published price keeps its decimals, because an admin checks it against a price list. */
export function formatRateUsd(value: number | undefined): string {
	return value == null ? "—" : USD_RATE.format(value);
}
