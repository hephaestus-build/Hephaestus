import type { PracticeRepository } from "@/api/types.gen";
import { isRecord } from "@/lib/is-record";

/** The directory the server reads when a request names none. */
export const DEFAULT_PRACTICE_DIRECTORY = ".hephaestus/practices";

/**
 * The server's rule for a directory (`SetPracticeRepositoryRequestDTO`): a path relative to the
 * repository root, of `[A-Za-z0-9._-]` segments, with no leading or trailing slash and no `.`, `..`
 * or `.git` segment.
 */
const DIRECTORY_PATTERN =
	/^(?!\/)(?!.*(?:^|\/)(?:\.\.?|\.git)(?:\/|$))[A-Za-z0-9._-]+(?:\/[A-Za-z0-9._-]+)*$/u;
const DIRECTORY_MAX_LENGTH = 255;

/** Whether the server accepts `directory`, already trimmed, as the directory of the folders. */
export function isPracticeDirectory(directory: string): boolean {
	return directory.length <= DIRECTORY_MAX_LENGTH && DIRECTORY_PATTERN.test(directory);
}

/**
 * Whether a read of the setting names a practice repository. A workspace without one answers 204,
 * which the client hands over as `null`, `undefined` or `{}`, so every read goes through here.
 */
export function isPracticeRepository(value: unknown): value is PracticeRepository {
	return (
		isRecord(value) && typeof value.repositoryItemId === "string" && value.repositoryItemId !== ""
	);
}

/** One reason why a practice's folder cannot become a practice, as the server states it. */
export interface PracticeFolderProblem {
	/** The file, relative to the practice's folder, such as `practice.md`. */
	file: string;
	line?: number;
	column?: number;
	message: string;
}

const positionOf = (value: unknown) =>
	typeof value === "number" && Number.isInteger(value) && value > 0 ? value : undefined;

/**
 * The problems a refused Move to repository lists for the folder (a 422 body). Each entry is read
 * on its own, so one that this build cannot read does not hide the others.
 */
export function practiceFolderProblemsOf(error: unknown): PracticeFolderProblem[] {
	if (!isRecord(error) || !Array.isArray(error.problems)) {
		return [];
	}
	return error.problems.flatMap((problem: unknown) => {
		if (!isRecord(problem) || typeof problem.message !== "string") {
			return [];
		}
		return [
			{
				file: typeof problem.file === "string" ? problem.file : "",
				line: positionOf(problem.line),
				column: positionOf(problem.column),
				message: problem.message,
			},
		];
	});
}

/** What a 400 says about the directory, from the field errors of its body, when it names one. */
export function directoryErrorOf(error: unknown): string | undefined {
	if (!isRecord(error) || !isRecord(error.errors)) {
		return undefined;
	}
	const messages = error.errors.directory;
	if (!Array.isArray(messages)) {
		return undefined;
	}
	const [message] = messages.filter(
		(entry: unknown): entry is string => typeof entry === "string" && entry !== "",
	);
	return message === undefined ? undefined : `The directory ${message.replace(/\.$/u, "")}.`;
}
