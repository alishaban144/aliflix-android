/** Scene extraction has its own latency profile; it must not inherit the
 * general agent model or send a thinking level that model does not support. */
export function geminiSceneConfig(env: { GEMINI_SCENE_MODEL?: string }) {
 const model = env.GEMINI_SCENE_MODEL || 'gemini-3.6-flash';
 const thinkingLevel = /^gemini-3\.[56]-flash(?:$|-)/.test(model) ? 'minimal' : 'low';
 return { model, thinkingLevel };
}
