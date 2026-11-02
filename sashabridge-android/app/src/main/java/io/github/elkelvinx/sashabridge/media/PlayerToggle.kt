package io.github.elkelvinx.sashabridge.media

/**
 * A button of the player that SashaBridge presses for the user, and whose state it can read
 * back to tell whether the player obeyed: Spotify ignores taking a song out of "Tus me gusta"
 * from outside its app, for one.
 *
 * Pure and thread-safe.
 */
enum class PlayerToggle {

    /** "Me gusta": Spotify's heart, YouTube Music's thumb up. */
    LIKE {
        override fun stateOf(snapshot: MediaSnapshot): Any? = snapshot.liked
    },

    /** Shuffle. */
    SHUFFLE {
        override fun stateOf(snapshot: MediaSnapshot): Any? = snapshot.shuffle
    },

    /** Repeat. */
    REPEAT {
        override fun stateOf(snapshot: MediaSnapshot): Any? = snapshot.repeat
    },
    ;

    /** The state of this button in [snapshot], or `null` if the player does not reveal it. */
    abstract fun stateOf(snapshot: MediaSnapshot): Any?

    /**
     * Whether the player ignored a press: [after], [CHECK_MS] after pressing on [before], is
     * still the same track of the same player with the same state. A player that hides the
     * state, or a track that changed meanwhile, tells nothing, so neither counts as ignored.
     */
    fun ignored(before: MediaSnapshot, after: MediaSnapshot?): Boolean {
        val state = stateOf(before) ?: return false
        return after != null && after.source == before.source && after.trackId == before.trackId && stateOf(after) == state
    }

    companion object {

        /** Time a player has to show the new state; Spotify and YouTube Music take well under one second. */
        const val CHECK_MS = 2_000L
    }
}

/** Where a command came from, which decides who hears about a command the player refused. */
enum class CommandOrigin {

    /** The PC: its own on-screen notice tells the user. */
    PC,

    /** The app's own screen, which tells the user itself. */
    SCREEN,
}

/**
 * What a player could not do, to tell the user instead of doing nothing silently.
 *
 * @property title First line, such as "No se quitó de Me gusta".
 * @property detail Second line: why, and where to do it instead.
 * @property origin Where the refused command came from.
 */
data class PlayerNotice(val title: String, val detail: String, val origin: CommandOrigin)
