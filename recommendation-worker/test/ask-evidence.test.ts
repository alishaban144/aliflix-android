import {afterEach,expect,it,vi} from 'vitest';
import {candidateEvidence} from '../src/askEvidence';
afterEach(()=>vi.restoreAllMocks());
it('requests actual multi-title extracts and checks every Wikipedia identity through Wikidata',async()=>{
 const calls:string[]=[];
 vi.spyOn(globalThis,'fetch').mockImplementation(async input=>{
  const url=new URL(String(input));calls.push(url.toString());
  if(url.hostname==='en.wikipedia.org'){
   expect(url.searchParams.get('exintro')).toBe('1');expect(url.searchParams.get('exlimit')).toBe('max');expect(Number(url.searchParams.get('exchars'))).toBeLessThanOrEqual(1200);
   return Response.json({query:{pages:{1:{pageid:1,title:'Film A',extract:'A memory story.',pageprops:{wikibase_item:'Q1'}},2:{pageid:2,title:'Film B',extract:'An identity story.',pageprops:{wikibase_item:'Q2'}},3:{pageid:3,title:'Wrong remake',extract:'Not the requested film.',pageprops:{wikibase_item:'Q3'}}}}});
  }
  return Response.json({entities:Object.fromEntries([['Q1','tt1'],['Q2','tt2'],['Q3','tt999']].map(([id,imdb])=>[id,{claims:{P345:[{mainsnak:{datavalue:{value:imdb}}}]}}]))});
 });
 const facts=await candidateEvidence([{title:'Film A',year:2000,imdbId:'tt1'},{title:'Film B',year:2010,imdbId:'tt2'}],AbortSignal.timeout(1000));
 expect([...facts.keys()]).toEqual(['Film A','Film B']);expect(facts.get('Film A')?.attribution).toContain('CC BY-SA');expect(calls).toHaveLength(2);
});
