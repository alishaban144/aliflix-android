# Original request - 2026-10-26

The following request is preserved verbatim as received, including spelling and encoded formatting. This is the source of truth across context compactions.

\- I asked aliflix talk for comedy movies and and in other call for scifi movies. It retrieved good options, but the speech didn't come out. I needed to say "Hi?" then the speech came out as if it was already running in the background with no sound and didn't sound until I say something. This particular issue needs to be fully fixed.
\- the voice talking keeps interrupting itself. I understand that I should be able to interrupt it with no issues, but it's interrupting itself the whole time. This is ridiculous.&#x20;
\- The way it talks still sounds rudimentary. I asked about a movie, it said only like 2 words about it. I said "And?" it then said something stupidly unrelated like it didn't understand what I mean by "and?".&#x20;
\- when you are testing the app, I noticed that you keep doing the same old generic tests; asking the same questions about the same movies and same old everything, even though I literally told you to be extensive in your testing and far-fetched and out of the box testing to detect unwanted or not fully capable features. You need to first study the app and then look up the web, research, and do crazy unthinkable testing inputs to test the feature to its full limits. I don't want simple generic tests like "Recommend latest scifi movies", every LLM nowadays can do that, it's not special. What I need is a significantly different feature that offers a whole lot more than any other comparable service. Aliflix talked is stated as a state-of-the-art feature, do don't behave other than that.
-make aliflix talk more inetlligent, fully capabale to do evrerything smarty across the app. It should be a real intelligent brain and not just a small feature that does generic thing. Your goal is to think of more than 12+ singificant new things that aliflix talk can do. Big things, like controlling the whole app, whole app or player integration? idk if that can be elegantly useful?, big substancial upgrades like this! don't just add changes to meet the number goal, they actually need to serve an important purpose and blow minds! research extensively well about each feature. I don't want mere uiux changes! but substantial features with intricate behaviour behind it.&#x20;
\- in Askflix, the "for you" items are completely unrelated are calculated with no regard to the actual matching algorithm Aliflix has. Fix this completely. No small changes. The items should be very very related and most related to what I actually like.
\- another major issues in ask aliflix describe: it displays results at first, but when scrolling down for more results, the whole results items already loaded change too. This should be fixed. Also, refine prompts are not working fine, I asked for more recent but it still displayed results from 20 years ago. Also, they don't look comprehensive tbh. I understand the results are genuine but they are very generic and already well-known. Again, I don't want another feature that any LLM can do, that literally defies the whole purpose of the app.
\- when using describe or similar, the loading animation is appearing twice on the screen. replace the loading animation with this one without affecting its fluency "[https://uiverse.io/mobinkakei/proud-ladybug-46](https://uiverse.io/mobinkakei/proud-ladybug-46)", making it big, kinda centered, changing the uiux of the screen.\
Also, don't show "for you" items once describe search is initiated. Look for best uiux practices.
-keep reviewing what you do so you don't end up with something that clearly defies logic.&#x20;
\- keep looking for small bugs or issues across the app and fix them.
\- Don't use descriptive text in any implementation.
Important note: the current context windoe keeps compacting and losing context, you should keep this prompt saved and save other info so you don't lose conext and get back to it after compacting. This whole prompt is important so don't miss anything in it strictly. You should never miss any word of this prompt strictly!! \
Finish and release to github. my phone is connected.

## User steering - 2026-10-26

dont use personal-match explanations, i already said no descriptive. also, the features you listed are all minimal and doesnt offer anything out of the box new at all . you need to reasearch what state of the art ai agents are doing, something similar to meta muse or openai dots

## Scope clarification - 2026-10-26

Question: For the larger agent upgrade, I’m focusing on Aliflix: persistent goals, background research and monitoring, and multi-step work across discovery, your library, downloads, and playback. Do you also want it to act in external apps?

Answer: Keep it within Aliflix

## AI-path decision - 2026-10-26

Question: May I add a Gemini verification call that sends a task’s request, candidate film metadata, and retrieved public evidence to check difficult constraints and movie-identification clues?

Answer: Keep the current AI paths

No additional Gemini verification endpoint/call is authorized. Continue with existing AI paths and catalogue/research checks.
