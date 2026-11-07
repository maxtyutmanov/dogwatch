import { defineConfig } from "vite";

// `npm run dev` proxies the API to the dev agent (`./gradlew :core:run`) or a real phone:
//   DOGWATCH_AGENT=http://100.x.y.z:8080 npm run dev
const agent = process.env.DOGWATCH_AGENT ?? "http://localhost:8080";

export default defineConfig({
  oxc: {
    jsx: { runtime: "automatic", importSource: "preact" },
  },
  server: {
    proxy: {
      "/api": { target: agent, ws: true, changeOrigin: true },
    },
  },
  build: {
    outDir: "dist",
    assetsDir: "assets",
    target: "es2020",
  },
});
