# Phone subtitle initialization in 3.1.70

Caption positioning now runs after the entire player layout pass, using measured view bounds. Initial layout, video-size changes, rotation and manual position changes share this path. Padding is bounded so saved offsets cannot remove the caption canvas from the available viewport.

Late subtitle downloads previously wrote a VTT file and enabled text selection without adding a Media3 text source. Updating the media item alone also retained the old progressive source in the regression. The service now prepares the updated subtitle configuration at the existing position and preserves play/pause state. Each replacement uses a distinct cached VTT URI, cleaned up when the service ends. Loading a new title clears the previous title's in-memory cues.

`NativeSubtitleRenderingTest` uses the real service, player and HTTP video fixture. It requires decoded subtitle cues and visible caption pixels with Vertical Shift at zero, then covers rotation, disabling/replacing captions while paused, position preservation and Activity recreation. The late-caption assertion failed before the source-rebuild correction and passed afterward on Android API 37. The cloud phone suite also includes this regression.

The fix does not guarantee availability of subtitles from third-party providers. Adding or replacing a subtitle source can briefly rebuffer while preserving position. The TV app remains on 3.1.69; the release carries its existing public APK and update manifest unchanged.
