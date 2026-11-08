import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

const root = fileURLToPath(new URL("../", import.meta.url));
const version = readFileSync(new URL("../androidApp/module.yaml", import.meta.url), "utf8")
  .match(/^\s*versionName: "([0-9.]+)"$/m)[1];
function release(tag, extra = {}) {
  return spawnSync("bash", ["tools/release-apk.sh", tag], {
    cwd: root,
    env: {
      PATH: process.env.PATH,
      HOME: process.env.HOME,
      ANDROID_HOME: "/nonexistent-sdk",
      ...extra,
    },
    encoding: "utf8",
  });
}

test("release rejects mismatched or non-version tags before building", () => {
  for (const tag of ["v999.0.0", "main", `v${version}-unreviewed`]) {
    const result = release(tag);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /Release tag must match/);
  }
});

test("a matching tag still requires an existing signing key without logging its password", () => {
  const missing = release(`v${version}`);
  assert.notEqual(missing.status, 0);
  assert.match(missing.stderr, /ANDROID_KEYSTORE_PATH/);
  const absent = release(`v${version}`, {
    ANDROID_KEYSTORE_PATH: "/nonexistent-release-key.p12",
    ANDROID_KEYSTORE_PASSWORD: "synthetic-secret-never-print",
  });
  assert.equal(absent.status, 1);
  assert.match(absent.stderr, /Release keystore does not exist/);
  assert.doesNotMatch(absent.stdout + absent.stderr, /synthetic-secret-never-print/);
});
