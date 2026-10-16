import { z } from 'zod';

// Only identifiers and the viewer's own progress cross this boundary. TMDB
// overviews, artwork, cast and subtitle bodies are deliberately not accepted.
export const TalkContextSchema = z.object({
  id: z.number().int().positive().optional(), type: z.enum(['movie', 'tv']).optional(),
  imdbId: z.string().regex(/^tt\d{1,12}$/).optional(),
  season: z.number().int().min(0).max(1000).default(1),
  episode: z.number().int().min(1).max(10000).default(1),
  position: z.number().finite().min(0).max(604800).default(0),
  completed: z.boolean().default(false), spoilers: z.boolean().default(false),
  library: z.array(z.object({ imdbId: z.string().regex(/^tt\d{1,12}$/),
    kind: z.enum(['saved', 'favorite', 'watched']), type: z.enum(['movie', 'tv']) }).strict()).max(8).default([]),
}).strict();
export type TalkContext = z.infer<typeof TalkContextSchema>;
export type Evidence = { id: string; title: string; text: string; url: string; license: string; season?: number; episode?: number };

export function permittedEpisode(context: TalkContext, season: number, episode: number): boolean {
  return context.spoilers || season < context.season || season === context.season &&
    (episode < context.episode || episode === context.episode && context.completed);
}
export function stripMarkup(text: string) {
  return text.replace(/<[^>]*>/g, ' ').replace(/&amp;/g, '&').replace(/&#39;/g, "'")
    .replace(/&quot;/g, '"').replace(/\s+/g, ' ').trim().slice(0, 5000);
}
async function sourceJson(url: string, signal: AbortSignal, limit = 256_000): Promise<unknown> {
  const response = await fetch(url, { signal, headers: { 'user-agent': 'Aliflix/1.0 (attributed on-demand companion)' }, cf: { cacheTtl: 3600 } });
  if (!response.ok) throw Error('EVIDENCE_UNAVAILABLE');
  const reader = response.body?.getReader(); if (!reader) throw Error('EVIDENCE_UNAVAILABLE');
  const chunks: Uint8Array[] = []; let size = 0;
  try { for (;;) { const r = await reader.read(); if (r.done) break; size += r.value.length;
    if (size > limit) throw Error('EVIDENCE_TOO_LARGE'); chunks.push(r.value); } }
  finally { await reader.cancel(); }
  const bytes = new Uint8Array(size); let at = 0; for (const chunk of chunks) { bytes.set(chunk, at); at += chunk.length; }
  return JSON.parse(new TextDecoder().decode(bytes));
}
const showSchema = z.object({ id: z.number().int().positive(), name: z.string(), url: z.string().url(),
  genres: z.array(z.string()).default([]), premiered: z.string().nullable().optional(),
  externals: z.object({ imdb: z.string().nullable().optional() }).optional() });
const episodeSchema = z.object({ id: z.number(), name: z.string(), season: z.number(), number: z.number().nullable(),
  summary: z.string().nullable(), url: z.string().url() });

/** TVmaze API content is CC BY-SA; preserve attribution and share-alike notice. */
export async function lookupTalkTitles(query: string, signal: AbortSignal): Promise<Evidence[]> {
  const raw = await sourceJson('https://api.tvmaze.com/search/shows?q=' + encodeURIComponent(query.slice(0, 100)), signal);
  const entries = z.array(z.object({ show: showSchema })).parse(raw).slice(0, 5);
  return entries.map(({ show }) => ({ id: 'tvmaze-show-' + show.id, title: show.name,
    text: JSON.stringify({ name: show.name, year: show.premiered?.slice(0, 4), genres: show.genres }),
    url: show.url, license: 'TVmaze · CC BY-SA 4.0' }));
}

export async function recommendTalkTitles(query: string, signal: AbortSignal): Promise<Evidence[]> {
  // TVmaze's public index is cached by its CDN. Only metadata is passed to the
  // reasoner; show summaries and future-episode links are never included.
  const raw = await sourceJson('https://api.tvmaze.com/shows?page=0', signal, 2_000_000);
  const shows = z.array(showSchema.extend({ rating: z.object({ average: z.number().nullable() }).optional() })).parse(raw);
  const categories: [RegExp, string][] = [[/sci.?fi|science.?fiction|خيال|wissenschaft/i, 'Science-Fiction'],
    [/comedy|funny|kom[oö]die|كوميدي/i, 'Comedy'], [/crime|krimi|جريمة/i, 'Crime'],
    [/thriller|تشويق/i, 'Thriller'], [/mystery|r[aä]tsel|غموض/i, 'Mystery'],
    [/fantasy|فانتازيا/i, 'Fantasy'], [/horror|رعب/i, 'Horror'], [/drama|دراما/i, 'Drama']];
  const genres = categories.filter(([pattern]) => pattern.test(query)).map(([, genre]) => genre);
  if (!genres.length) return lookupTalkTitles(query, signal);
  return shows.filter(show => genres.every(genre => show.genres.includes(genre)))
    .sort((a, b) => (b.rating?.average || 0) - (a.rating?.average || 0)).slice(0, 5)
    .map(show => ({ id: 'tvmaze-show-' + show.id, title: show.name,
      text: JSON.stringify({ name: show.name, year: show.premiered?.slice(0, 4), genres: show.genres }),
      url: show.url, license: 'TVmaze · CC BY-SA 4.0' }));
}

export async function talkTitleDetails(imdbId: string, signal: AbortSignal): Promise<Evidence[]> {
  if (!/^tt\d{1,12}$/.test(imdbId)) return [];
  const show = showSchema.parse(await sourceJson('https://api.tvmaze.com/lookup/shows?imdb=' + imdbId, signal));
  if (show.externals?.imdb !== imdbId) throw Error('EVIDENCE_IDENTITY_MISMATCH');
  return [{ id: 'tvmaze-show-' + show.id, title: show.name,
    text: JSON.stringify({ name: show.name, genres: show.genres, year: show.premiered?.slice(0, 4) }),
    url: show.url, license: 'TVmaze · CC BY-SA 4.0' }];
}

export const TalkToolSchema = z.object({ tool: z.enum(['lookup_titles', 'recommendations', 'title_details', 'episode_evidence', 'my_space']),
  query: z.string().max(100).optional(), imdbId: z.string().regex(/^tt\d{1,12}$/).optional(),
  season: z.number().int().min(0).max(1000).optional(), episode: z.number().int().min(1).max(10000).optional() }).strict();

export async function episodeEvidence(context: TalkContext, season: number, episode: number, signal: AbortSignal): Promise<Evidence[]> {
  if (!context.imdbId || context.type !== 'tv' || !permittedEpisode(context, season, episode)) return [];
  const show = showSchema.parse(await sourceJson('https://api.tvmaze.com/lookup/shows?imdb=' + context.imdbId, signal));
  if (show.externals?.imdb !== context.imdbId) throw Error('EVIDENCE_IDENTITY_MISMATCH');
  const item = episodeSchema.parse(await sourceJson(`https://api.tvmaze.com/shows/${show.id}/episodebynumber?season=${season}&number=${episode}`, signal));
  if (item.season !== season || item.number !== episode || !item.summary) return [];
  return [{ id: 'tvmaze-episode-' + item.id, title: `${show.name} · S${season} E${episode}`,
    text: stripMarkup(item.summary), url: item.url, license: 'TVmaze · CC BY-SA 4.0', season, episode }];
}

/** Wikipedia search is driven by words the person actually spoke, never an
 * indiscriminate full-series article. Whole-article plots cannot be safely
 * clipped to a partially watched scene and are therefore not retrieved. */
export async function movieEvidence(query: string, signal: AbortSignal): Promise<Evidence[]> {
  const raw = await sourceJson('https://en.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=0&gsrlimit=3&gsrsearch=' +
    encodeURIComponent(query.slice(0, 100) + ' film') + '&prop=info&inprop=url', signal);
  const parsed = z.object({ query: z.object({ pages: z.record(z.string(), z.object({ pageid: z.number(), title: z.string(), fullurl: z.string().url() })) }).optional() }).parse(raw);
  return Object.values(parsed.query?.pages || {}).map(page => ({ id: 'wikipedia-' + page.pageid, title: page.title,
    text: page.title, url: page.fullurl, license: 'Wikipedia contributors · CC BY-SA 4.0' }));
}

/** Wikidata identifiers are CC0. Wikipedia prose is CC BY-SA. Retrieve only a
 * movie's own Plot section, after explicit completion/spoiler permission; never
 * scrape arbitrary URLs, full series pages or unlicensed subtitle downloads. */
export async function completedMovieEvidence(context: TalkContext, signal: AbortSignal): Promise<Evidence[]> {
  if (context.type !== 'movie' || !context.imdbId || !context.completed && !context.spoilers) return [];
  const query = `SELECT ?article WHERE { ?item wdt:P345 "${context.imdbId}" . ?article schema:about ?item; schema:isPartOf <https://en.wikipedia.org/> . } LIMIT 2`;
  const identifiers = z.object({ results: z.object({ bindings: z.array(z.object({ article: z.object({ value: z.string().url() }) })) }) })
    .parse(await sourceJson('https://query.wikidata.org/sparql?format=json&query=' + encodeURIComponent(query), signal));
  if (identifiers.results.bindings.length !== 1) return [];
  const article = new URL(identifiers.results.bindings[0].article.value);
  if (article.hostname !== 'en.wikipedia.org' || !article.pathname.startsWith('/wiki/')) return [];
  const title = decodeURIComponent(article.pathname.slice(6)).replaceAll('_', ' ');
  const api = 'https://en.wikipedia.org/w/api.php?action=parse&format=json&redirects=1&page=' + encodeURIComponent(title);
  const metadata = z.object({ parse: z.object({ pageid: z.number(), title: z.string(), revid: z.number(),
    sections: z.array(z.object({ line: z.string(), index: z.string() })) }) }).parse(await sourceJson(api + '&prop=sections|revid', signal));
  const section = metadata.parse.sections.find(s => /^(plot|plot summary|synopsis)$/i.test(stripMarkup(s.line)));
  if (!section || !/^\d{1,3}$/.test(section.index)) return [];
  const result = z.object({ parse: z.object({ text: z.object({ '*': z.string() }) }) }).parse(await sourceJson(api + '&prop=text&section=' + section.index, signal));
  const text = stripMarkup(result.parse.text['*']); if (!text) return [];
  return [{ id: 'wikipedia-plot-' + metadata.parse.pageid + '-' + metadata.parse.revid, title: metadata.parse.title,
    text, url: 'https://en.wikipedia.org/w/index.php?oldid=' + metadata.parse.revid,
    license: 'Wikipedia contributors · CC BY-SA 4.0 · adapted' }];
}

export const TalkAnswerSchema = z.object({ answer: z.string().min(1).max(1600),
  kind: z.enum(['fact', 'interpretation', 'question', 'unknown']),
  sources: z.array(z.string().max(80)).max(5), words: z.string().max(32) });
export function validateTalkAnswer(raw: unknown, evidence: Evidence[]) {
  const value = TalkAnswerSchema.parse(raw);
  const known = new Set(evidence.map(s => s.id));
  if (value.sources.some(s => !known.has(s)) || (value.kind === 'fact' || value.kind === 'interpretation') && value.sources.length === 0)
    throw Error('UNSUPPORTED_ANSWER');
  return { ...value, words: value.words.replace(/[^\p{L}\p{N} :'’-]/gu, '').slice(0, 32),
    references: evidence.filter(s => value.sources.includes(s.id)).map(({ id, title, url, license }) => ({ id, title, url, license })) };
}
