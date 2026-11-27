# Personal matching on mobile

The displayed percentage is a content-affinity index. It is not a measured probability of enjoyment.
Aliflix has an explicit Likes library and title metadata, but no population interaction matrix,
trained embeddings, exposure logs or held-out satisfaction labels. No algorithm can promise perfect
matches from this information. My List, details opens and playback starts are not treated as likes.

## Research and implementation

- [Netflix: recommendations](https://help.netflix.com/en/node/100639) describes explicit feedback,
  viewing behavior, similar members and metadata. We use the available explicit Likes signal;
  we do not pretend to implement Netflix's private collaborative model.
- [Google: content-based filtering](https://developers.google.com/machine-learning/recommendation/content-based/basics)
  describes feature-based similarity. Aliflix compares normalized genre, keyword, creator, cast
  and plot channels independently using cosine similarity, then combines available evidence.
- [Google: retrieval](https://developers.google.com/machine-learning/recommendation/dnn/retrieval)
  describes nearest-neighbor related-item retrieval. A similarity-weighted top-three neighborhood
  preserves multiple interests instead of averaging unrelated liked films into a single taste.
- [Google: collaborative filtering](https://developers.google.com/machine-learning/recommendation/collaborative/basics)
  requires other users' interactions. That evidence is not available to this on-device calculation.

The previous implementation estimated IDF from the user's favorites, reducing the weight of recurring
interests, and compared a single mixed vector whose magnitude depended on missing metadata.
The replacement excludes unavailable channels from each comparison, aliases common TMDB/OMDb
genre names, deduplicates likes by media type and ID, and skips cross-language plot comparisons.
Channel weights (genres .26, keywords .32, creators .18, cast .10, plot .14) are explicit engineering
priors, not learned Netflix/Google coefficients. Evidence-based shrinkage prevents a sole genre match
from reaching 100. Zero overlap yields 0, no comparable evidence yields no badge, an explicitly liked
title yields 98. Cache keys include metadata so refreshed details cannot reuse stale feature vectors.

Future probability calibration needs consented exposure/outcome data, a temporal held-out evaluation,
ranking metrics and reliability curves, then a measured calibration model. Do not relabel the current
index as a probability or claim its recommendation quality has been empirically validated.
Tests and device validation were explicitly waived for v3.1.119; mobile compilation and release
artifact integrity are checked separately.
