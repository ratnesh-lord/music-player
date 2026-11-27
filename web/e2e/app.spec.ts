import { expect, test } from "@playwright/test";

/** 1 s, 16-bit mono sine as a WAV file. */
function wav(seconds = 1, rate = 44100): Buffer {
  const n = seconds * rate;
  const b = Buffer.alloc(44 + n * 2);
  b.write("RIFF", 0); b.writeUInt32LE(36 + n * 2, 4); b.write("WAVE", 8); b.write("fmt ", 12);
  b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22); b.writeUInt32LE(rate, 24); b.writeUInt32LE(rate * 2, 28);
  b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34); b.write("data", 36); b.writeUInt32LE(n * 2, 40);
  for (let i = 0; i < n; i++) b.writeInt16LE(Math.round(Math.sin((2 * Math.PI * 440 * i) / rate) * 8000), 44 + i * 2);
  return b;
}

/** The same tone as a big-endian AIFF (Chromium can't play AIFF; the app converts it). */
function aiff(seconds = 1, rate = 44100): Buffer {
  const n = seconds * rate;
  const b = Buffer.alloc(54 + n * 2);
  b.write("FORM", 0); b.writeUInt32BE(46 + n * 2, 4); b.write("AIFF", 8);
  b.write("COMM", 12); b.writeUInt32BE(18, 16); b.writeUInt16BE(1, 20); b.writeUInt32BE(n, 22); b.writeUInt16BE(16, 26);
  b.writeUInt16BE(16383 + 15, 28); b.writeUInt32BE((rate << 16) >>> 0, 30); b.writeUInt32BE(0, 34); // 44100 = 1.3458… × 2^15
  b.write("SSND", 38); b.writeUInt32BE(8 + n * 2, 42); b.writeUInt32BE(0, 46); b.writeUInt32BE(0, 50);
  for (let i = 0; i < n; i++) b.writeInt16BE(Math.round(Math.sin((2 * Math.PI * 440 * i) / rate) * 8000), 54 + i * 2);
  return b;
}

test("imports local files, shows honest quality, plays WAV and converted AIFF", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "UNPAUSED" })).toBeVisible();
  await page.locator('input[type=file][accept^="audio"]').setInputFiles([
    { name: "Tone WAV.wav", mimeType: "audio/wav", buffer: wav() },
    { name: "Tone AIFF.aiff", mimeType: "audio/aiff", buffer: aiff() },
  ]);
  await expect(page.getByText("Tone WAV", { exact: true })).toBeVisible();
  await expect(page.getByText("Tone AIFF", { exact: true })).toBeVisible();

  await page.getByText("Tone WAV", { exact: true }).click();
  await expect(page.getByRole("button", { name: "Pause" })).toBeVisible({ timeout: 10_000 });
  await page.getByRole("button", { name: "Open player details" }).click();
  await page.getByRole("button", { name: "audio" }).click();
  await expect(page.getByText("WAV · 16-bit · 44.1 kHz", { exact: false })).toBeVisible();
  await expect(page.getByText("Not bit-perfect", { exact: false })).toBeVisible();
  await page.getByRole("button", { name: "Close" }).click();

  await page.getByText("Tone AIFF", { exact: true }).click();
  await expect(page.getByRole("button", { name: "Pause" })).toBeVisible({ timeout: 10_000 });
  await expect(page.getByRole("alert")).toHaveCount(0);
});

test("library survives a reload when copies are kept in the browser", async ({ page }) => {
  await page.goto("/");
  await page.locator('input[type=file][accept^="audio"]').setInputFiles([{ name: "Persisted.wav", mimeType: "audio/wav", buffer: wav() }]);
  await expect(page.getByText("Persisted", { exact: true })).toBeVisible();
  await page.reload();
  await page.getByText("Persisted", { exact: true }).click();
  await expect(page.getByRole("button", { name: "Pause" })).toBeVisible({ timeout: 10_000 });
});
