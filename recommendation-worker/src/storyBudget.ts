import type {StoryRequest,StorySources} from './talkStory';
const words=(text:string)=>text.toLowerCase().match(/[\p{L}\p{N}]{3,}/gu)||[];
const stop=new Set('the this that they them woman man person scene what where when why how does did has have was were with from into just earlier previous before after about there their would could should and but not for are had his her him she our you your who which only also then than out can may its all any some most more been being will other while each both tells says asks film films movie movies series episode season american starring directed based released very real end year years days believe'.split(' '));

/** Retrieve whole exchanges, not the first isolated mention of a clue. Ranking
 * uses only the selected-cut prefix; untimed public prose supplies hints only. */
export function storySupportingDialogue(q:StoryRequest,hints:string[]){
 const query=new Set(words(q.question+' '+q.dialogue.slice(-8).map(row=>row[2]).join(' ')).filter(word=>!stop.has(word)));
 const expanded=new Set(hints);
 const tokens=q.dialogue.map(row=>new Set(words(row[2])));
 const candidates=tokens.map((row,index)=>({index,hit:[...row].some(word=>query.has(word)||expanded.has(word))})).filter(row=>row.hit);
 const ranked=candidates.map(({index})=>{
  const window=new Set(tokens.slice(Math.max(0,index-7),index+8).flatMap(row=>[...row]));
  return {index,score:[...window].reduce((sum,word)=>sum+(query.has(word)?3:0)+(expanded.has(word)?2:0),0)};
 }).sort((a,b)=>b.score-a.score||a.index-b.index);
 const selected=new Set<number>();let characters=0,windows=0;
 const addWindow=(hit:{index:number})=>{
  if(selected.has(hit.index)||windows>=12)return;
  windows++;
  for(let index=Math.max(0,hit.index-7);index<Math.min(q.dialogue.length,hit.index+8);index++){
   const size=q.dialogue[index][2].length;
   if(!selected.has(index)&&characters+size<=8000){selected.add(index);characters+=size;}
  }
 };
 // A dense generic exchange must not crowd out a rare causal clue later on.
 for(const hint of hints){const hit=ranked.find(row=>tokens[row.index].has(hint));if(hit)addWindow(hit);}
 for(const hit of ranked)addWindow(hit);
 return [...selected].sort((a,b)=>a-b).map(id=>({startSeconds:q.dialogue[id][0],endSeconds:q.dialogue[id][1],text:q.dialogue[id][2]}));
}

/** Keep the complete transcript available locally; send the compiler a relevant,
 * chronological selection rather than spending free-tier tokens on every line. */
export function budgetStoryEvidence(q:StoryRequest,sources:StorySources){
 const terms=new Set(words(q.question+' '+q.dialogue.slice(-8).map(row=>row[2]).join(' ')).filter(word=>!stop.has(word)));
 const names=new Set(sources.characters.flatMap(c=>words(c.name)).filter(word=>word.length>3));
 const score=(text:string)=>words(text).reduce((sum,word)=>sum+(terms.has(word)?4:0)+(names.has(word)?1:0),0);
 // Untimed research supplies search terms, never scene facts. Only terms also
 // present in this selected-cut earlier dialogue can expand retrieval.
 const frequency=new Map<string,number>();
 for(const row of q.dialogue)for(const word of new Set(words(row[2])))frequency.set(word,(frequency.get(word)||0)+1);
 const hintWeights=new Map<string,number>();
 const titleTerms=new Set(words(sources.title));
 for(const text of sources.evidence.flatMap(source=>source.text.split(/\n\s*\n|(?<=[.!?])\s+(?=[A-Z])/))){
  for(const word of new Set(words(text))){
   const count=frequency.get(word)||0;
   if(count>0&&count<=20&&score(text)>0&&!stop.has(word)&&!titleTerms.has(word)&&!names.has(word)&&!names.has(word.replace(/s$/,''))&&!terms.has(word))
    hintWeights.set(word,(hintWeights.get(word)||0)+(1+score(text)/16)/Math.sqrt(count));
  }
 }
 const hints=new Set([...hintWeights].sort((a,b)=>b[1]-a[1]||a[0].localeCompare(b[0])).slice(0,8).map(([word])=>word));
 const selected=new Set<number>();let characters=0;
 const add=(index:number)=>{if(index<0||index>=q.dialogue.length||selected.has(index))return;
  const size=q.dialogue[index][2].length;if(characters+size<=16000){selected.add(index);characters+=size;}};
 for(let index=Math.max(0,q.dialogue.length-24);index<q.dialogue.length;index++)add(index);
 const hintHits=q.dialogue.map((row,index)=>({index,rank:words(row[2]).reduce((sum,word)=>sum+(hints.has(word)?hintWeights.get(word)||0:0),0)})).filter(hit=>hit.rank>0).sort((a,b)=>b.rank-a.rank||a.index-b.index);
 for(const hit of hintHits.slice(0,6))for(const delta of [0,-1,1])add(hit.index+delta);
 const ranked=q.dialogue.map((row,index)=>({index,score:score(row[2])})).filter(item=>item.score>0).sort((a,b)=>b.score-a.score||a.index-b.index);
 for(const hit of ranked)for(const delta of [0,-1,1,-2,2])add(hit.index+delta);
 for(let index=0;index<Math.min(8,q.dialogue.length);index++)add(index);
 const dialogue=[...selected].sort((a,b)=>a-b).map(index=>q.dialogue[index]);
 let researchBudget=6000;
 const evidence=sources.evidence.map(source=>{
  const paragraphs=source.text.split(/\n\s*\n|(?<=[.!?])\s+(?=[A-Z])/).map((text,index)=>({text,index,score:score(text)}));
  const chosen=paragraphs.sort((a,b)=>b.score-a.score||a.index-b.index).slice(0,5).sort((a,b)=>a.index-b.index);
  const text=chosen.map(p=>p.text).join('\n').slice(0,Math.min(2400,researchBudget));researchBudget-=text.length;
  return {...source,text};
 }).filter(source=>source.text.length>0);
 return {request:{...q,dialogue,transcriptComplete:q.transcriptComplete&&dialogue.length===q.dialogue.length},sources:{...sources,evidence},originalCueCount:q.dialogue.length,establishedSearchTerms:[...hints]};
}
