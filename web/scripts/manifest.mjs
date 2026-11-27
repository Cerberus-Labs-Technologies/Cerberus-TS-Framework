import { readdir, writeFile } from "node:fs/promises"
import { join } from "node:path"

async function files(directory, prefix = "") {
  const entries = await readdir(directory, { withFileTypes: true })
  const result = []
  for (const entry of entries) {
    if (entry.name === "assets.list") continue
    const name = prefix + entry.name
    if (entry.isDirectory()) result.push(...await files(join(directory, entry.name), `${name}/`))
    else if (entry.isFile()) result.push(name)
  }
  return result
}
await writeFile("dist/assets.list", (await files("dist")).sort().join("\n") + "\n")
