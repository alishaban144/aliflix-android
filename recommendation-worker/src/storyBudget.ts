import type {StoryRequest,StorySources} from './talkStory';
const words=(text:string)=>text.toLowerCase().match(/[\p{L}\p{N}]{3,}/gu)||[];
const stop=new Set('the this that they them woman man person scene what where when why how does did has have was were with from into just earlier previous before after about there their would could should and but not for are had his her him she our you your who which only also then than out can may its all any some most more been being will other while each both tells says asks film films movie movies series episode season american starring directed based released very real end year years days believe'.split(' '));

/** Retrieve whole exchanges, not the first isolated mention of a clue. Ranking
 * uses only the selected-cut prefix; untimed public prose supplies hints only. */
export function storySupportingDialogue(q:StoryRequest,hints:string[],limit=8000){
 const query=new Set(words(q.question).filter(word=>!stop.has(word)));
 const current=new Set(words(q.dialogue.slice(-12).map(row=>row[2]).join(' ')).filter(word=>!stop.has(word)));
 const expanded=new Set(hints);
 const tokens=q.dialogue.map(row=>new Set(words(row[2])));
 const frequency=new Map<string,number>();
 for(const row of tokens)for(const word of row)frequency.set(word,(frequency.get(word)||0)+1);
 const weights=new Map<string,number>();
 for(const word of new Set([...query,...current,...expanded])){
  const weight=(query.has(word)?6:0)+(current.has(word)?1:0)+(expanded.has(word)?2:0);
  weights.set(word,weight/Math.sqrt(1+(frequency.get(word)||0)/8));
 }
 const candidates=tokens.map((row,index)=>({index,hit:[...row].some(word=>weights.has(word))})).filter(row=>row.hit);
 const ranked=candidates.map(({index})=>{
  const window=new Set(tokens.slice(Math.max(0,index-7),index+8).flatMap(row=>[...row]));
  return {index,score:[...window].reduce((sum,word)=>sum+(weights.get(word)||0),0)};
 }).sort((a,b)=>b.score-a.score||a.index-b.index);
 const selected=new Set<number>();let characters=0,windows=0;
 const addCue=(index:number)=>{
  const size=q.dialogue[index][2].length;
  if(!selected.has(index)&&characters+size<=limit){selected.add(index);characters+=size;}
 };
 const addWindow=(hit:{index:number},radius=7)=>{
  if(selected.has(hit.index)||windows>=12)return false;
  windows++;
  for(let index=Math.max(0,hit.index-radius);index<Math.min(q.dialogue.length,hit.index+radius+1);index++)addCue(index);
  return true;
 };
 // Preserve the actual current exchange. Reserve room for distinct earlier
 // encounters before a dense current conversation can consume the budget.
 for(let index=Math.max(0,q.dialogue.length-12);index<q.dialogue.length;index++)addCue(index);
 const earlierStarts:number[]=[];
 for(const hit of ranked){
  if(earlierStarts.length>=3)break;
  const time=q.dialogue[hit.index][0];
  if(time>q.anchorSeconds-90||earlierStarts.some(start=>Math.abs(start-time)<30))continue;
  if(addWindow(hit,11))earlierStarts.push(time);
 }
 // A dense generic exchange must not crowd out a rare causal clue later on.
 for(const hint of hints){const hit=ranked.find(row=>tokens[row.index].has(hint));if(hit)addWindow(hit);}
 for(const hit of ranked)addWindow(hit);
 return [...selected].sort((a,b)=>a-b).map(id=>({startSeconds:q.dialogue[id][0],endSeconds:q.dialogue[id][1],text:q.dialogue[id][2]}));
}

/** A current target's earlier offers or refusals can explain the planner's
 * goal. Surface exact quotes so a conspicuous payment cannot replace them. */
export function storyPriorIntent(q:StoryRequest,characters:StorySources['characters']){
 if(!/\b(?:why|goal|hope|trying|intend|purpose|plan|want)\b/i.test(q.question))return [];
 const current=new Set(words(q.question+' '+q.dialogue.slice(-16).map(row=>row[2]).join(' ')));
 const earlier=q.dialogue.filter(row=>row[1]<=q.anchorSeconds-90);
 const tokens=earlier.map(row=>new Set(words(row[2])));
 const specific=/\b(?:hire[ds]?|represent(?:ed|ing)?|offer(?:ed|s)?|promis(?:e|ed|es)|refus(?:e|ed|es)|declin(?:e|ed|es)|owe[ds]?)\b/i;
 const general=/\b(?:want(?:ed|s)?|need(?:ed|s)?|ask(?:ed|s)?|agree[ds]?|help(?:ed|s)?|pay|paid)\b/i;
 const names=characters.map(({name})=>({name,terms:words(name).filter(word=>word.length>=4&&current.has(word)&&!stop.has(word))}))
  .filter(person=>person.terms.length).slice(0,8);
 const results=names.flatMap(person=>{
  let best:{name:string;score:number;dialogue:StoryRequest['dialogue']}|undefined;
  tokens.forEach((row,index)=>{
   const nameMatches=person.terms.filter(term=>row.has(term)).length;
   if(!nameMatches)return;
   const candidates=earlier.slice(Math.max(0,index-9),index+12)
    .filter(cue=>Math.abs(cue[0]-earlier[index][0])<=35);
   const quotes=candidates.map(cue=>({cue,rank:specific.test(cue[2])?4:general.test(cue[2])?1:0}))
    .filter(item=>item.rank>0).sort((a,b)=>b.rank-a.rank||a.cue[0]-b.cue[0]).slice(0,3);
   if(!quotes.some(item=>item.rank>=4))return;
   const score=nameMatches+quotes.reduce((sum,item)=>sum+item.rank,0);
   if(!best||score>best.score)best={name:person.name,score,dialogue:quotes.map(item=>item.cue).sort((a,b)=>a[0]-b[0])};
  });
  return best?[best]:[];
 });
 return results.sort((a,b)=>b.score-a.score).slice(0,2).map(({name,dialogue})=>({personReference:name,dialogue}));
}

/** A first-person callback needs a small earlier exchange, separated from the
 * broader dialogue so the model can distinguish it from later discussion. */
export function storyPriorEncounter(q:StoryRequest,speakerLabel?:string){
 if(!/\b(?:earlier|before|previous|repeat)\b|\b(?:how|where|when)\b.{0,50}\b(?:know|learn|find|meet|recogniz\w*)\b/i.test(q.question))return [];
 const terms=new Set(words(q.question).filter(word=>word.length>=5&&!stop.has(word)&&word!=='these'));
 const earlier=q.dialogue.filter(row=>row[1]<=q.anchorSeconds-90);
 const hits=earlier.map(row=>[...new Set(words(row[2]).filter(word=>terms.has(word)))]);
 const frequency=new Map<string,number>();
 for(const row of hits)for(const word of row)frequency.set(word,(frequency.get(word)||0)+1);
 let best=-1;
 let bestScore=0;
 hits.forEach((row,index)=>{
  if(!row.length)return;
  const namedSpeaker=!!speakerLabel&&earlier.slice(Math.max(0,index-10),index+13).some(cue=>cue[2].includes(`${speakerLabel}:`));
  const score=row.reduce((sum,word)=>sum+1/Math.sqrt(frequency.get(word)||1),0)+(namedSpeaker?2:0);
  if(score>bestScore){best=index;bestScore=score;}
 });
 if(best<0)return [];
 const center=earlier[best][0];
 const candidates=earlier.slice(Math.max(0,best-10),best+13).filter(cue=>Math.abs(cue[0]-center)<=36);
 const labelled=(cue:StoryRequest['dialogue'][number])=>speakerLabel&&cue[2].includes(`${speakerLabel}:`)?1:0;
 candidates.sort((a,b)=>labelled(b)-labelled(a)||Math.abs(a[0]-center)-Math.abs(b[0]-center));
 let size=0;
 return candidates.filter(cue=>{
  if(size+cue[2].length>2400)return false;
  size+=cue[2].length;
  return true;
 }).sort((a,b)=>a[0]-b[0]);
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
