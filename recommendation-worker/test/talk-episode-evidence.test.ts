import {afterEach,describe,expect,it,vi} from 'vitest';
import {wikipediaEpisodeEvidence,wikipediaEpisodeText,wikipediaTitle,TalkContextSchema} from '../src/talkEvidence';
import {env,runInDurableObject} from 'cloudflare:test';
import {TalkSession} from '../src/talk';
const plot='Jonas explores the post-apocalyptic power plant in 2053. He discovers a mysterious black substance.';
const rows=(n:number,text:string)=>`<tr class="vevent module-episode-list-row"><th>${n+10}</th><td>${n}</td><td class="summary">Title</td></tr><tr class="expand-child"><td class="description"><p>${text}</p></td></tr>`;
const table=(body:string)=>`<table class="wikitable wikiepisodetable">${body}</table>`;
afterEach(()=>vi.restoreAllMocks());
describe('exact episode evidence fences',()=>{
 it('retains strict episode grounding and the permission boundary on cache hits',async()=>{
  await runInDurableObject(env.TALK_SESSIONS.getByName('cached-episode-boundary'),async(instance,state)=>{
   const service=instance as unknown as TalkSession,session=crypto.randomUUID(),expires=Date.now()+60000;
   const context=TalkContextSchema.parse({});const key=JSON.stringify(['dark',2017,'tv',2,1]);
   state.storage.sql.exec('INSERT INTO memory VALUES(?,?,?)','live:'+session,JSON.stringify({context,expires,calls:0,searches:0,grants:[key]}),expires);
   const cache='evidence:v4:'+JSON.stringify([key,true,context.imdbId,context.viewedRanges,context.completed]);
   state.storage.sql.exec('INSERT INTO memory VALUES(?,?,?)',cache,JSON.stringify([{id:'wiki-episode',title:'Dark',season:2,episode:1,text:plot,url:'https://en.wikipedia.org/wiki/Dark',license:'CC BY-SA'}]),expires);
   const result=await service.knowledge(session,{title:'Dark',year:2017,kind:'tv',season:2,episode:1,query:'recap'},'Erkläre Dark Staffel 2 Folge 1.');
   expect(result.episodeScope).toMatchObject({title:'Dark',season:2,episode:1,yearsMentioned:['2053']});expect(result.cached).toBe(true);expect(result.instruction).toContain('ONLY events expressly stated');expect(result.instruction).toContain('diary/object');expect(result.boundary).toBe('requested title/episode only');
  });
 });

 it('a new explicit episode boundary overrides older spoiler grants and cached future plots',async()=>{
  await runInDurableObject(env.TALK_SESSIONS.getByName('earlier-episode-after-grant'),async(instance,state)=>{
   const service=instance as unknown as TalkSession,session=crypto.randomUUID(),expires=Date.now()+60000;
   const context=TalkContextSchema.parse({season:2,episode:2,completed:true});const key=JSON.stringify(['dark',2017,'tv',2,2]);
   state.storage.sql.exec('INSERT INTO memory VALUES(?,?,?)','live:'+session,JSON.stringify({context,expires,calls:0,searches:0,grants:[key]}),expires);
   state.storage.sql.exec('INSERT INTO memory VALUES(?,?,?)','evidence:v4:'+JSON.stringify([key,true,context.imdbId,context.viewedRanges,context.completed]),JSON.stringify([{id:'future',text:'Future secret'}]),expires);
   const result=await service.knowledge(session,{title:'Dark',year:2017,kind:'tv',season:2,episode:2,query:'recap'},'Explain Dark season 2 episode 1.');
   expect(result).toMatchObject({evidence:[],unavailable:true,requestedEpisode:{season:2,episode:1}});
  });
 });
 it('reuses public revision evidence across sessions and source outages without caching private questions',async()=>{
  const claim=(value:unknown)=>({mainsnak:{datavalue:{value}}});
  const spy=vi.spyOn(globalThis,'fetch').mockImplementation(async u=>{
   const url=String(u);if(url.includes('api.tvmaze.com'))return new Response('outage',{status:503});
   if(url.includes('generator=search'))return Response.json({query:{pages:{1:{pageid:1,title:'Dark (TV series)',pageprops:{wikibase_item:'Q28443710'}}}}});
   if(url.includes('wbgetentities'))return Response.json({entities:{Q28443710:{claims:{P345:[claim('tt5753856')],P580:[claim({time:'+2017-12-01T00:00:00Z'})]}}}});
   if(url.includes('prop=info'))return Response.json({query:{pages:{1:{pageid:1,title:'Dark',revisions:[{revid:987}]}}}});
   if(url.includes('prop=sections'))return Response.json({parse:{sections:[{line:'Episodes',toclevel:1,index:'6'},{line:'Season 2 (2019)',toclevel:2,index:'8',anchor:'Season_2'}]}});
   return Response.json({parse:{text:{'*':table(rows(1,plot)+rows(2,'Future secret.'))}}});
  });
  await runInDurableObject(env.TALK_SESSIONS.getByName('shared-licensed-cache-test'),async(instance,state)=>{
   const service=instance as unknown as TalkSession;
   const q={title:'Dark',year:2017,kind:'tv' as const,season:2,episode:1,query:'PRIVATE QUESTION'};
   const first=await service.licensedEpisode(q);expect(first[0].text).toBe(plot);
   spy.mockImplementation(async()=>{throw Error('source unavailable');});const count=spy.mock.calls.length;
   expect(await service.licensedEpisode({...q,query:'another private question'})).toEqual(first);expect(spy.mock.calls.length).toBe(count);
   const rows=state.storage.sql.exec<{value:string}>('SELECT value FROM memory').toArray();expect(rows).toHaveLength(2);for(const row of rows){expect(row.value).not.toContain('PRIVATE');expect(row.value).not.toContain('Future secret');}
  });
 });
 it('resolves an exact short series title without relying on broad search ranking or a supplied year',async()=>{
  const urls:string[]=[];vi.spyOn(globalThis,'fetch').mockImplementation(async u=>{
   urls.push(String(u));return String(u).includes('prop=pageprops')
    ?Response.json({query:{pages:{1:{pageid:1,title:'Dark (TV series)',pageprops:{wikibase_item:'Q28443710'}}}}})
    :Response.json({entities:{Q28443710:{claims:{P345:[{mainsnak:{datavalue:{value:'tt5753856'}}}],P580:[{mainsnak:{datavalue:{value:{time:'+2017-12-01T00:00:00Z'}}}}]}}}});
  });
  expect(await wikipediaTitle({title:'Dark',kind:'tv',query:''},new AbortController().signal)).toMatchObject({imdbId:'tt5753856',year:2017});
  expect(urls).toHaveLength(2);expect(urls.some(u=>u.includes('generator=search'))).toBe(false);
 });
 it('matches a series premiere using P580 while rejecting the wrong year',async()=>{
  vi.spyOn(globalThis,'fetch').mockImplementation(async u=>String(u).includes('generator=search')
   ?Response.json({query:{pages:{1:{pageid:1,title:'Dark (TV series)',pageprops:{wikibase_item:'Q28443710'}}}}})
   :Response.json({entities:{Q28443710:{claims:{P345:[{mainsnak:{datavalue:{value:'tt5753856'}}}],P580:[{mainsnak:{datavalue:{value:{time:'+2017-12-01T00:00:00Z'}}}}],P31:[{mainsnak:{datavalue:{value:{id:'Q5398426'}}}}]}}}}));
  expect(await wikipediaTitle({title:'Dark',year:2017,kind:'tv',query:''},new AbortController().signal)).toMatchObject({imdbId:'tt5753856',year:2017});
  expect(await wikipediaTitle({title:'Dark',year:2019,kind:'tv',query:''},new AbortController().signal)).toBeUndefined();
 });
 it('extracts only the selected episode and rejects combined or ambiguous rows',()=>{
  const html=table(rows(1,plot)+rows(2,'FUTURE EVENT MUST NEVER REACH THE MODEL.'));
  expect(wikipediaEpisodeText(html,1)).toBe(plot);
  expect(wikipediaEpisodeText(html,3)).toBeUndefined();
  expect(wikipediaEpisodeText(html+html,1)).toBeUndefined();
  expect(wikipediaEpisodeText(html.replace('<td>1</td>','<td>1–2</td>'),1)).toBeUndefined();
 });
 it('selects the season under Episodes, not Music or Reception, with revision attribution',async()=>{
  const calls:string[]=[];
  vi.spyOn(globalThis,'fetch').mockImplementation(async u=>{
   const url=String(u);calls.push(url);
   if(url.includes('prop=info'))return Response.json({query:{pages:{1:{pageid:1,title:'Dark (TV series)',revisions:[{revid:987}]}}}});
   if(url.includes('prop=sections'))return Response.json({parse:{sections:[
    {line:'Episodes',toclevel:1,index:'6'},
    {line:'Season 2 (2019)',toclevel:2,index:'8',anchor:'Season_2_(2019)'},
    {line:'Music',toclevel:1,index:'13'},
    {line:'Season 2',toclevel:2,index:'14'}]}});
   expect(url).toContain('section=8');
   return Response.json({parse:{text:{'*':table(rows(1,plot)+rows(2,'Future episode secret.'))}}});
  });
  const result=await wikipediaEpisodeEvidence('Dark (TV series)',{title:'Dark',year:2017,kind:'tv',season:2,episode:1,query:'recap'},new AbortController().signal);
  expect(result).toHaveLength(1);expect(result[0]).toMatchObject({text:plot,season:2,episode:1});
  expect(result[0].url).toContain('oldid=987');expect(result[0].license).toContain('CC BY-SA');
  expect(calls).toHaveLength(3);
 });
 it('does not retrieve an ambiguous season or an unrelated section',async()=>{
  vi.spyOn(globalThis,'fetch').mockImplementation(async u=>String(u).includes('prop=info')
   ?Response.json({query:{pages:{1:{revisions:[{revid:987}]}}}})
   :Response.json({parse:{sections:[{line:'Music',toclevel:1,index:'2'},{line:'Season 2',toclevel:2,index:'3'}]}}));
  expect(await wikipediaEpisodeEvidence('Dark',{title:'Dark',kind:'tv',season:2,episode:1,query:'recap'},new AbortController().signal)).toEqual([]);
 });
});
