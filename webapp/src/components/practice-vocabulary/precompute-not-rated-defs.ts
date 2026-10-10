import type { PrecomputeModelUse, PrecomputeNotRated } from "@/api/types.gen";
import { statusValues } from "@/components/common/status-def";

export type PrecomputeNotRatedReason = PrecomputeNotRated["reason"];

interface PrecomputeNotRatedDef {
	/** The reason after its count, as in "5 too slow". */
	label: string;
	/** True when another model, or the assigned one checked again, is the way out. */
	modelFault: boolean;
}

/**
 * Why a model rated none of some places. Words only, with no badge: the review's Precompute scripts
 * section lists them one per line. A count here is model calls, never places, so no sentence may
 * read it as a number of places.
 */
export const PRECOMPUTE_NOT_RATED_DEFS = {
	UNAVAILABLE: { label: "model missing", modelFault: true },
	BUDGET: { label: "limit reached", modelFault: false },
	DEADLINE: { label: "too slow", modelFault: false },
	TOO_LARGE: { label: "too large", modelFault: false },
	OFF_FORMAT: { label: "wrong format", modelFault: true },
	REFUSED: { label: "declined", modelFault: true },
	ERROR: { label: "failed", modelFault: true },
} satisfies Record<PrecomputeNotRatedReason, PrecomputeNotRatedDef>;

const REASONS = statusValues(PRECOMPUTE_NOT_RATED_DEFS);

interface PrecomputeNotRatedSummary {
	/** Model calls that were not rated, across every model of the script. */
	count: number;
	/** Each reason with its count, in registry order, such as "5 too slow". Empty at 0. */
	reasons: string[];
	/** True when some reason is one no model change fixes, such as a spent limit or the deadline. */
	outsideTheModel: boolean;
}

/** What a script's models could not rate, summed by reason across its models. */
export function precomputeNotRated(models: PrecomputeModelUse[]): PrecomputeNotRatedSummary {
	const byReason = new Map<PrecomputeNotRatedReason, number>();
	for (const entry of models.flatMap((model) => model.notRated)) {
		if (entry.count > 0) {
			byReason.set(entry.reason, (byReason.get(entry.reason) ?? 0) + entry.count);
		}
	}
	const counted = REASONS.flatMap((reason) => {
		const count = byReason.get(reason);
		return count === undefined ? [] : [{ reason, count }];
	});
	return {
		count: counted.reduce((sum, { count }) => sum + count, 0),
		reasons: counted.map(
			({ reason, count }) => `${count} ${PRECOMPUTE_NOT_RATED_DEFS[reason].label}`,
		),
		outsideTheModel: counted.some(({ reason }) => !PRECOMPUTE_NOT_RATED_DEFS[reason].modelFault),
	};
}

/** The models whose unrated calls point at the model itself, in the order the run lists them. */
export function precomputeModelsToCheck(
	models: PrecomputeModelUse[],
): PrecomputeModelUse["purpose"][] {
	return models
		.filter((model) =>
			model.notRated.some(
				(entry) => entry.count > 0 && PRECOMPUTE_NOT_RATED_DEFS[entry.reason].modelFault,
			),
		)
		.map((model) => model.purpose);
}
