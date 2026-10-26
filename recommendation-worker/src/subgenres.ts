import { MediaType } from './types';

export type Subgenre = { id: string; name: string; keywords: string[]; genres: number[] };
type Theme = [name: string, keywords?: string, genres?: number[]];

// Editorial subgenres, constrained by the parent genre AND a specific theme.
// Keyword names are resolved against TMDB; unknown keywords never widen a query.
const movie: Record<number, Theme[]> = {
  18: [
    ['Crime Drama', '', [80]], ['Romantic Drama', '', [10749]], ['Historical Drama', '', [36]],
    ['Social Issue Drama', 'social issues|social injustice'], ['Political Drama', 'politics|political corruption'],
    ['Family Drama', 'family relationships|dysfunctional family'], ['Teen Drama', 'teenage life|teenager'],
    ['Medical Drama', 'hospital|doctor'], ['Legal Drama', 'lawyer|lawsuit'], ['Biographical Drama', 'biography'],
    ['War Drama', '', [10752]], ['Sports Drama', 'sports'], ['Coming-of-Age Drama', 'coming of age'],
    ['Psychological Drama', 'psychological drama'], ['Workplace Drama', 'workplace|office'],
    ['Period Drama', 'period drama'], ['Music Drama', '', [10402]], ['Courtroom Drama', 'courtroom'],
    ['Dramedy', '', [35]], ['Based-on-a-True-Story Drama', 'based on true story'],
  ],
  878: [
    ['Alien Sci-Fi', 'alien'], ['Space Sci-Fi', 'space'], ['Dystopian Sci-Fi', 'dystopia'],
    ['Cyberpunk', 'cyberpunk'], ['Time-Travel Sci-Fi', 'time travel'], ['Post-Apocalyptic Sci-Fi', 'post-apocalyptic future'],
    ['Apocalyptic Sci-Fi', 'apocalypse'], ['Artificial Intelligence Sci-Fi', 'artificial intelligence'],
    ['Robot & Android Sci-Fi', 'robot|android'], ['Genetic Engineering Sci-Fi', 'genetic engineering'],
    ['Alternate-Reality Sci-Fi', 'alternate reality'], ['Parallel-Universe Sci-Fi', 'parallel universe'],
    ['Space Exploration Sci-Fi', 'space exploration'], ['First-Contact Sci-Fi', 'first contact'],
    ['Military Sci-Fi', 'space war|space battle'], ['Sci-Fi Horror', '', [27]], ['Sci-Fi Thriller', '', [53]],
    ['Sci-Fi Action', '', [28]], ['Sci-Fi Adventure', '', [12]], ['Speculative Sci-Fi', 'futuristic|future society'],
  ],
  53: [
    ['Psychological Thriller', 'psychological thriller'], ['Crime Thriller', '', [80]], ['Action Thriller', '', [28]],
    ['Mystery Thriller', '', [9648]], ['Political Thriller', 'political thriller|politics'], ['Spy Thriller', 'spy|espionage'],
    ['Conspiracy Thriller', 'conspiracy'], ['Legal Thriller', 'lawyer|courtroom'], ['Tech Thriller', 'hacker|cybercrime'],
    ['Survival Thriller', 'survival'], ['Disaster Thriller', 'disaster'], ['Revenge Thriller', 'revenge'],
    ['Heist Thriller', 'heist'], ['Serial-Killer Thriller', 'serial killer'], ['Domestic Thriller', 'domestic violence|stalker'],
    ['Erotic Thriller', 'erotic thriller'], ['Supernatural Thriller', 'supernatural'],
    ['Techno-Thriller', 'technological thriller|surveillance'], ['Investigative Thriller', 'investigation'],
    ['Suspense Thriller', 'suspense'],
  ],
  36: [
    ['Ancient History', 'ancient world|ancient rome|ancient egypt'], ['Medieval History', 'medieval|middle ages'],
    ['Royal & Dynastic History', 'royalty|monarchy'], ['Revolutionary History', 'revolution'],
    ['World War I History', 'world war i'], ['World War II History', 'world war ii'],
    ['Cold War History', 'cold war'], ['Civil Rights History', 'civil rights|racial segregation'],
    ['Political History', 'politics|political assassination'], ['Colonial History', 'colonialism'],
    ['Historical Biography', 'biography'], ['Exploration History', 'explorer|expedition'],
    ['Maritime History', 'naval warfare|sailing'], ['Religious History', 'religious history|christianity'],
    ['American Civil War History', 'american civil war'], ['Holocaust History', 'holocaust (shoah)'],
    ['Historical Court Cases', 'trial|courtroom'], ['Industrial History', 'industrial revolution'],
    ['Historical Epics', 'epic'], ['Women in History', 'women\'s rights|suffragette'],
  ],
  28: [
    ['Martial Arts Action', 'martial arts'], ['Spy Action', 'spy|espionage'], ['Heist Action', 'heist'],
    ['Military Action', 'military|commando'], ['Superhero Action', 'superhero'], ['Revenge Action', 'revenge'],
    ['Car-Chase Action', 'car chase'], ['Disaster Action', 'disaster'], ['Survival Action', 'survival'],
    ['Vigilante Action', 'vigilante'], ['Buddy-Cop Action', 'buddy cop'], ['Prison-Break Action', 'prison escape'],
    ['Samurai Action', 'samurai'], ['Swashbuckling Action', 'sword fight|swashbuckler'],
  ],
  12: [
    ['Treasure-Hunt Adventure', 'treasure hunt'], ['Jungle Adventure', 'jungle'], ['Ocean Adventure', 'ocean|sea voyage'],
    ['Pirate Adventure', 'pirate'], ['Mountain Adventure', 'mountain|mountaineering'], ['Desert Adventure', 'desert'],
    ['Expedition Adventure', 'expedition|explorer'], ['Lost-World Adventure', 'lost civilization|lost world'],
    ['Quest Adventure', 'quest'], ['Wilderness Adventure', 'wilderness'], ['Space Adventure', 'space travel'],
    ['Time-Travel Adventure', 'time travel'], ['Archaeological Adventure', 'archaeologist'], ['Road Adventure', 'road trip'],
  ],
  16: [
    ['Anime Films', 'anime'], ['Stop-Motion Animation', 'stop motion'], ['Hand-Drawn Animation', 'hand drawn animation'],
    ['Adult Animation', 'adult animation'], ['Animated Fairy Tales', 'fairy tale'], ['Animated Superheroes', 'superhero'],
    ['Animated Animal Stories', 'talking animal|anthropomorphism'], ['Animated Space Adventures', 'space travel'],
    ['Animated Musicals', 'musical'], ['Animated Coming-of-Age', 'coming of age'], ['Animated Fantasy', '', [14]],
    ['Animated Comedy', '', [35]], ['Animated Family Adventures', '', [10751, 12]], ['Animated Horror', '', [27]],
  ],
  35: [
    ['Romantic Comedy', '', [10749]], ['Dark Comedy', 'dark comedy'], ['Slapstick Comedy', 'slapstick'],
    ['Satirical Comedy', 'satire'], ['Parody Comedy', 'parody'], ['Screwball Comedy', 'screwball comedy'],
    ['Buddy Comedy', 'buddy comedy'], ['Workplace Comedy', 'workplace'], ['Teen Comedy', 'teen movie|teenager'],
    ['Family Comedy', '', [10751]], ['Musical Comedy', 'musical'], ['Mockumentary', 'mockumentary'],
    ['Road-Trip Comedy', 'road trip'], ['Horror Comedy', '', [27]],
  ],
  80: [
    ['Gangster Crime', 'gangster'], ['Organized Crime', 'organized crime|mafia'], ['Heist Crime', 'heist'],
    ['Police Procedurals', 'police investigation|police procedural'], ['Detective Crime', 'detective'],
    ['True Crime', 'true crime'], ['Serial-Killer Crime', 'serial killer'], ['Prison Crime', 'prison'],
    ['Drug-Trafficking Crime', 'drug trafficking|drug cartel'], ['Financial Crime', 'financial crime|fraud'],
    ['Cybercrime', 'cybercrime|hacker'], ['Crime Noir', 'film noir|neo-noir'],
    ['Kidnapping Crime', 'kidnapping'], ['Undercover Crime', 'undercover'],
  ],
  99: [
    ['Nature Documentary', 'nature documentary|wildlife'], ['Science Documentary', 'science|scientific research'],
    ['Historical Documentary', 'history'], ['True-Crime Documentary', 'true crime'], ['Music Documentary', 'music documentary'],
    ['Sports Documentary', 'sports documentary|sports'], ['Political Documentary', 'politics'],
    ['Social-Issue Documentary', 'social issues|social injustice'], ['Biographical Documentary', 'biography'],
    ['Environmental Documentary', 'environment|climate change'], ['Food Documentary', 'food|cooking'],
    ['Travel Documentary', 'travel'], ['Space Documentary', 'space exploration'], ['Art Documentary', 'art|artist'],
  ],
  10751: [
    ['Family Animal Adventures', 'animal|dog'], ['Family Fantasy', '', [14]], ['Family Fairy Tales', 'fairy tale'],
    ['Family Musicals', 'musical'], ['Family Sports Stories', 'sports'], ['Family Holiday Films', 'christmas'],
    ['Family Friendship Stories', 'friendship'], ['Family School Stories', 'school'], ['Family Road Trips', 'road trip'],
    ['Family Superheroes', 'superhero'], ['Family Coming-of-Age', 'coming of age'], ['Family Treasure Hunts', 'treasure hunt'],
  ],
  14: [
    ['High Fantasy', 'high fantasy|sword and sorcery'], ['Dark Fantasy', 'dark fantasy'], ['Urban Fantasy', 'urban fantasy'],
    ['Fairy-Tale Fantasy', 'fairy tale'], ['Mythological Fantasy', 'mythology'], ['Magical Realism', 'magic realism'],
    ['Sword & Sorcery', 'sword and sorcery'], ['Dragon Fantasy', 'dragon'], ['Witchcraft Fantasy', 'witch|witchcraft'],
    ['Portal Fantasy', 'portal|fantasy world'], ['Supernatural Fantasy', 'supernatural'], ['Romantic Fantasy', '', [10749]],
  ],
  27: [
    ['Supernatural Horror', 'supernatural horror|supernatural'], ['Psychological Horror', 'psychological horror'],
    ['Slasher Horror', 'slasher'], ['Body Horror', 'body horror'], ['Folk Horror', 'folk horror'],
    ['Cosmic Horror', 'cosmic horror|lovecraftian'], ['Found-Footage Horror', 'found footage'],
    ['Zombie Horror', 'zombie'], ['Vampire Horror', 'vampire'], ['Werewolf Horror', 'werewolf'],
    ['Haunted-House Horror', 'haunted house'], ['Demonic-Possession Horror', 'demonic possession'],
    ['Creature Horror', 'creature|monster'], ['Survival Horror', 'survival horror|survival'],
  ],
  10402: [
    ['Music Biographies', 'musician|biography'], ['Concert Films', 'concert film|concert'], ['Rock Music Films', 'rock music'],
    ['Jazz Music Films', 'jazz'], ['Hip-Hop Music Films', 'hip-hop|rap music'], ['Country Music Films', 'country music'],
    ['Classical Music Films', 'classical music'], ['Opera Films', 'opera'], ['Dance Musicals', 'dance|dancing'],
    ['Stage Musicals', 'broadway|stage musical'], ['Band Stories', 'band|rock band'], ['Singer Stories', 'singer'],
  ],
  9648: [
    ['Whodunit Mystery', 'whodunit'], ['Locked-Room Mystery', 'locked room mystery'], ['Detective Mystery', 'detective'],
    ['Cozy Mystery', 'cozy mystery'], ['Murder Mystery', 'murder mystery'], ['Missing-Person Mystery', 'missing person'],
    ['Supernatural Mystery', 'supernatural'], ['Psychological Mystery', 'psychological thriller'],
    ['Conspiracy Mystery', 'conspiracy'], ['Historical Mystery', 'period drama'],
    ['Amnesia Mystery', 'amnesia'], ['Investigative Mystery', 'investigation'],
  ],
  10749: [
    ['Romantic Comedy', '', [35]], ['Romantic Drama', '', [18]], ['Historical Romance', '', [36]],
    ['Teen Romance', 'teenage love|teen romance'], ['Forbidden Romance', 'forbidden love'],
    ['Second-Chance Romance', 'second chance|reunion'], ['Long-Distance Romance', 'long distance relationship'],
    ['Tragic Romance', 'tragic love'], ['Holiday Romance', 'christmas'], ['LGBTQ+ Romance', 'lgbt|gay romance|lesbian romance'],
    ['Fantasy Romance', '', [14]], ['First-Love Romance', 'first love'],
  ],
  10752: [
    ['World War I Films', 'world war i'], ['World War II Films', 'world war ii'], ['Vietnam War Films', 'vietnam war'],
    ['Korean War Films', 'korean war'], ['Civil War Films', 'civil war'], ['Naval Warfare', 'naval warfare|submarine'],
    ['Aerial Warfare', 'aerial combat|fighter pilot'], ['Prisoner-of-War Films', 'prisoner of war'],
    ['Resistance & Partisan Warfare', 'resistance|partisan'], ['War Correspondent Stories', 'war correspondent'],
    ['Anti-War Films', 'anti war'], ['Special-Forces Warfare', 'special forces|commando'],
  ],
  37: [
    ['Spaghetti Western', 'spaghetti western'], ['Revisionist Western', 'revisionist western'], ['Neo-Western', 'neo-western'],
    ['Outlaw Western', 'outlaw'], ['Revenge Western', 'revenge'], ['Frontier Western', 'frontier'],
    ['Cavalry Western', 'cavalry'], ['Bounty-Hunter Western', 'bounty hunter'], ['Ranch Western', 'ranch'],
    ['Gold-Rush Western', 'gold rush'], ['Comedy Western', '', [35]], ['Survival Western', 'survival'],
  ],
  10770: [
    ['Television Crime Drama', '', [80, 18]], ['Television Romance', '', [10749]], ['Television Mystery', '', [9648]],
    ['Television Biography', 'biography'], ['Television True Stories', 'based on true story'], ['Television Holiday Films', 'christmas'],
    ['Television Family Films', '', [10751]], ['Television Thrillers', '', [53]],
  ],
};

const tvOnly: Record<number, Theme[]> = {
  10759: [...movie[28], ...movie[12]].filter((_, i) => i % 2 === 0),
  10765: [...movie[878].slice(0, 10), ...movie[14].slice(0, 10)],
  10762: [
    ['Preschool Learning', 'preschool|educational'], ['Kids Animal Adventures', 'animal'], ['Kids Superhero Adventures', 'superhero'],
    ['Kids Fantasy Quests', 'magic|quest'], ['Kids School Stories', 'school'], ['Kids Friendship Stories', 'friendship'],
    ['Kids Science Shows', 'science'], ['Kids Musical Shows', 'musical'],
  ],
  10763: [
    ['Political News', 'politics'], ['Investigative News', 'investigative journalism'], ['Sports News', 'sports'],
    ['Business News', 'business|economics'], ['Science News', 'science'], ['Entertainment News', 'celebrity'],
    ['Current-Affairs News', 'current affairs'], ['International News', 'international relations'],
  ],
  10764: [
    ['Cooking Competitions', 'cooking competition'], ['Dating Reality', 'dating show'], ['Survival Reality', 'survival'],
    ['Home-Renovation Reality', 'home renovation'], ['Travel Reality', 'travel'], ['Music Competitions', 'singing competition'],
    ['Dance Competitions', 'dance competition'], ['Business Reality', 'business'], ['Fashion Reality', 'fashion'],
    ['Medical Reality', 'hospital'], ['Police Reality', 'police'], ['Family Reality', 'family relationships'],
  ],
  10766: [
    ['Family-Saga Soap', 'family saga'], ['Medical Soap', 'hospital'], ['Teen Soap', 'teenager'],
    ['Romantic Soap', 'love triangle'], ['Dynastic Soap', 'wealthy family'], ['Historical Soap', 'period drama'],
    ['Workplace Soap', 'workplace'], ['Revenge Soap', 'revenge'],
  ],
  10767: [
    ['Late-Night Talk', 'late-night show'], ['Celebrity Interview Shows', 'celebrity interview|interview'],
    ['Political Talk', 'politics'], ['Comedy Panel Shows', 'panel show'], ['Sports Talk', 'sports'],
    ['Food & Cooking Talk', 'cooking'], ['Music Talk', 'music'], ['Daytime Talk', 'daytime talk show|talk show'],
  ],
  10768: [
    ['Political Drama Series', 'politics'], ['Election & Campaign Series', 'election|political campaign'],
    ['Diplomatic Series', 'diplomacy'], ['Espionage Series', 'espionage'], ['Military Combat Series', 'military|combat'],
    ['World War II Series', 'world war ii'], ['Cold War Series', 'cold war'], ['Resistance Series', 'resistance'],
    ['Political Corruption Series', 'political corruption'], ['Revolution Series', 'revolution'],
  ],
};

export function subgenres(type: MediaType, parent: number): Subgenre[] {
  const themes = (type === 'tv' ? tvOnly[parent] : undefined) ?? movie[parent] ?? [];
  return themes.map(([name, keywords = '', genres = []]) => {
    // TV combines movie Action/Adventure and Sci-Fi/Fantasy in its genre taxonomy.
    const keywordGenres: Record<number, string> = { 10749: 'romance', 36: 'period drama', 10402: 'music', 27: 'horror', 53: 'thriller' };
    const genreMap: Record<number, number> = { 28: 10759, 12: 10759, 878: 10765, 14: 10765, 10752: 10768 };
    const terms = keywords.split('|').filter(Boolean);
    if (type === 'tv') for (const id of genres) if (keywordGenres[id]) terms.push(keywordGenres[id]);
    const mapped = type === 'tv' ? genres.filter(id => !keywordGenres[id]).map(id => genreMap[id] ?? id) : genres;
    return { id: name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, ''), name,
      keywords: terms, genres: [...new Set(mapped)] };
  });
}
