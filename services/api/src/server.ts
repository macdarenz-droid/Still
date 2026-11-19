import { chmodSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { ControlPlane } from "../../workflows/src/store.ts";
import { createApi } from "./app.ts";

// Restrict the database, journal and any newly created local state directories.
process.umask(0o077);
const databasePath = resolve(process.env.STILL_DB_PATH ?? ".local/still.sqlite");
mkdirSync(dirname(databasePath), { recursive: true, mode: 0o700 });
const store = new ControlPlane(databasePath);
chmodSync(databasePath, 0o600);
const token = process.env.STILL_API_TOKEN;
const ownerId = process.env.STILL_OWNER_ID ?? "local-development";
const server = createApi({ store, principals: token ? [{ token, ownerId }] : [] });
const port = Number(process.env.PORT ?? 8787);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("PORT must be a valid TCP port.");
server.listen(port, "127.0.0.1", () => {
  console.log(`Still development API: http://127.0.0.1:${port}`);
  console.log(token ? "Private routes use the configured local token. Cloud providers remain disconnected." : "Private routes are disabled. Configure STILL_API_TOKEN to use them; cloud providers remain disconnected.");
});
let stopping = false;
const shutdown = (): void => {
  if (stopping) return;
  stopping = true;
  server.close(() => { store.close(); });
  server.closeIdleConnections();
};
process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);
