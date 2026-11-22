/** How a chart upgrade changes the bundled PostgreSQL's Pod template, and whether that restart is one a release intends. */
import { isDeepStrictEqual } from "node:util";

import { parseAllDocuments } from "yaml";

import { isRecord } from "./json.ts";

/** The Pod template of the bundled PostgreSQL in a rendered chart. */
function postgresqlTemplate(rendered: string): unknown {
	for (const document of parseAllDocuments(rendered)) {
		const resource: unknown = document.toJS();
		if (
			isRecord(resource) &&
			resource.kind === "StatefulSet" &&
			isRecord(resource.metadata) &&
			isRecord(resource.metadata.labels) &&
			resource.metadata.labels["app.kubernetes.io/component"] === "postgresql" &&
			isRecord(resource.spec)
		) {
			return resource.spec.template;
		}
	}
	throw new Error("The chart rendered no PostgreSQL StatefulSet");
}

/** The names of a list of named items, such as containers or env, or undefined for any other list. */
function namesOf(items: unknown[]): string[] | undefined {
	const names: string[] = [];
	for (const item of items) {
		if (!isRecord(item) || typeof item.name !== "string") {
			return undefined;
		}
		names.push(item.name);
	}
	return new Set(names).size === names.length ? names : undefined;
}

/** The paths at which two values differ. Lists of named items, such as containers, compare item by item. */
function differences(left: unknown, right: unknown, at: string): string[] {
	if (isDeepStrictEqual(left, right)) {
		return [];
	}
	// Lists of uniquely named items in the same order compare item by item. A changed order or a repeated name, which
	// matters for env expansion, is a change of the whole list.
	const leftNames = Array.isArray(left) ? namesOf(left) : undefined;
	const rightNames = Array.isArray(right) ? namesOf(right) : undefined;
	if (
		Array.isArray(left) &&
		Array.isArray(right) &&
		leftNames !== undefined &&
		rightNames !== undefined &&
		isDeepStrictEqual(leftNames, rightNames)
	) {
		return leftNames.flatMap((name, index) =>
			differences(left[index], right[index], `${at}[${name}]`),
		);
	}
	if (isRecord(left) && isRecord(right)) {
		const keys = new Set([...Object.keys(left), ...Object.keys(right)]);
		return [...keys].flatMap((key) => differences(left[key], right[key], `${at}.${key}`));
	}
	return [at];
}

/**
 * What the new chart changes in PostgreSQL's Pod template: a container's image or arguments, which restart PostgreSQL
 * as the release intends, or a container's memory request or limit. The chart derives the memory from the PostgreSQL
 * settings (max_connections and the per-connection figure), so a release that changes that derivation changes it once,
 * and its upgrade notes announce the restart. Any other change fails, because a template that changes on every release
 * would cut the database on each upgrade.
 */
export function changedPostgresql(previous: string, next: string): string[] {
	const changed = differences(postgresqlTemplate(previous), postgresqlTemplate(next), "");
	const unexpected = changed.filter(
		(path) =>
			!/^\.spec\.(?:initContainers|containers)\[[^\]]+\]\.(?:image|args)$/u.test(path) &&
			!/^\.spec\.containers\[[^\]]+\]\.resources\.(?:requests|limits)\.memory$/u.test(path),
	);
	if (unexpected.length > 0) {
		throw new Error(
			`The new chart changes PostgreSQL's Pod template beyond its image, arguments and memory: ${unexpected.join(", ")}`,
		);
	}
	return changed;
}
