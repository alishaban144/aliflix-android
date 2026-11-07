/** Scene extraction has its own latency profile; it must not inherit the
 * general agent model or send a thinking level that model does not support. */
export function geminiSceneConfig(env: { GEMINI_SCENE_MODEL?: string }) {
 const model = env.GEMINI_SCENE_MODEL || 'gemini-3.6-flash';
 const thinkingLevel = model.includes('flash-lite') ? 'minimal' : 'low';
 return { model, thinkingLevel };
}

/** Provider grammar uses the portable schema subset; all size, reference and
 * strict-object constraints remain enforced by the application validator. */
export function geminiSceneSchema(value:unknown):unknown{
 if(Array.isArray(value))return value.map(geminiSceneSchema);
 if(!value||typeof value!=='object')return value;
 const allowed=new Set(['type','properties','required','items','enum','description']);
 return Object.fromEntries(Object.entries(value).filter(([key])=>allowed.has(key)).map(([key,item])=>
  [key,key==='properties'?Object.fromEntries(Object.entries(item as Record<string,unknown>).map(([name,schema])=>[name,geminiSceneSchema(schema)])):geminiSceneSchema(item)]));
}
