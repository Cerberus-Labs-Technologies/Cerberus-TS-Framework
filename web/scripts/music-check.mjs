import fs from "node:fs/promises";
import path from "node:path";
import assert from "node:assert/strict";
import { chromium, expect } from "@playwright/test";

const metadata = JSON.parse(await fs.readFile(process.argv[2], "utf8"));
if (metadata.mode !== "fixture")
  throw new Error("Music browser checks only run on the isolated fixture.");
const reportFile =
  process.argv[3] || "../docs/validation/music-web-browser.json";
const results = [];
const browser = await chromium.launch({
  headless: true,
  executablePath: process.env.CERBERUS_BROWSER,
  args: ["--use-angle=swiftshader", "--enable-unsafe-swiftshader"],
});
const context = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  locale: "de-DE",
  reducedMotion: "reduce",
});
const page = await context.newPage();
const uncaught = [];
page.on("pageerror", (error) => uncaught.push(error.message));
const base = metadata.url.replace(/\/$/, "");
async function check(name, action) {
  try {
    await action();
    results.push({ name, passed: true });
    console.log("PASS " + name);
  } catch (error) {
    results.push({
      name,
      passed: false,
      error: String(error.message)
        .replaceAll(metadata.admin, "[token]")
        .replaceAll(metadata.read, "[token]"),
    });
    throw new Error(results.at(-1).error);
  }
}
async function api(
  endpoint,
  body,
  token = metadata.admin,
  method = body ? "POST" : "GET",
) {
  const response = await fetch(base + "/api/plugins/music" + endpoint, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  assert.ok(response.ok, `${endpoint}: HTTP ${response.status}`);
  return response.status === 204 ? null : response.json();
}
async function login(token = metadata.admin) {
  await page.getByLabel("Zugriffstoken", { exact: true }).fill(token);
  await page.getByRole("button", { name: "Mit Token anmelden" }).click();
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(
    "Dein Server im Überblick",
  );
}
async function navigate(name) {
  const toggle = page.getByRole("button", {
    name: "Navigation öffnen",
    exact: true,
  });
  if (await toggle.isVisible()) await toggle.click();
  await page
    .getByRole("navigation", { name: "Hauptnavigation" })
    .getByRole("link", { name, exact: true })
    .click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
}
async function ready() {
  await expect(
    page.locator('[data-plugin-page="music/control"]'),
  ).toHaveAttribute("aria-busy", "false");
  await expect(
    page.locator('[data-plugin-page="music/control"] > div'),
  ).toHaveAttribute("aria-busy", "false");
}
async function click(name) {
  await page.getByRole("button", { name, exact: true }).click();
  await ready();
}
try {
  const directory = path.join(metadata.data, "music", "files");
  await fs.mkdir(directory, { recursive: true });
  const pcm = Buffer.alloc(48000 * 4 * 60);
  const header = Buffer.alloc(44);
  header.write("RIFF");
  header.writeUInt32LE(pcm.length + 36, 4);
  header.write("WAVEfmt ", 8);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20);
  header.writeUInt16LE(2, 22);
  header.writeUInt32LE(48000, 24);
  header.writeUInt32LE(192000, 28);
  header.writeUInt16LE(4, 32);
  header.writeUInt16LE(16, 34);
  header.write("data", 36);
  header.writeUInt32LE(pcm.length, 40);
  await fs.writeFile(
    path.join(directory, "ui-test.wav"),
    Buffer.concat([header, pcm]),
  );
  for (const bot of await api("/bots"))
    await api(
      `/bots/${encodeURIComponent(bot.id)}`,
      undefined,
      metadata.admin,
      "DELETE",
    );
  await page.goto(base);
  await login();
  await navigate("Musik-Bots");
  await check("plugin page loads actual assets and empty state", async () => {
    await ready();
    await expect(page.locator("#music-empty")).toBeVisible();
    await expect(
      page.getByRole("button", { name: "Jetzt abspielen", exact: true }),
    ).toBeDisabled();
    await expect(
      page.getByLabel("Start-Channel", { exact: true }),
    ).toContainText("Musik (#2)");
  });
  await check("connect a bot through the form and select it", async () => {
    await page.getByLabel("Bot-ID", { exact: true }).fill("radio");
    await page.getByLabel("Anzeigename", { exact: true }).fill("Web Test");
    await page.getByLabel("Start-Channel", { exact: true }).selectOption("2");
    await click("Bot verbinden");
    await expect(page.locator("#music-client")).toContainText("Bot radio");
    assert.equal((await api("/bots"))[0].connected, true);
  });
  await check("play real local WAV and queue a second source", async () => {
    await page.getByLabel("Musikquelle", { exact: true }).fill("ui-test.wav");
    await click("Jetzt abspielen");
    await expect(page.locator("#music-phase")).toHaveText("Spielt", {
      timeout: 10000,
    });
    await click("Pausieren");
    await expect(page.locator("#music-phase")).toHaveText("Pausiert");
    await click("Zur Queue hinzufügen");
    await expect(page.locator("#music-queue li")).toHaveText(["ui-test.wav"]);
    assert.equal((await api("/bots"))[0].paused, true);
  });
  await check(
    "polling preserves unfinished input and keyboard focus",
    async () => {
      const source = page.getByLabel("Musikquelle", { exact: true });
      await source.fill("https://radio.example/noch-nicht-gesendet");
      await source.focus();
      await page.waitForTimeout(4300);
      await expect(source).toBeFocused();
      await expect(source).toHaveValue(
        "https://radio.example/noch-nicht-gesendet",
      );
      assert.equal(
        await source.evaluate((el) => getComputedStyle(el).outlineStyle),
        "solid",
      );
    },
  );
  await check(
    "volume and channel controls submit actual plugin actions",
    async () => {
      await page.getByLabel("Lautstärke in %", { exact: true }).fill("25");
      await click("Lautstärke setzen");
      assert.equal((await api("/bots"))[0].volume, 25);
      await page.getByLabel("Ziel-Channel", { exact: true }).selectOption("3");
      await click("In Channel verschieben");
      await expect(page.locator("#music-notice")).toHaveText(
        "Musik-Bot verschoben.",
      );
    },
  );
  await check("resume skip and stop update queue and playback", async () => {
    await click("Fortsetzen");
    await expect(page.locator("#music-phase")).toHaveText("Spielt");
    await click("Nächster Titel");
    await expect(page.locator("#music-queue li")).toHaveCount(0);
    await click("Stoppen & Queue leeren");
    await expect.poll(async () => (await api("/bots"))[0].current).toBeNull();
    await expect(page.locator("#music-phase")).toHaveText("Bereit", {
      timeout: 7000,
    });
  });
  await check("asynchronous media failure is visible", async () => {
    await page
      .getByLabel("Musikquelle", { exact: true })
      .fill("missing-ui-test.wav");
    await click("Jetzt abspielen");
    await expect(page.locator("#music-playback-error")).toBeVisible({
      timeout: 7000,
    });
    assert.ok((await api("/bots"))[0].error);
  });
  for (const width of [1440, 1024, 820, 768, 390, 320])
    await check(`music page bounds and actions at ${width}px`, async () => {
      await page.setViewportSize({ width, height: width < 600 ? 844 : 900 });
      assert.ok(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        `overflow at ${width}`,
      );
      await expect(
        page.getByRole("button", { name: "Jetzt abspielen", exact: true }),
      ).toHaveCSS("min-height", "44px");
      await navigate("Übersicht");
      await navigate("Musik-Bots");
      await ready();
    });
  await check(
    "read token cannot change playback through the browser",
    async () => {
      await page.setViewportSize({ width: 1440, height: 900 });
      await page
        .getByRole("button", { name: "Vom Webinterface abmelden", exact: true })
        .click();
      await login(metadata.read);
      await navigate("Musik-Bots");
      await ready();
      const before = (await api("/bots"))[0].volume;
      await page.getByLabel("Lautstärke in %", { exact: true }).fill("70");
      await click("Lautstärke setzen");
      await expect(page.locator("#music-error")).toContainText(
        "nicht berechtigt",
      );
      assert.equal((await api("/bots"))[0].volume, before);
      await page
        .getByRole("button", { name: "Vom Webinterface abmelden", exact: true })
        .click();
      await login();
      await navigate("Musik-Bots");
      await ready();
    },
  );
  await check(
    "disconnect selected bot removes its actual voice connection",
    async () => {
      await click("Bot trennen");
      await expect(page.locator("#music-empty")).toBeVisible();
      assert.deepEqual(await api("/bots"), []);
    },
  );
  await check("navigation stops plugin polling", async () => {
    await navigate("Übersicht");
    let requests = 0;
    const listener = (request) => {
      if (request.url().endsWith("/api/plugins/music/bots")) requests++;
    };
    page.on("request", listener);
    await page.waitForTimeout(4500);
    page.off("request", listener);
    assert.equal(requests, 0);
  });
  // Leave real fixture state ready for separate Taste-owned screenshots.
  await api("/bots", { id: "radio", nickname: "Web Radio", channelId: 2 });
  await api("/bots/radio/actions", { action: "play", source: "ui-test.wav" });
  await expect.poll(async () => (await api("/bots"))[0].phase).toBe("PLAYING");
  await api("/bots/radio/actions", { action: "pause" });
  await api("/bots/radio/actions", {
    action: "enqueue",
    source: "ui-test.wav",
  });
  await api("/bots/radio/actions", { action: "volume", volume: 25 });
  assert.deepEqual(uncaught, []);
} finally {
  await browser.close();
  await fs.mkdir(path.dirname(reportFile), { recursive: true });
  await fs.writeFile(
    reportFile,
    JSON.stringify(
      {
        mode: metadata.mode,
        date: new Date().toISOString(),
        results,
        uncaught,
      },
      null,
      2,
    ),
  );
}
console.log(`Music browser checks passed: ${results.length}`);
