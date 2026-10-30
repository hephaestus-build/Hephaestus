/** The fields of a Rolldown output chunk that the entry check reads. */
export interface BuiltChunk {
	fileName: string;
	isEntry: boolean;
	/** Chunks this one imports statically: the browser fetches them before the chunk runs. */
	imports: string[];
	moduleIds: string[];
	bytes: number;
}

export interface EntryClosure {
	chunks: number;
	bytes: number;
	packages: string[];
}

/** The package that a module id belongs to, or `undefined` for application source. */
export function packageOf(moduleId: string): string | undefined {
	const match = /[/\\]node_modules[/\\](?<name>@[^/\\]+[/\\][^/\\]+|[^/\\@][^/\\]*)[/\\]/u.exec(
		moduleId.slice(moduleId.lastIndexOf("node_modules") - 1),
	);
	return match?.groups?.name?.replace("\\", "/");
}

/**
 * What a cold load of any page fetches and runs before the router can render: the entry chunk and
 * every chunk it imports statically, transitively. A dynamic import is a route or a lazy component.
 */
export function entryClosure(chunks: BuiltChunk[]): EntryClosure {
	const byName = new Map(chunks.map((chunk) => [chunk.fileName, chunk]));
	const entries = chunks.filter((chunk) => chunk.isEntry);
	if (entries.length !== 1) {
		throw new Error(`Expected one entry chunk, found ${entries.length}`);
	}
	const seen = new Set<string>();
	const pending = entries.map((chunk) => chunk.fileName);
	for (let name = pending.pop(); name !== undefined; name = pending.pop()) {
		if (seen.has(name)) {
			continue;
		}
		seen.add(name);
		pending.push(...(byName.get(name)?.imports ?? []));
	}
	const closure = [...seen].flatMap((name) => byName.get(name) ?? []);
	const packages = new Set(
		closure.flatMap((chunk) => chunk.moduleIds.flatMap((id) => packageOf(id) ?? [])),
	);
	return {
		chunks: closure.length,
		bytes: closure.reduce((sum, chunk) => sum + chunk.bytes, 0),
		packages: [...packages].sort(),
	};
}

export interface EntryBudget {
	/** The most bytes the closure may have. */
	ceiling: number;
	/** How far below the ceiling the closure may fall before the ceiling must come down with it. */
	slack: number;
	/** Packages that only some routes use. Each belongs in that route's chunk. */
	routeOnlyPackages: string[];
}

/** The reasons the closure breaks the budget; empty when it keeps it. */
export function budgetViolations(closure: EntryClosure, budget: EntryBudget): string[] {
	const violations: string[] = [];
	if (closure.bytes > budget.ceiling) {
		violations.push(
			`The entry closure is ${closure.bytes} bytes, over its ceiling of ${budget.ceiling}. ` +
				"Find the route option (validateSearch, beforeLoad, loader, pendingComponent) that imports a component module.",
		);
	}
	if (closure.bytes < budget.ceiling - budget.slack) {
		violations.push(
			`The entry closure is ${closure.bytes} bytes, more than ${budget.slack} under its ceiling of ` +
				`${budget.ceiling}. Lower the ceiling to ${closure.bytes}, so the saving stays.`,
		);
	}
	for (const name of budget.routeOnlyPackages.filter((pkg) => closure.packages.includes(pkg))) {
		violations.push(`${name} is in the entry closure; only the routes that use it may load it.`);
	}
	return violations;
}
