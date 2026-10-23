package com.aliflix.app.talk

import org.json.JSONObject

internal data class TalkPresentation(val cards:List<TalkTitleCard>,val speechText:String)

/** The model chooses from catalogue identities, never a parallel list of
 * spoken names. Cards and their short narration are derived in one commit. */
internal fun talkPresentation(pool:List<TalkTitleCard>,args:JSONObject,count:Int=3):TalkPresentation {
    val picks=args.getJSONArray("picks")
    require(picks.length()==minOf(count.coerceIn(1,3),pool.size)&&picks.length()>0){"Choose requestedCount returned keys, or all if fewer."}
    val rows=(0 until picks.length()).map{picks.getJSONObject(it)}
    val cards=rows.map{row->requireNotNull(pool.firstOrNull{it.key==row.getString("key")}){"Use only returned catalogue keys."}}
    require(cards.distinctBy{it.key}.size==cards.size){"Choose distinct titles."}
    val text=cards.mapIndexed{index,card->
        val reason=rows[index].getString("reason").trim().replace(Regex("\\s+")," ").trimEnd('.','!','?')
        require(reason.isNotBlank()&&reason.length<=100&&reason.split(' ').size<=12){"Use a brief 4-7 word reason."}
        val ambiguous=pool.count{it.title.equals(card.title,true)}>1
        card.title+(if(ambiguous&&card.year.isNotBlank())" (${card.year})" else "")+": "+reason+"."
    }.joinToString(" ")
    return TalkPresentation(cards,text)
}
