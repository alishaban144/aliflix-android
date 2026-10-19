import {test} from 'node:test';
import assert from 'node:assert/strict';
import {awaitAskReadiness,verifyAskCollections} from './verify-ask-readiness.mjs';
const at=1792454460000;
const complete=()=>['movie','tv'].map(mediaType=>({publishedAt:at,results:Array.from({length:20},(_,i)=>({tmdbId:i+1,mediaType,title:'Verified '+i,retrievalSources:['https://www.bfi.org.uk/films']}))}));
test('requires distinct, sourced identities and one real atomic publication',()=>{
 verifyAskCollections(complete(),at);
 for(const mutate of [c=>c[1].publishedAt--,c=>c[0].results[1].tmdbId=1,c=>c[0].results[0].retrievalSources=[],c=>c[0].results[0].retrievalSources=['http://insecure.test']]){const c=complete();mutate(c);assert.throws(()=>verifyAskCollections(c,at));}
});
test('waits for a real bootstrap then verifies every result',async()=>{
 let time=at,reads=0;
 const result=await awaitAskReadiness({maxWaitMs:60000,now:()=>time,wait:async ms=>{time+=ms;},read:async()=>++reads===1?[{results:[],state:'limited'},{results:[],state:'warming'}]:complete()});
 assert.equal(reads,2);assert.equal(result[1].results.length,20);
});
test('quota or repeated empty results cannot bypass the deadline',async()=>{
 let time=at;
 await assert.rejects(awaitAskReadiness({maxWaitMs:60000,now:()=>time,wait:async ms=>{time+=ms;},read:async()=>[{results:[],state:'limited'},{results:[],state:'limited'}]}),/20 required/);
 assert.equal(time,at+60000);
});
test('corrupt data fails immediately even if the other collection is warming',async()=>{
 const c=complete();c[0].results[0].retrievalSources=[];c[1]={results:[],state:'warming'};
 await assert.rejects(awaitAskReadiness({maxWaitMs:60000,now:()=>at,wait:async()=>{throw Error('must not wait');},read:async()=>c}),/metadata/);
});
test('a transient read failure cannot publish or immediately abort a bounded wait',async()=>{
 let reads=0,time=at;
 const result=await awaitAskReadiness({maxWaitMs:60000,now:()=>time,wait:async ms=>{time+=ms;},read:async()=>{if(++reads===1)throw Error('temporary network failure');return complete();}});
 assert.equal(reads,2);assert.equal(result[0].results.length,20);
});
