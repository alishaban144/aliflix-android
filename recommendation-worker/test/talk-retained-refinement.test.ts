import { describe,it,expect } from 'vitest';
import { refineTalkCollection } from '../src/talkIntent';
import type { RecommendationResult } from '../src/types';

const row=(id:number,year:number,extra:Partial<RecommendationResult>={}):RecommendationResult=>({tmdbId:id,mediaType:'movie',title:`Owned fixture ${id}`,releaseDate:`${year}-01-01`,genres:['Animation'],originCountries:['JP'],matchLevel:'Strong',finalScore:1,matchReasons:[],retrievalSources:['https://example.org/film'],...extra});
describe('catalogue refinements preserve verified work during research outages',()=>{
 const original=[row(1,2007),row(2,2019),row(3,2022),row(4,2024)];
 it('narrows a collection without changing it or excluding its qualifying titles',()=>{
  const refined=refineTalkCollection(original,'Only films from 2020 onward. Keep the other constraints.');
  expect(refined?.map(v=>v.tmdbId)).toEqual([3,4]);expect(original).toHaveLength(4);
  expect(refined?.every(v=>v.originCountries.includes('JP'))).toBe(true);
 });
 it('retains strict runtime and actual series status rather than guessed values',()=>{
  expect(refineTalkCollection([row(1,2024,{runtimeMinutes:89}),row(2,2024,{runtimeMinutes:90}),row(3,2024)],'Only films under 90 minutes')?.map(v=>v.tmdbId)).toEqual([1]);
  expect(refineTalkCollection([row(1,2024,{mediaType:'tv',status:'Ended'}),row(2,2024,{mediaType:'tv',status:'Returning Series'}),row(3,2024)],'Only ended series. Keep the other constraints from this search.')?.map(v=>v.tmdbId)).toEqual([1]);
 });
 it('never presents old evidence as proof of a new premise or an additional batch',()=>{
  for(const query of ['More different titles with the same constraints.','Only films from 2020 with no happy endings','Only films from 2020. Keep the other constraints. And no happy endings.','Find something like Paprika','Now suggest Icelandic thrillers'])expect(refineTalkCollection(original,query)).toBeNull();
 });
 it('requires research when no retained item can satisfy the new constraint',()=>{
  expect(refineTalkCollection(original,'Only films after 2025')).toBeNull();
 });
});
