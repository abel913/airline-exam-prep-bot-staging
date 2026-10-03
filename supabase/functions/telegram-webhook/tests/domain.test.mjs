import test from "node:test";
import assert from "node:assert/strict";
import {
  constantTimeTextEqual,
  decodePhoneIdentityKey,
  hmacSha256Hex,
  normalizeEthiopianPhone,
  phoneIdentity,
  phoneKeyFingerprint,
  requiredConfiguration,
} from "../domain.mjs";

const fixtureKey = "dGVzdC1vbmx5LWtleS0zMi1ieXRlcy1ub3QtYS1zZWNyZXQ=";
const expectedPhoneHmac = "58c00037a5d53df2352ca0fd6e3588a8a948ae94d0c9813d0b83e721288979cf";
const expectedFingerprint = "daaf96344e5e0f133fb7c34f0fca6701718e634d6ddb8ee355d499c186ff97a7";

test("Ethiopian phone formats match Spring's canonicalization", () => {
  for (const input of ["0912345678", "+251912345678", "251912345678", "09 1234 5678", "+251 (91) 234-5678"]) {
    assert.equal(normalizeEthiopianPhone(input), "+251912345678");
  }
  assert.equal(normalizeEthiopianPhone("0712345678"), "+251712345678");
});

test("rejects phone formats Spring rejects", () => {
  for (const input of ["", "123", "+254912345678", "0812345678", "09123456789", "+2510912345678", "0912abc678", "++251912345678", null, "9".repeat(33)]) {
    assert.throws(() => normalizeEthiopianPhone(input), { message: "INVALID_ETHIOPIAN_PHONE" });
  }
});

test("HMAC matches the deterministic Spring compatibility fixture", async () => {
  const key = decodePhoneIdentityKey(fixtureKey);
  assert.equal(key.length, 35);
  assert.equal(await phoneIdentity(key, normalizeEthiopianPhone("0912345678")), expectedPhoneHmac);
  assert.equal(await phoneIdentity(key, normalizeEthiopianPhone("+251912345678")), expectedPhoneHmac);
  assert.equal(await phoneKeyFingerprint(key), expectedFingerprint);
  assert.equal(await hmacSha256Hex(key, "phone:+251712345678"), "4556fa102b5a987a681c712421fd4b5f43f2e1574ea71b8524d02421cae95c13");
});

test("HMAC identity key must be valid Base64 with at least 32 bytes", () => {
  assert.throws(() => decodePhoneIdentityKey("not_base64!"));
  assert.throws(() => decodePhoneIdentityKey("dGVzdA=="));
});

test("webhook secret comparison checks equal, unequal, and unequal-length values", () => {
  assert.equal(constantTimeTextEqual("staging_secret-123", "staging_secret-123"), true);
  assert.equal(constantTimeTextEqual("staging_secret-123", "staging_secret-124"), false);
  assert.equal(constantTimeTextEqual("staging_secret-123", "short"), false);
});

test("runtime configuration requires a transaction pooler URL and independent secrets", () => {
  const base = {
    TELEGRAM_BOT_TOKEN: "test-token",
    TELEGRAM_WEBHOOK_SECRET: "test-secret",
    PHONE_IDENTITY_HMAC_KEY: fixtureKey,
    DATABASE_URL: "postgresql://test:placeholder@localhost:6543/postgres",
  };
  assert.equal(requiredConfiguration(base).ok, true);
  assert.equal(requiredConfiguration({ ...base, DATABASE_URL: base.DATABASE_URL.replace(":6543", ":5432") }).ok, false);
  assert.equal(requiredConfiguration({ ...base, TELEGRAM_WEBHOOK_SECRET: "" }).webhookConfigured, false);
  assert.equal(requiredConfiguration({ ...base, PHONE_IDENTITY_HMAC_KEY: "dGVzdA==" }).ok, false);
});
