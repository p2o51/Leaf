package dev.chenwuyi.leaf.data.voice

/**
 * Joins a segment the recognizer already finished to the words after it. Chinese and Japanese
 * run on without spaces; Latin words need one, or a long hold reads "endNext".
 */
fun joinTranscript(head: String, tail: String): String {
    val first = head.trimEnd()
    val second = tail.trimStart()
    if (first.isEmpty()) return second
    if (second.isEmpty()) return first
    val spaced = !isWide(first.last()) && !isWide(second.first())
    return if (spaced) "$first $second" else first + second
}

/** Han, kana and full-width punctuation take no space between segments; Hangul does, like Latin. */
private fun isWide(c: Char): Boolean =
    c >= '⺀' && c !in '가'..'힯' && c !in '㄰'..'㆏'

/** Every line hold-to-talk can show, in one place so the wording can be reviewed at once. */
object VoiceCopy {
    fun permission(route: VoiceRoute): String = when (route) {
        VoiceRoute.System -> "只在按住时收音，转写可能经过系统的联网服务"
        VoiceRoute.Cloud -> "只在按住时收音，松开后发给 ElevenLabs 转写"
        else -> "只在按住时收音，转写在这台设备上完成"
    }

    const val Ready = "按住说，松开就转写"
    const val Holding = "在听，松开结束"
    const val Finishing = "转写中"
    const val Transcribed = "转写好了，改一改再记下"
    const val HoldToTalk = "要按住说话，松开结束"
    const val Denied = "没有麦克风权限，先用打字"
    const val Unavailable = "这台设备不能语音转写，先用打字，或者在设置里填上 ElevenLabs 密钥"
    const val NothingHeard = "没听清，按住再说一次"
    const val Network = "连不上转写服务，稍后再试，或者先用打字"
    const val Busy = "转写服务正忙，稍后再试"
    const val Unauthorized = "ElevenLabs 不认这个密钥，到设置里换一个，或者先用打字"
    const val Failed = "这次没转写成，按住再说一次，或者先用打字"

    fun stop(kind: VoiceException.Kind): String = when (kind) {
        VoiceException.Kind.NoPermission -> Denied
        VoiceException.Kind.Unavailable -> Unavailable
        VoiceException.Kind.Network -> Network
        VoiceException.Kind.Busy -> Busy
        VoiceException.Kind.Unauthorized -> Unauthorized
        VoiceException.Kind.Failed -> Failed
    }

    /** A take cut short after some words: they are kept, and this says why it stopped. */
    fun partial(kind: VoiceException.Kind): String = when (kind) {
        VoiceException.Kind.Network -> "连不上转写服务，只转写了这些"
        VoiceException.Kind.Busy -> "转写服务正忙，只转写了这些"
        else -> "转写中断了，只转写了这些"
    }
}
