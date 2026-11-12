import { foldedReviewOptions } from "./bounded-text.ts";
import type { Candidate, ReviewOptions } from "./contract.ts";
import { registerDomainAccess } from "./domain-access.ts";
import { MAX_LOG_CHARS, type ProgramJob, type ReadRequest, type ReadValues } from "./ipc.ts";
import type { Evidence, PracticeScriptContext } from "./practice-script.ts";

export interface ProgramPort {
	read: <R extends ReadRequest>(request: R) => Promise<ReadValues[R["op"]]>;
	review: (options: ReviewOptions | undefined) => Promise<Candidate>;
	log: (text: string) => void;
	abortSignal: AbortSignal;
}

export function programContext(job: ProgramJob, port: ProgramPort): PracticeScriptContext {
	const evidence: Evidence = {
		record: async (name) => port.read({ op: "record", name }),
		citeRecord: async (name, startLine, endLine) =>
			port.read({
				op: "cite",
				source: "record",
				name,
				startLine,
				...(endLine === undefined ? {} : { endLine }),
			}),
	};
	const context: PracticeScriptContext = {
		...job.inputs,
		evidence,
		now: job.now,
		abortSignal: port.abortSignal,
		log: (text) => port.log(text.slice(0, MAX_LOG_CHARS)),
		review: Object.assign(
			async (options?: ReviewOptions): Promise<Candidate> =>
				port.review(foldedReviewOptions(options)),
			job.inputs.review,
		),
	};
	registerDomainAccess(context, job.files, port);
	return context;
}
