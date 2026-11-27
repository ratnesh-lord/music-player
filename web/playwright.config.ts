import { defineConfig, devices } from "@playwright/test";

// E2E against the production build (`npm run build` first). PW_CHROMIUM lets a sandbox reuse a
// preinstalled Chromium; CI installs the matching one with `npx playwright install chromium`.
export default defineConfig({
  testDir: "e2e",
  timeout: 30_000,
  reporter: [["list"]],
  use: {
    baseURL: "http://127.0.0.1:4173",
    ...devices["Desktop Chrome"],
    launchOptions: {
      args: ["--autoplay-policy=no-user-gesture-required"],
      ...(process.env.PW_CHROMIUM ? { executablePath: process.env.PW_CHROMIUM } : {}),
    },
  },
  webServer: { command: "npx vite preview --host 127.0.0.1 --port 4173 --strictPort", url: "http://127.0.0.1:4173", reuseExistingServer: true },
});
