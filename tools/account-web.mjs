import http from "node:http";
import https from "node:https";
import { readFile, realpath } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const defaultWebRoot = path.join(
  projectRoot,
  "build/tasks/_webApp_buildWasmJsAppWasmJsDebug",
);
const allowed = new Set([
  "POST /api/auth/login",
  "POST /api/auth/login/totp",
  "POST /api/auth/login/send-code",
  "POST /api/auth/login/verify",
  "GET /api/public/auth-settings",
  "GET /api/auth/me",
  "GET /api/subscription",
  "GET /api/credits",
  "POST /api/request-logs/stats",
  "POST /api/request-logs/model-stats",
]);
const mime = {
  ".html": "text/html; charset=utf-8",
  ".mjs": "text/javascript",
  ".js": "text/javascript",
  ".wasm": "application/wasm",
  ".css": "text/css",
  ".otf": "font/otf",
  ".ttf": "font/ttf",
  ".xml": "application/xml",
  ".png": "image/png",
  ".svg": "image/svg+xml",
  ".json": "application/json",
};

function fail(response, status, message) {
  if (response.writableEnded || response.destroyed) return;
  response.writeHead(status, {
    "Content-Type": "application/json",
    "Cache-Control": "no-store",
  });
  response.end(JSON.stringify({ success: false, code: status, message }));
}

async function limited(stream, maximum) {
  const chunks = [];
  let size = 0;
  for await (const chunk of stream.iterator({ destroyOnReturn: false })) {
    size += chunk.length;
    if (size > maximum) throw new Error("size-limit");
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

// 固定上游与白名单；不接受 Cookie，也不将官网 Cookie 写回浏览器。
export function createAccountServer(
  webRoot = defaultWebRoot,
  requestUpstream = https.request,
) {
  return http.createServer(async (request, response) => {
    try {
      const host = request.headers.host || "";
      const hostname = new URL("http://" + host).hostname;
      if (!["127.0.0.1", "localhost", "[::1]"].includes(hostname))
        return fail(response, 421, "Invalid host");
      if (
        request.headers.origin &&
        new URL(request.headers.origin).host !== host
      )
        return fail(response, 403, "Cross-origin requests are not allowed");
      const rawPath = decodeURIComponent(request.url.split("?")[0]);
      if (rawPath.split("/").includes("..") || rawPath.includes("\\"))
        return fail(response, 403, "Invalid path");
      const url = new URL(request.url, "http://" + host);
      if (url.pathname.startsWith("/krill-account/")) {
        const endpoint = url.pathname.slice("/krill-account".length);
        if (!allowed.has(`${request.method} ${endpoint}`) || url.search)
          return fail(response, 405, "Account endpoint is not allowed");
        const authorization = request.headers.authorization;
        const publicAuth =
          endpoint.startsWith("/api/auth/login") ||
          endpoint === "/api/public/auth-settings";
        if (
          !publicAuth &&
          (!authorization || !/^Bearer [^\s]{1,16384}$/.test(authorization))
        )
          return fail(response, 401, "Account session is required");
        let body;
        try {
          body = await limited(request, 64 * 1024);
        } catch {
          request.resume();
          return fail(response, 413, "Request is too large");
        }
        const headers = {
          Accept: "application/json",
          "Content-Type": "application/json",
          "X-Language": "zh-CN",
        };
        if (authorization && !publicAuth) headers.Authorization = authorization;
        let finished = false;
        const outgoing = requestUpstream(
          {
            protocol: "https:",
            hostname: "www.krill-code.com",
            port: 443,
            path: endpoint,
            method: request.method,
            headers,
          },
          async (upstream) => {
            try {
              const bytes = await limited(upstream, 2 * 1024 * 1024);
              if (finished) return;
              finished = true;
              clearTimeout(timer);
              const status = upstream.statusCode || 502;
              if (status >= 300 && status < 400)
                return fail(response, 502, "Upstream redirect refused");
              const resultHeaders = {
                "Content-Type": "application/json",
                "Cache-Control": "no-store",
              };
              if (/^\d{1,10}$/.test(upstream.headers["retry-after"] || ""))
                resultHeaders["Retry-After"] = upstream.headers["retry-after"];
              response.writeHead(status, resultHeaders);
              response.end(bytes);
            } catch {
              upstream.destroy();
              if (!finished) {
                finished = true;
                clearTimeout(timer);
                fail(response, 502, "Account response unavailable");
              }
            }
          },
        );
        const timer = setTimeout(() => {
          if (!finished) {
            finished = true;
            outgoing.destroy();
            fail(response, 504, "Account request timed out");
          }
        }, 15000);
        outgoing.on("error", () => {
          if (!finished) {
            finished = true;
            clearTimeout(timer);
            fail(response, 502, "Account service unavailable");
          }
        });
        response.on("close", () => {
          clearTimeout(timer);
          if (!finished) {
            finished = true;
            outgoing.destroy();
          }
        });
        outgoing.end(body);
        return;
      }
      if (
        url.pathname.startsWith("/api/") ||
        !["GET", "HEAD"].includes(request.method)
      )
        return fail(response, 404, "Not found");
      const root = await realpath(webRoot);
      let target = path.resolve(root, "." + url.pathname);
      if (url.pathname.endsWith("/")) target = path.join(target, "index.html");
      let actual;
      try {
        actual = await realpath(target);
      } catch {
        if (!path.extname(url.pathname)) actual = path.join(root, "index.html");
        else return fail(response, 404, "Not found");
      }
      if (!actual.startsWith(root + path.sep))
        return fail(response, 403, "Invalid path");
      const bytes = await readFile(actual);
      response.writeHead(200, {
        "Content-Type":
          mime[path.extname(actual)] || "application/octet-stream",
        "X-Content-Type-Options": "nosniff",
      });
      response.end(request.method === "HEAD" ? undefined : bytes);
    } catch {
      fail(response, 500, "Unable to serve request; run ./kotlin build first");
    }
  });
}

if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const port = Number(process.argv[2] || 8080);
  if (!Number.isInteger(port) || port < 1 || port > 65535)
    throw new Error("Port must be between 1 and 65535");
  await readFile(path.join(defaultWebRoot, "index.html"));
  createAccountServer().listen(port, "127.0.0.1", () =>
    console.log(`Krill Monitor: http://127.0.0.1:${port}`),
  );
}
