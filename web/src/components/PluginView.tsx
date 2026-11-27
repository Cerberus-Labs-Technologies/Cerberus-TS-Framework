import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { type Api, type PluginPage } from "@/lib/api";

type Dispose = void | (() => void);
type PluginModule = {
  mount(root: HTMLElement, api: PluginApi): Dispose | Promise<Dispose>;
};
export type PluginApi = {
  version: 1;
  request<T>(path: string, options?: RequestInit): Promise<T>;
  navigate(path: string): void;
  notify(message: string, kind?: "success" | "error"): void;
};
export function PluginView({
  page,
  api,
  notify,
  refresh = 0,
}: {
  page: PluginPage;
  api: Api;
  notify: PluginApi["notify"];
  refresh?: number;
}) {
  const root = useRef<HTMLDivElement>(null);
  const [error, setError] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const element = root.current!;
    // Each effect owns a separate host, including when a slow old mount finishes after navigation.
    const host = document.createElement("div");
    let disposed = false;
    let cleanup: Dispose;
    let link: HTMLLinkElement | undefined;
    const abort = new AbortController();
    setLoading(true);
    setError(undefined);
    element.replaceChildren(host);
    function dispose(callback: Dispose) {
      try {
        if (typeof callback === "function") callback();
      } catch (error) {
        console.error(
          "Plugin-Seite konnte nicht vollständig aufgeräumt werden.",
          error,
        );
      }
    }
    async function load() {
      try {
        const prefix = `/plugin-assets/${page.plugin}/${page.id}/`;
        if (
          !page.module.startsWith(prefix) ||
          (!page.module.endsWith(".js") && !page.module.endsWith(".mjs"))
        )
          throw new Error(
            "Das Plugin hat einen ungültigen Moduleinstieg registriert.",
          );
        if (page.stylesheet) {
          if (!page.stylesheet.startsWith(prefix))
            throw new Error("Ungültiger Stylesheet-Pfad.");
          link = document.createElement("link");
          link.rel = "stylesheet";
          link.href = page.stylesheet;
          await new Promise<void>((resolve, reject) => {
            const current = link!;
            const finish = (error?: Error) => {
              current.onload = null;
              current.onerror = null;
              abort.signal.removeEventListener("abort", cancelled);
              if (error) reject(error);
              else resolve();
            };
            const cancelled = () =>
              finish(new DOMException("Abgebrochen", "AbortError"));
            current.onload = () => finish();
            current.onerror = () =>
              finish(
                new Error(
                  "Das Stylesheet der Plugin-Seite konnte nicht geladen werden.",
                ),
              );
            abort.signal.addEventListener("abort", cancelled, { once: true });
            document.head.append(current);
          });
        }
        const module = (await import(
          /* @vite-ignore */ page.module
        )) as PluginModule;
        if (disposed) return;
        if (typeof module.mount !== "function")
          throw new Error("Dem Plugin fehlt die mount-Funktion.");
        const pluginApi: PluginApi = {
          version: 1,
          request: (path, options = {}) => {
            if (
              !path.startsWith("/") ||
              path.startsWith("//") ||
              path.includes("..")
            )
              throw new Error("Ungültiger Plugin-API-Pfad.");
            return api.request(`/api/plugins/${page.plugin}${path}`, {
              ...options,
              signal: options.signal
                ? AbortSignal.any([options.signal, abort.signal])
                : abort.signal,
            });
          },
          navigate: (path) => {
            if (path.startsWith("/") && !path.startsWith("//"))
              location.hash = path;
          },
          notify,
        };
        const result = await module.mount(host, pluginApi);
        if (disposed) dispose(result);
        else {
          cleanup = result;
          setLoading(false);
        }
      } catch (error) {
        abort.abort();
        link?.remove();
        host.replaceChildren();
        if (!disposed) {
          setLoading(false);
          setError(
            error instanceof Error
              ? error.message
              : "Die Plugin-Seite konnte nicht geladen werden.",
          );
        }
      }
    }
    void load();
    return () => {
      disposed = true;
      abort.abort();
      dispose(cleanup);
      link?.remove();
      host.remove();
    };
  }, [
    page.plugin,
    page.id,
    page.module,
    page.stylesheet,
    api,
    notify,
    retry,
    refresh,
  ]);
  return (
    <>
      {loading && (
        <div
          className="space-y-4"
          role="status"
          aria-label="Plugin-Seite wird geladen"
        >
          <Skeleton className="h-12 w-2/3" />
          <Skeleton className="h-52 w-full" />
        </div>
      )}
      {error && (
        <div className="inline-error" role="alert">
          <p>{error}</p>
          <Button
            variant="outline"
            onClick={() => setRetry((value) => value + 1)}
          >
            Plugin erneut laden
          </Button>
        </div>
      )}
      <div
        ref={root}
        data-plugin-page={`${page.plugin}/${page.id}`}
        aria-busy={loading}
      />
    </>
  );
}
