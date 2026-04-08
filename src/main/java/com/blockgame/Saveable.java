package com.blockgame;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Marks a component whose state can be serialised to / deserialised from a
 * binary data stream.
 *
 * <p>The {@link Game} class coordinates top-level save/load by writing a
 * versioned file that contains one tagged section per {@code Saveable}.
 * Unknown sections are skipped gracefully, allowing older builds to read save
 * files that contain newer sections.
 */
public interface Saveable {

    /**
     * Writes this component's state to {@code out}.
     *
     * @param out the destination stream (not closed by this method)
     * @throws IOException on any I/O error
     */
    void save(DataOutputStream out) throws IOException;

    /**
     * Replaces this component's state with data read from {@code in}.
     *
     * @param in the source stream positioned at the start of this component's
     *           data (not closed by this method)
     * @throws IOException on any I/O error
     */
    void load(DataInputStream in) throws IOException;
}
