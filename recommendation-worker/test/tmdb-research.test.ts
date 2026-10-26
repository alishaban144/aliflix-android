import {editorialSeedCandidates,catalogueSuggestions} from '../src/askResearch';
import {afterEach,expect,it,vi} from 'vitest';
import {env} from 'cloudflare:test';
import {TmdbClient} from '../src/tmdb';
afterEach(()=>vi.restoreAllMocks());
it('research fetches only structured details and TV identifiers, leaving full title pages unchanged',async()=>{
 const urls:string[]=[];
 vi.spyOn(globalThis,'fetch').mockImplementation(async url=>{urls.push(String(url));return Response.json({id:70523,name:'Dark',first_air_date:'2017-12-01',genres:[],external_ids:{imdb_id:'tt5753856'}});});
 const client=new TmdbClient({...env,TMDB_READ_ACCESS_TOKEN:'test-only',CURSOR_SIGNING_SECRET:'test-only'});
 const result=await client.researchDetails('tv',70523);
 expect(result.external_ids?.imdb_id).toBe('tt5753856');
 expect(new URL(urls[0]).searchParams.get('append_to_response')).toBe('external_ids');
 await client.researchDetails('movie',11);expect(new URL(urls[1]).searchParams.has('append_to_response')).toBe(false);
 await client.details('tv',70523);expect(new URL(urls[2]).searchParams.get('append_to_response')).toContain('aggregate_credits');
});

it('editorial seeds preserve actual premiere years and strip editorial prose before model use',async()=>{
 const discover=vi.fn(async()=>({page:1,total_pages:1,total_results:4,results:[
  {id:1,name:'A recent series',first_air_date:'2024-01-02',overview:'Must never reach the model'},
  {id:2,name:'Older season is not a premiere',first_air_date:'2018-01-01'},
  {id:3,name:'Unannounced',first_air_date:''},
  {id:4,name:'Future',first_air_date:'2028-01-01'}
 ]}));
 const seeds=await editorialSeedCandidates({discover} as unknown as TmdbClient,'tv',2023,2026);
 expect(seeds).toEqual([{title:'A recent series',year:2024,id:1,evidence:[],reason:''}]);
 expect(discover).toHaveBeenCalledTimes(3);
 expect(discover.mock.calls[0]).toEqual(['tv',expect.objectContaining({'first_air_date.gte':'2023-01-01'})]);
});

it('catalogue suggestions exclude unreleased, adult, low-confidence and duplicate identities without inventing AI scores',()=>{
 const valid={id:1,name:'Verified series',first_air_date:'2025-01-02',poster_path:'/poster.jpg',vote_count:100,vote_average:8,genre_ids:[18]};
 const results=catalogueSuggestions([valid,valid,{...valid,id:2,first_air_date:'2027-01-01'},{...valid,id:3,adult:true},{...valid,id:4,vote_count:0},{...valid,id:5,first_air_date:'2010-01-01'}],'tv',new Map([[18,'Drama']]),'2026-10-20');
 expect(results).toHaveLength(1);expect(results[0]).toMatchObject({tmdbId:1,genres:['Drama'],finalScore:0,matchReasons:[],retrievalSources:['https://www.themoviedb.org/tv/1']});
});
