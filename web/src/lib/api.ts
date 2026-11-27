import { useCallback, useEffect, useState } from "react"

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}
export type Api = {
  request<T>(path: string, options?: RequestInit): Promise<T>
}
export function createApi(token: string): Api {
  return {
    async request<T>(path: string, options: RequestInit = {}): Promise<T> {
      if (!path.startsWith("/api/") || path.includes("..")) throw new Error("Ungültiger API-Pfad")
      const headers = new Headers(options.headers)
      headers.set("Authorization", `Bearer ${token}`)
      if (options.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json")
      let response: Response
      try { response = await fetch(path, { ...options, headers, credentials: "omit", cache: "no-store" }) }
      catch (error) {
        if (error instanceof DOMException && error.name === "AbortError") throw error
        throw new ApiError(0, "Der Bot ist nicht erreichbar. Prüfe die Verbindung.")
      }
      if (!response.ok) {
        const value = await response.json().catch(() => null)
        throw new ApiError(response.status, response.status === 401 ? "Dein Token ist ungültig, abgelaufen oder für diese Aktion nicht berechtigt." : value?.error ?? `Anfrage fehlgeschlagen (${response.status}).`)
      }
      return response.status === 204 ? undefined as T : await response.json() as T
    },
  }
}
export function useResource<T>(api: Api, path: string, refresh: number, interval = 6000) {
  const [data, setData] = useState<T>()
  const [error, setError] = useState<string>()
  const [loading, setLoading] = useState(true)
  const [updated, setUpdated] = useState<Date>()
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    let disposed = false
    let timer: ReturnType<typeof setTimeout>
    const abort = new AbortController()
    async function load() {
      if (disposed) return
      setLoading(true)
      try {
        const result = await api.request<T>(path, { signal: abort.signal })
        if (!disposed) { setData(result); setError(undefined); setUpdated(new Date()) }
      } catch (error) {
        if (!disposed && !(error instanceof DOMException && error.name === "AbortError")) setError(error instanceof Error ? error.message : "Daten konnten nicht geladen werden.")
      } finally {
        if (!disposed) { setLoading(false); timer = setTimeout(load, interval) }
      }
    }
    void load()
    return () => { disposed = true; clearTimeout(timer); abort.abort() }
  }, [api, path, refresh, retry, interval])
  return { data, error, loading, updated, retry: useCallback(() => setRetry(value => value + 1), []) }
}

export type Status = {
  state: string; connected: boolean; serverId: number; serverName: string | null; version: string | null
  maxClients: number | null; onlineClients: number | null; queryClients: number | null
}
export type Client = { id: number; channelId: number; databaseId: number | null; uniqueId: string | null; nickname: string; serverGroups: number[] }
export type Channel = { id: number; parentId: number; name: string; order: number | null; clients: number | null }
export type Plugin = { id: string; name: string; version: string; description: string; authors: string[] }
export type PluginPage = { plugin: string; pluginName: string; id: string; title: string; description: string; module: string; stylesheet: string | null; order: number }
export type MusicBot = { id: string; connected: boolean; clientId: number; current: string | null; queued: string[]; paused: boolean; volume: number; error: string | null; metadata: { title: string; artist?: string; station?: string } | null; phase: string }
