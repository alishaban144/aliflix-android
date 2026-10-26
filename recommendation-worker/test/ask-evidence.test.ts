import { afterEach, expect, it, vi } from "vitest";
import { candidateEvidence } from "../src/askEvidence";
afterEach(() => vi.restoreAllMocks());
it("requests actual multi-title extracts and checks every Wikipedia identity through Wikidata", async () => {
  const calls: string[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const url = new URL(String(input));
    calls.push(url.toString());
    if (url.hostname === "en.wikipedia.org") {
      if (url.searchParams.get("prop") === "revisions")
        return Response.json({
          query: {
            pages: {
              1: {
                pageid: 1,
                revisions: [
                  {
                    revid: 501,
                    slots: {
                      main: {
                        "*": "== Plot ==\nA person discovers that all their memories were manufactured in a simulated city. They investigate their identity and discover the truth.\n== Cast ==\nCast omitted.",
                      },
                    },
                  },
                ],
              },
              2: {
                pageid: 2,
                revisions: [
                  {
                    revid: 502,
                    slots: {
                      main: {
                        "*": "== Plot ==\nA protagonist investigates an elaborate deception about their identity. The mystery concerns the relationship between memory and reality.\n== Cast ==\nCast omitted.",
                      },
                    },
                  },
                ],
              },
            },
          },
        });
      expect(url.searchParams.get("exintro")).toBe("1");
      expect(url.searchParams.get("exlimit")).toBe("max");
      expect(Number(url.searchParams.get("exchars"))).toBeLessThanOrEqual(1200);
      return Response.json({
        query: {
          pages: {
            1: {
              pageid: 1,
              title: "Film A",
              extract: "A memory story.",
              pageprops: { wikibase_item: "Q1" },
            },
            2: {
              pageid: 2,
              title: "Film B",
              extract: "An identity story.",
              pageprops: { wikibase_item: "Q2" },
            },
            3: {
              pageid: 3,
              title: "Wrong remake",
              extract: "Not the requested film.",
              pageprops: { wikibase_item: "Q3" },
            },
          },
        },
      });
    }
    if (url.hostname === "query.wikidata.org")
      throw new Error("Query service unavailable");
    expect(url.searchParams.get("property")).toBe("P345");
    expect(url.searchParams.get("action")).toBe("wbgetclaims");
    const id = url.searchParams.get("entity");
    return Response.json({
      claims: {
        P345: [
          {
            mainsnak: {
              datavalue: {
                value: id === "Q1" ? "tt1" : id === "Q2" ? "tt2" : "tt999",
              },
            },
          },
        ],
      },
    });
  });
  const facts = await candidateEvidence(
    [
      { title: "Film A", year: 2000, imdbId: "tt1" },
      { title: "Film B", year: 2010, imdbId: "tt2" },
    ],
    AbortSignal.timeout(1000),
  );
  expect([...facts.keys()]).toEqual(["Film A (2000)", "Film B (2010)"]);
  expect(facts.get("Film A (2000)")?.attribution).toContain("CC BY-SA");
  expect(facts.get("Film A (2000)")?.text).toContain("simulated city");
  expect(facts.get("Film A (2000)")?.url).toContain("oldid=501");
  expect(calls).toHaveLength(5);
});
