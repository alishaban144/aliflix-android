/** Resolve era preference at the request boundary, rather than trusting model memory. */
export function talkRecommendationPolicy(query: string, now = new Date()) {
  const year = now.getUTCFullYear();
  const eraRequested = /\b(?:classic|classics|older|old|retro|vintage|any (?:year|era)|all time|of all time|before|between|from (?:the )?(?:19|20)\d{2}|(?:19|20)\d0s|(?:19|20)\d{2}\s*(?:to|through|[-–])\s*(?:19|20)\d{2})\b|كلاسيك|قديم|جميع السنوات|älter|klassiker|vor \d{4}/i.test(query) || /^(?:19|20)\d{2}\b/.test(query.trim());
  return eraRequested ? { query, filters: {} } : {
    query: `${query}\nRecommend released ${year - 3}–${year} titles first, as of ${now.toISOString().slice(0,10)}. Use dated current sources. Exclude unreleased announcements. Do not substitute a decades-old classic.`,
    filters: { minimumYear: year - 3, maximumYear: year },
  };
}
