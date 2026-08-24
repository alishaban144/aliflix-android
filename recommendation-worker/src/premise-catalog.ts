import { MediaType } from './types';

interface EditorialPremise {
  mediaType: MediaType;
  matches: (query: string) => boolean;
  seedTitles: string[];
}

const has = (query: string, pattern: RegExp): boolean => pattern.test(query);

/**
 * Editorial anchors cover premise archetypes for which TMDB keyword coverage is
 * known to be sparse. They only retrieve canonical TMDB identities; candidates
 * still have to pass synopsis/genre/keyword relevance checks after hydration.
 */
const EDITORIAL_PREMISES: EditorialPremise[] = [
  {
    mediaType: 'movie',
    matches: query => has(query, /\b(?:aliens?|extraterrestrials?|ufos?)\b/u) && has(query, /\b(?:abduct|abduction|kidnap)/u),
    seedTitles: ['Fire in the Sky', 'The Fourth Kind', 'Dark Skies', 'Communion', 'Alien Abduction', 'The UFO Incident', 'Phoenix Forgotten', 'No One Will Save You'],
  },
  {
    mediaType: 'movie',
    matches: query => has(query, /\b(?:natural disaster|natural catastrophe|disaster movie|earthquake|tornado|tsunami|volcan|hurricane|flood|wildfire|extreme storm)/u),
    seedTitles: ["The Impossible", 'Twister', 'Twisters', "Dante's Peak", 'San Andreas', 'The Day After Tomorrow', 'The Wave', 'The Perfect Storm', '2012', 'Crawl'],
  },
  {
    mediaType: 'movie',
    matches: query => has(query, /\b(?:kids?|children|teens?|teenagers?|adolescents?|young people|youth)\b/u) && has(query, /\b(?:supernatural|psychic|superhuman|superpowers?|powers|abilities)\b/u),
    seedTitles: ['Chronicle', 'Freaks', 'Midnight Special', 'The Innocents', 'Brightburn', 'Push', 'Code 8', 'The Darkest Minds'],
  },
  {
    mediaType: 'tv',
    matches: query => has(query, /\b(?:trap(?:ped)?|stranded|cannot escape|inescapable|cut off)\b/u) && has(query, /\b(?:mysterious|unknown|place|location|town|island)\b/u),
    seedTitles: ['From', 'Wayward Pines', 'Lost', 'Under the Dome', 'The I-Land', 'The Wilds', 'Yellowjackets', 'Persons Unknown'],
  },
  {
    mediaType: 'tv',
    matches: query => has(query, /\b(?:artificial intelligence|ai|robot|android|synthetic)/u) && has(query, /\b(?:conscious|sentient|self aware|self-aware|awakening|aware)/u),
    seedTitles: ['Westworld', 'Humans', 'Person of Interest', 'Better Than Us', 'Raised by Wolves', 'Almost Human', 'Next', 'Pantheon'],
  },
  {
    mediaType: 'tv',
    matches: query => has(query, /\b(?:kids?|children|teens?|teenagers?|adolescents?|young people|youth)\b/u) && has(query, /\b(?:supernatural|psychic|superhuman|superpowers?|powers|abilities)\b/u),
    seedTitles: ['Stranger Things', 'The Gifted', 'I Am Not Okay with This', 'Misfits', 'The Umbrella Academy', 'Raising Dion', 'The Imperfects', "Marvel's Runaways"],
  },
];

export function editorialPremiseSeeds(query: string, mediaType: MediaType): string[] {
  const normalized = query.toLocaleLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
  return [...new Set(EDITORIAL_PREMISES
    .filter(premise => premise.mediaType === mediaType && premise.matches(normalized))
    .flatMap(premise => premise.seedTitles))];
}

const EDITORIAL_RELEASE_YEARS = new Map<string, number>([
  ['movie:fire in the sky', 1993], ['movie:the fourth kind', 2009], ['movie:dark skies', 2013],
  ['movie:communion', 1989], ['movie:alien abduction', 2014], ['movie:the ufo incident', 1975],
  ['movie:phoenix forgotten', 2017], ['movie:no one will save you', 2023],
  ['movie:the impossible', 2012], ['movie:twister', 1996], ['movie:twisters', 2024],
  ["movie:dante's peak", 1997], ['movie:san andreas', 2015], ['movie:the day after tomorrow', 2004],
  ['movie:the wave', 2015], ['movie:the perfect storm', 2000], ['movie:2012', 2009], ['movie:crawl', 2019],
  ['movie:chronicle', 2012], ['movie:freaks', 2019], ['movie:midnight special', 2016],
  ['movie:the innocents', 2021], ['movie:brightburn', 2019], ['movie:push', 2009],
  ['movie:code 8', 2019], ['movie:the darkest minds', 2018],
  ['tv:from', 2022], ['tv:wayward pines', 2015], ['tv:lost', 2004], ['tv:under the dome', 2013],
  ['tv:the i land', 2019], ['tv:the wilds', 2020], ['tv:yellowjackets', 2021], ['tv:persons unknown', 2010],
  ['tv:westworld', 2016], ['tv:humans', 2015], ['tv:person of interest', 2011], ['tv:better than us', 2018],
  ['tv:raised by wolves', 2020], ['tv:almost human', 2013], ['tv:next', 2020], ['tv:pantheon', 2022],
  ['tv:stranger things', 2016], ['tv:the gifted', 2017], ['tv:i am not okay with this', 2020],
  ['tv:misfits', 2009], ['tv:the umbrella academy', 2019], ['tv:raising dion', 2019],
  ['tv:the imperfects', 2022], ["tv:marvel s runaways", 2017],
]);

export function editorialPremiseSeedYear(title: string, mediaType: MediaType): number | undefined {
  const normalizedTitle = title.toLocaleLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
  return EDITORIAL_RELEASE_YEARS.get(`${mediaType}:${normalizedTitle}`);
}
