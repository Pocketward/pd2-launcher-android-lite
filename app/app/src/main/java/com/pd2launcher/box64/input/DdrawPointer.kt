package com.pd2launcher.box64.input

/**
 * Touch for the game's DirectDraw renderer: cnc-ddraw (PD2's `ddraw.dll`), which the official
 * launcher's Options pick (DDRAW; the game then runs with `-ddraw`). Lite's `ddraw.ini` keeps the
 * picture's shape (`maintas`, [com.pd2launcher.box64.DdrawIni]): cnc-ddraw draws PD2's picture
 * (the menus 800 x 600, the world 1068 x 600) as large as fits its window, the whole X screen,
 * centred, with black bars on the other sides. Its mouse hook scales the pointer's place in the
 * window to the game's pixels but does not take the bars off (measured: a tap lands the bar's
 * height lower, in the menus and in the world alike). So the game view moves each touch by the
 * picture's [offset] before it reaches X; D2GL (Glide) needs no such move.
 */
object DdrawPointer {
    private const val MENU_WIDTH = 800
    private const val WORLD_WIDTH = 1068
    private const val GAME_HEIGHT = 600

    /** The game runs with DirectDraw: [arguments], its command line's words, name `-ddraw`. */
    fun directDraw(arguments: List<String>): Boolean = arguments.any { it.equals("-ddraw", ignoreCase = true) }

    /**
     * Where cnc-ddraw puts the [picture] on a [width] x [height] window: its left and top edge, in
     * X screen pixels, by cnc-ddraw's own rule (the full width, else the full height; whole pixels,
     * cut down). 0, 0 when the picture is not known.
     */
    fun offset(width: Int, height: Int, picture: GamePicture): Pair<Int, Int> {
        val gameWidth = when (picture) {
            GamePicture.MENU -> MENU_WIDTH
            GamePicture.WORLD -> WORLD_WIDTH
            GamePicture.UNKNOWN -> return 0 to 0
        }
        var w = width
        var h = (GAME_HEIGHT.toFloat() / gameWidth * w).toInt()
        if (h > height) {
            w = (w.toFloat() / h * height).toInt()
            h = height
        }
        return (width / 2 - w / 2) to (height / 2 - h / 2)
    }
}
