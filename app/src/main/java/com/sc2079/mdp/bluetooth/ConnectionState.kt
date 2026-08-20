package com.sc2079.mdp.bluetooth

/** Lifecycle of the RFCOMM link, surfaced on the connection bar of the GUI. */
enum class ConnectionState {
    /** No link, and the controller is not trying to build one. */
    DISCONNECTED,

    /** First outgoing attempt to a device the user just picked. */
    CONNECTING,

    /**
     * The link dropped but the controller is still retrying, and is also
     * listening for the remote side to connect back (checklist C.8).
     */
    RECONNECTING,

    /** A socket is open and the reader thread is running. */
    CONNECTED,
}
