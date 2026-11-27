import fs from "node:fs/promises";
import path from "node:path";
import assert from "node:assert/strict";
import { chromium, expect } from "@playwright/test";

// Runs against WebPreview's real HTTP APIs. Tokens are read from its private metadata file, never logged.
const metadata = JSON.parse(await fs.readFile(process.argv[2], "utf8"));
const reportFile = process.argv[3] ?? ".taste-skill/browser-report.json";
const results = [];
const browser = await chromium.launch({
  headless: true,
  timeout: 20_000,
  executablePath: process.env.CERBERUS_BROWSER || undefined,
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
    const message = String(error.message)
      .replaceAll(metadata.admin, "[token]")
      .replaceAll(metadata.read, "[token]");
    results.push({ name, passed: false, error: message });
    console.error(
      "Browser state",
      await page.evaluate(() => ({
        dialogs: [...document.querySelectorAll('[role="dialog"]')].map(
          (element) => ({
            state: element.getAttribute("data-state"),
            visibility: getComputedStyle(element).visibility,
            animation: getComputedStyle(element).animationName,
          }),
        ),
        focus: document.activeElement?.tagName,
        focusLabel: document.activeElement?.getAttribute("aria-label"),
        tooltips: document.querySelectorAll('[role="tooltip"]').length,
      })),
    );
    throw new Error(message);
  }
}
async function login(token = metadata.admin) {
  await page.getByLabel("Zugriffstoken", { exact: true }).fill(token);
  await page.getByRole("button", { name: "Mit Token anmelden" }).click();
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(
    "Dein Server im Überblick",
  );
}
async function navigate(name) {
  if (
    await page
      .getByRole("button", { name: "Navigation öffnen", exact: true })
      .isVisible()
  )
    await page
      .getByRole("button", { name: "Navigation öffnen", exact: true })
      .click();
  await page
    .getByRole("navigation", { name: "Hauptnavigation" })
    .getByRole("link", { name, exact: true })
    .click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
}
async function api(pathname, options = {}) {
  const response = await fetch(base + pathname, {
    ...options,
    headers: { Authorization: `Bearer ${metadata.admin}`, ...options.headers },
  });
  assert.ok(response.ok, `${pathname}: ${response.status}`);
  return response.json();
}
try {
  await page.goto(base, { waitUntil: "networkidle" });
  await check("login keyboard and field validation", async () => {
    await page.keyboard.press("Tab");
    await expect(page.locator(":focus")).toHaveText("Zum Inhalt");
    await page.keyboard.press("Tab");
    await expect(
      page.getByLabel("Zugriffstoken", { exact: true }),
    ).toBeFocused();
    assert.deepEqual(
      await page.locator(":focus").evaluate((element) => ({
        width: getComputedStyle(element).outlineWidth,
        style: getComputedStyle(element).outlineStyle,
      })),
      { width: "2px", style: "solid" },
    );
    await page.getByRole("button", { name: "Mit Token anmelden" }).click();
    await expect(page.getByRole("alert")).toContainText("Zugriffstoken");
    await expect(
      page.getByLabel("Zugriffstoken", { exact: true }),
    ).toBeFocused();
    await page
      .getByLabel("Zugriffstoken", { exact: true })
      .fill("invalid-token");
    await page.getByRole("button", { name: "Mit Token anmelden" }).click();
    await expect(page.getByRole("alert")).toContainText("ungültig");
  });
  await check(
    "login uses authenticated bot status and keeps token out of URL and browser storage",
    async () => {
      await login();
      const status = await api("/api/web/status");
      await expect(page.locator(".server-chip")).toContainText(
        status.serverName,
      );
      assert.ok(!page.url().includes(metadata.admin));
      assert.deepEqual(
        await page.evaluate(() => ({
          local: Object.keys(localStorage),
          session: Object.keys(sessionStorage),
          cookies: document.cookie,
        })),
        { local: [], session: [], cookies: "" },
      );
    },
  );
  await check("clients use current API data and name filtering", async () => {
    const clients = await api("/api/web/clients");
    await navigate("Clients");
    if (clients.length) {
      await expect(page.locator("tbody tr")).toHaveCount(clients.length);
      await page.getByLabel("Clients suchen").fill(clients[0].nickname);
      await expect(page.locator("tbody tr")).toHaveCount(
        clients.filter((client) =>
          client.nickname
            .toLowerCase()
            .includes(clients[0].nickname.toLowerCase()),
        ).length,
      );
    }
    await page
      .getByLabel("Clients suchen")
      .fill("keine-uebereinstimmung-cerberus-qa");
    await expect(
      page.getByRole("heading", { name: "Kein Client passt zur Suche" }),
    ).toBeVisible();
    await page.getByLabel("Clients suchen").fill("");
  });
  await check("channels select actual details", async () => {
    const channels = await api("/api/web/channels");
    await navigate("Channels");
    if (channels.length) {
      await page.locator(".channel-name").first().click();
      await expect(
        page.locator(".panel").filter({
          has: page.getByRole("heading", { name: "Channel-Details" }),
        }),
      ).toContainText(channels[0].name);
    }
  });
  for (const [width, height] of [
    [1440, 900],
    [1024, 768],
    [820, 1180],
    [768, 1024],
    [390, 844],
    [320, 640],
  ]) {
    await check(`core navigation and document bounds ${width}`, async () => {
      await page.setViewportSize({ width, height });
      await navigate("Übersicht");
      await expect(page.getByRole("heading", { level: 1 })).toHaveText(
        "Dein Server im Überblick",
      );
      assert.ok(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        `horizontal document overflow at ${width}`,
      );
      if (width < 1024) {
        const toggle = page.getByRole("button", {
          name: "Navigation öffnen",
          exact: true,
        });
        await toggle.click();
        await expect(page.getByRole("dialog")).toBeVisible();
        await expect(
          page
            .getByRole("dialog")
            .getByRole("link", { name: "Übersicht", exact: true }),
        ).toBeFocused();
        await page.keyboard.press("Escape");
        await expect(page.getByRole("dialog")).toHaveCount(0);
        await expect(toggle).toBeFocused();
      }
    });
  }
  await page.setViewportSize({ width: 1440, height: 900 });
  await check(
    "status fetch failure is shown and retry restores data",
    async () => {
      await page.route("**/api/web/status", (route) => route.abort("failed"));
      await page.getByRole("button", { name: "Daten aktualisieren" }).click();
      await expect(page.locator(".connection-label")).toHaveText(
        "Status unbekannt",
      );
      await expect(page.getByRole("alert")).toContainText("nicht erreichbar");
      await expect(
        page
          .locator(".panel")
          .filter({
            has: page.getByRole("heading", { name: "Verbindung", exact: true }),
          }),
      ).toContainText("Status unbekannt");
      await page.unroute("**/api/web/status");
      await page
        .getByRole("button", { name: "Abruf wiederholen" })
        .first()
        .click();
      await expect(page.locator(".connection-label")).toHaveText("Verbunden");
    },
  );
  if (metadata.mode === "fixture") {
    await check(
      "welcome plugin writes through its real protected API and shared refresh remounts it",
      async () => {
        await navigate("Begrüßung");
        await expect(page.getByLabel("Text vor dem Namen")).toBeVisible();
        await page.getByLabel("Text vor dem Namen").fill("Guten Abend");
        await page.getByRole("button", { name: "Begrüßung speichern" }).click();
        await expect(page.locator("#welcome-status")).toHaveText(
          "Begrüßung gespeichert.",
        );
        assert.equal(
          (await api("/api/plugins/welcome/settings")).greeting,
          "Guten Abend",
        );
        await api("/api/plugins/welcome/settings", {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ greeting: "Extern geändert" }),
        });
        await page.getByRole("button", { name: "Daten aktualisieren" }).click();
        await expect(page.getByLabel("Text vor dem Namen")).toHaveValue(
          "Extern geändert",
        );
      },
    );
    await check(
      "slow previous plugin mount cannot overwrite the next page",
      async () => {
        await navigate("Verspäteter Inhalt");
        await expect(page.locator('[data-plugin-page="qa/slow"]')).toHaveText(
          "Wird vorbereitet",
        );
        await navigate("Begrüßung");
        await expect(page.getByLabel("Text vor dem Namen")).toBeVisible();
        await page.waitForTimeout(850);
        await expect(
          page
            .locator("[data-plugin-page]")
            .getByText("Verspäteter Inhalt", { exact: true }),
        ).toHaveCount(0);
      },
    );
    await check(
      "plugin cleanup error leaves main navigation usable",
      async () => {
        await navigate("Fehler beim Aufräumen");
        await expect(
          page.getByText("Seite mit fehlerhaftem Cleanup"),
        ).toBeVisible();
        await navigate("Clients");
        await expect(page.getByLabel("Clients suchen")).toBeVisible();
      },
    );
    await check(
      "plugin mount failure clears partial content and offers retry",
      async () => {
        await navigate("Fehler beim Laden");
        await expect(page.getByRole("alert")).toContainText(
          "Erwarteter Pluginfehler",
        );
        await expect(
          page.getByText("UNVOLLSTAENDIG", { exact: true }),
        ).toHaveCount(0);
        await expect(
          page.getByRole("button", { name: "Plugin erneut laden" }),
        ).toBeVisible();
      },
    );
    await check(
      "plugin stylesheet failure is visible and retry loads it",
      async () => {
        await page.route(
          "**/plugin-assets/qa/stylesheet/**/style.css",
          (route) => route.fulfill({ status: 503, body: "unavailable" }),
        );
        await navigate("Stylesheet-Prüfung");
        await expect(page.getByRole("alert")).toContainText("Stylesheet");
        await page.unroute("**/plugin-assets/qa/stylesheet/**/style.css");
        await page.getByRole("button", { name: "Plugin erneut laden" }).click();
        await expect(
          page.getByText("Stylesheet geladen", { exact: true }),
        ).toBeVisible();
      },
    );
    await check(
      "page disposal aborts plugin request even with its own signal",
      async () => {
        const started = page.waitForRequest("**/api/plugins/qa/delay");
        const failed = page.waitForEvent("requestfailed", {
          predicate: (request) =>
            request.url().endsWith("/api/plugins/qa/delay"),
        });
        await navigate("Anfrage abbrechen");
        await started;
        await navigate("Clients");
        await failed;
        await expect(page.getByLabel("Clients suchen")).toBeVisible();
      },
    );
    await check("read-only token cannot save plugin settings", async () => {
      await page
        .getByRole("button", { name: "Vom Webinterface abmelden" })
        .click();
      await login(metadata.read);
      await navigate("Begrüßung");
      await page
        .getByLabel("Text vor dem Namen")
        .fill("Darf nicht gespeichert werden");
      await page.getByRole("button", { name: "Begrüßung speichern" }).click();
      await expect(page.locator("#welcome-status")).toContainText(
        "nicht berechtigt",
      );
      assert.equal(
        (await api("/api/plugins/welcome/settings")).greeting,
        "Extern geändert",
      );
    });
  }
  await check("logout clears session and reload stays logged out", async () => {
    await page
      .getByRole("button", { name: "Vom Webinterface abmelden" })
      .click();
    await expect(page.getByLabel("Zugriffstoken", { exact: true })).toHaveValue(
      "",
    );
    await page.reload();
    await expect(
      page.getByRole("button", { name: "Mit Token anmelden" }),
    ).toBeVisible();
  });
  await check(
    "login has no horizontal overflow at all target widths",
    async () => {
      for (const width of [1440, 1024, 820, 768, 390, 320]) {
        await page.setViewportSize({ width, height: width < 600 ? 844 : 900 });
        assert.ok(
          await page.evaluate(
            () => document.documentElement.scrollWidth <= innerWidth,
          ),
          `login overflow ${width}`,
        );
      }
    },
  );
  assert.deepEqual(uncaught, [], "uncaught browser errors");
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
console.log(`Browser checks passed: ${results.length}; mode=${metadata.mode}`);
