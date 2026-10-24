import {env,runInDurableObject} from 'cloudflare:test';
import {afterEach,describe,it,expect,vi} from 'vitest';
import {AskResearch} from '../src/askResearch';

afterEach(()=>vi.restoreAllMocks());
describe('Similar creative relationships',()=>{
 it('uses structured creator credits, ignores acting/unreleased credits and keeps exact IDs',async()=>{
  vi.spyOn(globalThis,'fetch').mockImplementation(async url=>{
   if(String(url).includes('/search/tv'))return Response.json({results:[{id:6,name:'Anchor',first_air_date:'2005-01-01',genre_ids:[80,18],vote_count:100},{id:7,name:'Anchor',first_air_date:'2004-01-01',genre_ids:[35],vote_count:1000}]});
   if(String(url).includes('/person/44/combined_credits'))return Response.json({crew:[{id:10,name:'Linked original creator series',media_type:'tv',job:'Novel',first_air_date:'2021-01-01',genre_ids:[80,18],vote_count:100}]});
   expect(String(url)).toContain('/person/42/combined_credits');
   return Response.json({cast:[{id:88,name:'Only acting',media_type:'tv',first_air_date:'2021-01-01',genre_ids:[80],vote_count:100}],crew:[
    {id:2,name:'Closest creative connection',media_type:'tv',job:'Novel',first_air_date:'2021-01-01',genre_ids:[80,18],vote_count:200},
    {id:3,name:'Future project',media_type:'tv',job:'Creator',first_air_date:'2099-01-01',genre_ids:[80],vote_count:100},
    {id:4,title:'Different format',media_type:'movie',job:'Writer',release_date:'2020-01-01',genre_ids:[80],vote_count:100},
   ]});
  });
  await runInDurableObject(env.ASK_RESEARCH.getByName('related-structured-credits'),async(instance,state)=>{
   const original=(instance as any).env.TMDB_API_KEY;(instance as any).env.TMDB_API_KEY='test-only';
   const anchor={tmdbId:1,mediaType:'tv',title:'Anchor',year:'2007',genreIds:[80,18],creatorIds:[42],creators:['An actual creator'],related:[{id:5,title:'Community connection',year:2020}]};
   state.storage.sql.exec('INSERT INTO ask_cache VALUES(?,?,?)','anchor-context-v3:tv:1',JSON.stringify(anchor),Date.now()+60000);
   state.storage.sql.exec('INSERT INTO ask_cache VALUES(?,?,?)','anchor-context-v3:tv:6',JSON.stringify({...anchor,tmdbId:6,creatorIds:[44],creators:['Original creator']}),Date.now()+60000);
   try {
    const rows=await (instance as unknown as AskResearch).relatedPicks('tv',1);
    expect(rows.map(v=>v.id)).toEqual([2,10,6,5]);
    expect(rows[0]).toMatchObject({title:'Closest creative connection',sharedCreator:true,why:'Creative connection: An actual creator'});
    expect(rows[1]).toMatchObject({sharedCreator:true,why:'Creative connection: Original creator'});
   } finally {(instance as any).env.TMDB_API_KEY=original;}
  });
 });
 it('retains closest creator matches during Gemini failure, while respecting dislikes',async()=>{
  vi.spyOn(globalThis,'fetch').mockImplementation(async url=>{
   if(String(url).includes('tavily.com'))return Response.json({results:[{title:'Crime series research',url:'https://example.org/research',content:'Thoughtful crime television series recommendations.'}],usage:{credits:1}});
   const pair=new WebSocketPair();pair[1].accept();
   pair[1].addEventListener('message',()=>pair[1].send(JSON.stringify({error:{message:'Temporarily unavailable'}})));
   return new Response(null,{status:101,webSocket:pair[0]});
  });
  await runInDurableObject(env.ASK_RESEARCH.getByName('related-capacity-fallback'),async instance=>{
   const binding=(instance as any).env,original={ASK_RESEARCH:binding.ASK_RESEARCH,TAVILY_API_KEY:binding.TAVILY_API_KEY,GEMINI_API_KEY:binding.GEMINI_API_KEY};
   binding.TAVILY_API_KEY='test';binding.GEMINI_API_KEY='test';
   binding.ASK_RESEARCH={getByName:()=>({anchorContext:async()=>({tmdbId:1,mediaType:'tv',title:'Anchor',year:'2007',creators:['Creator']}),relatedPicks:async()=>[
    {id:2,title:'Closest connection',year:2021,sharedCreator:true,why:'Creative connection: Creator'},
    {id:3,title:'Other connection',year:2020},
   ],resolveBatch:async(type:string,picks:Array<{id:number;title:string;year:number}>)=>picks.map(v=>({tmdbId:v.id,mediaType:type,title:v.title,releaseDate:v.year+'-01-01',genres:['Crime','Drama'],matchReasons:[],retrievalSources:[]}))})};
   try {
    const request={mode:'similar',query:'',mediaType:'tv',anchors:[{tmdbId:1,mediaType:'tv',title:'Anchor'}],pageSize:24,filters:{},library:[]};
    const result=await (instance as unknown as AskResearch).recommend('owner',{...request,requestId:crypto.randomUUID()});
    expect(result.results.map(v=>v.tmdbId)).toEqual([2,3]);
    expect(result.results[0].retrievalSources).toContain('https://www.themoviedb.org/tv/2');
    expect(result.metrics).toMatchObject({geminiCalls:1,clefCalls:0,tavilyCredits:1,pipeline:'tavily-tmdb'});
    const filtered=await (instance as unknown as AskResearch).recommend('owner',{...request,requestId:crypto.randomUUID(),library:[{id:2,type:'tv',kind:'dislike'}]});
    expect(filtered.results.map(v=>v.tmdbId)).toEqual([3]);
   } finally {Object.assign(binding,original);}
  });
 });
});
