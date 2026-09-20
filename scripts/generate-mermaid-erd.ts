import fs from "node:fs/promises";
import path from "node:path";
import process from "node:process";

import { Command, InvalidArgumentError, Option } from "commander";
import { Client } from "pg";

type LogLevel = "debug" | "info" | "silent";

interface Logger {
	debug: (message: string) => void;
	info: (message: string) => void;
	error: (message: string) => void;
}

function createLogger(level: LogLevel): Logger {
	return {
		debug: (msg) => {
			if (level === "debug") {
				console.log(msg);
			}
		},
		info: (msg) => {
			if (level !== "silent") {
				console.log(msg);
			}
		},
		error: (msg) => console.error(msg),
	};
}

interface ColumnInfo {
	name: string;
	type: string;
	constraints: string[];
	comment: string;
	isPrimaryKey: boolean;
	isForeignKey: boolean;
}

type RelationshipCardinality = "||--||" | "||--o{" | "}o--o{";

interface RelationshipInfo {
	childTable: string;
	childColumn: string;
	parentTable: string;
	parentColumn: string;
	constraintName: string;
	label: string;
	cardinality: RelationshipCardinality;
}

interface DatabaseConfig {
	host: string;
	port: number;
	database: string;
	username: string;
	password: string;
}

interface JdbcInfo {
	host: string;
	port: number;
	database: string;
}

interface GeneratorOptions {
	schema: string;
	includeLiquibase: boolean;
	logger: Logger;
}

const DEFAULT_OUTPUT_PATH = "docs/contributor/erd/schema.mmd";

/** Rendered in the order they appear, each heading its own section of the diagram. */
const CARDINALITY_NAMES = [
	["||--||", "One-to-One"],
	["||--o{", "One-to-Many"],
	["}o--o{", "Many-to-Many"],
] as const satisfies readonly (readonly [RelationshipCardinality, string])[];

const messageOf = (error: unknown): string =>
	error instanceof Error ? error.message : String(error);

function generateRelationshipLabel(
	childTable: string,
	parentTable: string,
	childColumn: string,
): string {
	const cleanColumn = childColumn.replace("_id", "").replace("id", "");

	if (childTable.includes("assignee")) {
		return "assigned_to";
	}
	if (childTable.includes("comment")) {
		return "commented_on";
	}
	if (childTable.includes("review")) {
		return "reviewed";
	}
	if (childTable.includes("label")) {
		return "labeled";
	}
	if (childTable.includes("member")) {
		return "belongs_to";
	}
	if (childTable.includes("repository") && childTable.includes("monitor")) {
		return "monitors";
	}
	if (parentTable === "user" && childColumn.includes("author")) {
		return "authored_by";
	}
	if (parentTable === "user" && childColumn.includes("creator")) {
		return "created_by";
	}
	if (parentTable === "user" && childColumn.includes("merged_by")) {
		return "merged_by";
	}
	if (childColumn.includes(parentTable) || parentTable.includes(cleanColumn)) {
		return "has";
	}
	return "references";
}

function formatDataType(
	dataType: string,
	charMaxLen: number | null,
	numPrecision: number | null,
	numScale: number | null,
): string {
	const typeMapping: Record<string, string> = {
		"character varying": "VARCHAR",
		character: "CHAR",
		text: "TEXT",
		integer: "INTEGER",
		bigint: "BIGINT",
		smallint: "SMALLINT",
		boolean: "BOOLEAN",
		"timestamp without time zone": "TIMESTAMP",
		"timestamp with time zone": "TIMESTAMPTZ",
		date: "DATE",
		time: "TIME",
		numeric: "NUMERIC",
		decimal: "DECIMAL",
		real: "REAL",
		"double precision": "DOUBLE",
		oid: "OID",
		uuid: "UUID",
		json: "JSON",
		jsonb: "JSONB",
	};

	let formattedType = typeMapping[dataType] ?? dataType.toUpperCase();

	if (
		charMaxLen !== null &&
		charMaxLen > 0 &&
		(formattedType === "VARCHAR" || formattedType === "CHAR")
	) {
		formattedType = `${formattedType}(${charMaxLen})`;
	} else if (
		numPrecision !== null &&
		numPrecision > 0 &&
		(formattedType === "NUMERIC" || formattedType === "DECIMAL")
	) {
		formattedType =
			numScale !== null && numScale > 0
				? `${formattedType}(${numPrecision},${numScale})`
				: `${formattedType}(${numPrecision})`;
	}

	return formattedType;
}

function toEntityName(tableName: string): string {
	const specialCases: Record<string, string> = {
		issue_assignee: "IssueAssignee",
		issue_comment: "IssueComment",
		issue_label: "IssueLabel",
		pull_request_review: "PullRequestReview",
		pull_request_review_comment: "PullRequestReviewComment",
		pull_request_requested_reviewers: "PullRequestRequestedReviewer",
		repository_to_monitor: "RepositoryToMonitor",
		team_labels: "TeamLabel",
		team_members: "TeamMember",
		team_repositories: "TeamRepository",
	};

	const specialCase = specialCases[tableName];
	if (specialCase !== undefined) {
		return specialCase;
	}

	let singular = tableName;
	if (tableName.endsWith("ies")) {
		singular = `${tableName.slice(0, -3)}y`;
	} else if (tableName.endsWith("s") && !tableName.endsWith("ss")) {
		singular = tableName.slice(0, -1);
	}

	return singular
		.split("_")
		.map((word) => word.charAt(0).toUpperCase() + word.slice(1))
		.join("");
}

function getEntityStyleClass(tableName: string): string | null {
	const primaryEntities = [
		"user",
		"repository",
		"issue",
		"milestone",
		"label",
		"team",
		"workspace",
	];

	const associationEntities = [
		"issue_assignee",
		"issue_label",
		"team_members",
		"team_labels",
		"team_repositories",
		"pull_request_requested_reviewers",
	];

	const metadataEntities = ["session", "message", "repository_to_monitor"];

	if (primaryEntities.includes(tableName)) {
		return "primaryEntity";
	}
	if (associationEntities.includes(tableName)) {
		return "associationEntity";
	}
	if (metadataEntities.includes(tableName)) {
		return "metadataEntity";
	}
	return null;
}

function buildMermaidContent(
	tableData: Map<string, ColumnInfo[]>,
	relationships: RelationshipInfo[],
): string {
	const lines: string[] = [
		"---",
		"config:",
		"    layout: elk",
		"---",
		"erDiagram",
		"    %% Generated automatically from PostgreSQL database schema",
		"    %% using scripts/generate-mermaid-erd.ts",
		"    %% To regenerate: vp run db:generate-erd-docs",
		"",
		"    direction LR",
		"",
	];

	for (const [tableName, columns] of tableData.entries()) {
		const entityName = toEntityName(tableName);
		lines.push(`    ${entityName} {`);

		for (const column of columns) {
			let typeAndName = `${column.type} ${column.name}`;
			if (column.constraints.length > 0) {
				typeAndName += ` ${column.constraints.join(",")}`;
			}
			if (column.comment) {
				typeAndName += ` "${column.comment}"`;
			}
			lines.push(`        ${typeAndName}`);
		}

		lines.push("    }", "");
	}

	lines.push("    %% Relationships");

	const relationshipGroups: Record<RelationshipCardinality, RelationshipInfo[]> = {
		"||--||": [],
		"||--o{": [],
		"}o--o{": [],
	};

	for (const rel of relationships) {
		relationshipGroups[rel.cardinality].push(rel);
	}

	// Disambiguate FKs that would render as identical edges (e.g. account_id + acting_account_id
	// both -> Account): only colliding lines get qualified by column; all other labels unchanged.
	const renderedLine = (rel: RelationshipInfo) =>
		`${toEntityName(rel.parentTable)} ${rel.cardinality} ${toEntityName(
			rel.childTable,
		)} : ${rel.label || "has"}`;
	const lineCounts = new Map<string, number>();
	for (const rel of relationships) {
		lineCounts.set(renderedLine(rel), (lineCounts.get(renderedLine(rel)) ?? 0) + 1);
	}

	for (const [cardinality, cardinalityName] of CARDINALITY_NAMES) {
		const rels = relationshipGroups[cardinality];
		if (rels.length === 0) {
			continue;
		}
		lines.push(`    %% ${cardinalityName} relationships`);

		for (const rel of rels) {
			const parentEntity = toEntityName(rel.parentTable);
			const childEntity = toEntityName(rel.childTable);
			const label =
				(lineCounts.get(renderedLine(rel)) ?? 0) > 1 ? rel.childColumn : rel.label || "has";
			lines.push(`    ${parentEntity} ${cardinality} ${childEntity} : ${label}`);
		}
		lines.push("");
	}

	lines.push(
		"    %% Styling",
		"    classDef primaryEntity fill:#e1f5fe,stroke:#01579b,stroke-width:2px",
		"    classDef associationEntity fill:#f3e5f5,stroke:#4a148c,stroke-width:2px",
		"    classDef metadataEntity fill:#e8f5e8,stroke:#1b5e20,stroke-width:2px",
		"",
	);

	for (const tableName of tableData.keys()) {
		const entityName = toEntityName(tableName);
		const styleClass = getEntityStyleClass(tableName);
		if (styleClass !== null) {
			lines.push(`    class ${entityName} ${styleClass}`);
		}
	}

	return `${lines.join("\n")}\n`;
}

class MermaidErdGenerator {
	private readonly client: Client;
	private readonly schema: string;
	private readonly includeLiquibase: boolean;
	private readonly logger: Logger;

	constructor(config: DatabaseConfig, options: GeneratorOptions) {
		this.client = new Client({
			host: config.host,
			port: config.port,
			user: config.username,
			password: config.password,
			database: config.database,
		});
		this.schema = options.schema;
		this.includeLiquibase = options.includeLiquibase;
		this.logger = options.logger;
	}

	async connect() {
		try {
			await this.client.connect();
			this.logger.info(`Connected to database: ${this.client.database}`);
		} catch (error) {
			throw new Error(`Failed to connect to database: ${messageOf(error)}`, { cause: error });
		}
	}

	async disconnect() {
		try {
			await this.client.end();
			this.logger.debug("Database connection closed.");
		} catch (error) {
			this.logger.error(`Failed to close database connection: ${messageOf(error)}`);
		}
	}

	async generate(outputFile: string) {
		const tables = await this.getTables();
		const tableData = new Map<string, ColumnInfo[]>();

		for (const tableName of tables) {
			const columns = await this.getTableColumns(tableName);
			tableData.set(tableName, columns);
		}

		const relationships = await this.getForeignKeyRelationships();
		const content = buildMermaidContent(tableData, relationships);

		const outputPath = path.resolve(outputFile);
		await fs.mkdir(path.dirname(outputPath), { recursive: true });
		await fs.writeFile(outputPath, content, "utf8");
		this.logger.info(`ERD written to ${outputFile}`);
	}

	private async getTables(): Promise<string[]> {
		const excludedTables = this.includeLiquibase
			? []
			: ["databasechangelog", "databasechangeloglock"];

		const exclusionsClause =
			excludedTables.length > 0
				? `AND c.relname NOT IN (${excludedTables.map((_, idx) => `$${idx + 2}`).join(", ")})`
				: "";
		// pg_catalog, not information_schema: partition children (relispartition=true, e.g.
		// auth_event_p202605/_default) report as BASE TABLE and would each become an entity that
		// churns monthly. relkind IN ('r','p') AND NOT relispartition keeps tables + partitioned parents.
		const query = `
			SELECT c.relname AS table_name
			FROM pg_class c
			JOIN pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = $1
			  AND c.relkind IN ('r', 'p')
			  AND c.relispartition = false
			  ${exclusionsClause}
			ORDER BY c.relname
		`;
		const params = [this.schema, ...excludedTables];
		const result = await this.client.query<{ table_name: string }>(query, params);
		return result.rows.map((row) => row.table_name);
	}

	private async getTableColumns(tableName: string): Promise<ColumnInfo[]> {
		const query = `
			SELECT
				c.column_name,
				c.data_type,
				c.character_maximum_length,
				c.numeric_precision,
				c.numeric_scale,
				c.is_nullable,
				CASE WHEN pk.column_name IS NOT NULL THEN 'YES' ELSE 'NO' END as is_primary_key,
				CASE WHEN fk.column_name IS NOT NULL THEN 'YES' ELSE 'NO' END as is_foreign_key,
				CASE WHEN uk.column_name IS NOT NULL THEN 'YES' ELSE 'NO' END as is_unique_key
			FROM information_schema.columns c
			LEFT JOIN (
				SELECT DISTINCT ku.column_name
				FROM information_schema.table_constraints tc
				JOIN information_schema.key_column_usage ku
				  ON tc.constraint_name = ku.constraint_name
				 AND tc.table_schema = ku.table_schema
				WHERE tc.table_name = $1
				  AND tc.table_schema = $2
				  AND tc.constraint_type = 'PRIMARY KEY'
			) pk ON c.column_name = pk.column_name
			LEFT JOIN (
				SELECT DISTINCT ku.column_name
				FROM information_schema.table_constraints tc
				JOIN information_schema.key_column_usage ku
				  ON tc.constraint_name = ku.constraint_name
				 AND tc.table_schema = ku.table_schema
				WHERE tc.table_name = $1
				  AND tc.table_schema = $2
				  AND tc.constraint_type = 'FOREIGN KEY'
			) fk ON c.column_name = fk.column_name
			LEFT JOIN (
				SELECT DISTINCT ku.column_name
				FROM information_schema.table_constraints tc
				JOIN information_schema.key_column_usage ku
				  ON tc.constraint_name = ku.constraint_name
				 AND tc.table_schema = ku.table_schema
				WHERE tc.table_name = $1
				  AND tc.table_schema = $2
				  AND tc.constraint_type = 'UNIQUE'
			) uk ON c.column_name = uk.column_name
			WHERE c.table_name = $1
			  AND c.table_schema = $2
			ORDER BY c.ordinal_position
		`;
		const result = await this.client.query<{
			column_name: string;
			data_type: string;
			character_maximum_length: number | null;
			numeric_precision: number | null;
			numeric_scale: number | null;
			is_nullable: "YES" | "NO";
			is_primary_key: "YES" | "NO";
			is_foreign_key: "YES" | "NO";
			is_unique_key: "YES" | "NO";
		}>(query, [tableName, this.schema]);

		return result.rows.map((row) => {
			const formattedType = formatDataType(
				row.data_type,
				row.character_maximum_length,
				row.numeric_precision,
				row.numeric_scale,
			);

			const constraints: string[] = [];
			if (row.is_primary_key === "YES") {
				constraints.push("PK");
			}
			if (row.is_foreign_key === "YES") {
				constraints.push("FK");
			}
			if (row.is_unique_key === "YES") {
				constraints.push("UK");
			}

			const comment = row.is_nullable === "NO" && row.is_primary_key !== "YES" ? "NOT NULL" : "";

			return {
				name: row.column_name,
				type: formattedType,
				constraints,
				comment,
				isPrimaryKey: row.is_primary_key === "YES",
				isForeignKey: row.is_foreign_key === "YES",
			};
		});
	}

	private async getForeignKeyRelationships(): Promise<RelationshipInfo[]> {
		const excludedTables = this.includeLiquibase
			? []
			: ["databasechangelog", "databasechangeloglock"];
		const exclusionsClause =
			excludedTables.length > 0
				? `AND conrel.relname NOT IN (${excludedTables.map((_, idx) => `$${idx + 2}`).join(", ")})
			   AND confrel.relname NOT IN (${excludedTables
						.map((_, idx) => `$${idx + 2 + excludedTables.length}`)
						.join(", ")})`
				: "";
		const params = [this.schema, ...excludedTables, ...excludedTables];
		// pg_constraint, not constraint_column_usage: conparentid=0 drops FKs cascaded onto partition
		// children (else every FK repeats per child); unnest(conkey,confkey) handles composite keys
		// without the ccu cross-join + DISTINCT.
		const query = `
			SELECT
				conrel.relname AS child_table,
				array_agg(att.attname ORDER BY k.ord)::text[] AS child_columns,
				confrel.relname AS parent_table,
				array_agg(fatt.attname ORDER BY k.ord)::text[] AS parent_columns,
				con.conname     AS constraint_name
			FROM pg_constraint con
			JOIN pg_class conrel ON conrel.oid = con.conrelid
			JOIN pg_namespace conns ON conns.oid = conrel.relnamespace
			JOIN pg_class confrel ON confrel.oid = con.confrelid
			JOIN LATERAL unnest(con.conkey, con.confkey) WITH ORDINALITY
				AS k(conkey, confkey, ord) ON true
			JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = k.conkey
			JOIN pg_attribute fatt ON fatt.attrelid = con.confrelid AND fatt.attnum = k.confkey
			WHERE con.contype = 'f'
			  AND con.conparentid = 0
			  AND conrel.relispartition = false
			  AND conns.nspname = $1
			  ${exclusionsClause}
			GROUP BY conrel.relname, confrel.relname, con.conname
			ORDER BY conrel.relname, con.conname
		`;
		const result = await this.client.query<{
			child_table: string;
			child_columns: string[];
			parent_table: string;
			parent_columns: string[];
			constraint_name: string;
		}>(query, params);

		const relationships: RelationshipInfo[] = [];
		for (const row of result.rows) {
			const childColumn = row.child_columns[0];
			const parentColumn = row.parent_columns[0];
			if (childColumn === undefined || parentColumn === undefined) {
				continue;
			}
			const label = generateRelationshipLabel(row.child_table, row.parent_table, childColumn);
			const cardinality = await this.detectRelationshipCardinality(
				row.child_table,
				row.child_columns,
				row.parent_table,
				row.parent_columns,
			);
			relationships.push({
				childTable: row.child_table,
				childColumn,
				parentTable: row.parent_table,
				parentColumn,
				constraintName: row.constraint_name,
				label,
				cardinality,
			});
		}

		return relationships;
	}

	private async detectRelationshipCardinality(
		childTable: string,
		childColumns: string[],
		_parentTable: string,
		_parentColumns: string[],
	): Promise<RelationshipCardinality> {
		const sortedChildColumns = [...childColumns].toSorted();
		const uniqueQuery = `
			SELECT COUNT(*) FROM (
				SELECT tc.constraint_name
				FROM information_schema.table_constraints tc
				JOIN information_schema.key_column_usage ku
				  ON tc.constraint_name = ku.constraint_name
				 AND tc.table_schema = ku.table_schema
				WHERE tc.table_name = $1
				  AND tc.table_schema = $2
				  AND tc.constraint_type IN ('PRIMARY KEY', 'UNIQUE')
				GROUP BY tc.constraint_name
				HAVING array_agg(ku.column_name::text ORDER BY ku.column_name) <@ $3::text[]
			) AS unique_constraints
		`;
		const uniqueResult = await this.client.query<{ count: string }>(uniqueQuery, [
			childTable,
			this.schema,
			sortedChildColumns,
		]);
		const isUnique = Number(uniqueResult.rows[0]?.count ?? 0) > 0;

		const fkCountResult = await this.client.query<{ count: string }>(
			`
				SELECT COUNT(*)
				FROM information_schema.table_constraints tc
				WHERE tc.table_name = $1
				  AND tc.table_schema = $2
				  AND tc.constraint_type = 'FOREIGN KEY'
			`,
			[childTable, this.schema],
		);
		const fkCount = Number(fkCountResult.rows[0]?.count ?? 0);

		const totalColumnsResult = await this.client.query<{ count: string }>(
			`
				SELECT COUNT(*)
				FROM information_schema.columns
				WHERE table_name = $1
				  AND table_schema = $2
			`,
			[childTable, this.schema],
		);
		const totalColumns = Number(totalColumnsResult.rows[0]?.count ?? 0);

		if (fkCount >= 2 && totalColumns <= fkCount + 2) {
			return "}o--o{";
		}
		if (isUnique) {
			return "||--||";
		}
		return "||--o{";
	}
}

function sanitizeJdbcUrl(jdbcUrl: string): string {
	// Strip query parameters that might contain credentials (defense-in-depth)
	return jdbcUrl.split("?")[0] ?? jdbcUrl;
}

function parseJdbcUrl(jdbcUrl: string): JdbcInfo {
	// Reject JDBC URLs with query parameters (credentials should be passed separately)
	if (jdbcUrl.includes("?")) {
		throw new Error(
			"JDBC URL must not contain query parameters. Pass credentials via --username and --password flags instead.",
		);
	}

	// Match: jdbc:postgresql://host[:port]/database
	const match = /^jdbc:postgresql:\/\/(?<host>[^:/]+)(?::(?<port>\d+))?\/(?<database>[^?]+)$/u.exec(
		jdbcUrl,
	);
	if (!match) {
		throw new Error(
			`Invalid JDBC URL format: ${sanitizeJdbcUrl(jdbcUrl)}. Expected jdbc:postgresql://host:port/database`,
		);
	}
	const host = match.groups?.host;
	const portText = match.groups?.port;
	const port = portText === undefined ? 5432 : Number.parseInt(portText, 10);
	const database = match.groups?.database;
	if (host === undefined || database === undefined || !Number.isFinite(port)) {
		throw new Error(`Invalid JDBC URL: ${sanitizeJdbcUrl(jdbcUrl)}`);
	}
	return { host, port, database };
}

function ensureNonEmpty(value: string | undefined, message: string): string {
	if (value === undefined || value.trim() === "") {
		throw new Error(message);
	}
	return value;
}

function maskSecret(value: string): string {
	if (value.length <= 4) {
		return "****";
	}
	return `${value.slice(0, 2)}****${value.slice(-2)}`;
}

function parseLogLevel(value: string): LogLevel {
	if (value === "debug" || value === "info" || value === "silent") {
		return value;
	}
	throw new InvalidArgumentError(`Invalid log level: ${value}`);
}

interface CliOptions {
	jdbcUrl?: string;
	username?: string;
	password?: string;
	output?: string;
	schema: string;
	includeLiquibase: boolean;
	dryRun: boolean;
	logLevel: LogLevel;
}

async function main() {
	const program = new Command();
	program
		.name("generate-mermaid-erd")
		.description("Generate a Mermaid ERD from a PostgreSQL database schema")
		.argument("[jdbcUrl]", "JDBC URL (jdbc:postgresql://host:port/database)")
		.argument("[username]", "Database username")
		.argument("[password]", "Database password")
		.argument("[outputFile]", "Output file path")
		.option("--jdbc-url <url>", "JDBC URL (overrides positional argument)")
		.option("--username <username>", "Database username (overrides positional)")
		.option("--password <password>", "Database password (overrides positional)")
		.option("--output <file>", "Output file path")
		.option("--schema <schema>", "Database schema to inspect", "public")
		.option("--include-liquibase", "Include Liquibase metadata tables", false)
		.option("--dry-run", "Validate configuration and exit", false)
		.addOption(
			new Option("--log-level <level>", "Log verbosity")
				.choices(["debug", "info", "silent"])
				.default("info")
				.argParser(parseLogLevel),
		);

	program.parse(process.argv);

	const options = program.opts<CliOptions>();
	const { args } = program;
	const jdbcUrl = options.jdbcUrl ?? args[0] ?? process.env.HEPHAESTUS_DB_JDBC_URL;
	const username = options.username ?? args[1] ?? process.env.POSTGRES_USER;
	const password = options.password ?? args[2] ?? process.env.POSTGRES_PASSWORD;
	const outputFile = options.output ?? args[3] ?? DEFAULT_OUTPUT_PATH;

	const logger = createLogger(options.logLevel);

	const resolvedJdbcUrl = ensureNonEmpty(
		jdbcUrl,
		"JDBC URL is required. Provide --jdbc-url or positional argument.",
	);
	const resolvedUsername = ensureNonEmpty(
		username,
		"Database username is required. Provide --username or positional argument.",
	);
	const resolvedPassword = ensureNonEmpty(
		password,
		"Database password is required. Provide --password or positional argument.",
	);
	const resolvedOutput = ensureNonEmpty(
		outputFile,
		"Output file path is required. Provide --output or positional argument.",
	);

	const parsedJdbc = parseJdbcUrl(resolvedJdbcUrl);
	const config: DatabaseConfig = {
		...parsedJdbc,
		username: resolvedUsername,
		password: resolvedPassword,
	};

	logger.debug(
		`Resolved configuration: jdbcUrl=${sanitizeJdbcUrl(resolvedJdbcUrl)}, username=${resolvedUsername}, password=${maskSecret(resolvedPassword)}, output=${resolvedOutput}, schema=${options.schema}`,
	);

	if (options.dryRun) {
		logger.info("Dry run enabled. Configuration validated successfully.");
		return;
	}

	const generator = new MermaidErdGenerator(config, {
		schema: options.schema,
		includeLiquibase: options.includeLiquibase,
		logger,
	});

	await generator.connect();
	try {
		await generator.generate(resolvedOutput);
	} finally {
		await generator.disconnect();
	}
}

try {
	await main();
} catch (error) {
	console.error(`ERD generation failed: ${messageOf(error)}`);
	process.exit(1);
}
