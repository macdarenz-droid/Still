import { createHash, timingSafeEqual } from "node:crypto";
import { createServer } from "node:http";
import type { IncomingMessage, Server, ServerResponse } from "node:http";
import { ControlPlaneError, fault } from "../../../packages/contracts/src/index.ts";
import type { ControlPlane } from "../../workflows/src/store.ts";

export interface ApiPrincipal { token: string; ownerId: string }
export interface ApiOptions { store: ControlPlane; principals?: ApiPrincipal[] }
const MAX_BODY_BYTES = 32_768;

async function readJson(request: IncomingMessage): Promise<unknown> {
  if (request.headers["content-type"]?.split(";")[0].trim().toLowerCase() !== "application/json") {
    fault("unsupported_media_type", "Send this request as JSON.", 415);
  }
  const length = Number(request.headers["content-length"] ?? 0);
  if (!Number.isFinite(length) || length > MAX_BODY_BYTES) fault("request_too_large", "This request exceeds the size limit.", 413);
  const chunks: Buffer[] = [];
  let size = 0;
  // Drain an oversized request without buffering it or destroying the response socket.
  await new Promise<void>((resolve, reject) => {
    let rejected = false;
    const timer = setTimeout(() => {
      rejected = true;
      reject(new ControlPlaneError(408, { code: "request_timeout", message: "The request took too long.", retryable: true, scope: "request", recoveryAction: "Try the request again." }));
      request.resume();
    }, 10_000);
    request.on("data", (chunk: Buffer) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        if (!rejected) {
          rejected = true;
          clearTimeout(timer);
          reject(new ControlPlaneError(413, { code: "request_too_large", message: "This request exceeds the size limit.", retryable: false, scope: "request", recoveryAction: "Reduce the request size." }));
        }
      } else if (!rejected) chunks.push(chunk);
    });
    request.on("end", () => { clearTimeout(timer); if (!rejected) resolve(); });
    request.on("error", error => { clearTimeout(timer); reject(error); });
    request.on("aborted", () => { clearTimeout(timer); reject(new Error("Request aborted")); });
  });
  try { return JSON.parse(Buffer.concat(chunks).toString("utf8")) as unknown; }
  catch { fault("invalid_json", "This request does not contain valid JSON."); }
}

function respond(response: ServerResponse, status: number, value: unknown): void {
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff", "X-Frame-Options": "DENY",
  });
  response.end(JSON.stringify(value));
}

/** Local development API. Tokens identify a fixed owner; clients cannot choose one. */
export function createApi(options: ApiOptions): Server {
  const principals = (options.principals ?? []).map(principal => {
    if (principal.token.length < 32 || !/^[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}$/.test(principal.ownerId)) {
      throw new Error("API principals need a token of at least 32 characters and an opaque owner ID.");
    }
    return { digest: createHash("sha256").update(principal.token).digest(), ownerId: principal.ownerId };
  });
  if (new Set(principals.map(p => p.digest.toString("hex"))).size !== principals.length) throw new Error("API tokens must identify exactly one owner.");
  const authenticate = (request: IncomingMessage): string => {
    if (!principals.length) fault("authentication_unconfigured", "Private API access is not configured.", 503, "authentication", "Configure a local API token. The phone gallery works without an account.");
    const authorization = request.headers.authorization;
    const digest = createHash("sha256").update(authorization?.startsWith("Bearer ") ? authorization.slice(7) : "").digest();
    const principal = principals.find(candidate => timingSafeEqual(candidate.digest, digest));
    if (!principal) fault("unauthorized", "This request needs a valid access token.", 401, "authentication", "Use the access token for this local API.");
    return principal.ownerId;
  };
  const server = createServer({ maxHeaderSize: 8192 }, async (request, response) => {
    try {
      const path = new URL(request.url ?? "/", "http://localhost").pathname;
      const method = request.method;
      if (path === "/health" && method === "GET") return respond(response, 200, { status: "ok", schemaVersion: 1 });
      if (path === "/v1/capabilities" && method === "GET") return respond(response, 200, options.store.capabilities());
      const ownerId = authenticate(request);
      if (path === "/v1/consent") {
        if (method === "GET") return respond(response, 200, options.store.getConsent(ownerId));
        if (method === "PUT") return respond(response, 200, options.store.setConsent(ownerId, await readJson(request)));
      }
      if (path === "/v1/backup-policy") {
        if (method === "GET") return respond(response, 200, options.store.getBackupPolicy(ownerId));
        if (method === "PUT") return respond(response, 200, options.store.setBackupPolicy(ownerId, await readJson(request)));
      }
      if (path === "/v1/jobs") {
        if (method === "GET") return respond(response, 200, { items: options.store.listJobs(ownerId) });
        if (method === "POST") return respond(response, 201, options.store.createJob(ownerId, await readJson(request)));
      }
      const match = /^\/v1\/jobs\/([a-zA-Z0-9_-]{1,128})(\/cancel)?$/.exec(path);
      if (match && !match[2] && method === "GET") return respond(response, 200, options.store.getJob(ownerId, match[1]));
      if (match?.[2] && method === "POST") return respond(response, 200, options.store.cancelJob(ownerId, match[1], await readJson(request)));
      fault("route_not_found", "This API route is unavailable.", 404);
    } catch (error) {
      if (error instanceof ControlPlaneError) return respond(response, error.status, { error: error.detail });
      // Do not expose SQL, paths, prompts, tokens or private input references.
      respond(response, 500, { error: { code: "internal_error", message: "The request could not be completed.", retryable: true, scope: "request", recoveryAction: "Try again. If it continues, inspect the local service." } });
    }
  });
  server.headersTimeout = 10_000;
  server.requestTimeout = 15_000;
  server.keepAliveTimeout = 5_000;
  return server;
}
