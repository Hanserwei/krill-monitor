import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { EventEmitter } from "node:events";
import { Readable } from "node:stream";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { createAccountServer } from "./account-web.mjs";

async function fixture(
  t,
  responseBody = '{"success":true,"data":{}}',
  status = 200,
  fault = null,
) {
  const root = await mkdtemp(path.join(tmpdir(), "krill-web-"));
  await writeFile(path.join(root, "index.html"), "<html>Monitor</html>");
  await writeFile(path.join(root, "app.wasm"), "wasm");
  const calls = [];
  const transport = (options, callback) => {
    const outgoing = new EventEmitter();
    outgoing.destroy = () => {};
    outgoing.end = (body) => {
      calls.push({ ...options, body: body.toString() });
      queueMicrotask(() => {
        if (fault === "timeout") return;
        if (fault === "network")
          return outgoing.emit("error", new Error("private credential"));
        const upstream = Readable.from([Buffer.from(responseBody)]);
        upstream.statusCode = status;
        upstream.headers = {
          "set-cookie": "private-cookie",
          "retry-after": "60",
        };
        callback(upstream);
      });
    };
    return outgoing;
  };
  const server = createAccountServer(root, transport);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(async () => {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
    await rm(root, { recursive: true, force: true });
  });
  return { origin: `http://127.0.0.1:${server.address().port}`, calls };
}

test("only whitelisted requests go to the fixed official host without cookies", async (t) => {
  const { origin, calls } = await fixture(t);
  const response = await fetch(origin + "/krill-account/api/auth/login", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Cookie: "private-browser-cookie",
    },
    body: '{"email":"test@example.test","password":"test-only"}',
  });
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("set-cookie"), null);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.equal(calls[0].hostname, "www.krill-code.com");
  assert.equal(calls[0].headers.Cookie, undefined);
  assert.equal(calls[0].path, "/api/auth/login");
  assert.equal(JSON.parse(calls[0].body).password, "test-only");
  const read = await fetch(origin + "/krill-account/api/credits", {
    headers: { Authorization: "Bearer fake-jwt", Cookie: "private-cookie" },
  });
  assert.equal(read.status, 200);
  assert.equal(calls[1].headers.Authorization, "Bearer fake-jwt");
  assert.equal(calls[1].headers.Cookie, undefined);
});

test("email-code endpoints and public settings do not forward stale authorization", async (t) => {
  const { origin, calls } = await fixture(t);
  for (const [route, body] of [
    ["/api/public/auth-settings", null],
    ["/api/auth/login/send-code", { email: "demo@example.test" }],
    [
      "/api/auth/login/verify",
      { email: "demo@example.test", verification_code: "123456" },
    ],
  ]) {
    const response = await fetch(origin + "/krill-account" + route, {
      method: body === null ? "GET" : "POST",
      headers: {
        Authorization: "Bearer stale-test-session",
        "Content-Type": "application/json",
        Cookie: "private-cookie",
      },
      body: body === null ? undefined : JSON.stringify(body),
    });
    assert.equal(response.status, 200);
    assert.equal(calls.at(-1).path, route);
    assert.equal(calls.at(-1).headers.Authorization, undefined);
    assert.equal(calls.at(-1).headers.Cookie, undefined);
    if (body !== null) assert.deepEqual(JSON.parse(calls.at(-1).body), body);
  }
});

test("no open proxy, third-party origin, query parameters or cookie-only authorization", async (t) => {
  const { origin, calls } = await fixture(t);
  for (const [route, options, expected] of [
    ["/krill-account/api/auth/accept-tos", { method: "POST" }, 405],
    ["/krill-account/api/credits?target=evil", {}, 405],
    [
      "/krill-account/api/credits",
      { headers: { Cookie: "krill_jwt=cookie" } },
      401,
    ],
    [
      "/krill-account/api/auth/login",
      { method: "POST", headers: { Origin: "https://evil.example" } },
      403,
    ],
  ])
    assert.equal((await fetch(origin + route, options)).status, expected);
  assert.equal(calls.length, 0);
});

test("upstream authorization and rate-limit statuses survive, redirects do not", async (t) => {
  for (const status of [401, 429, 302]) {
    const { origin } = await fixture(t, '{"success":false}', status);
    const response = await fetch(origin + "/krill-account/api/credits", {
      headers: { Authorization: "Bearer fake-jwt" },
    });
    assert.equal(response.status, status === 302 ? 502 : status);
    if (status === 429) assert.equal(response.headers.get("retry-after"), "60");
  }
});

test("oversized upstream responses are rejected and static assets have the correct MIME", async (t) => {
  const { origin } = await fixture(t, "x".repeat(2 * 1024 * 1024 + 1));
  const response = await fetch(origin + "/krill-account/api/credits", {
    headers: { Authorization: "Bearer fake-jwt" },
  });
  assert.equal(response.status, 502);
  assert.equal(
    (await fetch(origin + "/app.wasm")).headers.get("content-type"),
    "application/wasm",
  );
  assert.match(await (await fetch(origin + "/settings")).text(), /Monitor/);
  assert.equal((await fetch(origin + "/%2e%2e%2fprivate")).status, 403);
  assert.equal((await fetch(origin + "/api/subscription")).status, 404);
});

test("oversized login requests never reach the upstream", async (t) => {
  const { origin, calls } = await fixture(t);
  const response = await fetch(origin + "/krill-account/api/auth/login", {
    method: "POST",
    body: "x".repeat(65537),
  });
  assert.equal(response.status, 413);
  assert.equal(calls.length, 0);
});

test("upstream network failures return a safe error", async (t) => {
  const { origin } = await fixture(t, "", 200, "network");
  const response = await fetch(origin + "/krill-account/api/credits", {
    headers: { Authorization: "Bearer fake-jwt" },
  });
  assert.equal(response.status, 502);
  assert.doesNotMatch(await response.text(), /private credential|fake-jwt/);
});

test("a stalled upstream is stopped at the deadline", async (t) => {
  const { origin, calls } = await fixture(t, "", 200, "timeout");
  t.mock.timers.enable({ apis: ["setTimeout"] });
  try {
    const pending = fetch(origin + "/krill-account/api/credits", {
      headers: { Authorization: "Bearer fake-jwt" },
    });
    while (calls.length === 0)
      await new Promise((resolve) => setImmediate(resolve));
    t.mock.timers.tick(15001);
    assert.equal((await pending).status, 504);
  } finally {
    t.mock.timers.reset();
  }
});

test("public channel status allows only the anonymous 24 hour query", async (t) => {
  const { origin, calls } = await fixture(t);
  const route = "/krill-account/api/public/channel-status";
  for (const headers of [{}, { Authorization: "Bearer private", Cookie: "private-cookie" }]) {
    assert.equal((await fetch(origin + route + "?hours=24", { headers })).status, 200);
    assert.equal(calls.at(-1).path, "/api/public/channel-status?hours=24");
    assert.equal(calls.at(-1).headers.Authorization, undefined);
    assert.equal(calls.at(-1).headers.Cookie, undefined);
  }
  for (const query of ["", "?hours=72", "?hours=168", "?hours=24&hours=24", "?hours=24&x=1", "?x=24"])
    assert.equal((await fetch(origin + route + query)).status, 405);
  assert.equal((await fetch(origin + route + "?hours=24", { method: "POST" })).status, 405);
  assert.equal(calls.length, 2);
});

test("only public channel status accepts responses above the private limit", async (t) => {
  const big = await fixture(t, "x".repeat(2 * 1024 * 1024 + 1));
  assert.equal((await fetch(big.origin + "/krill-account/api/public/channel-status?hours=24")).status, 200);
  assert.equal((await fetch(big.origin + "/krill-account/api/credits", { headers: { Authorization: "Bearer test" } })).status, 502);
  const excessive = await fixture(t, "x".repeat(8 * 1024 * 1024 + 1));
  assert.equal((await fetch(excessive.origin + "/krill-account/api/public/channel-status?hours=24")).status, 502);
});

test("line probes use only fixed hosts without credentials", async (t) => {
  const { origin, calls } = await fixture(t, '{}', 401);
  for (const [name, host] of [["overseas", "api.kbsfdxc.net"], ["china", "api-slb.kbsfdxc.net"], ["cdn", "api.kbsfdxc.com"]]) {
    const response = await fetch(`${origin}/krill-lines/${name}`, {
      headers: { Authorization: "Bearer private", Cookie: "private-cookie", Origin: origin },
    });
    assert.equal(response.status, 401);
    assert.equal(response.headers.get("set-cookie"), null);
    assert.equal(calls.at(-1).hostname, host);
    assert.equal(calls.at(-1).path, "/v1/models");
    assert.equal(calls.at(-1).method, "GET");
    assert.equal(calls.at(-1).headers.Authorization, undefined);
    assert.equal(calls.at(-1).headers.Cookie, undefined);
  }
  for (const [route, options, status] of [
    ["/krill-lines/other", {}, 405],
    ["/krill-lines/overseas/extra", {}, 405],
    ["/krill-lines/overseas?target=evil", {}, 405],
    ["/krill-lines/overseas", { method: "POST" }, 405],
    ["/krill-lines/overseas", { headers: { Origin: "https://evil.example" } }, 403],
    ["/krill-lines/overseas", { headers: { Origin: origin.replace("http:", "https:") } }, 403],
    ["/krill-account/api/tokens", { headers: { Authorization: "Bearer private" } }, 405],
  ]) assert.equal((await fetch(origin + route, options)).status, status);
  for (const [route, headers, status] of [
    ["/krill-lines/overseas", { Host: "evil.example" }, 421],
    ["/krill-lines/overseas?", {}, 405],
  ]) {
    const actual = await new Promise((resolve, reject) => {
      const request = http.get(origin, { path: route, headers }, (response) => {
        response.resume();
        resolve(response.statusCode);
      });
      request.on("error", reject);
    });
    assert.equal(actual, status);
  }
  assert.equal(calls.length, 3);
});

test("line probe redirects and network failures are safe", async (t) => {
  for (const [status, fault] of [[302, null], [200, "network"]]) {
    const { origin, calls } = await fixture(t, "private credential", status, fault);
    const response = await fetch(origin + "/krill-lines/overseas");
    assert.equal(response.status, 502);
    assert.doesNotMatch(await response.text(), /private credential/);
    assert.equal(calls.length, 1);
  }
});
