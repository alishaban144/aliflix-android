import { describe, it, expect, vi } from 'vitest';
import {
  normalizeOmdbResponse,
  validateOmdbIdentity,
  getKvCacheKey,
  OmdbLookupRequest,
} from '../src/omdb';
import worker from '../src/index';

describe('OMDb Worker Module', () => {
  it('should normalize full OMDb response correctly and parse ratings', () => {
    const raw = {
      Response: 'True',
      Title: 'The Godfather',
      Year: '1972',
      Rated: 'R',
      Runtime: '175 min',
      Genre: 'Crime, Drama',
      Director: 'Francis Ford Coppola',
      Actors: 'Marlon Brando, Al Pacino, James Caan',
      Plot: 'The aging patriarch of an organized crime dynasty transfers control...',
      Language: 'English, Italian, Latin',
      Country: 'United States',
      Awards: 'Won 3 Oscars.',
      Ratings: [
        { Source: 'Internet Movie Database', Value: '9.2/10' },
        { Source: 'Rotten Tomatoes', Value: '97%' },
        { Source: 'Metacritic', Value: '100/100' },
      ],
      Metascore: '100',
      imdbRating: '9.2',
      imdbVotes: '1,900,000',
      imdbID: 'tt0068646',
      Type: 'movie',
    };

    const norm = normalizeOmdbResponse(raw);
    expect(norm.found).toBe(true);
    expect(norm.title).toBe('The Godfather');
    expect(norm.year).toBe(1972);
    expect(norm.runtimeMinutes).toBe(175);
    expect(norm.genres).toEqual(['Crime', 'Drama']);
    expect(norm.director).toBe('Francis Ford Coppola');
    expect(norm.actors).toEqual(['Marlon Brando', 'Al Pacino', 'James Caan']);
    expect(norm.imdbRating).toBe(9.2);
    expect(norm.imdbVotes).toBe(1900000);
    expect(norm.rottenTomatoesRating).toBe(97);
    expect(norm.metascore).toBe(100);
    expect(norm.imdbId).toBe('tt0068646');
    expect(norm.type).toBe('movie');
  });

  it('should convert "N/A" values to null or empty lists', () => {
    const raw = {
      Response: 'True',
      Title: 'Obscure Title',
      Year: 'N/A',
      Rated: 'N/A',
      Runtime: 'N/A',
      Genre: 'N/A',
      Director: 'N/A',
      Actors: 'N/A',
      Plot: 'N/A',
      Language: 'N/A',
      Country: 'N/A',
      Awards: 'N/A',
      Ratings: [],
      Metascore: 'N/A',
      imdbRating: 'N/A',
      imdbVotes: 'N/A',
      imdbID: 'tt9999999',
      Type: 'movie',
    };

    const norm = normalizeOmdbResponse(raw);
    expect(norm.found).toBe(true);
    expect(norm.year).toBeNull();
    expect(norm.contentRating).toBeNull();
    expect(norm.runtimeMinutes).toBeNull();
    expect(norm.genres).toEqual([]);
    expect(norm.director).toBeNull();
    expect(norm.actors).toEqual([]);
    expect(norm.plot).toBeNull();
    expect(norm.imdbRating).toBeNull();
    expect(norm.imdbVotes).toBeNull();
    expect(norm.rottenTomatoesRating).toBeNull();
    expect(norm.metascore).toBeNull();
  });

  it('should validate identity accurately', () => {
    const req: OmdbLookupRequest = {
      imdbId: 'tt0068646',
      title: 'The Godfather',
      year: 1972,
      mediaType: 'movie',
    };
    const validNorm = normalizeOmdbResponse({
      Response: 'True',
      Title: 'The Godfather',
      Year: '1972',
      Type: 'movie',
      imdbID: 'tt0068646',
    });
    expect(validateOmdbIdentity(req, validNorm)).toBe(true);

    // Mismatched IMDb ID
    const diffIdNorm = normalizeOmdbResponse({
      Response: 'True',
      Title: 'The Godfather',
      Year: '1972',
      Type: 'movie',
      imdbID: 'tt9999999',
    });
    expect(validateOmdbIdentity(req, diffIdNorm)).toBe(false);

    // Mismatched media type
    const reqSeries: OmdbLookupRequest = {
      title: 'Breaking Bad',
      year: 2008,
      mediaType: 'series',
    };
    const movieNorm = normalizeOmdbResponse({
      Response: 'True',
      Title: 'Breaking Bad',
      Year: '2008',
      Type: 'movie',
      imdbID: 'tt0944947',
    });
    expect(validateOmdbIdentity(reqSeries, movieNorm)).toBe(false);
  });

  it('should generate canonical KV cache keys', () => {
    expect(getKvCacheKey({ imdbId: 'tt0068646', mediaType: 'movie' })).toBe('omdb:id:tt0068646');
    expect(getKvCacheKey({ title: 'The Godfather', year: 1972, mediaType: 'movie' })).toBe(
      'omdb:movie:the-godfather:1972'
    );
  });
});

describe('Worker OMDb Endpoints', () => {
  const env = { GEMINI_API_KEY: 'test-gemini', OMDB_API_KEY: 'test-omdb' };
  const ctx = { waitUntil: () => {}, passThroughOnException: () => {} } as any;

  it('/health should include omdbConfigured and omdbCacheConfigured without calling OMDb', async () => {
    const request = new Request('http://localhost/health', { method: 'GET' });
    const response = await worker.fetch(request, env, ctx);
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body).toEqual({
      status: 'ok',
      service: 'aliflix-recommendations',
      geminiConfigured: true,
      omdbConfigured: true,
      omdbCacheConfigured: false,
    });
  });

  it('/v1/metadata/omdb should reject requests missing both imdbId and title', async () => {
    const request = new Request('http://localhost/v1/metadata/omdb', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mediaType: 'movie' }),
    });
    const response = await worker.fetch(request, env, ctx);
    expect(response.status).toBe(400);
  });

  it('/v1/metadata/omdb/batch should reject batches larger than 25', async () => {
    const titles = new Array(26).fill({ title: 'Test', mediaType: 'movie' });
    const request = new Request('http://localhost/v1/metadata/omdb/batch', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ titles }),
    });
    const response = await worker.fetch(request, env, ctx);
    expect(response.status).toBe(400);
  });
});
