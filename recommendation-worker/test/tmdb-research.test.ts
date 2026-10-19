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
