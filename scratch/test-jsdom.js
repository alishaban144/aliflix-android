const jsdom = require("jsdom");
const { JSDOM } = jsdom;

async function run() {
  try {
    const res = await fetch('https://www.themoviedb.org/discover/movie?sort_by=popularity.desc', {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
        'Accept': 'text/html,application/xhtml+xml',
        'Accept-Language': 'en-US,en;q=0.9'
      }
    });
    const html = await res.text();
    const dom = new JSDOM(html);
    const document = dom.window.document;
    
    const oldCards = document.querySelectorAll("div.card.style_1");
    console.log("Old cards:", oldCards.length);
    
    // New cards structure?
    // Let's try finding the cards by looking for the image links
    const cards = document.querySelectorAll("div.flex.flex-wrap.content-start.items-start");
    console.log("div.flex... cards:", cards.length);

    // Let's try finding by options div which has data-id
    const optionDivs = document.querySelectorAll("div.options[data-id]");
    console.log("div.options[data-id]:", optionDivs.length);

    if (optionDivs.length > 0) {
      const card = optionDivs[0].parentElement.parentElement;
      const titleLink = card.querySelector("h2") ? card.querySelector("h2").parentElement : null;
      const title = card.querySelector("h2") ? card.querySelector("h2").textContent : "";
      const year = card.querySelector(".release_date") ? card.querySelector(".release_date").textContent : "";
      const id = optionDivs[0].getAttribute("data-id");
      const img = card.querySelector("img.poster");
      const posterPath = img ? img.getAttribute("src") : "";
      console.log("Parsed First Card:", { id, title, year, posterPath });
    }
  } catch (err) {
    console.error(err);
  }
}
run();
