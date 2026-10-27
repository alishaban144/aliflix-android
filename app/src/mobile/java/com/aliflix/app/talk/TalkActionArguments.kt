package com.aliflix.app.talk

import com.aliflix.app.model.MediaType
import org.json.JSONObject

internal fun JSONObject.talkOptionalInteger(name:String,min:Int=0):Int? {
    if(!has(name)||isNull(name))return null
    val value=opt(name)
    require(value is Number){"$name must be an integer"}
    val number=value.toDouble()
    require(number.isFinite()&&number>=min&&number<=Int.MAX_VALUE&&number%1.0==0.0){"Invalid $name"}
    return number.toInt()
}

internal data class TalkEpisodeArguments(val season:Int?,val episode:Int?) {
    fun requireKind(type:MediaType){require(type==MediaType.TV||season==null&&episode==null){"Episode numbers require a series"}}
    fun matches(seasonNumber:Int?,episodeNumber:Int?)=(season==null||season==seasonNumber)&&(episode==null||episode==episodeNumber)
    companion object {fun from(args:JSONObject)=TalkEpisodeArguments(args.talkOptionalInteger("season"),args.talkOptionalInteger("episode",1))}
}

internal fun JSONObject.talkSettingNumber(range:ClosedFloatingPointRange<Float>):Float {
    require(opt("numberValue") is Number){"A number is required"}
    val value=getDouble("numberValue").toFloat()
    require(value.isFinite()&&value in range){"Setting is outside its supported range"}
    return value
}
