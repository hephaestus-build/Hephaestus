/**
 * The staging hold: freeze automatic promotion on the commit that staging has applied, run a measurement, and lift the
 * freeze afterwards, also when the measurement fails or is interrupted. The freeze is the documented control
 * (docs/admin/pull-based-deployment.mdx): a Staging promotion with `freeze`, lifted by a promotion without it.
 */

export type HoldSignal = "SIGINT" | "SIGTERM" | "SIGHUP";

export interface Measurement {
	/** Resolves with the exit code. */
	exited: Promise<number>;
	kill: (signal: HoldSignal) => void;
}

export interface HoldDeps {
	/** The commit that staging has applied. */
	applied: () => Promise<string>;
	/** Promotes `commit` to Staging with or without freeze, and resolves with the run's id once it succeeded. */
	promote: (commit: string, freeze: boolean) => Promise<string>;
	/** Starts the measurement with QUAL_STAGING_HOLD set to the frozen commit. */
	start: (frozen: string) => Measurement;
	/** Calls `handler` on SIGINT, SIGTERM and SIGHUP. */
	onSignal: (handler: (signal: HoldSignal) => void) => void;
	log: (line: string) => void;
}

/** Exit codes besides the measurement's own. */
export const FREEZE_NOT_LIFTED = 2;
export const STAGING_MOVED = 3;
export const INTERRUPTED = 130;

/** Holds staging for one measurement and returns the exit code: a lift failure wins, then a move, then the measurement's. */
export async function hold(deps: HoldDeps): Promise<number> {
	let signalled: HoldSignal | undefined;
	let measurement: Measurement | undefined;
	// Registered before the freeze: an interrupt while the freeze runs must still lift it.
	deps.onSignal((signal) => {
		signalled = signal;
		measurement?.kill(signal);
	});

	let pinned = await deps.applied();
	const freezeRun = await deps.promote(pinned, true);

	const lift = async (reason: string): Promise<boolean> => {
		try {
			deps.log(
				`staging-hold: freeze lifted (${reason}) by promotion run ${await deps.promote(pinned, false)}`,
			);
			return true;
		} catch (error) {
			// The freeze stays, and automatic promotion is off until someone lifts it: say so loudly.
			deps.log(
				`staging-hold: FREEZE NOT LIFTED (${reason}): ${error instanceof Error ? error.message : String(error)}. Lift it with a Staging promotion without freeze.`,
			);
			return false;
		}
	};
	const stillOn = async (): Promise<boolean> => {
		try {
			return (await deps.applied()) === pinned;
		} catch (error) {
			deps.log(
				`staging-hold: could not read staging's applied commit: ${error instanceof Error ? error.message : String(error)}`,
			);
			return false;
		}
	};

	const applied = await deps.applied();
	if (applied !== pinned) {
		pinned = applied;
		const lifted = await lift("staging moved while freezing");
		deps.log(
			"staging-hold: staging applied another commit while the freeze was set; nothing was measured",
		);
		return lifted ? STAGING_MOVED : FREEZE_NOT_LIFTED;
	}
	deps.log(`staging-hold: staging frozen on ${pinned} by promotion run ${freezeRun}`);

	let code = INTERRUPTED;
	if (signalled === undefined) {
		measurement = deps.start(pinned);
		code = await measurement.exited;
	}
	const moved = !(await stillOn());
	if (moved) {
		deps.log(
			`staging-hold: WARNING staging left ${pinned} during the measurement; its results are invalid`,
		);
	}
	const lifted = await lift(
		signalled === undefined ? `measurement exited ${code}` : `interrupted by ${signalled}`,
	);
	if (!lifted) {
		return FREEZE_NOT_LIFTED;
	}
	return moved ? STAGING_MOVED : code;
}
