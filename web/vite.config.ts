import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// BASE_PATH is set by the GitHub Pages workflow ("/music-player/"); "/" for local dev and preview.
export default defineConfig({
  base: process.env.BASE_PATH ?? "/",
  plugins: [react(), tailwindcss()],
  build: { target: "es2022", sourcemap: true },
  test: { environment: "node", include: ["tests/**/*.test.ts"] },
});
