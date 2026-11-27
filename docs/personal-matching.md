# Personal matching

Mobile uses [MobileTasteModel](../app/src/main/java/com/aliflix/app/recommendation/MobileTasteModel.kt) for title badges and [TastePicks](../app/src/main/java/com/aliflix/app/recommendation/TastePicks.kt) for Home ranking. Cards show labels such as **Good match** and **Liked**. The model uses explicit Likes and a bounded, lower-weight Recently Played history. It compares story concepts, keywords, creators, cast, and genre. Genre overlap alone cannot earn a match.

`AliflixViewModel` owns the Home candidate refresh and updates taste history after account scope is aligned. The model merges richer cached metadata into sparse catalogue cards so Details and Home can show consistent labels. Its scores are intended for ordering within Aliflix.

See the [matching tests](../app/src/test/java/com/aliflix/app/recommendation/MobileTasteModelTest.kt) for expected behavior. The [3.1.119 design](archive/personal-matching-v3.1.119.md) records an earlier implementation.
