package com.aliflix.app.data

/** Part numbers are identity, not punctuation. Keep I/II and One/Two distinct. */
internal fun normalizedEpisodeRatingTitle(value:String):String {
    val numbers=mapOf("one" to "1","two" to "2","three" to "3","four" to "4","five" to "5","six" to "6","seven" to "7","eight" to "8","nine" to "9","ten" to "10","i" to "1","ii" to "2","iii" to "3","iv" to "4","v" to "5")
    val parts=Regex("(?i)\\b(?:part|pt|teil)\\.?\\s*([0-9]{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|iii|ii|iv|i|v)\\b")
        .replace(value) { " "+(numbers[it.groupValues[1].lowercase()]?:it.groupValues[1])+" " }
    return java.text.Normalizer.normalize(parts,java.text.Normalizer.Form.NFKD).replace(Regex("\\p{M}+"),"")
        .lowercase().replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
}
internal fun episodeRatingTitleBase(value:String):String {
    val isPart=Regex("(?i)(?:\\(\\d{1,2}\\)|\\b(?:part|pt|teil)\\.?\\s*(?:[0-9]{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|iii|ii|iv|i|v))\\s*$").containsMatchIn(value)
    val normalized=normalizedEpisodeRatingTitle(value)
    return if(isPart)normalized.replace(Regex(" \\d{1,2}$"),"") else normalized
}
