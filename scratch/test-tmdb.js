async function run() {
  try {
    const res = await fetch('https://www.themoviedb.org/discover/movie?sort_by=popularity.desc', {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
        'Accept': 'text/html,application/xhtml+xml',
        'Accept-Language': 'en-US,en;q=0.9'
      }
    });
    const text = await res.text();
    const movieLinks = text.match(/href="\/movie\/[0-9]+[^"]*"/g);
    if (movieLinks && movieLinks.length > 0) {
      const firstLinkIndex = text.indexOf(movieLinks[0]);
      console.log(text.substring(Math.max(0, firstLinkIndex), firstLinkIndex + 1500));
    }
  } catch (err) {
    console.error(err);
  }
}
run();
