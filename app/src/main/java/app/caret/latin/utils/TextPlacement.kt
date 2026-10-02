package app.caret.latin.utils

class TextPlacement(@JvmField var text: String, @JvmField val startPosition: Int) {

    fun endPosition(): Int {
        return startPosition + text.length
    }
}
