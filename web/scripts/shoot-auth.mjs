import fs from "node:fs/promises";
import path from "node:path";
import { spawn } from "node:child_process";
import { chromium, expect } from "@playwright/test";

// The unchanged Taste script owns the only Chrome process and takes the screenshot.
// This helper attaches to that browser solely to log in through the normal form.
const [
  metadataFile,
  tasteDirectory,
  widthText = "1440",
  route = "/",
  output = "shots/app-1440.png",
] = process.argv.slice(2);
if (!metadataFile || !tasteDirectory)
  throw new Error(
    "Use: shoot-auth.mjs metadata.json taste-directory width route output.png",
  );
const metadata = JSON.parse(await fs.readFile(metadataFile, "utf8"));
const width = Number(widthText);
if (![1440, 1024, 820, 768, 390, 320].includes(width))
  throw new Error("Unsupported viewport");
await fs.mkdir(".taste-skill", { recursive: true });
// Fresh profiles prevent a restored previous tab from being mistaken for Taste's capture target.
const profile = await fs.mkdtemp(
  path.resolve(`.taste-skill/chrome-auth-${width}-`),
);
const child = spawn(
  process.execPath,
  [
    path.join(tasteDirectory, "scripts/shoot.mjs"),
    "--url",
    metadata.url,
    "--w",
    String(width),
    "--out",
    output,
    "--profile",
    profile,
    "--no-tiles",
  ],
  { stdio: ["ignore", "pipe", "pipe"] },
);
child.stdout.pipe(process.stdout);
child.stderr.pipe(process.stderr);
let saved = false;
let savedAt = 0;
let completedAfterBrowserClose = false;
child.stdout.on("data", (data) => {
  if (String(data).includes(`saved ${output} (`)) {
    saved = true;
    savedAt = Date.now();
    // Capture is complete. Release only the browser created by this Taste process.
    if (browser?.isConnected()) void browser.close().catch(() => {});
  }
});
let exitCode;
const exited = new Promise((resolve) => {
  child.once("exit", (code) => {
    exitCode = code;
    resolve(code);
  });
});
const watchdog = setTimeout(() => child.kill("SIGTERM"), 540_000);
let browser;
let browserPid;
// CDP can already be disconnected before its one-shot event is observed.
// End only our exact Taste Node child after capture AND confirmed browser closure.
const release = setInterval(() => {
  if (!saved || exitCode !== undefined) return;
  let browserExited = false;
  if (browserPid) {
    try {
      process.kill(browserPid, 0);
      if (Date.now() - savedAt > 5000) process.kill(browserPid, "SIGTERM");
    } catch (error) {
      if (error.code === "ESRCH") browserExited = true;
    }
  }
  if (browserExited || (browser && !browser.isConnected())) {
    completedAfterBrowserClose = true;
    child.kill("SIGTERM");
  }
}, 500);
try {
  let port;
  const deadline = Date.now() + 20_000;
  while (!port && Date.now() < deadline && exitCode === undefined) {
    try {
      port = Number(
        (
          await fs.readFile(path.join(profile, "DevToolsActivePort"), "utf8")
        ).split("\n")[0],
      );
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
  }
  if (!port) throw new Error("Taste Chrome did not start");
  browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`, {
    timeout: 10_000,
  });
  const session = await browser.newBrowserCDPSession();
  const processes = await session.send("SystemInfo.getProcessInfo");
  browserPid = processes.processInfo.find(
    (process) => process.type === "browser",
  )?.id;
  await session.detach();
  browser.once("disconnected", () => {
    // shoot.mjs closes Chrome in its finally block but can retain a Node WebSocket handle.
    // Only end that exact child after the saved-image message AND browser disconnection.
    if (saved)
      setTimeout(() => {
        if (exitCode === undefined) {
          completedAfterBrowserClose = true;
          console.log(
            "Taste Chrome closed after saving; ending its lingering Node helper",
          );
          child.kill("SIGTERM");
        }
      }, 1000).unref();
  });
  let page;
  while (!page && Date.now() < deadline) {
    page = browser
      .contexts()
      .flatMap((context) => context.pages())
      .find((candidate) => candidate.url().startsWith(metadata.url));
    if (!page) await new Promise((resolve) => setTimeout(resolve, 30));
  }
  if (!page) throw new Error("Taste target page did not open");
  if (route !== "login") {
    await page
      .getByLabel("Zugriffstoken", { exact: true })
      .fill(metadata.admin, { timeout: 5000 });
    await page.getByRole("button", { name: "Mit Token anmelden" }).click();
    await expect(page.getByRole("heading", { level: 1 })).toHaveText(
      "Dein Server im Überblick",
    );
    if (route !== "/") {
      const toggle = page.getByRole("button", {
        name: "Navigation öffnen",
        exact: true,
      });
      if (await toggle.isVisible()) await toggle.click();
      await page
        .getByRole("navigation", { name: "Hauptnavigation" })
        .locator(`a[href="#${route}"]`)
        .click();
      await expect(page.getByRole("dialog")).toHaveCount(0);
      if (route.startsWith("/plugins/"))
        await expect(page.locator("[data-plugin-page]")).toHaveAttribute(
          "aria-busy",
          "false",
        );
    }
    if (metadata.mode === "fixture" && route === "/channels") {
      await page.locator(".channel-name").first().click();
      await expect(
        page.getByLabel("Channel-Name", { exact: true }),
      ).toBeVisible();
    }
    if (metadata.mode === "fixture" && route === "/groups") {
      await page.locator(".admin-list-item").first().click();
      await expect(
        page.getByLabel("Gruppenname", { exact: true }),
      ).toBeVisible();
    }
    await expect(page.locator(".connection-label")).toHaveText("Verbunden");
    console.log(
      `Authenticated through normal form: viewport=${width}; route=${route}; tokens omitted`,
    );
  } else {
    await expect(
      page.getByRole("button", { name: "Mit Token anmelden" }),
    ).toBeVisible();
    console.log(`Public login page: viewport=${width}`);
  }
  const code = await exited;
  if (code !== 0 && !completedAfterBrowserClose)
    throw new Error(`Taste shoot exited ${code}`);
  const signature = (await fs.readFile(output)).subarray(0, 8);
  if (!signature.equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])))
    throw new Error("Taste output is not a PNG");
} catch (error) {
  child.kill("SIGTERM");
  // This is the Taste-owned browser, not a separately launched browser or a user session.
  if (browser?.isConnected()) await browser.close().catch(() => {});
  await exited;
  throw new Error(
    String(error.message)
      .replaceAll(metadata.admin, "[token]")
      .replaceAll(metadata.read, "[token]"),
  );
} finally {
  clearTimeout(watchdog);
  clearInterval(release);
}
