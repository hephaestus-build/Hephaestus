import { spawnSync } from "node:child_process";
import type { Dirent } from "node:fs";
import { appendFile, readdir, readFile, realpath } from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

import type { DescriptionDictionary } from "@actions/expressions";
import type { ActionsMetadataProvider } from "@actions/languageservice";
import type { ActionTemplate } from "@actions/workflow-parser/actions/action-template";
import type { Step } from "@actions/workflow-parser/model/workflow-template";
import type { MappingToken } from "@actions/workflow-parser/templates/tokens/mapping-token";
import type { TemplateToken } from "@actions/workflow-parser/templates/tokens/template-token";
import { TextDocument } from "vscode-languageserver-textdocument";

import { type ActionsLanguage, openActionsLanguage } from "./lib/actions-language.ts";
import { asArray, asRecord, isRecord, parseJson } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

/** Actionlint passes these to ShellCheck too: each misreads a masked expression or a runner variable. */
const SHELLCHECK_EXCLUDED = "SC1091,SC2194,SC2050,SC2154,SC2157";

/** The `merge_group` webhook payload: https://docs.github.com/en/webhooks/webhook-events-and-payloads#merge_group */
const MERGE_GROUP = ["head_sha", "head_ref", "base_sha", "base_ref"];
const COMMIT = ["id", "tree_id", "message", "timestamp"];

/** `owner/repository[/path]@commit`: the only remote reference whose metadata is read. */
const PIN =
	/^(?<owner>[\w.-]+)\/(?<repository>[\w.-]+)(?<subpath>(?:\/[\w.-]+)*)@(?<ref>[\da-f]{40})$/u;
const METADATA_LIMIT = 1024 * 1024;

export interface WorkflowCheck {
	problems: string[];
	/** Workflows with no native background or parallel step, which Actionlint can still read. */
	compatible: string[];
}

function isNative(step: Step): boolean {
	return (
		"parallel" in step ||
		"wait" in step ||
		"wait-all" in step ||
		"cancel" in step ||
		("background" in step && step.background === true)
	);
}

/**
 * A run script as the shell receives it, each expression replaced by underscores across its own
 * source span, or `undefined` when that cannot be done exactly. The parser's expressions delimit
 * each span, so a `}}` inside a quoted string does not end one.
 */
export function runScript(language: ActionsLanguage, run: TemplateToken): string | undefined {
	const { isBasicExpression, isString } = language.parser;
	if (isString(run)) {
		return run.value;
	}
	if (!isBasicExpression(run) || run.source === undefined || run.range === undefined) {
		return undefined;
	}
	const masked = maskSource(run.source, run.originalExpressions ?? [run]);
	// A one-line `format()` transform holds the decoded value. Every other source is the YAML as
	// written: quoted, or a block scalar's indented lines without its header.
	if (
		masked === undefined ||
		(run.originalExpressions !== undefined && run.range.start.line === run.range.end.line)
	) {
		return masked;
	}
	// The scalar is decoded alone, at its own column after `run: `, by GitHub's own YAML reader.
	const indent = run.range.start.column - "run: ".length - 1;
	if (indent < 0) {
		return undefined;
	}
	const header = run.blockScalarHeader === undefined ? "" : `${run.blockScalarHeader}\n`;
	const reader = new language.yamlReader.YamlObjectReader(
		undefined,
		`${" ".repeat(indent)}run: ${header}${masked}\n`,
	);
	reader.validateStart();
	const value =
		reader.allowMappingStart() === undefined
			? undefined
			: (reader.allowLiteral(), reader.allowLiteral());
	return reader.errors.length === 0 && value !== undefined && isString(value)
		? value.value
		: undefined;
}

function maskSource(
	source: string,
	expressions: readonly { expression: string }[],
): string | undefined {
	let masked = "";
	let index = 0;
	for (const expression of expressions) {
		const open = source.indexOf("${{", index);
		const body = open === -1 ? -1 : source.indexOf(expression.expression, open + 3);
		const close = body === -1 ? -1 : source.indexOf("}}", body + expression.expression.length);
		if (close === -1) {
			return undefined;
		}
		masked += source.slice(index, open) + source.slice(open, close + 2).replaceAll(/[^\n]/gu, "_");
		index = close + 2;
	}
	return masked + source.slice(index);
}

function inside(base: string, target: string): boolean {
	const relative = path.relative(base, target);
	return relative !== "" && !relative.startsWith("..") && !path.isAbsolute(relative);
}

function interpreter(shell: string): "bash" | "sh" | "python" | undefined {
	const program = path.basename(shell.trim().split(/\s+/u)[0] ?? "");
	if (program === "bash" || program === "sh") {
		return program;
	}
	return program === "python" || program === "python3" ? "python" : undefined;
}

/** Problems a script checker reports for one script, or an error when the checker is absent. */
function checkScript(where: string, shell: string, script: string): string[] {
	const kind = interpreter(shell);
	if (kind === undefined) {
		return [];
	}
	if (kind === "python") {
		const result = spawnSync("python3", ["-m", "pyflakes"], {
			input: script,
			encoding: "utf8",
			maxBuffer: CAPTURE_LIMIT_BYTES,
		});
		if (result.error !== undefined || /No module named pyflakes/u.test(result.stderr)) {
			throw new Error(`Pyflakes is required to check ${where}`);
		}
		if (result.status !== 0 && result.status !== 1) {
			throw new Error(`Pyflakes failed on ${where}: ${result.stderr}`);
		}
		return result.stdout
			.split("\n")
			.filter((line) => line !== "")
			.map((line) => `${where}: ${line.replace(/^<stdin>:/u, "line ")}`);
	}
	const result = spawnSync(
		"shellcheck",
		[
			"--norc",
			"--format=json1",
			"--severity=warning",
			`--shell=${kind}`,
			`--exclude=${SHELLCHECK_EXCLUDED}`,
			"-",
		],
		{ input: script, encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES },
	);
	if (result.error !== undefined) {
		throw new Error(`ShellCheck is required to check ${where}`, { cause: result.error });
	}
	if (result.status !== 0 && result.status !== 1) {
		throw new Error(`ShellCheck failed on ${where}: ${result.stderr}`);
	}
	return asArray(asRecord(parseJson(result.stdout), "ShellCheck report").comments, "comments").map(
		(value) => {
			const comment = asRecord(value, "ShellCheck comment");
			return `${where}: line ${String(comment.line)}: SC${String(comment.code)} ${String(comment.message)}`;
		},
	);
}

export async function checkRepository(
	language: ActionsLanguage,
	root: string,
): Promise<WorkflowCheck> {
	const { isMapping, isSequence, isString, NoOperationTraceWriter } = language.parser;
	const { DescriptionDictionary, FeatureFlags, data, isDescriptionDictionary } =
		language.expressions;
	// The released native steps, and no other experimental feature.
	const featureFlags = new FeatureFlags({ allowBackgroundSteps: true });
	// The validator catches its own failures and logs them; a failure is not a clean workflow.
	language.service.registerLogger({
		error(message) {
			throw new Error(`GitHub's workflow validator failed: ${message}`);
		},
		warn(message) {
			throw new Error(`GitHub's workflow validator warned: ${message}`);
		},
		info: console.info,
		log: console.log,
	});
	language.service.setLogLevel(language.service.LogLevel.Warn);
	const realRoot = await realpath(root);
	const name = (file: string) => path.relative(root, file).split(path.sep).join("/");

	/** Reads a repository file only when it and every link to it stay inside the repository. */
	async function read(file: string): Promise<string> {
		if (!inside(root, path.resolve(file)) || !inside(realRoot, await realpath(file))) {
			throw new Error(`${file} resolves outside the repository`);
		}
		return readFile(file, "utf8");
	}

	/** An action's official template, after the parser's errors from parsing and converting it. */
	function templateOf(action: string, content: string): ActionTemplate {
		const parsed = language.actionParser.parseAction(
			{ name: action, content },
			new NoOperationTraceWriter(),
		);
		const template =
			parsed.value === undefined
				? undefined
				: language.actionTemplate.convertActionTemplate(parsed.context, parsed.value);
		const errors = parsed.context.errors.getErrors();
		if (template === undefined || errors.length > 0) {
			throw new Error(
				`${action} is not a valid action: ${errors.map((error) => error.message).join("; ")}`,
			);
		}
		return template;
	}

	const metadata = new Map<string, Promise<ActionTemplate>>();
	async function cached(key: string, load: () => Promise<ActionTemplate>): Promise<ActionTemplate> {
		const loading = metadata.get(key) ?? load();
		metadata.set(key, loading);
		return loading;
	}

	const localAction = async (uses: string) =>
		cached(uses, async () => {
			for (const file of ["action.yml", "action.yaml"].map((entry) =>
				path.resolve(root, uses, entry),
			)) {
				let content: string;
				try {
					content = await read(file);
				} catch (error) {
					if (isRecord(error) && error.code === "ENOENT") {
						continue;
					}
					throw error;
				}
				return templateOf(uses, content);
			}
			throw new Error(`${uses} names no action in this repository`);
		});

	/** A public action's template at an immutable commit, read once per run without credentials. */
	async function pinnedAction(
		owner: string,
		repository: string,
		subpath: string | undefined,
		ref: string,
	) {
		const segments = [
			owner,
			repository,
			...(subpath ?? "").split("/").filter((part) => part !== ""),
		];
		const action = `${segments.join("/")}@${ref}`;
		if (
			!/^[\da-f]{40}$/u.test(ref) ||
			segments.some((part) => !/^[\w.-]+$/u.test(part) || /^\.+$/u.test(part))
		) {
			throw new Error(`${action} is not a public action pinned to a commit`);
		}
		const [first = "", second = "", ...rest] = segments;
		const base = `https://raw.githubusercontent.com/${[first, second, ref, ...rest].join("/")}`;
		return cached(action, async () => {
			for (const entry of ["action.yml", "action.yaml"]) {
				const response = await fetch(`${base}/${entry}`, {
					redirect: "error",
					signal: AbortSignal.timeout(5000),
				});
				if (response.status === 404) {
					continue;
				}
				const content = response.ok ? await response.text() : "";
				if (!response.ok || content.length > METADATA_LIMIT) {
					throw new Error(
						`${base}/${entry} answered ${response.status} with ${content.length} characters`,
					);
				}
				return templateOf(action, content);
			}
			throw new Error(`${action} has no action.yml`);
		});
	}

	const names = (keys: string[], extra: { key: string; value: DescriptionDictionary }[] = []) => {
		const dictionary = new DescriptionDictionary(
			...keys.map((key) => ({ key, value: new data.Null() })),
			...extra,
		);
		dictionary.complete = true;
		return dictionary;
	};

	type Config = NonNullable<Parameters<ActionsLanguage["service"]["validate"]>[1]>;
	type ActionMetadata = NonNullable<
		Awaited<ReturnType<ActionsMetadataProvider["fetchActionMetadata"]>>
	>;
	// Local reusable workflows only.
	const fileProvider: Config["fileProvider"] = {
		async getFileContent(reference) {
			if ("repository" in reference) {
				throw new Error(`${reference.repository}/${reference.path} is not in this repository`);
			}
			return { name: reference.path, content: await read(path.resolve(root, reference.path)) };
		},
	};
	const actionsMetadataProvider: ActionsMetadataProvider = {
		async fetchActionMetadata(reference): Promise<ActionMetadata> {
			const template = await pinnedAction(
				reference.owner,
				reference.name,
				reference.path,
				reference.ref,
			);
			return {
				name: template.name,
				description: template.description,
				inputs: Object.fromEntries(
					(template.inputs ?? []).map((input) => [
						input.id,
						{
							description: input.description ?? "",
							required: input.required,
							default: input.default?.toString(),
							deprecationMessage: input.deprecationMessage,
						},
					]),
				),
				outputs: Object.fromEntries(
					(template.outputs ?? []).map((output) => [
						output.id,
						{ description: output.description ?? "" },
					]),
				),
			};
		},
	};

	async function addDeclaredOutputs(
		defaultContext: DescriptionDictionary,
		steps: readonly Step[],
		native: boolean,
	) {
		// Populate only the step IDs GitHub's default context already makes visible.
		for (const pair of defaultContext.pairs()) {
			if (!isDescriptionDictionary(pair.value)) {
				continue;
			}
			const step = steps.find((candidate) => candidate.id === pair.key);
			if (step === undefined || !("uses" in step)) {
				continue;
			}
			const uses = step.uses.value;
			let template: ActionTemplate | undefined;
			if (uses.startsWith("./")) {
				template = await localAction(uses).catch(() => undefined);
			} else if (native) {
				const pin = PIN.exec(uses)?.groups;
				if (pin !== undefined) {
					template = await pinnedAction(
						pin.owner ?? "",
						pin.repository ?? "",
						pin.subpath?.slice(1),
						pin.ref ?? "",
					);
				}
			}
			const outputs = pair.value.get("outputs");
			if (template !== undefined && outputs !== undefined && isDescriptionDictionary(outputs)) {
				for (const output of template.outputs ?? []) {
					outputs.add(output.id, new data.Null());
				}
				outputs.complete = true;
			}
		}
		return defaultContext;
	}

	/** Native workflows also read the declared metadata of the public actions they pin. */
	const configFor = (native: boolean): Config => ({
		featureFlags,
		fileProvider,
		...(native ? { actionsMetadataProvider } : {}),
		contextProviderConfig: {
			// `undefined` leaves a context to GitHub's own default.
			async getContext(context, defaultContext, workflowContext) {
				if (defaultContext === undefined) {
					return;
				}
				const events = workflowContext.template?.events;
				if (context === "github") {
					const event = defaultContext.get("event");
					if (
						events === undefined ||
						!("merge_group" in events) ||
						event === undefined ||
						!isDescriptionDictionary(event)
					) {
						return;
					}
					if (event.get("merge_group") === undefined) {
						const person = () => names(["name", "email"]);
						const commit = names(COMMIT, [
							{ key: "author", value: person() },
							{ key: "committer", value: person() },
						]);
						event.add("merge_group", names(MERGE_GROUP, [{ key: "head_commit", value: commit }]));
					}
					return defaultContext;
				}
				if (context !== "steps" || workflowContext.job === undefined) {
					return;
				}
				return addDeclaredOutputs(defaultContext, workflowContext.job.steps, native);
			},
		},
	});

	const problems: string[] = [];
	const compatible: string[] = [];

	const mapping = (token: TemplateToken | undefined) =>
		token !== undefined && isMapping(token) ? token : undefined;
	const text = (token: TemplateToken | undefined) =>
		token !== undefined && isString(token) ? token.value : undefined;
	const shellOf = (owner: MappingToken | undefined) =>
		text(mapping(mapping(owner?.find("defaults"))?.find("run"))?.find("shell"));
	/** A sequence's step mappings in order, a `parallel` block's steps in its place. */
	const stepsOf = (token: TemplateToken | undefined): MappingToken[] =>
		token !== undefined && isSequence(token)
			? [...token].flatMap((step) => {
					const map = mapping(step);
					if (map === undefined) {
						return [];
					}
					const parallel = map.find("parallel");
					return parallel === undefined ? [map] : stepsOf(parallel);
				})
			: [];

	const script = (run: TemplateToken) => runScript(language, run);

	async function checkStep(
		file: string,
		label: string,
		step: MappingToken,
		shell: string | undefined,
		scripts: boolean,
	) {
		const uses = text(step.find("uses"));
		if (uses?.startsWith("./") === true) {
			try {
				if (!inside(root, path.resolve(root, uses))) {
					throw new Error(`${uses} leaves the repository`);
				}
				const template = await localAction(uses);
				const given = new Set(
					[...(mapping(step.find("with")) ?? [])].map((pair) => text(pair.key)),
				);
				const inputs = template.inputs ?? [];
				for (const key of given) {
					if (!inputs.some((input) => input.id === key)) {
						problems.push(`${file}: ${label}: ${uses} declares no input "${String(key)}"`);
					}
				}
				for (const input of inputs) {
					if (input.required === true && input.default === undefined && !given.has(input.id)) {
						problems.push(`${file}: ${label}: ${uses} requires input "${input.id}"`);
					}
				}
			} catch (error) {
				problems.push(
					`${file}: ${label}: ${error instanceof Error ? error.message : String(error)}`,
				);
			}
		}
		const run = step.find("run");
		const stepShell = text(step.find("shell")) ?? shell;
		if (!scripts || run === undefined || stepShell === undefined) {
			return;
		}
		const body = script(run);
		if (body === undefined) {
			problems.push(
				`${file}: ${label}: the run script's expressions cannot be located in its source`,
			);
			return;
		}
		problems.push(...checkScript(`${file}: ${label}`, stepShell, body));
	}

	// A link is listed whatever it points at; reading through it is confined like any other read.
	const list = async (directory: string, keep: (entry: Dirent) => boolean) => {
		try {
			const entries = await readdir(path.join(root, directory), { withFileTypes: true });
			return entries
				.filter((entry) => entry.isSymbolicLink() || keep(entry))
				.map((entry) => path.join(root, directory, entry.name))
				.toSorted();
		} catch (error) {
			if (isRecord(error) && error.code === "ENOENT") {
				return [];
			}
			throw error;
		}
	};
	const workflowFiles = await list(".github/workflows", (entry) => entry.isFile());
	const workflows = workflowFiles.filter((file) => /\.ya?ml$/u.test(file));
	const actionDirectories = await list(".github/actions", (entry) => entry.isDirectory());
	const actions = actionDirectories.flatMap((directory) =>
		["action.yml", "action.yaml"].map((entry) => path.join(directory, entry)),
	);

	async function checkJobs(documentName: string, top: MappingToken | undefined, native: boolean) {
		for (const pair of mapping(top?.find("jobs")) ?? []) {
			const job = mapping(pair.value);
			const runsOn = job?.find("runs-on");
			// GitHub's default is bash everywhere but Windows, whose PowerShell nothing here checks.
			const windows = runsOn !== undefined && JSON.stringify(runsOn.toJSON()).includes("windows");
			const shell = shellOf(job) ?? shellOf(top) ?? (windows ? undefined : "bash");
			for (const [index, step] of stepsOf(job?.find("steps")).entries()) {
				// Actionlint already checks the scripts of the workflows it can read.
				await checkStep(
					documentName,
					`${String(text(pair.key))} step ${index + 1}`,
					step,
					shell,
					native,
				);
			}
		}
	}

	async function checkFile(file: string) {
		let content: string;
		try {
			content = await read(file);
		} catch (error) {
			// A directory holds one of the two names, and a linked file is no action directory.
			if (
				actions.includes(file) &&
				isRecord(error) &&
				(error.code === "ENOENT" || error.code === "ENOTDIR")
			) {
				return;
			}
			throw error;
		}
		const document = { name: name(file), content };
		const validate = async (native: boolean) => {
			const diagnostics = await language.service.validate(
				TextDocument.create(pathToFileURL(file).href, "yaml", 1, content),
				configFor(native),
			);
			for (const diagnostic of diagnostics) {
				// 1 is an error and 2 a warning; an unknown context or step reference is a warning.
				if (diagnostic.severity === undefined || diagnostic.severity <= 2) {
					const { line, character } = diagnostic.range.start;
					const message =
						typeof diagnostic.message === "string" ? diagnostic.message : diagnostic.message.value;
					problems.push(`${document.name}:${line + 1}:${character + 1}: ${message}`);
				}
			}
		};

		if (actions.includes(file)) {
			await validate(false);
			const parsed = language.actionParser.parseAction(document, new NoOperationTraceWriter());
			const steps = stepsOf(mapping(mapping(parsed.value)?.find("runs"))?.find("steps"));
			for (const [index, step] of steps.entries()) {
				await checkStep(document.name, `step ${index + 1}`, step, undefined, true);
			}
			return;
		}

		const context = new language.templateContext.TemplateContext(
			new language.templateContext.TemplateValidationErrors(),
			language.workflowSchema.getWorkflowSchema(),
			new NoOperationTraceWriter(),
		);
		context.state.featureFlags = featureFlags;
		const parsed = language.parser.parseWorkflow(document, context);
		const top = mapping(parsed.value);
		const template =
			parsed.value === undefined
				? undefined
				: await language.parser.convertWorkflowTemplate(
						parsed.context,
						parsed.value,
						fileProvider,
						{
							featureFlags,
						},
					);
		const native = (template?.jobs ?? []).some(
			(job) => job.type === "job" && job.steps.some(isNative),
		);
		await validate(native);
		if (template !== undefined && !native) {
			compatible.push(document.name);
		}
		await checkJobs(document.name, top, native);
	}
	for (const file of [...workflows, ...actions]) {
		await checkFile(file);
	}
	return { problems, compatible };
}

if (import.meta.main) {
	const language = await openActionsLanguage();
	try {
		const { problems, compatible } = await checkRepository(language, process.cwd());
		for (const problem of problems) {
			console.error(problem);
		}
		// The list reaches Actionlint as words, so a name that a shell could split is refused.
		const unsafe = compatible.filter(
			(file) => !/^\.github\/workflows\/[\w.-]+\.ya?ml$/u.test(file),
		);
		if (unsafe.length > 0) {
			throw new Error(`Workflow names Actionlint cannot take as arguments: ${unsafe.join(", ")}`);
		}
		const output = process.env.GITHUB_OUTPUT;
		if (output !== undefined && output !== "") {
			await appendFile(output, `compatible=${compatible.join(" ")}\n`);
		}
		if (problems.length > 0) {
			process.exitCode = 1;
		}
	} finally {
		await language.close();
	}
}
