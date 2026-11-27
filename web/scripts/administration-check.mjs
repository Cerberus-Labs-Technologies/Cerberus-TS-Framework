import fs from "node:fs/promises";
import path from "node:path";
import assert from "node:assert/strict";
import { chromium, expect } from "@playwright/test";

const metadata = JSON.parse(await fs.readFile(process.argv[2], "utf8"));
if (metadata.mode !== "fixture")
  throw new Error(
    "Mutating administration checks require the isolated fixture.",
  );
const reportFile =
  process.argv[3] || "../docs/validation/web-administration-browser.json";
const results = [],
  uncaught = [];
const browser = await chromium.launch({
  headless: true,
  executablePath: process.env.CERBERUS_BROWSER,
  args: ["--use-angle=swiftshader", "--enable-unsafe-swiftshader"],
});
const page = await browser.newPage({
  viewport: { width: 1440, height: 900 },
  locale: "de-DE",
  reducedMotion: "reduce",
});
page.on("pageerror", (error) => uncaught.push(error.message));
const base = metadata.url.replace(/\/$/, "");
async function check(name, fn) {
  try {
    await fn();
    results.push({ name, passed: true });
    console.log("PASS " + name);
  } catch (error) {
    const message = String(error.message)
      .replaceAll(metadata.admin, "[token]")
      .replaceAll(metadata.read, "[token]");
    results.push({ name, passed: false, error: message });
    throw new Error(message);
  }
}
async function api(endpoint, body, method = body ? "POST" : "GET") {
  const response = await fetch(base + "/api/web" + endpoint, {
    method,
    headers: {
      Authorization: `Bearer ${metadata.admin}`,
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
async function submit(button, endpoint, method, status = 204) {
  const pending = page.waitForResponse(
    (r) =>
      r.url() === base + "/api/web" + endpoint &&
      r.request().method() === method,
  );
  await page.getByRole("button", { name: button, exact: true }).click();
  const response = await pending;
  if (response.status() !== status)
    assert.fail(
      `Expected HTTP ${status}, received ${response.status()}: ${await response.text().catch(() => "response body unavailable")}`,
    );
  return status === 200 ? response.json() : null;
}
async function channel(name) {
  await page.locator(".channel-name").filter({ hasText: name }).click();
  await expect(page.getByLabel("Channel-Name", { exact: true })).toHaveValue(
    name,
  );
}
async function group(name) {
  await page.locator(".admin-list-item").filter({ hasText: name }).click();
  await expect(page.getByLabel("Gruppenname", { exact: true })).toHaveValue(
    name,
  );
}
let channelId, serverGroupId, channelGroupId;
try {
  // Each fixture is private to this harness; remove only objects created by these tests on retries.
  for (const kind of ["server", "channel"])
    for (const item of await api(`/groups/${kind}`))
      if (item.id >= 40)
        await api(
          `/groups/${kind}/${item.id}`,
          { expectedName: item.name, force: true },
          "DELETE",
        );
  for (const item of (await api("/channels"))
    .filter((c) => c.id >= 40)
    .reverse())
    await api(`/channels/${item.id}`, { expectedName: item.name }, "DELETE");
  await page.goto(base);
  await login();
  await check(
    "create channel with topic and description through real HTTP API",
    async () => {
      await navigate("Channels");
      await page
        .getByRole("button", { name: "Neuer Channel", exact: true })
        .click();
      await page
        .getByLabel("Channel-Name", { exact: true })
        .fill("Browser Lounge");
      await page.getByLabel("Thema", { exact: true }).fill("Radio und Musik");
      await page
        .getByLabel("Beschreibung", { exact: true })
        .fill(
          "Ein Channel aus der Browserprüfung.\n<img src=x onerror=\"throw new Error('unsafe')\">",
        );
      const result = await submit(
        "Channel erstellen",
        "/channels",
        "POST",
        200,
      );
      channelId = result.id;
      await expect(
        page.getByLabel("Channel-Name", { exact: true }),
      ).toHaveValue("Browser Lounge");
      assert.equal(
        (await api(`/channels/${channelId}`)).topic,
        "Radio und Musik",
      );
      assert.equal(await page.locator(".admin-body img").count(), 0);
    },
  );
  await check(
    "edit channel settings and redact password from responses",
    async () => {
      await page
        .getByLabel("Channel-Name", { exact: true })
        .fill("Browser Radio");
      await page.getByLabel("Thema", { exact: true }).fill("Neues Thema");
      await page
        .locator("summary")
        .filter({ hasText: "Audio und Passwort" })
        .click();
      await page.getByLabel("Passwort ändern", { exact: true }).check();
      await page
        .getByLabel("Neues Channel-Passwort", { exact: true })
        .fill("fixture-private-password");
      await submit("Channel speichern", `/channels/${channelId}`, "PUT", 200);
      await expect(
        page.getByLabel("Channel-Name", { exact: true }),
      ).toHaveValue("Browser Radio");
      const detail = await api(`/channels/${channelId}`);
      assert.equal(detail.hasPassword, true);
      assert.ok(!JSON.stringify(detail).includes("fixture-private-password"));
    },
  );
  await check(
    "polling preserves channel drafts and stale save is rejected",
    async () => {
      const topic = page.getByLabel("Thema", { exact: true });
      await topic.fill("Ungespeicherter Entwurf");
      await topic.focus();
      await page.waitForTimeout(6300);
      await expect(topic).toHaveValue("Ungespeicherter Entwurf");
      await expect(topic).toBeFocused();
      await api(`/channels/${channelId}`, { topic: "Extern geändert" }, "PUT");
      await submit("Channel speichern", `/channels/${channelId}`, "PUT", 409);
      await expect(page.getByRole("alert")).toContainText(
        "inzwischen verändert",
      );
      assert.equal(
        (await api(`/channels/${channelId}`)).topic,
        "Extern geändert",
      );
      await expect(topic).toHaveValue("Ungespeicherter Entwurf");
      await page.getByRole("button", { name: "Daten aktualisieren" }).click();
      await expect(page.getByLabel("Thema", { exact: true })).toHaveValue(
        "Extern geändert",
      );
    },
  );
  await check("move a channel and update channel permissions", async () => {
    await page
      .getByLabel("Übergeordneter Channel", { exact: true })
      .selectOption("3");
    await submit("Channel verschieben", `/channels/${channelId}/move`, "POST");
    await expect(
      page.getByLabel("Übergeordneter Channel", { exact: true }),
    ).toHaveValue("3");
    assert.equal((await api(`/channels/${channelId}`)).parentId, 3);
    await page
      .getByLabel("Recht", { exact: true })
      .fill("i_channel_needed_join_power");
    await page.getByLabel("Rechtewert", { exact: true }).fill("15");
    await submit(
      "Recht speichern",
      `/permissions/channel/${channelId}/101`,
      "PUT",
    );
    await expect(
      page.getByRole("button", { name: "Recht 101 bearbeiten", exact: true }),
    ).toBeVisible();
    assert.equal((await api(`/permissions/channel/${channelId}`))[0].value, 15);
    await submit(
      "Recht 101 entfernen",
      `/permissions/channel/${channelId}/101`,
      "DELETE",
    );
    await expect(
      page.getByRole("button", { name: "Recht 101 bearbeiten", exact: true }),
    ).toHaveCount(0);
  });
  await check(
    "create and rename a server group copied from an existing group",
    async () => {
      await navigate("Gruppen");
      await expect(
        page.getByRole("heading", { name: "Servergruppen", exact: true }),
      ).toBeVisible();
      await page
        .getByRole("button", { name: "Neue Gruppe", exact: true })
        .click();
      await page.getByLabel("Gruppenname", { exact: true }).fill("Browser DJs");
      await page
        .getByLabel("Rechte kopieren von", { exact: true })
        .selectOption("6");
      const result = await submit(
        "Gruppe erstellen",
        "/groups/server",
        "POST",
        200,
      );
      serverGroupId = result.id;
      await expect(page.getByLabel("Gruppenname", { exact: true })).toHaveValue(
        "Browser DJs",
      );
      assert.equal(
        (await api(`/permissions/server-group/${serverGroupId}`))[0].value,
        75,
      );
      await page.getByLabel("Gruppenname", { exact: true }).fill("Radio DJs");
      await submit(
        "Gruppenname speichern",
        `/groups/server/${serverGroupId}`,
        "PUT",
      );
      await expect(
        page.locator(".admin-list-item").filter({ hasText: "Radio DJs" }),
      ).toBeVisible();
    },
  );
  await check(
    "edit server group rights with negated and skip and assign offline client",
    async () => {
      await page
        .getByRole("button", { name: "Recht 100 bearbeiten", exact: true })
        .click();
      await page.getByLabel("Rechtewert", { exact: true }).fill("42");
      await page.getByLabel("Negated", { exact: true }).check();
      await page.getByLabel("Skip", { exact: true }).check();
      await submit(
        "Recht speichern",
        `/permissions/server-group/${serverGroupId}/100`,
        "PUT",
      );
      assert.deepEqual(
        (await api(`/permissions/server-group/${serverGroupId}`))[0],
        { id: 100, value: 42, negated: true, skip: true },
      );
      await page.getByLabel("Client-Datenbank-ID", { exact: true }).fill("99");
      await submit(
        "Gruppe zuweisen",
        `/groups/server/${serverGroupId}/members`,
        "POST",
      );
      await expect(
        page.getByRole("button", { name: "Client 99 entfernen", exact: true }),
      ).toBeVisible();
    },
  );
  await check(
    "group deletion reports existing members and requires explicit force",
    async () => {
      await page
        .locator("summary")
        .filter({ hasText: "Gruppe löschen" })
        .click();
      await submit(
        "Endgültig löschen",
        `/groups/server/${serverGroupId}`,
        "DELETE",
        409,
      );
      await expect(page.getByRole("alert")).toContainText("1283");
      await page
        .getByLabel("Auch vorhandene Zuweisungen entfernen", { exact: true })
        .check();
      await submit(
        "Endgültig löschen",
        `/groups/server/${serverGroupId}`,
        "DELETE",
      );
      await expect(
        page.locator(".admin-list-item").filter({ hasText: "Radio DJs" }),
      ).toHaveCount(0);
    },
  );
  await check(
    "channel groups can be created assigned and reset to server default",
    async () => {
      await page
        .getByRole("button", { name: "Channelgruppen", exact: true })
        .click();
      await expect(
        page.getByRole("heading", { name: "Channelgruppen", exact: true }),
      ).toBeVisible();
      await page
        .getByRole("button", { name: "Neue Gruppe", exact: true })
        .click();
      await page
        .getByLabel("Gruppenname", { exact: true })
        .fill("Browser Moderation");
      await page
        .getByLabel("Rechte kopieren von", { exact: true })
        .selectOption("5");
      const result = await submit(
        "Gruppe erstellen",
        "/groups/channel",
        "POST",
        200,
      );
      channelGroupId = result.id;
      await expect(page.getByLabel("Gruppenname", { exact: true })).toHaveValue(
        "Browser Moderation",
      );
      await page.getByLabel("Client-Datenbank-ID", { exact: true }).fill("99");
      await page
        .getByLabel("Channel für Zuweisung", { exact: true })
        .selectOption("3");
      await submit(
        "Gruppe zuweisen",
        `/groups/channel/${channelGroupId}/members`,
        "POST",
      );
      await expect(
        page.getByRole("button", {
          name: "Client 99 in Channel 3 entfernen",
          exact: true,
        }),
      ).toBeVisible();
      await submit(
        "Client 99 in Channel 3 entfernen",
        `/groups/channel/${channelGroupId}/members/99/3`,
        "DELETE",
      );
      await expect(
        page.getByRole("button", {
          name: "Client 99 in Channel 3 entfernen",
          exact: true,
        }),
      ).toHaveCount(0);
      assert.deepEqual(
        await api(`/groups/channel/${channelGroupId}/members`),
        [],
      );
      await page
        .locator("summary")
        .filter({ hasText: "Gruppe löschen" })
        .click();
      await submit(
        "Endgültig löschen",
        `/groups/channel/${channelGroupId}`,
        "DELETE",
      );
    },
  );
  await check(
    "query groups are visibly read only and group switching never reuses old editor",
    async () => {
      await page
        .getByRole("button", { name: "Servergruppen", exact: true })
        .click();
      await group("Query Admin");
      await expect(
        page.getByLabel("Gruppenname", { exact: true }),
      ).toBeDisabled();
      await expect(
        page.getByRole("button", { name: "Recht speichern", exact: true }),
      ).toBeDisabled();
      await page
        .getByRole("button", { name: "Channelgruppen", exact: true })
        .click();
      await expect(page.getByLabel("Gruppenname", { exact: true })).toHaveCount(
        0,
      );
      await group("Guest");
      await expect(
        page.getByLabel("Gruppenname", { exact: true }),
      ).toBeEnabled();
    },
  );
  for (const width of [1440, 1024, 820, 768, 390, 320])
    for (const view of ["Channels", "Gruppen"])
      await check(
        `${view} editor layout and navigation at ${width}px`,
        async () => {
          await page.setViewportSize({
            width,
            height: width < 600 ? 844 : 900,
          });
          await navigate(view);
          if (view === "Channels") await channel("Support");
          else await group("Mitglied");
          assert.ok(
            await page.evaluate(
              () => document.documentElement.scrollWidth <= innerWidth,
            ),
            `${view} overflow ${width}`,
          );
          await expect(
            page.getByRole("button", {
              name:
                view === "Channels"
                  ? "Channel speichern"
                  : "Gruppenname speichern",
              exact: true,
            }),
          ).toHaveCSS("min-height", "44px");
        },
      );
  await check(
    "read token can open both editors but cannot persist changes",
    async () => {
      await page.setViewportSize({ width: 1440, height: 900 });
      await page
        .getByRole("button", { name: "Vom Webinterface abmelden" })
        .click();
      await login(metadata.read);
      await navigate("Channels");
      await channel("Support");
      await page
        .getByLabel("Channel-Name", { exact: true })
        .fill("Denied change");
      await submit("Channel speichern", "/channels/3", "PUT", 401);
      await expect(page.getByRole("alert")).toContainText("nicht berechtigt");
      assert.equal((await api("/channels/3")).name, "Support");
      await navigate("Gruppen");
      await group("Mitglied");
      await page
        .getByLabel("Gruppenname", { exact: true })
        .fill("Denied group");
      await submit("Gruppenname speichern", "/groups/server/7", "PUT", 401);
      await expect(page.getByRole("alert")).toContainText("nicht berechtigt");
      assert.equal(
        (await api("/groups/server")).find((g) => g.id === 7).name,
        "Mitglied",
      );
      await page
        .getByRole("button", { name: "Vom Webinterface abmelden" })
        .click();
      await login();
    },
  );
  await check(
    "occupied channel cannot be deleted and own empty channel can be deleted",
    async () => {
      await navigate("Channels");
      await channel("Musik");
      await page
        .locator("summary")
        .filter({ hasText: "Channel löschen" })
        .click();
      await submit("Endgültig löschen", "/channels/2", "DELETE", 409);
      await expect(page.getByRole("alert")).toContainText("belegt");
      await channel("Browser Radio");
      await page
        .locator("summary")
        .filter({ hasText: "Channel löschen" })
        .click();
      await submit("Endgültig löschen", `/channels/${channelId}`, "DELETE");
      await expect(
        page.locator(".channel-name").filter({ hasText: "Browser Radio" }),
      ).toHaveCount(0);
    },
  );
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
console.log(`Administration browser checks passed: ${results.length}`);
