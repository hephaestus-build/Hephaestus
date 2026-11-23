import process from "node:process";
import { applyDatabaseRoles } from "./lib/database-roles.ts";

const [container, database] = process.argv.slice(2);
if (container === undefined || database === undefined) {
	throw new Error("Usage: node scripts/setup-database-roles.ts <container> <database>");
}
await applyDatabaseRoles(container, database, {
	DATABASE_OWNER_PASSWORD: process.env.SPRING_LIQUIBASE_PASSWORD,
	DATABASE_APP_PASSWORD: process.env.SPRING_DATASOURCE_PASSWORD,
});
