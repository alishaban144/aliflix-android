import type { RecommendationFilters } from './types';
import { ServiceError } from './types';

/** Deterministic constraints are checked on catalogue facts after curation. */
export function discoveryConstraints(input:RecommendationFilters,query:string,year=new Date().getUTCFullYear()):RecommendationFilters {
 const filters={...input};
 // The latest explicit refinement replaces its dimension, while preserving others.
 const clauses=query.split(/[\n,;]+/).map(v=>v.trim()).filter(Boolean);
 for(const clause of clauses){
  if(/\b(?:recent|newer|latest|modern|more recent|neuere?|aktuell|neuste|letzten jahre)\b|أحدث|حديثة|حديثه|الأخيرة|الاخيرة/i.test(clause)){
   filters.minimumYear=year-3;filters.maximumYear=year;
  }
  const last=/(?:last|past|letzten)\s+(\d{1,2})\s*(?:years?|jahre)|آخر\s*(\d{1,2})\s*سنوات/i.exec(clause);
  if(last){filters.minimumYear=year-Number(last[1]||last[2])+1;filters.maximumYear=year;}
  const newer=/(?:newer|more recent)\s+than\s*((?:18|19|20)\d{2})/i.exec(clause);
  if(newer)filters.minimumYear=Number(newer[1])+1;
  for(const m of clause.matchAll(/(?:after|since|from|ab|nach|seit|بعد|منذ)\s*((?:18|19|20)\d{2})/gi)){
   if(/(?:not|nicht)\s+$/i.test(clause.slice(0,m.index))){filters.maximumYear=Number(m[1]);continue;}
   filters.minimumYear=Number(m[1])+(/^(?:after|nach|بعد)/i.test(m[0])?1:0);
   if(filters.maximumYear&&filters.maximumYear<filters.minimumYear)filters.maximumYear=undefined;
  }
  for(const m of clause.matchAll(/(?:before|until|through|vor|bis|قبل|حتى)\s*((?:18|19|20)\d{2})/gi)){
   if(/(?:not|nicht)\s+$/i.test(clause.slice(0,m.index))){filters.minimumYear=Number(m[1]);continue;}
   filters.maximumYear=Number(m[1])-(/^(?:before|vor|قبل)/i.test(m[0])?1:0);
  }
  const range=/\b((?:18|19|20)\d{2})\s*(?:-|–|to|through|bis|إلى)\s*((?:18|19|20)\d{2})/.exec(clause);
  if(range){filters.minimumYear=Number(range[1]);filters.maximumYear=Number(range[2]);}
  const decade=/\b((?:19|20)\d)0s\b/.exec(clause);
  if(decade){filters.minimumYear=Number(decade[1]+'0');filters.maximumYear=Number(decade[1]+'9');}
  const runtime=/(?:under|less than|at most|max(?:imum)?|unter|höchstens|أقل من)\s*(\d{1,3})\s*(?:min(?:ute)?s?|minutes?|minuten|دقيقة)/i.exec(clause);
  if(runtime)filters.maximumRuntimeMinutes=Number(runtime[1])-(/^(?:under|less than|unter|أقل من)/i.test(runtime[0])?1:0);
  const hours=/(?:under|less than|at most|max(?:imum)?|no longer than|unter|höchstens|nicht länger als|أقل من)\s*(\d+(?:\.\d+)?|one|two|three|eine?|zwei|drei)\s*(?:hours?|hrs?|h|stunden?)(?:\s*(\d{1,2})\s*(?:min(?:ute)?s?|minuten)?)?/i.exec(clause);
  if(hours){const words:Record<string,number>={one:1,two:2,three:3,ein:1,eine:1,zwei:2,drei:3};const number=words[hours[1].toLowerCase()]??Number(hours[1]);filters.maximumRuntimeMinutes=Math.floor(number*60+Number(hours[2]||0))-(/^(?:under|less than|unter|أقل من)/i.test(hours[0])?1:0);}
 }
 if(filters.minimumYear&&filters.maximumYear&&filters.minimumYear>filters.maximumYear||filters.maximumRuntimeMinutes!==undefined&&filters.maximumRuntimeMinutes<1)
  throw new ServiceError('CONTRADICTORY_FILTERS','Constraints do not overlap',400,false);
 return filters;
}
