import {afterEach,describe,expect,it,vi} from 'vitest';
import {wikipediaEpisodeEvidence,wikipediaEpisodeText} from '../src/talkEvidence';
const plot='Jonas explores the post-apocalyptic power plant in 2053. He discovers a mysterious black substance.';
const rows=(n:number,text:string)=>`<tr class="vevent module-episode-list-row"><th>${n+10}</th><td>${n}</td><td class="summary">Title</td></tr><tr class="expand-child"><td class="description"><p>${text}</p></td></tr>`;
const table=(body:string)=>`<table class="wikitable wikiepisodetable">${body}</table>`;
afterEach(()=>vi.restoreAllMocks());
describe('exact episode evidence fences',()=>{
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
