import type {StoryRequest,StorySources} from './talkStory';
const words=(text:string)=>text.toLowerCase().match(/[\p{L}\p{N}]{3,}/gu)||[];
const stop=new Set('the this that they them woman man person scene what where when why how does did has have was were with from into just earlier previous before after about there their would could should'.split(' '));

/** Keep the complete transcript available locally; send the compiler a relevant,
 * chronological selection rather than spending free-tier tokens on every line. */
export function budgetStoryEvidence(q:StoryRequest,sources:StorySources){
 const terms=new Set(words(q.question+' '+q.dialogue.slice(-8).map(row=>row[2]).join(' ')).filter(word=>!stop.has(word)));
 const names=new Set(sources.characters.flatMap(c=>words(c.name)).filter(word=>word.length>3));
 const score=(text:string)=>words(text).reduce((sum,word)=>sum+(terms.has(word)?4:0)+(names.has(word)?1:0),0);
 const selected=new Set<number>();let characters=0;
 const add=(index:number)=>{if(index<0||index>=q.dialogue.length||selected.has(index))return;
  const size=q.dialogue[index][2].length;if(characters+size<=16000){selected.add(index);characters+=size;}};
 for(let index=Math.max(0,q.dialogue.length-24);index<q.dialogue.length;index++)add(index);
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
 return {request:{...q,dialogue,transcriptComplete:q.transcriptComplete&&dialogue.length===q.dialogue.length},sources:{...sources,evidence},originalCueCount:q.dialogue.length};
}
