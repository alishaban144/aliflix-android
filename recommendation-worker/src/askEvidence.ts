import { boundedJson } from "./talkProviders";
import type { WebFinding } from "./askResearch";

export type ResearchIdentity = { title: string; year: number; imdbId?: string };
/** Batch, independently identity-check and attribute CC BY-SA Wikipedia facts.
 * TMDB contributes external IDs only; no catalogue prose enters model context. */
export async function candidateEvidence(
  items: ResearchIdentity[],
  signal: AbortSignal,
): Promise<Map<string, WebFinding>> {
  const result = new Map<string, WebFinding>();
  const wanted = items.filter((v) => /^tt\d+$/.test(v.imdbId || ""));
  if (!wanted.length) return result;
  const titles = [
    ...new Set(
      wanted.flatMap((v) => [
        `${v.title} (${v.year} film)`,
        `${v.title} (film)`,
        `${v.title} (TV series)`,
        v.title,
      ]),
    ),
  ];
  const pages: Array<{
    title: string;
    extract?: string;
    pageprops?: { wikibase_item?: string };
    pageid: number;
  }> = [];
  async function json(url: URL, limit = 400000, timeout = 4500) {
    const response = await fetch(url, {
      signal: AbortSignal.any([signal, AbortSignal.timeout(timeout)]),
      headers: {
        "user-agent":
          "AliflixResearch/1.0 (+https://github.com/alishaban144/aliflix-android)",
      },
      cf: { cacheTtl: 86400 },
    });
    if (!response.ok)
      console.warn("ask_wikimedia_status", url.hostname, response.status);
    return boundedJson(response, limit) as Promise<any>;
  }
  // At most four batched requests instead of one search per title.
  const batches: Array<string[]> = [];
  for (let i = 0; i < titles.length; i += 32)
    batches.push(titles.slice(i, i + 32));
  const responses = await Promise.allSettled(
    batches.map(async (batch) => {
      const url = new URL("https://en.wikipedia.org/w/api.php");
      url.search = new URLSearchParams({
        action: "query",
        format: "json",
        redirects: "1",
        prop: "extracts|pageprops",
        explaintext: "1",
        exchars: "1200",
        exintro: "1",
        exlimit: "max",
        exsectionformat: "plain",
        titles: batch.join("|"),
      }).toString();
      return json(url);
    }),
  );
  responses.forEach((r) => {
    if (r.status === "fulfilled")
      pages.push(
        ...(Object.values(r.value.query?.pages || {}) as typeof pages),
      );
  });
  const ids = [
    ...new Set(
      pages
        .map((p) => p.pageprops?.wikibase_item)
        .filter((v): v is string => !!v && /^Q\d+$/.test(v)),
    ),
  ];
  if (!ids.length) {
    console.warn("ask_wikimedia_empty", wanted.length, pages.length);
    return result;
  }
  // Request only IMDb IDs. Full Wikidata claims include megabytes of unrelated
  // cast references and qualifiers; they are unnecessary for this identity join.
  const entities: Record<string, any> = {};
  try {
    const url = new URL("https://query.wikidata.org/sparql");
    url.search = new URLSearchParams({
      format: "json",
      query: `SELECT ?item ?imdb WHERE { VALUES ?item { ${ids.map((id) => " <http://www.wikidata.org/entity/" + id + ">").join(" ")} } ?item <http://www.wikidata.org/prop/direct/P345> ?imdb }`,
    }).toString();
    const data = await json(url, 400000, 1200);
    for (const row of data.results?.bindings || []) {
      const id = String(row.item?.value || "")
          .split("/")
          .pop(),
        imdb = row.imdb?.value;
      if (id && typeof imdb === "string")
        entities[id] = {
          claims: { P345: [{ mainsnak: { datavalue: { value: imdb } } }] },
        };
    }
  } catch (error) {
    console.warn(
      "ask_wikidata_unavailable",
      error instanceof Error ? error.message : "error",
    );
  }
  if (!Object.keys(entities).length) {
    // P345-only recovery avoids both a slow query service and multi-megabyte
    // full entity records. At most40 IDs +4 extracts +4 revisions +1 SPARQL
    // attempt stay within the free runtime's50-subrequest ceiling.
    const normalized = (s: string) =>
      s
        .replace(/\([^)]*\)/g, "")
        .normalize("NFKC")
        .toLocaleLowerCase()
        .replace(/[^\p{L}\p{N}]+/gu, " ")
        .trim();
    const choices = wanted.map((item) =>
      pages
        .filter(
          (p) =>
            p.extract &&
            p.pageid > 0 &&
            /^Q\d+$/.test(p.pageprops?.wikibase_item || "") &&
            normalized(p.title) === normalized(item.title),
        )
        .sort((a, b) => {
          const score = (p: (typeof pages)[number]) =>
            (p.title.includes(String(item.year)) ? 10 : 0) +
            (p.extract?.includes(String(item.year)) ? 3 : 0);
          return score(b) - score(a);
        }),
    );
    const chosen = new Map<number, (typeof pages)[number]>();
    for (const list of choices)
      if (list[0]) chosen.set(list[0].pageid, list[0]);
    for (const list of choices)
      for (const p of list.slice(1))
        if (chosen.size < 40) chosen.set(p.pageid, p);
    const selected = [
      ...new Set([...chosen.values()].map((p) => p.pageprops!.wikibase_item!)),
    ].slice(0, 40);
    let next = 0;
    await Promise.all(
      Array.from({ length: Math.min(6, selected.length) }, async () => {
        while (next < selected.length) {
          const id = selected[next++];
          try {
            const url = new URL("https://www.wikidata.org/w/api.php");
            url.search = new URLSearchParams({
              action: "wbgetclaims",
              format: "json",
              entity: id,
              property: "P345",
            }).toString();
            entities[id] = {
              claims: (await json(url, 64000, 2200)).claims || {},
            };
          } catch {}
        }
      }),
    );
  }
  const byImdb = new Map(wanted.map((v) => [v.imdbId!, v]));
  for (const page of pages) {
    const entity = entities[page.pageprops?.wikibase_item || ""];
    const imdb = (entity?.claims?.P345 || [])
      .map((c: any) => c.mainsnak?.datavalue?.value)
      .find((v: unknown) => typeof v === "string" && byImdb.has(v));
    if (!imdb || !page.extract || page.pageid <= 0) continue;
    const item = byImdb.get(imdb)!;
    result.set(`${item.title} (${item.year})`, {
      title: page.title,
      url: "https://en.wikipedia.org/?curid=" + page.pageid,
      text: page.extract.slice(0, 1900),
      attribution:
        "Wikipedia contributors · CC BY-SA 4.0 · adapted. Identity verified through Wikidata IMDb ID.",
    });
  }
  // Retrieve the actual narrative section in four bounded revision batches.
  // Introductory metadata often omits the very premise being evaluated.
  const matches = [...result].map(([title, finding]) => ({
    title,
    finding,
    id: Number(new URL(finding.url).searchParams.get("curid")),
  }));
  const revisionBatches: Array<typeof matches> = [];
  for (let i = 0; i < matches.length; i += 8)
    revisionBatches.push(matches.slice(i, i + 8));
  const revisions = await Promise.allSettled(
    revisionBatches.map(async (batch) => {
      const url = new URL("https://en.wikipedia.org/w/api.php");
      url.search = new URLSearchParams({
        action: "query",
        format: "json",
        prop: "revisions",
        rvprop: "ids|content",
        rvslots: "main",
        rvlimit: "1",
        pageids: batch.map((v) => v.id).join("|"),
      }).toString();
      return json(url, 2000000);
    }),
  );
  for (const response of revisions)
    if (response.status === "fulfilled")
      for (const page of Object.values(
        response.value.query?.pages || {},
      ) as any[]) {
        const match = matches.find((v) => v.id === page.pageid),
          revision = page.revisions?.[0],
          raw = revision?.slots?.main?.["*"];
        if (!match || typeof raw !== "string") continue;
        const sections = [...raw.matchAll(/^==\s*([^=\n]+?)\s*==\s*$/gm)];
        const at = sections.findIndex((v) =>
          /^(plot|synopsis|plot summary)$/i.test(v[1].trim()),
        );
        if (at < 0) continue;
        let text = raw
          .slice(
            sections[at].index! + sections[at][0].length,
            sections[at + 1]?.index ?? raw.length,
          )
          .replace(/<ref\b[^>]*>[\s\S]*?<\/ref>|<ref\b[^>]*\/>/gi, " ")
          .replace(/\[\[(?:File|Image):[^\]]*\]\]/gi, " ");
        for (let i = 0; i < 5; i++) text = text.replace(/\{\{[^{}]*\}\}/g, " ");
        text = text
          .replace(/\[\[(?:[^\]|]*\|)?([^\]]+)\]\]/g, "$1")
          .replace(/\[https?:\/\/\S+\s+([^\]]+)\]/g, "$1")
          .replace(/'{2,5}|<[^>]*>/g, "")
          .replace(/\s+/g, " ")
          .trim();
        if (text.length > 2100)
          text = text.slice(0, 1450) + " […] " + text.slice(-650);
        if (text.length > 100)
          result.set(match.title, {
            ...match.finding,
            text,
            url: Number.isInteger(revision.revid)
              ? "https://en.wikipedia.org/w/index.php?oldid=" + revision.revid
              : match.finding.url,
          });
      }
  return result;
}
