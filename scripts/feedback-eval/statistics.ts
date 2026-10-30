export interface Count {
	group: string;
	successes: number;
	total: number;
}

export function rate(rows: Count[]) {
	for (const row of rows) {
		if (
			!Number.isInteger(row.successes) ||
			!Number.isInteger(row.total) ||
			row.successes < 0 ||
			row.total < row.successes
		) {
			throw new Error("Invalid evaluation counts");
		}
	}
	const successes = rows.reduce((sum, row) => sum + row.successes, 0);
	const total = rows.reduce((sum, row) => sum + row.total, 0);
	return {
		successes,
		total,
		workGroups: new Set(rows.filter((row) => row.total > 0).map((row) => row.group)).size,
		estimate: total === 0 ? null : successes / total,
		interval95: null,
	};
}

/** Wilson intervals apply to independent binary work-item outcomes, never correlated claims. */
export function workItemRate(outcomes: boolean[], infer: boolean) {
	const successes = outcomes.filter(Boolean).length;
	const total = outcomes.length;
	const estimate = total === 0 ? null : successes / total;
	if (!infer || estimate === null) {
		return {
			successes,
			total,
			estimate,
			interval95: null,
			uncertainty:
				"descriptive-only: missing or unsettled labels, selected claims, nonrandom sample, unknown or mixed arms, or unequal repeats",
		};
	}
	const z = 1.959963984540054;
	const denominator = 1 + (z * z) / total;
	const center = (estimate + (z * z) / (2 * total)) / denominator;
	const half =
		(z * Math.sqrt((estimate * (1 - estimate)) / total + (z * z) / (4 * total * total))) /
		denominator;
	return {
		successes,
		total,
		estimate,
		interval95: [
			successes === 0 ? 0 : Math.max(0, center - half),
			successes === total ? 1 : Math.min(1, center + half),
		],
		uncertainty:
			"Wilson marginal 95% interval for work items with any selected error across their recorded attempts; not claim precision; owner-attested sampling; failed captures excluded",
	};
}

export function agreement(pairs: [string, string][]) {
	if (pairs.length === 0) {
		return { pairs: 0, observedAgreement: null, kappa: null };
	}
	const categories = new Set(pairs.flat());
	const observed = pairs.filter(([left, right]) => left === right).length / pairs.length;
	let chance = 0;
	for (const category of categories) {
		chance +=
			((pairs.filter(([left]) => left === category).length / pairs.length) *
				pairs.filter(([, right]) => right === category).length) /
			pairs.length;
	}
	return {
		pairs: pairs.length,
		observedAgreement: observed,
		kappa: chance === 1 ? null : (observed - chance) / (1 - chance),
	};
}
